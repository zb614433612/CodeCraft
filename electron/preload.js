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
  }
})
