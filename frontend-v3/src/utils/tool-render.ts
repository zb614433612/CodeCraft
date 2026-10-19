/**
 * 工具结果语义化（F13）
 * ----------------------------------------------------------------------------
 * 后端工具输出是**给我们（LLM）看的纯文本**，格式五花八门：
 *
 *   > **file_writer**
 *   ✅ 写入成功：C:\...\foo.ts
 *   大小：7.0 KB
 *   内容长度：5696 字符
 *   操作：新建文件
 *   [TOOL_RESULT]{"additions":185,"deletions":0}[/TOOL_RESULT]
 *
 * 直接把它塞进 `<pre>`（v3 之前就是这么做的）有三个问题：
 *   1. 折叠态只能看到「✅ 写入成功…」这一行，看不出改了哪个文件、加了几行；
 *   2. 展开后是一坨等宽纯文本，命令输出、搜索结果、SQL 结果长得一模一样；
 *   3. 20+ 种工具的格式差异全被抹平，用户得自己找关键信息。
 *
 * 本模块把原始文本归一成 {@link ToolRenderModel} 联合类型，交给 `ToolResultCard`
 * 按 `kind` 渲染成不同卡片（文件改动带 +N/-M 徽章、命令用终端块 + 退出码色标、
 * 搜索用命中列表、网页用标题 + 域名 + 摘要……）。
 *
 * 设计原则：
 *   - **按工具名选 kind，再宽松解析**：正则只用于抽字段，抽不到就留空，
 *     绝不因为后端换了个措辞就整张卡片崩掉（失败一律降级到 `raw`）。
 *   - **原文永远保留**（`raw` 字段）：卡片底部有「查看原始输出」，
 *     解析不全时用户自己能看到完整内容。
 *   - **解析开销可控**：`detail` 已在 chat-mapper 截断到 4000 字符；
 *     折叠态另走 {@link toolHeadline} 只看前几行，不碰全文。
 */

/** 卡片形态 */
export type ToolKind =
  | 'file-write'
  | 'file-read'
  | 'file-tree'
  | 'search'
  | 'command'
  | 'web'
  | 'db'
  | 'git'
  | 'agent'
  | 'task'
  | 'error'
  | 'raw'

interface ToolBase {
  kind: ToolKind
  /** 原始工具名（`file_writer` 等），卡片右上角作为技术标识展示 */
  name: string
  /** 清理过头部标记（`> **name**`、分隔线、首尾空行）的原文，用于「查看原始输出」 */
  raw: string
  /** 工具是否返回了错误（`【参数缺失】`、`❌` 等） */
  failed: boolean
}

export interface FileWriteModel extends ToolBase {
  kind: 'file-write'
  action: 'write' | 'edit' | 'delete' | 'other'
  path: string
  sizeText?: string
  linesAdded?: number
  linesDeleted?: number
  /** 其余元信息行（内容长度 / 匹配位置 / 行数变化 / 文件现总行数 …） */
  meta: string[]
  /** 变更预览（保留 `-|` / `+|` 前缀，供 diff 着色） */
  diff: string[]
}

export interface FileReadModel extends ToolBase {
  kind: 'file-read'
  path: string
  encoding?: string
  typeText?: string
  /** 文件总行数 */
  lines?: number
  /** 「显示 1 - 104」这样的可见范围 */
  rangeText?: string
  /** 分隔线之后的正文（带行号，保持等宽展示） */
  body: string
}

export interface FileTreeModel extends ToolBase {
  kind: 'file-tree'
  root: string
  outline: string
}

export interface SearchHit {
  /** 所属文件（grep 分组行；glob 时为路径本身） */
  file?: string
  /** 行号（grep 命中行） */
  line?: number
  /** 命中文本 / glob 的文件大小 */
  text?: string
}

export interface SearchModel extends ToolBase {
  kind: 'search'
  mode: 'grep' | 'glob'
  pattern: string
  scope?: string
  total?: number
  hits: SearchHit[]
}

export interface CommandModel extends ToolBase {
  kind: 'command'
  mode: 'exec' | 'service'
  cmd: string
  workDir?: string
  exitCode?: number | null
  durationText?: string
  stdout: string
}

export interface WebResult {
  index: number
  title: string
  url: string
  domain?: string
  snippet?: string
}

export interface WebModel extends ToolBase {
  kind: 'web'
  mode: 'search' | 'fetch'
  query?: string
  url?: string
  results: WebResult[]
  body: string
}

export interface DbModel extends ToolBase {
  kind: 'db'
  dbLabel?: string
  /** 查询完成 / 执行成功，影响 N 行 */
  summary: string
  body: string
}

export interface GitModel extends ToolBase {
  kind: 'git'
  /** 语义动作：暂存 / 提交 / 推送 / 状态 / 差异 / 合并 … */
  action: string
  summary: string
  body: string
}

export interface AgentModel extends ToolBase {
  kind: 'agent'
  action: string
  summary: string
  body: string
}

export interface TaskRow {
  id: string
  title: string
  status: string
  priority?: string
  group?: string
  dependsOn?: string[]
}

export interface TaskModel extends ToolBase {
  kind: 'task'
  summary: string
  tasks: TaskRow[]
}

export interface ErrorModel extends ToolBase {
  kind: 'error'
  /** 错误标题（方括号内的类型，如「参数缺失」；没有则取首行） */
  title: string
  message: string
}

export interface RawModel extends ToolBase {
  kind: 'raw'
}

export type ToolRenderModel =
  | FileWriteModel
  | FileReadModel
  | FileTreeModel
  | SearchModel
  | CommandModel
  | WebModel
  | DbModel
  | GitModel
  | AgentModel
  | TaskModel
  | ErrorModel
  | RawModel

/* ============================================================================
 * 基础工具函数
 * ========================================================================== */

/** 40 个连字符 / 制表符构成的分隔线（后端工具输出头部固定带一条） */
const SEP_RE = /^[-─—━=]{6,}$/
/** 工具名前缀行：`> **file_writer**` */
const TOOL_HEAD_RE = /^>\s*\*\*.+\*\*$/
/** 错误首行：`【参数缺失】…` / `❌ 推送失败…` / `错误：…` */
const ERROR_HEAD_RE = /^(?:【[^】]{1,24}】|❌|⚠️\s*高危|错误[:：])/

/** 去掉 CRLF、头部标记行与首尾空行 */
function cleanup(raw: string): string {
  const lines = String(raw || '')
    .replace(/\r\n/g, '\n')
    .split('\n')
  while (lines.length) {
    const l = lines[0].trim()
    if (!l || SEP_RE.test(l) || TOOL_HEAD_RE.test(l)) {
      lines.shift()
      continue
    }
    break
  }
  while (lines.length && !lines[lines.length - 1].trim()) lines.pop()
  return lines.join('\n')
}

/** 从「键：值」行里取值 */
function valueOf(lines: string[], ...keys: string[]): string | undefined {
  for (const key of keys) {
    for (const line of lines.slice(0, 12)) {
      const m = line.match(new RegExp(`^\\s*${key}\\s*[:：]\\s*(.+)$`))
      if (m) return m[1].trim()
    }
  }
  return undefined
}

/** 数字抽取（`185` / `1,234`） */
function toNumber(text?: string): number | undefined {
  if (!text) return undefined
  const n = Number(text.replace(/[^\d.-]/g, ''))
  return Number.isFinite(n) ? n : undefined
}

/** 首行 */
function firstLine(text: string): string {
  return text.split('\n')[0]?.trim() || ''
}

/** 取分隔线之后的正文；没有分隔线则返回全文 */
function afterSeparator(text: string): string {
  const lines = text.split('\n')
  for (let i = 0; i < lines.length; i += 1) {
    if (SEP_RE.test(lines[i].trim())) return lines.slice(i + 1).join('\n').replace(/^\n+/, '')
  }
  return text
}

/** 提取 `[TOOL_RESULT]…[/TOOL_RESULT]` 里的 JSON（截断导致解析失败时静默忽略） */
function readToolResultBlock(text: string): Record<string, unknown> | null {
  const start = text.indexOf('[TOOL_RESULT]')
  if (start === -1) return null
  const end = text.indexOf('[/TOOL_RESULT]', start)
  const json = text.slice(start + '[TOOL_RESULT]'.length, end === -1 ? undefined : end).trim()
  if (!json) return null
  try {
    const parsed = JSON.parse(json)
    return parsed && typeof parsed === 'object' ? (parsed as Record<string, unknown>) : null
  } catch {
    return null
  }
}

/* ============================================================================
 * 折叠态摘要（轻量：只看前几行 + TOOL_RESULT 徽章）
 * ========================================================================== */

/** 折叠态摘要：工具名 + 语义标签 + 目标 + 徽章 */
export interface ToolHeadline {
  kind: ToolKind
  /** 语义标签，如「写入」「搜索文件」「执行命令」 */
  label: string
  /** 目标（路径 / 命令 / 关键词 / URL），过长会被截断 */
  target?: string
  /** 关键徽章，如 `+185 -0`、`退出码 0`、`12 处命中` */
  badge?: string
  /** 徽章色调 */
  tone?: 'ok' | 'warn' | 'danger' | 'accent'
  failed: boolean
}

const HEADLINE_CACHE = new Map<string, ToolHeadline>()

/**
 * 折叠态摘要。
 *
 * 只解析前 8 行 + `[TOOL_RESULT]` 里的增删行数 —— 一个会话里可能有几百次工具调用，
 * 折叠态全部展开解析会把首屏渲染拖慢。展开后才走 {@link renderToolModel} 做完整解析。
 */
export function toolHeadline(name: string, detail?: string): ToolHeadline {
  const raw = detail || ''
  const key = `${name}\u0000${raw.length}\u0000${raw.slice(0, 120)}`
  const hit = HEADLINE_CACHE.get(key)
  if (hit) return hit

  const text = cleanup(raw)
  const head = firstLine(text)
  const failed = ERROR_HEAD_RE.test(head)
  let result: ToolHeadline

  if (failed) {
    const titleMatch = head.match(/^【([^】]+)】/)
    result = { kind: 'error', label: '失败', target: (titleMatch ? titleMatch[1] : head).slice(0, 60), tone: 'danger', failed: true }
  } else if (name === 'file_writer') {
    const action = /编辑成功|文件已编辑/.test(head) ? '编辑' : /已删除|删除成功/.test(head) ? '删除' : /写入成功/.test(head) ? '写入' : '文件'
    const path = head.match(/^(?:✅\s*)?(?:写入成功|编辑成功|文件已删除|删除成功)[:：]\s*(.+)$/)?.[1]
      || valueOf(text.split('\n'), '路径')
      || ''
    const block = readToolResultBlock(text)
    const add = toNumber(String(block?.additions ?? ''))
    const del = toNumber(String(block?.deletions ?? ''))
    result = {
      kind: 'file-write',
      label: action,
      target: path ? shortPath(path) : undefined,
      badge: add != null || del != null ? `+${add ?? 0} -${del ?? 0}` : undefined,
      tone: 'ok',
      failed: false
    }
  } else if (name === 'file_explorer') {
    if (/^路径:/.test(head)) {
      const path = valueOf(text.split('\n'), '路径') || ''
      const lines = toNumber(valueOf(text.split('\n'), '行数'))
      result = { kind: 'file-read', label: '读取', target: shortPath(path), badge: lines != null ? `${lines} 行` : undefined, tone: 'accent', failed: false }
    } else if (/^项目目录：/.test(head)) {
      const root = valueOf(text.split('\n'), '项目目录') || ''
      result = { kind: 'file-tree', label: '目录树', target: shortPath(root), tone: 'accent', failed: false }
    } else if (/^(搜索模式：|匹配结果)/.test(head) || /^搜索模式：/.test(text.split('\n').find(l => l.trim()) || '')) {
      const lines = text.split('\n')
      const pattern = valueOf(lines, '搜索模式') || ''
      const total = toNumber(lines.find(l => /匹配结果：共 \d+/.test(l))?.match(/共 (\d+)/)?.[1])
      const isGlob = /共 \d+ 个文件/.test(text)
      result = {
        kind: 'search',
        label: isGlob ? '搜索文件' : '搜索内容',
        target: pattern,
        badge: total != null ? `${total} ${isGlob ? '个文件' : '处'}` : undefined,
        tone: total === 0 ? 'warn' : 'accent',
        failed: false
      }
    } else {
      result = { kind: 'raw', label: '文件操作', target: head.slice(0, 60), failed: false }
    }
  } else if (name === 'command') {
    const lines = text.split('\n')
    const cmd = valueOf(lines, '执行命令', '命令') || ''
    const exitCode = toNumber(valueOf(lines, '退出码'))
    const service = /后台服务已启动|服务 #\d+ 输出|后台服务列表|已停止/.test(head)
    result = {
      kind: 'command',
      label: service ? '服务' : '执行命令',
      target: cmd ? cmd.slice(0, 80) : head.slice(0, 60),
      badge: exitCode != null ? `退出码 ${exitCode}` : undefined,
      tone: exitCode == null ? 'accent' : exitCode === 0 ? 'ok' : 'danger',
      failed: exitCode != null && exitCode !== 0
    }
  } else if (name === 'web_search') {
    const count = (text.match(/^\s*\d+\.\s+/gm) || []).length
    result = { kind: 'web', label: '联网搜索', target: head.replace(/^---.*?---\s*/, '').slice(0, 60) || undefined, badge: count ? `${count} 条` : undefined, tone: count ? 'accent' : 'warn', failed: false }
  } else if (name === 'web_fetch') {
    const url = text.match(/^---\s*网页内容:\s*(.+?)\s*---/)?.[1] || ''
    result = { kind: 'web', label: '抓取网页', target: url ? shortPath(url) : undefined, tone: 'accent', failed: false }
  } else if (name === 'execute_sql') {
    result = { kind: 'db', label: 'SQL', target: (valueOf(text.split('\n'), '目标库') || head).slice(0, 60), tone: 'accent', failed: false }
  } else if (name.startsWith('git_')) {
    result = { kind: 'git', label: gitLabel(name, text), target: head.slice(0, 60), tone: 'accent', failed: false }
  } else if (name === 'agent' || name === 'agent_invoke') {
    result = { kind: 'agent', label: '子 Agent', target: head.slice(0, 60), tone: 'accent', failed: false }
  } else if (name === 'task_manager') {
    const done = (text.match(/"status":"completed"/g) || []).length
    const total = (text.match(/"id":/g) || []).length
    result = { kind: 'task', label: '任务清单', badge: total ? `${done}/${total}` : undefined, tone: 'accent', failed: false }
  } else {
    result = { kind: 'raw', label: '工具', target: head.slice(0, 60), failed: false }
  }

  if (HEADLINE_CACHE.size > 400) HEADLINE_CACHE.clear()
  HEADLINE_CACHE.set(key, result)
  return result
}

/** 路径压缩：只保留最后两段（`…/utils/tool-render.ts`） */
function shortPath(path: string): string {
  const clean = path.trim().replace(/\\/g, '/')
  const parts = clean.split('/').filter(Boolean)
  if (parts.length <= 2) return clean
  return `…/${parts.slice(-2).join('/')}`
}

/**
 * 工具输出截断：**保头也保尾**。
 *
 * 原来的 `abbreviate(text, 4000)` 只留头部，但工具的关键结构化信息常常在尾部：
 *   - `file_writer` 的 `[TOOL_RESULT]{ "additions": 185, "deletions": 0 }`（增删行数徽章）；
 *   - `task_manager` 的 `---TASK_JSON---` + 任务数组；
 *   - `command` 的退出码统计有时在末尾。
 * 一刀切掉尾部会让卡片徽章永远显示 `+0 -0`、任务清单回放失效。
 *
 * 策略：超长时保留头 3/4 + 尾 1/4，中间插一行省略说明。
 */
export function clipToolDetail(text: string, max = 4000): string {
  const raw = String(text || '')
  if (raw.length <= max) return raw
  const tailLen = Math.min(1200, Math.floor(max * 0.3))
  const headLen = max - tailLen - 40
  const omitted = raw.length - headLen - tailLen
  return `${raw.slice(0, headLen)}\n…（中间省略 ${omitted} 字符）…\n${raw.slice(-tailLen)}`
}

/* ============================================================================
 * git 语义标签（沿用旧版 extractActionFromToolContent 的判定思路）
 * ========================================================================== */

const GIT_ACTION_MAP: Record<string, Record<string, string>> = {
  git_submit: {
    add: '暂存',
    commit: '提交',
    push: '推送',
    pull: '拉取',
    fetch: '获取远程',
    unstage: '取消暂存',
    restore: '还原改动',
    reset: '回退提交',
    revert: '撤销提交'
  },
  git_branch: { create: '创建分支', switch: '切换分支', merge: '合并分支', delete: '删除分支', list: '分支列表' },
  git_stash: { save: '储藏', pop: '恢复储藏', drop: '删除储藏', list: '储藏列表' },
  git_tag: { create: '创建标签', push: '推送标签', delete: '删除标签', list: '标签列表' },
  git_remote: { add: '添加远程', set_url: '修改远程', remove: '删除远程', list: '远程列表' },
  git_query: { status: '状态', diff: '差异', log: '日志', show: '提交详情' }
}

function gitLabel(name: string, text: string): string {
  const head = firstLine(text)
  const action = text.match(/action=(\w+)/)?.[1]
  if (action && GIT_ACTION_MAP[name]?.[action]) return GIT_ACTION_MAP[name][action]
  if (name === 'git_submit') {
    if (/暂存/.test(head)) return '暂存'
    if (/提交|commit/i.test(head)) return '提交'
    if (/推送|push/i.test(head)) return '推送'
    if (/拉取|pull/i.test(head)) return '拉取'
    if (/获取远程|fetch/i.test(head)) return '获取远程'
    if (/已还原|已取消暂存/.test(head)) return '还原改动'
    if (/反向|回退|撤销/.test(head)) return '回退提交'
  }
  if (name === 'git_query') {
    if (/文件变更状态|工作区干净/.test(text)) return '状态'
    if (/^diff --git|\+\+\+ |--- /m.test(text)) return '差异'
    if (/^commit [0-9a-f]{6,}/m.test(text)) return '提交详情'
    if (/暂无提交记录/.test(text)) return '日志'
    return '查询'
  }
  return 'Git'
}

/* ============================================================================
 * 完整解析
 * ========================================================================== */

/** 任务 JSON 提取（与 chat-mapper 的 extractJsonArray 同思路，独立实现避免模块耦合） */
function extractJsonArray(text: string): unknown[] | null {
  const start = text.indexOf('[')
  if (start === -1) return null
  let depth = 0
  let inString = false
  let escaped = false
  for (let i = start; i < text.length; i += 1) {
    const ch = text[i]
    if (inString) {
      if (escaped) escaped = false
      else if (ch === '\\') escaped = true
      else if (ch === '"') inString = false
      continue
    }
    if (ch === '"') {
      inString = true
      continue
    }
    if (ch === '[') depth += 1
    else if (ch === ']') {
      depth -= 1
      if (depth === 0) {
        try {
          const parsed = JSON.parse(text.slice(start, i + 1))
          return Array.isArray(parsed) ? parsed : null
        } catch {
          return null
        }
      }
    }
  }
  return null
}

function parseTaskModel(name: string, text: string, failed: boolean, raw: string): TaskModel {
  const marker = '---TASK_JSON---'
  let tasks: TaskRow[] = []
  const positions: number[] = []
  let idx = text.indexOf(marker)
  while (idx !== -1) {
    positions.push(idx)
    idx = text.indexOf(marker, idx + marker.length)
  }
  for (let i = positions.length - 1; i >= 0 && !tasks.length; i -= 1) {
    const parsed = extractJsonArray(text.slice(positions[i] + marker.length))
    if (!parsed) continue
    tasks = parsed
      .filter((v): v is Record<string, unknown> => !!v && typeof v === 'object')
      .map(v => ({
        id: v.id == null ? '' : String(v.id),
        title: String(v.description ?? v.title ?? ''),
        status: String(v.status ?? ''),
        priority: v.priority == null ? undefined : String(v.priority),
        group: v.group == null ? undefined : String(v.group),
        dependsOn: Array.isArray(v.depends_on)
          ? (v.depends_on as unknown[]).map(String)
          : Array.isArray(v.dependsOn)
            ? (v.dependsOn as unknown[]).map(String)
            : undefined
      }))
      .filter(t => t.id)
  }
  return { kind: 'task', name, raw, failed, summary: firstLine(text).replace(marker, '').trim(), tasks }
}

function parseFileWrite(name: string, text: string, raw: string): FileWriteModel {
  const lines = text.split('\n')
  const head = firstLine(text)
  const action: FileWriteModel['action'] = /编辑成功/.test(head)
    ? 'edit'
    : /写入成功/.test(head)
      ? 'write'
      : /已删除|删除成功/.test(head)
        ? 'delete'
        : 'other'
  const path =
    head.match(/^(?:✅\s*)?(?:写入成功|编辑成功|文件已删除|删除成功|部分失败)[:：]\s*(.+)$/)?.[1]?.trim() ||
    valueOf(lines, '路径', '文件') ||
    ''
  const block = readToolResultBlock(text)
  const meta: string[] = []
  const diff: string[] = []
  let sizeText: string | undefined
  let inDiff = false
  for (const line of lines.slice(1)) {
    const t = line.trim()
    if (!t || t.startsWith('[') || t.startsWith('{') || /^"\w+"/.test(t)) continue
    if (/^变更预览[:：]/.test(t)) {
      inDiff = true
      continue
    }
    if (inDiff) {
      if (/^(?:[-+]|\||@@)/.test(t)) diff.push(t)
      continue
    }
    if (/^大小[:：]/.test(t)) sizeText = t.replace(/^大小[:：]\s*/, '')
    else if (/^(内容长度|操作|匹配位置|行数变化|文件现总行数|编码)[:：]/.test(t)) meta.push(t)
  }
  const add = toNumber(String(block?.additions ?? '')) ?? toNumber(meta.find(m => /^行数变化/.test(m))?.match(/→\s*\+?(\d+)/)?.[1])
  const del = toNumber(String(block?.deletions ?? ''))
  return { kind: 'file-write', name, raw, failed: false, action, path, sizeText, linesAdded: add, linesDeleted: del, meta, diff }
}

function parseFileRead(name: string, text: string, raw: string): FileReadModel {
  const lines = text.split('\n')
  // `行数: 104 行 | 显示: 1 - 104` —— 总数与可见范围挤在同一行
  const lineText = valueOf(lines, '行数')
  return {
    kind: 'file-read',
    name,
    raw,
    failed: false,
    path: valueOf(lines, '路径') || '',
    encoding: valueOf(lines, '编码'),
    typeText: valueOf(lines, '类型'),
    lines: toNumber(lineText?.match(/^(\d+)/)?.[1]),
    rangeText: lineText?.match(/\|\s*(?:显示[:：]\s*)?(.+)$/)?.[1]?.trim(),
    body: afterSeparator(text)
  }
}

function parseFileTree(name: string, text: string, raw: string): FileTreeModel {
  return {
    kind: 'file-tree',
    name,
    raw,
    failed: false,
    root: valueOf(text.split('\n'), '项目目录') || '',
    outline: afterSeparator(text)
  }
}

function parseSearch(name: string, text: string, raw: string): SearchModel {
  const lines = text.split('\n')
  const isGlob = /共 \d+ 个文件/.test(text)
  const totalMatch = text.match(/匹配结果：共 (\d+)/)
  const hits: SearchHit[] = []
  let currentFile: string | undefined
  for (const line of afterSeparator(text).split('\n')) {
    const t = line.replace(/\s+$/, '')
    if (!t.trim()) continue
    // grep 命中行：`  123 > 内容` / `  120 - 上下文`
    const hitMatch = t.match(/^\s*(\d+)\s+([>-])\s(.*)$/)
    if (hitMatch) {
      hits.push({ file: currentFile, line: Number(hitMatch[1]), text: hitMatch[3] })
      continue
    }
    // glob 行：`  12.3KB   src/main/App.java`
    const globMatch = t.match(/^\s*([\d.]+\s*[KMGT]?B)\s+(.+)$/i)
    if (globMatch) {
      hits.push({ file: globMatch[2].trim(), text: globMatch[1].trim() })
      continue
    }
    if (/^\s*\.\.\.\s/.test(t)) continue
    if (/^\s*（无匹配结果）/.test(t)) continue
    // 其余视为文件分组标题
    if (!/^[【─━=]/.test(t.trim())) currentFile = t.trim()
  }
  return {
    kind: 'search',
    name,
    raw,
    failed: false,
    mode: isGlob ? 'glob' : 'grep',
    pattern: valueOf(lines, '搜索模式') || '',
    scope: valueOf(lines, '搜索目录'),
    total: totalMatch ? Number(totalMatch[1]) : undefined,
    hits: hits.slice(0, 60)
  }
}

function parseCommand(name: string, text: string, raw: string): CommandModel {
  const lines = text.split('\n')
  const head = firstLine(text)
  const service = /后台服务|服务 #\d+/.test(head)
  const cmd = valueOf(lines, '执行命令', '命令') || ''
  const exitCodeText = valueOf(lines, '退出码')
  const exitCode = exitCodeText && /^\d+$/.test(exitCodeText) ? Number(exitCodeText) : exitCodeText === '未知' ? null : undefined
  const durationRaw = valueOf(lines, '耗时')
  /**
   * 正文：分隔线之后的内容，但要剥掉「退出码 / 耗时」两行 ——
   * 它们已经在卡片头部的色标里展示过，留在正文里会让终端块开头多两行噪音。
   */
  const body = afterSeparator(text)
    .split('\n')
    .filter(line => !/^\s*(退出码|耗时)[:：]/.test(line))
    .join('\n')
    .replace(/^\n+/, '')
    .replace(/\n+$/, '')
  return {
    kind: 'command',
    name,
    raw,
    failed: exitCode != null && exitCode !== 0,
    mode: service ? 'service' : 'exec',
    cmd,
    workDir: valueOf(lines, '工作目录'),
    exitCode,
    durationText: durationRaw ? (/\d+\.\d+$/.test(durationRaw) ? `${durationRaw} 秒` : durationRaw) : undefined,
    stdout: body
  }
}

function parseWeb(name: string, text: string, raw: string): WebModel {
  const lines = text.split('\n')
  if (name === 'web_fetch') {
    const url = text.match(/^---\s*网页内容:\s*(.+?)\s*---/)?.[1] || ''
    const bodyStart = lines.findIndex(l => /^---\s*网页内容:/.test(l.trim()))
    return {
      kind: 'web',
      name,
      raw,
      failed: false,
      mode: 'fetch',
      url,
      results: [],
      body: bodyStart === -1 ? text : lines.slice(bodyStart + 1).join('\n').trim()
    }
  }
  const results: WebResult[] = []
  let current: WebResult | null = null
  for (const line of lines) {
    const t = line.trim()
    const head = t.match(/^(\d+)\.\s+(.+)$/)
    if (head && !/^链接[:：]/.test(t)) {
      current = { index: Number(head[1]), title: head[2], url: '' }
      results.push(current)
      continue
    }
    if (!current) continue
    const link = t.match(/^链接[:：]\s*(\S+)/)
    if (link) {
      current.url = link[1]
      continue
    }
    const domain = t.match(/^来源[:：]\s*(.+)$/)
    if (domain) {
      current.domain = domain[1].trim()
      continue
    }
    if (!current.snippet && t && !/^---|^（检索源状态|^检索源状态/.test(t)) current.snippet = t
  }
  return { kind: 'web', name, raw, failed: false, mode: 'search', results: results.slice(0, 12), body: '' }
}

function parseDb(name: string, text: string, raw: string): DbModel {
  const lines = text.split('\n')
  return {
    kind: 'db',
    name,
    raw,
    failed: false,
    dbLabel: valueOf(lines, '目标库'),
    summary: firstLine(text),
    body: afterSeparator(text) === text ? text : afterSeparator(text)
  }
}

function parseGit(name: string, text: string, raw: string): GitModel {
  return {
    kind: 'git',
    name,
    raw,
    failed: false,
    action: gitLabel(name, text),
    summary: firstLine(text),
    body: text.includes('\n') ? text.split('\n').slice(1).join('\n').trim() : ''
  }
}

function parseAgent(name: string, text: string, raw: string): AgentModel {
  const head = firstLine(text)
  const action = /已创建.*后台运行|已在后台运行/.test(head)
    ? '创建子任务'
    : /执行完毕|收集/.test(head)
      ? '收集结果'
      : /详情|inspect/.test(head)
        ? '查看详情'
        : /异步派发|委托已受理/.test(head)
          ? '发起委托'
          : /已完成/.test(head)
            ? '委托完成'
            : /失败/.test(head)
              ? '委托失败'
              : '子 Agent'
  return {
    kind: 'agent',
    name,
    raw,
    failed: false,
    action,
    summary: head,
    body: text.includes('\n') ? text.split('\n').slice(1).join('\n').trim() : ''
  }
}

function parseError(name: string, text: string, raw: string): ErrorModel {
  const head = firstLine(text)
  const titleMatch = head.match(/^【([^】]+)】/)
  const title = titleMatch ? titleMatch[1] : head.replace(/^❌\s*/, '').slice(0, 30)
  const message = (titleMatch ? head.replace(/^【[^】]+】\s*/, '') : head.replace(/^❌\s*/, '')).trim()
  const rest = text.includes('\n') ? text.split('\n').slice(1).join('\n').trim() : ''
  return { kind: 'error', name, raw, failed: true, title, message: [message, rest].filter(Boolean).join('\n') }
}

/**
 * 完整解析：原始「工具名 + 输出文本」→ 结构化模型。
 *
 * 解析不出来的部分一律降级到 `raw`（原文仍可展开查看），不会抛异常。
 */
export function renderToolModel(name: string, detail?: string): ToolRenderModel {
  const raw = cleanup(detail || '')
  const toolName = (name || '').trim()
  if (!raw) return { kind: 'raw', name: toolName || '工具', raw: '', failed: false }

  const head = firstLine(raw)
  if (ERROR_HEAD_RE.test(head)) return parseError(toolName || '工具', raw, raw)

  const lower = toolName.toLowerCase()
  if (lower === 'file_writer') {
    const model = parseFileWrite(toolName, raw, raw)
    // file_writer 的失败也很常见（【匹配失败】…），但已在上面的错误分支拦下
    return model
  }
  if (lower === 'file_explorer') {
    if (/^路径[:：]/.test(head)) return parseFileRead(toolName, raw, raw)
    if (/^项目目录[:：]|^【概览树】/.test(head) || /【概览树】/.test(raw.slice(0, 200))) return parseFileTree(toolName, raw, raw)
    if (/^搜索模式[:：]|匹配结果：共/.test(head) || /^搜索模式[:：]/m.test(raw)) return parseSearch(toolName, raw, raw)
    return { kind: 'raw', name: toolName, raw, failed: false }
  }
  if (lower === 'command') return parseCommand(toolName, raw, raw)
  if (lower === 'web_search' || lower === 'web_fetch') return parseWeb(lower, raw, raw)
  if (lower === 'execute_sql') return parseDb(toolName, raw, raw)
  if (lower.startsWith('git_')) return parseGit(toolName, raw, raw)
  if (lower === 'agent' || lower === 'agent_invoke') return parseAgent(toolName, raw, raw)
  if (lower === 'task_manager') return parseTaskModel(toolName, raw, false, raw)

  return { kind: 'raw', name: toolName || '工具', raw, failed: false }
}

/** 卡片标题（kind → 中文名），供组件展示 */
export const TOOL_KIND_LABEL: Record<ToolKind, string> = {
  'file-write': '文件改动',
  'file-read': '文件读取',
  'file-tree': '目录树',
  search: '搜索',
  command: '命令',
  web: '网页',
  db: '数据库',
  git: 'Git',
  agent: '子 Agent',
  task: '任务清单',
  error: '执行失败',
  raw: '工具输出'
}
