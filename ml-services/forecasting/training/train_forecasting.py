"""
ChamaSmart - contribution forecasting training (Google Colab or local).

What it does, in plain words:
  1. Loads the weekly CSV (one row per member per week).
  2. For every member, holds out the LAST few weeks as a test period (time-ordered, never shuffled).
  3. Compares two simple BASELINES (repeat last value, 4-week moving average) with a per-member ARIMA
     (order chosen by lowest AIC on the training weeks only - a small grid search).
  4. Reports MAE for each method on the held-out weeks.
  5. Saves each member's chosen order + fitted parameters so the service can forecast without retraining.

Colab usage:
    from google.colab import drive; drive.mount('/content/drive')
    !git clone https://github.com/Juddist01/ChamaSmart /content/ChamaSmart
    !python /content/ChamaSmart/ml-services/forecasting/training/train_forecasting.py

Column names can be set with environment variables (the Colab notebook does this for you):
    CHAMA_MEMBER, CHAMA_TIME, CHAMA_VALUE, CHAMA_DATA, CHAMA_ROOT, CHAMA_REPO, CHAMA_RUN_TAG, CHAMA_MAX_MEMBERS
Note: this uses a statsmodels AIC grid search rather than the pmdarima "auto_arima" package. If
Chapter 3 says Auto-ARIMA, describe what you actually ran here.
"""
import json
import os
import platform
import subprocess
import warnings
from datetime import datetime
from pathlib import Path

import joblib
import matplotlib

matplotlib.use("Agg")
import matplotlib.pyplot as plt
import numpy as np
import pandas as pd
import statsmodels
from statsmodels.tsa.arima.model import ARIMA

# ======================= CONFIG (edit these) =======================
ROOT = Path(os.environ.get("CHAMA_ROOT", "/content/drive/MyDrive/ChamaSmart"))
DATA_PATH = Path(os.environ.get("CHAMA_DATA", ROOT / "datasets" / "chama_forecasting_dataset.csv"))
REPO_DIR = os.environ.get("CHAMA_REPO", "/content/ChamaSmart")
MEMBER_COL = os.environ.get("CHAMA_MEMBER", "member_id")   # column that identifies the member
TIME_COL = os.environ.get("CHAMA_TIME", "week")            # column that orders the rows in time
VALUE_COL = os.environ.get("CHAMA_VALUE", "days_to_pay")   # the number being forecast
HOLDOUT_WEEKS = 12
MIN_TRAIN_POINTS = 24
ORDER_GRID = [(p, d, q) for p in range(3) for d in range(2) for q in range(3)]
MAX_MEMBERS = int(os.environ.get("CHAMA_MAX_MEMBERS", "0"))  # 0 = all (set small for a quick test)
SEED = 42
# ===================================================================


def mount_drive_if_colab():
    if str(ROOT).startswith("/content/drive") and not Path("/content/drive/MyDrive").exists():
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


def mae(actual, predicted):
    return float(np.mean(np.abs(np.asarray(actual) - np.asarray(predicted))))


def fit_best_arima(train):
    best = None
    for order in ORDER_GRID:
        try:
            res = ARIMA(train, order=order).fit()
        except Exception:
            continue
        if best is None or res.aic < best[0]:
            best = (res.aic, order, res)
    return best


def main():
    warnings.filterwarnings("ignore")
    mount_drive_if_colab()
    run_id = datetime.now().strftime("%Y%m%d_%H%M%S") + os.environ.get("CHAMA_RUN_TAG", "")
    run_dir = ROOT / "runs" / "forecasting" / run_id
    art_dir = run_dir / "service_artifacts"
    art_dir.mkdir(parents=True, exist_ok=True)
    print(f"Run folder: {run_dir}")

    df = pd.read_csv(DATA_PATH)
    for col in (MEMBER_COL, TIME_COL, VALUE_COL):
        if col not in df.columns:
            raise SystemExit(f"Column '{col}' not found in {list(df.columns)}. Edit the CONFIG block.")
    print(f"Loaded {len(df)} rows; missing values: {int(df[VALUE_COL].isna().sum())}")

    members = sorted(df[MEMBER_COL].unique().tolist())
    if MAX_MEMBERS:
        members = members[:MAX_MEMBERS]

    rows, models, sample = [], {}, None
    for i, m in enumerate(members, 1):
        series = (df[df[MEMBER_COL] == m].sort_values(TIME_COL)[VALUE_COL]
                  .astype(float).dropna().to_numpy())
        if len(series) < MIN_TRAIN_POINTS + HOLDOUT_WEEKS:
            print(f"  skipping member {m}: only {len(series)} points")
            continue
        train, test = series[:-HOLDOUT_WEEKS], series[-HOLDOUT_WEEKS:]

        naive = np.repeat(train[-1], HOLDOUT_WEEKS)
        ma4 = np.repeat(train[-4:].mean(), HOLDOUT_WEEKS)
        best = fit_best_arima(train)
        if best is None:
            print(f"  member {m}: ARIMA failed, skipped")
            continue
        _, order, res = best
        arima_fc = np.asarray(res.forecast(HOLDOUT_WEEKS))

        rows.append({"member": m, "order": str(order),
                     "mae_naive": mae(test, naive), "mae_ma4": mae(test, ma4),
                     "mae_arima": mae(test, arima_fc)})
        models[str(m)] = {"order": order, "params": np.asarray(res.params, dtype=float).tolist()}
        if sample is None:
            sample = (m, train, test, arima_fc, naive)
        if i % 10 == 0:
            print(f"  processed {i}/{len(members)} members")

    per_member = pd.DataFrame(rows)
    if per_member.empty:
        raise SystemExit("No members could be modelled. Check your CONFIG block and data.")
    per_member.to_csv(run_dir / "per_member_metrics.csv", index=False)

    summary = {
        "members_modelled": int(len(per_member)),
        "holdout_weeks": HOLDOUT_WEEKS,
        "mean_mae_naive_last_value": round(float(per_member["mae_naive"].mean()), 4),
        "mean_mae_moving_average_4": round(float(per_member["mae_ma4"].mean()), 4),
        "mean_mae_arima": round(float(per_member["mae_arima"].mean()), 4),
        "share_members_arima_beats_best_baseline": round(float(
            (per_member["mae_arima"] < per_member[["mae_naive", "mae_ma4"]].min(axis=1)).mean()), 4),
    }
    print("RESULTS (held-out weeks):", json.dumps(summary, indent=2))

    if sample is not None:
        m, train, test, fc, naive = sample
        plt.figure(figsize=(8, 4))
        x_train = np.arange(len(train))
        x_test = np.arange(len(train), len(train) + len(test))
        plt.plot(x_train[-40:], train[-40:], label="train")
        plt.plot(x_test, test, label="actual (held out)")
        plt.plot(x_test, fc, label="ARIMA forecast")
        plt.plot(x_test, naive, "--", label="last-value baseline")
        plt.title(f"Member {m}: forecast vs actual")
        plt.xlabel("week index")
        plt.ylabel(VALUE_COL)
        plt.legend()
        plt.tight_layout()
        plt.savefig(run_dir / "sample_forecast.png", dpi=150)
        plt.close()

    metadata = {
        "model_version": f"forecast-arima-{run_id}",
        "run_id": run_id,
        "git_commit": git_commit(),
        "seed": SEED,
        "dataset": DATA_PATH.name,
        "value_column": VALUE_COL,
        "holdout_weeks": HOLDOUT_WEEKS,
        "order_grid": ORDER_GRID,
        "library_versions": {"python": platform.python_version(), "statsmodels": statsmodels.__version__,
                             "numpy": np.__version__, "pandas": pd.__version__},
        "results": summary,
        "note": "Forecasts are estimates; compare against the baselines reported in results.",
    }
    joblib.dump(models, art_dir / "arima_models.joblib")
    (art_dir / "metadata.json").write_text(json.dumps(metadata, indent=2))
    (run_dir / "metrics.json").write_text(json.dumps(summary, indent=2))

    print(f"RUN_DIR={run_dir}")
    print("\nDONE.")
    print(f"Copy the two files in {art_dir} into ml-services/forecasting/model/ in your repo.")


if __name__ == "__main__":
    main()
