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
  /** 「有单价、可估算成本」的请求条数；0 表示金额不可用（显示「—」而非 ¥0） */
  pricedRequestCount: number
  /** 缓存命中率（0~1）；-1 表示 Provider 未返回缓存字段 */
  cacheHitRate: number
  /** 命中部分估算费用（元） */
  cacheHitCost: number
  /** 未命中部分估算费用（元） */
  cacheMissCost: number
  /** 输出部分估算费用（元） */
  outputCost: number
  /** 三段费用拆分是否可用（false 时隐藏费用结构图） */
  breakdownAvailable: boolean
  /** 是否启用峰谷计价（true 时下方时段字段才有意义；旧后端不返回按 false 处理） */
  peakPricingEnabled?: boolean
  /** 当前时段：peak / off-peak / null（未启用峰谷时为 null） */
  currentPeriod?: 'peak' | 'off-peak' | null
  /** 高峰时段记录的费用合计（元，结构参考值） */
  peakCostEstimate?: number
  /** 空闲时段记录的费用合计（元，结构参考值） */
  offPeakCostEstimate?: number
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
  /** 组内「有单价、可估算成本」的请求条数；0 表示该组金额不可用 */
  pricedRequestCount: number
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
  /** 估算费用（元）；null = 该模型未配置单价，不估算（≠ 免费） */
  costEstimate: number | null
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
  /** 本轮「有单价、可估算成本」的请求条数；0 表示金额不可用 */
  pricedRequestCount: number
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
 * 会话内「按轮次」聚合（聊天页每条 AI 回复的成本 / 命中率 / token）
 *
 * <p>只返回带 turnId 的记录：v3.0.9 及更早的存量数据没有 turnId，
 * 无法归并到具体某一轮，只体现在会话汇总里。</p>
 */
export function getUsageTurns(conversationId: number) {
  return fetchData<TokenUsageTurn[]>(`/token-usage/turns?conversationId=${conversationId}`)
}

/** 单模型两档单价（元 / 百万 tokens） */
export interface TokenPricingPrice {
  cacheHitInput: number
  cacheMissInput: number
  output: number
}

/**
 * 峰谷计价上下文（当前时段 / 下次切换 / 各模型两档单价）。
 *
 * <p>峰谷下同一任务高峰/空闲金额可差数倍，界面必须能把「为什么金额变了」讲清楚。</p>
 * <p>{@code enabled=false} 时 {@code period / nextSwitchAt / windows} 均为空，前端不展示时段 UI。</p>
 */
export interface TokenPricingContext {
  zone: string
  now: string
  period: 'peak' | 'off-peak' | null
  nextSwitchAt: string | null
  windows: string[]
  enabled: boolean
  peakMultiplier: number
  models: Array<{
    model: string
    offPeak: TokenPricingPrice
    peak: TokenPricingPrice
    effective: 'peak' | 'off-peak' | null
  }>
}

/**
 * 峰谷计价上下文（只读内存配置，不查库不联网，可高频调用）
 *
 * @param provider Provider 编码（如 deepseek）；不传时只返回全局信息与单价表
 */
export function getPricingContext(provider?: string) {
  const query = provider ? `?provider=${encodeURIComponent(provider)}` : ''
  return fetchData<TokenPricingContext>(`/token-usage/pricing-context${query}`)
}
