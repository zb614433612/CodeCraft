<template>
  <div class="p2p-app">
    <!-- ============ 顶部状态栏 ============ -->
    <header class="top-bar">
      <div class="top-left">
        <div class="logo-icon">
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
            <circle cx="12" cy="6" r="4"/>
            <path d="M4 20c0-4 4-7 8-7s8 3 8 7"/>
            <circle cx="8" cy="15" r="2"/>
            <circle cx="16" cy="12" r="2"/>
            <circle cx="12" cy="18" r="2"/>
          </svg>
        </div>
        <span class="top-title">P2P</span>
        <span :class="['live-dot', status.running ? 'on' : 'off']"></span>
        <span class="top-status">{{ status.running ? '在线' : '离线' }}</span>
      </div>
      <div class="top-mid">
        <span class="top-meta">ID: <code>{{ shortPeerId }}</code></span>
        <span class="top-sep">|</span>
        <span class="top-meta">端口: <code>{{ status.port || '--' }}</code></span>
        <span class="top-sep">|</span>
        <span class="top-meta">节点: <code class="accent">{{ status.peerCount || 0 }}</code></span>
      </div>
      <div class="top-right">
        <a-input
          v-model:value="myName"
          size="small"
          placeholder="设备名称"
          class="name-input-top"
          @blur="saveName"
        />
      </div>
    </header>

    <!-- ============ 主体：侧边栏 + 聊天 ============ -->
    <div class="main-layout">
      <!-- ===== 左侧面板 ===== -->
      <aside class="side-panel">
        <!-- 节点列表 -->
        <div class="side-section side-peers">
          <div class="section-head">
            <span class="section-head-icon">🖥</span>
            <span>在线节点</span>
            <span class="section-badge">{{ peers.length }}</span>
            <button class="btn-icon-only" @click="refreshPeers" title="刷新">🔄</button>
          </div>
          <div class="peer-list">
            <div v-if="peers.length === 0" class="side-empty">
              <div class="side-empty-icon">📡</div>
              <span>暂无连接</span>
              <span class="side-empty-hint">使用「连接对方」或扫码添加节点</span>
            </div>
            <div
              v-for="peer in peers"
              :key="peer.peerId"
              :class="['peer-card', selectedPeerId === peer.peerId ? 'active' : '']"
              @click="selectPeer(peer)"
            >
              <div :class="['peer-avatar', selectedPeerId === peer.peerId ? 'active' : '']"
                   :style="{ background: peerAvatarColor(peer.peerId) }">
                {{ (peer.displayName || '?')[0] }}
              </div>
              <div class="peer-info">
                <div class="peer-name">
                  {{ peer.displayName || shortId(peer.peerId) }}
                  <span class="remark-edit" @click.stop="editRemark(peer)" title="修改备注">✏️</span>
                </div>
                <div class="peer-addr">{{ peer.address }}</div>
              </div>
              <span :class="['mini-dot', peer.online ? 'on' : 'off']"></span>
              <button
                class="btn-disconnect-sm"
                @click.stop="doDisconnect(peer.peerId)"
                title="断开"
              >×</button>
            </div>
          </div>
        </div>

        <!-- 分隔 -->
        <div class="side-divider"></div>

        <!-- 工具区（可折叠） -->
        <div class="side-section side-tools">
          <div class="section-head" @click="toolsOpen = !toolsOpen" style="cursor:pointer">
            <span class="section-head-icon">🔧</span>
            <span>工具</span>
            <span class="section-arrow" :class="{ open: toolsOpen }">▾</span>
          </div>
          <div v-if="toolsOpen" class="tools-body">
            <!-- 连接对方 -->
            <div class="tool-card">
              <div class="tool-label">🔌 连接对方</div>
              <div class="tool-row">
                <a-input v-model:value="inputConnStr" size="small" placeholder="粘贴连接串..." />
                <a-button type="primary" size="small" :loading="loading.connect" :disabled="!inputConnStr" @click="doConnect">连接</a-button>
              </div>
              <div v-if="connectError" class="mini-msg err">{{ connectError }}</div>
              <div v-if="connectSuccess" class="mini-msg ok">{{ connectSuccess }}</div>
            </div>
            <!-- 二维码 -->
            <div class="tool-card">
              <div class="tool-label">📱 二维码</div>
              <div class="tool-row">
                <a-button size="small" :loading="loading.qr" @click="generateQR">✨ 生成</a-button>
                <a-button v-if="connectionString" size="small" @click="copyConnectionString">📋 复制连接串</a-button>
              </div>
              <div v-if="qrCodeBase64" class="qr-mini">
                <img :src="qrCodeBase64" alt="QR" />
              </div>
              <div v-if="qrError" class="mini-msg err">{{ qrError }}</div>
            </div>
            <!-- 本机地址 -->
            <div class="tool-card">
              <div class="tool-label">📡 本机地址</div>
              <div v-if="addressInfo.bestAddress" class="tool-row">
                <code class="addr-mini">{{ addressInfo.bestAddress }}</code>
                <a-button size="small" @click="copyAddress">📋</a-button>
              </div>
              <div v-else class="mini-msg warn">⚠️ 无可用 IPv6 地址</div>
            </div>
          </div>
        </div>
      </aside>

      <!-- ===== 右侧聊天主体 ===== -->
      <main class="chat-main">
        <!-- 未选中节点：Hero -->
        <div v-if="!selectedPeer" class="chat-hero">
          <div class="hero-illustration">
            <div class="hero-orb orb-1"></div>
            <div class="hero-orb orb-2"></div>
            <div class="hero-orb orb-3"></div>
            <div class="hero-icon">💬</div>
          </div>
          <h2 class="hero-title">P2P 安全聊天</h2>
          <p class="hero-desc">端到端加密通信，选择左侧节点开始对话</p>
          <div class="hero-features">
            <div class="hero-feat-item">
              <span class="hero-feat-icon">🔐</span>
              <span>端到端加密</span>
            </div>
            <div class="hero-feat-item">
              <span class="hero-feat-icon">⚡</span>
              <span>低延迟直连</span>
            </div>
            <div class="hero-feat-item">
              <span class="hero-feat-icon">🌐</span>
              <span>去中心化</span>
            </div>
            <div class="hero-feat-item">
              <span class="hero-feat-icon">🤖</span>
              <span>Agent 协作</span>
            </div>
          </div>
        </div>

        <!-- 已选中节点：聊天窗口 -->
        <template v-else>
          <div class="chat-header">
            <div class="chat-header-avatar"
                 :style="{ background: peerAvatarColor(selectedPeer.peerId) }">
              {{ (selectedPeer.displayName || '?')[0] }}
            </div>
            <div class="chat-header-info">
              <div class="chat-header-name">{{ selectedPeer.displayName || shortId(selectedPeer.peerId) }}</div>
              <div class="chat-header-addr">{{ selectedPeer.address }}</div>
            </div>
            <span :class="['chat-header-dot', selectedPeer.online ? 'on' : 'off']"></span>

            <!-- 模式指示器 -->
            <div class="chat-mode-indicator" :class="chatMode">
              <span v-if="chatMode === 'chat'" class="mode-badge mode-chat">💬 聊天模式</span>
              <span v-else class="mode-badge mode-agent">🤖 Agent 模式</span>
            </div>

            <a-button size="small" class="btn-header-action" @click="openAuthModal">🔑 授权管理</a-button>
            <a-button size="small" class="btn-header-action" danger @click="doDisconnect(selectedPeer.peerId)">断开</a-button>
            <a-button size="small" class="btn-header-action" @click="doClearMessages" v-if="chatHistory.length > 0">清空</a-button>
          </div>

          <!-- 消息列表 -->
          <div class="chat-messages" ref="chatMsgs">
            <div v-for="(msg, i) in chatHistory" :key="i"
                 :class="['msg-row',
                   msg.from === 'me' ? 'me' : 'peer',
                   msg.messageType === 'auth_grant' || msg.messageType === 'auth_cancel' ? 'msg-system' : '',
                   msg.messageType === 'agent_invoke' ? 'msg-agent-invoke' : '',
                   msg.messageType === 'agent_response' ? 'msg-agent-response' : '']">

              <!-- 对方头像（Agent响应用特殊头像） -->
              <div v-if="msg.from !== 'me'" class="msg-avatar-sm"
                   :class="{
                     'avatar-agent': msg.messageType === 'agent_response',
                     'avatar-system': msg.messageType === 'auth_grant' || msg.messageType === 'auth_cancel'
                   }">
                <template v-if="msg.messageType === 'agent_response'">🤖</template>
                <template v-else-if="msg.messageType === 'auth_grant' || msg.messageType === 'auth_cancel'">🔔</template>
                <template v-else>{{ (selectedPeer?.displayName || '?')[0] }}</template>
              </div>

              <div class="msg-body">
                <!-- 发送者 + 时间 -->
                <div class="msg-sender">
                  <span v-if="msg.messageType === 'auth_grant' || msg.messageType === 'auth_cancel'">🔔 系统</span>
                  <span v-else-if="msg.messageType === 'agent_invoke'" class="sender-agent-invoke">📤 {{ msg.name }}</span>
                  <span v-else-if="msg.messageType === 'agent_response'" class="sender-agent-resp">🤖 {{ msg.name }}</span>
                  <span v-else>{{ msg.name }}</span>
                  <span class="msg-time">{{ msg.time }}</span>
                </div>

                <!-- 消息内容 -->
                <div :class="['msg-bubble',
                  msg.from === 'me' ? 'bubble-me' : 'bubble-peer',
                  msg.messageType === 'agent_invoke' ? 'bubble-agent-invoke' : '',
                  msg.messageType === 'agent_response' ? 'bubble-agent-response' : '',
                  msg.messageType === 'auth_grant' || msg.messageType === 'auth_cancel' ? 'bubble-system' : '']">

                  <!-- Agent调用消息 -->
                  <template v-if="msg.messageType === 'agent_invoke'">
                    <div class="agent-call-header">
                      <span class="agent-call-icon">🤖</span>
                      <span>调用 Agent「{{ msg.agentName }}」</span>
                    </div>
                    <div class="agent-call-body">{{ extractAgentMessage(msg.content) }}</div>
                    <!-- 调用方的loading动画 -->
                    <div v-if="msg.loading" class="agent-loading-inline">
                      <div class="agent-loading-dots">
                        <span></span><span></span><span></span>
                      </div>
                      <span class="agent-loading-text">AI 正在思考中...</span>
                    </div>
                  </template>

                  <!-- Agent响应消息 -->
                  <template v-else-if="msg.messageType === 'agent_response'">
                    <div class="agent-resp-header">
                      <span class="agent-resp-icon">✨</span>
                      <span>Agent「{{ msg.name }}」响应</span>
                    </div>
                    <div class="agent-resp-body markdown-content" v-html="renderMarkdown(msg.content)"></div>
                  </template>

                  <!-- 普通消息 / 系统消息 -->
                  <template v-else>{{ msg.content }}</template>
                </div>
              </div>

              <!-- 我的头像（在后） -->
              <div v-if="msg.from === 'me'" class="msg-avatar-sm me"
                   :class="{ 'avatar-agent-invoke': msg.messageType === 'agent_invoke' }">
                {{ (myName || '我')[0] }}
              </div>
            </div>
          </div>

          <!-- ===== 聊天底部：双模式 ===== -->
          <div class="chat-footer">
            <!-- 模式切换 Tab -->
            <div class="mode-tabs">
              <button
                :class="['mode-tab', chatMode === 'chat' ? 'active' : '']"
                @click="switchMode('chat')"
              >
                <span class="mode-tab-icon">💬</span>
                <span class="mode-tab-label">聊天</span>
              </button>
              <button
                :class="['mode-tab', chatMode === 'agent' ? 'active' : '']"
                @click="switchMode('agent')"
                :disabled="peerAuthToMe.length === 0"
                :title="peerAuthToMe.length === 0 ? '对方未授权任何 Agent' : '切换到 Agent 调用模式'"
              >
                <span class="mode-tab-icon">🤖</span>
                <span class="mode-tab-label">Agent</span>
                <span v-if="peerAuthToMe.length > 0" class="mode-tab-count">{{ peerAuthToMe.length }}</span>
              </button>
            </div>

            <!-- Agent 模式下：Agent选择器 -->
            <div v-if="chatMode === 'agent' && peerAuthToMe.length > 0" class="agent-select-row">
              <span class="agent-select-label">目标 Agent：</span>
              <div class="agent-chips">
                <button
                  v-for="a in peerAuthToMe"
                  :key="a.id"
                  :class="['agent-chip', selectedAgentId === a.id ? 'active' : '']"
                  @click="selectedAgentId = a.id"
                >
                  <span class="agent-chip-avatar">{{ a.avatar || '🤖' }}</span>
                  <span>{{ a.name }}</span>
                </button>
              </div>
            </div>
            <div v-else-if="chatMode === 'agent' && peerAuthToMe.length === 0" class="agent-select-row">
              <span class="agent-empty-hint">😕 对方暂未授权任何 Agent，请先通过「授权管理」让对方授权</span>
            </div>

            <!-- 输入区 -->
            <div class="input-row">
              <a-input
                v-model:value="chatInput"
                :placeholder="chatMode === 'agent'
                  ? (selectedAgentId ? '输入给 Agent 的指令... (Enter 发送)' : '请先选择一个 Agent')
                  : '输入消息... (Enter 发送)'"
                class="chat-input"
                :disabled="chatMode === 'agent' && !selectedAgentId"
                @pressEnter="doSend" />
              <a-button
                type="primary"
                @click="doSend"
                :disabled="!chatInput || (chatMode === 'agent' && !selectedAgentId)"
                class="btn-send"
              >
                {{ chatMode === 'agent' ? '🚀 发送' : '发送' }}
              </a-button>
            </div>
          </div>
        </template>
      </main>

      <!-- ===== Agent 授权管理弹窗 ===== -->
      <Teleport to="body">
        <Transition name="modal-fade">
          <div v-if="authModalVisible" class="auth-modal-overlay" @click.self="authModalVisible = false">
            <div class="auth-modal">
              <div class="auth-modal-header">
                <h3>🔑 Agent 授权管理</h3>
                <span class="auth-modal-peer">对方：{{ selectedPeer?.displayName || '未知' }}</span>
                <button class="auth-modal-close" @click="authModalVisible = false">✕</button>
              </div>
              <div class="auth-modal-body">
                <!-- 我授权给对方的 -->
                <div class="auth-section">
                  <div class="auth-section-title">📤 我授权给对方的 Agent</div>
                  <div v-if="myAgents.length === 0" class="auth-empty">暂无可用 Agent</div>
                  <div v-for="agent in myAgents" :key="agent.id" class="auth-agent-item">
                    <span class="auth-agent-avatar">{{ agent.avatar || '🤖' }}</span>
                    <div class="auth-agent-info">
                      <span class="auth-agent-name">{{ agent.name }}</span>
                      <span class="auth-agent-desc">{{ agent.description || '' }}</span>
                    </div>
                    <a-button size="small"
                      :type="isAgentAuthorized(agent.id!) ? 'default' : 'primary'"
                      :danger="isAgentAuthorized(agent.id!)"
                      @click="toggleAgentAuth(agent.id!)">
                      {{ isAgentAuthorized(agent.id!) ? '取消授权' : '授权' }}
                    </a-button>
                  </div>
                </div>
                <div class="auth-divider"></div>
                <!-- 对方授权给我的 -->
                <div class="auth-section">
                  <div class="auth-section-title">📥 对方授权给我的 Agent（可调用）</div>
                  <div v-if="peerAuthToMe.length === 0" class="auth-empty">对方暂未授权任何 Agent</div>
                  <div v-for="agent in peerAuthToMe" :key="agent.id" class="auth-agent-item received">
                    <span class="auth-agent-avatar">{{ agent.avatar || '🤖' }}</span>
                    <div class="auth-agent-info">
                      <span class="auth-agent-name">{{ agent.name }}</span>
                    </div>
                    <span class="auth-badge">来自对方</span>
                  </div>
                </div>
              </div>
            </div>
          </div>
        </Transition>
      </Teleport>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted, onUnmounted, computed, nextTick } from 'vue'
import { renderMarkdown } from '@/utils/markdown'
import {
  getP2pStatus, getP2pAddress, setP2pName,
  generateQrCode, connectPeer, disconnectPeer, getPeers,
  sendMessage, pollMessages, getHistory, deleteMessages, setRemark,
  grantAgentAuth, cancelAgentAuth, getMyAuthToPeer, getPeerAuthToMe, invokeAgent,
  type P2pStatus, type P2pAddressInfo, type P2pPeer, type AgentAuthItem
} from '@/api/p2p'
import { listAgentConfigs, type AgentConfig } from '@/api/agent-config'

// ==================== 状态 ====================
const status = ref<P2pStatus>({ running: false, peerId: '', port: 0, peerCount: 0, certFingerprint: '', myName: '' })
const addressInfo = ref<P2pAddressInfo>({ addresses: [], bestAddress: null, hasIpv6: false })
const myName = ref('')
const qrCodeBase64 = ref('')
const qrError = ref('')
const connectionString = ref('')
const inputConnStr = ref('')
const peers = ref<P2pPeer[]>([])
const selectedPeerId = ref('')
const chatHistory = ref<{ from: string; name: string; content: string; time: string; messageType?: string; agentConfigId?: number; agentName?: string }[]>([])
const chatInput = ref('')
const chatMsgs = ref<HTMLElement | null>(null)

const loading = ref({ qr: false, connect: false })
const connectError = ref('')
const connectSuccess = ref('')
const toolsOpen = ref(true)
let pollTimer: ReturnType<typeof setInterval> | null = null

// ===== 聊天模式：'chat' = 人对人，'agent' = 人对Agent =====
const chatMode = ref<'chat' | 'agent'>('chat')
const selectedAgentId = ref<number | null>(null)

// ===== Agent 授权相关状态 =====
const authModalVisible = ref(false)
const myAgents = ref<AgentConfig[]>([])
const myAuthToPeer = ref<AgentAuthItem[]>([])
const peerAuthToMe = ref<AgentAuthItem[]>([])

const shortPeerId = computed(() => status.value.peerId?.substring(0, 16) + '...')
const selectedPeer = computed(() => peers.value.find(p => p.peerId === selectedPeerId.value) || null)

function shortId(id: string) { return id?.substring(0, 12) + '...' }

// ==================== 彩色头像 ====================
const avatarColors = [
  'linear-gradient(135deg, #8b5cf6, #7c3aed)',
  'linear-gradient(135deg, #f59e0b, #f97316)',
  'linear-gradient(135deg, #10b981, #059669)',
  'linear-gradient(135deg, #3b82f6, #2563eb)',
  'linear-gradient(135deg, #ec4899, #db2777)',
  'linear-gradient(135deg, #06b6d4, #0891b2)',
  'linear-gradient(135deg, #84cc16, #65a30d)',
  'linear-gradient(135deg, #f43f5e, #e11d48)',
]
function peerAvatarColor(peerId: string): string {
  if (!peerId) return avatarColors[0]
  let hash = 0
  for (let i = 0; i < peerId.length; i++) {
    hash = ((hash << 5) - hash) + peerId.charCodeAt(i)
    hash |= 0
  }
  return avatarColors[Math.abs(hash) % avatarColors.length]
}

// ==================== 模式切换 ====================
function switchMode(mode: 'chat' | 'agent') {
  chatMode.value = mode
  if (mode === 'chat') {
    selectedAgentId.value = null
  } else if (peerAuthToMe.value.length > 0 && !selectedAgentId.value) {
    // 自动选中第一个Agent
    selectedAgentId.value = peerAuthToMe.value[0].id
  }
}

// ==================== 方法 ====================
async function loadAll() {
  try { status.value = await getP2pStatus(); myName.value = status.value.myName || '' } catch (e) { /* */ }
  try { addressInfo.value = await getP2pAddress() } catch (e) { /* */ }
  try { peers.value = await getPeers() } catch (e) { /* */ }
}

async function saveName() {
  if (myName.value) await setP2pName(myName.value)
}

async function generateQR() {
  loading.value.qr = true; qrError.value = ''
  try {
    const data = await generateQrCode()
    connectionString.value = data.connectionString
    qrCodeBase64.value = data.qrCodeBase64 || ''
    if (!data.qrCodeBase64) qrError.value = (data as any).qrError || '二维码生成失败'
  } catch (e: any) {
    qrError.value = '请求失败: ' + (e.message || e)
  } finally { loading.value.qr = false }
}

async function doConnect() {
  if (!inputConnStr.value) return
  loading.value.connect = true; connectError.value = ''; connectSuccess.value = ''
  try {
    const result = await connectPeer(inputConnStr.value)
    connectSuccess.value = '连接成功！'
    inputConnStr.value = ''
    await refreshPeers()
    if (result.peerId) selectPeerById(result.peerId)
  } catch (e: any) {
    connectError.value = '连接失败: ' + (e.message || e)
  } finally { loading.value.connect = false }
}

async function doDisconnect(peerId: string) {
  await disconnectPeer(peerId)
  if (selectedPeerId.value === peerId) {
    selectedPeerId.value = ''
    chatHistory.value = []
    chatMode.value = 'chat'
    selectedAgentId.value = null
  }
  await refreshPeers()
}

async function refreshPeers() {
  try { peers.value = await getPeers() } catch (e) { /* */ }
}

function selectPeer(peer: P2pPeer) {
  selectedPeerId.value = peer.peerId
  chatMode.value = 'chat'
  selectedAgentId.value = null
  loadHistory(peer.peerId)
  loadPeerAuth(peer.peerId)
}
function selectPeerById(peerId: string) {
  if (peers.value.find(p => p.peerId === peerId)) {
    selectedPeerId.value = peerId
    chatMode.value = 'chat'
    selectedAgentId.value = null
    loadHistory(peerId)
    loadPeerAuth(peerId)
  }
}

async function loadPeerAuth(peerId: string) {
  try { peerAuthToMe.value = await getPeerAuthToMe(peerId) } catch (e) { peerAuthToMe.value = [] }
}

async function loadHistory(peerId: string) {
  chatHistory.value = []
  try {
    const msgs = await getHistory(peerId)
    for (const m of msgs) {
      chatHistory.value.push({
        from: m.direction === 'sent' ? 'me' : 'peer',
        name: m.name,
        content: m.content,
        time: formatTime(m.time),
        messageType: m.messageType || 'chat',
        agentConfigId: m.agentConfigId,
        agentName: m.agentName
      })
    }
    // ★ 刷新/切换页面后恢复loading状态：如果agent_invoke之后没有同Agent的response，说明AI还在执行
    for (let i = 0; i < chatHistory.value.length; i++) {
      const msg = chatHistory.value[i]
      if (msg.messageType === 'agent_invoke') {
        let hasResponse = false
        for (let j = i + 1; j < chatHistory.value.length; j++) {
          const later = chatHistory.value[j]
          if (later.messageType === 'agent_response' && later.agentName === msg.agentName) {
            hasResponse = true
            break
          }
        }
        if (!hasResponse) {
          (msg as any).loading = true
        }
      }
    }
    scrollChat()
  } catch (e) { /* */ }
}

async function doClearMessages() {
  if (!selectedPeer.value) return
  await deleteMessages(selectedPeer.value.peerId)
  chatHistory.value = []
}

function editRemark(peer: P2pPeer) {
  const newRemark = prompt('修改备注', peer.remark || '')
  if (newRemark !== null) {
    setRemark(peer.peerId, newRemark).then(() => refreshPeers())
  }
}

function formatTime(time?: string) {
  if (!time) return new Date().toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit' })
  return new Date(time).toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit' })
}

async function pollChat() {
  if (!selectedPeer.value) return
  try {
    const msgs = await pollMessages(selectedPeer.value.peerId)
    if (msgs.length > 0) {
      for (const m of msgs) {
        const msgType = (m as any).messageType || 'chat'
        const agentName = (m as any).agentName
        // ★ 根据后端传来的 direction 判断消息角度 direction='sent'→我发出的
        const dir = (m as any).direction
        const from = dir === 'sent' ? 'me' : 'peer'

        // 被调用方收到 agent_invoke 时显示loading
        if (msgType === 'agent_invoke' && from === 'peer') {
          chatHistory.value.push({
            from, name: m.name || '系统',
            content: m.content, time: formatTime(),
            messageType: 'agent_invoke',
            agentConfigId: (m as any).agentConfigId, agentName,
            loading: true
          })
        } else {
          // 收到 agent_response → 关掉最近一条仍在loading的invoke（不限方向，调用方/被调用方都适用）
          if (msgType === 'agent_response') {
            for (let i = chatHistory.value.length - 1; i >= 0; i--) {
              if (chatHistory.value[i].messageType === 'agent_invoke' && (chatHistory.value[i] as any).loading) {
                (chatHistory.value[i] as any).loading = false
                break
              }
            }
          }
          chatHistory.value.push({
            from, name: m.name || '未知',
            content: m.content, time: formatTime(),
            messageType: msgType,
            agentConfigId: (m as any).agentConfigId, agentName
          })
        }
      }
      scrollChat()
    }
  } catch (e) { /* */ }
}

function scrollChat() {
  nextTick(() => {
    if (chatMsgs.value) chatMsgs.value.scrollTop = chatMsgs.value.scrollHeight
  })
}

function copyAddress() { if (addressInfo.value.bestAddress) navigator.clipboard.writeText(addressInfo.value.bestAddress) }
function copyConnectionString() { if (connectionString.value) navigator.clipboard.writeText(connectionString.value) }

// ===== Agent 授权管理 =====

async function openAuthModal() {
  if (!selectedPeer.value) return
  authModalVisible.value = true
  try { myAgents.value = (await listAgentConfigs()).data || [] } catch (e) { myAgents.value = [] }
  try { myAuthToPeer.value = await getMyAuthToPeer(selectedPeer.value.peerId) } catch (e) { myAuthToPeer.value = [] }
  try { peerAuthToMe.value = await getPeerAuthToMe(selectedPeer.value.peerId) } catch (e) { peerAuthToMe.value = [] }
}

function isAgentAuthorized(agentId: number): boolean {
  return myAuthToPeer.value.some(a => a.id === agentId)
}

async function toggleAgentAuth(agentId: number) {
  if (!selectedPeer.value) return
  if (isAgentAuthorized(agentId)) {
    await cancelAgentAuth(selectedPeer.value.peerId, [agentId])
    myAuthToPeer.value = myAuthToPeer.value.filter(a => a.id !== agentId)
  } else {
    await grantAgentAuth(selectedPeer.value.peerId, [agentId])
    const agent = myAgents.value.find(a => a.id === agentId)
    if (agent) myAuthToPeer.value.push({ id: agent.id!, name: agent.name, avatar: agent.avatar })
  }
}

// ===== 统一发送 =====

async function doSend() {
  if (!chatInput.value || !selectedPeer.value) return
  if (chatMode.value === 'agent') {
    if (!selectedAgentId.value) return
    await doSendAgentInvoke()
  } else {
    await doSendChat()
  }
}

async function doSendChat() {
  if (!chatInput.value || !selectedPeer.value) return
  const content = chatInput.value; chatInput.value = ''
  const senderName = myName.value || '未知'
  chatHistory.value.push({ from: 'me', name: senderName, content, time: formatTime(), messageType: 'chat' })
  scrollChat()
  try { await sendMessage(selectedPeer.value.peerId, content) } catch (e) { /* */ }
}

async function doSendAgentInvoke() {
  if (!chatInput.value || !selectedPeer.value || !selectedAgentId.value) return
  const message = chatInput.value; chatInput.value = ''
  const agentName = peerAuthToMe.value.find(a => a.id === selectedAgentId.value)?.name || 'Agent'
  chatHistory.value.push({
    from: 'me', name: myName.value || '未知',
    content: `调用 Agent「${agentName}」: ${message}`,
    time: formatTime(), messageType: 'agent_invoke',
    agentConfigId: selectedAgentId.value, agentName,
    loading: true  // 加载中标记
  })
  scrollChat()
  try { await invokeAgent(selectedPeer.value.peerId, selectedAgentId.value, message) } catch (e) { /* */ }
}

// ===== 辅助函数 =====

function extractAgentMessage(content: string): string {
  const idx = content.indexOf(': ')
  return idx >= 0 ? content.substring(idx + 2) : content
}

function truncateContent(content: string, maxLen: number): string {
  if (!content || content.length <= maxLen) return content
  return content.substring(0, maxLen) + '...'
}

// ==================== 生命周期 ====================
onMounted(() => {
  loadAll()
  pollTimer = setInterval(async () => {
    await refreshPeers()
    await pollChat()
    if (selectedPeer.value) {
      try { peerAuthToMe.value = await getPeerAuthToMe(selectedPeer.value.peerId) } catch (e) { /* */ }
    }
  }, 3000)
})

onUnmounted(() => {
  if (pollTimer) clearInterval(pollTimer)
})
</script>

<style>
/* ============================================================
   P2P Panel v3 — 双模式聊天 · Bento卡片 · 暗色增强 · 微交互
   ============================================================ */

/* ---------- CSS 变量 ---------- */
.p2p-app {
  --accent: #8b5cf6;
  --accent-lt: rgba(139,92,246,0.08);
  --accent-md: rgba(139,92,246,0.18);
  --accent-dk: #7c3aed;
  --accent-glow: rgba(139,92,246,0.3);

  /* Agent 色系（琥珀/橙） */
  --agent: #f59e0b;
  --agent-lt: rgba(245,158,11,0.08);
  --agent-md: rgba(245,158,11,0.2);
  --agent-dk: #d97706;
  --agent-glow: rgba(245,158,11,0.3);

  --bg-root: #f5f3fa;
  --bg-card: #ffffff;
  --bg-chat: #faf9fc;
  --text-1: #1a1a2e;
  --text-2: #5c5c78;
  --text-3: #9696aa;
  --text-4: #b8b8c8;
  --border: #e8e5f0;
  --shadow-sm: 0 1px 3px rgba(0,0,0,0.04);
  --shadow-md: 0 4px 12px rgba(0,0,0,0.06);
  --shadow-lg: 0 8px 30px rgba(0,0,0,0.08);
  --green: #10b981;
  --red: #ef4444;
  --radius: 14px;
  --radius-sm: 10px;
  --radius-xs: 6px;

  height: 100%;
  display: flex;
  flex-direction: column;
  background: var(--bg-root);
  font-size: 14px;
  color: var(--text-1);
  font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', 'PingFang SC', 'Microsoft YaHei', sans-serif;
}

/* ============ 顶部栏 ============ */
.top-bar {
  display: flex;
  align-items: center;
  gap: 16px;
  padding: 10px 20px;
  background: var(--bg-card);
  border-bottom: 1px solid var(--border);
  box-shadow: var(--shadow-sm);
  z-index: 10;
  flex-shrink: 0;
  backdrop-filter: blur(10px);
}
.top-left {
  display: flex; align-items: center; gap: 8px; flex-shrink: 0;
}
.logo-icon {
  width: 32px; height: 32px; border-radius: 10px;
  background: linear-gradient(135deg, var(--accent), var(--accent-dk));
  display: flex; align-items: center; justify-content: center;
  color: #fff;
  box-shadow: 0 2px 8px var(--accent-glow);
}
.logo-icon svg { width: 18px; height: 18px; }
.top-title { font-weight: 700; font-size: 15px; letter-spacing: -0.2px; }
.live-dot {
  width: 8px; height: 8px; border-radius: 50%; flex-shrink: 0;
  transition: all 0.3s ease;
}
.live-dot.on {
  background: var(--green);
  box-shadow: 0 0 0 4px rgba(16,185,129,0.2);
  animation: dotPulse 2.5s infinite;
}
.live-dot.off { background: #c0c0cc; }
@keyframes dotPulse {
  0%,100% { box-shadow: 0 0 0 3px rgba(16,185,129,0.2); }
  50% { box-shadow: 0 0 0 8px rgba(16,185,129,0); }
}
.top-status { font-size: 13px; font-weight: 500; color: var(--text-2); }
.top-mid {
  flex: 1; display: flex; align-items: center; gap: 8px;
  font-size: 12px; color: var(--text-3); min-width: 0; overflow: hidden;
}
.top-meta code {
  font-size: 11px; font-family: 'SF Mono','Consolas','Fira Code',monospace;
  color: var(--text-1); background: #f0edf6; padding: 2px 7px;
  border-radius: 4px; font-weight: 500;
}
.top-meta code.accent { color: var(--accent); font-weight: 700; }
.top-sep { color: #d0ccdc; }
.top-right { flex-shrink: 0; }
.name-input-top { width: 160px; }

/* ============ 主布局 ============ */
.main-layout {
  flex: 1; display: flex; overflow: hidden; min-height: 0;
}

/* ============ 侧边栏 ============ */
.side-panel {
  width: 280px; flex-shrink: 0; display: flex; flex-direction: column;
  border-right: 1px solid var(--border);
  background: var(--bg-card); overflow-y: auto;
}
.side-panel::-webkit-scrollbar { width: 4px; }
.side-panel::-webkit-scrollbar-thumb { background: #dcd8ea; border-radius: 2px; }

.side-section { padding: 14px 14px; }
.section-head {
  display: flex; align-items: center; gap: 8px;
  font-size: 13px; font-weight: 700; color: var(--text-1);
  margin-bottom: 10px; user-select: none;
}
.section-head-icon { font-size: 14px; }
.section-badge {
  font-size: 11px; background: var(--accent); color: #fff;
  padding: 2px 9px; border-radius: 12px; font-weight: 700;
  min-width: 20px; text-align: center; line-height: 1.5;
}
.section-arrow {
  margin-left: auto; font-size: 12px; color: var(--text-3);
  transition: transform 0.3s ease;
}
.section-arrow.open { transform: rotate(180deg); }
.btn-icon-only {
  margin-left: auto; background: none; border: none; cursor: pointer;
  font-size: 14px; opacity: 0.5; padding: 0 4px; transition: opacity 0.2s;
}
.btn-icon-only:hover { opacity: 1; }

.side-empty {
  display: flex; flex-direction: column; align-items: center;
  gap: 6px; padding: 24px 12px; color: var(--text-3); font-size: 13px;
  text-align: center;
}
.side-empty-icon { font-size: 32px; opacity: 0.5; margin-bottom: 4px; }
.side-empty-hint { font-size: 11px; color: var(--text-4); }
.side-divider { height: 1px; background: var(--border); margin: 0 14px; }

/* ----- 节点卡片 ----- */
.peer-list {
  display: flex; flex-direction: column; gap: 6px;
  max-height: 320px; overflow-y: auto;
}
.peer-list::-webkit-scrollbar { width: 3px; }
.peer-list::-webkit-scrollbar-thumb { background: #e0dcec; border-radius: 2px; }

.peer-card {
  display: flex; align-items: center; gap: 10px;
  padding: 10px 12px; border-radius: var(--radius-sm);
  cursor: pointer; transition: all 0.2s ease;
  border: 1px solid transparent;
  position: relative;
  margin-right: 2px; /* 预留hover指示条空间，避免布局抖动 */
}
.peer-card::before {
  content: '';
  position: absolute;
  left: 0; top: 8px; bottom: 8px;
  width: 3px; border-radius: 0 3px 3px 0;
  background: transparent;
  transition: background 0.2s ease;
}
.peer-card:hover {
  background: var(--accent-lt);
  border-color: var(--accent-md);
  box-shadow: var(--shadow-sm);
}
.peer-card:hover::before {
  background: var(--accent);
}
.peer-card.active {
  background: linear-gradient(135deg, rgba(139,92,246,0.1), rgba(99,102,241,0.06));
  border-color: var(--accent-md);
  box-shadow: var(--shadow-md);
}
.peer-card.active::before {
  background: var(--accent);
}
.peer-avatar {
  width: 36px; height: 36px; border-radius: 10px;
  display: flex; align-items: center; justify-content: center;
  font-weight: 700; font-size: 14px; flex-shrink: 0;
  color: #fff; transition: all 0.25s ease;
}
.peer-avatar.active {
  box-shadow: 0 4px 12px var(--accent-glow);
  transform: scale(1.05);
}
.peer-info { flex: 1; min-width: 0; }
.peer-name {
  font-weight: 600; font-size: 13px;
  overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
}
.remark-edit { cursor:pointer; opacity:0.4; margin-left:4px; font-size:11px; transition: opacity 0.2s; }
.remark-edit:hover { opacity:1; }
.peer-addr {
  font-size: 10px; color: var(--text-3);
  font-family: 'SF Mono','Consolas','Fira Code',monospace;
  overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
  margin-top: 2px;
}
.mini-dot {
  width: 7px; height: 7px; border-radius: 50%; flex-shrink: 0;
  transition: all 0.3s ease;
}
.mini-dot.on { background: var(--green); box-shadow: 0 0 0 2px rgba(16,185,129,0.2); }
.mini-dot.off { background: #d4d4dc; }
.btn-disconnect-sm {
  background: none; border: none; color: #c0c0cc; font-size: 18px;
  cursor: pointer; padding: 0 4px; line-height: 1;
  opacity: 0; transition: all 0.2s ease;
  border-radius: 4px;
}
.peer-card:hover .btn-disconnect-sm { opacity: 0.7; }
.peer-card:hover .btn-disconnect-sm:hover { opacity: 1; color: var(--red); background: rgba(239,68,68,0.08); }

/* ----- 工具区 ----- */
.tools-body { display: flex; flex-direction: column; gap: 8px; padding-top: 2px; }
.tool-card {
  background: var(--bg-chat); border-radius: var(--radius-xs);
  padding: 10px 12px; display: flex; flex-direction: column; gap: 6px;
  border: 1px solid transparent; transition: border-color 0.2s;
}
.tool-card:hover { border-color: var(--border); }
.tool-label { font-size: 12px; font-weight: 700; color: var(--text-2); }
.tool-row { display: flex; gap: 6px; align-items: center; }
.qr-mini { margin-top: 4px; }
.qr-mini img {
  width: 100px; height: 100px; border-radius: 10px;
  border: 1px solid var(--border); box-shadow: var(--shadow-sm);
}
.addr-mini {
  flex: 1; font-size: 10px; font-family: 'SF Mono','Consolas','Fira Code',monospace;
  word-break: break-all; color: var(--text-1);
  background: #f5f3fa; padding: 5px 8px; border-radius: 4px;
  overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
}
.mini-msg { font-size: 11px; padding: 4px 10px; border-radius: 6px; font-weight: 500; }
.mini-msg.err { color: #dc2626; background: rgba(239,68,68,0.07); }
.mini-msg.ok { color: #059669; background: rgba(16,185,129,0.07); }
.mini-msg.warn { color: #d97706; }

/* ============ 聊天主体 ============ */
.chat-main {
  flex: 1; display: flex; flex-direction: column;
  min-width: 0; background: var(--bg-chat);
}

/* ----- Hero 空状态（未选中节点） ----- */
.chat-hero {
  flex: 1; display: flex; flex-direction: column;
  align-items: center; justify-content: center;
  gap: 12px; user-select: none; padding: 40px;
}
.hero-illustration {
  position: relative; width: 120px; height: 120px;
  display: flex; align-items: center; justify-content: center;
  margin-bottom: 12px;
}
.hero-orb {
  position: absolute; border-radius: 50%; opacity: 0.2;
  animation: orbFloat 3s ease-in-out infinite;
}
.hero-orb.orb-1 {
  width: 100px; height: 100px;
  background: radial-gradient(circle, var(--accent), transparent);
  animation-delay: 0s;
}
.hero-orb.orb-2 {
  width: 70px; height: 70px;
  background: radial-gradient(circle, #a78bfa, transparent);
  animation-delay: 0.6s;
  top: 10px; right: 5px;
}
.hero-orb.orb-3 {
  width: 50px; height: 50px;
  background: radial-gradient(circle, #c4b5fd, transparent);
  animation-delay: 1.2s;
  bottom: 5px; left: 0;
}
@keyframes orbFloat {
  0%,100% { transform: translateY(0) scale(1); }
  50% { transform: translateY(-8px) scale(1.08); }
}
.hero-icon {
  font-size: 56px; z-index: 1;
  filter: drop-shadow(0 4px 8px rgba(0,0,0,0.1));
  animation: heroBounce 2s ease-in-out infinite;
}
@keyframes heroBounce {
  0%,100% { transform: translateY(0); }
  30% { transform: translateY(-6px); }
  50% { transform: translateY(0); }
}
.hero-title { font-size: 24px; font-weight: 800; color: var(--text-1); margin: 0; letter-spacing: -0.3px; }
.hero-desc { font-size: 14px; margin: 0; color: var(--text-3); }
.hero-features {
  display: flex; flex-wrap: wrap; gap: 10px; margin-top: 16px; justify-content: center;
}
.hero-feat-item {
  display: flex; align-items: center; gap: 6px;
  font-size: 12px; font-weight: 600;
  background: var(--bg-card); padding: 8px 16px;
  border-radius: 20px; color: var(--text-2);
  box-shadow: var(--shadow-sm); border: 1px solid var(--border);
  transition: all 0.2s ease;
}
.hero-feat-item:hover {
  transform: translateY(-2px);
  box-shadow: var(--shadow-md);
  border-color: var(--accent-md);
}
.hero-feat-icon { font-size: 15px; }

/* ----- 聊天头部 ----- */
.chat-header {
  display: flex; align-items: center; gap: 10px;
  padding: 10px 18px; background: var(--bg-card);
  border-bottom: 1px solid var(--border); flex-shrink: 0;
}
.chat-header-avatar {
  width: 38px; height: 38px; border-radius: 12px;
  color: #fff; display: flex; align-items: center; justify-content: center;
  font-weight: 700; font-size: 15px; flex-shrink: 0;
  box-shadow: 0 3px 10px var(--accent-glow);
}
.chat-header-info { flex: 0 1 auto; min-width: 0; }
.chat-header-name { font-weight: 700; font-size: 14px; }
.chat-header-addr {
  font-size: 11px; color: var(--text-3);
  font-family: 'SF Mono','Consolas','Fira Code',monospace;
  overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
}
.chat-header-dot {
  width: 8px; height: 8px; border-radius: 50%; flex-shrink: 0;
  transition: all 0.3s ease;
}
.chat-header-dot.on { background: var(--green); box-shadow: 0 0 0 3px rgba(16,185,129,0.2); }
.chat-header-dot.off { background: #d4d4dc; }

/* 模式指示器 */
.chat-mode-indicator { flex-shrink: 0; }
.mode-badge {
  font-size: 11px; font-weight: 700; padding: 4px 12px;
  border-radius: 20px; white-space: nowrap;
  transition: all 0.3s ease;
}
.mode-badge.mode-chat {
  background: var(--accent-lt); color: var(--accent);
  border: 1px solid var(--accent-md);
}
.mode-badge.mode-agent {
  background: var(--agent-lt); color: var(--agent-dk);
  border: 1px solid var(--agent-md);
  animation: agentPulse 2s infinite;
}
@keyframes agentPulse {
  0%,100% { box-shadow: 0 0 0 0 rgba(245,158,11,0.2); }
  50% { box-shadow: 0 0 0 6px rgba(245,158,11,0); }
}

.btn-header-action {
  border-radius: var(--radius-xs); font-size: 12px; font-weight: 600;
}

/* ----- 消息列表 ----- */
.chat-messages {
  flex: 1; overflow-y: auto; padding: 18px 20px;
  display: flex; flex-direction: column; gap: 12px;
  scroll-behavior: smooth;
}
.chat-messages::-webkit-scrollbar { width: 4px; }
.chat-messages::-webkit-scrollbar-thumb { background: #dcd8ea; border-radius: 2px; }

.msg-row {
  display: flex; gap: 8px; align-items: flex-end;
  animation: msgSlideIn 0.3s cubic-bezier(0.16, 1, 0.3, 1);
}
@keyframes msgSlideIn {
  from { opacity: 0; transform: translateY(10px) scale(0.97); }
  to { opacity: 1; transform: translateY(0) scale(1); }
}
.msg-row.me { justify-content: flex-end; }
.msg-row.peer { justify-content: flex-start; }
.msg-row.msg-system { justify-content: center; }

.msg-avatar-sm {
  width: 30px; height: 30px; border-radius: 10px;
  background: #ece6f8; color: #7c3aed;
  display: flex; align-items: center; justify-content: center;
  font-weight: 700; font-size: 11px; flex-shrink: 0;
  transition: all 0.2s ease;
}
.msg-avatar-sm.me {
  background: linear-gradient(135deg, var(--accent), var(--accent-dk));
  color: #fff; box-shadow: 0 2px 8px var(--accent-glow);
}
.msg-avatar-sm.avatar-agent {
  background: linear-gradient(135deg, var(--agent), var(--agent-dk));
  color: #fff; box-shadow: 0 2px 8px var(--agent-glow);
}
.msg-avatar-sm.avatar-system {
  background: #e8e5f0; color: var(--text-2);
}
.msg-avatar-sm.avatar-agent-invoke {
  background: linear-gradient(135deg, var(--agent), var(--agent-dk));
  color: #fff; box-shadow: 0 2px 8px var(--agent-glow);
}

.msg-body { max-width: 68%; }
.msg-row.msg-system .msg-body { max-width: 85%; }

.msg-sender {
  font-size: 10px; color: var(--text-3); margin-bottom: 3px;
  padding: 0 8px; font-weight: 500;
}
.msg-row.me .msg-sender { text-align: right; }
.sender-agent-invoke { color: var(--agent-dk); font-weight: 600; }
.sender-agent-resp { color: var(--agent-dk); font-weight: 600; }
.msg-time { font-size: 9px; color: var(--text-4); margin-left: 4px; font-weight: 400; }

/* ----- 消息气泡（核心样式） ----- */
.msg-bubble {
  padding: 10px 16px; border-radius: 18px;
  font-size: 13.5px; line-height: 1.6; word-break: break-word;
  transition: all 0.2s ease;
  position: relative;
}

/* 人-人聊天：我的消息（紫色） */
.bubble-me {
  background: linear-gradient(135deg, var(--accent), var(--accent-dk));
  color: #fff; border-bottom-right-radius: 6px;
  box-shadow: 0 3px 12px var(--accent-glow);
}

/* 人-人聊天：对方消息（白色卡片） */
.bubble-peer {
  background: #fff; color: var(--text-1);
  border: 1px solid #e8e5f0; border-bottom-left-radius: 6px;
  box-shadow: 0 1px 3px rgba(0,0,0,0.04);
}

/* Agent 调用消息（我发出的） */
.bubble-agent-invoke {
  background: linear-gradient(135deg, var(--agent), var(--agent-dk));
  color: #fff; border-bottom-right-radius: 6px;
  box-shadow: 0 3px 12px var(--agent-glow);
}

/* Agent 响应消息（对方返回的） */
.bubble-agent-response {
  background: linear-gradient(135deg, rgba(245,158,11,0.06), rgba(249,115,22,0.03));
  border: 1px solid rgba(245,158,11,0.25); border-bottom-left-radius: 6px;
  color: var(--text-1); box-shadow: 0 1px 8px rgba(245,158,11,0.06);
}

/* 系统消息 */
.bubble-system {
  background: rgba(139,92,246,0.05); border: 1px dashed var(--accent-md);
  border-radius: 12px; font-size: 12px; color: var(--text-2);
  text-align: center; box-shadow: none; padding: 8px 18px;
}

/* Agent 调用消息内部 */
.agent-call-header {
  display: flex; align-items: center; gap: 6px;
  font-size: 11px; font-weight: 600; color: rgba(255,255,255,0.8);
  margin-bottom: 6px; padding-bottom: 6px;
  border-bottom: 1px solid rgba(255,255,255,0.2);
}
.agent-call-icon { font-size: 14px; }
.agent-call-body { font-size: 13.5px; color: #fff; }

/* Agent调用消息内的loading动画 */
.agent-loading-inline {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-top: 8px;
  padding-top: 8px;
  border-top: 1px solid rgba(255,255,255,0.2);
}
.agent-loading-inline .agent-loading-dots {
  display: flex;
  align-items: center;
  gap: 3px;
}
.agent-loading-inline .agent-loading-dots span {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: rgba(255,255,255,0.7);
  animation: dotBounce 1.4s ease-in-out infinite;
}
.agent-loading-inline .agent-loading-dots span:nth-child(2) { animation-delay: 0.2s; }
.agent-loading-inline .agent-loading-dots span:nth-child(3) { animation-delay: 0.4s; }
.agent-loading-inline .agent-loading-text {
  font-size: 11px;
  color: rgba(255,255,255,0.7);
  font-weight: 500;
}
@keyframes dotBounce {
  0%, 80%, 100% { transform: scale(0.6); opacity: 0.4; }
  40% { transform: scale(1); opacity: 1; }
}

/* Agent 响应消息内部 */
.agent-resp-header {
  display: flex; align-items: center; gap: 6px;
  font-size: 11px; font-weight: 600; color: var(--agent-dk);
  margin-bottom: 6px; padding-bottom: 6px;
  border-bottom: 1px solid rgba(245,158,11,0.15);
}
.agent-resp-icon { font-size: 14px; }
.agent-resp-body {
  font-size: 13.5px; line-height: 1.6; white-space: pre-wrap;
  word-break: break-word; color: var(--text-1);
}

.agent-detail-link {
  display: inline-block; margin-top: 8px;
  font-size: 12px; color: var(--accent); text-decoration: none;
  font-weight: 600; transition: color 0.2s;
}
.agent-detail-link:hover { color: var(--accent-dk); text-decoration: underline; }

/* ============ 聊天底部（双模式） ============ */
.chat-footer {
  display: flex; flex-direction: column; gap: 10px;
  padding: 12px 18px; border-top: 1px solid var(--border);
  background: var(--bg-card); flex-shrink: 0;
  backdrop-filter: blur(10px);
}

/* 模式切换 Tab */
.mode-tabs {
  display: flex; gap: 6px;
}
.mode-tab {
  display: flex; align-items: center; gap: 6px;
  padding: 8px 18px; border-radius: 24px;
  border: 2px solid transparent;
  background: var(--bg-chat);
  cursor: pointer; font-size: 13px; font-weight: 600;
  color: var(--text-2); transition: all 0.25s ease;
  white-space: nowrap;
  position: relative;
}
.mode-tab:hover:not(:disabled) {
  background: var(--accent-lt); color: var(--accent);
  border-color: var(--accent-md);
}
.mode-tab.active {
  background: linear-gradient(135deg, var(--accent), var(--accent-dk));
  color: #fff; border-color: transparent;
  box-shadow: 0 4px 14px var(--accent-glow);
}
.mode-tab:disabled {
  opacity: 0.4; cursor: not-allowed;
}
.mode-tab-icon { font-size: 16px; }
.mode-tab-label { font-size: 13px; }
.mode-tab-count {
  font-size: 10px; background: rgba(255,255,255,0.25);
  color: inherit; padding: 1px 7px; border-radius: 10px;
  font-weight: 700;
}
.mode-tab.active .mode-tab-count {
  background: rgba(255,255,255,0.3);
}

/* Agent 选择行 */
.agent-select-row {
  display: flex; align-items: center; gap: 8px;
  padding: 6px 0;
}
.agent-select-label {
  font-size: 12px; font-weight: 600; color: var(--text-2);
  flex-shrink: 0;
}
.agent-chips {
  display: flex; gap: 6px; flex-wrap: wrap;
}
.agent-chip {
  display: flex; align-items: center; gap: 4px;
  padding: 5px 12px; border-radius: 20px;
  border: 1.5px solid var(--border);
  background: var(--bg-card); cursor: pointer;
  font-size: 12px; font-weight: 500; color: var(--text-2);
  transition: all 0.2s ease;
}
.agent-chip:hover {
  border-color: var(--agent-md); background: var(--agent-lt);
  color: var(--agent-dk);
}
.agent-chip.active {
  border-color: var(--agent); background: linear-gradient(135deg, var(--agent), var(--agent-dk));
  color: #fff; box-shadow: 0 2px 10px var(--agent-glow);
  font-weight: 700;
}
.agent-chip-avatar { font-size: 15px; }
.agent-empty-hint {
  font-size: 12px; color: var(--text-3); font-style: italic;
}

/* 输入行 */
.input-row {
  display: flex; gap: 8px; align-items: center;
}
.chat-input { flex: 1; }
.btn-send {
  border-radius: var(--radius-sm); font-weight: 700;
  padding: 0 22px; white-space: nowrap; transition: all 0.2s ease;
}
.btn-send:not(:disabled):hover {
  transform: translateY(-1px);
  box-shadow: 0 4px 12px var(--accent-glow);
}

/* ============ Agent 授权弹窗 ============ */
.auth-modal-overlay {
  position: fixed; inset: 0; z-index: 9999;
  background: rgba(0,0,0,0.72);
  display: flex; align-items: center; justify-content: center;
}
.auth-modal {
  /* === 自持 CSS 变量（Teleport 脱离 .p2p-app 作用域，必须在此重新定义）=== */
  --am-bg: #ffffff;
  --am-text-1: #1a1a2e;
  --am-text-2: #5c5c78;
  --am-text-3: #9696aa;
  --am-border: #e8e5f0;
  --am-accent-lt: rgba(139,92,246,0.08);
  --am-accent-md: rgba(139,92,246,0.18);
  --am-green: #10b981;

  background: var(--am-bg); border-radius: 18px;
  width: 540px; max-height: 75vh;
  display: flex; flex-direction: column;
  border: 2px solid var(--am-border);
  box-shadow: 0 20px 60px rgba(0,0,0,0.3);
  overflow: hidden;
  color: var(--am-text-1);
}
.auth-modal-header {
  display: flex; align-items: center; gap: 12px;
  padding: 18px 24px; border-bottom: 1px solid var(--am-border);
}
.auth-modal-header h3 { margin: 0; font-size: 17px; flex: 1; font-weight: 700; color: var(--am-text-1); }
.auth-modal-peer { font-size: 12px; color: var(--am-text-3); }
.auth-modal-close {
  background: none; border: none; font-size: 20px;
  cursor: pointer; color: var(--am-text-3); padding: 4px 10px;
  border-radius: 8px; transition: all 0.2s;
}
.auth-modal-close:hover { color: var(--am-text-1); background: #f0edf6; }
.auth-modal-body { flex: 1; overflow-y: auto; padding: 18px 24px; }
.auth-section { margin-bottom: 16px; }
.auth-section-title {
  font-size: 13px; font-weight: 700; color: var(--am-text-1);
  margin-bottom: 10px;
}
.auth-empty {
  font-size: 13px; color: var(--am-text-3); text-align: center;
  padding: 20px 0;
}
.auth-agent-item {
  display: flex; align-items: center; gap: 12px;
  padding: 12px 14px; border-radius: 12px;
  background: var(--am-accent-lt); margin-bottom: 8px;
  transition: all 0.2s ease;
}
.auth-agent-item:hover { background: var(--am-accent-md); }
.auth-agent-item.received { background: rgba(16,185,129,0.05); }
.auth-agent-item.received:hover { background: rgba(16,185,129,0.1); }
.auth-agent-avatar { font-size: 20px; flex-shrink: 0; }
.auth-agent-info { flex: 1; min-width: 0; }
.auth-agent-name { font-weight: 700; font-size: 13px; display: block; color: var(--am-text-1); }
.auth-agent-desc {
  font-size: 11px; color: var(--am-text-3);
  overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
  display: block; margin-top: 2px;
}
.auth-badge {
  font-size: 10px; background: var(--am-green); color: #fff;
  padding: 3px 10px; border-radius: 10px; flex-shrink: 0; font-weight: 600;
}
.auth-divider { height: 1px; background: var(--am-border); margin: 16px 0; }

/* 弹窗过渡 */
.modal-fade-enter-active,
.modal-fade-leave-active {
  transition: opacity 0.25s ease;
}
.modal-fade-enter-active .auth-modal,
.modal-fade-leave-active .auth-modal {
  transition: transform 0.25s cubic-bezier(0.16, 1, 0.3, 1);
}
.modal-fade-enter-from,
.modal-fade-leave-to { opacity: 0; }
.modal-fade-enter-from .auth-modal { transform: scale(0.95) translateY(10px); }
.modal-fade-leave-to .auth-modal { transform: scale(0.95) translateY(10px); }

/* ============ 暗色模式 ============ */
@media (prefers-color-scheme: dark) {
  .p2p-app {
    --bg-root: #121117;
    --bg-card: #1a1925;
    --bg-chat: #15141d;
    --text-1: #e4e2f0;
    --text-2: #a09eb8;
    --text-3: #6a6880;
    --text-4: #525070;
    --border: #2a2838;
    --shadow-sm: 0 1px 3px rgba(0,0,0,0.3);
    --shadow-md: 0 4px 12px rgba(0,0,0,0.4);
    --shadow-lg: 0 8px 30px rgba(0,0,0,0.5);
  }
  .top-meta code { background: #2a2838; }
  .addr-mini { background: #1e1d2c; }
  .msg-bubble.bubble-peer { background: #1e1d2c; border-color: #2a2838; }
  .chat-messages::-webkit-scrollbar-thumb { background: #3a3850; }
  .side-panel::-webkit-scrollbar-thumb { background: #3a3850; }
  .peer-list::-webkit-scrollbar-thumb { background: #3a3850; }
  .mini-msg.err { background: rgba(239,68,68,0.12); }
  .mini-msg.ok { background: rgba(16,185,129,0.12); }
  .msg-bubble.bubble-system { background: rgba(139,92,246,0.06); }
  .tool-card { background: #1a1925; }
  .hero-feat-item { background: #1a1925; border-color: #2a2838; }
  .mode-tab { background: #1a1925; }
  .agent-chip { background: #1a1925; }
  .agent-chip:hover { background: rgba(245,158,11,0.08); }
  .auth-modal-overlay { background: rgba(0,0,0,0.82); }
  .auth-modal {
    --am-bg: #1e1d2c;
    --am-text-1: #e4e2f0;
    --am-text-2: #a09eb8;
    --am-text-3: #7a7898;
    --am-border: #363448;
    --am-accent-lt: rgba(139,92,246,0.1);
    --am-accent-md: rgba(139,92,246,0.2);
    --am-green: #10b981;
    border-color: rgba(255,255,255,0.1);
    box-shadow: 0 20px 60px rgba(0,0,0,0.6), 0 0 0 4px rgba(255,255,255,0.04);
  }
  .auth-modal-close:hover { background: #2a2838; color: var(--am-text-1); }
  .msg-avatar-sm.avatar-system { background: #2a2838; }
  .msg-bubble.bubble-agent-response {
    background: linear-gradient(135deg, rgba(245,158,11,0.06), rgba(249,115,22,0.03));
    border-color: rgba(245,158,11,0.2);
  }
}

/* ============ 响应式 ============ */
@media (max-width: 768px) {
  .side-panel { width: 240px; }
  .auth-modal { width: 92vw; max-height: 85vh; }
  .mode-tab { padding: 6px 14px; }
  .mode-tab-label { font-size: 12px; }
  .msg-body { max-width: 78%; }
  .chat-header { flex-wrap: wrap; gap: 6px; }
}

/* ================================================================
   暗色模式（data-theme 属性驱动）
   ================================================================ */

/* CSS 变量覆盖 */
[data-theme="dark"] .p2p-app {
  --bg-root: #121117;
  --bg-card: #1a1925;
  --bg-chat: #15141d;
  --text-1: #e4e2f0;
  --text-2: #a09eb8;
  --text-3: #6a6880;
  --text-4: #525070;
  --border: #2a2838;
  --shadow-sm: 0 1px 3px rgba(0,0,0,0.3);
  --shadow-md: 0 4px 12px rgba(0,0,0,0.4);
  --shadow-lg: 0 8px 30px rgba(0,0,0,0.5);
}

[data-theme="dark"] .top-meta code { background: #2a2838; }
[data-theme="dark"] .addr-mini { background: #1e1d2c; }
[data-theme="dark"] .msg-bubble.bubble-peer { background: #1e1d2c; border-color: #2a2838; }
[data-theme="dark"] .chat-messages::-webkit-scrollbar-thumb { background: #3a3850; }
[data-theme="dark"] .side-panel::-webkit-scrollbar-thumb { background: #3a3850; }
[data-theme="dark"] .peer-list::-webkit-scrollbar-thumb { background: #3a3850; }
[data-theme="dark"] .mini-msg.err { background: rgba(239,68,68,0.12); }
[data-theme="dark"] .mini-msg.ok { background: rgba(16,185,129,0.12); }
[data-theme="dark"] .msg-bubble.bubble-system { background: rgba(139,92,246,0.06); }
[data-theme="dark"] .tool-card { background: #1a1925; }
[data-theme="dark"] .hero-feat-item { background: #1a1925; border-color: #2a2838; }
[data-theme="dark"] .mode-tab { background: #1a1925; }
[data-theme="dark"] .agent-chip { background: #1a1925; }
[data-theme="dark"] .agent-chip:hover { background: rgba(245,158,11,0.08); }
[data-theme="dark"] .auth-modal-overlay { background: rgba(0,0,0,0.82); }
[data-theme="dark"] .auth-modal {
  --am-bg: #1e1d2c;
  --am-text-1: #e4e2f0;
  --am-text-2: #a09eb8;
  --am-text-3: #7a7898;
  --am-border: #363448;
  --am-accent-lt: rgba(139,92,246,0.1);
  --am-accent-md: rgba(139,92,246,0.2);
  --am-green: #10b981;
  border-color: rgba(255,255,255,0.1);
  box-shadow: 0 20px 60px rgba(0,0,0,0.6), 0 0 0 4px rgba(255,255,255,0.04);
}
[data-theme="dark"] .auth-modal-close:hover { background: #2a2838; color: var(--am-text-1); }
[data-theme="dark"] .msg-avatar-sm.avatar-system { background: #2a2838; }
[data-theme="dark"] .msg-bubble.bubble-agent-response {
  background: linear-gradient(135deg, rgba(245,158,11,0.06), rgba(249,115,22,0.03));
  border-color: rgba(245,158,11,0.2);
}

/* Ant Design 输入框暗色穿透 */
[data-theme="dark"] :deep(.ant-input) {
  background: #1e1d2c;
  border-color: #2a2838;
  color: #e4e2f0;
}
[data-theme="dark"] :deep(.ant-input:hover) {
  border-color: #3a3850;
}
[data-theme="dark"] :deep(.ant-input:focus) {
  border-color: #8b5cf6;
}
[data-theme="dark"] :deep(.ant-btn-default) {
  background: #1e1d2c;
  border-color: #2a2838;
  color: #e4e2f0;
}
[data-theme="dark"] :deep(.ant-btn-default:hover) {
  color: #8b5cf6;
  border-color: #8b5cf6;
}

/* ===== Markdown 内容排版（完整抄 AI助手 code-message 样式，类名改为 markdown-content） ===== */
/* ===== 代码块 ===== */
.markdown-content pre {
  background: var(--bg-root, #f5f3fa);
  border-radius: 8px;
  padding: 14px 18px;
  font-size: 13px;
  line-height: 1.55;
  margin: 8px 0;
  white-space: pre-wrap;
  word-break: break-word;
  overflow-x: hidden;
  border: 1px solid var(--border, #e8e5f0);
}
.markdown-content code {
  font-family: 'SF Mono', 'Monaco', 'Fira Code', monospace;
  font-size: 0.9em;
}
.markdown-content p code {
  background: #f0edf6;
  padding: 2px 6px;
  border-radius: 4px;
  color: var(--accent, #8b5cf6);
}

/* ===== highlight.js 主题（浅色） ===== */
.markdown-content pre code.hljs {
  background: transparent;
  color: var(--text-1, #1a1a2e);
}
.markdown-content .hljs-keyword { color: #cf222e; }
.markdown-content .hljs-string { color: #0a3069; }
.markdown-content .hljs-number { color: #0550ae; }
.markdown-content .hljs-comment { color: #6e7781; font-style: italic; }
.markdown-content .hljs-title { color: var(--accent, #8250df); }
.markdown-content .hljs-built_in { color: #0550ae; }
.markdown-content .hljs-type { color: #0550ae; }
.markdown-content .hljs-literal { color: #0550ae; }
.markdown-content .hljs-attr { color: #0550ae; }
.markdown-content .hljs-selector-class { color: #0550ae; }
.markdown-content .hljs-meta { color: var(--accent, #8250df); }
.markdown-content .hljs-tag { color: #116329; }
.markdown-content .hljs-name { color: #116329; }
.markdown-content .hljs-attribute { color: #0550ae; }

/* ===== Diff 高亮（浅色） ===== */
.markdown-content .hljs-addition {
  background: #e6ffed;
  color: #1a7f37;
  display: inline-block;
  width: 100%;
}
.markdown-content .hljs-deletion {
  background: #ffebe9;
  color: #cf222e;
  display: inline-block;
  width: 100%;
}
.markdown-content .hljs-section { color: var(--accent, #8b5cf6); font-weight: 700; }

/* ===== 列表排版 ===== */
.markdown-content ol,
.markdown-content ul {
  padding-left: 28px;
  margin: 6px 0;
  text-align: left;
}
.markdown-content li {
  margin-bottom: 4px;
  line-height: 1.75;
  text-align: left;
}
.markdown-content li p { margin: 0; display: inline; }
.markdown-content p {
  margin: 6px 0;
  line-height: 1.75;
  text-align: left;
}
.markdown-content h1, .markdown-content h2, .markdown-content h3, .markdown-content h4 {
  margin: 14px 0 6px;
  color: var(--text-1, #1a1a2e);
  text-align: left;
}
.markdown-content blockquote {
  margin: 8px 0;
  padding: 6px 14px;
  border-left: 3px solid var(--accent, #8b5cf6);
  color: var(--text-2, #5c5c78);
  background: var(--bg-root, #f5f3fa);
  border-radius: 0 6px 6px 0;
}
.markdown-content table {
  border-collapse: collapse;
  margin: 8px 0;
  font-size: 13px;
}
.markdown-content th, .markdown-content td {
  border: 1px solid var(--border, #e8e5f0);
  padding: 6px 10px;
  text-align: left;
}
.markdown-content th {
  background: #f0edf6;
  font-weight: 700;
}

/* ===== 暗色模式适配（完整抄 AI助手） ===== */
[data-theme="dark"] .markdown-content pre {
  background: #1a1925;
  border-color: #2a2838;
}
[data-theme="dark"] .markdown-content p code {
  background: #2a2838;
  color: #a78bfa;
}
[data-theme="dark"] .markdown-content blockquote {
  background: #1a1925;
  color: #a09eb8;
}
[data-theme="dark"] .markdown-content th { background: #1e1d2c; }
[data-theme="dark"] .markdown-content td {
  background: #1a1925;
  color: #a09eb8;
}
[data-theme="dark"] .markdown-content th,
[data-theme="dark"] .markdown-content td { border-color: #2a2838; }
[data-theme="dark"] .markdown-content table {
  background: #1a1925;
}

/* hljs diff 高亮 — 暗色 */
[data-theme="dark"] .markdown-content .hljs-addition {
  background: rgba(16,185,129,0.12);
  color: #4ade80;
}
[data-theme="dark"] .markdown-content .hljs-deletion {
  background: rgba(239,68,68,0.12);
  color: #f87171;
}

/* hljs 语法高亮 — 暗色 */
[data-theme="dark"] .markdown-content .hljs-keyword { color: #f87171; }
[data-theme="dark"] .markdown-content .hljs-string { color: #a5d6ff; }
[data-theme="dark"] .markdown-content .hljs-number { color: #79c0ff; }
[data-theme="dark"] .markdown-content .hljs-comment { color: #6a6880; }
[data-theme="dark"] .markdown-content .hljs-built_in { color: #79c0ff; }
[data-theme="dark"] .markdown-content .hljs-type { color: #79c0ff; }
[data-theme="dark"] .markdown-content .hljs-literal { color: #79c0ff; }
[data-theme="dark"] .markdown-content .hljs-attr { color: #79c0ff; }
[data-theme="dark"] .markdown-content .hljs-tag { color: #7ee787; }
[data-theme="dark"] .markdown-content .hljs-name { color: #7ee787; }
[data-theme="dark"] .markdown-content .hljs-attribute { color: #79c0ff; }
[data-theme="dark"] .markdown-content .hljs-selector-class { color: #79c0ff; }
[data-theme="dark"] .markdown-content pre code.hljs {
  color: #e4e2f0;
}
</style>
