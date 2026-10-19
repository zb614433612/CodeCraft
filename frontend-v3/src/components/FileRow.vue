<!--
  文件资产行（v2）
  ----------------------------------------------------------------------------
  信息层次：类型图标（图片 accent / 文档 info）+ 显示名 + 扩展名 + 存储 + 状态 + 来源
           → 原始文件名 · MIME · 体积（等宽弱底一行，与可重命名的显示名区分）
           → 元信息：Provider / 引用次数 / 创建时间
  状态语义（与后端 FileAssetVO 一致）：
    storageType: cloud=云端 Files API（图片，另有本地副本）/ local=仅本地存储（文档）
    status:      active=可用 / deleted=已删除（远端+副本已清理，仅保留记录）/ orphan=孤儿（引用已消失，可清理）
    source:      upload=上传 / paste=粘贴 / tool_capture=工具产出（截图）
  数据接缝：
    file.*            -> GET /api/files/page（分页）
    emit('open')      -> 打开详情抽屉（PATCH /api/files/{id} 重命名）
    emit('delete')    -> DELETE /api/files/{id}
-->
<template>
  <div class="cc-file-row" :class="{ 'is-off': isOff }">
    <div class="cc-file-row__icon" :class="iconMod">
      <svg class="cc-i"><use :href="isImage ? '#i-image' : '#i-file'" /></svg>
    </div>

    <div class="cc-file-row__main">
      <!-- 显示名 + 扩展名 + 存储 + 状态 + 来源 -->
      <div class="cc-file-row__title">
        <span class="cc-file-name">{{ displayText }}</span>
        <span class="cc-tag">{{ extText }}</span>
        <span class="cc-tag" :class="{ 'cc-tag--accent': isCloud }">{{ isCloud ? '云端' : '本地' }}</span>
        <span class="cc-status" :class="statusMod">
          <span class="cc-status__dot" />{{ statusText }}
        </span>
        <span class="cc-tag">{{ sourceText }}</span>
      </div>

      <!-- 原始文件名 · MIME · 体积 -->
      <div class="cc-file-meta-line">
        <span class="cc-file-meta-line__name">{{ file.filename || '（无文件名）' }}</span>
        <span class="cc-file-meta-line__sep">·</span>
        <span class="cc-file-meta-line__spec">{{ file.mimeType || '未知类型' }}</span>
        <span class="cc-file-meta-line__sep">·</span>
        <span class="cc-file-meta-line__spec">{{ sizeText }}</span>
      </div>

      <!-- 元信息 -->
      <div class="cc-file-row__meta">
        <span v-if="file.providerCode" class="cc-file-meta-item">
          <svg class="cc-i cc-i--sm"><use href="#i-agent" /></svg>{{ file.providerCode }}
        </span>
        <span v-if="refCount > 0" class="cc-file-meta-item">
          <svg class="cc-i cc-i--sm"><use href="#i-link" /></svg>被引用 <b>{{ refCount }}</b> 次
        </span>
        <span v-else-if="isOrphan" class="cc-file-meta-item cc-file-ref--none">
          <svg class="cc-i cc-i--sm"><use href="#i-link" /></svg>引用会话已删除，可清理
        </span>
        <span v-else class="cc-file-meta-item">
          <svg class="cc-i cc-i--sm"><use href="#i-link" /></svg>未被引用
        </span>
        <span v-if="file.status === 'deleted'">云端文件与本地副本已清理，仅保留记录</span>
        <span v-else>创建于 {{ formatTime(file.createdAt) }}</span>
      </div>
    </div>

    <!-- 右侧：hover 才显的操作 -->
    <div class="cc-file-row__side">
      <div class="cc-file-row__ops">
        <!-- 引用到聊天：写入 file-ref store 后跳聊天页，附件区自动带上（不重传） -->
        <button class="cc-icon-btn cc-icon-btn--sm" title="引用到聊天" @click="$emit('reference', file)">
          <svg class="cc-i cc-i--sm"><use href="#i-link" /></svg>
        </button>
        <button class="cc-icon-btn cc-icon-btn--sm" title="预览 / 重命名" @click="$emit('open', file)">
          <svg class="cc-i cc-i--sm"><use href="#i-brush" /></svg>
        </button>
        <button class="cc-icon-btn cc-icon-btn--sm" title="删除" @click="$emit('delete', file)">
          <svg class="cc-i cc-i--sm"><use href="#i-trash" /></svg>
        </button>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'

export interface FileItem {
  id: number | string
  /** 远端 Files API 文件ID（仅本地文件为空） */
  fileId?: string
  /** 原始文件名 */
  filename?: string
  /** 显示名（重命名后；空时展示 filename） */
  displayName?: string
  /** MIME 类型，如 image/png */
  mimeType?: string
  /** cloud=云端 Files API（图片）/ local=本地存储（文档） */
  storageType?: string
  /** 字节数 */
  size?: number
  /** upload / paste / tool_capture 等 */
  source?: string
  /** active / deleted / orphan */
  status?: string
  providerCode?: string
  createdAt?: string
  /** 预览地址（本地副本）：/api/files/{id}/preview */
  previewUrl?: string
  /** withRefs=true 时返回的引用信息 */
  references?: Array<{ conversationId?: number | string; messageId?: number | string }>
}

const props = defineProps<{ file: FileItem }>()

defineEmits<{
  (e: 'open', file: FileItem): void
  (e: 'delete', file: FileItem): void
  /** 引用到聊天：父级写入 file-ref store 后跳聊天页 */
  (e: 'reference', file: FileItem): void
}>()

const SOURCE_MAP: Record<string, string> = {
  upload: '上传',
  paste: '粘贴',
  tool_capture: '工具产出',
  tool: '工具产出'
}

const isCloud = computed(() => props.file.storageType === 'cloud')
const isImage = computed(() => (props.file.mimeType || '').startsWith('image/'))
const isOrphan = computed(() => props.file.status === 'orphan')
/** 已删除 / 孤儿：整行降饱和 */
const isOff = computed(() => props.file.status === 'deleted' || props.file.status === 'orphan')
const iconMod = computed(() => (isImage.value ? 'is-image' : 'is-doc'))

/** 显示名优先，其次原始文件名 */
const displayText = computed(() => props.file.displayName || props.file.filename || '（未命名）')

/** 扩展名：取原始文件名后缀，最长 5 字符（避免超长 tag） */
const extText = computed(() => {
  const name = props.file.filename || ''
  const idx = name.lastIndexOf('.')
  if (idx <= 0 || idx === name.length - 1) return 'FILE'
  return name.slice(idx + 1).toUpperCase().slice(0, 5)
})

const statusText = computed(() => {
  switch (props.file.status) {
    case 'deleted': return '已删除'
    case 'orphan': return '孤儿'
    default: return '可用'
  }
})

const statusMod = computed(() => {
  switch (props.file.status) {
    case 'deleted': return ''
    case 'orphan': return 'is-orphan'
    default: return 'is-connected'
  }
})

const sourceText = computed(() => SOURCE_MAP[props.file.source || ''] || '未知来源')

const refCount = computed(() => (props.file.references || []).length)

/** 体积格式化：后端 size 为字节数 */
const sizeText = computed(() => {
  const bytes = Number(props.file.size) || 0
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

/** 相对时间：今天 HH:mm / 昨天 / N 天前 / 具体日期 */
function formatTime(value?: string): string {
  if (!value) return '-'
  const date = new Date(value.replace(/-/g, '/'))
  if (Number.isNaN(date.getTime())) return value
  const now = new Date()
  if (date.toDateString() === now.toDateString()) {
    return `今天 ${String(date.getHours()).padStart(2, '0')}:${String(date.getMinutes()).padStart(2, '0')}`
  }
  const diffDays = Math.floor((now.getTime() - date.getTime()) / 86_400_000)
  if (diffDays === 1) return '昨天'
  if (diffDays > 1 && diffDays < 7) return `${diffDays} 天前`
  return `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, '0')}-${String(date.getDate()).padStart(2, '0')}`
}
</script>
