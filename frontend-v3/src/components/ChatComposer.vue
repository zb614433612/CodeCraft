<!--
  聊天输入区（v2 Composer）
  ----------------------------------------------------------------------------
  设计目标：只保留 4 个常驻控件 —— 附件（+）/ 输入框 / 模型 / 发送（生成中变停止）。
  低频信息（上下文 Token、余额）降级为右下角 12px 极浅小字，不占控件位。
  数据接缝：
    v-model            -> 输入文本
    model / models     -> 模型选择（来自 Provider 接口）
    attachments        -> 已上传附件（来自文件上传接口）
    running            -> 是否有任务在跑（true 时显示停止按钮并禁用输入）
    contextTokens / balance -> 来自会话统计与余额接口
-->
<template>
  <div class="cc-composer-wrap">
    <div class="cc-composer">
      <!-- 附件 chips：图片显示缩略图（可点开大图），文档保留图标 + 文件名 -->
      <div v-if="attachments.length" class="cc-attachments">
        <span
          v-for="att in attachments"
          :key="att.id"
          class="cc-attach"
          :class="{ 'is-image': att.isImage && att.previewUrl }"
        >
          <button
            v-if="att.isImage && att.previewUrl"
            class="cc-attach__thumb"
            :title="`查看大图：${att.name}`"
            @click="previewAttachment(att)"
          >
            <img :src="att.previewUrl" :alt="att.name" />
          </button>
          <template v-else>
            <span>{{ att.icon || '📎' }}</span>
            <span class="cc-attach__name">{{ att.name }}</span>
            <span v-if="att.size" class="cc-attach__size">{{ att.size }}</span>
          </template>
          <button class="cc-attach__remove" title="移除" @click="$emit('remove-attachment', att.id)">×</button>
        </span>
      </div>

      <!-- 优化状态条：优化是同步等 LLM 返回（3~10 秒，最长 30 秒兜底），
           不给反馈的话用户只会觉得「点了没反应」，于是连点好几次打爆接口。
           优化中显示旋转图标 + 已耗时；完成后同一位置变成「撤销」入口。 -->
      <div v-if="optimizing || undoAvailable" class="cc-opt-bar" :class="{ 'is-done': !optimizing }">
        <template v-if="optimizing">
          <span class="cc-opt-spinner" aria-hidden="true" />
          <span class="cc-opt-bar__text">
            AI 正在优化提示词<template v-if="optimizeElapsed > 0"> · 已等待 {{ optimizeElapsed }}s</template>
          </span>
          <span class="cc-opt-bar__tip">通常 3–10 秒</span>
        </template>
        <template v-else>
          <span class="cc-opt-bar__text">✨ 提示词已优化</span>
          <button
            type="button"
            class="cc-opt-bar__undo"
            title="还原为优化前的提示词"
            @click="$emit('undo-optimize')"
          >撤销</button>
        </template>
      </div>

      <!-- 输入框：任务运行中整框禁用（与旧版 hasRunningTask 行为一致）。
           只禁发送不禁输入会让用户白打一段字，最后发现发不出去；要追加需求走上方「💡 补充需求」。 -->
      <div class="cc-composer__box">
        <textarea
          ref="textareaRef"
          :value="modelValue"
          :rows="rows"
          :placeholder="effectivePlaceholder"
          :disabled="disabled || running"
          :readonly="optimizing"
          @input="onInput"
          @keydown.enter.exact.prevent="onEnter"
          @paste="$emit('paste', $event)"
        />
        <div class="cc-composer__bar">
          <!-- 附件入口：点开两项菜单（上传新文件 / 用历史文件）。
               历史文件与「粘贴」共用文件资产通道（fileAssetId），不重复上传 -->
          <div ref="attachEntry" class="cc-attach-entry">
            <button
              class="cc-round-btn"
              title="添加附件"
              :disabled="disabled || running"
              @click.stop="attachMenuOpen = !attachMenuOpen"
            >
              <svg class="cc-i cc-i--sm"><use href="#i-plus" /></svg>
            </button>
            <div class="cc-attach-menu" :class="{ 'is-open': attachMenuOpen }">
              <button type="button" class="cc-attach-menu__item" @click="onMenuPick('upload')">
                <svg class="cc-i cc-i--sm"><use href="#i-plus" /></svg>
                上传新文件
              </button>
              <button type="button" class="cc-attach-menu__item" @click="onMenuPick('history')">
                <svg class="cc-i cc-i--sm"><use href="#i-file" /></svg>
                用历史文件
              </button>
            </div>
          </div>
          <button
            class="cc-round-btn cc-round-btn--opt"
            :class="{ 'is-loading': optimizing }"
            :title="optimizing ? 'AI 正在优化提示词，请稍候…' : 'AI 优化提示词'"
            :aria-label="optimizing ? 'AI 正在优化提示词' : 'AI 优化提示词'"
            :aria-busy="optimizing ? 'true' : 'false'"
            :disabled="disabled || running || optimizing"
            @click="$emit('optimize')"
          >
            <span v-if="optimizing" class="cc-opt-spinner" aria-hidden="true" />
            <template v-else>✨</template>
          </button>

          <div class="cc-composer__right">
            <button class="cc-model-btn" @click="$emit('select-model')">
              {{ model }}<span class="cc-model-btn__ver">▾</span>
            </button>

            <!-- 生成中显示停止，否则显示发送 -->
            <button v-if="running" class="cc-stop-btn" title="停止生成" @click="$emit('stop')">
              <svg class="cc-i cc-i--sm" style="stroke: #fff"><use href="#i-stop" /></svg>
            </button>
            <button v-else class="cc-send-btn" title="发送" :disabled="!canSend" @click="onSend">
              <svg class="cc-i cc-i--sm" style="stroke: #fff"><use href="#i-send" /></svg>
            </button>
          </div>
        </div>
      </div>

      <!-- 状态小字：上下文 / 余额 -->
      <div class="cc-composer__foot">
        <!-- 口径：已加载消息的正文 + 思考过程 + 工具返回，前端字符估算（后端未透传真实 usage）。
             精简模式下口径会变（历史轮次不计思考、工具只算摘要），故加一枚徽章说明当前口径。 -->
        <span
          v-if="contextTokens"
          :title="contextTitle"
        >上下文 <b>{{ contextTokens }}</b></span>
        <span v-if="compactMode" class="cc-ctx-badge" :title="compactTip">⚡ 精简</span>
        <span v-if="contextTokens && balance" class="cc-dot" />
        <span v-if="balance">余额 <b>{{ balance }}</b></span>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { openLightbox } from '../utils/lightbox'

export interface Attachment {
  id: string
  name: string
  size?: string
  /** 图标（emoji 或后续替换为文件类型图标） */
  icon?: string
  /** 是否图片（图片附件渲染 48×48 缩略图并可点开大图，F14） */
  isImage?: boolean
  /** 图片缩略图地址（blob: / data: / http(s): 均可） */
  previewUrl?: string
}

const props = withDefaults(
  defineProps<{
    modelValue?: string
    model?: string
    attachments?: Attachment[]
    /** 任务运行中：显示停止按钮并禁用输入 */
    running?: boolean
    disabled?: boolean
    /**
     * 提示词优化进行中：✨ 按钮换成旋转图标、整框 readonly、
     * 输入框上方出现「AI 正在优化…」状态条（含已等待秒数）。
     */
    optimizing?: boolean
    /** 优化已完成且草稿仍是优化结果：状态条右侧给「撤销」入口 */
    undoAvailable?: boolean
    contextTokens?: string
    balance?: string
    /** 上下文模式：full / compact（compact 时底部加「⚡ 精简」徽章并说明口径） */
    contextMode?: string
    placeholder?: string
    /** textarea 初始行数（空状态用 3，会话中默认 2） */
    rows?: number
  }>(),
  {
    modelValue: '',
    model: 'deepseek-v4-pro',
    attachments: () => [],
    running: false,
    disabled: false,
    optimizing: false,
    undoAvailable: false,
    contextTokens: '',
    balance: '',
    contextMode: 'full',
    placeholder: '描述你的需求，Enter 发送 / Shift+Enter 换行',
    rows: 2
  }
)

const emit = defineEmits<{
  (e: 'update:modelValue', v: string): void
  (e: 'send'): void
  (e: 'stop'): void
  (e: 'optimize'): void
  /** 还原为优化前的草稿（状态条上的「撤销」） */
  (e: 'undo-optimize'): void
  /** 上传新文件（打开系统文件选择框） */
  (e: 'attach'): void
  /** 从文件库挑已有文件（引用历史文件，不重复上传） */
  (e: 'attach-history'): void
  (e: 'select-model'): void
  (e: 'remove-attachment', id: string): void
  (e: 'paste', ev: ClipboardEvent): void
}>()

const textareaRef = ref<HTMLTextAreaElement | null>(null)
const attachEntry = ref<HTMLElement | null>(null)
const attachMenuOpen = ref(false)

/** 附件菜单两项分发：收起菜单再抛给父级（父级负责开文件框 / 开历史文件弹窗） */
function onMenuPick(kind: 'upload' | 'history') {
  attachMenuOpen.value = false
  if (kind === 'upload') emit('attach')
  else emit('attach-history')
}

/**
 * 点开图片附件的缩略图 → 全站灯箱（F14）。
 *
 * 只把**图片类**附件收进灯箱列表，这样在聊天里附了 2 张图 + 1 个 PDF 时，
 * 左右切换不会切到没有缩略图的文档上。
 */
function previewAttachment(att: Attachment) {
  const images = props.attachments
    .filter(a => a.isImage && a.previewUrl)
    .map(a => ({ src: a.previewUrl as string, name: a.name }))
  const start = props.attachments.filter(a => a.isImage && a.previewUrl).findIndex(a => a.id === att.id)
  openLightbox(images, start < 0 ? 0 : start)
}

/** 点面板外部 / 按 ESC 收起附件菜单 */
function onDocClick(ev: MouseEvent) {
  if (!attachMenuOpen.value) return
  const target = ev.target as Node | null
  if (attachEntry.value && target && attachEntry.value.contains(target)) return
  attachMenuOpen.value = false
}
function onDocKeydown(ev: KeyboardEvent) {
  if (ev.key === 'Escape') attachMenuOpen.value = false
}
onMounted(() => {
  document.addEventListener('click', onDocClick)
  document.addEventListener('keydown', onDocKeydown)
})
onBeforeUnmount(() => {
  document.removeEventListener('click', onDocClick)
  document.removeEventListener('keydown', onDocKeydown)
  stopOptimizeTimer()
})

const canSend = computed(() => {
  if (props.running || props.disabled) return false
  // 优化中禁止发送：此刻草稿马上会被回填，发出去的就是将被替换的旧文案
  if (props.optimizing) return false
  return props.modelValue.trim().length > 0 || props.attachments.length > 0
})

/**
 * 优化已等待秒数。
 *
 * <p>优化是一次同步等待 LLM 返回（后端 15s 超时、前端 30s 兜底），
 * 只转个圈用户没法判断「在跑」还是「卡死」。这里每秒 +1 打出量化进度，
 * 一般 3~10 秒就会出结果。</p>
 */
const optimizeElapsed = ref(0)
let optimizeTimer: ReturnType<typeof setInterval> | undefined

function stopOptimizeTimer() {
  if (optimizeTimer !== undefined) {
    clearInterval(optimizeTimer)
    optimizeTimer = undefined
  }
}

watch(
  () => props.optimizing,
  active => {
    stopOptimizeTimer()
    optimizeElapsed.value = 0
    if (!active) return
    optimizeTimer = setInterval(() => {
      // 到 60s 停止累加：此时前端兜底超时早已触发，继续跳数字只会让人以为彻底卡住
      if (optimizeElapsed.value >= 60) {
        stopOptimizeTimer()
        return
      }
      optimizeElapsed.value += 1
    }, 1000)
  },
  { immediate: true }
)

const compactMode = computed(() => props.contextMode === 'compact')

const contextTitle = computed(() =>
  compactMode.value
    ? '按已加载消息估算，精简模式口径：本轮含思考与工具明细，历史轮次只计正文 + 工具摘要（非后端真实用量）'
    : '按已加载消息估算：正文 + 思考过程 + 工具返回（非后端真实用量）'
)

const compactTip = '精简模式：历史轮次的思考过程已移除、工具调用只保留摘要，节省上下文'

/**
 * 运行中给出明确原因，避免用户以为输入框坏了。
 * 要追加要求走运行状态栏里的「💡 补充需求」（走后端 /deepseek/supplement 注入下一轮迭代）。
 */
const effectivePlaceholder = computed(() => {
  if (props.running) return '当前会话正在执行任务：如需追加要求点上方「💡 补充需求」，或先停止生成'
  if (props.disabled) return '当前不可输入'
  if (props.optimizing) return 'AI 正在优化提示词，完成后会自动回填…'
  return props.placeholder
})

function onInput(e: Event) {
  emit('update:modelValue', (e.target as HTMLTextAreaElement).value)
  autoResize()
}

/** Enter 发送；Shift+Enter 换行由 textarea 默认行为处理 */
function onEnter() {
  if (!canSend.value) return
  onSend()
}

function onSend() {
  if (!canSend.value) return
  emit('send')
}

/** 高度自适应（max-height 由 CSS 控制为 200px） */
function autoResize() {
  const el = textareaRef.value
  if (!el) return
  el.style.height = 'auto'
  el.style.height = `${Math.min(el.scrollHeight, 200)}px`
}

watch(
  () => props.modelValue,
  () => autoResize()
)

defineExpose({ focus: () => textareaRef.value?.focus() })
</script>
