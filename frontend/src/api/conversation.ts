import type { ApiResponse } from '@/store/user'
import { estimateTokenCount } from '@/utils/tokenCalculator'
import { request } from './user'

// 后端返回的会话数据类型
export interface ConversationResponse {
  id: number
  name: string
  userId: number
  createdAt: string // ISO字符串
  updatedAt: string // ISO字符串
}

// 后端返回的消息数据类型
export interface MessageResponse {
  id: number
  conversationId: number
  role: string // SYSTEM, user, assistant, tool
  content: string
  reasoning: string | null
  createdAt: string // ISO字符串
}

// 映射到前端的会话数据类型（与HomeView中的Conversation接口保持一致）
export interface Conversation {
  id: string // 转换为字符串
  title: string // 对应name字段
  updatedAt: number // 时间戳
  messageCount: number // 默认0，后续可根据实际消息数更新
}

// 获取会话列表
export async function getConversationList(agentType?: string): Promise<ApiResponse<ConversationResponse[]>> {
  const params = agentType ? `?agentType=${agentType}` : ''
  const response = await request<ConversationResponse[]>(`/conversation/list${params}`, {
    method: 'GET'
  })
  return response
}

// 获取会话消息列表
export async function getConversationMessages(conversationId: number): Promise<ApiResponse<MessageResponse[]>> {
  // 使用通用的request函数，它会自动添加Authorization头
  const response = await request<MessageResponse[]>(`/conversation/${conversationId}/messages`, {
    method: 'GET'
  })
  return response
}

// 将后端响应数据映射到前端格式
export function mapConversationResponseToConversation(resp: ConversationResponse): Conversation {
  let updatedAt: number
  const date = new Date(resp.updatedAt)
  if (isNaN(date.getTime())) {
    console.warn('无效的updatedAt字符串:', resp.updatedAt, '使用当前时间')
    updatedAt = Date.now()
  } else {
    updatedAt = date.getTime()
  }
  return {
    id: resp.id.toString(), // 数字id转为字符串
    title: resp.name,
    updatedAt,
    messageCount: 0 // 默认0，后续可考虑从其他接口获取
  }
}

// 将后端消息数据映射到前端聊天消息格式
export function mapMessageResponseToChatMessage(resp: MessageResponse): any {
  const timestamp = new Date(resp.createdAt).getTime()
  // 角色映射：SYSTEM -> system, user -> user, assistant -> assistant, tool -> tool
  // 注意：前端ChatMessage接口的role是'user' | 'assistant'，需要扩展或过滤
  const role = resp.role.toLowerCase()
  // 计算消息内容的Token数量（不计算思考过程）
  const tokenCount = estimateTokenCount(resp.content)

  return {
    id: resp.id.toString(),
    role: role === 'user' ? 'user' : 'assistant', // 暂时只处理user和assistant，其他角色过滤掉
    content: resp.content,
    thinking: resp.reasoning || undefined,
    timestamp,
    isStreaming: false,
    tokenCount
  }
}

// 处理消息分组，将分段存储的思考过程和TOOL消息合并到相应的助手消息中
export function processMessageGroups(messages: MessageResponse[]): any[] {
  const marker = '----工具调用:----'
  const result: any[] = []
  let currentAssistantMsg: any = null
  const pendingThinking: string[] = []

  const flushAssistantMsg = () => {
    if (!currentAssistantMsg) return

    const processed = processThinkingParts(pendingThinking, marker)
    pendingThinking.length = 0

    const cleanThinking = processed.cleanThinking || undefined
    const toolResults = processed.toolResults.length > 0 ? processed.toolResults : undefined

    currentAssistantMsg.thinking = cleanThinking
    currentAssistantMsg.toolResults = toolResults

    // 加上 toolResults 中工具返回数据的 token 数
    if (toolResults) {
      for (const tr of toolResults) {
        currentAssistantMsg.tokenCount += estimateTokenCount(tr.content)
      }
    }

    delete currentAssistantMsg.segments

    result.push(currentAssistantMsg)
    currentAssistantMsg = null
  }

  for (const msg of messages) {
    const role = msg.role.toLowerCase()
    const timestamp = new Date(msg.createdAt).getTime()

    if (role === 'user') {
      flushAssistantMsg()
      result.push({
        id: msg.id.toString(),
        role: 'user',
        content: msg.content,
        timestamp,
        isStreaming: false,
        tokenCount: estimateTokenCount(msg.content)
      })
    } else if (role === 'assistant') {
      if (!currentAssistantMsg) {
        currentAssistantMsg = {
          id: msg.id.toString(),
          role: 'assistant',
          content: '',
          timestamp,
          isStreaming: false,
          tokenCount: 0
        }
      }

      if (msg.content) {
        // 拼接多个内容段（不立即flush，让同一轮对话的多次回答合并为一条消息）
        if (currentAssistantMsg.content) {
          currentAssistantMsg.content += '\n' + msg.content
        } else {
          currentAssistantMsg.content = msg.content
        }
        currentAssistantMsg.tokenCount += estimateTokenCount(msg.content)
        if (msg.reasoning) {
          pendingThinking.push(msg.reasoning)
        }
      } else {
        if (msg.reasoning) {
          pendingThinking.push(msg.reasoning)
        }
        currentAssistantMsg.timestamp = timestamp
      }
    } else if (role === 'tool') {
      if (msg.reasoning) {
        pendingThinking.push(msg.reasoning)
      }
    }
  }

  flushAssistantMsg()
  return result
}

function processThinkingParts(parts: string[], marker: string): { cleanThinking: string; toolResults: { at: number; content: string }[] } {
  const processing = [...parts]
  parts.length = 0
  let cleanThinking = ''
  const toolResults: { at: number; content: string }[] = []

  for (const part of processing) {
    const detail = processSinglePart(part, marker)
    if (detail.type === 'thinking') {
      if (cleanThinking) cleanThinking += '\n'
      cleanThinking += detail.content
    } else {
      toolResults.push({
        at: cleanThinking.length,
        content: detail.content
      })
    }
  }

  return { cleanThinking, toolResults }
}

function processSinglePart(part: string, marker: string): { type: 'thinking' | 'tool'; content: string } {
  if (part.includes(marker)) {
    const idx = part.indexOf(marker)
    const headerEnd = part.indexOf('\n', idx)
    const toolContent = headerEnd !== -1 ? part.substring(headerEnd + 1).trim() : ''
    return { type: 'tool', content: toolContent }
  }
  return { type: 'thinking', content: part }
}

// 删除会话
export async function deleteConversation(conversationId: number): Promise<ApiResponse<void>> {
  // 使用通用的request函数，它会自动添加Authorization头
  const response = await request<void>(`/conversation/${conversationId}`, {
    method: 'DELETE'
  })
  return response
}

