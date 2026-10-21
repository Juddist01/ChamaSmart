"""API tests for the forecasting service. Run from ml-services/forecasting:  pytest -v"""
import sys
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import forecasting  # noqa: E402

pytestmark = pytest.mark.skipif(
    not forecasting.STATE["ready"],
    reason="No trained models in model/ yet - train in Colab and copy arima_models.joblib + metadata.json here.")

HISTORY = [3.0, 4.0, 2.0, 5.0, 3.0, 4.0, 3.0, 2.0, 4.0, 3.0, 5.0, 4.0]


@pytest.fixture()
def client():
    return forecasting.app.test_client()


def known_member():
    return next(iter(forecasting.STATE["models"]))


def test_health_ok(client):
    assert client.get("/health").status_code == 200


def test_known_member_gets_arima_forecast(client):
    r = client.post("/api/forecast", json={"member_id": known_member(), "history": HISTORY, "weeks": 4})
    body = r.get_json()
    assert r.status_code == 200
    assert body["status"] == "forecast" and body["method"] == "arima"
    assert len(body["forecast"]) == 4


def test_unknown_member_gets_labelled_baseline(client):
    r = client.post("/api/forecast", json={"member_id": "NO-SUCH-MEMBER", "history": HISTORY, "weeks": 3})
    body = r.get_json()
    assert r.status_code == 200
    assert body["status"] == "baseline" and len(body["forecast"]) == 3


def test_history_too_short_rejected(client):
    r = client.post("/api/forecast", json={"member_id": "x", "history": [1, 2, 3], "weeks": 2})
    assert r.status_code == 400 and "history" in r.get_json()["error"]["fields"]


def test_non_numeric_history_rejected(client):
    r = client.post("/api/forecast", json={"member_id": "x", "history": ["a"] * 10, "weeks": 2})
    assert r.status_code == 400


@pytest.mark.parametrize("weeks", [0, 13, -1, "4", 2.5])
def test_bad_weeks_rejected(client, weeks):
    r = client.post("/api/forecast", json={"member_id": "x", "history": HISTORY, "weeks": weeks})
    assert r.status_code == 400 and "weeks" in r.get_json()["error"]["fields"]


def test_missing_member_rejected(client):
    r = client.post("/api/forecast", json={"history": HISTORY, "weeks": 2})
    assert r.status_code == 400 and "member_id" in r.get_json()["error"]["fields"]
