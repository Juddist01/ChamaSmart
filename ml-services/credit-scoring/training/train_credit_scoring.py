"""
ChamaSmart - Credit Scoring Model Training

Production model: Logistic Regression.
Comparison models: Random Forest and XGBoost.

Workflow:
1. Load and validate the dataset.
2. Split into training, validation and test sets.
3. Train Logistic Regression, Random Forest and XGBoost.
4. Tune Random Forest and XGBoost using training data only.
5. Select a decision threshold using validation data.
6. Evaluate all models on the held-out test set.
7. Save Logistic Regression as the production model.
8. Save comparison models, metrics, plots and metadata.
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
from sklearn.metrics import (
    ConfusionMatrixDisplay,
    accuracy_score,
    confusion_matrix,
    f1_score,
    precision_score,
    recall_score,
    roc_auc_score,
    roc_curve,
)
from sklearn.model_selection import (
    GridSearchCV,
    StratifiedKFold,
    train_test_split,
)
from sklearn.pipeline import Pipeline
from sklearn.preprocessing import OneHotEncoder, StandardScaler
from xgboost import XGBClassifier


# ============================================================
# CONFIGURATION
# ============================================================

REPO_DIR = Path(
    os.environ.get(
        "CHAMA_REPO",
        Path(__file__).resolve().parents[3]
    )
).resolve()

ROOT = Path(
    os.environ.get("CHAMA_ROOT", str(REPO_DIR))
).resolve()

DATA_PATH = Path(
    os.environ.get(
        "CHAMA_DATA",
        str(ROOT / "datasets" / "chama_credit_scoring_dataset.csv")
    )
)

LABEL_COL = os.environ.get("CHAMA_LABEL", "default")

# 1 means the member defaulted; 0 means the member did not default.
POSITIVE_MEANS = os.environ.get(
    "CHAMA_POSITIVE_MEANS", "default"
)

SEED = 42
TEST_SIZE = 0.15
VAL_SIZE = 0.15

RISK_BANDS = {
    "low_max": 0.35,
    "high_min": 0.65,
}


# ============================================================
# HELPER FUNCTIONS
# ============================================================

def git_commit():
    """Return the current Git commit, if available."""
    try:
        return subprocess.check_output(
            [
                "git",
                "-C",
                str(REPO_DIR),
                "rev-parse",
                "--short",
                "HEAD",
            ],
            stderr=subprocess.DEVNULL,
            text=True,
        ).strip()
    except Exception:
        return "unknown"


def make_preprocessor(num_cols, cat_cols):
    """Build preprocessing steps fitted only on training data."""
    transformers = []

    if num_cols:
        transformers.append(
            ("num", StandardScaler(), num_cols)
        )

    if cat_cols:
        transformers.append(
            (
                "cat",
                OneHotEncoder(
                    handle_unknown="ignore",
                    sparse_output=False,
                ),
                cat_cols,
            )
        )

    return ColumnTransformer(
        transformers,
        sparse_threshold=0,
    )


def best_threshold(y_true, scores):
    """Choose the threshold with the best validation F1 score."""
    grid = np.linspace(0.05, 0.95, 91)

    f1_scores = [
        f1_score(
            y_true,
            (scores >= threshold).astype(int),
            zero_division=0,
        )
        for threshold in grid
    ]

    return float(grid[int(np.argmax(f1_scores))])


def evaluate(y_true, scores, threshold):
    """Calculate classification metrics at a selected threshold."""
    predictions = (scores >= threshold).astype(int)

    return {
        "auc_roc": round(
            float(roc_auc_score(y_true, scores)), 4
        ),
        "f1": round(
            float(f1_score(
                y_true, predictions, zero_division=0
            )), 4
        ),
        "precision": round(
            float(precision_score(
                y_true, predictions, zero_division=0
            )), 4
        ),
        "recall": round(
            float(recall_score(
                y_true, predictions, zero_division=0
            )), 4
        ),
        "accuracy": round(
            float(accuracy_score(y_true, predictions)), 4
        ),
        "threshold": round(float(threshold), 3),
        "confusion_matrix [[TN, FP], [FN, TP]]":
            confusion_matrix(y_true, predictions).tolist(),
        "n": int(len(y_true)),
    }


# ============================================================
# MAIN TRAINING WORKFLOW
# ============================================================

def main():

    run_id = (
        datetime.now().strftime("%Y%m%d_%H%M%S")
        + os.environ.get("CHAMA_RUN_TAG", "")
    )

    run_dir = ROOT / "runs" / "credit_scoring" / run_id
    art_dir = run_dir / "service_artifacts"

    art_dir.mkdir(parents=True, exist_ok=True)

    print("=" * 65)
    print("CHAMASMART CREDIT SCORING TRAINING")
    print("=" * 65)
    print(f"Repository: {REPO_DIR}")
    print(f"Dataset: {DATA_PATH}")
    print(f"Run folder: {run_dir}")

    # --------------------------------------------------------
    # 1. LOAD AND VALIDATE DATA
    # --------------------------------------------------------

    if not DATA_PATH.exists():
        raise SystemExit(
            f"Dataset not found: {DATA_PATH}\n"
            "Set CHAMA_DATA or CHAMA_ROOT to the correct location."
        )

    df = pd.read_csv(DATA_PATH)

    print(f"\nLoaded {len(df)} rows.")
    print("Columns:", list(df.columns))

    if LABEL_COL not in df.columns:
        raise SystemExit(
            f"Target column '{LABEL_COL}' was not found."
        )

    if POSITIVE_MEANS not in ("default", "repayment"):
        raise SystemExit(
            "CHAMA_POSITIVE_MEANS must be 'default' or 'repayment'."
        )

    if set(df[LABEL_COL].dropna().unique()) != {0, 1}:
        raise SystemExit(
            "The target column must contain both 0 and 1 only."
        )

    print("\nMissing values before cleaning:")
    print(df.isna().sum().to_string())

    print(
        "\nDuplicate rows:",
        int(df.duplicated().sum())
    )

    df = df.dropna().reset_index(drop=True)

    counts = df[LABEL_COL].value_counts().to_dict()

    if len(counts) < 2 or min(counts.values()) < 30:
        raise SystemExit(
            "STOP: At least 30 examples of each target class "
            f"are required. Current counts: {counts}"
        )

    print("\nRows after removing missing values:", len(df))
    print("Target distribution:", counts)

    # --------------------------------------------------------
    # 2. PREPARE FEATURES
    # --------------------------------------------------------

    extra_drop = [
        column.strip()
        for column in os.environ.get("CHAMA_DROP", "").split(",")
        if column.strip()
    ]

    identifier_cols = [
        column
        for column in df.columns
        if column != LABEL_COL
        and (
            column.lower() == "id"
            or column.lower().endswith("_id")
        )
    ]

    drop_cols = sorted(
        set(extra_drop + identifier_cols) & set(df.columns)
    )

    print("\nExcluded identifier columns:", drop_cols or "none")

    X = df.drop(columns=[LABEL_COL] + drop_cols)
    y = df[LABEL_COL].astype(int)

    if X.empty:
        raise SystemExit("No usable predictor columns remain.")

    num_cols = [
        column
        for column in X.columns
        if pd.api.types.is_numeric_dtype(X[column])
    ]

    cat_cols = [
        column
        for column in X.columns
        if column not in num_cols
    ]

    for column in cat_cols:
        X[column] = X[column].astype(str)

    print("Numeric features:", num_cols)
    print("Categorical features:", cat_cols)

    # --------------------------------------------------------
    # 3. TRAIN / VALIDATION / TEST SPLIT
    # --------------------------------------------------------

    X_tv, X_test, y_tv, y_test = train_test_split(
        X,
        y,
        test_size=TEST_SIZE,
        stratify=y,
        random_state=SEED,
    )

    X_train, X_val, y_train, y_val = train_test_split(
        X_tv,
        y_tv,
        test_size=VAL_SIZE / (1 - TEST_SIZE),
        stratify=y_tv,
        random_state=SEED,
    )

    print("\nDataset split:")
    print("Training:", len(X_train))
    print("Validation:", len(X_val))
    print("Test:", len(X_test))

    cv = StratifiedKFold(
        n_splits=5,
        shuffle=True,
        random_state=SEED,
    )

    # --------------------------------------------------------
    # 4. LOGISTIC REGRESSION
    # --------------------------------------------------------

    print("\nTraining Logistic Regression...")

    logistic_model = Pipeline([
        ("pre", make_preprocessor(num_cols, cat_cols)),
        (
            "clf",
            LogisticRegression(
                max_iter=2000,
                random_state=SEED,
                class_weight="balanced",
            ),
        ),
    ])

    logistic_model.fit(X_train, y_train)

    # --------------------------------------------------------
    # 5. RANDOM FOREST
    # --------------------------------------------------------

    print("\nTraining Random Forest...")

    rf_pipe = Pipeline([
        ("pre", make_preprocessor(num_cols, cat_cols)),
        (
            "clf",
            RandomForestClassifier(
                random_state=SEED,
                n_jobs=1,
                class_weight="balanced",
            ),
        ),
    ])

    rf_grid = {
        "clf__n_estimators": [200, 400],
        "clf__max_depth": [None, 8],
        "clf__min_samples_leaf": [1, 3],
    }

    rf_search = GridSearchCV(
        rf_pipe,
        rf_grid,
        scoring="roc_auc",
        refit=True,
        n_jobs=-1,
        cv=cv,
    )

    rf_search.fit(X_train, y_train)

    rf_model = rf_search.best_estimator_

    print("Best Random Forest parameters:")
    print(rf_search.best_params_)

    # --------------------------------------------------------
    # 6. XGBOOST
    # --------------------------------------------------------

    print("\nTraining XGBoost...")

    xgb_pipe = Pipeline([
        ("pre", make_preprocessor(num_cols, cat_cols)),
        (
            "clf",
            XGBClassifier(
                eval_metric="logloss",
                random_state=SEED,
                n_jobs=1,
            ),
        ),
    ])

    xgb_grid = {
        "clf__n_estimators": [100, 300],
        "clf__max_depth": [3, 5],
        "clf__learning_rate": [0.05, 0.1],
        "clf__subsample": [0.8, 1.0],
    }

    xgb_search = GridSearchCV(
        xgb_pipe,
        xgb_grid,
        scoring="roc_auc",
        refit=True,
        n_jobs=-1,
        cv=cv,
    )

    xgb_search.fit(X_train, y_train)

    xgb_model = xgb_search.best_estimator_

    print("Best XGBoost parameters:")
    print(xgb_search.best_params_)

    pd.DataFrame(
        xgb_search.cv_results_
    ).to_csv(
        run_dir / "xgboost_cv_results.csv",
        index=False,
    )

    # --------------------------------------------------------
    # 7. VALIDATION: COMPARE MODELS AND SELECT THRESHOLDS
    # --------------------------------------------------------

    print("\nEvaluating models on validation data...")

    val_scores = {
        "baseline_logreg":
            logistic_model.predict_proba(X_val)[:, 1],
        "random_forest":
            rf_model.predict_proba(X_val)[:, 1],
        "xgboost":
            xgb_model.predict_proba(X_val)[:, 1],
    }

    thresholds = {
        name: best_threshold(y_val, scores)
        for name, scores in val_scores.items()
    }

    validation = {
        name: evaluate(y_val, scores, thresholds[name])
        for name, scores in val_scores.items()
    }

    print("\nVALIDATION RESULTS:")
    print(json.dumps(validation, indent=2))

    # --------------------------------------------------------
    # 8. FINAL TEST EVALUATION
    # --------------------------------------------------------

    print("\nEvaluating models on held-out test data...")

    test_scores = {
        "baseline_logreg":
            logistic_model.predict_proba(X_test)[:, 1],
        "random_forest":
            rf_model.predict_proba(X_test)[:, 1],
        "xgboost":
            xgb_model.predict_proba(X_test)[:, 1],
    }

    test = {
        name: evaluate(y_test, scores, thresholds[name])
        for name, scores in test_scores.items()
    }

    print("\nTEST RESULTS:")
    print(json.dumps(test, indent=2))

    # --------------------------------------------------------
    # 9. ROC CURVE
    # --------------------------------------------------------

    plt.figure(figsize=(7, 6))

    for name, scores in test_scores.items():
        fpr, tpr, _ = roc_curve(y_test, scores)

        plt.plot(
            fpr,
            tpr,
            label=f"{name} (AUC {test[name]['auc_roc']})",
        )

    plt.plot(
        [0, 1],
        [0, 1],
        "k--",
        linewidth=0.8,
    )

    plt.xlabel("False positive rate")
    plt.ylabel("True positive rate")
    plt.title("Credit scoring model comparison - test set")
    plt.legend()
    plt.tight_layout()

    plt.savefig(
        run_dir / "roc_curve.png",
        dpi=150,
    )

    plt.close()

    # --------------------------------------------------------
    # 10. LOGISTIC REGRESSION CONFUSION MATRIX
    # --------------------------------------------------------

    ConfusionMatrixDisplay.from_predictions(
        y_test,
        (
            test_scores["baseline_logreg"]
            >= thresholds["baseline_logreg"]
        ).astype(int),
    )

    plt.title("Logistic Regression confusion matrix - test")

    plt.tight_layout()

    plt.savefig(
        run_dir / "confusion_matrix_logreg.png",
        dpi=150,
    )

    plt.close()

    # --------------------------------------------------------
    # 11. OPTIONAL XGBOOST SHAP COMPARISON PLOT
    # --------------------------------------------------------

    try:
        import shap

        pre = xgb_model.named_steps["pre"]
        clf = xgb_model.named_steps["clf"]

        feature_names = [
            name.split("__", 1)[-1]
            for name in pre.get_feature_names_out()
        ]

        transformed_test = pre.transform(X_test)

        shap_values = shap.TreeExplainer(
            clf
        ).shap_values(transformed_test)

        shap.summary_plot(
            shap_values,
            transformed_test,
            feature_names=feature_names,
            show=False,
        )

        plt.tight_layout()

        plt.savefig(
            run_dir / "xgboost_shap_summary.png",
            dpi=150,
        )

        plt.close()

    except Exception as exc:
        print("\nOptional XGBoost SHAP plot skipped:", exc)

    # --------------------------------------------------------
    # 12. BUILD FEATURE METADATA
    # --------------------------------------------------------

    features = []

    for column in num_cols:
        features.append({
            "name": column,
            "type": "numeric",
            "min": float(X_train[column].min()),
            "max": float(X_train[column].max()),
        })

    for column in cat_cols:
        features.append({
            "name": column,
            "type": "categorical",
            "allowed": sorted(
                X_train[column].unique().tolist()
            ),
        })

    versions = {
        "python": platform.python_version(),
        "scikit-learn": sklearn.__version__,
        "xgboost": xgboost.__version__,
        "pandas": pd.__version__,
        "numpy": np.__version__,
    }

    # --------------------------------------------------------
    # 13. SAVE LOGISTIC REGRESSION AS PRODUCTION MODEL
    # --------------------------------------------------------

    metadata = {
        "model_version": f"credit-logreg-{run_id}",
        "model_type": "Logistic Regression",
        "selected_model": "baseline_logreg",
        "run_id": run_id,
        "git_commit": git_commit(),
        "seed": SEED,
        "dataset": DATA_PATH.name,
        "n_rows": int(len(df)),
        "label_column": LABEL_COL,
        "positive_class_meaning": POSITIVE_MEANS,
        "features": features,
        "risk_bands": RISK_BANDS,
        "decision_threshold": thresholds["baseline_logreg"],
        "best_params": logistic_model.named_steps["clf"].get_params(),
        "random_forest_best_params": rf_search.best_params_,
        "xgboost_best_params": xgb_search.best_params_,
        "library_versions": versions,
        "validation": validation,
        "test": test,
        "note": (
            "risk_score is a model score, not a calibrated probability. "
            "Logistic Regression is the selected production model. "
            "Random Forest and XGBoost are comparison models."
        ),
    }

    # Production model: Logistic Regression.
    joblib.dump(
        logistic_model,
        art_dir / "model.joblib",
    )

    (art_dir / "metadata.json").write_text(
        json.dumps(metadata, indent=2)
    )

    # Preserve all models separately for research comparison.
    joblib.dump(
        logistic_model,
        run_dir / "baseline_logreg.joblib",
    )

    joblib.dump(
        rf_model,
        run_dir / "random_forest.joblib",
    )

    joblib.dump(
        xgb_model,
        run_dir / "xgboost.joblib",
    )

    metrics = {
        "selected_production_model": "Logistic Regression",
        "validation": validation,
        "test": test,
        "versions": versions,
    }

    (run_dir / "metrics.json").write_text(
        json.dumps(metrics, indent=2)
    )

    print("\n" + "=" * 65)
    print("TRAINING COMPLETED")
    print("=" * 65)

    print("Selected production model: Logistic Regression")
    print("Validation threshold:", thresholds["baseline_logreg"])
    print("Production artifacts:", art_dir)
    print("Full experiment results:", run_dir)

    print("\nCopy these two files into:")
    print("ml-services/credit-scoring/model/")

    print("1. model.joblib")
    print("2. metadata.json")


if __name__ == "__main__":
    main()
