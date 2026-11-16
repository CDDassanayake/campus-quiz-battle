#!/usr/bin/env bash
# Creates a self-signed server certificate (keystore) and a client truststore.
# Usage:  QB_KEYSTORE_PASSWORD=... [QB_EXTRA_SAN="ip:192.168.1.20"] ./scripts/generate-keystore.sh
# QB_EXTRA_SAN must contain the server's LAN IP so clients can verify the host name.
set -euo pipefail
: "${QB_KEYSTORE_PASSWORD:?Set QB_KEYSTORE_PASSWORD first (never commit it)}"
mkdir -p secrets
SAN="dns:localhost,ip:127.0.0.1"
if [ -n "${QB_EXTRA_SAN:-}" ]; then SAN="$SAN,$QB_EXTRA_SAN"; fi

keytool -genkeypair -alias quizserver -keyalg RSA -keysize 2048 -storetype PKCS12 \
  -keystore secrets/keystore.p12 -storepass "$QB_KEYSTORE_PASSWORD" -validity 60 \
  -dname "CN=localhost, OU=QuizBattle, O=Campus, C=LK" -ext "SAN=$SAN"

keytool -exportcert -alias quizserver -keystore secrets/keystore.p12 \
  -storepass "$QB_KEYSTORE_PASSWORD" -rfc -file secrets/server-cert.pem

keytool -importcert -noprompt -alias quizserver -file secrets/server-cert.pem \
  -storetype PKCS12 -keystore secrets/truststore.p12 -storepass "$QB_KEYSTORE_PASSWORD"

echo "Done. Share ONLY secrets/truststore.p12 with clients. Keep secrets/keystore.p12 on the server."
