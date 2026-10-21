"""API tests for the credit-scoring service. Run from ml-services/credit-scoring:  pytest -v"""
import sys
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import credit_scoring  # noqa: E402

pytestmark = pytest.mark.skipif(
    not credit_scoring.STATE["ready"],
    reason="No trained model in model/ yet - train it in Colab and copy model.joblib + metadata.json here.")


@pytest.fixture()
def client():
    return credit_scoring.app.test_client()


def valid_payload():
    payload = {}
    for f in credit_scoring.STATE["meta"]["features"]:
        payload[f["name"]] = (f["min"] + f["max"]) / 2 if f["type"] == "numeric" else f["allowed"][0]
    return payload


def first_numeric():
    return next(f for f in credit_scoring.STATE["meta"]["features"] if f["type"] == "numeric")


def test_health_ok(client):
    r = client.get("/health")
    assert r.status_code == 200
    assert r.get_json()["status"] == "ok"


def test_valid_request_returns_score_and_factors(client):
    r = client.post("/api/score", json=valid_payload())
    body = r.get_json()
    assert r.status_code == 200
    assert body["status"] == "scored"
    assert 0.0 <= body["risk_score"] <= 1.0
    assert body["risk_band"] in {"low", "medium", "high"}
    assert 1 <= len(body["top_factors"]) <= 3
    assert body["model_version"]


def test_missing_field_is_rejected(client):
    payload = valid_payload()
    missing = first_numeric()["name"]
    payload.pop(missing)
    r = client.post("/api/score", json=payload)
    assert r.status_code == 400
    assert missing in r.get_json()["error"]["fields"]


def test_non_numeric_value_is_rejected(client):
    payload = valid_payload()
    name = first_numeric()["name"]
    payload[name] = "abc"
    r = client.post("/api/score", json=payload)
    assert r.status_code == 400
    assert name in r.get_json()["error"]["fields"]


def test_not_json_is_rejected(client):
    r = client.post("/api/score", data="not json", content_type="text/plain")
    assert r.status_code == 400
    assert r.get_json()["error"]["code"] == "invalid_json"


def test_value_outside_training_range_needs_review(client):
    payload = valid_payload()
    f = first_numeric()
    payload[f["name"]] = f["max"] + 1000
    r = client.post("/api/score", json=payload)
    body = r.get_json()
    assert r.status_code == 200
    assert body["status"] == "needs_review"
    assert body["risk_score"] is None


def test_request_id_is_echoed(client):
    r = client.post("/api/score", json=valid_payload(), headers={"X-Request-ID": "abc-123"})
    assert r.get_json()["request_id"] == "abc-123"
