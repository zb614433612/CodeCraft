; ============================================================
; CodeCraft 安装包自定义脚本（NSIS）
;
; 职责一：覆盖安装前结束残留的 CodeCraft 进程（3.0.0 实测踩坑）
;   NSIS 只管「按进程名结束 CodeCraft.exe」，既不会主动结束它 spawn 出来的 java 子进程，
;   也拦不住被强杀后残留的孤儿 java。这两类进程都持有
;   <安装目录>\resources\backend\codecraft.jar（以及 app.asar）的文件句柄。若不先结束它们：
;     ① NSIS 覆盖写 codecraft.jar 失败，而且失败是**静默的** —— 照常弹「安装完成」，
;        但注册表版本、文件时间、jar 内容全是旧的（用户以为装上了，其实没装）；
;     ② 残留 java 继续占着 8084 与 H2 文件锁 → 新客户端启动时新 java 卡住不退出
;        → 界面报「后端服务启动失败：后端服务启动超时」。
;   所以这里按「CodeCraft.exe 优雅退出 → CodeCraft.exe 强杀 → 孤儿 java 兜底」三步清理；
;   java 侧只精确匹配「命令行含 codecraft.jar」的进程，绝不用 taskkill /IM java.exe，
;   以免误杀用户机器上的 IDEA 等其它 JVM 进程。
;
; 职责二：首次从「数据存放在安装目录」的旧版本升级时，避免覆盖安装删除用户数据
;   背景：
;   - 2.0.2 及更早版本：后端 cwd = <安装目录>\resources\backend，
;     H2 数据库/快照/日志都在该目录内；覆盖安装时旧版卸载程序会删除这些文件。
;   - 本版本起：后端 cwd 已改为 %APPDATA%\CodeCraft\runtime（安装器不会触碰）。
;   因此安装程序在**初始化阶段（customInit，早于卸载旧版本）**把默认安装路径下的
;   数据复制到 %APPDATA%\CodeCraft\legacy-backup；新版本首次启动时自动恢复到 runtime。
;   限制：若用户把应用安装到了自定义目录，$LOCALAPPDATA\Programs\CodeCraft 下找不到数据，
;        此处无法备份 —— 设置页已提示这类用户升级前先「导出备份」或手动复制 data 目录。
; ============================================================

!macro customInit
  ; ── 1) 结束残留的 CodeCraft 进程（释放 codecraft.jar / app.asar 文件句柄）──
  ; 三步缺一不可，顺序也不能换：
  ;   ① 先「优雅」结束 CodeCraft.exe（不带 /F）→ 触发 Electron 的 before-quit
  ;      → stopBackend() 正常停掉后端 java，内存数据落盘、端口与 jar 句柄一并释放；
  ;   ② 若 2 秒内没退干净（进程卡死、或上次是被强杀的），再 /F 强杀主进程；
  ;   ③ 最后兜底扫掉「命令行含 codecraft.jar」的 java.exe —— 强制退出留下的孤儿 java
  ;      不认 CodeCraft.exe 的父子关系，①② 两步根本管不到它们。
  ; 安装器自身进程名是 CodeCraft.Setup.x.y.z.exe，不会匹配到 CodeCraft.exe，安全。
  DetailPrint "正在结束残留的 CodeCraft 进程 ..."
  nsExec::ExecToLog 'taskkill /IM CodeCraft.exe'
  Pop $0
  Sleep 2000
  nsExec::ExecToLog 'taskkill /IM CodeCraft.exe /F'
  Pop $0
  Sleep 1000

  ; 只精确匹配命令行含 codecraft.jar 的 java.exe，绝不用 taskkill /IM java.exe，
  ; 以免误杀用户机器上的 IDEA 等其它 JVM 进程。
  ; PowerShell 命令里的 NSIS 变量符 $ 需写成 $$；整串用反引号包裹，内部单双引号无需转义。
  DetailPrint "正在清理残留的 CodeCraft 后端 java 进程 ..."
  nsExec::ExecToLog `powershell -NoProfile -ExecutionPolicy Bypass -WindowStyle Hidden -Command "Get-CimInstance Win32_Process | Where-Object { $$_.Name -eq 'java.exe' -and $$_.CommandLine -like '*codecraft.jar*' } | ForEach-Object { Stop-Process -Id $$_.ProcessId -Force }"`
  Pop $0
  ; 给进程退出留出时间，确保 jar 文件句柄已释放
  Sleep 1200

  ; ── 2) 备份旧版本数据（数据曾存放在安装目录的版本：2.0.2 及更早）──
  ; electron-builder 默认 per-user 安装路径
  StrCpy $R9 "$LOCALAPPDATA\Programs\CodeCraft"

  ; 备份目标必须与运行时读取的目录一致：
  ;   Electron app.getPath('userData') = %APPDATA%\<package.json 的 name> = codecraft-desktop
  ; 早期版本误写成 productName 目录 %APPDATA%\CodeCraft，导致 main.js 的 resolveBackendWorkDir
  ; 找不到备份 → 2.0.2 用户升级后看起来「数据全丢了」。
  ; 现统一写到 codecraft-desktop；main.js 仍兼容读取旧的 CodeCraft 目录，救回已备份的数据。
  StrCpy $R8 "$APPDATA\codecraft-desktop"

  ${If} ${FileExists} "$R9\resources\backend\data\codecraft.mv.db"
    DetailPrint "检测到旧版本数据，正在备份到 $R8\legacy-backup ..."
    CreateDirectory "$R8"
    ; 用 xcopy 递归复制（NSIS 自带 CopyFiles 不支持子目录）
    ExecWait '"$SYSDIR\cmd.exe" /c xcopy /E /I /Y "$R9\resources\backend\data" "$R8\legacy-backup\data"'

    ${If} ${FileExists} "$R9\resources\backend\snapshots\*.*"
      DetailPrint "正在备份代码回滚快照 ..."
      ExecWait '"$SYSDIR\cmd.exe" /c xcopy /E /I /Y "$R9\resources\backend\snapshots" "$R8\legacy-backup\snapshots"'
    ${EndIf}

    ${If} ${FileExists} "$R9\resources\backend\logs\*.*"
      DetailPrint "正在备份后端日志 ..."
      ExecWait '"$SYSDIR\cmd.exe" /c xcopy /E /I /Y "$R9\resources\backend\logs" "$R8\legacy-backup\logs"'
    ${EndIf}

    DetailPrint "旧版本数据备份完成（新版本首次启动会自动恢复）"
  ${EndIf}
!macroend
