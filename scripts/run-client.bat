@echo off
if "%QB_KEYSTORE_PASSWORD%"=="" (echo Set QB_KEYSTORE_PASSWORD & exit /b 1)
call mvn -q -DskipTests install || exit /b 1
call mvn -q -pl app exec:java -Dapp.main=com.quizbattle.app.ClientMain
