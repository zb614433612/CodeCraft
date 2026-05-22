import type { ApiResponse } from '@/store/user'
import { getAuthHeaders, authFetch } from '@/utils/http-client'
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
}

// 流式聊天的额外选项
export interface StreamChatOptions {
  promptFileName?: string
  executionMode?: string
  projectRoot?: string
  model?: string
  thinkingMode?: string
  turnId?: string
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
  pendingQuestionUuid?: string
  pendingQuestionText?: string
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
 * 返回 SSE 事件流，格式与 streamChat 一致
 */
export async function* taskStream(
  conversationId: number,
  abortController?: AbortController
): AsyncGenerator<StreamChatEvent, void, unknown> {
  const controller = abortController ?? new AbortController()

  try {
    yield* readSseStream(`/api/deepseek/task/${conversationId}/stream`, {
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
  fileName: string
  extension: string
  size: number
  content: string
  image: boolean
  language: string
  error?: string
}

/**
 * 上传附件文件，后端读取文本内容后返回
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

// 非流式聊天请求（备用）
export async function chat(
  message: string,
  sessionId?: number
): Promise<ApiResponse<ChatResponse>> {
  const { request } = await import('./user')

  const requestBody: ChatRequest = { message }
  if (sessionId !== undefined) {
    requestBody.sessionId = sessionId
  }

  return request<ChatResponse>('/deepseek/chat', {
    method: 'POST',
    body: JSON.stringify(requestBody)
  })
}
