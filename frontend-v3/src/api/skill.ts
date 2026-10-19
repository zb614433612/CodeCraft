import { request } from '@/utils/http-client'

export interface SkillData {
  id?: number
  name: string
  description: string
  toolNames: string
  instructions: string
  triggerWords?: string
  confidence?: number
  usageCount?: number
  successCount?: number
  failCount?: number
  userId: number
  agentType?: string
  agentConfigId?: number
  createdAt?: string
  updatedAt?: string
}

export async function listSkills(userId: number, agentConfigId?: number) {
  const params = new URLSearchParams({ userId: String(userId) })
  if (agentConfigId) params.set('agentConfigId', String(agentConfigId))
  return request<SkillData[]>(`/skills?${params}`)
}

/**
 * 创建技能
 *
 * <p>后端返回 `ApiResponse<Skill>`，`res.data` 即新建的技能实体；
 * 旧版本误写为 `{ success, data, message }`，此处已对齐后端契约。</p>
 */
export async function createSkill(data: Partial<SkillData>) {
  return request<SkillData>('/skills', {
    method: 'POST',
    body: JSON.stringify(data)
  })
}

/** 更新技能（返回更新后的技能实体） */
export async function updateSkill(id: number, data: Partial<SkillData>) {
  return request<SkillData>(`/skills/${id}`, {
    method: 'PUT',
    body: JSON.stringify(data)
  })
}

/** 删除技能（需同时提供 userId，后端校验归属） */
export async function deleteSkillApi(id: number, userId: number) {
  return request<null>(`/skills/${id}?userId=${userId}`, { method: 'DELETE' })
}
