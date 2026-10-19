<!--
  差异查看器（单栏 unified / 双栏对照）
  ----------------------------------------------------------------------------
  两种输入，同一套行模型（见 `utils/diff-hunk.ts`）：
    · unified  —— `GET /api/git/diff` 的原文，行号由 git 算好，可逐块/逐行撤销；
    · oldText + newText —— 快照轮次的「备份内容 vs 当前内容」，前端算 diff，
      **只读**（快照撤销走整文件回滚，语义上不该在这个视图里做 hunk 撤销）。

  撤销职责边界：本组件只负责「把 patch 文本 emit 出去」，真正调
  `POST /api/git/restore-hunks` 的动作在父级——因为只有父级知道 projectRoot
  与是否 git 仓库。这样组件可以同时被 Git 面板与改动面板复用。

  大文件保护：hunk 超过 4000 行时默认全部折叠，先给用户 hunk 概览，
  逐个展开再看，避免一次性铺几万个 DOM 节点。
-->
<template>
  <div class="cc-dv">
    <!-- 工具栏 -->
    <div class="cc-dv__bar">
      <span class="cc-dv__title" :title="title">{{ title || '代码差异' }}</span>
      <span class="cc-dv__stats">
        <span class="cc-dv__stat is-add">+{{ stats.added }}</span>
        <span class="cc-dv__stat is-del">-{{ stats.removed }}</span>
        <span class="cc-dv__stat is-dim">{{ stats.hunks }} 块</span>
      </span>
      <span class="cc-dv__spacer" />
      <div class="cc-dv__seg" role="group" aria-label="视图切换">
        <button
          class="cc-dv__seg-btn"
          :class="{ 'is-active': mode === 'unified' }"
          @click="mode = 'unified'"
        >单栏</button>
        <button
          class="cc-dv__seg-btn"
          :class="{ 'is-active': mode === 'split' }"
          :disabled="!hunks.length"
          @click="mode = 'split'"
        >双栏</button>
      </div>
      <button
        v-if="hunks.length > 1"
        class="cc-dv__ghost"
        :title="allCollapsed ? '展开全部变更块' : '折叠全部变更块'"
        @click="toggleAll"
      >{{ allCollapsed ? '展开全部' : '折叠全部' }}</button>
      <button class="cc-icon-btn cc-icon-btn--sm" title="关闭" @click="emit('close')">
        <svg class="cc-i cc-i--sm" style="transform: rotate(45deg)"><use href="#i-plus" /></svg>
      </button>
    </div>

    <div v-if="busy" class="cc-dv__state">正在应用改动…</div>
    <div v-else-if="error" class="cc-dv__state is-error">{{ error }}</div>
    <div v-else-if="binary" class="cc-dv__state">二进制文件，无法以文本方式对比</div>
    <div v-else-if="!hunks.length" class="cc-dv__state">
      没有可显示的差异（文件与对比基线一致，或该文件是新文件但没有内容）
    </div>

    <div v-else class="cc-dv__body">
      <div v-for="(hunk, hunkIndex) in hunks" :key="hunkIndex" class="cc-dv__hunk">
        <!-- hunk 头：折叠开关 + 行号区间 + 撤销块 -->
        <div class="cc-dv__hunk-head">
          <button class="cc-dv__fold" :title="isCollapsed(hunkIndex) ? '展开' : '折叠'" @click="toggle(hunkIndex)">
            <svg class="cc-i cc-i--sm cc-dv__fold-icon" :class="{ 'is-open': !isCollapsed(hunkIndex) }">
              <use href="#i-chev" />
            </svg>
          </button>
          <span class="cc-dv__hunk-range">{{ hunk.header }}</span>
          <span class="cc-dv__hunk-stat">
            <span class="cc-dv__stat is-add">+{{ hunk.added }}</span>
            <span class="cc-dv__stat is-del">-{{ hunk.removed }}</span>
          </span>
          <button
            v-if="restorable"
            class="cc-dv__ghost is-danger"
            :disabled="busy"
            title="把这一整块改动还原到 HEAD 版本"
            @click="restoreHunk(hunk)"
          >撤销此块</button>
        </div>

        <template v-if="!isCollapsed(hunkIndex)">
          <!-- 单栏 -->
          <template v-if="mode === 'unified'">
            <div
              v-for="(line, lineIndex) in hunk.lines"
              :key="lineIndex"
              class="cc-dv__row"
              :class="`is-${line.type}`"
            >
              <span class="cc-dv__ln">{{ line.oldLine ?? '' }}</span>
              <span class="cc-dv__ln">{{ line.newLine ?? '' }}</span>
              <span class="cc-dv__sign">{{ line.type === 'add' ? '+' : line.type === 'del' ? '-' : ' ' }}</span>
              <span class="cc-dv__text">{{ line.text || ' ' }}</span>
              <button
                v-if="restorable && line.type !== 'ctx'"
                class="cc-dv__line-act"
                :disabled="busy"
                :title="line.type === 'add' ? '撤销这一行新增' : '恢复这一行删除'"
                @click="restoreLine(hunk, lineIndex)"
              >{{ line.type === 'add' ? '撤销' : '恢复' }}</button>
            </div>
          </template>

          <!-- 双栏 -->
          <template v-else>
            <div
              v-for="(row, rowIndex) in splitRows(hunk)"
              :key="rowIndex"
              class="cc-dv__row is-split"
              :class="rowClass(row)"
            >
              <span class="cc-dv__ln">{{ row.left?.oldLine ?? '' }}</span>
              <span class="cc-dv__text is-half" :class="row.left ? `is-${row.left.type}` : 'is-empty'">
                {{ row.left ? (row.left.text || ' ') : '' }}
              </span>
              <span class="cc-dv__ln is-second">{{ row.right?.newLine ?? '' }}</span>
              <span class="cc-dv__text is-half" :class="row.right ? `is-${row.right.type}` : 'is-empty'">
                {{ row.right ? (row.right.text || ' ') : '' }}
              </span>
            </div>
          </template>
        </template>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import {
  computeHunks,
  hunkPatch,
  parseUnifiedDiff,
  patchForLine,
  splitHunk,
  summarize,
  type DiffHunk,
  type SplitRow
} from '../utils/diff-hunk'

const props = withDefaults(
  defineProps<{
    /** 标题（一般是文件路径） */
    title?: string
    /** git diff 原文（优先于 oldText/newText） */
    unified?: string
    /** 对比基线内容（快照 / HEAD） */
    oldText?: string
    /** 当前内容 */
    newText?: string
    /** 是否显示撤销入口（仅 Git 仓库且文件已跟踪时由父级打开） */
    restorable?: boolean
    /** 撤销请求进行中 */
    busy?: boolean
    /** 外部错误（如 diff 拉取失败） */
    error?: string
  }>(),
  { title: '', unified: '', oldText: undefined, newText: undefined, restorable: false, busy: false, error: '' }
)

const emit = defineEmits<{
  (e: 'restore-hunk', patch: string): void
  (e: 'restore-line', patch: string): void
  (e: 'close'): void
}>()

/** 超过这个行数就默认全部折叠（先给概览，按块展开） */
const AUTO_COLLAPSE_ROWS = 4000

const mode = ref<'unified' | 'split'>('unified')

const parsed = computed(() => (props.unified ? parseUnifiedDiff(props.unified) : []))
const binary = computed(() => parsed.value.some(file => file.binary))

const hunks = computed<DiffHunk[]>(() => {
  if (props.unified) return parsed.value.flatMap(file => file.hunks)
  if (props.oldText === undefined && props.newText === undefined) return []
  return computeHunks(props.oldText ?? '', props.newText ?? '')
})

const stats = computed(() => summarize(hunks.value))

const totalRows = computed(() => hunks.value.reduce((sum, hunk) => sum + hunk.lines.length, 0))

const collapsed = ref<Set<number>>(new Set())

/** 换文件 / 换内容时按体量决定初始折叠态 */
watch(
  () => [props.unified, props.oldText, props.newText],
  () => {
    mode.value = 'unified'
    if (totalRows.value > AUTO_COLLAPSE_ROWS) {
      collapsed.value = new Set(hunks.value.map((_, index) => index))
    } else {
      collapsed.value = new Set()
    }
  },
  { immediate: true }
)

function isCollapsed(index: number): boolean {
  return collapsed.value.has(index)
}

function toggle(index: number): void {
  const next = new Set(collapsed.value)
  if (next.has(index)) next.delete(index)
  else next.add(index)
  collapsed.value = next
}

const allCollapsed = computed(() => hunks.value.length > 0 && collapsed.value.size >= hunks.value.length)

function toggleAll(): void {
  collapsed.value = allCollapsed.value ? new Set() : new Set(hunks.value.map((_, index) => index))
}

/** 双栏行缓存：hunk 对象不变就不重算（splitHunk 每次都会新建数组） */
const splitCache = new WeakMap<DiffHunk, SplitRow[]>()
function splitRows(hunk: DiffHunk): SplitRow[] {
  const cached = splitCache.get(hunk)
  if (cached) return cached
  const rows = splitHunk(hunk)
  splitCache.set(hunk, rows)
  return rows
}

function rowClass(row: SplitRow): string {
  if (row.left && row.right) return 'is-modified'
  return row.left ? 'is-del' : 'is-add'
}

function restoreHunk(hunk: DiffHunk): void {
  emit('restore-hunk', hunkPatch(hunk))
}

function restoreLine(hunk: DiffHunk, lineIndex: number): void {
  const patch = patchForLine(hunk, lineIndex)
  if (patch) emit('restore-line', patch)
}
</script>
