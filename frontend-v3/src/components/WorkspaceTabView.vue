<!--
  工作区 · 标签内容分发（P4 · F02）
  ----------------------------------------------------------------------------
  按 tab.kind 渲染对应面板：

    tree  文件树（点文件交回父级开新标签，树内不再就地编辑）
    file  代码编辑器（自带读写 + 未保存上报）
    diff  Git 差异（只读，复用 P3 的 DiffViewer）
    git   Git 变更面板（暂存 / 提交 / 分支 / 储藏）
    term  终端（xterm）
    web   内嵌网页
    image 图片预览（blob URL，点击走全站灯箱）

  每个标签一个组件实例，因此「状态天然隔离」：切标签不丢滚动位置、
  不丢未保存内容，这正是多标签相对抽屉的核心收益。
-->
<template>
  <div class="cc-wsv-wrap">
    <ProjectFileTree
      v-if="tab.kind === 'tree'"
      :root="root"
      external-open
      @open-file="onOpenFile"
    />

    <WorkspaceFileView
      v-else-if="tab.kind === 'file'"
      :path="tab.payload.path || ''"
      @dirty="$emit('dirty', { id: tab.id, dirty: $event })"
    />

    <WorkspaceDiffView
      v-else-if="tab.kind === 'diff'"
      :root="root"
      :path="tab.payload.path || ''"
      :staged="!!tab.payload.staged"
    />

    <GitChangePanel v-else-if="tab.kind === 'git'" :root="root" />

    <!--
      终端只在激活时挂载：xterm 在 display:none 容器里初始化会拿到 0 尺寸，
      切回来就变成一条缝。宁可切走即销毁 —— 命令历史已落 localStorage，丢的只是输出。
    -->
    <TerminalPanel v-else-if="tab.kind === 'term' && active" :root="root" />

    <WebFrame v-else-if="tab.kind === 'web'" :url="tab.payload.url || ''" @navigate="onNavigate" />

    <div v-else-if="tab.kind === 'image'" class="cc-wimg">
      <div v-if="imageError" class="cc-wsv__state is-error">
        <p class="cc-wsv__state-text">{{ imageError }}</p>
        <button class="cc-btn cc-btn--ghost" @click="loadImage">重试</button>
      </div>
      <div v-else-if="!imageUrl" class="cc-wsv__state">图片加载中…</div>
      <button v-else class="cc-wimg__btn" title="点击查看大图" @click="zoomImage">
        <img class="cc-wimg__img" :src="imageUrl" :alt="tab.title" />
      </button>
    </div>
  </div>
</template>

<script setup lang="ts">
import { defineAsyncComponent, onBeforeUnmount, ref, watch } from 'vue'
import ProjectFileTree from './ProjectFileTree.vue'
import WorkspaceFileView from './WorkspaceFileView.vue'
import WorkspaceDiffView from './WorkspaceDiffView.vue'
import GitChangePanel from './GitChangePanel.vue'
import WebFrame from './WebFrame.vue'
import { getProjectFileBlobUrl } from '../api/project'
import { openLightbox } from '../utils/lightbox'
import type { TabKind, WorkspacePayload, WorkspaceTab } from '../store/workspace'

/* 终端引入 xterm（约 300KB）：静态 import 会进主 chunk，改成按需加载 */
const TerminalPanel = defineAsyncComponent(() => import('./TerminalPanel.vue'))

const props = defineProps<{
  tab: WorkspaceTab
  /** 当前 Agent 工作目录（文件树 / Git / 终端 / 差异的根） */
  root: string
  /** 是否处于激活态（终端据此决定挂不挂载） */
  active: boolean
}>()

const emit = defineEmits<{
  (e: 'dirty', payload: { id: string; dirty: boolean }): void
  /** 文件树里点了文件：父级负责开新标签 */
  (e: 'open-file', path: string): void
  /** 网页跳转：父级把新地址写回标签 payload（刷新后可恢复） */
  (e: 'navigate', payload: { id: string; url: string; kind: TabKind; data: WorkspacePayload }): void
}>()

function onOpenFile(path: string) {
  emit('open-file', path)
}

function onNavigate(url: string) {
  emit('navigate', { id: props.tab.id, url, kind: 'web', data: { url } })
}

/* ---------------- 图片标签 ---------------- */

const imageUrl = ref('')
const imageError = ref('')
/** 当前持有的 objectURL（卸载时释放，否则图片多了会一直占内存） */
let objectUrl = ''

function releaseImage() {
  if (objectUrl) {
    URL.revokeObjectURL(objectUrl)
    objectUrl = ''
  }
  imageUrl.value = ''
}

async function loadImage() {
  const path = props.tab.payload.path || ''
  if (!path) {
    imageError.value = '图片路径为空'
    return
  }
  releaseImage()
  imageError.value = ''
  try {
    objectUrl = await getProjectFileBlobUrl(path)
    imageUrl.value = objectUrl
  } catch (err) {
    imageError.value = err instanceof Error ? err.message : '图片加载失败'
  }
}

function zoomImage() {
  if (!imageUrl.value) return
  openLightbox([{ src: imageUrl.value, name: props.tab.title }])
}

watch(
  () => [props.tab.kind, props.tab.payload.path],
  () => {
    if (props.tab.kind === 'image') void loadImage()
  },
  { immediate: true }
)

onBeforeUnmount(releaseImage)
</script>
