@echo off
REM Creates the server keystore (private, keep on the server) and server-cert.pem (public, give to clients).
REM Usage: set QB_KEYSTORE_PASSWORD=...   optional: set QB_EXTRA_SAN=ip:192.168.1.20 (server LAN IP)
if "%QB_KEYSTORE_PASSWORD%"=="" (
  echo Set QB_KEYSTORE_PASSWORD first. Never commit it.
  exit /b 1
)
if not exist secrets mkdir secrets
set SAN=dns:localhost,ip:127.0.0.1
if defined QB_EXTRA_SAN set SAN=%SAN%,%QB_EXTRA_SAN%
keytool -genkeypair -alias quizserver -keyalg RSA -keysize 2048 -storetype PKCS12 -keystore secrets\keystore.p12 -storepass %QB_KEYSTORE_PASSWORD% -validity 60 -dname "CN=localhost, OU=QuizBattle, O=Campus, C=LK" -ext "SAN=%SAN%"
keytool -exportcert -alias quizserver -keystore secrets\keystore.p12 -storepass %QB_KEYSTORE_PASSWORD% -rfc -file secrets\server-cert.pem
echo Done. Copy ONLY secrets\server-cert.pem to the client machines (into their secrets folder).
