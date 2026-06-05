<template>
  <div class="git-sidebar">
    <!-- 仓库状态 -->
    <div class="git-status-header">
      <span class="branch-label" v-if="isRepo">
        <BranchesOutlined /> {{ branch }}
      </span>
      <span v-else class="branch-label no-repo">不是 git 仓库</span>
      <button class="refresh-btn" @click="refreshStatus" :disabled="!isRepo">
        <ReloadOutlined />
      </button>
    </div>

    <!-- 变更文件列表 -->
    <div class="changes-section" v-if="isRepo">
      <div class="changes-toolbar" v-if="changes.length > 0">
        <button class="toolbar-btn" @click="stageAll">全部暂存</button>
        <button class="toolbar-btn" @click="unstageSelected">撤销选中</button>
      </div>
      <div class="change-list">
        <div v-if="loading" class="loading-text">加载中...</div>
        <div v-else-if="changes.length === 0" class="empty-text">工作区干净，无变更</div>
        <div
          v-for="change in changes"
          :key="change.file"
          :class="['change-item', { selected: selectedFile === change.file }]"
        >
          <label class="change-checkbox">
            <input
              type="checkbox"
              :checked="stagedFiles.has(change.file) || change.status === 'staged'"
              @change="toggleStage(change)"
            />
          </label>
          <span :class="['change-status', change.status]">{{ statusLabel(change) }}</span>
          <span class="change-file" @dblclick="handleDblClick(change)" :title="change.file">
            {{ fileDisplayName(change.file) }}
          </span>
        </div>
      </div>

      <!-- 提交区 -->
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
            :disabled="!commitMessage.trim() || stagedCount === 0"
          >提交</button>
        </div>
      </div>
    </div>
  </div>

</template>

<script setup lang="ts">
import { ref, computed, onMounted, watch } from 'vue'
import { message } from 'ant-design-vue'
import { BranchesOutlined, ReloadOutlined, UndoOutlined } from '@ant-design/icons-vue'
import {
  getGitStatus,
  gitAdd,
  gitCommit,
  gitRestore,
  type GitChange
} from '@/api/git'

const emit = defineEmits<{
  fileDblclick: [filePath: string, projectRoot: string]
}>()

const props = defineProps<{ projectRoot: string }>()

const isRepo = ref(false)
const branch = ref('')
const changes = ref<GitChange[]>([])
const loading = ref(false)
const commitMessage = ref('')
const stagedFiles = ref<Set<string>>(new Set())

const stagedCount = computed(() => {
  let count = stagedFiles.value.size
  changes.value.forEach(c => {
    if (c.status === 'staged') count++
  })
  return count
})

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

const handleDblClick = (change: GitChange) => {
  emit('fileDblclick', change.file, props.projectRoot)
}

const toggleStage = async (change: GitChange) => {
  if (change.status === 'staged') {
    // 取消暂存
    try {
      await gitAdd(props.projectRoot, undefined, false)
      // 简化处理：用 -- git rm --cached
      await fetch('/api/git/add', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ projectRoot: props.projectRoot, files: [change.file], all: false })
      })
      stagedFiles.value.delete(change.file)
      await refreshStatus()
    } catch (e) {
      message.error('取消暂存失败')
    }
  } else {
    // 暂存
    try {
      const result = await gitAdd(props.projectRoot, [change.file])
      if (result.success) {
        stagedFiles.value.add(change.file)
        await refreshStatus()
      } else {
        message.error('暂存失败: ' + (result.error || ''))
      }
    } catch (e: any) {
      message.error('暂存失败: ' + (e.message || ''))
    }
  }
}

const stageAll = async () => {
  try {
    const result = await gitAdd(props.projectRoot, undefined, true)
    if (result.success) {
      message.success('已暂存全部变更')
      await refreshStatus()
    } else {
      message.error('暂存失败: ' + (result.error || ''))
    }
  } catch (e: any) {
    message.error('暂存失败: ' + (e.message || ''))
  }
}

const unstageSelected = async () => {
  try {
    // 先获取已暂存的文件列表
    for (const change of changes.value) {
      if (change.status === 'staged' && stagedFiles.value.has(change.file)) {
        // reset each staged file
        await fetch('/api/git/add', {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ projectRoot: props.projectRoot, files: [change.file], all: false })
        })
      }
    }
    stagedFiles.value.clear()
    await refreshStatus()
    message.success('已撤销选中')
  } catch (e: any) {
    message.error('撤销失败: ' + (e.message || ''))
  }
}

const handleRestoreFile = async (file: string) => {
  try {
    const result = await gitRestore(props.projectRoot, file)
    if (result.success) {
      message.success('已撤销: ' + file)
      await refreshStatus()
    } else {
      message.error('撤销失败: ' + (result.error || ''))
    }
  } catch (e: any) {
    message.error('撤销失败: ' + (e.message || ''))
  }
}

const handleCommit = async () => {
  const msg = commitMessage.value.trim()
  if (!msg) return

  // 如果还有未暂存的已选中文件，先暂存
  for (const change of changes.value) {
    if (stagedFiles.value.has(change.file) && change.status !== 'staged') {
      await gitAdd(props.projectRoot, [change.file])
    }
  }

  try {
    const result = await gitCommit(props.projectRoot, msg)
    if (result.success) {
      message.success('提交成功')
      commitMessage.value = ''
      stagedFiles.value.clear()
      await refreshStatus()
    } else {
      message.error('提交失败: ' + (result.error || ''))
    }
  } catch (e: any) {
    message.error('提交失败: ' + (e.message || ''))
  }
}

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
}
.branch-label.no-repo { color: #999; font-weight: normal; }

.refresh-btn {
  border: none;
  background: transparent;
  cursor: pointer;
  color: #8c8c8c;
  font-size: 14px;
  padding: 2px;
  display: flex;
}
.refresh-btn:hover:not(:disabled) { color: #1677ff; }
.refresh-btn:disabled { opacity: 0.4; cursor: not-allowed; }

/* 变更列表 */
.changes-section {
  flex: 1;
  overflow-y: auto;
  display: flex;
  flex-direction: column;
}

.changes-toolbar {
  display: flex;
  gap: 4px;
  padding: 4px 10px;
  border-bottom: 1px solid #eef2f7;
  flex-shrink: 0;
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
.toolbar-btn:hover { border-color: #1677ff; color: #1677ff; }

.change-list {
  flex: 1;
  overflow-y: auto;
  padding: 4px 0;
}

.loading-text, .empty-text {
  padding: 20px 10px;
  text-align: center;
  color: #8c8c8c;
}

.change-item {
  display: flex;
  align-items: center;
  gap: 4px;
  padding: 3px 10px;
  cursor: pointer;
  transition: background 0.1s;
}
.change-item:hover { background: #f0f5ff; }
.change-item.selected { background: #e6f4ff; }

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
.change-list::-webkit-scrollbar { width: 4px; }
.changes-section::-webkit-scrollbar-thumb,
.change-list::-webkit-scrollbar-thumb { background: #dcd8ea; border-radius: 2px; }
.changes-section::-webkit-scrollbar-track,
.change-list::-webkit-scrollbar-track { background: transparent; }

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
[data-theme="dark"] .refresh-btn {
  color: #8b8f98;
}
[data-theme="dark"] .refresh-btn:hover:not(:disabled) {
  color: #5ba0ff;
}
[data-theme="dark"] .changes-toolbar {
  border-bottom-color: #2a2d33;
}
[data-theme="dark"] .toolbar-btn {
  background: #1a1d22;
  border-color: #2a2d33;
  color: #c4c8ce;
}
[data-theme="dark"] .toolbar-btn:hover {
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
[data-theme="dark"] .change-item.selected {
  background: #1e2440;
}
[data-theme="dark"] .change-file {
  color: #c4c8ce;
}
[data-theme="dark"] .change-status.untracked {
  color: #8b8f98;
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
[data-theme="dark"] .change-list::-webkit-scrollbar-thumb {
  background: #3a3850;
}
</style>
