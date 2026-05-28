<template>
  <div class="log-container">
    <!-- ===== 顶部工具栏 ===== -->
    <div class="log-toolbar">
      <div class="toolbar-left">
        <div class="page-title-group">
          <span class="page-icon">📋</span>
          <h2 class="page-title">运行日志</h2>
          <span v-if="mode === 'tail'" class="mode-badge mode-badge-tail">实时</span>
          <span v-else class="mode-badge mode-badge-search">搜索</span>
        </div>
        <div class="stats-row" v-if="displayLines.length > 0 && !loading">
          <span class="stats-item">
            <span class="stats-dot stats-dot-error"></span>
            {{ errorCount }} 错误
          </span>
          <span class="stats-item">
            <span class="stats-dot stats-dot-warn"></span>
            {{ warnCount }} 警告
          </span>
          <span class="stats-divider">·</span>
          <span class="stats-item stats-item-total">共 {{ displayLines.length }} 行</span>
        </div>
      </div>
      <div class="toolbar-right">
        <a-button type="primary" size="small" :loading="loading" @click="refresh" class="toolbar-btn">
          <template #icon><ReloadOutlined /></template>
          刷新
        </a-button>
      </div>
    </div>

    <!-- ===== 搜索栏（标签式筛选） ===== -->
    <div class="search-bar">
      <div class="search-input-wrap">
        <SearchOutlined class="search-prefix-icon" />
        <input
          v-model="searchKeyword"
          placeholder="搜索关键词..."
          class="search-input"
          @keydown.enter="doSearch"
        />
        <span v-if="searchKeyword" class="search-clear" @click="searchKeyword = ''">×</span>
      </div>
      <div class="filter-chips">
        <span class="filter-label">级别</span>
        <button
          v-for="lvl in logLevels"
          :key="lvl.value"
          :class="['filter-chip', { active: searchLevel === lvl.value }]"
          @click="toggleLevel(lvl.value)"
        >
          <span class="chip-dot" :class="'chip-dot-' + lvl.css"></span>
          {{ lvl.label }}
        </button>
      </div>
      <div class="filter-chips">
        <span class="filter-label">日期</span>
        <a-select
          v-model:value="searchDate"
          placeholder="全部日期"
          allow-clear
          size="small"
          class="date-select"
          :loading="datesLoading"
        >
          <a-select-option v-for="d in availableDates" :key="d" :value="d">{{ d }}</a-select-option>
        </a-select>
      </div>
      <a-button size="small" type="primary" :loading="searchLoading" @click="doSearch" class="search-btn">
        <SearchOutlined /> 搜索
      </a-button>
      <span v-if="mode === 'search'" class="search-result-hint">
        找到 {{ searchTotal }} 条结果
        <a-button type="link" size="small" @click="switchToTail">返回实时</a-button>
      </span>
    </div>

    <!-- ===== 日志主区域 ===== -->
    <div class="log-viewer">
      <!-- 空状态 -->
      <div v-if="displayLines.length === 0 && !loading" class="log-empty-state">
        <span class="empty-icon">{{ mode === 'tail' ? '📭' : '🔍' }}</span>
        <span class="empty-text">{{ mode === 'tail' ? '暂无日志输出' : '未找到匹配的日志' }}</span>
        <span class="empty-sub">应用启动后将在此显示日志</span>
      </div>

      <!-- 虚拟滚动日志列表 -->
      <DynamicScroller
        v-if="displayLines.length > 0"
        :items="displayLines"
        key-field="id"
        :min-item-size="30"
        :prerender="30"
        class="log-scroller"
        ref="scrollerRef"
        @scroll="onScroll"
      >
        <template #default="{ item, active, index }">
          <DynamicScrollerItem :item="item" :active="active" :size-dependencies="[item.text]">
            <div
              class="log-row"
              :class="rowClass(item.text)"
              @click="copyText(item.text)"
              :title="'点击复制此行'"
            >
              <span class="log-row-num">{{ index !== undefined ? index + 1 : '' }}</span>
              <span class="log-row-badge" :class="'badge-' + rowLevel(item.text)">{{ rowLevel(item.text) }}</span>
              <span class="log-row-text">{{ item.text }}</span>
            </div>
          </DynamicScrollerItem>
        </template>
      </DynamicScroller>

      <!-- 加载中 -->
      <div v-if="loading" class="log-loading-state">
        <LoadingOutlined spin class="loading-spin" />
        <span>加载中...</span>
      </div>
    </div>

    <!-- ===== 底部状态栏 ===== -->
    <div class="log-statusbar">
      <span class="statusbar-item" v-if="mode === 'tail' && hasMore">
        <DownOutlined class="statusbar-icon" /> 滚动到顶部加载更多
      </span>
      <span class="statusbar-item" v-else-if="mode === 'tail'">
        <CheckCircleOutlined class="statusbar-icon statusbar-ok" /> 已加载全部日志
      </span>
      <span class="statusbar-item statusbar-copy-hint">
        点击任意行可复制内容
      </span>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted, nextTick } from 'vue'
import {
  ReloadOutlined,
  SearchOutlined,
  DownOutlined,
  LoadingOutlined,
  CheckCircleOutlined,
} from '@ant-design/icons-vue'
import { message } from 'ant-design-vue'
import { DynamicScroller, DynamicScrollerItem } from 'vue-virtual-scroller'
import 'vue-virtual-scroller/dist/vue-virtual-scroller.css'
import { getAuthHeaders } from '@/utils/http-client'
import { searchLogs, getLogDates } from '@/api/logs'

const PAGE_SIZE = 500

// ===== 日志级别 =====
const logLevels = [
  { value: 'ERROR', label: 'ERROR', css: 'error' },
  { value: 'WARN', label: 'WARN', css: 'warn' },
  { value: 'INFO', label: 'INFO', css: 'info' },
  { value: 'DEBUG', label: 'DEBUG', css: 'debug' },
]

// ===== 模式 =====
const mode = ref<'tail' | 'search'>('tail')
const tailLines = ref<string[]>([])
const searchLines = ref<{ lines: { content: string }[]; total: number }>({ lines: [], total: 0 })
// 包装为对象数组，确保 DynamicScroller 的 key-field 稳定追踪每个 item
const displayLines = computed(() => {
  const raw = mode.value === 'tail' ? tailLines.value : searchLines.value.lines.map(l => l.content)
  return raw.map((text, i) => ({ id: i, text }))
})
const searchTotal = computed(() => searchLines.value.total)
const loading = ref(false)
const hasMore = ref(true)

// ===== 统计 =====
const errorCount = computed(() => displayLines.value.filter(l => rowLevel(l.text) === 'ERROR').length)
const warnCount = computed(() => displayLines.value.filter(l => rowLevel(l.text) === 'WARN').length)

// ===== 搜索条件 =====
const searchKeyword = ref('')
const searchLevel = ref<string | undefined>(undefined)
const searchDate = ref<string | undefined>(undefined)
const searchLoading = ref(false)
const datesLoading = ref(false)
const availableDates = ref<string[]>([])

function toggleLevel(level: string) {
  searchLevel.value = searchLevel.value === level ? undefined : level
}

// ===== 滚动 =====
const scrollerRef = ref<any>(null)
const autoScroll = ref(true)

// ===== Tail 模式 =====
async function loadTail(skip: number): Promise<string[]> {
  try {
    const authHeaders = await getAuthHeaders()
    const res = await fetch(`/api/logs/tail?maxLines=${PAGE_SIZE}&skip=${skip}`, {
      headers: { ...authHeaders },
    })
    if (!res.ok) throw new Error(`HTTP ${res.status}`)
    const body = await res.json()
    if (body.code === 200 && Array.isArray(body.data)) return body.data as string[]
    return []
  } catch (e: any) {
    console.warn('加载日志失败:', e.message)
    return []
  }
}

async function refresh() {
  mode.value = 'tail'
  tailLines.value = []
  loading.value = true
  const lines = await loadTail(0)
  tailLines.value = lines
  hasMore.value = lines.length >= PAGE_SIZE
  loading.value = false
  await nextTick()
  scrollToBottom()
}

async function initLoad() {
  loading.value = true
  const [lines, dates] = await Promise.all([loadTail(0), getLogDates()])
  tailLines.value = lines
  hasMore.value = lines.length >= PAGE_SIZE
  availableDates.value = dates
  loading.value = false
  await nextTick()
  scrollToBottom()
}

async function loadMore() {
  if (loading.value || !hasMore.value || mode.value !== 'tail') return
  loading.value = true
  const el = scrollerRef.value?.$el as HTMLElement | undefined
  const oldScrollHeight = el?.scrollHeight || 0
  const olderLines = await loadTail(tailLines.value.length)
  if (olderLines.length === 0) {
    hasMore.value = false
    loading.value = false
    return
  }
  tailLines.value = [...olderLines, ...tailLines.value]
  hasMore.value = olderLines.length >= PAGE_SIZE
  loading.value = false
  await nextTick()
  if (el) el.scrollTop = el.scrollHeight - oldScrollHeight
}

// ===== 搜索模式 =====
async function doSearch() {
  if (!searchKeyword.value && !searchLevel.value && !searchDate.value) {
    message.warning('请至少输入一个搜索条件')
    return
  }
  searchLoading.value = true
  mode.value = 'search'
  try {
    const result = await searchLogs({
      keyword: searchKeyword.value || undefined,
      level: searchLevel.value || undefined,
      date: searchDate.value || undefined,
      page: 1,
      size: 5000,
    })
    searchLines.value = { lines: result.lines || [], total: result.total }
  } catch (e: any) {
    message.error(e.message || '搜索失败')
  } finally {
    searchLoading.value = false
  }
}

function switchToTail() {
  mode.value = 'tail'
  searchKeyword.value = ''
  searchLevel.value = undefined
  searchDate.value = undefined
  autoScroll.value = true
  nextTick(() => scrollToBottom())
}

// ===== 滚动事件 =====
function onScroll(event: any) {
  const target = event.target || event
  if (!target) return
  if (target.scrollTop <= 5 && mode.value === 'tail' && !loading.value && hasMore.value) {
    loadMore()
  }
  const threshold = 100
  autoScroll.value = target.scrollHeight - target.scrollTop - target.clientHeight < threshold
}

function scrollToBottom() {
  if (!autoScroll.value) return
  const el = scrollerRef.value?.$el as HTMLElement | undefined
  if (el) {
    const doScroll = () => {
      el.scrollTop = el.scrollHeight
    }
    doScroll()
    setTimeout(doScroll, 100)
    setTimeout(doScroll, 300)
  }
}

// ===== 行样式 =====
function rowLevel(line: string): string {
  if (line.includes(' ERROR ') || line.startsWith('ERROR')) return 'ERROR'
  if (line.includes(' WARN ') || line.startsWith('WARN')) return 'WARN'
  if (line.includes(' INFO ') || line.startsWith('INFO')) return 'INFO'
  if (line.includes(' DEBUG ') || line.startsWith('DEBUG')) return 'DEBUG'
  return ''
}

function rowClass(line: string): string {
  const lvl = rowLevel(line)
  if (lvl) return 'log-row-level-' + lvl.toLowerCase()
  return ''
}

function copyText(text: string) {
  navigator.clipboard.writeText(text).then(
    () => message.success('已复制'),
    () => {
      const ta = document.createElement('textarea')
      ta.value = text
      document.body.appendChild(ta)
      ta.select()
      document.execCommand('copy')
      document.body.removeChild(ta)
      message.success('已复制')
    }
  )
}

onMounted(() => {
  initLoad()
})
</script>

<style scoped>
/* ===== 容器 ===== */
.log-container {
  padding: 16px 20px;
  height: 100%;
  display: flex;
  flex-direction: column;
  background: #f7f9fc;
  gap: 10px;
}

/* ===== 顶部工具栏 ===== */
.log-toolbar {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  padding: 14px 20px;
  background: #fff;
  border-radius: 10px;
  box-shadow: 0 1px 3px rgba(0, 0, 0, 0.04);
  flex-shrink: 0;
  border: 1px solid #eef2f7;
}
.toolbar-left {
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.toolbar-right {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-shrink: 0;
}
.page-title-group {
  display: flex;
  align-items: center;
  gap: 8px;
}
.page-icon {
  font-size: 18px;
  line-height: 1;
}
.page-title {
  margin: 0;
  font-size: 18px;
  font-weight: 650;
  color: #1a202c;
  letter-spacing: -0.2px;
}

/* 模式徽章 */
.mode-badge {
  font-size: 11px;
  font-weight: 600;
  padding: 2px 10px;
  border-radius: 10px;
  line-height: 18px;
}
.mode-badge-tail {
  background: #e6f7ff;
  color: #1677ff;
  border: 1px solid #91d5ff;
}
.mode-badge-search {
  background: #fff7e6;
  color: #d48806;
  border: 1px solid #ffe58f;
}

/* 统计行 */
.stats-row {
  display: flex;
  align-items: center;
  gap: 12px;
  flex-wrap: wrap;
}
.stats-item {
  font-size: 12px;
  color: #64748b;
  display: flex;
  align-items: center;
  gap: 5px;
}
.stats-dot {
  width: 7px;
  height: 7px;
  border-radius: 50%;
  flex-shrink: 0;
}
.stats-dot-error {
  background: #f48771;
}
.stats-dot-warn {
  background: #e2b93b;
}
.stats-divider {
  color: #d9d9d9;
  font-size: 13px;
}
.stats-item-total {
  color: #94a3b8;
}

.toolbar-btn {
  font-size: 12px;
  height: 30px;
  border-radius: 6px;
  padding: 0 14px;
}

/* ===== 搜索栏 ===== */
.search-bar {
  display: flex;
  align-items: center;
  padding: 10px 16px;
  background: #fff;
  border-radius: 10px;
  box-shadow: 0 1px 3px rgba(0, 0, 0, 0.04);
  border: 1px solid #eef2f7;
  flex-shrink: 0;
  gap: 10px;
  flex-wrap: wrap;
}

/* 搜索输入 */
.search-input-wrap {
  position: relative;
  display: flex;
  align-items: center;
  flex-shrink: 0;
}
.search-prefix-icon {
  position: absolute;
  left: 10px;
  font-size: 13px;
  color: #94a3b8;
  pointer-events: none;
  z-index: 1;
}
.search-input {
  width: 180px;
  height: 30px;
  padding: 0 28px 0 30px;
  border: 1px solid #e0e4ea;
  border-radius: 6px;
  font-size: 12px;
  color: #334155;
  background: #f8fafc;
  outline: none;
  transition: all 0.2s;
  font-family: inherit;
}
.search-input::placeholder {
  color: #b0b8c4;
}
.search-input:focus {
  border-color: #1677ff;
  box-shadow: 0 0 0 2px rgba(22, 119, 255, 0.1);
  background: #fff;
}
.search-clear {
  position: absolute;
  right: 6px;
  cursor: pointer;
  font-size: 14px;
  color: #94a3b8;
  padding: 2px 4px;
  border-radius: 3px;
  line-height: 1;
  transition: all 0.15s;
  z-index: 1;
}
.search-clear:hover {
  color: #ff4d4f;
  background: #fff1f0;
}

/* 筛选芯片组 */
.filter-chips {
  display: flex;
  align-items: center;
  gap: 4px;
}
.filter-label {
  font-size: 11px;
  color: #94a3b8;
  margin-right: 2px;
  flex-shrink: 0;
  font-weight: 500;
}
.filter-chip {
  display: flex;
  align-items: center;
  gap: 4px;
  padding: 3px 10px;
  font-size: 11px;
  font-weight: 500;
  border: 1px solid #e0e4ea;
  border-radius: 14px;
  background: #f8fafc;
  color: #64748b;
  cursor: pointer;
  transition: all 0.18s;
  font-family: inherit;
  line-height: 18px;
  white-space: nowrap;
}
.filter-chip:hover {
  border-color: #c0c8d4;
  background: #f1f5f9;
  color: #334155;
}
.filter-chip.active {
  background: #e6f4ff;
  border-color: #1677ff;
  color: #1677ff;
}
.chip-dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  flex-shrink: 0;
}
.chip-dot-error {
  background: #f48771;
}
.chip-dot-warn {
  background: #e2b93b;
}
.chip-dot-info {
  background: #75beff;
}
.chip-dot-debug {
  background: #94a3b8;
}

.date-select {
  width: 130px;
}
.date-select :deep(.ant-select-selector) {
  border-radius: 14px !important;
  font-size: 11px !important;
  height: 28px !important;
}

.search-btn {
  height: 28px;
  font-size: 11px;
  border-radius: 14px;
  padding: 0 14px;
  flex-shrink: 0;
}

.search-result-hint {
  font-size: 12px;
  color: #64748b;
  display: flex;
  align-items: center;
  gap: 4px;
  margin-left: auto;
}

/* ===== 日志主区域 ===== */
.log-viewer {
  flex: 1;
  background: #1b1e2b;
  border-radius: 10px;
  overflow: hidden;
  position: relative;
  border: 1px solid #2a2d3a;
  box-shadow: inset 0 1px 0 rgba(255, 255, 255, 0.03);
}
.log-scroller {
  height: 100%;
}

/* 空状态 */
.log-empty-state {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  height: 100%;
  gap: 8px;
  color: #64748b;
  user-select: none;
}
.empty-icon {
  font-size: 40px;
  opacity: 0.5;
}
.empty-text {
  font-size: 14px;
  font-weight: 500;
  color: #8b95a5;
}
.empty-sub {
  font-size: 12px;
  color: #5a6475;
}

/* 加载状态 */
.log-loading-state {
  display: flex;
  align-items: center;
  justify-content: center;
  height: 100%;
  gap: 8px;
  color: #64748b;
  font-size: 13px;
}
.loading-spin {
  font-size: 16px;
  color: #1677ff;
}

/* ===== 日志行 ===== */
.log-row {
  display: flex;
  align-items: flex-start;
  padding: 2px 0;
  font-family: 'Cascadia Code', 'Fira Code', 'JetBrains Mono', 'Consolas', 'SF Mono', monospace;
  font-size: 12.5px;
  line-height: 22px;
  min-height: 26px;
  height: auto;
  cursor: pointer;
  transition: background 0.1s;
  user-select: none;
}
.log-row:hover {
  background: rgba(255, 255, 255, 0.04);
}

/* 行号 */
.log-row-num {
  width: 48px;
  flex-shrink: 0;
  text-align: right;
  padding-right: 14px;
  color: #4a5568;
  font-size: 11px;
  line-height: 22px;
  user-select: none;
}

/* 级别徽章 */
.log-row-badge {
  width: 48px;
  flex-shrink: 0;
  text-align: center;
  font-size: 9.5px;
  font-weight: 700;
  letter-spacing: 0.3px;
  line-height: 18px;
  margin-top: 2px;
  border-radius: 3px;
  padding: 0 4px;
  user-select: none;
}
.badge-ERROR {
  color: #f48771;
  background: rgba(244, 135, 113, 0.12);
}
.badge-WARN {
  color: #e2b93b;
  background: rgba(226, 185, 59, 0.12);
}
.badge-INFO {
  color: #75beff;
  background: rgba(117, 190, 255, 0.1);
}
.badge-DEBUG {
  color: #8b95a5;
  background: rgba(139, 149, 165, 0.1);
}

/* 日志文本 */
.log-row-text {
  flex: 1;
  color: #c9d1d9;
  white-space: pre-wrap;
  word-break: break-all;
  text-align: left;
  padding-right: 12px;
}

/* 按级别整行着色 */
.log-row-level-error .log-row-text {
  color: #f48771;
}
.log-row-level-warn .log-row-text {
  color: #e2b93b;
}
.log-row-level-info .log-row-text {
  color: #75beff;
}
.log-row-level-debug .log-row-text {
  color: #8b95a5;
}

/* ===== 底部状态栏 ===== */
.log-statusbar {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 6px 16px;
  background: #fff;
  border-radius: 8px;
  box-shadow: 0 1px 3px rgba(0, 0, 0, 0.04);
  border: 1px solid #eef2f7;
  flex-shrink: 0;
}
.statusbar-item {
  font-size: 11px;
  color: #94a3b8;
  display: flex;
  align-items: center;
  gap: 4px;
}
.statusbar-icon {
  font-size: 11px;
}
.statusbar-ok {
  color: #52c41a;
}
.statusbar-copy-hint {
  color: #b0b8c4;
}

/* ===== 滚动条美化 ===== */
.log-scroller::-webkit-scrollbar {
  width: 6px;
}
.log-scroller::-webkit-scrollbar-track {
  background: transparent;
}
.log-scroller::-webkit-scrollbar-thumb {
  background: #3a3f50;
  border-radius: 3px;
}
.log-scroller::-webkit-scrollbar-thumb:hover {
  background: #4a5065;
}

/* ===== 暗色模式 ===== */
[data-theme="dark"] .log-container {
  background: #121418;
}
[data-theme="dark"] .log-toolbar {
  background: #1a1d22;
  border-color: #2a2d33;
}
[data-theme="dark"] .page-title {
  color: #e4e6ea;
}
[data-theme="dark"] .mode-badge-tail {
  background: rgba(22, 119, 255, 0.15);
  color: #5ba0ff;
  border-color: rgba(22, 119, 255, 0.3);
}
[data-theme="dark"] .mode-badge-search {
  background: rgba(212, 136, 6, 0.15);
  color: #f0b726;
  border-color: rgba(212, 136, 6, 0.3);
}
[data-theme="dark"] .stats-item {
  color: #8b8f98;
}
[data-theme="dark"] .stats-divider {
  color: #3a3d44;
}
[data-theme="dark"] .stats-item-total {
  color: #8b8f98;
}
[data-theme="dark"] .search-bar {
  background: #1a1d22;
  border-color: #2a2d33;
}
[data-theme="dark"] .search-prefix-icon {
  color: #8b8f98;
}
[data-theme="dark"] .search-input {
  background: #141619;
  border-color: #2a2d33;
  color: #e4e6ea;
}
[data-theme="dark"] .search-input::placeholder {
  color: #5a5f68;
}
[data-theme="dark"] .search-input:focus {
  background: #141619;
  border-color: #1677ff;
}
[data-theme="dark"] .search-clear {
  color: #8b8f98;
}
[data-theme="dark"] .search-clear:hover {
  color: #ff7875;
  background: rgba(255, 77, 79, 0.1);
}
[data-theme="dark"] .filter-label {
  color: #8b8f98;
}
[data-theme="dark"] .filter-chip {
  background: #141619;
  border-color: #2a2d33;
  color: #8b8f98;
}
[data-theme="dark"] .filter-chip:hover {
  background: #1e2126;
  border-color: #3a3d44;
  color: #b0b5bd;
}
[data-theme="dark"] .filter-chip.active {
  background: rgba(22, 119, 255, 0.15);
  border-color: #1677ff;
  color: #5ba0ff;
}
[data-theme="dark"] .search-result-hint {
  color: #8b8f98;
}
[data-theme="dark"] .log-empty-state {
  color: #8b8f98;
}
[data-theme="dark"] .empty-text {
  color: #8b8f98;
}
[data-theme="dark"] .empty-sub {
  color: #5a5f68;
}
[data-theme="dark"] .log-loading-state {
  color: #8b8f98;
}
[data-theme="dark"] .log-statusbar {
  background: #1a1d22;
  border-color: #2a2d33;
}
[data-theme="dark"] .statusbar-item {
  color: #8b8f98;
}
[data-theme="dark"] .statusbar-copy-hint {
  color: #5a5f68;
}
/* 日期选择器 */
[data-theme="dark"] .date-select :deep(.ant-select-selector) {
  background: #141619 !important;
  border-color: #2a2d33 !important;
  color: #e4e6ea !important;
}
[data-theme="dark"] :deep(.ant-select-dropdown) {
  background: #1a1d22;
}
[data-theme="dark"] :deep(.ant-select-item) {
  color: #e4e6ea;
}
[data-theme="dark"] :deep(.ant-select-item-option-selected) {
  background: rgba(22, 119, 255, 0.12);
}
</style>
