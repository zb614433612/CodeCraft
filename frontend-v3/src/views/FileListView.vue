<!--
  文件管理页（v2）
  ----------------------------------------------------------------------------
  布局：页面头（图标 + 说明 + 云端文件/上传）→ 统计（内联一行）→ 工具栏（搜索 / 存储 / 状态）
       → 文件行列表 → 分页条
  与旧版的差异：旧版是「统计卡片 + 表格 + 详情抽屉 + 云端弹窗」，新版收敛为
  「页面头 + 一行统计 + 行列表 + 分页」，详情与重命名合并为一个抽屉。
  数据接缝（FileAssetController）：
    props.files       -> GET /api/files/page?page=&pageSize=&keyword=&status=&storageType=&order=
    props.total       -> 同接口 total（全量筛选口径，不受分页影响）
    props.totalSize   -> 同接口 totalSize（字节，全量筛选口径）
    props.loading     -> 请求进行中（禁用分页按钮）
    props.error       -> 加载失败原因（页面顶部红色提示条）
    props.cloud       -> GET /api/files/cloud?providerCode=（打开云端抽屉时按需拉取）
    emit('open')      -> 打开详情抽屉，父组件按 id 拉预览 blob（preview 接口需鉴权头）
    emit('open-cloud')-> 打开云端抽屉（按需拉取云端清单，不随列表预加载）
    emit('search')    -> keyword 参数（父组件建议加 300ms 防抖）
    emit('page-change')-> page 参数
    emit('upload')    -> POST /api/files（multipart；后端单文件接口，父组件按文件循环调用）
    emit('rename')    -> PATCH /api/files/{id} { displayName }
    emit('delete')    -> DELETE /api/files/{id}
    emit('delete-cloud') -> DELETE /api/files/cloud/{fileId}
    emit('purge-cloud')  -> 批量删除全部「仅云端」
  说明：/page 接口只提供 total 与 totalSize 两项全量统计，因此「云端 / 本地 / 孤儿」
       按当前页数据统计并标注「本页」，避免把分页数据当成全量。
-->
<template>
  <div class="cc-page-scroll">
    <div class="cc-page-inner">
      <!-- 页面头 -->
      <div class="cc-page-head">
        <div class="cc-page-head__icon">
          <svg class="cc-i"><use href="#i-file" /></svg>
        </div>
        <div>
          <h1 class="cc-page-head__title">文件管理</h1>
          <p class="cc-page-head__sub">
            AI 上传与工具产出的文件统一沉淀：图片走云端 Files API，文档存本地；可重命名、预览与清理
          </p>
        </div>
        <div class="cc-page-head__actions">
          <button class="cc-btn" @click="onOpenCloud">
            <svg class="cc-i cc-i--sm"><use href="#i-disk" /></svg>云端文件
          </button>
          <button class="cc-btn cc-btn--primary" @click="fileInput?.click()">
            <svg class="cc-i cc-i--sm"><use href="#i-plus" /></svg>上传文件
          </button>
          <input ref="fileInput" type="file" multiple style="display: none" @change="onPick" />
        </div>
      </div>

      <!-- 请求反馈：失败提示（loading 由分页/刷新按钮禁用态表达，避免双份提示） -->
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
        <span>共 <b>{{ total }}</b> 个文件</span><span class="cc-dot" />
        <span>占用 <b>{{ totalSizeText }}</b></span><span class="cc-dot" />
        <span>本页云端 <b>{{ pageCloudCount }}</b></span><span class="cc-dot" />
        <span>本页本地 <b>{{ pageLocalCount }}</b></span><span class="cc-dot" />
        <span>本页孤儿 <b>{{ pageOrphanCount }}</b></span>
      </div>

      <!-- 工具栏 -->
      <div class="cc-toolbar">
        <label class="cc-search">
          <svg class="cc-i cc-i--sm"><use href="#i-search" /></svg>
          <input v-model="keywordModel" placeholder="搜索显示名或文件名" />
        </label>

        <div class="cc-seg">
          <button
            v-for="opt in STORAGE_OPTIONS"
            :key="opt.value"
            :class="{ 'is-active': storageFilter === opt.value }"
            @click="onStorageChange(opt.value)"
          >
            {{ opt.label }}
          </button>
        </div>

        <select v-model="statusFilter" class="cc-select" style="width: 128px; height: 34px" @change="applyFilter">
          <option value="all">全部状态</option>
          <option value="active">可用</option>
          <option value="deleted">已删除</option>
          <option value="orphan">孤儿</option>
        </select>

        <div style="margin-left: auto; font-size: var(--fs-sm); color: var(--text-3)">
          共 <b style="color: var(--text-1)">{{ total }}</b> 个
        </div>
      </div>

      <!-- 文件列表 -->
      <div v-if="files.length" class="cc-file-list">
        <FileRow
          v-for="file in files"
          :key="file.id"
          :file="file"
          @open="openDetail"
          @delete="onDelete"
          @reference="$emit('reference', $event)"
        />
      </div>

      <!-- 空状态 -->
      <div v-else-if="!loading && !error" class="cc-agent-empty">
        <span class="cc-agent-empty__icon">
          <svg class="cc-i cc-i--lg"><use href="#i-file" /></svg>
        </span>
        <span>{{ hasFilter ? '没有匹配的文件' : '还没有任何文件' }}</span>
        <span v-if="!hasFilter" style="font-size: var(--fs-xs); color: var(--text-4)">
          聊天里上传图片/文档，或工具产出截图后会自动沉淀到这里
        </span>
        <button v-else class="cc-btn" style="margin-top: var(--sp-2)" @click="resetFilter">
          <svg class="cc-i cc-i--sm"><use href="#i-refresh" /></svg>清空筛选
        </button>
      </div>

      <!-- 分页 -->
      <div class="cc-pager">
        <span class="cc-pager__info">
          第 <b>{{ page }}</b> / <b>{{ totalPages }}</b> 页 · 共 <b>{{ total }}</b> 个
        </span>
        <button class="cc-btn" :disabled="loading || page <= 1" @click="$emit('page-change', page - 1)">
          <svg class="cc-i cc-i--sm" style="transform: rotate(180deg)"><use href="#i-chev" /></svg>上一页
        </button>
        <button class="cc-btn" :disabled="loading || page >= totalPages" @click="$emit('page-change', page + 1)">
          下一页<svg class="cc-i cc-i--sm"><use href="#i-chev" /></svg>
        </button>
      </div>
    </div>

    <!-- 遮罩 + 两个抽屉 -->
    <div v-if="detailOpen || cloudOpen" class="cc-mask is-open" @click="closeAll" />

    <FileDetailDrawer
      v-model:open="detailOpen"
      :file="current"
      :preview-src="previewSrc"
      @save="$emit('rename', $event)"
      @delete="onDeleteById"
    />

    <CloudFilesDrawer
      v-model:open="cloudOpen"
      :cloud="cloud"
      :loading="cloudLoading"
      @delete-cloud="$emit('delete-cloud', $event)"
      @purge="$emit('purge-cloud')"
      @refresh="$emit('refresh-cloud')"
    />
  </div>
</template>

<script setup lang="ts">
import { computed, ref, onMounted } from 'vue'
import FileRow, { type FileItem } from '../components/FileRow.vue'
import FileDetailDrawer from '../components/FileDetailDrawer.vue'
import CloudFilesDrawer, { type CloudFileListModel } from '../components/CloudFilesDrawer.vue'

type StorageFilter = 'all' | 'cloud' | 'local'
type StatusFilter = 'all' | 'active' | 'deleted' | 'orphan'

const props = withDefaults(
  defineProps<{
    files?: FileItem[]
    /** 全量文件数（同筛选口径，不受分页影响） */
    total?: number
    /** 全量占用空间（字节） */
    totalSize?: number
    page?: number
    pageSize?: number
    /** 当前搜索词（受控，便于父组件回填与防抖） */
    keyword?: string
    /** 请求进行中（禁用分页按钮，避免重复提交） */
    loading?: boolean
    /** 加载失败原因（父组件从接口异常中提取，空串表示无错误） */
    error?: string
    cloud?: CloudFileListModel | null
    cloudLoading?: boolean
    /** 图片预览地址（父组件 fetch + blob 生成） */
    previewSrc?: string
    /**
     * 进入页面时的数据加载（由 App.vue 的 PAGE_BINDINGS 注入）
     * 页面随路由真实挂载，所以在这里调一次即可；重复进页由父级的「已加载 / 加载中 / 已失败」跳过
     */
    load?: () => void
  }>(),
  {
    files: () => [],
    total: 0,
    totalSize: 0,
    page: 1,
    pageSize: 20,
    keyword: '',
    loading: false,
    error: '',
    cloud: null,
    cloudLoading: false,
    previewSrc: ''
  }
)

const emit = defineEmits<{
  (e: 'page-change', page: number): void
  (e: 'search', keyword: string): void
  (e: 'filter-change', payload: { storageType: StorageFilter; status: StatusFilter }): void
  (e: 'upload', files: File[]): void
  /** 打开详情抽屉：父组件据此按 id 加载预览 blob（preview 接口需鉴权头） */
  (e: 'open', file: FileItem): void
  /** 打开云端文件抽屉：父组件按需拉取 GET /api/files/cloud（可能较慢，不随列表预加载） */
  (e: 'open-cloud'): void
  (e: 'rename', payload: { id: number | string; displayName: string }): void
  (e: 'delete', id: number | string): void
  (e: 'delete-cloud', fileId?: string): void
  (e: 'purge-cloud'): void
  (e: 'refresh-cloud'): void
  /** 引用到聊天：父级写入 file-ref store 后跳聊天页（附件区自动带上，不重传） */
  (e: 'reference', file: FileItem): void
}>()

const STORAGE_OPTIONS: Array<{ label: string; value: StorageFilter }> = [
  { label: '全部', value: 'all' },
  { label: '云端', value: 'cloud' },
  { label: '本地', value: 'local' }
]

const fileInput = ref<HTMLInputElement | null>(null)

/* ---------------- 搜索 / 筛选（受控） ---------------- */
const keywordModel = computed({
  get: () => props.keyword,
  set: (v: string) => emit('search', v)
})

const storageFilter = ref<StorageFilter>('all')
const statusFilter = ref<StatusFilter>('all')

const hasFilter = computed(
  () => Boolean(props.keyword.trim()) || storageFilter.value !== 'all' || statusFilter.value !== 'all'
)

/** 存储筛选需要服务端参与（分页场景），因此改筛选项时通知父组件 */
function applyFilter() {
  emit('filter-change', { storageType: storageFilter.value, status: statusFilter.value })
}

function onStorageChange(value: StorageFilter) {
  storageFilter.value = value
  applyFilter()
}

function resetFilter() {
  storageFilter.value = 'all'
  statusFilter.value = 'all'
  keywordModel.value = ''
  applyFilter()
}

/* ---------------- 统计 ---------------- */
const totalPages = computed(() => Math.max(1, Math.ceil((props.total || 0) / (props.pageSize || 20))))

const pageCloudCount = computed(() => props.files.filter(f => f.storageType === 'cloud').length)
const pageLocalCount = computed(() => props.files.filter(f => f.storageType !== 'cloud').length)
const pageOrphanCount = computed(() => props.files.filter(f => f.status === 'orphan').length)

const totalSizeText = computed(() => {
  const bytes = Number(props.totalSize) || 0
  if (bytes <= 0) return '0 B'
  const units = ['B', 'KB', 'MB', 'GB']
  let i = 0
  let n = bytes
  while (n >= 1024 && i < units.length - 1) {
    n /= 1024
    i++
  }
  return `${i === 0 ? n : n.toFixed(1)} ${units[i]}`
})

/* ---------------- 上传 ---------------- */
function onPick(e: Event) {
  const input = e.target as HTMLInputElement
  const picked = [...(input.files || [])]
  if (!picked.length) return
  // 后端为单文件接口（@RequestParam("file")），父组件按文件逐个 POST /api/files
  emit('upload', picked)
  input.value = ''
}

/* ---------------- 详情抽屉 ---------------- */
const detailOpen = ref(false)
const current = ref<FileItem | null>(null)

function openDetail(file: FileItem) {
  current.value = file
  detailOpen.value = true
  // 预览需要鉴权头，父组件用 fetch + blob 生成地址后回传 previewSrc
  emit('open', file)
}

function onDelete(file: FileItem) {
  emit('delete', file.id)
}

function onDeleteById(id: number | string) {
  detailOpen.value = false
  emit('delete', id)
}

/* ---------------- 云端抽屉 ---------------- */
const cloudOpen = ref(false)

/** 打开云端抽屉：云端清单由服务端实时拉取（可能较慢），故交给父组件按需加载 */
function onOpenCloud() {
  cloudOpen.value = true
  emit('open-cloud')
}

function closeAll() {
  detailOpen.value = false
  cloudOpen.value = false
}

/**
 * 「进入页面才拉数据」的触发点（原 App.vue 的 PAGE_LOADERS 已下沉至此）
 * 父级通过 props.load 注入具体加载函数，组件只负责在最合适的时机调用它。
 */
onMounted(() => props.load?.())
</script>
