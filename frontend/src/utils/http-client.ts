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

/**
 * 401 未授权处理：清除用户登录状态并跳转到登录页面
 * 提取为公共函数，供 request 和 authFetch 共用
 */
export async function handleUnauthorized(): Promise<void> {
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

/**
 * 判断 body 是否为 FormData（FormData 需要浏览器自动设置 Content-Type）
 */
function isFormData(body: any): boolean {
  return typeof FormData !== 'undefined' && body instanceof FormData
}

/**
 * 带认证和 401 自动跳转的 fetch 包装函数
 * 自动注入 token，遇到 HTTP 401 自动清除登录状态并跳转到登录页
 * 与原生 fetch 接口兼容，返回 Response 对象
 */
export async function authFetch(url: string, options: RequestInit = {}): Promise<Response> {
  const authHeader = await getAuthHeaders()

  const headers: Record<string, string> = { ...authHeader }
  // 非 FormData 请求才设置默认 Content-Type
  if (!isFormData(options.body)) {
    headers['Content-Type'] = 'application/json'
  }
  // 用户自定义 headers 可覆盖默认值
  Object.assign(headers, options.headers || {})

  const response = await fetch(url, {
    ...options,
    headers
  })

  if (response.status === 401) {
    await handleUnauthorized()
  }

  return response
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

  const defaultHeaders: Record<string, string> = { ...authHeader }
  // 非 FormData 请求才设置默认 Content-Type
  if (!isFormData(options.body)) {
    defaultHeaders['Content-Type'] = 'application/json'
  }
  // 用户自定义 headers 可覆盖默认值
  if (options.headers) {
    Object.assign(defaultHeaders, options.headers)
  }

  const defaultOptions: RequestInit = {
    headers: defaultHeaders,
    signal: controller.signal
  }

  try {
    // 显式合并 headers 和 options，避免 options 覆盖导致 token 丢失
    const mergedHeaders: Record<string, string> = { ...defaultHeaders }
    if (options.headers) {
      Object.assign(mergedHeaders, options.headers)
    }
    const response = await fetch(`/api${url}`, {
      method: options.method || defaultOptions.method,
      headers: mergedHeaders,
      body: options.body,
      signal: options.signal || defaultOptions.signal
    })

    clearTimeout(timeoutId)

    if (!response.ok) {
      const errorText = await response.text()
      console.error(`HTTP error! status: ${response.status}, response: ${errorText}`)

      // 处理401未授权错误：清除用户token并跳转到登录页面
      if (response.status === 401) {
        await handleUnauthorized()
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

    // 处理业务层面的401：后端可能返回 HTTP 200 但 code=401（如某些 Controller 内部校验 token）
    if (data.code === 401) {
      await handleUnauthorized()
      throw new Error(data.message || '登录已过期，请重新登录')
    }

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
