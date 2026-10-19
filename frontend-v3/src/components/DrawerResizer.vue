<!--
  抽屉左边界拖拽条（F16 · 拖拽调整面板宽度）
  ----------------------------------------------------------------------------
  为什么做成「放进抽屉里的一行组件」而不是让每个抽屉都写 composable：

    17 个抽屉（聊天工具抽屉 + 16 个管理页编辑抽屉）的模板结构完全一致，
    都用 `<aside class="cc-drawer" style="--drawer-w: 480px">` 声明默认宽度。
    如果每个组件都要 import composable、声明 6 个解构变量、把 :style 接上，
    等于把同一段样板抄 17 遍 —— 新增抽屉时必然漏掉，宽度语义也会逐渐分叉。

  本组件把这件事收进抽屉内部，接入方只需一行：

    <aside class="cc-drawer" :class="{ 'is-open': open }" style="--drawer-w: 480px">
      <DrawerResizer storage-key="agent-edit" />
      ...
    </aside>

  它自己负责三件事：

    1) 挂载后从父元素反读现有 `--drawer-w` 作为默认宽度 —— 不用在样式与脚本两处
       重复写同一个数字（写两遍必然漂移）；
    2) 有持久化记录时覆盖成用户上次拖出来的宽度（localStorage，按 storage-key 分抽屉隔离）；
    3) 拖拽 / 双击复位时**直接改父元素的 CSS 变量** —— 父组件无需任何 JS 改动，
       也不用改 DOM 结构（`.cc-drawer` 的定位、滑入动画、遮罩全部照旧）。

  交互细节：
    - 命中区 8px、跨在描边上，视觉竖线只在 hover / 拖拽时出现（见 app.css 第 14 节）；
    - 双击复位到默认宽度；
    - 窄屏（≤900px）不响应拖拽，宽度由 CSS `min(var(--drawer-w), 100vw)` 兜底；
    - 拖拽期间给 body 挂 `cc-resizing`：全局光标变 col-resize 且禁止误选文本。
-->
<template>
  <div
    ref="rootRef"
    class="cc-drawer__resizer"
    :class="{ 'is-dragging': resizing }"
    title="拖动调整面板宽度，双击复位"
    @pointerdown="onPointerDown"
    @pointermove="onPointerMove"
    @pointerup="onPointerUp"
    @pointercancel="onPointerUp"
    @dblclick="resetWidth"
  />
</template>

<script setup lang="ts">
import { onMounted, ref, watch } from 'vue'
import { useDrawerResize } from '../utils/drawer-resize'

const props = withDefaults(
  defineProps<{
    /** 持久化键后缀：同宽度的不同抽屉互不干扰（如 agent-edit / provider-edit） */
    storageKey: string
    /** 最小宽度（px） */
    min?: number
    /** 最大宽度（px），实际仍受视口约束（最多 72% 视口、主区至少留 320px） */
    max?: number
  }>(),
  { min: 320, max: 1200 }
)

const rootRef = ref<HTMLDivElement | null>(null)
/** 真正承载宽度的 `<aside class="cc-drawer">` */
let drawerEl: HTMLElement | null = null

const { width, resizing, onPointerDown, onPointerMove, onPointerUp, resetWidth, setDefaultWidth } =
  useDrawerResize(props.storageKey, { min: props.min, max: props.max })

/** 把当前宽度写回父元素的 CSS 变量（这是本组件唯一对外副作用） */
function applyWidth() {
  drawerEl?.style.setProperty('--drawer-w', `${width.value}px`)
}

// flush: 'sync' —— 拖拽是高频 pointermove，异步 flush 会让面板明显滞后于鼠标
watch(width, applyWidth, { flush: 'sync' })

onMounted(() => {
  const parent = rootRef.value?.parentElement
  if (!parent) return
  drawerEl = parent
  // 反读抽屉自身声明的默认宽度（各抽屉写死 400 / 480 / 520 / 560 / 720 不等）
  const raw = getComputedStyle(parent).getPropertyValue('--drawer-w').trim()
  const parsed = Number.parseFloat(raw)
  if (Number.isFinite(parsed) && parsed > 0) setDefaultWidth(parsed)
  // 有持久化记录时立刻覆盖成用户上次的宽度；没有则等于默认值（无视觉变化）
  applyWidth()
})
</script>
