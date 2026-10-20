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
  <!--
    用户消息：气泡右对齐 + 右侧头像（与助手消息的头像位置对称）。

    ★ 附件（图片 / 文档）渲染在气泡内上方：发送时由 App.vue 直接挂到消息上（不必等刷新），
    历史消息由 loadMessageFileRefs 按 file_reference 回填。
    以前用户发的图片 / 文件在气泡里完全看不出来，只剩一句文本（甚至空气泡）。
  -->
  <div v-if="message.role === 'user'" class="cc-msg-me">
    <div class="cc-msg-me__col">
      <div v-if="message.fileAssets?.length" class="cc-msg-me__files">
        <template v-for="asset in message.fileAssets" :key="`${asset.kind}-${asset.id || asset.filename}`">
          <!-- 图片：48×48 缩略图，点击打开灯箱 -->
          <button
            v-if="asset.kind === 'image'"
            class="cc-msg-file is-image"
            :title="assetDisplayName(asset)"
            @click="previewMessageImage(asset)"
          >
            <img v-if="asset.previewUrl" :src="asset.previewUrl" :alt="asset.filename" />
            <span v-else class="cc-msg-file__ph">🖼️</span>
          </button>
          <!-- 文档：图标 + 文件名 + 大小，点击下载（旧临时通道无法下载时给出提示） -->
          <button
            v-else
            class="cc-msg-file is-doc"
            :title="assetDisplayName(asset)"
            @click="downloadMessageAsset(asset)"
          >
            <span class="cc-msg-file__icon">{{ assetDocIcon(asset) }}</span>
            <span class="cc-msg-file__name">{{ assetDisplayName(asset) }}</span>
            <span v-if="asset.size" class="cc-msg-file__size">{{ assetSizeText(asset.size) }}</span>
          </button>
        </template>
      </div>
      <div v-if="message.content" class="cc-msg-me__bubble">{{ message.content }}</div>
    </div>
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

    <!--
      思考过程 + 技能命中：思考区只承载思考文本，工具输出已移出（见下方工具调用区）。

      技能命中标签与思考按钮同一行（与经典版 thinking-header 的布局一致）：
      没有思考过程、只执行了技能时也能看到「🎯 命中技能」，不会被 v-if 一起吞掉。
    -->
    <template v-if="hasProcess || hitSkills.length">
      <div class="cc-proc">
        <button
          v-if="hasProcess"
          class="cc-proc__btn"
          :class="{ 'is-open': procOpen }"
          @click="toggleProcess"
        >
          <svg class="cc-i cc-i--sm cc-proc__caret"><use href="#i-chev" /></svg>
          {{ processLabel }}
        </button>
        <span
          v-for="name in hitSkills"
          :key="name"
          class="cc-skill-hit"
          :title="`本次已执行的技能：${hitSkills.join('、')}`"
        >🎯 {{ name }}</span>
      </div>
      <!--
        思考区是独立滚动容器（max-height 320px）：它内部自己贴底，
        与整个消息流的贴底是两套独立逻辑（外层由 ChatView 的 follow 负责）。
        wrap 只做定位基准，让「回到最新思考」按钮悬浮在思考区右下角。

        ★ v-if="procOpen"：折叠态**完全不进 DOM**。
        以前是「内容常驻 DOM + CSS display:none」：一条几万字的思考虽然看不见，
        却已经跑完 markdown 并挂了几千个节点 —— 十几个这样的历史消息就能让切会话卡死。
        经典版（CodeAssistantView）也是 `v-if="getThinkingVisible(id)"`，这里对齐。
      -->
      <div v-if="procOpen" class="cc-proc__wrap">
        <div
          ref="procBodyEl"
          class="cc-proc__body"
          :class="{ 'is-open': procOpen }"
          @scroll.passive="onProcScroll"
        >
          <div v-for="(p, i) in visibleProcess" :key="i" class="cc-proc__item">
            <span v-if="visibleProcess.length > 1 || p.tag !== '思考'" class="cc-proc__tag">{{ p.tag }}</span>
            <!-- 思考过程同样是 markdown：代码块 / ```diff 改动红绿 / 列表 / 表格都要正常渲染 -->
            <div class="cc-md cc-proc__text" v-html="processHtml(p)" />
          </div>
        </div>
        <!--
          超长思考的二次渲染门槛：默认只渲染尾部 6 万字（见 PROC_RENDER_LIMIT），
          更早的内容等用户主动点这一下 —— 展开一条 50 万字的推理不该同步卡住主线程。
        -->
        <button v-if="procTruncated" class="cc-proc__more" type="button" @click="renderFullProcess">
          渲染完整思考（共 {{ processCharCount.toLocaleString() }} 字）
        </button>
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
/* 用户消息附件：图片点开灯箱、文档走 blob 下载（<a href> 无法带鉴权头） */
import { openLightbox } from '../utils/lightbox'
import { toast } from '../utils/toast'
import {
  downloadFileAsset,
  docTypeIcon,
  docTypeOf,
  fileAssetDisplayName,
  formatAssetSize
} from '../api/file-asset'
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

/**
 * 消息气泡里展示的文件（图片 → 缩略图；文档 → 图标 chip）。
 *
 * <p>数据来源两条：本轮发送直接取输入区附件（`App.vue` 的 `attachments`）；
 * 历史回显由 `loadMessageFileRefs` 用 `GET /api/files?conversationId=X&withRefs=true`
 * 拿文件名/类型，再按 `file_reference.messageId` 映射到具体消息。</p>
 */
export interface MessageFileAsset {
  /** file_asset 主键（旧版临时通道的文档为 0：只能展示，不能下载/预览） */
  id: number
  filename: string
  displayName?: string
  mimeType?: string
  size?: number
  /** image=图片（渲染缩略图）/ doc=文档（渲染图标 + 文件名） */
  kind: 'image' | 'doc'
  /** 图片缩略图 blob URL（异步填充；文档不填） */
  previewUrl?: string
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
  /**
   * 技能命中（本次真正执行过 skill action=load 的技能名，去重）。
   *
   * <p>数据来源两条：实时流看 `tool_call_start.summaries` 的「🎯 命中技能：xxx」；
   * 历史回放看工具输出里的「【技能详情】xxx (ID:n)」（解析逻辑统一在 utils/skill-hit.ts）。</p>
   */
  hitSkills?: string[]
  /** 用户消息附带的文件资产（图片缩略图 / 文档 chip；发送时与历史回显共用同一结构） */
  fileAssets?: MessageFileAsset[]
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

/** 技能命中列表（父级解析好直接传，本组件只负责展示） */
const hitSkills = computed(() => props.message.hitSkills ?? [])

/* ---------------- 用户消息附件 ---------------- */

/** 附件显示名：优先 displayName，其次 filename */
function assetDisplayName(asset: MessageFileAsset): string {
  return fileAssetDisplayName({
    id: asset.id,
    filename: asset.filename,
    displayName: asset.displayName
  })
}

/** 文档图标（pdf / word / excel / text） */
function assetDocIcon(asset: MessageFileAsset): string {
  return docTypeIcon(docTypeOf(asset.filename, asset.mimeType))
}

/** 附件大小文案（仅文档展示；0/未知时不显示） */
function assetSizeText(size?: number): string {
  return size && size > 0 ? formatAssetSize(size) : ''
}

/** 点击图片：打开灯箱（blob URL 已在发送时或历史回显阶段准备好） */
function previewMessageImage(asset: MessageFileAsset) {
  if (!asset.previewUrl) {
    toast.info('缩略图还未加载完成，请稍后再试')
    return
  }
  openLightbox([{ src: asset.previewUrl, name: assetDisplayName(asset) }])
}

/** 点击文档：走鉴权 blob 下载（旧临时通道没有 file_asset id，只能提示） */
async function downloadMessageAsset(asset: MessageFileAsset) {
  if (!asset.id) {
    toast.info('该附件来自旧版临时通道，已无法下载', { description: asset.filename })
    return
  }
  try {
    await downloadFileAsset(asset.id, assetDisplayName(asset))
  } catch (err) {
    toast.error('下载失败', { description: err instanceof Error ? err.message : '请稍后重试' })
  }
}

/** 是否有思考过程：决定要不要渲染折叠按钮与展开区（只有技能命中时只显示标签行） */
const hasProcess = computed(() => (props.message.process?.length ?? 0) > 0)

/* ---------------- 超长思考的渲染保护 ---------------- */
/**
 * 单条思考默认最多渲染多少字符（6 万）。
 *
 * <p>为什么需要：markdown 解析 + 代码高亮都是同步 CPU 活，一条几十万字的推理
 * 一次性渲染能让主线程卡住数秒；而用户真正想看的往往只是「最近在想什么」。
 * 超过该上限时先渲染尾部，更早的部分由用户点「渲染完整思考」显式加载
 * （一次性、可预期，不会在切会话时被动触发）。</p>
 */
const PROC_RENDER_LIMIT = 60000

/**
 * 流式期间的单次渲染上限（1.5 万字）。
 *
 * <p>流式时思考区每更新一次 html，浏览器都要重建整棵子树；把上限从 6 万降到 1.5 万，
 * 单次重建成本能压到几十毫秒级。收尾（streaming=false）后自动回到 6 万字。
 * 用户仍可点「渲染完整思考」一次性看到全文。</p>
 */
const STREAM_PROC_RENDER_LIMIT = 15000

/** 当前生效的渲染上限：流式更激进，历史/收尾更宽松 */
const procRenderLimit = computed(() =>
  props.message.streaming ? STREAM_PROC_RENDER_LIMIT : PROC_RENDER_LIMIT
)

/** 用户是否已要求渲染完整思考（默认 false：只渲染尾部 procRenderLimit 字） */
const procFullRender = ref(false)

/** 思考总字数：折叠标题与「渲染完整思考」按钮都用它 */
const processCharCount = computed(() =>
  (props.message.process ?? []).reduce((n, p) => n + (p.text?.length ?? 0), 0)
)

/** 当前是否存在被省略的更早内容 */
const procTruncated = computed(
  () => !procFullRender.value && processCharCount.value > procRenderLimit.value
)

/**
 * 实际参与渲染的思考条目（尾部优先）。
 *
 * <p>从最后一条往前累计字符预算：整条放得下就原样渲染；放不下就只取尾巴，
 * 并在开头插一行说明。返回条目会清空预渲染 `html`（那是按原文生成的，截断后不能复用）。</p>
 */
const visibleProcess = computed<ProcessItem[]>(() => {
  const list = props.message.process ?? []
  if (procFullRender.value) return list
  const limit = procRenderLimit.value
  // 未超限时直接返回原数组引用：computed 命中时 Vue 不会重建 v-for 列表，DOM diff 也更轻
  if (processCharCount.value <= limit) return list
  let budget = limit
  const out: ProcessItem[] = []
  for (let i = list.length - 1; i >= 0 && budget > 0; i--) {
    const p = list[i]
    const text = p.text || ''
    if (text.length <= budget) {
      out.unshift(p)
      budget -= text.length
      continue
    }
    const omitted = text.length - budget
    out.unshift({
      ...p,
      text: `（更早的 ${omitted.toLocaleString()} 字已省略，点下方「渲染完整思考」查看）\n\n` + text.slice(-budget),
      html: ''
    })
    budget = 0
  }
  return out
})

/** 用户显式要求渲染完整思考（一次性开销，之后不再截断） */
function renderFullProcess() {
  procFullRender.value = true
}

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
  // 兜底路径同样区分流式：流式期间跳过代码高亮（最贵的一步），避免每次重渲染都跑一遍
  const raw = props.message.content || ''
  return props.message.streaming ? renderMarkdownLight(raw) : renderMarkdown(raw)
})

/**
 * 思考过程 HTML。
 * 绝大多数情况直接用父级预渲染好的 `p.html`（历史消息一次性渲染 / 流式由 App.vue 节流写入）；
 * 兜底路径（外部直接塞 text 的调用方）才在这里渲染一次，并用 Map 按原文缓存，
 * 避免流式期间每次重渲染都把整段思考重新解析一遍。
 */
const procHtmlCache = new Map<string, string>()

/**
 * 超过这个长度不再跑代码高亮。
 *
 * <p>highlight.js 是**纯同步 CPU 活**：几千行代码块在高亮上是秒级开销，
 * 而主线程在这期间完全无法响应。思考过程又常常是大段代码/JSON 的堆叠，
 * 所以宁可少一层配色，也不能让「点展开」变成几秒到几十秒的白屏。</p>
 */
const PROC_HIGHLIGHT_LIMIT = 20000

function processHtml(p: ProcessItem): string {
  if (p.html) return p.html
  const raw = p.text || ''
  if (!raw) return ''
  const hit = procHtmlCache.get(raw)
  if (hit !== undefined) return hit
  /*
    流式期间 & 超长历史思考都走轻量版（跳过代码高亮）：
    前者是为了降低每帧重建成本，后者是为了避免「展开那一下」把主线程锁死。
  */
  const heavy = !props.message.streaming && raw.length <= PROC_HIGHLIGHT_LIMIT
  const html = heavy ? renderMarkdown(raw) : renderMarkdownLight(raw)
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
