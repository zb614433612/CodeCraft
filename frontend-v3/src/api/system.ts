import { request, authFetch } from '@/utils/http-client'
import type { ApiResponse } from '@/store/user'

// ==================== 版本信息（F06） ====================

/**
 * 后端版本与运行环境（`GET /api/system/version`）。
 * <p>与其它系统接口不同：**不做管理员校验**，只返回版本号 / 构建时间 / JVM 环境，不含敏感数据。</p>
 */
export interface AppVersionInfo {
  name: string
  version: string
  /** 构建时间（Maven 资源过滤注入；IDE 直接运行时可能缺省） */
  buildTime?: string
  javaVersion?: string
  osName?: string
  osArch?: string
  /** 后端进程号（排查「是不是连着旧实例」时有用） */
  pid?: number
}

/** 读取后端版本信息 */
export async function fetchAppVersion(): Promise<AppVersionInfo> {
  const res = await request<AppVersionInfo>('/system/version')
  return res.data
}


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
  try {
    return await request<DataImportResult>(
      '/system/import-data',
      { method: 'POST', body: form },
      300000 // 大数据量导入可能较慢：5 分钟超时
    )
  } catch (err) {
    const msg = err instanceof Error ? err.message : String(err)
    /*
     * 上传大文件时，最常见的两种失败长这样：
     *   1) 请求体超过服务器 multipart 上限 → Tomcat 直接重置连接 → 浏览器只报
     *      `TypeError: Failed to fetch`，被 http-client 统一翻成「请确认后端服务已启动」；
     *   2) 后端确实是旧版本（上限 64MB）或进程没起来。
     * 两者对用户来说长得一样，但处理方式完全不同，所以这里补一句「文件多大 + 可能原因」，
     * 避免用户反复重启客户端排查。
     */
    if (/后端服务已启动|网络连接失败|Failed to fetch|NetworkError|请求超时/i.test(msg)) {
      const sizeText = file.size >= 1024 * 1024
        ? `${(file.size / 1024 / 1024).toFixed(1)} MB`
        : `${Math.max(1, Math.round(file.size / 1024))} KB`
      throw new Error(
        `导入请求被中断（文件 ${sizeText}）。可能原因：① 后端尚未重启到支持 500MB 的版本（旧版上限 64MB）；` +
        `② 后端进程异常退出。请确认后端已更新并重启后重试。`
      )
    }
    throw err
  }
}
