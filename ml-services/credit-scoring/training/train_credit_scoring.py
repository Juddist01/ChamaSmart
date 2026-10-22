"""
ChamaSmart - credit scoring training (Google Colab or local).

What it does, in plain words:
  1. Loads the CSV and checks it.
  2. Splits it into TRAIN / VALIDATION / TEST (test is touched only once, at the end).
  3. Trains a BASELINE (logistic regression), a RANDOM FOREST, and the MAIN model (XGBoost),
     each tuned with GridSearchCV on the training data only.
  4. Picks a decision threshold per model on the VALIDATION set.
  5. Evaluates all three models once on the TEST set.
  6. Saves everything (model, metrics, plots, versions, seed, git commit) to a run folder.

Colab usage:
    from google.colab import drive; drive.mount('/content/drive')
    !pip -q install shap
    !git clone https://github.com/Juddist01/ChamaSmart /content/ChamaSmart
    !python /content/ChamaSmart/ml-services/credit-scoring/training/train_credit_scoring.py

Column names can be set with environment variables (the Colab notebook does this for you):
    CHAMA_LABEL, CHAMA_POSITIVE_MEANS, CHAMA_DATA, CHAMA_ROOT, CHAMA_REPO, CHAMA_RUN_TAG
"""
import json
import os
import platform
import subprocess
from datetime import datetime
from pathlib import Path

import joblib
import matplotlib

matplotlib.use("Agg")
import matplotlib.pyplot as plt
import numpy as np
import pandas as pd
import sklearn
import xgboost
from sklearn.compose import ColumnTransformer
from sklearn.ensemble import RandomForestClassifier
from sklearn.linear_model import LogisticRegression
from sklearn.metrics import (ConfusionMatrixDisplay, accuracy_score, confusion_matrix,
                             f1_score, precision_score, recall_score, roc_auc_score, roc_curve)
from sklearn.model_selection import GridSearchCV, StratifiedKFold, train_test_split
from sklearn.pipeline import Pipeline
from sklearn.preprocessing import OneHotEncoder, StandardScaler
from xgboost import XGBClassifier

# ======================= CONFIG (edit these) =======================
ROOT = Path(os.environ.get("CHAMA_ROOT", "/content/drive/MyDrive/ChamaSmart"))
DATA_PATH = Path(os.environ.get("CHAMA_DATA", ROOT / "datasets" / "chama_credit_scoring_dataset.csv"))
REPO_DIR = os.environ.get("CHAMA_REPO", "/content/ChamaSmart")   # only used to record the git commit
LABEL_COL = os.environ.get("CHAMA_LABEL", "label")   # name of the target column in your CSV
POSITIVE_MEANS = os.environ.get("CHAMA_POSITIVE_MEANS", "default")  # "default": 1 = defaulted (risky); "repayment": 1 = repaid
SEED = 42
TEST_SIZE = 0.15
VAL_SIZE = 0.15
# Fixed policy cut-offs for the risk band shown to users. Agree these with your supervisor.
RISK_BANDS = {"low_max": 0.35, "high_min": 0.65}
# ===================================================================


def mount_drive_if_colab():
    if str(ROOT).startswith("/content/drive"):
        try:
            from google.colab import drive
            drive.mount("/content/drive")
        except ImportError:
            pass


def git_commit():
    try:
        return subprocess.check_output(["git", "-C", REPO_DIR, "rev-parse", "--short", "HEAD"],
                                       stderr=subprocess.DEVNULL, text=True).strip()
    except Exception:
        return "unknown"


def make_preprocessor(num_cols, cat_cols):
    # Fitted inside each pipeline, so it only ever learns from training data.
    return ColumnTransformer(
        [("num", StandardScaler(), num_cols),
         ("cat", OneHotEncoder(handle_unknown="ignore", sparse_output=False), cat_cols)],
        sparse_threshold=0,
    )


def best_threshold(y_true, scores):
    grid = np.linspace(0.05, 0.95, 91)
    f1s = [f1_score(y_true, (scores >= t).astype(int), zero_division=0) for t in grid]
    return float(grid[int(np.argmax(f1s))])


def evaluate(y_true, scores, threshold):
    pred = (scores >= threshold).astype(int)
    return {
        "auc_roc": round(float(roc_auc_score(y_true, scores)), 4),
        "f1": round(float(f1_score(y_true, pred, zero_division=0)), 4),
        "precision": round(float(precision_score(y_true, pred, zero_division=0)), 4),
        "recall": round(float(recall_score(y_true, pred, zero_division=0)), 4),
        "accuracy": round(float(accuracy_score(y_true, pred)), 4),
        "threshold": round(threshold, 3),
        "confusion_matrix [[TN, FP], [FN, TP]]": confusion_matrix(y_true, pred).tolist(),
        "n": int(len(y_true)),
    }


def main():
    mount_drive_if_colab()
    run_id = datetime.now().strftime("%Y%m%d_%H%M%S") + os.environ.get("CHAMA_RUN_TAG", "")
    run_dir = ROOT / "runs" / "credit_scoring" / run_id
    art_dir = run_dir / "service_artifacts"
    art_dir.mkdir(parents=True, exist_ok=True)
    print(f"Run folder: {run_dir}")

    # ---------- 1. load and check ----------
    df = pd.read_csv(DATA_PATH)
    print(f"Loaded {len(df)} rows, columns: {list(df.columns)}")
    if LABEL_COL not in df.columns:
        raise SystemExit(f"Column '{LABEL_COL}' not found. Set LABEL_COL in the CONFIG block.")
    if set(df[LABEL_COL].unique()) - {0, 1}:
        raise SystemExit("Label must contain only 0 and 1.")
    print("Missing values per column:\n", df.isna().sum().to_string())
    print(f"Duplicate rows: {int(df.duplicated().sum())}")
    print(f"Label balance (share of 1s): {df[LABEL_COL].mean():.3f}")
    df = df.dropna().reset_index(drop=True)

    X = df.drop(columns=[LABEL_COL])
    y = df[LABEL_COL].astype(int)
    num_cols = [c for c in X.columns if pd.api.types.is_numeric_dtype(X[c])]
    cat_cols = [c for c in X.columns if c not in num_cols]
    for c in cat_cols:
        X[c] = X[c].astype(str)

    # ---------- 2. split ----------
    X_tv, X_test, y_tv, y_test = train_test_split(
        X, y, test_size=TEST_SIZE, stratify=y, random_state=SEED)
    X_train, X_val, y_train, y_val = train_test_split(
        X_tv, y_tv, test_size=VAL_SIZE / (1 - TEST_SIZE), stratify=y_tv, random_state=SEED)
    print(f"Split sizes -> train {len(X_train)}, validation {len(X_val)}, test {len(X_test)}")

    # ---------- 3a. baseline: logistic regression ----------
    baseline = Pipeline([
        ("pre", make_preprocessor(num_cols, cat_cols)),
        ("clf", LogisticRegression(max_iter=1000, random_state=SEED)),
    ]).fit(X_train, y_train)

    # ---------- 3b. main model: XGBoost + grid search (training data only) ----------
    xgb_pipe = Pipeline([
        ("pre", make_preprocessor(num_cols, cat_cols)),
        ("clf", XGBClassifier(eval_metric="logloss", random_state=SEED, n_jobs=1)),
    ])
    grid = {
        "clf__n_estimators": [100, 300],
        "clf__max_depth": [3, 5],
        "clf__learning_rate": [0.05, 0.1],
        "clf__subsample": [0.8, 1.0],
    }
    search = GridSearchCV(
        xgb_pipe, grid, scoring="roc_auc", refit=True, n_jobs=-1,
        cv=StratifiedKFold(5, shuffle=True, random_state=SEED))
    search.fit(X_train, y_train)
    model = search.best_estimator_
    print("Best XGBoost params:", search.best_params_)
    pd.DataFrame(search.cv_results_).to_csv(run_dir / "cv_results.csv", index=False)

    # ---------- 3c. second comparison model: random forest ----------
    rf_pipe = Pipeline([
        ("pre", make_preprocessor(num_cols, cat_cols)),
        ("clf", RandomForestClassifier(random_state=SEED, n_jobs=1)),
    ])
    rf_grid = {
        "clf__n_estimators": [200, 400],
        "clf__max_depth": [None, 8],
        "clf__min_samples_leaf": [1, 3],
    }
    rf_search = GridSearchCV(
        rf_pipe, rf_grid, scoring="roc_auc", refit=True, n_jobs=-1,
        cv=StratifiedKFold(5, shuffle=True, random_state=SEED))
    rf_search.fit(X_train, y_train)
    rf_model = rf_search.best_estimator_
    print("Best random forest params:", rf_search.best_params_)

    # ---------- 4. validation: compare and choose thresholds ----------
    val_scores = {"baseline_logreg": baseline.predict_proba(X_val)[:, 1],
                  "random_forest": rf_model.predict_proba(X_val)[:, 1],
                  "xgboost": model.predict_proba(X_val)[:, 1]}
    thresholds = {k: best_threshold(y_val, v) for k, v in val_scores.items()}
    validation = {k: evaluate(y_val, v, thresholds[k]) for k, v in val_scores.items()}
    print("Validation:", json.dumps(validation, indent=2))

    # ---------- 5. FINAL test evaluation (done once; do not tune after seeing this) ----------
    test_scores = {"baseline_logreg": baseline.predict_proba(X_test)[:, 1],
                   "random_forest": rf_model.predict_proba(X_test)[:, 1],
                   "xgboost": model.predict_proba(X_test)[:, 1]}
    test = {k: evaluate(y_test, v, thresholds[k]) for k, v in test_scores.items()}
    print("TEST:", json.dumps(test, indent=2))

    # ---------- 6. plots ----------
    plt.figure(figsize=(5, 5))
    for name, s in test_scores.items():
        fpr, tpr, _ = roc_curve(y_test, s)
        plt.plot(fpr, tpr, label=f"{name} (AUC {test[name]['auc_roc']})")
    plt.plot([0, 1], [0, 1], "k--", linewidth=0.8)
    plt.xlabel("False positive rate")
    plt.ylabel("True positive rate")
    plt.title("ROC curve (test set)")
    plt.legend()
    plt.tight_layout()
    plt.savefig(run_dir / "roc_curve.png", dpi=150)
    plt.close()

    ConfusionMatrixDisplay.from_predictions(
        y_test, (test_scores["xgboost"] >= thresholds["xgboost"]).astype(int))
    plt.title("XGBoost confusion matrix (test)")
    plt.tight_layout()
    plt.savefig(run_dir / "confusion_matrix_xgboost.png", dpi=150)
    plt.close()

    try:
        import shap
        pre, clf = model.named_steps["pre"], model.named_steps["clf"]
        names = [n.split("__", 1)[-1] for n in pre.get_feature_names_out()]
        Xt = pre.transform(X_test)
        shap_values = shap.TreeExplainer(clf).shap_values(Xt)
        shap.summary_plot(shap_values, Xt, feature_names=names, show=False)
        plt.tight_layout()
        plt.savefig(run_dir / "shap_summary.png", dpi=150)
        plt.close()
    except Exception as exc:  # SHAP is for explanation only; do not fail the run
        print("SHAP plot skipped:", exc)

    # ---------- 7. save artefacts ----------
    features = []
    for c in num_cols:
        features.append({"name": c, "type": "numeric",
                         "min": float(X_train[c].min()), "max": float(X_train[c].max())})
    for c in cat_cols:
        features.append({"name": c, "type": "categorical",
                         "allowed": sorted(X_train[c].unique().tolist())})
    versions = {"python": platform.python_version(), "scikit-learn": sklearn.__version__,
                "xgboost": xgboost.__version__, "pandas": pd.__version__, "numpy": np.__version__}
    metadata = {
        "model_version": f"credit-xgb-{run_id}",
        "run_id": run_id,
        "git_commit": git_commit(),
        "seed": SEED,
        "dataset": str(DATA_PATH.name),
        "n_rows": int(len(df)),
        "label_column": LABEL_COL,
        "positive_class_meaning": POSITIVE_MEANS,
        "features": features,
        "risk_bands": RISK_BANDS,
        "decision_threshold": thresholds["xgboost"],
        "best_params": search.best_params_,
        "random_forest_best_params": rf_search.best_params_,
        "library_versions": versions,
        "validation": validation,
        "test": test,
        "note": "risk_score is a model score, NOT a calibrated probability.",
    }
    joblib.dump(model, art_dir / "model.joblib")
    (art_dir / "metadata.json").write_text(json.dumps(metadata, indent=2))
    joblib.dump(baseline, run_dir / "baseline_logreg.joblib")
    joblib.dump(rf_model, run_dir / "random_forest.joblib")
    (run_dir / "metrics.json").write_text(json.dumps(
        {"validation": validation, "test": test, "versions": versions}, indent=2))

    print(f"RUN_DIR={run_dir}")
    print("\nDONE.")
    print(f"Copy the two files in {art_dir} into ml-services/credit-scoring/model/ in your repo.")
    print("Then pin the library versions above in ml-services/credit-scoring/requirements.txt.")


if __name__ == "__main__":
    main()
