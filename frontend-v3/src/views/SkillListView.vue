<!--
  技能管理页（v2）
  ----------------------------------------------------------------------------
  布局：页面头（图标 + 标题 + 新建按钮）→ 统计行（内联）→ 工具栏（搜索/筛选/作用域）→ 技能行列表
  与旧版的差异：
    1) 背景纯色，技能行只保留 1px 描边（hover 只变描边）；
    2) 旧版「统计卡 + 工具栏卡 + 内容卡」三层卡片 → 收敛为「页面头 + 一行统计 + 工具栏 + 列表」；
    3) 置信度用细进度条 + 百分比，低于 0.1（后端不再注入）时进度条置灰；
    4) 操作按钮 hover 才出现，列表首屏更干净；
    5) 新增「已激活 / 已隐藏」筛选与作用域筛选，技能变多后仍可用。
  数据接缝：
    props.skills    -> GET /skills?userId=（后端按 userId 隔离，userId 为必填）
    emit('save')    -> POST /skills（新建）或 PUT /skills/{id}（编辑），已带上 id 便于区分
    emit('delete')  -> DELETE /skills/{id}?userId=（后端校验归属）
    agents / toolGroups -> 传入抽屉（Agent 列表与工具注册表）
-->
<template>
  <div class="cc-page-scroll">
    <div class="cc-page-inner">
      <!-- 页面头 -->
      <div class="cc-page-head">
        <div class="cc-page-head__icon">
          <svg class="cc-i"><use href="#i-skill" /></svg>
        </div>
        <div>
          <h1 class="cc-page-head__title">技能管理</h1>
          <p class="cc-page-head__sub">管理编码助手的技能库：触发词、指令与工具组合</p>
        </div>
        <div class="cc-page-head__actions">
          <button class="cc-btn cc-btn--primary" @click="openCreate">
            <svg class="cc-i cc-i--sm"><use href="#i-plus" /></svg>新建技能
          </button>
        </div>
      </div>

      <!-- 请求反馈：加载中 / 失败（后端未启通时避免只剩空列表让人误解） -->
      <div v-if="loading" class="cc-stats-inline" style="margin-bottom: var(--sp-4); color: var(--text-3)">
        <span>正在从后端加载技能…</span>
      </div>
      <div
        v-else-if="error"
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
        <span><b>{{ skills.length }}</b> 个技能</span><span class="cc-dot" />
        <span><b>{{ activeCount }}</b> 个已激活</span><span class="cc-dot" />
        <span>平均置信度 <b>{{ avgConfidence }}%</b></span><span class="cc-dot" />
        <span>累计使用 <b>{{ totalUsage }}</b> 次</span>
      </div>

      <!-- 工具栏 -->
      <div class="cc-toolbar">
        <label class="cc-search">
          <svg class="cc-i cc-i--sm"><use href="#i-search" /></svg>
          <input v-model="keyword" placeholder="搜索技能名称、描述或触发词" />
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

        <select v-model="agentFilter" class="cc-select" style="width: 160px; height: 34px">
          <option :value="null">全部作用域</option>
          <option :value="0">全局</option>
          <option v-for="a in agents" :key="a.id" :value="a.id">{{ a.name }}</option>
        </select>

        <div style="margin-left: auto; font-size: var(--fs-sm); color: var(--text-3)">
          共 <b style="color: var(--text-1)">{{ filteredSkills.length }}</b> 条
        </div>
      </div>

      <!-- 技能列表 -->
      <div v-if="filteredSkills.length" class="cc-skill-list">
        <SkillRow
          v-for="skill in filteredSkills"
          :key="skill.id"
          :skill="skill"
          @edit="openEdit"
          @delete="$emit('delete', $event.id)"
        />
      </div>

      <!-- 空状态 -->
      <div v-else-if="!loading && !error" class="cc-agent-empty">
        <span class="cc-agent-empty__icon">
          <svg class="cc-i cc-i--lg"><use href="#i-skill" /></svg>
        </span>
        <span>{{ keyword || filter !== 'all' ? '没有匹配的技能' : '还没有技能' }}</span>
        <button
          v-if="!keyword && filter === 'all'"
          class="cc-btn cc-btn--primary"
          style="margin-top: var(--sp-2)"
          @click="openCreate"
        >
          <svg class="cc-i cc-i--sm"><use href="#i-plus" /></svg>立即创建
        </button>
      </div>
    </div>

    <!-- 遮罩 + 抽屉 -->
    <div v-if="drawerOpen" class="cc-mask is-open" @click="drawerOpen = false" />
    <SkillEditDrawer
      v-model:open="drawerOpen"
      :skill="editingSkill"
      :agents="agents"
      :tool-groups="toolGroups"
      @save="$emit('save', { ...$event, id: editingSkill?.id })"
    />
  </div>
</template>

<script setup lang="ts">
import { computed, ref, onMounted } from 'vue'
import SkillRow, { type SkillItem } from '../components/SkillRow.vue'
import SkillEditDrawer from '../components/SkillEditDrawer.vue'

type FilterValue = 'all' | 'active' | 'hidden'

const props = withDefaults(
  defineProps<{
    skills?: Array<SkillItem & { instructions?: string; tools?: string[] }>
    /** Agent 列表（用于作用域筛选与抽屉下拉） */
    agents?: Array<{ id: number; name: string }>
    /** 工具注册表（按分类） */
    toolGroups?: Array<{ label: string; tools: Array<{ name: string; description?: string; risk?: boolean }> }>
    /** 请求中：显示「正在加载」提示条 */
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
    skills: () => [],
    agents: () => [],
    toolGroups: () => [],
    loading: false,
    error: ''
  }
)

defineEmits<{
  (e: 'save', payload: Record<string, unknown>): void
  (e: 'delete', id: number | string): void
}>()

/* ---------------- 统计 ---------------- */
/** 与后端一致：confidence >= 0.1 才会被注入系统提示 */
const activeCount = computed(() => props.skills.filter(s => (s.confidence ?? 0.5) >= 0.1).length)
const avgConfidence = computed(() => {
  if (!props.skills.length) return 0
  const sum = props.skills.reduce((acc, s) => acc + (s.confidence ?? 0.5), 0)
  return Math.round((sum / props.skills.length) * 100)
})
const totalUsage = computed(() => props.skills.reduce((acc, s) => acc + (s.usageCount || 0), 0))

/* ---------------- 筛选 ---------------- */
const filter = ref<FilterValue>('all')
const keyword = ref('')
/** null=全部 / 0=全局 / 其他=Agent id */
const agentFilter = ref<number | null>(null)

const filterOptions: Array<{ label: string; value: FilterValue }> = [
  { label: '全部', value: 'all' },
  { label: '已激活', value: 'active' },
  { label: '已隐藏', value: 'hidden' }
]

/** 触发词统一转数组（兼容 JSON 字符串） */
function toTriggerArray(raw: SkillItem['triggerWords']): string[] {
  if (!raw) return []
  if (Array.isArray(raw)) return raw
  try {
    const parsed = JSON.parse(raw)
    return Array.isArray(parsed) ? parsed : []
  } catch {
    return String(raw).split(/[,，]/).map(s => s.trim()).filter(Boolean)
  }
}

const filteredSkills = computed(() => {
  const kw = keyword.value.trim().toLowerCase()
  return props.skills.filter(s => {
    const confidence = s.confidence ?? 0.5
    if (filter.value === 'active' && confidence < 0.1) return false
    if (filter.value === 'hidden' && confidence >= 0.1) return false

    if (agentFilter.value !== null) {
      if (agentFilter.value === 0 && s.agentConfigId) return false
      if (agentFilter.value !== 0 && s.agentConfigId !== agentFilter.value) return false
    }

    if (!kw) return true
    const haystack = [
      s.name,
      s.description || '',
      ...toTriggerArray(s.triggerWords)
    ].join(' ').toLowerCase()
    return haystack.includes(kw)
  })
})

/* ---------------- 抽屉 ---------------- */
const drawerOpen = ref(false)
const editingSkill = ref<(SkillItem & { instructions?: string; tools?: string[] }) | null>(null)

function openCreate() {
  editingSkill.value = null
  drawerOpen.value = true
}

function openEdit(skill: SkillItem) {
  editingSkill.value = skill as (SkillItem & { instructions?: string; tools?: string[] })
  drawerOpen.value = true
}

/**
 * 「进入页面才拉数据」的触发点（原 App.vue 的 PAGE_LOADERS 已下沉至此）
 * 父级通过 props.load 注入具体加载函数，组件只负责在最合适的时机调用它。
 */
onMounted(() => props.load?.())
</script>
