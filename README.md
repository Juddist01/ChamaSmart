# ChamaSmart
### Community Savings Credit Risk Scoring and Forecasting System

**Student:** Jude Makau | **Admission No:** 166487 | **Class:** ICS 4C
**Supervisor:** Mr. James Gikera | **Strathmore University** | 2026

## Architecture
- **Backend:** Play Framework (Java) + MySQL + Ebean ORM
- **Frontend:** Twirl templates + Async JavaScript
- **ML Services:** Python 3.10 + Flask
  - Credit Scoring: Logistic Regression → XGBoost + SHAP
  - Forecasting: Auto-ARIMA + LSTM
- **Notifications:** Africa's Talking SMS API

## Sprint Progress
- [x] Sprint 1: Environment setup, datasets, ML scaffolding
- [ ] Sprint 2: Backend + frontend development
- [ ] Sprint 3: ML training, integration, testing

## Running the Backend
cd try-chama && sbt run

## Running ML Services
cd ml-services/credit-scoring && python credit_scoring.py
cd ml-services/forecasting && python forecasting.py
