import { request, authFetch } from '@/utils/http-client'
import type { ApiResponse } from '@/store/user'

// 重置所有数据（清空会话、消息、技能、定时任务、用户、LLM Provider 等全部业务数据并恢复出厂基础数据）
export async function resetAllData() {
  return request<void>('/system/reset-data', {
    method: 'POST'
  })
}

// ==================== 数据导出 / 导入（备份与恢复） ====================

/** 读取各类别当前数据量（行数），用于导出界面展示 */
export async function fetchDataStats(): Promise<Record<string, number>> {
  const res = await request<Record<string, number>>('/system/data-stats')
  return res.data
}

/** 导出备份结果：blob 内容 + 服务端建议文件名 */
export interface ExportResult {
  blob: Blob
  filename: string
}

/**
 * 导出系统数据备份（返回 Blob，由调用方触发下载）
 * @param types 要导出的类别 key 列表（llmProvider/agent/skill/chat/lesson/mcp/dbConnection）
 */
export async function exportSystemData(types: string[]): Promise<ExportResult> {
  const query = types.length ? `?types=${encodeURIComponent(types.join(','))}` : ''
  const res = await authFetch(`/api/system/export-data${query}`)
  if (!res.ok) {
    let msg = `导出失败（HTTP ${res.status}）`
    try {
      const data = await res.json()
      msg = data.message || msg
    } catch {
      /* 非 JSON 错误响应，使用默认提示 */
    }
    throw new Error(msg)
  }
  const blob = await res.blob()
  const disposition = res.headers.get('Content-Disposition') || ''
  const match = disposition.match(/filename="?([^";]+)"?/)
  return {
    blob,
    filename: match?.[1] || `codecraft-backup-${Date.now()}.json`
  }
}

/** 导入结果明细：单表统计 */
export interface DataImportTableDetail {
  table: string
  inserted: number
  updated: number
  failed: number
  errors?: string[]
}

/** 导入结果明细：单类别统计 */
export interface DataImportCategoryDetail {
  category: string
  label: string
  tables: DataImportTableDetail[]
}

/** 导入结果汇总 */
export interface DataImportResult {
  inserted: number
  updated: number
  failed: number
  details: DataImportCategoryDetail[]
}

/**
 * 导入备份文件（multipart 上传，一键合并导入）
 * @param file 备份 JSON 文件
 */
export async function importSystemData(file: File): Promise<ApiResponse<DataImportResult>> {
  const form = new FormData()
  form.append('file', file)
  return request<DataImportResult>(
    '/system/import-data',
    { method: 'POST', body: form },
    300000 // 大数据量导入可能较慢：5 分钟超时
  )
}
