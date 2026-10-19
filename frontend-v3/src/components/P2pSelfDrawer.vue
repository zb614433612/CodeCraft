<!--
  我的设备抽屉（v2）：本机身份 + 二维码 + 连接串
  ----------------------------------------------------------------------------
  用途：把自己的「连接信息」交给对方，让对方扫码 / 粘贴连接串来配对。
  与后端对应：
    GET  /api/p2p/status   -> running / peerId / port / peerCount / certFingerprint / myName
    GET  /api/p2p/address  -> addresses[] / bestAddress / hasIpv6
    POST /api/p2p/name     -> 修改本机名称（握手时告知对方）
    POST /api/p2p/qrcode   -> connectionString / compactString / qrCodeBase64（失败时带 qrError）
  注意：qrCodeBase64 可能是纯 base64 或已含 data: 前缀，组件做了兼容判断。
  数据接缝：
    v-model:open    -> 抽屉开关
    name            -> 当前本机名称
    status/address  -> 上面两个 GET 的返回
    signaling       -> POST /api/p2p/qrcode 的返回
    emit('save-name')-> POST /api/p2p/name
    emit('refresh') -> 重新生成二维码
-->
<template>
  <aside class="cc-drawer" :class="{ 'is-open': open }" style="--drawer-w: 560px">
    <DrawerResizer storage-key="p2p-self" />
    <div class="cc-drawer__head">
      <div style="font-size: var(--fs-md); font-weight: 700">我的设备</div>
      <span class="cc-tag" :class="address?.hasIpv6 ? 'cc-tag--ok' : 'cc-tag--warn'">
        {{ address?.hasIpv6 ? 'IPv6 可用' : '仅 IPv4' }}
      </span>
      <button class="cc-icon-btn" style="margin-left: auto" title="关闭" @click="close">
        <svg class="cc-i cc-i--sm" style="transform: rotate(45deg)"><use href="#i-plus" /></svg>
      </button>
    </div>

    <div class="cc-drawer__body">
      <!-- 二维码 -->
      <div class="cc-qr-box">
        <img v-if="qrSrc" :src="qrSrc" alt="我的连接二维码" />
        <div v-else class="cc-qr-box__empty">
          {{ signaling?.qrError ? '二维码生成失败：' + signaling.qrError : '点下方「刷新二维码」生成连接信息' }}
        </div>
        <div class="cc-qr-hint">
          让对方在「连接设备」里扫码，或把下方连接串直接发给对方
        </div>
      </div>

      <div class="cc-form">
        <div class="cc-field">
          <label class="cc-field__label">设备名称</label>
          <input v-model="localName" class="cc-input" placeholder="例如：办公室-MacBook" />
          <div class="cc-field__hint">握手时会把名称告知对方（POST /api/p2p/name）</div>
        </div>

        <div class="cc-field">
          <label class="cc-field__label">连接串</label>
          <div class="cc-copy-row">
            <input class="cc-input" :value="signaling?.connectionString || ''" readonly placeholder="尚未生成" />
            <button class="cc-btn" :disabled="!signaling?.connectionString" @click="copy(signaling?.connectionString)">
              <svg class="cc-i cc-i--sm"><use href="#i-copy" /></svg>复制
            </button>
          </div>
        </div>

        <div class="cc-field">
          <label class="cc-field__label">精简串</label>
          <div class="cc-copy-row">
            <input class="cc-input" :value="signaling?.compactString || ''" readonly placeholder="尚未生成" />
            <button class="cc-btn" :disabled="!signaling?.compactString" @click="copy(signaling?.compactString)">
              <svg class="cc-i cc-i--sm"><use href="#i-copy" /></svg>复制
            </button>
          </div>
        </div>
      </div>

      <!-- 只读事实区 -->
      <div class="cc-lesson-facts">
        <div class="cc-lesson-facts__item">
          <span class="cc-lesson-facts__label">peerId</span>
          <span class="cc-lesson-facts__value is-mono">{{ status?.peerId || '-' }}</span>
        </div>
        <div class="cc-lesson-facts__item">
          <span class="cc-lesson-facts__label">监听端口</span>
          <span class="cc-lesson-facts__value is-mono">{{ status?.port ?? '-' }}</span>
        </div>
        <div class="cc-lesson-facts__item">
          <span class="cc-lesson-facts__label">服务状态</span>
          <span class="cc-lesson-facts__value">{{ status?.running ? '运行中' : '已停止' }}</span>
        </div>
        <div class="cc-lesson-facts__item">
          <span class="cc-lesson-facts__label">已连设备</span>
          <span class="cc-lesson-facts__value">{{ status?.peerCount ?? 0 }}</span>
        </div>
        <div class="cc-lesson-facts__item cc-lesson-facts__item--wide">
          <span class="cc-lesson-facts__label">证书指纹</span>
          <span class="cc-lesson-facts__value is-mono">{{ status?.certFingerprint || '-' }}</span>
        </div>
        <div class="cc-lesson-facts__item cc-lesson-facts__item--wide">
          <span class="cc-lesson-facts__label">地址列表</span>
          <span class="cc-lesson-facts__value is-mono">
            {{ address?.addresses?.length ? address.addresses.join('  /  ') : '（尚未采集到可用地址）' }}
          </span>
        </div>
      </div>
    </div>

    <div class="cc-drawer__foot">
      <button class="cc-btn" @click="$emit('refresh')">
        <svg class="cc-i cc-i--sm"><use href="#i-refresh" /></svg>刷新二维码
      </button>
      <button class="cc-btn" @click="close">关闭</button>
      <button class="cc-btn cc-btn--primary" @click="onSave">保存名称</button>
    </div>
  </aside>
</template>

<script setup lang="ts">
import DrawerResizer from './DrawerResizer.vue'
import { computed, ref, watch } from 'vue'

/** 本机 P2P 状态（GET /api/p2p/status） */
export interface P2pStatus {
  running: boolean
  peerId: string
  port: number
  peerCount: number
  certFingerprint: string
  myName: string
}

/** 本机地址（GET /api/p2p/address） */
export interface P2pAddressInfo {
  addresses: string[]
  bestAddress: string | null
  hasIpv6: boolean
}

/** 信令结果（POST /api/p2p/qrcode） */
export interface P2pSignalingResult {
  connectionString: string
  compactString: string
  qrCodeBase64: string
  qrError?: string
}

const props = withDefaults(
  defineProps<{
    open: boolean
    name?: string
    status?: P2pStatus | null
    address?: P2pAddressInfo | null
    signaling?: P2pSignalingResult | null
  }>(),
  {
    name: '',
    status: null,
    address: null,
    signaling: null
  }
)

const emit = defineEmits<{
  (e: 'update:open', v: boolean): void
  (e: 'save-name', name: string): void
  (e: 'refresh'): void
}>()

const localName = ref('')

watch(
  () => [props.open, props.name] as const,
  () => {
    if (props.open) localName.value = props.name || ''
  },
  { immediate: true }
)

/** 后端可能返回纯 base64 或带 data: 前缀，这里统一补前缀 */
const qrSrc = computed(() => {
  const raw = props.signaling?.qrCodeBase64
  if (!raw) return ''
  return raw.startsWith('data:') ? raw : `data:image/png;base64,${raw}`
})

function onSave() {
  emit('save-name', localName.value.trim())
  close()
}

async function copy(text?: string) {
  if (!text) return
  try {
    await navigator.clipboard.writeText(text)
  } catch {
    // 非安全上下文（如 file://）降级：写入临时 textarea 再选中复制
    const el = document.createElement('textarea')
    el.value = text
    document.body.appendChild(el)
    el.select()
    try {
      document.execCommand('copy')
    } catch {
      /* 浏览器不支持则忽略 */
    }
    document.body.removeChild(el)
  }
}

function close() {
  emit('update:open', false)
}
</script>
