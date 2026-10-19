<!--
  工作区标签栏（P4 · F02）
  ----------------------------------------------------------------------------
  一行标签 + 右侧两个按钮（新建 / 关闭本栏全部）。

  交互：
    · 单击切换、中键关闭、关闭按钮关闭（dirty 时的二次确认由父级负责，
      标签栏只管发事件 —— 它不知道「有未保存」该不该拦，那是工作区的策略）；
    · 拖拽排序：同栏拖动走 reorder；拖到另一栏的标签栏走 move-pane；
    · Ctrl+W / Ctrl+Tab 由 WorkspacePanel 统一注册（跨栏切换需要知道两栏状态）。

  为什么不用 v-show 控制选中态：`.cc-wtab.is-active` 是样式表里的类，
  v-show 只会改内联 display，覆盖不了类规则（本项目反复踩过的坑）。
-->
<template>
  <div class="cc-wtabs">
    <div class="cc-wtabs__list">
      <div
        v-for="(tab, index) in tabs"
        :key="tab.id"
        class="cc-wtab"
        :class="{
          'is-active': tab.id === activeId,
          'is-dirty': tab.dirty,
          'is-drop-target': dropIndex === index
        }"
        :title="tab.dirty ? `${tab.title}（有未保存的修改）` : tab.title"
        draggable="true"
        @click="$emit('activate', tab.id)"
        @auxclick="onAuxClick($event, tab)"
        @dragstart="onDragStart($event, tab)"
        @dragover.prevent="onDragOver(index)"
        @dragleave="onDragLeave(index)"
        @drop.stop.prevent="onDrop(index)"
        @dragend="onDragEnd"
      >
        <svg class="cc-i cc-i--sm cc-wtab__icon"><use :href="'#' + KIND_ICON[tab.kind]" /></svg>
        <span class="cc-wtab__title">{{ tab.title }}</span>
        <span v-if="tab.dirty" class="cc-wtab__dot" />
        <button class="cc-wtab__close" title="关闭标签" @click.stop="$emit('close', tab.id)">
          <svg class="cc-i cc-i--sm" style="transform: rotate(45deg)"><use href="#i-plus" /></svg>
        </button>
      </div>
      <span v-if="!tabs.length" class="cc-wtabs__empty">没有打开的标签</span>
    </div>

    <div class="cc-wtabs__ops">
      <button class="cc-wtabs__op" title="新建标签" @click="$emit('add')">
        <svg class="cc-i cc-i--sm"><use href="#i-plus" /></svg>
      </button>
      <button v-if="tabs.length > 1" class="cc-wtabs__op" title="关闭本栏全部标签" @click="$emit('close-pane')">
        <svg class="cc-i cc-i--sm" style="transform: rotate(45deg)"><use href="#i-plus" /></svg>
      </button>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import { KIND_ICON, type PaneId, type WorkspaceTab } from '../store/workspace'

const props = defineProps<{
  /** 本栏标签（顺序即显示顺序） */
  tabs: WorkspaceTab[]
  /** 本栏激活的标签 id */
  activeId: string
  /** 本栏标识，拖拽时用于判断是否跨栏 */
  pane: PaneId
}>()

const emit = defineEmits<{
  (e: 'activate', id: string): void
  (e: 'close', id: string): void
  (e: 'add'): void
  (e: 'close-pane'): void
  /** 同栏内排序（toIndex 为本栏内的目标下标） */
  (e: 'reorder', payload: { id: string; toIndex: number }): void
  /** 拖到另一栏 */
  (e: 'move-pane', payload: { id: string; pane: PaneId }): void
}>()

/** 拖动中的标签（dragover 时才拿得到 dataTransfer，故用本地变量记录来源栏） */
let dragging: { id: string; pane: PaneId } | null = null
const dropIndex = ref(-1)

function onAuxClick(event: MouseEvent, tab: WorkspaceTab) {
  if (event.button === 1) {
    event.preventDefault()
    emit('close', tab.id)
  }
}

function onDragStart(event: DragEvent, tab: WorkspaceTab) {
  dragging = { id: tab.id, pane: props.pane }
  // Firefox 必须设置 dataTransfer 才会真正开始拖拽
  event.dataTransfer?.setData('text/plain', tab.id)
  if (event.dataTransfer) event.dataTransfer.effectAllowed = 'move'
}

function onDragOver(index: number) {
  dropIndex.value = index
}

function onDragLeave(index: number) {
  // 只有离开当前高亮项才清除，避免子元素冒泡导致的闪烁
  if (dropIndex.value === index) dropIndex.value = -1
}

function onDrop(index: number) {
  dropIndex.value = -1
  if (!dragging) return
  const { id, pane } = dragging
  dragging = null
  if (pane !== props.pane) {
    emit('move-pane', { id, pane: props.pane })
    return
  }
  emit('reorder', { id, toIndex: index })
}

function onDragEnd() {
  dropIndex.value = -1
  dragging = null
}
</script>
