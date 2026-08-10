import { request } from '@/utils/http-client'

// 重置所有数据（清空会话、消息、技能、定时任务、用户、LLM Provider 等全部业务数据并恢复出厂基础数据）
export async function resetAllData() {
  return request<void>('/system/reset-data', {
    method: 'POST'
  })
}
