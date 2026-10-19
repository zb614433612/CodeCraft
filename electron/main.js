const { app, BrowserWindow, dialog, ipcMain, Menu, nativeTheme, screen, shell } = require('electron')
const { spawn, exec } = require('child_process')
const path = require('path')
const fs = require('fs')
const http = require('http')
// 自动更新管理器（检测 GitHub Releases / 下载 / 安装 / 升级前备份）
const { initUpdateManager } = require('./update-manager')

const DEV_MODE = process.env.DEV_MODE === 'true'

const IS_MAC = process.platform === 'darwin'
const IS_WIN = process.platform === 'win32'

// ── 单实例锁（必须）────────────────────────────────────────────
// 场景：macOS 上关闭窗口后进程仍在 Dock 里（点不消失），用户再次双击 .app，
// 若没有单实例锁会拉起第二个应用实例 → 第二个实例重复 spawn 后端 →
// H2 数据库文件锁 + 8084 端口被第一个实例占用 → 后端起不来 →
// 弹出「启动失败」或白屏。加锁后第二次启动只会激活已有实例的窗口。
const singleInstanceLock = app.requestSingleInstanceLock()
if (!singleInstanceLock) {
  // 已有实例在运行：本实例立即退出，由已运行实例的 second-instance 处理器唤起窗口
  app.quit()
}

// ── 标题栏配置（透明自定义标题栏）──
// color 为全透明：系统标题栏隐藏后，标题栏区域直接露出网页内容
//（普通模式为主题底色；开启「背景图」时透出背景图 —— 标题栏随应用背景变化）；
// 窗口控制按钮（最小化/最大化/关闭）由 Electron 覆盖层绘制，符号色随深浅主题切换。
const TITLE_BAR_THEME = {
  dark: {
    color: 'rgba(0, 0, 0, 0)', // 全透明：露出网页内容
    symbolColor: '#9ca3af'     // 与前端暗色文字 --text 一致
  },
  light: {
    color: 'rgba(0, 0, 0, 0)', // 全透明：露出网页内容
    symbolColor: '#333333'
  }
}

/**
 * 根据主题模式更新窗口标题栏样式和 Electron 原生主题
 * （透明自定义标题栏：color 恒为透明、背景透出网页内容；
 *   仅切换窗口控制按钮的符号色，保证在浅色 / 深色页面上都清晰可辨）
 * @param {string} mode - 'light' | 'dark' | 'auto'
 */
function applyTitleBarTheme(mode) {
  // 解析实际主题
  let resolved
  if (mode === 'auto') {
    resolved = nativeTheme.shouldUseDarkColors ? 'dark' : 'light'
  } else {
    resolved = mode === 'dark' ? 'dark' : 'light'
  }

  // 设置 Electron 原生主题（影响原生对话框、右键菜单等）
  nativeTheme.themeSource = mode === 'auto' ? 'system' : (mode === 'dark' ? 'dark' : 'light')

  // 更新窗口标题栏覆盖层（透明背景 + 主题化按钮符号色）
  // 注意：setTitleBarOverlay 仅在 Windows / Linux 生效，macOS 由系统 traffic lights 托管，跳过以免无谓调用
  const overlay = TITLE_BAR_THEME[resolved]
  if (mainWindow) {
    if (!IS_MAC) {
      mainWindow.setTitleBarOverlay({
        color: overlay.color,
        symbolColor: overlay.symbolColor,
        height: 32  // 与前端自定义标题栏拖拽区高度一致（32px）
      })
    }
    // 窗口底色兜底：页面首帧渲染前与当前主题接近，避免加载阶段白边闪烁
    mainWindow.setBackgroundColor(resolved === 'dark' ? '#16171d' : '#ffffff')
  }
}

/**
 * 获取后端服务端口
 * 优先级：环境变量 SERVER_PORT > application.yml 配置 > 默认值 8084
 * 这样修改后端 application.yml 中的端口后，Electron 会自动跟随，无需同步修改此处
 */
function getBackendPort() {
  // 1. 优先使用环境变量（打包部署时可用此方式覆盖）
  if (process.env.SERVER_PORT) {
    return parseInt(process.env.SERVER_PORT, 10)
  }
  // 2. 尝试从 application.yml 读取（开发环境）
  try {
    const configPath = path.join(__dirname, '..', 'src', 'main', 'resources', 'application.yml')
    if (fs.existsSync(configPath)) {
      const content = fs.readFileSync(configPath, 'utf-8')
      // 精确匹配 server: 块下的 port，避免匹配到 redis/milvus 等其他配置的 port
      const match = content.match(/^server:\s*\n\s+port:\s*(\d+)/m)
      if (match) {
        return parseInt(match[1], 10)
      }
    }
  } catch (e) {
    console.warn('读取 application.yml 失败，使用默认端口:', e.message)
  }
  // 3. 默认值
  return 8084
}

const PORT = getBackendPort()
const BACKEND_URL = `http://localhost:${PORT}`

let mainWindow = null
let javaProcess = null

// ── 启动画面（Splash）状态 ──
let splashWindow = null
let splashReady = false          // loading.html 是否已完成加载（完成后才补发缓存进度）
let splashShownAt = 0            // 启动画面展示时刻（用于最短展示时长控制）
const SPLASH_MIN_DURATION = 600  // 启动画面最短展示时长（ms），避免启动过快时一闪而过
const splashProgress = { percent: 0, message: '正在准备启动', error: false }

// ── 后端进程状态（启动等待期间用于「提前失败」判断）──
let javaExited = false           // 后端进程是否已退出
let backendExitReason = ''       // 提前退出的原因描述
// 是否为「非本进程拉起的残留后端」（上次被强制退出后遗留的 java 进程）：
// 这类后端可复用但无法由本进程 kill，需单独标记，否则每次 activate 都会误判为「无后端」而走完整重启动流程
let externalBackend = false

// ── 启动画面（Splash）────────────────────────────────────

/**
 * 创建启动画面窗口：居中悬浮、无边框、透明、不抢焦点、鼠标穿透
 * 页面为本地 loading.html（CodeCraft 艺术字 + 进度条），随后端启动进度实时更新
 * 注意：窗口仅覆盖屏幕中央的一小块区域，不遮挡用户的其他内容与操作
 */
function createSplashWindow () {
  // 启动画面窗口尺寸：居中悬浮在屏幕上，只占中央一小块
  const SPLASH_WIDTH = 740
  const SPLASH_HEIGHT = 400
  const { bounds } = screen.getPrimaryDisplay()
  const x = bounds.x + Math.round((bounds.width - SPLASH_WIDTH) / 2)
  const y = bounds.y + Math.round((bounds.height - SPLASH_HEIGHT) / 2)

  const win = new BrowserWindow({
    x,
    y,
    width: SPLASH_WIDTH,
    height: SPLASH_HEIGHT,
    frame: false,            // 无边框：内容直接悬浮在屏幕中央
    transparent: true,       // 透明：无黑幕，仅保留中央的柔光与文字
    hasShadow: false,
    resizable: false,
    movable: false,
    minimizable: false,
    maximizable: false,
    fullscreenable: false,
    skipTaskbar: true,       // 不在任务栏显示
    alwaysOnTop: true,       // 启动画面置顶
    focusable: false,        // 不抢焦点：启动期间用户仍可正常操作其他窗口
    show: false,
    webPreferences: {
      nodeIntegration: false,
      contextIsolation: true,
      preload: path.join(__dirname, 'preload.js'),
      // 启动画面需要持续渲染进度动画，禁止后台节流以免掉帧
      backgroundThrottling: false
    }
  })

  // 鼠标事件穿透：黑幕只做视觉反馈，不拦截任何点击
  win.setIgnoreMouseEvents(true)

  splashWindow = win
  splashReady = false
  splashShownAt = Date.now()

  win.once('ready-to-show', () => {
    if (!win.isDestroyed()) win.showInactive()
  })

  win.webContents.on('did-finish-load', () => {
    if (win.isDestroyed()) return
    splashReady = true
    // 页面加载完成：补发加载期间缓存的最新进度
    sendSplashProgress()
  })

  win.on('closed', () => {
    if (splashWindow === win) {
      splashWindow = null
      splashReady = false
    }
  })

  win.loadFile(path.join(__dirname, 'loading.html'))
}

/**
 * 将当前缓存的最新启动进度发送到启动画面
 */
function sendSplashProgress () {
  if (!splashWindow || splashWindow.isDestroyed() || !splashReady) return
  try {
    splashWindow.webContents.send('startup-progress', { ...splashProgress })
  } catch (e) {
    console.warn('发送启动进度失败:', e.message)
  }
}

/**
 * 更新启动画面进度（只升不降）与状态文案
 * @param {number|null} percent - 目标进度（0~100）；传 null 表示仅更新文案/状态
 * @param {string} [message] - 状态文案（不传则保持上次）
 * @param {boolean} [isError] - 是否进入失败状态（进度条变红等）
 */
function updateSplashProgress (percent, message, isError = false) {
  if (typeof percent === 'number' && Number.isFinite(percent)) {
    splashProgress.percent = Math.max(splashProgress.percent, Math.min(100, Math.round(percent)))
  }
  if (message) splashProgress.message = message
  if (isError) splashProgress.error = true
  sendSplashProgress()
}

/**
 * 关闭启动画面
 * 常规路径：进度补满 + 页面淡出动画后再关闭（与主窗口显示衔接）；
 * 失败路径（immediate=true）：立即销毁，为原生错误弹窗让路
 */
function closeSplashWindow (immediate = false) {
  const win = splashWindow
  if (!win || win.isDestroyed()) return
  splashWindow = null
  const wasReady = splashReady
  splashReady = false

  const finish = () => {
    if (win.isDestroyed()) return
    if (immediate) win.destroy()
    else win.close()
  }

  // 页面未就绪或需要立即关闭：直接结束，不播放动画
  if (immediate || !wasReady) {
    finish()
    return
  }

  const delay = Math.max(0, SPLASH_MIN_DURATION - (Date.now() - splashShownAt))
  setTimeout(() => {
    if (win.isDestroyed()) return
    try {
      win.webContents.send('startup-progress', { percent: 100, done: true })
    } catch (_) { /* 忽略发送失败：直接关闭 */ }
    setTimeout(finish, 380) // 等待页面淡出动画（0.36s）播完
  }, delay)
}

/**
 * 启动日志锚点：将 Spring Boot 关键启动日志行映射为启动画面进度
 * 仅做「抬升」（只升不降）；日志格式不匹配时也不影响基本运行（时间维度进度兜底）
 */
const STARTUP_ANCHORS = [
  { pattern: /Starting .*Application/, percent: 12, message: '正在初始化运行环境' },
  { pattern: /Tomcat initialized/, percent: 45, message: '正在启动 Web 服务' },
  { pattern: /Tomcat started on port/, percent: 75, message: '正在加载应用界面' },
  { pattern: /Started .* in .* seconds/, percent: 92, message: '即将完成' }
]

/**
 * 扫描后端日志，命中启动锚点则推进启动画面进度
 * @param {string} text - 后端 stdout 输出片段（可能包含多行）
 */
function scanStartupAnchors (text) {
  for (const anchor of STARTUP_ANCHORS) {
    if (anchor.pattern.test(text)) {
      updateSplashProgress(anchor.percent, anchor.message)
    }
  }
}

/**
 * 检查后端服务是否已就绪（同步上报启动画面进度）
 * 进度双重保障：① 时间维度兜底估算；② 启动日志锚点跳跃提升（见 scanStartupAnchors）
 * 若后端进程提前退出，立即失败，不再空等剩余轮询
 */
function waitForBackend(retries = 30, interval = 2000) {
  return new Promise((resolve, reject) => {
    const check = (remaining) => {
      // 后端进程已退出：立即失败（附上退出原因）
      if (javaExited) {
        reject(new Error(backendExitReason || '后端进程意外退出'))
        return
      }
      if (remaining <= 0) {
        reject(new Error('后端服务启动超时'))
        return
      }
      // 时间维度进度兜底：从 8% 随等待时间平滑爬升至 90%（日志锚点可进一步推高）
      const elapsed = retries - remaining
      const timePercent = Math.min(90, 8 + Math.round((elapsed / retries) * 82))
      const slowMessage = timePercent >= 78 ? '首次启动需要初始化数据，请耐心等待…' : undefined
      updateSplashProgress(timePercent, slowMessage)

      http.get(`${BACKEND_URL}/`, (res) => {
        if (res.statusCode >= 200 && res.statusCode < 500) {
          resolve()
        } else {
          setTimeout(() => check(remaining - 1), interval)
        }
      }).on('error', () => {
        setTimeout(() => check(remaining - 1), interval)
      })
    }
    check(retries)
  })
}

/**
 * 查找 Java 可执行文件路径
 * 优先级：内置 JRE > JAVA_HOME > 系统 PATH
 */
function findJava() {
  const javaExe = IS_WIN ? 'java.exe' : 'java'

  // 打包模式下优先使用内置 JRE
  if (app.isPackaged) {
    const bundledJava = path.join(process.resourcesPath, 'jre', 'bin', javaExe)
    if (fs.existsSync(bundledJava)) {
      return bundledJava
    }
    // 内置 JRE 与当前平台不匹配（安装包里放的是别的平台的 JRE，例如 Windows 的 jre/ 被塞进 mac 包）
    // 这里记录一条日志便于排查「启动时提示找不到 java」，随后回退到系统 JDK
    console.warn(`内置 JRE 不可用（${bundledJava} 不存在），回退系统 JDK`)
  }
  // 其次使用 JAVA_HOME 环境变量
  if (process.env.JAVA_HOME) {
    const javaHomePath = path.join(process.env.JAVA_HOME, 'bin', javaExe)
    if (fs.existsSync(javaHomePath)) {
      return javaHomePath
    }
  }
  // macOS 专用探测：macOS 的 /usr/bin/java 只是 stub，未装 JDK 时执行会失败，
  // 且 macOS 上 JAVA_HOME 默认未设置。这里主动找常见 JDK 安装位置，
  // 覆盖 Apple Silicon(arm64) 与 Intel(x64) 两种 Homebrew 路径 + 系统 JVM 目录。
  if (IS_MAC) {
    const directCandidates = [
      '/opt/homebrew/opt/openjdk/bin/java', // Apple Silicon Homebrew
      '/usr/local/opt/openjdk/bin/java'     // Intel Homebrew
    ]
    for (const candidate of directCandidates) {
      if (fs.existsSync(candidate)) {
        return candidate
      }
    }
    try {
      const jvmBase = '/Library/Java/JavaVirtualMachines'
      if (fs.existsSync(jvmBase)) {
        for (const dir of fs.readdirSync(jvmBase)) {
          const candidate = path.join(jvmBase, dir, 'Contents', 'Home', 'bin', 'java')
          if (fs.existsSync(candidate)) {
            return candidate
          }
        }
      }
    } catch (e) {
      console.warn('探测 macOS JDK 目录失败（回退系统 PATH）:', e.message)
    }
  }
  // 最后回退到系统 PATH
  return 'java'
}

/**
 * 获取后端 JAR 文件路径
 * 开发环境：动态扫描 target 目录取最新构建的 jar，避免 artifactId/版本号写死导致漂移
 */
function getJarPath() {
  if (app.isPackaged) {
    // 打包后 extraResources 位于 resources/ 目录下
    return path.join(process.resourcesPath, 'backend', 'codecraft.jar')
  }
  // 开发环境：扫描 target 目录，取修改时间最新的构建产物
  const targetDir = path.join(__dirname, '..', 'target')
  try {
    if (fs.existsSync(targetDir)) {
      const jars = fs.readdirSync(targetDir)
        .filter(f => f.endsWith('.jar') && !f.endsWith('.original') && !f.endsWith('-sources.jar') && !f.endsWith('-javadoc.jar'))
        .map(name => ({ name, mtime: fs.statSync(path.join(targetDir, name)).mtimeMs }))
        .sort((a, b) => b.mtime - a.mtime)
      if (jars.length > 0) {
        const jarPath = path.join(targetDir, jars[0].name)
        console.log('使用后端 JAR:', jarPath)
        return jarPath
      }
    }
  } catch (e) {
    console.warn('扫描 target 目录失败:', e.message)
  }
  // 未找到构建产物：返回 null，由 startBackend 提示用户先构建（不写死版本号，避免升级后误用旧路径）
  return null
}

/**
 * 解析打包环境下的后端工作目录（数据目录），确保「升级安装不会丢用户数据」。
 *
 * 背景：后端 H2 数据库、快照、日志都使用相对路径（./data、./snapshots、./logs），
 * 由 java 进程的 cwd 决定。历史实现把 cwd 设为 jar 所在目录（<安装目录>/resources/backend），
 * 一旦覆盖安装（NSIS 会替换 resources 目录），用户数据库/快照就会丢失。
 *
 * 现策略：
 * 1) cwd 固定为 <userData>/runtime（Windows: %APPDATA%/CodeCraft/runtime），安装器不会触碰；
 * 2) 首次运行且旧位置存在历史数据时，先完整复制到临时目录、再原子改名为正式目录（避免半拷贝）；
 * 3) 任一步失败都回退用旧目录启动 —— 宁可暂时不迁移，也绝不丢数据。
 *
 * @param {string} jarDir 后端 jar 所在目录（历史数据目录）
 * @returns {string} 后端 cwd
 */
function resolveBackendWorkDir (jarDir) {
  // 开发环境：保持原有行为（数据留在 target 目录旁，方便调试）
  if (!app.isPackaged) return jarDir

  const runtimeDir = path.join(app.getPath('userData'), 'runtime')
  const runtimeDb = path.join(runtimeDir, 'data', 'codecraft.mv.db')
  const legacyDb = path.join(jarDir, 'data', 'codecraft.mv.db')

  try {
    // 1) 新位置已有数据（或此前已迁移过）→ 直接使用
    if (fs.existsSync(runtimeDb)) return runtimeDir

    // 2) 首次从「数据在安装目录」的旧版本升级：安装包 customInit 已把旧数据备份到 legacy-backup，
    //    这里恢复到 runtime（保留 legacy-backup 作为额外副本，不删除）
    const legacyBackupDir = path.join(app.getPath('userData'), 'legacy-backup')
    const legacyBackupDb = path.join(legacyBackupDir, 'data', 'codecraft.mv.db')
    if (fs.existsSync(legacyBackupDb)) {
      const restoreTmp = runtimeDir + '.restoring'
      fs.rmSync(restoreTmp, { recursive: true, force: true })
      fs.mkdirSync(restoreTmp, { recursive: true })
      for (const name of ['data', 'snapshots', 'logs']) {
        const src = path.join(legacyBackupDir, name)
        if (fs.existsSync(src)) {
          fs.cpSync(src, path.join(restoreTmp, name), { recursive: true })
          console.log(`[migrate] 已从安装包备份恢复 ${name}`)
        }
      }
      fs.rmSync(runtimeDir, { recursive: true, force: true })
      fs.renameSync(restoreTmp, runtimeDir)
      console.log(`[migrate] 旧版本数据已恢复到: ${runtimeDir}`)
      return runtimeDir
    }

    // 3) 旧位置（安装目录内）仍有历史数据 → 一次性迁移（数据库 + 快照 + 日志）
    if (fs.existsSync(legacyDb)) {
      const tmpDir = runtimeDir + '.migrating'
      fs.rmSync(tmpDir, { recursive: true, force: true })
      fs.mkdirSync(tmpDir, { recursive: true })
      const copyNames = ['data', 'snapshots', 'logs']
      for (const name of copyNames) {
        const src = path.join(jarDir, name)
        if (fs.existsSync(src)) {
          fs.cpSync(src, path.join(tmpDir, name), { recursive: true })
          console.log(`[migrate] 已复制 ${name} → ${path.join(tmpDir, name)}`)
        }
      }
      // 原子替换：避免出现「目录已建但数据不全」的中间态
      fs.rmSync(runtimeDir, { recursive: true, force: true })
      fs.renameSync(tmpDir, runtimeDir)
      console.log(`[migrate] 历史数据迁移完成: ${jarDir} → ${runtimeDir}`)
      return runtimeDir
    }

    // 全新安装：直接创建数据目录
    fs.mkdirSync(runtimeDir, { recursive: true })
    return runtimeDir
  } catch (e) {
    console.error('[migrate] 数据目录迁移失败，回退使用安装目录（数据不丢失，仅位置不理想）:', e)
    return jarDir
  }
}

/**
 * 探测本机是否已有可用的 CodeCraft 后端在监听
 * 场景：应用被强制退出（macOS 上「强制退出」或旧版 Cmd+Q 不可用时的强杀）后，
 * java 子进程会成为孤儿进程继续占用 8084 端口与 H2 数据库文件锁。
 * 若不检测就直接 spawn 新的 java，新进程会因端口/文件锁失败 → 表现为「再次启动失败」。
 * @returns {Promise<boolean>} 是否已存在可复用的后端
 */
function probeExistingBackend (timeoutMs = 1500) {
  return new Promise((resolve) => {
    const req = http.get(`${BACKEND_URL}/`, (res) => {
      // 只读前 4KB：足够判断是不是 CodeCraft 页面，避免把无关服务当成后端
      let body = ''
      res.setEncoding('utf8')
      res.on('data', (chunk) => {
        if (body.length < 4096) body += chunk
      })
      res.on('end', () => {
        const okStatus = res.statusCode >= 200 && res.statusCode < 400
        resolve(okStatus && /<div\s+id="app"|CodeCraft/i.test(body))
      })
    })
    req.on('error', () => resolve(false))
    req.setTimeout(timeoutMs, () => {
      req.destroy()
      resolve(false)
    })
  })
}

/**
 * 后端进程是否仍在运行
 * 注意：仅代表进程存在，不代表 HTTP 端口已就绪（就绪判定见 waitForBackend）
 */
function backendAlive () {
  return (!!javaProcess && !javaExited) || externalBackend
}

/**
 * 停止后端进程（关闭窗口 / 退出应用 / 覆盖安装前调用）
 */
function stopBackend () {
  if (!javaProcess) return
  try {
    javaProcess.kill()
  } catch (e) {
    console.warn('停止后端进程失败:', e.message)
  }
  javaProcess = null
}

/**
 * 启动 Spring Boot 后端 JAR
 */
async function startBackend() {
  // 幂等保护：后端已在运行时不再重复 spawn，
  // 否则第二个 java 进程会因 8084 端口占用 + H2 文件锁失败（macOS 重开窗口场景会走到这里）
  if (backendAlive()) {
    console.log('后端服务已在运行，跳过重复启动')
    return true
  }
  // 残留后端检测：上次被强制退出遗留的 java 仍占着 8084 与数据库文件锁，
  // 此时直接复用比再 spawn 一个更安全（新进程必然起不来）
  if (await probeExistingBackend()) {
    console.log('检测到已有后端实例在运行，直接复用（不再启动新进程）')
    javaExited = false
    backendExitReason = ''
    externalBackend = true
    return true
  }
  const jarPath = getJarPath()
  if (!jarPath) {
    console.error('未找到后端 JAR 文件，请先在项目根目录执行 "mvn package" 构建后端')
    // 失败提示统一由 start() 处理（启动画面展示错误 + 原生弹窗），这里仅返回失败标志
    return false
  }
  const javaCmd = findJava()

  console.log(`启动后端服务: ${jarPath}`)
  console.log(`Java 路径: ${javaCmd}`)

  // cwd 决定 Java 的 user.dir → H2 数据库 ./data、快照 ./snapshots、日志 ./logs 的落点。
  // 打包后必须指向 <userData>/runtime（与安装目录解耦），否则覆盖安装会丢失用户数据。
  const workDir = resolveBackendWorkDir(path.dirname(jarPath))
  // 确保工作目录存在
  if (!fs.existsSync(workDir)) {
    fs.mkdirSync(workDir, { recursive: true })
  }
  console.log(`后端工作目录: ${workDir}`)

  // 通过系统属性将 userData 路径传给 Java，方便需要时使用
  const userDataDir = app.isPackaged ? app.getPath('userData') : workDir
  javaExited = false
  backendExitReason = ''
  externalBackend = false   // 本次由自己拉起，退出时需由自己负责 stop
  javaProcess = spawn(javaCmd, [
    '-Dfile.encoding=UTF-8',
    `-Dapp.userdata=${userDataDir}`,
    '-jar', jarPath
  ], {
    cwd: workDir,
    stdio: ['ignore', 'pipe', 'pipe']
  })

  javaProcess.stdout.on('data', (data) => {
    const text = data.toString().trim()
    console.log(`[backend] ${text}`)
    // 命中启动日志锚点则推进启动画面进度（只升不降）
    scanStartupAnchors(text)
  })

  javaProcess.stderr.on('data', (data) => {
    console.error(`[backend-err] ${data.toString().trim()}`)
  })

  javaProcess.on('error', (err) => {
    console.error('启动后端失败:', err.message)
    // 标记提前失败：waitForBackend 会立即结束等待，错误统一在 start() 中提示
    backendExitReason = `无法启动后端服务：${err.message}`
    javaExited = true
  })

  javaProcess.on('exit', (code) => {
    console.log(`后端进程退出，退出码: ${code}`)
    if (!backendExitReason) {
      backendExitReason = `后端进程意外退出（退出码 ${code}）`
    }
    javaExited = true
    javaProcess = null
    externalBackend = false
  })

  return true
}

// 防止「加载失败 → 恢复 → 又失败」递归触发
let recovering = false

/**
 * 主窗口连续加载失败后的恢复处理
 * 1) 后端进程已不在（macOS 关闭窗口后被杀 / 意外退出）→ 销毁白屏窗口，重新走完整启动流程；
 * 2) 后端仍在但页面就是打不开 → 弹窗告知并给出「重试 / 退出」，避免用户面对无提示的白屏。
 */
async function recoverFromLoadFailure () {
  if (recovering) return
  recovering = true
  try {
    // 情况 1：后端进程已不在（意外退出 / 被清理）→ 完整重启后端并重建窗口
    if (!DEV_MODE && !backendAlive()) {
      console.log('[recover] 后端未运行，重新拉起后端并重建窗口')
      if (mainWindow && !mainWindow.isDestroyed()) {
        mainWindow.destroy()
      }
      mainWindow = null
      await start()
      return
    }

    // 情况 2：后端进程还在（很可能仍在慢启动）→ 先等它就绪，再重新加载页面
    if (!DEV_MODE) {
      try {
        await waitForBackend(20, 1000)
        console.log('[recover] 后端已就绪，重新加载界面')
        if (mainWindow && !mainWindow.isDestroyed()) {
          mainWindow.loadURL(BACKEND_URL).catch(() => {})
        }
        return
      } catch (err) {
        console.warn('[recover] 等待后端就绪失败:', err.message)
      }
    }

    // 情况 3：后端就绪但页面仍然打不开 → 弹窗告知，给出重试/退出
    const { response } = await dialog.showMessageBox({
      type: 'error',
      buttons: ['重试', '退出'],
      defaultId: 0,
      cancelId: 1,
      title: '界面加载失败',
      message: '无法加载 CodeCraft 界面',
      detail: `本地服务（${BACKEND_URL}）响应异常，界面可能显示为空白。\n\n` +
        '· 点击「重试」将重新加载界面\n' +
        '· 若持续失败，请退出应用（macOS 上按 Cmd+Q）后重新启动'
    })
    if (response === 0) {
      if (mainWindow && !mainWindow.isDestroyed()) {
        mainWindow.loadURL(BACKEND_URL).catch(() => {})
      }
    } else {
      app.quit()
    }
  } finally {
    recovering = false
  }
}

/**
 * 创建主窗口
 */
function createWindow() {
  // 菜单栏：
  // - macOS：绝对不能置空。macOS 的应用菜单由系统统一托管，置空会导致
  //   Cmd+Q（退出）、Cmd+W（关窗）、Cmd+C/Cmd+V（复制粘贴）、Cmd+M 等标准快捷键全部失效，
  //   用户只能点红叉关窗（进程不退、Dock 点不消失）而无法真正退出应用。
  // - Windows / Linux：保持无菜单（界面自带自定义标题栏）
  if (IS_MAC) {
    Menu.setApplicationMenu(Menu.buildFromTemplate([
      { role: 'appMenu' },   // 关于 / 服务 / 隐藏 / 退出（Cmd+Q）
      { role: 'editMenu' },  // 撤销 / 复制 / 粘贴 / 全选（聊天输入框必需）
      { role: 'windowMenu' } // 最小化 / 缩放 / 关闭（Cmd+W）
    ]))
  } else {
    Menu.setApplicationMenu(null)
  }

  // 根据当前系统主题预设 titleBarOverlay 初始值
  const initialDark = nativeTheme.shouldUseDarkColors
  const initialOverlay = initialDark ? TITLE_BAR_THEME.dark : TITLE_BAR_THEME.light

  mainWindow = new BrowserWindow({
    width: 1400,
    height: 900,
    minWidth: 1024,
    minHeight: 700,
    webPreferences: {
      nodeIntegration: false,
      contextIsolation: true,
      // 开启 <webview> 标签：用于应用内「网页标签页」嵌入外部网页（像浏览器一样浏览）
      webviewTag: true,
      preload: path.join(__dirname, 'preload.js')
    },
    title: 'CodeCraft',
    icon: path.join(__dirname, 'icon.png'),
    // 隐藏系统标题栏，改用「透明自定义标题栏」：
    // 标题栏区域由网页内容填充（普通模式=主题底色，背景图模式=背景图透出），
    // 窗口控制按钮由下方 titleBarOverlay 覆盖层绘制；
    // 窗口拖动由前端顶部 32px 拖拽区（App.vue .electron-titlebar-drag）负责
    titleBarStyle: 'hidden',
    backgroundColor: initialDark ? '#16171d' : '#ffffff',
    // 窗口控制按钮覆盖层：透明背景（透出网页内容）+ 随主题变化的符号色
    titleBarOverlay: {
      color: initialOverlay.color,
      symbolColor: initialOverlay.symbolColor,
      height: 32
    },
    show: false
  })

  // ── 加载失败自愈（防白屏）──────────────────────────────────
  // 背景：macOS 上关闭窗口后后端可能已退出，重新激活时窗口会加载 http://localhost:8084，
  // 若后端未就绪，Chromium 会停在空白/错误页，用户只看到「白屏」且无任何提示。
  // 策略：连续失败先自动重试（后端可能正在慢启动）；多次仍失败则重建后端或弹窗说明。
  let loadFailCount = 0
  mainWindow.webContents.on('did-finish-load', () => {
    loadFailCount = 0
  })
  mainWindow.webContents.on('did-fail-load', (_event, errorCode, errorDescription, validatedURL, isMainFrame) => {
    // 只关心主框架；-3 = ERR_ABORTED（用户主动中断或导航被替换，属正常）
    if (!isMainFrame || errorCode === -3) return
    loadFailCount += 1
    console.warn(`主窗口加载失败（第 ${loadFailCount} 次）: ${errorCode} ${errorDescription} ${validatedURL}`)
    if (loadFailCount <= 6) {
      setTimeout(() => {
        if (mainWindow && !mainWindow.isDestroyed()) {
          mainWindow.loadURL(BACKEND_URL).catch(() => {})
        }
      }, 1200)
      return
    }
    recoverFromLoadFailure()
  })

  mainWindow.loadURL(BACKEND_URL).catch((err) => {
    console.warn('主窗口 loadURL 调用失败:', err.message)
  })

  // 阻止主窗口被外部链接「导航走」：否则点击聊天中的链接后当前页面被替换，无法返回应用
  // 应用自身（后端页面）的导航放行，外部 http(s) 链接一律阻止（由前端改为在「网页标签页」内打开）
  mainWindow.webContents.on('will-navigate', (event, url) => {
    if (!url.startsWith(BACKEND_URL)) {
      event.preventDefault()
      console.log('已阻止主窗口外部导航:', url)
    }
  })
  // 拦截 window.open / target=_blank：不在 Electron 内新开窗口，改由系统浏览器打开
  mainWindow.webContents.setWindowOpenHandler(({ url }) => {
    if (/^https?:\/\//i.test(url)) {
      shell.openExternal(url).catch(() => {})
    }
    return { action: 'deny' }
  })

  // 清除 HTTP 缓存，避免覆盖安装后旧缓存与新前端资源冲突导致页面白屏
  mainWindow.webContents.session.clearCache().then(() => {
    console.log('HTTP 缓存已清除')
  }).catch(() => {})

  // 兜底显示：窗口以 show:false 创建，正常情况下等 ready-to-show。
  // 但页面加载失败时 ready-to-show 可能不触发，窗口会一直隐藏 ——
  // 用户看到的现象就是「点了没反应 / 启动失败」。因此加超时强制显示。
  const forceShowTimer = setTimeout(() => {
    if (mainWindow && !mainWindow.isDestroyed() && !mainWindow.isVisible()) {
      console.warn('[window] ready-to-show 超时，强制显示窗口')
      mainWindow.show()
      closeSplashWindow()
    }
  }, 12000)

  mainWindow.once('ready-to-show', () => {
    clearTimeout(forceShowTimer)
    if (!mainWindow || mainWindow.isDestroyed()) return
    mainWindow.show()
    // 主窗口已显示：关闭启动画面（先淡出再移除，衔接过渡自然）
    closeSplashWindow()
  })

  // 打开开发者工具（仅开发模式）
  if (DEV_MODE) {
    mainWindow.webContents.openDevTools()
  }

  mainWindow.on('closed', () => {
    clearTimeout(forceShowTimer)
    mainWindow = null
  })
}
/**
 * IPC handler：设置窗口标题栏主题
 * 由渲染进程在用户切换主题时调用
 */
ipcMain.handle('theme:setTheme', async (_event, mode) => {
  applyTitleBarTheme(mode)
})

/**
 * IPC handler：用系统默认浏览器打开外部链接
 * 由渲染进程在「网页标签页」点击「在浏览器中打开」时调用
 * @param {string} url - 合法链接（仅接受 http/https）
 */
ipcMain.handle('shell:openExternal', async (_event, url) => {
  if (typeof url === 'string' && /^https?:\/\//i.test(url)) {
    await shell.openExternal(url)
    return true
  }
  return false
})

/**
 * IPC handler：设置整个窗口的整体透明度
 * 由渲染进程在用户调节「外观设置 - 整体透明度」时调用
 * @param {number} value - 0.2 ~ 1（这里做边界保护；部分 Linux 窗口管理器不支持，失败静默）
 */
ipcMain.handle('window:setOpacity', async (_event, value) => {
  const opacity = Math.min(1, Math.max(0.2, Number(value) || 1))
  try {
    if (mainWindow && !mainWindow.isDestroyed()) {
      mainWindow.setOpacity(opacity)
    }
  } catch (e) {
    console.warn('设置窗口透明度失败（平台可能不支持）:', e.message)
  }
  return opacity
})

/**
 * IPC handler：打开原生目录选择对话框
 */
ipcMain.handle('dialog:selectDirectory', async () => {
  const result = await dialog.showOpenDialog(mainWindow, {
    properties: ['openDirectory'],
    title: '选择工作目录'
  })
  if (result.canceled || result.filePaths.length === 0) {
    return null
  }
  return result.filePaths[0]
})

/**
 * 注册防火墙规则（放行 P2P 端口）
 * 支持 Windows / macOS / Linux
 */
function registerFirewallRules() {
  const P2P_PORT = 9527
  const RULE_NAME = 'CodeCraft P2P'

  if (process.platform === 'win32') {
    const checkCmd = `netsh advfirewall firewall show rule name="${RULE_NAME}"`
    exec(checkCmd, (err, stdout) => {
      if (stdout && stdout.includes(RULE_NAME)) {
        console.log(`防火墙规则 "${RULE_NAME}" 已存在，跳过`)
        return
      }
      const addCmd = `netsh advfirewall firewall add rule name="${RULE_NAME}" dir=in action=allow protocol=TCP localport=${P2P_PORT}`
      exec(addCmd, (addErr) => {
        if (addErr) {
          console.warn('添加防火墙规则失败（可能需要管理员权限）:', addErr.message)
        } else {
          console.log(`防火墙规则已添加: TCP ${P2P_PORT} 入站`)
        }
      })
    })
  } else if (process.platform === 'darwin') {
    console.log('macOS: P2P 端口防火墙规则将在首次使用时自动提示')
  } else if (process.platform === 'linux') {
    const checkCmd = `iptables -C INPUT -p tcp --dport ${P2P_PORT} -j ACCEPT 2>/dev/null`
    exec(checkCmd, (err) => {
      if (!err) {
        console.log(`iptables 规则已存在: TCP ${P2P_PORT}`)
        return
      }
      const addCmd = `iptables -A INPUT -p tcp --dport ${P2P_PORT} -j ACCEPT`
      exec(addCmd, (addErr) => {
        if (addErr) {
          console.warn('添加 iptables 规则失败（可能需要 root 权限）:', addErr.message)
        } else {
          console.log(`iptables 规则已添加: TCP ${P2P_PORT}`)
        }
      })
    })
  }
}

/**
 * 移除防火墙规则（应用退出时）
 */
function removeFirewallRules() {
  const RULE_NAME = 'CodeCraft P2P'
  if (process.platform === 'win32') {
    exec(`netsh advfirewall firewall delete rule name="${RULE_NAME}"`, (err) => {
      if (err) console.warn('移除防火墙规则失败:', err.message)
      else console.log('防火墙规则已移除')
    })
  }
}

/**
 * IPC handler：获取 P2P 状态（转发到后端 API）
 */
ipcMain.handle('p2p:getStatus', async () => {
  return new Promise((resolve) => {
    http.get(`${BACKEND_URL}/api/p2p/status`, (res) => {
      let data = ''
      res.on('data', chunk => data += chunk)
      res.on('end', () => {
        try { resolve(JSON.parse(data)) } catch { resolve(null) }
      })
    }).on('error', () => resolve(null))
  })
})

/**
 * IPC handler：获取本机 IPv6 地址
 */
ipcMain.handle('p2p:getAddress', async () => {
  return new Promise((resolve) => {
    http.get(`${BACKEND_URL}/api/p2p/address`, (res) => {
      let data = ''
      res.on('data', chunk => data += chunk)
      res.on('end', () => {
        try { resolve(JSON.parse(data)) } catch { resolve(null) }
      })
    }).on('error', () => resolve(null))
  })
})

/**
 * 启动失败收尾：启动画面展示错误 → 原生错误框（等待用户确认）→ 清理启动画面并退出
 * 注意顺序：先弹框再销毁窗口 —— 若先销毁唯一窗口，会触发 window-all-closed → app.quit()，弹窗可能被跳过
 * @param {string} message - 展示在启动画面上的错误文案
 * @param {string} detail - 原生错误框中的详细说明
 */
async function failStartup (message, detail) {
  updateSplashProgress(null, message, true)
  // 留时间让用户看到启动画面上的失败状态
  await new Promise((resolve) => setTimeout(resolve, 1200))
  // 弹窗之前取消启动画面置顶，避免遮挡原生错误框
  if (splashWindow && !splashWindow.isDestroyed()) {
    splashWindow.setAlwaysOnTop(false)
  }
  dialog.showErrorBox('启动失败', detail)
  closeSplashWindow(true)
  app.quit()
}

// 启动流程互斥：macOS 上 whenReady 与 activate 可能接连触发，
// 也可能因「后端已退出」而重新进入 start()，必须避免并发拉起多个后端与多个窗口
let starting = false

/**
 * 应用启动流程
 */
async function start() {
  if (starting) {
    console.log('[lifecycle] 启动流程已在进行中，忽略重复调用')
    return
  }
  starting = true
  try {
    // 注册 P2P 防火墙规则
    registerFirewallRules()

    // 非开发模式：先展示启动画面，再拉起后端并等待就绪
    if (!DEV_MODE) {
      // 已有残留启动画面时先清掉，避免叠加多个启动窗口
      if (!splashWindow || splashWindow.isDestroyed()) {
        createSplashWindow()
      }
      updateSplashProgress(3, '正在启动本地服务')

      // 启动后端进程（失败时提前终止，不进入无意义的等待）
      if (!(await startBackend())) {
        await failStartup(
          '启动失败：未找到后端 JAR 文件',
          '未找到后端 JAR 文件。\n\n请先在项目根目录执行 "mvn package" 构建后端，或确认 target 目录存在构建产物。'
        )
        return
      }

      try {
        await waitForBackend()
        updateSplashProgress(100, '启动完成')
      } catch (err) {
        await failStartup(
          `启动失败：${err.message}`,
          `后端服务启动失败：${err.message}\n\n请检查后端日志后重试。`
        )
        return
      }
    }

    // 已有窗口（例如加载失败恢复路径）则复用，避免同一应用出现两个主窗口
    if (mainWindow && !mainWindow.isDestroyed()) {
      mainWindow.loadURL(BACKEND_URL).catch(() => {})
      return
    }
    createWindow()
  } finally {
    starting = false
  }
}


/**
 * 安装更新前停止后端进程：释放数据库/日志文件句柄。
 * 目的：① 升级前备份能拿到完整一致的文件；② 安装器替换文件时不会被占用。
 * 最多等待 3 秒，超时也继续（安装器自身也会关闭旧实例）。
 */
async function stopBackendForUpdate () {
  if (!javaProcess) return
  try {
    javaProcess.kill()
  } catch (e) {
    console.warn('停止后端进程失败:', e.message)
  }
  const deadline = Date.now() + 3000
  while (javaProcess && Date.now() < deadline) {
    await new Promise(resolve => setTimeout(resolve, 100))
  }
  console.log('后端进程已停止（准备安装更新）')
}

app.whenReady().then(() => {
  // 未拿到单实例锁：已有实例在运行，本实例不启动后端、不建窗口，直接退出
  if (!singleInstanceLock) {
    console.log('[lifecycle] 检测到已有实例在运行，本次启动立即退出')
    app.quit()
    return
  }

  // 第二次启动（再次双击 .app / 从 Dock 重开）由持有锁的本实例负责唤到前台
  app.on('second-instance', () => {
    console.log('[lifecycle] 捕获到第二次启动请求，激活已有窗口')
    handleActivate()
  })

  // 自动更新管理器不依赖后端，尽早初始化（自动检查在 15s 后触发，不阻塞启动）
  initUpdateManager({
    getMainWindow: () => mainWindow,
    // 安装前先停后端：释放数据库文件句柄，保证备份一致且安装不被占用
    beforeInstall: stopBackendForUpdate
  })
  start()
})

/**
 * 激活应用（点 Dock 图标 / 第二次启动）：
 * 有窗口就聚焦；没窗口则按「后端是否还在」决定「仅重建窗口」还是「完整重启后端」
 */
function handleActivate () {
  // ① 主窗口还在（可能只是被隐藏 / 最小化）→ 直接唤到前台
  if (mainWindow && !mainWindow.isDestroyed()) {
    if (mainWindow.isMinimized()) mainWindow.restore()
    mainWindow.show()
    mainWindow.focus()
    return
  }
  // ② 启动流程进行中：它自己会创建窗口，这里不打断
  if (starting) return
  // ③ 忽略启动画面窗口：splash 存在不代表有可用主界面（不能因此跳过重建）
  const otherWindows = BrowserWindow.getAllWindows()
    .filter((w) => !w.isDestroyed() && w !== splashWindow)
  if (otherWindows.length > 0) {
    const win = otherWindows[0]
    if (win.isMinimized()) win.restore()
    win.show()
    win.focus()
    return
  }
  // ④ 清理可能残留的启动画面，避免与新建窗口叠加
  if (splashWindow && !splashWindow.isDestroyed()) {
    closeSplashWindow(true)
  }
  // ⑤ 后端仍在（macOS 关窗不杀后端）：直接重建窗口，秒开；
  //    后端已退出（异常退出 / 旧版本残留）：走完整启动流程（含启动画面与就绪等待）
  if (DEV_MODE || backendAlive()) {
    createWindow()
  } else {
    console.log('[lifecycle] 后端未在运行，重新执行完整启动流程')
    start()
  }
}

app.on('window-all-closed', () => {
  if (IS_MAC) {
    // macOS 惯例：关闭最后一个窗口 ≠ 退出应用（Dock 图标保留、下方小圆点不消失属预期行为）。
    // 关键修复：这里**不能**停止后端。原实现在 mac 上关窗就 kill 掉 java 进程却不退出应用，
    // 用户再次点 Dock 图标时 activate 只重建窗口、不重启后端，
    // 窗口加载 http://localhost:8084 连接失败 → 白屏 / 再次启动失败。
    console.log('[lifecycle] macOS 已关闭全部窗口：应用与后端保持后台运行（Cmd+Q 才退出）')
    return
  }
  // Windows / Linux：关闭窗口即退出应用，同时释放后端
  stopBackend()
  app.quit()
})

app.on('activate', () => {
  handleActivate()
})

app.on('before-quit', () => {
  // 清理启动画面（若退出时仍在显示）
  if (splashWindow && !splashWindow.isDestroyed()) {
    splashWindow.destroy()
  }
  splashWindow = null
  removeFirewallRules()
  // 真正退出（Cmd+Q / 菜单退出 / app.quit()）时才停止后端进程
  stopBackend()
})

