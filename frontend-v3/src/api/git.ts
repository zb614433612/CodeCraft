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

/** Git 写操作通用返回（output 为命令输出文本） */
export interface GitOperationResult {
  success: boolean
  output?: string
  error?: string
}

/** 分支列表结果 */
export interface GitBranchesResult {
  success: boolean
  current?: string
  branches?: string[]
  error?: string
}

/** 标签列表结果 */
export interface GitTagsResult {
  success: boolean
  tags?: string[]
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
 * 撤销工作区改动
 * - gitRestore(root, 'a.txt') → 还原单个文件
 * - gitRestore(root, undefined, true) → 还原全部改动
 */
export async function gitRestore(projectRoot: string, file?: string, all?: boolean): Promise<GitOperationResult> {
  const res = await authFetch('/api/git/restore', {
    method: 'POST',
    body: JSON.stringify({ projectRoot, file, all })
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

/* ==================== 远程同步（pull / push / fetch） ==================== */

/**
 * 拉取远程更新（storedAuth）
 */
export async function gitPull(projectRoot: string, remote?: string, branch?: string): Promise<GitOperationResult> {
  const res = await authFetch('/api/git/pull', {
    method: 'POST',
    body: JSON.stringify({ projectRoot, remote, branch })
  })
  return res.json()
}

/**
 * 获取远程更新（不合并，storedAuth）
 */
export async function gitFetch(projectRoot: string, remote?: string, prune?: boolean): Promise<GitOperationResult> {
  const res = await authFetch('/api/git/fetch', {
    method: 'POST',
    body: JSON.stringify({ projectRoot, remote, prune })
  })
  return res.json()
}

/**
 * 推送到远程（storedAuth）
 */
export async function gitPush(
  projectRoot: string,
  remote?: string,
  branch?: string,
  setUpstream?: boolean,
  force?: boolean
): Promise<GitOperationResult> {
  const res = await authFetch('/api/git/push', {
    method: 'POST',
    body: JSON.stringify({ projectRoot, remote, branch, setUpstream, force })
  })
  return res.json()
}

/* ==================== 暂存区撤销 / 工作区还原 ==================== */

/**
 * 取消暂存（reset HEAD --）
 * - gitUnstage(root, ['a.txt']) → 取消指定文件
 * - gitUnstage(root, undefined, true) → 取消全部暂存
 */
export async function gitUnstage(projectRoot: string, files?: string[], all?: boolean): Promise<GitOperationResult> {
  const res = await authFetch('/api/git/unstage', {
    method: 'POST',
    body: JSON.stringify({ projectRoot, files, all })
  })
  return res.json()
}

/* ==================== 储藏栈（stash） ==================== */

/**
 * 储藏当前变更
 */
export async function gitStashSave(projectRoot: string, message?: string): Promise<GitOperationResult> {
  const res = await authFetch('/api/git/stash', {
    method: 'POST',
    body: JSON.stringify({ projectRoot, message })
  })
  return res.json()
}

/**
 * 获取储藏列表（原始 `stash list` 文本）
 */
export async function gitStashList(projectRoot: string): Promise<GitOperationResult> {
  const params = new URLSearchParams({ projectRoot })
  const res = await authFetch(`/api/git/stash?${params}`)
  return res.json()
}

/**
 * 弹出储藏（默认最新）
 */
export async function gitStashPop(projectRoot: string, index?: number): Promise<GitOperationResult> {
  const res = await authFetch('/api/git/stash/pop', {
    method: 'POST',
    body: JSON.stringify({ projectRoot, index })
  })
  return res.json()
}

/**
 * 删除储藏（默认最新）
 */
export async function gitStashDrop(projectRoot: string, index?: number): Promise<GitOperationResult> {
  const res = await authFetch('/api/git/stash/drop', {
    method: 'POST',
    body: JSON.stringify({ projectRoot, index })
  })
  return res.json()
}

/* ==================== 合并 / 回退 / 撤销提交 ==================== */

/**
 * 合并指定分支到当前分支
 */
export async function gitMerge(
  projectRoot: string,
  branch: string,
  noFf?: boolean,
  message?: string
): Promise<GitOperationResult> {
  const res = await authFetch('/api/git/merge', {
    method: 'POST',
    body: JSON.stringify({ projectRoot, branch, noFf, message })
  })
  return res.json()
}

/**
 * 重置到指定提交（hard 模式需 confirm=true）
 */
export async function gitReset(
  projectRoot: string,
  mode: 'soft' | 'mixed' | 'hard',
  target: string,
  confirm?: boolean
): Promise<GitOperationResult> {
  const res = await authFetch('/api/git/reset', {
    method: 'POST',
    body: JSON.stringify({ projectRoot, mode, target, confirm })
  })
  return res.json()
}

/**
 * 撤销指定提交
 */
export async function gitRevert(projectRoot: string, commit?: string, noCommit?: boolean): Promise<GitOperationResult> {
  const res = await authFetch('/api/git/revert', {
    method: 'POST',
    body: JSON.stringify({ projectRoot, commit, noCommit })
  })
  return res.json()
}

/* ==================== 分支管理 ==================== */

/**
 * 获取本地分支列表（含当前分支）
 */
export async function gitBranches(projectRoot: string): Promise<GitBranchesResult> {
  const params = new URLSearchParams({ projectRoot })
  const res = await authFetch(`/api/git/branches?${params}`)
  return res.json()
}

/**
 * 切换分支
 */
export async function gitBranchSwitch(projectRoot: string, name: string): Promise<GitOperationResult> {
  const res = await authFetch('/api/git/branch/switch', {
    method: 'POST',
    body: JSON.stringify({ projectRoot, name })
  })
  return res.json()
}

/**
 * 新建分支（switchTo=true 时同时切换）
 */
export async function gitBranchCreate(projectRoot: string, name: string, switchTo?: boolean): Promise<GitOperationResult> {
  const res = await authFetch('/api/git/branch/create', {
    method: 'POST',
    body: JSON.stringify({ projectRoot, name, switch: switchTo })
  })
  return res.json()
}

/**
 * 删除分支
 */
export async function gitBranchDelete(projectRoot: string, name: string, force?: boolean): Promise<GitOperationResult> {
  const res = await authFetch('/api/git/branch/delete', {
    method: 'POST',
    body: JSON.stringify({ projectRoot, name, force })
  })
  return res.json()
}

/* ==================== 标签管理 ==================== */

/**
 * 获取标签列表
 */
export async function gitTags(projectRoot: string): Promise<GitTagsResult> {
  const params = new URLSearchParams({ projectRoot })
  const res = await authFetch(`/api/git/tags?${params}`)
  return res.json()
}

/**
 * 创建标签（带 message 则创建附注标签）
 */
export async function gitTagCreate(projectRoot: string, name: string, message?: string): Promise<GitOperationResult> {
  const res = await authFetch('/api/git/tag', {
    method: 'POST',
    body: JSON.stringify({ projectRoot, name, message })
  })
  return res.json()
}

/**
 * 删除标签
 */
export async function gitTagDelete(projectRoot: string, name: string): Promise<GitOperationResult> {
  const params = new URLSearchParams({ projectRoot, name })
  const res = await authFetch(`/api/git/tag?${params}`, {
    method: 'DELETE'
  })
  return res.json()
}

/**
 * 推送标签（指定 name 推单个，否则推送全部标签）
 */
export async function gitTagPush(projectRoot: string, name?: string, remote?: string): Promise<GitOperationResult> {
  const res = await authFetch('/api/git/tag/push', {
    method: 'POST',
    body: JSON.stringify({ projectRoot, name, remote })
  })
  return res.json()
}

/* ==================== 远程仓库管理 ==================== */

/**
 * 获取远程仓库列表（原始 `remote -v` 文本）
 */
export async function gitRemotes(projectRoot: string): Promise<GitOperationResult> {
  const params = new URLSearchParams({ projectRoot })
  const res = await authFetch(`/api/git/remotes?${params}`)
  return res.json()
}

/**
 * 保存远程仓库（后端自动判断 add / set-url）
 */
export async function gitRemoteSave(projectRoot: string, name: string, url: string): Promise<GitOperationResult> {
  const res = await authFetch('/api/git/remote', {
    method: 'POST',
    body: JSON.stringify({ projectRoot, name, url })
  })
  return res.json()
}

/**
 * 删除远程仓库
 */
export async function gitRemoteRemove(projectRoot: string, name: string): Promise<GitOperationResult> {
  const params = new URLSearchParams({ projectRoot, name })
  const res = await authFetch(`/api/git/remote?${params}`, {
    method: 'DELETE'
  })
  return res.json()
}
