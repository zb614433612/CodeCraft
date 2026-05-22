@echo off
chcp 65001 >nul
echo ========================================
echo   CodeCraft 桌面应用启动器
echo ========================================
echo.

REM 检查 JAR 是否存在
if not exist "..\target\codecraft-0.0.1-SNAPSHOT.jar" (
    echo [错误] 未找到后端 JAR 文件！
    echo 请先执行: mvn clean package -DskipTests
    pause
    exit /b 1
)

REM 检查 Electron 是否安装
if not exist "node_modules\.bin\electron.cmd" (
    echo [信息] 正在安装 Electron...
    npm install
)

echo [信息] 启动桌面应用...
npx electron .
