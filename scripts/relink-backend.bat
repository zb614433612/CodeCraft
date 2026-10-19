@echo off
REM ============================================================
REM  Rebuild the backend JAR and swap it into the installed
REM  CodeCraft desktop app (which runs a frozen JAR from
REM  resources\backend\codecraft.jar).
REM
REM  Why: after changing anything under src/main/java, restarting
REM  the desktop client is NOT enough - it keeps launching the old
REM  JAR shipped with the installer.
REM
REM  Usage:  scripts\relink-backend.bat          (build + replace, backs up old jar)
REM          scripts\relink-backend.bat --dry    (build only, print paths)
REM
REM  NOTE: close CodeCraft completely (including tray icon) first.
REM  All messages are ASCII on purpose: cmd reads .bat in the OEM
REM  code page, so non-ASCII text gets mangled.
REM ============================================================
setlocal enabledelayedexpansion
cd /d "%~dp0\.."
set "PROJECT_ROOT=%cd%"
set "DRY=0"
if /i "%~1"=="--dry" set "DRY=1"

echo ============================================================
echo   CodeCraft backend JAR rebuild / relink
echo ============================================================
echo.

if "%DRY%"=="0" (
    tasklist /fi "imagename eq CodeCraft.exe" 2>nul | find /i "CodeCraft.exe" >nul
    if not errorlevel 1 (
        echo [ERROR] CodeCraft is running. Quit it completely ^(tray icon too^) and retry.
        exit /b 1
    )
)

echo [INFO] Step 1/3: building backend JAR ^(skip tests / skip frontend^)...
call mvn -q clean package -DskipTests -DskipFrontend=true
if errorlevel 1 (
    echo [ERROR] Maven build failed.
    exit /b 1
)

set "JAR_FILE="
for /f "delims=" %%f in ('dir /b /o-d "%PROJECT_ROOT%\target\*.jar" 2^>nul') do (
    if not defined JAR_FILE set "JAR_FILE=%PROJECT_ROOT%\target\%%f"
)
if not defined JAR_FILE (
    echo [ERROR] No jar found under target\.
    exit /b 1
)
echo [INFO] Built: "!JAR_FILE!"

set "INSTALL_JAR=%LOCALAPPDATA%\Programs\CodeCraft\resources\backend\codecraft.jar"
echo [INFO] Step 2/3: installed jar: "%INSTALL_JAR%"

if not exist "%INSTALL_JAR%" (
    echo [WARN] Installed jar not found ^(custom install path?^).
    echo        Copy "!JAR_FILE!" to the client's resources\backend\codecraft.jar manually.
    exit /b 0
)

if "%DRY%"=="1" (
    echo [INFO] --dry mode: nothing replaced.
    echo        To apply: copy /Y "!JAR_FILE!" "%INSTALL_JAR%"
    exit /b 0
)

echo [INFO] Step 3/3: backup + replace...
set "STAMP=%DATE:/=-%_%TIME::=-%"
set "STAMP=%STAMP: =0%"
copy /Y "%INSTALL_JAR%" "%INSTALL_JAR%.bak-%STAMP%" >nul
if errorlevel 1 (
    echo [ERROR] Failed to back up the old jar, nothing replaced.
    exit /b 1
)
copy /Y "!JAR_FILE!" "%INSTALL_JAR%" >nul
if errorlevel 1 (
    echo [ERROR] Copy failed ^(file still locked?^). Backup: %INSTALL_JAR%.bak-%STAMP%
    exit /b 1
)

echo.
echo ============================================================
echo   DONE - installed backend replaced
echo   backup: %INSTALL_JAR%.bak-%STAMP%
echo   next  : restart the CodeCraft client
echo ============================================================
endlocal
