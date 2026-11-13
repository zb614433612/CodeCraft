/**
 * 真终端（PTY）管理器
 * ============================================================================
 * <h3>为什么需要它</h3>
 * xterm.js 只是「显示器」，本身不执行命令。要让终端真的像终端（能跑 vim / top /
 * ssh / python REPL、能 Ctrl+C、能 Tab 补全、长命令实时出字），必须接一个**伪终端（PTY）**。
 * 这里用 `node-pty`（VS Code 内置终端同款）在 Electron 主进程 spawn 真实 shell，
 * 再通过 IPC 与渲染进程双向转发：
 *
 * <pre>
 *   渲染进程 xterm.onData ──pty:write──► 主进程 proc.write ──► 真实 shell
 *   渲染进程 xterm.write  ◄──pty:data──  主进程 proc.onData ◄── 真实 shell 输出
 * </pre>
 *
 * <h3>为什么不用 @xterm/addon-attach（WebSocket）</h3>
 * 走 WebSocket 要额外开本地端口 + 自定义鉴权，本地回环端口本身也是攻击面。
 * Electron 的 IPC 天然只在主进程 ↔ 本应用渲染进程之间，不监听端口，更安全也更简单。
 *
 * <h3>跨平台</h3>
 * node-pty 1.1.0 用 N-API（node-addon-api）+ 自带 prebuilds（win32-x64/arm64、darwin-x64/arm64），
 * 因此**不需要 electron-rebuild**。Linux 没有官方 prebuild，需构建机具备 node-gyp 工具链。
 *
 * <h3>安全边界</h3>
 * PTY 能执行任意命令 —— 这本来就是终端的功能。约束是：① 只在 Electron 桌面端启用
 * （浏览器环境由前端降级到后端 /project/exec）；② IPC 不监听网络；③ 会话数上限防打爆。
 */

const fs = require('fs')
const { ipcMain } = require('electron')

/** 延迟加载的 node-pty 模块（require 失败时保留错误，供前端展示明确原因） */
let ptyModule = null
let ptyLoadError = null

/** sessionId → { id, proc, sender } */
const sessions = new Map()

/** 会话数上限：每个 PTY 都是一个真实 shell 进程，防止渲染进程疯狂创建把机器打爆 */
const MAX_SESSIONS = 8

/** 数值夹取（cols/rows 传 0 或负数会让 node-pty 抛原生异常） */
function clamp(value, min, max, fallback) {
  const n = Number(value)
  if (!Number.isFinite(n)) return fallback
  return Math.min(max, Math.max(min, Math.round(n)))
}

/**
 * 加载 node-pty（失败只抛一次，避免反复尝试拖慢调用）
 */
function loadPty() {
  if (ptyModule) return ptyModule
  if (ptyLoadError) throw ptyLoadError
  try {
    ptyModule = require('node-pty')
    return ptyModule
  } catch (e) {
    ptyLoadError = new Error('node-pty 加载失败（终端不可用）：' + (e && e.message))
    throw ptyLoadError
  }
}

/**
 * 选择默认 shell。
 * Windows 优先 PowerShell（pwsh 7 有更好的 UTF-8 与 ANSI 支持，回退 powershell.exe）；
 * macOS / Linux 用登录 shell，找不到就 bash。
 */
function defaultShell() {
  if (process.platform === 'win32') {
    return process.env.COMSPEC || 'powershell.exe'
  }
  return process.env.SHELL || '/bin/bash'
}

/**
 * 校验工作目录：只接受真实存在的目录，否则回退到主进程 cwd。
 * 不校验会踩 spawn ENOENT（node-pty 的报错信息很难懂）。
 */
function sanitizeCwd(cwd) {
  if (!cwd || typeof cwd !== 'string') return process.cwd()
  try {
    return fs.statSync(cwd).isDirectory() ? cwd : process.cwd()
  } catch (_) {
    return process.cwd()
  }
}

/**
 * 创建 PTY 会话
 *
 * @returns {{sessionId:string, pid:number, shell:string, cwd:string, cols:number, rows:number}}
 */
function createSession(event, payload) {
  const pty = loadPty()
  const opts = payload || {}

  if (sessions.size >= MAX_SESSIONS) {
    throw new Error(`终端会话数已达上限（${MAX_SESSIONS}），请先关闭不用的终端再开新的`)
  }

  const sessionId = opts.sessionId && typeof opts.sessionId === 'string'
    ? opts.sessionId
    : ('pty-' + Date.now() + '-' + Math.random().toString(36).slice(2, 8))
  const cols = clamp(opts.cols, 2, 400, 80)
  const rows = clamp(opts.rows, 2, 200, 24)
  const cwd = sanitizeCwd(opts.cwd)
  const shell = (opts.shell && String(opts.shell)) || defaultShell()

  const proc = pty.spawn(shell, [], {
    name: 'xterm-256color',
    cols,
    rows,
    cwd,
    // TERM / COLORTERM 决定命令输出是否带 256 色与真彩色转义
    env: Object.assign({}, process.env, { TERM: 'xterm-256color', COLORTERM: 'truecolor' })
  })

  const sender = event.sender
  const session = { id: sessionId, proc, sender }
  sessions.set(sessionId, session)

  // ===== 输出回推：只发给创建它的那个 webContents，避免多窗口串屏 =====
  proc.onData((data) => {
    if (!sender || sender.isDestroyed()) return
    sender.send('pty:data', { sessionId, data })
  })

  proc.onExit(({ exitCode, signal }) => {
    sessions.delete(sessionId)
    if (!sender || sender.isDestroyed()) return
    sender.send('pty:exit', { sessionId, exitCode, signal })
  })

  // 渲染进程销毁（窗口关闭 / 页面刷新）时自动回收，防止僵尸 shell
  if (sender && !sender.isDestroyed()) {
    sender.once('destroyed', () => killSession(sessionId))
  }

  return { sessionId, pid: proc.pid, shell, cwd, cols, rows }
}

/** 写入（键盘输入 / 粘贴） */
function writeSession(sessionId, data) {
  const session = sessions.get(sessionId)
  if (!session) return false
  try {
    session.proc.write(String(data == null ? '' : data))
    return true
  } catch (_) {
    return false
  }
}

/** 调整终端尺寸（xterm fit 后必须同步给 PTY，否则换行错位） */
function resizeSession(sessionId, cols, rows) {
  const session = sessions.get(sessionId)
  if (!session) return false
  const c = clamp(cols, 2, 400, 80)
  const r = clamp(rows, 2, 200, 24)
  try {
    session.proc.resize(c, r)
    return true
  } catch (_) {
    return false
  }
}

/** 结束会话 */
function killSession(sessionId) {
  const session = sessions.get(sessionId)
  if (!session) return false
  sessions.delete(sessionId)
  try {
    session.proc.kill()
  } catch (_) { /* 进程可能已退出 */ }
  return true
}

/** 结束全部会话（应用退出 / 重载前调用） */
function disposeAllSessions() {
  for (const id of Array.from(sessions.keys())) {
    killSession(id)
  }
}

/**
 * 注册 IPC 通道（应用启动时调用一次）
 */
function initPtyManager() {
  ipcMain.handle('pty:isAvailable', () => {
    try {
      loadPty()
      return {
        available: true,
        platform: process.platform,
        defaultShell: defaultShell(),
        maxSessions: MAX_SESSIONS
      }
    } catch (e) {
      return { available: false, error: e && e.message }
    }
  })

  ipcMain.handle('pty:create', (event, payload) => createSession(event, payload))
  ipcMain.handle('pty:write', (_event, payload) => {
    if (!payload) return false
    return writeSession(payload.sessionId, payload.data)
  })
  ipcMain.handle('pty:resize', (_event, payload) => {
    if (!payload) return false
    return resizeSession(payload.sessionId, payload.cols, payload.rows)
  })
  ipcMain.handle('pty:kill', (_event, payload) => {
    if (!payload) return false
    return killSession(payload.sessionId)
  })

  console.log('[pty] PTY 管理器已初始化，默认 shell =', defaultShell())
}

module.exports = {
  initPtyManager,
  disposeAllSessions,
  createSession,
  writeSession,
  resizeSession,
  killSession
}
