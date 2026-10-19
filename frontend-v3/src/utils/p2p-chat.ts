/**
 * P2P 聊天：消息归一化与展示工具（F01）
 * ----------------------------------------------------------------------------
 * 后端有三条来源，字段各不相同，页面不应该到处写 `if (messageType === ...)`：
 *   1) GET  /api/p2p/messages/{peerId}/history  → HistoryMessage（带 time / direction / 文件字段）
 *   2) GET  /api/p2p/messages/{peerId}          → ChatMessage（轮询增量，无 time，用本地时间补）
 *   3) 本地乐观插入（发送文本 / 发文件 / 调 Agent）→ 直接构造 P2pChatMessage
 * 本模块把三者统一成 P2pChatMessage，并给出渲染需要的展示字段（kind / 日期分组 / 文件徽章）。
 *
 * 后端语义备忘（P2pController）：
 *   - `direction`：`sent` = 我发出的，`received` = 对方发来的（Agent 消息也带该字段）
 *   - `messageType`：chat / file_transfer / agent_invoke / agent_response / auth_grant / auth_cancel
 *   - 文件消息：发送方 `localPath = null`（后端注释「发送方本地无文件」），因此
 *     `GET /file/{peerId}/{transferId}` 对己方发出的文件会 404 —— 气泡需用占位而非 <img> 硬加载
 *   - 历史接口是 `ORDER BY created_at ASC LIMIT ? OFFSET ?`，即「最早的 N 条」，
 *     没有总数接口，故一次拉 HISTORY_LIMIT 条足够覆盖真实使用量（超出时顶部提示）
 */
import type { ChatMessage, HistoryMessage } from '../api/p2p'

/** 页面一次拉取的历史条数（后端 limit 无上限校验，ASC 语义见文件头注释） */
export const HISTORY_LIMIT = 1000

/** 后端 P2pConstants.MAX_FILE_SIZE：2GB */
export const MAX_FILE_SIZE = 2 * 1024 * 1024 * 1024

/** 渲染分类（比后端 messageType 更贴近视觉） */
export type P2pMessageKind = 'chat' | 'file' | 'agent-invoke' | 'agent-response' | 'system'

/** 文件传输状态（后端 fileStatus） */
export type P2pFileStatus = 'completed' | 'transferring' | 'failed'

/** 统一后的聊天消息（渲染唯一输入） */
export interface P2pChatMessage {
  /** 本地唯一键（历史 / 轮询 / 乐观插入统一生成，用于 v-for :key） */
  key: string
  from: 'me' | 'peer'
  kind: P2pMessageKind
  /** 后端原始 messageType，保留用于「回显去重」与语义判断 */
  messageType: string
  /** 发送者展示名 */
  name: string
  content: string
  /** 毫秒时间戳（无 time 的轮询消息取到达时刻） */
  at: number
  agentConfigId?: number
  agentName?: string
  /** 文件类消息 */
  fileName?: string
  fileSize?: number
  mimeType?: string
  fileCategory?: string
  transferId?: string
  fileStatus?: P2pFileStatus
  /** 已发出但未收到响应的 Agent 调用（转圈提示） */
  pending?: boolean
  /** 本地发送失败（气泡加红 + 重试） */
  failed?: boolean
}

let seq = 0
/** 生成消息唯一键：时间戳 + 自增序号，避免同一毫秒内多条冲突 */
export function nextKey(prefix = 'm'): string {
  seq += 1
  return `${prefix}-${Date.now()}-${seq}`
}

/**
 * 解析后端时间。
 * 后端 `LocalDateTime.toString()` 形如 `2026-10-18T12:34:56.789`（无时区），
 * 直接 `new Date(iso)` 在部分浏览器会按 UTC 解析导致 8 小时偏差，故手工拆字段。
 */
export function parseServerTime(raw?: string | null): Date | null {
  if (!raw) return null
  const s = String(raw).trim()
  if (!s) return null
  const m = s.match(/^(\d{4})-(\d{2})-(\d{2})[T ](\d{2}):(\d{2})(?::(\d{2}))?/)
  if (m) {
    const d = new Date(+m[1], +m[2] - 1, +m[3], +m[4], +m[5], m[6] ? +m[6] : 0)
    return Number.isNaN(d.getTime()) ? null : d
  }
  const fallback = new Date(s)
  return Number.isNaN(fallback.getTime()) ? null : fallback
}

/** HH:mm */
export function formatChatTime(at: number): string {
  const d = new Date(at)
  const hh = String(d.getHours()).padStart(2, '0')
  const mm = String(d.getMinutes()).padStart(2, '0')
  return `${hh}:${mm}`
}

/** 日期键 YYYY-MM-DD（分组用） */
export function dayKeyOf(at: number): string {
  const d = new Date(at)
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`
}

/** 日期分隔标签：今天 / 昨天 / M月D日 / YYYY年M月D日 */
export function dayLabelOf(at: number): string {
  const d = new Date(at)
  const today = new Date()
  const startOf = (x: Date) => new Date(x.getFullYear(), x.getMonth(), x.getDate()).getTime()
  const diffDays = Math.round((startOf(today) - startOf(d)) / 86_400_000)
  if (diffDays === 0) return '今天'
  if (diffDays === 1) return '昨天'
  if (d.getFullYear() === today.getFullYear()) return `${d.getMonth() + 1}月${d.getDate()}日`
  return `${d.getFullYear()}年${d.getMonth() + 1}月${d.getDate()}日`
}

/** 字节数 → 人类可读（B / KB / MB / GB，最多一位小数） */
export function formatFileSize(bytes?: number | null): string {
  if (!bytes || bytes <= 0) return '0 B'
  const units = ['B', 'KB', 'MB', 'GB']
  let i = 0
  let size = bytes
  while (size >= 1024 && i < units.length - 1) {
    size /= 1024
    i += 1
  }
  return `${size.toFixed(i > 0 ? 1 : 0)} ${units[i]}`
}

/** 扩展名徽章（最多 4 字符，无扩展名返回 FILE） */
export function fileExtBadge(fileName?: string): string {
  const name = String(fileName || '')
  const idx = name.lastIndexOf('.')
  if (idx <= 0 || idx === name.length - 1) return 'FILE'
  return name.slice(idx + 1).toUpperCase().slice(0, 4)
}

/** 是否图片类文件消息 */
export function isImageFile(msg: P2pChatMessage): boolean {
  if (msg.messageType !== 'file_transfer') return false
  if (msg.fileCategory === 'image') return true
  return String(msg.mimeType || '').startsWith('image/')
}

/** 稳定头像渐变：同一 peerId 永远同色（8 组柔和渐变，深浅主题都可用） */
const PEER_GRADIENTS = [
  'linear-gradient(135deg, #7C5CFC, #A78BFA)',
  'linear-gradient(135deg, #0EA5E9, #38BDF8)',
  'linear-gradient(135deg, #10B981, #34D399)',
  'linear-gradient(135deg, #F59E0B, #FBBF24)',
  'linear-gradient(135deg, #EF4444, #F87171)',
  'linear-gradient(135deg, #8B5CF6, #C084FC)',
  'linear-gradient(135deg, #14B8A6, #2DD4BF)',
  'linear-gradient(135deg, #EC4899, #F472B6)'
]

export function peerGradient(peerId: string): string {
  const id = String(peerId || '')
  let hash = 0
  for (let i = 0; i < id.length; i += 1) {
    hash = (hash * 31 + id.charCodeAt(i)) | 0
  }
  return PEER_GRADIENTS[Math.abs(hash) % PEER_GRADIENTS.length]
}

/** 后端 messageType → 渲染分类 */
function kindOf(messageType?: string): P2pMessageKind {
  switch (messageType) {
    case 'file_transfer':
      return 'file'
    case 'agent_invoke':
      return 'agent-invoke'
    case 'agent_response':
      return 'agent-response'
    case 'auth_grant':
    case 'auth_cancel':
      return 'system'
    default:
      return 'chat'
  }
}

/** 归一化历史接口返回（含时间与文件字段） */
export function normalizeHistory(list: HistoryMessage[]): P2pChatMessage[] {
  return (list || []).map((m, i) => {
    const at = parseServerTime(m.time)?.getTime() ?? Date.now() + i
    return {
      key: nextKey('h'),
      from: m.direction === 'sent' ? 'me' : 'peer',
      kind: kindOf(m.messageType),
      messageType: m.messageType || 'chat',
      name: m.name || '',
      content: m.content || '',
      at,
      agentConfigId: m.agentConfigId,
      agentName: m.agentName,
      fileName: (m as HistoryMessage & { fileName?: string }).fileName,
      fileSize: (m as HistoryMessage & { fileSize?: number }).fileSize,
      mimeType: (m as HistoryMessage & { mimeType?: string }).mimeType,
      fileCategory: (m as HistoryMessage & { fileCategory?: string }).fileCategory,
      transferId: (m as HistoryMessage & { transferId?: string }).transferId,
      fileStatus: ((m as HistoryMessage & { fileStatus?: string }).fileStatus || 'completed') as P2pFileStatus
    }
  })
}

/** 归一化轮询增量（后端不回显自己发出的文本，故不会与本地区消息重复） */
export function normalizePoll(list: ChatMessage[]): P2pChatMessage[] {
  return (list || []).map(m => {
    const raw = m as ChatMessage & {
      direction?: string
      fileName?: string
      fileSize?: number
      mimeType?: string
      fileCategory?: string
      transferId?: string
      fileStatus?: string
    }
    return {
      key: nextKey('p'),
      from: raw.direction === 'sent' ? 'me' : 'peer',
      kind: kindOf(raw.messageType),
      messageType: raw.messageType || 'chat',
      name: raw.name || '',
      content: raw.content || '',
      at: Date.now(),
      agentConfigId: raw.agentConfigId,
      agentName: raw.agentName,
      fileName: raw.fileName,
      fileSize: raw.fileSize,
      mimeType: raw.mimeType,
      fileCategory: raw.fileCategory,
      transferId: raw.transferId,
      fileStatus: (raw.fileStatus || 'completed') as P2pFileStatus
    }
  })
}

/** 日期分组（渲染时插入日期分隔条） */
export interface P2pDayGroup {
  key: string
  label: string
  items: P2pChatMessage[]
}

export function groupByDay(messages: P2pChatMessage[]): P2pDayGroup[] {
  const groups: P2pDayGroup[] = []
  for (const msg of messages) {
    const key = dayKeyOf(msg.at)
    const last = groups[groups.length - 1]
    if (last && last.key === key) {
      last.items.push(msg)
    } else {
      groups.push({ key, label: dayLabelOf(msg.at), items: [msg] })
    }
  }
  return groups
}

/** 轮询回显去重：同方向 + 同类型 + 同内容且 10 秒内的重复消息只保留一条 */
export function dedupeIncoming(existing: P2pChatMessage[], incoming: P2pChatMessage[]): P2pChatMessage[] {
  const result: P2pChatMessage[] = []
  for (const msg of incoming) {
    const dup = [...existing, ...result].some(
      old =>
        old.from === msg.from &&
        old.messageType === msg.messageType &&
        old.content === msg.content &&
        Math.abs(old.at - msg.at) < 10_000
    )
    if (!dup) result.push(msg)
  }
  return result
}
