@echo off
chcp 65001 >nul
setlocal
title CodeCraft 启动修复（结束残留进程）

echo ============================================================
echo  CodeCraft 启动修复工具
echo ------------------------------------------------------------
echo  作用：结束残留的 CodeCraft 窗口进程与后端 java 进程
echo        让新安装包能够真正覆盖 resources\backend\codecraft.jar
echo  说明：只结束「命令行含 codecraft.jar」的 java，
echo        不会影响 IDEA 等其它 JVM 程序
echo ============================================================
echo.

echo [1/3] 结束 CodeCraft 窗口进程 ...
tasklist /FI "IMAGENAME eq CodeCraft.exe" | findstr /I "CodeCraft.exe" >nul
if errorlevel 1 (
  echo     - 没有正在运行的 CodeCraft.exe
) else (
  taskkill /F /IM CodeCraft.exe >nul 2>&1
  echo     - 已结束 CodeCraft.exe
)
echo.

echo [2/3] 结束残留的后端 java 进程 ...
powershell -NoProfile -ExecutionPolicy Bypass -Command "$found = $false; Get-CimInstance Win32_Process | Where-Object { $_.Name -eq 'java.exe' -and $_.CommandLine -like '*codecraft.jar*' } | ForEach-Object { $found = $true; Write-Host ('     - 结束 java.exe PID ' + $_.ProcessId); Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }; if (-not $found) { Write-Host '     - 没有残留的后端 java 进程' }"
echo.

echo [3/3] 检查 8084 端口 ...
netstat -ano -p tcp | findstr ":8084" | findstr "LISTENING" >nul
if errorlevel 1 (
  echo     - 端口 8084 已释放，可以重新安装 / 启动
) else (
  echo     - 端口 8084 仍被占用，占用情况如下：
  netstat -ano -p tcp | findstr ":8084" | findstr "LISTENING"
  echo       请用任务管理器按上面的 PID 结束对应进程后再试
)
echo.

echo ============================================================
echo  完成。接下来：
echo    1) 重新运行最新安装包（CodeCraft.Setup.x.y.z.exe）完成安装
echo    2) 启动 CodeCraft
echo  若仍提示启动超时，请看日志：
echo    %%APPDATA%%\codecraft-desktop\runtime\logs\app.log
echo ============================================================
echo.
pause
