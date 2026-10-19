/**
 * 任务清单模型与统计（F12）
 * ----------------------------------------------------------------------------
 * 后端 `task_manager` 工具输出的 JSON 元素长这样（实测）：
 *
 *   { "id": "T1", "description": "…", "status": "pending",
 *     "priority": "HIGH", "group": "p0", "depends_on": ["T2"] }
 *
 * v3 早期只取了 `id / description / status`，把 `priority`（优先级）与
 * `depends_on`（前置依赖）直接丢了 —— 于是「同时有 6 个任务、其中 3 个必须等
 * 前两个做完」这种信息在界面上完全看不出来，用户只能看到一排平铺的待办。
 *
 * 本模块把这三类语义补齐：
 *   - 优先级 → 行左侧色条（HIGH 红 / MEDIUM 橙 / LOW 灰）+ 文字徽章；
 *   - 依赖   → 「等待 T1、T3」小字；依赖未完成时状态显示「阻塞中」；
 *   - 失败   → `failed` 单独标记（后端用 status=failed / error 表示）。
 *
 * 定义放在 utils 而不是某个组件里：任务清单同时被「消息里的工具卡片」
 * （ToolResultCard）、「状态栏任务浮层」（ChatView）与「历史回放」（App.vue）
 * 三处消费，谁都不该成为类型的唯一宿主。
 */

/** 归一化后的状态（后端有多种写法，见 {@link normalizeStatus}） */
export type TaskStatus = 'done' | 'doing' | 'todo'

export type TaskPriority = 'HIGH' | 'MEDIUM' | 'LOW'

export interface TaskItem {
  id: string
  title: string
  status: TaskStatus
  /** 优先级：缺省时不渲染色条（旧数据没有该字段） */
  priority?: TaskPriority
  /** 前置依赖的任务 id（后端字段名 `depends_on`） */
  dependsOn?: string[]
  /** 分组（后端字段 `group`，如 `p0` / `f13`） */
  group?: string
  /** 执行失败（后端用 status=failed / error 表示） */
  failed?: boolean
}

/** 后端状态写法 → 三态。`failed` / `error` 归到 todo 并单独打失败标记 */
export function normalizeStatus(raw: unknown): TaskStatus {
  const text = String(raw ?? '').trim().toLowerCase()
  if (text === 'completed' || text === 'complete' || text === 'done' || text === 'success') return 'done'
  if (text === 'in_progress' || text === 'in-progress' || text === 'doing' || text === 'running') return 'doing'
  return 'todo'
}

/** 优先级归一化：只认 HIGH / MEDIUM / LOW（大小写与中文都兼容） */
export function normalizePriority(raw: unknown): TaskPriority | undefined {
  const text = String(raw ?? '').trim().toUpperCase()
  if (!text) return undefined
  if (text === 'HIGH' || text === 'P0' || text === 'P1' || text === '高' || text === '紧急') return 'HIGH'
  if (text === 'LOW' || text === 'P3' || text === 'P4' || text === '低') return 'LOW'
  if (text === 'MEDIUM' || text === 'NORMAL' || text === 'P2' || text === '中') return 'MEDIUM'
  return undefined
}

/** 依赖字段兼容 `depends_on` / `dependsOn` / 逗号串三种写法 */
export function normalizeDepends(raw: unknown): string[] | undefined {
  if (Array.isArray(raw)) {
    const list = raw.map(v => String(v ?? '').trim()).filter(Boolean)
    return list.length ? list : undefined
  }
  if (typeof raw === 'string' && raw.trim()) {
    const list = raw
      .split(/[,，\s]+/)
      .map(s => s.trim())
      .filter(Boolean)
    return list.length ? list : undefined
  }
  return undefined
}

/**
 * 未满足的前置依赖 id 列表。
 *
 * 判定口径：
 *   - 自身已完成 → 不存在阻塞（历史遗留的依赖不该让已完成的任务显示「等待」）；
 *   - 依赖的任务**存在于同一清单**且未完成 → 阻塞；
 *   - 依赖的任务不在清单里 → 忽略（脏数据/跨清单依赖不应把整行染红）。
 */
export function blockedBy(item: TaskItem, all: TaskItem[]): string[] {
  if (item.status === 'done' || !item.dependsOn?.length) return []
  const map = new Map(all.map(t => [t.id, t]))
  return item.dependsOn.filter(id => {
    const dep = map.get(id)
    return !!dep && dep.status !== 'done'
  })
}

/** 是否被阻塞 */
export function isBlocked(item: TaskItem, all: TaskItem[]): boolean {
  return blockedBy(item, all).length > 0
}

export interface TaskStats {
  total: number
  done: number
  /** 未完成且被前置依赖挡住的任务数 */
  blocked: number
  /** 执行失败的任务数 */
  failed: number
}

/** 任务统计（徽章与浮层头部共用一份口径） */
export function taskStats(items: TaskItem[]): TaskStats {
  let done = 0
  let blocked = 0
  let failed = 0
  for (const item of items) {
    if (item.status === 'done') done += 1
    else if (isBlocked(item, items)) blocked += 1
    if (item.failed) failed += 1
  }
  return { total: items.length, done, blocked, failed }
}

/** 当前进行中的任务（浮层头部提示 / 折叠态摘要用） */
export function currentTask(items: TaskItem[]): TaskItem | undefined {
  return items.find(t => t.status === 'doing') || undefined
}
