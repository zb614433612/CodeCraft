const { contextBridge, ipcRenderer } = require('electron')

contextBridge.exposeInMainWorld('electronAPI', {
  selectDirectory: () => ipcRenderer.invoke('dialog:selectDirectory'),
  setTheme: (mode) => ipcRenderer.invoke('theme:setTheme', mode),
  // 设置窗口整体透明度（0.2~1），用于「外观设置」中的整体透明度调节
  setWindowOpacity: (value) => ipcRenderer.invoke('window:setOpacity', value),
  // 用系统默认浏览器打开外部链接（网页标签页的「在浏览器中打开」按钮）
  openExternal: (url) => ipcRenderer.invoke('shell:openExternal', url),
  // 监听启动进度（启动画面 loading.html 专用）：
  // 回调接收 { percent, message, error, done }；返回取消监听函数
  onStartupProgress: (callback) => {
    const listener = (_event, data) => callback(data)
    ipcRenderer.on('startup-progress', listener)
    return () => ipcRenderer.removeListener('startup-progress', listener)
  },

  // ==================== 自动更新（实现见 electron/update-manager.js） ====================
  // 检测：GitHub Releases（公开仓库，无需 token）
  // 下载：支持国内镜像前缀 + 失败自动回退直连
  // 安装：Windows 启动安装器（可静默）/ macOS 打开 dmg / Linux 定位文件
  update: {
    /** 当前版本、最新版本、下载状态、本地设置（渲染进程初始化用） */
    getState: () => ipcRenderer.invoke('update:getState'),
    /** 检查更新；force=true 表示用户手动点击（仍受 30s 最小间隔保护） */
    check: (force) => ipcRenderer.invoke('update:check', !!force),
    /** 开始下载安装包（已有完整文件会直接返回完成态） */
    download: () => ipcRenderer.invoke('update:download'),
    /** 取消下载 */
    cancelDownload: () => ipcRenderer.invoke('update:cancelDownload'),
    /** 执行安装：{ silent: true } 走静默安装（仅 Windows 生效） */
    install: (options) => ipcRenderer.invoke('update:install', options || {}),
    /** 打开发布页（浏览器兜底） */
    openReleasePage: () => ipcRenderer.invoke('update:openReleasePage'),
    getSettings: () => ipcRenderer.invoke('update:getSettings'),
    setSettings: (patch) => ipcRenderer.invoke('update:setSettings', patch),
    /** 监听下载进度；返回取消监听函数 */
    onDownloadProgress: (callback) => {
      const listener = (_event, data) => callback(data)
      ipcRenderer.on('update:download-progress', listener)
      return () => ipcRenderer.removeListener('update:download-progress', listener)
    },
    /** 监听「自动检查发现新版本」；返回取消监听函数 */
    onAvailable: (callback) => {
      const listener = (_event, data) => callback(data)
      ipcRenderer.on('update:available', listener)
      return () => ipcRenderer.removeListener('update:available', listener)
    }
  },

  /**
   * 清理本地非数据库数据（升级备份 / 已下载安装包 / 更新检测缓存）。
   * 「重置所有数据」成功后调用，确保"清空"是彻底清空（备份里不会残留数据库副本）。
   */
  clearLocalData: () => ipcRenderer.invoke('app:clearLocalData')
})
