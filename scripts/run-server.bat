@echo off
if "%QB_KEYSTORE_PASSWORD%"=="" (echo Set QB_KEYSTORE_PASSWORD & exit /b 1)
if "%QB_TOKEN_SECRET%"=="" (echo Set QB_TOKEN_SECRET & exit /b 1)
call mvn -q -DskipTests install || exit /b 1
call mvn -q -pl app exec:java -Dapp.main=com.quizbattle.app.ServerMain
