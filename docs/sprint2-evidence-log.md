# ChamaSmart – Sprint II Evidence Log

Fill this in as you work. Only write what actually happened. Leave a cell blank until you have run the test.
Chapter 5 tables must be copied from here, not the other way round.

## 1. Sprint tasks (agreed with supervisor)

| Task | Linked objective | Status (done / in progress / blocked) | Notes |
|------|------------------|----------------------------------------|-------|
| Core workflow: loan application → credit score → admin decision → saved in DB | SO-ii | | |
| Forecasting slice: member history → forecast → shown on dashboard | SO-iii | | |
| Database schema deployed and models wired | | | |
| Initial tests recorded | | | |

## 2. Test record

Fill in "Actual", "Result" and "Fix / retest" only after running each test. Add the screenshot name as evidence.

| ID | Requirement | Input / conditions | Expected | Actual | Result | Evidence | Fix / retest |
|----|-------------|--------------------|----------|--------|--------|----------|--------------|
| T01 | Score a valid loan application | All fields valid | Score, band and top factors shown to admin; loan saved with score | | | | |
| T02 | Reject missing field | `loan_amount` missing | 400 with field-level message; nothing saved | | | | |
| T03 | Reject non-numeric value | `loan_amount = "abc"` | Clear error beside the field; form values kept | | | | |
| T04 | Negative / zero amount | `loan_amount = -500` | Rejected by Play validation before calling Flask | | | | |
| T05 | Applicant outside training range | Very large `savings_balance` | Status "needs review"; no score shown | | | | |
| T06 | ML service offline | Stop credit-scoring container | App shows "Scoring unavailable – needs review"; loan still saved as pending | | | | |
| T07 | ML service slow | Timeout in Play client | Safe message; no duplicate loan after retry | | | | |
| T08 | Double submit | Click Apply twice quickly | Exactly one loan row in DB | | | | |
| T09 | Permission: member opens another member's loan | Change id in URL | Access denied (403/redirect) | | | | |
| T10 | Permission: member tries admin approval | Direct POST as member | Denied on the server | | | | |
| T11 | Persistence | Apply, restart app, reopen | Loan and score still present | | | | |
| T12 | Forecast for known member | 12+ weeks of history | 4 forecast values, method "arima" | | | | |
| T13 | Forecast for unknown member | New member id | Labelled baseline, not ARIMA | | | | |
| T14 | Forecast with too little history | 3 data points | Clear validation error | | | | |

Automated tests (pytest) – paste the final line of the CI run and link the GitHub Actions run:

- credit-scoring: ______ passed, ______ skipped, ______ failed — commit `____`
- forecasting: ______ passed, ______ skipped, ______ failed — commit `____`

## 3. ML experiment record (one block per run)

| Field | Credit scoring | Forecasting |
|-------|----------------|-------------|
| Run id (from metadata.json) | | |
| Git commit | | |
| Dataset file and rows | | |
| Seed | | |
| Split | | |
| Baseline | Logistic regression | Last value / 4-week moving average |
| Main model | XGBoost (GridSearchCV) | Per-member ARIMA (AIC grid) |
| Validation result (baseline → main) | | |
| Final test result (baseline → main) | | |
| Drive folder path | | |
| Limitations observed | | |

Unsuccessful or disappointing runs: keep them here too, with what you changed next.

## 4. AI suggestion that I checked (required at review)

- What I asked the AI agent (exact prompt):
- What it claimed (point to the file/line or output it referred to):
- How I verified it (code check, rerun, literature):
- Accepted / rejected, and why:

## 5. Known limitations and open defects

-
-

## 6. Deployment requirements identified (for Sprint III)

- Services to host: Play app, credit-scoring, forecasting, MySQL
- Secrets needed: DB password, application secret, Africa's Talking key
- Chosen host (agreed with supervisor):
- Estimated cost / credit expiry:
