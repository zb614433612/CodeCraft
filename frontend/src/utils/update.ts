/**
 * 自动更新前端封装（仅 Electron 桌面端可用；浏览器环境自动降级）。
 *
 * 主进程实现见 electron/update-manager.js：
 * - 检测：GitHub Releases（公开仓库，无需 token，6 小时缓存）
 * - 下载：国内镜像前缀 + 失败自动回退直连
 * - 安装：Windows 启动安装器（可静默）/ macOS 打开 dmg / Linux 定位文件
 * - 数据安全：数据目录在 userData/runtime，安装前额外备份到 userData/backups
 */

export interface UpdateAssetInfo {
  name: string
  size: number
  url: string
}

export interface UpdateCheckResult {
  currentVersion: string
  latestVersion: string
  latestName?: string
  hasUpdate: boolean
  notes?: string
  publishedAt?: string
  releaseUrl: string
  asset?: UpdateAssetInfo | null
  platform: string
  arch: string
  checkedAt?: number
}

export type UpdateDownloadStatus = 'idle' | 'downloading' | 'done' | 'error' | 'cancelled'

export interface UpdateDownloadState {
  status: UpdateDownloadStatus
  percent: number
  received: number
  total: number
  filePath: string
  fileName: string
  version: string
  error: string
}

export interface UpdateSettings {
  autoCheck: boolean
  useMirror: boolean
  mirrorPrefix: string
  maxBackups?: number
}

export interface UpdateStateSnapshot {
  currentVersion: string
  platform: string
  arch: string
  latest: UpdateCheckResult | null
  download: UpdateDownloadState
  settings: UpdateSettings
  lastCheckedAt: number
}

/** 获取主进程暴露的更新 API（浏览器环境返回 undefined） */
export function getUpdateApi (): any {
  return (window as any).electronAPI?.update
}

/** 当前是否支持自动更新（Electron 桌面端） */
export function isUpdateSupported (): boolean {
  return !!getUpdateApi()
}

/** 清理本地非数据库数据（重置所有数据后调用） */
export async function clearLocalAppData (): Promise<{ ok: boolean; removed: string[] } | null> {
  const fn = (window as any).electronAPI?.clearLocalData
  if (typeof fn !== 'function') return null
  try {
    return await fn()
  } catch (e) {
    console.warn('清理本地备份失败:', e)
    return null
  }
}

/** 按平台给出「安装更新」的交互文案 */
export function describeInstallAction (platform: string): { title: string; button: string; hint: string } {
  if (platform === 'win32') {
    return {
      title: '安装更新（Windows）',
      button: '立即安装并重启',
      hint: '点击后应用会退出，安装程序完成安装并自动启动新版本。'
    }
  }
  if (platform === 'darwin') {
    return {
      title: '安装更新（macOS）',
      button: '打开安装镜像',
      hint: '将自动打开 dmg 镜像，请把 CodeCraft 拖入「应用程序」完成替换（无 Apple 开发者证书，无法静默替换）。'
    }
  }
  return {
    title: '安装更新（Linux）',
    button: '定位安装包',
    hint: '已在文件管理器中定位安装包，请手动替换当前版本。'
  }
}
