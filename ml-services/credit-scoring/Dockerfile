FROM python:3.11-slim
ENV PYTHONDONTWRITEBYTECODE=1 PYTHONUNBUFFERED=1
# xgboost needs the OpenMP runtime
RUN apt-get update && apt-get install -y --no-install-recommends libgomp1 \
    && rm -rf /var/lib/apt/lists/*
WORKDIR /app
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt
COPY credit_scoring.py .
COPY model ./model
EXPOSE 5001
CMD ["gunicorn", "-b", "0.0.0.0:5001", "--workers", "2", "--timeout", "60", "credit_scoring:app"]
