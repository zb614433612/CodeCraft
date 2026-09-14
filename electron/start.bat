@echo off
chcp 65001 >nul
echo ========================================
echo   CodeCraft 桌面应用启动器
echo ========================================
echo.

REM 检查 JAR 是否存在（动态扫描 target 下最新的 code-craft-*.jar，与 main.js 逻辑一致，避免版本号写死漂移）
set "JAR_FILE="
for /f "delims=" %%f in ('dir /b /o-d "..\target\code-craft-*.jar" 2^>nul') do (
    if not defined JAR_FILE set "JAR_FILE=..\target\%%f"
)
if not defined JAR_FILE (
    echo [错误] 未找到后端 JAR 文件！
    echo 请先执行: mvn clean package -DskipTests
    pause
    exit /b 1
)
echo [信息] 检测到后端 JAR: %JAR_FILE%

REM 检查 Electron 是否安装
if not exist "node_modules\.bin\electron.cmd" (
    echo [信息] 正在安装 Electron...
    npm install
)

echo [信息] 启动桌面应用...
npx electron .
