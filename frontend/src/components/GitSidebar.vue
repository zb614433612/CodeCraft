<template>
  <div class="git-sidebar">
    <!-- 1. 仓库状态头 -->
    <div class="git-status-header">
      <span class="branch-label" v-if="isRepo">
        <BranchesOutlined /> {{ branch || 'HEAD' }}
      </span>
      <span v-else class="branch-label no-repo">不是 git 仓库</span>
      <div class="header-actions">
        <button
          class="icon-btn"
          :class="{ active: branchesExpanded }"
          :disabled="!isRepo"
          title="分支面板"
          @click="toggleBranchesPanel"
        >
          <BranchesOutlined />
        </button>
        <button class="icon-btn" :disabled="!isRepo || loading" title="刷新" @click="refreshStatus">
          <ReloadOutlined />
        </button>
      </div>
    </div>

    <!-- 2. 远程同步行 -->
    <div class="sync-row" v-if="isRepo">
      <button class="sync-btn" :disabled="!!syncBusy" @click="handlePull">
        <CloudDownloadOutlined /> {{ syncBusy === 'pull' ? '拉取中…' : '拉取' }}
      </button>
      <button class="sync-btn" :disabled="!!syncBusy" @click="handlePush">
        <CloudUploadOutlined /> {{ syncBusy === 'push' ? '推送中…' : '推送' }}
      </button>
      <button class="sync-btn" :disabled="!!syncBusy" @click="handleFetch">
        <SyncOutlined :spin="syncBusy === 'fetch'" /> {{ syncBusy === 'fetch' ? '获取中…' : '获取' }}
      </button>
    </div>

    <div class="changes-section" v-if="isRepo">
      <!-- 3. 变更工具栏 -->
      <div class="changes-toolbar">
        <button class="toolbar-btn" :disabled="!!changeBusy || changes.length === 0" @click="stageAll">
          全部暂存
        </button>
        <button class="toolbar-btn" :disabled="!!changeBusy || changes.length === 0" @click="unstageAll">
          全部取消暂存
        </button>
        <button class="toolbar-btn" :disabled="!!changeBusy || changes.length === 0" @click="stashAll">
          {{ changeBusy === 'stash' ? '储藏中…' : '储藏变更' }}
        </button>
      </div>

      <!-- 4. 变更列表 -->
      <div class="change-list">
        <div v-if="loading" class="loading-text">加载中...</div>
        <div v-else-if="changes.length === 0" class="empty-text">工作区干净，无变更</div>
        <div v-for="change in changes" :key="change.file" class="change-item">
          <label class="change-checkbox">
            <input
              type="checkbox"
              :checked="change.status === 'staged'"
              :disabled="toggleBusy === change.file"
              @change="toggleStage(change, $event)"
            />
          </label>
          <span :class="['change-status', change.status]">{{ statusLabel(change) }}</span>
          <span class="change-file" @dblclick="handleDblClick(change)" :title="change.file">
            {{ fileDisplayName(change.file) }}
          </span>
          <button
            class="row-action"
            :disabled="rowBusy === change.file"
            title="撤销该文件的改动"
            @click.stop="handleRestoreFile(change.file)"
          >
            <UndoOutlined />
          </button>
        </div>
      </div>

      <!-- 5. 折叠区：储藏记录 / 分支 / 标签 -->
      <div class="collapse-area">
        <!-- 储藏记录 -->
        <div class="collapse-section">
          <div class="collapse-header" @click="toggleStash">
            <span class="collapse-arrow" :class="{ open: stashExpanded }">▸</span>
            <InboxOutlined class="collapse-icon" />
            <span class="collapse-title">储藏记录</span>
            <span class="collapse-count" v-if="stashExpanded">({{ stashLines.length }})</span>
          </div>
          <div class="collapse-body" v-show="stashExpanded">
            <div v-if="stashLoading" class="empty-text small">加载中...</div>
            <div v-else-if="stashLines.length === 0" class="empty-text small">没有储藏记录</div>
            <template v-else>
              <div v-for="(line, idx) in stashLines" :key="stashKey(line, idx)" class="list-row">
                <span class="list-text" :title="line">{{ line }}</span>
                <span class="row-actions">
                  <button class="mini-btn" :disabled="!!stashBusy" @click="handleStashPop(line)">弹出</button>
                  <button class="mini-btn danger" :disabled="!!stashBusy" @click="handleStashDrop(line)">删除</button>
                </span>
              </div>
            </template>
          </div>
        </div>

        <!-- 分支 -->
        <div class="collapse-section">
          <div class="collapse-header" @click="toggleBranches">
            <span class="collapse-arrow" :class="{ open: branchesExpanded }">▸</span>
            <SwapOutlined class="collapse-icon" />
            <span class="collapse-title">分支</span>
            <span class="collapse-count" v-if="branchesExpanded">({{ branchList.length }})</span>
          </div>
          <div class="collapse-body" v-show="branchesExpanded">
            <div class="create-row">
              <input
                v-model="newBranchName"
                class="mini-input"
                placeholder="新分支名"
                @keyup.enter="handleBranchCreate"
              />
              <button
                class="mini-btn"
                :disabled="!newBranchName.trim() || !!branchBusy"
                @click="handleBranchCreate"
              >
                <PlusOutlined /> 新建并切换
              </button>
            </div>
            <div v-if="branchesLoading" class="empty-text small">加载中...</div>
            <div v-else-if="branchList.length === 0" class="empty-text small">暂无分支</div>
            <template v-else>
              <div
                v-for="b in branchList"
                :key="b"
                :class="['list-row', { current: b === currentBranch }]"
              >
                <span class="list-text" :title="b">{{ b }}</span>
                <span class="row-actions" v-if="b !== currentBranch">
                  <button class="mini-btn" :disabled="!!branchBusy" @click="handleBranchSwitch(b)">切换</button>
                  <button class="mini-btn" :disabled="!!branchBusy" @click="handleBranchMerge(b)">合并</button>
                  <button class="mini-btn danger" :disabled="!!branchBusy" @click="handleBranchDelete(b)">删除</button>
                </span>
              </div>
            </template>
          </div>
        </div>

        <!-- 标签 -->
        <div class="collapse-section">
          <div class="collapse-header" @click="toggleTags">
            <span class="collapse-arrow" :class="{ open: tagsExpanded }">▸</span>
            <TagsOutlined class="collapse-icon" />
            <span class="collapse-title">标签</span>
            <span class="collapse-count" v-if="tagsExpanded">({{ tagList.length }})</span>
          </div>
          <div class="collapse-body" v-show="tagsExpanded">
            <div class="create-row">
              <input
                v-model="newTagName"
                class="mini-input"
                placeholder="标签名"
                @keyup.enter="handleTagCreate"
              />
              <button class="mini-btn" :disabled="!newTagName.trim() || !!tagBusy" @click="handleTagCreate">
                <PlusOutlined /> 创建
              </button>
            </div>
            <div v-if="tagsLoading" class="empty-text small">加载中...</div>
            <div v-else-if="tagList.length === 0" class="empty-text small">暂无标签</div>
            <template v-else>
              <div v-for="t in tagList" :key="t" class="list-row">
                <span class="list-text" :title="t">{{ t }}</span>
                <span class="row-actions">
                  <button class="mini-btn" :disabled="!!tagBusy" @click="handleTagPush(t)">推送</button>
                  <button class="mini-btn danger" :disabled="!!tagBusy" @click="handleTagDelete(t)">删除</button>
                </span>
              </div>
            </template>
          </div>
        </div>
      </div>

      <!-- 6. 提交区 -->
      <div class="commit-section">
        <textarea
          v-model="commitMessage"
          placeholder="提交信息..."
          :rows="3"
          class="commit-input"
        ></textarea>
        <div class="commit-actions">
          <span class="commit-hint" v-if="stagedCount > 0">{{ stagedCount }} 个文件已暂存</span>
          <button
            class="commit-btn"
            @click="handleCommit"
            :disabled="!commitMessage.trim() || stagedCount === 0 || !!commitBusy"
          >{{ commitBusy ? '提交中…' : '提交' }}</button>
        </div>
      </div>
    </div>
  </div>

</template>

<script setup lang="ts">
import { ref, computed, onMounted, watch } from 'vue'
import { message } from 'ant-design-vue'
import {
  BranchesOutlined,
  ReloadOutlined,
  UndoOutlined,
  CloudDownloadOutlined,
  CloudUploadOutlined,
  SyncOutlined,
  InboxOutlined,
  SwapOutlined,
  TagsOutlined,
  PlusOutlined
} from '@ant-design/icons-vue'
import {
  getGitStatus,
  gitAdd,
  gitCommit,
  gitRestore,
  gitUnstage,
  gitPull,
  gitPush,
  gitFetch,
  gitStashSave,
  gitStashList,
  gitStashPop,
  gitStashDrop,
  gitMerge,
  gitBranches,
  gitBranchSwitch,
  gitBranchCreate,
  gitBranchDelete,
  gitTags,
  gitTagCreate,
  gitTagDelete,
  gitTagPush,
  type GitChange
} from '@/api/git'

const emit = defineEmits<{
  fileDblclick: [filePath: string, projectRoot: string]
}>()

const props = defineProps<{ projectRoot: string }>()

/* ==================== 基础状态 ==================== */
const isRepo = ref(false)
const branch = ref('')
const changes = ref<GitChange[]>([])
const loading = ref(false)
const commitMessage = ref('')

/** 各异步操作的防重入标记（空串表示空闲） */
const syncBusy = ref<'' | 'pull' | 'push' | 'fetch'>('')
const changeBusy = ref<'' | 'add' | 'unstage' | 'stash'>('')
const commitBusy = ref(false)
const toggleBusy = ref('')
const rowBusy = ref('')

/** stagedCount 纯基于 changes 中 status==='staged' 的数量 */
const stagedCount = computed(() => changes.value.filter(c => c.status === 'staged').length)

/* ==================== 储藏记录 ==================== */
const stashExpanded = ref(false)
const stashLoading = ref(false)
const stashLines = ref<string[]>([])
const stashBusy = ref(false)

/* ==================== 分支 ==================== */
const branchesExpanded = ref(false)
const branchesLoading = ref(false)
const branchList = ref<string[]>([])
const currentBranch = ref('')
const branchBusy = ref(false)
const newBranchName = ref('')

/* ==================== 标签 ==================== */
const tagsExpanded = ref(false)
const tagsLoading = ref(false)
const tagList = ref<string[]>([])
const tagBusy = ref(false)
const newTagName = ref('')

/* ==================== 工具函数 ==================== */

/** 错误文本截断（最多 200 字符） */
const trimError = (e: unknown): string => {
  const msg = e instanceof Error ? e.message : String(e ?? '')
  return (msg || '未知错误').slice(0, 200)
}

/** 统一错误提示 */
const showError = (prefix: string, detail?: string): void => {
  const text = detail ? `${prefix}：${detail}` : prefix
  message.error(text.slice(0, 200))
}

const statusLabel = (change: GitChange) => {
  if (change.status === 'untracked') return '?'
  if (change.status === 'staged') return change.stagedStatus || 'A'
  return change.workingStatus || 'M'
}

/** 提取文件名最后一段显示（目录/目录/文件名 → 文件名） */
const fileDisplayName = (fullPath: string): string => {
  const idx = Math.max(fullPath.lastIndexOf('/'), fullPath.lastIndexOf('\\'))
  return idx >= 0 ? fullPath.substring(idx + 1) : fullPath
}

/** 从 `stash@{n}: ...` 文本中提取 index */
const stashIndexOf = (line: string): number | undefined => {
  const m = line.match(/stash@\{(\d+)\}/)
  return m ? Number(m[1]) : undefined
}

const stashKey = (line: string, idx: number): string => `${idx}-${line}`

/* ==================== 状态刷新 ==================== */
const refreshStatus = async () => {
  if (!props.projectRoot) return
  loading.value = true
  // 先清空旧状态，防止 API 失败时残留上一次的数据
  isRepo.value = false
  branch.value = ''
  changes.value = []
  try {
    const status = await getGitStatus(props.projectRoot)
    isRepo.value = status.isRepo
    branch.value = status.branch || ''
    changes.value = status.changes || []
  } catch (e: any) {
    console.error('获取 git 状态失败:', e)
    // catch 中已重置为默认值，无需额外操作
  } finally {
    loading.value = false
  }
}

/* ==================== 远程同步 ==================== */
const handlePull = async () => {
  if (syncBusy.value) return
  syncBusy.value = 'pull'
  try {
    const result = await gitPull(props.projectRoot)
    if (result.success) {
      message.success('拉取完成')
      await refreshStatus()
    } else {
      showError('拉取失败', result.error)
    }
  } catch (e) {
    showError('拉取失败', trimError(e))
  } finally {
    syncBusy.value = ''
  }
}

const handlePush = async () => {
  if (syncBusy.value) return
  syncBusy.value = 'push'
  try {
    // setUpstream=true：新分支首次推送时自动建立跟踪关系
    const result = await gitPush(props.projectRoot, undefined, undefined, true)
    if (result.success) {
      message.success('推送完成')
      await refreshStatus()
    } else {
      showError('推送失败', result.error)
    }
  } catch (e) {
    showError('推送失败', trimError(e))
  } finally {
    syncBusy.value = ''
  }
}

const handleFetch = async () => {
  if (syncBusy.value) return
  syncBusy.value = 'fetch'
  try {
    const result = await gitFetch(props.projectRoot)
    if (result.success) {
      message.success('获取完成')
      await refreshStatus()
    } else {
      showError('获取失败', result.error)
    }
  } catch (e) {
    showError('获取失败', trimError(e))
  } finally {
    syncBusy.value = ''
  }
}

/* ==================== 变更列表操作 ==================== */
const handleDblClick = (change: GitChange) => {
  emit('fileDblclick', change.file, props.projectRoot)
}

/** checkbox 切换暂存状态：直接以真实状态为准 */
const toggleStage = async (change: GitChange, ev: Event) => {
  if (toggleBusy.value) return
  const input = ev.target as HTMLInputElement
  const wasStaged = change.status === 'staged'
  toggleBusy.value = change.file
  try {
    const result = wasStaged
      ? await gitUnstage(props.projectRoot, [change.file])
      : await gitAdd(props.projectRoot, [change.file])
    if (result.success) {
      await refreshStatus()
    } else {
      // 失败时把 checkbox 视觉状态还原为真实状态
      input.checked = wasStaged
      showError(wasStaged ? '取消暂存失败' : '暂存失败', result.error)
    }
  } catch (e) {
    input.checked = wasStaged
    showError(wasStaged ? '取消暂存失败' : '暂存失败', trimError(e))
  } finally {
    toggleBusy.value = ''
  }
}

const stageAll = async () => {
  if (changeBusy.value) return
  changeBusy.value = 'add'
  try {
    const result = await gitAdd(props.projectRoot, undefined, true)
    if (result.success) {
      message.success('已暂存全部变更')
      await refreshStatus()
    } else {
      showError('暂存失败', result.error)
    }
  } catch (e) {
    showError('暂存失败', trimError(e))
  } finally {
    changeBusy.value = ''
  }
}

const unstageAll = async () => {
  if (changeBusy.value) return
  changeBusy.value = 'unstage'
  try {
    const result = await gitUnstage(props.projectRoot, undefined, true)
    if (result.success) {
      message.success('已取消全部暂存')
      await refreshStatus()
    } else {
      showError('取消暂存失败', result.error)
    }
  } catch (e) {
    showError('取消暂存失败', trimError(e))
  } finally {
    changeBusy.value = ''
  }
}

const stashAll = async () => {
  if (changeBusy.value) return
  changeBusy.value = 'stash'
  try {
    const result = await gitStashSave(props.projectRoot)
    if (result.success) {
      message.success('已储藏当前变更')
      await refreshStatus()
      // 储藏区数据刷新（展开时重载，收起时清空缓存以便下次展开拉取）
      if (stashExpanded.value) {
        await reloadStash()
      } else {
        stashLines.value = []
      }
    } else {
      showError('储藏失败', result.error)
    }
  } catch (e) {
    showError('储藏失败', trimError(e))
  } finally {
    changeBusy.value = ''
  }
}

/** 还原单个文件的改动 */
const handleRestoreFile = async (file: string) => {
  if (rowBusy.value) return
  if (!window.confirm(`确定撤销「${file}」的改动吗？此操作不可恢复。`)) return
  rowBusy.value = file
  try {
    const result = await gitRestore(props.projectRoot, file)
    if (result.success) {
      message.success('已撤销: ' + file)
      await refreshStatus()
    } else {
      showError('撤销失败', result.error)
    }
  } catch (e) {
    showError('撤销失败', trimError(e))
  } finally {
    rowBusy.value = ''
  }
}

const handleCommit = async () => {
  const msg = commitMessage.value.trim()
  if (!msg || commitBusy.value) return
  commitBusy.value = true
  try {
    const result = await gitCommit(props.projectRoot, msg)
    if (result.success) {
      message.success('提交成功')
      commitMessage.value = ''
      await refreshStatus()
    } else {
      showError('提交失败', result.error)
    }
  } catch (e) {
    showError('提交失败', trimError(e))
  } finally {
    commitBusy.value = false
  }
}

/* ==================== 储藏记录 ==================== */
const reloadStash = async () => {
  stashLoading.value = true
  try {
    const result = await gitStashList(props.projectRoot)
    if (result.success) {
      stashLines.value = (result.output || '')
        .split('\n')
        .map(l => l.trim())
        .filter(Boolean)
    } else {
      stashLines.value = []
      showError('获取储藏列表失败', result.error)
    }
  } catch (e) {
    stashLines.value = []
    showError('获取储藏列表失败', trimError(e))
  } finally {
    stashLoading.value = false
  }
}

const toggleStash = async () => {
  stashExpanded.value = !stashExpanded.value
  if (stashExpanded.value) {
    await reloadStash()
  }
}

const handleStashPop = async (line: string) => {
  if (stashBusy.value) return
  stashBusy.value = true
  try {
    const result = await gitStashPop(props.projectRoot, stashIndexOf(line))
    if (result.success) {
      message.success('已弹出储藏')
      await reloadStash()
      await refreshStatus()
    } else {
      showError('弹出储藏失败', result.error)
    }
  } catch (e) {
    showError('弹出储藏失败', trimError(e))
  } finally {
    stashBusy.value = false
  }
}

const handleStashDrop = async (line: string) => {
  if (stashBusy.value) return
  if (!window.confirm(`确定删除该储藏记录吗？\n${line}`)) return
  stashBusy.value = true
  try {
    const result = await gitStashDrop(props.projectRoot, stashIndexOf(line))
    if (result.success) {
      message.success('已删除储藏')
      await reloadStash()
    } else {
      showError('删除储藏失败', result.error)
    }
  } catch (e) {
    showError('删除储藏失败', trimError(e))
  } finally {
    stashBusy.value = false
  }
}

/* ==================== 分支 ==================== */
const reloadBranches = async () => {
  branchesLoading.value = true
  try {
    const result = await gitBranches(props.projectRoot)
    if (result.success) {
      branchList.value = result.branches || []
      currentBranch.value = result.current || ''
    } else {
      branchList.value = []
      showError('获取分支列表失败', result.error)
    }
  } catch (e) {
    branchList.value = []
    showError('获取分支列表失败', trimError(e))
  } finally {
    branchesLoading.value = false
  }
}

const toggleBranches = async () => {
  branchesExpanded.value = !branchesExpanded.value
  if (branchesExpanded.value) {
    await reloadBranches()
  }
}

/** 状态头的分支面板开关按钮 */
const toggleBranchesPanel = async () => {
  if (!isRepo.value) return
  await toggleBranches()
}

const handleBranchCreate = async () => {
  const name = newBranchName.value.trim()
  if (!name || branchBusy.value) return
  branchBusy.value = true
  try {
    const result = await gitBranchCreate(props.projectRoot, name, true)
    if (result.success) {
      message.success(`已创建并切换到分支 ${name}`)
      newBranchName.value = ''
      await reloadBranches()
      await refreshStatus()
    } else {
      showError('创建分支失败', result.error)
    }
  } catch (e) {
    showError('创建分支失败', trimError(e))
  } finally {
    branchBusy.value = false
  }
}

const handleBranchSwitch = async (name: string) => {
  if (branchBusy.value) return
  branchBusy.value = true
  try {
    const result = await gitBranchSwitch(props.projectRoot, name)
    if (result.success) {
      message.success(`已切换到分支 ${name}`)
      await reloadBranches()
      await refreshStatus()
    } else {
      showError('切换分支失败', result.error)
    }
  } catch (e) {
    showError('切换分支失败', trimError(e))
  } finally {
    branchBusy.value = false
  }
}

const handleBranchMerge = async (name: string) => {
  if (branchBusy.value) return
  if (!window.confirm(`将把「${name}」合并到当前分支「${currentBranch.value || 'HEAD'}」，确定继续吗？`)) return
  branchBusy.value = true
  try {
    const result = await gitMerge(props.projectRoot, name)
    if (result.success) {
      message.success(`已合并 ${name}`)
      await reloadBranches()
      await refreshStatus()
    } else {
      showError('合并分支失败', result.error)
    }
  } catch (e) {
    showError('合并分支失败', trimError(e))
  } finally {
    branchBusy.value = false
  }
}

const handleBranchDelete = async (name: string) => {
  if (branchBusy.value) return
  if (!window.confirm(`确定删除分支「${name}」吗？`)) return
  branchBusy.value = true
  try {
    const result = await gitBranchDelete(props.projectRoot, name)
    if (result.success) {
      message.success(`已删除分支 ${name}`)
      await reloadBranches()
      await refreshStatus()
    } else {
      showError('删除分支失败', result.error)
    }
  } catch (e) {
    showError('删除分支失败', trimError(e))
  } finally {
    branchBusy.value = false
  }
}

/* ==================== 标签 ==================== */
const reloadTags = async () => {
  tagsLoading.value = true
  try {
    const result = await gitTags(props.projectRoot)
    if (result.success) {
      tagList.value = result.tags || []
    } else {
      tagList.value = []
      showError('获取标签列表失败', result.error)
    }
  } catch (e) {
    tagList.value = []
    showError('获取标签列表失败', trimError(e))
  } finally {
    tagsLoading.value = false
  }
}

const toggleTags = async () => {
  tagsExpanded.value = !tagsExpanded.value
  if (tagsExpanded.value) {
    await reloadTags()
  }
}

const handleTagCreate = async () => {
  const name = newTagName.value.trim()
  if (!name || tagBusy.value) return
  tagBusy.value = true
  try {
    const result = await gitTagCreate(props.projectRoot, name)
    if (result.success) {
      message.success(`已创建标签 ${name}`)
      newTagName.value = ''
      await reloadTags()
    } else {
      showError('创建标签失败', result.error)
    }
  } catch (e) {
    showError('创建标签失败', trimError(e))
  } finally {
    tagBusy.value = false
  }
}

const handleTagPush = async (name: string) => {
  if (tagBusy.value) return
  tagBusy.value = true
  try {
    const result = await gitTagPush(props.projectRoot, name)
    if (result.success) {
      message.success(`已推送标签 ${name}`)
    } else {
      showError('推送标签失败', result.error)
    }
  } catch (e) {
    showError('推送标签失败', trimError(e))
  } finally {
    tagBusy.value = false
  }
}

const handleTagDelete = async (name: string) => {
  if (tagBusy.value) return
  if (!window.confirm(`确定删除标签「${name}」吗？`)) return
  tagBusy.value = true
  try {
    const result = await gitTagDelete(props.projectRoot, name)
    if (result.success) {
      message.success(`已删除标签 ${name}`)
      await reloadTags()
    } else {
      showError('删除标签失败', result.error)
    }
  } catch (e) {
    showError('删除标签失败', trimError(e))
  } finally {
    tagBusy.value = false
  }
}

/* ==================== 生命周期 ==================== */
onMounted(() => {
  if (props.projectRoot) {
    refreshStatus()
  }
})

// 监听 projectRoot 变化
watch(() => props.projectRoot, (val) => {
  if (val) {
    refreshStatus()
  }
})
</script>

<style scoped>
.git-sidebar {
  height: 100%;
  display: flex;
  flex-direction: column;
  font-size: 12px;
  background: #fafbfc;
  overflow: hidden;
}

/* 仓库状态头 */
.git-status-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 6px 10px;
  border-bottom: 1px solid #eef2f7;
  background: #f5f7fa;
  flex-shrink: 0;
}

.branch-label {
  font-weight: 600;
  font-size: 12px;
  color: #1a202c;
  display: flex;
  align-items: center;
  gap: 4px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.branch-label.no-repo { color: #999; font-weight: normal; }

.header-actions {
  display: flex;
  align-items: center;
  gap: 2px;
  flex-shrink: 0;
}

.icon-btn {
  border: none;
  background: transparent;
  cursor: pointer;
  color: #8c8c8c;
  font-size: 14px;
  padding: 2px;
  display: flex;
  border-radius: 4px;
}
.icon-btn:hover:not(:disabled) { color: #1677ff; }
.icon-btn.active { color: #1677ff; background: #e6f4ff; }
.icon-btn:disabled { opacity: 0.4; cursor: not-allowed; }

/* 远程同步行 */
.sync-row {
  display: flex;
  gap: 4px;
  padding: 4px 10px;
  border-bottom: 1px solid #eef2f7;
  flex-shrink: 0;
}

.sync-btn {
  flex: 1;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 3px;
  padding: 2px 8px;
  border: 1px solid #d9d9d9;
  border-radius: 4px;
  font-size: 11px;
  cursor: pointer;
  background: white;
  color: #333;
  font-family: inherit;
}
.sync-btn:hover:not(:disabled) { border-color: #1677ff; color: #1677ff; }
.sync-btn:disabled { opacity: 0.55; cursor: not-allowed; }

/* 变更列表 */
.changes-section {
  flex: 1;
  min-height: 0;
  display: flex;
  flex-direction: column;
  overflow: hidden;
}

.changes-toolbar {
  display: flex;
  gap: 4px;
  padding: 4px 10px;
  border-bottom: 1px solid #eef2f7;
  flex-shrink: 0;
  flex-wrap: wrap;
}

.toolbar-btn {
  padding: 2px 8px;
  border: 1px solid #d9d9d9;
  border-radius: 4px;
  font-size: 11px;
  cursor: pointer;
  background: white;
  font-family: inherit;
}
.toolbar-btn:hover:not(:disabled) { border-color: #1677ff; color: #1677ff; }
.toolbar-btn:disabled { opacity: 0.5; cursor: not-allowed; }

.change-list {
  flex: 1 1 auto;
  min-height: 48px;
  overflow-y: auto;
  padding: 4px 0;
}

.loading-text, .empty-text {
  padding: 20px 10px;
  text-align: center;
  color: #8c8c8c;
}
.empty-text.small { padding: 8px 10px; font-size: 11px; }

.change-item {
  display: flex;
  align-items: center;
  gap: 4px;
  padding: 3px 10px;
  transition: background 0.1s;
}
.change-item:hover { background: #f0f5ff; }

.change-checkbox {
  display: flex;
  align-items: center;
}
.change-checkbox input { margin: 0; cursor: pointer; }

.change-status {
  font-weight: 700;
  font-size: 10px;
  width: 16px;
  text-align: center;
  flex-shrink: 0;
}
.change-status.modified { color: #faad14; }
.change-status.staged { color: #52c41a; }
.change-status.untracked { color: #8c8c8c; }

.change-file {
  flex: 1;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-size: 11px;
  color: #333;
  text-align: left;
}

/* 行内还原图标（hover 显示） */
.row-action {
  border: none;
  background: transparent;
  color: #8c8c8c;
  cursor: pointer;
  padding: 0 2px;
  font-size: 12px;
  display: flex;
  align-items: center;
  opacity: 0;
  transition: opacity 0.1s;
  flex-shrink: 0;
}
.change-item:hover .row-action { opacity: 1; }
.row-action:hover:not(:disabled) { color: #1677ff; }
.row-action:disabled { opacity: 0.4; cursor: not-allowed; }

/* 折叠区 */
.collapse-area {
  flex-shrink: 0;
  max-height: 45%;
  overflow-y: auto;
  border-top: 1px solid #eef2f7;
}

.collapse-section { border-bottom: 1px solid #eef2f7; }

.collapse-header {
  display: flex;
  align-items: center;
  gap: 4px;
  padding: 4px 10px;
  cursor: pointer;
  user-select: none;
  font-size: 11px;
  color: #4a5568;
}
.collapse-header:hover { background: #f0f5ff; }

.collapse-arrow {
  font-size: 9px;
  color: #8c8c8c;
  transition: transform 0.15s;
  display: inline-block;
}
.collapse-arrow.open { transform: rotate(90deg); }

.collapse-icon { font-size: 12px; color: #8c8c8c; }
.collapse-title { font-weight: 600; }
.collapse-count { color: #8c8c8c; }

.collapse-body { padding: 2px 10px 6px; }

.create-row {
  display: flex;
  align-items: center;
  gap: 4px;
  padding: 2px 0 4px;
}

.mini-input {
  flex: 1;
  min-width: 0;
  padding: 2px 6px;
  border: 1px solid #d9d9d9;
  border-radius: 4px;
  font-size: 11px;
  outline: none;
  font-family: inherit;
  box-sizing: border-box;
}
.mini-input:focus { border-color: #1677ff; }

.list-row {
  display: flex;
  align-items: center;
  gap: 4px;
  padding: 2px 0;
}
.list-row:hover { background: #f0f5ff; }
.list-row.current .list-text { font-weight: 700; color: #1677ff; }

.list-text {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-size: 11px;
  color: #333;
}

.row-actions {
  display: flex;
  gap: 3px;
  flex-shrink: 0;
}

.mini-btn {
  display: inline-flex;
  align-items: center;
  gap: 2px;
  padding: 1px 6px;
  border: 1px solid #d9d9d9;
  border-radius: 4px;
  font-size: 10px;
  cursor: pointer;
  background: white;
  color: #333;
  font-family: inherit;
  white-space: nowrap;
}
.mini-btn:hover:not(:disabled) { border-color: #1677ff; color: #1677ff; }
.mini-btn.danger:hover:not(:disabled) { border-color: #ff4d4f; color: #ff4d4f; }
.mini-btn:disabled { opacity: 0.5; cursor: not-allowed; }

/* 提交区 */
.commit-section {
  padding: 8px 10px;
  border-top: 1px solid #e8e8e8;
  background: white;
  flex-shrink: 0;
}

.commit-input {
  width: 100%;
  padding: 6px 8px;
  border: 1px solid #d9d9d9;
  border-radius: 4px;
  font-size: 11px;
  resize: vertical;
  outline: none;
  box-sizing: border-box;
  font-family: inherit;
}
.commit-input:focus { border-color: #1677ff; }

.commit-actions {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-top: 4px;
}

.commit-hint {
  font-size: 10px;
  color: #52c41a;
}

.commit-btn {
  padding: 3px 14px;
  background: #1677ff;
  color: white;
  border: none;
  border-radius: 4px;
  font-size: 12px;
  cursor: pointer;
  font-family: inherit;
}
.commit-btn:hover:not(:disabled) { background: #4096ff; }
.commit-btn:disabled { opacity: 0.5; cursor: not-allowed; }

/* 自定义滚动条 */
.changes-section::-webkit-scrollbar,
.change-list::-webkit-scrollbar,
.collapse-area::-webkit-scrollbar { width: 4px; }
.changes-section::-webkit-scrollbar-thumb,
.change-list::-webkit-scrollbar-thumb,
.collapse-area::-webkit-scrollbar-thumb { background: #dcd8ea; border-radius: 2px; }
.changes-section::-webkit-scrollbar-track,
.change-list::-webkit-scrollbar-track,
.collapse-area::-webkit-scrollbar-track { background: transparent; }

</style>

<!-- 暗色模式 — 全局强制覆盖 -->
<style>
[data-theme="dark"] .git-sidebar {
  background: #121418;
}
[data-theme="dark"] .git-status-header {
  background: #1a1d22;
  border-bottom-color: #2a2d33;
}
[data-theme="dark"] .branch-label {
  color: #e4e6ea;
}
[data-theme="dark"] .branch-label.no-repo {
  color: #8b8f98;
}
[data-theme="dark"] .icon-btn {
  color: #8b8f98;
}
[data-theme="dark"] .icon-btn:hover:not(:disabled) {
  color: #5ba0ff;
}
[data-theme="dark"] .icon-btn.active {
  color: #5ba0ff;
  background: #1e2440;
}
[data-theme="dark"] .sync-row {
  border-bottom-color: #2a2d33;
}
[data-theme="dark"] .sync-btn {
  background: #1a1d22;
  border-color: #2a2d33;
  color: #c4c8ce;
}
[data-theme="dark"] .sync-btn:hover:not(:disabled) {
  border-color: #4f7df3;
  color: #4f7df3;
}
[data-theme="dark"] .changes-toolbar {
  border-bottom-color: #2a2d33;
}
[data-theme="dark"] .toolbar-btn {
  background: #1a1d22;
  border-color: #2a2d33;
  color: #c4c8ce;
}
[data-theme="dark"] .toolbar-btn:hover:not(:disabled) {
  border-color: #4f7df3;
  color: #4f7df3;
}
[data-theme="dark"] .loading-text,
[data-theme="dark"] .empty-text {
  color: #8b8f98;
}
[data-theme="dark"] .change-item:hover {
  background: #1e2126;
}
[data-theme="dark"] .change-file {
  color: #c4c8ce;
}
[data-theme="dark"] .change-status.untracked {
  color: #8b8f98;
}
[data-theme="dark"] .row-action {
  color: #8b8f98;
}
[data-theme="dark"] .row-action:hover:not(:disabled) {
  color: #5ba0ff;
}
/* 折叠区 */
[data-theme="dark"] .collapse-area {
  border-top-color: #2a2d33;
}
[data-theme="dark"] .collapse-section {
  border-bottom-color: #2a2d33;
}
[data-theme="dark"] .collapse-header {
  color: #c4c8ce;
}
[data-theme="dark"] .collapse-header:hover {
  background: #1e2126;
}
[data-theme="dark"] .collapse-arrow,
[data-theme="dark"] .collapse-icon,
[data-theme="dark"] .collapse-count {
  color: #8b8f98;
}
[data-theme="dark"] .mini-input {
  background: #141619;
  border-color: #2a2d33;
  color: #e4e6ea;
}
[data-theme="dark"] .mini-input:focus {
  border-color: #4f7df3;
}
[data-theme="dark"] .list-row:hover {
  background: #1e2126;
}
[data-theme="dark"] .list-row.current .list-text {
  color: #5ba0ff;
}
[data-theme="dark"] .list-text {
  color: #c4c8ce;
}
[data-theme="dark"] .mini-btn {
  background: #1a1d22;
  border-color: #2a2d33;
  color: #c4c8ce;
}
[data-theme="dark"] .mini-btn:hover:not(:disabled) {
  border-color: #4f7df3;
  color: #4f7df3;
}
[data-theme="dark"] .mini-btn.danger:hover:not(:disabled) {
  border-color: #ff4d4f;
  color: #ff4d4f;
}
[data-theme="dark"] .commit-section {
  background: #1a1d22;
  border-top-color: #2a2d33;
}
[data-theme="dark"] .commit-input {
  background: #141619;
  border-color: #2a2d33;
  color: #e4e6ea;
}
[data-theme="dark"] .commit-input:focus {
  border-color: #4f7df3;
}
[data-theme="dark"] .commit-hint {
  color: #22c55e;
}
/* 暗色滚动条 */
[data-theme="dark"] .changes-section::-webkit-scrollbar-thumb,
[data-theme="dark"] .change-list::-webkit-scrollbar-thumb,
[data-theme="dark"] .collapse-area::-webkit-scrollbar-thumb {
  background: #3a3850;
}
</style>
