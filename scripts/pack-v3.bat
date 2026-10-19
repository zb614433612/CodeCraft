@echo off
REM ============================================================================
REM  Build the v3 frontend (frontend-v3) into the backend resource folder.
REM  ---------------------------------------------------------------------------
REM  Usage:
REM    scripts\pack-v3.bat              install deps if missing + typecheck + build
REM    scripts\pack-v3.bat --skip-check skip typecheck (quick build only)
REM
REM  Output:
REM    src\main\resources\v3-static\    served at  http://localhost:8084/v3/
REM
REM  Notes:
REM    * vite.config.ts already points outDir at resources\v3-static, no copy step.
REM    * v3 output is deliberately OUTSIDE src\main\resources\static\ : the classic
REM      frontend (frontend\) builds into static\ with emptyOutDir=true, which wipes
REM      the whole folder on every build. Putting v3 there means "rebuild classic ->
REM      v3 disappears".
REM    * /v3/** is mapped by SpaConfig (ResourceHandler) to classpath:/v3-static/.
REM    * restart the backend (or run scripts\relink-backend.bat) after packing,
REM      otherwise the running jar still serves the old resources.
REM ============================================================================
setlocal enabledelayedexpansion
chcp 65001 >nul

set "SCRIPT_DIR=%~dp0"
for %%i in ("%SCRIPT_DIR%..") do set "ROOT=%%~fi"
set "V3=%ROOT%\frontend-v3"

if not exist "%V3%\package.json" (
  echo [ERROR] frontend-v3 not found at: %V3%
  exit /b 1
)

cd /d "%V3%" || exit /b 1

echo [1/3] Dependencies
if not exist "node_modules" (
  echo       installing...
  call npm install --no-audit --no-fund || goto :failed
) else (
  echo       node_modules present, skipped.
)

if /I "%~1"=="--skip-check" (
  echo [2/3] Typecheck skipped by flag.
) else (
  echo [2/3] Typecheck
  call npm run typecheck || goto :failed
)

echo [3/3] Build
call npm run build || goto :failed

echo.
echo ============================================================================
echo  Done.
echo  Output : %ROOT%\src\main\resources\v3-static
echo  Entry  : /v3/    (classic version stays at /)
echo  Next   : restart backend or run scripts\relink-backend.bat to refresh jar
echo ============================================================================
endlocal
exit /b 0

:failed
echo.
echo [FAILED] build aborted, see the output above.
endlocal
exit /b 1
