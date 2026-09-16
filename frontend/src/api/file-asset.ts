import { request, getAuthHeaders } from '@/utils/http-client'
import type { ApiResponse } from '@/store/user'

// ===== 类型 =====

/** 文件资产（对应后端 FileAssetVO） */
export interface FileAssetData {
  id: number
  /** 远端 Files API 文件ID（file-api-...；本地存储文档为 null） */
  fileId?: string | null
  /** 原始文件名 */
  filename: string
  /** 显示名（重命名后；空时显示 filename） */
  displayName?: string
  mimeType?: string
  /** 存储类型：cloud=云端 Files API（图片）/ local=本地存储（文档） */
  storageType?: string
  size: number
  source: string
  status: string
  providerCode?: string | null
  createdAt?: string
  previewUrl?: string
  references?: FileReferenceData[]
}

/** 文件引用（含会话/消息ID，供历史回显） */
export interface FileReferenceData {
  id: number
  fileAssetId: number
  conversationId: number
  messageId?: number
  createdAt?: string
}

/** 列表查询参数 */
export interface ListFileAssetsParams {
  after?: number
  limit?: number
  order?: 'asc' | 'desc'
  keyword?: string
  status?: string
  /** 存储类型筛选：cloud=云端（图片）/ local=本地（文档） */
  storageType?: string
  conversationId?: number
  withRefs?: boolean
}

// ===== CRUD =====

/**
 * 上传文件资产（multipart）
 * 图片（JPEG/PNG/GIF/WebP）→ 云端 Files API；文档（pdf/word/excel/文本/代码）→ 本地存储（后端按内容自动分流）。
 * 用原生 fetch（不走 request 的 15s 超时——大文件上传可能较慢）
 *
 * @param file 文件（≤64MB）
 * @param options providerCode（缺省用首个 Provider；仅图片使用）；source（upload/paste）
 */
export async function uploadFileAsset(
  file: File,
  options: { providerCode?: string; source?: string } = {}
): Promise<ApiResponse<FileAssetData>> {
  const formData = new FormData()
  formData.append('file', file)
  if (options.providerCode) formData.append('providerCode', options.providerCode)
  if (options.source) formData.append('source', options.source)

  const authHeader = await getAuthHeaders()
  const response = await fetch('/api/files', {
    method: 'POST',
    headers: authHeader,
    body: formData
  })
  return response.json()
}

/** 列出文件资产（跨会话、含未引用；withRefs=true 附带引用信息） */
export async function listFileAssets(params: ListFileAssetsParams = {}): Promise<ApiResponse<FileAssetData[]>> {
  const sp = new URLSearchParams()
  if (params.after != null) sp.set('after', String(params.after))
  if (params.limit != null) sp.set('limit', String(params.limit))
  if (params.order) sp.set('order', params.order)
  if (params.keyword) sp.set('keyword', params.keyword)
  if (params.status) sp.set('status', params.status)
  if (params.storageType) sp.set('storageType', params.storageType)
  if (params.conversationId != null) sp.set('conversationId', String(params.conversationId))
  if (params.withRefs) sp.set('withRefs', 'true')
  const qs = sp.toString()
  return request<FileAssetData[]>(`/files${qs ? '?' + qs : ''}`)
}

/** 查询单个文件资产（含引用信息） */
export async function getFileAsset(id: number): Promise<ApiResponse<FileAssetData>> {
  return request<FileAssetData>(`/files/${id}`)
}

/** 重命名（更新本地显示名） */
export async function renameFileAsset(id: number, displayName: string): Promise<ApiResponse<FileAssetData>> {
  return request<FileAssetData>(`/files/${id}`, {
    method: 'PATCH',
    body: JSON.stringify({ displayName })
  })
}

/** 删除文件资产（远端 + 引用 + 记录 + 本地副本） */
export async function deleteFileAsset(id: number): Promise<ApiResponse<void>> {
  return request<void>(`/files/${id}`, { method: 'DELETE' })
}

/** Files API 能力查询（当前/指定 Provider 是否支持） */
export async function getFilesSupported(providerCode?: string): Promise<ApiResponse<{ supported: boolean; providerCode: string }>> {
  const qs = providerCode ? `?providerCode=${encodeURIComponent(providerCode)}` : ''
  return request<{ supported: boolean; providerCode: string }>(`/files/supported${qs}`)
}

// ===== 预览（blob 工具：<img> 无法携带鉴权头，需 fetch 后转 objectURL） =====

const previewUrlCache = new Map<number, string>()

/**
 * 获取文件预览的 blob URL（带缓存）
 * 注意：调用方无需 revoke，由 invalidatePreviewCache 统一管理（组件卸载/删除文件时清理）
 */
export async function getFilePreviewBlobUrl(id: number): Promise<string> {
  const cached = previewUrlCache.get(id)
  if (cached) return cached
  const authHeader = await getAuthHeaders()
  const res = await fetch(`/api/files/${id}/preview`, { headers: authHeader })
  if (!res.ok) {
    throw new Error(res.status === 404 ? '本地副本不存在' : `预览加载失败（${res.status}）`)
  }
  const blob = await res.blob()
  const url = URL.createObjectURL(blob)
  previewUrlCache.set(id, url)
  return url
}

/** 清理预览缓存（单文件删除时传 id；全量清理时不传） */
export function invalidatePreviewCache(id?: number): void {
  if (id === undefined) {
    previewUrlCache.forEach(url => URL.revokeObjectURL(url))
    previewUrlCache.clear()
    return
  }
  const url = previewUrlCache.get(id)
  if (url) {
    URL.revokeObjectURL(url)
    previewUrlCache.delete(id)
  }
}

// ===== 辅助 =====

/** 显示名：displayName > filename > #id */
export function fileAssetDisplayName(a: Pick<FileAssetData, 'id' | 'filename' | 'displayName'>): string {
  return a.displayName || a.filename || `#${a.id}`
}

/** 字节数格式化（B/KB/MB） */
export function formatAssetSize(bytes?: number): string {
  if (bytes == null || bytes <= 0) return '0 B'
  if (bytes < 1024) return `${bytes} B`
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`
  return `${(bytes / 1024 / 1024).toFixed(2)} MB`
}

/** 是否为支持的图片类型（与后端魔数支持范围一致） */
export function isImageMime(mime?: string): boolean {
  return !!mime && ['image/jpeg', 'image/png', 'image/gif', 'image/webp'].includes(mime)
}

/**
 * 文档分类（用于图标显示）：pdf / word / excel / text
 * 优先按文件扩展名判断，缺扩展名时回退 MIME
 */
export function docTypeOf(filename?: string, mime?: string): 'pdf' | 'word' | 'excel' | 'text' {
  const name = (filename || '').toLowerCase()
  const ext = name.includes('.') ? name.split('.').pop() || '' : ''
  if (ext === 'pdf') return 'pdf'
  if (ext === 'doc' || ext === 'docx') return 'word'
  if (ext === 'xls' || ext === 'xlsx' || ext === 'csv') return 'excel'
  if (ext) return 'text'
  if (mime === 'application/pdf') return 'pdf'
  if (mime && (mime.includes('word') || mime.includes('msword'))) return 'word'
  if (mime && mime.includes('excel')) return 'excel'
  return 'text'
}

/** 文档分类 → 图标（与聊天页附件区 getAttachmentIcon 一致的 emoji 体系） */
export function docTypeIcon(type: 'pdf' | 'word' | 'excel' | 'text'): string {
  const icons: Record<string, string> = { pdf: '📕', word: '📘', excel: '📊', text: '📄' }
  return icons[type] || '📎'
}

/**
 * 下载文件资产（fetch blob → a[download]；图片/文档通用）
 * 用原生 fetch 携带鉴权头（<a href> 无法带 Authorization）
 */
export async function downloadFileAsset(id: number, filename?: string): Promise<void> {
  const authHeader = await getAuthHeaders()
  const res = await fetch(`/api/files/${id}/preview`, { headers: authHeader })
  if (!res.ok) {
    throw new Error(res.status === 404 ? '文件不存在或已被清理' : `下载失败（${res.status}）`)
  }
  const blob = await res.blob()
  const url = URL.createObjectURL(blob)
  try {
    const a = document.createElement('a')
    a.href = url
    a.download = filename || `file-${id}`
    document.body.appendChild(a)
    a.click()
    a.remove()
  } finally {
    // 延迟释放，确保浏览器已开始下载
    setTimeout(() => URL.revokeObjectURL(url), 1000)
  }
}
