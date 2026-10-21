/**
 * CodeCraft AI 浏览器桥（Electron 主进程侧）
 *
 * ── 为什么需要这一层 ────────────────────────────────────────────────
 * 后端 Java 需要「DOM 级」操作网页的能力（比截图 + 模拟点击准确得多、省 token 得多）。
 * 而 Electron 的 webContents.debugger 本身就是 Chromium 的 CDP 客户端，无需 Playwright /
 * Selenium 之类要额外下载浏览器的大依赖（与 214MB 安装包的体积约束冲突）。
 *
 * 缺的只是一条「Java → Electron」的通道：Electron 主进程原本只有 ipcMain（渲染进程 → 主进程），
 * 没有对外端口。所以这里起一个只绑 127.0.0.1 的极小 HTTP 服务，把 CDP 调用转发进去。
 *
 * ── 职责边界（刻意保持窄）────────────────────────────────────────────
 * 本文件只做两件事：
 *   1. 窗口池管理：按 conversationId 分配独立窗口、空闲回收、显隐切换、关闭
 *   2. CDP 转发：POST /cdp 把 { method, params } 原样交给 debugger.sendCommand
 *
 * 它不理解的语义（ref 失效判定、点击要不要先滚动、表单怎么填）全部留在 Java 侧编排。
 * 好处是以后加浏览器动作不用改这里，坏处是每次动作多一两次本机回环往返（< 5ms，可忽略）。
 *
 * ── 窗口隔离（方案 D）──────────────────────────────────────────────
 * · 窗口独立：每个 conversationId 一个 BrowserWindow —— 两个会话并行时不会互相抢页面
 * · 登录态共享：session partition 按「项目根」哈希命名 —— 同项目下新会话打开同站点仍是登录态
 * · 窗口关掉不等于掉登录：partition 带 persist: 前缀，cookie 落在磁盘上
 *
 * ── 安全约束 ────────────────────────────────────────────────────
 * · 只监听 127.0.0.1，且必须带正确 token（启动时随机生成，写入握手文件）
 * · 只允许 http / https（拒绝 file: / javascript: / data: —— file: 能绕过后端路径白名单）
 * · 页面不注入 preload（远程页面拿到任何 Node API 都是攻击面）
 * · 下载默认拦截（AI 误点「下载全部附件」能把磁盘写满）
 */
const http = require('http')
const fs = require('fs')
const path = require('path')
const crypto = require('crypto')
const { BrowserWindow, session, app } = require('electron')

/** 握手文件名（相对 runtime 目录），Java 侧按同名读取 */
const HANDSHAKE_FILE = 'browser-bridge.json'
/** 等价于 browser-bridge.json 的东西变了要同步改 Java 侧 BrowserBridgeClient */
const PROTOCOL_VERSION = 1

/** 单次请求体上限：snapshot 结果可能带 base64 图片，给 8MB 余量 */
const MAX_BODY_BYTES = 8 * 1024 * 1024
/** 每个窗口保留的最近浏览器事件条数（页面报错 / console.error 等） */
const MAX_EVENTS = 200
/** 单条事件文本截断长度 */
const EVENT_TEXT_LIMIT = 500

const DEFAULT_VIEWPORT = { width: 1280, height: 860 }
/** 只放行这两种协议；about:blank 单独走「空页面」分支 */
const ALLOWED_PROTOCOLS = new Set(['http:', 'https:'])

// ── 模块状态 ───────────────────────────────────────────────────────
let server = null
let handshakePath = null
let token = ''
let port = 0
let idleTimer = null

/**
 * 窗口条目表：key = `${conversationId}::${tabId}`。
 *
 * 为什么按标签拆 key：一个会话可能需要同时保留多个页面状态（一个停在列表页、
 * 另一个在填表单），单页面会互相顶掉。标签在视觉上表现为独立窗口（各有系统标题栏），
 * 语义上同属一个会话，共享同一个 session partition（登录态一致）。
 */
const windowEntries = new Map()

/** 会话当前活跃标签：conversationId → tabId（后续未指定 tabId 的动作默认打这里） */
const activeTabOf = new Map()

/** 运行参数（由 initBrowserBridge 传入） */
let options = {
  maxWindows: 4,
  maxTabsPerConversation: 4,
  idleMinutes: 10,
  allowDownloads: false,
  defaultShow: true,
  sessionPartitionPrefix: 'ccb'
}

// ── 工具函数 ───────────────────────────────────────────────────────

function truncate (text, limit) {
  const s = String(text == null ? '' : text)
  if (s.length <= limit) return s
  return s.slice(0, limit) + `…(截断，共 ${s.length} 字)`
}

/**
 * 按项目根算 session partition。
 *
 * 为什么必须哈希：partition 名会被 Electron 当成磁盘目录名的一部分，
 * 而 Windows 路径里的 `\` `:` 都是非法字符。取 SHA-1 前 12 位，
 * 冲突概率可忽略，且同一项目根永远得到同一个 partition（登录态才能复用）。
 *
 * 没有项目根时统一落到 `default`：所有未设工作目录的会话共享登录态，
 * 但窗口仍然各自独立（不会互抢页面）。
 */
function partitionFor (projectRoot) {
  const key = String(projectRoot || 'default').trim().toLowerCase() || 'default'
  const hash = crypto.createHash('sha1').update(key).digest('hex').slice(0, 12)
  return `persist:${options.sessionPartitionPrefix}-${hash}`
}

/**
 * 只放行 http/https。允许省略协议（输入 example.com 时按 https 处理），
 * 但不允许 file: / javascript: / data: —— 这三种都能绕过后端对本地文件的访问控制。
 */
function normalizeUrl (raw) {
  const text = String(raw == null ? '' : raw).trim()
  if (!text) throw new Error('url 不能为空')

  let parsed
  try {
    parsed = new URL(text)
  } catch {
    // 省略协议：形如 example.com/path
    if (/^[\w.-]+\.[a-z]{2,}(?:[/:?#]|$)/i.test(text)) {
      parsed = new URL('https://' + text)
    } else {
      throw new Error(`url 不合法：${text}`)
    }
  }
  if (!ALLOWED_PROTOCOLS.has(parsed.protocol)) {
    throw new Error(`只允许 http / https，当前为 ${parsed.protocol}`)
  }
  return parsed.toString()
}

/** 下载拦截：同一个 partition 的 session 是单例，只注册一次 */
function guardDownloads (ses) {
  if (options.allowDownloads) return
  if (ses.__ccDownloadGuarded) return
  ses.__ccDownloadGuarded = true
  ses.on('will-download', (event, item) => {
    // 不 preventDefault 的话，AI 点一下「下载全部附件」就能把磁盘写满，
    // 而下载下来的文件模型也读不到，纯属副作用。
    if (!options.allowDownloads) {
      event.preventDefault()
      console.log('[browser-bridge] 已拦截下载:', item.getFilename())
    }
  })
}

function pushEvent (entry, evt) {
  entry.seq += 1
  entry.events.push({ seq: entry.seq, at: Date.now(), ...evt })
  if (entry.events.length > MAX_EVENTS) {
    entry.events.splice(0, entry.events.length - MAX_EVENTS)
  }
}

function pageUrlOf (win) {
  try {
    if (!win || win.isDestroyed()) return ''
    const url = win.webContents.getURL() || ''
    // DevTools / 内部页面对模型没有意义
    if (url.startsWith('devtools://')) return ''
    return url
  } catch {
    return ''
  }
}

// ── CDP 附着与事件订阅 ───────────────────────────────────────────────

function attachDebugger (entry) {
  const win = entry.win
  if (!win || win.isDestroyed()) throw new Error('浏览器窗口已关闭')
  const dbg = win.webContents.debugger
  if (dbg.isAttached()) return dbg

  try {
    dbg.attach('1.3')
  } catch (e) {
    // 典型原因：用户按了 F12 打开开发者工具 —— 同一 webContents 只允许一个调试器
    throw new Error(
      `无法附加浏览器调试协议（该窗口可能已打开开发者工具 F12，请先关闭）：${e.message}`
    )
  }

  entry.attachedAt = Date.now()
  // 事件订阅：只留「模型可能关心且有诊断价值」的四类，其余丢弃（避免内存无限涨）
  dbg.on('message', (_event, method, params) => {
    try {
      if (method === 'Runtime.consoleAPICalled') {
        if (params.type !== 'error' && params.type !== 'warning') return
        const text = (params.args || [])
          .map((a) => (a.value != null ? a.value : (a.description || a.type)))
          .join(' ')
        pushEvent(entry, { type: 'console', level: params.type, text: truncate(text, EVENT_TEXT_LIMIT) })
      } else if (method === 'Runtime.exceptionThrown') {
        const detail = params.exceptionDetails || {}
        const text = (detail.exception && detail.exception.description) || detail.text || '未捕获异常'
        pushEvent(entry, { type: 'exception', text: truncate(text, EVENT_TEXT_LIMIT) })
      } else if (method === 'Log.entryAdded') {
        const e = params.entry || {}
        if (e.level !== 'error' && e.level !== 'warning') return
        pushEvent(entry, { type: 'log', level: e.level, text: truncate(e.text, EVENT_TEXT_LIMIT) })
      } else if (method === 'Page.javascriptDialogOpening') {
        // alert / confirm / prompt 会阻塞页面渲染与部分 CDP 命令，
        // 自动接受并记录一条，否则模型点一次就永久卡死。
        pushEvent(entry, { type: 'dialog', text: truncate(params.message, EVENT_TEXT_LIMIT) })
        dbg.sendCommand('Page.handleJavaScriptDialog', { accept: true }).catch(() => {})
      }
    } catch (e) {
      console.warn('[browser-bridge] 处理 CDP 事件失败:', e.message)
    }
  })

  dbg.on('detach', (_event, reason) => {
    console.log('[browser-bridge] 调试协议已断开:', reason)
    entry.attachedAt = 0
  })

  // 这两个域不 enable 就收不到 console / exception / dialog 事件
  dbg.sendCommand('Runtime.enable').catch(() => {})
  dbg.sendCommand('Log.enable').catch(() => {})
  dbg.sendCommand('Page.enable').catch(() => {})
  return dbg
}

// ── 窗口池 ─────────────────────────────────────────────────────────

/** 组合 key：会话 + 标签，全局唯一 */
function entryKey (conversationId, tabId) {
  return `${String(conversationId || 'default')}::${String(tabId || 't1')}`
}

/** 某会话下的全部存活标签（按创建时间升序，t1 在前） */
function tabsOf (conversationId) {
  const cid = String(conversationId || 'default')
  return [...windowEntries.values()]
    .filter((e) => e.conversationId === cid && e.win && !e.win.isDestroyed())
    .sort((a, b) => a.createdAt - b.createdAt)
}

/** 会话内下一个可用 tabId（t1 / t2 …），编号复用空洞 */
function nextTabId (conversationId) {
  const used = new Set(tabsOf(conversationId).map((e) => e.tabId))
  for (let i = 1; i <= 99; i += 1) {
    const candidate = `t${i}`
    if (!used.has(candidate)) return candidate
  }
  return `t${Date.now()}`
}

/** 该会话已有标签数（新建标签前的上限判断用） */
function tabCountOf (conversationId) {
  return tabsOf(conversationId).length
}

/**
 * 解析目标标签。
 *
 * <p>显式 tabId 精确查找；未给则用「该会话的活跃标签」；活跃标签也没了，
 * 退回最近创建的那个。找不到返回 null —— 调用方给出明确错误，绝不静默重建。</p>
 */
function resolveEntry (conversationId, tabId) {
  const cid = String(conversationId || 'default')
  const wanted = tabId ? String(tabId) : (activeTabOf.get(cid) || '')
  if (wanted) {
    const hit = windowEntries.get(entryKey(cid, wanted))
    if (hit && hit.win && !hit.win.isDestroyed()) {
      hit.lastUsedAt = Date.now()
      activeTabOf.set(cid, hit.tabId)
      return hit
    }
  }
  const tabs = tabsOf(cid)
  if (!tabs.length) {
    activeTabOf.delete(cid)
    return null
  }
  const latest = tabs[tabs.length - 1]
  latest.lastUsedAt = Date.now()
  activeTabOf.set(cid, latest.tabId)
  return latest
}

function createWindowEntry ({ conversationId, projectRoot, show, width, height, url, tabId, tabIndex }) {
  const partition = partitionFor(projectRoot)
  const ses = session.fromPartition(partition)
  guardDownloads(ses)

  const tab = String(tabId || 't1')
  const title = tabIndex && tabIndex > 1
    ? `CodeCraft · AI 浏览器 (标签 ${tabIndex})`
    : 'CodeCraft · AI 浏览器'

  const win = new BrowserWindow({
    width: Number(width) || DEFAULT_VIEWPORT.width,
    height: Number(height) || DEFAULT_VIEWPORT.height,
    show: show !== false,
    title,
    backgroundColor: '#ffffff',
    autoHideMenuBar: true,
    // 用系统标题栏：用户能拖动、最小化、关闭 —— 这是「真实可见的窗口」，
    // 不是藏在后台的隐形浏览器。符合「操作可审计」的产品定位。
    webPreferences: {
      partition,
      nodeIntegration: false,
      contextIsolation: true,
      sandbox: true,
      webviewTag: false,
      // 不给 preload：这是远程页面，任何 Node API 注入都是攻击面
      // 隐藏窗口时 Chromium 会把定时器 / requestAnimationFrame 降到 1fps，
      // 地图、图表、轮询类站点在隐藏期间会状态不同步 → 关掉节流
      backgroundThrottling: false
    }
  })
  win.__ccBrowser = true
  win.__ccConversationId = String(conversationId)
  win.setMenuBarVisibility(false)

  const entry = {
    conversationId: String(conversationId),
    tabId: tab,
    key: entryKey(conversationId, tab),
    projectRoot: projectRoot || '',
    partition,
    win,
    createdAt: Date.now(),
    lastUsedAt: Date.now(),
    attachedAt: 0,
    seq: 0,
    events: [],
    lastUrl: ''
  }

  // 用户手动关窗口：从池里摘掉，下次调用会明确告知「窗口已关闭」而不是静默重建。
  // 静默重建会让模型以为还在原页面，然后在一个空页面上乱点。
  win.on('closed', () => {
    const current = windowEntries.get(entry.key)
    if (current === entry) windowEntries.delete(entry.key)
    if (activeTabOf.get(entry.conversationId) === entry.tabId) activeTabOf.delete(entry.conversationId)
  })

  win.webContents.on('did-navigate', (_e, navUrl) => {
    entry.lastUrl = navUrl || ''
    entry.lastUsedAt = Date.now()
  })
  win.webContents.on('did-navigate-in-page', (_e, navUrl) => {
    entry.lastUrl = navUrl || ''
  })

  // target=_blank / window.open：不弹新窗口，直接在本窗口导航过去
  win.webContents.setWindowOpenHandler(({ url: openUrl }) => {
    try {
      const target = normalizeUrl(openUrl)
      setTimeout(() => {
        if (!win.isDestroyed()) win.loadURL(target).catch(() => {})
      }, 0)
    } catch {
      /* 非 http(s) 一律无视 */
    }
    return { action: 'deny' }
  })

  windowEntries.set(entry.key, entry)
  activeTabOf.set(entry.conversationId, entry.tabId)
  return entry
}

function destroyEntry (entry, reason) {
  if (!entry) return
  if (entry.key) windowEntries.delete(entry.key)
  if (activeTabOf.get(entry.conversationId) === entry.tabId) activeTabOf.delete(entry.conversationId)
  const win = entry.win
  if (!win || win.isDestroyed()) return
  try {
    // 关窗口前先摘调试器：避免 close 过程中 CDP 回调还在往已销毁的 webContents 上写
    if (win.webContents.debugger.isAttached()) win.webContents.debugger.detach()
  } catch {
    /* 忽略 */
  }
  try {
    win.close()
  } catch {
    try { win.destroy() } catch { /* 忽略 */ }
  }
  console.log(`[browser-bridge] 关闭窗口 conv=${entry.conversationId} tab=${entry.tabId}（${reason}）`)
}

/** 取窗口；不存在返回 null（不静默重建） */
function getEntry (conversationId, tabId) {
  return resolveEntry(conversationId, tabId)
}

function sweepIdle () {
  const idleMs = Math.max(1, options.idleMinutes) * 60 * 1000
  const now = Date.now()
  for (const [key, entry] of [...windowEntries.entries()]) {
    if (!entry.win || entry.win.isDestroyed()) {
      windowEntries.delete(key)
      if (activeTabOf.get(entry.conversationId) === entry.tabId) activeTabOf.delete(entry.conversationId)
      continue
    }
    if (now - entry.lastUsedAt > idleMs) {
      destroyEntry(entry, '空闲回收')
    }
  }
}

// ── 请求处理 ───────────────────────────────────────────────────────

function readBody (req) {
  return new Promise((resolve, reject) => {
    const chunks = []
    let size = 0
    req.on('data', (c) => {
      size += c.length
      if (size > MAX_BODY_BYTES) {
        reject(new Error('请求体过大'))
        req.destroy()
        return
      }
      chunks.push(c)
    })
    req.on('end', () => {
      const text = Buffer.concat(chunks).toString('utf8')
      if (!text.trim()) return resolve({})
      try {
        resolve(JSON.parse(text))
      } catch {
        reject(new Error('请求体不是合法 JSON'))
      }
    })
    req.on('error', reject)
  })
}

function sendJson (res, status, payload) {
  const body = Buffer.from(JSON.stringify(payload), 'utf8')
  res.writeHead(status, {
    'Content-Type': 'application/json; charset=utf-8',
    'Content-Length': body.length,
    'Cache-Control': 'no-store'
  })
  res.end(body)
}

/** 把 CDP 的 result 里的 RemoteObject 压成 JSON 可传的纯值 */
function unwrapRemoteObject (ro) {
  if (!ro) return null
  if (Object.prototype.hasOwnProperty.call(ro, 'value')) return ro.value
  if (ro.unserializableValue != null) return ro.unserializableValue
  return ro.description != null ? ro.description : null
}

async function handleCdp (payload) {
  const conversationId = payload.conversationId || 'default'
  const entry = getEntry(conversationId, payload.tabId)
  if (!entry) {
    const err = new Error('浏览器窗口不存在或已被关闭。需要继续操作请重新调用 action=open（登录态会保留）')
    err.status = 410
    throw err
  }
  const dbg = attachDebugger(entry)

  const sinceSeq = Number(payload.sinceSeq) || 0

  // 批量：一次往返跑多条 CDP 命令（click = 滚动 + 取坐标 + press + release）
  // 注意这里必须先于 method 校验——批量模式只传 calls，不传 method
  if (Array.isArray(payload.calls)) {
    const results = []
    for (const call of payload.calls) {
      const m = String((call && call.method) || '').trim()
      if (!m) {
        results.push({ method: m, ok: false, error: 'method 不能为空' })
        continue
      }
      try {
        const r = await dbg.sendCommand(m, (call && call.params) || {})
        results.push({ method: m, ok: true, result: r || {}, value: unwrapRemoteObject(r && r.result) })
      } catch (e) {
        results.push({ method: m, ok: false, error: e.message })
        // 批量里一条失败不中断：调用方要能看到「滚动成功了但点击失败了」这类细节
      }
    }
    return {
      ok: true,
      tabId: entry.tabId,
      results,
      pageUrl: pageUrlOf(entry.win),
      lastSeq: entry.seq,
      events: entry.events.filter((e) => e.seq > sinceSeq)
    }
  }

  // 单条
  const method = String(payload.method || '').trim()
  if (!method) {
    const err = new Error('method 不能为空')
    err.status = 400
    throw err
  }
  const result = await dbg.sendCommand(method, payload.params || {})
  return {
    ok: true,
    tabId: entry.tabId,
    result: result || {},
    value: unwrapRemoteObject(result && result.result),
    pageUrl: pageUrlOf(entry.win),
    lastSeq: entry.seq,
    events: entry.events.filter((e) => e.seq > sinceSeq)
  }
}

async function route (req, res) {
  const url = new URL(req.url, 'http://127.0.0.1')

  // 只接受本机回环来源；token 校验放在最前面，未授权的一切请求都不处理
  const given = req.headers['x-cc-token'] || ''
  if (!token || given !== token) {
    sendJson(res, 401, { ok: false, error: 'token 无效' })
    return
  }

  if (req.method === 'GET' && url.pathname === '/health') {
    sendJson(res, 200, {
      ok: true,
      protocol: PROTOCOL_VERSION,
      appVersion: app.getVersion(),
      // windows = 标签总数（每个标签一个独立窗口），conversations = 会话数
      windows: windowEntries.size,
      conversations: new Set([...windowEntries.values()].map((e) => e.conversationId)).size,
      maxWindows: options.maxWindows,
      maxTabsPerConversation: options.maxTabsPerConversation
    })
    return
  }

  if (req.method !== 'POST') {
    sendJson(res, 405, { ok: false, error: '只支持 POST' })
    return
  }

  let payload
  try {
    payload = await readBody(req)
  } catch (e) {
    sendJson(res, 400, { ok: false, error: e.message })
    return
  }

  if (url.pathname === '/open') {
    const conversationId = String(payload.conversationId || 'default')
    const show = Object.prototype.hasOwnProperty.call(payload, 'show')
      ? payload.show !== false
      : options.defaultShow
    const wantTabId = payload.tabId ? String(payload.tabId) : ''
    const newTab = payload.newTab === true

    // 已有窗口：复用（可选调整显隐），不重复创建。newTab=true 时跳过复用，强制开新标签。
    const existing = newTab ? null : resolveEntry(conversationId, wantTabId)
    if (existing) {
      if (show) {
        if (!existing.win.isVisible()) existing.win.show()
        existing.win.focus()
      } else if (existing.win.isVisible()) {
        existing.win.hide()
      }
      // 仅在显式给了 url 时才导航，否则保持当前页面（复用窗口不该被无故跳走）
      if (payload.url) {
        let target
        try {
          target = normalizeUrl(payload.url)
        } catch (e) {
          sendJson(res, 400, { ok: false, error: e.message })
          return
        }
        try {
          await existing.win.loadURL(target)
          existing.lastUrl = target
        } catch (e) {
          sendJson(res, 200, {
            ok: false,
            error: `页面加载失败：${e.message}`,
            conversationId,
            tabId: existing.tabId,
            tabCount: tabCountOf(conversationId),
            pageUrl: pageUrlOf(existing.win)
          })
          return
        }
      }
      sendJson(res, 200, {
        ok: true,
        reused: true,
        conversationId,
        tabId: existing.tabId,
        tabCount: tabCountOf(conversationId),
        partition: existing.partition,
        visible: existing.win.isVisible(),
        pageUrl: pageUrlOf(existing.win) || existing.lastUrl || ''
      })
      return
    }

    // 同会话标签数超上限：关掉该会话最久没用的那个（保留其他会话不受影响）
    const maxTabs = Math.max(1, options.maxTabsPerConversation || 4)
    while (tabCountOf(conversationId) >= maxTabs) {
      const oldestTab = tabsOf(conversationId).sort((a, b) => a.lastUsedAt - b.lastUsedAt)[0]
      if (!oldestTab) break
      destroyEntry(oldestTab, '超出会话标签上限')
    }
    // 全局窗口数超上限：关掉全局最久没用的那个，而不是报错拒绝新会话
    if (windowEntries.size >= Math.max(1, options.maxWindows)) {
      const oldest = [...windowEntries.values()].sort((a, b) => a.lastUsedAt - b.lastUsedAt)[0]
      if (oldest) destroyEntry(oldest, '超出窗口上限')
    }

    // 先校验 URL 再建窗口：否则地址非法时会留下一个空窗口占着名额
    // 空页面：about:blank 不走协议白名单（它是浏览器内部页，不是外部资源）
    let target
    try {
      target = payload.url ? normalizeUrl(payload.url) : 'about:blank'
    } catch (e) {
      sendJson(res, 400, { ok: false, error: e.message })
      return
    }

    const tabId = wantTabId || nextTabId(conversationId)
    const entry = createWindowEntry({
      conversationId,
      projectRoot: payload.projectRoot,
      show,
      width: payload.width,
      height: payload.height,
      tabId,
      tabIndex: tabCountOf(conversationId) + 1
    })

    try {
      await entry.win.loadURL(target)
      entry.lastUrl = target
    } catch (e) {
      // 加载失败不算致命：窗口已经建好，模型可以改地址重试
      sendJson(res, 200, {
        ok: false,
        error: `页面加载失败：${e.message}`,
        conversationId,
        tabId: entry.tabId,
        tabCount: tabCountOf(conversationId),
        partition: entry.partition,
        visible: entry.win.isVisible(),
        pageUrl: ''
      })
      return
    }

    sendJson(res, 200, {
      ok: true,
      reused: false,
      conversationId,
      tabId: entry.tabId,
      tabCount: tabCountOf(conversationId),
      partition: entry.partition,
      visible: entry.win.isVisible(),
      pageUrl: pageUrlOf(entry.win) || target
    })
    return
  }

  if (url.pathname === '/cdp') {
    try {
      const result = await handleCdp(payload)
      sendJson(res, 200, result)
    } catch (e) {
      sendJson(res, e.status || 500, { ok: false, error: e.message })
    }
    return
  }

  if (url.pathname === '/show') {
    const entry = getEntry(payload.conversationId, payload.tabId)
    if (!entry) {
      sendJson(res, 410, { ok: false, error: '浏览器窗口不存在或已被关闭' })
      return
    }
    const visible = payload.visible !== false
    if (visible) {
      entry.win.show()
      entry.win.focus()
    } else {
      entry.win.hide()
    }
    sendJson(res, 200, { ok: true, tabId: entry.tabId, visible: entry.win.isVisible() })
    return
  }

  if (url.pathname === '/close') {
    // all=true：关掉该会话下全部标签（用户说「关掉浏览器」时最符合直觉）
    if (payload.all === true) {
      const tabs = tabsOf(payload.conversationId)
      for (const tab of tabs) destroyEntry(tab, payload.reason || '调用方关闭全部')
      sendJson(res, 200, { ok: true, closed: tabs.length > 0, closedCount: tabs.length })
      return
    }
    const entry = getEntry(payload.conversationId, payload.tabId)
    if (!entry) {
      sendJson(res, 200, { ok: true, closed: false, note: '窗口本就不存在' })
      return
    }
    const tabId = entry.tabId
    destroyEntry(entry, payload.reason || '调用方关闭')
    sendJson(res, 200, { ok: true, closed: true, tabId, remain: tabCountOf(payload.conversationId) })
    return
  }

  if (url.pathname === '/events') {
    // 显式拉取事件流（不依赖 CDP 调用）。
    // 为什么不复用 /cdp：页面导航中 / 崩溃后 evaluate 会失败，而模型恰恰最需要看那时的报错。
    const entry = getEntry(payload.conversationId, payload.tabId)
    if (!entry) {
      sendJson(res, 410, { ok: false, error: '浏览器窗口不存在或已被关闭' })
      return
    }
    const sinceSeq = Number(payload.sinceSeq) || 0
    const limit = Math.max(1, Math.min(200, Number(payload.limit) || 40))
    const fresh = entry.events.filter((e) => e.seq > sinceSeq)
    // 只保留最后 limit 条（越靠后越新，模型更关心最近的失败）
    const sliced = fresh.length > limit ? fresh.slice(fresh.length - limit) : fresh
    sendJson(res, 200, {
      ok: true,
      tabId: entry.tabId,
      events: sliced,
      dropped: fresh.length - sliced.length,
      lastSeq: entry.seq,
      pageUrl: pageUrlOf(entry.win) || entry.lastUrl || ''
    })
    return
  }

  if (url.pathname === '/list') {
    const onlyConv = payload.conversationId ? String(payload.conversationId) : ''
    const list = [...windowEntries.values()]
      .filter((e) => !onlyConv || e.conversationId === onlyConv)
      .sort((a, b) => a.createdAt - b.createdAt)
      .map((e) => ({
        conversationId: e.conversationId,
        tabId: e.tabId,
        active: activeTabOf.get(e.conversationId) === e.tabId,
        visible: e.win && !e.win.isDestroyed() ? e.win.isVisible() : false,
        pageUrl: pageUrlOf(e.win) || e.lastUrl,
        partition: e.partition,
        idleMs: Date.now() - e.lastUsedAt
      }))
    sendJson(res, 200, {
      ok: true,
      windows: list,
      maxWindows: options.maxWindows,
      maxTabsPerConversation: options.maxTabsPerConversation
    })
    return
  }

  if (url.pathname === '/tabs') {
    // 极简标签管理：只维护「活跃标签」指针 + 显隐，标签本身就是独立窗口，
    // 没有自绘标签栏需要重绘（远程页面里注入标签栏会被站点 CSP / 布局打乱）。
    const conversationId = String(payload.conversationId || 'default')
    if (payload.tabId) {
      const target = tabsOf(conversationId).find((t) => t.tabId === String(payload.tabId))
      if (!target) {
        sendJson(res, 404, { ok: false, error: `标签 ${payload.tabId} 不存在（可能已关闭或空闲回收）` })
        return
      }
      activeTabOf.set(conversationId, target.tabId)
      target.lastUsedAt = Date.now()
      if (payload.show !== false) {
        if (!target.win.isVisible()) target.win.show()
        target.win.focus()
      }
    }
    const tabs = tabsOf(conversationId).map((t) => ({
      tabId: t.tabId,
      active: activeTabOf.get(conversationId) === t.tabId,
      visible: t.win.isVisible(),
      pageUrl: pageUrlOf(t.win) || t.lastUrl || '',
      idleMs: Date.now() - t.lastUsedAt
    }))
    sendJson(res, 200, {
      ok: true,
      conversationId,
      tabs,
      activeTabId: activeTabOf.get(conversationId) || '',
      maxTabsPerConversation: options.maxTabsPerConversation
    })
    return
  }

  sendJson(res, 404, { ok: false, error: `未知端点：${url.pathname}` })
}

// ── 生命周期 ───────────────────────────────────────────────────────

/**
 * 启动 bridge。
 *
 * @param {object} opts
 * @param {string} opts.userDataDir     握手文件落点（打包= app.getPath('userData')，dev= 项目根）
 * @param {number} [opts.maxWindows]    全局标签上限（每个标签一个窗口）
 * @param {number} [opts.maxTabsPerConversation] 单会话标签上限
 * @param {number} [opts.idleMinutes]
 * @param {boolean} [opts.allowDownloads]
 * @param {boolean} [opts.defaultShow]
 * @returns {Promise<{port:number, token:string, handshakePath:string}>}
 */
function initBrowserBridge (opts) {
  options = {
    ...options,
    ...(opts || {})
  }
  handshakePath = path.join(options.userDataDir, 'runtime', HANDSHAKE_FILE)
  token = crypto.randomBytes(24).toString('hex')

  return new Promise((resolve, reject) => {
    server = http.createServer((req, res) => {
      route(req, res).catch((e) => {
        console.warn('[browser-bridge] 处理请求异常:', e && e.message)
        try {
          sendJson(res, 500, { ok: false, error: (e && e.message) || '内部错误' })
        } catch {
          /* 响应可能已发出 */
        }
      })
    })

    server.on('error', (e) => {
      console.warn('[browser-bridge] HTTP 服务启动失败（浏览器工具将不可用）:', e.message)
      server = null
      reject(e)
    })

    // 端口 0 = 让系统分配空闲端口，避免与其他软件冲突（8084 是后端，这里不能抢）
    server.listen(0, '127.0.0.1', () => {
      port = server.address().port
      try {
        fs.mkdirSync(path.dirname(handshakePath), { recursive: true })
        fs.writeFileSync(handshakePath, JSON.stringify({
          protocol: PROTOCOL_VERSION,
          port,
          token,
          pid: process.pid,
          appVersion: app.getVersion(),
          startedAt: Date.now()
        }, null, 2), { encoding: 'utf8', mode: 0o600 })
        console.log(`[browser-bridge] 已启动 127.0.0.1:${port}，握手文件 ${handshakePath}`)
      } catch (e) {
        console.warn('[browser-bridge] 写入握手文件失败:', e.message)
      }

      // 空闲回收：60 秒扫一次就够，太频繁纯属空转
      idleTimer = setInterval(sweepIdle, 60 * 1000)
      if (idleTimer.unref) idleTimer.unref()

      resolve({ port, token, handshakePath })
    })
  })
}

/** 退出前收尾：关窗口、停服务、删握手文件（否则下次启动 Java 会读到过期端口） */
function stopBrowserBridge () {
  try {
    if (idleTimer) {
      clearInterval(idleTimer)
      idleTimer = null
    }
  } catch { /* 忽略 */ }

  for (const entry of [...windowEntries.values()]) {
    destroyEntry(entry, '应用退出')
  }
  windowEntries.clear()

  try {
    if (server) {
      server.close()
      server = null
    }
  } catch { /* 忽略 */ }

  try {
    if (handshakePath && fs.existsSync(handshakePath)) fs.unlinkSync(handshakePath)
  } catch { /* 忽略 */ }
}

/** 供 Java 侧探测用：端口（0 = 未启动） */
function getBridgePort () {
  return port
}

module.exports = { initBrowserBridge, stopBrowserBridge, getBridgePort }
