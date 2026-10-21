#!/usr/bin/env bash
# ChamaSmart one-command setup.   Usage:  ./setup.sh
set -euo pipefail
cd "$(dirname "$0")"

if ! command -v docker >/dev/null 2>&1; then
  echo "Docker is not installed. Install Docker Desktop first: https://www.docker.com/products/docker-desktop"
  exit 1
fi

if [ ! -f .env ]; then
  cp .env.example .env
  echo "Created .env from .env.example."
  echo "Open .env, replace every CHANGE_ME with a real value, then run ./setup.sh again."
  exit 0
fi

if grep -q "CHANGE_ME" .env; then
  echo "Your .env still contains CHANGE_ME. Fill in real values first."
  exit 1
fi

echo "Building and starting ChamaSmart (first run takes a few minutes)..."
docker compose up --build -d

echo
echo "Running:"
echo "  Web app           http://localhost:9000"
echo "  Credit scoring    http://localhost:5001/health"
echo "  Forecasting       http://localhost:5002/health"
echo "Stop everything with:  docker compose down"
