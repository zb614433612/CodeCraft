import { request } from '@/utils/http-client'

/** 踩坑经验实体（与后端 Lesson 对应） */
export interface LessonData {
  id?: number
  projectKey?: string
  toolName?: string
  errorCategory?: string
  errorCode?: string
  errorSignature?: string
  symptom?: string
  rootCause?: string
  solution?: string
  paramsJson?: string
  applicableCond?: string
  keywords?: string
  status?: number // 0=草稿 1=有效 2=隐藏
  source?: string // auto/llm/manual
  hitCount?: number
  successCount?: number
  failCount?: number
  type?: string // FAILURE=失败经验 / DETOUR=弯路经验（P1）
  goal?: string // DETOUR 专用：任务目标
  createdAt?: string
  updatedAt?: string
}

/** 分页结果 */
export interface LessonPageResult {
  items: LessonData[]
  total: number
  page: number
  size: number
}

/** 统计看板 */
export interface LessonStats {
  projectKey: string
  total: number
  draftCount: number
  activeCount: number
  hiddenCount: number
  totalHits: number
  totalSuccess: number
  totalFail: number
  successRate: number // 百分比数值，如 85.7
  autoCount: number
  llmCount: number
  manualCount: number
  detourCount: number // P1：弯路经验数
  recent7d: number
  // P0 归一化管线指标
  ruleMatchCount?: number
  normLlmCallCount?: number
  normCacheHitCount?: number
  normBackfillCount?: number
}

/** 分页查询 */
export async function pageLessons(params: {
  projectKey?: string
  status?: number
  toolName?: string
  errorCode?: string
  type?: string // P1：FAILURE / DETOUR
  page?: number
  size?: number
}) {
  const query = new URLSearchParams()
  if (params.projectKey) query.set('projectKey', params.projectKey)
  if (params.status !== undefined && params.status !== null && params.status !== '') query.set('status', String(params.status))
  if (params.toolName) query.set('toolName', params.toolName)
  if (params.errorCode) query.set('errorCode', params.errorCode)
  if (params.type) query.set('type', params.type)
  query.set('page', String(params.page ?? 1))
  query.set('size', String(params.size ?? 10))
  return request<LessonPageResult>(`/lessons?${query}`)
}

/** 查询单条详情 */
export async function getLesson(id: number) {
  return request<LessonData>(`/lessons/${id}`)
}

/** 统计看板 */
export async function getLessonStats(projectKey?: string) {
  const query = projectKey ? `?projectKey=${encodeURIComponent(projectKey)}` : ''
  return request<LessonStats>(`/lessons/stats${query}`)
}

/** 编辑/补全经验（只更新非空字段） */
export async function updateLesson(id: number, data: Partial<LessonData>) {
  return request<string>(`/lessons/${id}`, {
    method: 'PUT',
    body: JSON.stringify(data)
  })
}

/** 反馈验证结果 */
export async function feedbackLesson(id: number, effective: boolean) {
  return request<string>(`/lessons/${id}/feedback?effective=${effective}`, { method: 'POST' })
}

/** 删除经验 */
export async function deleteLesson(id: number) {
  return request<void>(`/lessons/${id}`, { method: 'DELETE' })
}
