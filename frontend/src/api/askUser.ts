import type { ApiResponse } from '@/store/user'
import { getAuthHeaders } from '@/utils/http-client'

/**
 * 提交 ask_user 问题的答案
 */
export async function submitAnswer(uuid: string, answer: string): Promise<ApiResponse<{ success: boolean }>> {
  const authHeader = await getAuthHeaders()

  const response = await fetch('/api/deepseek/answer', {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      ...authHeader
    },
    body: JSON.stringify({ uuid, answer })
  })

  return response.json()
}
