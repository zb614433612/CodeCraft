@echo off
REM ============================================================
REM build.bat ?? CodeCraft ????????????? (Windows)
REM ?÷?: scripts\build.bat [?汾??]  (?汾?????????? pom.xml ???)
REM ============================================================
setlocal enabledelayedexpansion

cd /d "%~dp0\.."
set "PROJECT_ROOT=%cd%"
set "ELECTRON_DIR=%PROJECT_ROOT%\electron"
set "RELEASE_DIR=%ELECTRON_DIR%\release"

echo ============================================================
echo   CodeCraft ??????????
echo ============================================================

REM ==================== Step 0: ?汾?? ====================
if not "%~1"=="" (
    set "VERSION=%~1"
    REM ??? v ??
    if "!VERSION:~0,1!"=="v" set "VERSION=!VERSION:~1!"
    echo [INFO] ???????汾: !VERSION!
    REM ???? pom.xml ?汾??
    powershell -NoProfile -Command ^
        "$xml = [xml](Get-Content pom.xml);" ^
        "$xml.project.version = '!VERSION!';" ^
        "$xml.Save((Resolve-Path pom.xml).Path)"
) else (
    for /f "tokens=*" %%a in ('powershell -NoProfile -Command ^
        "$xml = [xml](Get-Content pom.xml); $xml.project.version"') do (
        set "VERSION=%%a"
        goto :got_version
    )
    :got_version
    echo [INFO] ?? pom.xml ????汾: !VERSION!
)

REM ==================== Step 1: ?汾??? ====================
echo.
echo [INFO] Step 1/6: ????汾??...
call scripts\sync-version.bat
if errorlevel 1 (
    echo [ERROR] 版本同步失败, 已中止打包。请检查 pom.xml 与 sync-version.bat。
    echo         否则可能把旧版 JAR 打进新安装包, 风险极高。
    exit /b 1
)

REM ==================== Step 2: Maven ???? ====================
echo.
echo [INFO] Step 2/6: Maven ??????? JAR...
call mvn clean package -DskipTests
if %errorlevel% neq 0 (
    echo [ERROR] Maven ????????
    exit /b 1
)
echo [INFO] Maven ???????

REM ??? JAR ????
set "JAR_FILE=%PROJECT_ROOT%\target\code-craft-%VERSION%.jar"
if not exist "%JAR_FILE%" (
    echo [ERROR] JAR ?????????: %JAR_FILE%
    exit /b 1
)
echo [INFO] JAR ????: %JAR_FILE%

REM ==================== Step 3: JRE ?ü? ====================
echo.
echo [INFO] Step 3/6: jlink ?ü????? JRE...

set "JRE_DIR=%ELECTRON_DIR%\jre"
if exist "%JRE_DIR%\bin\java.exe" (
    echo [INFO] ???? JRE ???????????ü?????????2ü?????? electron\jre ????
) else (
    if defined JAVA_HOME (
        set "JLINK=%JAVA_HOME%\bin\jlink.exe"
    ) else (
        set "JLINK=jlink.exe"
    )

    REM ??? jlink ??????
    where !JLINK! >nul 2>&1
    if %errorlevel% neq 0 (
        echo [WARN] jlink ?????????????ü?
        echo        ??? JDK 17+ ?????? JAVA_HOME
    ) else (
        echo [INFO] ????ü? JRE??? 30 ??...
        if exist "%JRE_DIR%" rmdir /s /q "%JRE_DIR%"
        "!JLINK!" ^
            --add-modules java.base,java.logging,java.sql,java.xml,java.naming,jdk.naming.dns,java.management,java.instrument,java.security.jgss,java.net.http,jdk.unsupported,java.scripting,java.compiler,java.desktop,jdk.crypto.cryptoki,jdk.security.auth,java.transaction.xa,java.rmi,java.management.rmi ^
              --strip-debug --compress 2 --no-header-files --no-man-pages ^
            --output "%JRE_DIR%"
        if %errorlevel% equ 0 (
            echo [INFO] JRE ?ü????
        ) else (
            echo [WARN] JRE ?ü??????????????????????????? JRE??
        )
    )
)

REM ==================== Step 4: ???????Windows ?? icon.ico?? ====================
echo.
echo [INFO] Step 4/6: Windows ??? icon.ico ????????????????????

REM ==================== Step 5: ??? Electron ???? ====================
echo.
echo [INFO] Step 5/6: ??? Electron ????...
cd /d "%ELECTRON_DIR%"
if not exist "node_modules" (
    echo [INFO] ??? Electron ?????????? 2 ?????...
    call npm install
)

REM ==================== Step 6: Electron ??? ====================
echo.
echo [INFO] Step 6/6: Electron ??? (Windows)...
call npm run dist:win
if %errorlevel% neq 0 (
    echo [ERROR] Electron ???????
    exit /b 1
)

REM ==================== ???? ====================
echo.
echo ============================================================
echo   ? ???????
echo ============================================================
echo.
if exist "%RELEASE_DIR%" (
    dir "%RELEASE_DIR%\*.exe" /b 2>nul
    dir "%RELEASE_DIR%\*.yml" /b 2>nul
    echo.
    echo ?汾: %VERSION%  ^|  ??: Windows
    echo.
    REM ???? MD5 (note: actual artifact is "CodeCraft Setup x.y.z.exe" with spaces)
    for %%f in ("%RELEASE_DIR%\CodeCraft*%VERSION%.exe") do (
        certutil -hashfile "%%f" MD5 | findstr /v ":" | findstr /v "^$"
    )
)
echo ============================================================

cd /d "%PROJECT_ROOT%"
endlocal
