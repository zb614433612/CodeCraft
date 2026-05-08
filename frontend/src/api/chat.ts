import type { ApiResponse } from '@/store/user'

export interface ChatRequest {
  message: string
  sessionId?: number
  promptFileName?: string
  userProfileId?: number
  executionMode?: string
  projectRoot?: string
  model?: string
  thinkingMode?: string
  gitCommitMode?: string
}

// 流式聊天的额外选项
export interface StreamChatOptions {
  promptFileName?: string
  userProfileId?: number
  executionMode?: string
  projectRoot?: string
  model?: string
  thinkingMode?: string
  gitCommitMode?: string
}

// 聊天响应数据（非流式，用于创建会话等）
export interface ChatResponse {
  sessionId?: number
  content?: string
  thinking?: string
}

// 流式聊天响应的事件类型
export interface StreamChatEvent {
  type: 'thinking' | 'content' | 'complete' | 'error' | 'ask_user' | 'resume' | 'pending_commit'
  data: string
  sessionId?: number
}

// 流式聊天响应解析器
class StreamChatParser {
  private buffer = ''
  private inThinking = false
  private thinkingContent = ''
  sessionId?: number

  // 解析SSE事件行
  parseEventLine(line: string): StreamChatEvent | null {
    // 匹配 data: 前缀，允许可选的空格
    const dataPrefixMatch = line.match(/^data:\s?/)
    if (dataPrefixMatch) {
      const data = line.slice(dataPrefixMatch[0].length)
      if (data === '[DONE]') {
        return { type: 'complete', data: '', sessionId: this.sessionId }
      }

      try {
        const parsed = JSON.parse(data)
        // 假设后端返回格式: { "sessionId": 123, "content": "..." } 或 { "thinking": "...", "content": "..." }
        // 新的DeepSeek流式格式: { "id": "...", "object": "...", "choices": [{ "index": 0, "delta": { "content": "...", "reasoning_content": "..." } }] }
        if (parsed.sessionId !== undefined) {
          this.sessionId = parsed.sessionId
        }

        // 处理新的DeepSeek流式格式
        if (parsed.choices && Array.isArray(parsed.choices) && parsed.choices.length > 0) {
          const choice = parsed.choices[0]
          if (choice.delta) {
            // reasoning_content和content互斥，一个为空时另一个有数据
            if (choice.delta.reasoning_content !== undefined && choice.delta.reasoning_content !== null && choice.delta.reasoning_content !== '') {
              const reasoningContent = choice.delta.reasoning_content

              return { type: 'thinking', data: reasoningContent, sessionId: this.sessionId }
            } else if (choice.delta.content !== undefined && choice.delta.content !== null && choice.delta.content !== '') {
              return { type: 'content', data: choice.delta.content, sessionId: this.sessionId }
            }
            // 如果两者都为空，则忽略这个chunk
            return null
          }
        }

        // 向后兼容：处理旧的思考内容字段
        if (parsed.thinking !== undefined) {
          return { type: 'thinking', data: parsed.thinking, sessionId: this.sessionId }
        }

        // 向后兼容：处理旧的普通内容字段
        if (parsed.content !== undefined) {
          return { type: 'content', data: parsed.content, sessionId: this.sessionId }
        }

        // 如果后端直接返回字符串内容
        if (typeof parsed === 'string') {
          return { type: 'content', data: parsed, sessionId: this.sessionId }
        }
      } catch (e) {
        // 如果不是JSON，返回null，让parseTextChunk处理
        return null
      }
    }
    return null
  }

  // 提取一行中所有data:前缀的数据块
  extractDataChunks(line: string): string[] {
    const chunks: string[] = []
    // 正则匹配 data: 前缀（可选空格），并分割字符串
    // 使用负向前瞻确保不会错误匹配内容中的"data:"
    // 简单的实现：按 data: 分割，但需要处理可能没有空格的情况
    const parts = line.split(/(?=data:\s?)/)
    for (const part of parts) {
      const match = part.match(/^data:\s?(.*)/)
      if (match) {
        chunks.push(match[1])
      }
    }
    // 如果没有找到data:前缀，返回原行
    if (chunks.length === 0) {
      return [line]
    }
    return chunks
  }

  // 解析原始文本流中的<think>标签
  parseTextChunk(chunk: string): { thinking?: string; content?: string } {
    const result: { thinking?: string; content?: string } = {}
    this.buffer += chunk

    // 处理<think>标签
    while (true) {
      if (!this.inThinking) {
        const thinkStart = this.buffer.indexOf('<think>')
        if (thinkStart !== -1) {
          // <think>之前的内容是普通内容
          const beforeThink = this.buffer.substring(0, thinkStart)
          if (beforeThink) {
            result.content = beforeThink
          }
          this.buffer = this.buffer.substring(thinkStart + 7) // 移除<think>
          this.inThinking = true
          this.thinkingContent = ''
          continue
        }
      }

      if (this.inThinking) {
        const thinkEnd = this.buffer.indexOf('</think>')
        if (thinkEnd !== -1) {
          // 提取思考内容
          this.thinkingContent += this.buffer.substring(0, thinkEnd)
          result.thinking = this.thinkingContent
          this.buffer = this.buffer.substring(thinkEnd + 8) // 移除</think>
          this.inThinking = false
          this.thinkingContent = ''
          continue
        } else {
          // 整个buffer都是思考内容的一部分
          this.thinkingContent += this.buffer
          this.buffer = ''
          break
        }
      }

      break
    }

    // 如果不在思考状态且buffer有内容，则是普通内容
    if (!this.inThinking && this.buffer) {
      result.content = this.buffer
      this.buffer = ''
    }

    return result
  }

  // 处理数据块并返回事件（流式处理每个数据块）
  processDataChunk(dataChunk: string): StreamChatEvent | null {
    // 首先尝试解析为JSON
    try {
      const parsed = JSON.parse(dataChunk)
      if (parsed.sessionId !== undefined) {
        this.sessionId = parsed.sessionId
      }
      // 处理 ask_user 事件
      if (parsed.event === 'ask_user') {
        return { type: 'ask_user', data: JSON.stringify({ uuid: parsed.uuid, question: parsed.question }), sessionId: this.sessionId }
      }
      // 处理 resume 事件
      if (parsed.event === 'resume') {
        return { type: 'resume', data: '', sessionId: this.sessionId }
      }
      // 处理新的DeepSeek流式格式
      if (parsed.choices && Array.isArray(parsed.choices) && parsed.choices.length > 0) {
        const choice = parsed.choices[0]
        if (choice.delta) {
          // reasoning_content和content互斥，一个为空时另一个有数据
          if (choice.delta.reasoning_content !== undefined && choice.delta.reasoning_content !== null && choice.delta.reasoning_content !== '') {
            const reasoningContent = choice.delta.reasoning_content

            return { type: 'thinking', data: reasoningContent, sessionId: this.sessionId }
          } else if (choice.delta.content !== undefined && choice.delta.content !== null && choice.delta.content !== '') {
            return { type: 'content', data: choice.delta.content, sessionId: this.sessionId }
          }
          // 如果两者都为空，则忽略这个chunk
          return null
        }
      }
      // 向后兼容：处理旧的思考内容字段
      if (parsed.thinking !== undefined) {
        return { type: 'thinking', data: parsed.thinking, sessionId: this.sessionId }
      } else if (parsed.content !== undefined) {
        return { type: 'content', data: parsed.content, sessionId: this.sessionId }
      } else if (typeof parsed === 'string') {
        return { type: 'content', data: parsed, sessionId: this.sessionId }
      }
    } catch (e) {
      // 不是JSON，继续处理
    }

    // 处理普通文本，可能包含<think>标签
    // 使用新的流式处理逻辑
    let chunk = dataChunk

    // 检查是否包含<think>开始标签
    const thinkStartIndex = chunk.indexOf('<think>')
    if (thinkStartIndex !== -1) {
      // 开始思考模式
      this.inThinking = true
      this.thinkingContent = ''
      // 移除<think>标签，剩余部分可能是思考内容
      chunk = chunk.substring(thinkStartIndex + 7)
      // 如果还有内容，添加到思考内容
      if (chunk) {
        this.thinkingContent += chunk
        return { type: 'thinking', data: this.thinkingContent, sessionId: this.sessionId }
      } else {
        // 只有标签，没有内容，等待下一个数据块
        return null
      }
    }

    // 检查是否包含</think>结束标签
    const thinkEndIndex = chunk.indexOf('</think>')
    if (thinkEndIndex !== -1) {
      // 结束思考模式
      const beforeEnd = chunk.substring(0, thinkEndIndex)
      if (beforeEnd) {
        this.thinkingContent += beforeEnd
      }
      const result: StreamChatEvent = { type: 'thinking', data: this.thinkingContent, sessionId: this.sessionId }
      // 重置状态
      this.inThinking = false
      this.thinkingContent = ''
      // 检查</think>之后是否还有内容（应该是普通内容）
      const afterEnd = chunk.substring(thinkEndIndex + 8)
      if (afterEnd) {
        // 之后的内容是普通内容
        // 注意：这里可能需要处理多个事件，但简化处理，只返回thinking事件
        // 实际上下一个数据块会处理剩余内容
        // 暂时将剩余内容添加到buffer中，下次处理
        this.buffer = afterEnd
      }
      return result
    }

    // 如果处于思考模式
    if (this.inThinking) {
      this.thinkingContent += chunk
      return { type: 'thinking', data: this.thinkingContent, sessionId: this.sessionId }
    }

    // 普通内容模式
    if (chunk) {
      return { type: 'content', data: chunk, sessionId: this.sessionId }
    }

    return null
  }
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

    // 如果是第一次对话，返回模拟sessionId
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
  const timeoutId = setTimeout(() => controller.abort(), 30000) // 30秒超时

  // 获取用户token
  let authHeader = {}
  try {
    const { useUserStore } = await import('@/store/user')
    const userStore = useUserStore()
    if (userStore.token) {
      authHeader = {
        'Authorization': `Bearer ${userStore.token}`
      }
    }
  } catch (error) {
    console.warn('获取用户token失败:', error)
  }

  const requestBody: ChatRequest = { message }
  if (sessionId !== undefined) {
    requestBody.sessionId = sessionId
  }
  if (options?.promptFileName) {
    requestBody.promptFileName = options.promptFileName
  }
  if (options?.userProfileId !== undefined) {
    requestBody.userProfileId = options.userProfileId
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
  if (options?.gitCommitMode) {
    requestBody.gitCommitMode = options.gitCommitMode
  }

  try {
    const response = await fetch('/api/deepseek/chat/stream', {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        ...authHeader,
        'Accept': 'text/event-stream' // 明确要求SSE
      },
      body: JSON.stringify(requestBody),
      signal: controller.signal
    })

    clearTimeout(timeoutId)

    if (!response.ok) {
      const errorText = await response.text()
      console.error(`HTTP error! status: ${response.status}, response: ${errorText}`)

      // 处理401未授权错误
      if (response.status === 401) {
        try {
          const { useUserStore } = await import('@/store/user')
          const userStore = useUserStore()
          userStore.clearUserInfo()
          console.log('检测到401未授权，已清除用户登录状态')

          // 跳转到登录页面
          const router = await import('@/router').then(module => module.default)
          if (router) {
            const currentRoute = router.currentRoute.value
            if (currentRoute.path !== '/login') {
              router.push('/login')
            }
          }
        } catch (error) {
          console.warn('处理401错误失败:', error)
        }
      }

      throw new Error(`请求失败，状态码: ${response.status}`)
    }

    const reader = response.body?.getReader()
    if (!reader) {
      throw new Error('响应体不可读')
    }

    const decoder = new TextDecoder()
    const parser = new StreamChatParser()
    let buffer = ''

    try {
      while (true) {
        const { done, value } = await reader.read()
        if (done) break

        const chunk = decoder.decode(value, { stream: true })
        console.log('Received chunk:', chunk.length, 'bytes, content:', chunk)
        buffer += chunk

        // 按行分割处理SSE格式
        const lines = buffer.split('\n')
        buffer = lines.pop() || '' // 最后一行可能不完整，保留在buffer中

        console.log('Processing lines:', lines.length, 'lines:', lines)
        for (const line of lines) {
          if (line.trim() === '') continue // 空行分隔事件
          console.log('Processing line:', line)

          // 提取一行中所有的 data: 数据块
          const dataChunks = parser.extractDataChunks(line)
          console.log('Extracted data chunks:', dataChunks)

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
        // 提取所有的 data: 数据块
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
  } catch (error: any) {
    clearTimeout(timeoutId)

    // 处理用户主动中止（如点击停止按钮）
    if (error.name === 'AbortError' && abortController) {
      throw new Error('__USER_ABORT__')
    }

    // 处理特定类型的错误
    if (error.name === 'AbortError') {
      throw new Error('请求超时，请检查网络连接或稍后重试')
    }

    // 处理网络错误
    if (error.message.includes('Failed to fetch') || error.message.includes('NetworkError')) {
      throw new Error('网络连接失败，请检查网络连接')
    }

    // 传递其他错误
    throw error
  }
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