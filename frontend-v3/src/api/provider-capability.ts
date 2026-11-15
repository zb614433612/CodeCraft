import { request } from '@/utils/http-client'

/**
 * Provider 能力档案 API（T9 / T10）
 * ----------------------------------------------------------------------------
 * 后端 {@code ProviderCapabilityService} 是「一个 Provider 有哪些能力」的唯一真相源。
 * 本文件只做传输，不做任何推导 —— 推导逻辑（含凭证成对判定、Coding Plan 计费模式识别）
 * 全部在后端完成，前端从「推导者」降级为「展示者」。
 *
 * 为什么必须这样改：旧实现里 {@code utils/provider-capability.ts} 维护了 350 行推导副本
 * （VISION_TEMPLATES + 5 段正则 + deriveCapabilities），与后端规则「改一处、漏三处」，
 * 而漏掉不报错 —— 表现为「界面说能发图，实际图片被静默丢弃」。
 * ----------------------------------------------------------------------------
 */

/** 单项能力（与后端 {@code ProviderCapabilityVO.CapabilityItem} 一一对应） */
export interface RemoteCapabilityItem {
  /** on=可用 / warn=支持但当前配置用不了 / off=平台没有该能力 */
  state: 'on' | 'warn' | 'off'
  /** 徽章短文案（如「联网搜索·通用抓取」） */
  label: string
  /** 悬浮说明：讲清「为什么」与「怎么修」 */
  hint: string
  /** 平台本身是否具备该能力（false 时 state 必为 off） */
  platformSupported: boolean
  /** 缺失的凭证字段名（仅「平台支持但缺凭证」时非空，如 ['secretKey']） */
  missingKeys: string[]
}

/** Provider 能力档案（与后端 {@code ProviderCapabilityVO} 一一对应） */
export interface RemoteCapability {
  template: string
  /** 模板级接口为 null；实例级接口为实际 code */
  providerCode?: string | null
  label: string
  iconTone: string
  order: number
  defaultModel?: string | null
  vision: RemoteCapabilityItem
  search: RemoteCapabilityItem
  balance: RemoteCapabilityItem
  /** 云端文件上传能力；warn 表示「仅本地存储 + base64 内联」，功能仍可用 */
  filesApi: RemoteCapabilityItem
  /** 计费模式：TOKEN=按 token 估价 / QUOTA=套餐次数制不估价 / FREE=本地免费 */
  pricingMode: 'TOKEN' | 'QUOTA' | 'FREE'
  pricingHint: string
}

async function fetchData<T>(url: string): Promise<T> {
  const res = await request<T>(url)
  return (res as any)?.data as T
}

/**
 * 全部内置模板的能力档案（模板级，不含凭证判定）。
 *
 * 用途：新建 Provider 的模板下拉 + 刚选模板、还没填凭证时的能力预览。
 * 此状态下 search / balance 为 warn（「平台支持，配好凭证即可用」），而非 off。
 */
export function getTemplateCapabilities() {
  return fetchData<RemoteCapability[]>('/llm-providers/templates')
}

/**
 * 所有已启用 Provider 的能力（key = provider code）。
 *
 * 一次请求即可填满本地缓存，避免逐个 Provider 发 N 次请求。
 */
export function getAllProviderCapabilities() {
  return fetchData<Record<string, RemoteCapability>>('/llm-providers/capabilities')
}

/**
 * 单个 Provider 的能力（结合其真实凭证）。
 *
 * 注意：后端读的是 DB 原始值，因此能精确判断「凭证是否成对齐全」并返回 missingKeys；
 * 返回值本身不含任何密钥，可安全下发。
 */
export function getProviderCapability(code: string) {
  return fetchData<RemoteCapability>(
    `/llm-providers/${encodeURIComponent(code)}/capabilities`
  )
}
