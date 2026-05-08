<template>
  <div class="git-sidebar">
    <!-- Git 配置区 -->
    <div class="git-config">
      <div class="config-title">Git 配置</div>
      <div class="config-row">
        <input
          v-model="remoteUrl"
          placeholder="Remote URL（可选）"
          class="config-input"
        />
      </div>
      <div class="config-row">
        <div class="token-row">
          <input
            v-model="tokenInput"
            :type="showToken ? 'text' : 'password'"
            :placeholder="hasToken ? 'Token 已设置，输入新值覆盖' : 'Token（可选）'"
            class="config-input"
          />
          <button class="config-btn" @click="showToken = !showToken">
            {{ showToken ? '隐藏' : '显示' }}
          </button>
        </div>
      </div>
      <div class="config-row config-actions">
        <template v-if="isRepo">
          <button class="config-btn primary" @click="saveAuth" :disabled="!tokenInput">保存 Token</button>
          <button v-if="hasToken" class="config-btn danger" @click="clearAuth">清除</button>
        </template>
        <template v-else>
          <button class="config-btn primary" @click="initModalVisible = true">初始化 Git 仓库</button>
        </template>
      </div>
    </div>

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
          <span class="change-file" @click="showDiff(change.file)">{{ change.file }}</span>
        </div>
      </div>

      <!-- Diff 预览 -->
      <div v-if="diffContent !== null" class="diff-preview">
        <div class="diff-header">
          <span>Diff: {{ diffFile }}</span>
          <button class="diff-close" @click="diffContent = null">✕</button>
        </div>
        <pre class="diff-text">{{ diffContent }}</pre>
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

  <!-- 初始化 Git 仓库 Modal -->
  <a-modal
    v-model:visible="initModalVisible"
    title="初始化 Git 仓库"
    @ok="handleInitRepo"
    :confirm-loading="initLoading"
    ok-text="确认初始化"
    cancel-text="取消"
  >
    <div class="init-form">
      <div class="init-form-row">
        <label class="init-form-label">Remote URL（可选）</label>
        <input v-model="initRemoteUrl" placeholder="https://github.com/user/repo.git" class="init-form-input" />
      </div>
      <div class="init-form-row">
        <label class="init-form-label">Token（可选）</label>
        <input v-model="initToken" type="password" placeholder="Git 个人访问令牌" class="init-form-input" />
      </div>
    </div>
  </a-modal>
</template>

<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import { message } from 'ant-design-vue'
import { BranchesOutlined, ReloadOutlined } from '@ant-design/icons-vue'
import {
  getGitStatus,
  getGitDiff,
  gitAdd,
  gitCommit,
  saveGitToken,
  clearGitToken,
  gitInit,
  type GitChange
} from '@/api/git'

const props = defineProps<{ projectRoot: string }>()

const isRepo = ref(false)
const branch = ref('')
const changes = ref<GitChange[]>([])
const loading = ref(false)
const remoteUrl = ref('')
const hasToken = ref(false)
const showToken = ref(false)
const tokenInput = ref('')
const selectedFile = ref<string | null>(null)
const diffContent = ref<string | null>(null)
const diffFile = ref('')
const commitMessage = ref('')
const stagedFiles = ref<Set<string>>(new Set())
const initModalVisible = ref(false)
const initLoading = ref(false)
const initRemoteUrl = ref('')
const initToken = ref('')

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

const refreshStatus = async () => {
  if (!props.projectRoot) return
  loading.value = true
  try {
    const status = await getGitStatus(props.projectRoot)
    isRepo.value = status.isRepo
    branch.value = status.branch || ''
    changes.value = status.changes || []
    remoteUrl.value = status.remoteUrl || ''
    hasToken.value = status.hasToken || false
  } catch (e: any) {
    console.error('获取 git 状态失败:', e)
  } finally {
    loading.value = false
  }
}

const showDiff = async (file: string) => {
  selectedFile.value = file
  diffFile.value = file
  try {
    const result = await getGitDiff(props.projectRoot, file)
    diffContent.value = result.success ? result.diff : '获取 diff 失败: ' + (result.error || '')
  } catch (e: any) {
    diffContent.value = '获取 diff 失败: ' + (e.message || '')
  }
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
      diffContent.value = null
      await refreshStatus()
    } else {
      message.error('提交失败: ' + (result.error || ''))
    }
  } catch (e: any) {
    message.error('提交失败: ' + (e.message || ''))
  }
}

const saveAuth = async () => {
  if (!tokenInput.value) return
  try {
    const result = await saveGitToken(props.projectRoot, tokenInput.value)
    if (result.success) {
      message.success('Token 已保存')
      hasToken.value = true
      tokenInput.value = ''
    } else {
      message.error('保存失败: ' + (result.error || ''))
    }
  } catch (e: any) {
    message.error('保存失败: ' + (e.message || ''))
  }
}

const clearAuth = async () => {
  try {
    await clearGitToken(props.projectRoot)
    hasToken.value = false
    tokenInput.value = ''
    message.success('Token 已清除')
  } catch (e: any) {
    message.error('清除失败')
  }
}

const handleInitRepo = async () => {
  initLoading.value = true
  try {
    const result = await gitInit(props.projectRoot, initRemoteUrl.value || undefined, initToken.value || undefined)
    if (result.success) {
      message.success('Git 仓库初始化成功')
      initModalVisible.value = false
      initRemoteUrl.value = ''
      initToken.value = ''
      await refreshStatus()
    } else {
      message.error('初始化失败: ' + (result.error || ''))
    }
  } catch (e: any) {
    message.error('初始化失败: ' + (e.message || ''))
  } finally {
    initLoading.value = false
  }
}

onMounted(() => {
  if (props.projectRoot) {
    refreshStatus()
  }
})

// 监听 projectRoot 变化
import { watch } from 'vue'
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

/* 配置区 */
.git-config {
  padding: 8px 10px;
  border-bottom: 1px solid #eef2f7;
  background: #f8fafc;
  flex-shrink: 0;
}

.config-title {
  font-size: 11px;
  font-weight: 600;
  color: #666;
  margin-bottom: 6px;
}

.config-row {
  margin-bottom: 4px;
}

.config-input {
  width: 100%;
  padding: 4px 6px;
  border: 1px solid #d9d9d9;
  border-radius: 4px;
  font-size: 11px;
  outline: none;
  box-sizing: border-box;
  font-family: inherit;
}
.config-input:focus {
  border-color: #1677ff;
}
.config-input:disabled {
  background: #f5f5f5;
  color: #999;
}

.token-row {
  display: flex;
  gap: 4px;
}
.token-row .config-input {
  flex: 1;
}
.config-btn {
  padding: 2px 8px;
  border: 1px solid #d9d9d9;
  border-radius: 4px;
  font-size: 11px;
  cursor: pointer;
  background: white;
  white-space: nowrap;
  font-family: inherit;
}
.config-btn:hover:not(:disabled) { border-color: #1677ff; color: #1677ff; }
.config-btn.primary { background: #1677ff; color: white; border-color: #1677ff; }
.config-btn.primary:hover:not(:disabled) { background: #4096ff; }
.config-btn.danger { color: #ff4d4f; border-color: #ff4d4f; }
.config-btn.danger:hover:not(:disabled) { background: #fff2f0; }
.config-btn:disabled { opacity: 0.5; cursor: not-allowed; }

.config-actions {
  display: flex;
  gap: 4px;
  margin-top: 4px;
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
}

/* Diff 预览 */
.diff-preview {
  border-top: 1px solid #e8e8e8;
  max-height: 200px;
  overflow: hidden;
  display: flex;
  flex-direction: column;
  flex-shrink: 0;
}

.diff-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 4px 10px;
  background: #f5f5f5;
  font-size: 11px;
  color: #666;
  flex-shrink: 0;
}

.diff-close {
  border: none;
  background: transparent;
  cursor: pointer;
  color: #999;
  font-size: 12px;
  padding: 0 4px;
}
.diff-close:hover { color: #333; }

.diff-text {
  margin: 0;
  padding: 6px 10px;
  font-size: 10px;
  line-height: 1.4;
  overflow-y: auto;
  white-space: pre-wrap;
  word-break: break-all;
  flex: 1;
  background: #fafbfc;
  font-family: 'SF Mono', 'Monaco', 'Menlo', monospace;
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

/* 初始化 Modal 表单 */
.init-form { padding: 8px 0; }
.init-form-row { margin-bottom: 12px; }
.init-form-label { display: block; font-size: 12px; color: #666; margin-bottom: 4px; font-weight: 500; }
.init-form-input {
  width: 100%;
  padding: 6px 10px;
  border: 1px solid #d9d9d9;
  border-radius: 4px;
  font-size: 13px;
  outline: none;
  box-sizing: border-box;
  font-family: inherit;
}
.init-form-input:focus { border-color: #1677ff; }
</style>
