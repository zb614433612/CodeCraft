<!--
  聊天页 · 改动回滚面板
  ----------------------------------------------------------------------------
  数据接缝（sessionId = 当前会话 id）：
    GET  /api/snapshots/session-changes?sessionId=   会话内全部文件改动（含增删行数）
    GET  /api/snapshots/list?sessionId=              快照列表
    GET  /api/snapshots/preview?snapshotId=          快照包含哪些文件（回滚前预览）
    POST /api/snapshots/rollback-file { sessionId, filePath }   回滚单个文件
    POST /api/snapshots/rollback-session { sessionId }          回滚全部文件
    POST /api/snapshots/rollback { snapshotId }                 回滚某个快照

  设计要点：
    1) 「按文件」是日常入口（改坏一个文件就退一个），「按快照」是按时间点整体回退；
    2) 这些 api 层函数在内部吞掉了异常（失败返回 false / null），所以成功与否只能看返回值，
       失败时统一 toast 提示，不做乐观更新；
    3) 回滚是破坏性操作，全部走二次确认。
-->
<template>
  <Teleport to="body">
    <div v-if="open" class="cc-mask is-open" style="z-index: 82" @click="close" />
    <div class="cc-changes" :class="{ 'is-open': open }">
      <div class="cc-changes__head">
        <div class="cc-changes__title">
          改动与回滚
          <span v-if="changes" class="cc-changes__summary">
            {{ changes.totalFiles }} 个文件
            <span style="color: var(--ok)">+{{ changes.totalLinesAdded }}</span>
            <span style="color: var(--danger)">-{{ changes.totalLinesDeleted }}</span>
          </span>
        </div>
        <button class="cc-icon-btn cc-icon-btn--sm" title="刷新" :disabled="loading || busy" @click="load">
          <svg class="cc-i cc-i--sm"><use href="#i-refresh" /></svg>
        </button>
        <button class="cc-icon-btn cc-icon-btn--sm" title="关闭" @click="close">
          <svg class="cc-i cc-i--sm" style="transform: rotate(45deg)"><use href="#i-plus" /></svg>
        </button>
      </div>

      <div class="cc-changes__seg">
        <button
          class="cc-changes__seg-btn"
          :class="{ 'is-active': view === 'files' }"
          @click="view = 'files'"
        >
          按文件
        </button>
        <button
          class="cc-changes__seg-btn"
          :class="{ 'is-active': view === 'snapshots' }"
          @click="switchToSnapshots"
        >
          按快照
        </button>
      </div>

      <div class="cc-changes__body">
        <!-- 差异视图：覆盖列表（DiffViewer 自带工具栏与关闭按钮） -->
        <template v-if="diffFile">
          <div v-if="diffLoading" class="cc-changes__state">读取差异…</div>
          <div v-else-if="diffError" class="cc-changes__state is-error">{{ diffError }}</div>
          <DiffViewer
            v-else
            :title="diffFile"
            :old-text="diffOld"
            :new-text="diffNew"
            @close="closeDiffView"
          />
        </template>
        <div v-else-if="!sessionId" class="cc-changes__state">还没有会话，无法回滚改动</div>
        <div v-else-if="loading" class="cc-changes__state">加载中…</div>
        <div v-else-if="error" class="cc-changes__state is-error">{{ error }}</div>

        <!-- 按文件 -->
        <template v-else-if="view === 'files'">
          <div v-if="!(changes?.files || []).length" class="cc-changes__state">
            本会话没有记录到文件改动
          </div>
          <div
            v-for="file in changes?.files || []"
            :key="file.relativePath"
            class="cc-changes__row"
            :class="{ 'is-rolled': file.rolledBack }"
          >
            <span class="cc-changes__file" :title="file.relativePath">{{ file.relativePath }}</span>
            <span v-if="file.wasNewFile" class="cc-changes__tag">新增</span>
            <span v-if="file.rolledBack" class="cc-changes__tag is-dim">已回滚</span>
            <span class="cc-changes__delta">
              <span style="color: var(--ok)">+{{ file.linesAdded }}</span>
              <span style="color: var(--danger)">-{{ file.linesDeleted }}</span>
            </span>
            <button
              class="cc-icon-btn cc-icon-btn--sm"
              title="查看该文件与快照基线的差异"
              :disabled="busy || file.rolledBack || (!file.linesAdded && !file.linesDeleted)"
              @click="openDiff(file)"
            >
              <svg class="cc-i cc-i--sm"><use href="#i-search" /></svg>
            </button>
            <button
              class="cc-icon-btn cc-icon-btn--sm"
              title="回滚这个文件"
              :disabled="busy || file.rolledBack"
              @click="rollbackOne(file.relativePath)"
            >
              <svg class="cc-i cc-i--sm"><use href="#i-refresh" /></svg>
            </button>
          </div>
        </template>

        <!-- 按快照 -->
        <template v-else>
          <div v-if="!(snapshots || []).length" class="cc-changes__state">本会话没有快照记录</div>
          <template v-for="snap in snapshots" :key="snap.snapshotId">
            <div class="cc-changes__row" :class="{ 'is-rolled': snap.rolledBack }">
              <span class="cc-changes__snaptime">{{ formatTime(snap.timestamp) }}</span>
              <span
                class="cc-changes__file is-text"
                :title="hintOf(snap) || '后端未返回该轮提问（旧版本后端或已清理），可从聊天记录中对照时间定位'"
              >
                {{ hintOf(snap) || '（该轮提问未获取到）' }}
              </span>
              <span class="cc-changes__tag is-dim">{{ snap.fileCount }} 个文件</span>
              <span v-if="snap.rolledBack" class="cc-changes__tag is-dim">已回滚</span>
              <span class="cc-changes__delta">{{ formatSize(snap.totalSize) }}</span>
              <button
                class="cc-icon-btn cc-icon-btn--sm"
                :title="previewId === snap.snapshotId ? '收起' : '查看快照文件'"
                :disabled="busy"
                @click="togglePreview(snap.snapshotId)"
              >
                <svg class="cc-i cc-i--sm"><use href="#i-search" /></svg>
              </button>
              <button
                class="cc-icon-btn cc-icon-btn--sm"
                title="回滚到这个快照"
                :disabled="busy || snap.rolledBack"
                @click="rollbackSnapshot(snap.snapshotId)"
              >
                <svg class="cc-i cc-i--sm"><use href="#i-refresh" /></svg>
              </button>
            </div>
            <div v-if="previewId === snap.snapshotId" class="cc-changes__preview">
              <div v-if="previewLoading" class="cc-changes__state">读取快照…</div>
              <div v-else-if="!(preview?.files || []).length" class="cc-changes__state">快照里没有文件</div>
              <div v-for="f in preview?.files || []" :key="f.relativePath" class="cc-changes__preview-row">
                <span class="cc-changes__tag" :class="f.action === 'delete' ? 'is-del' : 'is-restore'">
                  {{ f.action === 'delete' ? '删除' : '还原' }}
                </span>
                <span class="cc-changes__file" :title="f.relativePath">{{ f.relativePath }}</span>
              </div>
            </div>
          </template>
        </template>
      </div>

      <div class="cc-changes__foot">
        <button class="cc-btn cc-btn--ghost" :disabled="busy" @click="close">关闭</button>
        <button
          class="cc-btn cc-btn--danger"
          :disabled="busy || !sessionId || !(changes?.files || []).some(f => !f.rolledBack)"
          @click="rollbackAll"
        >
          {{ busy ? '处理中…' : '回滚本会话全部改动' }}
        </button>
      </div>
    </div>
  </Teleport>
</template>

<script setup lang="ts">
import { ref, watch } from 'vue'
import {
  listSnapshots,
  previewRollback,
  executeRollback,
  getSessionChanges,
  rollbackFile,
  rollbackAllFiles,
  getSnapshotFileContent,
  type RollbackPreview,
  type SessionChanges,
  type SnapshotSummary,
  type SessionFileChange
} from '../api/snapshot'
import { readProjectFile } from '../api/project'
import { toast } from '../utils/toast'
import { confirm } from '../utils/confirm'
import DiffViewer from './DiffViewer.vue'

const props = withDefaults(
  defineProps<{
    open?: boolean
    sessionId?: number | null
    /**
     * turnId → 该轮用户提问摘要。
     * 快照本身只有时间 / 文件数，光看这些认不出是哪一轮；
     * 父级用已加载的历史消息补上，缺失时（更早的轮次还没翻到）显示占位文案。
     */
    turnHints?: Record<string, string>
    /** 当前 Agent 工作目录：用于把相对路径拼成可读的绝对路径（查看差异用） */
    projectRoot?: string
  }>(),
  { open: false, sessionId: null, turnHints: () => ({}), projectRoot: '' }
)

const emit = defineEmits<{
  (e: 'update:open', value: boolean): void
  (e: 'changed'): void
}>()

const view = ref<'files' | 'snapshots'>('files')
const changes = ref<SessionChanges | null>(null)
const snapshots = ref<SnapshotSummary[]>([])
const loading = ref(false)
const error = ref('')
const busy = ref(false)

const previewId = ref('')
const preview = ref<RollbackPreview | null>(null)
const previewLoading = ref(false)

/* ---------------- 差异查看（只读） ----------------
 * 快照撤销的粒度是「整文件回滚」，所以这里的 diff 只做只读展示，不给 hunk 撤销按钮
 * （hunk 撤销是 Git 视图的语义，见 GitChangePanel / DiffViewer）。
 */
const diffFile = ref('')
const diffOld = ref('')
const diffNew = ref('')
const diffLoading = ref(false)
const diffError = ref('')

/** 把会话改动里的 relativePath 还原成可读绝对路径（跨盘时后端已给绝对路径） */
function absolutePathOf(relative: string): string {
  const normalized = relative.replace(/\\/g, '/')
  if (/^[a-zA-Z]:\//.test(normalized) || normalized.startsWith('/')) return normalized
  const root = (props.projectRoot || '').replace(/\\/g, '/').replace(/\/+$/, '')
  return root ? `${root}/${normalized}` : normalized
}

/**
 * 打开某文件的差异：基线 = 该会话最早快照里的版本，对照 = 当前磁盘内容。
 * <p>两个接口都自带 try/catch（失败返回 null），所以这里只能靠返回值判断。</p>
 *
 * <p>注意 `getSnapshotFileContent` 返回 null 有两种含义：① 该文件在快照里没有备份
 * （快照被清理 / 路径对不上）；② 它本来就是这个会话里**新建**的文件 —— 后端注释写明
 * 「如果是新建文件则返回 null」。所以必须先看 `wasNewFile`，否则新建文件的差异永远打不开。</p>
 */
async function openDiff(file: SessionFileChange): Promise<void> {
  const sid = props.sessionId
  if (!sid) return
  previewId.value = ''
  preview.value = null
  diffFile.value = file.relativePath
  diffLoading.value = true
  diffError.value = ''
  diffOld.value = ''
  diffNew.value = ''
  try {
    const [oldText, current] = await Promise.all([
      getSnapshotFileContent(sid, file.relativePath),
      readProjectFile(absolutePathOf(file.relativePath))
    ])
    if (oldText === null && !file.wasNewFile) {
      diffError.value = '拿不到该文件的快照基线（可能快照已被清理，或路径与快照记录不一致）'
      return
    }
    const currentText = typeof current.data === 'string' ? current.data : ''
    if (!currentText && current.code !== 200) {
      diffError.value = current.message || '读取当前文件内容失败'
      return
    }
    // 新建文件的基线是空内容 → diff 会把整个文件显示为新增，符合直觉
    diffOld.value = oldText ?? ''
    diffNew.value = currentText
  } catch (err) {
    diffError.value = err instanceof Error ? err.message : '读取文件内容失败'
  } finally {
    diffLoading.value = false
  }
}

function closeDiffView(): void {
  diffFile.value = ''
  diffOld.value = ''
  diffNew.value = ''
  diffError.value = ''
}

function close() {
  emit('update:open', false)
}

function formatTime(value?: string): string {
  if (!value) return '—'
  // 后端 LocalDateTime 序列化为 ISO（2026-10-17T12:52:40.3792586），去掉 T 与毫秒
  const m = /^(\d{4})-(\d{2})-(\d{2})[T ](\d{2}):(\d{2}):(\d{2})/.exec(value)
  if (m) return `${m[2]}-${m[3]} ${m[4]}:${m[5]}:${m[6]}`
  return value
}

function formatSize(bytes?: number): string {
  const n = bytes ?? 0
  if (n >= 1024 * 1024) return `${(n / 1024 / 1024).toFixed(1)} MB`
  if (n >= 1024) return `${(n / 1024).toFixed(0)} KB`
  return `${n} B`
}

/**
 * 取该轮的提问摘要。
 *
 * <p>优先用后端回填的 `snap.turnPrompt`（`/api/snapshots/list` 按 turnId 联查消息），
 * 它对**所有**历史轮次都有效，不受「前端只加载了最近 5 轮」限制；
 * 只有老后端才回退到父级用已加载消息拼的 `turnHints`。</p>
 */
function hintOf(snap: SnapshotSummary): string {
  if (snap.turnPrompt) return snap.turnPrompt
  if (!snap.turnId) return ''
  return props.turnHints[snap.turnId] || ''
}

async function load() {
  const sid = props.sessionId
  if (!sid) {
    changes.value = null
    snapshots.value = []
    return
  }
  loading.value = true
  error.value = ''
  try {
    // 两个接口都自带 try/catch（失败返回 null / []），这里不会抛
    const [changeResult, snapshotResult] = await Promise.all([getSessionChanges(sid), listSnapshots(sid)])
    changes.value = changeResult
    snapshots.value = snapshotResult
    if (!changeResult && !snapshotResult.length) error.value = '没有读到改动记录（可能是新会话或后端未启用快照）'
  } finally {
    loading.value = false
  }
}

function switchToSnapshots() {
  view.value = 'snapshots'
  if (!snapshots.value.length) void load()
}

async function togglePreview(snapshotId: string) {
  if (previewId.value === snapshotId) {
    previewId.value = ''
    preview.value = null
    return
  }
  previewId.value = snapshotId
  preview.value = null
  previewLoading.value = true
  try {
    preview.value = await previewRollback(snapshotId)
  } finally {
    previewLoading.value = false
  }
}

async function rollbackOne(filePath: string) {
  const sid = props.sessionId
  if (!sid || busy.value) return
  const ok = await confirm({
    title: '回滚单个文件',
    content: `将把「${filePath}」恢复到本会话开始前的版本，其他文件不受影响。此操作不可撤销。`,
    okText: '回滚',
    type: 'danger'
  })
  if (!ok) return
  busy.value = true
  try {
    const success = await rollbackFile(sid, filePath)
    if (!success) {
      toast.error('回滚失败', { description: '后端未返回成功，请查看运行日志' })
      return
    }
    toast.success('已回滚', { description: filePath })
    emit('changed')
    await load()
  } finally {
    busy.value = false
  }
}

async function rollbackAll() {
  const sid = props.sessionId
  if (!sid || busy.value) return
  const count = (changes.value?.files || []).filter(f => !f.rolledBack).length
  const ok = await confirm({
    title: '回滚全部改动',
    content: `将把本会话改动的 ${count} 个文件全部恢复到会话开始前的版本。此操作不可撤销。`,
    okText: '全部回滚',
    type: 'danger'
  })
  if (!ok) return
  busy.value = true
  try {
    const success = await rollbackAllFiles(sid)
    if (!success) {
      toast.error('全部回滚失败', { description: '后端未返回成功，请查看运行日志' })
      return
    }
    toast.success('已回滚全部改动')
    emit('changed')
    await load()
  } finally {
    busy.value = false
  }
}

async function rollbackSnapshot(snapshotId: string) {
  if (busy.value) return
  const ok = await confirm({
    title: '回滚到快照',
    content: `将把文件恢复到快照「${snapshotId}」记录的状态。此操作不可撤销。`,
    okText: '回滚',
    type: 'danger'
  })
  if (!ok) return
  busy.value = true
  try {
    const success = await executeRollback(snapshotId)
    if (!success) {
      toast.error('快照回滚失败', { description: '后端未返回成功，请查看运行日志' })
      return
    }
    toast.success('已回滚到快照', { description: snapshotId })
    emit('changed')
    await load()
  } finally {
    busy.value = false
  }
}

/** 打开时拉一次；会话切换（sessionId 变化）也重拉 */
watch(
  () => [props.open, props.sessionId] as const,
  ([open]) => {
    if (!open) return
    previewId.value = ''
    preview.value = null
    void load()
  },
  { immediate: true }
)
</script>
