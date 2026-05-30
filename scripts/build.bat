@echo off
REM ============================================================
REM build.bat — CodeCraft 一键构建打包脚本 (Windows)
REM 用法: scripts\build.bat [版本号]  (版本号可选，默认从 pom.xml 读取)
REM ============================================================
setlocal enabledelayedexpansion

cd /d "%~dp0\.."
set "PROJECT_ROOT=%cd%"
set "ELECTRON_DIR=%PROJECT_ROOT%\electron"
set "RELEASE_DIR=%ELECTRON_DIR%\release"

echo ============================================================
echo   CodeCraft 一键构建打包
echo ============================================================

REM ==================== Step 0: 版本号 ====================
if not "%~1"=="" (
    set "VERSION=%~1"
    REM 去掉 v 前缀
    if "!VERSION:~0,1!"=="v" set "VERSION=!VERSION:~1!"
    echo [INFO] 使用指定版本: !VERSION!
    REM 更新 pom.xml 版本号
    powershell -NoProfile -Command ^
        "$xml = [xml](Get-Content pom.xml);" ^
        "$xml.project.version = '!VERSION!';" ^
        "$xml.Save((Resolve-Path pom.xml).Path)"
) else (
    for /f "tokens=2 delims=<>" %%a in ('findstr /r "<version>[0-9].*</version>" pom.xml') do (
        set "VERSION=%%a"
        goto :got_version
    )
    :got_version
    echo [INFO] 从 pom.xml 读取版本: !VERSION!
)

REM ==================== Step 1: 版本同步 ====================
echo.
echo [INFO] Step 1/6: 同步版本号...
call scripts\sync-version.bat

REM ==================== Step 2: Maven 构建 ====================
echo.
echo [INFO] Step 2/6: Maven 构建后端 JAR...
call mvn clean package -DskipTests
if %errorlevel% neq 0 (
    echo [ERROR] Maven 构建失败！
    exit /b 1
)
echo [INFO] Maven 构建完成

REM 确认 JAR 存在
set "JAR_FILE=%PROJECT_ROOT%\target\codecraft-%VERSION%.jar"
if not exist "%JAR_FILE%" (
    echo [ERROR] JAR 文件不存在: %JAR_FILE%
    exit /b 1
)
echo [INFO] JAR 产物: %JAR_FILE%

REM ==================== Step 3: JRE 裁剪 ====================
echo.
echo [INFO] Step 3/6: jlink 裁剪内置 JRE...

set "JRE_DIR=%ELECTRON_DIR%\jre"
if exist "%JRE_DIR%\bin\java.exe" (
    echo [INFO] 内置 JRE 已存在，跳过裁剪（如需重新裁剪请删除 electron\jre 目录）
) else (
    if defined JAVA_HOME (
        set "JLINK=%JAVA_HOME%\bin\jlink.exe"
    ) else (
        set "JLINK=jlink.exe"
    )

    REM 检查 jlink 是否可用
    where !JLINK! >nul 2>&1
    if %errorlevel% neq 0 (
        echo [WARN] jlink 不可用，跳过热裁剪
        echo        请安装 JDK 17+ 并设置 JAVA_HOME
    ) else (
        echo [INFO] 正在裁剪 JRE（约 30 秒）...
        if exist "%JRE_DIR%" rmdir /s /q "%JRE_DIR%"
        "!JLINK!" ^
            --add-modules java.base,java.logging,java.sql,java.xml,java.naming,java.management,java.instrument,java.security.jgss,java.net.http,jdk.unsupported,java.scripting,java.compiler,java.desktop,jdk.crypto.cryptoki,jdk.security.auth,java.transaction.xa,java.rmi,java.management.rmi ^
            --strip-debug --compress 2 --no-header-files --no-man-pages ^
            --output "%JRE_DIR%"
        if %errorlevel% equ 0 (
            echo [INFO] JRE 裁剪完成
        ) else (
            echo [WARN] JRE 裁剪失败，将继续打包（可能缺少内置 JRE）
        )
    )
)

REM ==================== Step 4: 跳过图标（Windows 有 icon.ico） ====================
echo.
echo [INFO] Step 4/6: Windows 图标 icon.ico 使用现有文件，跳过生成

REM ==================== Step 5: 安装 Electron 依赖 ====================
echo.
echo [INFO] Step 5/6: 检查 Electron 依赖...
cd /d "%ELECTRON_DIR%"
if not exist "node_modules" (
    echo [INFO] 安装 Electron 依赖（首次约 2 分钟）...
    call npm install
)

REM ==================== Step 6: Electron 打包 ====================
echo.
echo [INFO] Step 6/6: Electron 打包 (Windows)...
call npm run dist:win
if %errorlevel% neq 0 (
    echo [ERROR] Electron 打包失败！
    exit /b 1
)

REM ==================== 汇总 ====================
echo.
echo ============================================================
echo   ✅ 打包完成！
echo ============================================================
echo.
if exist "%RELEASE_DIR%" (
    dir "%RELEASE_DIR%\*.exe" /b 2>nul
    dir "%RELEASE_DIR%\*.yml" /b 2>nul
    echo.
    echo 版本: %VERSION%  ^|  平台: Windows
    echo.
    REM 计算 MD5
    for %%f in ("%RELEASE_DIR%\CodeCraft-Setup-%VERSION%.exe") do (
        certutil -hashfile "%%f" MD5 | findstr /v ":" | findstr /v "^$"
    )
)
echo ============================================================

cd /d "%PROJECT_ROOT%"
endlocal
