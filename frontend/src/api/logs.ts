import { request } from '@/utils/http-client'

/** 日志行 */
export interface LogLine {
  date: string
  fileName: string
  lineNumber: number
  content: string
}

/** 搜索结果 */
export interface LogSearchResult {
  date: string
  lines: LogLine[]
  total: number
  page: number
  size: number
}

/** 搜索历史日志 */
export async function searchLogs(params: {
  keyword?: string
  level?: string
  date?: string
  page?: number
  size?: number
}): Promise<LogSearchResult> {
  const query = new URLSearchParams()
  if (params.keyword) query.set('keyword', params.keyword)
  if (params.level) query.set('level', params.level)
  if (params.date) query.set('date', params.date)
  if (params.page) query.set('page', String(params.page))
  if (params.size) query.set('size', String(params.size))
  const qs = query.toString()
  const url = `/logs/search${qs ? '?' + qs : ''}`

  const res = await request<any>(url, { method: 'GET' })
  if (res.code === 200) {
    return res.data as LogSearchResult
  }
  throw new Error(res.message || '搜索日志失败')
}

/** 获取有日志的日期列表 */
export async function getLogDates(): Promise<string[]> {
  const res = await request<any>('/logs/dates', { method: 'GET' })
  if (res.code === 200) {
    return res.data || []
  }
  return []
}

/** 导出结果 */
export interface LogExportResult {
  date: string
  fileNames: string[]
  content: string
  lineCount: number
  charCount: number
}

/** 导出指定日期的完整日志（date 为空时导出最新日期） */
export async function exportLogFile(date?: string): Promise<LogExportResult> {
  const url = date ? `/logs/download?date=${encodeURIComponent(date)}` : '/logs/download'
  const res = await request<any>(url, { method: 'GET' })
  if (res.code === 200) {
    return res.data as LogExportResult
  }
  throw new Error(res.message || '导出日志失败')
}
