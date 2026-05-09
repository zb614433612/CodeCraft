<template>
  <div class="chat-container">
    <!-- 侧边栏 -->
    <aside class="sidebar" :class="{ collapsed }">
      <div class="sidebar-header">
        <div class="sidebar-header-row">
          <a-button type="primary" class="new-chat-btn" @click="startNewChat">
            <template #icon>
              <plus-outlined />
            </template>
            <span class="btn-text">新对话</span>
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
          <p>暂无会话</p>
          <p class="empty-hint">点击"新对话"开始聊天</p>
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
            <div class="message-text" v-html="formatMessage(msg.content)" v-if="msg.content"></div>
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
              :value="settingsStore.getModel('ai_assistant')"
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
              :value="settingsStore.getThinkingMode('ai_assistant')"
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
import { ref, computed, onMounted, nextTick, watch } from 'vue'
import { message, Modal } from 'ant-design-vue'
import { useUserStore } from '@/store/user'
import { getConversationList, mapConversationResponseToConversation, getConversationMessages, processMessageGroups, deleteConversation as deleteConversationApi } from '@/api/conversation'
import { streamChat } from '@/api/chat'
import { submitAnswer } from '@/api/askUser'
import { useSettingsStore } from '@/store/settings'
import { renderMarkdown } from '@/utils/markdown'
import { estimateTokenCount, formatTokenCount } from '@/utils/tokenCalculator'
import {
  PlusOutlined,
  MessageOutlined,
  DeleteOutlined,
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
  isLocal?: boolean // 标记是否为本地创建的新会话
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

// ask_user 待处理问题
const pendingQuestion = ref<{ uuid: string; question: string } | null>(null)
const pendingQuestionAnswer = ref('')
const isSending = ref(false)
const isLoadingConversations = ref(false)
const isLoadingMessages = ref(false)
const messageListRef = ref<HTMLElement>()
// 流式聊天相关状态
const currentSessionId = ref<number>() // 当前会话的sessionId（来自后端）
const thinkingVisible = ref<Record<string, boolean>>({}) // 控制每个消息的思考过程可见性
const currentAiMessage = ref<ChatMessage | null>(null) // 当前正在流式传输的AI消息引用
const stopAbortController = ref<AbortController | null>(null)


// 获取思考过程可见性
const getThinkingVisible = (messageId: string) => thinkingVisible.value[messageId] || false

// 模型与思考模式切换
const handleModelChange = (val: string) => {
  settingsStore.setModel('ai_assistant', val as 'deepseek-v4-flash' | 'deepseek-v4-pro')
}
const handleThinkingModeChange = (val: string) => {
  settingsStore.setThinkingMode('ai_assistant', val as 'non-thinking' | 'thinking' | 'thinking_max')
}


const currentMessages = computed(() => {
  return messages.value[currentConversationId.value] || []
})

// 计算当前会话上下文总Token
const totalContextTokens = computed(() => {
  return currentMessages.value.reduce((sum, msg) => sum + (msg.tokenCount || 0), 0)
})

// 计算当前会话的总Token数量

// 方法
/**
 * 合并服务器会话和本地会话
 */
const mergeConversations = (
  serverConversations: Conversation[],
  localConversations: Conversation[],
  allLocalConversations: Conversation[]
): Conversation[] => {
  const merged: Conversation[] = []

  // 1. 处理每个服务器会话
  serverConversations.forEach(serverConv => {
    // 检查本地是否有相同ID的对话
    const localConv = allLocalConversations.find(local => local.id === serverConv.id)
    if (localConv) {
      // 如果本地有相同ID的对话，比较更新时间戳
      if (localConv.updatedAt > serverConv.updatedAt) {
        // 本地版本更新，使用本地版本（但确保isLocal=false，表示是服务器会话）
        const mergedConv: Conversation = {
          ...serverConv, // 保留服务器ID和其他属性
          title: localConv.title, // 使用本地更新的标题
          updatedAt: localConv.updatedAt // 使用本地更新时间
        }
        merged.push(mergedConv)
      } else {
        // 服务器版本更新或相同，使用服务器版本
        merged.push(serverConv)
      }
    } else {
      // 本地没有相同ID的对话，直接使用服务器版本
      merged.push(serverConv)
    }
  })

  // 2. 添加真正的本地新会话（isLocal=true且ID不在服务器列表中）
  localConversations.forEach(localConv => {
    // 检查这个本地会话是否已存在于服务器列表中
    const existsInServer = serverConversations.some(serverConv => serverConv.id === localConv.id)
    if (!existsInServer) {
      merged.push(localConv)
    }
  })

  // 按更新时间倒序排序（最新的在前）
  return merged.sort((a, b) => b.updatedAt - a.updatedAt)
}

/**
 * 确保当前选中的对话ID有效
 */
const ensureCurrentConversation = (
  mergedConversations: Conversation[],
  previousConversationId: string
): string => {
  if (mergedConversations.length === 0) {
    return ''
  }

  // 如果之前有选中的对话且仍然存在于合并列表中，保持选中状态
  if (previousConversationId && mergedConversations.some(conv => conv.id === previousConversationId)) {
    return previousConversationId
  }

  // 否则选择第一个
  return mergedConversations[0].id
}

const fetchConversations = async () => {
  isLoadingConversations.value = true
  console.log('开始获取会话列表...')
  try {
    const response = await getConversationList('ai_assistant')
    console.log('会话列表API响应:', response)
    if (response.code === 200 && response.data) {
      // 映射数据
      const serverConversations = response.data.map(mapConversationResponseToConversation)
      console.log('服务器会话列表:', serverConversations.map(c => ({id: c.id, title: c.title, updatedAt: c.updatedAt})))

      // 获取当前所有本地创建的会话（isLocal为true的会话）
      const localConversations = conversations.value.filter(conv => conv.isLocal)
      console.log('本地会话列表（isLocal=true）:', localConversations.map(c => ({id: c.id, title: c.title, isLocal: c.isLocal})))

      // 获取所有本地对话（包括已迁移的，isLocal=false的）
      const allLocalConversations = conversations.value
      console.log('所有本地对话:', allLocalConversations.map(c => ({id: c.id, title: c.title, isLocal: c.isLocal, updatedAt: c.updatedAt})))

      // 使用辅助函数合并会话
      const mergedConversations = mergeConversations(serverConversations, localConversations, allLocalConversations)
      console.log('最终会话列表:', mergedConversations.map(c => ({id: c.id, title: c.title, updatedAt: new Date(c.updatedAt).toISOString()})))

      // 记录当前选中的对话ID，以便之后恢复
      const previousConversationId = currentConversationId.value
      console.log('更新前当前选中的对话ID:', previousConversationId)

      conversations.value = mergedConversations
      console.log('最终会话列表:', mergedConversations.map(c => ({id: c.id, title: c.title, updatedAt: new Date(c.updatedAt).toISOString()})))

      // 使用辅助函数确定当前选中的对话ID
      const newConversationId = ensureCurrentConversation(mergedConversations, previousConversationId)
      if (newConversationId !== currentConversationId.value) {
        currentConversationId.value = newConversationId
        console.log('更新当前选中对话ID:', currentConversationId.value)

        // 如果是新选择的第一个会话（之前没有选中），且是服务器会话，设置sessionId
        if (!previousConversationId && mergedConversations.length > 0 && newConversationId === mergedConversations[0].id) {
          const convIdNum = parseInt(newConversationId)
          if (!isNaN(convIdNum) && newConversationId.length <= 8) {
            currentSessionId.value = convIdNum
          }
        }
      }
    } else {
      console.warn('获取会话列表失败，响应码:', response.code, '消息:', response.message)
      message.error(response.message || '获取会话列表失败')
    }
  } catch (error: any) {
    console.error('获取会话列表失败:', error)
    message.error(error.message || '获取会话列表失败')
  } finally {
    isLoadingConversations.value = false
    console.log('获取会话列表完成，当前选中对话ID:', currentConversationId.value)

    // 会话列表加载完成后，确保当前选中的服务器会话设置了sessionId
    if (currentConversationId.value) {
      // 检查当前选中的会话是否在服务器会话列表中
      const currentConv = conversations.value.find(conv => conv.id === currentConversationId.value)
      if (currentConv) {
        // 判断是否为服务器会话：ID是数字字符串且长度较小（服务器会话ID通常较短，时间戳ID是13位）
        const convIdNum = parseInt(currentConv.id)
        if (!isNaN(convIdNum) && currentConv.id.length <= 8) {
          // 很可能是服务器会话，设置sessionId
          currentSessionId.value = convIdNum
          console.log('设置当前sessionId:', currentSessionId.value)
        } else {
          console.log('当前选中对话不是服务器会话，ID:', currentConv.id)
        }
      }

      // 如果是服务器会话，尝试加载其消息
      const convId = parseInt(currentConversationId.value)
      if (!isNaN(convId) && (!messages.value[currentConversationId.value] || messages.value[currentConversationId.value].length === 0)) {
        console.log('尝试加载会话消息，会话ID:', currentConversationId.value)
        // 异步加载消息，不阻塞UI
        loadConversationMessages(currentConversationId.value).catch(err => {
          console.error('后台加载消息失败:', err)
          // 静默失败，不显示错误消息
        })
      }
    }
  }
}

/**
 * 创建新对话
 */
const startNewChat = () => {
  const newId = Date.now().toString()
  const newConversation: Conversation = {
    id: newId,
    title: '新对话',
    updatedAt: Date.now(),
    messageCount: 0,
    isLocal: true
  }
  conversations.value.unshift(newConversation)
  messages.value[newId] = []
  currentConversationId.value = newId
  // 新对话不需要sessionId
  currentSessionId.value = undefined
  message.success('已创建新对话')
}

/**
 * 选择对话
 * @param id 对话ID
 */
const selectConversation = async (id: string) => {
  // 如果已经选中当前会话，不重复加载
  if (currentConversationId.value === id && messages.value[id]?.length > 0) {
    return
  }

  // 检查当前会话是否为空本地新会话，如果是则删除
  const currentConv = conversations.value.find(conv => conv.id === currentConversationId.value)
  if (currentConv && currentConv.id !== id && currentConv.isLocal && (!messages.value[currentConv.id] || messages.value[currentConv.id].length === 0)) {
    // 删除空本地新会话
    const index = conversations.value.findIndex(conv => conv.id === currentConv.id)
    if (index !== -1) {
      conversations.value.splice(index, 1)
      delete messages.value[currentConv.id]
      // 如果当前会话被删除，不需要更新currentConversationId，因为即将切换到目标会话
    }
  }

  currentConversationId.value = id
  // 清除当前会话的sessionId，因为切换会话后需要重新获取
  currentSessionId.value = undefined

  // 如果会话ID是数字字符串且长度较小（服务器会话），则设置sessionId
  const conversationIdNum = parseInt(id)
  if (!isNaN(conversationIdNum) && id.length <= 8) {
    currentSessionId.value = conversationIdNum
  }

  // 如果已有消息数据，直接显示
  if (messages.value[id] && messages.value[id].length > 0) {
    return
  }

  // 否则从服务器加载消息
  await loadConversationMessages(id)
}

/**
 * 加载会话消息
 * @param conversationId 对话ID
 */
const loadConversationMessages = async (conversationId: string) => {
  // 检查是否已经是服务器ID（数字字符串）
  const convId = parseInt(conversationId)
  if (isNaN(convId)) {
    console.log('会话ID不是数字，跳过加载历史消息:', conversationId)
    return
  }

  // 检查是否为本地新会话，如果是则跳过加载（没有历史消息）
  const conversation = conversations.value.find(conv => conv.id === conversationId)
  if (conversation?.isLocal) {
    console.log('会话为本地新会话，跳过加载历史消息:', conversationId)
    return
  }

  isLoadingMessages.value = true
  try {
    const response = await getConversationMessages(convId)
    console.log('获取会话消息响应:', response)

    if (response.code === 200) {
      // 使用新的消息分组处理逻辑，处理分段思考过程和TOOL消息
      const messagesData = response.data || []
      const mappedMessages = processMessageGroups(messagesData)

      console.log('映射后的消息列表:', mappedMessages)

      // 保存到messages对象中
      messages.value[conversationId] = mappedMessages

      // 更新会话的消息数量
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

const deleteConversation = async (id: string) => {
  console.log('deleteConversation called with id:', id)
  // 检查是否为本地会话
  const conversation = conversations.value.find(conv => conv.id === id)
  console.log('found conversation:', conversation)
  if (!conversation) {
    // 会话可能已被迁移或删除，刷新列表
    try {
      await fetchConversations()
      message.info('会话列表已刷新，请重试删除操作')
    } catch (error) {
      console.error('刷新会话列表失败:', error)
    }
    return
  }
  console.log('conversation.isLocal:', conversation.isLocal)

  // 用户确认
  Modal.confirm({
    title: '确认删除',
    content: `确定要删除对话 "${conversation.title}" 吗？删除后无法恢复。`,
    okText: '删除',
    okType: 'danger',
    cancelText: '取消',
    onOk: async () => {
      try {
        // 判断是否为服务器会话：非本地会话或ID为服务器ID（数字且长度<=8）
        console.log('deleteConversation onOk: conversation.isLocal =', conversation.isLocal, 'id =', id)
        const isServerId = !isNaN(parseInt(id)) && id.length <= 8
        console.log('isServerId:', isServerId)
        if (!conversation.isLocal || isServerId) {
          const conversationIdNum = parseInt(id)
          console.log('parsed conversationIdNum:', conversationIdNum)
          if (!isNaN(conversationIdNum)) {
            console.log('calling deleteConversationApi')
            await deleteConversationApi(conversationIdNum)
            console.log('deleteConversationApi called successfully')
          } else {
            console.log('conversationIdNum is NaN')
          }
        } else {
          console.log('conversation is local and id is not server id, skipping API call')
        }

        // 从前端删除会话
        const index = conversations.value.findIndex(conv => conv.id === id)
        if (index !== -1) {
          const isCurrentConversation = currentConversationId.value === id

          conversations.value.splice(index, 1)
          delete messages.value[id]

          // 如果删除的是当前选中的会话
          if (isCurrentConversation) {
            if (conversations.value.length > 0) {
              // 有剩余会话，选择第一个并加载消息
              const nextConversationId = conversations.value[0].id
              await selectConversation(nextConversationId)
            } else {
              // 没有剩余会话，清空当前选中
              currentConversationId.value = ''
              currentSessionId.value = undefined
            }
          }

          message.success('对话已删除')
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
  console.log('sendMessage called with text:', text)
  if (!text) return

  // 防重复发送检查
  if (isSending.value) {
    console.warn('已有消息正在发送中，忽略重复点击')
    return
  }

  // 检查是否有未完成的AI消息
  if (currentAiMessage.value?.isStreaming) {
    console.warn('有未完成的AI消息正在流式传输，忽略新消息')
    message.warning('请等待AI回复完成后再发送新消息')
    return
  }

  // 保存当前对话ID到局部变量，防止在流式传输过程中切换对话导致的问题
  let conversationId = currentConversationId.value

  // 如果没有当前会话（会话列表为空、当前选中为空、或当前选中会话不存在于列表中），自动创建新会话
  if (!conversationId || conversations.value.length === 0 || !conversations.value.find(conv => conv.id === conversationId)) {
    startNewChat()
    conversationId = currentConversationId.value
  }

  // 获取当前会话对象
  const currentConv = conversations.value.find(conv => conv.id === conversationId)

  console.log('Starting streaming for conversation:', conversationId)

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

  // 获取当前对话对象（使用conversationId而不是currentConversationId.value）

  // 更新对话标题（如果是新对话且第一条消息）
  if (currentConv?.title === '新对话' && messages.value[conversationId].length === 1) {
    currentConv.title = text.length > 20 ? text.substring(0, 20) + '...' : text
  }

  // 更新对话时间
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
  currentAiMessage.value = aiMsg // 保存当前AI消息引用

  // 默认思考过程展开（流式聊天）
  thinkingVisible.value[aiMsgId] = true

  try {
    // 调用流式聊天API
    const sessionId = currentSessionId.value
    const abortCtrl = new AbortController()
    stopAbortController.value = abortCtrl
    console.log('Calling streamChat with text:', text, 'sessionId:', sessionId)
    const events = streamChat(text, sessionId, { executionMode: settingsStore.getMode('ai_assistant'), model: settingsStore.getModel('ai_assistant'), thinkingMode: settingsStore.getThinkingMode('ai_assistant') }, abortCtrl)
    console.log('streamChat returned, starting to process events')
    console.log('Current conversation ID:', conversationId, 'AI message ID:', aiMsgId)

    let thinkingContent = ''
    let responseContent = ''

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
            console.log('Stream complete, setting isStreaming=false')
            currentAiMessage.value.isStreaming = false
            currentAiMessage.value.tokenCount = estimateTokenCount(responseContent)
            currentAiMessage.value.content = responseContent
            // 触发响应式更新
            messages.value[conversationId] = [...messages.value[conversationId]]
          } else {
            console.warn('currentAiMessage not found or ID mismatch for content:', aiMsgId)
          }
          scrollToBottom()
          break

        case 'complete':
          console.log('complete事件触发，event.sessionId:', event.sessionId, '本次调用sessionId:', sessionId)

          // 保存sessionId（如果后端返回）
          if (event.sessionId !== undefined) {
            const newSessionId = event.sessionId
            console.log('收到后端返回的sessionId:', newSessionId)
            currentSessionId.value = newSessionId
          }

          // 标记流式传输完成
          if (currentAiMessage.value && currentAiMessage.value.id === aiMsgId) {
            console.log('Marking AI message as complete:', 'role:', currentAiMessage.value.role)
            currentAiMessage.value.isStreaming = false
            // 计算AI消息内容的Token数量（不包含思考过程）
            currentAiMessage.value.tokenCount = estimateTokenCount(responseContent)
            // 触发响应式更新
            messages.value[conversationId] = [...messages.value[conversationId]]
          } else {
            console.warn('currentAiMessage not found or ID mismatch for complete:', aiMsgId)
          }
          break

        case 'error':
          throw new Error(event.data)

        case 'ask_user':
          // 收到 ask_user 问题，显示问答面板
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
      }
    }

    // 如果是本地新会话的第一次流式应答完成，更新会话信息
    if (currentConv?.isLocal) {
      try {
        console.log('本地新会话第一次流式应答完成，获取最新会话列表更新信息')
        const listResponse = await getConversationList('ai_assistant')
        if (listResponse.code === 200 && listResponse.data && listResponse.data.length > 0) {
          const firstConversation = listResponse.data[0]
          console.log('获取到最新会话列表第一条数据:', firstConversation)

          // 更新当前会话的标题和ID
          const serverConversation = mapConversationResponseToConversation(firstConversation)
          currentConv.title = serverConversation.title
          currentConv.id = serverConversation.id // 更新ID为服务器返回的ID
          currentConv.isLocal = false // 标记为非本地会话

          // 更新sessionId为服务器会话ID
          const serverIdNum = parseInt(serverConversation.id)
          if (!isNaN(serverIdNum)) {
            currentSessionId.value = serverIdNum
            console.log('更新sessionId为服务器会话ID:', serverIdNum)
          }

          // 更新messages映射的键（如果ID变化）
          if (serverConversation.id !== conversationId) {
            messages.value[serverConversation.id] = messages.value[conversationId] || []
            delete messages.value[conversationId]
            conversationId = serverConversation.id
            currentConversationId.value = serverConversation.id
          }

          console.log('本地新会话已同步到服务器:', currentConv)
        }
      } catch (error) {
        console.error('获取会话列表更新本地会话失败:', error)
        // 静默失败，不影响主流程
      }
    }

    // 如果没有任何内容，设置默认响应
    if (!responseContent && !thinkingContent) {
      if (currentAiMessage.value && currentAiMessage.value.id === aiMsgId) {
        currentAiMessage.value.content = '收到空响应，请重试。'
        currentAiMessage.value.isStreaming = false
        // 触发响应式更新
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
    console.error('Error details:', error.name, error.message, error.stack)
    // 更新错误消息
    if (currentAiMessage.value && currentAiMessage.value.id === aiMsgId) {
      currentAiMessage.value.content = `请求失败: ${error.message}`
      currentAiMessage.value.isStreaming = false
      // 触发响应式更新
      messages.value[conversationId] = [...messages.value[conversationId]]
    }
    // message.error(error.message || '聊天请求失败') // 错误消息已展示在AI助手消息泡泡中
  } finally {
    isSending.value = false
    currentAiMessage.value = null // 清除当前AI消息引用
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
  // 使用完整的Markdown渲染
  return renderMarkdown(content)
}

// 格式化思考过程（纯文本展示，模仿DeepSeek官网风格）
const escapeHtml = (text: string) => {
  return text
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
    .replace(/'/g, '&#039;')
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
        let seg = escapeHtml(thinking.slice(lastPos, tr.at))
        seg = seg.replace(/&lt;strong&gt;/g, '<strong>').replace(/&lt;\/strong&gt;/g, '</strong>')
        parts.push(seg)
      }
      const escaped = escapeHtml(tr.content)
      parts.push(`<details class="thinking-tool-result"><summary>🔧 工具调用结果</summary><pre>${escaped}</pre></details>`)
      lastPos = tr.at
    }
    if (lastPos < thinking.length) {
      let seg = escapeHtml(thinking.slice(lastPos))
      seg = seg.replace(/&lt;strong&gt;/g, '<strong>').replace(/&lt;\/strong&gt;/g, '</strong>')
      parts.push(seg)
    }
    return parts.join('\n')
  }

  let processed = escapeHtml(thinking)
  processed = processed.replace(/&lt;strong&gt;/g, '<strong>').replace(/&lt;\/strong&gt;/g, '</strong>')
  return processed
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

.conv-actions {
  opacity: 0;
  transition: opacity 0.2s;
}

.conversation-item:hover .conv-actions {
  opacity: 1;
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
.collapsed .empty-conversations p,
.collapsed .empty-hint {
  display: none;
}

.collapsed .conversation-list {
  padding: 12px 4px;
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

.empty-hint {
  font-size: 11px;
  margin-top: 6px;
  color: #a0aec0;
}


.user-info {
  display: flex;
  align-items: center;
}

.user-avatar {
  width: 32px;
  height: 32px;
  background: linear-gradient(135deg, #667eea, #764ba2);
  border-radius: 50%;
  display: flex;
  align-items: center;
  justify-content: center;
  color: white;
  font-weight: bold;
  margin-right: 12px;
}

.user-name {
  font-weight: 500;
  color: #262626;
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

/* 思考过程样式 - DeepSeek官网风格 */
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

/* 第一个思考文本块 */
.thinking-content .thinking-text:first-child {
  border-top: 1px solid #e2e8f0;
  border-radius: 6px 6px 0 0;
}

/* 第二个及之后的思考文本块 */
.thinking-content .thinking-text:not(:first-child) {
  border-top: 1px solid #e2e8f0;
  border-radius: 0;
  margin-top: -1px; /* 消除边框重叠产生的间隙 */
}

/* 最后一个思考文本块 */
.thinking-content .thinking-text:last-child {
  border-bottom: 1px solid #e2e8f0;
  border-radius: 0 0 6px 6px;
}

/* 当只有一个思考文本块时 */
.thinking-content .thinking-text:first-child:last-child {
  border-radius: 6px;
  border-top: 1px solid #e2e8f0;
  border-bottom: 1px solid #e2e8f0;
}


/* 为了向后兼容，保留pre样式但不再使用 */
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