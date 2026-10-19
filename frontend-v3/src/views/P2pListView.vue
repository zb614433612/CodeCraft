<!--
  设备互联页（v2）
  ----------------------------------------------------------------------------
  布局：页面头（我的二维码 / 连接设备）→ 本机身份卡 → 内联统计 → 工具栏（搜索 + 在线筛选）
       → 设备行列表 → 三个抽屉（设备详情/授权、连接设备、我的设备）
  与旧版 P2pPanel 的分工：旧面板把「设备列表 + 聊天 + 文件传输」塞在一个双栏组件里（82KB）；
  新版把「设备与连接管理」独立成页，聊天与文件传输留在聊天页，页面职责单一。
  数据接缝：
    status          -> GET /api/p2p/status（running / peerId / port / peerCount / certFingerprint / myName）
    address         -> GET /api/p2p/address（addresses / bestAddress / hasIpv6）
    signaling       -> POST /api/p2p/qrcode（connectionString / compactString / qrCodeBase64）
    peers           -> GET /api/p2p/peers（在线来自连接池 + 离线来自已知节点表）
    grantedCounts   -> GET /api/p2p/agent-auth/sent/{peerId} 的条数汇总
    sentAgentsByPeer     -> 同上（按 peerId 分组）
    receivedAgentsByPeer -> GET /api/p2p/agent-auth/received/{peerId}
    emit('toggle')       -> POST /api/p2p/disconnect/{peerId} 或 /reconnect/{peerId}
    emit('delete-peer')  -> DELETE /api/p2p/peer/{peerId}
    emit('save-peer')    -> PUT /api/p2p/peer/{peerId}/remark + agent-auth grant/cancel 差集
    emit('connect')      -> POST /api/p2p/connect
    emit('parse-qr')     -> POST /api/p2p/qrcode/parse
    emit('save-name')    -> POST /api/p2p/name
    emit('refresh-qr')   -> 重新生成二维码
    emit('refresh')      -> 聊天抽屉关闭后重拉列表（同步「已授权 N 个」与在线状态）

  聊天抽屉（P2pChatDrawer）自包含：历史 / 2 秒轮询 / 发文本 / 传文件 / 调 Agent / 授权
  全部在组件内部直调 /api/p2p，本页只负责「哪台设备」与开合。
-->
<template>
  <div class="cc-page-scroll">
    <div class="cc-page-inner">
      <!-- 页面头 -->
      <div class="cc-page-head">
        <div class="cc-page-head__icon">
          <svg class="cc-i"><use href="#i-link" /></svg>
        </div>
        <div>
          <h1 class="cc-page-head__title">设备互联</h1>
          <p class="cc-page-head__sub">
            端到端加密的点对点连接（TLS + IPv6 优先）：扫码或粘贴连接串配对设备，可远程调用对方授权的 Agent
          </p>
        </div>
        <div class="cc-page-head__actions">
          <button class="cc-btn" @click="openQr">
            <svg class="cc-i cc-i--sm"><use href="#i-qr" /></svg>我的二维码
          </button>
          <button class="cc-btn cc-btn--primary" @click="connectOpen = true">
            <svg class="cc-i cc-i--sm"><use href="#i-plus" /></svg>连接设备
          </button>
        </div>
      </div>

      <!-- 本机身份卡 -->
      <div class="cc-p2p-self">
        <div class="cc-p2p-self__icon">
          <svg class="cc-i"><use href="#i-agent" /></svg>
        </div>
        <div class="cc-p2p-self__main">
          <div class="cc-p2p-self__title">
            <span class="cc-p2p-self__name">{{ selfName || '未命名设备' }}</span>
            <span class="cc-status" :class="status?.running ? 'is-connected' : ''">
              <span class="cc-status__dot" />{{ status?.running ? '服务运行中' : '服务已停止' }}
            </span>
            <span class="cc-tag" :class="address?.hasIpv6 ? 'cc-tag--ok' : 'cc-tag--warn'">
              {{ address?.hasIpv6 ? 'IPv6 可用' : '仅 IPv4' }}
            </span>
          </div>

          <div class="cc-p2p-kv">
            <span class="cc-p2p-kv__item">
              <span class="cc-p2p-kv__label">peerId</span>
              <span class="cc-p2p-mono">{{ status?.peerId || '-' }}</span>
            </span>
            <span class="cc-p2p-kv__item">
              <span class="cc-p2p-kv__label">监听端口</span>
              <b style="color: var(--text-2)">{{ status?.port ?? '-' }}</b>
            </span>
            <span class="cc-p2p-kv__item">
              <span class="cc-p2p-kv__label">证书</span>
              <span class="cc-p2p-mono">{{ shortFingerprint }}</span>
            </span>
          </div>

          <div class="cc-p2p-kv">
            <span class="cc-p2p-kv__item">
              <span class="cc-p2p-kv__label">最佳地址</span>
              <span class="cc-p2p-mono">{{ address?.bestAddress || '（尚未采集到可用地址）' }}</span>
            </span>
          </div>
        </div>
        <div class="cc-p2p-self__actions">
          <button class="cc-btn" @click="selfOpen = true">
            <svg class="cc-i cc-i--sm"><use href="#i-brush" /></svg>改名
          </button>
        </div>
      </div>

      <!-- 统计 -->
      <div class="cc-stats-inline" style="margin-bottom: var(--sp-4)">
        <span>在线设备 <b>{{ onlineCount }}</b></span><span class="cc-dot" />
        <span>离线设备 <b>{{ offlineCount }}</b></span><span class="cc-dot" />
        <span>已知节点 <b>{{ peers.length }}</b></span><span class="cc-dot" />
        <span>监听端口 <b>{{ status?.port ?? '-' }}</b></span>
      </div>

      <!-- 加载中 / 接口失败提示（内联样式，不新增 CSS 类） -->
      <div
        v-if="loading || error"
        :style="{
          marginBottom: 'var(--sp-3)',
          fontSize: 'var(--fs-sm)',
          color: error ? 'var(--danger)' : 'var(--text-3)'
        }"
      >
        {{ error || '正在读取本机节点信息…' }}
      </div>

      <!-- 工具栏 -->
      <div class="cc-toolbar">
        <label class="cc-search">
          <svg class="cc-i cc-i--sm"><use href="#i-search" /></svg>
          <input v-model="keyword" placeholder="搜索设备名称、备注或 peerId" />
        </label>
        <div class="cc-seg">
          <button
            v-for="opt in STATE_OPTIONS"
            :key="opt.value"
            :class="{ 'is-active': stateFilter === opt.value }"
            @click="stateFilter = opt.value"
          >
            {{ opt.label }}
          </button>
        </div>
        <div style="margin-left: auto; font-size: var(--fs-sm); color: var(--text-3)">
          共 <b style="color: var(--text-1)">{{ filteredPeers.length }}</b> 个
        </div>
      </div>

      <!-- 设备列表 -->
      <div v-if="filteredPeers.length" class="cc-p2p-list">
        <P2pPeerRow
          v-for="peer in filteredPeers"
          :key="peer.peerId"
          :peer="peer"
          :granted-count="grantedCounts[peer.peerId] || 0"
          @open="openPeer"
          @chat="openChat"
          @toggle="$emit('toggle', $event)"
          @delete="onDelete"
        />
      </div>

      <!-- 空状态 -->
      <div v-else class="cc-agent-empty">
        <span class="cc-agent-empty__icon">
          <svg class="cc-i cc-i--lg"><use href="#i-link" /></svg>
        </span>
        <span>{{ peers.length ? '没有匹配的设备' : '还没有配对任何设备' }}</span>
        <span v-if="!peers.length" style="font-size: var(--fs-xs); color: var(--text-4)">
          点右上角「连接设备」粘贴对方的连接串，或扫码添加
        </span>
        <button v-else class="cc-btn" style="margin-top: var(--sp-2)" @click="resetFilter">
          <svg class="cc-i cc-i--sm"><use href="#i-refresh" /></svg>清空筛选
        </button>
      </div>
    </div>

    <!-- 遮罩 + 抽屉 -->
    <div v-if="peerOpen || connectOpen || selfOpen || chatOpen" class="cc-mask is-open" @click="closeAll" />

    <P2pPeerDrawer
      v-model:open="peerOpen"
      :peer="currentPeer"
      :sent-agents="currentSentAgents"
      :received-agents="currentReceivedAgents"
      :available-agents="availableAgents"
      @save="$emit('save-peer', $event)"
      @delete="$emit('delete-peer', $event)"
    />

    <P2pConnectDrawer
      v-model:open="connectOpen"
      :connecting="connecting"
      :parsed-connection-string="parsedConnectionString"
      @connect="$emit('connect', $event)"
      @parse-qr="$emit('parse-qr', $event)"
    />

    <P2pSelfDrawer
      v-model:open="selfOpen"
      :name="selfName"
      :status="status"
      :address="address"
      :signaling="signaling"
      @save-name="$emit('save-name', $event)"
      @refresh="$emit('refresh-qr')"
    />

    <!-- 聊天抽屉：关闭后重拉列表，保证「已授权 N 个」与在线状态是最新的 -->
    <P2pChatDrawer
      :open="chatOpen"
      :peer="chatPeer"
      :my-name="selfName"
      @update:open="onChatOpenChange"
    />
  </div>
</template>

<script setup lang="ts">
import { computed, ref, onMounted } from 'vue'
import P2pPeerRow, { type P2pPeer } from '../components/P2pPeerRow.vue'
import P2pPeerDrawer, { type AgentAuthItem } from '../components/P2pPeerDrawer.vue'
import P2pConnectDrawer from '../components/P2pConnectDrawer.vue'
import P2pChatDrawer from '../components/P2pChatDrawer.vue'
import P2pSelfDrawer, {
  type P2pStatus,
  type P2pAddressInfo,
  type P2pSignalingResult
} from '../components/P2pSelfDrawer.vue'

type StateFilter = 'all' | 'online' | 'offline'

const props = withDefaults(
  defineProps<{
    selfName?: string
    status?: P2pStatus | null
    address?: P2pAddressInfo | null
    signaling?: P2pSignalingResult | null
    peers?: P2pPeer[]
    /** 每个节点「我授权给对方的 Agent」数量 */
    grantedCounts?: Record<string, number>
    sentAgentsByPeer?: Record<string, AgentAuthItem[]>
    receivedAgentsByPeer?: Record<string, AgentAuthItem[]>
    /** 可授权的全部 Agent */
    availableAgents?: AgentAuthItem[]
    connecting?: boolean
    parsedConnectionString?: string
    /** 首次加载中（status / address / peers 并行请求） */
    loading?: boolean
    /** 接口失败原因（非空时展示提示条） */
    error?: string
    /**
     * 进入页面时的数据加载（由 App.vue 的 PAGE_BINDINGS 注入）
     * 页面随路由真实挂载，所以在这里调一次即可；重复进页由父级的「已加载 / 加载中 / 已失败」跳过
     */
    load?: () => void
  }>(),
  {
    selfName: '',
    status: null,
    address: null,
    signaling: null,
    peers: () => [],
    grantedCounts: () => ({}),
    sentAgentsByPeer: () => ({}),
    receivedAgentsByPeer: () => ({}),
    availableAgents: () => [],
    connecting: false,
    parsedConnectionString: '',
    loading: false,
    error: ''
  }
)

const emit = defineEmits<{
  (e: 'toggle', peer: P2pPeer): void
  (e: 'delete-peer', peerId: string): void
  (e: 'save-peer', payload: { peerId: string; remark: string; grantedIds: number[] }): void
  (e: 'connect', connectionString: string): void
  (e: 'parse-qr', imageBase64: string): void
  (e: 'save-name', name: string): void
  (e: 'refresh-qr'): void
  /** 聊天抽屉关闭后请求父级重拉列表 */
  (e: 'refresh'): void
}>()

const STATE_OPTIONS: Array<{ label: string; value: StateFilter }> = [
  { label: '全部', value: 'all' },
  { label: '在线', value: 'online' },
  { label: '离线', value: 'offline' }
]

const keyword = ref('')
const stateFilter = ref<StateFilter>('all')

const onlineCount = computed(() => props.peers.filter(p => p.online).length)
const offlineCount = computed(() => props.peers.length - onlineCount.value)

const filteredPeers = computed(() => {
  const kw = keyword.value.trim().toLowerCase()
  return props.peers.filter(peer => {
    if (stateFilter.value === 'online' && !peer.online) return false
    if (stateFilter.value === 'offline' && peer.online) return false
    if (!kw) return true
    return [peer.displayName, peer.name, peer.remark, peer.peerId, peer.address]
      .filter(Boolean)
      .join(' ')
      .toLowerCase()
      .includes(kw)
  })
})

/** 证书指纹缩略：只留前 16 位与末 4 位，避免撑破卡片 */
const shortFingerprint = computed(() => {
  const fp = props.status?.certFingerprint || ''
  if (!fp) return '-'
  return fp.length <= 24 ? fp : `${fp.slice(0, 16)}…${fp.slice(-4)}`
})

function resetFilter() {
  keyword.value = ''
  stateFilter.value = 'all'
}

/* ---------------- 抽屉状态 ---------------- */
const peerOpen = ref(false)
const connectOpen = ref(false)
const selfOpen = ref(false)
const currentPeer = ref<P2pPeer | null>(null)

/* ---------------- 聊天抽屉 ---------------- */
const chatOpen = ref(false)
const chatPeer = ref<P2pPeer | null>(null)

/**
 * 打开聊天：先关掉其它抽屉（互斥），再设置目标设备。
 * 离线设备也允许打开 —— 只读历史比「点了没反应」有用，输入区在组件内自动禁用。
 */
function openChat(peer: P2pPeer) {
  closeAll()
  chatPeer.value = peer
  chatOpen.value = true
}

/** 抽屉自己请求关闭（点 × / ESC / 遮罩）时，顺带让父级刷新列表 */
function onChatOpenChange(value: boolean) {
  chatOpen.value = value
  if (!value) emit('refresh')
}

function openPeer(peer: P2pPeer) {
  closeAll()
  currentPeer.value = peer
  peerOpen.value = true
}

/**
 * 打开「我的二维码」
 * ----------------------------------------------------------------------------
 * 二维码由 POST /p2p/qrcode 现算（含本机地址 / peerId / 证书指纹），
 * 因此每次打开都重新生成一次，避免显示上次会话的过期信息。
 */
function openQr() {
  selfOpen.value = true
  emit('refresh-qr')
}

const currentSentAgents = computed(() =>
  currentPeer.value ? props.sentAgentsByPeer[currentPeer.value.peerId] || [] : []
)
const currentReceivedAgents = computed(() =>
  currentPeer.value ? props.receivedAgentsByPeer[currentPeer.value.peerId] || [] : []
)

function onDelete(peer: P2pPeer) {
  // 行内删除：直接请求父组件彻底删除（连带聊天记录与接收文件）
  emit('delete-peer', peer.peerId)
}

function closeAll() {
  // 遮罩点击会走这里：聊天抽屉被关掉时同样要让父级刷新一次（与 onChatOpenChange 对齐）
  const wasChatOpen = chatOpen.value
  peerOpen.value = false
  connectOpen.value = false
  selfOpen.value = false
  chatOpen.value = false
  if (wasChatOpen) emit('refresh')
}

/**
 * 「进入页面才拉数据」的触发点（原 App.vue 的 PAGE_LOADERS 已下沉至此）
 * 父级通过 props.load 注入具体加载函数，组件只负责在最合适的时机调用它。
 */
onMounted(() => props.load?.())
</script>
