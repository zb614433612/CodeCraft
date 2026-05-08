export interface Skill {
  id: number
  name: string
  description: string
  toolNames: string
  instructions: string
  confidence: number
  usageCount: number
  successCount: number
  failCount: number
  userId: number
  agentType: string
  createdAt: string
  updatedAt: string
}

export interface DeleteResult {
  success: boolean
  message?: string
}

/**
 * 获取用户技能列表
 */
export async function listSkills(userId: number): Promise<Skill[]> {
  const params = new URLSearchParams({ userId: String(userId) })
  const res = await fetch(`/api/skills?${params}`)
  return res.json()
}

/**
 * 删除技能
 */
export async function deleteSkill(id: number, userId: number): Promise<DeleteResult> {
  const params = new URLSearchParams({ userId: String(userId) })
  const res = await fetch(`/api/skills/${id}?${params}`, { method: 'DELETE' })
  return res.json()
}
