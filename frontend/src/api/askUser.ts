import type { ApiResponse } from '@/store/user'
import { authFetch } from '@/utils/http-client'

/**
 * 提交 ask_user 问题的答案
 * @param uuid 问题UUID
 * @param answer 回答内容（当 action 为 custom 时为用户自定义消息）
 * @param action 动作类型：approve（同意）/ approve_all（本轮全部同意）/ reject（拒绝）/ custom（其他）
 */
export async function submitAnswer(uuid: string, answer: string, action: string = 'approve'): Promise<ApiResponse<{ success: boolean }>> {
  const response = await authFetch('/api/deepseek/answer', {
    method: 'POST',
    body: JSON.stringify({ uuid, answer, action })
  })

  return response.json()
}
