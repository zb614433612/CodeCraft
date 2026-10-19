<!--
  内嵌网页（P4 · F04）
  ----------------------------------------------------------------------------
  双通道：
    · Electron 环境（window.electronAPI 存在）→ <webview>：能力完整，可加载任意站点；
    · 浏览器环境 → <iframe sandbox>：受站点 X-Frame-Options / CSP 限制。

  关于「被禁嵌」的检测方式（不虚构能力）：
    浏览器出于安全考虑，跨域 iframe 被 X-Frame-Options 拒绝时**不会**向前端抛任何事件，
    load 事件依然会触发（加载的是浏览器的错误页），因此「精确检测」在 Web 端做不到。
    这里的做法是诚实降级：
      1) 超时（4s 内没有 load）判定为「大概率被拒」，显示居中提示卡片 + 外部打开按钮；
      2) 顶部常驻一条可关闭的提示条，说明「部分站点禁止嵌入」，随时能一键转系统浏览器。
    比假装能检测、白屏后一声不吭要实用。

  安全：
    · 只接受 http / https（见 utils/web-url.ts），javascript: / file:// / data: 直接拒绝；
    · sandbox 放开 scripts / same-origin / forms / popups / modals，
      但**不放开** allow-top-navigation —— 内嵌页不能把整个应用导航走；
    · 不透传任何 token，也不注入脚本。
-->
<template>
  <div class="cc-web">
    <div class="cc-web__bar">
      <button class="cc-icon-btn cc-icon-btn--sm" title="后退" :disabled="!canBack" @click="goBack">
        <svg class="cc-i cc-i--sm" style="transform: rotate(180deg)"><use href="#i-chev" /></svg>
      </button>
      <button class="cc-icon-btn cc-icon-btn--sm" title="前进" :disabled="!canForward" @click="goForward">
        <svg class="cc-i cc-i--sm"><use href="#i-chev" /></svg>
      </button>
      <button class="cc-icon-btn cc-icon-btn--sm" title="刷新" @click="reload">
        <svg class="cc-i cc-i--sm"><use href="#i-refresh" /></svg>
      </button>
      <input
        v-model="address"
        class="cc-web__addr"
        spellcheck="false"
        placeholder="输入网址，Enter 打开（仅支持 http / https）"
        @keydown.enter="onAddressEnter"
        @focus="onAddressFocus"
      />
      <button class="cc-icon-btn cc-icon-btn--sm" title="在系统浏览器打开" @click="openExternal">
        <svg class="cc-i cc-i--sm"><use href="#i-link" /></svg>
      </button>
    </div>

    <!-- 常驻说明：不假装能检测，把「空白怎么办」的出路讲清楚 -->
    <div v-if="showHint" class="cc-web__hint">
      <span class="cc-web__hint-text">
        部分站点（如 github.com）禁止被嵌入，若下方空白请点右侧按钮改用系统浏览器打开。
      </span>
      <button class="cc-web__hint-close" title="不再提示" @click="showHint = false">知道了</button>
    </div>

    <div class="cc-web__stage">
      <div v-if="!currentUrl" class="cc-web__empty">
        <svg class="cc-i cc-i--lg" style="color: var(--text-4)"><use href="#i-link" /></svg>
        <p class="cc-web__empty-title">在地址栏输入网址开始浏览</p>
        <p class="cc-web__empty-desc">例如 example.com、developer.mozilla.org</p>
        <div class="cc-web__empty-links">
          <button v-for="item in SUGGESTIONS" :key="item.url" class="cc-web__suggest" @click="go(item.url)">
            {{ item.label }}
          </button>
        </div>
      </div>

      <template v-else>
        <!-- Electron：能力完整，可加载任意站点 -->
        <webview
          v-if="isElectron"
          :key="frameKey"
          class="cc-web__view"
          :src="currentUrl"
          @did-start-loading="loading = true"
          @did-stop-loading="onLoaded"
          @did-fail-load="onFail"
        />
        <!-- 浏览器：沙箱 iframe，受站点安全头限制 -->
        <iframe
          v-else
          :key="frameKey"
          class="cc-web__view"
          :src="currentUrl"
          sandbox="allow-scripts allow-same-origin allow-forms allow-popups allow-modals"
          referrerpolicy="no-referrer"
          @load="onLoaded"
          @error="onFail"
        />

        <div v-if="loading" class="cc-web__loading">
          <span class="cc-web__spinner" />
          正在加载 {{ displayHost || '页面' }}…
        </div>

        <div v-if="blocked" class="cc-web__blocked">
          <svg class="cc-i cc-i--lg" style="color: var(--warn)"><use href="#i-shield" /></svg>
          <p class="cc-web__blocked-title">这个站点很可能禁止被嵌入</p>
          <p class="cc-web__blocked-desc">
            浏览器加载了 {{ displayHost }} 的拒绝页，通常是因为站点返回了
            <code>X-Frame-Options</code> 或 <code>Content-Security-Policy</code> 限制。
          </p>
          <div class="cc-web__blocked-ops">
            <button class="cc-btn cc-btn--primary" @click="openExternal">在系统浏览器打开</button>
            <button class="cc-btn cc-btn--ghost" @click="reload">仍要重试</button>
          </div>
        </div>
      </template>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { toast } from '../utils/toast'
import { describeWebUrlError, normalizeWebUrl } from '../utils/web-url'

const props = withDefaults(
  defineProps<{
    /** 初始网址（父级来自标签 payload，切换标签时同步） */
    url?: string
  }>(),
  { url: '' }
)

const emit = defineEmits<{
  /** 地址变化（父级据此更新标签标题与 payload，刷新后仍能恢复） */
  (e: 'navigate', url: string): void
}>()

/** 空态推荐站点：都是允许被嵌入的官方文档站 */
const SUGGESTIONS = [
  { label: 'example.com', url: 'https://example.com' },
  { label: 'MDN Web 文档', url: 'https://developer.mozilla.org' },
  { label: 'Vue 官方文档', url: 'https://cn.vuejs.org' }
] as const

/** iframe 无 load 事件超过这个时长，判定为「大概率被站点拒绝」 */
const BLOCK_TIMEOUT = 4000

const isElectron = typeof window !== 'undefined' && !!(window as unknown as { electronAPI?: unknown }).electronAPI

const address = ref(props.url || '')
const currentUrl = ref(normalizeWebUrl(props.url || '') || '')
const loading = ref(false)
const blocked = ref(false)
const showHint = ref(true)
/** 换 key 强制重建 iframe / webview —— 对相同 src 重新赋值不会触发重新加载 */
const frameKey = ref(0)

/** 自维护历史栈：跨域 iframe 读不到内部 history，只能自己记 */
const stack = ref<string[]>([])
const cursor = ref(-1)

const canBack = computed(() => cursor.value > 0)
const canForward = computed(() => cursor.value < stack.value.length - 1)
const displayHost = computed(() => {
  try {
    return new URL(currentUrl.value).host
  } catch {
    return currentUrl.value
  }
})

let blockTimer: ReturnType<typeof setTimeout> | null = null

function clearTimer() {
  if (blockTimer) {
    clearTimeout(blockTimer)
    blockTimer = null
  }
}

/** 加载超时 → 判定为大概率被拒；真加载成功会在 onLoaded 里清掉定时器 */
function armTimer() {
  clearTimer()
  blockTimer = setTimeout(() => {
    if (loading.value) {
      loading.value = false
      blocked.value = true
    }
  }, BLOCK_TIMEOUT)
}

function pushHistory(url: string) {
  const cut = stack.value.slice(0, cursor.value + 1)
  cut.push(url)
  stack.value = cut
  cursor.value = cut.length - 1
}

/**
 * 打开网址。
 * @param raw 用户输入或外部传入（可以不带协议头）
 * @param opts.silent 不写历史（后退/前进时用）
 */
function go(raw: string, opts: { silent?: boolean } = {}) {
  const url = normalizeWebUrl(raw)
  if (!url) {
    toast.error(describeWebUrlError(raw))
    return
  }
  blocked.value = false
  loading.value = true
  address.value = url
  currentUrl.value = url
  frameKey.value += 1
  if (!opts.silent) pushHistory(url)
  armTimer()
  emit('navigate', url)
}

function onAddressEnter() {
  go(address.value)
}

/** 地址栏聚焦时全选，省去用户手动清空 */
function onAddressFocus(event: FocusEvent) {
  ;(event.target as HTMLInputElement | null)?.select()
}

function reload() {
  if (!currentUrl.value) return
  blocked.value = false
  loading.value = true
  frameKey.value += 1
  armTimer()
}

function goBack() {
  if (!canBack.value) return
  cursor.value -= 1
  go(stack.value[cursor.value], { silent: true })
}

function goForward() {
  if (!canForward.value) return
  cursor.value += 1
  go(stack.value[cursor.value], { silent: true })
}

function onLoaded() {
  clearTimer()
  loading.value = false
  blocked.value = false
}

function onFail() {
  clearTimer()
  loading.value = false
  blocked.value = true
}

function openExternal() {
  const url = normalizeWebUrl(currentUrl.value)
  if (!url) return
  window.open(url, '_blank', 'noopener,noreferrer')
}

/*
 * 父级切换标签 / 恢复持久化标签时同步地址。
 * 比较规范化结果而不是原始字符串：本次导航刚 emit 上去时 props.url 会跟着变，
 * 直接比较会再触发一次 go()，把刚加载好的页面又刷一遍。
 */
watch(
  () => props.url,
  next => {
    const url = normalizeWebUrl(next || '') || ''
    if (url === currentUrl.value) return
    address.value = url
    currentUrl.value = url
    blocked.value = false
    loading.value = false
    if (url) {
      frameKey.value += 1
      loading.value = true
      armTimer()
      pushHistory(url)
    }
  }
)

// 首次挂载若有初始地址，记入历史并开始计时
onMounted(() => {
  if (!currentUrl.value) return
  pushHistory(currentUrl.value)
  loading.value = true
  armTimer()
})

onBeforeUnmount(clearTimer)
</script>
