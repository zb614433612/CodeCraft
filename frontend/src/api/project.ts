import type { ApiResponse } from '@/store/user'

export interface ProjectTreeNode {
  name: string
  path: string
  directory: boolean
  children?: ProjectTreeNode[]
}

export interface DirectoryEntry {
  name: string
  path: string
}

async function request<T>(url: string, options: RequestInit = {}): Promise<ApiResponse<T>> {
  let authHeader = {}
  try {
    const { useUserStore } = await import('@/store/user')
    const userStore = useUserStore()
    if (userStore.token) {
      authHeader = { 'Authorization': `Bearer ${userStore.token}` }
    }
  } catch { /* ignore */ }

  const response = await fetch(`/api${url}`, {
    headers: { 'Content-Type': 'application/json', ...authHeader, ...options.headers },
    ...options
  })
  return response.json()
}

export async function getProjectTree(root = '', depth = 4): Promise<ApiResponse<ProjectTreeNode>> {
  const params = new URLSearchParams()
  if (root) params.set('root', root)
  params.set('depth', String(depth))
  return request<ProjectTreeNode>(`/project/tree?${params}`)
}

export async function getDrives(): Promise<ApiResponse<DirectoryEntry[]>> {
  return request<DirectoryEntry[]>('/project/drives')
}

export async function getDirChildren(path: string): Promise<ApiResponse<DirectoryEntry[]>> {
  const params = new URLSearchParams()
  params.set('path', path)
  return request<DirectoryEntry[]>(`/project/children?${params}`)
}
