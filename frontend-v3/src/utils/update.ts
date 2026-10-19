/**
 * 自动更新前端封装（F06）——仅 Electron 桌面端可用，浏览器环境安全降级。
 * ----------------------------------------------------------------------------
 * 主进程实现见 `electron/update-manager.js`（v3 直接复用，未做任何改动）：
 *   - 检测：GitHub Releases（公开仓库，无需 token，6 小时缓存 + 手动点击 30s 最小间隔）
 *   - 下载：优先国内镜像前缀，失败自动回退 GitHub 直连
 *   - 安装：Windows 启动安装器（可静默）/ macOS 打开 dmg / Linux 在文件管理器定位
 *   - 数据安全：数据目录在 `userData/runtime`（安装器不触碰），安装前额外备份到 `userData/backups`
 *
 * <h3>为什么需要「两层版本号」</h3>
 * 桌面端：`app.getVersion()` 读的是 `electron/package.json` 的 version（安装包真实版本）；
 * 浏览器端：没有 Electron，只能读后端 `GET /api/system/version`（jar 里的版本）。
 * 两者在同一个发布包里应当一致，但升级过程中可能短暂不一致（只换了 jar 没重装客户端），
 * 所以界面把「客户端版本」和「后端版本」分开显示，不做强行合并。
 *
 * <h3>IPC 通道（由 preload.js 暴露为 window.electronAPI.update）</h3>
 * getState / check / download / cancelDownload / install / openReleasePage / getSettings / setSettings
 * 事件：onDownloadProgress（下载进度）、onAvailable（后台自动检查发现新版本）
 */

/** 安装包资产信息 */
export interface UpdateAssetInfo {
  name: string
  size: number
  url: string
}

/** 一次「检查更新」的结果（主进程 buildResult 的返回结构） */
export interface UpdateCheckResult {
  currentVersion: string
  latestVersion: string
  latestName?: string
  hasUpdate: boolean
  /** Release 正文（markdown，桌面端已做长度裁剪） */
  notes?: string
  publishedAt?: string
  releaseUrl: string
  asset?: UpdateAssetInfo | null
  platform: string
  arch: string
  checkedAt?: number
}

export type UpdateDownloadStatus = 'idle' | 'downloading' | 'done' | 'error' | 'cancelled'

/** 下载状态（主进程通过 update:download-progress 推送） */
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

/** 更新设置（持久化在主进程 userData/update-settings.json） */
export interface UpdateSettings {
  /** 启动 15s 后静默检查一次 */
  autoCheck: boolean
  /** 下载走国内镜像 */
  useMirror: boolean
  /** 镜像前缀（末尾自动补 /） */
  mirrorPrefix: string
  /** 升级前备份保留份数（1~10） */
  maxBackups?: number
}

/** 主进程 publicState() 的完整快照（渲染进程初始化用） */
export interface UpdateStateSnapshot {
  currentVersion: string
  platform: string
  arch: string
  latest: UpdateCheckResult | null
  download: UpdateDownloadState
  settings: UpdateSettings
  lastCheckedAt: number
}

/** 主进程更新 API 的最小类型（避免引入 any 满天飞） */
export interface UpdateApi {
  getState: () => Promise<UpdateStateSnapshot>
  check: (force?: boolean) => Promise<UpdateCheckResult>
  download: () => Promise<UpdateDownloadState>
  cancelDownload: () => Promise<UpdateDownloadState>
  install: (options?: { silent?: boolean }) => Promise<{ ok: boolean; action?: string; error?: string }>
  openReleasePage: () => Promise<boolean>
  getSettings: () => Promise<UpdateSettings>
  setSettings: (patch: Partial<UpdateSettings>) => Promise<UpdateSettings>
  onDownloadProgress: (cb: (state: UpdateDownloadState) => void) => () => void
  onAvailable: (cb: (result: UpdateCheckResult) => void) => () => void
}

/** 取主进程暴露的更新 API（浏览器环境返回 undefined） */
export function getUpdateApi (): UpdateApi | undefined {
  if (typeof window === 'undefined') return undefined
  const api = (window as unknown as { electronAPI?: { update?: UpdateApi } }).electronAPI
  return api?.update
}

/** 当前是否支持自动更新（Electron 桌面端） */
export function isUpdateSupported (): boolean {
  return Boolean(getUpdateApi())
}

/**
 * 清理本地「非数据库」数据（重置所有数据后调用）：
 * 升级前备份 / 已下载安装包 / 更新检测缓存。桌面端由主进程实现，浏览器环境返回 null。
 */
export async function clearLocalAppData (): Promise<{ ok: boolean; removed: string[] } | null> {
  if (typeof window === 'undefined') return null
  const fn = (window as unknown as { electronAPI?: { clearLocalData?: () => Promise<{ ok: boolean; removed: string[] }> } })
    .electronAPI?.clearLocalData
  if (typeof fn !== 'function') return null
  try {
    return await fn()
  } catch (e) {
    console.warn('[update] 清理本地备份失败:', e)
    return null
  }
}

/** 按平台给出「安装更新」的交互文案（各平台能力差异由主进程决定，这里只负责措辞） */
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

/** 平台展示名（win32 → Windows 等） */
export function platformLabel (platform: string): string {
  if (platform === 'win32') return 'Windows'
  if (platform === 'darwin') return 'macOS'
  if (platform === 'linux') return 'Linux'
  return platform || '未知平台'
}

/** 字节数 → 人类可读（安装包体积、已下载量） */
export function formatBytes (bytes?: number): string {
  const n = Number(bytes) || 0
  if (n <= 0) return '—'
  const units = ['B', 'KB', 'MB', 'GB']
  let value = n
  let i = 0
  while (value >= 1024 && i < units.length - 1) {
    value /= 1024
    i++
  }
  return `${value >= 100 || i === 0 ? Math.round(value) : value.toFixed(1)} ${units[i]}`
}

/** Release 发布时间：ISO → YYYY-MM-DD HH:mm（解析失败原样返回） */
export function formatReleaseTime (value?: string): string {
  if (!value) return ''
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return value
  const pad = (n: number) => String(n).padStart(2, '0')
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())} ${pad(date.getHours())}:${pad(date.getMinutes())}`
}
