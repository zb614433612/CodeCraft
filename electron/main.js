const { app, BrowserWindow, dialog, ipcMain, Menu } = require('electron')
const { spawn } = require('child_process')
const path = require('path')
const fs = require('fs')
const http = require('http')

const DEV_MODE = process.env.DEV_MODE === 'true'

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

/**
 * 检查后端服务是否已就绪
 */
function waitForBackend(retries = 30, interval = 2000) {
  return new Promise((resolve, reject) => {
    const check = (remaining) => {
      if (remaining <= 0) {
        reject(new Error('后端服务启动超时'))
        return
      }
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
 */
function getJarPath() {
  if (app.isPackaged) {
    // 打包后 extraResources 位于 resources/ 目录下
    return path.join(process.resourcesPath, 'backend', 'codecraft.jar')
  }
  // 开发环境
  return path.join(__dirname, '..', 'target', 'codecraft-1.0.1.jar')
}

/**
 * 获取当前 exe 文件的修改时间戳（毫秒）
 * 用于检测是否发生了覆盖安装（exe 被重新替换）
 */
function getExeMtime() {
  try {
    const exePath = app.getPath('exe')
    const stat = fs.statSync(exePath)
    return stat.mtimeMs
  } catch (e) {
    console.warn('获取 exe 文件信息失败:', e.message)
    return 0
  }
}

/**
 * 读取上次记录的 exe 修改时间戳
 */
function getLastExeMtime(dataDir) {
  const flagFile = path.join(dataDir, '.exe_mtime')
  try {
    if (fs.existsSync(flagFile)) {
      return parseFloat(fs.readFileSync(flagFile, 'utf-8')) || 0
    }
  } catch (e) {
    console.warn('读取 exe 时间戳记录失败:', e.message)
  }
  return 0
}

/**
 * 记录当前 exe 修改时间戳
 */
function saveExeMtime(dataDir, mtime) {
  const flagFile = path.join(dataDir, '.exe_mtime')
  try {
    if (!fs.existsSync(dataDir)) {
      fs.mkdirSync(dataDir, { recursive: true })
    }
    fs.writeFileSync(flagFile, String(mtime), 'utf-8')
  } catch (e) {
    console.warn('保存 exe 时间戳记录失败:', e.message)
  }
}

/**
 * 清理所有旧数据：H2 数据库文件 + 浏览器 localStorage + Session Storage
 * 仅在检测到覆盖安装后执行，确保重装后无残留
 */
function cleanAllData(dataDir) {
  console.log('检测到覆盖安装，正在清理旧数据...')

  // 1. 清理 H2 数据库文件
  const dbDir = path.join(dataDir, 'data')
  const dbFiles = [
    path.join(dbDir, 'codecraft.mv.db'),
    path.join(dbDir, 'codecraft.trace.db')
  ]
  for (const file of dbFiles) {
    try {
      if (fs.existsSync(file)) {
        fs.unlinkSync(file)
        console.log('已删除数据库文件:', file)
      }
    } catch (e) {
      console.error('删除数据库文件失败:', e.message)
    }
  }

  // 2. 清理浏览器 localStorage（持久化设置和登录 token）
  const localStorageDir = path.join(dataDir, 'Local Storage')
  try {
    if (fs.existsSync(localStorageDir)) {
      for (const file of fs.readdirSync(localStorageDir)) {
        fs.unlinkSync(path.join(localStorageDir, file))
      }
      console.log('已清理 localStorage')
    }
  } catch (e) {
    console.error('清理 localStorage 失败:', e.message)
  }

  // 3. 清理 Session Storage
  const sessionDir = path.join(dataDir, 'Session Storage')
  try {
    if (fs.existsSync(sessionDir)) {
      for (const file of fs.readdirSync(sessionDir)) {
        fs.unlinkSync(path.join(sessionDir, file))
      }
      console.log('已清理 Session Storage')
    }
  } catch (e) {
    console.error('清理 Session Storage 失败:', e.message)
  }

  console.log('旧数据清理完成')
}

/**
 * 检查 exe 文件是否被替换（覆盖安装），是则清理旧数据
 * 对比逻辑：首次安装无记录 → 清理；exe 修改时间变化 → 清理；相同 → 正常启动跳过
 */
function checkDataCleanup(dataDir) {
  if (!app.isPackaged) {
    return // 开发模式不触发清理
  }
  const currentMtime = getExeMtime()
  if (currentMtime === 0) {
    return // 无法获取 exe 信息，跳过
  }
  const lastMtime = getLastExeMtime(dataDir)
  if (lastMtime === 0 || currentMtime !== lastMtime) {
    cleanAllData(dataDir)
    saveExeMtime(dataDir, currentMtime)
  }
}

/**
 * 启动 Spring Boot 后端 JAR
 */
function startBackend() {
  const jarPath = getJarPath()
  const javaCmd = findJava()

  console.log(`启动后端服务: ${jarPath}`)
  console.log(`Java 路径: ${javaCmd}`)
  // 使用 Electron 的用户数据目录作为工作目录，确保数据库文件位于统一位置
  const dataDir = app.isPackaged ? app.getPath('userData') : path.dirname(jarPath)
  // 确保数据目录存在
  if (!fs.existsSync(dataDir)) {
    fs.mkdirSync(dataDir, { recursive: true })
  }
  console.log(`后端工作目录: ${dataDir}`)
  javaProcess = spawn(javaCmd, ['-Dfile.encoding=UTF-8', '-jar', jarPath], {
    cwd: dataDir,
    stdio: ['ignore', 'pipe', 'pipe']
  })

  javaProcess.stdout.on('data', (data) => {
    console.log(`[backend] ${data.toString().trim()}`)
  })

  javaProcess.stderr.on('data', (data) => {
    console.error(`[backend-err] ${data.toString().trim()}`)
  })

  javaProcess.on('error', (err) => {
    console.error('启动后端失败:', err.message)
    dialog.showErrorBox('启动失败', `无法启动后端服务:\n${err.message}`)
  })

  javaProcess.on('exit', (code) => {
    console.log(`后端进程退出，退出码: ${code}`)
    javaProcess = null
  })
}

/**
 * 创建主窗口
 */
function createWindow() {
  // 移除默认菜单栏
  Menu.setApplicationMenu(null)
  
  mainWindow = new BrowserWindow({
    width: 1400,
    height: 900,
    minWidth: 1024,
    minHeight: 700,
    webPreferences: {
      nodeIntegration: false,
      contextIsolation: true,
      preload: path.join(__dirname, 'preload.js')
    },
    title: 'CodeCraft',
    icon: path.join(__dirname, 'icon.png'),
    show: false
  })

  mainWindow.loadURL(BACKEND_URL)

  mainWindow.once('ready-to-show', () => {
    mainWindow.show()
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
 * 应用启动流程
 */
async function start() {
  // 非开发模式：自动启动后端
  if (!DEV_MODE) {
    // 在启动后端之前，先检测 exe 文件是否被替换（覆盖安装），是则清理旧数据
    const dataDir = app.getPath('userData')
    checkDataCleanup(dataDir)

    startBackend()
    try {
      await waitForBackend()
    } catch (err) {
      dialog.showErrorBox('启动超时', '后端服务启动超时，请检查日志。')
      app.quit()
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
  if (javaProcess) {
    javaProcess.kill()
    javaProcess = null
  }
})
