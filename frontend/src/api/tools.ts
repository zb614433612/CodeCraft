import { request } from '@/utils/http-client'

/** 工具信息 */
export interface ToolInfo {
  name: string
  description: string
  risk: 'low' | 'medium' | 'high'
}

/** 工具分类 */
export interface ToolCategory {
  code: string
  label: string
  tools: ToolInfo[]
}

/** 工具注册表响应 */
export interface ToolRegistryData {
  categories: ToolCategory[]
}

/**
 * 获取工具注册表（按分类分组，含描述）
 */
export async function listToolRegistry() {
  return request<ToolRegistryData>('/tools/registry')
}
