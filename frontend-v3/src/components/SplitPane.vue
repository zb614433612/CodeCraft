<!--
  两栏分屏容器（P4 · F03）
  ----------------------------------------------------------------------------
  用途：把工作区拆成两栏（左右 / 上下），两栏各自独立滚动、各自有标签栏。

  设计要点：
    1) 用 CSS Grid 的 `fr` 表达比例而不是固定像素：窗口 resize 时两栏自动等比缩放，
       不需要监听 resize 重算（只有「最小宽保护」需要测量）；
    2) 拖拽用 Pointer Events 而不是 mousemove：触屏 / 触控板同样可用，
       且窗口外松开也能正确收尾（监听挂在 window 上）；
    3) 比例有上下限（0.2~0.8）+ 像素最小宽（默认 280px）：
       拖到极端值会让其中一栏完全没法用，宁可不让拖到底；
    4) 容器太窄时（两栏各 280px 放不下）退化为固定 50%，避免比例来回弹。
-->
<template>
  <div ref="rootEl" class="cc-split" :class="`is-${direction}`" :style="gridStyle">
    <div class="cc-split__pane">
      <slot name="first" />
    </div>
    <div
      class="cc-split__bar"
      :class="{ 'is-dragging': dragging }"
      :title="direction === 'h' ? '拖动调整左右比例，双击复位' : '拖动调整上下比例，双击复位'"
      @pointerdown="onPointerDown"
      @dblclick="resetRatio"
    />
    <div class="cc-split__pane">
      <slot name="second" />
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'

const props = withDefaults(
  defineProps<{
    /** h = 左右分屏；v = 上下分屏 */
    direction?: 'h' | 'v'
    /** 第一栏占比（0.2 ~ 0.8） */
    ratio?: number
    /** 每栏最小宽/高（px） */
    minSize?: number
  }>(),
  { direction: 'h', ratio: 0.5, minSize: 280 }
)

const emit = defineEmits<{ (e: 'update:ratio', value: number): void }>()

/** 比例硬上下限：低于 0.2 / 高于 0.8 时相邻一栏只剩一条缝 */
const MIN_RATIO = 0.2
const MAX_RATIO = 0.8

const rootEl = ref<HTMLElement | null>(null)
const dragging = ref(false)
/** 容器主轴尺寸（宽或高），用于最小宽保护 */
const size = ref(0)

let observer: ResizeObserver | null = null

const gridStyle = computed(() => {
  const r = props.ratio
  return props.direction === 'h'
    ? { gridTemplateColumns: `minmax(0, ${r}fr) 6px minmax(0, ${1 - r}fr)` }
    : { gridTemplateRows: `minmax(0, ${r}fr) 6px minmax(0, ${1 - r}fr)` }
})

function clamp(value: number, min: number, max: number): number {
  return Math.min(max, Math.max(min, value))
}

/**
 * 当前允许的比例区间。
 * 容器放不下两倍 minSize 时退化为 0.5，否则按像素换算出更紧的上下限。
 */
function bounds(): [number, number] {
  const total = size.value
  if (!total) return [MIN_RATIO, MAX_RATIO]
  const px = props.minSize / total
  const min = Math.max(MIN_RATIO, Math.min(px, 0.5))
  const max = Math.min(MAX_RATIO, Math.max(1 - px, 0.5))
  return min <= max ? [min, max] : [0.5, 0.5]
}

function measure() {
  const el = rootEl.value
  if (!el) return
  size.value = props.direction === 'h' ? el.clientWidth : el.clientHeight
}

/** 容器变窄后把越界的比例收敛回来（否则窄窗口下某一栏会被压没） */
function enforceBounds() {
  const [min, max] = bounds()
  const next = clamp(props.ratio, min, max)
  if (Math.abs(next - props.ratio) > 0.001) emit('update:ratio', next)
}

function onPointerDown(event: PointerEvent) {
  if (event.button !== 0) return
  event.preventDefault()
  const el = rootEl.value
  if (!el) return
  const rect = el.getBoundingClientRect()
  dragging.value = true
  const bodyClass = props.direction === 'h' ? 'cc-split-drag-h' : 'cc-split-drag-v'
  document.body.classList.add(bodyClass)

  const onMove = (ev: PointerEvent) => {
    const total = props.direction === 'h' ? rect.width : rect.height
    if (!total) return
    const pos = props.direction === 'h' ? ev.clientX - rect.left : ev.clientY - rect.top
    const [min, max] = bounds()
    emit('update:ratio', clamp(pos / total, min, max))
  }
  const onUp = () => {
    dragging.value = false
    document.body.classList.remove(bodyClass)
    window.removeEventListener('pointermove', onMove)
    window.removeEventListener('pointerup', onUp)
    window.removeEventListener('pointercancel', onUp)
  }
  window.addEventListener('pointermove', onMove)
  window.addEventListener('pointerup', onUp)
  window.addEventListener('pointercancel', onUp)
}

function resetRatio() {
  emit('update:ratio', 0.5)
}

onMounted(() => {
  measure()
  if (typeof ResizeObserver !== 'undefined' && rootEl.value) {
    observer = new ResizeObserver(() => {
      measure()
      enforceBounds()
    })
    observer.observe(rootEl.value)
  }
  enforceBounds()
})

onBeforeUnmount(() => {
  observer?.disconnect()
  observer = null
  document.body.classList.remove('cc-split-drag-h', 'cc-split-drag-v')
})
</script>
