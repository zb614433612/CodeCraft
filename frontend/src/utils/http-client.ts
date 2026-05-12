import type { ApiResponse } from '@/store/user'

/**
 * 获取认证请求头（从 userStore 读取 token）
 */
export async function getAuthHeaders(): Promise<Record<string, string>> {
  try {
    const { useUserStore } = await import('@/store/user')
    const userStore = useUserStore()
    if (userStore.token) {
      return { 'Authorization': `Bearer ${userStore.token}` }
    }
  } catch (error) {
    console.warn('获取用户token失败:', error)
  }
  return {}
}

// 请求超时时间（毫秒）
const REQUEST_TIMEOUT = 15000

/**
 * 通用请求函数 — 带超时、token注入、401自动跳转登录
 * 自动拼接 /api 前缀
 */
export async function request<T>(url: string, options: RequestInit = {}): Promise<ApiResponse<T>> {
  const controller = new AbortController()
  const timeoutId = setTimeout(() => controller.abort(), REQUEST_TIMEOUT)

  const authHeader = await getAuthHeaders()

  const defaultOptions: RequestInit = {
    headers: {
      'Content-Type': 'application/json',
      ...authHeader,
      ...options.headers
    },
    signal: controller.signal
  }

  try {
    const response = await fetch(`/api${url}`, {
      ...defaultOptions,
      ...options
    })

    clearTimeout(timeoutId)

    if (!response.ok) {
      const errorText = await response.text()
      console.error(`HTTP error! status: ${response.status}, response: ${errorText}`)

      // 处理401未授权错误：清除用户token并跳转到登录页面
      if (response.status === 401) {
        try {
          const { useUserStore } = await import('@/store/user')
          const userStore = useUserStore()
          userStore.clearUserInfo()
          console.log('检测到401未授权，已清除用户登录状态')
        } catch (error) {
          console.warn('清除用户store失败:', error)
        }

        try {
          const router = await import('@/router').then(module => module.default)
          if (router) {
            const currentRoute = router.currentRoute.value
            if (currentRoute.path !== '/login') {
              router.push('/login')
              console.log('已重定向到登录页面')
            }
          }
        } catch (error) {
          console.warn('跳转到登录页面失败:', error)
        }
      }

      // 尝试解析错误消息
      let errorMessage = `请求失败，状态码: ${response.status}`
      try {
        const errorData = JSON.parse(errorText)
        errorMessage = errorData.message || errorMessage
      } catch {
        if (errorText) {
          errorMessage = errorText
        }
      }

      throw new Error(errorMessage)
    }

    const data: ApiResponse<T> = await response.json()

    if (data.code !== 200) {
      throw new Error(data.message || '请求失败')
    }

    return data
  } catch (error: any) {
    clearTimeout(timeoutId)

    if (error.name === 'AbortError') {
      throw new Error('请求超时，请检查网络连接或稍后重试')
    }

    if (error.message.includes('Failed to fetch') || error.message.includes('NetworkError')) {
      throw new Error('网络连接失败，请检查网络连接')
    }

    throw error
  }
}
