import type { ApiResponse } from '@/store/user'
import { request } from './user'

// 后端返回的 Profile 数据类型
export interface ProfileResponse {
  id: number
  username: string
  createdAt: string
}

// 获取 Profile 列表（聊天助手会话列表）
export async function getProfileList(): Promise<ApiResponse<ProfileResponse[]>> {
  return request<ProfileResponse[]>('/user-profile/list', {
    method: 'GET'
  })
}

// 创建新的 Profile（聊天助手会话）
export async function createProfile(username: string): Promise<ApiResponse<ProfileResponse>> {
  return request<ProfileResponse>(`/user-profile/create?username=${encodeURIComponent(username)}`, {
    method: 'POST'
  })
}

// 删除 Profile（聊天助手会话）
export async function deleteProfile(id: number): Promise<ApiResponse<Record<string, unknown>>> {
  return request<Record<string, unknown>>(`/user-profile/${id}`, {
    method: 'DELETE'
  })
}

// 修改聊天历史消息
export async function updateMessage(messageId: number, content: string): Promise<ApiResponse<Record<string, unknown>>> {
  return request<Record<string, unknown>>(`/user-profile/message/${messageId}?content=${encodeURIComponent(content)}`, {
    method: 'PUT'
  })
}
