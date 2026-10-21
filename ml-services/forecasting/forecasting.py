"""
ChamaSmart contribution-forecasting microservice.

POST /api/forecast  body: {"member_id": "M001", "history": [numbers, oldest first], "weeks": 4}
GET  /health

The frozen per-member ARIMA parameters (from training) are applied to the member's recent history
with `smooth` - no model is trained while serving. A member with no trained model gets a clearly
labelled 4-week moving-average baseline instead of an invented ARIMA result.
"""
import json
import logging
import math
import os
import uuid
from pathlib import Path

import joblib
import numpy as np
from flask import Flask, jsonify, request
from statsmodels.tsa.arima.model import ARIMA

MODEL_DIR = Path(os.environ.get("MODEL_DIR", Path(__file__).parent / "model"))
MIN_HISTORY = 8
MAX_HISTORY = 520
MAX_WEEKS = 12

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")
log = logging.getLogger("forecasting")

app = Flask(__name__)
app.config["MAX_CONTENT_LENGTH"] = 64 * 1024

STATE = {"ready": False, "error": None, "models": None, "meta": None}


def load_artifacts():
    try:
        models = joblib.load(MODEL_DIR / "arima_models.joblib")
        meta = json.loads((MODEL_DIR / "metadata.json").read_text())
        STATE.update(ready=True, error=None, models=models, meta=meta)
        log.info("Loaded %s (%d member models)", meta.get("model_version"), len(models))
    except Exception as exc:
        STATE.update(ready=False, error=str(exc), models=None, meta=None)
        log.error("Models could not be loaded: %s", exc)


load_artifacts()


def error_response(code, message, status, request_id, fields=None):
    body = {"request_id": request_id, "error": {"code": code, "message": message}}
    if fields:
        body["error"]["fields"] = fields
    resp = jsonify(body)
    resp.status_code = status
    resp.headers["X-Request-ID"] = request_id
    return resp


@app.get("/health")
def health():
    if not STATE["ready"]:
        return jsonify({"status": "unavailable", "reason": "models not loaded"}), 503
    return jsonify({"status": "ok", "model_version": STATE["meta"]["model_version"]})


@app.post("/api/forecast")
def forecast():
    request_id = request.headers.get("X-Request-ID") or str(uuid.uuid4())
    if not STATE["ready"]:
        return error_response("model_unavailable", "The forecasting model is not available.", 503, request_id)

    payload = request.get_json(silent=True)
    if not isinstance(payload, dict):
        return error_response("invalid_json", "Request body must be a JSON object.", 400, request_id)

    errors = {}
    member_id = payload.get("member_id")
    if not isinstance(member_id, (str, int)) or isinstance(member_id, bool) or str(member_id).strip() == "":
        errors["member_id"] = "Required (text or whole number)."

    history = payload.get("history")
    if (not isinstance(history, list) or not (MIN_HISTORY <= len(history) <= MAX_HISTORY)
            or not all(isinstance(v, (int, float)) and not isinstance(v, bool) and math.isfinite(v)
                       for v in history)):
        errors["history"] = f"Required: a list of {MIN_HISTORY}-{MAX_HISTORY} numbers, oldest first."

    weeks = payload.get("weeks", 4)
    if isinstance(weeks, bool) or not isinstance(weeks, int) or not (1 <= weeks <= MAX_WEEKS):
        errors["weeks"] = f"Must be a whole number from 1 to {MAX_WEEKS}."

    if errors:
        return error_response("invalid_input", "Some fields are missing or invalid.", 400,
                              request_id, fields=errors)

    meta = STATE["meta"]
    series = np.asarray(history, dtype=float)
    entry = STATE["models"].get(str(member_id))

    if entry is None:
        values = np.repeat(series[-4:].mean(), weeks)
        status, method = "baseline", "moving_average_4"
        note = "No trained model for this member; showing a 4-week moving average, not an ARIMA forecast."
    else:
        try:
            res = ARIMA(series, order=tuple(entry["order"])).smooth(np.asarray(entry["params"]))
            values = np.asarray(res.forecast(weeks), dtype=float)
            status, method, note = "forecast", "arima", "Estimate based on this member's history."
        except Exception as exc:
            log.error("request=%s arima failed: %s", request_id, exc)
            return error_response("forecast_failed", "The forecast could not be computed.", 500, request_id)

    log.info("request=%s status=%s method=%s", request_id, status, method)  # no member data logged
    resp = jsonify({"request_id": request_id, "status": status, "method": method,
                    "forecast": [round(float(v), 3) for v in values], "weeks": weeks,
                    "model_version": meta["model_version"], "note": note})
    resp.headers["X-Request-ID"] = request_id
    return resp


if __name__ == "__main__":
    app.run(host="0.0.0.0", port=int(os.getenv("PORT", "5002")))
