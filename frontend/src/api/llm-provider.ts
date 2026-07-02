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

/** 解析 JSON 模型列表为 string[] */
export function parseModelList(modelList?: string): string[] {
  if (!modelList) return []
  try {
    return JSON.parse(modelList) as string[]
  } catch {
    return []
  }
}
