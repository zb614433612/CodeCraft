<template>
  <div class="chat-container">
    <!-- 侧边栏 -->
    <aside class="sidebar" :class="{ collapsed }">
      <div class="sidebar-header">
        <div class="sidebar-header-row">
          <a-button type="primary" class="new-chat-btn" @click="createNewProfile">
            <template #icon>
              <plus-outlined />
            </template>
            <span class="btn-text">新增会话</span>
          </a-button>
          <div class="sidebar-collapse-btn" @click="toggleCollapsed">
            <MenuFoldOutlined v-if="!collapsed" />
            <MenuUnfoldOutlined v-else />
          </div>
        </div>
      </div>
      <div class="conversation-list">
        <!-- 加载状态 -->
        <div v-if="isLoadingConversations" class="loading-conversations">
          <a-spin size="small" />
          <span>加载会话中...</span>
        </div>
        <!-- 空状态 -->
        <div v-else-if="conversations.length === 0" class="empty-conversations">
          <p>暂无历史会话</p>
        </div>
        <!-- 会话列表 -->
        <div
          v-for="conv in conversations"
          :key="conv.id"
          :class="['conversation-item', { active: currentConversationId === conv.id }]"
          @click="selectConversation(conv.id)"
        >
          <div class="conv-icon">
            <message-outlined />
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
    </aside>

    <!-- 主聊天区域 -->
    <main class="chat-main">
      <!-- 消息列表 -->
      <div class="message-list" ref="messageListRef">
        <!-- 消息加载状态 -->
        <div v-if="isLoadingMessages" class="loading-messages">
          <a-spin size="small" />
          <span>加载消息中...</span>
        </div>
        <div v-for="msg in currentMessages" :key="msg.id" :class="['message-item', msg.role]">
          <div class="message-avatar">
            <div v-if="msg.role === 'user'" class="avatar user-avatar">
              {{ userStore.userInfo?.username?.charAt(0).toUpperCase() || 'U' }}
            </div>
            <div v-else class="avatar ai-avatar">
              <robot-outlined />
            </div>
          </div>
          <div class="message-content">
            <div class="message-header">
              <span class="message-sender">{{ msg.role === 'user' ? userStore.userInfo?.username || '用户' : 'zb-agent' }}</span>
              <span class="message-time">{{ formatMessageTime(msg.timestamp) }}</span>
              <span class="message-token" v-if="msg.tokenCount !== undefined">· {{ formatTokenCount(msg.tokenCount) }} token</span>
              <span class="message-token" v-else-if="msg.isStreaming">· 计算中...</span>
              <!-- 编辑按钮 -->
              <span v-if="!msg.isStreaming && editingMessageId !== msg.id" class="message-edit-btn" @click="startEdit(msg)">
                <edit-outlined />
              </span>
              <!-- 流式指示器 -->
              <span v-if="msg.isStreaming" class="streaming-indicator">
                <span class="streaming-dot"></span>
                正在输入...
              </span>
            </div>

            <!-- 思考过程（仅AI消息） -->
            <div v-if="msg.role === 'assistant' && msg.thinking" class="thinking-section">
              <div class="thinking-header" @click="toggleThinkingVisibility(msg.id)">
                <span class="thinking-title">
                  <down-outlined v-if="!getThinkingVisible(msg.id)" />
                  <up-outlined v-else />
                  思考过程
                </span>
                <span class="thinking-hint">点击{{ getThinkingVisible(msg.id) ? '收起' : '展开' }}</span>
              </div>
              <div v-if="getThinkingVisible(msg.id)" class="thinking-content">
                <div v-if="msg.thinking || msg.toolResults?.length" class="thinking-text" v-html="formatThinking(msg.thinking, msg.toolResults)"></div>
              </div>
            </div>

            <!-- 消息内容 -->
            <div v-if="editingMessageId === msg.id" class="message-edit-area">
              <a-textarea
                v-model:value="editingContent"
                :auto-size="{ minRows: 3, maxRows: 10 }"
                placeholder="请输入消息内容"
              />
              <div class="edit-actions">
                <a-button size="small" @click="cancelEdit">取消</a-button>
                <a-button size="small" type="primary" :loading="isSavingEdit" @click="saveEdit(msg)">保存</a-button>
              </div>
            </div>
            <div v-else-if="msg.content" class="message-text" v-html="formatMessage(msg.content)"></div>
          </div>
        </div>

      </div>

      <!-- 输入区域 -->
      <footer class="chat-input-area">
        <div class="input-tools">
          <a-button type="text" @click="toggleAttach">
            <paper-clip-outlined />
          </a-button>
          <a-button type="text" @click="toggleEmoji">
            <smile-outlined />
          </a-button>
        </div>
        <div class="input-wrapper">
          <a-textarea
            v-model:value="inputMessage"
            placeholder="输入消息...（Shift+Enter换行，Enter发送）"
            :auto-size="{ minRows: 1, maxRows: 6 }"
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
            <send-outlined />
          </a-button>
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
      <div class="input-footer">
        <div class="footer-left">
          <div class="mode-selector">
            <SettingOutlined class="mode-icon" />
            <a-select
              :value="settingsStore.getModel('chat_assistant')"
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
              :value="settingsStore.getThinkingMode('chat_assistant')"
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
        <div class="footer-right">
          <span v-if="currentMessages.length > 0" class="context-tokens">上下文 Token: {{ formatTokenCount(totalContextTokens) }} · </span>
          <span class="hint-text">zb-agent可以联网搜索，请告知它开启联网功能</span>
          <a-checkbox v-model:checked="enableWebSearch" class="web-search-checkbox">联网搜索</a-checkbox>
        </div>
      </div>
    </footer>
  </main>
</div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted, nextTick, watch, h } from 'vue'
import { message, Modal } from 'ant-design-vue'
import { useUserStore } from '@/store/user'
import { getProfileList, createProfile, deleteProfile, updateMessage } from '@/api/profile'
import { getConversationMessages, processMessageGroups } from '@/api/conversation'
import { streamChat } from '@/api/chat'
import { submitAnswer } from '@/api/askUser'
import { useSettingsStore } from '@/store/settings'
import { renderMarkdown } from '@/utils/markdown'
import { estimateTokenCount, formatTokenCount } from '@/utils/tokenCalculator'
import {
  PlusOutlined,
  MessageOutlined,
  DeleteOutlined,
  EditOutlined,
  RobotOutlined,
  PaperClipOutlined,
  SmileOutlined,
  SendOutlined,
  DownOutlined,
  UpOutlined,
  MenuFoldOutlined,
  MenuUnfoldOutlined,
  CloseOutlined,
  SettingOutlined
} from '@ant-design/icons-vue'

const userStore = useUserStore()
const settingsStore = useSettingsStore()

// 对话相关状态
interface Conversation {
  id: string
  title: string
  updatedAt: number
  messageCount: number
}

/**
 * 聊天消息接口定义
 */
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
const collapsed = ref(false)
const toggleCollapsed = () => { collapsed.value = !collapsed.value }
const inputMessage = ref('')
const enableWebSearch = ref(false)
const isSending = ref(false)
const isLoadingConversations = ref(false)
const isLoadingMessages = ref(false)
const messageListRef = ref<HTMLElement>()
// 流式聊天相关状态
const currentSessionId = ref<number>()
const currentUserProfileId = ref<number>()
const thinkingVisible = ref<Record<string, boolean>>({})
const currentAiMessage = ref<ChatMessage | null>(null)
// 消息编辑相关状态
const editingMessageId = ref<string>('')
const editingContent = ref('')
const isSavingEdit = ref(false)
const pendingQuestion = ref<{ uuid: string; question: string } | null>(null)
const pendingQuestionAnswer = ref('')
const stopAbortController = ref<AbortController | null>(null)


// 获取思考过程可见性
const getThinkingVisible = (messageId: string) => thinkingVisible.value[messageId] || false


const currentMessages = computed(() => {
  return messages.value[currentConversationId.value] || []
})

const totalContextTokens = computed(() => {
  return currentMessages.value.reduce((sum, msg) => sum + (msg.tokenCount || 0), 0)
})


const fetchConversations = async () => {
  isLoadingConversations.value = true
  try {
    const response = await getProfileList()
    if (response.code === 200 && response.data) {
      const profileConversations: Conversation[] = response.data.map(profile => ({
        id: profile.id.toString(),
        title: profile.username,
        updatedAt: new Date(profile.createdAt).getTime(),
        messageCount: 0
      }))

      // 按创建时间倒序（最新的在前）
      conversations.value = profileConversations.sort((a, b) => b.updatedAt - a.updatedAt)

      // 自动选中第一个会话
      if (conversations.value.length > 0 && !currentConversationId.value) {
        const firstConv = conversations.value[0]
        currentConversationId.value = firstConv.id
        currentSessionId.value = parseInt(firstConv.id)
        currentUserProfileId.value = parseInt(firstConv.id)

        // 异步加载消息
        loadConversationMessages(firstConv.id).catch(err => {
          console.error('后台加载消息失败:', err)
        })
      }
    } else {
      message.error(response.message || '获取会话列表失败')
    }
  } catch (error: any) {
    console.error('获取会话列表失败:', error)
    message.error(error.message || '获取会话列表失败')
  } finally {
    isLoadingConversations.value = false
  }
}

/**
 * 新增聊天助手会话
 */
const createNewProfile = () => {
  let inputValue = ''
  Modal.confirm({
    title: '新增会话',
    content: h('div', {}, [
      h('p', { style: { 'margin-bottom': '12px', color: '#666' } }, '请输入会话名称'),
      h('input', {
        style: {
          width: '100%',
          padding: '8px 12px',
          border: '1px solid #d9d9d9',
          'border-radius': '6px',
          outline: 'none',
          'font-size': '14px',
          'box-sizing': 'border-box'
        },
        placeholder: '请输入名称',
        onInput: (e: any) => { inputValue = e.target.value }
      })
    ]),
    okText: '创建',
    cancelText: '取消',
    onOk: async () => {
      const name = inputValue.trim()
      if (!name) {
        message.warning('请输入会话名称')
        throw new Error('cancel')
      }
      try {
        isLoadingConversations.value = true
        const response = await createProfile(name)
        if (response.code === 200) {
          message.success('会话创建成功')
          // 刷新会话列表
          await fetchConversations()
          // 如果返回了新建会话的ID，选中它
          if (response.data?.id) {
            const newId = response.data.id.toString()
            await selectConversation(newId)
          }
        } else {
          message.error(response.message || '创建会话失败')
        }
      } catch (error: any) {
        console.error('创建会话失败:', error)
        message.error(error.message || '创建会话失败')
      } finally {
        isLoadingConversations.value = false
      }
    }
  })
}

/**
 * 选择对话
 * 选择对话
 */
const selectConversation = async (id: string) => {
  if (currentConversationId.value === id && messages.value[id]?.length > 0) {
    return
  }

  currentConversationId.value = id

  const convIdNum = parseInt(id)
  if (!isNaN(convIdNum)) {
    currentSessionId.value = convIdNum
    currentUserProfileId.value = convIdNum
  }

  if (messages.value[id] && messages.value[id].length > 0) {
    return
  }

  await loadConversationMessages(id)
}

/**
 * 加载会话消息
 */
const loadConversationMessages = async (conversationId: string) => {
  const convId = parseInt(conversationId)
  if (isNaN(convId)) {
    return
  }

  isLoadingMessages.value = true
  try {
    const response = await getConversationMessages(convId)
    if (response.code === 200) {
      const messagesData = response.data || []
      const mappedMessages = processMessageGroups(messagesData)
      messages.value[conversationId] = mappedMessages

      const convIndex = conversations.value.findIndex(conv => conv.id === conversationId)
      if (convIndex !== -1) {
        conversations.value[convIndex].messageCount = mappedMessages.length
      }
    } else {
      message.error(response.message || '获取消息失败')
    }
  } catch (error: any) {
    console.error('获取会话消息失败:', error)
    message.error(error.message || '获取消息失败')
  } finally {
    isLoadingMessages.value = false
  }
}


/**
 * 删除聊天助手会话
 */
const deleteConversation = async (id: string) => {
  const conversation = conversations.value.find(conv => conv.id === id)
  if (!conversation) {
    await fetchConversations()
    message.info('会话列表已刷新，请重试删除操作')
    return
  }

  Modal.confirm({
    title: '确认删除',
    content: `确定要删除会话 "${conversation.title}" 吗？删除后无法恢复。`,
    okText: '删除',
    okType: 'danger',
    cancelText: '取消',
    onOk: async () => {
      try {
        const profileId = parseInt(id)
        if (!isNaN(profileId)) {
          await deleteProfile(profileId)
        }

        // 从前端删除会话
        const index = conversations.value.findIndex(conv => conv.id === id)
        if (index !== -1) {
          const isCurrentConversation = currentConversationId.value === id

          conversations.value.splice(index, 1)
          delete messages.value[id]

          if (isCurrentConversation) {
            if (conversations.value.length > 0) {
              const nextConversationId = conversations.value[0].id
              await selectConversation(nextConversationId)
            } else {
              currentConversationId.value = ''
              currentSessionId.value = undefined
              currentUserProfileId.value = undefined
            }
          }

          message.success('会话已删除')
        }
      } catch (error: any) {
        console.error('删除会话失败:', error)
        message.error(error.message || '删除会话失败')
      }
    }
  })
}


// 切换思考过程可见性
const toggleThinkingVisibility = (messageId: string) => {
  thinkingVisible.value[messageId] = !thinkingVisible.value[messageId]
}

// 模型与思考模式切换
const handleModelChange = (val: string) => {
  settingsStore.setModel('chat_assistant', val as 'deepseek-v4-flash' | 'deepseek-v4-pro')
}
const handleThinkingModeChange = (val: string) => {
  settingsStore.setThinkingMode('chat_assistant', val as 'non-thinking' | 'thinking' | 'thinking_max')
}

// 消息编辑相关方法
const startEdit = (msg: ChatMessage) => {
  editingMessageId.value = msg.id
  editingContent.value = msg.content
}

const cancelEdit = () => {
  editingMessageId.value = ''
  editingContent.value = ''
}

const saveEdit = async (msg: ChatMessage) => {
  const content = editingContent.value.trim()
  if (!content) {
    message.warning('内容不能为空')
    return
  }

  const messageId = parseInt(msg.id)
  if (isNaN(messageId)) {
    message.error('无法编辑此消息')
    return
  }

  isSavingEdit.value = true
  try {
    await updateMessage(messageId, content)

    // 更新本地消息内容
    const convMessages = messages.value[currentConversationId.value]
    if (convMessages) {
      const index = convMessages.findIndex(m => m.id === msg.id)
      if (index !== -1) {
        convMessages[index].content = content
        convMessages[index].tokenCount = estimateTokenCount(content)
        messages.value[currentConversationId.value] = [...convMessages]
      }
    }

    message.success('消息已修改')
    editingMessageId.value = ''
    editingContent.value = ''
  } catch (error: any) {
    console.error('修改消息失败:', error)
    message.error(error.message || '修改消息失败')
  } finally {
    isSavingEdit.value = false
  }
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

// 中断流式响应
const stopStreaming = () => {
  if (stopAbortController.value) {
    stopAbortController.value.abort()
    stopAbortController.value = null
  }
}

const sendMessage = async () => {
  const text = inputMessage.value.trim()
  if (!text) return

  if (isSending.value) {
    return
  }

  if (currentAiMessage.value?.isStreaming) {
    message.warning('请等待AI回复完成后再发送新消息')
    return
  }

  const conversationId = currentConversationId.value

  if (!conversationId || conversations.value.length === 0 || !conversations.value.find(conv => conv.id === conversationId)) {
    message.warning('请先选择一个历史会话')
    return
  }

  const currentConv = conversations.value.find(conv => conv.id === conversationId)

  // 添加用户消息
  const userMsg: ChatMessage = {
    id: `${conversationId}-${Date.now()}`,
    role: 'user',
    content: text,
    timestamp: Date.now(),
    tokenCount: estimateTokenCount(text)
  }

  if (!messages.value[conversationId]) {
    messages.value[conversationId] = []
  }
  messages.value[conversationId].push(userMsg)

  // 更新对话时间和消息数量
  if (currentConv) {
    currentConv.updatedAt = Date.now()
    currentConv.messageCount = messages.value[conversationId].length
  }

  inputMessage.value = ''
  isSending.value = true

  // 创建AI消息占位符
  const aiMsgId = `${conversationId}-${Date.now()}`
  const aiMsg: ChatMessage = {
    id: aiMsgId,
    role: 'assistant',
    content: '',
    thinking: '',
    toolResults: [],
    timestamp: Date.now(),
    isStreaming: true
  }
  messages.value[conversationId].push(aiMsg)
  currentAiMessage.value = aiMsg

  // 默认思考过程展开
  thinkingVisible.value[aiMsgId] = true

  try {
    const sessionId = currentSessionId.value
    const userProfileId = currentUserProfileId.value
    const abortCtrl = new AbortController()
    stopAbortController.value = abortCtrl
    const events = streamChat(text, sessionId, {
      promptFileName: 'romantic_chat_agent_prompt.txt',
      userProfileId: userProfileId,
      executionMode: settingsStore.getMode('chat_assistant'),
      model: settingsStore.getModel('chat_assistant'),
      thinkingMode: settingsStore.getThinkingMode('chat_assistant')
    }, abortCtrl)

    let thinkingContent = ''
    let responseContent = ''
    let toolDataNext = false

    for await (const event of events) {
      switch (event.type) {
        case 'thinking':
          // 检测工具调用结果标记，分离存储
          const toolMarkerIdx = event.data.indexOf('----工具调用:----')
          if (toolMarkerIdx !== -1) {
            const cs = event.data.indexOf('\n', toolMarkerIdx)
            const toolContent = cs !== -1 ? event.data.substring(cs + 1).trim() : ''
            if (toolContent && currentAiMessage.value && currentAiMessage.value.id === aiMsgId && currentAiMessage.value.toolResults) {
              currentAiMessage.value.toolResults.push({
                at: thinkingContent.length,
                content: toolContent
              })
              messages.value[conversationId] = [...messages.value[conversationId]]
            }
            break
          }

          let processedData = event.data

          if (toolDataNext) {
            toolDataNext = false
          }
          thinkingContent += processedData
          if (currentAiMessage.value && currentAiMessage.value.id === aiMsgId) {
            currentAiMessage.value.thinking = thinkingContent
            messages.value[conversationId] = [...messages.value[conversationId]]
          }
          break

        case 'content':
          responseContent += event.data
          if (currentAiMessage.value && currentAiMessage.value.id === aiMsgId) {
            currentAiMessage.value.content = responseContent
            messages.value[conversationId] = [...messages.value[conversationId]]
          }
          scrollToBottom()
          break

        case 'complete':
          if (event.sessionId !== undefined) {
            currentSessionId.value = event.sessionId
          }

          if (currentAiMessage.value && currentAiMessage.value.id === aiMsgId) {
            currentAiMessage.value.isStreaming = false
            const toolResultsText = (currentAiMessage.value.toolResults || []).map(r => r.content).join('')
            currentAiMessage.value.tokenCount = estimateTokenCount(responseContent + toolResultsText)
            messages.value[conversationId] = [...messages.value[conversationId]]
          }
          break

        case 'ask_user':
          try {
            pendingQuestion.value = { uuid: event.data.uuid, question: event.data.question }
            pendingQuestionAnswer.value = ''
            console.log('收到 ask_user 问题:', event.data.question)
          } catch (e) {
            console.warn('解析 ask_user 事件失败:', e)
          }
          break

        case 'resume':
          console.log('收到 resume 事件，流已恢复')
          break

        case 'error':
          throw new Error(event.data)
      }
    }

    if (!responseContent && !thinkingContent) {
      if (currentAiMessage.value && currentAiMessage.value.id === aiMsgId) {
        currentAiMessage.value.content = '收到空响应，请重试。'
        currentAiMessage.value.isStreaming = false
        messages.value[conversationId] = [...messages.value[conversationId]]
      }
    }
  } catch (error: any) {
    if (error.message === '__USER_ABORT__') {
      console.log('用户中断流式响应')
      if (currentAiMessage.value && currentAiMessage.value.id === aiMsgId) {
        currentAiMessage.value.isStreaming = false
        messages.value[conversationId] = [...messages.value[conversationId]]
      }
      return
    }
    console.error('聊天请求失败:', error)
    if (currentAiMessage.value && currentAiMessage.value.id === aiMsgId) {
      currentAiMessage.value.content = `请求失败: ${error.message}`
      currentAiMessage.value.isStreaming = false
      messages.value[conversationId] = [...messages.value[conversationId]]
    }
  } finally {
    isSending.value = false
    currentAiMessage.value = null
    scrollToBottom()
  }
}

const handleShiftEnter = () => {
  inputMessage.value = inputMessage.value
}

const toggleAttach = () => {
  message.info('附件功能开发中')
}

const toggleEmoji = () => {
  message.info('表情功能开发中')
}

const formatTime = (timestamp: number) => {
  const now = Date.now()
  const diff = now - timestamp

  if (diff < 60000) return '刚刚'
  if (diff < 3600000) return `${Math.floor(diff / 60000)}分钟前`
  if (diff < 86400000) return `${Math.floor(diff / 3600000)}小时前`
  return `${Math.floor(diff / 86400000)}天前`
}

const formatMessageTime = (timestamp: number) => {
  const date = new Date(timestamp)
  return date.toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit' })
}

const formatMessage = (content: string) => {
  return renderMarkdown(content)
}

const formatThinking = (thinking: string | undefined, toolResults?: { at: number; content: string }[]) => {
  if (!thinking && (!toolResults || toolResults.length === 0)) return ''
  thinking = thinking || ''

  const escapeHtml = (text: string) => {
    return text
      .replace(/&/g, '&amp;')
      .replace(/</g, '&lt;')
      .replace(/>/g, '&gt;')
      .replace(/"/g, '&quot;')
      .replace(/'/g, '&#039;')
  }

  // 如果有单独存储的工具调用结果，按位置注入
  if (toolResults && toolResults.length > 0) {
    const sorted = [...toolResults].sort((a, b) => a.at - b.at)
    const segments: string[] = []
    let lastPos = 0
    for (const tr of sorted) {
      if (tr.at > lastPos) {
        let seg = escapeHtml(thinking.slice(lastPos, tr.at))
        seg = seg.replace(/&lt;strong&gt;/g, '<strong>').replace(/&lt;\/strong&gt;/g, '</strong>')
        segments.push(seg)
      }
      const escaped = escapeHtml(tr.content)
      segments.push(`<details class="thinking-tool-result"><summary>🔧 工具调用结果</summary><pre>${escaped}</pre></details>`)
      lastPos = tr.at
    }
    if (lastPos < (thinking || '').length) {
      let seg = escapeHtml(thinking.slice(lastPos))
      seg = seg.replace(/&lt;strong&gt;/g, '<strong>').replace(/&lt;\/strong&gt;/g, '</strong>')
      segments.push(seg)
    }
    return segments.join('\n')
  }

  // 降级：历史消息中嵌入的标记解析
  const headerRe = /^-{48}工具调用:-{48}\s*$/m
  if (!headerRe.test(thinking)) {
    let processed = escapeHtml(thinking)
    processed = processed.replace(/&lt;strong&gt;/g, '<strong>').replace(/&lt;\/strong&gt;/g, '</strong>')
    return processed
  }

  const segments: string[] = []
  const positions: number[] = []
  const globalRe = /^-{48}工具调用:-{48}\s*$/gm
  let m: RegExpExecArray | null
  while ((m = globalRe.exec(thinking)) !== null) {
    positions.push(m.index)
  }

  let start = 0
  for (let i = 0; i < positions.length; i++) {
    const hp = positions[i]
    if (hp > start) {
      let seg = escapeHtml(thinking.slice(start, hp))
      seg = seg.replace(/&lt;strong&gt;/g, '<strong>').replace(/&lt;\/strong&gt;/g, '</strong>')
      segments.push(seg)
    }
    const lineEnd = thinking.indexOf('\n', hp)
    const toolStart = lineEnd >= 0 ? lineEnd + 1 : thinking.length
    const toolEnd = i + 1 < positions.length ? positions[i + 1] : thinking.length
    const raw = thinking.slice(toolStart, toolEnd).trim()
    const escaped = escapeHtml(raw)
    segments.push(`<details class="thinking-tool-result"><summary>🔧 工具调用结果</summary><pre>${escaped}</pre></details>`)
    start = toolEnd
  }
  if (start < thinking.length) {
    let seg = escapeHtml(thinking.slice(start))
    seg = seg.replace(/&lt;strong&gt;/g, '<strong>').replace(/&lt;\/strong&gt;/g, '</strong>')
    segments.push(seg)
  }
  return segments.join('\n')
}


const scrollToBottom = () => {
  nextTick(() => {
    if (messageListRef.value) {
      messageListRef.value.scrollTop = messageListRef.value.scrollHeight
    }
  })
}


// 监听消息变化，自动滚动
watch(currentMessages, () => {
  scrollToBottom()
}, { deep: true })

onMounted(() => {
  fetchConversations()
  scrollToBottom()
})
</script>

<style scoped>
.chat-container {
  display: flex;
  height: 100%;
  background-color: white;
}

/* 侧边栏样式 - 聊天历史面板 */
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

.sidebar.collapsed {
  width: 80px;
}

.sidebar-header {
  padding: 16px 12px;
  border-bottom: 1px solid #eef2f7;
  background: #f8fafc;
}

.sidebar-header-row {
  display: flex;
  align-items: center;
  gap: 6px;
}

.new-chat-btn {
  flex: 1;
  min-width: 0;
  background: linear-gradient(135deg, #667eea, #764ba2);
  border: none;
  border-radius: 8px;
  font-weight: 500;
  box-shadow: 0 2px 8px rgba(102, 126, 234, 0.2);
}

.collapsed .new-chat-btn {
  flex: 0 0 auto;
  width: 36px;
  padding: 0;
  display: flex;
  align-items: center;
  justify-content: center;
}

.collapsed .btn-text {
  display: none;
}

.sidebar-collapse-btn {
  cursor: pointer;
  font-size: 18px;
  color: #8c8c8c;
  display: flex;
  align-items: center;
  justify-content: center;
  transition: color 0.2s;
  flex-shrink: 0;
}

.sidebar-collapse-btn:hover {
  color: #1890ff;
}

/* 折叠状态：隐藏文字、居中图标 */
.collapsed .conv-info {
  display: none;
}

.collapsed .conv-actions {
  display: none;
}

.collapsed .conversation-item {
  padding: 10px 0;
  justify-content: center;
}

.collapsed .conv-icon {
  margin-right: 0;
  font-size: 18px;
}

.collapsed .loading-conversations span,
.collapsed .empty-conversations p {
  display: none;
}

.collapsed .conversation-list {
  padding: 12px 4px;
}

.conversation-list {
  flex: 1;
  overflow-y: auto;
  padding: 12px;
  background: #fafbfc;
}

.conversation-item {
  display: flex;
  align-items: center;
  padding: 10px 12px;
  border-radius: 8px;
  cursor: pointer;
  margin-bottom: 6px;
  transition: all 0.2s;
  border: 1px solid transparent;
  background: white;
  box-shadow: 0 1px 3px rgba(0, 0, 0, 0.04);
}

.conversation-item:hover {
  background-color: #f8fafc;
  border-color: #e1e8f0;
  box-shadow: 0 2px 6px rgba(0, 0, 0, 0.06);
}

.conversation-item.active {
  background-color: #f0f9ff;
  border-color: #bae6fd;
  box-shadow: 0 2px 8px rgba(56, 189, 248, 0.12);
}

.conv-actions {
  opacity: 0;
  transition: opacity 0.2s;
}

.conversation-item:hover .conv-actions {
  opacity: 1;
}

.conv-icon {
  margin-right: 12px;
  color: #667eea;
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
  font-size: 13px;
}

.conv-time {
  font-size: 11px;
  color: #718096;
  margin-top: 2px;
}

.loading-conversations {
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 30px 20px;
  color: #718096;
  gap: 8px;
  background: #f8fafc;
  border-radius: 8px;
  margin: 12px;
}

.loading-messages {
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 30px 20px;
  color: #718096;
  gap: 8px;
  background: #f8fafc;
  border-radius: 8px;
  margin: 12px;
}

.empty-conversations {
  text-align: center;
  padding: 40px 20px;
  color: #718096;
  background: #f8fafc;
  border-radius: 8px;
  margin: 12px;
}

.empty-conversations p {
  margin: 0;
}

/* 主聊天区域样式 */
.chat-main {
  flex: 1;
  display: flex;
  flex-direction: column;
  height: 100%;
  background: white;
}

.message-list {
  flex: 1;
  overflow-y: auto;
  padding: 32px 24px;
  background-color: #f8fafc;
}

.message-item {
  display: flex;
  margin-bottom: 24px;
  animation: fadeIn 0.3s ease;
}

@keyframes fadeIn {
  from {
    opacity: 0;
    transform: translateY(10px);
  }
  to {
    opacity: 1;
    transform: translateY(0);
  }
}

.message-item.user {
  flex-direction: row-reverse;
}

.message-item.assistant .message-content {
  width: 800px;
  word-wrap: break-word;
  overflow-wrap: break-word;
}

.message-avatar {
  margin: 0 12px;
}

.avatar {
  width: 40px;
  height: 40px;
  border-radius: 50%;
  display: flex;
  align-items: center;
  justify-content: center;
  font-weight: bold;
}

.user-avatar {
  background: linear-gradient(135deg, #667eea, #764ba2);
  color: white;
}

.ai-avatar {
  background: #1890ff;
  color: white;
  font-size: 18px;
}

.message-content {
  max-width: 70%;
  min-width: 200px;
}

.message-item.user .message-content {
  text-align: right;
}

.message-header {
  margin-bottom: 8px;
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.message-item.user .message-header {
  flex-direction: row-reverse;
}

.message-sender {
  font-weight: 500;
  color: #262626;
}

.message-time {
  font-size: 12px;
  color: #8c8c8c;
  margin-left: 8px;
}

.message-item.user .message-time {
  margin-left: 0;
  margin-right: 8px;
}

.message-text {
  background: white;
  padding: 12px 16px;
  border-radius: 12px;
  box-shadow: 0 2px 8px rgba(0, 0, 0, 0.04);
  line-height: 1.6;
  color: #1a202c;
  text-align: left;
  border: 1px solid #f1f5f9;
}

.message-item.user .message-text {
  background: linear-gradient(135deg, #667eea, #764ba2);
  color: white;
  text-align: left;
  border: none;
  box-shadow: 0 2px 8px rgba(102, 126, 234, 0.2);
}

.message-item.assistant .message-text {
  word-wrap: break-word;
  overflow-wrap: break-word;
}

.message-text :deep(code) {
  background: #f6f8fa;
  padding: 2px 6px;
  border-radius: 4px;
  font-family: 'Courier New', monospace;
  font-size: 0.9em;
}

.message-item.user .message-text :deep(code) {
  background: rgba(255, 255, 255, 0.2);
  color: white;
}

/* 输入区域样式 */
.chat-input-area {
  padding: 16px 24px;
  background: white;
  border-top: 1px solid #eef2f7;
}

.input-tools {
  display: flex;
  gap: 8px;
  margin-bottom: 12px;
}

.input-wrapper {
  display: flex;
  gap: 12px;
  align-items: flex-end;
}

.input-wrapper :deep(.ant-input) {
  flex: 1;
  border-radius: 20px;
  padding: 12px 16px;
  resize: none;
  border: 1px solid #d9d9d9;
}

.input-wrapper :deep(.ant-input:hover) {
  border-color: #40a9ff;
}

.input-wrapper :deep(.ant-input:focus) {
  border-color: #40a9ff;
  box-shadow: 0 0 0 2px rgba(24, 144, 255, 0.2);
}

.send-btn {
  width: 40px;
  height: 40px;
  border-radius: 50%;
  display: flex;
  align-items: center;
  justify-content: center;
}

.stop-btn {
  width: 40px;
  height: 40px;
  border-radius: 50%;
  display: flex;
  align-items: center;
  justify-content: center;
  background: #ff4d4f;
  border-color: #ff4d4f;
  color: white;
}
.stop-btn:hover {
  background: #ff7875;
  border-color: #ff7875;
  color: white;
}

.input-footer {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-top: 12px;
  font-size: 12px;
}

.footer-left {
  display: flex;
  align-items: center;
  gap: 8px;
}

.footer-right {
  display: flex;
  align-items: center;
  gap: 8px;
}

.hint-text {
  color: #8c8c8c;
}

.context-tokens {
  color: #8c8c8c;
}

.web-search-checkbox {
  white-space: nowrap;
}

/* 模型/思考模式选择器 */
.mode-selector {
  display: flex;
  align-items: center;
  gap: 4px;
  padding: 2px 8px 2px 6px;
  background: #f5f5f5;
  border: 1px solid #e8e8e8;
  border-radius: 6px;
  height: 26px;
}

.mode-icon {
  font-size: 12px;
  opacity: 0.55;
}

.model-select {
  width: 130px;
}

.model-select :deep(.ant-select-selection-item) {
  font-size: 12px !important;
}

/* 响应式设计 */
@media (max-width: 768px) {
  .sidebar {
    width: 100%;
    height: auto;
    border-right: none;
    border-bottom: 1px solid #eef2f7;
  }

  .chat-container {
    flex-direction: column;
  }

  .message-content {
    max-width: 85%;
  }

  .message-item.assistant .message-content {
    width: auto;
    max-width: 85%;
  }

  .thinking-section {
    width: auto;
    max-width: 100%;
  }
}

/* 思考过程样式 */
.thinking-section {
  margin-bottom: 12px;
  border: 1px solid #e2e8f0;
  border-radius: 8px;
  overflow: hidden;
  background-color: #f8fafc;
  text-align: left;
  width: 800px;
}

.thinking-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 6px 12px;
  background-color: #f1f5f9;
  cursor: pointer;
  user-select: none;
  transition: background-color 0.2s;
  border-bottom: 1px solid #e2e8f0;
}

.thinking-header:hover {
  background-color: #e2e8f0;
}

.thinking-title {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 12px;
  font-weight: 500;
  color: #586069;
  font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Helvetica, Arial, sans-serif;
}

.thinking-hint {
  font-size: 11px;
  color: #959da5;
  font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Helvetica, Arial, sans-serif;
}

.thinking-content {
  padding: 0;
  background-color: transparent;
  border-top: none;
  max-height: 300px;
  overflow-y: auto;
}

.thinking-content .thinking-text {
  margin: 0;
  font-family: 'SF Mono', 'Monaco', 'Menlo', 'Ubuntu Mono', monospace;
  font-size: 12px;
  line-height: 1.2;
  color: #4a5568;
  white-space: pre-wrap;
  word-break: break-word;
  background-color: #f1f5f9;
  padding: 6px 12px;
  text-align: left;
  border-left: 3px solid #a0aec0;
  border-right: 1px solid #e2e8f0;
}

.thinking-content .thinking-text:first-child {
  border-top: 1px solid #e2e8f0;
  border-radius: 6px 6px 0 0;
}

.thinking-content .thinking-text:not(:first-child) {
  border-top: 1px solid #e2e8f0;
  border-radius: 0;
  margin-top: -1px;
}

.thinking-content .thinking-text:last-child {
  border-bottom: 1px solid #e2e8f0;
  border-radius: 0 0 6px 6px;
}

.thinking-content .thinking-text:first-child:last-child {
  border-radius: 6px;
  border-top: 1px solid #e2e8f0;
  border-bottom: 1px solid #e2e8f0;
}

.thinking-content pre {
  margin: 0;
  font-family: 'Monaco', 'Menlo', 'Ubuntu Mono', monospace;
  font-size: 12px;
  line-height: 1.5;
  color: #595959;
  white-space: pre-wrap;
  word-break: break-word;
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

/* Token计数样式 */
.message-token {
  font-size: 12px;
  color: #8c8c8c;
  margin-left: 8px;
}

/* 编辑按钮样式 */
.message-edit-btn {
  margin-left: 8px;
  cursor: pointer;
  color: #8c8c8c;
  font-size: 13px;
  transition: color 0.2s;
}

.message-edit-btn:hover {
  color: #1890ff;
}

/* 消息编辑区域样式 */
.message-edit-area {
  background: white;
  padding: 12px 16px;
  border-radius: 12px;
  border: 1px solid #d9d9d9;
}

.message-edit-area :deep(.ant-input) {
  border-radius: 8px;
  resize: vertical;
}

.edit-actions {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
  margin-top: 8px;
}

/* 流式指示器样式 */
.streaming-indicator {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  margin-left: 12px;
  font-size: 12px;
  color: #8c8c8c;
}

.streaming-dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background-color: #52c41a;
  animation: pulse 1.5s infinite ease-in-out;
}

@keyframes pulse {
  0%, 100% {
    opacity: 0.5;
    transform: scale(0.9);
  }
  50% {
    opacity: 1;
    transform: scale(1.1);
  }
}

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

</style>
