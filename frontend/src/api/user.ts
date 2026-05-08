import type { ApiResponse, RandomCodeData, UserInfo, LoginParams, RandomCodeParams } from '@/store/user'

// 请求超时时间（毫秒）
const REQUEST_TIMEOUT = 15000

// 通用请求函数
async function request<T>(url: string, options: RequestInit = {}): Promise<ApiResponse<T>> {
  const controller = new AbortController()
  const timeoutId = setTimeout(() => controller.abort(), REQUEST_TIMEOUT)

  // 获取用户token
  let authHeader = {}
  try {
    // 动态导入pinia和store（避免循环依赖）
    const { useUserStore } = await import('@/store/user')
    // 注意：useUserStore需要pinia实例，这里假设pinia已经在应用中注册
    const userStore = useUserStore()
    if (import.meta.env.DEV) {
      console.log('当前用户token:', userStore.token) // 仅开发环境调试日志
    }
    if (userStore.token) {
      authHeader = {
        'Authorization': `Bearer ${userStore.token}`
      }
      if (import.meta.env.DEV) {
        console.log('已添加Authorization头:', authHeader) // 仅开发环境调试日志
      }
    } else if (import.meta.env.DEV) {
      console.log('用户token为空，不添加Authorization头') // 仅开发环境调试日志
    }
  } catch (error) {
    console.warn('获取用户token失败:', error)
  }

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
        // 清除用户store中的token和登录状态
        try {
          const { useUserStore } = await import('@/store/user')
          const userStore = useUserStore()
          userStore.clearUserInfo() // 清除用户信息，但保留记住的用户名
          console.log('检测到401未授权，已清除用户登录状态')
        } catch (error) {
          console.warn('清除用户store失败:', error)
        }

        // 跳转到登录页面
        try {
          const router = await import('@/router').then(module => module.default)
          if (router) {
            // 避免重复跳转（如果当前已经在登录页面）
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
        // 如果无法解析为JSON，使用原始文本
        if (errorText) {
          errorMessage = errorText
        }
      }

      throw new Error(errorMessage)
    }

    const data: ApiResponse<T> = await response.json()

    // 可以根据业务需求检查 code 是否为成功状态
    if (data.code !== 200) {
      throw new Error(data.message || '请求失败')
    }

    return data
  } catch (error: any) {
    clearTimeout(timeoutId)

    // 处理特定类型的错误
    if (error.name === 'AbortError') {
      throw new Error('请求超时，请检查网络连接或稍后重试')
    }

    // 处理网络错误
    if (error.message.includes('Failed to fetch') || error.message.includes('NetworkError')) {
      throw new Error('网络连接失败，请检查网络连接')
    }

    // 传递其他错误
    throw error
  }
}

// 获取随机码
export async function getRandomCode(username: string): Promise<ApiResponse<RandomCodeData>> {
  const params: RandomCodeParams = { username }
  return request<RandomCodeData>('/user/random-code', {
    method: 'POST',
    body: JSON.stringify(params)
  })
}

// 用户登录
export async function login(username: string, password: string): Promise<ApiResponse<UserInfo>> {
  const params: LoginParams = { username, password }
  return request<UserInfo>('/user/login', {
    method: 'POST',
    body: JSON.stringify(params)
  })
}

// 用户注销
export async function logout(): Promise<ApiResponse<void>> {
  return request<void>('/user/logout', {
    method: 'POST'
  })
}

// 导出 request 函数，以便其他模块使用
export { request }