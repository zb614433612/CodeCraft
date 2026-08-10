@echo off
REM ============================================================
REM sync-version.bat - Sync version from pom.xml to electron/package.json (Windows)
REM NOTE: keep this file pure ASCII (English comments only) to avoid
REM GBK/UTF-8 mixed-encoding garbled text in cmd console.
REM ============================================================
setlocal
cd /d "%~dp0\.."

REM -------------------- read version from pom.xml --------------------
for /f "tokens=*" %%a in ('powershell -NoProfile -Command ^
    "$xml = [xml](Get-Content -Path pom.xml -Encoding UTF8); $xml.project.version"') do (
    set "VERSION=%%a"
    goto :found_version
)
echo [ERROR] Failed to read version from pom.xml
exit /b 1

:found_version
echo [INFO] Version from pom.xml: %VERSION%

REM -------------------- update electron/package.json via Node --------------------
REM Use Node.js (always available in this Electron project) to keep the original
REM 2-space JSON formatting. PowerShell's ConvertTo-Json reformats the whole file
REM and escapes '&&' into \u0026\u0026, producing a huge polluted diff.
node -e "var fs=require('fs');var p='electron/package.json';var j=JSON.parse(fs.readFileSync(p,'utf8'));if(!j.build||!j.build.extraResources||!j.build.extraResources[0]){console.error('[ERROR] extraResources missing');process.exit(1)}j.version=process.argv[1];j.build.extraResources[0].from='../target/code-craft-'+process.argv[1]+'.jar';fs.writeFileSync(p,JSON.stringify(j,null,2)+'\n','utf8');console.log('[INFO] electron/package.json updated: version='+j.version)" %VERSION%
if errorlevel 1 (
    echo [ERROR] Failed to update electron/package.json - file NOT modified.
    exit /b 1
)

echo.
echo [INFO] Verify with: git diff electron/package.json
endlocal
