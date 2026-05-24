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

export async function createSkill(data: Partial<SkillData>) {
  return request<{ success: boolean; data: SkillData; message: string }>('/skills', {
    method: 'POST',
    body: JSON.stringify(data)
  })
}

export async function updateSkill(id: number, data: Partial<SkillData>) {
  return request<{ success: boolean; data: SkillData; message: string }>(`/skills/${id}`, {
    method: 'PUT',
    body: JSON.stringify(data)
  })
}

export async function deleteSkillApi(id: number, userId: number) {
  return request<{ success: boolean; message: string }>(`/skills/${id}?userId=${userId}`, { method: 'DELETE' })
}
