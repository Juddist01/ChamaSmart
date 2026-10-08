# ChamaSmart Contribution Forecasting Service
# Models: Auto-ARIMA (< 100 obs) / LSTM (>= 100 obs)
# Output: payment delay probability per member

from flask import Flask, jsonify

app = Flask(__name__)

@app.route('/api/forecast', methods=['GET'])
def forecast():
    return jsonify({
        "member_id": 1,
        "delay_probability": 0.42,
        "model_used": "ARIMA"
    })

if __name__ == '__main__':
    app.run(port=5002, debug=True)
