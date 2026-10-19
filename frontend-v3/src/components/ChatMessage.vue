<!--
  聊天消息（v3）
  ----------------------------------------------------------------------------
  两种形态：
    user      —— 浅紫气泡、右对齐、纯文本（保留换行）
    assistant —— 全宽无气泡：头部（头像 + 名称 + 模型标签）
                 → 思考折叠（只放思考，不再混入工具输出）
                 → 工具调用区（独立区域，默认折叠成一行汇总）
                 → markdown 正文 → 操作

  正文渲染：`contentHtml`（由 utils/markdown 渲染，v-html 输出）；
           没有则组件内兜底调用 renderMarkdown(message.content)。

  数据接缝：
    message.content      -> 后端 markdown 原文（复制、重新生成都用它）
    message.contentHtml  -> 前端渲染后的 HTML（流式期间用轻量版，收尾后完整版）
    message.process      -> 思考过程（仅思考，工具调用不在此处）
    message.tools        -> 工具调用明细（独立展示，可展开看参数与结果）
    message.turnId       -> 轮次 id：回滚按钮按它匹配代码快照、重新生成按它定位删除范围
    message.snapshotId   -> 本轮代码快照 id（有值才出现「回滚改动」按钮）

  ★ 操作区（hover 出现）三件套：复制 / 重新生成 / 回滚改动。
    「回滚改动」从旧项目的用户消息头部搬到这里，与另外两个操作放在同一排，
    破坏性操作会先出预览 + 二次确认（逻辑在 App.vue 的 onRollbackMessage）。
-->
<template>
  <!-- 用户消息：气泡右对齐 + 右侧头像（与助手消息的头像位置对称） -->
  <div v-if="message.role === 'user'" class="cc-msg-me">
    <div class="cc-msg-me__bubble">{{ message.content }}</div>
    <span class="cc-msg-me__avatar" :title="userName || '我'">
      <img v-if="userAvatarIsImage" :src="userAvatar" :alt="userName || '我'" />
      <template v-else>{{ userAvatarText }}</template>
    </span>
  </div>

  <!-- 助手消息 -->
  <div v-else class="cc-msg-ai">
    <div class="cc-msg-ai__head">
      <span class="cc-msg-ai__avatar" :title="message.agentName || '编码助手'">
        <img v-if="aiAvatarIsImage" :src="aiAvatarRaw" :alt="message.agentName || 'AI'" />
        <template v-else>{{ aiAvatarText }}</template>
      </span>
      <span>{{ message.agentName || '编码助手' }}</span>
      <span v-if="message.model" class="cc-tag cc-tag--accent">{{ message.model }}</span>
      <span v-if="message.streaming" style="color: var(--text-4)">生成中…</span>
    </div>

    <!-- 思考过程：只承载思考文本，工具输出已移出（见下方工具调用区） -->
    <template v-if="message.process && message.process.length">
      <div class="cc-proc">
        <button class="cc-proc__btn" :class="{ 'is-open': procOpen }" @click="toggleProcess">
          <svg class="cc-i cc-i--sm cc-proc__caret"><use href="#i-chev" /></svg>
          {{ processLabel }}
        </button>
      </div>
      <!--
        思考区是独立滚动容器（max-height 320px）：它内部自己贴底，
        与整个消息流的贴底是两套独立逻辑（外层由 ChatView 的 follow 负责）。
        wrap 只做定位基准，让「回到最新思考」按钮悬浮在思考区右下角。
      -->
      <div class="cc-proc__wrap">
        <div
          ref="procBodyEl"
          class="cc-proc__body"
          :class="{ 'is-open': procOpen }"
          @scroll.passive="onProcScroll"
        >
          <div v-for="(p, i) in message.process" :key="i" class="cc-proc__item">
            <span v-if="message.process.length > 1 || p.tag !== '思考'" class="cc-proc__tag">{{ p.tag }}</span>
            <!-- 思考过程同样是 markdown：代码块 / ```diff 改动红绿 / 列表 / 表格都要正常渲染 -->
            <div class="cc-md cc-proc__text" v-html="processHtml(p)" />
          </div>
        </div>
        <!-- 用户上滑查看更早思考时出现；点一下恢复自动跟随（对齐旧项目 think-scroll-btn） -->
        <button
          v-if="procOpen && procShowJump"
          class="cc-proc__jump"
          type="button"
          title="回到最新思考"
          @click="procScrollToBottom"
        >
          <svg class="cc-i cc-i--sm"><use href="#i-chev" /></svg>
        </button>
      </div>
    </template>

    <!-- 工具调用：独立区域（默认折叠，避免几十上百次调用撑爆消息流） -->
    <div v-if="tools.length" class="cc-tools">
      <button class="cc-tools__btn" :class="{ 'is-open': toolsOpen }" @click="toggleTools">
        <svg class="cc-i cc-i--sm cc-proc__caret"><use href="#i-chev" /></svg>
        <svg class="cc-i cc-i--sm"><use href="#i-term" /></svg>
        <span>{{ toolsLabel }}</span>
      </button>

      <div class="cc-tools__body" :class="{ 'is-open': toolsOpen }">
        <div
          v-for="(tool, i) in tools"
          :key="i"
          class="cc-tool-card"
          :class="{ 'is-open': openTools.has(i) }"
        >
          <button class="cc-tool-card__head" @click="toggleTool(i)">
            <svg class="cc-i cc-i--sm cc-proc__caret" :style="{ transform: openTools.has(i) ? 'rotate(90deg)' : '' }">
              <use href="#i-chev" />
            </svg>
            <span class="cc-tool-card__name">{{ tool.name }}</span>
            <!--
              折叠态摘要：工具名只是技术标识，用户要看的是「做了什么」——
              `file_writer` → 「写入 · …/utils/x.ts  +185 -0」，
              `command` → 「执行命令 · npm run build  退出码 0」。
              解析只走 toolHeadline（前几行 + TOOL_RESULT 徽章），不碰全文。
            -->
            <span class="cc-tool-card__summary" :title="tool.summary || undefined">
              {{ headlines[i]?.label || tool.summary }}
              <template v-if="headlines[i]?.target"> · {{ headlines[i]!.target }}</template>
            </span>
            <span
              v-if="headlines[i]?.badge"
              class="cc-tool-card__badge"
              :class="`is-${headlines[i]!.tone || 'muted'}`"
            >{{ headlines[i]!.badge }}</span>
            <span class="cc-tool-card__state" :class="stateClass(tool.state)">
              {{ stateText(tool.state) }}
            </span>
          </button>
          <!-- 展开态：结构化卡片（按工具类型渲染文件改动 / 终端块 / 命中列表 …） -->
          <div v-if="openTools.has(i)" class="cc-tool-card__body is-card">
            <ToolResultCard :name="tool.name" :detail="tool.detail" />
          </div>
        </div>
      </div>
    </div>

    <!-- 正文（markdown） -->
    <div class="cc-msg-ai__body" :class="{ 'is-streaming': message.streaming }">
      <div class="cc-md" v-html="renderedContent" />
      <span v-if="message.streaming" class="cc-caret" />
    </div>

    <!-- 悬浮操作（复制 / 重新生成 / 回滚本轮改动） -->
    <div v-if="!message.streaming" class="cc-msg-ai__actions">
      <button class="cc-icon-btn cc-icon-btn--sm" title="复制" @click="$emit('copy', message)">
        <svg class="cc-i cc-i--sm"><use href="#i-copy" /></svg>
      </button>
      <button class="cc-icon-btn cc-icon-btn--sm" title="重新生成" @click="$emit('regenerate', message)">
        <svg class="cc-i cc-i--sm"><use href="#i-refresh" /></svg>
      </button>
      <!--
        回滚本轮改动：本轮产生过代码快照时才出现（snapshotId 由 App.vue 按 turnId 匹配快照写入）。
        做成「图标 + 文字」而非纯图标：破坏性操作需要一眼能看懂，不靠猜图标含义。
      -->
      <button
        v-if="message.snapshotId"
        class="cc-msg-ai__rollback"
        title="把这一轮改过的文件恢复到修改前的状态"
        @click="$emit('rollback', message)"
      >
        <svg class="cc-i cc-i--sm"><use href="#i-undo" /></svg>
        回滚改动
      </button>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, nextTick, ref, watch } from 'vue'
import { renderMarkdown, renderMarkdownLight } from '../utils/markdown'
import { isImageAvatar, avatarFallback } from '../utils/avatar'
import { toolHeadline } from '../utils/tool-render'
import type { TaskItem } from '../utils/task-list'
import ToolResultCard from './ToolResultCard.vue'

export interface ProcessItem {
  /** 标签：目前只有「思考」，保留字段便于后续扩展（如子 Agent 事件） */
  tag: string
  text: string
  /**
   * 预渲染好的 HTML（可选，优先于 text）。
   * 历史消息由 chat-mapper 一次性渲染；流式消息由 App.vue 节流写入
   * （过程用轻量版、收尾用完整版），避免组件里对几万字思考反复跑 markdown。
   */
  html?: string
}

export interface ToolCallItem {
  name: string
  summary: string
  /** 展开后的参数/结果文本 */
  detail?: string
  state?: 'done' | 'running' | 'failed'
}

export interface ChatMessageModel {
  id: string
  role: 'user' | 'assistant'
  content: string
  /** 前端渲染后的 HTML（可选，优先于 content） */
  contentHtml?: string
  agentName?: string
  model?: string
  streaming?: boolean
  /** 过程摘要（折叠展示，只含思考） */
  process?: ProcessItem[]
  /** 过程折叠标题，如「思考 1234 字」 */
  processLabel?: string
  tools?: ToolCallItem[]
  /**
   * 轮次 id（后端 turn_id，只有 user 消息落库时有值，assistant 继承前一条 user 的）。
   * 用途：① 回滚按钮按它匹配代码快照；② 重新生成时按它删除该轮及其后的消息。
   */
  turnId?: string
  /**
   * 本轮的代码快照 id（由 App.vue 用 listSnapshots 按 turnId 匹配后写入）。
   * 有值 = 本轮改过文件且尚未回滚 → 操作区出现「回滚改动」。
   */
  snapshotId?: string
  /**
   * 本条回复携带的 task_manager 任务清单快照（历史回放用）。
   *
   * 为什么挂在消息上：任务清单没有独立落库，只存在于工具输出的 `---TASK_JSON---` 段里，
   * 而工具卡片展示用的 `tools[].detail` 会被截断，长输出会把尾部的 JSON 切掉。
   * 所以在 chat-mapper 里用**未截断的原文**解析一次并挂在这里，App.vue 回放时直接取用。
   *
   * 类型来自 utils/task-list（含 priority / dependsOn / failed，F12），
   * 该模块不依赖任何组件，不会与 ChatView 构成循环引用。
   */
  taskSnapshot?: TaskItem[]

  /**
   * 生成本条回复的 Agent 头像（图片 URL / emoji / 单字）。
   * 记录在消息上而不是统一取「当前 Agent」：多会话并行后，后台会话可能属于另一个 Agent，
   * 用当前 Agent 兜底会把别人的头像挂在它的回复上。
   */
  agentAvatar?: string
}

const props = withDefaults(
  defineProps<{
    message: ChatMessageModel
    /** 当前用户头像（图片 URL / emoji / 单字），渲染在用户气泡右侧 */
    userAvatar?: string
    /** 当前用户昵称（无头像时取首字） */
    userName?: string
    /** 当前 Agent 头像：消息自身没带 agentAvatar 时兜底 */
    agentAvatar?: string
  }>(),
  {
    userAvatar: '',
    userName: '',
    agentAvatar: ''
  }
)

/** 用户头像：图片走 <img>，其余（emoji / 单字）直接显示文本 */
const userAvatarIsImage = computed(() => isImageAvatar(props.userAvatar))
const userAvatarText = computed(() => avatarFallback(props.userName || '我', '我'))

/** AI 头像：消息级优先，其次当前 Agent，最后退回 "AI" 字样 */
const aiAvatarRaw = computed(() => props.message.agentAvatar || props.agentAvatar || '')
const aiAvatarIsImage = computed(() => isImageAvatar(aiAvatarRaw.value))
const aiAvatarText = computed(() =>
  aiAvatarRaw.value && !aiAvatarIsImage.value
    ? aiAvatarRaw.value
    : avatarFallback(props.message.agentName, 'AI')
)

const emit = defineEmits<{
  (e: 'copy', message: ChatMessageModel): void
  (e: 'regenerate', message: ChatMessageModel): void
  /** 回滚本轮代码改动（App.vue 负责 preview → 二次确认 → execute） */
  (e: 'rollback', message: ChatMessageModel): void
  /**
   * 用户主动展开「思考过程 / 工具调用」：说明他在关注最新进展，
   * 父级据此恢复「跟随底部」，否则展开后新增内容会被当成「用户在读历史」而不再贴底。
   */
  (e: 'expand', kind: 'process' | 'tools'): void
}>()

/**
 * 思考折叠默认收起（信息密度优先）；展开时通知父级恢复跟随
 */
const procOpen = ref(false)

/* ---------------- 思考区内部滚动：始终贴底，除非用户主动上滑 ---------------- */
/**
 * 思考区是 `max-height: 320px; overflow: auto` 的独立滚动容器，
 * 内容一长就会把「最新思考」顶到看不见的位置。旧项目用
 * `thinkAutoScroll` + `handleThinkScroll` 解决：内容增长时写
 * `scrollTop = scrollHeight`，用户一旦上滑（距底 > 30px）就停止跟随，
 * 并浮出「回到底部」按钮。这里按同一套语义重做。
 */
const procBodyEl = ref<HTMLElement | null>(null)
/** 是否跟随思考区内部滚动（距底 < PROC_STICK_GAP 视为仍在跟随） */
const procAutoScroll = ref(true)
/** 是否显示「回到最新思考」按钮（用户上滑离开底部时为 true） */
const procShowJump = ref(false)

/** 思考区距底阈值（px）：与旧项目 handleThinkScroll 的 30px 对齐 */
const PROC_STICK_GAP = 30

/** 思考区滚动监听：只记录流式消息的滚动意图（历史消息允许从头读，不弹按钮） */
function onProcScroll() {
  if (!props.message.streaming) return
  const el = procBodyEl.value
  if (!el) return
  const gap = el.scrollHeight - el.scrollTop - el.clientHeight
  const atBottom = gap < PROC_STICK_GAP
  procAutoScroll.value = atBottom
  procShowJump.value = !atBottom
}

/**
 * 把思考区滚到最新处（只滚内部容器，不动整个消息流）。
 *
 * <p>思考内容是 markdown 80ms 节流渲染的，`nextTick` 之后高度还会继续涨，
 * 所以补一帧再追平一次，否则会稳定差最后几十像素。</p>
 */
async function procScrollToBottom() {
  procAutoScroll.value = true
  procShowJump.value = false
  await nextTick()
  const el = procBodyEl.value
  if (el) el.scrollTop = el.scrollHeight
  requestAnimationFrame(() => {
    const cur = procBodyEl.value
    if (cur && procAutoScroll.value) cur.scrollTop = cur.scrollHeight
  })
}

/**
 * 思考内容增长 → 思考区内部贴底。
 *
 * <p>三个前置条件缺一不可：已展开（否则容器 display:none，scrollTop 无意义）、
 * 仍在跟随态（用户上滑后不再拽回）、消息仍在流式（历史消息不自动滚，方便从头读）。</p>
 */
watch(
  () => (props.message.process ?? []).reduce((n, p) => n + (p.text?.length ?? 0), 0),
  () => {
    if (!procOpen.value || !procAutoScroll.value) return
    void procScrollToBottom()
  }
)

function toggleProcess() {
  procOpen.value = !procOpen.value
  if (!procOpen.value) return
  // 展开：恢复跟随态；流式消息直接跳到最新思考（历史消息保持从头读）
  procAutoScroll.value = true
  procShowJump.value = false
  if (props.message.streaming) void procScrollToBottom()
  emit('expand', 'process')
}
/** 记录哪些工具卡片处于展开态 */
const openTools = ref<Set<number>>(new Set())

const tools = computed(() => props.message.tools ?? [])

/**
 * 折叠态摘要（每个工具一条）。
 *
 * 为什么不直接在模板里调 `toolHeadline(...)`：模板每次重渲染都会重新调用，
 * 一个会话里工具可能有上百条、流式期间每帧都在重算。这里用 computed 收敛成
 * 一次数组计算（toolHeadline 内部还有 LRU 缓存，流式追加时只有新条目需要解析）。
 */
const headlines = computed(() => tools.value.map(t => toolHeadline(t.name, t.detail)))

/**
 * 工具区默认折叠（无论几次调用）：
 * 工具调用属过程信息、正文才是结果；几十上百次调用全铺开会把消息流撑爆。
 * 首次出现工具时保持折叠，之后保留用户手动展开的选择，不被流式更新覆盖。
 */
const toolsOpen = ref(false)
watch(
  () => tools.value.length,
  (next, prev) => {
    // 从「无工具」变为「有工具」时重置为折叠（重新生成会复用同一组件实例）
    if (prev === 0 && next > 0) toolsOpen.value = false
  }
)

function toggleTools() {
  toolsOpen.value = !toolsOpen.value
  // 展开工具明细 → 用户在关注最新进展，恢复跟随（否则新增内容不再贴底）
  if (toolsOpen.value) emit('expand', 'tools')
}

/** 思考折叠标题：父级给了字数就用它，否则退回通用文案 */
const processLabel = computed(() => props.message.processLabel || '思考过程')

const toolsLabel = computed(() => {
  const total = tools.value.length
  if (!total) return ''
  const running = tools.value.filter(t => t.state === 'running').length
  const failed = tools.value.filter(t => t.state === 'failed').length
  const suffix = running ? ` · ${running} 个执行中` : failed ? ` · ${failed} 个失败` : ''
  return `${total} 个工具调用${suffix}`
})

/**
 * 正文 HTML：优先用父级渲染好的 contentHtml（流式期间由 App.vue 节流更新），
 * 没有则组件内兜底渲染一次，保证任何调用方都能正确显示 markdown。
 */
const renderedContent = computed(() => {
  if (props.message.contentHtml) return props.message.contentHtml
  return renderMarkdown(props.message.content || '')
})

/**
 * 思考过程 HTML。
 * 绝大多数情况直接用父级预渲染好的 `p.html`（历史消息一次性渲染 / 流式由 App.vue 节流写入）；
 * 兜底路径（外部直接塞 text 的调用方）才在这里渲染一次，并用 Map 按原文缓存，
 * 避免流式期间每次重渲染都把整段思考重新解析一遍。
 */
const procHtmlCache = new Map<string, string>()

function processHtml(p: ProcessItem): string {
  if (p.html) return p.html
  const raw = p.text || ''
  if (!raw) return ''
  const hit = procHtmlCache.get(raw)
  if (hit !== undefined) return hit
  // 流式期间跳过代码高亮（最贵的一步），收尾后父级会给完整版 html
  const html = props.message.streaming ? renderMarkdownLight(raw) : renderMarkdown(raw)
  if (procHtmlCache.size > 20) procHtmlCache.clear()
  procHtmlCache.set(raw, html)
  return html
}

function toggleTool(index: number) {
  const next = new Set(openTools.value)
  if (next.has(index)) next.delete(index)
  else next.add(index)
  openTools.value = next
  // 展开单个工具卡片同样视为「在关注最新进展」
  if (next.has(index)) emit('expand', 'tools')
}

function stateText(state?: ToolCallItem['state']): string {
  if (state === 'failed') return '失败'
  if (state === 'running') return '执行中'
  return '已完成'
}

function stateClass(state?: ToolCallItem['state']): string {
  if (state === 'failed') return 'is-failed'
  if (state === 'running') return 'is-running'
  return 'is-done'
}
</script>
