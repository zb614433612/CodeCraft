@echo off
REM ============================================================
REM sync-version.bat ?? ?·Ú???????? (Windows)
REM ?? pom.xml ????·Ú??????????? electron/package.json
REM ============================================================
setlocal enabledelayedexpansion

cd /d "%~dp0\.."

REM -------------------- ??? pom.xml ?·Ú?? --------------------
REM ??? PowerShell XML ???????????? project.version
for /f "tokens=*" %%a in ('powershell -NoProfile -Command ^
    "$xml = [xml](Get-Content pom.xml); $xml.project.version"') do (
    set "VERSION=%%a"
    goto :found_version
)

echo ? ????? pom.xml ????·Ú??
exit /b 1

:found_version
echo ?? ????·Ú: %VERSION%

REM -------------------- ???? package.json --------------------
REM ??t???¡¤?????????????????????
powershell -NoProfile -Command ^
    "$pkg = '%cd%\electron\package.json';" ^
    "$json = Get-Content $pkg -Raw | ConvertFrom-Json;" ^
    "$json.version = '%VERSION%';" ^
    "$json.build.extraResources[0].from = '../target/codecraft-%VERSION%.jar';" ^
    "$json | ConvertTo-Json -Depth 10 | Set-Content $pkg -Encoding UTF8;" ^
    "Write-Host '? package.json ?????: version=%VERSION%'"

echo.
echo   ???? git diff electron/package.json ?????
endlocal
