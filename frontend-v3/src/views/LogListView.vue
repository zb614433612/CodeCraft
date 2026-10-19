<!--
  运行日志页（v2）
  ----------------------------------------------------------------------------
  布局：页面头（导出 / 刷新）→ 内联统计 → 工具栏（模式 / 关键词 / 级别 / 日期 / 搜索）
        → 日志面板（顶部工具条 + 等宽行列表）→ 状态栏
  数据接缝：
    lines          -> GET /api/logs/tail?maxLines=500&skip=0（实时，纯文本行，从旧到新）
                      GET /api/logs/search?keyword&level&date&page&size（搜索，含 fileName / lineNumber）
    availableDates -> GET /api/logs/dates
    emit('refresh')     -> 重新加载实时日志并复位分页
    emit('search', q)   -> GET /api/logs/search（关键词 / 级别 / 日期）
    emit('switch-tail') -> 回到实时模式
    emit('load-more')   -> GET /api/logs/tail?maxLines=500&skip={已加载行数}
    emit('copy', text)  -> 复制单行（父组件做提示或埋点）
    emit('export-view') -> 导出当前展示行（前端 join 文本下载）
    emit('export-file') -> GET /api/logs/download?date（后端拼接当日全部文件）
  说明：
    1) 后端 tail 只读「今天」的归档文件与 app.log，历史日期必须走搜索模式，故日期选择在实时模式禁用；
    2) 级别判定与后端 LogService 一致，见 utils/log-line.ts；
    3) 面板固定高度 + 内部滚动，滚动到顶部自动加载更早（tail 模式）。
-->
<template>
  <div class="cc-page-scroll">
    <div class="cc-page-inner">
      <!-- 页面头 -->
      <div class="cc-page-head">
        <div class="cc-page-head__icon">
          <svg class="cc-i"><use href="#i-log" /></svg>
        </div>
        <div>
          <h1 class="cc-page-head__title">运行日志</h1>
          <p class="cc-page-head__sub">
            实时追踪最新日志输出，或按级别 / 关键词 / 日期检索历史归档文件
          </p>
        </div>
        <div class="cc-page-head__actions">
          <button class="cc-btn" :disabled="!filteredLines.length" @click="$emit('export-view')">
            <svg class="cc-i cc-i--sm"><use href="#i-file" /></svg>导出当前视图
          </button>
          <button class="cc-btn" @click="$emit('export-file')">
            <svg class="cc-i cc-i--sm"><use href="#i-disk" /></svg>下载完整日志
          </button>
          <button class="cc-btn cc-btn--primary" @click="$emit('refresh')">
            <svg class="cc-i cc-i--sm"><use href="#i-refresh" /></svg>刷新
          </button>
        </div>
      </div>

      <!-- 统计：口径与行内徽章一致（都走 splitLogLine） -->
      <div class="cc-stats-inline" style="margin-bottom: var(--sp-4)">
        <span>模式 <b>{{ mode === 'tail' ? '实时' : '搜索' }}</b></span><span class="cc-dot" />
        <span>错误 <b :style="errorCount ? 'color: var(--danger)' : ''">{{ errorCount }}</b></span>
        <span class="cc-dot" />
        <span>警告 <b :style="warnCount ? 'color: var(--warn)' : ''">{{ warnCount }}</b></span>
        <span class="cc-dot" />
        <span>本页 <b>{{ filteredLines.length }}</b> 行</span><span class="cc-dot" />
        <span v-if="mode === 'search'">命中 <b>{{ total }}</b> 条</span>
        <span v-else>已加载 <b>{{ lines.length }}</b> 行</span>
      </div>

      <!-- 工具栏 -->
      <div class="cc-toolbar">
        <div class="cc-seg">
          <button :class="{ 'is-active': mode === 'tail' }" @click="$emit('switch-tail')">实时</button>
          <button :class="{ 'is-active': mode === 'search' }" @click="submitSearch">搜索</button>
        </div>

        <label class="cc-search">
          <svg class="cc-i cc-i--sm"><use href="#i-search" /></svg>
          <input v-model="keyword" placeholder="搜索关键词（大小写不敏感）" @keydown.enter="submitSearch" />
        </label>

        <div class="cc-seg">
          <button
            v-for="opt in LOG_LEVEL_FILTERS"
            :key="opt.value"
            :class="{ 'is-active': level === opt.value }"
            @click="level = opt.value"
          >
            {{ opt.label }}
          </button>
        </div>

        <select
          v-model="dateModel"
          class="cc-select cc-log-date"
          :disabled="mode === 'tail'"
          title="实时模式只读取今天的日志；历史日期请切到搜索模式"
        >
          <option value="">最新日期</option>
          <option v-for="d in availableDates" :key="d" :value="d">{{ d }}</option>
        </select>

        <button class="cc-btn cc-btn--primary" :disabled="loading" @click="submitSearch">
          <svg class="cc-i cc-i--sm"><use href="#i-search" /></svg>搜索
        </button>

        <div style="margin-left: auto; font-size: var(--fs-sm); color: var(--text-3)">
          <template v-if="mode === 'search'">
            找到 <b style="color: var(--text-1)">{{ total }}</b> 条
            <button class="cc-text-btn" @click="$emit('switch-tail')">返回实时</button>
          </template>
          <template v-else>级别筛选只作用于已加载的行；关键词搜索走后端检索</template>
        </div>
      </div>

      <!-- 日志面板 -->
      <div v-if="filteredLines.length" class="cc-log-panel">
        <div class="cc-log-topbar">
          <span class="cc-log-topbar__file">
            <svg class="cc-i cc-i--sm"><use href="#i-file" /></svg>{{ fileName }}
          </span>
          <span class="cc-log-topbar__sep">·</span>
          <span>{{ panelMeta }}</span>
          <div class="cc-log-topbar__tools">
            <span class="cc-log-auto" title="开启后新日志自动滚动到底部" @click="toggleAutoScroll">
              <span class="cc-switch" :class="{ 'is-on': autoScroll }" />自动滚动
            </span>
            <button v-if="mode === 'tail' && hasMore" class="cc-btn cc-btn--ghost" @click="requestMore">
              <svg class="cc-i cc-i--sm" style="transform: rotate(-90deg)"><use href="#i-chev" /></svg>加载更早
            </button>
          </div>
        </div>

        <div ref="listRef" class="cc-log-list" @scroll="onScroll">
          <LogRow
            v-for="(line, index) in filteredLines"
            :key="line.id"
            :line="line"
            :index="index"
            @copy="copyLine"
          />
        </div>
      </div>

      <!-- 状态栏 -->
      <div v-if="filteredLines.length" class="cc-log-statusbar">
        <span class="cc-log-statusbar__item">
          <svg v-if="mode === 'tail' && hasMore" class="cc-i cc-i--sm" style="transform: rotate(-90deg)">
            <use href="#i-chev" />
          </svg>
          <svg v-else class="cc-i cc-i--sm"><use href="#i-check" /></svg>
          {{ statusText }}
        </span>
        <span class="cc-log-statusbar__spacer">{{ copyHint || '单击行复制 · 拖选多行后 Ctrl+C' }}</span>
      </div>

      <!-- 空状态 -->
      <div v-if="!filteredLines.length" class="cc-agent-empty">
        <div class="cc-agent-empty__icon">
          <svg class="cc-i" style="width: 28px; height: 28px"><use href="#i-log" /></svg>
        </div>
        <div style="font-size: var(--fs-base); font-weight: 600; color: var(--text-1)">
          {{ emptyTitle }}
        </div>
        <div style="font-size: var(--fs-sm); color: var(--text-3)">{{ emptyDesc }}</div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, nextTick, onMounted, ref, watch } from 'vue'
import LogRow, { type LogLineItem } from '../components/LogRow.vue'
import { countLogLevels, LOG_LEVEL_FILTERS, splitLogLine } from '../utils/log-line'

/** 搜索条件（date 为空表示最新日期，与后端 /logs/search 语义一致） */
export interface LogSearchQuery {
  keyword?: string
  level?: string
  date?: string
}

const props = withDefaults(
  defineProps<{
    /** 当前展示的日志行（tail 为纯文本行；search 带后端行号与来源文件） */
    lines?: LogLineItem[]
    /** tail=实时（只读今天） / search=历史检索 */
    mode?: 'tail' | 'search'
    /** 搜索命中总数（后端 total，大于当前已加载行数时提示仍有剩余） */
    total?: number
    /** GET /api/logs/dates 返回的日期列表（从新到旧） */
    availableDates?: string[]
    /** 搜索日期（空=最新） */
    date?: string
    loading?: boolean
    /** tail 模式是否还有更早的日志可加载 */
    hasMore?: boolean
    /**
     * 进入页面时的数据加载（由 App.vue 的 PAGE_BINDINGS 注入）
     * 页面随路由真实挂载，所以在这里调一次即可；重复进页由父级的「已加载 / 加载中 / 已失败」跳过
     */
    load?: () => void
  }>(),
  {
    lines: () => [],
    mode: 'tail',
    total: 0,
    availableDates: () => [],
    date: '',
    loading: false,
    hasMore: false
  }
)

const emit = defineEmits<{
  (e: 'refresh'): void
  (e: 'search', query: LogSearchQuery): void
  (e: 'switch-tail'): void
  (e: 'load-more'): void
  (e: 'copy', text: string): void
  (e: 'export-view'): void
  (e: 'export-file'): void
}>()

const keyword = ref('')
const level = ref('')
const dateModel = ref(props.date)
const autoScroll = ref(true)
const copyHint = ref('')

const listRef = ref<HTMLElement | null>(null)
/** 加载更早前的可视高度，用于渲染后保持视口位置 */
let pendingPrependHeight = 0
let copyTimer: number | undefined

/** 本地过滤：级别 + 关键词即时作用于已加载的行（后端检索由搜索按钮触发） */
const filteredLines = computed(() => {
  const kw = keyword.value.trim().toLowerCase()
  const lvl = level.value
  if (!kw && !lvl) return props.lines
  return props.lines.filter(line => {
    if (lvl && splitLogLine(line.text).level !== lvl) return false
    if (kw && !line.text.toLowerCase().includes(kw)) return false
    return true
  })
})

const levelStats = computed(() => countLogLevels(filteredLines.value))
const errorCount = computed(() => levelStats.value.error)
const warnCount = computed(() => levelStats.value.warn)

const fileName = computed(() => props.lines[0]?.fileName || 'app.log')

const dateLabel = computed(() => dateModel.value || '最新日期')

const panelMeta = computed(() => {
  if (props.mode === 'tail') {
    return `实时 · 已加载 ${props.lines.length} 行${props.hasMore ? '（可继续加载更早）' : ''}`
  }
  return `搜索 · ${dateLabel.value} · 命中 ${props.total} 条`
})

const statusText = computed(() => {
  if (props.mode === 'search') {
    return `共命中 ${props.total} 条，当前展示 ${filteredLines.value.length} 条`
  }
  return props.hasMore ? '滚动到顶部或点「加载更早」继续加载' : '已加载全部日志'
})

const emptyTitle = computed(() =>
  props.mode === 'tail' ? '暂无日志输出' : '未找到匹配的日志'
)

const emptyDesc = computed(() => {
  if (props.mode === 'tail') {
    return props.loading ? '正在读取日志文件…' : '应用启动后日志会写入 logs/app.log'
  }
  return dateModel.value
    ? `${dateModel.value} 没有匹配的日志，换个关键词或级别再试`
    : '换个关键词或级别再试，或选择其它日期'
})

function submitSearch() {
  emit('search', {
    keyword: keyword.value.trim() || undefined,
    level: level.value || undefined,
    date: dateModel.value || undefined
  })
}

function scrollToBottom() {
  const el = listRef.value
  if (el) el.scrollTop = el.scrollHeight
}

function toggleAutoScroll() {
  autoScroll.value = !autoScroll.value
  if (autoScroll.value) scrollToBottom()
}

function requestMore() {
  const el = listRef.value
  pendingPrependHeight = el ? el.scrollHeight - el.scrollTop : 0
  emit('load-more')
}

function onScroll() {
  const el = listRef.value
  if (!el) return
  autoScroll.value = el.scrollHeight - el.scrollTop - el.clientHeight < 24
  if (props.mode === 'tail' && props.hasMore && !props.loading && el.scrollTop <= 8) {
    requestMore()
  }
}

function fallbackCopy(text: string) {
  const ta = document.createElement('textarea')
  ta.value = text
  ta.style.position = 'fixed'
  ta.style.opacity = '0'
  document.body.appendChild(ta)
  ta.select()
  try {
    document.execCommand('copy')
  } catch {
    // 降级失败时静默（父组件可自行提示）
  }
  document.body.removeChild(ta)
}

function copyLine(text: string) {
  // 有选中文本时不复制整行，避免与「拖选多行复制」冲突
  const sel = window.getSelection()
  if (sel && !sel.isCollapsed && sel.toString().trim()) return

  if (navigator.clipboard?.writeText) {
    navigator.clipboard.writeText(text).catch(() => fallbackCopy(text))
  } else {
    fallbackCopy(text)
  }

  copyHint.value = '已复制该行'
  window.clearTimeout(copyTimer)
  copyTimer = window.setTimeout(() => {
    copyHint.value = ''
  }, 1600)

  emit('copy', text)
}

/** 行数变化：加载更早时保持视口，实时模式有新日志时跟随到底部 */
watch(
  () => props.lines.length,
  async () => {
    await nextTick()
    const el = listRef.value
    if (!el) return
    if (pendingPrependHeight > 0) {
      el.scrollTop = el.scrollHeight - pendingPrependHeight
      pendingPrependHeight = 0
    } else if (props.mode === 'tail' && autoScroll.value) {
      el.scrollTop = el.scrollHeight
    }
  }
)

/** 模式切换：实时滚到底部，搜索从第一条开始看 */
watch(
  () => props.mode,
  async () => {
    await nextTick()
    if (props.mode === 'tail') {
      if (autoScroll.value) scrollToBottom()
    } else if (listRef.value) {
      listRef.value.scrollTop = 0
    }
  }
)

/** 父组件重置搜索日期（如刷新）时同步下拉框，避免残留上一次的日期 */
watch(
  () => props.date,
  (value) => {
    dateModel.value = value
  }
)

onMounted(async () => {
  await nextTick()
  scrollToBottom()
})

/**
 * 「进入页面才拉数据」的触发点（原 App.vue 的 PAGE_LOADERS 已下沉至此）
 * 父级通过 props.load 注入具体加载函数，组件只负责在最合适的时机调用它。
 */
onMounted(() => props.load?.())
</script>
