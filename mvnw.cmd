@echo off
setlocal enabledelayedexpansion

set "WRAPPER_VERSION=3.9.11"
set "DIST_NAME=apache-maven-%WRAPPER_VERSION%"
set "CACHE_DIR=%USERPROFILE%\.m2\wrapper\dists"
set "DIST_DIR=%CACHE_DIR%\%DIST_NAME%-bin"
set "MAVEN_HOME=%DIST_DIR%\%DIST_NAME%"
set "MAVEN_ZIP=%DIST_DIR%\%DIST_NAME%-bin.zip"
set "MAVEN_URL=https://repo.maven.apache.org/maven2/org/apache/maven/apache-maven/%WRAPPER_VERSION%/%DIST_NAME%-bin.zip"

if exist "%MAVEN_HOME%\bin\mvn.cmd" goto run

if not exist "%DIST_DIR%" mkdir "%DIST_DIR%" >nul 2>&1

if not exist "%MAVEN_ZIP%" (
  powershell -NoProfile -ExecutionPolicy Bypass -Command ^
    "$ProgressPreference = 'SilentlyContinue';" ^
    "Invoke-WebRequest -Uri '%MAVEN_URL%' -OutFile '%MAVEN_ZIP%';" ^
    "Expand-Archive -LiteralPath '%MAVEN_ZIP%' -DestinationPath '%DIST_DIR%' -Force"
  if errorlevel 1 exit /b %errorlevel%
)

if not exist "%MAVEN_HOME%\bin\mvn.cmd" (
  powershell -NoProfile -ExecutionPolicy Bypass -Command ^
    "$ProgressPreference = 'SilentlyContinue';" ^
    "Expand-Archive -LiteralPath '%MAVEN_ZIP%' -DestinationPath '%DIST_DIR%' -Force"
  if errorlevel 1 exit /b %errorlevel%
)

:run
"%MAVEN_HOME%\bin\mvn.cmd" %*
exit /b %errorlevel%
