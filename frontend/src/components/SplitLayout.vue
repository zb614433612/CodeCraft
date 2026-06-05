<template>
  <div
    class="split-container"
    :class="{
      'split-h': direction === 'h' && hasSplit,
      'split-v': direction === 'v' && hasSplit,
      'split-active': hasSplit
    }"
  >
    <!-- ===== 主面板 ===== -->
    <div
      class="split-pane pane-primary"
      :style="paneStyle"
    >
      <slot name="primary" />
    </div>

    <!-- ===== 分隔条 ===== -->
    <div
      v-if="hasSplit"
      ref="dividerRef"
      class="split-divider"
      :class="{ 'divider-h': direction === 'h', 'divider-v': direction === 'v' }"
      @mousedown.prevent="startDividerResize"
    >
      <div class="divider-grip">
        <span v-if="direction === 'h'">⋮</span>
        <span v-else>⋯</span>
      </div>
    </div>

    <!-- ===== 副面板 ===== -->
    <div
      v-if="hasSplit"
      class="split-pane pane-secondary"
      :style="secondaryPaneStyle"
    >
      <slot name="secondary" />
    </div>

    <!-- ===== 拖拽分屏预览遮罩（覆盖整个 split-container） ===== -->
    <div v-if="dragState.dragging && !hasSplit" class="drop-full-overlay">
      <div class="drop-full-bg"></div>
      <!-- 四个方向热区 -->
      <div
        :class="['drop-zone', 'drop-zone-left', { active: dragState.dropZone === 'left' }]"
      >
        <span v-if="dragState.dropZone === 'left'" class="drop-zone-label">← 左侧分屏</span>
      </div>
      <div
        :class="['drop-zone', 'drop-zone-right', { active: dragState.dropZone === 'right' }]"
      >
        <span v-if="dragState.dropZone === 'right'" class="drop-zone-label">→ 右侧分屏</span>
      </div>
      <div
        :class="['drop-zone', 'drop-zone-top', { active: dragState.dropZone === 'top' }]"
      >
        <span v-if="dragState.dropZone === 'top'" class="drop-zone-label">↑ 上方分屏</span>
      </div>
      <div
        :class="['drop-zone', 'drop-zone-bottom', { active: dragState.dropZone === 'bottom' }]"
      >
        <span v-if="dragState.dropZone === 'bottom'" class="drop-zone-label">↓ 下方分屏</span>
      </div>
      <!-- 中央文字提示 -->
      <div v-if="!dragState.dropZone" class="drop-center-hint">
        拖拽到边缘进行分屏
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onBeforeUnmount } from 'vue'

export interface SplitDragInfo {
  dragging: boolean
  tabId: string | null
  startX: number
  startY: number
  currentX: number
  currentY: number
  dropZone: string | null
}

const props = defineProps<{
  direction: 'h' | 'v'
  hasSplit: boolean
  splitRatio: number
  dragState: SplitDragInfo
}>()

const emit = defineEmits<{
  ratioChange: [ratio: number]
}>()

// ─── 面板样式 ───
const paneStyle = computed(() => {
  if (!props.hasSplit) return {}
  const pct = (props.splitRatio * 100).toFixed(1) + '%'
  if (props.direction === 'h') {
    return { width: pct, minWidth: '200px' }
  } else {
    return { height: pct, minHeight: '200px' }
  }
})
const secondaryPaneStyle = computed(() => {
  if (!props.hasSplit) return {}
  if (props.direction === 'h') {
    return { flex: 1, minWidth: '200px' }
  } else {
    return { flex: 1, minHeight: '200px' }
  }
})

// ─── 分隔条拖拽调整大小 ───
const dividerRef = ref<HTMLElement | null>(null)
let resizeStart = 0
let resizeStartRatio = 0.5

const startDividerResize = (e: MouseEvent) => {
  resizeStart = props.direction === 'h' ? e.clientX : e.clientY
  resizeStartRatio = props.splitRatio
  document.addEventListener('mousemove', onDividerResize)
  document.addEventListener('mouseup', stopDividerResize)
}

const onDividerResize = (e: MouseEvent) => {
  const container = (e.target as HTMLElement)?.closest('.split-container')
  if (!container) return
  const rect = container.getBoundingClientRect()
  const totalSize = props.direction === 'h' ? rect.width : rect.height
  if (totalSize <= 0) return
  const currentPos = props.direction === 'h' ? e.clientX : e.clientY
  const delta = currentPos - resizeStart
  const deltaRatio = delta / totalSize
  const newRatio = Math.max(0.25, Math.min(0.75, resizeStartRatio + deltaRatio))
  emit('ratioChange', newRatio)
}

const stopDividerResize = () => {
  document.removeEventListener('mousemove', onDividerResize)
  document.removeEventListener('mouseup', stopDividerResize)
}

onBeforeUnmount(() => {
  document.removeEventListener('mousemove', onDividerResize)
  document.removeEventListener('mouseup', stopDividerResize)
})
</script>

<style scoped>
/* ===== 分屏容器 ===== */
.split-container {
  display: flex;
  flex: 1;
  min-height: 0;
  overflow: hidden;
  position: relative;
  background: var(--bg-root, #f5f3fa);
}
.split-container.split-v {
  flex-direction: column;
}
.split-container.split-h {
  flex-direction: row;
}

/* ===== 单个面板 ===== */
.split-pane {
  display: flex;
  flex-direction: column;
  flex: 1;
  min-height: 0;
  min-width: 0;
  overflow: hidden;
  position: relative;
  transition: width 0.05s, height 0.05s;
}
.split-active .pane-primary {
  flex: none;
}
.pane-secondary {
  flex: 1;
}

/* ===== 分隔条 ===== */
.split-divider {
  flex-shrink: 0;
  background: var(--border, #e8e5f0);
  display: flex;
  align-items: center;
  justify-content: center;
  transition: background 0.15s;
  z-index: 50;
  user-select: none;
}
.split-divider:hover {
  background: var(--accent, #8b5cf6);
}
.divider-h {
  width: 5px;
  cursor: col-resize;
}
.divider-v {
  height: 5px;
  cursor: row-resize;
}
.divider-grip {
  color: transparent;
  font-size: 12px;
  transition: color 0.15s;
}
.split-divider:hover .divider-grip {
  color: #fff;
}

/* ===== 拖拽分屏全屏遮罩 ===== */
.drop-full-overlay {
  position: absolute;
  inset: 0;
  z-index: 200;
  pointer-events: none;
  display: grid;
  grid-template-columns: 1fr 1fr 1fr;
  grid-template-rows: 1fr 1fr 1fr;
}
.drop-full-bg {
  position: absolute;
  inset: 0;
  background: rgba(0, 0, 0, 0.08);
  pointer-events: none;
}
.drop-zone {
  display: flex;
  align-items: center;
  justify-content: center;
  transition: background 0.15s;
}
.drop-zone.active {
  background: rgba(139, 92, 246, 0.25);
  backdrop-filter: blur(2px);
}
.drop-zone-left {
  grid-column: 1;
  grid-row: 1 / 4;
  border-radius: 6px 0 0 6px;
}
.drop-zone-right {
  grid-column: 3;
  grid-row: 1 / 4;
  border-radius: 0 6px 6px 0;
}
.drop-zone-top {
  grid-column: 2;
  grid-row: 1;
  border-radius: 6px 6px 0 0;
}
.drop-zone-bottom {
  grid-column: 2;
  grid-row: 3;
  border-radius: 0 0 6px 6px;
}

.drop-zone-label {
  padding: 8px 16px;
  background: rgba(139, 92, 246, 0.9);
  color: #fff;
  border-radius: 8px;
  font-size: 13px;
  font-weight: 600;
  backdrop-filter: blur(4px);
  box-shadow: 0 4px 20px rgba(139, 92, 246, 0.3);
  animation: dropLabelIn 0.15s ease-out;
}
@keyframes dropLabelIn {
  from { transform: scale(0.8); opacity: 0; }
  to { transform: scale(1); opacity: 1; }
}

.drop-center-hint {
  position: absolute;
  top: 50%;
  left: 50%;
  transform: translate(-50%, -50%);
  color: rgba(255, 255, 255, 0.5);
  font-size: 14px;
  font-weight: 500;
  pointer-events: none;
}

/* ===== 暗色模式适配 ===== */
[data-theme="dark"] .split-container {
  background: #0e0d16;
}
[data-theme="dark"] .split-divider {
  background: #2a2838;
}
[data-theme="dark"] .split-divider:hover {
  background: var(--accent, #8b5cf6);
}
[data-theme="dark"] .drop-full-bg {
  background: rgba(0, 0, 0, 0.2);
}
[data-theme="dark"] .drop-full-overlay {
  background: none;
}
[data-theme="dark"] .drop-center-hint {
  color: rgba(255, 255, 255, 0.35);
}
</style>
