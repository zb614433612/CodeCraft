<!--
  设备详情 / Agent 授权抽屉（v2）
  ----------------------------------------------------------------------------
  右侧滑出（560px），一个抽屉承载「看设备信息 + 改备注 + 管授权」：
    1) 只读事实区：显示名 / 状态 / peerId / 地址 / 连接于 / 最后心跳
    2) 备注：PUT /api/p2p/peer/{peerId}/remark（备注优先于对方设置的设备名）
    3) 双向授权：
       我授权给对方的 Agent —— POST /api/p2p/agent-auth/grant|cancel/{peerId}
                                 body { agentConfigIds: number[] }
                                 冲突时后端返回 409（同一 Agent 已被其他用户授权给该节点）
       对方授权给我的 Agent —— GET /api/p2p/agent-auth/received/{peerId}（可调用清单）
  数据接缝：
    v-model:open      -> 抽屉开关
    peer              -> 当前节点（GET /api/p2p/peers）
    sentAgents        -> GET /api/p2p/agent-auth/sent/{peerId}
    receivedAgents    -> GET /api/p2p/agent-auth/received/{peerId}
    availableAgents   -> 所有可选 Agent（由父组件从 Agent 列表提供）
    emit('save')      -> 备注 + 授权差集（granted 新增 → grant，移除 → cancel）
    emit('delete')    -> DELETE /api/p2p/peer/{peerId}（连带聊天记录与接收文件）
-->
<template>
  <aside class="cc-drawer" :class="{ 'is-open': open }" style="--drawer-w: 560px">
    <DrawerResizer storage-key="p2p-peer" />
    <div class="cc-drawer__head">
      <div style="font-size: var(--fs-md); font-weight: 700">设备详情 / Agent 授权</div>
      <button class="cc-icon-btn" style="margin-left: auto" title="关闭" @click="close">
        <svg class="cc-i cc-i--sm" style="transform: rotate(45deg)"><use href="#i-plus" /></svg>
      </button>
    </div>

    <div class="cc-drawer__body">
      <!-- 只读事实区 -->
      <div class="cc-lesson-facts">
        <div class="cc-lesson-facts__item">
          <span class="cc-lesson-facts__label">显示名</span>
          <span class="cc-lesson-facts__value">{{ peer?.displayName || peer?.name || '-' }}</span>
        </div>
        <div class="cc-lesson-facts__item">
          <span class="cc-lesson-facts__label">状态</span>
          <span class="cc-lesson-facts__value">{{ peer?.online ? '在线（心跳正常）' : '离线' }}</span>
        </div>
        <div class="cc-lesson-facts__item cc-lesson-facts__item--wide">
          <span class="cc-lesson-facts__label">peerId</span>
          <span class="cc-lesson-facts__value is-mono">{{ peer?.peerId || '-' }}</span>
        </div>
        <div class="cc-lesson-facts__item cc-lesson-facts__item--wide">
          <span class="cc-lesson-facts__label">地址</span>
          <span class="cc-lesson-facts__value is-mono">{{ peer?.address || '（无地址记录）' }}</span>
        </div>
        <div class="cc-lesson-facts__item">
          <span class="cc-lesson-facts__label">{{ peer?.online ? '连接于' : '上次连接' }}</span>
          <span class="cc-lesson-facts__value">{{ formatTime(peer?.connectedAt) }}</span>
        </div>
        <div class="cc-lesson-facts__item">
          <span class="cc-lesson-facts__label">最后心跳</span>
          <span class="cc-lesson-facts__value">{{ peer?.online ? formatTime(peer?.lastHeartbeat) : '—' }}</span>
        </div>
      </div>

      <!-- 备注 -->
      <div class="cc-form">
        <div class="cc-field">
          <label class="cc-field__label">备注</label>
          <input v-model="remark" class="cc-input" placeholder="给这台设备起个容易认的名字" />
          <div class="cc-field__hint">备注优先于对方设置的设备名（PUT /api/p2p/peer/{peerId}/remark）</div>
        </div>
      </div>

      <!-- 双向 Agent 授权 -->
      <div class="cc-p2p-agents">
        <div class="cc-p2p-agents__head">
          <svg class="cc-i cc-i--sm"><use href="#i-skill" /></svg>我授权给对方的 Agent
          <span>对方可远程调用</span>
        </div>

        <div v-if="availableAgents.length" class="cc-chips">
          <span
            v-for="agent in availableAgents"
            :key="agent.id"
            class="cc-chip"
            :class="{ 'is-active': selectedIds.includes(agent.id) }"
            @click="toggleAgent(agent.id)"
          >
            {{ agent.name }}
          </span>
        </div>
        <div v-else class="cc-p2p-agents__empty">暂无可授权的 Agent，请先在「智能体」页创建</div>

        <div class="cc-field__hint">
          点击切换授权状态；同一 Agent 已被其他用户授权给该节点时后端返回 409
        </div>

        <div class="cc-p2p-agents__head" style="margin-top: var(--sp-2)">
          <svg class="cc-i cc-i--sm"><use href="#i-agent" /></svg>对方授权给我的 Agent
          <span>我可在这台设备上调用</span>
        </div>

        <div v-if="receivedAgents.length" class="cc-chips">
          <span v-for="agent in receivedAgents" :key="agent.id" class="cc-chip is-active">{{ agent.name }}</span>
        </div>
        <div v-else class="cc-p2p-agents__empty">对方尚未授权任何 Agent 给我</div>
      </div>
    </div>

    <div class="cc-drawer__foot">
      <button class="cc-btn cc-btn--danger" @click="onDelete">
        <svg class="cc-i cc-i--sm"><use href="#i-trash" /></svg>删除节点
      </button>
      <button class="cc-btn" @click="close">取消</button>
      <button class="cc-btn cc-btn--primary" @click="onSave">保存</button>
    </div>
  </aside>
</template>

<script setup lang="ts">
import DrawerResizer from './DrawerResizer.vue'
import { ref, watch } from 'vue'
import type { P2pPeer } from './P2pPeerRow.vue'

/** 可授权 / 已授权的 Agent（对应后端 AgentAuthItem） */
export interface AgentAuthItem {
  id: number
  name: string
  avatar?: string
}

const props = withDefaults(
  defineProps<{
    open: boolean
    peer?: P2pPeer | null
    /** 我授权给该节点的 Agent */
    sentAgents?: AgentAuthItem[]
    /** 对方授权给我的 Agent */
    receivedAgents?: AgentAuthItem[]
    /** 可授权的全部 Agent */
    availableAgents?: AgentAuthItem[]
  }>(),
  {
    peer: null,
    sentAgents: () => [],
    receivedAgents: () => [],
    availableAgents: () => []
  }
)

const emit = defineEmits<{
  (e: 'update:open', v: boolean): void
  (e: 'save', payload: { peerId: string; remark: string; grantedIds: number[] }): void
  (e: 'delete', peerId: string): void
}>()

const remark = ref('')
const selectedIds = ref<number[]>([])

/** 打开或切换节点时回填备注与已授权集合 */
watch(
  () => [props.open, props.peer, props.sentAgents] as const,
  () => {
    if (!props.open) return
    remark.value = props.peer?.remark || ''
    selectedIds.value = props.sentAgents.map(a => a.id)
  },
  { immediate: true }
)

function toggleAgent(id: number) {
  const idx = selectedIds.value.indexOf(id)
  if (idx >= 0) selectedIds.value.splice(idx, 1)
  else selectedIds.value.push(id)
}

function formatTime(epoch?: number): string {
  if (!epoch) return '—'
  const d = new Date(epoch)
  if (Number.isNaN(d.getTime())) return '—'
  const pad = (v: number) => String(v).padStart(2, '0')
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}`
}

function close() {
  emit('update:open', false)
}

function onSave() {
  if (!props.peer?.peerId) return
  emit('save', {
    peerId: props.peer.peerId,
    remark: remark.value.trim(),
    grantedIds: [...selectedIds.value]
  })
  close()
}

function onDelete() {
  if (!props.peer?.peerId) return
  emit('delete', props.peer.peerId)
  close()
}
</script>
