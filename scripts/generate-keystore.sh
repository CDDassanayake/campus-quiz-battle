#!/usr/bin/env bash
# Creates the server's keystore (private key, keep on the server) and server-cert.pem (public, give to clients).
# Usage: QB_KEYSTORE_PASSWORD=... QB_EXTRA_SAN="ip:192.168.1.20" ./scripts/generate-keystore.sh
# QB_EXTRA_SAN must contain the server's current LAN IP, because clients verify the host name.
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

echo "Done. Copy ONLY secrets/server-cert.pem to the client machines (into their secrets/ folder)."
