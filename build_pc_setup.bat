@echo off
rem DVBridge Client PC installer. Keep this file ASCII. If the folder path has non-ASCII characters, run it through an ASCII junction (mklink /J).
rem Optional: set MANUAL=C:\path\manual.pdf to include the manual.
if not defined JAVA_HOME set "JAVA_HOME=C:\AndroidDev\jdk17"
set "VER=0.5.2"
set "B=C:\dev\dmrc-build"
set "ISCC=%LOCALAPPDATA%\Programs\Inno Setup 6\ISCC.exe"
cd /d "%~dp0"
call "%~dp0gradlew.bat" :composeApp:createDistributable -Pdmrc.buildDir=C:/dev/dmrc-build || exit /b 1
if not exist "%ISCC%" (echo [ERROR] Inno Setup not found & exit /b 1)
set "MAN="
if defined MANUAL set "MAN=/DManual=%MANUAL%"
"%ISCC%" /Q /DAppVer=%VER% /DStage=%B%\compose\binaries\main\app\DVBridgeClient /DOutDir=%B%\setup %MAN% "%~dp0packaging\DVBridgeClient.iss" || exit /b 1
echo Result: %B%\setup\DVBridgeClient-%VER%-setup.exe
