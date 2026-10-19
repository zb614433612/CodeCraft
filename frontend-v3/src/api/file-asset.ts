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

/** 分页查询参数（2026-09-21 文件管理页分页改造；page 从 1 开始） */
export interface ListFileAssetsPagedParams {
  page?: number
  pageSize?: number
  order?: 'asc' | 'desc'
  keyword?: string
  status?: string
  storageType?: string
  conversationId?: number
  withRefs?: boolean
}

/** 分页查询结果（对应后端 FilePageVO） */
export interface FilePageData {
  records: FileAssetData[]
  /** 筛选条件下的文件总数（全量，非当前页） */
  total: number
  /** 当前页码（1 开始） */
  page: number
  /** 每页条数 */
  pageSize: number
  /** 总页数 */
  totalPages: number
  /** 筛选条件下占用空间合计（字节，全量） */
  totalSize: number
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

/**
 * 分页列出文件资产（2026-09-21 文件管理页分页改造）
 * <p>标准页码分页（page 从 1 开始）+ 总数/占用空间统计；与游标版 listFileAssets 并存互不影响。</p>
 */
export async function listFileAssetsPaged(params: ListFileAssetsPagedParams = {}): Promise<ApiResponse<FilePageData>> {
  const sp = new URLSearchParams()
  if (params.page != null) sp.set('page', String(params.page))
  if (params.pageSize != null) sp.set('pageSize', String(params.pageSize))
  if (params.order) sp.set('order', params.order)
  if (params.keyword) sp.set('keyword', params.keyword)
  if (params.status) sp.set('status', params.status)
  if (params.storageType) sp.set('storageType', params.storageType)
  if (params.conversationId != null) sp.set('conversationId', String(params.conversationId))
  if (params.withRefs) sp.set('withRefs', 'true')
  const qs = sp.toString()
  return request<FilePageData>(`/files/page${qs ? '?' + qs : ''}`)
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

// ===== 云端文件（Files API 账号级视图，2026-09-21） =====

/** 云端文件条目（对应后端 CloudFileVO） */
export interface CloudFileData {
  /** 远端 Files API 文件ID（file-api-...） */
  fileId: string
  /** 文件名 */
  filename: string
  /** 文件大小（字节） */
  bytes?: number | null
  /** 创建时间（Unix 秒） */
  createdAtEpoch?: number | null
  /** 远端过期时间（Unix 秒；null=永久保存） */
  expiresAtEpoch?: number | null
  /** 是否已在本地登记（file_asset 存在同 provider+fileId 记录） */
  linked?: boolean
  /** 本地资产ID（linked=true 时；否则 null） */
  linkedAssetId?: number | null
  /** 本地显示名（linked=true 时；否则 null） */
  linkedDisplayName?: string | null
  /** 本地记录状态（active/orphan；linked=false 时 null） */
  linkedStatus?: string | null
}

/** 云端文件列表结果（对应后端 CloudFileListVO） */
export interface CloudFileListData {
  /** 实际查询的 Provider code */
  providerCode: string
  /** 云端文件总数（本次返回条数；truncated=true 时为拉取上限值） */
  total: number
  /** 已登记数（本地存在记录） */
  linkedCount: number
  /** 仅云端数（本地无记录，可清理） */
  cloudOnlyCount: number
  /** 仅云端文件占用空间合计（字节） */
  cloudOnlySize: number
  /** 是否因单次拉取上限而截断 */
  truncated?: boolean
  /** 云端文件列表（最新在前） */
  files: CloudFileData[]
}

/** 云端文件删除结果（对应后端 CloudFileDeleteResultVO） */
export interface CloudFileDeleteResult {
  /** 被删除的远端文件ID */
  fileId: string
  /** 远端是否已删除（幂等：目标不存在也视为 true） */
  deleted: boolean
  /** 是否联动清理了本地记录 */
  localRecordDeleted: boolean
  /** 联动清理的本地记录数 */
  localRecordCount: number
}

/**
 * 查询云端文件清单（Files API 账号级）
 * 服务端会拉取云端全量清单（可能多页）并与本地比对，耗时可能超过通用 15s 超时——
 * 故用原生 fetch + 60s 兜底（与 uploadFileAsset 同风格）。
 *
 * @param providerCode Provider code（缺省用后端首个 Provider）
 */
export async function listCloudFiles(providerCode?: string): Promise<ApiResponse<CloudFileListData>> {
  const qs = providerCode ? `?providerCode=${encodeURIComponent(providerCode)}` : ''
  const authHeader = await getAuthHeaders()
  const controller = new AbortController()
  const timeoutId = setTimeout(() => controller.abort(), 60000)
  try {
    const response = await fetch(`/api/files/cloud${qs}`, { headers: authHeader, signal: controller.signal })
    return await response.json()
  } catch (e: any) {
    if (e.name === 'AbortError') throw new Error('云端文件查询超时，请稍后重试')
    throw new Error(e.message || '云端文件查询失败')
  } finally {
    clearTimeout(timeoutId)
  }
}

/**
 * 删除云端文件（幂等：目标不存在视为已删除）
 * 本地存在同 provider+fileId 记录时联动清理（引用+记录+本地副本），保证云端不留孤儿。
 */
export async function deleteCloudFile(fileId: string, providerCode?: string): Promise<ApiResponse<CloudFileDeleteResult>> {
  const qs = providerCode ? `?providerCode=${encodeURIComponent(providerCode)}` : ''
  return request<CloudFileDeleteResult>(`/files/cloud/${encodeURIComponent(fileId)}${qs}`, { method: 'DELETE' })
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
