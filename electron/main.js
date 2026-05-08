const { app, BrowserWindow, dialog } = require('electron')
const { spawn } = require('child_process')
const path = require('path')
const fs = require('fs')
const http = require('http')

const DEV_MODE = process.env.DEV_MODE === 'true'
const PORT = 8080
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
  // 打包模式下优先使用内置 JRE
  if (app.isPackaged) {
    const bundledJava = path.join(process.resourcesPath, 'jre', 'bin', 'java.exe')
    if (fs.existsSync(bundledJava)) {
      return bundledJava
    }
  }
  // 其次使用 JAVA_HOME 环境变量
  if (process.env.JAVA_HOME) {
    return path.join(process.env.JAVA_HOME, 'bin', 'java.exe')
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
    return path.join(process.resourcesPath, 'backend', 'agent-deepseek.jar')
  }
  // 开发环境
  return path.join(__dirname, '..', 'target', 'agent-deepseek-0.0.1-SNAPSHOT.jar')
}

/**
 * 启动 Spring Boot 后端 JAR
 */
function startBackend() {
  const jarPath = getJarPath()
  const javaCmd = findJava()

  console.log(`启动后端服务: ${jarPath}`)
  console.log(`Java 路径: ${javaCmd}`)
  javaProcess = spawn(javaCmd, ['-jar', jarPath], {
    cwd: path.dirname(jarPath),
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
  mainWindow = new BrowserWindow({
    width: 1400,
    height: 900,
    minWidth: 1024,
    minHeight: 700,
    webPreferences: {
      nodeIntegration: false,
      contextIsolation: true
    },
    title: 'zb-agent',
    icon: path.join(__dirname, '..', 'frontend', 'public', 'favicon.svg'),
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
