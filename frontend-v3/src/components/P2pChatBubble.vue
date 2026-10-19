<!--
  P2P 单条消息气泡（F01）
  ----------------------------------------------------------------------------
  六类渲染：
    chat            文本气泡（保留换行，失败可重试）
    file (image)    图片缩略图（点击走全局灯箱看原图）
    file            文件卡片（扩展名徽章 / 文件名 / 大小 / 传输状态 / 打开目录）
    agent-invoke    调用对方 Agent（带 pending 转圈）
    agent-response  对方 Agent 的回复（Markdown 渲染）
    system          Agent 授权变更等系统通知（居中灰字）
  数据来源：P2pChatMessage（由 utils/p2p-chat.ts 归一化）
  重要边界：己方发出的文件 `local_path` 为空（后端 P2pController 注释「发送方本地无文件」），
            `GET /api/p2p/file/{peerId}/{transferId}` 会 404，故己方图片显示占位而不是硬加载。
-->
<template>
  <!-- 系统通知：授权变更等，居中一行 -->
  <div v-if="message.kind === 'system'" class="cc-p2p-sys">
    <svg class="cc-i cc-i--sm"><use href="#i-shield" /></svg>
    <span>{{ message.content || 'Agent 授权已变更' }}</span>
    <span class="cc-p2p-sys__time">{{ timeText }}</span>
  </div>

  <div v-else class="cc-p2p-msg" :class="message.from === 'me' ? 'is-me' : 'is-peer'">
    <!-- 对方头像 -->
    <div
      v-if="message.from === 'peer'"
      class="cc-p2p-msg__avatar"
      :class="{ 'is-agent': message.kind === 'agent-response' }"
      :style="message.kind === 'agent-response' ? undefined : { background: peerGradient(peerId) }"
    >
      <svg v-if="message.kind === 'agent-response'" class="cc-i cc-i--sm"><use href="#i-agent" /></svg>
      <template v-else>{{ peerInitial }}</template>
    </div>

    <div class="cc-p2p-msg__body">
      <div class="cc-p2p-msg__meta">
        <span class="cc-p2p-msg__name">{{ metaName }}</span>
        <span class="cc-p2p-msg__time">{{ timeText }}</span>
      </div>

      <!-- 文本 -->
      <div v-if="message.kind === 'chat'" class="cc-p2p-bubble" :class="{ 'is-failed': message.failed }">
        <span class="cc-p2p-bubble__text">{{ message.content }}</span>
        <button v-if="message.failed" class="cc-p2p-bubble__retry" @click="$emit('retry', message)">
          <svg class="cc-i cc-i--sm"><use href="#i-refresh" /></svg>重发
        </button>
      </div>

      <!-- 图片 -->
      <div v-else-if="isImageFile(message)" class="cc-p2p-img" :class="{ 'is-me': message.from === 'me' }">
        <img
          v-if="imgSrc && !imgFailed"
          :src="imgSrc"
          :alt="message.fileName || '图片'"
          class="cc-p2p-img__thumb"
          @click="openImage"
          @error="onImgError"
        />
        <div v-else class="cc-p2p-img__ph" @click="message.from === 'peer' && imgSrc && openImage()">
          <svg class="cc-i cc-i--lg"><use href="#i-image" /></svg>
          <span>{{ message.from === 'me' ? '图片已发送' : '图片加载失败' }}</span>
        </div>
        <div class="cc-p2p-img__foot">
          <span class="cc-p2p-img__name">{{ message.fileName || '图片' }}</span>
          <span class="cc-p2p-img__size">{{ formatFileSize(message.fileSize) }}</span>
        </div>
      </div>

      <!-- 普通文件 -->
      <div v-else-if="message.kind === 'file'" class="cc-p2p-file">
        <span class="cc-p2p-file__ext">{{ fileExtBadge(message.fileName) }}</span>
        <div class="cc-p2p-file__info">
          <span class="cc-p2p-file__name" :title="message.fileName">{{ message.fileName || '文件' }}</span>
          <span class="cc-p2p-file__meta">
            {{ formatFileSize(message.fileSize) }} · {{ statusText }}
          </span>
        </div>
        <button
          v-if="message.from === 'peer' && message.fileStatus === 'completed'"
          class="cc-btn"
          style="height: 26px; padding: 0 8px; font-size: var(--fs-xs)"
          title="在系统文件管理器中打开"
          @click="$emit('open-dir', message)"
        >
          打开
        </button>
      </div>

      <!-- Agent 调用 -->
      <div v-else-if="message.kind === 'agent-invoke'" class="cc-p2p-bubble cc-p2p-bubble--agent">
        <div class="cc-p2p-agent__head">
          <svg class="cc-i cc-i--sm"><use href="#i-agent" /></svg>
          调用 Agent「{{ message.agentName || 'Agent' }}」
        </div>
        <div class="cc-p2p-agent__text">{{ invokeText }}</div>
        <div v-if="message.pending" class="cc-p2p-agent__loading">
          <span class="cc-p2p-agent__dot" /><span class="cc-p2p-agent__dot" /><span class="cc-p2p-agent__dot" />
          <span>AI 正在思考中…</span>
        </div>
      </div>

      <!-- Agent 回复 -->
      <div v-else-if="message.kind === 'agent-response'" class="cc-p2p-bubble cc-p2p-bubble--agent is-response">
        <div class="cc-p2p-agent__head">
          <svg class="cc-i cc-i--sm"><use href="#i-skill" /></svg>
          Agent「{{ message.agentName || 'Agent' }}」响应
        </div>
        <div class="cc-md cc-p2p-agent__md" v-html="rendered" />
      </div>
    </div>

    <!-- 我的头像 -->
    <div v-if="message.from === 'me'" class="cc-p2p-msg__avatar is-me">{{ myInitial }}</div>
  </div>
</template>

<script setup lang="ts">
import { computed, ref } from 'vue'
import { getFileUrl, getThumbnailUrl } from '../api/p2p'
import { renderMarkdown } from '../utils/markdown'
import { openLightbox } from '../utils/lightbox'
import {
  fileExtBadge,
  formatChatTime,
  formatFileSize,
  isImageFile,
  peerGradient,
  type P2pChatMessage
} from '../utils/p2p-chat'

const props = withDefaults(
  defineProps<{
    message: P2pChatMessage
    /** 对方 peerId（拼文件 URL） */
    peerId: string
    /** 对方头像首字 */
    peerInitial?: string
    /** 我方头像首字 */
    myInitial?: string
  }>(),
  { peerInitial: '?', myInitial: '我' }
)

defineEmits<{
  (e: 'open-dir', msg: P2pChatMessage): void
  (e: 'retry', msg: P2pChatMessage): void
}>()

const timeText = computed(() => formatChatTime(props.message.at))

const metaName = computed(() => {
  if (props.message.kind === 'agent-response' || props.message.kind === 'agent-invoke') {
    return props.message.name || (props.message.from === 'me' ? '我' : '对方')
  }
  return props.message.from === 'me' ? '我' : props.message.name || '对方'
})

/** 「调用 Agent「X」: 正文」里取出正文（后端把整句塞在 content 里） */
const invokeText = computed(() => {
  const text = props.message.content || ''
  const idx = text.indexOf(': ')
  return idx >= 0 ? text.slice(idx + 2) : text
})

const statusText = computed(() => {
  switch (props.message.fileStatus) {
    case 'transferring':
      return '传输中'
    case 'failed':
      return '传输失败'
    default:
      return props.message.from === 'me' ? '已发送' : '已接收'
  }
})

/* ---------------- 图片：缩略图 → 原图 → 占位 三级降级 ---------------- */
const imgStage = ref<0 | 1 | 2>(0) // 0=缩略图 1=原图 2=失败
const imgFailed = computed(() => props.message.from === 'me' || imgStage.value === 2)

const imgSrc = computed(() => {
  if (props.message.from === 'me') return '' // 己方无本地文件，直接占位
  const { peerId, message } = props
  if (!message.transferId) return ''
  return imgStage.value === 1
    ? getFileUrl(peerId, message.transferId)
    : getThumbnailUrl(peerId, message.transferId)
})

function onImgError() {
  if (imgStage.value === 0) imgStage.value = 1
  else imgStage.value = 2
}

function openImage() {
  if (!props.message.transferId) return
  openLightbox([
    {
      src: getFileUrl(props.peerId, props.message.transferId),
      name: props.message.fileName || '图片'
    }
  ])
}

/* ---------------- Agent 回复：Markdown ---------------- */
const rendered = computed(() => renderMarkdown(props.message.content || ''))
</script>
