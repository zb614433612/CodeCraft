import type { ApiResponse } from '@/store/user'
import { authFetch as authFetchWithRedirect, getAuthHeaders } from '@/utils/http-client'

export interface ProjectTreeNode {
  name: string
  path: string
  directory: boolean
  children?: ProjectTreeNode[]
}

export interface DirectoryEntry {
  name: string
  path: string
  directory: boolean
}

async function request<T>(url: string, options: RequestInit = {}): Promise<ApiResponse<T>> {
  const response = await authFetchWithRedirect(`/api${url}`, {
    headers: { 'Content-Type': 'application/json', ...options.headers },
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

export async function getDirChildren(path: string, includeFiles = false): Promise<ApiResponse<DirectoryEntry[]>> {
  const params = new URLSearchParams()
  params.set('path', path)
  params.set('includeFiles', String(includeFiles))
  return request<DirectoryEntry[]>(`/project/children?${params}`)
}

export async function readProjectFile(path: string): Promise<ApiResponse<string>> {
  const params = new URLSearchParams()
  params.set('path', path)
  return request<string>(`/project/read?${params}`)
}

/**
 * 解析"不完整文件路径"（LLM 常输出省略前缀的短路径，如 api/appearance.ts）：
 * 后端在项目内按"路径后缀"匹配真实文件，返回候选的相对路径列表（精确匹配在前，最多 50 个）
 */
export async function resolveProjectFilePath(root: string, path: string): Promise<ApiResponse<string[]>> {
  const params = new URLSearchParams()
  if (root) params.set('root', root)
  params.set('path', path)
  return request<string[]>(`/project/resolve?${params}`)
}

/**
 * 读取项目/本地文件原始字节并转为 blob URL（图片、PDF 等二进制预览用）
 * 说明：<img src> 无法携带鉴权头，故用 fetch 带 Authorization 拉取 blob 再转 objectURL
 * 调用方需在合适时机对返回的 URL 调用 URL.revokeObjectURL 释放
 */
export async function getProjectFileBlobUrl(path: string): Promise<string> {
  const authHeader = await getAuthHeaders()
  const res = await fetch(`/api/project/raw?path=${encodeURIComponent(path)}`, { headers: authHeader })
  if (!res.ok) {
    throw new Error(res.status === 404 ? '文件不存在或无法访问' : `文件加载失败（${res.status}）`)
  }
  const blob = await res.blob()
  return URL.createObjectURL(blob)
}

export async function writeProjectFile(path: string, content: string): Promise<ApiResponse<null>> {
  return request<null>('/project/write', {
    method: 'POST',
    body: JSON.stringify({ path, content })
  })
}

// ===== 编译/运行/停止 =====

export interface BuildResult {
  success: boolean
  output: string
  exitCode: number
  duration: number
}

export interface RunResult {
  success: boolean
  message: string
  pid?: number
}

export interface StopResult {
  success: boolean
  message: string
}

export interface StatusResult {
  running: boolean
  pid?: number
  elapsed: number
}

export interface OutputResult {
  success: boolean
  lines: string[]
  running: boolean
}

async function authFetch(url: string, options: RequestInit = {}): Promise<any> {
  const response = await authFetchWithRedirect(`/api${url}`, {
    headers: { 'Content-Type': 'application/json', ...options.headers },
    ...options
  })
  return response.json()
}

/** 编译项目 */
export async function buildProject(projectRoot: string): Promise<BuildResult> {
  return authFetch('/project/build', {
    method: 'POST',
    body: JSON.stringify({ projectRoot })
  })
}

/** 运行项目（后台进程） */
export async function runProject(projectRoot: string): Promise<RunResult> {
  return authFetch('/project/run', {
    method: 'POST',
    body: JSON.stringify({ projectRoot })
  })
}

/** 停止运行中的项目 */
export async function stopProject(): Promise<StopResult> {
  return authFetch('/project/stop', {
    method: 'POST'
  })
}

/** 获取运行状态 */
export async function getRunStatus(): Promise<StatusResult> {
  return authFetch('/project/run/status')
}

export interface ExecResult {
  success: boolean
  output: string
  exitCode: number
  timedOut: boolean
}

/** 执行自定义命令（终端用） */
export async function execCommand(command: string, workingDirectory?: string, timeout?: number): Promise<ExecResult> {
  return authFetch('/project/exec', {
    method: 'POST',
    body: JSON.stringify({ command, workingDirectory, timeout })
  })
}

/** 获取控制台输出 */
export async function getRunOutput(tail?: number): Promise<OutputResult> {
  const params = tail !== undefined ? `?tail=${tail}` : ''
  return authFetch(`/project/run/output${params}`)
}
