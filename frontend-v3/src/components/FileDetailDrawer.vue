<!--
  文件详情 / 重命名抽屉（v2）
  ----------------------------------------------------------------------------
  右侧滑出（560px）。一个抽屉承担「看详情」与「改显示名」：
    1) 预览区：图片走 GET /api/files/{id}/preview（需鉴权头 → 父组件 fetch 成 blob URL 后传入 previewSrc）；
       文档类型显示占位（浏览器无法内联预览 PDF/Office）。
    2) 只读事实区：原始文件名 / MIME / 体积 / 存储·状态 / 来源 / Provider / 引用 / 创建时间 / 云端文件 ID。
    3) 可编辑：仅「显示名」——只改本地 displayName，不动原始文件名与云端对象。
  与后端约定：
    PATCH /api/files/{id}  body: { displayName }   空值表示恢复显示原始文件名
    DELETE /api/files/{id}                         远端（404 视为成功）+ 引用 + 记录 + 本地副本
  数据接缝：
    v-model:open  -> 抽屉开关
    file          -> 当前行（来自 GET /api/files/page）
    previewSrc    -> 父组件用 fetch + blob 生成的预览地址（携带鉴权头）
    emit('save')  -> PATCH /api/files/{id}
    emit('delete')-> DELETE /api/files/{id}
-->
<template>
  <aside class="cc-drawer" :class="{ 'is-open': open }" style="--drawer-w: 560px">
    <DrawerResizer storage-key="file-detail" />
    <div class="cc-drawer__head">
      <div style="font-size: var(--fs-md); font-weight: 700">文件详情 / 重命名</div>
      <button class="cc-icon-btn" style="margin-left: auto" title="关闭" @click="close">
        <svg class="cc-i cc-i--sm" style="transform: rotate(45deg)"><use href="#i-plus" /></svg>
      </button>
    </div>

    <div class="cc-drawer__body">
      <!-- 预览区：图片可点开全站灯箱（F14）看原图 -->
      <div class="cc-file-preview">
        <button
          v-if="isImage && previewSrc"
          class="cc-file-preview__zoomable"
          title="查看大图"
          @click="openPreview"
        >
          <img :src="previewSrc" alt="文件预览" />
        </button>
        <div v-else class="cc-file-preview__empty">
          <svg class="cc-i cc-i--lg"><use :href="isImage ? '#i-image' : '#i-file'" /></svg>
          <span>{{ isImage ? '图片预览需父组件传入 previewSrc（fetch preview 后转 blob）' : '该类型不支持内联预览' }}</span>
        </div>
      </div>

      <!-- 只读事实区 -->
      <div class="cc-lesson-facts">
        <div class="cc-lesson-facts__item">
          <span class="cc-lesson-facts__label">原始文件名</span>
          <span class="cc-lesson-facts__value is-mono">{{ file?.filename || '-' }}</span>
        </div>
        <div class="cc-lesson-facts__item">
          <span class="cc-lesson-facts__label">MIME 类型</span>
          <span class="cc-lesson-facts__value is-mono">{{ file?.mimeType || '-' }}</span>
        </div>
        <div class="cc-lesson-facts__item">
          <span class="cc-lesson-facts__label">体积</span>
          <span class="cc-lesson-facts__value">{{ sizeText }}</span>
        </div>
        <div class="cc-lesson-facts__item">
          <span class="cc-lesson-facts__label">存储 / 状态</span>
          <span class="cc-lesson-facts__value">{{ storageText }} · {{ statusText }}</span>
        </div>
        <div class="cc-lesson-facts__item">
          <span class="cc-lesson-facts__label">来源</span>
          <span class="cc-lesson-facts__value">{{ sourceText }}</span>
        </div>
        <div class="cc-lesson-facts__item">
          <span class="cc-lesson-facts__label">Provider</span>
          <span class="cc-lesson-facts__value">{{ file?.providerCode || '（本地存储）' }}</span>
        </div>
        <div class="cc-lesson-facts__item">
          <span class="cc-lesson-facts__label">引用</span>
          <span class="cc-lesson-facts__value">{{ refText }}</span>
        </div>
        <div class="cc-lesson-facts__item">
          <span class="cc-lesson-facts__label">创建时间</span>
          <span class="cc-lesson-facts__value">{{ file?.createdAt || '-' }}</span>
        </div>
        <div v-if="file?.fileId" class="cc-lesson-facts__item cc-lesson-facts__item--wide">
          <span class="cc-lesson-facts__label">云端文件 ID</span>
          <span class="cc-lesson-facts__value is-mono">{{ file.fileId }}</span>
        </div>
      </div>

      <div class="cc-form">
        <div class="cc-field">
          <label class="cc-field__label">显示名</label>
          <input v-model="displayName" class="cc-input" placeholder="留空则显示原始文件名" />
          <div class="cc-field__hint">只改本地显示名（PATCH /api/files/{id}），不影响原始文件名与云端对象</div>
        </div>
        <div class="cc-field">
          <div class="cc-field__hint">
            删除会清理云端文件（404 视为已删除）、引用记录与本地副本，操作不可撤销。
          </div>
        </div>
      </div>
    </div>

    <div class="cc-drawer__foot">
      <button class="cc-btn cc-btn--danger" @click="onDelete">
        <svg class="cc-i cc-i--sm"><use href="#i-trash" /></svg>删除
      </button>
      <button class="cc-btn" @click="close">取消</button>
      <button class="cc-btn cc-btn--primary" @click="onSave">保存</button>
    </div>
  </aside>
</template>

<script setup lang="ts">
import DrawerResizer from './DrawerResizer.vue'
import { computed, ref, watch } from 'vue'
import type { FileItem } from './FileRow.vue'
import { openLightbox } from '../utils/lightbox'

const props = withDefaults(
  defineProps<{
    open: boolean
    file?: FileItem | null
    /** 图片预览地址：父组件用 fetch + blob 生成（preview 接口需鉴权头） */
    previewSrc?: string
  }>(),
  {
    file: null,
    previewSrc: ''
  }
)

const emit = defineEmits<{
  (e: 'update:open', v: boolean): void
  (e: 'save', payload: { id: number | string; displayName: string }): void
  (e: 'delete', id: number | string): void
}>()

/**
 * 点预览图 → 全站灯箱（F14）。
 *
 * 抽屉里只有一张图，所以灯箱只放这一张（不跨文件拼列表）：用户是「看当前文件」
 * 而不是「浏览文件库」，能放大、能下载就够了。
 */
function openPreview() {
  if (!props.previewSrc) return
  openLightbox([
    {
      src: props.previewSrc,
      name: props.file?.displayName || props.file?.filename || '图片预览'
    }
  ])
}

const SOURCE_MAP: Record<string, string> = {
  upload: '上传',
  paste: '粘贴',
  tool_capture: '工具产出',
  tool: '工具产出'
}

const displayName = ref('')

/** 打开时回填显示名（没有 displayName 时先显示原始文件名，保存即等于不改） */
watch(
  () => [props.open, props.file] as const,
  () => {
    if (!props.open) return
    displayName.value = props.file?.displayName || props.file?.filename || ''
  },
  { immediate: true }
)

const isImage = computed(() => (props.file?.mimeType || '').startsWith('image/'))
const storageText = computed(() => (props.file?.storageType === 'cloud' ? '云端' : '本地'))

const statusText = computed(() => {
  switch (props.file?.status) {
    case 'deleted': return '已删除'
    case 'orphan': return '孤儿（可清理）'
    default: return '可用'
  }
})

const sourceText = computed(() => SOURCE_MAP[props.file?.source || ''] || '未知来源')

const refText = computed(() => {
  const list = props.file?.references || []
  if (!list.length) return '未被引用'
  const conversations = new Set(list.map(r => String(r.conversationId ?? '')))
  return `${list.length} 处（${conversations.size} 个会话）`
})

const sizeText = computed(() => {
  const bytes = Number(props.file?.size) || 0
  if (bytes <= 0) return '0 B'
  const units = ['B', 'KB', 'MB', 'GB']
  let i = 0
  let n = bytes
  while (n >= 1024 && i < units.length - 1) {
    n /= 1024
    i++
  }
  const human = `${i === 0 ? n : n.toFixed(1)} ${units[i]}`
  return `${human}（${bytes.toLocaleString('en-US')} 字节）`
})

function close() {
  emit('update:open', false)
}

function onSave() {
  if (props.file?.id == null) return
  // 空字符串 = 恢复显示原始文件名
  emit('save', { id: props.file.id, displayName: displayName.value.trim() })
}

function onDelete() {
  if (props.file?.id == null) return
  emit('delete', props.file.id)
  close()
}
</script>
