<template>
  <div class="log-container">
    <div class="page-header">
      <h2 class="page-title">运行日志</h2>
      <a-button type="primary" :loading="loading" @click="refresh">
        <template #icon><ReloadOutlined /></template>
        刷新
      </a-button>
    </div>

    <!-- 搜索栏 -->
    <div class="search-bar">
      <a-input v-model:value="searchKeyword" placeholder="关键词..." allow-clear style="width: 200px" @press-enter="doSearch" />
      <a-select v-model:value="searchLevel" placeholder="级别" allow-clear style="width: 110px; margin-left: 6px">
        <a-select-option value="ERROR">ERROR</a-select-option>
        <a-select-option value="WARN">WARN</a-select-option>
        <a-select-option value="INFO">INFO</a-select-option>
        <a-select-option value="DEBUG">DEBUG</a-select-option>
      </a-select>
      <a-select v-model:value="searchDate" placeholder="日期" allow-clear style="width: 140px; margin-left: 6px" :loading="datesLoading">
        <a-select-option v-for="d in availableDates" :key="d" :value="d">{{ d }}</a-select-option>
      </a-select>
      <a-button :loading="searchLoading" style="margin-left: 6px" @click="doSearch">搜索</a-button>
      <span v-if="mode === 'search'" class="search-hint">
        搜索结果 — 共 {{ searchTotal }} 条
        <a-button type="link" size="small" @click="switchToTail">返回最新</a-button>
      </span>
    </div>

    <!-- 日志列表 -->
    <div class="log-wrapper">
      <div v-if="displayLines.length === 0 && !loading" class="log-empty">
        {{ mode === 'tail' ? '暂无日志' : '未找到匹配的日志' }}
      </div>
      <DynamicScroller
        v-if="displayLines.length > 0"
        :items="displayLines"
        :min-item-size="22"
        class="log-scroller"
        ref="scrollerRef"
        @scroll="onScroll"
      >
        <template #default="{ item, active }">
          <DynamicScrollerItem :item="item" :active="active" :size-dependencies="[]">
            <div class="log-line" :class="lineClass(item)" @click="copyText(item)" :title="'点击复制'">{{ item }}</div>
          </DynamicScrollerItem>
        </template>
      </DynamicScroller>
      <div v-if="loading" class="log-loading">加载中...</div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted, nextTick } from 'vue'
import { ReloadOutlined } from '@ant-design/icons-vue'
import { message } from 'ant-design-vue'
import { DynamicScroller, DynamicScrollerItem } from 'vue-virtual-scroller'
import 'vue-virtual-scroller/dist/vue-virtual-scroller.css'
import { getAuthHeaders } from '@/utils/http-client'
import { searchLogs, getLogDates } from '@/api/logs'

const PAGE_SIZE = 500

// ===== 模式 =====
const mode = ref<'tail' | 'search'>('tail')
const tailLines = ref<string[]>([])
const searchLines = ref<{ lines: { content: string }[], total: number }>({ lines: [], total: 0 })
const displayLines = computed(() => mode.value === 'tail' ? tailLines.value : searchLines.value.lines.map(l => l.content))
const searchTotal = computed(() => searchLines.value.total)
const loading = ref(false)
const hasMore = ref(true)

// ===== 搜索条件 =====
const searchKeyword = ref('')
const searchLevel = ref<string | undefined>(undefined)
const searchDate = ref<string | undefined>(undefined)
const searchLoading = ref(false)
const datesLoading = ref(false)
const availableDates = ref<string[]>([])

// ===== 滚动 =====
const scrollerRef = ref<any>(null)
const autoScroll = ref(true)

// ===== Tail 模式 =====

async function loadTail(skip: number): Promise<string[]> {
  try {
    const authHeaders = await getAuthHeaders()
    const res = await fetch(`/api/logs/tail?maxLines=${PAGE_SIZE}&skip=${skip}`, { headers: { ...authHeaders } })
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
  if (olderLines.length === 0) { hasMore.value = false; loading.value = false; return }
  tailLines.value = [...olderLines, ...tailLines.value]
  hasMore.value = olderLines.length >= PAGE_SIZE
  loading.value = false
  await nextTick()
  // 保持滚动位置
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
      size: 5000  // 一次性取大量结果
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
  autoScroll.value = true
  nextTick(() => scrollToBottom())
}

// ===== 滚动事件 =====

function onScroll(event: any) {
  const target = event.target || event
  if (!target) return
  // 到达顶部加载更多（仅 tail 模式）
  if (target.scrollTop <= 5 && mode.value === 'tail' && !loading.value && hasMore.value) {
    loadMore()
  }
  // 判断是否在底部（自动滚动开关）
  const threshold = 100
  autoScroll.value = (target.scrollHeight - target.scrollTop - target.clientHeight) < threshold
}

/** 滚动到底部（直接操作 DOM scrollTop） */
function scrollToBottom() {
  if (!autoScroll.value) return
  const el = scrollerRef.value?.$el as HTMLElement | undefined
  if (el) {
    // 多次尝试确保渲染完成后滚动成功
    const doScroll = () => { el.scrollTop = el.scrollHeight }
    doScroll()
    setTimeout(doScroll, 100)
    setTimeout(doScroll, 300)
  }
}

// ===== 通用 =====

function lineClass(line: string): string {
  if (line.includes(' ERROR ') || line.startsWith('ERROR')) return 'level-error'
  if (line.includes(' WARN ') || line.startsWith('WARN')) return 'level-warn'
  if (line.includes(' INFO ') || line.startsWith('INFO')) return 'level-info'
  if (line.includes(' DEBUG ') || line.startsWith('DEBUG')) return 'level-debug'
  return ''
}

function copyText(text: string) {
  navigator.clipboard.writeText(text).then(() => message.success('已复制')).catch(() => {
    const ta = document.createElement('textarea')
    ta.value = text
    document.body.appendChild(ta)
    ta.select()
    document.execCommand('copy')
    document.body.removeChild(ta)
    message.success('已复制')
  })
}

onMounted(() => { initLoad() })
</script>

<style scoped>
.log-container {
  padding: 16px;
  height: 100%;
  display: flex;
  flex-direction: column;
  background: #f5f7fa;
}
.page-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 8px;
  padding: 12px 20px;
  background: white;
  border-radius: 8px;
  box-shadow: 0 1px 4px rgba(0, 0, 0, 0.06);
}
.page-title {
  margin: 0;
  font-size: 20px;
  font-weight: 600;
  color: #1a202c;
}
.search-bar {
  display: flex;
  align-items: center;
  padding: 10px 16px;
  background: white;
  border-radius: 8px;
  box-shadow: 0 1px 4px rgba(0, 0, 0, 0.06);
  margin-bottom: 8px;
  flex-shrink: 0;
  flex-wrap: wrap;
  gap: 2px;
}
.search-hint {
  margin-left: 12px;
  font-size: 13px;
  color: #8c8c8c;
}
.log-wrapper {
  flex: 1;
  background: #1e1e1e;
  border-radius: 8px;
  overflow: hidden;
  position: relative;
}
.log-scroller {
  height: 100%;
}
.log-empty, .log-loading {
  display: flex;
  align-items: center;
  justify-content: center;
  height: 100%;
  color: #8c8c8c;
  font-size: 14px;
}
.log-line {
  padding: 1px 12px;
  font-family: 'Cascadia Code', 'Fira Code', 'Consolas', monospace;
  font-size: 12px;
  line-height: 22px;
  color: #d4d4d4;
  cursor: pointer;
  white-space: nowrap;
  min-height: 22px;
  text-align: left;
}
.log-line:hover {
  background: rgba(255, 255, 255, 0.06);
}
.level-error { color: #f48771; }
.level-warn { color: #ffcc66; }
.level-info { color: #75beff; }
.level-debug { color: #8c8c8c; }
</style>
