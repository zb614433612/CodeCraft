<!--
  智能体管理页（v2）
  ----------------------------------------------------------------------------
  布局：页面头（图标 + 标题 + 副标题 + 新建按钮）→ 工具栏（搜索 + 分段筛选）→ 卡片网格
  与旧版的差异：
    1) 纯色背景，卡片只保留 1px 描边（hover 只变描边，不加阴影/位移）；
    2) 操作按钮 hover 才出现，卡片首屏更干净；
    3) 元信息（工作目录 / 工具数）收敛到底部一行，不再散落；
    4) 新增搜索与「全部 / 内置 / 自定义」筛选，Agent 变多后仍可用；
    5) 「新建 Agent」同时提供头部主按钮与网格中的虚线卡，位置固定不随列表变化。
  数据接缝：
    props.agents        -> GET /agent-config/list
    emit('save')        -> POST /agent-config（新建）或 PUT /agent-config/{id}（编辑）
    emit('delete')      -> DELETE /agent-config/{id}（卡片「更多」菜单）
    emit('set-default') -> PUT /agent-config/{id}/default（卡片「更多」菜单）
    emit('select')      -> 切换到该 Agent 的会话
    （工作目录由抽屉内自带的 DirectoryPicker 处理，本组件不参与）
-->
<template>
  <!-- 页面级滚动容器（与聊天页共用 cc-main，靠 flex 布局占满） -->
  <div class="cc-page-scroll">
    <div class="cc-page-inner">
      <!-- 页面头 -->
      <div class="cc-page-head">
        <div class="cc-page-head__icon">
          <svg class="cc-i"><use href="#i-agent" /></svg>
        </div>
        <div>
          <h1 class="cc-page-head__title">Agent 管理</h1>
          <p class="cc-page-head__sub">
            {{ agents.length }} 个 Agent<template v-if="builtinCount"> · {{ builtinCount }} 个内置</template>
          </p>
        </div>
        <div class="cc-page-head__actions">
          <button class="cc-btn cc-btn--primary" @click="openCreate">
            <svg class="cc-i cc-i--sm"><use href="#i-plus" /></svg>新建 Agent
          </button>
        </div>
      </div>

      <!-- 工具栏 -->
      <div class="cc-toolbar">
        <label class="cc-search">
          <svg class="cc-i cc-i--sm"><use href="#i-search" /></svg>
          <input v-model="keyword" placeholder="搜索 Agent 名称或描述" />
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
      </div>

      <!-- 卡片网格 -->
      <div v-if="filteredAgents.length" class="cc-agent-grid">
        <AgentCard
          v-for="agent in filteredAgents"
          :key="agent.id"
          :agent="agent"
          @edit="openEdit"
          @set-default="$emit('set-default', $event)"
          @delete="$emit('delete', $event.id)"
          @select="$emit('select', $event)"
        />

        <!-- 新建卡片（虚线） -->
        <button
          class="cc-agent-empty"
          style="min-height: 172px; justify-content: center; cursor: pointer"
          @click="openCreate"
        >
          <span class="cc-agent-empty__icon">
            <svg class="cc-i cc-i--lg"><use href="#i-plus" /></svg>
          </span>
          <span>新建 Agent</span>
          <span style="font-size: var(--fs-xs); color: var(--text-4)">配置角色、工具集与模型</span>
        </button>
      </div>

      <!-- 空状态 -->
      <div v-else class="cc-agent-empty">
        <span class="cc-agent-empty__icon">
          <svg class="cc-i cc-i--lg"><use href="#i-agent" /></svg>
        </span>
        <span>{{ keyword ? '没有匹配的 Agent' : '还没有 Agent' }}</span>
        <button v-if="!keyword" class="cc-btn cc-btn--primary" style="margin-top: var(--sp-2)" @click="openCreate">
          <svg class="cc-i cc-i--sm"><use href="#i-plus" /></svg>新建 Agent
        </button>
      </div>
    </div>
  </div>

  <!-- 遮罩：与抽屉共用 -->
  <div v-if="drawerOpen" class="cc-mask is-open" @click="drawerOpen = false" />

  <!-- 新建 / 编辑抽屉 -->
  <AgentEditDrawer
    v-model:open="drawerOpen"
    :agent="editingAgent"
    :providers="providers"
    :models="models"
    :tool-groups="toolGroups"
    @save="$emit('save', { ...$event, id: editingAgent?.id })"
  />
</template>

<script setup lang="ts">
import { computed, ref, onMounted } from 'vue'
import AgentCard, { type AgentItem } from '../components/AgentCard.vue'
import AgentEditDrawer, { type AgentProviderOption } from '../components/AgentEditDrawer.vue'
import type { ToolGroup } from '../utils/tool-catalog'

const props = withDefaults(
  defineProps<{
    agents?: AgentItem[]
    /** Provider 选项（来自 `GET /api/llm-providers`），用于模型下拉联动 */
    providers?: AgentProviderOption[]
    /** 兜底模型（Provider 未返回任何模型时使用） */
    models?: string[]
    /** 工具分组（来自 `GET /api/tools/registry`；为空时抽屉内回退内置快照） */
    toolGroups?: ToolGroup[]
    /**
     * 进入页面时的数据加载（由 App.vue 的 PAGE_BINDINGS 注入）
     * 页面随路由真实挂载，所以在这里调一次即可；重复进页由父级的「已加载 / 加载中 / 已失败」跳过
     */
    load?: () => void
  }>(),
  {
    agents: () => [],
    providers: () => [],
    models: () => ['deepseek-v4-pro', 'deepseek-v4-flash', 'gpt-5', 'claude-sonnet-4'],
    toolGroups: () => []
  }
)

defineEmits<{
  (e: 'create'): void
  (e: 'save', payload: Record<string, unknown>): void
  (e: 'delete', id: number | string): void
  /** 卡片「更多」菜单 → 设为默认（PUT /agent-configs/{id}/default） */
  (e: 'set-default', agent: AgentItem): void
  (e: 'select', id: number | string): void
}>()

/* ---------------- 筛选 ---------------- */
type FilterValue = 'all' | 'builtin' | 'custom'
const filter = ref<FilterValue>('all')
const keyword = ref('')

const filterOptions: Array<{ label: string; value: FilterValue }> = [
  { label: '全部', value: 'all' },
  { label: '内置', value: 'builtin' },
  { label: '自定义', value: 'custom' }
]

const builtinCount = computed(() => props.agents.filter(a => a.isBuiltin).length)

const filteredAgents = computed(() => {
  const kw = keyword.value.trim().toLowerCase()
  return props.agents.filter(a => {
    if (filter.value === 'builtin' && !a.isBuiltin) return false
    if (filter.value === 'custom' && a.isBuiltin) return false
    if (!kw) return true
    return (
      a.name.toLowerCase().includes(kw) ||
      (a.description || '').toLowerCase().includes(kw)
    )
  })
})

/* ---------------- 抽屉 ---------------- */
const drawerOpen = ref(false)
const editingAgent = ref<AgentItem | null>(null)

function openCreate() {
  editingAgent.value = null
  drawerOpen.value = true
}

function openEdit(agent: AgentItem) {
  editingAgent.value = agent
  drawerOpen.value = true
}

/**
 * 「进入页面才拉数据」的触发点（原 App.vue 的 PAGE_LOADERS 已下沉至此）
 * 父级通过 props.load 注入具体加载函数，组件只负责在最合适的时机调用它。
 */
onMounted(() => props.load?.())
</script>
