import type { ApiResponse } from '@/store/user'
import { request } from '@/utils/http-client'

// 用户管理 - 用户列表项
export interface UserItem {
  id: number
  username: string
  nickname: string
  role: string
  email?: string
  phone?: string
  avatar?: string
  status: number
  createTime: string
  updateTime: string
}

// 用户管理 - 分页结果
export interface PageResult<T> {
  records: T[]
  total: number
  page: number
  pageSize: number
  totalPages: number
}

// 用户管理 - 创建用户参数
export interface CreateUserParams {
  username: string
  password: string
  nickname?: string
  role?: string
  email?: string
  phone?: string
}

// 用户管理 - 更新用户参数
export interface UpdateUserParams {
  id: number
  username?: string
  password?: string
  nickname?: string
  role?: string
  email?: string
  phone?: string
  status?: number
}

// 用户管理 - 修改密码参数
export interface ChangePasswordParams {
  oldPassword: string
  newPassword: string
}

// 用户管理 - 更新个人信息参数
export interface UpdateProfileParams {
  nickname?: string
  email?: string
  phone?: string
  avatar?: string
}

// 当前用户信息（含角色）
export interface CurrentUserInfo {
  userId: number
  username: string
  role: string
}

/**
 * 获取用户列表（分页），支持按用户名搜索
 */
export async function getUserList(page: number = 1, pageSize: number = 10, search?: string): Promise<ApiResponse<PageResult<UserItem>>> {
  let url = `/user/list?page=${page}&pageSize=${pageSize}`
  if (search && search.trim()) {
    url += `&search=${encodeURIComponent(search.trim())}`
  }
  return request<PageResult<UserItem>>(url)
}

/**
 * 创建用户
 */
export async function createUser(data: CreateUserParams): Promise<ApiResponse<UserItem>> {
  return request<UserItem>('/user/create', {
    method: 'POST',
    body: JSON.stringify(data)
  })
}

/**
 * 更新用户
 */
export async function updateUser(data: UpdateUserParams): Promise<ApiResponse<void>> {
  return request<void>('/user/update', {
    method: 'PUT',
    body: JSON.stringify(data)
  })
}

/**
 * 删除用户
 */
export async function deleteUser(id: number): Promise<ApiResponse<void>> {
  return request<void>(`/user/delete/${id}`, {
    method: 'DELETE'
  })
}

/**
 * 获取当前登录用户的个人信息
 */
export async function getProfile(): Promise<ApiResponse<UserItem>> {
  return request<UserItem>('/user/profile')
}

/**
 * 更新个人信息
 */
export async function updateProfile(data: UpdateProfileParams): Promise<ApiResponse<void>> {
  return request<void>('/user/profile', {
    method: 'PUT',
    body: JSON.stringify(data)
  })
}

/**
 * 修改密码
 */
export async function changePassword(data: ChangePasswordParams): Promise<ApiResponse<void>> {
  return request<void>('/user/password', {
    method: 'PUT',
    body: JSON.stringify(data)
  })
}

/**
 * 获取当前用户基本信息（含角色）
 */
export async function getCurrentUser(): Promise<ApiResponse<CurrentUserInfo>> {
  return request<CurrentUserInfo>('/user/current')
}
