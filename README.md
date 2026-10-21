# ChamaSmart

### A Community Savings Credit Risk Scoring and Forecasting System

**Student:** Jude Makau | **Admission No:** 166487 | **Class:** ICS 4C
**Supervisor:** Mr. James Gikera | **Strathmore University** | 2026

ChamaSmart helps chama (community savings group) administrators record contributions, review loan
applications with a machine-learning risk score and plain-language reasons, and forecast member
contributions.

## Architecture

| Part | Technology | Folder |
|------|-----------|--------|
| Web app and business rules | Play Framework (Java), Twirl templates, Ebean ORM | `try-chama/` |
| Database | MySQL 8 | started by `docker-compose.yml` |
| Credit scoring service | Python, Flask, XGBoost (baseline: logistic regression), TreeSHAP | `ml-services/credit-scoring/` |
| Forecasting service | Python, Flask, per-member ARIMA (baselines: last value, moving average) | `ml-services/forecasting/` |
| SMS notifications | Africa's Talking API | configured in `try-chama/conf/application.conf` |

```
Browser --> Play app (9000) --> MySQL
                |
                +--> credit-scoring (5001)  POST /api/score
                +--> forecasting    (5002)  POST /api/forecast
```

## Repository layout

```
ChamaSmart/
├── try-chama/          Play Framework backend and views
├── ml-services/
│   ├── credit-scoring/ Flask service, training/ script, tests/, model/ (trained artefacts)
│   └── forecasting/    Flask service, training/ script, tests/, model/ (trained artefacts)
├── datasets/           Synthetic CSV datasets used for training
├── docs/               Sprint evidence log and project documents
├── .github/workflows/  Automated tests (GitHub Actions)
├── docker-compose.yml  Runs the whole system
├── setup.sh            One-command setup
└── .env.example        Template for the secrets the system needs
```

## Quick start (Docker)

Requires [Docker Desktop](https://www.docker.com/products/docker-desktop).

```bash
git clone https://github.com/Juddist01/ChamaSmart.git
cd ChamaSmart
./setup.sh          # first run creates .env from .env.example and stops
# open .env and replace every CHANGE_ME with a real value, then:
./setup.sh
```

| Service | URL |
|---------|-----|
| Web app | http://localhost:9000 |
| Credit scoring health check | http://localhost:5001/health |
| Forecasting health check | http://localhost:5002/health |

Stop everything with `docker compose down`.

## Run without Docker (development)

1. Create a MySQL database and user, then copy `.env.example` to `.env` and fill it in.
2. Load the variables and start the web app:
   ```bash
   set -a; source .env; set +a
   export DB_URL="jdbc:mysql://localhost:3306/chamasmart?useSSL=false&allowPublicKeyRetrieval=true"
   cd try-chama && sbt run          # http://localhost:9000
   ```
3. Start each ML service in its own terminal:
   ```bash
   cd ml-services/credit-scoring && pip install -r requirements.txt && python credit_scoring.py   # port 5001
   cd ml-services/forecasting   && pip install -r requirements.txt && python forecasting.py      # port 5002
   ```

## Configuration

Secrets are never stored in the repository. They are read from environment variables:

| Variable | Purpose |
|----------|---------|
| `DB_NAME`, `DB_USER`, `DB_PASSWORD`, `DB_ROOT_PASSWORD` | MySQL access |
| `DB_URL` | JDBC address (set automatically by Docker) |
| `APPLICATION_SECRET` | Play session signing key (`openssl rand -hex 32`) |
| `AT_USERNAME`, `AT_API_KEY` | Africa's Talking SMS (use `sandbox` while developing) |
| `CREDIT_SCORING_URL`, `FORECASTING_URL` | ML service addresses (set automatically by Docker) |

## Training the models

Training runs in Google Colab; each run saves its code version, seed, library versions, metrics
and plots to a run folder in Google Drive.

1. Put the CSVs from `datasets/` in Drive at `MyDrive/ChamaSmart/datasets/`.
2. Run `ml-services/credit-scoring/training/train_credit_scoring.py` and
   `ml-services/forecasting/training/train_forecasting.py` (instructions are at the top of each file).
3. Copy the files from the run's `service_artifacts/` folder into the matching `model/` folder.
4. Pin the library versions listed in `metadata.json` in the service's `requirements.txt`.

## Tests

```bash
cd ml-services/credit-scoring && pytest -v
cd ml-services/forecasting   && pytest -v
cd try-chama && sbt test
```

The same checks run automatically on every push through GitHub Actions. Manual test records are in
`docs/sprint2-evidence-log.md`.

## Project status

- [ ] Sprint I: environment, Git hygiene, secrets out of the repo, data documented, Chapter 4 aligned
- [ ] Sprint II: core workflow (loan application → score → admin decision → saved), initial tests, ML baseline
- [ ] Sprint III: remaining modules, deployment, usability testing, final evaluation, Chapter 6

## Known limitations

- The datasets are synthetic, so model results describe the generated data, not real chamas.
- `risk_score` is a model score, not a calibrated probability.
- Risk-band cut-offs are fixed policy values pending supervisor agreement.
