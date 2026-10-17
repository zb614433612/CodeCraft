; ============================================================
; CodeCraft 安装包自定义脚本（NSIS）
;
; 目的：**首次从「数据存放在安装目录」的旧版本升级时，避免覆盖安装删除用户数据。**
;
; 背景：
; - 2.0.2 及更早版本：后端 cwd = <安装目录>\resources\backend，
;   H2 数据库/快照/日志都在该目录内；覆盖安装时旧版卸载程序会删除这些文件。
; - 本版本起：后端 cwd 已改为 %APPDATA%\CodeCraft\runtime（安装器不会触碰）。
;
; 因此安装程序在**初始化阶段（customInit，早于卸载旧版本）**把默认安装路径下的
; 数据复制到 %APPDATA%\CodeCraft\legacy-backup；新版本首次启动时自动恢复到 runtime。
;
; 限制：若用户把应用安装到了自定义目录，$LOCALAPPDATA\Programs\CodeCraft 下找不到数据，
;      此处无法备份 —— 设置页已提示这类用户升级前先「导出备份」或手动复制 data 目录。
; ============================================================

!macro customInit
  ; electron-builder 默认 per-user 安装路径
  StrCpy $R9 "$LOCALAPPDATA\Programs\CodeCraft"

  ${If} ${FileExists} "$R9\resources\backend\data\codecraft.mv.db"
    DetailPrint "检测到旧版本数据，正在备份到 $APPDATA\CodeCraft\legacy-backup ..."
    CreateDirectory "$APPDATA\CodeCraft"
    ; 用 xcopy 递归复制（NSIS 自带 CopyFiles 不支持子目录）
    ExecWait '"$SYSDIR\cmd.exe" /c xcopy /E /I /Y "$R9\resources\backend\data" "$APPDATA\CodeCraft\legacy-backup\data"'

    ${If} ${FileExists} "$R9\resources\backend\snapshots\*.*"
      DetailPrint "正在备份代码回滚快照 ..."
      ExecWait '"$SYSDIR\cmd.exe" /c xcopy /E /I /Y "$R9\resources\backend\snapshots" "$APPDATA\CodeCraft\legacy-backup\snapshots"'
    ${EndIf}

    ${If} ${FileExists} "$R9\resources\backend\logs\*.*"
      ExecWait '"$SYSDIR\cmd.exe" /c xcopy /E /I /Y "$R9\resources\backend\logs" "$APPDATA\CodeCraft\legacy-backup\logs"'
    ${EndIf}

    DetailPrint "旧版本数据备份完成（新版本首次启动会自动恢复）"
  ${EndIf}
!macroend
