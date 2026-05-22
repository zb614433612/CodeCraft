import { request } from '@/utils/http-client'

export interface ScheduleTaskItem {
  id: number
  name: string
  agentType: string
  instruction: string
  cronExpression?: string
  executeTime?: string
  status: string
  lastExecuteTime?: string
  lastConversationId?: number
  executeCount: number
  maxExecuteCount: number
  userId: number
}

export interface CreateTaskParams {
  name: string
  agentType: string
  instruction: string
  cronExpression?: string
  executeTime?: string
  maxExecuteCount?: number
}

// 获取定时任务列表
export async function getTaskList() {
  return request<ScheduleTaskItem[]>('/schedule-task/list')
}

// 创建定时任务
export async function createTask(data: CreateTaskParams) {
  return request<ScheduleTaskItem>('/schedule-task/create', {
    method: 'POST',
    body: JSON.stringify(data)
  })
}

// 更新定时任务
export async function updateTask(data: Partial<ScheduleTaskItem>) {
  return request<void>('/schedule-task/update', {
    method: 'PUT',
    body: JSON.stringify(data)
  })
}

// 删除定时任务
export async function deleteTask(id: number) {
  return request<void>(`/schedule-task/delete/${id}`, { method: 'DELETE' })
}

// 启用任务
export async function enableTask(id: number) {
  return request<void>(`/schedule-task/enable/${id}`, { method: 'POST' })
}

// 禁用任务
export async function disableTask(id: number) {
  return request<void>(`/schedule-task/disable/${id}`, { method: 'POST' })
}
