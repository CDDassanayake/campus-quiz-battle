#!/usr/bin/env bash
# Build everything and start the server (needs QB_KEYSTORE_PASSWORD and QB_TOKEN_SECRET).
set -euo pipefail
: "${QB_KEYSTORE_PASSWORD:?Set QB_KEYSTORE_PASSWORD}"
: "${QB_TOKEN_SECRET:?Set QB_TOKEN_SECRET}"
mvn -q -DskipTests install
mvn -q -pl app exec:java -Dapp.main=com.quizbattle.app.ServerMain
