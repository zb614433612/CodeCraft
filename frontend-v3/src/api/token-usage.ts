import { request } from '@/utils/http-client'

/**
 * LLM 成本统计 API
 * ----------------------------------------------------------------------------
 * 数据来源：后端 llm_token_usage 表（每轮 API 请求一条真实 usage）。
 * ⚠️ 所有金额均为按单价表估算的「成本结构参考值」，**不是账单**，以官方平台为准。
 */

/** 用量汇总 */
export interface TokenUsageSummary {
  /** 请求次数（≈ 工具循环总轮次 + 独立请求数） */
  requestCount: number
  /** 输入 token（命中 + 未命中） */
  promptTokens: number
  /** 输出 token（含思考链） */
  completionTokens: number
  totalTokens: number
  cacheHitTokens: number
  cacheMissTokens: number
  /** 思考链 token（已含在 completionTokens 内） */
  reasoningTokens: number
  /** 估算总费用（元） */
  costEstimate: number
  /** 缓存命中率（0~1）；-1 表示 Provider 未返回缓存字段 */
  cacheHitRate: number
  /** 命中部分估算费用（元） */
  cacheHitCost: number
  /** 未命中部分估算费用（元） */
  cacheMissCost: number
  /** 输出部分估算费用（元） */
  outputCost: number
}

/** 分组统计（按模型 / 按会话） */
export interface TokenUsageGroup {
  groupKey: string
  groupName: string
  requestCount: number
  promptTokens: number
  completionTokens: number
  totalTokens: number
  cacheHitTokens: number
  cacheMissTokens: number
  costEstimate: number
  cacheHitRate: number
}

/** 单轮明细 */
export interface TokenUsageDetail {
  id: number
  conversationId: number
  messageId: number | null
  iteration: number
  provider: string
  model: string
  promptTokens: number
  completionTokens: number
  totalTokens: number
  cacheHitTokens: number
  cacheMissTokens: number
  reasoningTokens: number
  costEstimate: number
  createdAt: string
}

/** 单轮对话用量（一次用户提问，含其工具循环内的多次请求） */
export interface TokenUsageTurn {
  turnId: string
  /** 本轮发起过几次 API 请求（工具循环轮次） */
  requestCount: number
  promptTokens: number
  completionTokens: number
  totalTokens: number
  cacheHitTokens: number
  cacheMissTokens: number
  reasoningTokens: number
  /** 估算费用（元） */
  costEstimate: number
  /** 缓存命中率（0~1）；-1 表示 Provider 未返回缓存字段 */
  cacheHitRate: number
}

export type UsageRange = 'today' | '7d' | '30d' | 'all'

/**
 * 统一解包：http-client 的 request 返回 ApiResponse<T>，页面只关心 data
 */
async function fetchData<T>(url: string): Promise<T> {
  const res = await request<T>(url)
  return (res as any)?.data as T
}

/** 汇总（传 conversationId 时返回该会话全生命周期汇总，忽略 range） */
export function getUsageSummary(range: UsageRange = 'today', conversationId?: number) {
  const query = new URLSearchParams({ range })
  if (conversationId) {
    query.set('conversationId', String(conversationId))
  }
  return fetchData<TokenUsageSummary>(`/token-usage/summary?${query.toString()}`)
}

/** 按模型统计 */
export function getUsageByModel(range: UsageRange = '7d') {
  return fetchData<TokenUsageGroup[]>(`/token-usage/by-model?range=${range}`)
}

/** 按会话统计（费用倒序前 N） */
export function getUsageByConversation(range: UsageRange = '7d', limit = 10) {
  return fetchData<TokenUsageGroup[]>(`/token-usage/by-conversation?range=${range}&limit=${limit}`)
}

/** 会话内轮次明细 */
export function getUsageDetail(conversationId: number) {
  return fetchData<TokenUsageDetail[]>(`/token-usage/detail?conversationId=${conversationId}`)
}

/**
 * 会话内「按轮次」聚合（聊天页每条 AI 回复下方的成本 / 命中率 / token）
 *
 * <p>只返回带 turnId 的记录：v3.0.9 及更早的存量数据没有 turnId，
 * 无法归并到具体某一轮，只体现在会话汇总里。</p>
 */
export function getUsageTurns(conversationId: number) {
  return fetchData<TokenUsageTurn[]>(`/token-usage/turns?conversationId=${conversationId}`)
}
