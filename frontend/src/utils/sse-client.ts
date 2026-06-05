import { getAuthHeaders } from './http-client'

// ============================================================
// 流式聊天响应的事件类型
// ============================================================
export interface SkillMatchInfo {
  id: number
  name: string
  confidence: number
  usageCount: number
  triggerWords: string
  description: string
}

export type StreamChatEvent = {
  type: 'thinking' | 'content' | 'complete' | 'resume'
  data: string
  sessionId?: number
} | {
  type: 'ask_user'
  data: { uuid: string; question: string; askType?: string; toolName?: string; filePath?: string; fullDetail?: string }
  sessionId?: number
} | {
  type: 'skill_match'
  data: SkillMatchInfo[]
  sessionId?: number
} | {
  type: 'agent_event'
  data: AgentStreamEvent
  sessionId?: number
} | {
  type: 'error'
  data: string
  sessionId?: number
}

/**
 * 子Agent流式事件（与SSE事件格式对应）
 */
export interface AgentStreamEvent {
  event: 'agent_forked' | 'agent_thinking' | 'agent_tool_call'
       | 'agent_status' | 'agent_completed' | 'agent_skill_match'
  agent_id: string
  name?: string
  status?: string
  tool_name?: string
  file_path?: string
  result?: string
  reasoning_content?: string
  skills?: SkillMatchInfo[]
  summary?: string
}

// ============================================================
// SSE 流解析器
// ============================================================
export class SseParser {
  private inThinking = false
  private thinkingContent = ''
  sessionId?: number

  /**
   * 解析数据块并生成事件
   */
  processDataChunk(dataChunk: string): StreamChatEvent | null {
    // 尝试解析为JSON
    try {
      const parsed = JSON.parse(dataChunk)
      if (parsed.sessionId !== undefined) {
        this.sessionId = parsed.sessionId
      }
      // 处理 ask_user 事件
      if (parsed.event === 'ask_user') {
        return { type: 'ask_user', data: { uuid: parsed.uuid, question: parsed.question, askType: parsed.askType, toolName: parsed.toolName, filePath: parsed.filePath, fullDetail: parsed.fullDetail }, sessionId: this.sessionId }
      }
      // 处理 skill_match 事件
      if (parsed.event === 'skill_match') {
        return { type: 'skill_match', data: parsed.skills || [], sessionId: this.sessionId }
      }
      // 处理子Agent事件（agent_forked / agent_thinking / agent_tool_call 等）
      if (typeof parsed.event === 'string' && parsed.event.startsWith('agent_')) {
        return {
          type: 'agent_event',
          data: parsed as AgentStreamEvent,
          sessionId: this.sessionId
        }
      }
      // 处理 resume 事件
      if (parsed.event === 'resume') {
        return { type: 'resume', data: '', sessionId: this.sessionId }
      }
      // 处理新的DeepSeek流式格式 (choices[0].delta)
      if (parsed.choices && Array.isArray(parsed.choices) && parsed.choices.length > 0) {
        const choice = parsed.choices[0]
        if (choice.delta) {
          if (choice.delta.reasoning_content !== undefined && choice.delta.reasoning_content !== null && choice.delta.reasoning_content !== '') {
            return { type: 'thinking', data: choice.delta.reasoning_content, sessionId: this.sessionId }
          } else if (choice.delta.content !== undefined && choice.delta.content !== null && choice.delta.content !== '') {
            return { type: 'content', data: choice.delta.content, sessionId: this.sessionId }
          }
          return null
        }
      }
      // 向后兼容：处理旧的字段格式
      if (parsed.thinking !== undefined) {
        return { type: 'thinking', data: parsed.thinking, sessionId: this.sessionId }
      } else if (parsed.content !== undefined) {
        return { type: 'content', data: parsed.content, sessionId: this.sessionId }
      } else if (typeof parsed === 'string') {
        return { type: 'content', data: parsed, sessionId: this.sessionId }
      } else if (this.sessionId !== undefined && Object.keys(parsed).length <= 2 && 'sessionId' in parsed) {
        // 纯 sessionId 事件（后端在第一个事件中发送的真实会话 ID）
        // 返回一个空 content 事件将 sessionId 传递给上层，不要丢弃
        return { type: 'content', data: '', sessionId: this.sessionId }
      } else {
        return null
      }
    } catch {
      // 不是JSON，继续处理
    }

    // 处理 [DONE] 标记
    if (dataChunk.trim() === '[DONE]') {
      return { type: 'complete', data: '', sessionId: this.sessionId }
    }

    // 处理 <think> 标签
    let chunk = dataChunk

    const thinkStartIndex = chunk.indexOf('<think>')
    if (thinkStartIndex !== -1) {
      this.inThinking = true
      this.thinkingContent = ''
      chunk = chunk.substring(thinkStartIndex + 7)
      if (chunk) {
        this.thinkingContent += chunk
        return { type: 'thinking', data: this.thinkingContent, sessionId: this.sessionId }
      } else {
        return null
      }
    }

    const thinkEndIndex = chunk.indexOf('</think>')
    if (thinkEndIndex !== -1) {
      const beforeEnd = chunk.substring(0, thinkEndIndex)
      if (beforeEnd) {
        this.thinkingContent += beforeEnd
      }
      const result: StreamChatEvent = { type: 'thinking', data: this.thinkingContent, sessionId: this.sessionId }
      this.inThinking = false
      this.thinkingContent = ''
      return result
    }

    if (this.inThinking) {
      this.thinkingContent += chunk
      return { type: 'thinking', data: this.thinkingContent, sessionId: this.sessionId }
    }

    if (chunk) {
      return { type: 'content', data: chunk, sessionId: this.sessionId }
    }

    return null
  }

  /**
   * 从一行中提取 data: 前缀后的数据块
   */
  extractDataChunks(line: string): string[] {
    const match = line.match(/^data:\s?(.*)/)
    if (match) {
      return [match[1]]
    }
    if (line.trim()) {
      return [line]
    }
    return []
  }
}

// ============================================================
// SSE 流读取器 — 将 ReadableStream 转换为 AsyncGenerator
// ============================================================
export async function* readSseStream(
  url: string,
  options: {
    method?: string
    body?: unknown
    signal?: AbortSignal
  } = {}
): AsyncGenerator<StreamChatEvent, void, unknown> {
  const { method = 'GET', body, signal } = options

  const authHeader = await getAuthHeaders()

  const fetchOptions: RequestInit = {
    method,
    headers: {
      'Content-Type': 'application/json',
      ...authHeader,
      'Accept': 'text/event-stream'
    },
    signal
  }

  if (body && method === 'POST') {
    fetchOptions.body = JSON.stringify(body)
  }

  const response = await fetch(url, fetchOptions)

  if (!response.ok) {
    if (response.status === 401) {
      try {
        const { useUserStore } = await import('@/store/user')
        const userStore = useUserStore()
        userStore.clearUserInfo()
        const router = await import('@/router').then(module => module.default)
        if (router && router.currentRoute.value.path !== '/login') {
          router.push('/login')
        }
      } catch { /* ignore */ }
    }
    throw new Error(`SSE请求失败，状态码: ${response.status}`)
  }

  const reader = response.body?.getReader()
  if (!reader) {
    throw new Error('响应体不可读')
  }

  const decoder = new TextDecoder()
  const parser = new SseParser()
  let buffer = ''

  try {
    while (true) {
      // 检查是否已被用户中止（AbortController.abort()）
      // 注意：reader.read() 在某些环境中不会因 signal abort 而抛错，
      // 所以需要显式检查 signal.aborted 来确保取消生效
      if (signal?.aborted) {
        await reader.cancel()
        throw new DOMException('The operation was aborted', 'AbortError')
      }

      const { done, value } = await reader.read()
      if (done) break

      // 读取到数据后再检查一次（避免在 reader.read() 阻塞期间 abort 但未抛错）
      if (signal?.aborted) {
        await reader.cancel()
        throw new DOMException('The operation was aborted', 'AbortError')
      }

      buffer += decoder.decode(value, { stream: true })

      const lines = buffer.split('\n')
      buffer = lines.pop() || ''

      for (const line of lines) {
        if (line.trim() === '') continue

        const dataChunks = parser.extractDataChunks(line)
        for (const dataChunk of dataChunks) {
          const event = parser.processDataChunk(dataChunk)
          if (event) {
            yield event
          }
        }
      }
    }

    // 处理buffer中剩余的内容
    if (buffer !== '') {
      const dataChunks = parser.extractDataChunks(buffer)
      for (const dataChunk of dataChunks) {
        const event = parser.processDataChunk(dataChunk)
        if (event) {
          yield event
        }
      }
    }

    yield { type: 'complete', data: '' }
  } finally {
    reader.releaseLock()
  }
}
