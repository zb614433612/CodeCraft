<!--
  定时任务管理页（v2）
  ----------------------------------------------------------------------------
  布局：页面头（图标 + 说明 + 新建）→ 统计行（内联）→ 工具栏（搜索/状态/Agent）→ 任务行列表
  与旧版的差异：
    1) 旧版是「4 张统计卡 + 大内容卡 + 表格」，新版收敛为「页面头 + 一行统计 + 工具栏 + 行列表」；
    2) 旧版执行计划列同时用 emoji + 代码块表达，新版统一为「中文描述 + cron 原文」；
    3) 已禁用任务整行降饱和（不再靠开关颜色区分）；
    4) 新增「立即执行」按钮（旧版只能等调度触发）；
    5) 新增搜索（名称/指令）与状态/Agent 筛选。
  数据接缝：
    props.tasks  -> GET /schedule-task/list
    emit('save') -> POST /schedule-task（新建）/ PUT /schedule-task/{id}（编辑）
    emit('toggle-status') -> enable / disable 接口
    emit('run')  -> 立即执行（后端可复用一次会话发起）
    emit('delete') -> DELETE /schedule-task/{id}
    emit('view-conversation') -> 跳转 lastConversationId 对应会话
-->
<template>
  <div class="cc-page-scroll">
    <div class="cc-page-inner">
      <!-- 页面头 -->
      <div class="cc-page-head">
        <div class="cc-page-head__icon">
          <svg class="cc-i"><use href="#i-clock" /></svg>
        </div>
        <div>
          <h1 class="cc-page-head__title">定时任务</h1>
          <p class="cc-page-head__sub">按 Cron 或指定时间自动执行 Agent 指令</p>
        </div>
        <div class="cc-page-head__actions">
          <button class="cc-btn cc-btn--primary" @click="openCreate">
            <svg class="cc-i cc-i--sm"><use href="#i-plus" /></svg>新建任务
          </button>
        </div>
      </div>

      <!-- 统计（内联一行） -->
      <div class="cc-stats-inline" style="margin-bottom: var(--sp-4)">
        <span><b>{{ tasks.length }}</b> 个任务</span><span class="cc-dot" />
        <span><b>{{ enabledCount }}</b> 个启用中</span><span class="cc-dot" />
        <span>今日已执行 <b>{{ todayExecutedCount }}</b> 次</span>
      </div>

      <!-- 加载中 / 接口失败提示（内联样式，不新增 CSS 类） -->
      <div
        v-if="loading || error"
        :style="{
          marginBottom: 'var(--sp-3)',
          fontSize: 'var(--fs-sm)',
          color: error ? 'var(--danger)' : 'var(--text-3)'
        }"
      >
        {{ error || '正在加载定时任务…' }}
      </div>

      <!-- 工具栏 -->
      <div class="cc-toolbar">
        <label class="cc-search">
          <svg class="cc-i cc-i--sm"><use href="#i-search" /></svg>
          <input v-model="keyword" placeholder="搜索任务名称或指令内容" />
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

        <select v-model="agentFilter" class="cc-select" style="width: 150px; height: 34px">
          <option :value="null">全部 Agent</option>
          <option v-for="a in agents" :key="a.id" :value="a.id">{{ a.name }}</option>
        </select>

        <div style="margin-left: auto; font-size: var(--fs-sm); color: var(--text-3)">
          共 <b style="color: var(--text-1)">{{ filteredTasks.length }}</b> 条
        </div>
      </div>

      <!-- 任务列表 -->
      <div v-if="filteredTasks.length" class="cc-sched-list">
        <ScheduleTaskRow
          v-for="task in filteredTasks"
          :key="task.id"
          :task="withAgentName(task)"
          :run-busy="Number(task.id) === runBusyId"
          @edit="openEdit"
          @delete="onDelete"
          @toggle-status="(enabled) => $emit('toggle-status', { task, enabled })"
          @run="$emit('run', $event)"
          @view-conversation="$emit('view-conversation', $event)"
        />
      </div>

      <!-- 空状态 -->
      <div v-else class="cc-agent-empty">
        <span class="cc-agent-empty__icon">
          <svg class="cc-i cc-i--lg"><use href="#i-clock" /></svg>
        </span>
        <span>{{ keyword || filter !== 'all' ? '没有匹配的任务' : '还没有定时任务' }}</span>
        <button
          v-if="!keyword && filter === 'all'"
          class="cc-btn cc-btn--primary"
          style="margin-top: var(--sp-2)"
          @click="openCreate"
        >
          <svg class="cc-i cc-i--sm"><use href="#i-plus" /></svg>新建任务
        </button>
      </div>
    </div>

    <!-- 遮罩 + 抽屉 -->
    <div v-if="drawerOpen" class="cc-mask is-open" @click="drawerOpen = false" />
    <ScheduleTaskEditDrawer
      v-model:open="drawerOpen"
      :task="editingTask"
      :agents="agents"
      @save="$emit('save', { ...$event, id: editingTask?.id })"
    />
  </div>
</template>

<script setup lang="ts">
import { computed, ref, onMounted } from 'vue'
import ScheduleTaskRow, { type ScheduleTaskItem } from '../components/ScheduleTaskRow.vue'
import ScheduleTaskEditDrawer from '../components/ScheduleTaskEditDrawer.vue'

type FilterValue = 'all' | 'enabled' | 'disabled'

const props = withDefaults(
  defineProps<{
    tasks?: Array<ScheduleTaskItem & { enabled?: 0 | 1 }>
    /** Agent 列表：用于补全 agentName 与下拉筛选 */
    agents?: Array<{ id: number; name: string }>
    /** 首次加载中（GET /schedule-task/list） */
    loading?: boolean
    /** 接口失败原因（非空时展示提示条） */
    error?: string
    /** 正在「立即执行」的任务 id（0 表示空闲）：用于对应行的按钮 loading 态 */
    runBusyId?: number
    /**
     * 进入页面时的数据加载（由 App.vue 的 PAGE_BINDINGS 注入）
     * 页面随路由真实挂载，所以在这里调一次即可；重复进页由父级的「已加载 / 加载中 / 已失败」跳过
     */
    load?: () => void
  }>(),
  {
    tasks: () => [],
    agents: () => [],
    loading: false,
    error: '',
    runBusyId: 0
  }
)

const emit = defineEmits<{
  (e: 'save', payload: Record<string, unknown>): void
  (e: 'delete', id: number | string): void
  (e: 'run', task: ScheduleTaskItem): void
  (e: 'toggle-status', payload: { task: ScheduleTaskItem; enabled: boolean }): void
  (e: 'view-conversation', task: ScheduleTaskItem): void
}>()

/* ---------------- 统计 ---------------- */
const enabledCount = computed(() => props.tasks.filter(t => t.status === 'ENABLED').length)

/** 今日已执行：按 lastExecuteTime 落在今天统计（与旧版口径一致） */
const todayExecutedCount = computed(() => {
  const today = new Date().toDateString()
  return props.tasks.filter(t => {
    if (!t.lastExecuteTime) return false
    const d = new Date(t.lastExecuteTime.replace(/-/g, '/'))
    return !Number.isNaN(d.getTime()) && d.toDateString() === today
  }).length
})

/* ---------------- 筛选 ---------------- */
const filter = ref<FilterValue>('all')
const keyword = ref('')
const agentFilter = ref<number | null>(null)

const filterOptions: Array<{ label: string; value: FilterValue }> = [
  { label: '全部', value: 'all' },
  { label: '启用中', value: 'enabled' },
  { label: '已禁用', value: 'disabled' }
]

const filteredTasks = computed(() => {
  const kw = keyword.value.trim().toLowerCase()
  return props.tasks.filter(t => {
    if (filter.value === 'enabled' && t.status !== 'ENABLED') return false
    if (filter.value === 'disabled' && t.status === 'ENABLED') return false

    if (agentFilter.value !== null && t.agentConfigId !== agentFilter.value) return false

    if (!kw) return true
    return `${t.name} ${t.instruction || ''}`.toLowerCase().includes(kw)
  })
})

/** 列表接口未返回 agentName 时，用 agents 列表补全 */
function withAgentName(task: ScheduleTaskItem): ScheduleTaskItem {
  if (task.agentName) return task
  const agent = props.agents.find(a => a.id === task.agentConfigId)
  return agent ? { ...task, agentName: agent.name } : task
}

/** 行内删除：只上报 id，确认框与接口调用由父组件负责 */
function onDelete(task: ScheduleTaskItem) {
  emit('delete', task.id)
}

/* ---------------- 抽屉 ---------------- */
const drawerOpen = ref(false)
const editingTask = ref<(ScheduleTaskItem & { enabled?: 0 | 1 }) | null>(null)

function openCreate() {
  editingTask.value = null
  drawerOpen.value = true
}

function openEdit(task: ScheduleTaskItem) {
  editingTask.value = task as ScheduleTaskItem & { enabled?: 0 | 1 }
  drawerOpen.value = true
}

/**
 * 「进入页面才拉数据」的触发点（原 App.vue 的 PAGE_LOADERS 已下沉至此）
 * 父级通过 props.load 注入具体加载函数，组件只负责在最合适的时机调用它。
 */
onMounted(() => props.load?.())
</script>
