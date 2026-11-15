@echo off
rem DMRBridge Client PC installer. Keep this file ASCII. If the folder path has non-ASCII characters, run it through an ASCII junction (mklink /J).
rem Optional: set MANUAL=C:\path\manual.pdf to include the manual.
if not defined JAVA_HOME set "JAVA_HOME=C:\AndroidDev\jdk17"
if not defined ANDROID_HOME set "ANDROID_HOME=C:\AndroidDev\sdk"
set "VER=0.4"
set "B=C:\dev\dmrc-build"
set "ISCC=%LOCALAPPDATA%\Programs\Inno Setup 6\ISCC.exe"
cd /d "%~dp0"
call "%~dp0gradlew.bat" :composeApp:createDistributable -Pdmrc.buildDir=C:/dev/dmrc-build || exit /b 1
if not exist "%ISCC%" (echo [ERROR] Inno Setup not found & exit /b 1)
set "MAN="
if defined MANUAL set "MAN=/DManual=%MANUAL%"
"%ISCC%" /Q /DAppVer=%VER% /DStage=%B%\compose\binaries\main\app\DMRBridgeClient /DOutDir=%B%\setup %MAN% "%~dp0packaging\DMRBridgeClient.iss" || exit /b 1
echo Result: %B%\setup\DMRBridgeClient-%VER%-setup.exe
