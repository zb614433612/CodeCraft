/**
 * 统一 HTTP 请求封装（v3 · 显式登录）
 *
 * <h3>为什么要自己管 token</h3>
 * 后端 {@code TokenAuthenticationFilter}（{@code @Order(1)}）会拦截**所有** {@code /api/} 请求，
 * 缺少 {@code Authorization: Bearer <token>} 时直接返回 401「缺少访问令牌」。
 * 因此「有没有 token」决定了整个应用能不能用。
 *
 * <p>v3 早期版本用的是「隐形登录」（启动时用默认账号静默换 token，用户看不到登录页）。
 * 现在恢复<b>显式登录</b>：由 {@code views/LoginView.vue} 采集账号密码，调用
 * {@link performLogin} 换取 token；本模块只负责「存 token / 带 token / token 失效时通知上层」。</p>
 *
 * <h3>三条关键约束</h3>
 * <ol>
 *   <li><b>token 存在后端内存里</b>：{@code TokenStore} 基于 Caffeine（不是 Redis），
 *       后端一重启 token 全部失效 → 抓到 401 时清 token 并触发未授权回调（跳登录页）；</li>
 *   <li><b>登录是两步</b>：{@code POST /user/random-code} 取 4 位随机码（1 分钟有效），
 *       再 {@code POST /user/login} 提交 {@code MD5(MD5(明文) + 随机码)}；</li>
 *   <li><b>401 不在这里跳路由</b>：http-client 不认识 router（会造成循环依赖），
 *       改为调用 {@link setUnauthorizedHandler} 注册的回调，由 router 负责跳转。</li>
 * </ol>
 */

import { encryptLoginPassword } from './md5'

/** 后端统一响应包装（{@code ApiResponse<T>}） */
export interface ApiResponse<T = unknown> {
  code: number
  message: string
  data: T
  timestamp?: number
}

/** 登录响应（{@code LoginResponse}） */
export interface LoginResult {
  userId: number
  username: string
  nickname: string
  role: string
  token: string
  expire: number
}

/** 自动登录凭据 */
export interface AutoLoginCredential {
  username: string
  password: string
}

/** API 前缀（后端所有 REST 接口均以 /api 开头） */
const API_BASE = '/api'

/** 默认请求超时（毫秒）；长耗时接口可用第三参数覆盖 */
const DEFAULT_TIMEOUT = 15_000

/** 登录相关接口默认超时（随机码 + 登录两步，给足余量） */
const LOGIN_TIMEOUT = 20_000

/** localStorage：token 缓存键 */
const TOKEN_KEY = 'codecraft.token'

/**
 * localStorage：token 已被判废的标记。
 *
 * <p><b>为什么需要这个标记</b>：{@link getStoredToken} 在自身没有 token 时会回退读经典版（v2）
 * 持久化的 `user` 键（见 {@link readLegacyToken}）。两版共用 http://localhost:8084 这个 origin，
 * localStorage 是共享的 —— 只清 `codecraft.token` 的话，下一次 `hasToken()` 立刻又会从经典版
 * 读到同一个已失效的 token，路由守卫就认为「仍然登录」，把用户从 /login 弹回首页。
 * 表现正是：**提示了「登录已过期」，但页面永远退不回登录页**。</p>
 *
 * <p>写 1 表示「当前设备上的 token 已经确定失效，不要再做任何兜底」；
 * 用户重新登录成功时（{@link setAuthToken} 收到非空 token）自动清除。</p>
 */
const TOKEN_REVOKED_KEY = 'codecraft.token.revoked'

/**
 * localStorage：经典版（v2）pinia user store 的持久化键。
 *
 * <p>v2 的 user store 用 `persist: true`，默认键名就是 `user`，state 里躺着 `token`。
 * 这里只读它做兜底，绝不回写——否则会反过来污染经典版的登录态。</p>
 */
const LEGACY_USER_KEY = 'user'

/** localStorage：自动登录凭据覆盖键 */
const CREDENTIAL_KEY = 'codecraft.auto-login'

/** 默认自动登录账号（后端初始化种子：admin / 123456） */
const DEFAULT_CREDENTIAL: AutoLoginCredential = { username: 'admin', password: '123456' }

/** 内存 token（优先于 localStorage，减少读写） */
let memoryToken = ''

/** 进行中的登录任务（并发去重） */
let loginTask: Promise<string> | null = null

/** 登录相关路径：不参与 401 自动重登（避免死循环） */
function isAuthPath(url: string): boolean {
  return url.includes('/user/login') || url.includes('/user/random-code')
}

/** 拼接完整地址（允许调用方传入已带 /api 的地址） */
function toFullUrl(url: string): string {
  if (/^https?:\/\//i.test(url)) return url
  return url.startsWith('/api/') || url === '/api' ? url : `${API_BASE}${url}`
}

// ==================== token 管理 ====================

/**
 * 内存中的「token 已判废」标记（与 localStorage 标记同步，避免每次读盘）。
 *
 * <p>一旦为 true，{@link getStoredToken} 会直接返回空串，连经典版兜底也不读 ——
 * 否则清完 token 的下一次调用又会把旧 token 读回来，路由守卫随即把人从登录页弹走。</p>
 */
let revokedToken = false

/** 读取本地判废标记（localStorage 不可用时按未判废处理） */
function readRevokedFlag(): boolean {
  try {
    return localStorage.getItem(TOKEN_REVOKED_KEY) === '1'
  } catch {
    return false
  }
}

/**
 * 当前设备上的 token 是否已被判定失效（401 / 主动退出后为 true）。
 *
 * <p>登录成功会清除该标记；调用方一般不需要关心，路由守卫与 http-client 内部已消费。</p>
 */
export function isTokenRevoked(): boolean {
  if (!revokedToken) revokedToken = readRevokedFlag()
  return revokedToken
}

/**
 * 读取经典版（v2）持久化的 token。
 *
 * <p>仅作兜底：拆 JSON 失败、字段缺失、类型不对一律返回空串，
 * 不能让一段脏数据把整个应用的启动流程卡死。</p>
 *
 * <p>token 已被判废时直接返回空串：经典版那份同样属于「后端已不认」的旧 token，
 * 继续读它只会让 401 → 清 token → 守卫又看到 token → 回首页 的死循环成立。</p>
 */
function readLegacyToken(): string {
  if (isTokenRevoked()) return ''
  try {
    const raw = localStorage.getItem(LEGACY_USER_KEY)
    if (!raw) return ''
    const parsed = JSON.parse(raw) as { token?: unknown } | null
    return typeof parsed?.token === 'string' ? parsed.token : ''
  } catch {
    return ''
  }
}

/**
 * 读取已缓存 token（内存优先，回退 localStorage）。
 *
 * <p><b>跨版本登录态</b>：新版把 token 存在 `codecraft.token`，经典版存在
 * pinia `user` 键里，两者天然不通用。为满足「经典版 ↔ 新版来回切不掉登录」，
 * 新版在自身没有 token 时回退读经典版那一份（只读）。首次带着旧 token
 * 请求成功后就等同已登录；若该 token 已过期，401 会正常把人带到登录页。</p>
 */
export function getStoredToken(): string {
  // 已判废：无论哪里还留着旧 token 都不能再用（否则守卫会把用户从登录页弹回去）
  if (isTokenRevoked()) {
    memoryToken = ''
    return ''
  }
  if (memoryToken) return memoryToken
  try {
    memoryToken = localStorage.getItem(TOKEN_KEY) || ''
  } catch {
    memoryToken = ''
  }
  if (!memoryToken) {
    memoryToken = readLegacyToken()
  }
  return memoryToken
}

/** 写入 token */
export function setAuthToken(token: string): void {
  memoryToken = token || ''
  // 拿到新 token = 重新登录成功：解除判废标记，恢复经典版兜底能力
  if (token) revokedToken = false
  try {
    if (token) {
      localStorage.setItem(TOKEN_KEY, token)
      localStorage.removeItem(TOKEN_REVOKED_KEY)
    } else {
      localStorage.removeItem(TOKEN_KEY)
    }
  } catch {
    // localStorage 不可用（隐私模式 / file://）时仅保留内存 token
  }
}

/**
 * 清除 token，并把当前设备标记为「token 已判废」。
 *
 * <p>标记不是「顺手加的」：只清 `codecraft.token` 时，{@link getStoredToken} 会立刻
 * 从经典版 `user` 键把旧 token 兜底读回来 —— 于是「清 token → 守卫看到 token →
 * 弹回首页 → 请求又 401 → 再清」可以无限循环，用户永远停在业务页。</p>
 */
export function clearAuthToken(): void {
  setAuthToken('')
  revokedToken = true
  try {
    localStorage.setItem(TOKEN_REVOKED_KEY, '1')
  } catch {
    // 存储不可用时仅内存标记生效
  }
}

/** 读取自动登录凭据（localStorage 可覆盖默认值） */
export function readCredential(): AutoLoginCredential {
  try {
    const raw = localStorage.getItem(CREDENTIAL_KEY)
    if (raw) {
      const parsed = JSON.parse(raw) as Partial<AutoLoginCredential>
      if (parsed?.username && parsed?.password) {
        return { username: parsed.username, password: parsed.password }
      }
    }
  } catch {
    // 非法 JSON 忽略，回退默认账号
  }
  return { ...DEFAULT_CREDENTIAL }
}

/** 覆盖自动登录凭据（写入 localStorage，传 null 恢复默认） */
export function saveCredential(credential: AutoLoginCredential | null): void {
  try {
    if (credential) localStorage.setItem(CREDENTIAL_KEY, JSON.stringify(credential))
    else localStorage.removeItem(CREDENTIAL_KEY)
  } catch {
    // 忽略存储失败
  }
}

// ==================== 底层 fetch ====================

/**
 * 底层请求：只负责拼地址、注入认证头与超时，**不处理 401 重试**。
 *
 * <p>登录本身也走这里——若它内部再触发自动登录就会死循环。</p>
 */
async function rawFetch(
  fullUrl: string,
  init: RequestInit = {},
  timeout: number = DEFAULT_TIMEOUT
): Promise<Response> {
  const controller = new AbortController()
  const timer = setTimeout(() => controller.abort(), timeout)

  // FormData 必须让浏览器自动带 boundary，不能手写 Content-Type
  const isFormData = typeof FormData !== 'undefined' && init.body instanceof FormData
  const headers: Record<string, string> = { ...(init.headers as Record<string, string> | undefined) }
  if (!isFormData && !headers['Content-Type']) {
    headers['Content-Type'] = 'application/json'
  }
  const token = getStoredToken()
  if (token && !headers['Authorization']) {
    headers['Authorization'] = `Bearer ${token}`
  }

  try {
    return await fetch(fullUrl, { ...init, headers, signal: init.signal ?? controller.signal })
  } finally {
    clearTimeout(timer)
  }
}

/** 从响应文本提取后端错误消息 */
async function extractErrorMessage(response: Response): Promise<string> {
  let text = ''
  try {
    text = await response.text()
  } catch {
    return `请求失败（HTTP ${response.status}）`
  }
  try {
    const parsed = JSON.parse(text) as {
      message?: string
      status?: number
      error?: string
      path?: string
    }
    // 业务包装（ApiResponse）：优先用后端给的 message
    if (parsed?.message) return parsed.message
    // Spring 默认错误页：后端没有全局异常处理器，Controller 里抛 RuntimeException
    // （典型如 checkAdmin 抛「权限不足」）时会返回 {timestamp,status,error,path}。
    // 原样展示会把一大段 JSON 甩进 Toast，这里压成一句人话。
    if (typeof parsed?.status === 'number' && parsed?.error) {
      const where = parsed.path ? `（${parsed.path}）` : ''
      return `服务异常 HTTP ${parsed.status} ${parsed.error}${where}`
    }
  } catch {
    // 非 JSON 响应，直接回落原文
  }
  return text?.trim() || `请求失败（HTTP ${response.status}）`
}

// ==================== 登录 / 登出 ====================

/** 未授权回调（由 router 注册，负责跳转登录页） */
let unauthorizedHandler: ((reason: string) => void) | null = null

/**
 * 注册「未授权」回调。
 *
 * <p>http-client 不能直接 import router（router → session → http-client 会成环），
 * 因此把「跳登录页」这个动作交给上层注入。传 null 可注销（测试用）。</p>
 */
export function setUnauthorizedHandler(handler: ((reason: string) => void) | null): void {
  unauthorizedHandler = handler
}

/** 是否已持有 token（同步读取，不发请求；路由守卫用它做第一道判断） */
export function hasToken(): boolean {
  return !!getStoredToken()
}

/** 上一次真正触发未授权跳转的时间戳（用于 401 风暴去重） */
let lastUnauthorizedAt = 0

/** 并发 401 去重窗口（毫秒） */
const UNAUTHORIZED_DEDUPE_MS = 800

/**
 * 触发未授权回调；回调内部异常不应影响调用方原本的错误处理。
 *
 * <p>一次切页 / 一轮刷新往往会并发打出 5~10 个接口，token 失效时它们会同时 401。
 * 全部转成 `router.replace` 会互相打断（后一次导航取消前一次），日志里满是
 * NavigationFailure。这里做 800ms 去重：只让第一次真正跳转，其余直接丢弃 ——
 * token 已经清掉，后续请求本来也过不去。</p>
 */
function fireUnauthorized(reason: string): void {
  if (!unauthorizedHandler) return
  const now = Date.now()
  if (now - lastUnauthorizedAt < UNAUTHORIZED_DEDUPE_MS) return
  lastUnauthorizedAt = now
  try {
    unauthorizedHandler(reason)
  } catch {
    // 忽略：跳转失败不应盖住真正的业务错误
  }
}

/**
 * 供**非 request / authFetch 通道**（SSE、原生 fetch）主动上报「未授权」。
 *
 * <p>SSE 必须直连 fetch 才能拿到 ReadableStream，绕过了 request 里的统一 401 处理；
 * 由调用方在拿到 401 后调用本函数，行为与 request 内部保持一致：
 * 清 token → 触发 router 注册的回调 → 跳登录页。</p>
 *
 * <p>历史坑：v3 早期没有 /login 路由，SSE 401 只清了 token 就结束，
 * 于是「页面开着、后端重启导致 token 失效」时，用户发消息只会看到一句
 * 「SSE请求失败，状态码: 401」，既不退出也不提示重新登录。</p>
 */
export function notifyUnauthorized(reason = '登录已过期'): void {
  clearAuthToken()
  /*
    极端兜底：router 的回调理论上在 main.ts 里 `app.use(router)` 之前就注册了
    （router/index.ts 模块顶层），但 HMR、单测、或某些懒加载时序下可能还没注册。
    这时如果什么都不做，用户会看到「登录已过期」却停在原地 —— 这正是本次要修的观感。
    直接改 hash 是唯一不依赖 router 实例的跳转方式，hash 路由下与 router.push 等价。
  */
  if (!unauthorizedHandler) {
    try {
      if (!window.location.hash.startsWith('#/login')) window.location.hash = '#/login'
    } catch {
      // 非浏览器环境（SSR / 单测）忽略
    }
    return
  }
  fireUnauthorized(reason)
}

/**
 * 显式登录：随机码 → {@code MD5(MD5(明文) + 随机码)} → 换取 token。
 *
 * <p>成功后 token 已写入 localStorage；返回完整登录响应（含 userId / nickname / role）。</p>
 *
 * @param username 用户名
 * @param password 明文密码（本函数内部完成二次加密，调用方不要预先 MD5）
 */
export async function performLogin(username: string, password: string): Promise<LoginResult> {
  if (!username || !password) throw new Error('请输入用户名和密码')

  // 第 1 步：取随机码（1 分钟有效，存于后端 TokenStore）
  const codeResponse = await rawFetch(
    `${API_BASE}/user/random-code`,
    { method: 'POST', body: JSON.stringify({ username }) },
    LOGIN_TIMEOUT
  )
  if (!codeResponse.ok) {
    throw new Error(await extractErrorMessage(codeResponse))
  }
  const codeBody = (await codeResponse.json()) as ApiResponse<string>
  if (codeBody.code !== 200 || !codeBody.data) {
    throw new Error(codeBody.message || '获取随机码失败')
  }

  // 第 2 步：提交 MD5(MD5(明文) + 随机码)
  const loginResponse = await rawFetch(
    `${API_BASE}/user/login`,
    {
      method: 'POST',
      body: JSON.stringify({
        username,
        password: encryptLoginPassword(password, codeBody.data)
      })
    },
    LOGIN_TIMEOUT
  )
  if (!loginResponse.ok) {
    throw new Error(await extractErrorMessage(loginResponse))
  }
  const loginBody = (await loginResponse.json()) as ApiResponse<LoginResult>
  if (loginBody.code !== 200 || !loginBody.data?.token) {
    throw new Error(loginBody.message || '登录失败：未返回 token')
  }

  setAuthToken(loginBody.data.token)
  return loginBody.data
}

/**
 * 用 localStorage 里保存的凭据静默登录（可选能力，登录页不走这条路径）。
 *
 * <p>保留它是为「自动登录」这类后续增强：把账号密码存到
 * {@code codecraft.auto-login} 后，可在启动时无感换 token。</p>
 */
export function loginSilently(): Promise<string> {
  if (loginTask) return loginTask
  const credential = readCredential()
  loginTask = performLogin(credential.username, credential.password)
    .then(result => result.token)
    .finally(() => {
      loginTask = null
    })
  return loginTask
}

/**
 * 确保拿到可用 token：本地没有 token 时直接报错（有登录页后不再静默登录）。
 *
 * <p>调用方通常是「必须登录才能继续」的初始化流程（如 session.ensureIdentity）。</p>
 */
export async function ensureToken(): Promise<string> {
  const token = getStoredToken()
  if (!token) throw new Error('未登录，请先登录')
  return token
}


/** 是否为网络/中断类错误（用于给出更友好的提示） */
function isAbortError(error: unknown): boolean {
  return error instanceof DOMException ? error.name === 'AbortError' : false
}

/** 统一把异常转成可读 Error */
function normalizeError(error: unknown, timeout: number): Error {
  if (isAbortError(error)) {
    return new Error(`请求超时（${Math.round(timeout / 1000)} 秒），请检查后端服务是否正常`)
  }
  if (error instanceof TypeError && /fetch|network/i.test(error.message)) {
    return new Error('网络连接失败，请确认后端服务已启动')
  }
  return error instanceof Error ? error : new Error(String(error))
}

// ==================== 对外请求方法 ====================

/**
 * 通用请求：自动拼 {@code /api} 前缀、注入 token；未登录 / token 失效时
 * 触发未授权回调（由 router 跳登录页）并抛出可读错误。
 *
 * @param url     接口路径（如 {@code /llm-providers}，无需带 /api）
 * @param options fetch 配置
 * @param timeout 超时毫秒数，默认 15 秒；执行 SQL 等长耗时可放大
 * @returns 后端 {@code ApiResponse<T>}（已校验 code === 200）
 */
export async function request<T>(
  url: string,
  options: RequestInit = {},
  timeout: number = DEFAULT_TIMEOUT
): Promise<ApiResponse<T>> {
  const fullUrl = toFullUrl(url)
  const isAuth = isAuthPath(fullUrl)

  try {
    // 未登录：直接判定，不再打出这次必然 401 的请求
    if (!isAuth && !getStoredToken()) {
      fireUnauthorized('未登录')
      throw new Error('未登录，请先登录')
    }

    const response = await rawFetch(fullUrl, options, timeout)

    // 401：token 失效（多因后端重启：TokenStore 是内存缓存，不是 Redis）
    if (response.status === 401 && !isAuth) {
      clearAuthToken()
      fireUnauthorized('登录已过期')
      throw new Error('登录已过期，请重新登录')
    }

    if (!response.ok) {
      throw new Error(await extractErrorMessage(response))
    }

    const body = (await response.json()) as ApiResponse<T>

    // 业务码 401：HTTP 200 但 code=401（部分 Controller 内部自行校验登录态）
    if (body.code === 401 && !isAuth) {
      clearAuthToken()
      fireUnauthorized(body.message || '登录已过期')
      throw new Error(body.message || '登录已过期，请重新登录')
    }

    if (body.code !== 200) {
      throw new Error(body.message || '请求失败')
    }

    return body
  } catch (error) {
    throw normalizeError(error, timeout)
  }
}

/**
 * 获取原始 {@link Response}：用于下载文件、SSE、`<img>` blob 等场景。
 *
 * <p>与 {@link request} 的差异：不解析 JSON、不校验业务码，401 时同样只清 token +
 * 触发未授权回调（不抛错），把最终响应交回调用方判断。</p>
 */
export async function authFetch(
  url: string,
  options: RequestInit = {},
  timeout: number = DEFAULT_TIMEOUT
): Promise<Response> {
  const fullUrl = toFullUrl(url)
  const isAuth = isAuthPath(fullUrl)

  if (!isAuth && !getStoredToken()) {
    fireUnauthorized('未登录')
    throw new Error('未登录，请先登录')
  }

  const response = await rawFetch(fullUrl, options, timeout)
  if (response.status === 401 && !isAuth) {
    clearAuthToken()
    fireUnauthorized('登录已过期')
  }
  return response
}

/**
 * 认证请求头（兼容旧 api 模块的调用习惯；新代码建议直接用 request/authFetch）
 */
export function getAuthHeaders(): Record<string, string> {
  const token = getStoredToken()
  return token ? { Authorization: `Bearer ${token}` } : {}
}

/** 便捷方法：GET */
export function get<T>(url: string, timeout?: number): Promise<ApiResponse<T>> {
  return request<T>(url, { method: 'GET' }, timeout)
}

/** 便捷方法：POST（自动 JSON 序列化） */
export function post<T>(url: string, body?: unknown, timeout?: number): Promise<ApiResponse<T>> {
  return request<T>(
    url,
    { method: 'POST', body: body === undefined ? undefined : JSON.stringify(body) },
    timeout
  )
}

/** 便捷方法：PUT（自动 JSON 序列化） */
export function put<T>(url: string, body?: unknown, timeout?: number): Promise<ApiResponse<T>> {
  return request<T>(
    url,
    { method: 'PUT', body: body === undefined ? undefined : JSON.stringify(body) },
    timeout
  )
}

/** 便捷方法：DELETE */
export function del<T>(url: string, timeout?: number): Promise<ApiResponse<T>> {
  return request<T>(url, { method: 'DELETE' }, timeout)
}

/** 便捷方法：上传（multipart/form-data） */
export function upload<T>(
  url: string,
  formData: FormData,
  timeout: number = 60_000
): Promise<ApiResponse<T>> {
  return request<T>(url, { method: 'POST', body: formData }, timeout)
}
