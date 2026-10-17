import { request } from '@/utils/http-client'

/** Provider 配置 */
export interface ProviderConfig {
  id?: number
  code: string
  name: string
  baseUrl: string
  apiKey?: string
  defaultModel?: string
  modelList?: string        // JSON 数组字符串
  parsedModelList?: string[] // 前端解析后的模型数组
  /** 扩展配置（JSON 字符串）：火山引擎 accessKey/secretKey/searchApiKey 等平台增强凭证 */
  extraConfig?: string
  requestTemplate?: string
  isDefault?: number
  enabled?: number
  sortOrder?: number
}

/**
 * 获取所有启用的 Provider 列表
 */
export async function listProviders() {
  const res = await request<any[]>('/llm-providers', { method: 'GET' })
  if (res.code === 200 && res.data) {
    res.data = res.data.map((p: any) => ({
      ...p,
      parsedModelList: parseModelList(p.modelList)
    }))
  }
  return res
}

/**
 * 获取单个 Provider 详情
 */
export async function getProvider(id: number) {
  return request<ProviderConfig>(`/llm-providers/${id}`, { method: 'GET' })
}

/**
 * 创建 Provider
 */
export async function createProvider(data: Partial<ProviderConfig>) {
  return request<ProviderConfig>('/llm-providers', {
    method: 'POST',
    body: JSON.stringify(data)
  })
}

/**
 * 更新 Provider
 */
export async function updateProvider(id: number, data: Partial<ProviderConfig>) {
  return request<void>(`/llm-providers/${id}`, {
    method: 'PUT',
    body: JSON.stringify(data)
  })
}

/**
 * 删除 Provider
 */
export async function deleteProvider(id: number) {
  return request<void>(`/llm-providers/${id}`, { method: 'DELETE' })
}

/** Provider 单币种余额明细 */
export interface ProviderBalanceInfo {
  currency: string
  totalBalance: string
  grantedBalance: string
  toppedUpBalance: string
  /** 现金余额（火山引擎 / MiMo） */
  cashBalance?: string
  /** 信控额度（火山引擎）或剩余透支额度（MiMo） */
  creditLimit?: string
  /** 冻结金额（火山引擎 / MiMo） */
  freezeAmount?: string
  /** 欠费金额（火山引擎） */
  arrearsBalance?: string
  /** 账号 ID（火山引擎） */
  accountId?: string
  /** 剩余额度百分比（quota 类型：MiniMax Token Plan） */
  remainingPercent?: number
  /** 剩余次数（quota 类型） */
  remainingCount?: number
  /** 总额次数（quota 类型） */
  totalCount?: number
  /** 额度重置时间 epoch ms（quota 类型） */
  resetAt?: number
  /** 套餐名称（quota 类型） */
  planName?: string
}

/** Provider 账户余额 / 套餐额度（balance=金额，quota=额度百分比） */
export interface ProviderBalance {
  providerCode: string
  balanceType?: 'balance' | 'quota'
  isAvailable: boolean
  balanceInfos: ProviderBalanceInfo[]
  updatedAt?: number
}

/**
 * 查询 Provider 账户余额 / 额度（DeepSeek / 火山引擎 / MiniMax / MiMo 支持）
 */
export async function getProviderBalance(code: string) {
  return request<ProviderBalance>(`/llm-providers/${code}/balance`, { method: 'GET' })
}

/** 解析 JSON 模型列表为 string[] */
export function parseModelList(modelList?: string): string[] {
  if (!modelList) return []
  try {
    return JSON.parse(modelList) as string[]
  } catch {
    return []
  }
}
