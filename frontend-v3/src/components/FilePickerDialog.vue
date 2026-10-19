<!--
  历史文件选择器（v3）
  ----------------------------------------------------------------------------
  给聊天输入区补「引用历史上传文件」能力：从文件库里挑已有文件直接加入附件区，
  随消息以 `fileAssetIds` 发送（同一 file_id 复用，不重复上传、不额外占云端空间）。

  数据来源（全部真实接口，不自造数据）：
    GET /api/files?limit=200&order=desc   → 文件资产列表（图片 + 文档）
    GET /api/files/{id}/preview           → 图片缩略图（走 blob URL，带鉴权头）

  交互：
    · 打开即拉最新列表；图片显示缩略图，文档显示格式图标；
    · 关键字本地过滤（会话级列表量级有限，不打后端）；
    · 多选，已在附件区的文件置灰并标注「已在附件」，不可重复添加；
    · ESC / 点遮罩 / 关闭按钮 均关闭并清空选择。

  数据接缝：
    v-model:open  -> 开关
    attachedIds   -> 已在附件区的文件 id（用于去重置灰）
    emit('pick')  -> 确认选择，参数为选中的 FileAssetData[]
-->
<template>
  <Teleport to="body">
    <!-- 显隐用 .is-open 类（不用 v-if / v-show）：静态预览浮层 HTML 常驻默认 display:none，
         Vue 侧靠同一个类点亮，两边共用一条 CSS 规则（见踩坑经验 216） -->
    <div
      class="cc-filepick"
      :class="{ 'is-open': open }"
      :aria-hidden="!open"
      role="dialog"
      aria-modal="true"
      aria-labelledby="filePickTitle"
    >
      <div class="cc-filepick__mask" @click="close" />

      <div class="cc-filepick__panel">
        <div class="cc-filepick__head">
          <svg class="cc-i cc-i--sm" style="color: var(--accent)"><use href="#i-folder" /></svg>
          <h2 id="filePickTitle" class="cc-filepick__title">选择历史文件</h2>
          <button class="cc-icon-btn cc-icon-btn--sm" title="关闭" @click="close">
            <svg class="cc-i cc-i--sm"><use href="#i-close" /></svg>
          </button>
        </div>

        <div class="cc-filepick__bar">
          <div class="cc-search cc-filepick__search">
            <svg class="cc-i cc-i--sm"><use href="#i-search" /></svg>
            <input v-model="keyword" type="text" placeholder="搜索文件名" @keydown.esc="keyword = ''" />
            <button v-if="keyword" class="cc-filepick__clear" title="清空" @click="keyword = ''">×</button>
          </div>
          <span class="cc-filepick__count">已选 {{ selectedIds.size }} 个</span>
        </div>

        <div class="cc-filepick__body">
          <div v-if="loading" class="cc-filepick__tip">正在加载文件库…</div>
          <div v-else-if="error" class="cc-filepick__tip is-error">{{ error }}</div>
          <div v-else-if="!filtered.length" class="cc-filepick__tip">
            {{ keyword ? `没有匹配「${keyword}」的文件` : '文件库还是空的，可先用「上传新文件」传一个' }}
          </div>
          <div v-else class="cc-filepick__grid">
            <button
              v-for="f in filtered"
              :key="f.id"
              type="button"
              class="cc-filepick__item"
              :class="{ 'is-selected': selectedIds.has(f.id), 'is-attached': isAttached(f.id) }"
              :title="fileAssetDisplayName(f)"
              @click="toggle(f)"
            >
              <span class="cc-filepick__thumb">
                <img v-if="isImageMime(f.mimeType) && previews[f.id]" :src="previews[f.id]" :alt="fileAssetDisplayName(f)" />
                <span v-else class="cc-filepick__thumb-text">
                  {{ isImageMime(f.mimeType) ? '🖼️' : docTypeIcon(docTypeOf(f.filename, f.mimeType)) }}
                </span>
                <!-- 看图入口：外层是「选中文件」按钮，这里只能放带 stop 的 span（button 不能嵌套 button） -->
                <span
                  v-if="isImageMime(f.mimeType) && previews[f.id]"
                  class="cc-filepick__zoom"
                  title="查看大图"
                  @click.stop="zoomImage(f)"
                >
                  <svg class="cc-i cc-i--sm"><use href="#i-search" /></svg>
                </span>
                <span v-if="selectedIds.has(f.id)" class="cc-filepick__check">
                  <svg class="cc-i cc-i--sm"><use href="#i-check" /></svg>
                </span>
                <span v-if="isAttached(f.id)" class="cc-filepick__badge">已在附件</span>
              </span>
              <span class="cc-filepick__name">{{ fileAssetDisplayName(f) }}</span>
              <span class="cc-filepick__size">{{ formatAssetSize(f.size) }}</span>
            </button>
          </div>
        </div>

        <div class="cc-filepick__foot">
          <span class="cc-filepick__hint">引用已有文件不会重新上传，发送时按 fileAssetId 复用</span>
          <button class="cc-btn cc-btn--ghost" @click="close">取消</button>
          <button class="cc-btn cc-btn--primary" :disabled="!selectedIds.size" @click="confirm">
            添加到附件{{ selectedIds.size ? `（${selectedIds.size}）` : '' }}
          </button>
        </div>
      </div>
    </div>
  </Teleport>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import {
  listFileAssets,
  getFilePreviewBlobUrl,
  fileAssetDisplayName,
  formatAssetSize,
  isImageMime,
  docTypeOf,
  docTypeIcon,
  type FileAssetData
} from '../api/file-asset'
import { openLightbox } from '../utils/lightbox'

const props = withDefaults(
  defineProps<{
    open?: boolean
    /** 已在附件区的文件资产 id（去重置灰；不传则不禁用） */
    attachedIds?: number[]
    /** 单次最多可选数量，防止一次性挂几十个文件把上下文撑爆 */
    maxCount?: number
  }>(),
  { open: false, attachedIds: () => [], maxCount: 20 }
)

const emit = defineEmits<{
  (e: 'update:open', v: boolean): void
  (e: 'pick', items: FileAssetData[]): void
}>()

const keyword = ref('')
const loading = ref(false)
const error = ref('')
const list = ref<FileAssetData[]>([])
const selectedIds = ref<Set<number>>(new Set())
/** 图片缩略图（id → blob URL；file-asset 模块自带缓存，无需 revoke） */
const previews = ref<Record<number, string>>({})

const filtered = computed(() => {
  const kw = keyword.value.trim().toLowerCase()
  if (!kw) return list.value
  return list.value.filter(f => fileAssetDisplayName(f).toLowerCase().includes(kw))
})

function isAttached(id: number): boolean {
  return props.attachedIds.includes(id)
}

/**
 * 缩略图 → 全站灯箱（F14）。
 *
 * 只收集**当前筛选结果里有缩略图**的图片：左右切换不会跳到被搜索过滤掉的、
 * 或者预览还没加载出来的文件上。索引对齐用 filtered 的顺序。
 */
function zoomImage(f: FileAssetData) {
  const images = filtered.value
    .filter(x => isImageMime(x.mimeType) && previews.value[x.id])
    .map(x => ({ src: previews.value[x.id], name: fileAssetDisplayName(x) }))
  const start = filtered.value
    .filter(x => isImageMime(x.mimeType) && previews.value[x.id])
    .findIndex(x => x.id === f.id)
  openLightbox(images, start < 0 ? 0 : start)
}

function close() {
  emit('update:open', false)
}

function toggle(f: FileAssetData) {
  if (isAttached(f.id)) return
  const next = new Set(selectedIds.value)
  if (next.has(f.id)) next.delete(f.id)
  else {
    if (next.size >= props.maxCount) return
    next.add(f.id)
  }
  selectedIds.value = next
}

function confirm() {
  const picked = list.value.filter(f => selectedIds.value.has(f.id) && !isAttached(f.id))
  if (!picked.length) return
  emit('pick', picked)
  close()
}

async function load() {
  loading.value = true
  error.value = ''
  try {
    const res = await listFileAssets({ limit: 200, order: 'desc' })
    if (res.code !== 200) throw new Error(res.message || '加载文件库失败')
    list.value = res.data || []
    // 图片缩略图异步补齐（失败保留占位图标，不影响选择）
    for (const f of list.value) {
      if (!isImageMime(f.mimeType) || previews.value[f.id]) continue
      getFilePreviewBlobUrl(f.id)
        .then(url => {
          previews.value = { ...previews.value, [f.id]: url }
        })
        .catch(() => {})
    }
  } catch (err) {
    error.value = err instanceof Error ? err.message : '加载文件库失败'
  } finally {
    loading.value = false
  }
}

watch(
  () => props.open,
  v => {
    if (!v) {
      selectedIds.value = new Set()
      keyword.value = ''
      return
    }
    void load()
  }
)

function onKeydown(ev: KeyboardEvent) {
  if (ev.key === 'Escape' && props.open) {
    ev.stopPropagation()
    close()
  }
}

onMounted(() => document.addEventListener('keydown', onKeydown))
onBeforeUnmount(() => document.removeEventListener('keydown', onKeydown))

defineExpose({ reload: load })
</script>
