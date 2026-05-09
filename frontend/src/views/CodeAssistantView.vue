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
        <GitSidebar :project-root="settingsStore.projectRoot || ''" />
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
        <FileTree :root-path="fileTreeLoadPath" @select="onFileSelect" />
        <DirectoryBrowser :visible="showDirBrowser" @select="onDirSelected" @close="showDirBrowser = false" />
      </div>
    </aside>

    <!-- 主聊天区域 -->
    <main class="chat-main">
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
        <DynamicScrollerItem :item="msg" :active="active" :size-dependencies="[msg.content?.length || 0, msg.thinking?.length || 0]">
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
          任务正在后台执行中（第 {{ activeTask.iteration + 1 }} 轮）...
          <a-button size="small" type="link" @click="reconnectToTaskStream(parseInt(currentConversationId))">重新连接</a-button>
        </span>
      </div>

      <!-- 流式加载指示器 -->
      <div v-if="isSending &amp;&amp; streamStatus" class="stream-indicator">
        <span class="stream-pulse-dot"></span>
        <span class="stream-indicator-text">{{ streamStatus }}</span>
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
      <footer class="chat-input-area">
        <div class="input-wrapper">
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
            :disabled="!inputMessage.trim()"
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
          </div>
          <div class="footer-right" v-if="currentMessages.length > 0">
            <span class="context-tokens">上下文 Token: {{ formatTokenCount(totalContextTokens) }}</span>
          </div>
        </div>
      </footer>
    </main>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted, nextTick, watch } from 'vue'
import { message, Modal } from 'ant-design-vue'
import { useUserStore } from '@/store/user'
import { getConversationList, mapConversationResponseToConversation, getConversationMessages, processMessageGroups, deleteConversation as deleteConversationApi } from '@/api/conversation'
import { streamChat, checkActiveTask, taskStream, cancelTask } from '@/api/chat'
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
  MenuUnfoldOutlined,
  PlusOutlined,
  SettingOutlined,
  CloseOutlined,
  FolderOpenOutlined
} from '@ant-design/icons-vue'
import FileTree from '@/components/FileTree.vue'
import GitSidebar from '@/components/GitSidebar.vue'
import SkillList from '@/components/SkillList.vue'
import DirectoryBrowser from '@/components/DirectoryBrowser.vue'
import { DynamicScroller, DynamicScrollerItem } from 'vue-virtual-scroller'
import 'vue-virtual-scroller/dist/vue-virtual-scroller.css'

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
const activeTask = ref<{ taskId: number; status: string; iteration: number; eventCount: number; pendingQuestionUuid?: string; pendingQuestionText?: string } | null>(null)

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

// 节流渲染：将流式更新聚合并限制在每 50ms 一次，避免高频 chunk 导致卡死
let updateTimer: number | null = null

const scheduleMessageUpdate = (convId: string) => {
  if (updateTimer !== null) return
  updateTimer = window.setTimeout(() => {
    updateTimer = null
    if (messages.value[convId]) {
      messages.value[convId] = [...messages.value[convId]]
    }
    scrollToBottom()
  }, 50)
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
const sendMessage = async () => {
  const text = inputMessage.value.trim()
  if (!text || isSending.value) return

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
    content: text,
    timestamp: Date.now(),
    tokenCount: estimateTokenCount(text)
  }

  if (!messages.value[convId]) {
    messages.value[convId] = []
  }
  messages.value[convId].push(userMsg)
  inputMessage.value = ''
  isSending.value = true
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

    for await (const event of streamChat(text, sessionId, {
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
    assistantMsg.tokenCount = estimateTokenCount(fullContent) + (fullThinking ? estimateTokenCount(fullThinking) : 0)
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
}

const formatMessage = (content: string) => {
  return renderMarkdown(content)
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

// 监听消息变化自动滚动
watch(currentMessages, () => {
  scrollToBottom()
}, { deep: true })

onMounted(() => {
  fetchConversations()
  scrollToBottom()
  // 如有已保存的工作目录，自动加载文件树
  if (settingsStore.projectRoot) {
    fileTreeLoadPath.value = settingsStore.projectRoot
  }
})

// 当首次加载完会话列表且选中了会话后，检查活跃任务
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
  font-size: 12px;
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
