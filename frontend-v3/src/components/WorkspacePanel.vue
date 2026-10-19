<!--
  工作区外壳（P4 · F02 / F03 / F04）
  ----------------------------------------------------------------------------
  聊天页右侧的工作台：头部（布局切换 + 收起/切回抽屉）+ 主体（单栏 或 分屏两栏）。

  与旧抽屉的关系（PLAN 里的「抽屉降级为快捷入口」）：
    · 设置项 settings.workspaceMode = true（默认）→ 点「文件 / Git / 终端」打开工作区标签；
    · = false → 保持原来的右侧抽屉（老习惯可随时切回，按钮在头部「切回抽屉」）；
    · 两套实现并存，互不影响：抽屉的 #drawer 槽内容仍在 App.vue 里挂着。

  快捷键：
    Ctrl+Tab / Ctrl+Shift+Tab  在主栏标签间循环（浏览器允许 preventDefault）
    Ctrl+Alt+W                 关闭主栏当前标签
    注：Ctrl+W 被浏览器用于关闭整个标签页，网页里拦不住，故改用 Ctrl+Alt+W。
-->
<template>
  <aside class="cc-ws" :style="{ width: ws.width + 'px' }">
    <!-- 拖拽调宽：鼠标左移 = 变宽（工作区在右侧） -->
    <div
      class="cc-ws__resizer"
      title="拖动调整工作区宽度，双击恢复默认"
      @pointerdown="onResizeStart"
      @dblclick="ws.setWidth(600)"
    />

    <div class="cc-ws__head">
      <span class="cc-ws__title">工作区</span>

      <div class="cc-ws__layout">
        <button
          v-for="item in LAYOUTS"
          :key="item.value"
          class="cc-ws__lay"
          :class="{ 'is-on': ws.layout === item.value }"
          :title="item.title"
          @click="ws.setLayout(item.value)"
        >
          {{ item.label }}
        </button>
      </div>

      <button
        class="cc-icon-btn cc-icon-btn--sm"
        style="margin-left: auto"
        title="切回抽屉模式（设置里可改回）"
        @click="$emit('switch-mode')"
      >
        <svg class="cc-i cc-i--sm"><use href="#i-fold" /></svg>
      </button>
      <button class="cc-icon-btn cc-icon-btn--sm" title="收起工作区" @click="$emit('close')">
        <svg class="cc-i cc-i--sm" style="transform: rotate(45deg)"><use href="#i-plus" /></svg>
      </button>
    </div>

    <div class="cc-ws__body">
      <SplitPane
        v-if="ws.split"
        :direction="ws.layout === 'split-h' ? 'h' : 'v'"
        :ratio="ws.ratio"
        @update:ratio="ws.setRatio"
      >
        <template #first>
          <WorkspacePane pane="main" :root="root" />
        </template>
        <template #second>
          <WorkspacePane pane="side" :root="root" />
        </template>
      </SplitPane>
      <WorkspacePane v-else pane="main" :root="root" />
    </div>
  </aside>
</template>

<script setup lang="ts">
import { onBeforeUnmount, onMounted } from 'vue'
import SplitPane from './SplitPane.vue'
import WorkspacePane from './WorkspacePane.vue'
import { MIN_WORKSPACE_W, useWorkspaceStore, type WorkspaceLayout } from '../store/workspace'

defineProps<{
  /** 当前 Agent 工作目录（文件树 / Git / 终端 / 差异的根） */
  root: string
}>()

defineEmits<{
  (e: 'close'): void
  /** 切回旧的抽屉模式 */
  (e: 'switch-mode'): void
}>()

const ws = useWorkspaceStore()

const LAYOUTS: Array<{ value: WorkspaceLayout; label: string; title: string }> = [
  { value: 'single', label: '单栏', title: '单栏工作区' },
  { value: 'split-h', label: '左右', title: '左右分屏' },
  { value: 'split-v', label: '上下', title: '上下分屏' }
]

/** 聊天区最小保留宽度：拖再宽也不能把聊天挤没 */
const MIN_CHAT_W = 420

function onResizeStart(event: PointerEvent) {
  if (event.button !== 0) return
  event.preventDefault()
  const startX = event.clientX
  const startW = ws.width
  document.body.classList.add('cc-split-drag-h')

  const onMove = (ev: PointerEvent) => {
    const raw = startW - (ev.clientX - startX)
    // 先按「视口减聊天区最小宽」封顶，再交给 store 做 320~1200 的收敛
    ws.setWidth(Math.min(raw, Math.max(MIN_WORKSPACE_W, window.innerWidth - MIN_CHAT_W)))
  }
  const onUp = () => {
    document.body.classList.remove('cc-split-drag-h')
    window.removeEventListener('pointermove', onMove)
    window.removeEventListener('pointerup', onUp)
    window.removeEventListener('pointercancel', onUp)
  }
  window.addEventListener('pointermove', onMove)
  window.addEventListener('pointerup', onUp)
  window.addEventListener('pointercancel', onUp)
}

/* ---------------- 快捷键 ---------------- */

function cycleTab(backward: boolean) {
  // 主栏为主；主栏没有标签时退到副栏（分屏下鼠标可能停在右栏）
  const pane = ws.mainTabs.length ? 'main' : 'side'
  const list = ws.tabsOf(pane)
  if (list.length < 2) return
  const current = list.findIndex(t => t.id === ws.activeOf(pane))
  const step = backward ? -1 : 1
  const next = list[(current + step + list.length) % list.length]
  ws.activate(next.id)
}

async function closeActiveTab() {
  const pane = ws.mainTabs.length ? 'main' : 'side'
  const id = ws.activeOf(pane)
  if (!id) return
  const tab = ws.tabs.find(t => t.id === id)
  if (tab?.dirty) {
    const { confirm } = await import('../utils/confirm')
    const ok = await confirm({
      title: '放弃未保存的修改？',
      content: `${tab.title} 有未保存的修改，关闭标签会丢失这些改动。`,
      okText: '关闭并丢弃',
      type: 'danger'
    })
    if (!ok) return
    ws.markDirty(id, false)
  }
  ws.closeTab(id)
}

function onKeydown(event: KeyboardEvent) {
  if (!(event.ctrlKey || event.metaKey)) return
  if (event.key === 'Tab') {
    event.preventDefault()
    cycleTab(event.shiftKey)
    return
  }
  if (event.altKey && event.key.toLowerCase() === 'w') {
    event.preventDefault()
    void closeActiveTab()
  }
}

onMounted(() => document.addEventListener('keydown', onKeydown))
onBeforeUnmount(() => document.removeEventListener('keydown', onKeydown))
</script>
