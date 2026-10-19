<!--
  设备（P2P 节点）行（v2）
  ----------------------------------------------------------------------------
  信息层次：首字母头像（右下角在线点）+ 显示名 + 在线状态 + 已授权 Agent 数
           → 地址 · peerId（等宽弱底一行）
           → 元信息：连接时间 / 备注 / 离线说明
  数据来源：GET /api/p2p/peers（在线节点来自连接池，离线节点来自已知节点表）
  状态语义：
    online=true  在线（lastHeartbeat 未超过 heartbeatTimeout）
    online=false 离线（保留记录与聊天历史，可重连；地址/证书可能已变更）
   操作：
     在线 → 断开（POST /api/p2p/disconnect/{peerId}，保留消息与记录）
     离线 → 重连（POST /api/p2p/reconnect/{peerId}，使用库中保存的地址与证书）
     两者 → 聊天（打开 P2pChatDrawer，查看历史 / 发文本 / 传文件 / 调 Agent）
            详情/授权（抽屉）、删除节点（DELETE /api/p2p/peer/{peerId}，连带聊天与接收文件）
  说明：displayName 由后端按「备注 > Agent 配置名 > 握手名 > 未知」计算，前端直接展示。
-->
<template>
  <div class="cc-p2p-row" :class="peer.online ? 'is-online' : 'is-off'">
    <div class="cc-p2p-row__avatar">
      {{ initial }}<span class="cc-p2p-row__dot" />
    </div>

    <div class="cc-p2p-row__main">
      <div class="cc-p2p-row__title">
        <span class="cc-p2p-name">{{ displayText }}</span>
        <span class="cc-status" :class="peer.online ? 'is-connected' : ''">
          <span class="cc-status__dot" />{{ peer.online ? '在线' : '离线' }}
        </span>
        <span v-if="grantedCount > 0" class="cc-tag">已授权 {{ grantedCount }} 个 Agent</span>
      </div>

      <div class="cc-p2p-addr">
        <span>{{ peer.address || '（无地址记录）' }}</span>
        <span class="cc-p2p-addr__sep">·</span>
        <span class="cc-p2p-addr__id">peer {{ shortPeerId }}</span>
      </div>

      <div class="cc-p2p-row__meta">
        <span v-if="peer.online" class="cc-p2p-meta-item">
          <svg class="cc-i cc-i--sm"><use href="#i-clock" /></svg>连接于 {{ formatRelative(peer.connectedAt) }}
        </span>
        <span v-else class="cc-p2p-meta-item">
          <svg class="cc-i cc-i--sm"><use href="#i-clock" /></svg>上次连接 {{ formatRelative(peer.connectedAt) }}
        </span>
        <span v-if="peer.remark" class="cc-p2p-meta-item">
          <svg class="cc-i cc-i--sm"><use href="#i-book" /></svg>备注：{{ peer.remark }}
        </span>
        <span v-else-if="!peer.online" class="cc-p2p-meta-item">离线节点保留记录，可重连</span>
      </div>
    </div>

    <div class="cc-p2p-row__side">
      <!-- 聊天入口常显：hover 才出现的图标按钮发现性太差，而聊天是本页主要操作之一 -->
      <button
        class="cc-btn cc-p2p-row__chat"
        :title="peer.online ? '聊天 / 传文件' : '对方离线，可查看历史（恢复连接后可继续发送）'"
        @click="$emit('chat', peer)"
      >
        <svg class="cc-i cc-i--sm"><use href="#i-chat" /></svg><span>聊天</span>
      </button>
      <div class="cc-p2p-row__ops">
        <button class="cc-icon-btn cc-icon-btn--sm" title="详情 / 授权" @click="$emit('open', peer)">
          <svg class="cc-i cc-i--sm"><use href="#i-shield" /></svg>
        </button>
        <button class="cc-icon-btn cc-icon-btn--sm" :title="peer.online ? '断开连接' : '重连'" @click="$emit('toggle', peer)">
          <svg class="cc-i cc-i--sm"><use :href="peer.online ? '#i-stop' : '#i-refresh'" /></svg>
        </button>
        <button class="cc-icon-btn cc-icon-btn--sm" title="删除节点" @click="$emit('delete', peer)">
          <svg class="cc-i cc-i--sm"><use href="#i-trash" /></svg>
        </button>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'

/** P2P 节点（对应后端 /api/p2p/peers 的元素） */
export interface P2pPeer {
  peerId: string
  address: string
  /** 握手时对方上报的名称 */
  name: string
  /** 后端计算的展示名：备注 > Agent 配置名 > 握手名 > 未知 */
  displayName: string
  remark: string
  /** 连接建立时间（epoch 毫秒；离线节点为最后一次连接时间） */
  connectedAt: number
  /** 最后心跳（epoch 毫秒；离线节点为 0） */
  lastHeartbeat: number
  online: boolean
}

const props = withDefaults(
  defineProps<{
    peer: P2pPeer
    /** 我授权给该节点的 Agent 数量（由父组件按 peer 汇总） */
    grantedCount?: number
  }>(),
  { grantedCount: 0 }
)

defineEmits<{
  (e: 'open', peer: P2pPeer): void
  (e: 'toggle', peer: P2pPeer): void
  (e: 'delete', peer: P2pPeer): void
  (e: 'chat', peer: P2pPeer): void
}>()

const displayText = computed(() => props.peer.displayName || props.peer.name || '未知设备')

/** 头像首字（中文取第一个字，英文取首字母大写） */
const initial = computed(() => {
  const text = displayText.value.trim()
  return text ? text.slice(0, 1).toUpperCase() : '?'
})

/** peerId 截断展示：前 8 + 后 4 */
const shortPeerId = computed(() => {
  const id = props.peer.peerId || ''
  if (id.length <= 14) return id || '-'
  return `${id.slice(0, 8)}…${id.slice(-4)}`
})

/** 相对时间：刚刚 / N 分钟前 / N 小时前 / N 天前 / 日期 */
function formatRelative(epoch?: number): string {
  if (!epoch) return '未知'
  const diff = Date.now() - epoch
  if (diff < 0) return '刚刚'
  const minutes = Math.floor(diff / 60_000)
  if (minutes < 1) return '刚刚'
  if (minutes < 60) return `${minutes} 分钟前`
  const hours = Math.floor(minutes / 60)
  if (hours < 24) return `${hours} 小时前`
  const days = Math.floor(hours / 24)
  if (days < 30) return `${days} 天前`
  const d = new Date(epoch)
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`
}
</script>
