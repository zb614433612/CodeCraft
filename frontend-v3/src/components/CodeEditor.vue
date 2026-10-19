<!--
  代码编辑器（伪富文本）
  ----------------------------------------------------------------------------
  实现路线：`<pre><code>` 高亮层 + 透明 `<textarea>` 叠加。
    - 高亮层负责「好看」，pointer-events:none、user-select:none；
    - textarea 负责「输入」，文字透明、只留 caret，光标/选区/IME/原生撤销栈全部照旧。
  为什么不引 CodeMirror / Monaco：两者主包都在 1MB 量级，而这里的需求只有
  「着色 + 行号 + 查找 + 保存」，伪富文本约 300 行就能覆盖，体积代价为 0。

  严格对齐的三个前提（改样式时务必保持）：
    1) 高亮层与 textarea 的 font-family / font-size(13px) / line-height(20px) /
       padding(8px 12px) / tab-size / letter-spacing 必须完全一致；
    2) 行高固定 20px——行号栏、当前行高亮条、查找跳转全部按 `(行号-1)*20` 算位置；
    3) 滚动由 textarea 驱动，高亮层与行号栏用 translate 跟随（不用 scrollTop，
       因为二者都设了 overflow:hidden）。

  数据接缝：props.modelValue / emit('update:modelValue') 双向绑定，
  保存方法由父级注入（emit('save')），本组件不直接调接口。
-->
<template>
  <div class="cc-code-ed" :class="{ 'is-readonly': effectiveReadonly }">
    <!-- 头部：路径 / 语言 / 未保存 / 只读原因 / 操作 -->
    <div class="cc-code-ed__head">
      <span class="cc-code-ed__path" :title="path">{{ path }}</span>
      <span class="cc-code-ed__lang">{{ langLabel }}</span>
      <span v-if="dirty && !effectiveReadonly" class="cc-code-ed__dirty">未保存</span>
      <span v-if="sizeVerdict.tooLarge" class="cc-code-ed__limit" :title="sizeVerdict.reason">只读</span>
      <span class="cc-code-ed__spacer" />
      <button
        class="cc-icon-btn cc-icon-btn--sm"
        :title="searchOpen ? '关闭查找（Esc）' : '查找（Ctrl+F）'"
        @click="toggleSearch"
      >
        <svg class="cc-i cc-i--sm"><use href="#i-search" /></svg>
      </button>
      <button
        v-if="!effectiveReadonly"
        class="cc-icon-btn cc-icon-btn--sm"
        :title="saving ? '保存中…' : '保存（Ctrl+S）'"
        :disabled="saving || !dirty"
        @click="requestSave"
      >
        <svg class="cc-i cc-i--sm"><use href="#i-check" /></svg>
      </button>
      <button v-if="showClose" class="cc-icon-btn cc-icon-btn--sm" title="关闭" @click="emit('close')">
        <svg class="cc-i cc-i--sm" style="transform: rotate(45deg)"><use href="#i-plus" /></svg>
      </button>
    </div>

    <!-- 查找条 -->
    <div v-if="searchOpen" class="cc-code-ed__find">
      <input
        ref="findInput"
        v-model="findText"
        class="cc-code-ed__find-input"
        type="text"
        placeholder="在当前文件中查找"
        spellcheck="false"
        @keydown.enter.exact.prevent="jumpMatch(1)"
        @keydown.shift.enter.prevent="jumpMatch(-1)"
        @keydown.esc.prevent="toggleSearch"
      />
      <span class="cc-code-ed__find-count">{{ findCountText }}</span>
      <button class="cc-icon-btn cc-icon-btn--sm" title="上一个（Shift+Enter）" :disabled="!matchCount" @click="jumpMatch(-1)">
        <svg class="cc-i cc-i--sm" style="transform: rotate(-90deg)"><use href="#i-chev" /></svg>
      </button>
      <button class="cc-icon-btn cc-icon-btn--sm" title="下一个（Enter）" :disabled="!matchCount" @click="jumpMatch(1)">
        <svg class="cc-i cc-i--sm" style="transform: rotate(90deg)"><use href="#i-chev" /></svg>
      </button>
    </div>

    <div v-if="sizeVerdict.tooLarge" class="cc-code-ed__notice">
      {{ sizeVerdict.reason }}（{{ sizeVerdict.detail }}）
    </div>

    <!-- 主体：行号 + 编辑区 -->
    <div class="cc-code-ed__body">
      <div class="cc-code-ed__gutter">
        <div class="cc-code-ed__gutter-inner" :style="gutterStyle">
          <div
            v-for="n in lineCount"
            :key="n"
            class="cc-code-ed__ln"
            :class="{ 'is-active': n === cursorLine }"
          >{{ n }}</div>
        </div>
      </div>

      <div class="cc-code-ed__pane">
        <!-- 当前行高亮条：随滚动平移，行高固定 20px 才能这样算 -->
        <div class="cc-code-ed__active-line" :style="activeLineStyle" />
        <div class="cc-code-ed__layer">
          <pre class="cc-code-ed__pre cc-code" :style="layerStyle"><code class="hljs" v-html="highlighted"></code></pre>
        </div>
        <textarea
          ref="areaRef"
          class="cc-code-ed__area"
          :value="inner"
          :readonly="effectiveReadonly"
          spellcheck="false"
          wrap="off"
          autocapitalize="off"
          autocomplete="off"
          @input="onInput"
          @scroll="onScroll"
          @keydown="onKeydown"
          @click="syncCursor"
          @keyup="syncCursor"
          @select="syncCursor"
        />
      </div>
    </div>

    <!-- 状态栏 -->
    <div class="cc-code-ed__foot">
      <span>行 {{ cursorLine }}, 列 {{ cursorColumn }}</span>
      <span>{{ lineCount }} 行</span>
      <span>{{ sizeVerdict.detail }}</span>
      <span class="cc-code-ed__spacer" />
      <span v-if="effectiveReadonly && !sizeVerdict.tooLarge">只读</span>
      <span v-else>Tab 缩进 · Ctrl+S 保存 · Ctrl+F 查找</span>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, nextTick, ref, watch } from 'vue'
import hljs from 'highlight.js/lib/core'
import {
  checkFileSize,
  countLines,
  detectLanguage,
  isLanguageReady,
  languageLabel
} from '../utils/language-detect'

const props = withDefaults(
  defineProps<{
    /** 文件路径（用于语言推断与头部展示） */
    path?: string
    /** 当前内容（v-model） */
    modelValue?: string
    /** 读取到的原始内容：与 modelValue 不同即视为「未保存」 */
    original?: string
    /** 外部强制只读（如后端返回只读/权限不足） */
    readonly?: boolean
    /** 保存中（父级请求进行中） */
    saving?: boolean
    /** 是否显示头部「关闭」按钮（由父级决定是否托管关闭动作） */
    showClose?: boolean
  }>(),
  { path: '', modelValue: '', original: '', readonly: false, saving: false, showClose: false }
)

const emit = defineEmits<{
  (e: 'update:modelValue', value: string): void
  (e: 'save'): void
  (e: 'close'): void
}>()

/** 行高（px）：行号栏、当前行高亮、查找跳转全部依赖它 */
const LINE_HEIGHT = 20
/** 编辑区上内边距（px）：与 CSS `.cc-code-ed__pre` / `.cc-code-ed__area` 的 padding 一致 */
const PAD_TOP = 8

const areaRef = ref<HTMLTextAreaElement | null>(null)
const findInput = ref<HTMLInputElement | null>(null)

/** 组件内部的内容副本：受控于 props，但输入时先本地更新以免光标跳动 */
const inner = ref(normalize(props.modelValue))
watch(
  () => props.modelValue,
  value => {
    const next = normalize(value)
    if (next !== inner.value) inner.value = next
  }
)
/** 切换文件时重置滚动与查找态 */
watch(
  () => props.path,
  () => {
    scrollTop.value = 0
    scrollLeft.value = 0
    searchOpen.value = false
    findText.value = ''
    matchIndex.value = 0
    cursorLine.value = 1
    cursorColumn.value = 1
  }
)

function normalize(text: string): string {
  return (text ?? '').replace(/\r\n/g, '\n').replace(/\r/g, '\n')
}

const language = computed(() => detectLanguage(props.path))
const langLabel = computed(() => languageLabel(language.value))
const lineCount = computed(() => countLines(inner.value))
const sizeVerdict = computed(() => checkFileSize(inner.value))
const effectiveReadonly = computed(() => props.readonly || sizeVerdict.value.tooLarge)
const dirty = computed(() => normalize(inner.value) !== normalize(props.original))

/* ---------------- 高亮 ---------------- */

/** 输入时的高亮结果（防抖写入；输入瞬间先用上一次结果，避免闪白） */
const renderedHtml = ref('')
let highlightTimer: ReturnType<typeof setTimeout> | null = null

function escapeHtml(text: string): string {
  return text.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
}

function renderHighlight(): void {
  const code = inner.value
  if (!code) {
    renderedHtml.value = '&nbsp;'
    return
  }
  if (!isLanguageReady(language.value)) {
    renderedHtml.value = escapeHtml(code)
    return
  }
  try {
    renderedHtml.value = hljs.highlight(code, { language: language.value, ignoreIllegals: true }).value
  } catch {
    renderedHtml.value = escapeHtml(code)
  }
}

const highlighted = computed(() => renderedHtml.value || '&nbsp;')

// 首次与内容/语言变化时重渲染；100ms 防抖，连续输入只渲染最后一次
watch(
  [() => inner.value, () => language.value],
  () => {
    if (highlightTimer) clearTimeout(highlightTimer)
    highlightTimer = setTimeout(renderHighlight, 100)
  },
  { immediate: true }
)

/* ---------------- 滚动同步 ---------------- */

const scrollTop = ref(0)
const scrollLeft = ref(0)

function onScroll(): void {
  const area = areaRef.value
  if (!area) return
  scrollTop.value = area.scrollTop
  scrollLeft.value = area.scrollLeft
}

const layerStyle = computed(() => ({
  transform: `translate(${-scrollLeft.value}px, ${-scrollTop.value}px)`
}))
const gutterStyle = computed(() => ({
  transform: `translateY(${-scrollTop.value}px)`
}))
const activeLineStyle = computed(() => ({
  transform: `translateY(${PAD_TOP + (cursorLine.value - 1) * LINE_HEIGHT - scrollTop.value}px)`
}))

/* ---------------- 光标与输入 ---------------- */

const cursorLine = ref(1)
const cursorColumn = ref(1)

function syncCursor(): void {
  const area = areaRef.value
  if (!area) return
  const before = inner.value.slice(0, area.selectionStart)
  const rows = before.split('\n')
  cursorLine.value = rows.length
  cursorColumn.value = rows[rows.length - 1].length + 1
}

function onInput(): void {
  const area = areaRef.value
  if (!area) return
  inner.value = normalize(area.value)
  emit('update:modelValue', inner.value)
  syncCursor()
}

/** 用新内容替换选区，并恢复光标位置（保证撤销栈之外的行为一致） */
function replaceRange(start: number, end: number, text: string, caret: number): void {
  const value = inner.value
  const next = value.slice(0, start) + text + value.slice(end)
  inner.value = next
  emit('update:modelValue', next)
  const area = areaRef.value
  if (!area) return
  nextTick(() => {
    area.value = next
    area.selectionStart = area.selectionEnd = caret
    syncCursor()
  })
}

/** 当前行行首空白（用于回车自动缩进） */
function indentOfLine(lineIndex: number): string {
  const line = inner.value.split('\n')[lineIndex] ?? ''
  const matched = line.match(/^[ \t]*/)
  return matched ? matched[0] : ''
}

function onKeydown(event: KeyboardEvent): void {
  const area = areaRef.value
  if (!area) return
  const mod = event.ctrlKey || event.metaKey

  // Ctrl+F / Ctrl+S：只读态也能查找
  if (mod && (event.key === 'f' || event.key === 'F')) {
    event.preventDefault()
    toggleSearch()
    return
  }
  if (mod && (event.key === 's' || event.key === 'S')) {
    event.preventDefault()
    if (!effectiveReadonly.value) requestSave()
    return
  }
  if (effectiveReadonly.value) return

  // Tab / Shift+Tab：有选区时整块缩进，否则插入两空格
  if (event.key === 'Tab') {
    event.preventDefault()
    const start = area.selectionStart
    const end = area.selectionEnd
    if (start === end) {
      replaceRange(start, end, '  ', start + 2)
      return
    }
    const value = inner.value
    const lineStart = value.lastIndexOf('\n', start - 1) + 1
    const block = value.slice(lineStart, end)
    const shifted = event.shiftKey
      ? block.replace(/^ {1,2}/gm, '')
      : block.replace(/^/gm, '  ')
    const delta = shifted.length - block.length
    replaceRange(lineStart, end, shifted, Math.max(lineStart, end + delta))
    return
  }

  // Enter：沿用上一行缩进；上一行以 { ( [ : 结尾再加一级；当前行以 } ) ] 开头时先补一级缩进
  if (event.key === 'Enter') {
    event.preventDefault()
    const start = area.selectionStart
    const value = inner.value
    const lineStart = value.lastIndexOf('\n', start - 1) + 1
    const currentLine = value.slice(lineStart, start)
    const baseIndent = indentOfLine(value.slice(0, start).split('\n').length - 1)
    const opensBlock = /[{([:]\s*$/.test(currentLine)
    const extra = opensBlock ? '  ' : ''
    const afterCursor = value.slice(start)
    const closesNext = /^\s*[}\])]/.test(afterCursor) && opensBlock
    if (closesNext) {
      // 形如 `{|}`：回车后新行多一级，闭合符留在下一行并退回一级
      replaceRange(start, start, `\n${baseIndent}${extra}\n${baseIndent}`, start + 1 + baseIndent.length + extra.length)
    } else {
      replaceRange(start, start, `\n${baseIndent}${extra}`, start + 1 + baseIndent.length + extra.length)
    }
    return
  }

  if (event.key === 'Escape' && searchOpen.value) {
    event.preventDefault()
    toggleSearch()
  }
}

function requestSave(): void {
  if (effectiveReadonly.value || props.saving) return
  emit('save')
}

/* ---------------- 查找 ---------------- */

const searchOpen = ref(false)
const findText = ref('')
const matchIndex = ref(0)
const matches = ref<number[]>([])

const matchCount = computed(() => matches.value.length)
const findCountText = computed(() => {
  if (!findText.value) return '输入关键词'
  if (!matchCount.value) return '无匹配'
  return `${matchIndex.value + 1} / ${matchCount.value}`
})

watch(findText, () => {
  const keyword = findText.value
  const list: number[] = []
  if (keyword) {
    const haystack = inner.value.toLowerCase()
    const needle = keyword.toLowerCase()
    let from = 0
    while (list.length < 500) {
      const hit = haystack.indexOf(needle, from)
      if (hit < 0) break
      list.push(hit)
      from = hit + Math.max(1, needle.length)
    }
  }
  matches.value = list
  matchIndex.value = 0
  if (list.length) revealMatch(0)
})

async function toggleSearch(): Promise<void> {
  searchOpen.value = !searchOpen.value
  if (searchOpen.value) {
    await nextTick()
    findInput.value?.focus()
    findInput.value?.select()
  } else {
    findText.value = ''
    areaRef.value?.focus()
  }
}

function jumpMatch(step: number): void {
  if (!matchCount.value) return
  const next = (matchIndex.value + step + matchCount.value) % matchCount.value
  matchIndex.value = next
  revealMatch(next)
}

/** 选中第 index 个匹配并滚动到可见（用原生选区体现高亮，不做 DOM 着色以免破坏高亮层） */
function revealMatch(index: number): void {
  const area = areaRef.value
  const start = matches.value[index]
  if (!area || start == null) return
  const end = start + findText.value.length
  area.focus()
  area.setSelectionRange(start, end)
  // 滚动：目标行居中
  const line = inner.value.slice(0, start).split('\n').length
  const target = PAD_TOP + (line - 1) * LINE_HEIGHT - Math.max(0, area.clientHeight / 2 - LINE_HEIGHT)
  area.scrollTop = Math.max(0, target)
  onScroll()
  syncCursor()
}
</script>
