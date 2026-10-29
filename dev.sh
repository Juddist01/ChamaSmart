#!/usr/bin/env bash
# ChamaSmart - start the Play web app for development.
#
# Usage (from the project root):   ./dev.sh
#
# What it does, in plain words:
#   1. Loads your secrets from .env (your database login lives there, not in the code).
#   2. Checks the database login works BEFORE starting Play, and tells you clearly if it does not.
#   3. Starts the web app on http://localhost:9000
set -euo pipefail
cd "$(dirname "$0")"

if [ ! -f .env ]; then
  echo "There is no .env file yet."
  echo "Create it with:   cp .env.example .env"
  echo "then open .env and replace every CHANGE_ME with a real value."
  exit 1
fi

set -a
# shellcheck disable=SC1091
source .env
set +a

missing=0
for name in DB_USER DB_PASSWORD; do
  value="${!name:-}"
  if [ -z "$value" ] || [ "$value" = "CHANGE_ME" ]; then
    echo "$name is empty (or still says CHANGE_ME) in .env"
    missing=1
  fi
done
if [ "$missing" -eq 1 ]; then
  exit 1
fi

DB_NAME="${DB_NAME:-chama_app}"
export DB_URL="${DB_URL:-jdbc:mysql://localhost:3306/${DB_NAME}}"

echo "Checking the database login for user '$DB_USER' on database '$DB_NAME' ..."
if ! MYSQL_PWD="$DB_PASSWORD" mysql -u "$DB_USER" -e "USE \`${DB_NAME}\`; SELECT 1;" >/dev/null 2>&1; then
  echo
  echo "MySQL did not accept that login."
  echo "  - Is MySQL running?   sudo systemctl status mysql"
  echo "  - Do DB_USER / DB_PASSWORD in .env match a real MySQL user?"
  echo "  - Does the database '$DB_NAME' exist, and may '$DB_USER' use it?"
  echo "Fix that first; Play cannot start without it."
  exit 1
fi

echo "Database login OK. Starting the web app on http://localhost:9000 (first start takes a few minutes)..."
cd try-chama
exec sbt run
