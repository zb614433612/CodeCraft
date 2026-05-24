import { request } from '@/utils/http-client'

export interface AgentConfig {
  id?: number
  name: string
  description?: string
  avatar?: string
  systemPrompt?: string
  toolNames?: string
  tools?: string[]
  modelName?: string
  thinkingMode?: string
  executionMode?: string
  workDir?: string
  sortOrder?: number
  enabled?: number
  isDefault?: number
  isBuiltin?: number
  userId?: number
  createdAt?: string
  updatedAt?: string
}

/**
 * 获取用户可用的 Agent 列表
 */
export async function listAgentConfigs() {
  return request<AgentConfig[]>('/agent-configs/list')
}

/**
 * 创建 Agent
 */
export async function createAgentConfig(data: AgentConfig) {
  return request<AgentConfig>('/agent-configs/create', {
    method: 'POST',
    body: JSON.stringify(data)
  })
}

/**
 * 更新 Agent
 */
export async function updateAgentConfig(id: number, data: AgentConfig) {
  return request<void>(`/agent-configs/${id}`, {
    method: 'PUT',
    body: JSON.stringify(data)
  })
}

/**
 * 删除 Agent
 */
export async function deleteAgentConfig(id: number) {
  return request<void>(`/agent-configs/${id}`, { method: 'DELETE' })
}

/**
 * 设为默认 Agent
 */
export async function setDefaultAgentConfig(id: number) {
  return request<void>(`/agent-configs/${id}/default`, { method: 'PUT' })
}

/**
 * 更新 Agent 运行时配置（模型、思考模式、执行模式、工作目录）
 */
export async function updateAgentRuntime(id: number, data: Partial<AgentConfig>) {
  return request<AgentConfig>(`/agent-configs/${id}/runtime`, {
    method: 'PUT',
    body: JSON.stringify(data)
  })
}
