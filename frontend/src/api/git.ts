export interface GitStatusResult {
  isRepo: boolean
  branch?: string
  changes?: GitChange[]
  hasToken?: boolean
  remoteUrl?: string
  error?: string
}

export interface GitChange {
  file: string
  status: 'modified' | 'staged' | 'untracked'
  label: string
  stagedStatus?: string
  workingStatus?: string
}

export interface GitDiffResult {
  success: boolean
  diff: string
  error?: string
}

export interface GitLogResult {
  success: boolean
  log?: string
  error?: string
}

export interface GitCommitResult {
  success: boolean
  output?: string
  error?: string
}

export interface GitAddResult {
  success: boolean
  error?: string
}

export interface GitAuthResult {
  hasToken?: boolean
  success?: boolean
  error?: string
}

/**
 * 从 userStore 获取认证请求头
 */
async function getAuthHeaders(): Promise<Record<string, string>> {
  try {
    const { useUserStore } = await import('@/store/user')
    const userStore = useUserStore()
    if (userStore.token) {
      return { 'Authorization': `Bearer ${userStore.token}` }
    }
  } catch (error) {
    console.warn('获取用户token失败:', error)
  }
  return {}
}

/**
 * 获取 Git 仓库状态
 */
export async function getGitStatus(projectRoot: string): Promise<GitStatusResult> {
  const params = new URLSearchParams({ projectRoot })
  const headers = await getAuthHeaders()
  const res = await fetch(`/api/git/status?${params}`, { headers })
  return res.json()
}

/**
 * 获取文件 diff
 */
export async function getGitDiff(projectRoot: string, file?: string, staged?: boolean): Promise<GitDiffResult> {
  const params = new URLSearchParams({ projectRoot })
  if (file) params.append('file', file)
  if (staged) params.append('staged', 'true')
  const headers = await getAuthHeaders()
  const res = await fetch(`/api/git/diff?${params}`, { headers })
  return res.json()
}

/**
 * 暂存文件
 */
export async function gitAdd(projectRoot: string, files?: string[], all?: boolean): Promise<GitAddResult> {
  const headers = await getAuthHeaders()
  const res = await fetch('/api/git/add', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', ...headers },
    body: JSON.stringify({ projectRoot, files, all })
  })
  return res.json()
}

/**
 * 执行提交
 */
export async function gitCommit(projectRoot: string, message: string): Promise<GitCommitResult> {
  const headers = await getAuthHeaders()
  const res = await fetch('/api/git/commit', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', ...headers },
    body: JSON.stringify({ projectRoot, message })
  })
  return res.json()
}

/**
 * 获取提交历史
 */
export async function getGitLog(projectRoot: string, maxCount?: number): Promise<GitLogResult> {
  const params = new URLSearchParams({ projectRoot })
  if (maxCount) params.append('maxCount', String(maxCount))
  const headers = await getAuthHeaders()
  const res = await fetch(`/api/git/log?${params}`, { headers })
  return res.json()
}

/**
 * 保存 Git Token
 */
export async function saveGitToken(projectRoot: string, token: string): Promise<GitAuthResult> {
  const headers = await getAuthHeaders()
  const res = await fetch('/api/git/auth', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', ...headers },
    body: JSON.stringify({ projectRoot, token })
  })
  return res.json()
}

/**
 * 检查 Git Token 是否存在
 */
export async function checkGitToken(projectRoot: string): Promise<GitAuthResult> {
  const params = new URLSearchParams({ projectRoot })
  const headers = await getAuthHeaders()
  const res = await fetch(`/api/git/auth?${params}`, { headers })
  return res.json()
}

/**
 * 清除 Git Token
 */
export async function clearGitToken(projectRoot: string): Promise<GitAuthResult> {
  const params = new URLSearchParams({ projectRoot })
  const headers = await getAuthHeaders()
  const res = await fetch(`/api/git/auth?${params}`, {
    method: 'DELETE',
    headers
  })
  return res.json()
}

/**
 * 初始化 Git 仓库
 */
export async function gitInit(projectRoot: string, remoteUrl?: string, token?: string): Promise<{ success: boolean; error?: string }> {
  const headers = await getAuthHeaders()
  const res = await fetch('/api/git/init', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', ...headers },
    body: JSON.stringify({ projectRoot, remoteUrl, token })
  })
  return res.json()
}
