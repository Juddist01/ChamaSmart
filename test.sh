#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")"

set -a
source .env
set +a

export DB_URL="jdbc:mysql://localhost:3306/chama_test?useSSL=false&allowPublicKeyRetrieval=true"
export EVOLUTIONS_ENABLED=false

mkdir -p docs/evidence

cd try-chama
sbt test 2>&1 | tee "../docs/evidence/sbt-test-$(date +%F).txt"
