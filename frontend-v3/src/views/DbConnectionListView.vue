<!--
  数据库连接管理页（v2）
  ----------------------------------------------------------------------------
  布局：页面头（图标 + 说明 + 刷新/新建）→ 统计（内联一行）→ 工具栏（搜索 / 类型 / 状态）
       → 连接行列表（类型图标 + 连接名 + JDBC 连接串 + 元信息 + 启用开关 + hover 操作）
  设计要点：
    1) 密码只写不读：列表只显示「密码已配置 / 未配置密码」，编辑时留空表示不修改；
    2) 停用态整行降饱和（与定时任务、踩坑经验一致），不用红色表达；
    3) 「执行 SQL」是独立抽屉（720px）：管理页宽松模式，支持多条语句与多结果块；
    4) 测试连接结果就地落在行内 / 抽屉内，不弹 toast。
  数据接缝：
    props.connections    -> GET /api/db-connections
    props.loading        -> 请求进行中（禁用刷新按钮）
    props.error          -> 加载失败原因（页面顶部红色提示条）
    props.rowTestStates  -> POST /api/db-connections/{id}/test 的结果（按连接 id 索引）
    props.editTestState  -> POST /api/db-connections/test 的结果（编辑抽屉内）
    props.executeResult  -> POST /api/db-connections/{id}/execute 的返回
    emit('save')         -> POST /api/db-connections | PUT /api/db-connections/{id}
    emit('delete')       -> DELETE /api/db-connections/{id}
    emit('toggle')       -> PUT /api/db-connections/{id}（全量提交：后端 update 会覆盖所有列）
    emit('test')         -> POST /api/db-connections/{id}/test
    emit('execute')      -> POST /api/db-connections/{id}/execute
    emit('refresh')      -> 重新拉取列表
-->
<template>
  <div class="cc-page-scroll">
    <div class="cc-page-inner">
      <!-- 页面头 -->
      <div class="cc-page-head">
        <div class="cc-page-head__icon">
          <svg class="cc-i"><use href="#i-db" /></svg>
        </div>
        <div>
          <h1 class="cc-page-head__title">数据库连接</h1>
          <p class="cc-page-head__sub">
            配置 AI 可通过 execute_sql 访问的外部业务库（MySQL / PostgreSQL / H2），密码加密存储且不回显
          </p>
        </div>
        <div class="cc-page-head__actions">
          <button class="cc-btn" :disabled="loading" @click="$emit('refresh')">
            <svg class="cc-i cc-i--sm"><use href="#i-refresh" /></svg>刷新
          </button>
          <button class="cc-btn cc-btn--primary" @click="openEdit()">
            <svg class="cc-i cc-i--sm"><use href="#i-plus" /></svg>新建连接
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
        <span>共 <b>{{ connections.length }}</b> 个连接</span><span class="cc-dot" />
        <span>已启用 <b>{{ enabledCount }}</b></span><span class="cc-dot" />
        <span>已停用 <b>{{ disabledCount }}</b></span><span class="cc-dot" />
        <span>未配置密码 <b>{{ noPasswordCount }}</b></span><span class="cc-dot" />
        <span>系统级共享 <b>{{ systemCount }}</b></span>
      </div>

      <!-- 工具栏 -->
      <div class="cc-toolbar">
        <label class="cc-search">
          <svg class="cc-i cc-i--sm"><use href="#i-search" /></svg>
          <input v-model="keyword" placeholder="搜索连接名称、主机或库名" />
        </label>

        <div class="cc-seg">
          <button
            v-for="opt in TYPE_OPTIONS"
            :key="opt.value"
            :class="{ 'is-active': typeFilter === opt.value }"
            @click="typeFilter = opt.value"
          >
            {{ opt.label }}
          </button>
        </div>

        <select v-model="statusFilter" class="cc-select" style="width: 128px; height: 34px">
          <option value="all">全部状态</option>
          <option value="1">已启用</option>
          <option value="0">已停用</option>
        </select>

        <div style="margin-left: auto; font-size: var(--fs-sm); color: var(--text-3)">
          共 <b style="color: var(--text-1)">{{ filteredConnections.length }}</b> 个
        </div>
      </div>

      <!-- 连接列表 -->
      <div v-if="filteredConnections.length" class="cc-db-list">
        <DbConnectionRow
          v-for="db in filteredConnections"
          :key="db.id"
          :db="db"
          :test-state="rowTestStates[String(db.id)] || null"
          @test="$emit('test', $event)"
          @execute="openExecute"
          @edit="openEdit"
          @delete="onDelete"
          @toggle="$emit('toggle', $event)"
        />
      </div>

      <!-- 空状态 -->
      <div v-else-if="!loading && !error" class="cc-agent-empty">
        <span class="cc-agent-empty__icon">
          <svg class="cc-i cc-i--lg"><use href="#i-db" /></svg>
        </span>
        <span>{{ hasFilter ? '没有匹配的连接' : '还没有配置外部数据库连接' }}</span>
        <span v-if="!hasFilter" style="font-size: var(--fs-xs); color: var(--text-4)">
          不配置时 AI 的 execute_sql 仍可访问 CodeCraft 自身系统库
        </span>
        <button v-else class="cc-btn" style="margin-top: var(--sp-2)" @click="resetFilter">
          <svg class="cc-i cc-i--sm"><use href="#i-refresh" /></svg>清空筛选
        </button>
      </div>
    </div>

    <!-- 遮罩 + 两个抽屉 -->
    <div v-if="editOpen || execOpen" class="cc-mask is-open" @click="closeAll" />

    <DbConnectionEditDrawer
      v-model:open="editOpen"
      :connection="editing"
      :test-state="editTestState"
      @save="onSave"
      @test="$emit('test-form', $event)"
    />

    <DbExecuteDrawer
      v-model:open="execOpen"
      :connection="executing"
      :result="executeResult"
      :error="executeError"
      :running="executingRequest"
      @execute="$emit('execute', $event)"
    />
  </div>
</template>

<script setup lang="ts">
import { computed, ref, onMounted } from 'vue'
import DbConnectionRow, { type DbConnectionItem, type DbTestState } from '../components/DbConnectionRow.vue'
import DbConnectionEditDrawer from '../components/DbConnectionEditDrawer.vue'
import DbExecuteDrawer, { type SqlExecuteResult } from '../components/DbExecuteDrawer.vue'

type TypeFilter = 'all' | 'mysql' | 'postgresql' | 'h2'

const props = withDefaults(
  defineProps<{
    connections?: DbConnectionItem[]
    /** 请求进行中（禁用刷新按钮，避免重复提交） */
    loading?: boolean
    /** 加载失败原因（父组件从接口异常中提取，空串表示无错误） */
    error?: string
    /** 行内「测试连接」结果，按连接 id 索引 */
    rowTestStates?: Record<string, DbTestState>
    /** 编辑抽屉内「测试连接」结果 */
    editTestState?: DbTestState | null
    /** 执行 SQL 结果 / 错误 / 进行中标记 */
    executeResult?: SqlExecuteResult | null
    executeError?: string
    executingRequest?: boolean
    /**
     * 进入页面时的数据加载（由 App.vue 的 PAGE_BINDINGS 注入）
     * 页面随路由真实挂载，所以在这里调一次即可；重复进页由父级的「已加载 / 加载中 / 已失败」跳过
     */
    load?: () => void
  }>(),
  {
    connections: () => [],
    loading: false,
    error: '',
    rowTestStates: () => ({}),
    editTestState: null,
    executeResult: null,
    executeError: '',
    executingRequest: false
  }
)

const emit = defineEmits<{
  (e: 'save', payload: Record<string, unknown>): void
  (e: 'delete', id: number | string): void
  (e: 'toggle', db: DbConnectionItem): void
  /** 行内「测试连接」：payload 为连接行（含 id） */
  (e: 'test', db: DbConnectionItem): void
  /** 编辑抽屉内「测试连接」：payload 为表单配置（新建时无 id） */
  (e: 'test-form', payload: Record<string, unknown>): void
  (e: 'execute', payload: { id: number | string; sql: string; maxRows: number }): void
  (e: 'refresh'): void
}>()

/* ---------------- 统计 ---------------- */
const enabledCount = computed(() => props.connections.filter(c => Number(c.enabled) === 1).length)
const disabledCount = computed(() => props.connections.filter(c => Number(c.enabled) !== 1).length)
/** 后端脱敏后的 hasPassword=false 即视为「未配置密码」（h2 + sa 允许空密码） */
const noPasswordCount = computed(() => props.connections.filter(c => !c.hasPassword).length)
const systemCount = computed(() => props.connections.filter(c => c.userId == null).length)

/* ---------------- 筛选 ---------------- */
const keyword = ref('')
const typeFilter = ref<TypeFilter>('all')
const statusFilter = ref<'all' | '1' | '0'>('all')

const TYPE_OPTIONS: Array<{ label: string; value: TypeFilter }> = [
  { label: '全部', value: 'all' },
  { label: 'MySQL', value: 'mysql' },
  { label: 'PostgreSQL', value: 'postgresql' },
  { label: 'H2', value: 'h2' }
]

const hasFilter = computed(() => Boolean(keyword.value.trim()) || typeFilter.value !== 'all' || statusFilter.value !== 'all')

function resetFilter() {
  keyword.value = ''
  typeFilter.value = 'all'
  statusFilter.value = 'all'
}

const filteredConnections = computed(() => {
  const kw = keyword.value.trim().toLowerCase()
  return props.connections.filter(c => {
    if (typeFilter.value !== 'all' && (c.dbType || 'mysql').toLowerCase() !== typeFilter.value) return false
    if (statusFilter.value !== 'all' && String(Number(c.enabled) === 1 ? 1 : 0) !== statusFilter.value) return false
    if (!kw) return true
    const haystack = [c.name, c.host, c.databaseName, c.username, c.dbType, c.extraParams]
      .filter(Boolean)
      .join(' ')
      .toLowerCase()
    return haystack.includes(kw)
  })
})

/* ---------------- 编辑抽屉 ---------------- */
const editOpen = ref(false)
const editing = ref<DbConnectionItem | null>(null)

/** 不传参数即为新建 */
function openEdit(db?: DbConnectionItem) {
  editing.value = db || null
  editOpen.value = true
}

function onSave(payload: Record<string, unknown>) {
  emit('save', payload)
  // 乐观关闭：真实请求失败时由父组件重新打开抽屉并提示错误
  editOpen.value = false
}

function onDelete(db: DbConnectionItem) {
  emit('delete', db.id)
}

/* ---------------- 执行 SQL 抽屉 ---------------- */
const execOpen = ref(false)
const executing = ref<DbConnectionItem | null>(null)

function openExecute(db: DbConnectionItem) {
  executing.value = db
  execOpen.value = true
}

function closeAll() {
  editOpen.value = false
  execOpen.value = false
}

/**
 * 「进入页面才拉数据」的触发点（原 App.vue 的 PAGE_LOADERS 已下沉至此）
 * 父级通过 props.load 注入具体加载函数，组件只负责在最合适的时机调用它。
 */
onMounted(() => props.load?.())
</script>
