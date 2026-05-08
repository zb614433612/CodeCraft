import type { ApiResponse } from '@/store/user'
import { request } from './user'

// 自选股分组
export interface WatchlistGroup {
  id: number
  name: string
  stocks: WatchlistStock[]
  createdAt: string
  updatedAt: string
}

// 分组内股票
export interface WatchlistStock {
  id: number
  tsCode: string
  symbol: string
  stockName: string
  market: string
}

/**
 * 新增分组
 */
export async function createWatchlistGroup(name: string): Promise<ApiResponse<WatchlistGroup>> {
  return request<WatchlistGroup>('/watchlist/group', {
    method: 'POST',
    body: JSON.stringify({ name })
  })
}

/**
 * 编辑分组名
 */
export async function updateWatchlistGroup(id: number, name: string): Promise<ApiResponse<WatchlistGroup>> {
  return request<WatchlistGroup>(`/watchlist/group/${id}`, {
    method: 'PUT',
    body: JSON.stringify({ name })
  })
}

/**
 * 删除分组（级联删股票）
 */
export async function deleteWatchlistGroup(id: number): Promise<ApiResponse<null>> {
  return request<null>(`/watchlist/group/${id}`, {
    method: 'DELETE'
  })
}

/**
 * 查询全部分组及股票
 */
export async function getWatchlistGroups(): Promise<ApiResponse<WatchlistGroup[]>> {
  return request<WatchlistGroup[]>('/watchlist/groups', {
    method: 'GET'
  })
}

/**
 * 批量加自选（自动填股票名，重复自动跳过）
 */
export async function addWatchlistStocks(groupId: number, tsCodes: string[]): Promise<ApiResponse<null>> {
  return request<null>(`/watchlist/group/${groupId}/stocks`, {
    method: 'POST',
    body: JSON.stringify(tsCodes)
  })
}

/**
 * 批量删自选
 */
export async function removeWatchlistStocks(groupId: number, tsCodes: string[]): Promise<ApiResponse<null>> {
  return request<null>(`/watchlist/group/${groupId}/stocks`, {
    method: 'DELETE',
    body: JSON.stringify(tsCodes)
  })
}
