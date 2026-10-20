'use strict'

/**
 * CodeCraft 自动更新管理器（主进程模块）
 *
 * 设计目标：
 * 1) **页面内升级**：检测 GitHub Releases → 页面内带进度下载 → 一键安装/打开安装镜像；
 * 2) **零发布流程改造**：直接读 GitHub Releases 资产，不依赖 latest.yml（不要求改 CI、不要求代码签名）；
 * 3) **国内加速 + 失败回退**：下载优先走可配置的镜像前缀，失败自动回退 GitHub 直连；
 * 4) **用户数据无损**：升级前把 userData/runtime 下的数据库备份到 userData/backups（数据目录本身
 *    已在 userData，安装器不会触碰；备份是双保险）。
 *
 * 平台差异（无 Apple 开发者证书的客观限制）：
 * - Windows：下载 Setup.exe → 启动安装器（可静默）→ 退出应用 → 安装器拉起新版；
 * - macOS：下载 dmg → 自动 open 打开镜像 → 用户拖拽替换（Gatekeeper 限制，无法静默替换）；
 * - Linux：下载 AppImage → 在文件管理器中定位，提示用户替换。
 *
 * IPC 通道：
 * - update:getState / update:check / update:download / update:cancelDownload
 * - update:install / update:openReleasePage / update:getSettings / update:setSettings
 * 事件：
 * - update:download-progress（下载进度）
 * - update:available（自动检查发现新版本）
 */

const { app, ipcMain, shell, net, BrowserWindow } = require('electron')
const fs = require('fs')
const path = require('path')
const { spawn } = require('child_process')

/** GitHub 仓库（公开仓库，无需 token） */
const REPO = 'zb614433612/CodeCraft'
const RELEASES_PAGE = `https://github.com/${REPO}/releases`
const API_LATEST = `https://api.github.com/repos/${REPO}/releases/latest`

/** 检测结果缓存时长（6 小时）：GitHub 未认证 API 限流 60 次/小时，必须缓存 */
const CACHE_TTL_MS = 6 * 60 * 60 * 1000
/** 手动点击「检查更新」的最小间隔（防连点打爆限流） */
const FORCE_MIN_INTERVAL_MS = 30 * 1000
/** 启动后延迟自动检查（避免拖慢启动） */
const AUTO_CHECK_DELAY_MS = 15 * 1000
/** 默认国内加速前缀（可在设置中修改；失败自动回退直连） */
const DEFAULT_MIRROR_PREFIX = 'https://ghproxy.net/'

/** 主窗口获取器（由 main.js 注入） */
let mainWindowGetter = () => null
/** 安装前钩子（由 main.js 注入：停止后端进程，释放数据库/日志文件句柄） */
let beforeInstallHook = null

/** 用户设置 */
let settings = null
/** 检测缓存 */
let cache = null
/** 最近一次检测到的 release 信息 */
let latestInfo = null
/** 最近一次检测时间戳 */
let lastCheckedAt = 0
/** 是否正在检查（防并发） */
let checking = false
/** 当前待下载资产（由检测结果确定） */
let pendingAsset = null
/** 进行中的下载请求（用于取消） */
let currentRequest = null

/** 下载状态（推送给渲染进程） */
let downloadState = {
  status: 'idle',      // idle | downloading | done | error | cancelled
  percent: 0,
  received: 0,
  total: 0,
  filePath: '',
  fileName: '',
  version: '',
  error: ''
}

// ==================== 路径 / 设置 ====================

const settingsFile = () => path.join(app.getPath('userData'), 'update-settings.json')
const cacheFile = () => path.join(app.getPath('userData'), 'update-cache.json')
const downloadDir = () => path.join(app.getPath('temp'), 'codecraft-updates')
/** 与 main.js 的 resolveBackendWorkDir 保持一致：后端数据目录 */
const runtimeDir = () => path.join(app.getPath('userData'), 'runtime')
const backupRoot = () => path.join(app.getPath('userData'), 'backups')

function loadJson (file, fallback) {
  try {
    if (!fs.existsSync(file)) return fallback
    return JSON.parse(fs.readFileSync(file, 'utf8'))
  } catch (e) {
    console.warn('[update] 读取配置失败，使用默认值:', file, e.message)
    return fallback
  }
}

function saveJson (file, data) {
  try {
    fs.mkdirSync(path.dirname(file), { recursive: true })
    fs.writeFileSync(file, JSON.stringify(data, null, 2), 'utf8')
  } catch (e) {
    console.warn('[update] 写入配置失败:', file, e.message)
  }
}

function sanitizeSettings (raw) {
  const src = raw || {}
  const prefix = typeof src.mirrorPrefix === 'string' && src.mirrorPrefix.trim()
    ? src.mirrorPrefix.trim()
    : DEFAULT_MIRROR_PREFIX
  const maxBackups = Math.min(10, Math.max(1, Number(src.maxBackups) || 3))
  return {
    autoCheck: src.autoCheck !== false,
    useMirror: src.useMirror !== false,
    mirrorPrefix: prefix.endsWith('/') ? prefix : prefix + '/',
    maxBackups
  }
}

function loadSettings () {
  return sanitizeSettings(loadJson(settingsFile(), {}))
}

// ==================== 版本比较 / 资产选择（纯逻辑见 update-utils.js，可单测） ====================
const { normalizeVersion, compareVersion, pickAsset: pickAssetByPlatform } = require('./update-utils')

/** 按当前运行平台/架构挑选安装包 */
function pickAsset (assets) {
  return pickAssetByPlatform(assets, process.platform, process.arch)
}

// ==================== 网络（走 Electron net，自动使用系统代理） ====================

function httpGetJson (url) {
  return new Promise((resolve, reject) => {
    let settled = false
    const done = (fn, arg) => {
      if (settled) return
      settled = true
      fn(arg)
    }
    let request
    try {
      request = net.request({ method: 'GET', url, redirect: 'follow' })
    } catch (e) {
      return reject(e)
    }
    const timer = setTimeout(() => {
      try { request.abort() } catch (_) { /* ignore */ }
      done(reject, new Error('请求 GitHub 超时（15s）'))
    }, 15000)

    request.setHeader('User-Agent', 'CodeCraft-Updater')
    request.setHeader('Accept', 'application/vnd.github+json')

    request.on('response', (response) => {
      const chunks = []
      response.on('data', c => chunks.push(c))
      response.on('error', (e) => { clearTimeout(timer); done(reject, e) })
      response.on('end', () => {
        clearTimeout(timer)
        const text = Buffer.concat(chunks).toString('utf8')
        if (response.statusCode < 200 || response.statusCode >= 300) {
          return done(reject, new Error(`GitHub API HTTP ${response.statusCode}`))
        }
        try {
          done(resolve, JSON.parse(text))
        } catch (e) {
          done(reject, new Error('GitHub API 返回非 JSON'))
        }
      })
    })
    request.on('error', (e) => { clearTimeout(timer); done(reject, e) })
    request.end()
  })
}

function buildDownloadUrl (rawUrl, useMirror) {
  if (!useMirror) return rawUrl
  const prefix = (settings && settings.mirrorPrefix) || DEFAULT_MIRROR_PREFIX
  if (!prefix) return rawUrl
  const p = prefix.endsWith('/') ? prefix : prefix + '/'
  return p + rawUrl
}

// ==================== 检测更新 ====================

function normalizeRelease (release) {
  const version = normalizeVersion(release.tag_name || release.name || '')
  return {
    version,
    name: release.name || version,
    notes: release.body || '',
    publishedAt: release.published_at || '',
    releaseUrl: release.html_url || RELEASES_PAGE,
    assets: (release.assets || []).map(a => ({
      name: a.name,
      size: a.size || 0,
      url: a.browser_download_url
    }))
  }
}

function buildResult (info) {
  const currentVersion = app.getVersion()
  const hasUpdate = !!info && compareVersion(info.version, currentVersion) > 0
  const asset = hasUpdate ? pickAsset(info.assets) : null
  pendingAsset = asset ? { name: asset.name, size: asset.size, url: asset.url } : null
  return {
    currentVersion,
    latestVersion: info ? info.version : '',
    latestName: info ? info.name : '',
    hasUpdate,
    notes: info ? info.notes : '',
    publishedAt: info ? info.publishedAt : '',
    releaseUrl: info ? info.releaseUrl : RELEASES_PAGE,
    asset: pendingAsset ? { ...pendingAsset } : null,
    platform: process.platform,
    arch: process.arch,
    checkedAt: lastCheckedAt
  }
}

async function checkForUpdates (force) {
  const now = Date.now()

  // 非强制：命中缓存直接返回，避免触发 GitHub 限流
  if (!force && latestInfo && now - lastCheckedAt < CACHE_TTL_MS) {
    return buildResult(latestInfo)
  }
  // 强制：最小间隔内复用上次结果
  if (force && latestInfo && now - lastCheckedAt < FORCE_MIN_INTERVAL_MS) {
    return buildResult(latestInfo)
  }
  if (checking) {
    if (latestInfo) return buildResult(latestInfo)
    throw new Error('正在检查更新，请稍候')
  }

  checking = true
  try {
    const release = await httpGetJson(API_LATEST)
    latestInfo = normalizeRelease(release)
    lastCheckedAt = Date.now()
    cache = { checkedAt: lastCheckedAt, release: latestInfo }
    saveJson(cacheFile(), cache)
    /*
      检测结果可能已经翻篇（3.0.2 的包 + 3.0.3 的 Release）：
      先把本地不匹配的下载态作废，再返回结果。
      顺序不能反 —— buildResult 会把 pendingAsset 切成新版本的资产，
      留着旧的 done 状态就会让界面显示「安装 3.0.2」。
    */
    invalidateStaleDownload(latestInfo.version)
    return buildResult(latestInfo)
  } finally {
    checking = false
  }
}

// ==================== 下载 ====================

function broadcast (channel, payload) {
  const win = mainWindowGetter()
  const targets = (win && !win.isDestroyed()) ? [win] : BrowserWindow.getAllWindows()
  for (const w of targets) {
    try {
      if (w && !w.isDestroyed() && w.webContents) w.webContents.send(channel, payload)
    } catch (_) { /* ignore */ }
  }
}

function emitDownloadState () {
  broadcast('update:download-progress', { ...downloadState })
}

/**
 * 作废「已下载但版本对不上」的安装包。
 *
 * <p>为什么必须由主进程做：`downloadState` 是跨检查保留的（用户先下过 3.0.2，
 * 之后仓库发了 3.0.3），而检测更新只更新 `latestInfo`、不会碰下载状态 ——
 * 界面于是把 3.0.2 的包当成「最新版安装包」，按钮写着「安装更新」，
 * 点下去装的其实还是用户已经装着的旧版本。</p>
 *
 * <p>前端 3.0.3 起会按版本号比对兜底，但**旧前端（3.0.2 及以前）没有任何版本比对**，
 * 完全依赖这里把状态清干净；主进程与渲染进程各修一层，才不会出现
 * 「升级到新版之后仍然复发」。</p>
 *
 * @param {string} targetVersion 当前检测到的最新版本
 * @returns {boolean} 是否发生了作废
 */
function invalidateStaleDownload (targetVersion) {
  if (downloadState.status !== 'done') return false
  const target = normalizeVersion(targetVersion || '')
  const cached = normalizeVersion(downloadState.version || '')
  // 版本号缺失（老状态）也按「对不上」处理：宁可让用户重下，也不要把旧包装上去
  if (target && cached && target === cached) return false
  console.log('[update] 本地安装包与最新版本不一致，已作废:', cached || '(unknown)', '→', target || '(unknown)')
  downloadState = {
    status: 'idle',
    percent: 0,
    received: 0,
    total: 0,
    filePath: '',
    fileName: '',
    version: '',
    error: ''
  }
  emitDownloadState()
  return true
}

function downloadOnce (url, filePath, expectedSize) {
  return new Promise((resolve, reject) => {
    const tmpPath = filePath + '.part'
    try { fs.rmSync(tmpPath, { force: true }) } catch (_) { /* ignore */ }

    const out = fs.createWriteStream(tmpPath)
    let received = 0
    let settled = false

    const fail = (err) => {
      if (settled) return
      settled = true
      try { out.destroy() } catch (_) { /* ignore */ }
      try { fs.rmSync(tmpPath, { force: true }) } catch (_) { /* ignore */ }
      currentRequest = null
      reject(err)
    }

    const succeed = () => {
      if (settled) return
      settled = true
      currentRequest = null
      out.end(() => {
        try {
          fs.rmSync(filePath, { force: true })
          fs.renameSync(tmpPath, filePath)
          resolve()
        } catch (e) {
          reject(e)
        }
      })
    }

    let request
    try {
      request = net.request({ method: 'GET', url, redirect: 'follow' })
    } catch (e) {
      return fail(e)
    }
    currentRequest = request
    request.setHeader('User-Agent', 'CodeCraft-Updater')

    request.on('response', (response) => {
      if (response.statusCode < 200 || response.statusCode >= 300) {
        return fail(new Error(`下载失败 HTTP ${response.statusCode}`))
      }
      const lenHeader = response.headers['content-length']
      const lenValue = Array.isArray(lenHeader) ? Number(lenHeader[0]) : Number(lenHeader)
      const total = Number.isFinite(lenValue) && lenValue > 0 ? lenValue : (expectedSize || 0)
      downloadState.total = total
      let lastEmit = 0

      response.on('data', (chunk) => {
        received += chunk.length
        try { out.write(chunk) } catch (e) { fail(e) }
        const now = Date.now()
        if (now - lastEmit > 200) {
          lastEmit = now
          downloadState.received = received
          downloadState.percent = total > 0 ? Math.min(99, Math.round(received * 100 / total)) : 0
          emitDownloadState()
        }
      })
      response.on('end', () => {
        downloadState.received = received
        // 完整性校验：允许 0.5% 误差（镜像可能不返回 content-length）
        if (total > 0 && received < total * 0.995) {
          return fail(new Error(`下载不完整（${received}/${total} 字节）`))
        }
        succeed()
      })
      response.on('error', fail)
    })
    request.on('error', fail)
    request.on('abort', () => fail(new Error('下载已取消')))
    request.end()
  })
}

async function startDownload () {
  if (!pendingAsset) {
    throw new Error('没有可下载的安装包，请先检查更新')
  }
  if (downloadState.status === 'downloading') {
    return { ...downloadState }
  }

  const dir = downloadDir()
  fs.mkdirSync(dir, { recursive: true })
  const filePath = path.join(dir, pendingAsset.name)

  // 已存在且大小一致 → 直接视为完成（避免重复下载 200MB+）
  try {
    if (fs.existsSync(filePath) && pendingAsset.size > 0 && fs.statSync(filePath).size === pendingAsset.size) {
      downloadState = {
        status: 'done', percent: 100, received: pendingAsset.size, total: pendingAsset.size,
        filePath, fileName: pendingAsset.name, version: latestInfo ? latestInfo.version : '', error: ''
      }
      emitDownloadState()
      return { ...downloadState }
    }
  } catch (_) { /* ignore */ }

  downloadState = {
    status: 'downloading', percent: 0, received: 0, total: pendingAsset.size || 0,
    filePath, fileName: pendingAsset.name, version: latestInfo ? latestInfo.version : '', error: ''
  }
  emitDownloadState()

  // 候选下载源：镜像（启用时）→ GitHub 直连兜底
  const candidates = []
  if (settings && settings.useMirror) {
    candidates.push(buildDownloadUrl(pendingAsset.url, true))
  }
  candidates.push(pendingAsset.url)

  let lastError = null
  for (const url of candidates) {
    try {
      console.log('[update] 开始下载:', url)
      await downloadOnce(url, filePath, pendingAsset.size)
      downloadState = { ...downloadState, status: 'done', percent: 100, received: downloadState.total || downloadState.received }
      emitDownloadState()
      return { ...downloadState }
    } catch (e) {
      lastError = e
      console.warn('[update] 下载源失败，尝试下一个:', url, e.message)
    }
  }

  downloadState = { ...downloadState, status: 'error', error: lastError ? lastError.message : '下载失败' }
  emitDownloadState()
  return { ...downloadState }
}

function cancelDownload () {
  if (currentRequest) {
    try { currentRequest.abort() } catch (_) { /* ignore */ }
  }
  currentRequest = null
  downloadState = { ...downloadState, status: 'cancelled', error: '' }
  emitDownloadState()
  return { ...downloadState }
}

// ==================== 升级前数据备份（双保险） ====================

/**
 * 升级前备份数据库到 userData/backups（保留最近 N 份）。
 * 说明：数据目录已在 userData/runtime，安装器本身不会触碰；此备份用于极端情况兜底。
 */
function backupBeforeUpgrade () {
  try {
    const runtime = runtimeDir()
    const dbFile = path.join(runtime, 'data', 'codecraft.mv.db')
    if (!fs.existsSync(dbFile)) {
      console.log('[update] 未发现数据库文件，跳过升级前备份')
      return null
    }
    const root = backupRoot()
    fs.mkdirSync(root, { recursive: true })
    const stamp = new Date().toISOString().replace(/[:.]/g, '-')
    const targetDir = path.join(root, `upgrade-${app.getVersion()}-${stamp}`)
    fs.mkdirSync(targetDir, { recursive: true })
    fs.cpSync(path.join(runtime, 'data'), path.join(targetDir, 'data'), { recursive: true })

    // 清理旧备份：只保留最近 N 份
    const max = Math.max(1, Number(settings && settings.maxBackups) || 3)
    const dirs = fs.readdirSync(root).filter(n => n.startsWith('upgrade-')).sort()
    while (dirs.length > max) {
      const old = dirs.shift()
      try { fs.rmSync(path.join(root, old), { recursive: true, force: true }) } catch (_) { /* ignore */ }
    }
    console.log('[update] 升级前数据已备份:', targetDir)
    return targetDir
  } catch (e) {
    // 备份失败不阻塞安装：数据目录本身不受安装影响
    console.error('[update] 升级前备份失败（继续安装）:', e)
    return null
  }
}

// ==================== 安装 ====================

async function installDownloaded (opts) {
  if (downloadState.status !== 'done') {
    throw new Error('安装包尚未下载完成，请先下载更新')
  }
  /*
    再守一道版本闸门：前端（3.0.3 起）已经按版本号决定显示「下载」还是「安装」，
    但旧前端不做比对、用户也可能在旧界面上点下去 ——
    这里直接拒绝，避免「以为在升级、实际把旧版本又装了一遍」。
  */
  const latestVersion = normalizeVersion(latestInfo ? latestInfo.version : '')
  const localVersion = normalizeVersion(downloadState.version || '')
  if (latestInfo && latestVersion && localVersion !== latestVersion) {
    throw new Error(`本地安装包为 v${localVersion || '未知'}，最新版本是 v${latestVersion}，请重新下载`)
  }
  const filePath = downloadState.filePath
  if (!filePath || !fs.existsSync(filePath)) {
    throw new Error('安装包不存在，请先下载')
  }
  // ★ 先停后端：释放 H2 数据库/日志的文件句柄
  //   ① 保证升级前备份拿到一致的文件；② 避免安装器替换文件时被占用导致安装失败
  if (typeof beforeInstallHook === 'function') {
    try {
      await beforeInstallHook()
    } catch (e) {
      console.warn('[update] 安装前停止后端失败（继续安装）:', e.message)
    }
  }
  const backup = backupBeforeUpgrade()
  const silent = !!(opts && opts.silent)

  if (process.platform === 'win32') {
    const args = silent ? ['/S'] : []
    // 记录是否真正拉起安装程序：拉起失败（被杀软拦截/权限不足）时不退出应用，避免"什么都没发生就闪退"
    let launched = false
    const child = spawn(filePath, args, { detached: true, stdio: 'ignore' })
    child.on('spawn', () => { launched = true })
    child.on('error', (e) => console.error('[update] 启动安装程序失败:', e.message))
    child.unref()
    // 给安装器一点启动时间后退出应用（NSIS 安装器会关闭旧实例并拉起新版）
    setTimeout(() => {
      if (launched) {
        app.quit()
      } else {
        console.error('[update] 安装程序未能启动，应用保持运行（可手动双击安装包）')
      }
    }, silent ? 800 : 1500)
    return { ok: true, action: 'installer-launched', silent, backup }
  }

  if (process.platform === 'darwin') {
    const err = await shell.openPath(filePath)
    if (err) throw new Error(err)
    return { ok: true, action: 'dmg-opened', backup }
  }

  shell.showItemInFolder(filePath)
  return { ok: true, action: 'file-revealed', backup }
}

// ==================== 状态 / IPC ====================

function publicState () {
  /*
    惰性校正：启动时是拿缓存里的 latestInfo 预填「已下载」态的，
    缓存可能已经翻篇（上次检查到 3.0.2、期间发布了 3.0.3）。
    每次取状态前先对齐一次版本号，避免旧包一直被渲染成「可安装」。
  */
  if (latestInfo) invalidateStaleDownload(latestInfo.version)
  const latest = latestInfo ? buildResult(latestInfo) : null
  return {
    currentVersion: app.getVersion(),
    platform: process.platform,
    arch: process.arch,
    latest,
    download: { ...downloadState },
    settings: { ...settings },
    lastCheckedAt
  }
}

/**
 * 清理本模块在本地产生的「非数据库」数据（供「重置所有数据」调用）：
 * - userData/backups：升级前备份（内含数据库副本，必须清掉，否则"清空所有数据"不彻底）
 * - temp/codecraft-updates：已下载的安装包
 * - update-cache.json：版本检测缓存
 *
 * 注意：不动 userData/runtime（后端正在使用，数据库表由后端 /api/system/reset-data 清理）。
 *
 * @returns {{ ok: boolean, removed: string[] }}
 */
function clearLocalData () {
  const removed = []
  const removePath = (target, label) => {
    try {
      if (fs.existsSync(target)) {
        fs.rmSync(target, { recursive: true, force: true })
        removed.push(label)
      }
    } catch (e) {
      console.warn('[update] 清理失败:', target, e.message)
    }
  }
  removePath(backupRoot(), '升级前备份')
  removePath(path.join(app.getPath('userData'), 'legacy-backup'), '旧版本升级备份')
  removePath(downloadDir(), '已下载安装包')
  removePath(cacheFile(), '更新检测缓存')

  // 重置运行态，避免界面残留「可安装」
  downloadState = {
    status: 'idle', percent: 0, received: 0, total: 0,
    filePath: '', fileName: '', version: '', error: ''
  }
  latestInfo = null
  lastCheckedAt = 0
  console.log('[update] 本地数据清理完成:', removed.length ? removed.join('、') : '（无需清理）')
  return { ok: true, removed }
}

function registerIpc () {
  ipcMain.handle('update:getState', () => publicState())

  // 「重置所有数据」联动清理（升级备份 / 安装包 / 检测缓存）
  ipcMain.handle('app:clearLocalData', () => clearLocalData())

  ipcMain.handle('update:check', async (_event, force) => {
    return checkForUpdates(!!force)
  })

  ipcMain.handle('update:download', async () => startDownload())

  ipcMain.handle('update:cancelDownload', () => cancelDownload())

  ipcMain.handle('update:install', async (_event, opts) => installDownloaded(opts))

  ipcMain.handle('update:openReleasePage', async () => {
    const url = (latestInfo && latestInfo.releaseUrl) || RELEASES_PAGE
    await shell.openExternal(url)
    return true
  })

  ipcMain.handle('update:getSettings', () => ({ ...settings }))

  ipcMain.handle('update:setSettings', (_event, patch) => {
    settings = sanitizeSettings({ ...settings, ...(patch || {}) })
    saveJson(settingsFile(), settings)
    return { ...settings }
  })
}

function scheduleAutoCheck () {
  if (!settings || !settings.autoCheck) return
  setTimeout(async () => {
    try {
      const result = await checkForUpdates(false)
      if (result.hasUpdate) {
        broadcast('update:available', result)
      }
    } catch (e) {
      // 自动检查失败静默处理（不影响使用）
      console.warn('[update] 自动检查更新失败:', e.message)
    }
  }, AUTO_CHECK_DELAY_MS)
}

/**
 * 初始化更新管理器（在 app.whenReady 之后调用一次）
 * @param {{ getMainWindow?: () => any }} options
 */
function initUpdateManager (options) {
  mainWindowGetter = (options && options.getMainWindow) || (() => null)
  beforeInstallHook = (options && options.beforeInstall) || null
  settings = loadSettings()
  cache = loadJson(cacheFile(), null)
  if (cache && cache.release) {
    latestInfo = cache.release
    lastCheckedAt = cache.checkedAt || 0
    /*
      预填「已下载」态必须同时满足两个条件：
        1) 缓存里的版本确实比当前安装版本新 —— 否则那个包就是「当前版本的安装器」，
           显示成「可安装更新」毫无意义，还会挡住真正的新版本下载按钮；
        2) 磁盘上的文件名与大小和缓存资产完全一致 —— 复用之前先确认没被删 / 没下坏。
      旧实现只校验文件存在，于是用户从 3.0.1 升到 3.0.2 后，temp 里残留的
      CodeCraft-Setup_3.0.2.exe 会让界面一直停留在「安装 3.0.2」。
    */
    try {
      const hasNewer = compareVersion(latestInfo.version, app.getVersion()) > 0
      const dir = downloadDir()
      const asset = hasNewer ? pickAsset(latestInfo.assets || []) : null
      if (asset) {
        const filePath = path.join(dir, asset.name)
        if (fs.existsSync(filePath) && asset.size > 0 && fs.statSync(filePath).size === asset.size) {
          downloadState = {
            status: 'done', percent: 100, received: asset.size, total: asset.size,
            filePath, fileName: asset.name, version: latestInfo.version, error: ''
          }
        }
      }
    } catch (_) { /* ignore */ }
  }
  registerIpc()
  scheduleAutoCheck()
  console.log('[update] 更新管理器已初始化，当前版本:', app.getVersion())
}

module.exports = {
  initUpdateManager,
  checkForUpdates,
  backupBeforeUpgrade
}
