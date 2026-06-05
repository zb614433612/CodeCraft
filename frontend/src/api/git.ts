import { authFetch } from '@/utils/http-client'

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

export interface GitShowResult {
  success: boolean
  content?: string
  error?: string
}
/**
 * 获取 Git 仓库状态
 */
export async function getGitStatus(projectRoot: string): Promise<GitStatusResult> {
  const params = new URLSearchParams({ projectRoot })
  const res = await authFetch(`/api/git/status?${params}`)
  return res.json()
}

/**
 * 获取文件 diff
 */
export async function getGitDiff(projectRoot: string, file?: string, staged?: boolean): Promise<GitDiffResult> {
  const params = new URLSearchParams({ projectRoot })
  if (file) params.append('file', file)
  if (staged) params.append('staged', 'true')
  const res = await authFetch(`/api/git/diff?${params}`)
  return res.json()
}

/**
 * 暂存文件
 */
export async function gitAdd(projectRoot: string, files?: string[], all?: boolean): Promise<GitAddResult> {
  const res = await authFetch('/api/git/add', {
    method: 'POST',
    body: JSON.stringify({ projectRoot, files, all })
  })
  return res.json()
}

/**
 * 执行提交
 */
export async function gitCommit(projectRoot: string, message: string): Promise<GitCommitResult> {
  const res = await authFetch('/api/git/commit', {
    method: 'POST',
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
  const res = await authFetch(`/api/git/log?${params}`)
  return res.json()
}

/**
 * 保存 Git Token
 */
export async function saveGitToken(projectRoot: string, token: string): Promise<GitAuthResult> {
  const res = await authFetch('/api/git/auth', {
    method: 'POST',
    body: JSON.stringify({ projectRoot, token })
  })
  return res.json()
}

/**
 * 检查 Git Token 是否存在
 */
export async function checkGitToken(projectRoot: string): Promise<GitAuthResult> {
  const params = new URLSearchParams({ projectRoot })
  const res = await authFetch(`/api/git/auth?${params}`)
  return res.json()
}

/**
 * 清除 Git Token
 */
export async function clearGitToken(projectRoot: string): Promise<GitAuthResult> {
  const params = new URLSearchParams({ projectRoot })
  const res = await authFetch(`/api/git/auth?${params}`, {
    method: 'DELETE'
  })
  return res.json()
}

/**
 * 初始化 Git 仓库
 */
export async function gitRestore(projectRoot: string, file: string): Promise<{ success: boolean; error?: string; output?: string }> {
  const res = await authFetch('/api/git/restore', {
    method: 'POST',
    body: JSON.stringify({ projectRoot, file })
  })
  return res.json()
}

/**
 * 按块还原文件改动（git apply --reverse）
 */
export async function gitRestoreHunks(projectRoot: string, file: string, hunks: string): Promise<{ success: boolean; error?: string; output?: string }> {
  const res = await authFetch('/api/git/restore-hunks', {
    method: 'POST',
    body: JSON.stringify({ projectRoot, file, hunks })
  })
  return res.json()
}

export async function gitInit(projectRoot: string, remoteUrl?: string, token?: string): Promise<{ success: boolean; error?: string }> {
  const res = await authFetch('/api/git/init', {
    method: 'POST',
    body: JSON.stringify({ projectRoot, remoteUrl, token })
  })
  return res.json()
}

/**
 * 获取 HEAD 版本文件内容（git show HEAD:file）
 */
export async function gitShowFile(projectRoot: string, file: string): Promise<GitShowResult> {
  const params = new URLSearchParams({ projectRoot, file })
  const res = await authFetch(`/api/git/show?${params}`)
  return res.json()
}
