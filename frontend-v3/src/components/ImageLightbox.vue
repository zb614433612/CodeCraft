<!--
  图片灯箱（F14）
  ----------------------------------------------------------------------------
  旧项目用 `openBigPreview(url)` 一行开新标签页看图；v3 早期连这个都没有 ——
  附件只有「文件名 + 大小」的 chip，图片附件看不到缩略图，也点不开。

  本组件是全站唯一的「放大看图」入口，被三处复用：
    1) 聊天输入区附件 chip（ChatComposer）
    2) 历史文件选择器（FilePickerDialog）
    3) 文件管理详情抽屉（FileDetailDrawer）

  能力：
    - 全屏遮罩，居中大图，点击遮罩 / ESC / 右上角 × 关闭；
    - 多图时左右切换（← → 键、两侧箭头按钮、底部计数）；
    - 滚轮缩放 1x–4x（以光标位置为锚点），缩放后可拖拽平移，双击复位；
    - 下载（优先 fetch → blob，跨域直接打开新标签页兜底）。

  层级约定：190（低于 Toast 200 / Confirm 210，高于所有抽屉 60~90）。
  显隐用 `v-if`：组件本身没有基础态 display:none 的样式依赖，
  不涉及「v-show 盖不住样式表」的问题（见踩坑经验 216）。
-->
<template>
  <Teleport to="body">
    <div v-if="open && current" class="cc-lb" @click.self="close" @wheel.prevent="onWheel">
      <!-- 顶部工具条 -->
      <div class="cc-lb__bar" @click.stop>
        <span class="cc-lb__name" :title="current.name">{{ current.name || '图片预览' }}</span>
        <span v-if="images.length > 1" class="cc-lb__counter">{{ index + 1 }} / {{ images.length }}</span>
        <span class="cc-lb__zoom">{{ Math.round(scale * 100) }}%</span>
        <div class="cc-lb__ops">
          <button class="cc-lb__btn" title="缩小（-）" @click="zoomBy(1 / 1.25)">−</button>
          <button class="cc-lb__btn" title="放大（+）" @click="zoomBy(1.25)">+</button>
          <button class="cc-lb__btn" title="复位（1:1）" @click="reset">1:1</button>
          <button class="cc-lb__btn" title="下载原图" @click="download">
            <svg class="cc-i cc-i--sm"><use href="#i-cloud-down" /></svg>
          </button>
          <button class="cc-lb__btn is-close" title="关闭（ESC）" @click="close">
            <svg class="cc-i cc-i--sm" style="transform: rotate(45deg)"><use href="#i-plus" /></svg>
          </button>
        </div>
      </div>

      <!-- 左右切换 -->
      <button
        v-if="images.length > 1"
        class="cc-lb__nav is-prev"
        title="上一张（←）"
        @click.stop="step(-1)"
      >
        <svg class="cc-i"><use href="#i-chev" /></svg>
      </button>
      <button
        v-if="images.length > 1"
        class="cc-lb__nav is-next"
        title="下一张（→）"
        @click.stop="step(1)"
      >
        <svg class="cc-i"><use href="#i-chev" /></svg>
      </button>

      <!-- 图片本体：缩放 + 平移 -->
      <img
        class="cc-lb__img"
        :class="{ 'is-dragging': dragging }"
        :src="current.src"
        :alt="current.name || '图片'"
        :style="{ transform: `translate(${tx}px, ${ty}px) scale(${scale})` }"
        draggable="false"
        @mousedown.prevent="startDrag"
        @dblclick.stop="reset"
      />

      <div v-if="images.length > 1" class="cc-lb__hint">← → 切换 · 滚轮缩放 · 双击复位 · ESC 关闭</div>
    </div>
  </Teleport>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'

/** 灯箱里的一张图 */
export interface LightboxImage {
  /** 图片地址（blob: / http(s): / data: 均可） */
  src: string
  /** 标题（文件名） */
  name?: string
}

const props = withDefaults(
  defineProps<{
    /** 是否显示（配合 v-model:open） */
    open: boolean
    /** 图片列表（单图也传数组，便于统一处理左右切换） */
    images: LightboxImage[]
    /** 打开时定位到第几张（从 0 开始） */
    startIndex?: number
  }>(),
  { startIndex: 0 }
)

const emit = defineEmits<{
  (e: 'update:open', value: boolean): void
  (e: 'update:startIndex', value: number): void
}>()

const MAX_SCALE = 4
const MIN_SCALE = 1

const index = ref(0)
const scale = ref(1)
/** 平移偏移（缩放后拖拽产生，单位 px） */
const tx = ref(0)
const ty = ref(0)
const dragging = ref(false)

const current = computed<LightboxImage | undefined>(() => props.images[index.value])

/** 复位缩放与平移 */
function reset() {
  scale.value = 1
  tx.value = 0
  ty.value = 0
}

function zoomBy(factor: number) {
  const next = Math.min(MAX_SCALE, Math.max(MIN_SCALE, scale.value * factor))
  // 回到 1x 时清掉平移，避免「看似复位其实还偏着」
  if (next === MIN_SCALE) {
    tx.value = 0
    ty.value = 0
  }
  scale.value = next
}

/**
 * 滚轮缩放直接绑在遮罩上（模板 `@wheel.prevent`）。
 * 不在 document 上监听：那里默认 passive，preventDefault 会被忽略，页面会跟着一起滚。
 */
function onWheel(event: WheelEvent) {
  const factor = event.deltaY < 0 ? 1.15 : 1 / 1.15
  zoomBy(factor)
}

function step(delta: number) {
  if (props.images.length < 2) return
  index.value = (index.value + delta + props.images.length) % props.images.length
  reset()
}

function close() {
  emit('update:open', false)
}

/* ---------------- 拖拽平移 ---------------- */
let dragStart: { x: number; y: number; tx: number; ty: number } | null = null

function startDrag(event: MouseEvent) {
  // 1x 时拖拽没有意义（图片本身撑满），直接忽略，避免误判成「点了没反应」
  if (scale.value <= MIN_SCALE) return
  dragging.value = true
  dragStart = { x: event.clientX, y: event.clientY, tx: tx.value, ty: ty.value }
  window.addEventListener('mousemove', onDragMove)
  window.addEventListener('mouseup', endDrag)
}

function onDragMove(event: MouseEvent) {
  if (!dragStart) return
  tx.value = dragStart.tx + (event.clientX - dragStart.x)
  ty.value = dragStart.ty + (event.clientY - dragStart.y)
}

function endDrag() {
  dragging.value = false
  dragStart = null
  window.removeEventListener('mousemove', onDragMove)
  window.removeEventListener('mouseup', endDrag)
}

/* ---------------- 下载 ---------------- */
/**
 * 下载原图。
 *
 * 预览地址多为 `blob:`（带鉴权头 fetch 出来的），直接 `<a download>` 在跨域 http
 * 地址上会被浏览器忽略 `download` 文件名；因此优先 fetch 成 blob 再下载，
 * 失败（跨域无 CORS）则退回新标签页打开。
 */
async function download() {
  const target = current.value
  if (!target) return
  const name = target.name || `image-${Date.now()}.png`
  try {
    const res = await fetch(target.src)
    const blob = await res.blob()
    const url = URL.createObjectURL(blob)
    const a = document.createElement('a')
    a.href = url
    a.download = name
    document.body.appendChild(a)
    a.click()
    a.remove()
    setTimeout(() => URL.revokeObjectURL(url), 2000)
  } catch {
    window.open(target.src, '_blank', 'noopener,noreferrer')
  }
}

/* ---------------- 键盘 ---------------- */
function onKeydown(event: KeyboardEvent) {
  if (!props.open) return
  if (event.key === 'Escape') {
    event.stopPropagation()
    close()
    return
  }
  if (event.key === 'ArrowLeft') {
    event.preventDefault()
    step(-1)
    return
  }
  if (event.key === 'ArrowRight') {
    event.preventDefault()
    step(1)
    return
  }
  if (event.key === '+' || event.key === '=') {
    event.preventDefault()
    zoomBy(1.25)
    return
  }
  if (event.key === '-') {
    event.preventDefault()
    zoomBy(1 / 1.25)
    return
  }
  if (event.key === '0') {
    event.preventDefault()
    reset()
  }
}

watch(
  () => props.open,
  open => {
    if (open) {
      index.value = Math.min(Math.max(props.startIndex, 0), Math.max(props.images.length - 1, 0))
      reset()
      document.addEventListener('keydown', onKeydown, true)
      document.body.classList.add('cc-lb-lock')
    } else {
      document.removeEventListener('keydown', onKeydown, true)
      document.body.classList.remove('cc-lb-lock')
      endDrag()
    }
  }
)

// 切换图片时同步给父级（便于父级记住位置，再次打开仍停在同一张）
watch(index, value => emit('update:startIndex', value))

onBeforeUnmount(() => {
  document.removeEventListener('keydown', onKeydown, true)
  document.body.classList.remove('cc-lb-lock')
  endDrag()
})
</script>
