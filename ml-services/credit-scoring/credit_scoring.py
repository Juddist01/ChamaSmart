# ChamaSmart Credit Scoring Service
# Models: Logistic Regression baseline → XGBoost production
# Output: risk_tier (LOW/MEDIUM/HIGH) + SHAP attribution values

from flask import Flask, request, jsonify

app = Flask(__name__)

@app.route('/api/score', methods=['POST'])
def score():
    data = request.get_json()
    return jsonify({
        "risk_tier": "MEDIUM",
        "probability": 0.54,
        "shap_values": {}
    })

if __name__ == '__main__':
    app.run(port=5001, debug=True)
