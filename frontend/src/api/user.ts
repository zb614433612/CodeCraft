import type { ApiResponse, RandomCodeData, UserInfo, LoginParams, RandomCodeParams } from '@/store/user'
import { request, getAuthHeaders } from '@/utils/http-client'

// 向后兼容：重新导出 http-client 的 request 和 getAuthHeaders
export { request, getAuthHeaders }

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
