@echo off
rem DVBridge Client (Windows). Keep this file ASCII. If the folder path has non-ASCII characters, run it through an ASCII junction (mklink /J).
if not defined JAVA_HOME set "JAVA_HOME=C:\AndroidDev\jdk17"
cd /d "%~dp0"
rem Result: composeApp\build\compose\binaries\main\app\DVBridgeClient (exe + bundled runtime)

call "%~dp0gradlew.bat" :composeApp:createDistributable %*
