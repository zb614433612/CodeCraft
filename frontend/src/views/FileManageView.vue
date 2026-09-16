<template>
  <div class="file-manage">
    <!-- ===== 头部卡片 ===== -->
    <div class="page-header">
      <div class="header-left">
        <div class="header-icon-box">
          <span class="header-icon">📁</span>
        </div>
        <div class="header-text">
          <h2 class="header-title">文件管理</h2>
          <p class="header-subtitle">管理上传的文件（图片→云端 Files API；文档→本地存储）：预览/下载、重命名、删除、引用到聊天</p>
        </div>
      </div>
      <div class="header-right">
        <div class="stat-item">
          <span class="stat-num">{{ files.length }}</span>
          <span class="stat-label">文件总数</span>
        </div>
        <div class="stat-divider"></div>
        <div class="stat-item">
          <span class="stat-num">{{ formatAssetSize(totalSize) }}</span>
          <span class="stat-label">占用空间</span>
        </div>
      </div>
    </div>

    <!-- ===== 工具栏 ===== -->
    <div class="toolbar-card">
      <div class="toolbar-left">
        <div class="search-box">
          <SearchOutlined class="search-icon" />
          <input
            v-model="keyword"
            placeholder="搜索文件名..."
            class="search-input"
            @keyup.enter="loadFiles(true)"
          />
          <CloseCircleFilled
            v-if="keyword"
            class="search-clear"
            @click="keyword = ''; loadFiles(true)"
          />
        </div>
        <a-select
          v-model:value="statusFilter"
          placeholder="全部状态"
          style="width: 130px"
          @change="loadFiles(true)"
        >
          <a-select-option value="">全部状态</a-select-option>
          <a-select-option value="active">正常</a-select-option>
          <a-select-option value="orphan">待清理</a-select-option>
        </a-select>
        <a-select
          v-model:value="storageFilter"
          placeholder="全部存储"
          style="width: 130px"
          @change="loadFiles(true)"
        >
          <a-select-option value="">全部存储</a-select-option>
          <a-select-option value="cloud">云端</a-select-option>
          <a-select-option value="local">本地</a-select-option>
        </a-select>
        <a-button class="refresh-btn" @click="loadFiles(true)">
          <template #icon><ReloadOutlined /></template>
          刷新
        </a-button>
      </div>
      <div class="toolbar-right">
        <a-button type="primary" :loading="uploading" @click="triggerUpload" class="upload-btn">
          <template #icon><PlusOutlined /></template>
          上传文件
        </a-button>
        <input
          ref="fileInputRef"
          type="file"
          accept="image/jpeg,image/png,image/gif,image/webp,.pdf,.doc,.docx,.xls,.xlsx,.csv,.txt,.md,.log,.json,.yml,.yaml,.xml,.properties,.ini,.toml,.ts,.tsx,.vue,.js,.jsx,.css,.scss,.less,.html,.java,.py,.go,.rs,.c,.cpp,.h,.sh,.bat,.ps1,.sql"
          multiple
          style="display: none"
          @change="handleUpload"
        />
      </div>
    </div>

    <!-- ===== 内容区 ===== -->
    <div class="content-card">
      <!-- 加载中 -->
      <div v-if="loading" class="state-box">
        <a-spin size="large" />
        <p class="state-text">加载文件中...</p>
      </div>

      <!-- 空状态 -->
      <div v-else-if="files.length === 0" class="state-box empty-state">
        <div class="empty-illustration">
          <span class="empty-icon">📁</span>
        </div>
        <p class="empty-title">{{ hasActiveFilter ? '未找到匹配的文件' : '还没有文件' }}</p>
        <p class="empty-desc">
          {{ hasActiveFilter ? '尝试调整搜索关键词或筛选条件' : '点击「上传文件」或在聊天页上传：图片保存到云端 Files API，文档保存到本地' }}
        </p>
        <a-button v-if="!hasActiveFilter" type="primary" @click="triggerUpload" class="empty-cta">
          <PlusOutlined /> 上传第一个文件
        </a-button>
        <a-button v-else @click="clearFilters" class="empty-cta">清除筛选</a-button>
      </div>

      <!-- 文件列表 -->
      <div v-else class="file-list">
        <div v-for="f in files" :key="f.id" class="file-row" @click="openDetail(f)">
          <!-- 缩略图（图片→缩略图；文档→文件格式图标） -->
          <div class="row-thumb">
            <template v-if="isImageAsset(f)">
              <img v-if="previewUrlMap[f.id]" :src="previewUrlMap[f.id]" alt="preview" />
              <div v-else class="thumb-placeholder">🖼️</div>
            </template>
            <div v-else class="thumb-placeholder doc-placeholder">{{ docTypeIcon(docTypeOf(f.filename, f.mimeType)) }}</div>
          </div>
          <!-- 文件信息 -->
          <div class="row-info">
            <div class="row-name-line">
              <span class="row-name" :title="fileAssetDisplayName(f)">{{ fileAssetDisplayName(f) }}</span>
              <span v-if="f.displayName && f.displayName !== f.filename" class="row-origin-name" :title="f.filename">（{{ f.filename }}）</span>
            </div>
            <div class="row-meta">
              <span>{{ formatAssetSize(f.size) }}</span>
              <span class="meta-divider">·</span>
              <span>{{ f.mimeType || '未知类型' }}</span>
              <span class="meta-divider">·</span>
              <span>{{ formatTime(f.createdAt) }}</span>
            </div>
          </div>
          <!-- 标签 -->
          <div class="row-tags">
            <a-tag :color="f.storageType === 'local' ? 'cyan' : 'geekblue'" size="small">
              {{ f.storageType === 'local' ? '本地' : '云端' }}
            </a-tag>
            <a-tag :color="f.source === 'paste' ? 'purple' : 'blue'" size="small">
              {{ f.source === 'paste' ? '粘贴' : '上传' }}
            </a-tag>
            <a-tag v-if="f.status === 'orphan'" color="orange" size="small">待清理</a-tag>
            <a-tag v-else-if="f.status !== 'active'" color="default" size="small">{{ f.status }}</a-tag>
          </div>
          <!-- 引用数 -->
          <div class="row-refs" :title="refCountText(f)">
            {{ f.references?.length ?? 0 }} 引用
          </div>
          <!-- 操作 -->
          <div class="row-actions" @click.stop>
            <a-tooltip title="预览">
              <span class="action-btn" @click="openDetail(f)">🔍</span>
            </a-tooltip>
            <a-tooltip title="引用到聊天">
              <span class="action-btn" @click="pinToChat(f)">📌</span>
            </a-tooltip>
            <a-tooltip title="重命名">
              <span class="action-btn" @click="openRename(f)">✏️</span>
            </a-tooltip>
            <a-tooltip :title="f.storageType === 'local' ? '删除（同时删除本地文件）' : '删除（同时删除云端文件）'">
              <span class="action-btn danger" @click="handleDelete(f)">🗑️</span>
            </a-tooltip>
          </div>
        </div>

        <!-- 加载更多 -->
        <div v-if="hasMore" class="load-more">
          <a-button :loading="loadingMore" @click="loadFiles(false)">加载更多</a-button>
        </div>
      </div>
    </div>

    <!-- ===== 详情抽屉 ===== -->
    <a-drawer
      v-model:open="drawerVisible"
      title="文件详情"
      placement="right"
      :width="480"
      :body-style="{ padding: '16px' }"
    >
      <div v-if="currentFile" class="detail-body">
        <!-- 预览：图片→大图（点击放大）；文档→文件格式图标 + 下载 -->
        <template v-if="isImageAsset(currentFile)">
          <div class="detail-preview" @click="bigPreviewUrl = previewUrlMap[currentFile.id] || ''">
            <img v-if="previewUrlMap[currentFile.id]" :src="previewUrlMap[currentFile.id]" alt="preview" />
            <a-spin v-else style="margin: 60px auto" />
          </div>
        </template>
        <div v-else class="detail-preview detail-preview--doc">
          <span class="detail-doc-icon">{{ docTypeIcon(docTypeOf(currentFile.filename, currentFile.mimeType)) }}</span>
          <a-button class="detail-doc-download" @click="downloadCurrent">⬇️ 下载文件</a-button>
        </div>
        <div class="detail-fields">
          <div class="detail-field">
            <span class="field-label">显示名</span>
            <span class="field-value">{{ fileAssetDisplayName(currentFile) }}</span>
          </div>
          <div class="detail-field">
            <span class="field-label">原始文件名</span>
            <span class="field-value">{{ currentFile.filename }}</span>
          </div>
          <div class="detail-field">
            <span class="field-label">存储类型</span>
            <span class="field-value">{{ currentFile.storageType === 'local' ? '本地存储' : '云端（Files API）' }}</span>
          </div>
          <div class="detail-field" v-if="currentFile.storageType !== 'local'">
            <span class="field-label">远端 File ID</span>
            <span class="field-value mono">{{ currentFile.fileId || '—' }}</span>
          </div>
          <div class="detail-field">
            <span class="field-label">大小 / 类型</span>
            <span class="field-value">{{ formatAssetSize(currentFile.size) }} · {{ currentFile.mimeType || '未知' }}</span>
          </div>
          <div class="detail-field">
            <span class="field-label">Provider</span>
            <span class="field-value">{{ currentFile.providerCode || '—' }}</span>
          </div>
          <div class="detail-field">
            <span class="field-label">来源 / 状态</span>
            <span class="field-value">{{ currentFile.source }} / {{ currentFile.status }}</span>
          </div>
          <div class="detail-field">
            <span class="field-label">创建时间</span>
            <span class="field-value">{{ formatTime(currentFile.createdAt) }}</span>
          </div>
          <div class="detail-field">
            <span class="field-label">被引用</span>
            <span class="field-value">{{ refCountText(currentFile) }}</span>
          </div>
        </div>
        <div class="detail-actions">
          <a-button type="primary" @click="pinToChat(currentFile!)">📌 引用到聊天</a-button>
          <a-button @click="downloadCurrent">⬇️ 下载</a-button>
          <a-button @click="openRename(currentFile!)">✏️ 重命名</a-button>
          <a-button danger @click="handleDelete(currentFile!)">🗑️ 删除</a-button>
        </div>
      </div>
    </a-drawer>

    <!-- 大图预览遮罩 -->
    <div v-if="bigPreviewUrl" class="big-preview-mask" @click="bigPreviewUrl = ''">
      <img :src="bigPreviewUrl" alt="big-preview" />
    </div>

    <!-- ===== 重命名弹窗 ===== -->
    <a-modal
      v-model:open="renameVisible"
      title="重命名"
      :confirm-loading="renameSubmitting"
      @ok="submitRename"
    >
      <a-input
        v-model:value="renameValue"
        placeholder="输入新的显示名"
        :maxlength="512"
        @press-enter="submitRename"
      />
      <p class="rename-hint">仅修改本地显示名（Files API 无远端重命名端点）</p>
    </a-modal>

    <!-- ===== 引用到聊天：目标选择弹窗 ===== -->
    <a-modal
      v-model:open="refModalVisible"
      title="引用到聊天"
      :footer="null"
      :width="420"
    >
      <!-- 待引用文件 -->
      <div v-if="refTargetFile" class="ref-target-file">
        <template v-if="isImageAsset(refTargetFile)">
          <img
            v-if="previewUrlMap[refTargetFile.id]"
            :src="previewUrlMap[refTargetFile.id]"
            class="ref-target-thumb"
            alt="target"
          />
          <div v-else class="ref-target-thumb ref-thumb-placeholder">🖼️</div>
        </template>
        <div v-else class="ref-target-thumb ref-thumb-placeholder">{{ docTypeIcon(docTypeOf(refTargetFile.filename, refTargetFile.mimeType)) }}</div>
        <span class="ref-target-name" :title="fileAssetDisplayName(refTargetFile)">{{ fileAssetDisplayName(refTargetFile) }}</span>
      </div>

      <!-- 新建聊天 -->
      <div class="ref-new-chat" @click="pinToNewChat">
        <PlusOutlined />
        <span>新建聊天</span>
      </div>

      <!-- 会话搜索 -->
      <a-input
        v-model:value="refConvKeyword"
        class="ref-conv-search"
        placeholder="搜索会话标题"
        allow-clear
      >
        <template #prefix><SearchOutlined /></template>
      </a-input>

      <!-- 会话列表 -->
      <a-spin :spinning="refModalLoading">
        <div class="ref-conv-list">
          <div
            v-for="c in filteredRefConversations"
            :key="c.id"
            class="ref-conv-item"
            @click="pinToConversation(c)"
          >
            <span class="ref-conv-title" :title="c.title">{{ c.title }}</span>
            <span class="ref-conv-time">{{ formatTime(c.updatedAt) }}</span>
          </div>
          <div v-if="!refModalLoading && filteredRefConversations.length === 0" class="ref-conv-empty">
            {{ refConvs.length === 0 ? '暂无历史会话，可直接新建聊天' : '没有匹配的会话' }}
          </div>
        </div>
      </a-spin>
    </a-modal>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { message, Modal } from 'ant-design-vue'
import {
  SearchOutlined, PlusOutlined, ReloadOutlined, CloseCircleFilled
} from '@ant-design/icons-vue'
import {
  listFileAssets, uploadFileAsset, getFileAsset, renameFileAsset, deleteFileAsset,
  getFilePreviewBlobUrl, downloadFileAsset, invalidatePreviewCache, fileAssetDisplayName, formatAssetSize,
  docTypeOf, docTypeIcon, isImageMime,
  type FileAssetData
} from '@/api/file-asset'
import { useFileRefStore } from '@/store/file-ref'
import { compressImageIfNeeded } from '@/utils/image-compress'
import { getConversationList } from '@/api/conversation'

const router = useRouter()
const fileRefStore = useFileRefStore()

// ===== 状态 =====
const files = ref<FileAssetData[]>([])
const loading = ref(false)
const loadingMore = ref(false)
const uploading = ref(false)
const keyword = ref('')
const statusFilter = ref('')
/** M7：存储类型筛选（''=全部 / cloud=云端 / local=本地） */
const storageFilter = ref('')
const hasMore = ref(false)
const fileInputRef = ref<HTMLInputElement | null>(null)

// 缩略图 blob URL 映射（id → objectURL）
const previewUrlMap = ref<Record<number, string>>({})

// 详情/重命名/大图
const drawerVisible = ref(false)
const currentFile = ref<FileAssetData | null>(null)
const bigPreviewUrl = ref('')
const renameVisible = ref(false)
const renameValue = ref('')
const renameTarget = ref<FileAssetData | null>(null)
const renameSubmitting = ref(false)

const PAGE_SIZE = 50
const MAX_UPLOAD_SIZE = 64 * 1024 * 1024

const totalSize = computed(() => files.value.reduce((sum, f) => sum + (f.size || 0), 0))

/** 是否有激活的筛选条件（空状态文案切换用） */
const hasActiveFilter = computed(() => !!(keyword.value || statusFilter.value || storageFilter.value))

// ===== 数据加载 =====
const loadFiles = async (reset = true) => {
  if (reset) {
    loading.value = true
  } else {
    loadingMore.value = true
  }
  try {
    const params: Parameters<typeof listFileAssets>[0] = {
      limit: PAGE_SIZE,
      order: 'desc'
    }
    if (keyword.value.trim()) params.keyword = keyword.value.trim()
    if (statusFilter.value) params.status = statusFilter.value
    if (storageFilter.value) params.storageType = storageFilter.value
    if (!reset && files.value.length > 0) {
      params.after = files.value[files.value.length - 1].id
    }
    const res = await listFileAssets(params)
    if (res.code === 200 && res.data) {
      if (reset) {
        files.value = res.data
        // 重置后重新计算 hasMore：拉满一页说明可能还有
        hasMore.value = res.data.length >= PAGE_SIZE
        await ensurePreviews(res.data)
      } else {
        files.value = [...files.value, ...res.data]
        hasMore.value = res.data.length >= PAGE_SIZE
        await ensurePreviews(res.data)
      }
    } else {
      message.error(res.message || '加载文件列表失败')
    }
  } catch (e: any) {
    message.error(e.message || '加载文件列表失败')
  } finally {
    loading.value = false
    loadingMore.value = false
  }
}

/** 异步加载缩略图（仅图片，blob URL 带全局缓存；文档显示图标无需加载） */
const ensurePreviews = async (list: FileAssetData[]) => {
  for (const f of list) {
    if (!isImageMime(f.mimeType)) continue
    if (previewUrlMap.value[f.id]) continue
    try {
      const url = await getFilePreviewBlobUrl(f.id)
      previewUrlMap.value = { ...previewUrlMap.value, [f.id]: url }
    } catch (e) {
      // 单张失败不阻塞列表（占位图兜底）
      console.warn('加载缩略图失败:', f.id, e)
    }
  }
}

/** 是否图片资产（按 MIME 判定；非图片视为文档） */
const isImageAsset = (f: FileAssetData) => isImageMime(f.mimeType)

const clearFilters = () => {
  keyword.value = ''
  statusFilter.value = ''
  storageFilter.value = ''
  loadFiles(true)
}

// ===== 上传 =====
const triggerUpload = () => {
  fileInputRef.value?.click()
}

const handleUpload = async (event: Event) => {
  const target = event.target as HTMLInputElement
  const list = target.files
  if (!list || list.length === 0) return

  uploading.value = true
  let okCount = 0
  let failCount = 0
  try {
    for (const file of Array.from(list)) {
      if (file.size > MAX_UPLOAD_SIZE) {
        message.error(`「${file.name}」超过大小上限（最大 ${MAX_UPLOAD_SIZE / 1024 / 1024}MB）`)
        failCount++
        continue
      }
      try {
        // P3：图片上传前可选预压缩（超阈值转 JPEG；失败自动回退原图）
        const uploadTarget = file.type.startsWith('image/') ? await compressImageIfNeeded(file) : file
        const res = await uploadFileAsset(uploadTarget, { source: 'upload' })
        if (res.code === 200 && res.data) {
          okCount++
        } else {
          failCount++
          message.error(`「${file.name}」上传失败：${res.message || '未知错误'}`)
        }
      } catch (e: any) {
        failCount++
        message.error(`「${file.name}」上传失败：${e.message || '网络错误'}`)
      }
    }
    if (okCount > 0) {
      message.success(`成功上传 ${okCount} 个文件${failCount > 0 ? `，${failCount} 个失败` : ''}`)
      loadFiles(true)
    }
  } finally {
    uploading.value = false
    target.value = ''
  }
}

// ===== 详情 =====
const openDetail = async (f: FileAssetData) => {
  currentFile.value = f
  drawerVisible.value = true
  // 拉取最新详情（含引用信息）
  try {
    const res = await getFileAsset(f.id)
    if (res.code === 200 && res.data) {
      currentFile.value = res.data
    }
  } catch (e) {
    console.warn('加载文件详情失败:', e)
  }
  // 确保缩略图已加载（仅图片；文档显示图标）
  await ensurePreviews([f])
}

/** M7：下载当前详情文件（图片/文档通用，走鉴权 blob 下载） */
const downloadCurrent = async () => {
  if (!currentFile.value) return
  try {
    await downloadFileAsset(currentFile.value.id, fileAssetDisplayName(currentFile.value))
  } catch (e: any) {
    message.error(e.message || '下载失败')
  }
}

// ===== 引用到聊天（目标选择：新建聊天 / 指定已有会话） =====
const refModalVisible = ref(false)
const refModalLoading = ref(false)
const refTargetFile = ref<FileAssetData | null>(null)
const refConvs = ref<{ id: number; title: string; updatedAt: string; agentConfigId?: number | null }[]>([])
const refConvKeyword = ref('')

/** 搜索过滤后的会话列表 */
const filteredRefConversations = computed(() => {
  const kw = refConvKeyword.value.trim().toLowerCase()
  if (!kw) return refConvs.value
  return refConvs.value.filter(c => c.title.toLowerCase().includes(kw))
})

/** 打开「引用到聊天」弹窗（加载会话列表供选择目标） */
const pinToChat = async (f: FileAssetData) => {
  if (f.status !== 'active') {
    message.warning('该文件已失效，无法引用')
    return
  }
  refTargetFile.value = f
  refConvKeyword.value = ''
  refModalVisible.value = true
  refModalLoading.value = true
  try {
    const res = await getConversationList()
    if (res.code === 200 && res.data) {
      refConvs.value = res.data.map(c => ({
        id: c.id,
        title: c.name || '未命名会话',
        updatedAt: c.updatedAt,
        agentConfigId: c.agentConfigId
      }))
    } else {
      refConvs.value = []
      message.error(res.message || '加载会话列表失败')
    }
  } catch (e: any) {
    refConvs.value = []
    message.error(e.message || '加载会话列表失败')
  } finally {
    refModalLoading.value = false
  }
}

/** 写入跨页引用暂存（引用入口公共部分） */
const stageRef = (f: FileAssetData) => {
  fileRefStore.addRefs([{
    id: f.id,
    fileId: f.fileId,
    filename: f.filename,
    displayName: f.displayName,
    mimeType: f.mimeType,
    storageType: f.storageType,
    size: f.size
  }])
}

/** 引用 → 新建聊天 */
const pinToNewChat = () => {
  const f = refTargetFile.value
  if (!f) return
  stageRef(f)
  refModalVisible.value = false
  message.success(`已引用「${fileAssetDisplayName(f)}」，将打开新聊天`)
  router.push({ path: '/code-assistant', query: { refNew: '1' } })
}

/** 引用 → 指定已有会话 */
const pinToConversation = (c: { id: number; agentConfigId?: number | null }) => {
  const f = refTargetFile.value
  if (!f) return
  stageRef(f)
  refModalVisible.value = false
  const query: Record<string, string> = { refConv: String(c.id) }
  if (c.agentConfigId != null) query.refAgent = String(c.agentConfigId)
  message.success(`已引用「${fileAssetDisplayName(f)}」，正在跳转到目标聊天...`)
  router.push({ path: '/code-assistant', query })
}

// ===== 重命名 =====
const openRename = (f: FileAssetData) => {
  renameTarget.value = f
  renameValue.value = fileAssetDisplayName(f)
  renameVisible.value = true
}

const submitRename = async () => {
  if (!renameTarget.value) return
  const name = renameValue.value.trim()
  if (!name) {
    message.warning('显示名不能为空')
    return
  }
  renameSubmitting.value = true
  try {
    const res = await renameFileAsset(renameTarget.value.id, name)
    if (res.code === 200 && res.data) {
      message.success('重命名成功')
      renameVisible.value = false
      // 同步本地列表与详情
      const target = files.value.find(x => x.id === renameTarget.value!.id)
      if (target) target.displayName = res.data.displayName
      if (currentFile.value?.id === renameTarget.value.id) {
        currentFile.value = { ...currentFile.value, displayName: res.data.displayName }
      }
    } else {
      message.error(res.message || '重命名失败')
    }
  } catch (e: any) {
    message.error(e.message || '重命名失败')
  } finally {
    renameSubmitting.value = false
  }
}

// ===== 删除 =====
const handleDelete = (f: FileAssetData) => {
  Modal.confirm({
    title: '确认删除',
    content: f.storageType === 'local'
      ? `将删除「${fileAssetDisplayName(f)}」：本地文件 + 引用记录。此操作不可恢复。`
      : `将删除「${fileAssetDisplayName(f)}」：云端文件（Files API）+ 本地副本 + 引用记录。此操作不可恢复。`,
    okText: '删除',
    okType: 'danger',
    cancelText: '取消',
    async onOk() {
      try {
        const res = await deleteFileAsset(f.id)
        if (res.code === 200) {
          message.success('删除成功')
          invalidatePreviewCache(f.id)
          const map = { ...previewUrlMap.value }
          delete map[f.id]
          previewUrlMap.value = map
          files.value = files.value.filter(x => x.id !== f.id)
          if (currentFile.value?.id === f.id) {
            drawerVisible.value = false
            currentFile.value = null
          }
        } else {
          message.error(res.message || '删除失败')
        }
      } catch (e: any) {
        message.error(e.message || '删除失败')
      }
    }
  })
}

// ===== 展示辅助 =====
const refCountText = (f: FileAssetData): string => {
  const refs = f.references || []
  if (refs.length === 0) return '未被引用'
  const convIds = Array.from(new Set(refs.map(r => r.conversationId)))
  return `被 ${convIds.length} 个会话引用（${refs.length} 条引用）`
}

const formatTime = (iso?: string): string => {
  if (!iso) return '-'
  try {
    const d = new Date(iso)
    if (isNaN(d.getTime())) return iso
    const pad = (n: number) => String(n).padStart(2, '0')
    return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}`
  } catch {
    return iso
  }
}

onMounted(() => {
  loadFiles(true)
})
</script>

<style scoped>
.file-manage {
  padding: 20px 24px;
  max-width: 1200px;
  margin: 0 auto;
  display: flex;
  flex-direction: column;
  gap: 16px;
  height: 100%;
  overflow-y: auto;
}

/* ===== 头部 ===== */
.page-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  background: var(--bg-card, #fff);
  border: 1px solid var(--border, #e5e7eb);
  border-radius: 14px;
  padding: 18px 24px;
}
.header-left { display: flex; align-items: center; gap: 14px; }
.header-icon-box {
  width: 48px; height: 48px;
  display: flex; align-items: center; justify-content: center;
  background: var(--accent-lt, rgba(139, 92, 246, 0.1));
  border-radius: 12px;
  font-size: 24px;
}
.header-title { margin: 0; font-size: 18px; font-weight: 600; color: var(--text-1, #111827); }
.header-subtitle { margin: 2px 0 0; font-size: 12px; color: var(--text-3, #9ca3af); }
.header-right { display: flex; align-items: center; gap: 18px; }
.stat-item { display: flex; flex-direction: column; align-items: center; }
.stat-num { font-size: 18px; font-weight: 700; color: var(--accent, #8b5cf6); }
.stat-label { font-size: 11px; color: var(--text-3, #9ca3af); }
.stat-divider { width: 1px; height: 28px; background: var(--border, #e5e7eb); }

/* ===== 工具栏 ===== */
.toolbar-card {
  display: flex; align-items: center; justify-content: space-between;
  background: var(--bg-card, #fff);
  border: 1px solid var(--border, #e5e7eb);
  border-radius: 14px;
  padding: 12px 16px;
  gap: 12px;
  flex-wrap: wrap;
}
.toolbar-left { display: flex; align-items: center; gap: 10px; flex-wrap: wrap; }
.search-box {
  display: flex; align-items: center; gap: 6px;
  background: var(--bg-input, #f9fafb);
  border: 1px solid var(--border, #e5e7eb);
  border-radius: 8px;
  padding: 5px 10px;
  width: 230px;
}
.search-icon { color: var(--text-3, #9ca3af); font-size: 13px; }
.search-input {
  border: none; outline: none; background: transparent;
  font-size: 13px; width: 100%;
  color: var(--text-1, #111827);
}
.search-clear { color: var(--text-4, #d1d5db); cursor: pointer; font-size: 13px; }
.search-clear:hover { color: var(--text-2, #6b7280); }

/* ===== 内容区 ===== */
.content-card {
  background: var(--bg-card, #fff);
  border: 1px solid var(--border, #e5e7eb);
  border-radius: 14px;
  padding: 8px;
  flex: 1;
  min-height: 300px;
}
.state-box {
  display: flex; flex-direction: column; align-items: center; justify-content: center;
  padding: 70px 20px; gap: 10px;
}
.state-text { color: var(--text-3, #9ca3af); font-size: 13px; }
.empty-icon { font-size: 44px; }
.empty-title { margin: 6px 0 0; font-size: 15px; font-weight: 600; color: var(--text-1, #111827); }
.empty-desc { margin: 0 0 10px; font-size: 12.5px; color: var(--text-3, #9ca3af); max-width: 420px; text-align: center; }

/* ===== 文件行 ===== */
.file-list { display: flex; flex-direction: column; }
.file-row {
  display: flex; align-items: center; gap: 14px;
  padding: 10px 12px;
  border-radius: 10px;
  cursor: pointer;
  transition: background 0.15s;
}
.file-row:hover { background: var(--bg-hover, #f9fafb); }
.row-thumb {
  width: 52px; height: 52px;
  border-radius: 10px;
  overflow: hidden;
  background: var(--bg-input, #f3f4f6);
  display: flex; align-items: center; justify-content: center;
  flex-shrink: 0;
  border: 1px solid var(--border, #e5e7eb);
}
.row-thumb img { width: 100%; height: 100%; object-fit: cover; }
.thumb-placeholder { font-size: 22px; opacity: 0.5; }
/* M7：文档占位（文件格式图标） */
.doc-placeholder { font-size: 26px; opacity: 0.9; }
.row-info { flex: 1; min-width: 0; }
.row-name-line { display: flex; align-items: baseline; gap: 6px; min-width: 0; }
.row-name {
  font-size: 13.5px; font-weight: 600; color: var(--text-1, #111827);
  overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
}
.row-origin-name { font-size: 11.5px; color: var(--text-4, #9ca3af); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.row-meta { display: flex; align-items: center; gap: 6px; font-size: 11.5px; color: var(--text-3, #9ca3af); margin-top: 3px; }
.meta-divider { opacity: 0.5; }
.row-tags { display: flex; align-items: center; gap: 4px; flex-shrink: 0; }
.row-refs { font-size: 11.5px; color: var(--text-3, #9ca3af); flex-shrink: 0; width: 80px; text-align: center; }
.row-actions { display: flex; align-items: center; gap: 2px; flex-shrink: 0; }
.action-btn {
  padding: 4px 7px; border-radius: 8px; cursor: pointer;
  font-size: 14px; transition: background 0.15s; opacity: 0.8;
}
.action-btn:hover { background: var(--bg-hover, #f3f4f6); opacity: 1; }
.action-btn.danger:hover { background: #fef2f2; }
.load-more { display: flex; justify-content: center; padding: 14px; }

/* ===== 详情抽屉 ===== */
.detail-body { display: flex; flex-direction: column; gap: 14px; }
.detail-preview {
  border-radius: 12px; overflow: hidden;
  background: var(--bg-input, #f3f4f6);
  display: flex; align-items: center; justify-content: center;
  min-height: 220px; max-height: 320px; cursor: zoom-in;
  border: 1px solid var(--border, #e5e7eb);
}
.detail-preview img { max-width: 100%; max-height: 320px; object-fit: contain; }
/* M7：文档详情预览（图标 + 下载按钮） */
.detail-preview--doc {
  flex-direction: column;
  gap: 14px;
  cursor: default;
  min-height: 160px;
}
.detail-doc-icon { font-size: 52px; }
.detail-fields { display: flex; flex-direction: column; gap: 8px; }
.detail-field { display: flex; justify-content: space-between; gap: 12px; font-size: 12.5px; }
.field-label { color: var(--text-3, #9ca3af); flex-shrink: 0; }
.field-value { color: var(--text-1, #111827); text-align: right; word-break: break-all; }
.field-value.mono { font-family: monospace; font-size: 11.5px; }
.detail-actions { display: flex; gap: 8px; padding-top: 6px; }

/* ===== 大图遮罩 ===== */
.big-preview-mask {
  position: fixed; inset: 0; z-index: 2100;
  background: rgba(0, 0, 0, 0.78);
  display: flex; align-items: center; justify-content: center;
  cursor: zoom-out;
}
.big-preview-mask img { max-width: 92vw; max-height: 92vh; border-radius: 8px; }

.rename-hint { margin: 8px 0 0; font-size: 12px; color: var(--text-3, #9ca3af); }

/* ===== 引用到聊天：目标选择弹窗 ===== */
.ref-target-file {
  display: flex; align-items: center; gap: 10px;
  padding: 8px 10px; margin-bottom: 12px; border-radius: 10px;
  background: var(--bg-hover, #f9fafb);
}
.ref-target-thumb {
  width: 40px; height: 40px; border-radius: 8px; object-fit: cover;
  background: var(--bg-input, #f3f4f6); flex-shrink: 0;
}
.ref-thumb-placeholder { display: flex; align-items: center; justify-content: center; font-size: 18px; }
.ref-target-name { font-size: 13px; color: var(--text-1, #111827); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.ref-new-chat {
  display: flex; align-items: center; justify-content: center; gap: 6px;
  padding: 10px; margin-bottom: 10px; border-radius: 10px; cursor: pointer;
  border: 1px dashed var(--accent, #8b5cf6); color: var(--accent, #8b5cf6);
  font-size: 13px; font-weight: 500; transition: background 0.15s, border-color 0.15s;
}
.ref-new-chat:hover { background: rgba(139, 92, 246, 0.06); }
.ref-conv-search { margin-bottom: 8px; }
.ref-conv-list { max-height: 300px; overflow-y: auto; }
.ref-conv-item {
  display: flex; align-items: center; justify-content: space-between; gap: 10px;
  padding: 8px 10px; border-radius: 8px; cursor: pointer; transition: background 0.15s;
}
.ref-conv-item:hover { background: var(--bg-hover, #f3f4f6); }
.ref-conv-title { font-size: 13px; color: var(--text-1, #111827); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.ref-conv-time { font-size: 11.5px; color: var(--text-3, #9ca3af); flex-shrink: 0; }
.ref-conv-empty { text-align: center; padding: 24px 0; font-size: 12.5px; color: var(--text-3, #9ca3af); }
</style>
