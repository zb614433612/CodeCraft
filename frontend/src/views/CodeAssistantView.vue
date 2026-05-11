<template>
  <div class="chat-container">
    <!-- 侧边栏: 会话列表 / 文件树 -->
    <aside class="sidebar" :class="{ collapsed }">
      <div class="sidebar-header">
        <div class="sidebar-tabs">
          <button
            :class="['tab-btn', { active: sidebarTab === 'conversation' }]"
            @click="sidebarTab = 'conversation'"
          ><span class="tab-full">会话</span><span class="tab-short">会话</span></button>
          <button
            :class="['tab-btn', { active: sidebarTab === 'files' }]"
            @click="sidebarTab = 'files'"
          ><span class="tab-full">文件</span><span class="tab-short">文件</span></button>
          <button
            :class="['tab-btn', { active: sidebarTab === 'git' }]"
            @click="sidebarTab = 'git'"
          ><span class="tab-full">Git</span><span class="tab-short">Git</span></button>
          <button
            :class="['tab-btn', { active: sidebarTab === 'skills' }]"
            @click="sidebarTab = 'skills'"
          ><span class="tab-full">技能</span><span class="tab-short">技能</span></button>
        </div>
        <div class="sidebar-collapse-btn" @click="toggleCollapsed">
          <MenuFoldOutlined v-if="!collapsed" />
          <MenuUnfoldOutlined v-else />
        </div>
      </div>

      <!-- 会话列表 -->
      <div v-show="sidebarTab === 'conversation'" class="conversation-list">
        <div class="new-chat-btn" @click="startNewChat">
          <PlusOutlined />
          <span>新建会话</span>
        </div>
        <div v-if="isLoadingConversations" class="loading-conversations">
          <a-spin size="small" />
          <span>加载会话中...</span>
        </div>
        <div v-else-if="conversations.length === 0" class="empty-conversations">
          <p>暂无会话</p>
          <p class="empty-hint">输入消息开始编码</p>
        </div>
        <div
          v-for="conv in conversations"
          :key="conv.id"
          :class="['conversation-item', { active: currentConversationId === conv.id }]"
          @click="selectConversation(conv.id)"
        >
          <div class="conv-icon">
            <CodeOutlined />
          </div>
          <div class="conv-info">
            <div class="conv-title">{{ conv.title }}</div>
            <div class="conv-time">{{ formatTime(conv.updatedAt) }}</div>
          </div>
          <div class="conv-actions">
            <a-button type="text" size="small" @click.stop="deleteConversation(conv.id)">
              <delete-outlined />
            </a-button>
          </div>
        </div>
      </div>

      <!-- Git 面板 -->
      <div v-show="sidebarTab === 'git'" class="git-panel">
        <GitSidebar :project-root="settingsStore.projectRoot || ''" @file-dblclick="onGitFileDblClick" />
      </div>

      <!-- 技能面板 -->
      <div v-show="sidebarTab === 'skills'" class="skill-panel">
        <SkillList />
      </div>

      <!-- 文件树 -->
      <div v-show="sidebarTab === 'files'" class="filetree-panel">
        <div class="project-root-bar">
          <span class="project-root-label">工作目录</span>
          <div class="project-root-row">
            <a-input
              v-model:value="settingsStore.projectRoot"
              placeholder="点击右侧按钮选择目录"
              size="small"
              class="project-root-input"
              readonly
            />
            <a-button size="small" type="primary" @click="selectProjectRoot" class="project-root-btn">
              <FolderOpenOutlined />
            </a-button>
          </div>
        </div>
        <FileTree :root-path="fileTreeLoadPath" @select="onFileSelect" @dblclick="onFileDblClick" />
        <DirectoryBrowser :visible="showDirBrowser" @select="onDirSelected" @close="showDirBrowser = false" />
      </div>
    </aside>

    <!-- 主聊天区域（标签页系统） -->
    <main class="chat-main">
      <!-- 标签栏 -->
      <div class="tab-bar" v-if="tabs.length > 0">
        <div
          v-for="tab in tabs"
          :key="tab.id"
          :class="['tab-item', { active: activeTabId === tab.id, dirty: tab.isDirty }]"
          @click="activeTabId = tab.id"
          @mousedown.middle.prevent="closeTab(tab.id)"
        >
          <span class="tab-title">{{ tab.title }}</span>
          <span v-if="tab.isDirty" class="tab-dirty-dot">●</span>
          <span v-if="tab.type !== 'chat'" class="tab-close" @click.stop="closeTab(tab.id)">×</span>
        </div>
      </div>

      <!-- 标签内容区 -->
      <div class="tab-content">
        <!-- ===== 聊天标签 ===== -->
        <template v-if="activeTab?.type === 'chat'">
          <div class="chat-messages-area">
            <!-- 无消息时的占位区（撑满弹性空间，使输入框始终在底部） -->
      <div v-if="currentMessages.length === 0" class="message-list-empty">
        <div v-if="isLoadingMessages" class="loading-messages">
          <a-spin size="small" />
          <span>加载消息中...</span>
        </div>
      </div>

      <!-- 虚拟滚动消息列表 -->
      <DynamicScroller
        v-if="currentMessages.length > 0"
        class="message-list"
        ref="scrollerRef"
        :items="currentMessages"
        :min-item-size="80"
        key-field="id"
        v-slot="{ item: msg, active }"
      >
        <DynamicScrollerItem :item="msg" :active="active" :size-dependencies="msg.isStreaming ? [] : [msg.content?.length || 0, msg.thinking?.length || 0]">
        <div :class="['message-item', msg.role]">
          <div class="message-avatar">
            <div v-if="msg.role === 'user'" class="avatar user-avatar">
              {{ userStore.userInfo?.username?.charAt(0).toUpperCase() || 'U' }}
            </div>
            <div v-else class="avatar ai-avatar">
              <CodeOutlined />
            </div>
          </div>
          <div class="message-content">
            <div class="message-header">
              <span class="message-sender">{{ msg.role === 'user' ? userStore.userInfo?.username || '用户' : '编码助手' }}</span>
              <span class="message-time">{{ formatMessageTime(msg.timestamp) }}</span>
              <span class="message-token" v-if="msg.tokenCount !== undefined">· {{ formatTokenCount(msg.tokenCount) }} token</span>
              <span class="message-token" v-else-if="msg.isStreaming">· 计算中...</span>
              <span v-if="msg.isStreaming" class="streaming-indicator">
                <span class="streaming-dot"></span>
                正在输入...
              </span>
            </div>

            <!-- 思考过程（仅AI消息） -->
            <div v-if="msg.role === 'assistant' && msg.thinking" class="thinking-section">
              <div class="thinking-header" @click="toggleThinkingVisibility(msg.id)">
                <span class="thinking-title">
                  <DownOutlined v-if="!getThinkingVisible(msg.id)" />
                  <UpOutlined v-else />
                  思考过程
                </span>
                <span class="thinking-hint">点击{{ getThinkingVisible(msg.id) ? '收起' : '展开' }}</span>
              </div>
              <div v-if="getThinkingVisible(msg.id)" class="thinking-content">
                <div v-if="msg.thinking || msg.toolResults?.length" class="thinking-text" v-html="formatThinking(msg.thinking, msg.toolResults)"></div>
              </div>
            </div>
            <div class="message-text code-message" v-html="formatMessage(msg.content)" v-if="msg.content"></div>
          </div>
        </div>
        </DynamicScrollerItem>
      </DynamicScroller>

      <!-- 后台任务进行中指示器 -->
      <div v-if="activeTask &amp;&amp; !isSending" class="stream-indicator task-reconnect-banner">
        <span class="stream-pulse-dot"></span>
        <span class="stream-indicator-text">
          任务正在后台执行中（第 {{ activeTask.iteration + 1 }} 轮）... <span class="stream-elapsed">{{ formatElapsed(elapsedTime) }}</span>
          <a-button size="small" type="link" @click="reconnectToTaskStream(parseInt(currentConversationId))">重新连接</a-button>
        </span>
      </div>

      <!-- 流式加载指示器 -->
      <div v-if="isSending &amp;&amp; streamStatus" class="stream-indicator">
        <span class="stream-pulse-dot"></span>
        <span class="stream-indicator-text">{{ streamStatus }}</span>
        <span class="stream-elapsed" v-if="elapsedTime > 0">{{ formatElapsed(elapsedTime) }}</span>
      </div>

      <!-- 重连实时流式消息容器（独立消息盒子，溢出滚动） -->
      <div v-if="reconnectLiveMsg" class="reconnect-container">
        <div class="reconnect-header">
          <span class="reconnect-dot"></span>
          任务恢复中 - 实时输出
        </div>
        <div class="reconnect-body">
          <div class="message-item assistant">
            <div class="message-avatar">
              <div class="avatar ai-avatar">
                <CodeOutlined />
              </div>
            </div>
            <div class="message-content">
              <div class="message-meta">
                <span class="message-role">AI</span>
                <span class="message-time">重连中...</span>
              </div>
              <div v-if="reconnectLiveMsg.thinking" class="thinking-section">
                <div class="thinking-header" @click="toggleThinkingVisibility('reconnect')">
                  <span class="thinking-title">
                    <DownOutlined v-if="!getThinkingVisible('reconnect')" />
                    <UpOutlined v-else />
                    思考过程
                  </span>
                  <span class="thinking-hint">点击{{ getThinkingVisible('reconnect') ? '收起' : '展开' }}</span>
                </div>
                <div v-if="getThinkingVisible('reconnect')" class="thinking-content">
                  <div class="thinking-text" v-html="formatThinking(reconnectLiveMsg.thinking, [])"></div>
                </div>
              </div>
              <div class="message-text code-message" v-html="formatMessage(reconnectLiveMsg.content)" v-if="reconnectLiveMsg.content"></div>
              <div v-else class="message-text placeholder-text" style="color: #999;">等待模型响应...</div>
            </div>
          </div>
        </div>
      </div>

      <!-- ask_user 问答面板 -->
      <div v-if="pendingQuestion" class="ask-user-panel">
        <div class="ask-user-header">需要确认</div>
        <div class="ask-user-question">{{ pendingQuestion.question }}</div>
        <div class="ask-user-input-row">
          <a-input
            v-model:value="pendingQuestionAnswer"
            placeholder="请输入回答..."
            @pressEnter="submitPendingAnswer"
          />
          <a-button type="primary" @click="submitPendingAnswer">确认</a-button>
        </div>
      </div>

      <!-- 输入区域 -->
      <!-- 输入区域 -->
      <footer class="chat-input-area">
        <!-- 隐藏的文件选择器 -->
        <input
          ref="fileInputRef"
          type="file"
          style="display: none"
          @change="handleFileSelected"
        />
        <!-- 附件标签区 -->
        <div v-if="attachedFiles.length > 0" class="attachment-tags">
          <div
            v-for="att in attachedFiles"
            :key="att.id"
            :class="['attachment-tag', { 'attachment-error': att.error }]"
          >
            <span class="attachment-icon">{{ att.image ? '🖼️' : '📄' }}</span>
            <span class="attachment-name" :title="att.fileName">{{ att.fileName }}</span>
            <span class="attachment-size">{{ formatFileSize(att.size) }}</span>
            <span v-if="att.uploading" class="attachment-uploading">
              <LoadingOutlined spin />
            </span>
            <span v-else-if="att.error" class="attachment-error-msg" :title="att.error">上传失败</span>
            <span v-else class="attachment-remove" @click="removeAttachment(att.id)">×</span>
          </div>
        </div>
        <div class="input-wrapper">
          <a-button
            class="attach-btn"
            @click="triggerFileUpload"
            :disabled="isSending"
            title="上传附件"
          >
            <PaperClipOutlined />
          </a-button>
          <a-textarea
            v-model:value="inputMessage"
            placeholder="描述你的编码需求...（Shift+Enter换行，Enter发送）"
            :auto-size="{ minRows: 1, maxRows: 8 }"
            @keydown.enter.exact.prevent="sendMessage"
            @keydown.shift.enter="handleShiftEnter"
          />
          <a-button
            v-if="isSending"
            class="stop-btn"
            @click="stopStreaming"
          >
            <CloseOutlined />
          </a-button>
          <a-button
            v-else
            type="primary"
            class="send-btn"
            @click="sendMessage"
            :disabled="!inputMessage.trim() && attachedFiles.length === 0"
          >
            <SendOutlined />
          </a-button>
        </div>
        <div class="input-footer">
          <div class="footer-left">
            <div class="mode-selector">
              <SettingOutlined class="mode-icon" />
              <span class="mode-label">执行</span>
              <a-select
                :value="settingsStore.getMode('code_assistant')"
                @change="handleModeChange"
                size="small"
                class="mode-select"
                dropdown-class-name="mode-dropdown"
              >
                <a-select-option value="manual">手动 - 询问后执行</a-select-option>
                <a-select-option value="auto">自动 - 直接执行</a-select-option>
              </a-select>
            </div>
            <div class="mode-selector">
              <SettingOutlined class="mode-icon" />
              <a-select
                :value="settingsStore.getModel('code_assistant')"
                @change="handleModelChange"
                size="small"
                class="model-select"
                dropdown-class-name="mode-dropdown"
              >
                <a-select-option value="deepseek-v4-flash">deepseek-v4-flash</a-select-option>
                <a-select-option value="deepseek-v4-pro">deepseek-v4-pro</a-select-option>
              </a-select>
            </div>
            <div class="mode-selector">
              <SettingOutlined class="mode-icon" />
              <a-select
                :value="settingsStore.getThinkingMode('code_assistant')"
                @change="handleThinkingModeChange"
                size="small"
                class="model-select"
                dropdown-class-name="mode-dropdown"
              >
                <a-select-option value="non-thinking">non-thinking</a-select-option>
                <a-select-option value="thinking">thinking</a-select-option>
                <a-select-option value="thinking_max">thinking_max</a-select-option>
              </a-select>
            </div>
            <!-- 任务进度触发器 -->
            <div class="mode-selector task-trigger" @click="toggleTaskDropdown" :class="{ active: showTaskDropdown, loading: isSending }">
              <span class="mode-icon task-trigger-icon">
                <LoadingOutlined v-if="isSending" spin />
                <span v-else>📋</span>
              </span>
              <span class="task-trigger-text" v-if="taskItems.length === 0">任务</span>
              <span class="task-trigger-text" v-else>{{ completedTaskCount }}/{{ taskItems.length }}</span>
            </div>
          </div>
          <div class="footer-right" v-if="currentMessages.length > 0">
            <span class="context-tokens">上下文 Token: {{ formatTokenCount(totalContextTokens) }}</span>
          </div>
        </div>
        <!-- 任务清单下拉面板 -->
        <div v-if="showTaskDropdown && taskItems.length > 0" class="task-dropdown">
          <div class="task-dropdown-header">
            <span>📋 任务清单</span>
            <span class="task-dropdown-progress">{{ completedTaskCount }}/{{ taskItems.length }}</span>
          </div>
          <div class="task-dropdown-body">
            <div
              v-for="item in taskItems"
              :key="item.id"
              :class="['task-dropdown-item', {
                'task-dropdown-item--completed': item.status === 'completed',
                'task-dropdown-item--executing': item.id === executingTaskId
              }]"
            >
              <span class="task-dropdown-icon">
                {{ item.status === 'completed' ? '✅' : item.id === executingTaskId ? '🔄' : '⏳' }}
              </span>
              <span class="task-dropdown-id">{{ item.id }}</span>
              <span class="task-dropdown-desc">{{ item.description }}</span>
              <span v-if="item.priority === 'HIGH'" class="task-dropdown-priority-high">HIGH</span>
              <span v-if="item.depends_on && item.depends_on.length > 0" class="task-dropdown-deps">← {{ item.depends_on.join(', ') }}</span>
            </div>
          </div>      </div>
        </footer>
        </div>
        </template>
        <!-- ===== 文件编辑标签 ===== -->
        <template v-else-if="activeTab?.type === 'file'">
          <FileEditor
            :key="activeTab.id"
            :file-path="activeTab.filePath || ''"
            :content="activeTab.content || ''"
            :original-content="activeTab.originalContent"
            @save="onFileSaved"
            @content-change="onFileContentChange"
          />
        </template>
        <!-- ===== 差异对比标签 ===== -->
        <template v-else-if="activeTab?.type === 'diff'">
          <div class="diff-tab-content">
            <div class="diff-tab-header">
              <span class="diff-tab-title">差异对比: {{ activeTab.filePath }}</span>
              <div class="diff-tab-actions">
                <a-button size="small" danger @click="handleRevertFile(activeTab.filePath || '')">
                  <UndoOutlined />
                  撤销此文件改动
                </a-button>
              </div>
            </div>
            <div class="diff-tab-body">
              <DiffView :content="activeTab.diffContent || ''" :title="'Diff: ' + (activeTab.filePath || '')" />
            </div>
          </div>
        </template>
      </div>
    </main>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted, nextTick, watch } from 'vue'
import { message, Modal } from 'ant-design-vue'
import { useUserStore } from '@/store/user'
import { getConversationList, mapConversationResponseToConversation, getConversationMessages, processMessageGroups, deleteConversation as deleteConversationApi } from '@/api/conversation'
import { streamChat, checkActiveTask, taskStream, cancelTask, uploadAttachment } from '@/api/chat'
import { submitAnswer } from '@/api/askUser'
import { useSettingsStore } from '@/store/settings'
import { renderMarkdown } from '@/utils/markdown'
import { estimateTokenCount, formatTokenCount } from '@/utils/tokenCalculator'
import {
  CodeOutlined,
  DeleteOutlined,
  SendOutlined,
  DownOutlined,
  UpOutlined,
  MenuFoldOutlined,
  LoadingOutlined,
  MenuUnfoldOutlined,
  PlusOutlined,
  SettingOutlined,
  CloseOutlined,
  FolderOpenOutlined,
  UndoOutlined,
  PaperClipOutlined
} from '@ant-design/icons-vue'
import FileTree from '@/components/FileTree.vue'
import FileEditor from '@/components/FileEditor.vue'
import GitSidebar from '@/components/GitSidebar.vue'
import DiffView from '@/components/DiffView.vue'
import SkillList from '@/components/SkillList.vue'
import DirectoryBrowser from '@/components/DirectoryBrowser.vue'
import { DynamicScroller, DynamicScrollerItem } from 'vue-virtual-scroller'
import 'vue-virtual-scroller/dist/vue-virtual-scroller.css'
import { readProjectFile } from '@/api/project'
import { getGitDiff, gitRestore } from '@/api/git'

const PROMPT_FILE = 'code_agent_prompt.txt'

const userStore = useUserStore()
const settingsStore = useSettingsStore()

interface Conversation {
  id: string
  title: string
  updatedAt: number
  messageCount: number
  isLocal?: boolean
}

interface ChatMessage {
  id: string
  role: 'user' | 'assistant'
  content: string
  thinking?: string
  toolResults?: { at: number; content: string }[]
  timestamp: number
  isStreaming?: boolean
  tokenCount?: number
}

// ===== 附件系统 =====
interface AttachedFile {
  id: string
  fileName: string
  content: string
  size: number
  image: boolean
  language: string
  uploading: boolean
  error?: string
}

const attachedFiles = ref<AttachedFile[]>([])
const fileInputRef = ref<HTMLInputElement | null>(null)

const triggerFileUpload = () => {
  fileInputRef.value?.click()
}

const handleFileSelected = async (event: Event) => {
  const target = event.target as HTMLInputElement
  const file = target.files?.[0]
  if (!file) return

  const attId = `att-${Date.now()}`
  const attFile: AttachedFile = {
    id: attId,
    fileName: file.name,
    content: '',
    size: file.size,
    image: false,
    language: '',
    uploading: true
  }
  attachedFiles.value.push(attFile)

  try {
    const result = await uploadAttachment(file)
    if (result.success) {
      const found = attachedFiles.value.find(a => a.id === attId)
      if (found) {
        found.content = result.content
        found.image = result.image
        found.language = result.language
        found.uploading = false
      }
    } else {
      const found = attachedFiles.value.find(a => a.id === attId)
      if (found) {
        found.error = result.error || '上传失败'
        found.uploading = false
      }
    }
  } catch (e: any) {
    const found = attachedFiles.value.find(a => a.id === attId)
    if (found) {
      found.error = e.message || '上传失败'
      found.uploading = false
    }
  }

  // 清空 input 以便重复选择同一个文件
  target.value = ''
}

const removeAttachment = (id: string) => {
  attachedFiles.value = attachedFiles.value.filter(a => a.id !== id)
}

const formatFileSize = (bytes: number): string => {
  if (bytes < 1024) return bytes + ' B'
  if (bytes < 1024 * 1024) return (bytes / 1024).toFixed(1) + ' KB'
  return (bytes / 1024 / 1024).toFixed(1) + ' MB'
}

// ===== 标签页系统（Tab 管理） =====

// ===== 标签页系统（Tab 管理） =====
interface EditorTab {
  id: string
  type: 'chat' | 'file' | 'diff'
  title: string
  filePath?: string
  content?: string
  originalContent?: string
  isDirty?: boolean
  diffContent?: string
  language?: string
}

const tabs = ref<EditorTab[]>([
  { id: 'chat', type: 'chat', title: '💬 对话' }
])
const activeTabId = ref('chat')

const activeTab = computed(() => tabs.value.find(t => t.id === activeTabId.value) || tabs.value[0])

// 双击文件树中的文件，打开文件编辑标签
const onFileDblClick = async (path: string, isDirectory: boolean) => {
  if (isDirectory) return
  const existing = tabs.value.find(t => t.type === 'file' && t.filePath === path)
  if (existing) {
    activeTabId.value = existing.id
    return
  }
  try {
    const res = await readProjectFile(path)
    if (res.code === 200 && res.data !== null) {
      const fileName = path.split('/').pop() || path.split('\\').pop() || path
      const tabId = `file-${path}`
      tabs.value.push({
        id: tabId,
        type: 'file',
        title: fileName,
        filePath: path,
        content: res.data,
        originalContent: res.data,
        isDirty: false
      })
      activeTabId.value = tabId
    }
  } catch (e: any) {
    console.error('打开文件失败:', e)
  }
}

// 关闭标签
const closeTab = (tabId: string) => {
  const idx = tabs.value.findIndex(t => t.id === tabId)
  if (idx === -1 || tabs.value[idx].type === 'chat') return
  tabs.value.splice(idx, 1)
  if (activeTabId.value === tabId) {
    const newIdx = Math.min(idx, tabs.value.length - 1)
    activeTabId.value = tabs.value[newIdx]?.id || 'chat'
  }
}

// 文件保存后回调
const onFileSaved = (path: string, content: string) => {
  const tab = tabs.value.find(t => t.type === 'file' && t.filePath === path)
  if (tab) {
    tab.originalContent = content
    tab.content = content
    tab.isDirty = false
  }
}

// 文件内容变更回调
const onFileContentChange = (path: string, content: string) => {
  const tab = tabs.value.find(t => t.type === 'file' && t.filePath === path)
  if (tab) {
    tab.content = content
    tab.isDirty = content !== tab.originalContent
  }
}

// Git 文件双击回调
const onGitFileDblClick = (filePath: string, projectRoot: string) => {
  openDiffTab(filePath, projectRoot)
}

// 撤销文件改动
const handleRevertFile = async (filePath: string) => {
  const projectRoot = settingsStore.projectRoot
  if (!projectRoot) return
  try {
    const result = await gitRestore(projectRoot, filePath)
    if (result.success) {
      // 关闭当前的 diff 标签
      const tabId = `diff-${filePath}`
      closeTab(tabId)
      // 如果有对应的文件编辑标签，也关闭（内容已变化）
      const fileTabId = `file-${filePath}`
      const fileTab = tabs.value.find(t => t.id === fileTabId)
      if (fileTab) closeTab(fileTabId)
    } else {
      console.error('撤销失败:', result.error)
    }
  } catch (e: any) {
    console.error('撤销文件失败:', e)
  }
}

// 打开差异对比标签（Git 双击）
const openDiffTab = async (filePath: string, projectRoot: string) => {
  const tabId = `diff-${filePath}`
  const existing = tabs.value.find(t => t.id === tabId)
  if (existing) {
    activeTabId.value = tabId
    return
  }
  try {
    const result = await getGitDiff(projectRoot, filePath)
    if (result.success) {
      tabs.value.push({
        id: tabId,
        type: 'diff',
        title: filePath,
        filePath: filePath,
        diffContent: result.diff
      })
      activeTabId.value = tabId
    }
  } catch (e: any) {
    console.error('获取 diff 失败:', e)
  }
}

const conversations = ref<Conversation[]>([])
const messages = ref<Record<string, ChatMessage[]>>({})
const currentConversationId = ref<string>('')
const inputMessage = ref('')
const isSending = ref(false)
const isLoadingConversations = ref(false)
const isLoadingMessages = ref(false)
const scrollerRef = ref<any>(null)
const collapsed = ref(false)
const sidebarTab = ref<'conversation' | 'files' | 'git' | 'skills'>('conversation')
const stopAbortController = ref<AbortController | null>(null)
// 当用户点击「应用」时更新此值，触发文件树加载
const fileTreeLoadPath = ref('')
const showDirBrowser = ref(false)
const pendingQuestion = ref<{ uuid: string; question: string } | null>(null)
const pendingQuestionAnswer = ref('')
const streamStatus = ref('')
const taskStartTime = ref<number | null>(null)
const elapsedTime = ref(0)
let elapsedTimer: ReturnType<typeof setInterval> | null = null

const startElapsedTimer = () => {
  stopElapsedTimer()
  taskStartTime.value = Date.now()
  elapsedTime.value = 0
  elapsedTimer = setInterval(() => {
    if (taskStartTime.value) {
      elapsedTime.value = Date.now() - taskStartTime.value
    }
  }, 1000)
}

const stopElapsedTimer = () => {
  if (elapsedTimer !== null) {
    clearInterval(elapsedTimer)
    elapsedTimer = null
  }
  taskStartTime.value = null
}

const formatElapsed = (ms: number): string => {
  const totalSec = Math.floor(ms / 1000)
  const hours = Math.floor(totalSec / 3600)
  const minutes = Math.floor((totalSec % 3600) / 60)
  const secs = totalSec % 60
  if (hours > 0) return `${hours}h ${minutes}m ${secs}s`
  if (minutes > 0) return `${minutes}m ${secs}s`
  return `${secs}s`
}


// 任务清单状态（从 task_manager 工具调用结果中解析）
interface TaskItem {
  id: string
  description: string
  status: 'pending' | 'completed' | 'executing'
  priority?: 'HIGH' | 'MEDIUM' | 'LOW'
  depends_on?: string[]
}
const taskItems = ref<TaskItem[]>([])
const completedTaskCount = computed(() => taskItems.value.filter(t => t.status === 'completed').length)
const showTaskDropdown = ref(false)
// 「执行中」的任务：当 isSending 或 activeTask 时，第一个 pending 任务视为 executing
const executingTaskId = computed(() => {
  if (!isSending.value && !activeTask.value) return null
  const first = taskItems.value.find(t => t.status === 'pending')
  return first?.id ?? null
})
// 切换任务下拉
const toggleTaskDropdown = () => {
  if (taskItems.value.length > 0) {
    showTaskDropdown.value = !showTaskDropdown.value
  }
}
// 清除任务清单（新消息或刷新页面时调用）
const clearTaskList = () => { taskItems.value = [] }
const activeTask = ref<{ taskId: number; status: string; iteration: number; eventCount: number; pendingQuestionUuid?: string; pendingQuestionText?: string } | null>(null)

// Markdown 渲染缓存（避免历史消息重复解析 markdown，性能优化）
// key=content原文, value=渲染后的HTML，最大缓存200条
const markdownCache = new Map<string, string>()
const thinkingCache = new Map<string, string>()

const toggleCollapsed = () => { collapsed.value = !collapsed.value }

// 文件树选择
const onFileSelect = (_path: string, _isDirectory: boolean) => {
  // 可以后续扩展为预览文件内容等
}

// 会话相关
const currentMessages = computed(() => messages.value[currentConversationId.value] || [])

const totalContextTokens = computed(() => {
  return currentMessages.value.reduce((sum, msg) => sum + (msg.tokenCount || 0), 0)
})

const fetchConversations = async () => {
  isLoadingConversations.value = true
  try {
    const response = await getConversationList('code_assistant')
    if (response.code === 200 && response.data) {
      conversations.value = response.data.map(mapConversationResponseToConversation)
      // 默认选中第一个非本地会话
      if (!currentConversationId.value && conversations.value.length > 0) {
        const firstReal = conversations.value.find(c => !c.isLocal)
        if (firstReal) {
          selectConversation(firstReal.id)
        }
      }
    }
  } catch (error) {
    console.error('获取会话列表失败:', error)
  } finally {
    isLoadingConversations.value = false
  }
}

const fetchMessages = async (conversationId: string, force = false) => {
  if (!conversationId) return
  if (!force && messages.value[conversationId]) return // 已缓存（首次加载免重复请求）
  isLoadingMessages.value = true
  try {
    const response = await getConversationMessages(parseInt(conversationId))
    if (response.code === 200 && response.data) {
      const groups = processMessageGroups(response.data)
      messages.value[conversationId] = groups as ChatMessage[]
    }
  } catch (error) {
    console.error('获取消息失败:', error)
  } finally {
    isLoadingMessages.value = false
  }
}

// 监听消息加载完成后滚动到底部
watch(isLoadingMessages, (loading) => {
  if (!loading) {
    scrollToBottom()
  }
})

const selectConversation = (id: string) => {
  currentConversationId.value = id
  fetchMessages(id)
  // 检查是否有正在运行的后台任务
  checkAndReconnect(id)
}

const startNewChat = () => {
  const id = `local-${Date.now()}`
  const conv: Conversation = {
    id,
    title: '编码对话',
    updatedAt: Date.now(),
    messageCount: 0,
    isLocal: true
  }
  conversations.value.unshift(conv)
  currentConversationId.value = id
  messages.value[id] = []
}

const deleteConversation = (id: string) => {
  Modal.confirm({
    title: '删除会话',
    content: '确定要删除这个会话吗？',
    okText: '删除',
    okType: 'danger',
    cancelText: '取消',
    onOk: async () => {
      try {
        if (!id.startsWith('local-')) {
          await deleteConversationApi(Number(id))
        }
        conversations.value = conversations.value.filter(c => c.id !== id)
        if (currentConversationId.value === id) {
          const remaining = conversations.value.filter(c => !c.isLocal)
          currentConversationId.value = remaining[0]?.id || ''
        }
      } catch (e: any) {
        message.error('删除失败: ' + (e.message || '未知错误'))
      }
    }
  })
}

// 执行模式切换
const handleModeChange = (val: string) => {
  settingsStore.setMode('code_assistant', val as 'auto' | 'manual')
}

// 模型与思考模式切换
const handleModelChange = (val: string) => {
  settingsStore.setModel('code_assistant', val as 'deepseek-v4-flash' | 'deepseek-v4-pro')
}
const handleThinkingModeChange = (val: string) => {
  settingsStore.setThinkingMode('code_assistant', val as 'non-thinking' | 'thinking' | 'thinking_max')
}

// 停止流式响应
const stopStreaming = () => {
  if (stopAbortController.value) {
    const controller = stopAbortController.value
    stopAbortController.value = null
    controller.abort()
    // 重连模式下同时取消后端任务
    if (reconnectLiveMsg.value && currentConversationId.value) {
      const convId = parseInt(currentConversationId.value)
      if (!isNaN(convId)) cancelTask(convId)
    }
  }
}

// 设置项目工作目录
const reloadFileTree = () => {
  if (settingsStore.projectRoot) {
    fileTreeLoadPath.value = settingsStore.projectRoot
  }
}

// 调用原生目录选择对话框（Electron）或目录浏览器（浏览器）
const selectProjectRoot = async () => {
  if ((window as any).electronAPI?.selectDirectory) {
    const dir = await (window as any).electronAPI.selectDirectory()
    if (dir) {
      settingsStore.projectRoot = dir
      reloadFileTree()
    }
  } else {
    // 浏览器环境：弹出目录浏览器弹窗
    showDirBrowser.value = true
  }
}

// 目录浏览器选择完成回调
const onDirSelected = (path: string) => {
  settingsStore.projectRoot = path
  showDirBrowser.value = false
  reloadFileTree()
}

// ask_user 回答函数
const submitPendingAnswer = async () => {
  if (!pendingQuestion.value || !pendingQuestionAnswer.value.trim()) return
  const q = pendingQuestion.value
  const answer = pendingQuestionAnswer.value.trim()
  try {
    await submitAnswer(q.uuid, answer)
    pendingQuestion.value = null
    pendingQuestionAnswer.value = ''
    message.success('回答已发送')
  } catch (e: any) {
    message.error('回答发送失败: ' + (e.message || '未知错误'))
  }
}

// 节流渲染：将流式更新聚合并限制在每 150ms 一次，避免高频 chunk 导致卡死
let updateTimer: number | null = null

const scheduleMessageUpdate = (convId: string) => {
  if (updateTimer !== null) return
  updateTimer = window.setTimeout(() => {
    updateTimer = null
    if (messages.value[convId]) {
      // 只替换数组引用触发 DynamicScroller 更新，不替换消息对象（避免切断响应式连接导致流式内容不更新）
      messages.value[convId] = [...messages.value[convId]]
    }
    scrollToBottom()
  }, 150)
}

const flushMessageUpdate = (convId: string) => {
  if (updateTimer !== null) {
    clearTimeout(updateTimer)
    updateTimer = null
  }
  if (messages.value[convId]) {
    messages.value[convId] = [...messages.value[convId]]
  }
  scrollToBottom()
}

// 发送消息
// 发送消息
const sendMessage = async () => {
  const text = inputMessage.value.trim()
  if (!text || isSending.value) return
  // 发送新消息时清除旧的任务清单
  clearTaskList()

  // 构建最终消息：将附件内容拼接到用户消息前
  let finalMessage = text
  if (attachedFiles.value.length > 0) {
    const attachmentBlocks: string[] = []
    for (const att of attachedFiles.value) {
      if (att.content) {
        attachmentBlocks.push(`--- 附件: ${att.fileName} ---\n${att.content}\n--- 附件结束 ---`)
      } else if (att.image) {
        attachmentBlocks.push(`[用户上传了图片: ${att.fileName}（${formatFileSize(att.size)}）]`)
      }
    }
    if (attachmentBlocks.length > 0) {
      finalMessage = attachmentBlocks.join('\n\n') + '\n\n' + text
    }
  }

  let convId = currentConversationId.value
  // 如果是新会话，需要找到或创建真实的会话ID
  if (!convId || convId.startsWith('local-')) {
    // 需要创建真实会话
    // 先发消息让后端创建会话
  }

  // 添加用户消息
  const userMsg: ChatMessage = {
    id: `user-${Date.now()}`,
    role: 'user',
    content: finalMessage,
    timestamp: Date.now(),
    tokenCount: estimateTokenCount(finalMessage)
  }

  // 清空附件列表
  attachedFiles.value = []

  if (!messages.value[convId]) {
    messages.value[convId] = []
  }
  messages.value[convId].push(userMsg)
  inputMessage.value = ''
  isSending.value = true
  startElapsedTimer()
  streamStatus.value = '正在连接...'

  // 添加占位的助手消息
  const assistantMsg: ChatMessage = {
    id: `assistant-${Date.now()}`,
    role: 'assistant',
    content: '',
    thinking: '',
    toolResults: [],
    timestamp: Date.now(),
    isStreaming: true
  }
  messages.value[convId].push(assistantMsg)
  scrollToBottom()

  // 更新会话标题
  const conv = conversations.value.find(c => c.id === convId)
  if (conv && conv.isLocal) {
    conv.title = text.length > 12 ? text.substring(0, 12) + '...' : text
  }

  const abortCtrl = new AbortController()
  stopAbortController.value = abortCtrl

  try {
    const sessionId = convId.startsWith('local-') ? undefined : parseInt(convId)

    // 构建thinking内容
    let thinkingContent = ''

    for await (const event of streamChat(finalMessage, sessionId, {
      promptFileName: PROMPT_FILE,
      executionMode: settingsStore.getMode('code_assistant'),
      projectRoot: settingsStore.projectRoot || undefined,
      model: settingsStore.getModel('code_assistant'),
      thinkingMode: settingsStore.getThinkingMode('code_assistant')
    }, abortCtrl)) {
      if (event.type === 'thinking') {
        streamStatus.value = '思考分析中...'
        // 检测工具调用结果标记，分离存储
        const markerIdx = event.data.indexOf('----工具调用:----')
        if (markerIdx !== -1) {
          streamStatus.value = '执行工具中...'
          const contentStart = event.data.indexOf('\n', markerIdx)
          const toolContent = contentStart !== -1
            ? event.data.substring(contentStart + 1).trim()
            : ''
          if (toolContent) {
            assistantMsg.toolResults!.push({
              at: thinkingContent.length,
              content: toolContent
            })
            // 解析 task_manager 工具的任务清单
            console.log('[TaskList] toolContent:', toolContent.substring(0, 200))
            parseTaskManagerResult(toolContent)
          }
        } else {
          thinkingContent += event.data
        }
        assistantMsg.thinking = thinkingContent
        // 节流刷新
        scheduleMessageUpdate(convId)
      } else if (event.type === 'content') {
        streamStatus.value = '正在生成回答...'
        assistantMsg.content += event.data
        // 实时更新消息内容
        scheduleMessageUpdate(convId)
      } else if (event.type === 'complete' && event.sessionId) {
        // 更新会话ID
        const newId = String(event.sessionId)
        if (convId.startsWith('local-') && newId) {
          messages.value[newId] = messages.value[convId]
          delete messages.value[convId]
          currentConversationId.value = newId
          convId = newId
          // 更新会话列表
          const localConv = conversations.value.find(c => c.id === convId)
          if (localConv) {
            localConv.id = newId
            localConv.isLocal = false
          }
          // 刷新会话列表
          fetchConversations()
        }
      } else if (event.type === 'ask_user') {
        try {
          pendingQuestion.value = { uuid: event.data.uuid, question: event.data.question }
          pendingQuestionAnswer.value = ''
        } catch (e) {
          console.warn('解析 ask_user 事件失败:', e)
        }
      }
    }

    // 计算token
    const fullContent = assistantMsg.content
    const fullThinking = assistantMsg.thinking
    const toolResultsText = (assistantMsg.toolResults || []).map(r => r.content).join('')
    assistantMsg.tokenCount = estimateTokenCount(fullContent) + (fullThinking ? estimateTokenCount(fullThinking) : 0) + estimateTokenCount(toolResultsText)
    assistantMsg.isStreaming = false
    streamStatus.value = ''
    flushMessageUpdate(convId)

    // 消息不为空时更新会话标题
    if (conv && !conv.isLocal) {
      // 从后端刷新获取标题
      fetchConversations()
    }
  } catch (error: any) {
    if (error.message === '__USER_ABORT__') {
      console.log('用户中断流式响应')
      assistantMsg.isStreaming = false
      streamStatus.value = ''
      flushMessageUpdate(convId)
      return
    }
    console.error('发送消息失败:', error)
    assistantMsg.content = error.message || '发送失败，请重试'
    assistantMsg.isStreaming = false
    streamStatus.value = ''
    flushMessageUpdate(convId)
    message.error('发送失败: ' + (error.message || '未知错误'))
  } finally {
    isSending.value = false
    stopElapsedTimer()
    streamStatus.value = ''
    scrollToBottom()
  }
}

const handleShiftEnter = () => {
  // Shift+Enter 会由 textarea 自动处理为换行
}

// ===== 后台任务重连 =====

const reconnectLiveMsg = ref<{ content: string; thinking: string } | null>(null)
// 重连消息的渲染节流
let reconnectRenderTimer: ReturnType<typeof setTimeout> | null = null

const reconnectToTaskStream = async (convId: number) => {
  streamStatus.value = '任务恢复中...'
  isSending.value = true
  startElapsedTimer()
  const stringConvId = String(convId)

  // 用单独的重连消息显示实时流式输出，不污染 messages 列表
  reconnectLiveMsg.value = { content: '', thinking: '' }
  let thinkingContent = ''

  // 创建 AbortController 用于终止按钮
  const abortCtrl = new AbortController()
  stopAbortController.value = abortCtrl

  // 渲染节流：每秒最多更新 8 次 DOM
  const scheduleRender = () => {
    if (reconnectRenderTimer) return
    reconnectRenderTimer = setTimeout(() => {
      reconnectRenderTimer = null
      // 触发 Vue 响应式更新（shallowRef 需手动触发）
      if (reconnectLiveMsg.value) {
        reconnectLiveMsg.value = { ...reconnectLiveMsg.value }
      }
      scrollToBottom()
    }, 125)
  }

  try {
    for await (const event of taskStream(convId, abortCtrl)) {
      if (event.type === 'thinking') {
        const markerIdx = typeof event.data === 'string' ? event.data.indexOf('----工具调用:----') : -1
        if (markerIdx !== -1) {
          streamStatus.value = '执行工具中...'
          const contentStart = event.data.indexOf('\n', markerIdx)
          const toolContent = contentStart !== -1
            ? event.data.substring(contentStart + 1).trim()
            : ''
          if (toolContent) {
            thinkingContent += '\n' + toolContent
            parseTaskManagerResult(toolContent)
          }
        } else if (typeof event.data === 'string') {
          thinkingContent += event.data
        }
        reconnectLiveMsg.value.thinking = thinkingContent
        scheduleRender()
      } else if (event.type === 'content') {
        streamStatus.value = '正在生成回答...'
        reconnectLiveMsg.value.content += event.data
        scheduleRender()
      } else if (event.type === 'ask_user') {
        // 手动模式下权限审批弹窗
        streamStatus.value = '等待用户授权...'
        pendingQuestion.value = { uuid: event.data.uuid, question: event.data.question }
        pendingQuestionAnswer.value = ''
      } else if (event.type === 'resume') {
        streamStatus.value = '继续执行中...'
      } else if (event.type === 'complete') {
        break
      }
    }
  } catch (error: any) {
    if (error?.name === 'AbortError') {
      console.log('重连任务流被用户终止')
    } else {
      console.warn('重连任务流失败:', error)
    }
  } finally {
    if (reconnectRenderTimer) {
      clearTimeout(reconnectRenderTimer)
      reconnectRenderTimer = null
    }
    reconnectLiveMsg.value = null
    streamStatus.value = ''
    isSending.value = false
    stopElapsedTimer()
    activeTask.value = null
    stopAbortController.value = null
    // 任务可能已完成或有新消息，从 DB 强制刷新最新消息列表（绕过缓存）
    await fetchMessages(stringConvId, true)
  }
}

const checkAndReconnect = async (convId: string) => {
  if (convId.startsWith('local-')) return
  const task = await checkActiveTask(parseInt(convId))
  if (task.active) {
    activeTask.value = task as any
    // 如果有待审批问题，立即展示审批对话框（页面刷新后重连）
    if (task.pendingQuestionUuid) {
      pendingQuestion.value = { uuid: task.pendingQuestionUuid, question: task.pendingQuestionText || '请确认是否执行以上操作' }
      pendingQuestionAnswer.value = ''
      message.info('检测到有待审批的操作，请确认')
    }
    if (task.status === 'running') {
      // 任务仍在运行，连接事件流追踪进度
      reconnectToTaskStream(parseInt(convId))
    } else {
      // 任务已完成/失败/取消，清除 activeTask（数据已在 DB 中）
      activeTask.value = null
    }
  }
}

// 思考过程可见性
const thinkingVisible = ref<Set<string>>(new Set())

const toggleThinkingVisibility = (msgId: string) => {
  const s = new Set(thinkingVisible.value)
  if (s.has(msgId)) s.delete(msgId)
  else s.add(msgId)
  thinkingVisible.value = s
}

const getThinkingVisible = (msgId: string) => thinkingVisible.value.has(msgId)

// 格式化
const formatTime = (timestamp: number) => {
  const d = new Date(timestamp)
  const now = new Date()
  const isToday = d.toDateString() === now.toDateString()
  if (isToday) {
    return `${String(d.getHours()).padStart(2, '0')}:${String(d.getMinutes()).padStart(2, '0')}`
  }
  return `${d.getMonth() + 1}/${d.getDate()} ${String(d.getHours()).padStart(2, '0')}:${String(d.getMinutes()).padStart(2, '0')}`
}

const formatMessageTime = (timestamp: number) => {
  const d = new Date(timestamp)
  return `${String(d.getHours()).padStart(2, '0')}:${String(d.getMinutes()).padStart(2, '0')}:${String(d.getSeconds()).padStart(2, '0')}`
}

const formatThinking = (thinking: string | undefined, toolResults?: { at: number; content: string }[]) => {
  if (!thinking && (!toolResults || toolResults.length === 0)) return ''
  thinking = thinking || ''
  // 缓存 key 包含 thinking 全文和 toolResults 摘要，不同 toolResults 产生不同 HTML
  const cacheKey = thinking + '|' + JSON.stringify(toolResults?.map(t => ({ at: t.at, len: t.content.length })))
  const cached = thinkingCache.get(cacheKey)
  if (cached !== undefined) return cached
  const renderResult = (() => {
  // 工具调用结果按位置注入
  if (toolResults && toolResults.length > 0) {
    const sorted = [...toolResults].sort((a, b) => a.at - b.at)
    const parts: string[] = []
    let lastPos = 0
    for (const tr of sorted) {
      if (tr.at > lastPos) {
        parts.push(renderMarkdown(thinking.slice(lastPos, tr.at)))
      }
      const escaped = tr.content.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
      parts.push(`<details class="thinking-tool-result"><summary>🔧 工具调用结果</summary><pre>${escaped}</pre></details>`)
      lastPos = tr.at
    }
    if (lastPos < thinking.length) {
      parts.push(renderMarkdown(thinking.slice(lastPos)))
    }
    return parts.join('\n')
  }

  return renderMarkdown(thinking)
})()
  // 缓存渲染结果
  if (thinkingCache.size >= 200) {
    const firstKey = thinkingCache.keys().next().value
    if (firstKey) thinkingCache.delete(firstKey)
  }
  thinkingCache.set(cacheKey, renderResult)
  return renderResult
}

const formatMessage = (content: string) => {
  if (!content) return ''
  // 缓存命中直接返回，避免重复解析 markdown（核心性能优化）
  const cached = markdownCache.get(content)
  if (cached !== undefined) return cached
  const html = renderMarkdown(content)
  // LRU 淘汰：缓存超过上限时删除最早条目
  if (markdownCache.size >= 200) {
    const firstKey = markdownCache.keys().next().value
    if (firstKey) markdownCache.delete(firstKey)
  }
  markdownCache.set(content, html)
  return html
}

// 滚动（双重保障：nextTick + rAF + 延时兜底，适配 DynamicScroller 渲染时机）
const scrollToBottom = () => {
  const doScroll = () => {
    if (scrollerRef.value) {
      const el = scrollerRef.value.$el as HTMLElement
      if (el) {
        el.scrollTop = el.scrollHeight
      }
    }
  }
  nextTick(() => {
    requestAnimationFrame(() => {
      doScroll()
      // DynamicScroller 虚拟渲染可能需要额外一帧才能算出正确 scrollHeight
      requestAnimationFrame(() => doScroll())
    })
  })
}

// 监听消息数量变化自动滚动（替代 deep:true 深度监听，避免每次渲染触发全量比较）
watch(() => currentMessages.value.length + '|' + (currentMessages.value[currentMessages.value.length - 1]?.content?.length || 0), () => {
  scrollToBottom()
})

onMounted(() => {
  fetchConversations()
  scrollToBottom()
  // 如有已保存的工作目录，自动加载文件树
  if (settingsStore.projectRoot) {
    fileTreeLoadPath.value = settingsStore.projectRoot
  }
  // 点击外部关闭任务下拉
  document.addEventListener('click', (e) => {
    const target = e.target as HTMLElement
    if (!target.closest('.task-trigger') && !target.closest('.task-dropdown')) {
      showTaskDropdown.value = false
    }
  })
})

// 当首次加载完会话列表且选中了会话后，检查活跃任务
// ===== 任务清单解析 =====
const parseTaskManagerResult = (content: string) => {
  // 优先解析结构化 JSON（---TASK_JSON--- 分隔）
  const jsonMarkerIdx = content.indexOf('---TASK_JSON---')
  if (jsonMarkerIdx !== -1) {
    const jsonStr = content.substring(jsonMarkerIdx + '---TASK_JSON---'.length).trim()
    try {
      const parsed = JSON.parse(jsonStr)
      if (Array.isArray(parsed) && parsed.length > 0) {
        const existing = taskItems.value
        for (const item of parsed) {
          const idx = existing.findIndex(t => t.id === item.id)
          const st = item.status === 'completed' ? 'completed' as const : 'pending' as const
          if (idx !== -1) {
            existing[idx].status = st
            if (item.priority) existing[idx].priority = item.priority
            if (item.depends_on) existing[idx].depends_on = item.depends_on
          } else {
            existing.push({
              id: item.id,
              description: item.description,
              status: st,
              priority: item.priority,
              depends_on: item.depends_on
            })
          }
        }
        taskItems.value = [...existing]
        return
      }
    } catch (e) {
      // JSON 解析失败，回退到文本解析
    }
  }

  // 兼容旧格式：处理 complete 操作: "✅ 任务 T1 已完成！"
  const completeMatch = content.match(/✅\s*任务\s+(T\d+)\s*已完成/)
  if (completeMatch) {
    const tid = completeMatch[1]
    const existing = taskItems.value
    const found = existing.find(t => t.id === tid)
    if (found) { found.status = 'completed'; taskItems.value = [...existing] }
    return
  }
  // 处理 reopen 操作: "🔄 任务 T1 已重新打开"
  const reopenMatch = content.match(/🔄\s*任务\s+(T\d+)\s*已重新打开/)
  if (reopenMatch) {
    const tid = reopenMatch[1]
    const existing = taskItems.value
    const found = existing.find(t => t.id === tid)
    if (found) { found.status = 'pending'; taskItems.value = [...existing] }
    return
  }
  // 处理批量操作结果（兼容）
  const batchMatch = content.match(/✅\s*批量完成任务：(.+)/)
  if (batchMatch) {
    const ids = batchMatch[1].split(',').map(s => s.trim())
    const existing = taskItems.value
    for (const tid of ids) {
      const found = existing.find(t => t.id === tid)
      if (found) found.status = 'completed'
    }
    taskItems.value = [...existing]
    return
  }
  const batchReopenMatch = content.match(/🔄\s*批量重开任务：(.+)/)
  if (batchReopenMatch) {
    const ids = batchReopenMatch[1].split(',').map(s => s.trim())
    const existing = taskItems.value
    for (const tid of ids) {
      const found = existing.find(t => t.id === tid)
      if (found) found.status = 'pending'
    }
    taskItems.value = [...existing]
    return
  }
  if (!content.includes('任务清单') && !content.includes('任务 ')) return
  const lines = content.split('\n')
  const parsedTasks: TaskItem[] = []
  for (const line of lines) {
    const trimmed = line.trim()
    if (!trimmed) continue
    const parts = trimmed.split('-')
    if (parts.length < 2) continue
    const firstPart = parts[0].trim()
    const idMatch = firstPart.match(/(T\d+)/)
    if (!idMatch) continue
    const id = idMatch[1]
    const desc = parts.slice(1).join('-').trim()
    const cleaned = desc.replace(/[[✅⏳].*?]/g, '').trim()
    if (!cleaned) continue
    const isCompleted = trimmed.includes('✅')
    parsedTasks.push({ id, description: cleaned, status: isCompleted ? 'completed' : 'pending' })
  }
  if (parsedTasks.length > 0) {
    const existing = taskItems.value
    if (existing.length > 0) {
      for (const newTask of parsedTasks) {
        const idx = existing.findIndex(t => t.id === newTask.id)
        if (idx !== -1) existing[idx].status = newTask.status
        else existing.push(newTask)
      }
      taskItems.value = [...existing]
    } else {
      taskItems.value = parsedTasks
    }
  }
}

watch(currentConversationId, (newId) => {
  if (newId && !newId.startsWith('local-')) {
    checkAndReconnect(newId)
  }
})
</script>

<style scoped>
.chat-container {
  display: flex;
  height: 100%;
  background-color: #f7f9fc;
}

/* ===== 侧边栏 ===== */
.sidebar {
  width: 280px;
  background: #fafbfc;
  border-right: 1px solid #eef2f7;
  display: flex;
  flex-direction: column;
  height: 100%;
  transition: width 0.25s ease;
  overflow: hidden;
  flex-shrink: 0;
}
.sidebar.collapsed { width: 80px; }

.sidebar-header {
  padding: 12px 8px;
  border-bottom: 1px solid #eef2f7;
  background: #f8fafc;
  display: flex;
  align-items: center;
  gap: 4px;
  flex-shrink: 0;
}

.sidebar-tabs {
  display: flex;
  background: #f0f2f5;
  border-radius: 6px;
  padding: 2px;
  flex: 1;
  min-width: 0;
}

.tab-btn {
  flex: 1;
  border: none;
  background: transparent;
  padding: 4px 0;
  font-size: 12px;
  color: #666;
  cursor: pointer;
  border-radius: 4px;
  transition: all 0.2s;
  font-family: inherit;
}
.tab-btn.active {
  background: white;
  color: #1a202c;
  font-weight: 600;
  box-shadow: 0 1px 2px rgba(0,0,0,0.08);
}

.tab-full { display: inline; }
.tab-short { display: none; }

.collapsed .sidebar-tabs { flex: 1; }
.collapsed .tab-full { display: none; }
.collapsed .tab-short { display: inline; }

.sidebar-collapse-btn {
  cursor: pointer;
  font-size: 18px;
  color: #8c8c8c;
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
  transition: color 0.2s;
}
.sidebar-collapse-btn:hover { color: #1890ff; }

.conversation-list {
  flex: 1;
  overflow-y: auto;
  padding: 8px;
  background: #fafbfc;
}
.filetree-panel {
  flex: 1;
  overflow-y: auto;
  background: #fafbfc;
}

.conversation-item {
  display: flex;
  align-items: center;
  padding: 8px 10px;
  border-radius: 6px;
  cursor: pointer;
  margin-bottom: 4px;
  transition: all 0.15s;
  border: 1px solid transparent;
  background: white;
  box-shadow: 0 1px 2px rgba(0,0,0,0.04);
}
.conversation-item:hover {
  background-color: #f8fafc;
  border-color: #e1e8f0;
}
.conversation-item.active {
  background-color: #f0f9ff;
  border-color: #bae6fd;
}
.conv-icon {
  margin-right: 10px;
  color: #1677ff;
  font-size: 16px;
  flex-shrink: 0;
}
.conv-info {
  flex: 1;
  min-width: 0;
}
.conv-title {
  font-weight: 500;
  color: #1a202c;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  font-size: 12px;
}
.conv-time {
  font-size: 10px;
  color: #8c8c8c;
  margin-top: 1px;
}
.conv-actions {
  opacity: 0;
  transition: opacity 0.15s;
  flex-shrink: 0;
}
.conversation-item:hover .conv-actions { opacity: 1; }

.collapsed .conv-info,
.collapsed .conv-actions { display: none; }
.collapsed .conversation-item {
  padding: 8px 0;
  justify-content: center;
}
.collapsed .conv-icon { margin-right: 0; font-size: 18px; }
.collapsed .conversation-list { padding: 8px 4px; }

.new-chat-btn {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 6px 10px;
  margin-bottom: 8px;
  border-radius: 6px;
  cursor: pointer;
  font-size: 12px;
  color: #1677ff;
  background: #f0f5ff;
  border: 1px dashed #91caff;
  transition: all 0.2s;
}
.new-chat-btn:hover {
  background: #e6f4ff;
  border-color: #1677ff;
}
.collapsed .new-chat-btn {
  justify-content: center;
  padding: 6px 0;
}
.collapsed .new-chat-btn span { display: none; }

.loading-conversations {
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 30px 12px;
  color: #8c8c8c;
  gap: 8px;
}
.empty-conversations {
  text-align: center;
  padding: 30px 12px;
  color: #8c8c8c;
}
.empty-conversations p { margin: 0; font-size: 13px; }
.empty-hint { margin-top: 6px !important; font-size: 11px !important; color: #bbb; }
.collapsed .loading-conversations span,
.collapsed .empty-conversations p { display: none; }

/* ===== 主聊天区域 ===== */
.chat-main {
  flex: 1;
  display: flex;
  flex-direction: column;
  overflow: hidden;
  background: #f7f9fc;
}

/* ===== 标签栏 ===== */
.tab-bar {
  display: flex;
  background: #f0f0f0;
  border-bottom: 1px solid #d9d9d9;
  flex-shrink: 0;
  overflow-x: auto;
  overflow-y: hidden;
  min-height: 32px;
  align-items: stretch;
}
.tab-bar::-webkit-scrollbar { height: 2px; }
.tab-bar::-webkit-scrollbar-thumb { background: #ccc; border-radius: 1px; }

.tab-item {
  display: flex;
  align-items: center;
  gap: 4px;
  padding: 0 12px;
  font-size: 12px;
  color: #666;
  cursor: pointer;
  border-right: 1px solid #d9d9d9;
  background: #f0f0f0;
  white-space: nowrap;
  user-select: none;
  transition: all 0.15s;
  min-width: 0;
  position: relative;
}
.tab-item:hover {
  background: #e6e6e6;
  color: #333;
}
.tab-item.active {
  background: white;
  color: #1a202c;
  font-weight: 600;
  border-bottom: 2px solid #1677ff;
  margin-bottom: -1px;
}
.tab-item.dirty {
  color: #faad14;
}
.tab-title {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  max-width: 160px;
}
.tab-dirty-dot {
  font-size: 10px;
  color: #faad14;
}
.tab-close {
  font-size: 14px;
  line-height: 1;
  color: #999;
  padding: 0 2px;
  border-radius: 3px;
  transition: all 0.15s;
  flex-shrink: 0;
  margin-left: 2px;
}
.tab-close:hover {
  background: #ff4d4f;
  color: white;
}

/* ===== 标签内容区 ===== */
.tab-content {
  flex: 1;
  display: flex;
  flex-direction: column;
  overflow: hidden;
  min-height: 0;
}
.tab-content > .chat-messages-area {
  flex: 1;
  display: flex;
  flex-direction: column;
  overflow: hidden;
  min-height: 0;
}

/* ===== 差异对比标签 ===== */
.diff-tab-content {
  flex: 1;
  display: flex;
  flex-direction: column;
  overflow: hidden;
  background: #f7f9fc;
}
.diff-tab-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 8px 16px;
  background: #fff;
  border-bottom: 1px solid #e8e8e8;
  flex-shrink: 0;
}
.diff-tab-title {
  font-size: 13px;
  font-weight: 600;
  color: #262626;
}
.diff-tab-actions {
  display: flex;
  gap: 8px;
}
.diff-tab-body {
  flex: 1;
  overflow: auto;
  padding: 16px;
}

.message-list {
  flex: 1;
  overflow-y: auto;
  padding: 20px 24px;
  background: #f7f9fc;
}

.message-list-empty {
  flex: 1;
  display: flex;
  align-items: center;
  justify-content: center;
}
.loading-messages {
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 30px;
  color: #8c8c8c;
  gap: 8px;
}

.message-item {
  display: flex;
  margin-bottom: 20px;
  gap: 12px;
}

.message-avatar {
  flex-shrink: 0;
}

.avatar {
  width: 36px;
  height: 36px;
  border-radius: 50%;
  display: flex;
  align-items: center;
  justify-content: center;
  font-weight: bold;
  font-size: 14px;
}
.user-avatar {
  background: linear-gradient(135deg, #667eea, #764ba2);
  color: white;
}
.ai-avatar {
  background: linear-gradient(135deg, #1677ff, #0958d9);
  color: white;
  font-size: 18px;
}

.message-content {
  flex: 1;
  min-width: 0;
}

.message-header {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 6px;
  flex-wrap: wrap;
}

.message-sender {
  font-weight: 600;
  font-size: 13px;
  color: #262626;
}

.message-time {
  font-size: 11px;
  color: #bbb;
}

.message-token {
  font-size: 11px;
  color: #bbb;
}

.streaming-indicator {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  font-size: 11px;
  color: #1677ff;
}
.streaming-dot {
  width: 6px;
  height: 6px;
  background: #1677ff;
  border-radius: 50%;
  animation: pulse 1.2s ease-in-out infinite;
}
@keyframes pulse {
  0%, 100% { opacity: 1; }
  50% { opacity: 0.3; }
}

.message-text {
  font-size: 14px;
  line-height: 1.7;
  color: #262626;
  word-wrap: break-word;
  overflow-wrap: break-word;
}

/* thinking section */
.thinking-section {
  margin-bottom: 10px;
  border: 1px solid #e2e8f0;
  border-radius: 8px;
  overflow: hidden;
  background-color: #f8fafc;
}
.thinking-header {
  display: flex;
  align-items: center;
  gap: 4px;
  padding: 6px 12px;
  cursor: pointer;
  user-select: none;
  background: #f1f5f9;
  font-size: 12px;
}
.thinking-header:hover { background: #e9edf2; }
.thinking-title { font-weight: 600; color: #475569; flex: 1; }
.thinking-hint { font-size: 11px; color: #94a3b8; }
.thinking-content { padding: 8px 12px; }
.thinking-text {
  font-size: 13px;
  line-height: 1.6;
  color: #475569;
  white-space: pre-wrap;
  word-wrap: break-word;
  overflow-wrap: break-word;
}

/* tool result details (inline collapsible in thinking) */
.thinking-tool-result {
  margin: 6px 0;
  border: 1px solid #d4dce8;
  border-radius: 6px;
  overflow: hidden;
  background: #f0f4fa;
  font-size: 12px;
}
.thinking-tool-result summary {
  padding: 4px 10px;
  cursor: pointer;
  user-select: none;
  font-weight: 600;
  color: #4f46e5;
  background: #eef2f8;
}
.thinking-tool-result summary:hover {
  background: #e4eaf2;
}
.thinking-tool-result pre {
  margin: 0;
  padding: 8px 10px;
  font-family: 'SF Mono', 'Monaco', 'Menlo', 'Ubuntu Mono', monospace;
  font-size: 12px;
  line-height: 1.4;
  color: #334155;
  white-space: pre-wrap;
  word-break: break-all;
}

/* ===== 输入区域 ===== */
.chat-input-area {
  padding: 12px 24px 16px;
  background: white;
  border-top: 1px solid #e8e8e8;
  position: relative;
}
/* ===== 附件上传 ===== */
.attachment-tags {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  margin-bottom: 8px;
  padding: 0 2px;
}
.attachment-tag {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  padding: 2px 8px;
  background: #f0f5ff;
  border: 1px solid #d6e4ff;
  border-radius: 4px;
  font-size: 12px;
  line-height: 22px;
  transition: all 0.15s;
}
.attachment-tag:hover {
  background: #e6f0ff;
}
.attachment-tag.attachment-error {
  background: #fff2f0;
  border-color: #ffccc7;
}
.attachment-icon {
  font-size: 13px;
  flex-shrink: 0;
}
.attachment-name {
  color: #262626;
  max-width: 160px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.attachment-size {
  color: #8c8c8c;
  font-size: 11px;
  flex-shrink: 0;
}
.attachment-uploading {
  color: #1677ff;
  font-size: 12px;
}
.attachment-error-msg {
  color: #ff4d4f;
  font-size: 11px;
  cursor: help;
}
.attachment-remove {
  cursor: pointer;
  color: #8c8c8c;
  font-size: 14px;
  line-height: 1;
  padding: 0 2px;
  transition: color 0.15s;
  flex-shrink: 0;
}
.attachment-remove:hover {
  color: #ff4d4f;
}
/* 附件上传按钮 */
.attach-btn {
  height: 40px;
  width: 40px;
  border-radius: 8px;
  flex-shrink: 0;
  border: 1px dashed #d9d9d9;
  color: #8c8c8c;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  transition: all 0.2s;
}
.attach-btn:hover {
  color: #1677ff;
  border-color: #1677ff;
  background: #f0f5ff;
}
.attach-btn:disabled {
  color: #d9d9d9;
  border-color: #d9d9d9;
  background: transparent;
}


.input-wrapper {
  display: flex;
  gap: 8px;
  align-items: flex-end;
}
.input-wrapper :deep(.ant-input) {
  border-radius: 8px;
  resize: none;
  font-size: 14px;
}
.send-btn {
  height: 40px;
  width: 40px;
  border-radius: 8px;
  flex-shrink: 0;
}

.stop-btn {
  height: 40px;
  width: 40px;
  border-radius: 50%;
  flex-shrink: 0;
  background: rgba(0, 0, 0, 0.5);
  border: none;
  color: white;
  font-size: 16px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  transition: all 0.2s ease;
  box-shadow: 0 2px 6px rgba(0, 0, 0, 0.08);
}
.stop-btn:hover {
  background: #ff4d4f;
  color: white;
  box-shadow: 0 4px 12px rgba(255, 77, 79, 0.3);
  transform: scale(1.05);
}
.stop-btn:active {
  transform: scale(0.95);
}

.input-footer {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-top: 8px;
  flex-wrap: wrap;
  gap: 8px;
}

/* 任务进度触发器（与 mode-selector 风格一致） */
.task-trigger {
  cursor: pointer;
  position: relative;
  transition: all 0.2s;
}
.task-trigger:hover {
  background: #e6f7ff;
  border-color: #91d5ff;
}
.task-trigger.active {
  background: #e6f7ff;
  border-color: #1890ff;
}
.task-trigger.loading .task-trigger-icon {
  color: #1890ff;
}
.task-trigger-icon {
  font-size: 14px;
}
.task-trigger-text {
  font-size: 12px;
  font-weight: 500;
  color: #595959;
  white-space: nowrap;
}

/* 任务下拉面板 */
.task-dropdown {
  position: absolute;
  bottom: 100%;
  left: 12px;
  right: 12px;
  margin-bottom: 8px;
  background: #fff;
  border: 1px solid #d9d9d9;
  border-radius: 8px;
  box-shadow: 0 -4px 16px rgba(0, 0, 0, 0.12);
  overflow: hidden;
  z-index: 100;
  max-height: 240px;
  display: flex;
  flex-direction: column;
}
.task-dropdown-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 8px 12px;
  background: #f0f5ff;
  border-bottom: 1px solid #e8e8e8;
  font-size: 13px;
  font-weight: 600;
  color: #1a202c;
  flex-shrink: 0;
}
.task-dropdown-progress {
  font-size: 12px;
  color: #667eea;
  font-weight: 500;
}
.task-dropdown-body {
  padding: 4px 0;
  overflow-y: auto;
  flex: 1;
}
.task-dropdown-item {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 5px 12px;
  font-size: 12px;
  line-height: 1.5;
  transition: background 0.15s;
}
.task-dropdown-item--completed {
  opacity: 0.55;
}
.task-dropdown-item--executing {
  background: #e6f7ff;
}
.task-dropdown-icon {
  flex-shrink: 0;
  font-size: 13px;
}
.task-dropdown-id {
  flex-shrink: 0;
  font-family: 'SF Mono', 'Monaco', monospace;
  font-size: 11px;
  color: #667eea;
  font-weight: 600;
  min-width: 22px;
}
.task-dropdown-desc {
  color: #334155;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.task-dropdown-priority-high {
  flex-shrink: 0;
  font-size: 10px;
  padding: 0 4px;
  border-radius: 2px;
  background: #fff2f0;
  color: #ff4d4f;
  font-weight: 600;
  line-height: 16px;
}
.task-dropdown-deps {
  flex-shrink: 0;
  font-size: 10px;
  color: #8c8c8c;
  font-family: 'SF Mono', 'Monaco', monospace;
}

.footer-left {
  display: flex;
  align-items: center;
}

.footer-right {
  display: flex;
  align-items: center;
}

.context-tokens {
  color: #8c8c8c;
  font-size: 11px;
}

/* 模式选择器 */
.mode-selector {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 2px 10px 2px 8px;
  background: #f5f5f5;
  border: 1px solid #e8e8e8;
  border-radius: 6px;
  height: 26px;
}

.mode-icon {
  font-size: 12px;
  opacity: 0.55;
}

.mode-label {
  font-size: 11px;
  color: #8c8c8c;
  white-space: nowrap;
}

.mode-select {
  width: 130px;
}

.model-select {
  width: 150px;
}

.model-select :deep(.ant-select-selection-item) {
  font-size: 12px !important;
}

.mode-select :deep(.ant-select-selection-item) {
  font-size: 12px !important;
}

/* Git 侧边栏面板 */
.git-panel {
  flex: 1;
  overflow-y: auto;
  background: #fafbfc;
}

/* 技能面板 */
.skill-panel {
  flex: 1;
  overflow-y: auto;
  background: #fafbfc;
}

/* ===== 流式加载指示器（消息框左下角） ===== */
.stream-indicator {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 8px 24px;
  flex-shrink: 0;
}
.stream-pulse-dot {
  width: 8px;
  height: 8px;
  background: #1677ff;
  border-radius: 50%;
  animation: streamPulse 1.2s ease-in-out infinite;
  flex-shrink: 0;
}
.stream-indicator-text {
  font-size: 13px;
  color: #1677ff;
  font-weight: 500;
}
/* 后台任务重连指示器 */
.task-reconnect-banner {
  background: #fff7e6;
  border-bottom: 1px solid #ffd591;
}
.task-reconnect-banner .stream-indicator-text {
  color: #d46b08;
}
@keyframes streamPulse {
  0%, 100% { opacity: 1; transform: scale(1); }
  50% { opacity: 0.4; transform: scale(0.8); }
}

/* 深色模式适配 */
[data-theme='dark'] .mode-selector {
  background: #1f1f1f;
  border-color: #333;
}

[data-theme='dark'] .mode-selector .mode-label {
  color: #666;
}

[data-theme='dark'] .mode-selector .mode-icon {
  opacity: 0.4;
}
:deep(.code-block-cmd) {
  border: 1px solid #d0d7de;
  border-radius: 6px;
  overflow: hidden;
  font-family: 'SF Mono', 'Monaco', 'Menlo', 'Consolas', monospace;
  font-size: 12px;
  margin: 8px 0;
  background: #f6f8fa;
  color: #24292f;
}
:deep(.cbc-header) {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 5px 10px;
  background: #eef1f5;
  border-bottom: 1px solid #d0d7de;
}
:deep(.cbc-title) { font-weight: 600; font-size: 11px; color: #24292f; }
:deep(.cbc-status) { margin-left: auto; font-size: 10px; font-weight: 600; }
:deep(.cbc-status.success) { color: #1a7f37; }
:deep(.cbc-status.fail) { color: #cf222e; }
:deep(.cbc-body) { padding: 6px 0; }
:deep(.cbc-command) {
  display: flex;
  gap: 6px;
  padding: 2px 10px 6px;
  border-bottom: 1px solid #d0d7de;
  margin-bottom: 4px;
}
:deep(.cbc-prompt) { color: #1a7f37; font-weight: 600; user-select: none; }
:deep(.cbc-output) {
  padding: 0 10px;
  white-space: pre-wrap;
  word-break: break-word;
  line-height: 1.5;
  color: #656d76;
}

:deep(.code-block-filelist) {
  border: 1px solid #e8e8e8;
  border-radius: 6px;
  overflow: hidden;
  font-size: 12px;
  margin: 8px 0;
  background: #fff;
}
:deep(.cbf-header) {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 5px 10px;
  background: #fafafa;
  border-bottom: 1px solid #e8e8e8;
}
:deep(.cbf-title) { font-weight: 600; color: #262626; font-size: 11px; }
:deep(.cbf-count) { margin-left: auto; color: #8c8c8c; font-size: 10px; }
:deep(.cbf-body) { padding: 4px 0; }
:deep(.cbf-item) {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 3px 10px;
  font-family: 'SF Mono', 'Monaco', 'Menlo', monospace;
}
:deep(.cbf-badge) {
  font-size: 9px;
  padding: 0 4px;
  border-radius: 2px;
  font-weight: 600;
  flex-shrink: 0;
  line-height: 16px;
}
:deep(.cbf-item.add .cbf-badge) { background: #f6ffed; color: #52c41a; }
:deep(.cbf-item.mod .cbf-badge) { background: #fff7e6; color: #fa8c16; }
:deep(.cbf-item.del .cbf-badge) { background: #fff2f0; color: #ff4d4f; }
:deep(.cbf-path) { color: #262626; }
:deep(.cbf-summary) { color: #8c8c8c; }

/* ask_user 问答面板 */
.ask-user-panel {
  margin: 0 16px 8px;
  background: #fff;
  border: 1px solid #d9d9d9;
  border-radius: 8px;
  padding: 16px;
  box-shadow: 0 -4px 12px rgba(0, 0, 0, 0.08);
}
.ask-user-header {
  font-weight: 600;
  font-size: 14px;
  color: #faad14;
  margin-bottom: 8px;
}
.ask-user-question {
  font-size: 14px;
  color: #333;
  margin-bottom: 12px;
  line-height: 1.6;
  white-space: pre-wrap;
}
.ask-user-input-row {
  display: flex;
  gap: 8px;
}
.ask-user-input-row .a-input {
  flex: 1;
}

/* ===== 重连实时消息容器 ===== */
.reconnect-container {
  margin: 0 16px 8px;
  border: 1px solid #e8e8e8;
  border-radius: 8px;
  background: #fafafa;
  box-shadow: 0 -4px 12px rgba(0, 0, 0, 0.06);
  overflow: hidden;
}
.reconnect-header {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 8px 16px;
  font-size: 12px;
  color: #666;
  background: #f0f5ff;
  border-bottom: 1px solid #e8e8e8;
}
.reconnect-dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: #1677ff;
  animation: reconnect-pulse 1.2s ease-in-out infinite;
}
@keyframes reconnect-pulse {
  0%, 100% { opacity: 1; }
  50% { opacity: 0.3; }
}
.reconnect-body {
  max-height: 360px;
  overflow-y: auto;
  padding: 12px 16px 4px;
}
.reconnect-body .message-item {
  margin-bottom: 0;
}

.stream-elapsed {
  font-size: 13px;
  color: #1677ff;
  font-weight: 600;
  margin-left: 12px;
  font-variant-numeric: tabular-nums;
  white-space: nowrap;
}
.task-reconnect-banner .stream-elapsed {
  color: #d46b08;
}
</style>

<!-- 非 scoped 样式：markdown 内容排版（v-html 内元素不受 scoped 影响） -->
<style>
/* ===== 代码块 ===== */
.code-message pre {
  background: #f6f8fa;
  border-radius: 6px;
  padding: 12px 16px;
  font-size: 13px;
  line-height: 1.5;
  margin: 8px 0;
  white-space: pre-wrap;
  word-break: break-word;
  overflow-x: hidden;
  border: 1px solid #e1e4e8;
}
.code-message code {
  font-family: 'SF Mono', 'Monaco', 'Menlo', 'Consolas', monospace;
  font-size: 0.9em;
}
.code-message p code {
  background: #f0f2f5;
  padding: 1px 5px;
  border-radius: 3px;
  color: #1677ff;
}
/* 思考过程中代码块 */
.thinking-text pre {
  background: #f6f8fa;
  border: 1px solid #e1e4e8;
  border-radius: 6px;
  padding: 10px 14px;
  white-space: pre-wrap;
  word-break: break-word;
  overflow-x: hidden;
}

/* ===== highlight.js 浅色主题 ===== */
.code-message pre code.hljs,
.thinking-text pre code.hljs {
  background: transparent;
  color: #24292f;
}
.code-message .hljs-keyword,
.thinking-text .hljs-keyword { color: #cf222e; }
.code-message .hljs-string,
.thinking-text .hljs-string { color: #0a3069; }
.code-message .hljs-number,
.thinking-text .hljs-number { color: #0550ae; }
.code-message .hljs-comment,
.thinking-text .hljs-comment { color: #6e7781; font-style: italic; }
.code-message .hljs-title,
.thinking-text .hljs-title { color: #8250df; }
.code-message .hljs-built_in,
.thinking-text .hljs-built_in { color: #0550ae; }
.code-message .hljs-type,
.thinking-text .hljs-type { color: #0550ae; }
.code-message .hljs-literal,
.thinking-text .hljs-literal { color: #0550ae; }
.code-message .hljs-attr,
.thinking-text .hljs-attr { color: #0550ae; }
.code-message .hljs-selector-class,
.thinking-text .hljs-selector-class { color: #0550ae; }
.code-message .hljs-meta,
.thinking-text .hljs-meta { color: #8250df; }
.code-message .hljs-tag,
.thinking-text .hljs-tag { color: #116329; }
.code-message .hljs-name,
.thinking-text .hljs-name { color: #116329; }
.code-message .hljs-attribute,
.thinking-text .hljs-attribute { color: #0550ae; }

/* ===== Diff 高亮 ===== */
.code-message .hljs-addition,
.thinking-text .hljs-addition {
  background: #e6ffed;
  color: #1a7f37;
  display: inline-block;
  width: 100%;
}
.code-message .hljs-deletion,
.thinking-text .hljs-deletion {
  background: #ffebe9;
  color: #cf222e;
  display: inline-block;
  width: 100%;
}
.code-message .hljs-section,
.thinking-text .hljs-section { color: #1677ff; font-weight: 600; }

/* ===== 列表排版 ===== */
.code-message ol,
.code-message ul,
.thinking-text ol,
.thinking-text ul {
  padding-left: 28px;
  margin: 6px 0;
  text-align: left;
}
.code-message li,
.thinking-text li {
  margin-bottom: 4px;
  line-height: 1.7;
  text-align: left;
}
.code-message li p,
.thinking-text li p {
  margin: 0;
  display: inline;
}
.code-message p,
.thinking-text p {
  margin: 6px 0;
  line-height: 1.7;
  text-align: left;
}
.code-message h1,
.code-message h2,
.code-message h3,
.code-message h4,
.thinking-text h1,
.thinking-text h2,
.thinking-text h3,
.thinking-text h4 {
  margin: 14px 0 6px;
  color: #1a202c;
  text-align: left;
}
.code-message blockquote,
.thinking-text blockquote {
  margin: 8px 0;
  padding: 4px 12px;
  border-left: 3px solid #d0d7de;
  color: #57606a;
  background: #f6f8fa;
}
.code-message table,
.thinking-text table {
  border-collapse: collapse;
  margin: 8px 0;
  font-size: 13px;
}
.code-message th,
.code-message td,
.thinking-text th,
.thinking-text td {
  border: 1px solid #d0d7de;
  padding: 6px 10px;
  text-align: left;
}
.code-message th,
.thinking-text th {
  background: #f6f8fa;
  font-weight: 600;
}

</style>
