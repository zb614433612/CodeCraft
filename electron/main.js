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
 * 启动 Spring Boot 后端 JAR
 */
/**
 * 清理所有旧数据：H2 数据库文件 + 浏览器 localStorage
 * 每次启动时无条件执行，确保安装/重装/升级后不会残留任何旧数据
 */
function cleanAllData(dataDir) {
  console.log('清理所有旧数据（每次启动无条件执行）...')

  // 1. 清理 H2 数据库文件
  const dbDir = path.join(dataDir, 'data')
  const dbFile = path.join(dbDir, 'codecraft.mv.db')
  try {
    if (fs.existsSync(dbFile)) {
      fs.unlinkSync(dbFile)
      console.log('已删除旧数据库文件:', dbFile)
    }
    const traceFile = path.join(dbDir, 'codecraft.trace.db')
    if (fs.existsSync(traceFile)) {
      fs.unlinkSync(traceFile)
    }
  } catch (e) {
    console.error('清理数据库失败:', e.message)
  }

  // 2. 清理浏览器 localStorage（旧登录 token 会导致页面加载时鉴权异常）
  try {
    const localStorageDir = path.join(dataDir, 'Local Storage')
    if (fs.existsSync(localStorageDir)) {
      const files = fs.readdirSync(localStorageDir)
      for (const file of files) {
        const filePath = path.join(localStorageDir, file)
        fs.unlinkSync(filePath)
        console.log('已清理缓存文件:', filePath)
      }
      console.log('已清除浏览器 localStorage，用户需要重新登录')
    }
  } catch (e) {
    console.error('清理 localStorage 失败:', e.message)
  }

  // 3. 清理 Session 和 Cache 等其他浏览器数据
  const sessionDir = path.join(dataDir, 'Session Storage')
  try {
    if (fs.existsSync(sessionDir)) {
      const files = fs.readdirSync(sessionDir)
      for (const file of files) {
        fs.unlinkSync(path.join(sessionDir, file))
      }
    }
  } catch (e) {
    console.error('清理 Session 失败:', e.message)
  }
}

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

  // 每次启动都清理所有旧数据，确保无残留
  if (app.isPackaged) {
    cleanAllData(dataDir)
  }
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
      preload: path.join(__dirname, 'preload.js'),
      webSecurity: false  // 关闭同源策略限制，避免 Electron 端页面路由跳转异常
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
