<!--
  工作区 · 差异标签（P4 · F02，复用 P3 的 DiffViewer）
  ----------------------------------------------------------------------------
  只读视图：撤销入口仍在 Git 面板里（那边有完整的仓库上下文：
  是否已暂存、有没有 index.lock 冲突）。这里只解决「想单独把一个文件的 diff
  摊开看」——抽屉里点文件名也能看，但切来切去会丢掉滚动位置。

  数据来源：GET /api/git/diff?projectRoot=&file=&staged=
-->
<template>
  <div class="cc-wdiff">
    <div v-if="!root" class="cc-wsv__state is-hint">
      <p class="cc-wsv__state-text">当前 Agent 没有设置工作目录，无法读取 Git 差异。</p>
    </div>
    <div v-else-if="loading" class="cc-wsv__state">读取差异中…</div>
    <div v-else-if="error" class="cc-wsv__state is-error">
      <p class="cc-wsv__state-text">{{ error }}</p>
      <button class="cc-btn cc-btn--ghost" @click="load">重试</button>
    </div>
    <div v-else-if="!diff.trim()" class="cc-wsv__state is-hint">
      <p class="cc-wsv__state-text">该文件没有未提交的改动（或为二进制文件，Git 不输出文本差异）。</p>
    </div>
    <DiffViewer v-else :title="path" :unified="diff" :restorable="false" />
  </div>
</template>

<script setup lang="ts">
import { ref, watch } from 'vue'
import DiffViewer from './DiffViewer.vue'
import { getGitDiff } from '../api/git'

const props = defineProps<{
  /** 仓库工作目录（当前 Agent 工作目录） */
  root: string
  /** 文件相对路径（git 认的是相对仓库根的路径） */
  path: string
  /** true = 对比暂存区（git diff --cached） */
  staged?: boolean
}>()

const loading = ref(false)
const error = ref('')
const diff = ref('')

async function load() {
  if (!props.root || !props.path) return
  loading.value = true
  error.value = ''
  try {
    const res = await getGitDiff(props.root, props.path, props.staged)
    // 后端约定：success=false 时 diff 为空、error 里是原因（如「不是 git 仓库」）
    if (res.success === false) {
      error.value = res.error || res.diff || '读取差异失败'
      diff.value = ''
      return
    }
    diff.value = res.diff || ''
  } catch (err) {
    error.value = err instanceof Error ? err.message : '读取差异失败'
  } finally {
    loading.value = false
  }
}

watch(() => [props.root, props.path, props.staged], load, { immediate: true })
</script>
