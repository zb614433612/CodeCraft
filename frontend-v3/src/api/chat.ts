import type { ApiResponse } from '@/store/user'
import { getAuthHeaders, authFetch, notifyUnauthorized } from '@/utils/http-client'
import { readSseStream } from '@/utils/sse-client'
import type { StreamChatEvent } from '@/utils/sse-client'

export { type StreamChatEvent }

export interface ChatRequest {
  message: string
  sessionId?: number
  promptFileName?: string
  executionMode?: string
  projectRoot?: string
  model?: string
  thinkingMode?: string
  turnId?: string
  agentConfigId?: number
  providerCode?: string
  contextMode?: string
  attachmentIds?: string[]
  /** M4：文件资产ID列表（图片；发送时建立引用并注入 file 内容块） */
  fileAssetIds?: number[]
}

// 流式聊天的额外选项
export interface StreamChatOptions {
  promptFileName?: string
  executionMode?: string
  projectRoot?: string
  model?: string
  thinkingMode?: string
  turnId?: string
  agentConfigId?: number
  providerCode?: string
  contextMode?: string
  attachmentIds?: string[]
  /** M4：文件资产ID列表（图片；发送时建立引用并注入 file 内容块） */
  fileAssetIds?: number[]
}

// 聊天响应数据（非流式，用于创建会话等）
export interface ChatResponse {
  sessionId?: number
  content?: string
  thinking?: string
}

// 流式聊天请求
export async function* streamChat(
  message: string,
  sessionId?: number,
  options?: StreamChatOptions,
  abortController?: AbortController
): AsyncGenerator<StreamChatEvent, void, unknown> {
  // 开发环境模拟模式 - 通过环境变量控制
  const useMock = import.meta.env.VITE_API_MOCK === 'true'
  if (import.meta.env.DEV && useMock) {
    console.log('开发模式：模拟流式聊天，消息:', message, 'sessionId:', sessionId)

    // 模拟思考过程
    yield { type: 'thinking', data: '让我思考一下这个问题...\n用户询问了关于' + message.substring(0, 20) + '...' }
    await new Promise(resolve => setTimeout(resolve, 800))

    yield { type: 'thinking', data: '\n我需要从多个角度分析这个问题。' }
    await new Promise(resolve => setTimeout(resolve, 600))

    // 模拟内容
    yield { type: 'content', data: '你好！我收到了你的消息："' + message + '"。' }
    await new Promise(resolve => setTimeout(resolve, 300))

    yield { type: 'content', data: '\n\n这是一个模拟响应，实际会调用DeepSeek API。' }
    await new Promise(resolve => setTimeout(resolve, 200))

    yield { type: 'content', data: '\n\n思考过程已在上方展示，可以点击"思考过程"展开查看。' }

    if (!sessionId) {
      const mockSessionId = Math.floor(Math.random() * 1000) + 1
      yield { type: 'complete', data: '', sessionId: mockSessionId }
    } else {
      yield { type: 'complete', data: '' }
    }

    return
  }

  console.log('调用真实API /api/deepseek/chat/stream，消息:', message, 'sessionId:', sessionId, 'options:', options)

  const controller = abortController ?? new AbortController()

  const requestBody: ChatRequest = { message }
  if (sessionId !== undefined) {
    requestBody.sessionId = sessionId
  }
  if (options?.promptFileName) {
    requestBody.promptFileName = options.promptFileName
  }
  if (options?.executionMode) {
    requestBody.executionMode = options.executionMode
  }
  if (options?.projectRoot) {
    requestBody.projectRoot = options.projectRoot
  }
  if (options?.model) {
    requestBody.model = options.model
  }
  if (options?.thinkingMode) {
    requestBody.thinkingMode = options.thinkingMode
  }
  if (options?.turnId) {
    requestBody.turnId = options.turnId
  }
  if (options?.agentConfigId !== undefined) {
    requestBody.agentConfigId = options.agentConfigId
  }
  if (options?.providerCode) {
    requestBody.providerCode = options.providerCode
  }
  if (options?.contextMode) {
    requestBody.contextMode = options.contextMode
  }
  if (options?.attachmentIds) {
    requestBody.attachmentIds = options.attachmentIds
  }
  if (options?.fileAssetIds && options.fileAssetIds.length > 0) {
    requestBody.fileAssetIds = options.fileAssetIds
  }

  try {
    yield* readSseStream('/api/deepseek/chat/stream', {
      method: 'POST',
      body: requestBody,
      signal: controller.signal
    })
  } catch (error: any) {

    if (error.name === 'AbortError' && abortController) {
      throw new Error('__USER_ABORT__')
    }

    if (error.name === 'AbortError') {
      throw new Error('请求超时，请检查网络连接或稍后重试')
    }

    if (error.message.includes('Failed to fetch') || error.message.includes('NetworkError')) {
      throw new Error('网络连接失败，请检查网络连接')
    }

    throw error
  } finally {
  }
}

// ===== 后台任务重连 =====

/**
 * 检查会话是否有正在运行的后台任务
 */
export async function checkActiveTask(conversationId: number): Promise<{
  active: boolean
  taskId?: number
  status?: string
  iteration?: number
  eventCount?: number
  /** 当前最新事件序号：重连时作为 cursor 传给 taskStream，只接收增量事件 */
  seq?: number
  pendingQuestionUuid?: string
  pendingQuestionText?: string
  /** 最近一次失败任务摘要（active=false 时可能返回）：
   *  任务异常停止（错误停止，非人为停止/非正常完成）后，前端据此恢复
   *  「任务中断」提示与「一键继续」入口（刷新页面/切换会话场景） */
  lastTask?: {
    id?: number
    status?: string
    errorMessage?: string
  } | null
}> {
  try {
    const response = await authFetch(`/api/deepseek/task/${conversationId}`)
    return await response.json()
  } catch (e) {
    console.warn('检查活跃任务失败:', e)
    return { active: false }
  }
}

/**
 * 订阅后台任务的事件流（用于页面刷新后重连）
 * 返回 SSE 事件流，格式与 streamChat 一致。
 * @param cursor 已消费的事件序号游标：后端跳过 seq <= cursor 的历史事件只推增量（游标续传），
 *               历史内容由前端 fetchMessages(force=true) 从数据库兜底，避免历史重放导致重复弹窗/重复追加
 */
export async function* taskStream(
  conversationId: number,
  abortController?: AbortController,
  cursor = 0
): AsyncGenerator<StreamChatEvent, void, unknown> {
  const controller = abortController ?? new AbortController()

  try {
    yield* readSseStream(`/api/deepseek/task/${conversationId}/stream?cursor=${cursor}`, {
      signal: controller.signal
    })
  } catch (e) {
    console.warn('任务流读取失败:', e)
    return
  }
}

/**
 * 取消正在运行的后台任务
 */
export async function cancelTask(conversationId: number): Promise<boolean> {
  try {
    const res = await authFetch(`/api/deepseek/task/${conversationId}/cancel`, {
      method: 'POST'
    })
    return res.ok
  } catch (e) {
    console.warn('取消任务失败:', e)
    return false
  }
}

// ===== 附件上传 =====

export interface FileUploadResult {
  success: boolean
  attachmentId: string
  fileName: string
  extension: string
  size: number
  type: string  // text/pdf/word/excel/image
  error?: string
}

/**
 * 上传附件文件，后端暂存并返回附件ID
 * LLM 可通过 chat_attachment 工具读取内容（支持PDF/Word/Excel）
 * @param file 要上传的文件
 */
export async function uploadAttachment(file: File): Promise<FileUploadResult> {
  const formData = new FormData()
  formData.append('file', file)

  // uploadAttachment 使用 FormData，不能设置 Content-Type（让浏览器自动设 multipart boundary）
  const authHeader = await getAuthHeaders()

  const response = await fetch('/api/deepseek/upload', {
    method: 'POST',
    headers: authHeader,
    body: formData
  })
  return response.json()
}

/**
 * 非流式聊天请求（一次性返回完整回复，备用）。
 *
 * 后端只有 `POST /api/deepseek/chat/stream`（SSE），**没有**非流式的 `/deepseek/chat` 端点，
 * 所以这里不能直连（旧实现会 404），改为消费 {@link streamChat} 把流式事件聚合成一次性结果。
 */
export async function chat(
  message: string,
  sessionId?: number
): Promise<ApiResponse<ChatResponse>> {
  const contents: string[] = []
  const thoughts: string[] = []
  let resultSessionId = sessionId

  for await (const event of streamChat(message, sessionId)) {
    if (event.type === 'content') {
      contents.push(event.data)
    } else if (event.type === 'thinking' || event.type === 'resume') {
      thoughts.push(event.data)
    } else if (event.type === 'complete' && typeof event.sessionId === 'number') {
      resultSessionId = event.sessionId
    }
    // ask_user / agent_event 属交互类事件，非流式聚合场景直接忽略
  }

  return {
    code: 200,
    message: 'ok',
    data: {
      sessionId: resultSessionId,
      content: contents.join(''),
      thinking: thoughts.join('')
    }
  }
}

// ===== 补充需求 =====

/**
 * 中途补充需求，发送到正在执行的 ToolLoop 中
 * @param conversationId 会话ID
 * @param message 补充的需求内容
 */
export async function supplementRequest(conversationId: number, message: string): Promise<{ success: boolean; error?: string; message?: string }> {
  const authHeader = await getAuthHeaders()

  const response = await fetch('/api/deepseek/supplement', {
    method: 'POST',
    headers: { ...authHeader, 'Content-Type': 'application/json' },
    body: JSON.stringify({ conversationId: String(conversationId), message })
  })
  return response.json()
}

// ===== 提示词优化 =====

/**
 * 提示词优化结果。
 *
 * <p>为什么不再「失败就返回原文」：调用方无法区分「模型认为原文已经够好」和
 * 「接口 500 / 超时 / 没配模型」，只能统一提示「没有得到更好的表述」，
 * 用户点了按钮等十几秒却看到一句无关的话。这里把成功/失败与原因分开返回。</p>
 */
export interface OptimizePromptResult {
  /** 是否拿到了可用的优化结果 */
  ok: boolean
  /** 优化后的文本；失败时回落为原文，便于调用方无脑回填 */
  optimized: string
  /** 失败原因（ok=false 时非空） */
  error?: string
  /** 本次耗时（毫秒，用于「AI 优化完成 · 3.2s」这类反馈） */
  elapsedMs: number
  /** 后端实际使用的模型（便于排查是哪个模型拖慢/截断） */
  model?: string
}

/**
 * 前端兜底超时（毫秒）。
 *
 * <p>后端 PromptOptimizeService 内部超时 15s，但 provider 侧连接池卡住、
 * 网关不返回时前端 fetch 会一直挂着 —— 按钮转了半分钟用户只会以为页面死了。
 * 这里给 30s 兜底（后端 15s + 传输余量），到点主动 abort 并给出可读提示。</p>
 */
const OPTIMIZE_TIMEOUT = 30_000

/** 读取后端错误体里的可读信息（兼容裸 Map 与 ApiResponse 两种返回） */
function readOptimizeError(data: unknown): string {
  if (!data || typeof data !== 'object') return ''
  const record = data as Record<string, unknown>
  const candidate = record.error ?? record.message ?? record.msg
  return typeof candidate === 'string' ? candidate.trim() : ''
}

/**
 * 使用 LLM 优化用户消息，使其更清晰明确。
 *
 * <p>该接口返回的是**裸结构**（`{ success, optimized, error }`，没有 code/data 包装），
 * 所以不能走 http-client 的 `request()`（它会按 `code === 200` 校验并抛错），
 * 这里保留裸 fetch，但补齐三件事：401 跳登录、超时中断、失败原因透传。</p>
 *
 * @param message 原始用户消息
 */
export async function optimizePrompt(message: string): Promise<OptimizePromptResult> {
  const startedAt = Date.now()
  const controller = new AbortController()
  const timer = setTimeout(() => controller.abort(), OPTIMIZE_TIMEOUT)

  try {
    const authHeader = await getAuthHeaders()

    const response = await fetch('/api/prompt/optimize', {
      method: 'POST',
      headers: { ...authHeader, 'Content-Type': 'application/json' },
      body: JSON.stringify({ message }),
      signal: controller.signal
    })

    // token 失效：与 sse-client 保持一致，清 token 并跳登录，而不是静默「优化失败」
    if (response.status === 401) {
      notifyUnauthorized('登录已过期，请重新登录')
      return { ok: false, optimized: message, error: '登录已过期，请重新登录', elapsedMs: Date.now() - startedAt }
    }

    if (!response.ok) {
      return {
        ok: false,
        optimized: message,
        error: `优化接口返回 HTTP ${response.status}`,
        elapsedMs: Date.now() - startedAt
      }
    }

    const data: unknown = await response.json()
    const record = (data ?? {}) as Record<string, unknown>
    const optimized = typeof record.optimized === 'string' ? record.optimized.trim() : ''

    if (record.success === true && optimized) {
      return {
        ok: true,
        optimized,
        elapsedMs: typeof record.elapsedMs === 'number' ? record.elapsedMs : Date.now() - startedAt,
        model: typeof record.model === 'string' ? record.model : undefined
      }
    }

    return {
      ok: false,
      optimized: message,
      error: readOptimizeError(data) || '模型没有返回可用结果，请重试',
      elapsedMs: Date.now() - startedAt,
      model: typeof record.model === 'string' ? record.model : undefined
    }
  } catch (e) {
    const aborted = e instanceof DOMException && e.name === 'AbortError'
    let error: string
    if (aborted) {
      error = `优化超时（${OPTIMIZE_TIMEOUT / 1000} 秒未响应），请稍后重试`
    } else if (e instanceof SyntaxError) {
      // 老版本后端没有该接口时，静态资源回退会返回 index.html —— 直接说「接口坏了」比抛 JSON 解析错更好懂
      error = '优化接口返回了非 JSON 响应，后端服务可能版本过旧，请更新后重启'
    } else if (e instanceof TypeError && /fetch|network/i.test(e.message)) {
      error = '网络连接失败，请确认后端服务已启动'
    } else {
      error = e instanceof Error ? e.message || '未知错误' : '未知错误'
    }
    console.warn('提示词优化失败:', error)
    return { ok: false, optimized: message, error, elapsedMs: Date.now() - startedAt }
  } finally {
    clearTimeout(timer)
  }
}
