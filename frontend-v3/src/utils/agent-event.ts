import type { SubAgentRecord } from '../api/sub-agent'
import type { DelegateProgressData } from './sse-client'

/**
 * 子Agent / 委托调用的前端归一模型（F05 子Agent面板）。
 *
 * <h3>为什么是「读库 + 委托事件」而不是「收 agent_event」</h3>
 * 后端 v3 的 SSE 事件通道只有五种 JSON 事件：
 * `ask_user` / `tool_call_start` / `task_error` / `delegate_progress` / `resume`。
 * 前端 `sse-client.ts` 里那条 `parsed.event.startsWith('agent_')` 分支是**历史遗留**：
 * 后端 `AgentForkManager`（fork 子Agent）从不发这类事件，它的产出是纯文本写进
 * 主Agent思考流的（见 `DeepSeekServiceImpl` 的「📋 主Agent已完成，正在等待 N 个子Agent结果…」）。
 * 旧版 `AgentPanel.vue` 因此一直是一块空面板。
 *
 * <p>所以这里的数据源是两条<b>真实存在</b>的通道：</p>
 * <ol>
 *   <li><b>子Agent</b>：`GET /api/sub-agents` 读 `sub_agent_log` 表
 *       （fork 时 INSERT status=running，结束时 UPDATE completed/failed/timeout）。</li>
 *   <li><b>委托调用</b>：SSE 的 `delegate_progress` 心跳（Agent A 委托 Agent B 执行任务期间
 *       每约 5 秒一次，带 targetSessionId / targetAgent / elapsedSec）。</li>
 * </ol>
 * 两者语义不同（fork 出的是「分身」，委托是「找另一个智能体干活」），面板里分块展示。
 */

// ============================================================
// 子Agent（读库）
// ============================================================

export type SubAgentStatus = 'running' | 'completed' | 'failed' | 'timeout' | 'unknown'

/** 子Agent面板消费的归一模型 */
export interface SubAgentRun {
  /** 唯一键（agentId 在单会话内唯一） */
  key: string
  agentId: string
  name: string
  status: SubAgentStatus
  /** 任务描述（instructions） */
  task: string
  contextMode?: string
  tools: string[]
  skills: string[]
  summary?: string
  /** passed / failed / not_run */
  compileResult?: string
  iterationsUsed: number
  maxIterations: number
  errorMessage?: string
  turnId?: string
  files: { created: string[]; modified: string[]; deleted: string[] }
  /** yyyy-MM-dd HH:mm:ss */
  startedAt?: string
  endedAt?: string
  /** 已耗时（毫秒）：已结束为真实耗时，运行中为「到现在」 */
  elapsedMs: number
}

export type SubAgentTone = 'running' | 'ok' | 'danger' | 'warn'

/** 状态展示元数据（颜色 / 文案 / 图标统一，避免各处各写一套） */
export const SUB_AGENT_STATUS_META: Record<SubAgentStatus, { label: string; tone: SubAgentTone }> = {
  running: { label: '运行中', tone: 'running' },
  completed: { label: '已完成', tone: 'ok' },
  failed: { label: '失败', tone: 'danger' },
  timeout: { label: '超时', tone: 'warn' },
  unknown: { label: '状态未知', tone: 'warn' }
}

/** 上下文继承模式的中文说明（后端 enum：inherit_summary / inherit_full / none） */
const CONTEXT_MODE_LABEL: Record<string, string> = {
  inherit_summary: '继承摘要',
  inherit_full: '继承全文',
  none: '不继承'
}

export function describeContextMode(mode?: string): string {
  if (!mode) return ''
  return CONTEXT_MODE_LABEL[mode] || mode
}

/**
 * 解析后端时间字符串（`yyyy-MM-dd HH:mm:ss`）。
 *
 * <p>刻意不用 `new Date(text)`：带空格的格式在部分浏览器里返回 Invalid Date，
 * 后端 `LocalDateTime` 又不带时区。手动拆分后按本地时区构造，跨浏览器一致。</p>
 */
export function parseServerTime(text?: string | null): number | null {
  if (!text) return null
  const m = /^(\d{4})-(\d{2})-(\d{2})[ T](\d{2}):(\d{2}):(\d{2})/.exec(text)
  if (!m) return null
  return new Date(+m[1], +m[2] - 1, +m[3], +m[4], +m[5], +m[6]).getTime()
}

function normalizeStatus(status?: string): SubAgentStatus {
  const s = (status || '').trim().toLowerCase()
  if (s === 'running' || s === 'completed' || s === 'failed' || s === 'timeout') return s
  return 'unknown'
}

/**
 * 后端记录 → 面板模型。
 *
 * @param now 计算「运行中已耗时」的基准时间（默认当前时刻；测试可注入固定值）
 */
export function toSubAgentRun(record: SubAgentRecord, now: number = Date.now()): SubAgentRun {
  const status = normalizeStatus(record.status)
  const startedMs = parseServerTime(record.createdAt)
  const endedMs = parseServerTime(record.completedAt)
  let elapsedMs = 0
  if (startedMs !== null) {
    elapsedMs = Math.max(0, (endedMs !== null ? endedMs : now) - startedMs)
  }
  const agentId = record.agentId || ''
  return {
    key: agentId || `sub-${record.id ?? Math.random().toString(36).slice(2, 8)}`,
    agentId,
    name: record.name || agentId || '未命名子Agent',
    status,
    task: record.instructions || '',
    contextMode: record.contextMode,
    tools: Array.isArray(record.tools) ? record.tools : [],
    skills: Array.isArray(record.skills) ? record.skills : [],
    summary: record.summary || '',
    compileResult: record.compileResult,
    iterationsUsed: record.iterationsUsed ?? 0,
    maxIterations: record.maxIterations ?? 0,
    errorMessage: record.errorMessage || '',
    turnId: record.parentTurnId,
    files: {
      created: Array.isArray(record.createdFiles) ? record.createdFiles : [],
      modified: Array.isArray(record.modifiedFiles) ? record.modifiedFiles : [],
      deleted: Array.isArray(record.deletedFiles) ? record.deletedFiles : []
    },
    startedAt: record.createdAt,
    endedAt: record.completedAt,
    elapsedMs
  }
}

/** 批量归一（丢弃无 agentId 的空记录，按创建时间升序） */
export function toSubAgentRuns(records: SubAgentRecord[] | null | undefined, now?: number): SubAgentRun[] {
  if (!records || !records.length) return []
  return records
    .filter(r => !!r && (!!r.agentId || r.id != null))
    .map(r => toSubAgentRun(r, now))
    .sort((a, b) => (parseServerTime(a.startedAt) ?? 0) - (parseServerTime(b.startedAt) ?? 0))
}

export interface SubAgentStats {
  total: number
  running: number
  done: number
  failed: number
}

export function subAgentStats(runs: SubAgentRun[]): SubAgentStats {
  const stats: SubAgentStats = { total: runs.length, running: 0, done: 0, failed: 0 }
  for (const run of runs) {
    if (run.status === 'running') stats.running += 1
    else if (run.status === 'completed') stats.done += 1
    else if (run.status === 'failed' || run.status === 'timeout') stats.failed += 1
  }
  return stats
}

/** 某个子Agent的改动统计（面板折叠态只显示 +A/~M/-D 三个数） */
export function subAgentFileStats(run: SubAgentRun): { added: number; modified: number; deleted: number; total: number } {
  const added = run.files.created.length
  const modified = run.files.modified.length
  const deleted = run.files.deleted.length
  return { added, modified, deleted, total: added + modified + deleted }
}

/** 耗时文案：`12s` / `1m 20s` / `1h 2m`（面板与运行状态栏共用同一口径） */
export function formatDuration(ms: number): string {
  if (!Number.isFinite(ms) || ms <= 0) return '0s'
  const total = Math.floor(ms / 1000)
  if (total < 60) return `${total}s`
  const minutes = Math.floor(total / 60)
  const seconds = total % 60
  if (minutes < 60) return seconds ? `${minutes}m ${seconds}s` : `${minutes}m`
  const hours = Math.floor(minutes / 60)
  const restMinutes = minutes % 60
  return restMinutes ? `${hours}h ${restMinutes}m` : `${hours}h`
}

/**
 * 判断一批工具调用名里是否出现「fork 子Agent」的动作。
 *
 * <p>`tool_call_start` 事件只给工具名、不给参数，所以无法区分 `agent` 工具的
 * fork / collect / inspect 三种 action。宁可多拉一次列表（代价是一次只读请求），
 * 也不要漏掉子Agent创建 —— 面板拉空时前端自己会停轮询。</p>
 */
export function mayForkSubAgent(toolNames: string[]): boolean {
  return toolNames.some(name => (name || '').trim().toLowerCase() === 'agent')
}

// ============================================================
// 委托调用（SSE delegate_progress 心跳）
// ============================================================

/** 委托调用（A 委托 B 智能体执行任务）的归一模型 */
export interface DelegateCall {
  callId: string
  /** 目标（B）会话 id —— 「前往 B 会话」用 */
  targetSessionId?: number
  targetAgent?: string
  /** 后端仅发 RUNNING（终态由 A 的工具结果体现） */
  status?: string
  /** 已等待秒数（后端每约 5s 刷新一次） */
  elapsedSec: number
  /** 最近一次收到心跳的本地时间戳（判断心跳是否已停） */
  updatedAt: number
}

/** `delegate_progress` 事件数据 → 归一模型（callId 缺失时返回 null，无法去重） */
export function toDelegateCall(data: DelegateProgressData | null | undefined, now: number = Date.now()): DelegateCall | null {
  if (!data || !data.callId) return null
  return {
    callId: data.callId,
    targetSessionId: data.targetSessionId,
    targetAgent: data.targetAgent,
    status: data.status,
    elapsedSec: data.elapsedSec ?? 0,
    updatedAt: now
  }
}

/** 按 callId 合并（同一委托多次心跳只保留最新一条） */
export function mergeDelegateCall(list: DelegateCall[], next: DelegateCall): DelegateCall[] {
  const index = list.findIndex(item => item.callId === next.callId)
  if (index === -1) return [...list, next]
  const copy = list.slice()
  copy[index] = next
  return copy
}

/**
 * 心跳是否已过期。
 *
 * <p>后端只在 await 阻塞期间发心跳，委托结束（或会话断开）后不会再发。
 * 超过 20 秒没有任何心跳的委托标记为「已结束」，避免面板上永久挂着一条假运行项。</p>
 */
export const DELEGATE_STALE_MS = 20_000

export function isDelegateStale(call: DelegateCall, now: number = Date.now()): boolean {
  return now - call.updatedAt > DELEGATE_STALE_MS
}
