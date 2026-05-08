import type { ApiResponse } from '@/store/user'
import { request } from './user'

// 股票记录
export interface StockRecord {
  id: number
  tsCode: string
  symbol: string
  market: string
  name: string
  listDate: string
  totalShare: number
  status: number
  createdAt: string
  updatedAt: string
}

// 分页数据
export interface StockPageData {
  records: StockRecord[]
  total: number
  page: number
  pageSize: number
  totalPages: number
}

// 请求参数
export interface StockInfoParams {
  keyword?: string
  market?: string
  status?: number
  pageSize?: number
  page?: number
}

/**
 * 查询股票信息
 */
export async function getStockInfo(params: StockInfoParams): Promise<ApiResponse<StockPageData>> {
  const query = new URLSearchParams()
  if (params.keyword) query.set('keyword', params.keyword)
  if (params.market) query.set('market', params.market)
  if (params.status !== undefined) query.set('status', String(params.status))
  if (params.pageSize !== undefined) query.set('pageSize', String(params.pageSize))
  if (params.page !== undefined) query.set('page', String(params.page))

  const qs = query.toString()
  return request<StockPageData>(`/stock-info${qs ? `?${qs}` : ''}`, {
    method: 'GET'
  })
}

// 日K线记录
export interface KlineDailyRecord {
  id: number
  tsCode: string
  tradeDate: string
  open: number
  high: number
  low: number
  close: number
  preClose: number
  change: number
  pctChange: number
  vol: number
  amount: number
  turnover: number
  upPoint?: boolean
  createdAt: string
}

/**
 * 获取股票日K线数据
 */
export async function getKlineDaily(tsCode: string, adjust: string = 'forward'): Promise<ApiResponse<KlineDailyRecord[]>> {
  const qs = adjust ? `?adjust=${encodeURIComponent(adjust)}` : ''
  return request<KlineDailyRecord[]>(`/kline-daily/${encodeURIComponent(tsCode)}${qs}`, {
    method: 'GET'
  })
}

// 实时行情记录
export interface RealtimeRecord {
  tsCode: string
  name: string
  price: number
  open: number
  high: number
  low: number
  preClose: number
  change_: number
  pctChange: number
  volume: number
  amount: number
  turnoverRate: number
  pe: number
  pb: number
  totalMv: number
  circMv: number
  limitUp: number
  limitDown: number
  amplitude: number
  avgPrice: number
  volumeRatio: number
  peTtm: number
  eps: number
  bvps: number
  updateTime: string
  // 买卖五档（JSON 数组字符串，如 "[11.48,11.47,11.46,11.45,11.44]"）
  bidPrices?: string
  bidVolumes?: string
  askPrices?: string
  askVolumes?: string
}

/**
 * 获取实时行情
 */
export async function getRealtime(tsCodes: string[]): Promise<ApiResponse<RealtimeRecord[]>> {
  return request<RealtimeRecord[]>('/realtime', {
    method: 'POST',
    body: JSON.stringify(tsCodes)
  })
}

// 分钟K线记录
export interface KlineMinRecord {
  id: number
  tsCode: string
  tradeDate: string
  minute: string
  freq: number
  open: number
  high: number
  low: number
  close: number
  vol: number
  amount: number
  createdAt: string
}

/**
 * 获取分钟K线数据
 */
export async function getKlineMin(tsCode: string, date: string): Promise<ApiResponse<KlineMinRecord[]>> {
  const qs = date ? `?date=${encodeURIComponent(date)}` : ''
  return request<KlineMinRecord[]>(`/kline-min/${encodeURIComponent(tsCode)}${qs}`, {
    method: 'GET'
  })
}

// 分时成交记录
export interface TickRecord {
  time: string
  price: number
  volume: number
  direction: number
  type: string
}

/**
 * 获取分时成交明细
 */
export async function getTick(tsCode: string): Promise<ApiResponse<TickRecord[]>> {
  return request<TickRecord[]>(`/tick/${encodeURIComponent(tsCode)}`, {
    method: 'GET'
  })
}
