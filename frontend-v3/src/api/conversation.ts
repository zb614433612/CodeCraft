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
  /** 会话所属 Agent 配置 ID（后端 Conversation 实体实际返回；用于跨页引用目标定位/跨 Agent 切换） */
  agentConfigId?: number | null
}

// 后端返回的消息数据类型
export interface MessageResponse {
  id: number
  conversationId: number
  role: string // SYSTEM, user, assistant, tool
  content: string
  reasoning: string | null
  turnId: string | null
  /** 消息类型：null=普通消息，desktop_inject=桌面截图自动注入（前端不渲染为用户气泡） */
  messageType?: string | null
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
export async function getConversationList(agentType?: string, agentConfigId?: number): Promise<ApiResponse<ConversationResponse[]>> {
  const params = new URLSearchParams()
  if (agentConfigId) {
    params.set('agentConfigId', String(agentConfigId))
  } else if (agentType) {
    params.set('agentType', agentType)
  }
  const qs = params.toString()
  const response = await request<ConversationResponse[]>(`/conversation/list${qs ? '?' + qs : ''}`, {
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

/**
 * 会话消息分页结果（按「轮次」游标，从最新往前翻）
 * 一轮 = 一次用户提问 + 其后该轮的全部回复（assistant/tool）
 */
export interface MessagePageResponse {
  /** 本页消息（按 id 升序，与 processMessageGroups 的时间正序要求一致） */
  messages: MessageResponse[]
  /** 是否还有更早的历史消息（true 时前端可在滚动到顶部继续加载） */
  hasMore: boolean
  /** 下一页游标：本页最早一条消息 id；继续往前翻时作为 beforeId 传入 */
  nextBeforeId?: number | null
  /** 实际返回的轮数 */
  rounds?: number
  /*
   * ── 上下文压缩边界（2026-11-17 新增，对应后端 ConversationMessagePageVO）──
   *
   * 后端压缩后重建上下文只使用「system + 压缩摘要 + 摘要之后的消息」，
   * 被压缩的历史**不再计入上下文**，但原文仍完整保留在 conversation_message 表里
   * （用户向上翻页照样能看到历史聊天记录）。
   *
   * 前端「输入区下方『上下文 xx』」是独立的前端字符估算，必须用同一口径，
   * 否则长会话会虚高数倍，显示的是错误数据。
   *
   * · compactedUpToMessageId：已折叠进摘要的最后一条消息 id（含）→ id ≤ 它的消息不计入
   * · compactedSummaryTokens：被折叠那段历史压缩成摘要后占用的 token（多条摘要累加）
   * · 均可能为缺省（后端未发生压缩 / 老接口），此时行为与改动前完全一致
   */
  compactedUpToMessageId?: number | null
  compactedSummaryTokens?: number
}

/**
 * 分页获取会话消息（聊天记录懒加载）
 *
 * <p>改造背景：超长会话全量查询会让 DB 扫描、网络传输、前端 markdown 分组渲染全部变重，打开会话明显卡顿。
 * 现改为默认只取最近 5 轮，用户向上滚动到顶部时用 nextBeforeId 继续加载更早的 5 轮。</p>
 *
 * @param conversationId 会话ID
 * @param beforeId       分页游标（上一页返回的 nextBeforeId；首页传 null/undefined）
 * @param rounds         本次加载的轮数（默认 5，后端上限 50）
 */
export async function getConversationMessagesPage(
  conversationId: number,
  beforeId?: number | null,
  rounds = 5
): Promise<ApiResponse<MessagePageResponse>> {
  const params = new URLSearchParams()
  if (beforeId !== undefined && beforeId !== null) params.set('beforeId', String(beforeId))
  params.set('rounds', String(rounds))
  return request<MessagePageResponse>(`/conversation/${conversationId}/messages/page?${params.toString()}`, {
    method: 'GET'
  })
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
      // 修复：桌面截图自动注入消息（role=user 仅为兼容 API 消息还原）不渲染为用户气泡、也不打断 AI 消息聚合；
      // 仅把消息id记入宿主 AI 消息（injectSourceIds），由 loadMessageFileRefs 把截图资产归并到该 AI 消息回显
      if (msg.messageType === 'desktop_inject') {
        if (currentAssistantMsg) {
          if (!currentAssistantMsg.injectSourceIds) currentAssistantMsg.injectSourceIds = []
          currentAssistantMsg.injectSourceIds.push(msg.id.toString())
        }
        continue
      }
      // 用户中途补充需求（supplement_inject）：已作为 user 消息参与 API 上下文还原
      // （落库是为保证下一轮重建时前缀连续，否则跨轮首 token 会全量重算），
      // 但历史里不渲染气泡 —— 否则用户会看到一条系统代他"复读"需求的假气泡。
      if (msg.messageType === 'supplement_inject') {
        continue
      }
      flushAssistantMsg()
      result.push({
        id: msg.id.toString(),
        role: 'user',
        content: msg.content,
        timestamp,
        isStreaming: false,
        tokenCount: estimateTokenCount(msg.content),
        turnId: msg.turnId || undefined
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

// 更新会话名称
export async function updateConversationName(conversationId: number, name: string): Promise<ApiResponse<void>> {
  return request<void>(`/conversation/${conversationId}?name=${encodeURIComponent(name)}`, {
    method: 'PUT'
  })
}

// 删除会话
export async function deleteConversation(conversationId: number): Promise<ApiResponse<void>> {
  // 使用通用的request函数，它会自动添加Authorization头
  const response = await request<void>(`/conversation/${conversationId}`, {
    method: 'DELETE'
  })
  return response
}

/**
 * 从指定轮次起删除消息（含该轮自身）—— 「重新生成」的前置动作。
 *
 * <p>后端 `DELETE /api/conversation/{id}/messages?turnId=`：找到该 turnId 对应的
 * 最早一条消息（即那轮的 user 提问），把它及其后所有消息一并删除。</p>
 *
 * <p>为什么必须先删：直接重发会在 DB 里留下「旧回复 + 新提问 + 新回复」，
 * 刷新后历史错乱。删除后重发才是真正的「重新生成」。</p>
 *
 * @returns data 为实际删除的消息条数（0 表示该轮还没落库，可继续重发）
 */
export async function deleteMessagesFromTurn(conversationId: number, turnId: string): Promise<ApiResponse<number>> {
  const params = new URLSearchParams({ turnId })
  return request<number>(`/conversation/${conversationId}/messages?${params.toString()}`, {
    method: 'DELETE'
  })
}

