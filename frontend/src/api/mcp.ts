import { request } from '@/utils/http-client'

/** MCP 服务器配置 + 连接状态（后端返回） */
export interface McpServerVO {
  id: number
  name: string
  type: 'http' | 'stdio'
  url?: string
  command?: string
  /** 是否已配置请求头（后端不回显明文，避免密钥泄露） */
  hasHeaders?: boolean
  toolPrefix?: string
  permissionLevel: string
  enabled: number
  autoRegister: number
  createdAt?: string
  updatedAt?: string
  /** 连接状态：CONNECTED / FAILED / DISABLED */
  status: string
  errorMessage?: string
  connectedAt?: string
  /** 拉取到的外部工具数量 */
  toolCount: number
  /** 实际注册进 ToolRegistry 的工具数量 */
  registeredToolCount: number
}

/** MCP 服务器配置（提交用） */
export interface McpServerConfig {
  id?: number
  name: string
  type: 'http' | 'stdio'
  url?: string
  command?: string
  headers?: string
  toolPrefix?: string
  permissionLevel: string
  enabled: number
  autoRegister: number
}

/** 查询全部 MCP 服务器（含连接状态） */
export function listMcpServers() {
  return request<McpServerVO[]>('/mcp/servers')
}

/** 新增 MCP 服务器 */
export function createMcpServer(data: McpServerConfig) {
  return request<McpServerVO>('/mcp/servers', {
    method: 'POST',
    body: JSON.stringify(data)
  })
}

/** 更新 MCP 服务器 */
export function updateMcpServer(id: number, data: McpServerConfig) {
  return request<McpServerVO>(`/mcp/servers/${id}`, {
    method: 'PUT',
    body: JSON.stringify(data)
  })
}

/** 删除 MCP 服务器 */
export function deleteMcpServer(id: number) {
  return request<void>(`/mcp/servers/${id}`, { method: 'DELETE' })
}

/** 连接指定 MCP 服务器 */
export function connectMcpServer(id: number) {
  return request<McpServerVO>(`/mcp/servers/${id}/connect`, { method: 'POST' })
}

/** 断开指定 MCP 服务器 */
export function disconnectMcpServer(id: number) {
  return request<McpServerVO>(`/mcp/servers/${id}/disconnect`, { method: 'POST' })
}

/** 刷新指定 MCP 服务器的工具列表 */
export function refreshMcpServer(id: number) {
  return request<McpServerVO>(`/mcp/servers/${id}/refresh`, { method: 'POST' })
}
