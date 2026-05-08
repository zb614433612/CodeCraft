import type { ApiResponse } from '@/store/user'

/**
 * 提交 ask_user 问题的答案
 */
export async function submitAnswer(uuid: string, answer: string): Promise<ApiResponse<{ success: boolean }>> {
  let authHeader: Record<string, string> = {}
  try {
    const { useUserStore } = await import('@/store/user')
    const userStore = useUserStore()
    if (userStore.token) {
      authHeader = { 'Authorization': `Bearer ${userStore.token}` }
    }
  } catch (e) {
    // ignore
  }

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
