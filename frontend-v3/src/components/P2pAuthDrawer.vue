<!--
  P2P Agent 授权抽屉（F01）
  ----------------------------------------------------------------------------
  为什么单独一个抽屉：聊天抽屉里也要能改授权（对方突然授权了新 Agent，不该逼用户关掉聊天
  再回设备详情页），但又不想把两套 chips 逻辑塞进聊天组件。

  两个方向：
    我授权给对方的 —— POST /api/p2p/agent-auth/grant|cancel/{peerId}，body { agentConfigIds: number[] }
                       同一 Agent 已被其他用户授权给该节点时后端返回 409
    对方授权给我的 —— GET  /api/p2p/agent-auth/received/{peerId}（我只读，用于聊天页 Agent 模式）
  交互：点 chip 即时生效（不设「保存」按钮），失败回滚并 toast 报错原因。

  数据自包含：内部直接调 listAgentConfigs / getMyAuthToPeer / getPeerAuthToMe，
  不依赖父级传 Agent 列表（设备互联页与聊天抽屉都能独立使用）。
-->
<template>
  <aside class="cc-drawer" :class="{ 'is-open': open }" style="--drawer-w: 520px; z-index: 78">
    <DrawerResizer storage-key="p2p-auth" />
    <div class="cc-drawer__head">
      <div style="font-size: var(--fs-md); font-weight: 700">Agent 授权</div>
      <span v-if="peer" class="cc-tag" style="margin-left: 4px">{{ peerDisplayName }}</span>
      <button class="cc-icon-btn" style="margin-left: auto" title="关闭" @click="close">
        <svg class="cc-i cc-i--sm" style="transform: rotate(45deg)"><use href="#i-plus" /></svg>
      </button>
    </div>

    <div class="cc-drawer__body">
      <div v-if="loading" class="cc-p2p-auth__hint">正在读取授权信息…</div>

      <!-- 我授权给对方的 -->
      <div class="cc-p2p-agents">
        <div class="cc-p2p-agents__head">
          <svg class="cc-i cc-i--sm"><use href="#i-skill" /></svg>我授权给对方的 Agent
          <span>对方可远程调用</span>
        </div>

        <div v-if="myAgents.length" class="cc-chips">
          <span
            v-for="agent in myAgents"
            :key="agent.id"
            class="cc-chip"
            :class="{ 'is-active': grantedIds.includes(agent.id), 'is-busy': busyId === agent.id }"
            @click="toggleGrant(agent)"
          >
            {{ agent.name }}
          </span>
        </div>
        <div v-else class="cc-p2p-agents__empty">暂无可授权的 Agent，请先在「智能体」页创建</div>

        <div class="cc-field__hint">
          点击切换授权状态，即时生效；同一 Agent 已被其他用户授权给该节点时后端返回 409
        </div>

        <!-- 对方授权给我的 -->
        <div class="cc-p2p-agents__head" style="margin-top: var(--sp-3)">
          <svg class="cc-i cc-i--sm"><use href="#i-agent" /></svg>对方授权给我的 Agent
          <span>可在聊天页「Agent 模式」调用</span>
        </div>

        <div v-if="receivedAgents.length" class="cc-chips">
          <span v-for="agent in receivedAgents" :key="agent.id" class="cc-chip is-active">
            {{ agent.name }}
          </span>
        </div>
        <div v-else class="cc-p2p-agents__empty">对方尚未授权任何 Agent 给我</div>
      </div>
    </div>

    <div class="cc-drawer__foot">
      <button class="cc-btn" style="margin-left: auto" @click="close">关闭</button>
    </div>
  </aside>
</template>

<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import DrawerResizer from './DrawerResizer.vue'
import { cancelAgentAuth, getMyAuthToPeer, getPeerAuthToMe, grantAgentAuth } from '../api/p2p'
import { listAgentConfigs } from '../api/agent-config'
import { toast } from '../utils/toast'
import type { P2pPeer } from './P2pPeerRow.vue'

const props = withDefaults(
  defineProps<{
    open: boolean
    peer?: P2pPeer | null
  }>(),
  { peer: null }
)

const emit = defineEmits<{
  (e: 'update:open', value: boolean): void
  /** 授权变化（父级可据此刷新「已授权 N 个」等展示） */
  (e: 'changed', peerId: string): void
}>()

interface AgentOption {
  id: number
  name: string
}

const loading = ref(false)
const myAgents = ref<AgentOption[]>([])
const grantedIds = ref<number[]>([])
const receivedAgents = ref<AgentOption[]>([])
const busyId = ref<number | null>(null)

const peerDisplayName = computed(
  () => props.peer?.remark || props.peer?.displayName || props.peer?.name || props.peer?.peerId || ''
)

function close() {
  emit('update:open', false)
}

function errText(err: unknown, fallback: string) {
  if (err instanceof Error && err.message) return err.message
  return fallback
}

async function loadAll(peerId: string) {
  loading.value = true
  try {
    const [agents, sent, received] = await Promise.all([
      listAgentConfigs().catch(() => null),
      getMyAuthToPeer(peerId).catch(() => []),
      getPeerAuthToMe(peerId).catch(() => [])
    ])
    myAgents.value = (agents?.data || []).map(a => ({ id: Number(a.id), name: a.name }))
    grantedIds.value = (sent || []).map(a => Number(a.id))
    receivedAgents.value = (received || []).map(a => ({ id: Number(a.id), name: a.name }))
  } finally {
    loading.value = false
  }
}

async function toggleGrant(agent: AgentOption) {
  const peerId = props.peer?.peerId
  if (!peerId || busyId.value !== null) return
  const granted = grantedIds.value.includes(agent.id)
  busyId.value = agent.id
  // 乐观更新：失败再回滚
  const before = [...grantedIds.value]
  grantedIds.value = granted ? before.filter(id => id !== agent.id) : [...before, agent.id]
  try {
    if (granted) {
      await cancelAgentAuth(peerId, [agent.id])
      toast.success(`已取消「${agent.name}」的授权`)
    } else {
      await grantAgentAuth(peerId, [agent.id])
      toast.success(`已授权「${agent.name}」给对方`)
    }
    emit('changed', peerId)
  } catch (err) {
    grantedIds.value = before
    toast.error(errText(err, granted ? '取消授权失败' : '授权失败'))
  } finally {
    busyId.value = null
  }
}

/** 每次打开（或切换节点）重新拉一次，避免显示上一次的授权状态 */
watch(
  () => [props.open, props.peer?.peerId] as const,
  ([open, peerId]) => {
    if (open && peerId) void loadAll(peerId)
  },
  { immediate: true }
)
</script>
