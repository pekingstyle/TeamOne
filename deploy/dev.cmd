@echo off
rem TeamOne M0 一键起（Windows CMD）
if "%1"=="build" (cd server && call mvn -q -DskipTests package && exit /b)
cd /d %~dp0..
for /f "usebackq tokens=1,* delims==" %%a in (`findstr /r "^[A-Z]" deploy\.env`) do set "%%a=%%b"
cd server
call mvn -q -DskipTests package
java -jar teamone-app\target\teamone-app.jar
