const { contextBridge, ipcRenderer } = require('electron')

contextBridge.exposeInMainWorld('electronAPI', {
  selectDirectory: () => ipcRenderer.invoke('dialog:selectDirectory'),
  setTheme: (mode) => ipcRenderer.invoke('theme:setTheme', mode)
})
