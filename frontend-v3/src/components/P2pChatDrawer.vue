<!--
  P2P 会话抽屉（F01）
  ----------------------------------------------------------------------------
  /p2p 从「设备管理页」升级为「可聊天、可传文件」的完整协作面板：
    设备行「聊天」按钮 → 本抽屉（620px，可拖宽）→ 历史 + 2 秒轮询 + 发送。

  数据接缝（全部零后端改动）：
    GET    /api/p2p/messages/{peerId}/history?offset=0&limit=1000 → 历史（ASC，最早的 1000 条）
    GET    /api/p2p/messages/{peerId}                            → 轮询增量（后端队列，拉后清空）
    POST   /api/p2p/send/{peerId}        { content }             → 文本
    POST   /api/p2p/agent/invoke/{peerId}{ agentConfigId, message } → 调用对方的 Agent
    POST   /api/p2p/send-file/{peerId}   multipart(file)         → 图片/文件（≤2GB）
    POST   /api/p2p/file/{peerId}/{transferId}/open-dir          → 在系统文件管理器中定位
    DELETE /api/p2p/messages/{peerId}                            → 清空会话
    GET    /api/p2p/agent-auth/received/{peerId}                 → 我可调用的对方 Agent

  三条必须记住的后端语义：
    1) 轮询队列只放「对方发来的」帧（P2pConnectionManager.messageQueues），
       自己发的文本不会回显，因此本地乐观插入不会重复；仍保留 dedupeIncoming 兜底。
    2) 历史接口是 `ORDER BY created_at ASC LIMIT ? OFFSET ?`，没有总数接口 ——
       一次拉 HISTORY_LIMIT 条覆盖真实使用量，达到上限时顶部提示「仅显示最早 N 条」。
    3) 己方发出的文件 `local_path` 为 null，`GET /file/...` 会 404，图片气泡走占位（见 P2pChatBubble）。
-->
<template>
  <aside class="cc-drawer cc-p2p-chat" :class="{ 'is-open': open }" style="--drawer-w: 620px">
    <DrawerResizer storage-key="p2p-chat" />

    <!-- 头部：对方信息 + 授权 / 清空 / 关闭 -->
    <div class="cc-drawer__head">
      <div class="cc-p2p-chat__peer">
        <span class="cc-p2p-chat__avatar" :style="{ background: peerGradient(peer?.peerId || '') }">
          {{ peerInitial }}
        </span>
        <div class="cc-p2p-chat__peerinfo">
          <span class="cc-p2p-chat__name">{{ peerName }}</span>
          <span class="cc-p2p-chat__sub">
            <span class="cc-status" :class="peer?.online ? 'is-connected' : ''">
              <span class="cc-status__dot" />{{ peer?.online ? '在线' : '离线（可查历史，恢复连接后可继续发送）' }}
            </span>
          </span>
        </div>
      </div>

      <button class="cc-btn" style="margin-left: auto" title="管理 Agent 授权" @click="authOpen = true">
        <svg class="cc-i cc-i--sm"><use href="#i-shield" /></svg>授权
      </button>
      <button
        class="cc-icon-btn"
        title="清空聊天记录"
        :disabled="!messages.length"
        @click="clearHistory"
      >
        <svg class="cc-i cc-i--sm"><use href="#i-trash" /></svg>
      </button>
      <button class="cc-icon-btn" title="关闭" @click="close">
        <svg class="cc-i cc-i--sm" style="transform: rotate(45deg)"><use href="#i-plus" /></svg>
      </button>
    </div>

    <!-- 消息区 -->
    <div
      ref="listEl"
      class="cc-p2p-chat__list"
      :class="{ 'is-drag': dragOver }"
      @scroll="onScroll"
      @dragover.prevent="onDragOver"
      @dragleave.prevent="dragOver = false"
      @drop.prevent="onDrop"
    >
      <div v-if="loading" class="cc-p2p-chat__hint">正在读取聊天记录…</div>
      <div v-else-if="error" class="cc-p2p-chat__hint is-error">{{ error }}</div>

      <div v-if="!loading && !error && !messages.length" class="cc-p2p-chat__empty">
        <svg class="cc-i cc-i--lg"><use href="#i-chat" /></svg>
        <span>还没有消息</span>
        <span class="cc-p2p-chat__empty-sub">
          端到端加密直连，消息不经过服务器中转；也可以切到「Agent 模式」调用对方授权的智能体
        </span>
      </div>

      <div v-if="truncated" class="cc-p2p-chat__truncate">
        仅显示最早的 {{ HISTORY_LIMIT }} 条记录（更早的消息未加载）
      </div>

      <template v-for="group in grouped" :key="group.key">
        <div class="cc-p2p-chat__day"><span>{{ group.label }}</span></div>
        <P2pChatBubble
          v-for="msg in group.items"
          :key="msg.key"
          :message="msg"
          :peer-id="peer?.peerId || ''"
          :peer-initial="peerInitial"
          :my-initial="myInitial"
          @open-dir="onOpenDir"
          @retry="onRetry"
        />
      </template>
    </div>

    <!-- 输入区 -->
    <div class="cc-p2p-chat__foot">
      <div class="cc-p2p-chat__modes">
        <div class="cc-seg">
          <button :class="{ 'is-active': mode === 'chat' }" @click="switchMode('chat')">
            <svg class="cc-i cc-i--sm"><use href="#i-chat" /></svg>聊天
          </button>
          <button
            :class="{ 'is-active': mode === 'agent' }"
            :disabled="!peer?.online || !receivedAgents.length"
            :title="
              !peer?.online
                ? '对方已离线，无法调用 Agent'
                : !receivedAgents.length
                  ? '对方尚未授权任何 Agent（可在「授权」里让对方授权）'
                  : '调用对方授权的智能体'
            "
            @click="switchMode('agent')"
          >
            <svg class="cc-i cc-i--sm"><use href="#i-agent" /></svg>Agent
            <span v-if="receivedAgents.length" class="cc-p2p-chat__badge">{{ receivedAgents.length }}</span>
          </button>
        </div>
        <span v-if="uploading" class="cc-p2p-chat__uploading">
          <span class="cc-p2p-chat__spinner" />正在发送文件…
        </span>
      </div>

      <div v-if="mode === 'agent'" class="cc-p2p-chat__agents">
        <span class="cc-p2p-chat__agents-label">目标 Agent：</span>
        <template v-if="receivedAgents.length">
          <span
            v-for="agent in receivedAgents"
            :key="agent.id"
            class="cc-chip"
            :class="{ 'is-active': selectedAgentId === agent.id }"
            @click="selectedAgentId = agent.id"
          >
            {{ agent.name }}
          </span>
        </template>
        <span v-else class="cc-p2p-chat__agents-empty">对方暂未授权任何 Agent</span>
      </div>

      <div class="cc-p2p-chat__inputrow">
        <input ref="fileEl" type="file" style="display: none" @change="onFilePicked" />
        <button
          class="cc-icon-btn"
          :disabled="!peer?.online || uploading"
          title="发送图片或文件（也可直接拖拽 / 粘贴）"
          @click="fileEl?.click()"
        >
          <svg class="cc-i cc-i--sm"><use href="#i-file" /></svg>
        </button>

        <textarea
          v-model="input"
          class="cc-p2p-chat__input"
          rows="2"
          :disabled="!peer?.online"
          :placeholder="placeholder"
          @keydown.enter.exact.prevent="onSend"
          @paste="onPaste"
        />

        <button
          class="cc-btn cc-btn--primary cc-p2p-chat__send"
          :disabled="!canSend"
          :title="sendTitle"
          @click="onSend"
        >
          <svg class="cc-i cc-i--sm"><use href="#i-send" /></svg>发送
        </button>
      </div>
    </div>
  </aside>

  <!--
    授权管理抽屉必须是 <aside> 的兄弟节点，不能嵌在它里面：
    `.cc-drawer` 靠 `transform: translateX(100%)` 收起，而 transform 会为后代的
    `position: fixed` 创建新的包含块 —— 嵌在里面的抽屉会相对父抽屉定位而错位。
  -->
  <P2pAuthDrawer v-model:open="authOpen" :peer="peer" @changed="onAuthChanged" />
</template>

<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, ref, watch } from 'vue'
import DrawerResizer from './DrawerResizer.vue'
import P2pChatBubble from './P2pChatBubble.vue'
import P2pAuthDrawer from './P2pAuthDrawer.vue'
import {
  deleteMessages,
  getHistory,
  getPeerAuthToMe,
  invokeAgent,
  openFileDir,
  pollMessages,
  sendFile,
  sendMessage
} from '../api/p2p'
import { confirm } from '../utils/confirm'
import { toast } from '../utils/toast'
import type { P2pPeer } from './P2pPeerRow.vue'
import {
  HISTORY_LIMIT,
  MAX_FILE_SIZE,
  dedupeIncoming,
  groupByDay,
  nextKey,
  normalizeHistory,
  normalizePoll,
  peerGradient,
  type P2pChatMessage
} from '../utils/p2p-chat'

const props = withDefaults(
  defineProps<{
    open: boolean
    peer?: P2pPeer | null
    /** 本机名称（消息里显示「我」的头像首字） */
    myName?: string
  }>(),
  { peer: null, myName: '' }
)

const emit = defineEmits<{
  (e: 'update:open', value: boolean): void
}>()

interface AgentOption {
  id: number
  name: string
}

/** 轮询间隔：2 秒（页面不可见时跳过） */
const POLL_MS = 2000
/** 贴底判定阈值 */
const BOTTOM_GAP = 60

const messages = ref<P2pChatMessage[]>([])
const loading = ref(false)
const error = ref('')
const truncated = ref(false)

const mode = ref<'chat' | 'agent'>('chat')
const input = ref('')
const sending = ref(false)
const uploading = ref(false)
const receivedAgents = ref<AgentOption[]>([])
const selectedAgentId = ref<number | null>(null)
const authOpen = ref(false)

const listEl = ref<HTMLElement | null>(null)
const fileEl = ref<HTMLInputElement | null>(null)
const atBottom = ref(true)
const dragOver = ref(false)

let timer: number | null = null
let pollBusy = false

const peerName = computed(
  () => props.peer?.remark || props.peer?.displayName || props.peer?.name || props.peer?.peerId || '未知设备'
)

const peerInitial = computed(() => {
  const text = peerName.value.trim()
  return text ? text.slice(0, 1).toUpperCase() : '?'
})

const myInitial = computed(() => {
  const text = (props.myName || '我').trim()
  return text ? text.slice(0, 1).toUpperCase() : '我'
})

const grouped = computed(() => groupByDay(messages.value))

const canSend = computed(() => {
  if (!props.peer?.online || sending.value || uploading.value) return false
  if (!input.value.trim()) return false
  if (mode.value === 'agent') return !!selectedAgentId.value
  return true
})

const sendTitle = computed(() => {
  if (!props.peer?.online) return '对方已离线，无法发送'
  if (mode.value === 'agent' && !selectedAgentId.value) return '请先选择要调用的 Agent'
  if (!input.value.trim()) return '请输入内容'
  return '发送（Enter）'
})

const placeholder = computed(() => {
  if (!props.peer?.online) return '对方已离线，恢复连接后可继续发送'
  if (mode.value === 'agent') {
    return selectedAgentId.value ? '输入给 Agent 的指令…（Enter 发送，Shift+Enter 换行）' : '请先选择一个 Agent'
  }
  return '输入消息…（Enter 发送，Shift+Enter 换行，可粘贴图片或拖拽文件）'
})

function close() {
  emit('update:open', false)
}

function errText(err: unknown, fallback: string) {
  if (err instanceof Error && err.message) return err.message
  return fallback
}

/* ---------------- 滚动 ---------------- */
function onScroll() {
  const el = listEl.value
  if (!el) return
  atBottom.value = el.scrollHeight - el.scrollTop - el.clientHeight < BOTTOM_GAP
}

function scrollToBottom(force = false) {
  const el = listEl.value
  if (!el) return
  if (!force && !atBottom.value) return
  void nextTick(() => {
    el.scrollTop = el.scrollHeight
  })
}

/** push 后取回响应式代理（直接改原对象不会触发更新） */
function pushMessage(msg: P2pChatMessage): P2pChatMessage {
  messages.value.push(msg)
  return messages.value[messages.value.length - 1]
}

/* ---------------- 加载 ---------------- */
async function loadHistory(peerId: string) {
  loading.value = true
  error.value = ''
  try {
    const list = await getHistory(peerId, 0, HISTORY_LIMIT)
    const normalized = normalizeHistory(list || [])
    // 恢复「AI 仍在思考」：agent_invoke 之后没有同 Agent 的 response
    normalized.forEach((msg, i) => {
      if (msg.kind !== 'agent-invoke') return
      const answered = normalized
        .slice(i + 1)
        .some(later => later.kind === 'agent-response' && later.agentName === msg.agentName)
      if (!answered) msg.pending = true
    })
    messages.value = normalized
    truncated.value = normalized.length >= HISTORY_LIMIT
    atBottom.value = true
    scrollToBottom(true)
  } catch (err) {
    error.value = errText(err, '读取聊天记录失败')
  } finally {
    loading.value = false
  }
}

async function loadAgents(peerId: string) {
  try {
    const list = await getPeerAuthToMe(peerId)
    receivedAgents.value = (list || []).map(a => ({ id: Number(a.id), name: a.name }))
    if (!receivedAgents.value.some(a => a.id === selectedAgentId.value)) {
      selectedAgentId.value = receivedAgents.value[0]?.id ?? null
    }
  } catch {
    receivedAgents.value = []
    selectedAgentId.value = null
  }
}

/** 打开（或切设备）时：重置状态 + 拉历史 + 拉授权 + 开始轮询 */
async function bootstrap(peerId: string) {
  messages.value = []
  truncated.value = false
  mode.value = 'chat'
  input.value = ''
  error.value = ''
  await Promise.all([loadHistory(peerId), loadAgents(peerId)])
  startPoll()
}

/* ---------------- 轮询 ---------------- */
async function poll() {
  const peerId = props.peer?.peerId
  if (!peerId || !props.open || pollBusy) return
  if (typeof document !== 'undefined' && document.visibilityState === 'hidden') return
  pollBusy = true
  try {
    const list = await pollMessages(peerId)
    if (!list?.length) return
    const incoming = dedupeIncoming(messages.value, normalizePoll(list))
    if (!incoming.length) return

    for (const msg of incoming) {
      // 收到 Agent 回复 → 关掉对应 invoke 的转圈
      if (msg.kind === 'agent-response') {
        for (let i = messages.value.length - 1; i >= 0; i -= 1) {
          const old = messages.value[i]
          if (old.kind === 'agent-invoke' && old.pending) {
            old.pending = false
            break
          }
        }
      }
      pushMessage(msg)
    }
    scrollToBottom()
  } catch {
    // 轮询失败静默：网络抖动不该在聊天区刷错误
  } finally {
    pollBusy = false
  }
}

function startPoll() {
  stopPoll()
  timer = window.setInterval(() => void poll(), POLL_MS)
}

function stopPoll() {
  if (timer !== null) {
    window.clearInterval(timer)
    timer = null
  }
}

/* ---------------- 发送 ---------------- */
function switchMode(next: 'chat' | 'agent') {
  if (next === 'agent' && (!props.peer?.online || !receivedAgents.value.length)) return
  mode.value = next
}

function onSend() {
  if (!canSend.value) return
  if (mode.value === 'agent') void sendAgentInvoke()
  else void sendText()
}

async function sendText() {
  const peerId = props.peer?.peerId
  const text = input.value.trim()
  if (!peerId || !text) return
  input.value = ''
  sending.value = true
  const local = pushMessage({
    key: nextKey('local'),
    from: 'me',
    kind: 'chat',
    messageType: 'chat',
    name: props.myName || '我',
    content: text,
    at: Date.now()
  })
  scrollToBottom(true)
  try {
    await sendMessage(peerId, text)
  } catch (err) {
    local.failed = true
    toast.error(errText(err, '消息发送失败'))
  } finally {
    sending.value = false
  }
}

async function sendAgentInvoke() {
  const peerId = props.peer?.peerId
  const text = input.value.trim()
  const agent = receivedAgents.value.find(a => a.id === selectedAgentId.value)
  if (!peerId || !text || !agent) return
  input.value = ''
  sending.value = true
  const local = pushMessage({
    key: nextKey('local'),
    from: 'me',
    kind: 'agent-invoke',
    messageType: 'agent_invoke',
    name: props.myName || '我',
    content: `调用 Agent「${agent.name}」: ${text}`,
    at: Date.now(),
    agentConfigId: agent.id,
    agentName: agent.name,
    pending: true
  })
  scrollToBottom(true)
  try {
    await invokeAgent(peerId, agent.id, text)
  } catch (err) {
    local.pending = false
    local.failed = true
    toast.error(errText(err, '调用 Agent 失败'))
  } finally {
    sending.value = false
  }
}

/** 失败重发（仅文本消息） */
async function onRetry(msg: P2pChatMessage) {
  const peerId = props.peer?.peerId
  if (!peerId) return
  try {
    await sendMessage(peerId, msg.content)
    msg.failed = false
    toast.success('已重新发送')
  } catch (err) {
    toast.error(errText(err, '重发失败'))
  }
}

/* ---------------- 文件 / 图片 ---------------- */
function onFilePicked(event: Event) {
  const input = event.target as HTMLInputElement
  const file = input.files?.[0]
  input.value = ''
  if (file) void sendFileImpl(file)
}

function onDragOver() {
  if (props.peer?.online) dragOver.value = true
}

function onDrop(event: DragEvent) {
  dragOver.value = false
  if (!props.peer?.online) return
  const file = event.dataTransfer?.files?.[0]
  if (file) void sendFileImpl(file)
}

function onPaste(event: ClipboardEvent) {
  if (!props.peer?.online) return
  const items = event.clipboardData?.items
  if (!items) return
  for (const item of items) {
    if (item.kind !== 'file') continue
    const file = item.getAsFile()
    if (!file) continue
    event.preventDefault()
    void sendFileImpl(file)
    return
  }
}

async function sendFileImpl(file: File) {
  const peerId = props.peer?.peerId
  if (!peerId) return
  if (!props.peer?.online) {
    toast.warning('对方已离线，无法发送文件')
    return
  }
  if (file.size > MAX_FILE_SIZE) {
    toast.warning('文件过大，最大支持 2GB')
    return
  }
  if (uploading.value) return
  uploading.value = true
  try {
    const res = await sendFile(peerId, file)
    const isImage = res.category === 'image'
    pushMessage({
      key: nextKey('file'),
      from: 'me',
      kind: 'file',
      messageType: 'file_transfer',
      name: props.myName || '我',
      content: `[${isImage ? '图片' : '文件'}] ${file.name}`,
      at: Date.now(),
      fileName: file.name,
      fileSize: file.size,
      mimeType: file.type || 'application/octet-stream',
      fileCategory: res.category,
      transferId: res.transferId,
      fileStatus: 'completed'
    })
    scrollToBottom(true)
    toast.success(isImage ? '图片已发送' : '文件已发送')
  } catch (err) {
    toast.error(errText(err, '文件发送失败'))
  } finally {
    uploading.value = false
  }
}

async function onOpenDir(msg: P2pChatMessage) {
  const peerId = props.peer?.peerId
  if (!peerId || !msg.transferId) return
  try {
    await openFileDir(peerId, msg.transferId)
  } catch (err) {
    toast.error(errText(err, '无法打开文件所在目录'))
  }
}

/* ---------------- 清空 / 授权 ---------------- */
async function clearHistory() {
  const peerId = props.peer?.peerId
  if (!peerId || !messages.value.length) return
  const ok = await confirm({
    title: '清空聊天记录',
    content: `将删除与「${peerName.value}」的全部聊天记录（含文件消息），此操作不可撤销。`,
    okText: '清空',
    type: 'danger'
  })
  if (!ok) return
  try {
    await deleteMessages(peerId)
    messages.value = []
    truncated.value = false
    toast.success('已清空聊天记录')
  } catch (err) {
    toast.error(errText(err, '清空聊天记录失败'))
  }
}

async function onAuthChanged(peerId: string) {
  // 授权变化只影响「Agent 模式可选列表」，刷新即可；列表刷新由父级在关闭抽屉时统一做
  await loadAgents(peerId)
}

/* ---------------- 生命周期 ---------------- */
/** ESC：先关授权抽屉，再关本抽屉（轻量浮层优先） */
function onKeydown(event: KeyboardEvent) {
  if (!props.open || event.key !== 'Escape') return
  if (authOpen.value) {
    authOpen.value = false
    event.stopPropagation()
    return
  }
  close()
}

watch(
  () => props.open,
  open => {
    if (open) document.addEventListener('keydown', onKeydown)
    else document.removeEventListener('keydown', onKeydown)
  },
  { immediate: true }
)

watch(
  () => [props.open, props.peer?.peerId] as const,
  ([open, peerId]) => {
    if (!open || !peerId) {
      stopPoll()
      return
    }
    void bootstrap(peerId)
  },
  { immediate: true }
)

onBeforeUnmount(() => {
  stopPoll()
  document.removeEventListener('keydown', onKeydown)
})
</script>
