@echo off
setlocal EnableExtensions

REM MERV Java CLI launcher — requires Java 17+
REM Layout (project root):
REM   .\merv-client.bat
REM   .\lib\merv-client.jar
REM Auto-downloads the JAR into .\lib\ when missing.

set "SCRIPT_DIR=%~dp0"
set "SCRIPT_DIR=%SCRIPT_DIR:~0,-1%"
set "LIB_DIR=%SCRIPT_DIR%\lib"
set "JAR=%LIB_DIR%\merv-client.jar"

set "DOWNLOAD_BASE=%MERV_CLIENT_DOWNLOAD_URL%"
if "%DOWNLOAD_BASE%"=="" set "DOWNLOAD_BASE=https://merv.online/downloads"

if exist "%JAR%" goto run

echo [merv-client] merv-client.jar not found.
echo [merv-client] Downloading to %JAR% ...
echo [merv-client] URL: %DOWNLOAD_BASE%/merv-client.jar

if not exist "%LIB_DIR%" mkdir "%LIB_DIR%"
if not exist "%LIB_DIR%" (
  echo [merv-client] Could not create %LIB_DIR%
  exit /b 1
)

powershell -NoProfile -ExecutionPolicy Bypass -Command ^
  "$url='%DOWNLOAD_BASE%/merv-client.jar'; $out='%JAR%';" ^
  "try { Invoke-WebRequest -Uri $url -OutFile ($out + '.download') -UseBasicParsing; Move-Item -Force ($out + '.download') $out } catch { Write-Error $_; exit 1 }"
if errorlevel 1 (
  echo [merv-client] Download failed. See https://merv.online/merv-client-download.html
  exit /b 1
)
echo [merv-client] Saved to %JAR%

if not exist "%JAR%" (
  echo [merv-client] JAR not found at %JAR%
  echo [merv-client] Place merv-client.bat in your project root with a lib\ folder.
  exit /b 1
)

:run
if defined JAVA_HOME (
  set "JAVA_BIN=%JAVA_HOME%\bin\java.exe"
) else (
  set "JAVA_BIN=java.exe"
)

"%JAVA_BIN%" -jar "%JAR%" %*
exit /b %ERRORLEVEL%
