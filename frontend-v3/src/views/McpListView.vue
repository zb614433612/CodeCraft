<!--
  MCP 服务器管理页（v2）
  ----------------------------------------------------------------------------
  布局：页面头（图标 + 说明 + 刷新/新增）→ 统计行（内联）→ 工具栏（搜索/状态筛选）→ 服务器行列表
  与旧版的差异：
    1) 旧版是 8 列表格（名称/类型/地址/前缀/权限/启用/状态/工具数/操作），
       新版改成「行卡片」：连接目标单独一行等宽展示，状态/权限用标签，操作按需出现；
    2) 失败信息不再挤在状态列，而是独立一行红字（可截断 + hover 看全文）；
    3) 启用开关放到行右侧，一眼可见且可点击切换；
    4) 新增「全部 / 已连接 / 未连接 / 连接失败」筛选与关键词搜索（名称/地址/命令）。
  数据接缝：
    props.servers        -> GET /mcp/servers
    emit('connect')      -> POST /mcp/servers/{id}/connect
    emit('disconnect')   -> POST /mcp/servers/{id}/disconnect
    emit('refresh')      -> POST /mcp/servers/{id}/refresh
    emit('toggle-enabled')-> PUT /mcp/servers/{id}（服务层会做必填校验，需提交完整配置）
    emit('save')         -> POST /mcp/servers（新增）/ PUT /mcp/servers/{id}（编辑）
    emit('delete')       -> DELETE /mcp/servers/{id}
-->
<template>
  <div class="cc-page-scroll">
    <div class="cc-page-inner">
      <!-- 页面头 -->
      <div class="cc-page-head">
        <div class="cc-page-head__icon">
          <svg class="cc-i"><use href="#i-link" /></svg>
        </div>
        <div>
          <h1 class="cc-page-head__title">MCP 服务器</h1>
          <p class="cc-page-head__sub">
            配置 CodeCraft 作为 MCP Client 连接的外部服务器，其工具将自动注册供 AI 调用
          </p>
        </div>
        <div class="cc-page-head__actions">
          <button class="cc-btn" :disabled="loading" @click="$emit('reload')">
            <svg class="cc-i cc-i--sm"><use href="#i-refresh" /></svg>刷新
          </button>
          <button class="cc-btn cc-btn--primary" @click="openCreate">
            <svg class="cc-i cc-i--sm"><use href="#i-plus" /></svg>新增服务器
          </button>
        </div>
      </div>

      <!-- 请求反馈：失败提示（loading 由刷新按钮禁用态表达，避免双份提示） -->
      <div
        v-if="error"
        style="
          margin-bottom: var(--sp-4);
          padding: var(--sp-2) var(--sp-3);
          border: 1px solid var(--danger);
          background: var(--danger-lt);
          color: var(--danger);
          border-radius: var(--r-sm);
          font-size: var(--fs-sm);
          line-height: 1.5;
        "
      >
        加载失败：{{ error }}
      </div>

      <!-- 统计（内联一行） -->
      <div class="cc-stats-inline" style="margin-bottom: var(--sp-4)">
        <span><b>{{ servers.length }}</b> 个服务器</span><span class="cc-dot" />
        <span><b>{{ connectedCount }}</b> 个已连接</span><span class="cc-dot" />
        <span>共注册 <b>{{ registeredToolTotal }}</b> 个工具</span>
        <template v-if="failedCount">
          <span class="cc-dot" />
          <span style="color: var(--danger)">{{ failedCount }} 个连接失败</span>
        </template>
      </div>

      <!-- 工具栏 -->
      <div class="cc-toolbar">
        <label class="cc-search">
          <svg class="cc-i cc-i--sm"><use href="#i-search" /></svg>
          <input v-model="keyword" placeholder="搜索服务器名称、地址或命令" />
        </label>

        <div class="cc-seg">
          <button
            v-for="opt in filterOptions"
            :key="opt.value"
            :class="{ 'is-active': filter === opt.value }"
            @click="filter = opt.value"
          >
            {{ opt.label }}
          </button>
        </div>

        <div style="margin-left: auto; font-size: var(--fs-sm); color: var(--text-3)">
          共 <b style="color: var(--text-1)">{{ filteredServers.length }}</b> 条
        </div>
      </div>

      <!-- 服务器列表 -->
      <div v-if="filteredServers.length" class="cc-mcp-list">
        <McpRow
          v-for="server in filteredServers"
          :key="server.id"
          :server="server"
          @connect="$emit('connect', $event)"
          @disconnect="$emit('disconnect', $event)"
          @refresh="$emit('refresh', $event)"
          @edit="openEdit"
          @delete="$emit('delete', $event.id)"
          @toggle-enabled="(v) => $emit('toggle-enabled', { server, enabled: v })"
        />
      </div>

      <!-- 空状态 -->
      <div v-else-if="!loading && !error" class="cc-agent-empty">
        <span class="cc-agent-empty__icon">
          <svg class="cc-i cc-i--lg"><use href="#i-link" /></svg>
        </span>
        <span>{{ keyword || filter !== 'all' ? '没有匹配的服务器' : '还没有配置 MCP 服务器' }}</span>
        <button
          v-if="!keyword && filter === 'all'"
          class="cc-btn cc-btn--primary"
          style="margin-top: var(--sp-2)"
          @click="openCreate"
        >
          <svg class="cc-i cc-i--sm"><use href="#i-plus" /></svg>新增服务器
        </button>
      </div>
    </div>

    <!-- 遮罩 + 抽屉 -->
    <div v-if="drawerOpen" class="cc-mask is-open" @click="drawerOpen = false" />
    <McpEditDrawer
      v-model:open="drawerOpen"
      :server="editingServer"
      @save="$emit('save', { ...$event, id: editingServer?.id })"
    />
  </div>
</template>

<script setup lang="ts">
import { computed, ref, onMounted } from 'vue'
import McpRow, { type McpServerItem } from '../components/McpRow.vue'
import McpEditDrawer from '../components/McpEditDrawer.vue'

type FilterValue = 'all' | 'connected' | 'disconnected' | 'failed'

const props = withDefaults(
  defineProps<{
    servers?: Array<McpServerItem & { headers?: string; headersConfigured?: boolean }>
    loading?: boolean
    /** 请求失败信息：显示红色错误条 */
    error?: string
    /**
     * 进入页面时的数据加载（由 App.vue 的 PAGE_BINDINGS 注入）
     * 页面随路由真实挂载，所以在这里调一次即可；重复进页由父级的「已加载 / 加载中 / 已失败」跳过
     */
    load?: () => void
  }>(),
  {
    servers: () => [],
    loading: false,
    error: ''
  }
)

defineEmits<{
  (e: 'reload'): void
  (e: 'connect', server: McpServerItem): void
  (e: 'disconnect', server: McpServerItem): void
  (e: 'refresh', server: McpServerItem): void
  (e: 'save', payload: Record<string, unknown>): void
  (e: 'delete', id: number | string): void
  (e: 'toggle-enabled', payload: { server: McpServerItem; enabled: 0 | 1 }): void
}>()

/* ---------------- 统计 ---------------- */
const connectedCount = computed(() => props.servers.filter(s => s.status === 'CONNECTED').length)
const failedCount = computed(() => props.servers.filter(s => s.status === 'FAILED').length)
const registeredToolTotal = computed(() =>
  props.servers
    .filter(s => s.status === 'CONNECTED')
    .reduce((acc, s) => acc + (s.registeredToolCount || 0), 0)
)

/* ---------------- 筛选 ---------------- */
const filter = ref<FilterValue>('all')
const keyword = ref('')

const filterOptions: Array<{ label: string; value: FilterValue }> = [
  { label: '全部', value: 'all' },
  { label: '已连接', value: 'connected' },
  { label: '未连接', value: 'disconnected' },
  { label: '连接失败', value: 'failed' }
]

const filteredServers = computed(() => {
  const kw = keyword.value.trim().toLowerCase()
  return props.servers.filter(s => {
    if (filter.value === 'connected' && s.status !== 'CONNECTED') return false
    if (filter.value === 'failed' && s.status !== 'FAILED') return false
    if (filter.value === 'disconnected' && (s.status === 'CONNECTED' || s.status === 'FAILED')) return false

    if (!kw) return true
    const haystack = [s.name, s.url || '', s.command || '', s.toolPrefix || ''].join(' ').toLowerCase()
    return haystack.includes(kw)
  })
})

/* ---------------- 抽屉 ---------------- */
const drawerOpen = ref(false)
const editingServer = ref<(McpServerItem & { headers?: string; headersConfigured?: boolean }) | null>(null)

function openCreate() {
  editingServer.value = null
  drawerOpen.value = true
}

function openEdit(server: McpServerItem) {
  editingServer.value = server as McpServerItem & { headers?: string; headersConfigured?: boolean }
  drawerOpen.value = true
}

/**
 * 「进入页面才拉数据」的触发点（原 App.vue 的 PAGE_LOADERS 已下沉至此）
 * 父级通过 props.load 注入具体加载函数，组件只负责在最合适的时机调用它。
 */
onMounted(() => props.load?.())
</script>
