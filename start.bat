@echo off
chcp 65001 >nul
title zb-agent 启动器

echo ========================================
echo   zb-agent 启动器
echo ========================================
echo.

REM 检查 Maven
set MAVEN_CMD=mvn
where mvn >nul 2>&1
if %ERRORLEVEL% neq 0 (
    if exist "E:\apache-maven-3.9.9\bin\mvn.cmd" (
        set MAVEN_CMD=E:\apache-maven-3.9.9\bin\mvn.cmd
    ) else (
        echo [错误] 找不到 Maven，请安装或修改 start.bat 中的路径
        pause
        exit /b 1
    )
)

REM ===== 第一步：编译后端 =====
echo [1/3] 编译后端代码...
call %MAVEN_CMD% compile -q
if %ERRORLEVEL% neq 0 (
    echo.
    echo [失败] 后端编译错误！请修复后再启动。
    echo       运行 mvn compile 查看详细错误
    echo.
    pause
    exit /b 1
)
echo [成功] 后端编译通过
echo.

REM ===== 第二步：启动后端 =====
echo [2/3] 启动后端服务...
start "zb-agent-backend" cmd /c "%MAVEN_CMD% spring-boot:run"
echo [启动中] 等待后端就绪...
timeout /t 15 /nobreak >nul
echo [成功] 后端已启动
echo.

REM ===== 第三步：启动前端（可选） =====
echo [3/3] 启动前端开发服务器...
if exist frontend\package.json (
    cd frontend
    start "zb-agent-frontend" cmd /c "npm run dev"
    cd ..
    echo [成功] 前端已启动
) else (
    echo [跳过] 前端目录不存在
)

echo.
echo ========================================
echo   所有服务已启动
echo   后端地址: http://localhost:8080
echo   前端地址: http://localhost:5173
echo ========================================
echo.
echo 按任意键关闭本窗口（服务将继续在后台运行）
pause >nul
