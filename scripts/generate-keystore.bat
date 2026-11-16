@echo off
REM Creates a self-signed server certificate (keystore) and a client truststore.
REM Usage: set QB_KEYSTORE_PASSWORD=...   (optional: set QB_EXTRA_SAN=ip:192.168.1.20)
if "%QB_KEYSTORE_PASSWORD%"=="" (
  echo Set QB_KEYSTORE_PASSWORD first. Never commit it.
  exit /b 1
)
if not exist secrets mkdir secrets
set SAN=dns:localhost,ip:127.0.0.1
if defined QB_EXTRA_SAN set SAN=%SAN%,%QB_EXTRA_SAN%

keytool -genkeypair -alias quizserver -keyalg RSA -keysize 2048 -storetype PKCS12 -keystore secrets\keystore.p12 -storepass %QB_KEYSTORE_PASSWORD% -validity 60 -dname "CN=localhost, OU=QuizBattle, O=Campus, C=LK" -ext "SAN=%SAN%"
keytool -exportcert -alias quizserver -keystore secrets\keystore.p12 -storepass %QB_KEYSTORE_PASSWORD% -rfc -file secrets\server-cert.pem
keytool -importcert -noprompt -alias quizserver -file secrets\server-cert.pem -storetype PKCS12 -keystore secrets\truststore.p12 -storepass %QB_KEYSTORE_PASSWORD%

echo Done. Share ONLY secrets\truststore.p12 with clients.
