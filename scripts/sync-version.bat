@echo off
REM ============================================================
REM sync-version.bat — 版本号同步脚本 (Windows)
REM 从 pom.xml 提取版本号，自动同步到 electron/package.json
REM ============================================================
setlocal enabledelayedexpansion

cd /d "%~dp0\.."

REM -------------------- 提取 pom.xml 版本号 --------------------
REM 使用 PowerShell XML 解析，精确提取 project.version
for /f "tokens=*" %%a in ('powershell -NoProfile -Command ^
    "$xml = [xml](Get-Content pom.xml); $xml.project.version"') do (
    set "VERSION=%%a"
    goto :found_version
)

echo ❌ 无法从 pom.xml 提取版本号
exit /b 1

:found_version
echo 📦 当前版本: %VERSION%

REM -------------------- 更新 package.json --------------------
REM 使用绝对路径，避免依赖当前工作目录
powershell -NoProfile -Command ^
    "$pkg = '%cd%\electron\package.json';" ^
    "$json = Get-Content $pkg -Raw | ConvertFrom-Json;" ^
    "$json.version = '%VERSION%';" ^
    "$json.build.extraResources[0].from = '../target/codecraft-%VERSION%.jar';" ^
    "$json | ConvertTo-Json -Depth 10 | Set-Content $pkg -Encoding UTF8;" ^
    "Write-Host '✅ package.json 已同步: version=%VERSION%'"

echo.
echo   建议 git diff electron/package.json 确认变更
endlocal
