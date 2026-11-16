#!/usr/bin/env bash
# Build everything and start a client (needs QB_KEYSTORE_PASSWORD for the truststore).
set -euo pipefail
: "${QB_KEYSTORE_PASSWORD:?Set QB_KEYSTORE_PASSWORD}"
mvn -q -DskipTests install
mvn -q -pl app exec:java -Dapp.main=com.quizbattle.app.ClientMain
