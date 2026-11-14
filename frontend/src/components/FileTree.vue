<template>
  <div class="file-tree">
    <div class="ft-header" v-if="tree || loading">
      <span class="ft-current-path">{{ displayRoot || (loading ? '加载中...' : '') }}</span>
      <ReloadOutlined :class="['ft-reload', { loading: loading }]" @click="fetchTree" />
    </div>

    <!--
      文件搜索：
        树是一次性拉全量的（深度上限 10 层），深层文件可能没进树，
        所以搜索走后端 /api/project/search 全项目扫描（自动跳过 .git / node_modules / target）。
        输入 250ms 防抖；点击结果 → 与双击文件树同一条链路（emit dblclick 打开编辑标签）。
    -->
    <div class="ft-search">
      <SearchOutlined class="ft-search-icon" />
      <input
        v-model="keyword"
        class="ft-search-input"
        type="text"
        placeholder="搜索文件…（Esc 清空）"
        spellcheck="false"
        @keydown.esc="clearSearch"
      />
      <CloseCircleOutlined v-if="keyword" class="ft-search-clear" title="清空搜索（Esc）" @click="clearSearch" />
    </div>

    <!-- 搜索结果：命中优先于文件树展示，清空后自动回到树 -->
    <div v-if="searching" class="ft-search-result">
      <div v-if="searchLoading" class="ft-loading">
        <a-spin size="small" />
        <span>搜索中...</span>
      </div>
      <div v-else-if="searchError" class="ft-error">{{ searchError }}</div>
      <div v-else-if="!searchResults.length" class="ft-empty">
        <span>没有匹配的文件</span>
      </div>
      <template v-else>
        <div
          v-for="hit in searchResults"
          :key="hit"
          class="ft-search-row"
          :title="hit"
          @click="onSearchPick(hit)"
        >
          <FileOutlined class="ft-search-file" />
          <span class="ft-search-name">{{ hitName(hit) }}</span>
          <span class="ft-search-dir">{{ hitDir(hit) }}</span>
        </div>
        <div class="ft-search-foot">共 {{ searchResults.length }} 个结果（最多 100 个）</div>
      </template>
    </div>

    <div v-else-if="loading" class="ft-loading">
      <a-spin size="small" />
      <span>加载文件树...</span>
    </div>
    <div v-else-if="error" class="ft-error">{{ error }}</div>
    <div v-else-if="!tree" class="ft-empty">
      <span>请设置工作目录后点击「应用」</span>
    </div>
    <div v-else-if="isEmpty" class="ft-empty">
      <span>该目录下没有可显示的文件或文件夹</span>
    </div>
    <div v-else class="ft-tree">
      <TreeNode
        v-for="node in tree!.children!"
        :key="node.path"
        :node="node"
        :selected-path="selectedPath"
        @select="onSelect"
        @dblclick="onDblClick"
      />
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, watch, onBeforeUnmount } from 'vue'
import {
  getProjectTree,
  searchProjectFiles,
  type ProjectTreeNode
} from '@/api/project'
import { ReloadOutlined, SearchOutlined, CloseCircleOutlined, FileOutlined } from '@ant-design/icons-vue'
import TreeNode from './TreeNode.vue'

const emit = defineEmits<{
  select: [path: string, isDirectory: boolean]
  dblclick: [path: string, isDirectory: boolean]
}>()

const props = defineProps<{
  rootPath?: string
}>()

const tree = ref<ProjectTreeNode | null>(null)
const loading = ref(false)
const error = ref('')
const selectedPath = ref('')

/* ============================ 文件搜索 ============================ */

/** 搜索关键字：trim 后非空即切到「结果视图」 */
const keyword = ref('')
const searchLoading = ref(false)
const searchError = ref('')
const searchResults = ref<string[]>([])
/** 请求序号：快速输入时旧响应可能后到，用它丢弃过期结果 */
let searchSeq = 0
/** 防抖定时器 */
let searchTimer = 0

const searching = computed(() => keyword.value.trim().length > 0)

/** 命中路径的文件名 */
function hitName(path: string): string {
  const normalized = path.replace(/\\/g, '/').replace(/\/+$/, '')
  return normalized.split('/').pop() || normalized
}

/** 命中文件所在目录（根目录下返回空串） */
function hitDir(path: string): string {
  const normalized = path.replace(/\\/g, '/')
  const idx = normalized.lastIndexOf('/')
  return idx > 0 ? normalized.slice(0, idx) : ''
}

/** 相对路径补成绝对路径（后端 search 返回相对路径，父级打开文件需要绝对路径） */
function toAbsolute(p: string): string {
  const normalized = p.replace(/\\/g, '/')
  if (/^[a-zA-Z]:[\\/]/.test(normalized) || normalized.startsWith('/')) return normalized
  const base = (props.rootPath || '').replace(/\\/g, '/').replace(/\/+$/, '')
  return base ? `${base}/${normalized.replace(/^\/+/, '')}` : normalized
}

async function runSearch(kw: string) {
  if (!props.rootPath) {
    searchResults.value = []
    searchError.value = '还没有设置工作目录'
    return
  }
  const seq = ++searchSeq
  searchLoading.value = true
  searchError.value = ''
  try {
    const res = await searchProjectFiles(props.rootPath, kw, 100)
    if (seq !== searchSeq) return
    searchResults.value = (res?.data || []).filter((p): p is string => typeof p === 'string' && !!p)
  } catch (e: any) {
    if (seq !== searchSeq) return
    searchResults.value = []
    searchError.value = e?.message || '搜索失败'
  } finally {
    if (seq === searchSeq) searchLoading.value = false
  }
}

/** 清空搜索并回到文件树（不主动聚焦） */
function resetSearch() {
  if (searchTimer) window.clearTimeout(searchTimer)
  keyword.value = ''
  searchResults.value = []
  searchError.value = ''
  searchLoading.value = false
  searchSeq++
}

function clearSearch() {
  resetSearch()
}

/** 搜索结果点击：与双击文件树同一条链路（父级据此打开编辑标签） */
function onSearchPick(hit: string) {
  const target = toAbsolute(hit)
  // 选中即完成：清空搜索回到文件树，避免结果列表一直盖着树
  resetSearch()
  emit('dblclick', target, false)
}

watch(keyword, () => {
  const kw = keyword.value.trim()
  if (searchTimer) window.clearTimeout(searchTimer)
  if (!kw) {
    searchResults.value = []
    searchError.value = ''
    searchLoading.value = false
    searchSeq++
    return
  }
  searchTimer = window.setTimeout(() => void runSearch(kw), 250)
})

onBeforeUnmount(() => {
  if (searchTimer) window.clearTimeout(searchTimer)
  searchSeq++
})

const displayRoot = computed(() => {
  if (!tree.value?.path || tree.value.path === '.') return ''
  return tree.value.path
})

const isEmpty = computed(() => {
  return tree.value && (!tree.value.children || tree.value.children.length === 0)
})

const fetchTree = async () => {
  if (!props.rootPath) return
  loading.value = true
  error.value = ''
  try {
    const res = await getProjectTree(props.rootPath, 10)
    if (res.code === 200 && res.data) {
      tree.value = res.data
    } else {
      error.value = res.message || '获取文件树失败'
      tree.value = null
    }
  } catch (e: any) {
    error.value = e.message || '网络错误'
    tree.value = null
  } finally {
    loading.value = false
  }
}

// 监听 rootPath prop 变化，自动加载
watch(() => props.rootPath, (newPath) => {
  // 换目录后旧关键字的结果失去意义，必须清掉
  resetSearch()
  if (newPath) {
    tree.value = null
    error.value = ''
    fetchTree()
  } else {
    // rootPath 被清空时，同时清空文件树
    tree.value = null
    error.value = ''
  }
}, { immediate: true })

defineExpose({ fetchTree })

const onSelect = (path: string, isDirectory: boolean) => {
  selectedPath.value = path
  emit('select', path, isDirectory)
}

const onDblClick = (path: string, isDirectory: boolean) => {
  emit('dblclick', path, isDirectory)
}
</script>

<style scoped>
.file-tree {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  font-size: 13px;
}
.ft-header {
  display: flex;
  align-items: center;
  gap: 4px;
  padding: 6px 8px;
  border-bottom: 1px solid var(--border, #eef2f7);
  background: var(--bg-card, #f8fafc);
}
.ft-current-path {
  flex: 1;
  font-size: 11px;
  color: var(--text-3, #8c8c8c);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.ft-reload {
  cursor: pointer;
  font-size: 14px;
  color: var(--text-3, #8c8c8c);
  flex-shrink: 0;
  transition: color 0.2s, transform 0.2s;
}
.ft-reload:hover { color: var(--accent, #1890ff); }
.ft-reload.loading { animation: spin 1s linear infinite; color: var(--accent, #1890ff); }
@keyframes spin { from { transform: rotate(0deg); } to { transform: rotate(360deg); } }
.ft-loading {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  padding: 30px 12px;
  color: var(--text-3, #8c8c8c);
}
.ft-error {
  padding: 20px 12px;
  color: #ff4d4f;
  text-align: center;
  font-size: 12px;
}
.ft-empty {
  padding: 30px 12px;
  text-align: center;
  color: var(--text-3, #bbb);
  font-size: 12px;
}
.ft-tree {
  padding: 4px 0;
}

/* ---- 搜索行（sticky：树很长时也不会滚走） ---- */
.ft-search {
  position: sticky;
  top: 0;
  z-index: 2;
  display: flex;
  align-items: center;
  gap: 5px;
  height: 26px;
  margin: 6px 8px;
  padding: 0 6px;
  border: 1px solid var(--border, #eef2f7);
  border-radius: 4px;
  background: var(--bg-card, #fff);
  transition: border-color 0.2s;
}
.ft-search:focus-within { border-color: var(--accent, #1890ff); }
.ft-search-icon { flex: none; font-size: 12px; color: var(--text-3, #8c8c8c); }
.ft-search-input {
  flex: 1;
  min-width: 0;
  height: 100%;
  border: none;
  outline: none;
  background: transparent;
  font-size: 12px;
  color: var(--text-1, #333);
}
.ft-search-input::placeholder { color: var(--text-3, #bbb); }
.ft-search-clear { flex: none; font-size: 12px; color: var(--text-3, #bbb); cursor: pointer; }
.ft-search-clear:hover { color: var(--accent, #1890ff); }
.ft-search-result { padding-bottom: 6px; }
.ft-search-row {
  display: flex;
  align-items: center;
  gap: 6px;
  height: 28px;
  padding: 0 10px;
  cursor: pointer;
}
.ft-search-row:hover { background: var(--bg-hover, #f5f7fa); }
.ft-search-file { flex: none; font-size: 12px; color: var(--text-3, #8c8c8c); }
.ft-search-name {
  flex: none;
  max-width: 55%;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-size: 13px;
  color: var(--text-1, #333);
}
.ft-search-dir {
  margin-left: auto;
  max-width: 45%;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-size: 11px;
  color: var(--text-3, #8c8c8c);
}
.ft-search-foot {
  padding: 6px 10px;
  text-align: center;
  font-size: 11px;
  color: var(--text-3, #bbb);
}

/* 暗色模式适配 */
[data-theme="dark"] .ft-header {
  border-color: #2a2838;
  background: #1a1925;
}
[data-theme="dark"] .ft-current-path {
  color: #9696aa;
}
[data-theme="dark"] .ft-reload {
  color: #9696aa;
}
[data-theme="dark"] .ft-reload:hover,
[data-theme="dark"] .ft-reload.loading {
  color: #a78bfa;
}
[data-theme="dark"] .ft-loading {
  color: #9696aa;
}
[data-theme="dark"] .ft-empty {
  color: #6b6b80;
}
[data-theme="dark"] .ft-search {
  border-color: #2a2838;
  background: #1a1925;
}
[data-theme="dark"] .ft-search-input {
  color: #e6e6f0;
}
[data-theme="dark"] .ft-search-row:hover {
  background: #24222f;
}
[data-theme="dark"] .ft-search-name {
  color: #e6e6f0;
}
[data-theme="dark"] .ft-search-dir {
  color: #9696aa;
}
</style>
