import type { ApiResponse } from '@/store/user'
import { authFetch as authFetchWithRedirect } from '@/utils/http-client'

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
