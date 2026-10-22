"""
ChamaSmart - LSTM contribution forecasting (Google Colab or local).

What it does, in plain words:
  1. Loads the weekly CSV (one row per member per week).
  2. For every member, the LAST 12 weeks are the TEST period, the 12 weeks before that are the
     VALIDATION period, and everything earlier is TRAINING data (time-ordered, never shuffled).
  3. Trains ONE LSTM shared by all members. It looks at the previous 24 weeks and predicts the
     next 12 weeks in one go.
  4. Scales values using TRAINING weeks only. Stops training early when validation error stops
     improving (so the test weeks never influence training).
  5. Compares the LSTM with two simple baselines on exactly the same test weeks:
        - repeat the last known value
        - repeat the average of the last 4 weeks
  6. Saves the model, metrics, plots and settings to a run folder.

For a fair comparison with ARIMA, run train_forecasting.py on the SAME csv (the notebook does it).

Environment variables (the Colab notebook sets these for you):
    CHAMA_MEMBER, CHAMA_TIME, CHAMA_VALUE, CHAMA_DATA, CHAMA_ROOT, CHAMA_REPO,
    CHAMA_RUN_TAG, CHAMA_MAX_MEMBERS, CHAMA_EPOCHS
"""
import json
import os
import platform
import random
import subprocess
from datetime import datetime
from pathlib import Path

import matplotlib

matplotlib.use("Agg")
import matplotlib.pyplot as plt
import numpy as np
import pandas as pd

os.environ.setdefault("TF_CPP_MIN_LOG_LEVEL", "2")
import tensorflow as tf  # noqa: E402
from tensorflow import keras  # noqa: E402

# ======================= CONFIG =======================
ROOT = Path(os.environ.get("CHAMA_ROOT", "/content/drive/MyDrive/ChamaSmart"))
DATA_PATH = Path(os.environ.get("CHAMA_DATA", ROOT / "datasets" / "chama_forecasting_lstm_dataset.csv"))
REPO_DIR = os.environ.get("CHAMA_REPO", "/content/ChamaSmart")
MEMBER_COL = os.environ.get("CHAMA_MEMBER", "member_id")
TIME_COL = os.environ.get("CHAMA_TIME", "week")
VALUE_COL = os.environ.get("CHAMA_VALUE", "days_to_pay")
LOOKBACK = 24          # weeks of history the LSTM sees
HORIZON = 12           # weeks it predicts (same as the ARIMA hold-out)
UNITS = 64
DROPOUT = 0.2
BATCH_SIZE = 64
EPOCHS = int(os.environ.get("CHAMA_EPOCHS", "200"))   # early stopping usually ends sooner
PATIENCE = 12
MAX_MEMBERS = int(os.environ.get("CHAMA_MAX_MEMBERS", "0"))  # 0 = all members
SEED = 42
# ======================================================


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


def mae(actual, predicted):
    return float(np.mean(np.abs(np.asarray(actual) - np.asarray(predicted))))


def make_windows(series, end_input, horizon=HORIZON, lookback=LOOKBACK):
    """Input = series[end_input-lookback:end_input]; target = series[end_input:end_input+horizon]."""
    return series[end_input - lookback:end_input], series[end_input:end_input + horizon]


def main():
    mount_drive_if_colab()
    random.seed(SEED)
    np.random.seed(SEED)
    keras.utils.set_random_seed(SEED)

    run_id = datetime.now().strftime("%Y%m%d_%H%M%S") + os.environ.get("CHAMA_RUN_TAG", "")
    run_dir = ROOT / "runs" / "lstm_forecasting" / run_id
    art_dir = run_dir / "service_artifacts"
    art_dir.mkdir(parents=True, exist_ok=True)
    print(f"Run folder: {run_dir}")

    df = pd.read_csv(DATA_PATH)
    for col in (MEMBER_COL, TIME_COL, VALUE_COL):
        if col not in df.columns:
            raise SystemExit(f"Column '{col}' not found in {list(df.columns)}. Set CHAMA_MEMBER/CHAMA_TIME/CHAMA_VALUE.")
    print(f"Loaded {len(df)} rows; missing values in {VALUE_COL}: {int(df[VALUE_COL].isna().sum())}")

    members = sorted(df[MEMBER_COL].unique().tolist())
    if MAX_MEMBERS:
        members = members[:MAX_MEMBERS]

    series_by_member = {}
    for m in members:
        s = (df[df[MEMBER_COL] == m].sort_values(TIME_COL)[VALUE_COL].astype(float).dropna().to_numpy())
        if len(s) >= LOOKBACK + 3 * HORIZON:
            series_by_member[m] = s
        else:
            print(f"  skipping member {m}: only {len(s)} points (need {LOOKBACK + 3 * HORIZON})")
    if not series_by_member:
        raise SystemExit("No member has enough weeks. Reduce LOOKBACK/HORIZON or check the CSV.")
    print(f"Members used: {len(series_by_member)}")

    # ---------- scaling: learned from TRAINING weeks only ----------
    train_values = np.concatenate([s[:len(s) - 2 * HORIZON] for s in series_by_member.values()])
    mean, std = float(train_values.mean()), float(train_values.std() or 1.0)

    def scale(a):
        return (np.asarray(a) - mean) / std

    def unscale(a):
        return np.asarray(a) * std + mean

    # ---------- build windows ----------
    Xtr, ytr, Xva, yva, Xte, yte, test_members = [], [], [], [], [], [], []
    for m, s in series_by_member.items():
        L = len(s)
        train_end, val_end = L - 2 * HORIZON, L - HORIZON
        # training windows: the target must lie entirely inside the training region
        for end in range(LOOKBACK, train_end - HORIZON + 1):
            x, y = make_windows(s, end)
            Xtr.append(x); ytr.append(y)
        # validation window: predict the 12 weeks just before the test period
        x, y = make_windows(s, train_end)
        Xva.append(x); yva.append(y)
        # test window: predict the final 12 weeks (inputs are real past weeks only)
        x, y = make_windows(s, val_end)
        Xte.append(x); yte.append(y); test_members.append(m)

    def prep(X):
        return scale(np.asarray(X))[..., None]

    Xtr, Xva, Xte = prep(Xtr), prep(Xva), prep(Xte)
    ytr, yva, yte = scale(np.asarray(ytr)), scale(np.asarray(yva)), np.asarray(yte)  # yte stays in real units
    print(f"Windows -> train {len(Xtr)}, validation {len(Xva)}, test {len(Xte)}")

    # ---------- model ----------
    model = keras.Sequential([
        keras.layers.Input(shape=(LOOKBACK, 1)),
        keras.layers.LSTM(UNITS),
        keras.layers.Dropout(DROPOUT),
        keras.layers.Dense(HORIZON),
    ])
    model.compile(optimizer=keras.optimizers.Adam(1e-3), loss="mse")
    early = keras.callbacks.EarlyStopping(monitor="val_loss", patience=PATIENCE, restore_best_weights=True)
    history = model.fit(Xtr, ytr, validation_data=(Xva, yva), epochs=EPOCHS,
                        batch_size=BATCH_SIZE, shuffle=True, callbacks=[early], verbose=0)
    epochs_run = len(history.history["loss"])
    print(f"Training stopped after {epochs_run} epochs; best validation loss {min(history.history['val_loss']):.4f}")

    # ---------- FINAL evaluation on the test weeks ----------
    lstm_fc = unscale(model.predict(Xte, verbose=0))
    naive_fc, ma4_fc = [], []
    for m in test_members:
        s = series_by_member[m]
        history_part = s[:len(s) - HORIZON]
        naive_fc.append(np.repeat(history_part[-1], HORIZON))
        ma4_fc.append(np.repeat(history_part[-4:].mean(), HORIZON))
    naive_fc, ma4_fc = np.asarray(naive_fc), np.asarray(ma4_fc)

    per_member = pd.DataFrame({
        "member": test_members,
        "mae_naive": [mae(yte[i], naive_fc[i]) for i in range(len(yte))],
        "mae_ma4": [mae(yte[i], ma4_fc[i]) for i in range(len(yte))],
        "mae_lstm": [mae(yte[i], lstm_fc[i]) for i in range(len(yte))],
    })
    per_member.to_csv(run_dir / "per_member_metrics.csv", index=False)
    summary = {
        "members_modelled": int(len(per_member)),
        "holdout_weeks": HORIZON,
        "lookback_weeks": LOOKBACK,
        "epochs_run": epochs_run,
        "mean_mae_naive_last_value": round(float(per_member["mae_naive"].mean()), 4),
        "mean_mae_moving_average_4": round(float(per_member["mae_ma4"].mean()), 4),
        "mean_mae_lstm": round(float(per_member["mae_lstm"].mean()), 4),
        "share_members_lstm_beats_best_baseline": round(float(
            (per_member["mae_lstm"] < per_member[["mae_naive", "mae_ma4"]].min(axis=1)).mean()), 4),
    }
    print("RESULTS (held-out weeks):", json.dumps(summary, indent=2))

    # ---------- plots ----------
    plt.figure(figsize=(6, 4))
    plt.plot(history.history["loss"], label="training loss")
    plt.plot(history.history["val_loss"], label="validation loss")
    plt.xlabel("epoch"); plt.ylabel("MSE (scaled)"); plt.title("LSTM training curve")
    plt.legend(); plt.tight_layout()
    plt.savefig(run_dir / "training_curve.png", dpi=150); plt.close()

    m0 = test_members[0]
    s0 = series_by_member[m0]
    plt.figure(figsize=(8, 4))
    plt.plot(range(len(s0) - 40, len(s0) - HORIZON), s0[-40:-HORIZON], label="history")
    plt.plot(range(len(s0) - HORIZON, len(s0)), yte[0], label="actual (held out)")
    plt.plot(range(len(s0) - HORIZON, len(s0)), lstm_fc[0], label="LSTM forecast")
    plt.plot(range(len(s0) - HORIZON, len(s0)), naive_fc[0], "--", label="last-value baseline")
    plt.title(f"Member {m0}: LSTM forecast vs actual"); plt.xlabel("week index"); plt.ylabel(VALUE_COL)
    plt.legend(); plt.tight_layout()
    plt.savefig(run_dir / "sample_forecast.png", dpi=150); plt.close()

    # ---------- save ----------
    metadata = {
        "model_version": f"forecast-lstm-{run_id}",
        "run_id": run_id,
        "git_commit": git_commit(),
        "seed": SEED,
        "dataset": DATA_PATH.name,
        "value_column": VALUE_COL,
        "lookback_weeks": LOOKBACK,
        "horizon_weeks": HORIZON,
        "scaler": {"mean": mean, "std": std},
        "architecture": f"LSTM({UNITS}) -> Dropout({DROPOUT}) -> Dense({HORIZON})",
        "library_versions": {"python": platform.python_version(), "tensorflow": tf.__version__,
                             "numpy": np.__version__, "pandas": pd.__version__},
        "results": summary,
    }
    model.save(art_dir / "lstm_model.keras")
    (art_dir / "metadata.json").write_text(json.dumps(metadata, indent=2))
    (run_dir / "metrics.json").write_text(json.dumps(summary, indent=2))
    print(f"RUN_DIR={run_dir}")
    print("\nDONE.")


if __name__ == "__main__":
    main()
