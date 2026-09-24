const { app, BrowserWindow, dialog, ipcMain, Menu, nativeTheme, screen, shell } = require('electron')
const { spawn, exec } = require('child_process')
const path = require('path')
const fs = require('fs')
const http = require('http')

const DEV_MODE = process.env.DEV_MODE === 'true'

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
  const overlay = TITLE_BAR_THEME[resolved]
  if (mainWindow) {
    mainWindow.setTitleBarOverlay({
      color: overlay.color,
      symbolColor: overlay.symbolColor,
      height: 32  // 与前端自定义标题栏拖拽区高度一致（32px）
    })
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
  const javaExe = process.platform === 'win32' ? 'java.exe' : 'java'

  // 打包模式下优先使用内置 JRE
  if (app.isPackaged) {
    const bundledJava = path.join(process.resourcesPath, 'jre', 'bin', javaExe)
    if (fs.existsSync(bundledJava)) {
      return bundledJava
    }
  }
  // 其次使用 JAVA_HOME 环境变量
  if (process.env.JAVA_HOME) {
    const javaHomePath = path.join(process.env.JAVA_HOME, 'bin', javaExe)
    if (fs.existsSync(javaHomePath)) {
      return javaHomePath
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
 * 启动 Spring Boot 后端 JAR
 */
function startBackend() {
  const jarPath = getJarPath()
  if (!jarPath) {
    console.error('未找到后端 JAR 文件，请先在项目根目录执行 "mvn package" 构建后端')
    // 失败提示统一由 start() 处理（启动画面展示错误 + 原生弹窗），这里仅返回失败标志
    return false
  }
  const javaCmd = findJava()

  console.log(`启动后端服务: ${jarPath}`)
  console.log(`Java 路径: ${javaCmd}`)

  // cwd 设置为 jar 包所在目录，保证 Java 中 user.dir 指向正确位置
  // 这样 H2 数据库 ./data、snapshots、logs 等都放在 jar 包旁边
  const workDir = path.dirname(jarPath)
  // 确保工作目录存在
  if (!fs.existsSync(workDir)) {
    fs.mkdirSync(workDir, { recursive: true })
  }
  console.log(`后端工作目录: ${workDir}`)

  // 通过系统属性将 userData 路径传给 Java，方便需要时使用
  const userDataDir = app.isPackaged ? app.getPath('userData') : workDir
  javaExited = false
  backendExitReason = ''
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
  })

  return true
}

/**
 * 创建主窗口
 */
function createWindow() {
  // 移除默认菜单栏
  Menu.setApplicationMenu(null)
  
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

  mainWindow.loadURL(BACKEND_URL)

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

  mainWindow.once('ready-to-show', () => {
    mainWindow.show()
    // 主窗口已显示：关闭启动画面（先淡出再移除，衔接过渡自然）
    closeSplashWindow()
  })

  // 打开开发者工具（仅开发模式）
  if (DEV_MODE) {
    mainWindow.webContents.openDevTools()
  }

  mainWindow.on('closed', () => {
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

/**
 * 应用启动流程
 */
async function start() {
  // 注册 P2P 防火墙规则
  registerFirewallRules()

  // 非开发模式：先展示启动画面，再拉起后端并等待就绪
  if (!DEV_MODE) {
    createSplashWindow()
    updateSplashProgress(3, '正在启动本地服务')

    // 启动后端进程（失败时提前终止，不进入无意义的等待）
    if (!startBackend()) {
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

  createWindow()
}

app.whenReady().then(start)

app.on('window-all-closed', () => {
  // 关闭 Java 进程
  if (javaProcess) {
    javaProcess.kill()
    javaProcess = null
  }
  if (process.platform !== 'darwin') {
    app.quit()
  }
})

app.on('activate', () => {
  if (BrowserWindow.getAllWindows().length === 0) {
    createWindow()
  }
})

app.on('before-quit', () => {
  // 清理启动画面（若退出时仍在显示）
  if (splashWindow && !splashWindow.isDestroyed()) {
    splashWindow.destroy()
  }
  splashWindow = null
  removeFirewallRules()
  if (javaProcess) {
    javaProcess.kill()
    javaProcess = null
  }
})
