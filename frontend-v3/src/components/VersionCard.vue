<!--
  版本与更新卡片（F06）
  ----------------------------------------------------------------------------
  能力：当前版本 / 最新版本 / 发布时间 / Release 说明（markdown）/ 检查更新 / 下载（进度条）/ 安装 / 打开发布页。
  数据来源：
    · Electron 桌面端：window.electronAPI.update（preload.js → electron/update-manager.js，v3 直接复用）
    · 浏览器环境：isUpdateSupported() === false → 只显示版本号 + 一行灰字提示，不报错
    · 后端版本：GET /api/system/version（`api/system.ts` 的 fetchAppVersion）
  两处刻意的设计：
    1) 「客户端版本」与「后端版本」分开显示：桌面端 app.getVersion() 读的是安装包版本，
       浏览器端只能读 jar 里的版本；只换 jar 不重装客户端时两者会不一致，合并显示会误导排查；
    2) Release 正文用 markdown 渲染（renderMarkdown 开启了 html:false，外链自动加 target=_blank），
       不做 HTML 直出，避免 GitHub 侧内容变成 XSS 入口。
-->
<template>
  <section class="cc-about-card cc-vercard">
    <div class="cc-vercard__head">
      <div class="cc-vercard__brand">
        <div class="cc-vercard__logo">
          <svg class="cc-i"><use href="#i-refresh" /></svg>
        </div>
        <div>
          <div class="cc-vercard__title">
            {{ appName }}
            <span class="cc-vercard__badge">v{{ displayVersion }}</span>
            <span v-if="hasUpdate" class="cc-tag cc-tag--warn">有新版本</span>
          </div>
          <div class="cc-vercard__desc">
            {{ supported ? '桌面客户端内可直接下载并安装新版本' : '浏览器环境仅展示版本信息，自动更新在桌面客户端内可用' }}
          </div>
        </div>
      </div>
      <div class="cc-vercard__ops">
        <button class="cc-btn cc-btn--primary" :disabled="checking || downloading" @click="onCheck">
          <svg class="cc-i cc-i--sm"><use href="#i-refresh" /></svg>
          {{ checking ? '检查中…' : '检查更新' }}
        </button>
        <button class="cc-btn" @click="onOpenRelease">
          <svg class="cc-i cc-i--sm"><use href="#i-link" /></svg>打开发布页
        </button>
        <!-- 嵌入数据管理页时才有：更新设置（自动检查 / 下载加速 / 备份份数）在版本与更新页 -->
        <button v-if="showSettingsLink" class="cc-btn cc-btn--ghost" @click="emit('open-settings')">
          <svg class="cc-i cc-i--sm"><use href="#i-cog" /></svg>更新设置
        </button>
      </div>
    </div>

    <!-- 版本矩阵 -->
    <div class="cc-vercard__grid">
      <div class="cc-vercard__item">
        <span class="cc-vercard__label">客户端版本</span>
        <span class="cc-vercard__value">
          <span class="cc-vercard__mono">v{{ clientVersion || '—' }}</span>
          <span class="cc-tag">{{ supported ? '桌面端' : '浏览器' }}</span>
        </span>
      </div>
      <div class="cc-vercard__item">
        <span class="cc-vercard__label">后端版本</span>
        <span class="cc-vercard__value">
          <span class="cc-vercard__mono">v{{ backend.version || '—' }}</span>
          <span v-if="versionMismatch" class="cc-tag cc-tag--warn">与客户端不一致</span>
        </span>
      </div>
      <div class="cc-vercard__item">
        <span class="cc-vercard__label">构建时间</span>
        <span class="cc-vercard__value cc-vercard__mono">{{ backend.buildTime || '未提供' }}</span>
      </div>
      <div class="cc-vercard__item">
        <span class="cc-vercard__label">运行环境</span>
        <span class="cc-vercard__value">
          {{ platform }}<template v-if="arch"> · {{ arch }}</template>
          <template v-if="backend.javaVersion"> · Java {{ backend.javaVersion }}</template>
        </span>
      </div>
      <div class="cc-vercard__item">
        <span class="cc-vercard__label">最近检查</span>
        <span class="cc-vercard__value">{{ lastCheckedText || '本次启动尚未检查' }}</span>
      </div>
      <div class="cc-vercard__item">
        <span class="cc-vercard__label">后端进程</span>
        <span class="cc-vercard__value cc-vercard__mono">{{ backend.pid ? 'PID ' + backend.pid : '—' }}</span>
      </div>
    </div>

    <!-- 检查结果 -->
    <div v-if="error" class="cc-vercard__alert" role="alert">
      <svg class="cc-i cc-i--sm"><use href="#i-alert" /></svg>
      <span>{{ error }}</span>
    </div>
    <div v-else-if="checked && !hasUpdate" class="cc-vercard__alert is-ok">
      <svg class="cc-i cc-i--sm"><use href="#i-check" /></svg>
      <span>已是最新版本（v{{ displayVersion }}）</span>
    </div>

    <!-- 新版本详情 -->
    <div v-if="hasUpdate" class="cc-vercard__release">
      <div class="cc-vercard__release-head">
        <div>
          <span class="cc-vercard__release-ver">最新版本 v{{ latest?.latestVersion }}</span>
          <span v-if="publishedText" class="cc-vercard__release-time">发布于 {{ publishedText }}</span>
        </div>
        <span v-if="assetText" class="cc-vercard__release-size">安装包 {{ assetText }}</span>
      </div>

      <div v-if="notesHtml" class="cc-vercard__notes cc-md" v-html="notesHtml" />
      <div v-else class="cc-vercard__notes cc-vercard__notes--empty">该 Release 没有填写更新说明</div>

      <!-- 下载进度 -->
      <div v-if="downloading" class="cc-vercard__progress">
        <div class="cc-vercard__progress-bar">
          <span class="cc-vercard__progress-fill" :style="{ width: progressPercent + '%' }" />
        </div>
        <div class="cc-vercard__progress-meta">
          <span>{{ progressPercent }}%</span>
          <span>{{ formatBytes(download.received) }} / {{ formatBytes(download.total) }}</span>
          <button class="cc-btn cc-btn--ghost" @click="onCancelDownload">取消下载</button>
        </div>
      </div>

      <!-- 下载 / 安装 -->
      <div v-else class="cc-vercard__release-ops">
        <button
          v-if="!downloadReady"
          class="cc-btn cc-btn--primary"
          :disabled="busy"
          @click="onDownload"
        >
          <svg class="cc-i cc-i--sm"><use href="#i-disk" /></svg>
          下载更新{{ assetText ? '（' + assetText + '）' : '' }}
        </button>
        <template v-else>
          <button class="cc-btn cc-btn--primary" :disabled="busy" @click="onInstall">
            <svg class="cc-i cc-i--sm"><use href="#i-check" /></svg>
            {{ installAction.button }}
          </button>
          <button class="cc-btn" @click="onOpenFolder">
            <svg class="cc-i cc-i--sm"><use href="#i-file" /></svg>已下载：{{ download.fileName || '安装包' }}
          </button>
        </template>
        <span class="cc-vercard__hint">{{ installAction.hint }}</span>
      </div>

      <div v-if="download.status === 'error'" class="cc-vercard__alert" role="alert">
        <svg class="cc-i cc-i--sm"><use href="#i-alert" /></svg>
        <span>下载失败：{{ download.error || '未知原因' }}</span>
      </div>
    </div>

    <div v-if="!supported" class="cc-vercard__note">
      <svg class="cc-i cc-i--sm"><use href="#i-info" /></svg>
      <span>
        自动更新依赖 Electron 主进程（<code>electron/update-manager.js</code>）。
        在浏览器中只能查看版本；如需升级，请到 GitHub Releases 手动下载安装包。
      </span>
    </div>
  </section>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { fetchAppVersion, type AppVersionInfo } from '../api/system'
import { renderMarkdown } from '../utils/markdown'
import { toast } from '../utils/toast'
import {
  describeInstallAction,
  formatBytes,
  formatReleaseTime,
  getUpdateApi,
  isUpdateSupported,
  platformLabel,
  type UpdateCheckResult,
  type UpdateDownloadState
} from '../utils/update'

/**
 * 父级（AboutView）已经拉到的后端版本。
 * 传了就不再自己请求一次 —— 同一个页面里两份「后端版本」重复请求没有意义，
 * 而且两份状态可能出现「统计行已有值、卡片还是 —」的瞬时不一致。
 */
const props = withDefaults(
  defineProps<{
    backendInfo?: AppVersionInfo | null
    /**
     * 是否显示「更新设置」跳转按钮。
     * VersionCard 有两处宿主：独立的版本与更新页（自带设置区，不需要按钮）
     * 和「数据管理」页（只嵌入检测/下载卡片，设置区仍在版本与更新页，故给一个入口）。
     */
    showSettingsLink?: boolean
  }>(),
  { backendInfo: null, showSettingsLink: false }
)

const emit = defineEmits<{
  (e: 'open-settings'): void
}>()

const api = getUpdateApi()
const supported = isUpdateSupported()

const appName = ref('CodeCraft')
const clientVersion = ref('')
const platform = ref(supported ? '' : '浏览器')
const arch = ref('')
const latest = ref<UpdateCheckResult | null>(null)
const download = reactive<UpdateDownloadState>({
  status: 'idle',
  percent: 0,
  received: 0,
  total: 0,
  filePath: '',
  fileName: '',
  version: '',
  error: ''
})
const backend = reactive<AppVersionInfo>({ name: 'CodeCraft', version: '' })
const lastCheckedAt = ref(0)

const checking = ref(false)
const busy = ref(false)
const checked = ref(false)
const error = ref('')

/** 卸载回调（组件卸载时必须解绑，否则 IPC 监听会随页面切换越积越多） */
let offProgress: (() => void) | null = null

const displayVersion = computed(() => clientVersion.value || backend.version || '—')
const hasUpdate = computed(() => Boolean(latest.value?.hasUpdate))
const notesHtml = computed(() => {
  const raw = (latest.value?.notes || '').trim()
  return raw ? renderMarkdown(raw) : ''
})
const publishedText = computed(() => formatReleaseTime(latest.value?.publishedAt))
const assetText = computed(() => {
  const size = latest.value?.asset?.size || 0
  return size > 0 ? formatBytes(size) : ''
})
const installAction = computed(() => describeInstallAction(latest.value?.platform || (supported ? '' : 'web')))
const versionMismatch = computed(
  () => Boolean(clientVersion.value && backend.version) && clientVersion.value !== backend.version
)
const lastCheckedText = computed(() => (lastCheckedAt.value ? formatReleaseTime(new Date(lastCheckedAt.value).toISOString()) : ''))
const downloading = computed(() => download.status === 'downloading')
/** 版本号规范化：主进程 / Release 可能带 `v` 前缀，比较前统一去掉 */
function normalizeVersion (value?: string | null): string {
  return String(value || '').trim().replace(/^v/i, '')
}

/**
 * 已下载的安装包是否**正是**当前检测到的新版本。
 *
 * <p>必须比对版本号，不能只看 `status === 'done'`：主进程会把上一次下载完成的状态
 * 一并返回（用户先下过 3.0.1，后来发布了 3.0.2），此时界面会把「安装 3.0.1」
 * 摆在最前面 —— 用户以为在装新版本，实际装的是旧包。
 * `download.version` 为空（老版本主进程没回该字段）时按「不匹配」处理，宁可让用户重下。</p>
 */
const downloadReady = computed(() => {
  if (download.status !== 'done') return false
  const downloaded = normalizeVersion(download.version)
  const target = normalizeVersion(latest.value?.latestVersion)
  return !!downloaded && !!target && downloaded === target
})
const progressPercent = computed(() => Math.min(100, Math.max(0, Math.round(download.percent || 0))))

function applyDownload (next: UpdateDownloadState) {
  download.status = next.status
  download.percent = next.percent
  download.received = next.received
  download.total = next.total
  download.filePath = next.filePath
  download.fileName = next.fileName
  download.version = next.version
  download.error = next.error
}

/** 读取后端版本（浏览器 / 桌面端都要，用于「后端版本」一列） */
async function loadBackend () {
  // 父级已经拉到就直接用（AboutView 为了统计行会先拉一次）
  if (props.backendInfo && props.backendInfo.version) {
    Object.assign(backend, props.backendInfo)
    if (props.backendInfo.name) appName.value = props.backendInfo.name
    return
  }
  try {
    const info = await fetchAppVersion()
    Object.assign(backend, info)
    if (info.name) appName.value = info.name
  } catch (e) {
    // 后端版本拿不到不影响更新功能（桌面端版本来自 app.getVersion）
    console.warn('[update] 读取后端版本失败:', e)
  }
}

/** 读取主进程状态（版本 / 上次检查 / 已有下载 / 设置） */
async function loadState () {
  if (!api) {
    platform.value = '浏览器'
    await loadBackend()
    return
  }
  try {
    const state = await api.getState()
    clientVersion.value = state.currentVersion || ''
    platform.value = platformLabel(state.platform)
    arch.value = state.arch || ''
    latest.value = state.latest || null
    lastCheckedAt.value = state.lastCheckedAt || 0
    if (state.latest) checked.value = true
    applyDownload(state.download)
  } catch (e) {
    error.value = e instanceof Error ? e.message : '读取更新状态失败'
  }
  await loadBackend()
}

async function onCheck () {
  if (checking.value || busy.value) return
  if (!api) {
    toast.warning('浏览器环境不支持自动更新，请到 GitHub Releases 手动下载')
    return
  }
  checking.value = true
  error.value = ''
  try {
    const result = await api.check(true)
    latest.value = result
    lastCheckedAt.value = result.checkedAt || Date.now()
    checked.value = true
    if (result.hasUpdate) {
      /*
        检测到新版本：本地若躺着**另一个版本**的安装包，直接把它作废，
        让「下载更新」按钮重新出现。
        否则用户看到的是「安装 v3.0.1」（旧包），点下去装的也不是刚检测到的新版本。
      */
      const target = normalizeVersion(result.latestVersion)
      const downloaded = normalizeVersion(download.version)
      const staleDownload = download.status === 'done' && !!downloaded && downloaded !== target
      if (staleDownload) {
        download.status = 'idle'
        download.percent = 0
        download.received = 0
        download.total = 0
        download.filePath = ''
        download.fileName = ''
        download.version = ''
        download.error = ''
        toast.info('发现新版本，需要重新下载', {
          description: `本地已有 v${downloaded} 安装包，当前最新是 v${target}`
        })
      } else {
        toast.success(`发现新版本 v${result.latestVersion}`)
      }
    } else {
      toast.success('已是最新版本')
    }
  } catch (e) {
    error.value = e instanceof Error ? e.message : '检查更新失败'
    toast.error(error.value)
  } finally {
    checking.value = false
  }
}

async function onDownload () {
  if (!api || busy.value) return
  busy.value = true
  error.value = ''
  try {
    const next = await api.download()
    applyDownload(next)
    if (next.status === 'done') toast.success('安装包已下载完成，可以开始安装')
    else if (next.status === 'error') toast.error(`下载失败：${next.error || '未知原因'}`)
  } catch (e) {
    error.value = e instanceof Error ? e.message : '下载失败'
    toast.error(error.value)
  } finally {
    busy.value = false
  }
}

async function onCancelDownload () {
  if (!api) return
  try {
    applyDownload(await api.cancelDownload())
  } catch (e) {
    toast.error(e instanceof Error ? e.message : '取消下载失败')
  }
}

async function onInstall () {
  if (!api || busy.value) return
  busy.value = true
  try {
    const result = await api.install({ silent: false })
    if (result.ok) {
      toast.success('安装程序已启动，应用即将退出')
    } else {
      toast.error(result.error || '启动安装程序失败')
    }
  } catch (e) {
    toast.error(e instanceof Error ? e.message : '启动安装程序失败')
  } finally {
    busy.value = false
  }
}

async function onOpenRelease () {
  if (api) {
    try {
      await api.openReleasePage()
      return
    } catch {
      /* 落到下面的浏览器兜底 */
    }
  }
  const url = latest.value?.releaseUrl || 'https://github.com/zb614433612/CodeCraft/releases'
  window.open(url, '_blank', 'noopener')
}

/**
 * 「已下载」按钮：主进程没有「打开下载目录」的独立 IPC，
 * 用 shell.openExternal 打开 file:// 会被拦截，因此这里只提示路径（用户可自行定位）。
 */
function onOpenFolder () {
  if (!download.filePath) return
  toast.info('安装包位置', { description: download.filePath })
}

onMounted(async () => {
  await loadState()
  if (api?.onDownloadProgress) {
    offProgress = api.onDownloadProgress((state) => applyDownload(state))
  }
})

/** 父级异步拿到后端版本时补上（组件先挂载、请求后到的情况） */
watch(
  () => props.backendInfo,
  (info) => {
    if (!info || !info.version) return
    Object.assign(backend, info)
    if (info.name) appName.value = info.name
  }
)

onBeforeUnmount(() => {
  offProgress?.()
  offProgress = null
})
</script>
