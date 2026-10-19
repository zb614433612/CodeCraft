import { request } from '@/utils/http-client'

/**
 * 子Agent执行记录（后端 `GET /api/sub-agents` 的返回项）。
 *
 * <p>数据源是 `sub_agent_log` 表，不是 SSE 事件 —— 后端 `AgentForkManager`（fork 子Agent）
 * 从不发 `agent_event`，它的产出是以纯文本写进主Agent思考流的。因此子Agent的可视化
 * 只能读库：fork 时 INSERT（status=running），结束时 UPDATE（completed/failed/timeout）。</p>
 */
export interface SubAgentRecord {
  id?: number
  /** 主Agent fork 时指定的唯一标识，如 sub-dal-1 */
  agentId: string
  name?: string
  /** running / completed / failed / timeout */
  status?: string
  /** 完整任务描述 */
  instructions?: string
  /** inherit_summary / inherit_full / none */
  contextMode?: string
  tools?: string[]
  skills?: string[]
  /** 结构化执行摘要（完成时写入） */
  summary?: string
  /** passed / failed / not_run */
  compileResult?: string
  iterationsUsed?: number
  maxIterations?: number
  errorMessage?: string
  /** 创建该子Agent那一轮用户消息的 turnId */
  parentTurnId?: string
  createdFiles?: string[]
  modifiedFiles?: string[]
  deletedFiles?: string[]
  /** yyyy-MM-dd HH:mm:ss（服务端已格式化） */
  createdAt?: string
  completedAt?: string
}

/**
 * 查询某个会话下创建过的全部子Agent（按创建时间升序）。
 *
 * @param conversationId 主Agent会话 ID
 * @param turnId         可选：只取某一轮创建的子Agent
 */
export async function listSubAgents(conversationId: number, turnId?: string) {
  const query = new URLSearchParams({ conversationId: String(conversationId) })
  if (turnId) query.set('turnId', turnId)
  return request<SubAgentRecord[]>(`/sub-agents?${query.toString()}`)
}
