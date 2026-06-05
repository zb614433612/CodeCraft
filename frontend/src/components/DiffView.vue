<template>
  <div class="diff-view" v-if="headContent || currentContent">
    <!-- ===== 顶部工具栏 ===== -->
    <div class="dv-toolbar">
      <div class="dv-toolbar-left">
        <span class="dv-title">{{ title || '代码差异' }}</span>
        <span class="dv-stats">
          <span class="dv-add">+{{ added }}</span>
          <span class="dv-del">-{{ removed }}</span>
          <span class="dv-mod">{{ modified }} 修改</span>
        </span>
      </div>
      <div class="dv-toolbar-right">
        <button class="dv-btn dv-btn-undo-all" @click="$emit('restoreAll', filePath!)">
          全部恢复
        </button>
      </div>
    </div>

    <!-- ===== 表头 ===== -->
    <div class="dv-header">
      <div class="dv-header-left">
        <span class="dv-header-label">HEAD 版本</span>
        <span class="dv-header-hint">只读</span>
      </div>
      <div class="dv-header-right">
        <span class="dv-header-label">当前工作区</span>
        <span class="dv-header-hint">可逐行恢复</span>
      </div>
    </div>

    <!-- ===== 左右对比主体：双面板独立滚动，中线固定 ===== -->
    <div class="dv-body-wrapper">
      <!-- 左面板 (HEAD) -->
      <div class="dv-pane dv-pane-left" ref="leftPaneRef" @scroll="onLeftScroll">
        <div
          v-for="(row, ri) in alignedRows"
          :key="'L'+ri"
          :class="['dv-row', `dv-row-${row.type}`, { 'dv-row-empty': row.type === 'added' }]"
        >
          <span class="dv-ln">{{ row.leftLn || '' }}</span>
          <span class="dv-text">{{ row.type === 'added' ? '' : row.leftText }}</span>
          <button v-if="row.type === 'removed'" class="dv-restore-btn dv-restore-remove" @click="restoreRemoveRow(ri)" title="恢复此删除行到右侧">→</button>
        </div>
      </div>
      <!-- 右面板 (当前) -->
      <div class="dv-pane dv-pane-right" ref="rightPaneRef" @scroll="onRightScroll">
        <div
          v-for="(row, ri) in alignedRows"
          :key="'R'+ri"
          :class="['dv-row', `dv-row-${row.type}`, { 'dv-row-empty': row.type === 'removed' }]"
        >
          <span class="dv-ln">{{ row.rightLn || '' }}</span>
          <span class="dv-text">{{ row.type === 'removed' ? '' : row.rightText }}</span>
          <button v-if="row.type === 'added'" class="dv-restore-btn dv-restore-add" @click="restoreAddRow(ri)" title="撤销此新增行">←</button>
        </div>
      </div>
    </div>
  </div>
  <div v-else class="diff-view diff-empty">
    正在加载差异...
  </div>
</template>

<script setup lang="ts">
import { ref, computed, watch } from 'vue'
import { message } from 'ant-design-vue'
import { gitRestoreHunks } from '@/api/git'

const props = defineProps<{
  headContent: string       // HEAD 版本（左侧）
  currentContent: string    // 当前工作区版本（右侧）
  title?: string
  filePath?: string
  projectRoot?: string
}>()

const emit = defineEmits<{
  hunksRestored: [filePath: string]
  restoreAll: [filePath: string]
}>()

// ─── 对齐行结构 ───
interface AlignedRow {
  type: 'same' | 'added' | 'removed' | 'modified'
  leftLn: string
  rightLn: string
  leftText: string
  rightText: string
  // 原始行索引（用于恢复）
  leftLineIdx: number   // -1 表示无
  rightLineIdx: number  // -1 表示无
}

const alignedRows = ref<AlignedRow[]>([])
const bodyRef = ref<HTMLElement | null>(null)
const leftPaneRef = ref<HTMLElement | null>(null)
const rightPaneRef = ref<HTMLElement | null>(null)

// 纵向滚动同步（防止死循环）
let scrollSyncLock = false
const onLeftScroll = () => {
  if (scrollSyncLock) return
  scrollSyncLock = true
  if (rightPaneRef.value) rightPaneRef.value.scrollTop = leftPaneRef.value!.scrollTop
  scrollSyncLock = false
}
const onRightScroll = () => {
  if (scrollSyncLock) return
  scrollSyncLock = true
  if (leftPaneRef.value) leftPaneRef.value.scrollTop = rightPaneRef.value!.scrollTop
  scrollSyncLock = false
}

const added = computed(() => alignedRows.value.filter(r => r.type === 'added').length)
const removed = computed(() => alignedRows.value.filter(r => r.type === 'removed').length)
const modified = computed(() => alignedRows.value.filter(r => r.type === 'modified').length)

// ─── LCS Diff 算法 ───
/** 常数：超过此行数做截断标记 */
const MAX_LINES = 3000

function buildAlignedRows(headLines: string[], currLines: string[]) {
  const n = headLines.length
  const m = currLines.length

  if (n > MAX_LINES || m > MAX_LINES) {
    // 超大文件：简单逐行对比
    alignedRows.value = simpleAlign(headLines, currLines)
    return
  }

  // LCS DP 表 (n+1) x (m+1)
  const dp: number[][] = []
  for (let i = 0; i <= n; i++) {
    dp[i] = new Array(m + 1).fill(0)
  }
  for (let i = 1; i <= n; i++) {
    for (let j = 1; j <= m; j++) {
      if (headLines[i - 1] === currLines[j - 1]) {
        dp[i][j] = dp[i - 1][j - 1] + 1
      } else {
        dp[i][j] = Math.max(dp[i - 1][j], dp[i][j - 1])
      }
    }
  }

  // 回溯
  const result: AlignedRow[] = []
  let i = n, j = m
  let leftLn = 1, rightLn = 1

  // 先收集回溯路径
  const trace: Array<{ type: 'same' | 'removed' | 'added'; lIdx: number; rIdx: number }> = []
  while (i > 0 || j > 0) {
    if (i > 0 && j > 0 && headLines[i - 1] === currLines[j - 1]) {
      trace.push({ type: 'same', lIdx: i - 1, rIdx: j - 1 })
      i--; j--
    } else if (j > 0 && (i === 0 || dp[i][j - 1] >= dp[i - 1][j])) {
      trace.push({ type: 'added', lIdx: -1, rIdx: j - 1 })
      j--
    } else {
      trace.push({ type: 'removed', lIdx: i - 1, rIdx: -1 })
      i--
    }
  }
  trace.reverse()

  // 合并相邻的 removed + added 为 modified
  let idx = 0
  while (idx < trace.length) {
    if (
      idx + 1 < trace.length &&
      trace[idx].type === 'removed' &&
      trace[idx + 1].type === 'added'
    ) {
      result.push({
        type: 'modified',
        leftLn: String(leftLn++),
        rightLn: String(rightLn++),
        leftText: headLines[trace[idx].lIdx],
        rightText: currLines[trace[idx + 1].rIdx],
        leftLineIdx: trace[idx].lIdx,
        rightLineIdx: trace[idx + 1].rIdx
      })
      idx += 2
    } else if (trace[idx].type === 'removed') {
      result.push({
        type: 'removed',
        leftLn: String(leftLn++),
        rightLn: '',
        leftText: headLines[trace[idx].lIdx],
        rightText: '',
        leftLineIdx: trace[idx].lIdx,
        rightLineIdx: -1
      })
      idx++
    } else if (trace[idx].type === 'added') {
      result.push({
        type: 'added',
        leftLn: '',
        rightLn: String(rightLn++),
        leftText: '',
        rightText: currLines[trace[idx].rIdx],
        leftLineIdx: -1,
        rightLineIdx: trace[idx].rIdx
      })
      idx++
    } else {
      result.push({
        type: 'same',
        leftLn: String(leftLn++),
        rightLn: String(rightLn++),
        leftText: headLines[trace[idx].lIdx],
        rightText: currLines[trace[idx].rIdx],
        leftLineIdx: trace[idx].lIdx,
        rightLineIdx: trace[idx].rIdx
      })
      idx++
    }
  }

  alignedRows.value = result
}

/** 超大文件简单对齐 */
function simpleAlign(headLines: string[], currLines: string[]): AlignedRow[] {
  const result: AlignedRow[] = []
  const maxLen = Math.max(headLines.length, currLines.length)
  for (let i = 0; i < maxLen; i++) {
    const hl = headLines[i]
    const cl = currLines[i]
    if (hl !== undefined && cl !== undefined) {
      result.push({
        type: hl === cl ? 'same' : 'modified',
        leftLn: String(i + 1), rightLn: String(i + 1),
        leftText: hl, rightText: cl,
        leftLineIdx: i, rightLineIdx: i
      })
    } else if (hl !== undefined) {
      result.push({
        type: 'removed',
        leftLn: String(i + 1), rightLn: '',
        leftText: hl, rightText: '',
        leftLineIdx: i, rightLineIdx: -1
      })
    } else {
      result.push({
        type: 'added',
        leftLn: '', rightLn: String(i + 1),
        leftText: '', rightText: cl!,
        leftLineIdx: -1, rightLineIdx: i
      })
    }
  }
  return result
}

// ─── 恢复操作 ───
const restoring = ref(false)

/** 恢复单行删除 → 把该行插入回当前文件 */
const restoreRemoveRow = async (rowIdx: number) => {
  if (restoring.value || !props.filePath || !props.projectRoot) return
  const row = alignedRows.value[rowIdx]
  if (!row || row.type !== 'removed') return

  // 找到上下文：上方最近相同行
  const contextBefore = getContextBefore(rowIdx)

  // 构建 patch hunk（新增一行）
  const patch = buildAddPatch(contextBefore, row.leftText, row.leftLineIdx)

  restoring.value = true
  try {
    const result = await gitRestoreHunks(props.projectRoot, props.filePath, patch)
    if (result.success) {
      message.success('已恢复该行')
      emit('hunksRestored', props.filePath)
    } else {
      message.error('恢复失败: ' + (result.error || '未知错误'))
    }
  } catch (e: any) {
    message.error('恢复失败: ' + (e.message || ''))
  } finally {
    restoring.value = false
  }
}

/** 撤销新增行 → 从当前文件删除该行 */
const restoreAddRow = async (rowIdx: number) => {
  if (restoring.value || !props.filePath || !props.projectRoot) return
  const row = alignedRows.value[rowIdx]
  if (!row || row.type !== 'added') return

  const contextBefore = getContextBefore(rowIdx)

  // 构建 patch hunk（删除一行）
  const patch = buildRemovePatch(contextBefore, row.rightText, row.rightLineIdx)

  restoring.value = true
  try {
    const result = await gitRestoreHunks(props.projectRoot, props.filePath, patch)
    if (result.success) {
      message.success('已撤销该行')
      emit('hunksRestored', props.filePath)
    } else {
      message.error('撤销失败: ' + (result.error || '未知错误'))
    }
  } catch (e: any) {
    message.error('撤销失败: ' + (e.message || ''))
  } finally {
    restoring.value = false
  }
}

/** 获取 rowIdx 之前 3 行上下文（same 行） */
function getContextBefore(rowIdx: number): AlignedRow[] {
  const ctx: AlignedRow[] = []
  for (let i = rowIdx - 1; i >= 0 && ctx.length < 3; i--) {
    if (alignedRows.value[i].type === 'same') {
      ctx.unshift(alignedRows.value[i])
    }
  }
  return ctx
}

/** 构建新增一行的 unified diff patch */
function buildAddPatch(ctx: AlignedRow[], text: string, _origLn: number): string {
  let patch = `@@ -${ctx[0]?.leftLn || '1'},1 +${ctx[0]?.rightLn || '1'},1 @@\n`
  for (const c of ctx) {
    patch += ` ${c.leftText}\n`
  }
  patch += `+${text}\n`
  return patch
}

/** 构建删除一行的 unified diff patch */
function buildRemovePatch(ctx: AlignedRow[], text: string, _origLn: number): string {
  let patch = `@@ -${ctx[0]?.rightLn || '1'},1 +${ctx[0]?.rightLn || '1'},0 @@\n`
  for (const c of ctx) {
    patch += ` ${c.rightText}\n`
  }
  patch += `-${text}\n`
  return patch
}

// ─── 监听输入变化 ───
watch(
  () => [props.headContent, props.currentContent],
  () => {
    const headLines = (props.headContent || '').split('\n')
    const currLines = (props.currentContent || '').split('\n')
    buildAlignedRows(headLines, currLines)
  },
  { immediate: true }
)
</script>

<style scoped>
/* ===== 容器 ===== */
.diff-view {
  border: 1px solid var(--border, #e8e8e8);
  border-radius: 8px;
  overflow: hidden;
  font-family: 'SF Mono', 'Monaco', 'Menlo', 'Consolas', 'Courier New', monospace;
  font-size: 13px;
  line-height: 20px;
  display: flex;
  flex-direction: column;
  background: var(--bg-card, #fff);
  flex: 1;
  min-height: 0;
}

/* ===== 工具栏 ===== */
.dv-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 8px 14px;
  background: var(--bg-root, #fafafa);
  border-bottom: 1px solid var(--border, #e8e8e8);
  flex-shrink: 0;
  gap: 12px;
  flex-wrap: wrap;
}
.dv-toolbar-left { display: flex; align-items: center; gap: 12px; }
.dv-toolbar-right { display: flex; align-items: center; gap: 8px; }
.dv-title { font-weight: 700; color: var(--text-1, #262626); font-size: 13px; }
.dv-stats { display: flex; gap: 8px; font-size: 11px; align-items: center; }
.dv-add { color: #52c41a; font-weight: 600; }
.dv-del { color: #ff4d4f; font-weight: 600; }
.dv-mod { color: #fa8c16; font-weight: 600; }

.dv-btn {
  padding: 4px 12px;
  border-radius: 5px;
  font-size: 11px;
  font-weight: 600;
  cursor: pointer;
  border: 1px solid transparent;
  transition: all 0.15s;
  font-family: inherit;
}
.dv-btn-undo-all {
  background: #1677ff;
  color: #fff;
}
.dv-btn-undo-all:hover { background: #4096ff; }

/* ===== 表头 ===== */
.dv-header {
  display: flex;
  flex-shrink: 0;
  border-bottom: 2px solid var(--border, #e8e8e8);
}
.dv-header-left, .dv-header-right {
  flex: 1;
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 6px 14px;
  min-width: 0;
}
.dv-header-left {
  border-right: 1px solid var(--border, #e8e8e8);
  background: rgba(255, 77, 79, 0.03);
}
.dv-header-right {
  background: rgba(82, 196, 26, 0.03);
}
.dv-header-label {
  font-weight: 700;
  font-size: 11px;
  color: var(--text-2, #595959);
  text-transform: uppercase;
  letter-spacing: 0.5px;
}
.dv-header-hint {
  font-size: 10px;
  color: var(--text-3, #999);
}

/* ===== 双面板容器（flex 并排，中线固定） ===== */
.dv-body-wrapper {
  flex: 1;
  display: flex;
  min-height: 0;
  overflow: hidden;
}

/* ===== 单个面板（各自独立纵横滚动） ===== */
.dv-pane {
  flex: 1;
  min-width: 0;
  overflow: auto;
}
.dv-pane-left {
  border-right: 1px solid var(--border, #e8e8e8);
}

/* ===== 差异行 ===== */
.dv-row {
  display: flex;
  align-items: flex-start;
  min-height: 20px;
  line-height: 20px;
  border-bottom: 1px solid transparent;
  width: max-content;
  min-width: 100%;
  position: relative;
}
.dv-row-empty {
  background: var(--bg-root, #fafafa);
}

.dv-ln {
  width: 46px;
  text-align: right;
  padding-right: 8px;
  color: #bbb;
  flex-shrink: 0;
  font-size: 11px;
  line-height: 20px;
  user-select: none;
}

.dv-text {
  white-space: pre;
  tab-size: 4;
  -moz-tab-size: 4;
  padding: 0 28px 0 0;
  text-align: left;
  direction: ltr;
}
/* ::selection 见下方非 scoped 全局块 */
/* ===== 行颜色 ===== */
.dv-row-same { }
.dv-row-added { background: rgba(82, 196, 26, 0.08); }
.dv-row-added .dv-ln { color: #52c41a; }
.dv-row-removed { background: rgba(255, 77, 79, 0.06); }
.dv-row-removed .dv-ln { color: #ff4d4f; }
.dv-row-modified { background: rgba(250, 140, 22, 0.06); }
.dv-row-modified .dv-ln { color: #fa8c16; }

/* ===== 恢复按钮 ===== */
.dv-restore-btn {
  position: absolute;
  top: 2px;
  width: 20px;
  height: 20px;
  border-radius: 4px;
  border: 1px solid var(--border, #d9d9d9);
  background: #fff;
  color: var(--text-2, #595959);
  font-size: 11px;
  font-weight: 700;
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: center;
  transition: all 0.15s;
  opacity: 0;
  z-index: 5;
}
/* 删除行恢复按钮 → 在左栏右边缘 */
.dv-restore-remove {
  right: 4px;
}
/* 新增行撤销按钮 ← 在右栏左边缘（靠近中间分隔线） */
.dv-restore-add {
  left: 4px;
}
.dv-row:hover .dv-restore-btn { opacity: 1; }

.dv-restore-remove:hover {
  background: #52c41a;
  color: #fff;
  border-color: #52c41a;
}
.dv-restore-add:hover {
  background: #ff4d4f;
  color: #fff;
  border-color: #ff4d4f;
}

/* ===== 空状态 ===== */
.diff-empty {
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 30px;
  color: var(--text-3, #999);
}

/* ===== 暗色模式适配 ===== */
[data-theme="dark"] .diff-view {
  border-color: #2a2838;
  background: #1a1925;
}
[data-theme="dark"] .dv-toolbar {
  background: #121117;
  border-bottom-color: #2a2838;
}
[data-theme="dark"] .dv-title { color: #e4e2f0; }
[data-theme="dark"] .dv-header {
  border-bottom-color: #2a2838;
}
[data-theme="dark"] .dv-header-left {
  background: rgba(255, 77, 79, 0.03);
  border-right-color: #2a2838;
}
[data-theme="dark"] .dv-header-right {
  background: rgba(82, 196, 26, 0.03);
}
[data-theme="dark"] .dv-header-label { color: #a09eb8; }
[data-theme="dark"] .dv-header-hint { color: #6b6b80; }
[data-theme="dark"] .dv-pane-left {
  border-right-color: #2a2838;
}
[data-theme="dark"] .dv-row-empty {
  background: #121117;
}
[data-theme="dark"] .dv-ln { color: #525070; }

[data-theme="dark"] .dv-row-added { background: rgba(82, 196, 26, 0.06); }
[data-theme="dark"] .dv-row-removed { background: rgba(255, 77, 79, 0.05); }
[data-theme="dark"] .dv-row-modified { background: rgba(250, 140, 22, 0.05); }

[data-theme="dark"] .dv-restore-btn {
  background: #1a1925;
  border-color: #3a3850;
  color: #a09eb8;
}
[data-theme="dark"] .dv-restore-remove:hover {
  background: #3a5030;
  color: #52c41a;
  border-color: #52c41a;
}
[data-theme="dark"] .dv-restore-add:hover {
  background: #503030;
  color: #ff4d4f;
  border-color: #ff4d4f;
}
[data-theme="dark"] .dv-btn-undo-all {
  background: #4f7df3;
}
[data-theme="dark"] .dv-btn-undo-all:hover {
  background: #6a95f7;
}
</style>

<style>
/* ===== 全局样式：Diff 代码选中可见性 ===== */
.dv-text::selection { background: rgba(38, 79, 120, 0.30); color: #d4d4d4 !important; }
.dv-text *::selection { background: rgba(38, 79, 120, 0.30); color: #d4d4d4 !important; }
[data-theme="light"] .dv-text::selection { background: rgba(173, 214, 255, 0.30); color: #1e1e1e !important; }
[data-theme="light"] .dv-text *::selection { background: rgba(173, 214, 255, 0.30); color: #1e1e1e !important; }
</style>
