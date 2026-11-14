/**
 * 系统壳能力（仅在 Electron 桌面端可用）
 * ----------------------------------------------------------------------------
 * preload.js 注入的 `window.electronAPI` 里，与本文件相关的是三个 IPC：
 *   · openPath(path)          用系统默认程序打开文件（VS Code / 系统编辑器，由文件关联决定）
 *   · showItemInFolder(path)  在资源管理器 / Finder 中定位并选中该文件
 *   · openExternal(url)       用系统默认浏览器打开链接
 *
 * 浏览器环境下这些能力不存在：调用方必须先用 {@link canOpenWithSystem} 判断，
 * 不要直接调 —— 网页里没有「用本地程序打开」这种概念，静默失败比报错更糟。
 *
 * 为什么不像别的工具那样散着写 `(window as any).electronAPI`：
 *   IPC 通道名和参数约定是主进程的契约（electron/main.js 的 shell:openPath / shell:showItemInFolder），
 *   集中一处后改通道名只需要改这里，调用方不用跟着搜。
 */

/** preload 暴露的系统壳 API 子集（其余能力由各模块自行声明） */
interface ElectronShellApi {
  openPath?: (targetPath: string) => Promise<boolean>
  showItemInFolder?: (targetPath: string) => Promise<boolean>
  openExternal?: (url: string) => Promise<boolean>
}

function getShellApi(): ElectronShellApi | undefined {
  if (typeof window === 'undefined') return undefined
  const api = (window as unknown as { electronAPI?: ElectronShellApi }).electronAPI
  // 只有 preload 真的注入了本地文件能力才算「桌面端」：
  // 旧版本客户端（没有这两个通道）调用会静默失败，不如让 UI 直接不显示按钮。
  return api && (api.openPath || api.showItemInFolder) ? api : undefined
}

/** 当前是否支持「用系统默认程序打开本地文件」 */
export function canOpenWithSystem(): boolean {
  return typeof getShellApi()?.openPath === 'function'
}

/** 当前是否支持「在文件夹中显示」 */
export function canRevealInFolder(): boolean {
  return typeof getShellApi()?.showItemInFolder === 'function'
}

/**
 * 用系统默认程序打开本地文件。
 * @returns true 表示已交给系统；false 表示不支持 / 文件不存在 / 没有关联程序
 */
export async function openPathWithSystem(targetPath: string): Promise<boolean> {
  const fn = getShellApi()?.openPath
  if (typeof fn !== 'function' || !targetPath) return false
  try {
    return await fn(targetPath)
  } catch {
    return false
  }
}

/**
 * 在系统文件管理器中定位文件。
 * @returns true 表示已发起定位
 */
export async function revealInFileManager(targetPath: string): Promise<boolean> {
  const fn = getShellApi()?.showItemInFolder
  if (typeof fn !== 'function' || !targetPath) return false
  try {
    return await fn(targetPath)
  } catch {
    return false
  }
}
