<!--
  模型配置页（v2）
  ----------------------------------------------------------------------------
  布局：页面头（刷新 / 新增）→ 内联统计 → 工具栏（搜索 + 启用状态筛选）
       → Provider 行列表 → 编辑抽屉
  数据接缝：
    providers        -> GET  /api/llm-providers（管理员；apiKey 与 extraConfig 密钥为 **** 掩码）
    emit('create')   -> 打开空白抽屉（POST /api/llm-providers）
    emit('save')     -> POST /api/llm-providers 或 PUT /api/llm-providers/{id}
    emit('delete')   -> DELETE /api/llm-providers/{id}
    emit('toggle')   -> PUT /api/llm-providers/{id}（只提交 enabled）
    emit('query-balance') -> GET /api/llm-providers/{code}/balance
    emit('clear-balance') -> 切换编辑对象时清空上一次余额
    emit('refresh')  -> 重新拉取列表
  说明：
    1) is_default 后端无单独接口（创建强制 0、更新不处理），页面只读展示「默认」标记；
    2) 能力徽章由 utils/provider-capability.ts 按「请求模板 + 扩展配置 + 默认模型 + Base URL」推导，不虚构后端字段；
    3) 停用行整行降饱和，与其它管理页保持一致。
-->
<template>
  <div class="cc-page-scroll">
    <div class="cc-page-inner">
      <!-- 页面头 -->
      <div class="cc-page-head">
        <div class="cc-page-head__icon">
          <svg class="cc-i"><use href="#i-cog" /></svg>
        </div>
        <div>
          <h1 class="cc-page-head__title">模型配置</h1>
          <p class="cc-page-head__sub">
            统一管理 LLM 平台的接入凭证与能力：对话、图片识别、联网搜索、余额查询
          </p>
        </div>
        <div class="cc-page-head__actions">
          <button class="cc-btn" @click="$emit('refresh')">
            <svg class="cc-i cc-i--sm"><use href="#i-refresh" /></svg>刷新
          </button>
          <button class="cc-btn cc-btn--primary" @click="openCreate">
            <svg class="cc-i cc-i--sm"><use href="#i-plus" /></svg>新增 Provider
          </button>
        </div>
      </div>

      <!-- 请求反馈：加载中 / 失败（后端未启动时最有用，避免只剩一个空列表让人误解） -->
      <div
        v-if="loading"
        class="cc-stats-inline"
        style="margin-bottom: var(--sp-4); color: var(--text-3)"
      >
        <span>正在从后端加载 Provider 配置…</span>
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

      <!-- 统计（能力不受启用状态影响，便于一眼看出平台能力储备） -->
      <div class="cc-stats-inline" style="margin-bottom: var(--sp-4)">
        <span>平台 <b>{{ providers.length }}</b></span><span class="cc-dot" />
        <span>已启用 <b>{{ enabledCount }}</b></span><span class="cc-dot" />
        <span>默认 <b>{{ defaultCount }}</b></span><span class="cc-dot" />
        <span title="仅统计默认模型确定支持多模态的 Provider">图片就绪 <b>{{ visionCount }}</b></span><span class="cc-dot" />
        <span title="默认模型非多模态或未配置，需切换模型才能发图">图片待切换 <b>{{ visionPendingCount }}</b></span><span class="cc-dot" />
        <span title="平台官方搜索接口已就绪：火山 searchApiKey / 千问 enableSearch / MiniMax codingPlanKey / DeepSeek 走官方域名">搜索就绪 <b>{{ searchReadyCount }}</b></span><span class="cc-dot" />
        <span title="平台本来有官方搜索接口，但当前配置用不了（如 DeepSeek 走了第三方中转），联网只剩通用抓取">搜索待启用 <b>{{ searchPendingCount }}</b></span><span class="cc-dot" />
        <span>余额可查 <b>{{ balanceCount }}</b></span>
      </div>

      <!-- 工具栏 -->
      <div class="cc-toolbar">
        <label class="cc-search">
          <svg class="cc-i cc-i--sm"><use href="#i-search" /></svg>
          <input v-model="keyword" placeholder="搜索名称、编码、Base URL 或模型" />
        </label>
        <div class="cc-seg">
          <button
            v-for="opt in STATE_OPTIONS"
            :key="opt.value"
            :class="{ 'is-active': stateFilter === opt.value }"
            :title="opt.title"
            @click="stateFilter = opt.value"
          >
            {{ opt.label }}
          </button>
        </div>
        <div style="margin-left: auto; font-size: var(--fs-sm); color: var(--text-3)">
          共 <b style="color: var(--text-1)">{{ filteredProviders.length }}</b> 个
        </div>
      </div>

      <!-- 列表 -->
      <div class="cc-prov-list">
        <ProviderRow
          v-for="provider in filteredProviders"
          :key="provider.id ?? provider.code"
          :provider="provider"
          toggle-disabled
          @toggle="$emit('toggle', $event)"
          @balance="openBalance"
          @edit="openEdit"
          @delete="$emit('delete', $event)"
        />
      </div>

      <!-- 空状态（请求失败时不显示，避免「还没有配置」的误导） -->
      <div v-if="!filteredProviders.length && !error" class="cc-agent-empty">
        <div class="cc-agent-empty__icon">
          <svg class="cc-i" style="width: 28px; height: 28px"><use href="#i-cog" /></svg>
        </div>
        <div style="font-size: var(--fs-base); font-weight: 600; color: var(--text-1)">
          {{ emptyTitle }}
        </div>
        <div style="font-size: var(--fs-sm); color: var(--text-3)">
          {{ emptyDesc }}
        </div>
        <button v-if="!providers.length" class="cc-btn cc-btn--primary" @click="openCreate">
          <svg class="cc-i cc-i--sm"><use href="#i-plus" /></svg>新增 Provider
        </button>
      </div>
    </div>

    <!-- 遮罩 + 编辑抽屉 -->
    <div v-if="drawerOpen" class="cc-mask is-open" @click="drawerOpen = false" />

    <ProviderEditDrawer
      v-model:open="drawerOpen"
      :provider="editing"
      :balance="balance"
      :balance-loading="balanceLoading"
      :balance-error="balanceError"
      @save="$emit('save', $event)"
      @query-balance="$emit('query-balance', $event)"
    />
  </div>
</template>

<script setup lang="ts">
import { computed, ref, watch, onMounted } from 'vue'
import ProviderRow, { type ProviderItem } from '../components/ProviderRow.vue'
import ProviderEditDrawer, {
  type ProviderBalanceModel,
  type ProviderSavePayload
} from '../components/ProviderEditDrawer.vue'
import { deriveCapabilities } from '../utils/provider-capability'

type StateFilter = 'all' | 'on' | 'off'

const props = withDefaults(
  defineProps<{
    providers?: ProviderItem[]
    /** 列表请求进行中（GET /api/llm-providers） */
    loading?: boolean
    /** 列表请求失败信息（后端未启动 / 非管理员 / 网络异常） */
    error?: string
    /** 当前编辑对象的余额查询结果 */
    balance?: ProviderBalanceModel | null
    balanceLoading?: boolean
    balanceError?: string
    /**
     * 进入页面时的数据加载（由 App.vue 的 PAGE_BINDINGS 注入）
     * 页面随路由真实挂载，所以在这里调一次即可；重复进页由父级的「已加载 / 加载中 / 已失败」跳过
     */
    load?: () => void
  }>(),
  {
    providers: () => [],
    loading: false,
    error: '',
    balance: null,
    balanceLoading: false,
    balanceError: ''
  }
)

const emit = defineEmits<{
  (e: 'save', payload: ProviderSavePayload): void
  (e: 'delete', provider: ProviderItem): void
  (e: 'toggle', provider: ProviderItem): void
  (e: 'query-balance', code: string): void
  (e: 'clear-balance'): void
  (e: 'refresh'): void
}>()

/**
 * 状态筛选项。
 * 注意「已停用」：后端 `GET /api/llm-providers` 走 `selectAllEnabled()`，只返回启用项，
 * 所以该筛选在当前接口下恒为空 —— 保留入口是为了后端补「查全部」能力后自动生效，
 * 并用 title / 空状态文案说明原因，避免被误当成 bug。
 */
const STATE_OPTIONS: Array<{ label: string; value: StateFilter; title: string }> = [
  { label: '全部', value: 'all', title: '显示后端返回的全部 Provider（当前接口只返回已启用项）' },
  { label: '已启用', value: 'on', title: 'enabled = 1' },
  { label: '已停用', value: 'off', title: '后端列表接口只返回启用项，此筛选暂为空' }
]

const keyword = ref('')
const stateFilter = ref<StateFilter>('all')
const drawerOpen = ref(false)
/** 当前编辑对象；null 表示新增 */
const editing = ref<ProviderItem | null>(null)

const filteredProviders = computed(() => {
  const kw = keyword.value.trim().toLowerCase()
  return props.providers.filter(provider => {
    const enabled = provider.enabled !== 0
    const matchState =
      stateFilter.value === 'all' || (stateFilter.value === 'on' ? enabled : !enabled)
    if (!matchState) return false
    if (!kw) return true
    const hay = [
      provider.name,
      provider.code,
      provider.baseUrl,
      provider.defaultModel,
      provider.modelList
    ]
      .join(' ')
      .toLowerCase()
    return hay.includes(kw)
  })
})

const enabledCount = computed(() => props.providers.filter(p => p.enabled !== 0).length)
const defaultCount = computed(() => props.providers.filter(p => p.isDefault === 1).length)

/** 能力统计（与行内徽章同一推导函数，避免两处口径不一致） */
const capabilityStats = computed(() => {
  let vision = 0
  let visionPending = 0
  let searchReady = 0
  let searchPending = 0
  let balance = 0
  props.providers.forEach(provider => {
    const caps = deriveCapabilities(
      provider.requestTemplate,
      provider.code,
      provider.extraConfig,
      provider.defaultModel,
      // DeepSeek 官方搜索接口按 Base URL 判域名，省略会把官方配置误判成「仅通用抓取」
      provider.baseUrl
    )
    // 图片识别是模型级三态：on=默认模型确定支持，warn=需切换模型，off=平台不支持
    if (caps.vision === 'on') vision++
    else if (caps.vision === 'warn') visionPending++
    // 联网搜索：on=官方接口就绪，warn=本来有但被中转挡住（只剩通用抓取），off=平台无官方接口
    if (caps.search === 'on') searchReady++
    else if (caps.search === 'warn') searchPending++
    if (caps.balance !== 'off') balance++
  })
  return { vision, visionPending, searchReady, searchPending, balance }
})

const visionCount = computed(() => capabilityStats.value.vision)
const visionPendingCount = computed(() => capabilityStats.value.visionPending)
const searchReadyCount = computed(() => capabilityStats.value.searchReady)
const searchPendingCount = computed(() => capabilityStats.value.searchPending)
const balanceCount = computed(() => capabilityStats.value.balance)

/** 空状态标题：区分「一条都没有」与「筛选/关键词无匹配」 */
const emptyTitle = computed(() => {
  if (!props.providers.length) return '还没有配置 LLM Provider'
  return stateFilter.value === 'off' ? '列表里没有停用的 Provider' : '没有匹配的 Provider'
})

/** 空状态说明：把「已停用筛选恒为空」的后端原因讲清楚，避免被当成 bug */
const emptyDesc = computed(() => {
  if (!props.providers.length) {
    return '接入一个平台（DeepSeek / 火山方舟 / 通义千问 / Claude 等）后即可选择模型'
  }
  if (stateFilter.value === 'off') {
    return '后端列表接口只返回已启用的 Provider（selectAllEnabled），停用项不会出现在这里'
  }
  return '换个关键词，或清空筛选条件'
})

function openCreate() {
  editing.value = null
  emit('clear-balance')
  drawerOpen.value = true
}

function openEdit(provider: ProviderItem) {
  editing.value = provider
  emit('clear-balance')
  drawerOpen.value = true
}

/** 行内「查询余额」：先打开抽屉，再按 code 拉取余额 */
function openBalance(provider: ProviderItem) {
  editing.value = provider
  emit('clear-balance')
  drawerOpen.value = true
  emit('query-balance', provider.code)
}

/** 抽屉关闭后清空编辑对象，避免下次打开闪现旧数据 */
watch(drawerOpen, open => {
  if (!open) editing.value = null
})

/**
 * 「进入页面才拉数据」的触发点（原 App.vue 的 PAGE_LOADERS 已下沉至此）
 * 父级通过 props.load 注入具体加载函数，组件只负责在最合适的时机调用它。
 */
onMounted(() => props.load?.())
</script>
