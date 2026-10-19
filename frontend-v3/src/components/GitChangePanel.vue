<!--
  聊天页右侧抽屉 · Git
  ----------------------------------------------------------------------------
  数据接缝（projectRoot = 当前 Agent 工作目录，可被上层覆盖）：
    GET  /api/git/status?projectRoot=                  仓库状态 + 变更列表
    GET  /api/git/diff?projectRoot=&file=&staged=      单文件 diff
    POST /api/git/add { projectRoot, files, all }      暂存（单个 / 全部）
    POST /api/git/unstage { projectRoot, files, all }  取消暂存（单个 / 全部）
    POST /api/git/restore { projectRoot, file }        撤销工作区改动
    POST /api/git/commit { projectRoot, message }      提交
    POST /api/git/pull  |  push  |  fetch              远程同步
    POST /api/git/stash { message }                   储藏当前改动
    GET  /api/git/stash                               储藏列表（原始文本）
    POST /api/git/stash/pop | /stash/drop { index }   弹出 / 删除储藏
    GET  /api/git/branches                            分支列表 + 当前分支
    POST /api/git/branch/create | /branch/switch | /branch/delete | /merge
    GET  /api/git/tags                                标签列表
    POST /api/git/tag | /tag/push  DELETE /api/git/tag

  能力对齐（v3 补全，此前只有「看变更 → 暂存 → 提交」一条最短闭环）：
    · 远程同步：拉取 / 推送 / 获取
    · 暂存区：全部暂存 / 全部取消暂存 / 逐个勾选暂存 / 撤销单个文件改动
    · 储藏栈：储藏当前改动、列出储藏、弹出、删除
    · 分支：新建并切换、切换、合并到当前分支、删除
    · 标签：创建（可带说明）、推送、删除
    · 提交：提交信息 + 「N 个文件已暂存」提示

  说明：远端仓库地址/凭据（git remote / token）没有放进抽屉——那属于「仓库初始化」这类一次性配置，
       放在这里会让窄抽屉过载；Token 已保存的场景下 pull/push 会自动注入。
-->
<template>
  <div class="cc-git">
    <!-- 分支行 -->
    <div class="cc-git__bar">
      <button
        class="cc-git__branch-btn"
        :title="status?.remoteUrl || (status?.isRepo ? '点击展开分支面板' : '')"
        @click="toggleSection('branch')"
      >
        <svg class="cc-i cc-i--sm"><use href="#i-branch" /></svg>
        <span class="cc-git__branch">{{ branchLabel }}</span>
      </button>
      <button class="cc-icon-btn cc-icon-btn--sm" title="刷新" :disabled="loading || anyBusy" @click="refresh">
        <svg class="cc-i cc-i--sm" :class="{ 'is-spin': loading }"><use href="#i-refresh" /></svg>
      </button>
    </div>

    <div v-if="!root" class="cc-git__state">当前智能体没有设置工作目录</div>
    <div v-else-if="!status && loading" class="cc-git__state">加载中…</div>
    <div v-else-if="error" class="cc-git__state is-error">{{ error }}</div>
    <div v-else-if="!status?.isRepo" class="cc-git__state">
      当前工作目录不是 Git 仓库
      <span v-if="status?.error" class="cc-git__state-sub">{{ status.error }}</span>
    </div>

    <template v-else>
      <!-- 远程同步 -->
      <div class="cc-git__ops">
        <button class="cc-git__op" :disabled="anyBusy" @click="doSync('pull')">
          <svg class="cc-i cc-i--sm"><use href="#i-cloud-down" /></svg>{{ syncBusy === 'pull' ? '拉取中…' : '拉取' }}
        </button>
        <button class="cc-git__op" :disabled="anyBusy" @click="doSync('push')">
          <svg class="cc-i cc-i--sm"><use href="#i-cloud-up" /></svg>{{ syncBusy === 'push' ? '推送中…' : '推送' }}
        </button>
        <button class="cc-git__op" :disabled="anyBusy" @click="doSync('fetch')">
          <svg class="cc-i cc-i--sm" :class="{ 'is-spin': syncBusy === 'fetch' }"><use href="#i-sync" /></svg>
          {{ syncBusy === 'fetch' ? '获取中…' : '获取' }}
        </button>
      </div>

      <!-- 暂存区工具 -->
      <div class="cc-git__ops">
        <button class="cc-git__op" :disabled="anyBusy || !hasChanges" @click="stageAll">全部暂存</button>
        <button class="cc-git__op" :disabled="anyBusy || !hasStaged" @click="unstageAll">全部取消暂存</button>
        <button class="cc-git__op" :disabled="anyBusy || !hasChanges" @click="stashAll">
          {{ changeBusy === 'stash' ? '储藏中…' : '储藏变更' }}
        </button>
      </div>

      <!-- 中部滚动区：变更列表 + diff + 折叠区 -->
      <div class="cc-git__scroll">
        <!-- 变更列表 -->
        <div class="cc-git__changes">
          <div v-for="c in status.changes || []" :key="c.file" class="cc-git__row">
            <input
              type="checkbox"
              class="cc-git__check"
              :checked="c.status === 'staged'"
              :disabled="anyBusy"
              :title="c.status === 'staged' ? '取消暂存' : '暂存'"
              @change="toggleStage(c)"
            />
            <span class="cc-git__badge" :class="badgeClass(c)" :title="c.label">{{ badgeText(c) }}</span>
            <span class="cc-git__file" :title="c.file" @click="openDiff(c)">{{ baseName(c.file) }}</span>
            <button
              class="cc-git__act"
              title="撤销该文件的改动（不可恢复）"
              :disabled="anyBusy"
              @click.stop="restoreFile(c)"
            >
              <svg class="cc-i cc-i--sm"><use href="#i-undo" /></svg>
            </button>
          </div>
          <div v-if="!(status.changes || []).length" class="cc-git__state">工作区干净，没有待提交的改动</div>
        </div>

        <!-- diff 预览：DiffViewer 负责渲染与逐块/逐行撤销，请求仍由本组件发（只有这里知道 projectRoot） -->
        <div v-if="diffFile" class="cc-git__diff">
          <div v-if="diffLoading" class="cc-git__state">读取 diff…</div>
          <DiffViewer
            v-else
            :title="diffFile"
            :unified="diffText"
            :restorable="restorable"
            :busy="restoreBusy"
            :error="diffError"
            @restore-hunk="onRestorePatch"
            @restore-line="onRestorePatch"
            @close="closeDiff"
          />
        </div>

        <!-- 折叠区：储藏记录 / 分支 / 标签 -->
        <div class="cc-git__sections">
          <!-- 储藏记录 -->
          <div class="cc-git__section">
            <button class="cc-git__section-head" @click="toggleSection('stash')">
              <svg class="cc-i cc-i--sm cc-git__caret" :class="{ 'is-open': sectionOpen.stash }"><use href="#i-chev" /></svg>
              <svg class="cc-i cc-i--sm"><use href="#i-archive" /></svg>
              <span class="cc-git__section-title">储藏记录</span>
              <span v-if="sectionOpen.stash" class="cc-git__section-count">{{ stashLines.length }}</span>
            </button>
            <div v-show="sectionOpen.stash" class="cc-git__section-body">
              <div v-if="stashLoading" class="cc-git__mini-empty">加载中…</div>
              <div v-else-if="!stashLines.length" class="cc-git__mini-empty">没有储藏记录</div>
              <template v-else>
                <div v-for="line in stashLines" :key="line" class="cc-git__mini-row">
                  <span class="cc-git__mini-text" :title="line">{{ line }}</span>
                  <button class="cc-git__mini-btn" :disabled="anyBusy" @click="stashPop(line)">弹出</button>
                  <button class="cc-git__mini-btn is-danger" :disabled="anyBusy" @click="stashDrop(line)">删除</button>
                </div>
              </template>
            </div>
          </div>

          <!-- 分支 -->
          <div class="cc-git__section">
            <button class="cc-git__section-head" @click="toggleSection('branch')">
              <svg class="cc-i cc-i--sm cc-git__caret" :class="{ 'is-open': sectionOpen.branch }"><use href="#i-chev" /></svg>
              <svg class="cc-i cc-i--sm"><use href="#i-branch" /></svg>
              <span class="cc-git__section-title">分支</span>
              <span v-if="sectionOpen.branch" class="cc-git__section-count">{{ branchList.length }}</span>
            </button>
            <div v-show="sectionOpen.branch" class="cc-git__section-body">
              <div class="cc-git__create-row">
                <input
                  v-model="newBranchName"
                  class="cc-git__mini-input"
                  placeholder="新分支名"
                  @keydown.enter.prevent="branchCreate"
                />
                <button class="cc-git__mini-btn" :disabled="!newBranchName.trim() || anyBusy" @click="branchCreate">
                  新建并切换
                </button>
              </div>
              <div v-if="branchLoading" class="cc-git__mini-empty">加载中…</div>
              <div v-else-if="!branchList.length" class="cc-git__mini-empty">暂无分支</div>
              <template v-else>
                <div
                  v-for="b in branchList"
                  :key="b"
                  class="cc-git__mini-row"
                  :class="{ 'is-current': b === currentBranch }"
                >
                  <span class="cc-git__mini-text" :title="b">{{ b }}</span>
                  <template v-if="b !== currentBranch">
                    <button class="cc-git__mini-btn" :disabled="anyBusy" @click="branchSwitch(b)">切换</button>
                    <button class="cc-git__mini-btn" :disabled="anyBusy" @click="branchMerge(b)">合并</button>
                    <button class="cc-git__mini-btn is-danger" :disabled="anyBusy" @click="branchDelete(b)">删除</button>
                  </template>
                  <span v-else class="cc-git__mini-current">当前</span>
                </div>
              </template>
            </div>
          </div>

          <!-- 标签 -->
          <div class="cc-git__section">
            <button class="cc-git__section-head" @click="toggleSection('tag')">
              <svg class="cc-i cc-i--sm cc-git__caret" :class="{ 'is-open': sectionOpen.tag }"><use href="#i-chev" /></svg>
              <svg class="cc-i cc-i--sm"><use href="#i-tag" /></svg>
              <span class="cc-git__section-title">标签</span>
              <span v-if="sectionOpen.tag" class="cc-git__section-count">{{ tagList.length }}</span>
            </button>
            <div v-show="sectionOpen.tag" class="cc-git__section-body">
              <div class="cc-git__create-row">
                <input
                  v-model="newTagName"
                  class="cc-git__mini-input"
                  placeholder="标签名，如 v1.2.0"
                  @keydown.enter.prevent="tagCreate"
                />
                <button class="cc-git__mini-btn" :disabled="!newTagName.trim() || anyBusy" @click="tagCreate">创建</button>
              </div>
              <div v-if="tagLoading" class="cc-git__mini-empty">加载中…</div>
              <div v-else-if="!tagList.length" class="cc-git__mini-empty">暂无标签</div>
              <template v-else>
                <div v-for="t in tagList" :key="t" class="cc-git__mini-row">
                  <span class="cc-git__mini-text" :title="t">{{ t }}</span>
                  <button class="cc-git__mini-btn" :disabled="anyBusy" @click="tagPush(t)">推送</button>
                  <button class="cc-git__mini-btn is-danger" :disabled="anyBusy" @click="tagDelete(t)">删除</button>
                </div>
              </template>
            </div>
          </div>
        </div>
      </div>

      <!-- 提交区 -->
      <div class="cc-git__commit">
        <textarea
          v-model="commitMessage"
          class="cc-git__msg"
          rows="2"
          placeholder="提交信息…"
        />
        <div class="cc-git__commit-ops">
          <span class="cc-git__commit-hint">{{ hasStaged ? `${stagedCount} 个文件已暂存` : '先暂存要提交的文件' }}</span>
          <button class="cc-btn cc-btn--primary" :disabled="commitBusy || !hasStaged" @click="commit">
            {{ commitBusy ? '提交中…' : '提交' }}
          </button>
        </div>
      </div>
    </template>
  </div>
</template>

<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue'
import {
  getGitStatus,
  getGitDiff,
  gitAdd,
  gitUnstage,
  gitRestore,
  gitRestoreHunks,
  gitCommit,
  gitPull,
  gitPush,
  gitFetch,
  gitStashSave,
  gitStashList,
  gitStashPop,
  gitStashDrop,
  gitBranches,
  gitBranchCreate,
  gitBranchSwitch,
  gitBranchDelete,
  gitMerge,
  gitTags,
  gitTagCreate,
  gitTagDelete,
  gitTagPush,
  type GitChange,
  type GitStatusResult,
  type GitOperationResult
} from '../api/git'
import { toast } from '../utils/toast'
import { confirm } from '../utils/confirm'
import DiffViewer from './DiffViewer.vue'

const props = withDefaults(defineProps<{ root?: string }>(), { root: '' })

/* ---------------- 仓库状态 ---------------- */

const status = ref<GitStatusResult | null>(null)
const loading = ref(false)
const error = ref('')
const commitBusy = ref(false)
const commitMessage = ref('')

const changes = computed<GitChange[]>(() => status.value?.changes ?? [])
const hasChanges = computed(() => changes.value.length > 0)
const stagedCount = computed(() => changes.value.filter(c => c.status === 'staged').length)
const hasStaged = computed(() => stagedCount.value > 0)

const branchLabel = computed(() => {
  if (!props.root) return '—'
  if (!status.value) return '读取中…'
  if (!status.value.isRepo) return '非 Git 仓库'
  return status.value.branch || '(detached)'
})

/* ---------------- 通用 busy ---------------- */

type SyncKind = '' | 'pull' | 'push' | 'fetch'
type ChangeKind = '' | 'stageAll' | 'unstageAll' | 'stash'

const syncBusy = ref<SyncKind>('')
const changeBusy = ref<ChangeKind>('')
const rowBusy = ref('')
const branchBusy = ref(false)
const tagBusy = ref(false)
const stashBusy = ref(false)

/** 任一时长操作进行中：用于禁用整片按钮，避免并发 git 命令互相踩（index.lock） */
const anyBusy = computed(
  () =>
    !!syncBusy.value ||
    !!changeBusy.value ||
    !!rowBusy.value ||
    branchBusy.value ||
    tagBusy.value ||
    stashBusy.value ||
    commitBusy.value ||
    loading.value
)

function actionError(title: string, res: { success?: boolean; error?: string }) {
  if (res.success) return false
  toast.error(title, { description: res.error || '请稍后重试' })
  return true
}

async function refresh() {
  if (!props.root) {
    status.value = null
    return
  }
  loading.value = true
  error.value = ''
  try {
    status.value = await getGitStatus(props.root)
    // 折叠区已展开的列表跟着一起刷新（分支/标签/储藏在操作后可能变化）
    if (sectionOpen.branch) void loadBranches()
    if (sectionOpen.tag) void loadTags()
    if (sectionOpen.stash) void loadStashes()
  } catch (err) {
    error.value = err instanceof Error ? err.message : '读取 Git 状态失败'
    status.value = null
  } finally {
    loading.value = false
  }
}

function baseName(file: string): string {
  const parts = file.split(/[\\/]/)
  return parts[parts.length - 1] || file
}

function badgeClass(change: GitChange): string {
  if (change.status === 'staged') return 'is-staged'
  if (change.status === 'untracked') return 'is-untracked'
  return 'is-modified'
}

/**
 * 状态徽章文案：优先用后端给的单字母状态（stagedStatus / workingStatus，如 M / A / D），
 * 拿不到时按 status 兜底。中文 label（"已暂存"/"修改"）放在 title 里，避免窄抽屉里被撑宽。
 */
function badgeText(change: GitChange): string {
  const raw = (change.stagedStatus || change.workingStatus || '').trim()
  if (raw) return raw.charAt(0).toUpperCase()
  if (change.status === 'untracked') return 'U'
  if (change.status === 'staged') return 'A'
  return 'M'
}

/* ---------------- diff ---------------- */

const diffFile = ref('')
const diffText = ref('')
const diffLoading = ref(false)
const diffError = ref('')
/** 撤销请求进行中（块撤销与行撤销共用一把锁，避免并发写同一文件） */
const restoreBusy = ref(false)

/** 只有 git 仓库才能做 hunk 级撤销（后端走 `git apply --reverse`） */
const restorable = computed(() => !!status.value?.isRepo)

/** 拉取单文件 diff；撤销后需要就地重算，故从 openDiff 里拆出来 */
async function fetchDiff(file: string, staged: boolean): Promise<void> {
  if (!props.root) return
  diffLoading.value = true
  diffError.value = ''
  diffText.value = ''
  try {
    const res = await getGitDiff(props.root, file, staged)
    if (!res.success) {
      diffError.value = res.error || '读取 diff 失败'
      return
    }
    diffText.value = res.diff || ''
    if (!diffText.value.trim()) diffError.value = '该文件已无差异（可能已还原，或为二进制文件）'
  } catch (err) {
    diffError.value = err instanceof Error ? err.message : '读取 diff 失败'
  } finally {
    diffLoading.value = false
  }
}

async function openDiff(change: GitChange) {
  if (!props.root) return
  if (diffFile.value === change.file) {
    closeDiff()
    return
  }
  diffFile.value = change.file
  await fetchDiff(change.file, change.status === 'staged')
}

/**
 * 应用一段 patch（撤销块 / 撤销行统一入口）。
 * <p>后端 `POST /api/git/restore-hunks` 会补 `--- a/f` `+++ b/f` 头后执行
 * `git apply --reverse --unidiff-zero`；失败时把后端 error 原文透出，不吞错。</p>
 */
async function onRestorePatch(patch: string): Promise<void> {
  if (!props.root || !diffFile.value || restoreBusy.value || !patch) return
  const file = diffFile.value
  restoreBusy.value = true
  try {
    const res = await gitRestoreHunks(props.root, file, patch)
    if (!res.success) {
      toast.error('撤销失败', { description: res.error || res.output || '后端未返回失败原因' })
      return
    }
    toast.success('已撤销', { description: file })
    await refresh()
    // 撤销后差异变了：还有变更就重算 diff，彻底干净就收起面板
    const stillChanged = changes.value.find(item => item.file === file)
    if (stillChanged) await fetchDiff(file, stillChanged.status === 'staged')
    else closeDiff()
  } catch (err) {
    toast.error('撤销失败', { description: err instanceof Error ? err.message : '请稍后重试' })
  } finally {
    restoreBusy.value = false
  }
}

function closeDiff() {
  diffFile.value = ''
  diffText.value = ''
  diffError.value = ''
}

/* ---------------- 暂存 / 撤销 / 提交 ---------------- */

async function toggleStage(change: GitChange) {
  if (!props.root || anyBusy.value) return
  rowBusy.value = change.file
  try {
    const res =
      change.status === 'staged'
        ? await gitUnstage(props.root, [change.file])
        : await gitAdd(props.root, [change.file])
    if (actionError(change.status === 'staged' ? '取消暂存失败' : '暂存失败', res)) return
    closeDiff()
    await refresh()
  } catch (err) {
    toast.error('Git 操作失败', { description: err instanceof Error ? err.message : '请稍后重试' })
  } finally {
    rowBusy.value = ''
  }
}

async function stageAll() {
  if (!props.root || anyBusy.value) return
  changeBusy.value = 'stageAll'
  try {
    const res = await gitAdd(props.root, undefined, true)
    if (actionError('全部暂存失败', res)) return
    closeDiff()
    await refresh()
    toast.success('已全部暂存')
  } catch (err) {
    toast.error('全部暂存失败', { description: err instanceof Error ? err.message : '请稍后重试' })
  } finally {
    changeBusy.value = ''
  }
}

async function unstageAll() {
  if (!props.root || anyBusy.value) return
  changeBusy.value = 'unstageAll'
  try {
    const res = await gitUnstage(props.root, undefined, true)
    if (actionError('全部取消暂存失败', res)) return
    closeDiff()
    await refresh()
    toast.success('已全部取消暂存')
  } catch (err) {
    toast.error('全部取消暂存失败', { description: err instanceof Error ? err.message : '请稍后重试' })
  } finally {
    changeBusy.value = ''
  }
}

/** 撤销单个文件的改动：会丢内容，先确认 */
async function restoreFile(change: GitChange) {
  if (!props.root || anyBusy.value) return
  const ok = await confirm({
    title: '撤销文件改动',
    content: `将丢弃「${change.file}」尚未提交的修改，且无法恢复。`,
    okText: '撤销改动',
    type: 'danger'
  })
  if (!ok) return
  rowBusy.value = change.file
  try {
    const res = await gitRestore(props.root, change.file)
    if (actionError('撤销改动失败', res)) return
    closeDiff()
    await refresh()
    toast.success('已撤销改动', { description: change.file })
  } catch (err) {
    toast.error('撤销改动失败', { description: err instanceof Error ? err.message : '请稍后重试' })
  } finally {
    rowBusy.value = ''
  }
}

async function commit() {
  if (!props.root || commitBusy.value || !hasStaged.value) return
  commitBusy.value = true
  try {
    const message = commitMessage.value.trim() || 'chore: 通过 CodeCraft 提交'
    const res = await gitCommit(props.root, message)
    if (actionError('提交失败', res)) return
    commitMessage.value = ''
    closeDiff()
    await refresh()
    toast.success('已提交', { description: message })
  } catch (err) {
    toast.error('提交失败', { description: err instanceof Error ? err.message : '请稍后重试' })
  } finally {
    commitBusy.value = false
  }
}

/* ---------------- 远程同步 ---------------- */

async function doSync(kind: 'pull' | 'push' | 'fetch') {
  if (!props.root || anyBusy.value) return
  syncBusy.value = kind
  try {
    const label = kind === 'pull' ? '拉取' : kind === 'push' ? '推送' : '获取'
    const res: GitOperationResult =
      kind === 'pull'
        ? await gitPull(props.root)
        : kind === 'push'
          ? await gitPush(props.root)
          : await gitFetch(props.root)
    if (actionError(`${label}失败`, res)) return
    await refresh()
    toast.success(`${label}完成`, { description: (res.output || '').trim().slice(0, 200) || undefined })
  } catch (err) {
    toast.error('远程操作失败', { description: err instanceof Error ? err.message : '请稍后重试' })
  } finally {
    syncBusy.value = ''
  }
}

/* ---------------- 折叠区 ---------------- */

type SectionKey = 'stash' | 'branch' | 'tag'
const sectionOpen = reactive<Record<SectionKey, boolean>>({ stash: false, branch: false, tag: false })

function toggleSection(key: SectionKey) {
  // 分支入口在顶栏，其余在折叠区；统一走这里，展开时才拉数据
  if (key === 'branch' && !status.value?.isRepo) return
  sectionOpen[key] = !sectionOpen[key]
  if (!sectionOpen[key]) return
  if (key === 'stash') void loadStashes()
  if (key === 'branch') void loadBranches()
  if (key === 'tag') void loadTags()
}

/* ---------------- 储藏栈 ---------------- */

const stashLines = ref<string[]>([])
const stashLoading = ref(false)

async function loadStashes() {
  if (!props.root) return
  stashLoading.value = true
  try {
    const res = await gitStashList(props.root)
    stashLines.value = (res.output || '')
      .split('\n')
      .map(line => line.trim())
      .filter(Boolean)
  } catch {
    stashLines.value = []
  } finally {
    stashLoading.value = false
  }
}

/** 从 `stash@{2}: WIP on …` 里取序号；取不到时按最新（0）处理 */
function stashIndexOf(line: string): number {
  const matched = line.match(/stash@\{(\d+)\}/)
  return matched ? Number(matched[1]) : 0
}

async function stashAll() {
  if (!props.root || anyBusy.value || !hasChanges.value) return
  changeBusy.value = 'stash'
  try {
    const res = await gitStashSave(props.root, commitMessage.value.trim() || undefined)
    if (actionError('储藏失败', res)) return
    closeDiff()
    await refresh()
    if (sectionOpen.stash) await loadStashes()
    toast.success('已储藏当前改动')
  } catch (err) {
    toast.error('储藏失败', { description: err instanceof Error ? err.message : '请稍后重试' })
  } finally {
    changeBusy.value = ''
  }
}

async function stashPop(line: string) {
  if (!props.root || anyBusy.value) return
  stashBusy.value = true
  try {
    const res = await gitStashPop(props.root, stashIndexOf(line))
    if (actionError('弹出储藏失败', res)) return
    await refresh()
    await loadStashes()
    toast.success('已弹出储藏')
  } catch (err) {
    toast.error('弹出储藏失败', { description: err instanceof Error ? err.message : '请稍后重试' })
  } finally {
    stashBusy.value = false
  }
}

async function stashDrop(line: string) {
  if (!props.root || anyBusy.value) return
  const ok = await confirm({
    title: '删除储藏',
    content: '删除后无法恢复，确定要丢弃这条储藏吗？',
    okText: '删除',
    type: 'danger'
  })
  if (!ok) return
  stashBusy.value = true
  try {
    const res = await gitStashDrop(props.root, stashIndexOf(line))
    if (actionError('删除储藏失败', res)) return
    await loadStashes()
    toast.success('已删除储藏')
  } catch (err) {
    toast.error('删除储藏失败', { description: err instanceof Error ? err.message : '请稍后重试' })
  } finally {
    stashBusy.value = false
  }
}

/* ---------------- 分支 ---------------- */

const branchList = ref<string[]>([])
const currentBranch = ref('')
const branchLoading = ref(false)
const newBranchName = ref('')

async function loadBranches() {
  if (!props.root) return
  branchLoading.value = true
  try {
    const res = await gitBranches(props.root)
    branchList.value = (res.branches ?? []).map(b => b.replace(/^\*?\s*/, '').trim()).filter(Boolean)
    currentBranch.value = (res.current || status.value?.branch || '').replace(/^\*?\s*/, '').trim()
  } catch {
    branchList.value = []
  } finally {
    branchLoading.value = false
  }
}

async function branchCreate() {
  const name = newBranchName.value.trim()
  if (!props.root || anyBusy.value || !name) return
  branchBusy.value = true
  try {
    const res = await gitBranchCreate(props.root, name, true)
    if (actionError('新建分支失败', res)) return
    newBranchName.value = ''
    await refresh()
    await loadBranches()
    toast.success('已新建并切换到', { description: name })
  } catch (err) {
    toast.error('新建分支失败', { description: err instanceof Error ? err.message : '请稍后重试' })
  } finally {
    branchBusy.value = false
  }
}

async function branchSwitch(name: string) {
  if (!props.root || anyBusy.value) return
  branchBusy.value = true
  try {
    const res = await gitBranchSwitch(props.root, name)
    if (actionError('切换分支失败', res)) return
    closeDiff()
    await refresh()
    await loadBranches()
    toast.success('已切换到', { description: name })
  } catch (err) {
    toast.error('切换分支失败', { description: err instanceof Error ? err.message : '请稍后重试' })
  } finally {
    branchBusy.value = false
  }
}

async function branchMerge(name: string) {
  if (!props.root || anyBusy.value) return
  const ok = await confirm({
    title: '合并分支',
    content: `把「${name}」合并到当前分支「${currentBranch.value || 'HEAD'}」，可能产生冲突。`,
    okText: '合并'
  })
  if (!ok) return
  branchBusy.value = true
  try {
    const res = await gitMerge(props.root, name)
    if (actionError('合并分支失败', res)) return
    closeDiff()
    await refresh()
    await loadBranches()
    toast.success('已合并', { description: `${name} → ${currentBranch.value || 'HEAD'}` })
  } catch (err) {
    toast.error('合并分支失败', { description: err instanceof Error ? err.message : '请稍后重试' })
  } finally {
    branchBusy.value = false
  }
}

async function branchDelete(name: string) {
  if (!props.root || anyBusy.value) return
  const ok = await confirm({
    title: '删除分支',
    content: `确定删除分支「${name}」？未合并的提交会一并丢失。`,
    okText: '删除',
    type: 'danger'
  })
  if (!ok) return
  branchBusy.value = true
  try {
    const res = await gitBranchDelete(props.root, name)
    if (actionError('删除分支失败', res)) return
    await loadBranches()
    toast.success('已删除分支', { description: name })
  } catch (err) {
    toast.error('删除分支失败', { description: err instanceof Error ? err.message : '请稍后重试' })
  } finally {
    branchBusy.value = false
  }
}

/* ---------------- 标签 ---------------- */

const tagList = ref<string[]>([])
const tagLoading = ref(false)
const newTagName = ref('')

async function loadTags() {
  if (!props.root) return
  tagLoading.value = true
  try {
    const res = await gitTags(props.root)
    tagList.value = res.tags ?? []
  } catch {
    tagList.value = []
  } finally {
    tagLoading.value = false
  }
}

async function tagCreate() {
  const name = newTagName.value.trim()
  if (!props.root || anyBusy.value || !name) return
  tagBusy.value = true
  try {
    const res = await gitTagCreate(props.root, name)
    if (actionError('创建标签失败', res)) return
    newTagName.value = ''
    await loadTags()
    toast.success('已创建标签', { description: name })
  } catch (err) {
    toast.error('创建标签失败', { description: err instanceof Error ? err.message : '请稍后重试' })
  } finally {
    tagBusy.value = false
  }
}

async function tagPush(name: string) {
  if (!props.root || anyBusy.value) return
  tagBusy.value = true
  try {
    const res = await gitTagPush(props.root, name)
    if (actionError('推送标签失败', res)) return
    toast.success('已推送标签', { description: name })
  } catch (err) {
    toast.error('推送标签失败', { description: err instanceof Error ? err.message : '请稍后重试' })
  } finally {
    tagBusy.value = false
  }
}

async function tagDelete(name: string) {
  if (!props.root || anyBusy.value) return
  const ok = await confirm({
    title: '删除标签',
    content: `仅删除本地标签「${name}」，远端标签不受影响。`,
    okText: '删除',
    type: 'danger'
  })
  if (!ok) return
  tagBusy.value = true
  try {
    const res = await gitTagDelete(props.root, name)
    if (actionError('删除标签失败', res)) return
    await loadTags()
    toast.success('已删除标签', { description: name })
  } catch (err) {
    toast.error('删除标签失败', { description: err instanceof Error ? err.message : '请稍后重试' })
  } finally {
    tagBusy.value = false
  }
}

/* ---------------- 生命周期 ---------------- */

/** 首次进入或切换工作目录时拉一次状态，并清掉上一目录的残留 */
watch(
  () => props.root,
  () => {
    closeDiff()
    commitMessage.value = ''
    stashLines.value = []
    branchList.value = []
    tagList.value = []
    newBranchName.value = ''
    newTagName.value = ''
    void refresh()
  },
  { immediate: true }
)
</script>
