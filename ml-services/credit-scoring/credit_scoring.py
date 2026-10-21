"""
ChamaSmart credit-scoring microservice.

POST /api/score   -> risk score + top contributing factors (TreeSHAP) for one loan applicant
GET  /health      -> 200 when the model is loaded, 503 otherwise

The feature list, allowed values and training ranges come from model/metadata.json, which the
training script writes, so this file never hard-codes column names.
Prediction never trains anything: it only loads the frozen model at start-up.
"""
import json
import logging
import math
import os
import uuid
from pathlib import Path

import joblib
import numpy as np
import pandas as pd
import sklearn
import xgboost as xgb
from flask import Flask, jsonify, request

MODEL_DIR = Path(os.environ.get("MODEL_DIR", Path(__file__).parent / "model"))

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")
log = logging.getLogger("credit-scoring")

app = Flask(__name__)
app.config["MAX_CONTENT_LENGTH"] = 16 * 1024  # reject oversized requests

STATE = {"ready": False, "error": None, "pipe": None, "meta": None}


def load_artifacts():
    try:
        pipe = joblib.load(MODEL_DIR / "model.joblib")
        meta = json.loads((MODEL_DIR / "metadata.json").read_text())
        trained_with = meta.get("library_versions", {}).get("scikit-learn")
        if trained_with and trained_with != sklearn.__version__:
            log.warning("scikit-learn version mismatch: trained with %s, running %s",
                        trained_with, sklearn.__version__)
        STATE.update(ready=True, error=None, pipe=pipe, meta=meta)
        log.info("Loaded model %s", meta.get("model_version"))
    except Exception as exc:
        STATE.update(ready=False, error=str(exc), pipe=None, meta=None)
        log.error("Model could not be loaded: %s", exc)


load_artifacts()


def error_response(code, message, status, request_id, fields=None):
    body = {"request_id": request_id, "error": {"code": code, "message": message}}
    if fields:
        body["error"]["fields"] = fields
    resp = jsonify(body)
    resp.status_code = status
    resp.headers["X-Request-ID"] = request_id
    return resp


def validate(payload, features):
    """Return (clean_row, field_errors, out_of_range_names)."""
    row, errors, out_of_range = {}, {}, []
    for f in features:
        name = f["name"]
        if name not in payload or payload[name] is None:
            errors[name] = "This field is required."
            continue
        value = payload[name]
        if f["type"] == "numeric":
            if isinstance(value, bool) or not isinstance(value, (int, float)) or not math.isfinite(value):
                errors[name] = "Must be a number."
                continue
            row[name] = float(value)
            if value < f["min"] or value > f["max"]:
                out_of_range.append(name)
        else:
            if not isinstance(value, str) or value not in f["allowed"]:
                errors[name] = "Must be one of: " + ", ".join(f["allowed"])
                continue
            row[name] = value
    return row, errors, out_of_range


@app.get("/health")
def health():
    if not STATE["ready"]:
        return jsonify({"status": "unavailable", "reason": "model not loaded"}), 503
    return jsonify({"status": "ok", "model_version": STATE["meta"]["model_version"]})


@app.post("/api/score")
def score():
    request_id = request.headers.get("X-Request-ID") or str(uuid.uuid4())
    if not STATE["ready"]:
        return error_response("model_unavailable", "The scoring model is not available.", 503, request_id)

    payload = request.get_json(silent=True)
    if not isinstance(payload, dict):
        return error_response("invalid_json", "Request body must be a JSON object.", 400, request_id)

    meta, pipe = STATE["meta"], STATE["pipe"]
    row, errors, out_of_range = validate(payload, meta["features"])
    if errors:
        return error_response("invalid_input", "Some fields are missing or invalid.", 400,
                              request_id, fields=errors)

    base = {"request_id": request_id, "model_version": meta["model_version"]}

    # Abstain instead of guessing when the applicant is outside what the model was trained on.
    if out_of_range:
        body = dict(base, status="needs_review", risk_score=None, risk_band=None, top_factors=[],
                    reason="Values outside the training range: " + ", ".join(out_of_range)
                           + ". A human reviewer should assess this application.")
        log.info("request=%s status=needs_review", request_id)
        resp = jsonify(body)
        resp.headers["X-Request-ID"] = request_id
        return resp

    df = pd.DataFrame([row], columns=[f["name"] for f in meta["features"]])
    proba = float(pipe.predict_proba(df)[0, 1])
    positive_is_risk = meta["positive_class_meaning"] == "default"
    risk_score = proba if positive_is_risk else 1.0 - proba

    bands = meta["risk_bands"]
    band = "low" if risk_score < bands["low_max"] else ("high" if risk_score >= bands["high_min"] else "medium")

    # TreeSHAP contributions (log-odds of the positive class) straight from XGBoost.
    pre, clf = pipe.named_steps["pre"], pipe.named_steps["clf"]
    names = [n.split("__", 1)[-1] for n in pre.get_feature_names_out()]
    contribs = clf.get_booster().predict(xgb.DMatrix(pre.transform(df)), pred_contribs=True)[0][:-1]
    sign = 1.0 if positive_is_risk else -1.0
    order = np.argsort(-np.abs(contribs))[:3]
    top = [{"feature": names[i],
            "impact": round(float(sign * contribs[i]), 4),
            "direction": "raises risk" if sign * contribs[i] > 0 else "lowers risk"} for i in order]

    log.info("request=%s status=scored band=%s", request_id, band)  # never log applicant values
    resp = jsonify(dict(base, status="scored", risk_score=round(risk_score, 4), risk_band=band,
                        top_factors=top,
                        note="risk_score is a model score, not a calibrated probability."))
    resp.headers["X-Request-ID"] = request_id
    return resp


if __name__ == "__main__":
    app.run(host="0.0.0.0", port=int(os.getenv("PORT", "5001")))
