"""
ChamaSmart - synthetic credit-scoring dataset generator.

Run:  python datasets/generate_credit_dataset.py
Writes: datasets/chama_credit_scoring_dataset.csv  (1,500 members, fixed seed 42)

HOW THE DATA IS MADE (state this in Chapter 5):
  Each member gets a hidden "reliability" score. Reliable members tend to pay on time, rarely pay
  late, and repay past loans. Default is then drawn at random with a probability that rises when
  consistency and repayment are low and days-late is high. The intercept is tuned so about 20% of
  members default, and random noise is added so the outcome is not a perfect formula.

LIMITATION (state this too):
  The labels come from a formula chosen by the author, so a model that scores well here has learned
  that formula, not real chama behaviour. Results show the pipeline works; they do not show the
  model would perform the same on real borrowers.

Columns
  member_id            identifier only (never used as a model input)
  consistency_rate     share of contributions paid on time (0-1)
  avg_days_late        average days late across contributions
  prior_loans          number of earlier loans in the chama
  repayment_rate       share of past loan repayments made on time (0-1); 0.5 means "no loan history yet"
  tenure_weeks         weeks since joining
  total_contributions  total contributed so far (KES)
  default              1 = defaulted on the loan, 0 = repaid
"""
from pathlib import Path

import numpy as np
import pandas as pd

SEED = 42
N = 1500
TARGET_DEFAULT_RATE = 0.20
OUT = Path(__file__).resolve().parent / "chama_credit_scoring_dataset.csv"


def sigmoid(x):
    return 1.0 / (1.0 + np.exp(-x))


def main():
    rng = np.random.default_rng(SEED)

    reliability = rng.normal(0, 1, N)
    tenure_weeks = rng.integers(8, 260, N)
    consistency_rate = np.clip(0.78 + 0.12 * reliability + rng.normal(0, 0.07, N), 0.30, 1.00)
    avg_days_late = np.clip(3.0 - 2.2 * reliability + rng.normal(0, 2.0, N), 0, 30)
    prior_loans = rng.poisson(np.clip(tenure_weeks / 60, 0.3, 4.0))
    repayment_rate = np.where(
        prior_loans > 0,
        np.clip(0.85 + 0.10 * reliability + rng.normal(0, 0.10, N), 0.30, 1.00),
        0.5,
    )
    weekly_amount = rng.choice([200, 300, 500, 1000, 1500], size=N, p=[0.2, 0.25, 0.3, 0.15, 0.1])
    total_contributions = np.round(tenure_weeks * weekly_amount * consistency_rate, 0)

    risk = (
        -3.0 * (consistency_rate - 0.80)
        + 0.12 * avg_days_late
        - 1.5 * (repayment_rate - 0.85)
        - 0.004 * tenure_weeks
        + rng.normal(0, 0.6, N)          # noise: outcome is not a perfect formula
    )

    # tune the intercept so the overall default rate is close to the target
    lo, hi = -15.0, 15.0
    for _ in range(60):
        mid = (lo + hi) / 2
        if sigmoid(mid + risk).mean() > TARGET_DEFAULT_RATE:
            hi = mid
        else:
            lo = mid
    default = (rng.random(N) < sigmoid((lo + hi) / 2 + risk)).astype(int)

    df = pd.DataFrame({
        "member_id": [f"M{i:04d}" for i in range(1, N + 1)],
        "consistency_rate": consistency_rate.round(3),
        "avg_days_late": avg_days_late.round(1),
        "prior_loans": prior_loans,
        "repayment_rate": repayment_rate.round(3),
        "tenure_weeks": tenure_weeks,
        "total_contributions": total_contributions.astype(int),
        "default": default,
    })
    df.to_csv(OUT, index=False)
    print(f"Wrote {OUT} - {len(df)} rows, default rate {df['default'].mean():.3f}")


if __name__ == "__main__":
    main()
