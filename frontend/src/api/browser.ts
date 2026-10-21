import type { ApiResponse } from '@/store/user'
import { authFetch as authFetchWithRedirect } from '@/utils/http-client'

/**
 * AI 浏览器会话接口（经典版）。
 *
 * <p>与 v3 的 `api/browser.ts` 完全同构：数据由后端实时问 Electron bridge，不落库。
 * 两套 UI 共用同一组后端接口，字段名保持一致，便于对照维护。</p>
 */

/** 一个浏览器标签（视觉上是一个独立窗口，语义上属于当前会话） */
export interface BrowserTab {
  tabId: string
  /** 是否为当前活跃标签（后续 browser 动作默认打在它上面） */
  active: boolean
  visible: boolean
  pageUrl: string
  idleMs: number
}

export interface BrowserSessions {
  /** 通道是否可用（非桌面客户端 / 客户端刚启动时为 false） */
  available: boolean
  conversationId: string
  tabs: BrowserTab[]
  activeTabId: string
  totalWindows: number
  maxTabs: number
  /** 不可用原因（available=false 时给出） */
  reason?: string
}

async function request<T>(url: string, options: RequestInit = {}): Promise<ApiResponse<T>> {
  const response = await authFetchWithRedirect(`/api${url}`, {
    headers: { 'Content-Type': 'application/json', ...options.headers },
    ...options
  })
  return response.json()
}

/** 查询某会话的浏览器标签；不传 conversationId 时返回全部窗口（诊断用） */
export async function getBrowserSessions(conversationId?: string): Promise<ApiResponse<BrowserSessions>> {
  const params = new URLSearchParams()
  if (conversationId) params.set('conversationId', conversationId)
  const qs = params.toString()
  return request<BrowserSessions>(`/browser/sessions${qs ? `?${qs}` : ''}`)
}

/** 显示 / 隐藏 AI 浏览器窗口 */
export async function showBrowserSession(params: {
  conversationId: string
  tabId?: string
  visible?: boolean
}): Promise<ApiResponse<{ ok: boolean; error?: string }>> {
  return request<{ ok: boolean; error?: string }>('/browser/sessions/show', {
    method: 'POST',
    body: JSON.stringify(params)
  })
}

/** 关闭浏览器标签（all=true 关掉本会话全部） */
export async function closeBrowserSession(params: {
  conversationId: string
  tabId?: string
  all?: boolean
}): Promise<ApiResponse<{ ok: boolean; closed?: boolean; closedCount?: number; remain?: number; error?: string }>> {
  return request<{ ok: boolean; closed?: boolean; closedCount?: number; remain?: number; error?: string }>(
    '/browser/sessions/close',
    { method: 'POST', body: JSON.stringify(params) }
  )
}
