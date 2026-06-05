import { authFetch } from '@/utils/http-client'

export interface SnapshotSummary {
  snapshotId: string
  turnId: string
  sessionId: number
  timestamp: string
  fileCount: number
  totalSize: number
  rolledBack?: boolean
}

export interface RollbackFileInfo {
  relativePath: string
  action: 'restore' | 'delete'
}

export interface RollbackPreview {
  snapshotId: string
  timestamp: string
  files: RollbackFileInfo[]
}

export interface SessionFileChange {
  relativePath: string
  linesAdded: number
  linesDeleted: number
  snapshotId: string
  timestamp: string
  wasNewFile: boolean
  rolledBack: boolean
}

export interface SessionChanges {
  totalFiles: number
  totalLinesAdded: number
  totalLinesDeleted: number
  files: SessionFileChange[]
}

/**
 * 获取指定会话的所有代码快照
 */
export async function listSnapshots(sessionId: number): Promise<SnapshotSummary[]> {
  try {
    const res = await authFetch(`/api/snapshots/list?sessionId=${sessionId}`)
    const data = await res.json()
    return data.data || []
  } catch (e) {
    console.warn('获取快照列表失败:', e)
    return []
  }
}

/**
 * 预览回滚：查看快照中包含了哪些文件
 */
export async function previewRollback(snapshotId: string): Promise<RollbackPreview | null> {
  try {
    const res = await authFetch(`/api/snapshots/preview?snapshotId=${snapshotId}`)
    const data = await res.json()
    return data.data || null
  } catch (e) {
    console.warn('预览回滚失败:', e)
    return null
  }
}

/**
 * 执行回滚
 */
export async function executeRollback(snapshotId: string): Promise<boolean> {
  try {
    const res = await authFetch('/api/snapshots/rollback', {
      method: 'POST',
      body: JSON.stringify({ snapshotId })
    })
    const data = await res.json()
    return data.success === true
  } catch (e) {
    console.warn('执行回滚失败:', e)
    return false
  }
}

/**
 * 获取会话文件改动汇总（增删行数统计）
 */
export async function getSessionChanges(sessionId: number): Promise<SessionChanges | null> {
  try {
    const res = await authFetch(`/api/snapshots/session-changes?sessionId=${sessionId}`)
    const data = await res.json()
    return data.data || null
  } catch (e) {
    console.warn('获取会话改动统计失败:', e)
    return null
  }
}

/**
 * 回滚会话中的单个文件
 */
export async function rollbackFile(sessionId: number, filePath: string): Promise<boolean> {
  try {
    const res = await authFetch('/api/snapshots/rollback-file', {
      method: 'POST',
      body: JSON.stringify({ sessionId, filePath })
    })
    const data = await res.json()
    return data.success === true
  } catch (e) {
    console.warn('回滚文件失败:', e)
    return false
  }
}

/**
 * 回滚会话中的所有文件
 */
export async function rollbackAllFiles(sessionId: number): Promise<boolean> {
  try {
    const res = await authFetch('/api/snapshots/rollback-session', {
      method: 'POST',
      body: JSON.stringify({ sessionId })
    })
    const data = await res.json()
    return data.success === true
  } catch (e) {
    console.warn('回滚会话全部文件失败:', e)
    return false
  }
}

/**
 * 获取快照中的文件原始内容（用于 diff 对比）
 */
export async function getSnapshotFileContent(sessionId: number, filePath: string): Promise<string | null> {
  try {
    const res = await authFetch(`/api/snapshots/file-content?sessionId=${sessionId}&filePath=${encodeURIComponent(filePath)}`)
    const data = await res.json()
    return data.success ? (data.data ?? null) : null
  } catch (e) {
    console.warn('获取快照文件内容失败:', e)
    return null
  }
}
