"""
ChamaSmart Credit Scoring Microservice.

Production model: Logistic Regression.

POST /api/score -> risk score and top contributing factors.
GET /health    -> service and model health.

The service loads a previously trained model and metadata.
It never trains a model while handling requests.
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
from flask import Flask, jsonify, request


MODEL_DIR = Path(
    os.environ.get("MODEL_DIR", Path(__file__).parent / "model")
)

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s %(levelname)s %(message)s",
)

log = logging.getLogger("credit-scoring")

app = Flask(__name__)
app.config["MAX_CONTENT_LENGTH"] = 16 * 1024

STATE = {
    "ready": False,
    "error": None,
    "pipe": None,
    "meta": None,
}


def load_artifacts():
    """Load and validate the saved Logistic Regression artifacts."""
    try:
        model_path = MODEL_DIR / "model.joblib"
        metadata_path = MODEL_DIR / "metadata.json"

        if not model_path.exists():
            raise FileNotFoundError(f"Missing model: {model_path}")

        if not metadata_path.exists():
            raise FileNotFoundError(f"Missing metadata: {metadata_path}")

        pipe = joblib.load(model_path)
        meta = json.loads(metadata_path.read_text())

        if not hasattr(pipe, "named_steps"):
            raise ValueError("Model must be a sklearn Pipeline.")

        if "pre" not in pipe.named_steps or "clf" not in pipe.named_steps:
            raise ValueError("Model pipeline must contain pre and clf steps.")

        clf = pipe.named_steps["clf"]

        if not hasattr(clf, "coef_"):
            raise ValueError(
                "Production model must support Logistic Regression coefficients."
            )

        if meta.get("model_type") != "Logistic Regression":
            raise ValueError(
                "Metadata does not identify Logistic Regression as the production model."
            )

        if not meta.get("features"):
            raise ValueError("Model metadata has no feature definitions.")

        trained_with = (
            meta.get("library_versions", {}).get("scikit-learn")
        )

        if trained_with and trained_with != sklearn.__version__:
            log.warning(
                "scikit-learn version mismatch: trained with %s, running %s",
                trained_with,
                sklearn.__version__,
            )

        STATE.update(
            ready=True,
            error=None,
            pipe=pipe,
            meta=meta,
        )

        log.info(
            "Loaded production model %s",
            meta.get("model_version"),
        )

    except Exception as exc:
        STATE.update(
            ready=False,
            error=str(exc),
            pipe=None,
            meta=None,
        )

        log.error("Model could not be loaded: %s", exc)


load_artifacts()


def error_response(code, message, status, request_id, fields=None):
    body = {
        "request_id": request_id,
        "error": {
            "code": code,
            "message": message,
        },
    }

    if fields:
        body["error"]["fields"] = fields

    response = jsonify(body)
    response.status_code = status
    response.headers["X-Request-ID"] = request_id

    return response


def validate(payload, features):
    """Validate required features, data types and training ranges."""
    row = {}
    errors = {}
    out_of_range = []

    for feature in features:
        name = feature["name"]

        if name not in payload or payload[name] is None:
            errors[name] = "This field is required."
            continue

        value = payload[name]

        if feature["type"] == "numeric":

            if (
                isinstance(value, bool)
                or not isinstance(value, (int, float))
                or not math.isfinite(value)
            ):
                errors[name] = "Must be a number."
                continue

            row[name] = float(value)

            if (
                value < feature["min"]
                or value > feature["max"]
            ):
                out_of_range.append(name)

        else:
            if (
                not isinstance(value, str)
                or value not in feature["allowed"]
            ):
                errors[name] = (
                    "Must be one of: "
                    + ", ".join(feature["allowed"])
                )
                continue

            row[name] = value

    return row, errors, out_of_range


def logistic_top_factors(pipe, dataframe, positive_is_risk):
    """
    Calculate feature contributions using Logistic Regression.

    Contribution = standardized feature value * model coefficient.

    Positive contributions increase the score for the positive class.
    The direction is reversed when the positive class represents repayment.
    """

    preprocessor = pipe.named_steps["pre"]
    classifier = pipe.named_steps["clf"]

    transformed = np.asarray(
        preprocessor.transform(dataframe)
    )

    coefficients = np.asarray(classifier.coef_[0])

    feature_names = [
        name.split("__", 1)[-1]
        for name in preprocessor.get_feature_names_out()
    ]

    if transformed.ndim != 2:
        raise ValueError("Unexpected transformed feature dimensions.")

    if transformed.shape[1] != len(coefficients):
        raise ValueError("Feature and coefficient dimensions do not match.")

    if len(feature_names) != len(coefficients):
        raise ValueError("Feature names and coefficients do not match.")

    contributions = transformed[0] * coefficients

    # The model's positive class may mean repayment rather than default.
    sign = 1.0 if positive_is_risk else -1.0

    risk_contributions = sign * contributions

    order = np.argsort(
        -np.abs(risk_contributions)
    )[:3]

    top = []

    for index in order:
        impact = float(risk_contributions[index])

        top.append({
            "feature": feature_names[index],
            "impact": round(impact, 4),
            "direction": (
                "raises risk"
                if impact > 0
                else "lowers risk"
            ),
        })

    return top


@app.get("/health")
def health():
    if not STATE["ready"]:
        return jsonify({
            "status": "unavailable",
            "reason": "model not loaded",
        }), 503

    return jsonify({
        "status": "ok",
        "model_version": STATE["meta"]["model_version"],
        "model_type": STATE["meta"]["model_type"],
    })


@app.post("/api/score")
def score():

    request_id = (
        request.headers.get("X-Request-ID")
        or str(uuid.uuid4())
    )

    if not STATE["ready"]:
        return error_response(
            "model_unavailable",
            "The scoring model is not available.",
            503,
            request_id,
        )

    payload = request.get_json(silent=True)

    if not isinstance(payload, dict):
        return error_response(
            "invalid_json",
            "Request body must be a JSON object.",
            400,
            request_id,
        )

    meta = STATE["meta"]
    pipe = STATE["pipe"]

    row, errors, out_of_range = validate(
        payload,
        meta["features"],
    )

    if errors:
        return error_response(
            "invalid_input",
            "Some fields are missing or invalid.",
            400,
            request_id,
            fields=errors,
        )

    base = {
        "request_id": request_id,
        "model_version": meta["model_version"],
    }

    # Abstain rather than extrapolate beyond the training data.
    if out_of_range:

        body = dict(
            base,
            status="needs_review",
            risk_score=None,
            risk_band=None,
            top_factors=[],
            reason=(
                "Values outside the training range: "
                + ", ".join(out_of_range)
                + ". A human reviewer should assess this application."
            ),
        )

        log.info(
            "request=%s status=needs_review",
            request_id,
        )

        response = jsonify(body)
        response.headers["X-Request-ID"] = request_id

        return response

    dataframe = pd.DataFrame(
        [row],
        columns=[
            feature["name"]
            for feature in meta["features"]
        ],
    )

    try:
        probabilities = pipe.predict_proba(dataframe)[0]

        positive_probability = float(probabilities[1])

        positive_is_risk = (
            meta["positive_class_meaning"] == "default"
        )

        risk_score = (
            positive_probability
            if positive_is_risk
            else 1.0 - positive_probability
        )

        bands = meta["risk_bands"]

        if risk_score < bands["low_max"]:
            risk_band = "low"
        elif risk_score >= bands["high_min"]:
            risk_band = "high"
        else:
            risk_band = "medium"

        top_factors = logistic_top_factors(
            pipe,
            dataframe,
            positive_is_risk,
        )

    except Exception:
        log.exception(
            "request=%s scoring failed",
            request_id,
        )

        return error_response(
            "scoring_error",
            "The scoring model could not process this application.",
            500,
            request_id,
        )

    log.info(
        "request=%s status=scored band=%s",
        request_id,
        risk_band,
    )

    response = jsonify(
        dict(
            base,
            status="scored",
            risk_score=round(risk_score, 4),
            risk_band=risk_band,
            top_factors=top_factors,
            note=(
                "risk_score is a model score, "
                "not a calibrated probability."
            ),
        )
    )

    response.headers["X-Request-ID"] = request_id

    return response


if __name__ == "__main__":
    app.run(
        host="0.0.0.0",
        port=int(os.getenv("PORT", "5001")),
    )
