<!--
  连接设备抽屉（v2）
  ----------------------------------------------------------------------------
  两种添加方式：
    1) 粘贴连接串（完整串 p2p://... 或精简串）→ POST /api/p2p/connect
       body { connectionString }，后端阻塞等待结果，超时 10 秒（408）
    2) 导入对方的二维码截图 → POST /api/p2p/qrcode/parse
       body { image: base64 }，返回 { peerId, address, fingerprint }
       解析出的地址由父组件拼成连接串后通过 parsedConnectionString 回填
  安全说明：连接串内含地址 + peerId + 证书指纹，握手时做 TLS 双向校验；
           二维码图片只在本地读取 base64，不存档。
  数据接缝：
    v-model:open              -> 抽屉开关
    connecting                -> 连接请求进行中（按钮 loading / 禁用）
    parsedConnectionString    -> 父组件解析二维码后的回填值
    emit('connect')           -> POST /api/p2p/connect
    emit('parse-qr')          -> POST /api/p2p/qrcode/parse（传 base64）
-->
<template>
  <aside class="cc-drawer" :class="{ 'is-open': open }" style="--drawer-w: 480px">
    <DrawerResizer storage-key="p2p-connect" />
    <div class="cc-drawer__head">
      <div style="font-size: var(--fs-md); font-weight: 700">连接设备</div>
      <button class="cc-icon-btn" style="margin-left: auto" title="关闭" @click="close">
        <svg class="cc-i cc-i--sm" style="transform: rotate(45deg)"><use href="#i-plus" /></svg>
      </button>
    </div>

    <div class="cc-drawer__body">
      <div class="cc-banner" style="background: var(--info-lt); border-color: rgba(46,144,250,.22); margin-bottom: var(--sp-4)">
        <svg class="cc-i cc-i--sm" style="color: var(--info)"><use href="#i-link" /></svg>
        <span class="cc-banner__text">
          连接串由对方在「我的二维码」里生成，内含地址、peerId 与证书指纹；握手走 TLS 双向校验。
        </span>
      </div>

      <div class="cc-form">
        <div class="cc-field">
          <label class="cc-field__label">连接串</label>
          <textarea
            ref="connInput"
            v-model="connStr"
            class="cc-textarea"
            rows="4"
            placeholder="p2p://eyJwZWVySWQiOiJhMWIyYzNkNCIsImFkZHJlc3MiOiJbMjQwODo..."
          />
          <div class="cc-field__hint">支持完整连接串与精简串；连接超时为 10 秒</div>
        </div>

        <div class="cc-field">
          <label class="cc-field__label">或从二维码图片导入</label>
          <div class="cc-copy-row">
            <input class="cc-input" :value="qrFileName" placeholder="选择对方二维码截图（PNG / JPG）" readonly />
            <button class="cc-btn" :disabled="connecting" @click="qrInput?.click()">
              <svg class="cc-i cc-i--sm"><use href="#i-image" /></svg>选择图片
            </button>
          </div>
          <input ref="qrInput" type="file" accept="image/*" style="display: none" @change="onPickQr" />
          <div class="cc-field__hint">
            图片仅在本地读取为 base64 后调用解析接口，不会上传存档；解析成功会自动回填连接串
          </div>
        </div>
      </div>
    </div>

    <div class="cc-drawer__foot">
      <button class="cc-btn" @click="close">取消</button>
      <button class="cc-btn cc-btn--primary" :disabled="connecting" @click="onSubmit">
        <svg class="cc-i cc-i--sm"><use href="#i-link" /></svg>{{ connecting ? '连接中…' : '连接' }}
      </button>
    </div>
  </aside>
</template>

<script setup lang="ts">
import DrawerResizer from './DrawerResizer.vue'
import { ref, watch } from 'vue'

const props = withDefaults(
  defineProps<{
    open: boolean
    /** 连接请求进行中（后端阻塞等待最多 10 秒） */
    connecting?: boolean
    /** 父组件解析二维码后回填的连接串 */
    parsedConnectionString?: string
  }>(),
  {
    connecting: false,
    parsedConnectionString: ''
  }
)

const emit = defineEmits<{
  (e: 'update:open', v: boolean): void
  (e: 'connect', connectionString: string): void
  (e: 'parse-qr', imageBase64: string): void
}>()

const connStr = ref('')
const qrFileName = ref('')
const connInput = ref<HTMLTextAreaElement | null>(null)
const qrInput = ref<HTMLInputElement | null>(null)

/** 每次打开清空上一次的输入 */
watch(
  () => props.open,
  v => {
    if (!v) return
    connStr.value = ''
    qrFileName.value = ''
  }
)

/** 二维码解析完成后回填连接串 */
watch(
  () => props.parsedConnectionString,
  v => {
    if (v) connStr.value = v
  }
)

function onPickQr(e: Event) {
  const input = e.target as HTMLInputElement
  const file = input.files?.[0]
  if (!file) return
  qrFileName.value = file.name
  const reader = new FileReader()
  reader.onload = () => {
    const raw = String(reader.result || '')
    // data:image/png;base64,xxxx -> 只把 base64 部分交给后端
    emit('parse-qr', raw.includes(',') ? raw.split(',')[1] : raw)
  }
  reader.readAsDataURL(file)
  input.value = ''
}

function onSubmit() {
  const value = connStr.value.trim()
  if (!value) {
    connInput.value?.focus()
    return
  }
  emit('connect', value)
}

function close() {
  emit('update:open', false)
}
</script>
