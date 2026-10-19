<!--
  工作区 · 单栏容器（P4 · F02 / F03）
  ----------------------------------------------------------------------------
  一个 pane = 一条标签栏 + 一块内容区。分屏时同时存在两个 pane（main / side）。

  状态直接读写 workspace store（而不是层层 emit 到 ChatView）：
    pane 本身就是 store 的视图，把 activate/close/reorder 再往上转发一层
    只会让 ChatView 多出十几个和它无关的处理函数。
-->
<template>
  <div class="cc-wsp">
    <WorkspaceTabs
      :tabs="tabs"
      :active-id="activeId"
      :pane="pane"
      @activate="ws.activate"
      @close="onClose"
      @close-pane="onClosePane"
      @add="menuOpen = !menuOpen"
      @reorder="payload => ws.reorderTab(payload.id, payload.toIndex)"
      @move-pane="payload => ws.moveTab(payload.id, payload.pane)"
    />

    <!-- 新建菜单：开在本栏（分屏时两个 pane 各管各的） -->
    <div v-if="menuOpen" class="cc-wsp__menu">
      <div class="cc-wsp__menu-head">在本栏打开</div>
      <button v-for="item in MENU_ITEMS" :key="item.kind" class="cc-wsp__menu-item" @click="onMenuPick(item.kind)">
        <svg class="cc-i cc-i--sm"><use :href="'#' + item.icon" /></svg>
        {{ item.label }}
      </button>
      <button class="cc-wsp__menu-item" @click="onAddWeb">
        <svg class="cc-i cc-i--sm"><use href="#i-link" /></svg>
        浏览网页…
      </button>
    </div>

    <div class="cc-wsp__body">
      <template v-if="tabs.length">
        <!--
          所有标签都实例化、只显示激活的那个（v-show）：切回来时滚动位置、
          未保存的编辑内容都还在。终端例外，见 WorkspaceTabView 里的说明。
        -->
        <div v-for="tab in tabs" v-show="tab.id === activeId" :key="tab.id" class="cc-wsp__view">
          <WorkspaceTabView
            :tab="tab"
            :root="root"
            :active="tab.id === activeId"
            @dirty="onDirty"
            @open-file="onOpenFile"
            @navigate="onNavigate"
          />
        </div>
      </template>

      <div v-else class="cc-wsp__empty">
        <svg class="cc-i cc-i--lg" style="color: var(--text-4)"><use href="#i-folder" /></svg>
        <p class="cc-wsp__empty-title">这一栏还没有标签</p>
        <p class="cc-wsp__empty-desc">点右上角 + 打开文件树、Git 变更、终端或网页</p>
        <button class="cc-btn cc-btn--primary" @click="menuOpen = true">打开面板</button>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, ref } from 'vue'
import WorkspaceTabs from './WorkspaceTabs.vue'
import WorkspaceTabView from './WorkspaceTabView.vue'
import { confirm } from '../utils/confirm'
import { toast } from '../utils/toast'
import { prompt } from '../utils/prompt'
import { describeWebUrlError, normalizeWebUrl } from '../utils/web-url'
import { hostOf, MAX_TABS, useWorkspaceStore, type PaneId, type TabKind, type WorkspacePayload } from '../store/workspace'

const props = defineProps<{
  pane: PaneId
  /** 当前 Agent 工作目录 */
  root: string
}>()

const ws = useWorkspaceStore()

/** 菜单项：tree / git / term 是无参单例面板 */
const MENU_ITEMS: Array<{ kind: TabKind; label: string; icon: string }> = [
  { kind: 'tree', label: '文件树', icon: 'i-folder' },
  { kind: 'git', label: 'Git 变更', icon: 'i-git' },
  { kind: 'term', label: '终端', icon: 'i-term' }
]

const menuOpen = ref(false)

const tabs = computed(() => ws.tabsOf(props.pane))
const activeId = computed(() => ws.activeOf(props.pane))

function closeMenu() {
  menuOpen.value = false
}

function onMenuPick(kind: TabKind) {
  closeMenu()
  const id = ws.openTab(kind, {}, { pane: props.pane })
  if (!id) toast.warning(`标签最多 ${MAX_TABS} 个，请先关闭几个`)
}

async function onAddWeb() {
  closeMenu()
  const input = await prompt({
    title: '打开网页',
    label: '仅支持 http / https，被站点安全头拒绝时可改用系统浏览器',
    placeholder: 'https://example.com',
    okText: '打开',
    // 非法地址直接在弹窗里拦下，不让用户白跑一趟
    validate: value => (normalizeWebUrl(value) ? '' : describeWebUrlError(value))
  })
  if (input == null) return
  const url = normalizeWebUrl(input)
  if (!url) {
    toast.error(describeWebUrlError(input))
    return
  }
  ws.openTab('web', { url, host: hostOf(url) }, { pane: props.pane })
}

async function onClose(id: string) {
  const tab = ws.tabs.find(t => t.id === id)
  if (!tab) return
  if (tab.dirty) {
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

async function onClosePane() {
  const dirty = tabs.value.filter(t => t.dirty)
  if (dirty.length) {
    const ok = await confirm({
      title: '关闭本栏全部标签？',
      content: `${dirty.length} 个标签有未保存的修改，关闭后会丢失。`,
      okText: '全部关闭',
      type: 'danger'
    })
    if (!ok) return
    dirty.forEach(t => ws.markDirty(t.id, false))
  }
  ws.closePane(props.pane)
}

function onDirty(payload: { id: string; dirty: boolean }) {
  ws.markDirty(payload.id, payload.dirty)
}

/** 文件树里点了文件：在本栏开一个 file 标签（同路径已开则直接激活） */
function onOpenFile(path: string) {
  ws.openTab('file', { path }, { pane: props.pane })
}

function onNavigate(payload: { id: string; url: string; kind: TabKind; data: WorkspacePayload }) {
  ws.updatePayload(payload.id, payload.data)
  ws.renameTab(payload.id, hostOf(payload.url) || '网页')
}

/** 点击面板外关闭菜单（菜单是浮层，点别处应该收起） */
function onDocClick(event: MouseEvent) {
  if (!menuOpen.value) return
  const target = event.target as Element | null
  if (target?.closest('.cc-wsp__menu') || target?.closest('.cc-wtabs__op')) return
  closeMenu()
}
document.addEventListener('click', onDocClick)
onBeforeUnmount(() => document.removeEventListener('click', onDocClick))
</script>
