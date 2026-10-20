/**
 * 聊天页数据映射（L4 接线）
 * ----------------------------------------------------------------------------
 * 职责：把「后端数据 / SSE 事件」翻译成 ChatView 系列组件消费的模型，
 * 让 App.vue 只关心时序（请求 → 追加 → 收尾），不重复写解析逻辑。
 *
 * 三块映射：
 *  1) {@link toChatMessages}           历史消息（DB）        → ChatMessageModel[]
 *  2) {@link parseTaskManagerOutput}   task_manager 工具输出 → TaskItem[]
 *  3) {@link toApprovalPayload}        ask_user SSE 事件     → ApprovalPayload
 *
 * 历史消息的「思考 / 工具调用」是分段存储的（assistant 多段 + tool 行），
 * 复用 {@link processMessageGroups} 把它们合并回「一条 AI 消息」后再映射。
 *
 * 展示分工（v3 调整）：
 *   - `process` 只承载思考文本，工具输出不再混入（几十上百次工具调用会把折叠区塞爆）；
 *   - `tools`   独立成区，由 ChatMessage 折叠展示（>3 个默认收起为一行汇总）；
 *   - `contentHtml` 在映射时一次性渲染完整 markdown（含代码高亮 / 表格 / diff）。
 */
import type { ChatMessageModel, ProcessItem, ToolCallItem } from '../components/ChatMessage.vue'
import type { TaskItem } from './task-list'
import { normalizeDepends, normalizePriority, normalizeStatus } from './task-list'
import type { ApprovalPayload } from '../views/ChatView.vue'
import { processMessageGroups } from '../api/conversation'
import type { MessageResponse } from '../api/conversation'
import { renderMarkdown, renderMarkdownLight } from './markdown'
import { clipToolDetail } from './tool-render'

/**
 * 历史消息正文的代码高亮上限：超过就只渲染 markdown 结构、跳过 highlight.js。
 *
 * <p>完整版高亮是同步 CPU 活；一页会话里几条超长回复叠加，会让「切会话」明显卡顿。
 * 结构（段落/列表/表格/代码块）仍然完整，只是少了代码配色。</p>
 */
const HISTORY_HIGHLIGHT_LIMIT = 20000
/* 技能命中回放：与实时流共用同一套解析（skill load 的工具输出固定带「【技能详情】xxx (ID:n)」） */
import { collectSkillHits } from './skill-hit'

/** ask_user SSE 事件载荷（与 sse-client 的联合类型成员一致，此处独立声明避免循环依赖） */
export interface AskUserEventData {
  uuid: string
  question: string
  askType?: string
  toolName?: string
  filePath?: string
  fullDetail?: string
}

/** 工具卡片展开后的最大长度（避免一条消息撑爆 DOM） */
const TOOL_DETAIL_LIMIT = 4000
/**
 * 截断长文本（保留头部，尾部加省略号）。
 * 只用于「折叠态摘要」，不影响工具卡片 detail 与正文。
 */
export function abbreviate(text: string, max: number): string {
  const flat = text.replace(/\r\n/g, '\n').trim()
  if (flat.length <= max) return flat
  return `${flat.slice(0, max)}…`
}

/** 从工具结果文本提取工具名：旧格式首行形如 `> **file_writer**` */
function extractToolName(text: string): string {
  const m = text.match(/^>\s*\*\*(.+?)\*\*/)
  return m ? m[1].trim() : ''
}

/**
 * 工具结果 → 一行摘要。
 * 跳过 `> **tool**` 标题行与分隔线，取第一条有意义的正文行。
 */
function summarizeTool(text: string): string {
  const line = text
    .split('\n')
    .map(s => s.trim())
    .find(s => s && !/^>\s*\*\*/.test(s) && !/^[-─—=]{4,}$/.test(s))
  if (!line) return ''
  return line.length > 80 ? `${line.slice(0, 80)}…` : line
}

/**
 * 生成该会话回复的 Agent 身份（名称 + 头像）。
 *
 * <p>历史消息表里不存「哪条 AI 回复来自哪个 Agent」，只能按会话所属的 `agentConfigId`
 * 反查 Agent 配置。多会话并行后，后台会话可能属于另一个 Agent，因此不能用「当前选中的
 * Agent」一把梭，否则会把别人的头像挂到这条回复上。</p>
 */
export interface ChatAgentMeta {
  name?: string
  avatar?: string
}

/**
 * 历史消息（DB 分页接口返回）→ ChatView 消息模型。
 *
 * @param messages 后端 `MessageResponse[]`（按 id 升序）
 * @param model    当前模型名，仅用于给 AI 消息打标（历史消息本身不存模型）
 * @param agent    该会话所属 Agent 的名称/头像（缺省时不填，由组件用「当前 Agent」兜底）
 */
export function toChatMessages(
  messages: MessageResponse[],
  model?: string,
  agent?: ChatAgentMeta
): ChatMessageModel[] {
  const groups = processMessageGroups(messages) as any[]

  /**
   * 轮次 id 传递：后端只给 user 消息写了 `turn_id`（assistant / tool 行为空），
   * 而「回滚本轮改动」按钮挂在 AI 消息的操作区上，需要它来匹配快照。
   * 这里顺序遍历时把最近一条 user 的 turnId 传给其后的 assistant 消息。
   */
  let lastUserTurnId: string | undefined

  return groups.map(group => {
    const toolResults: Array<{ content?: string; toolName?: string }> = Array.isArray(group.toolResults)
      ? group.toolResults
      : []
    const thinking: string = typeof group.thinking === 'string' ? group.thinking : ''

    const tools: ToolCallItem[] = toolResults
      .filter(tr => (tr.content || '').trim().length > 0)
      .map(tr => {
        const content = tr.content || ''
        return {
          name: tr.toolName || extractToolName(content) || '工具',
          summary: summarizeTool(content),
          // 保头也保尾：尾部常带 `[TOOL_RESULT]`（增删行数）与 `---TASK_JSON---`（任务清单）
          detail: clipToolDetail(content, TOOL_DETAIL_LIMIT),
          state: 'done' as const
        }
      })

    /**
     * 任务清单快照：用**未截断**的工具输出原文解析。
     *
     * tools[].detail 截断到 4000 字符，长输出会把尾部的 `---TASK_JSON---` 切掉，
     * 导致历史回放解析失败（实测真实会话里出现「只捞到几百轮之前的旧清单」）。
     * 一段输出里可能多次调用 task_manager，取最后一个能解析的（最新的进度）。
     */
    let taskSnapshot: NonNullable<ChatMessageModel['taskSnapshot']> | undefined
    for (const tr of toolResults) {
      const raw = tr.content || ''
      if (!raw.includes('---TASK_JSON---')) continue
      const parsed = parseTaskManagerOutput(raw)
      if (parsed) taskSnapshot = parsed
    }

    // 思考与工具调用彻底分离：process 只放思考全文（工具在 tools 区独立展示）
    // 思考不截断 —— 折叠区默认收起，用户主动展开就是要看完整推理过程；
    // 展示高度由 CSS 的 max-height + overflow 控制，不会撑爆消息流。
    /*
      ★ 思考的 HTML 改为**懒渲染**（这里只留原文）。
      以前在映射阶段对每条消息都跑一次 renderMarkdown(thinking)：一条长思考动辄几万字，
      一页会话十几条就是几十万字 markdown，同步解析直接把主线程占满 ——
      表现就是「点开老会话卡死好几秒、滚动发涩」。
      现在交给 ChatMessage 在用户展开时按需渲染（`processHtml` 内有按原文的缓存），
      折叠态连 DOM 都不创建；与经典版「思考折叠 + 点击才渲染」完全一致。
    */
    const process: ProcessItem[] = thinking ? [{ tag: '思考', text: thinking }] : []

    /*
      技能命中回放：刷新页面 / 切换会话后，summaries 那条实时信号已经没了，
      只能从工具输出原文里反解「【技能详情】xxx (ID:n)」。
      用未截断的 content：tools[].detail 有 4000 字上限，标记可能正好被切掉。
    */
    const hitSkills = collectSkillHits(toolResults.map(tr => tr.content))

    const content = typeof group.content === 'string' ? group.content : ''
    const isUser = group.role === 'user'

    const turnId = isUser ? group.turnId || undefined : group.turnId || lastUserTurnId
    if (isUser) lastUserTurnId = turnId

    return {
      id: String(group.id),
      role: isUser ? 'user' : 'assistant',
      content,
      /*
        历史消息一次性渲染 markdown；用户消息保持纯文本气泡。
        超过 2 万字改走轻量版（跳过代码高亮）：完整版是同步秒级开销，
        几条超长回复叠加会让「切会话」明显卡顿。
      */
      contentHtml: isUser
        ? undefined
        : content.length > HISTORY_HIGHLIGHT_LIMIT
          ? renderMarkdownLight(content)
          : renderMarkdown(content),
      model: isUser ? undefined : model,
      // Agent 身份落到消息上：否则刷新/切回会话后，消息头会从「圆圆」退化成兜底的「编码助手」
      agentName: isUser ? undefined : agent?.name || undefined,
      agentAvatar: isUser ? undefined : agent?.avatar || undefined,
      process: process.length ? process : undefined,
      processLabel: thinking ? `思考 ${thinking.length} 字` : undefined,
      // 技能命中（回放）：ChatMessage 在思考区横栏显示「🎯 xxx」
      hitSkills: hitSkills.length ? hitSkills : undefined,
      tools: tools.length ? tools : undefined,
      // 任务清单快照（历史回放用）：App.vue restoreTasksFromMessages 优先取它
      taskSnapshot,

      // 轮次 id：回滚按钮据此匹配代码快照（见 App.vue matchSnapshotsForMessages）
      turnId
    }
  })
}

/**
 * 从任意文本里抠出第一个**括号配平**的 JSON 数组字面量。
 *
 * 为什么不能直接 `JSON.parse(marker 之后的全部文本)`：
 * 一个工具输出块里可能连着出现多次 `---TASK_JSON---`（模型的思考里引用、或一轮里多次调用
 * task_manager），marker 之后往往还跟着后续文本。实测真实会话里 7 段带 marker 的输出只有
 * 1 段能整段 parse 成功，其余全部因尾部杂质失败 —— 于是历史回放只能捞到很早以前的旧清单。
 *
 * 实现：从 `[` 起做深度扫描，同时跳过字符串内部的括号（处理 `"` 与 `\"` 转义）。
 * 匹配到深度归零即为数组结尾，天然容忍后面的任何杂质。
 */
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

/**
 * 原始任务数组 → 前端 TaskItem[]。
 *
 * <p>状态映射走 {@link normalizeStatus}（completed|done → done；in_progress|doing → doing；
 * failed|error → todo + failed 标记），并保留 `priority` / `depends_on` / `group` ——
 * v3 早期把这三个字段丢了，界面上就看不出「哪个任务紧急」「哪个任务在被谁卡住」。</p>
 */
function toTaskItems(parsed: unknown[]): TaskItem[] {
  const items: TaskItem[] = []
  for (const raw of parsed) {
    if (!raw || typeof raw !== 'object') continue
    const obj = raw as Record<string, unknown>
    const id = obj.id == null ? '' : String(obj.id)
    if (!id) continue
    const statusRaw = obj.status == null ? '' : String(obj.status)
    const failed = obj.failed === true || /fail|error/i.test(statusRaw)
    items.push({
      id,
      title: String(obj.description ?? obj.title ?? ''),
      status: normalizeStatus(statusRaw),
      priority: normalizePriority(obj.priority),
      dependsOn: normalizeDepends(obj.depends_on ?? obj.dependsOn),
      group: obj.group == null ? undefined : String(obj.group),
      failed: failed || undefined
    })
  }
  return items
}

/**
 * 解析 task_manager 工具输出里的结构化任务清单。
 *
 * 后端在输出尾部附 `---TASK_JSON---` + JSON 数组（元素含 id/description/status/priority/depends_on）。
 * 拿不到结构化清单时返回 null，由调用方保留上一次的任务清单，避免把已有进度清空。
 *
 * 健壮性：marker 可能在一段文本里出现多次（模型思考里引用过这个标记），因此**从后往前**
 * 逐个 marker 尝试，取第一个能解析出数组的结果 —— 越靠后越可能是真正的工具输出尾部。
 */
export function parseTaskManagerOutput(content: string): TaskItem[] | null {
  const marker = '---TASK_JSON---'
  if (!content || !content.includes(marker)) return null

  const positions: number[] = []
  let idx = content.indexOf(marker)
  while (idx !== -1) {
    positions.push(idx)
    idx = content.indexOf(marker, idx + marker.length)
  }

  for (let i = positions.length - 1; i >= 0; i -= 1) {
    const tail = content.slice(positions[i] + marker.length)
    const parsed = extractJsonArray(tail)
    if (!parsed) continue
    const items = toTaskItems(parsed)
    if (items.length) return items
  }
  return null
}

/**
 * ask_user 事件 → 审批卡片模型。
 *
 * askType 语义（后端 DeepSeekServiceImpl）：
 *  - `permission`        工具授权（approve / approve_all / reject / custom）
 *  - `clarification`     向用户提问（必须文本回答）
 *  - `iteration_confirm` 任务迭代确认（继续 +N 次 / 结束）
 *
 * 注意：question 是 **markdown 原文**（后端会拼工具详情、文件 diff 片段），
 * 这里不能截断，否则代码块被切断后整段渲染会错乱；展示高度由卡片 CSS 控制。
 * 渲染交给 ChatApproval（v-html + renderMarkdown）。
 */
export function toApprovalPayload(data: AskUserEventData, from?: string): ApprovalPayload {
  const askType = data.askType || 'clarification'
  const title =
    askType === 'permission'
      ? '需要确认：执行工具'
      : askType === 'iteration_confirm'
        ? '任务迭代确认'
        : 'AI 想确认一个问题'

  const command = [data.toolName, data.filePath].filter(Boolean).join('  ')

  return {
    title,
    description: (data.question || '').replace(/\r\n/g, '\n').trim(),
    command: command || undefined,
    from,
    askType
  }
}
