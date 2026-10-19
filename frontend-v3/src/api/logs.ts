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

/**
 * 读取最新日志（tail）
 *
 * <p>后端 {@code GET /api/logs/tail} 只读「今天」的日志，从文件末尾往前取，
 * 返回纯文本行（从旧到新），不含行号与文件名。</p>
 *
 * @param maxLines 最多读取行数（后端上限 5000，默认 500）
 * @param skip     跳过末尾多少行后再开始读（「加载更早」时传已加载行数）
 */
export async function tailLogs(maxLines = 500, skip = 0): Promise<string[]> {
  const res = await request<any>(`/logs/tail?maxLines=${maxLines}&skip=${skip}`, { method: 'GET' })
  if (res.code === 200) {
    return (res.data as string[]) || []
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
