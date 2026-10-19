<!--
  踩坑经验管理页（v2）
  ----------------------------------------------------------------------------
  布局：页面头（图标 + 说明 + 归并/刷新）→ 统计（内联一行）→ 工具栏（搜索 / 类型 / 状态 / 零命中）
       → 重复组面板（按需展开）→ 经验行列表
  与旧版（LessonManageView.vue）的差异：
    1) 旧版是「头部卡片 + 9 张统计卡 + 工具栏卡 + a-table + 详情抽屉 + 编辑弹窗 + 归并弹窗」；
       新版收敛为「页面头 + 一行统计 + 工具栏 + 行列表」，详情与补全合并为一个抽屉；
    2) 旧版用 emoji + 多色 tag 表达类型/状态/来源，新版统一为文字状态点和中性 tag；
    3) 新增「待补全」显式提示：草稿无可用解法时直接标注，避免误以为经验可被注入；
    4) 新增「只看零命中」筛选（巡检清理候选），对应后端 zeroHit 参数；
    5) 归并入口由独立弹窗改为内联面板，减少弹层层级。
  数据接缝：
    props.lessons  -> GET /lessons?projectKey=&status=&toolName=&errorCode=&type=&zeroHit=&page=&size=
    props.clusters -> GET /lessons/clusters
    props.total    -> 后端 total（筛选条件下的全量条数，可能大于已加载条数）
    emit('save')   -> PUT /lessons/{id}（只提交非空字段）
    emit('feedback') -> POST /lessons/{id}/feedback?effective=
    emit('delete') -> DELETE /lessons/{id}
    emit('merge')  -> POST /lessons/merge
    emit('refresh') -> 重新拉取列表与统计
-->
<template>
  <div class="cc-page-scroll">
    <div class="cc-page-inner">
      <!-- 页面头 -->
      <div class="cc-page-head">
        <div class="cc-page-head__icon">
          <svg class="cc-i"><use href="#i-book" /></svg>
        </div>
        <div>
          <h1 class="cc-page-head__title">踩坑经验</h1>
          <p class="cc-page-head__sub">AI 执行失败时自动沉淀的解法库，按项目隔离、按需检索</p>
        </div>
        <div class="cc-page-head__actions">
          <button class="cc-btn" :disabled="!clusters.length" @click="clustersOpen = !clustersOpen">
            <svg class="cc-i cc-i--sm"><use href="#i-branch" /></svg>归并重复
          </button>
          <button class="cc-btn" @click="$emit('refresh')">
            <svg class="cc-i cc-i--sm"><use href="#i-refresh" /></svg>刷新
          </button>
        </div>
      </div>

      <!-- 请求反馈：加载中 / 失败 -->
      <div v-if="loading" class="cc-stats-inline" style="margin-bottom: var(--sp-4); color: var(--text-3)">
        <span>正在从后端加载经验库…</span>
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
        <span>
          共 <b>{{ totalCount }}</b> 条经验
          <template v-if="totalCount > lessons.length">（已载入 {{ lessons.length }} 条）</template>
        </span><span class="cc-dot" />
        <span>已验证 <b>{{ activeCount }}</b></span><span class="cc-dot" />
        <span>草稿待补全 <b>{{ draftCount }}</b></span><span class="cc-dot" />
        <span>已隐藏 <b>{{ hiddenCount }}</b></span><span class="cc-dot" />
        <span>累计命中 <b>{{ totalHits }}</b> 次</span>
      </div>

      <!-- 工具栏 -->
      <div class="cc-toolbar">
        <label class="cc-search">
          <svg class="cc-i cc-i--sm"><use href="#i-search" /></svg>
          <input v-model="keyword" placeholder="搜索现象、解法、错误码或关键词" />
        </label>

        <div class="cc-seg">
          <button
            v-for="opt in typeOptions"
            :key="opt.value"
            :class="{ 'is-active': typeFilter === opt.value }"
            @click="typeFilter = opt.value"
          >
            {{ opt.label }}
          </button>
        </div>

        <select v-model="statusFilter" class="cc-select" style="width: 128px; height: 34px">
          <option value="all">全部状态</option>
          <option value="1">已验证</option>
          <option value="0">草稿</option>
          <option value="2">已隐藏</option>
        </select>

        <span class="cc-chip" :class="{ 'is-on': zeroHitOnly }" @click="zeroHitOnly = !zeroHitOnly">只看零命中</span>

        <div style="margin-left: auto; font-size: var(--fs-sm); color: var(--text-3)">
          共 <b style="color: var(--text-1)">{{ filteredLessons.length }}</b> 条
        </div>
      </div>

      <!-- 重复组面板 -->
      <div v-if="clustersOpen && clusters.length" class="cc-clusters">
        <div class="cc-clusters__head">
          <div>
            <div class="cc-clusters__title">重复经验组</div>
            <div class="cc-clusters__sub">
              同「项目 + 工具 + 类别 + 错误码」的多条记录，归并后保留最优一条，命中与验证计数汇聚
            </div>
          </div>
          <button class="cc-icon-btn cc-icon-btn--sm" style="margin-left: auto" title="收起" @click="clustersOpen = false">
            <svg class="cc-i cc-i--sm" style="transform: rotate(45deg)"><use href="#i-plus" /></svg>
          </button>
        </div>
        <div v-for="(group, i) in clusters" :key="i" class="cc-cluster-row">
          <code>{{ group.errorCode || '-' }}</code>
          <span v-if="group.errorCategory" class="cc-tag">{{ group.errorCategory }}</span>
          <span>{{ group.toolName || '-' }}</span>
          <span class="cc-cluster-count">{{ group.count || 0 }} 条记录</span>
          <button class="cc-text-btn cc-cluster-action" @click="$emit('merge', group)">归并</button>
        </div>
      </div>

      <!-- 经验列表 -->
      <div v-if="filteredLessons.length" class="cc-lesson-list">
        <LessonRow
          v-for="lesson in filteredLessons"
          :key="lesson.id"
          :lesson="lesson"
          @edit="openDrawer"
          @feedback="(l, effective) => $emit('feedback', { lesson: l, effective })"
          @delete="$emit('delete', $event.id)"
        />
      </div>

      <!-- 空状态 -->
      <div v-else-if="!loading && !error" class="cc-agent-empty">
        <span class="cc-agent-empty__icon">
          <svg class="cc-i cc-i--lg"><use href="#i-book" /></svg>
        </span>
        <span>{{ hasFilter ? '没有匹配的经验' : '还没有踩坑经验' }}</span>
        <span v-if="!hasFilter" style="font-size: var(--fs-xs); color: var(--text-4)">
          工具执行失败时会自动捕获草稿，也可由 AI 通过 lesson 工具主动记录
        </span>
      </div>
    </div>

    <!-- 遮罩 + 抽屉 -->
    <div v-if="drawerOpen" class="cc-mask is-open" @click="drawerOpen = false" />
    <LessonEditDrawer
      v-model:open="drawerOpen"
      :lesson="editingLesson"
      @save="$emit('save', { ...$event, id: editingLesson?.id ?? $event.id })"
    />
  </div>
</template>

<script setup lang="ts">
import { computed, ref, onMounted } from 'vue'
import LessonRow, { type LessonItem } from '../components/LessonRow.vue'
import LessonEditDrawer from '../components/LessonEditDrawer.vue'

/** 重复经验组（后端 /lessons/clusters 返回下划线风格键，这里做前端归一） */
export interface LessonClusterItem {
  projectKey?: string
  toolName?: string
  errorCategory?: string
  errorCode?: string
  /** 组内记录条数 */
  count?: number
  /** 归并时保留的样本 ID */
  sampleId?: number | string
}

type TypeFilter = 'all' | 'FAILURE' | 'DETOUR'

const props = withDefaults(
  defineProps<{
    lessons?: LessonItem[]
    clusters?: LessonClusterItem[]
    /** 后端 total（筛选条件下的全量条数；大于已加载条数时统计行会标注「已载入 N 条」） */
    total?: number
    /** 请求中 */
    loading?: boolean
    /** 请求失败信息 */
    error?: string
    /**
     * 进入页面时的数据加载（由 App.vue 的 PAGE_BINDINGS 注入）
     * 页面随路由真实挂载，所以在这里调一次即可；重复进页由父级的「已加载 / 加载中 / 已失败」跳过
     */
    load?: () => void
  }>(),
  {
    lessons: () => [],
    clusters: () => [],
    total: 0,
    loading: false,
    error: ''
  }
)

defineEmits<{
  (e: 'save', payload: Record<string, unknown>): void
  (e: 'feedback', payload: { lesson: LessonItem; effective: boolean }): void
  (e: 'delete', id: number | string): void
  (e: 'merge', group: LessonClusterItem): void
  (e: 'refresh'): void
}>()

/* ---------------- 统计 ---------------- */
/** 总条数：优先用后端 total（筛选条件下的全量），未提供时回落已加载条数 */
const totalCount = computed(() => props.total || props.lessons.length)
const activeCount = computed(() => props.lessons.filter(l => Number(l.status) === 1).length)
const draftCount = computed(() => props.lessons.filter(l => Number(l.status) === 0).length)
const hiddenCount = computed(() => props.lessons.filter(l => Number(l.status) === 2).length)
const totalHits = computed(() => props.lessons.reduce((sum, l) => sum + (Number(l.hitCount) || 0), 0))

/* ---------------- 筛选 ---------------- */
const keyword = ref('')
const typeFilter = ref<TypeFilter>('all')
const statusFilter = ref<'all' | '0' | '1' | '2'>('all')
const zeroHitOnly = ref(false)

const typeOptions: Array<{ label: string; value: TypeFilter }> = [
  { label: '全部', value: 'all' },
  { label: '失败经验', value: 'FAILURE' },
  { label: '弯路经验', value: 'DETOUR' }
]

/** 是否处于筛选态：用于区分「无结果」与「无数据」空状态文案 */
const hasFilter = computed(
  () => Boolean(keyword.value.trim()) || typeFilter.value !== 'all' || statusFilter.value !== 'all' || zeroHitOnly.value
)

const filteredLessons = computed(() => {
  const kw = keyword.value.trim().toLowerCase()
  return props.lessons.filter(l => {
    if (typeFilter.value !== 'all' && (l.type || 'FAILURE') !== typeFilter.value) return false
    if (statusFilter.value !== 'all' && String(Number(l.status)) !== statusFilter.value) return false
    if (zeroHitOnly.value && Number(l.hitCount) > 0) return false
    if (!kw) return true
    const haystack = [l.errorCode, l.errorCategory, l.symptom, l.rootCause, l.solution, l.keywords, l.toolName, l.goal]
      .filter(Boolean)
      .join(' ')
      .toLowerCase()
    return haystack.includes(kw)
  })
})

/* ---------------- 归并面板 ---------------- */
const clustersOpen = ref(false)

/* ---------------- 抽屉 ---------------- */
const drawerOpen = ref(false)
const editingLesson = ref<LessonItem | null>(null)

function openDrawer(lesson: LessonItem) {
  editingLesson.value = lesson
  drawerOpen.value = true
}

/**
 * 「进入页面才拉数据」的触发点（原 App.vue 的 PAGE_LOADERS 已下沉至此）
 * 父级通过 props.load 注入具体加载函数，组件只负责在最合适的时机调用它。
 */
onMounted(() => props.load?.())
</script>
