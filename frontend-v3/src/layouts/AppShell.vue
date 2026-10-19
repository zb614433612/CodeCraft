<!--
  应用外壳（v3）
  ----------------------------------------------------------------------------
  职责：
    1) 渲染全局图标库（AppIcons）与两栏骨架（侧栏 + 主区）；
    2) 管理「侧栏折叠」状态；
    3) 把 settingsStore 的外观设置真正落到界面上：
         theme         -> <html data-theme="light|dark">（auto 跟随 prefers-color-scheme）
         bgImageUrl    -> .cc-app-bg 固定背景层（background-image）
         bgOpacity     -> 背景层 opacity
         bgBlur        -> 背景层 filter: blur()
    4) 把侧栏事件透传给上层（App.vue / 具体页面负责路由跳转与数据请求）。
  样式：assets/app.css 中 `.cc-shell` / `.cc-sidebar` / `.cc-main` / `.cc-app-bg`。
  注意：颜色全部来自 tokens.css，本组件不写任何颜色值。
  ----------------------------------------------------------------------------
  主题与背景为什么读 store 而不是 props：
    外观设置页（/appearance）写入 settingsStore，本组件读同一个 store，
    改完立刻生效，无需逐层透传；刷新后由 pinia 持久化插件从 localStorage 恢复。
-->
<template>
  <AppIcons />
  <!--
    窗口拖拽条不在这里渲染 —— 它挂在 App.vue 的分支之外（登录页也要能拖窗口）。
    本组件只负责把 `cc-electron` 类补挂到 <html> 上（index.html 已早期挂过一次，
    这里是对「直接打开子页面 / 早期脚本失效」的兜底）。
  -->
  <!-- 窗口背景层：fixed 铺满、pointer-events: none，位于内容之下 -->
  <div v-if="settings.bgImageUrl" class="cc-app-bg" :style="bgStyle" />
  <div class="cc-shell" :class="{ 'is-collapsed': collapsed, 'has-bg': !!settings.bgImageUrl }">
    <AppSidebar
      :active-key="activeKey"
      :groups="groups"
      :active-conv-id="activeConvId"
      :user-name="userName"
      :user-avatar="userAvatar"
      :dark="isDark"
      :is-admin="isAdmin"
      :agents="agents"
      :current-agent-id="currentAgentId"
      :collapsed="collapsed"
      @select-menu="$emit('select-menu', $event)"
      @select-conv="$emit('select-conv', $event)"
      @rename-conv="$emit('rename-conv', $event)"
      @export-conv="$emit('export-conv', $event)"
      @delete-conv="$emit('delete-conv', $event)"
      @new-chat="$emit('new-chat')"
      @switch-agent="$emit('switch-agent', $event)"
      @open-profile="$emit('open-profile')"
      @logout="$emit('logout')"
      @switch-version="$emit('switch-version')"
      @toggle-collapse="collapsed = !collapsed"
      @toggle-theme="toggleTheme"
    />
    <main class="cc-main">
      <slot />
    </main>
  </div>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import AppIcons from '../components/AppIcons.vue'
import AppSidebar from '../components/AppSidebar.vue'
import type { AgentPickerItem } from '../components/ChatAgentPicker.vue'
import { useSettingsStore } from '../store/settings'
import type { ConversationGroup } from '../menu.config'

withDefaults(
  defineProps<{
    activeKey?: string
    groups?: ConversationGroup[]
    activeConvId?: string
    userName?: string
    /** 当前用户头像（图片 URL / emoji / 单字）：透传给侧栏左下角 */
    userAvatar?: string
    /** 是否管理员：透传给侧栏，用于过滤 adminOnly 菜单项 */
    isAdmin?: boolean
    /** 智能体切换器数据（聊天页侧栏顶部） */
    agents?: AgentPickerItem[]
    /** 当前智能体 id */
    currentAgentId?: number | string
  }>(),
  {
    activeKey: '/chat',
    groups: () => [],
    activeConvId: '',
    userName: '用户',
    userAvatar: '',
    isAdmin: false,
    agents: () => [],
    currentAgentId: ''
  }
)

defineEmits<{
  (e: 'select-menu', key: string): void
  (e: 'select-conv', id: string): void
  (e: 'rename-conv', id: string): void
  (e: 'export-conv', id: string): void
  (e: 'delete-conv', id: string): void
  (e: 'new-chat'): void
  /** 聊天页侧栏切换智能体 */
  (e: 'switch-agent', id: number | string): void
  /** 侧栏账号菜单：打开个人信息页 */
  (e: 'open-profile'): void
  /** 侧栏账号菜单：退出登录 */
  (e: 'logout'): void
  /** 侧栏账号菜单：切换到经典版（v2） */
  (e: 'switch-version'): void
}>()

const settings = useSettingsStore()

/**
 * 是否运行在 Electron 桌面壳里。
 *
 * <p>`preload.js` 会往 window 注入 `electronAPI`（浏览器环境为 undefined）。
 * 判定结果同时写到 `<html class="cc-electron">`，让 app.css 给 `.cc-shell`
 * 让出顶部 32px（原生窗口按钮的高度），避免右上角内容被最小化/关闭按钮压住。</p>
 */
const isElectron = typeof window !== 'undefined' && Boolean((window as any).electronAPI)

if (isElectron && typeof document !== 'undefined') {
  document.documentElement.classList.add('cc-electron')
}

/**
 * 侧栏折叠。
 *
 * <p>持久化到 localStorage：折叠属于「工作习惯」而不是本次会话的临时状态，
 * 刷新后突然又展开会让人以为折叠按钮没生效。键名与主题等本地偏好保持一致的
 * `codecraft.` 前缀。</p>
 */
const COLLAPSE_KEY = 'codecraft.sidebar-collapsed'
const collapsed = ref(
  typeof localStorage === 'undefined' ? false : localStorage.getItem(COLLAPSE_KEY) === '1'
)

/**
 * 把「侧栏折叠 / 是否启用壁纸」同步到 `<html>` 的类名上。
 *
 * <p>顶部自绘标题栏（App.vue 的 `.cc-titlebar`）在 `.cc-shell` 之外，读不到本组件的
 * props / state，只能靠 html 上的类名来决定「左段多宽」「两段用哪一档透明度」：
 *   cc-sidebar-collapsed -> 左段宽度切到 --sidebar-w-collapsed
 *   cc-has-bg            -> 两段改用 74% / 62% 半透明卡色（与外壳同口径）
 * 这两件事都必须跟着状态实时变，否则折叠后顶部会和侧栏错位、切壁纸后顶部颜色断层。</p>
 */
function syncChromeFlags() {
  if (typeof document === 'undefined') return
  const el = document.documentElement
  el.classList.toggle('cc-sidebar-collapsed', collapsed.value)
  el.classList.toggle('cc-has-bg', Boolean(settings.bgImageUrl))
}

watch(collapsed, value => {
  try {
    localStorage.setItem(COLLAPSE_KEY, value ? '1' : '0')
  } catch {
    // 隐私模式 / 存储被禁用时 localStorage 可能抛错，折叠功能本身不受影响
  }
  syncChromeFlags()
})

watch(() => settings.bgImageUrl, syncChromeFlags)

// setup 阶段先同步一次：否则首帧顶部左段宽度与透明度会用默认值，出现一次可见跳变
syncChromeFlags()

/** 系统外观是否为深色：theme === 'auto' 时用它决定实际明暗 */
const systemDark = ref(false)
let media: MediaQueryList | null = null

/** 实际是否深色：light / dark 直接决定，auto 跟随系统 */
const isDark = computed(
  () => settings.theme === 'dark' || (settings.theme === 'auto' && systemDark.value)
)

/** 把主题写到 <html data-theme>；颜色 token 在 tokens.css 里按该属性切换 */
function syncTheme() {
  document.documentElement.setAttribute('data-theme', isDark.value ? 'dark' : 'light')
}

/**
 * 侧栏「亮 / 暗」按钮
 * 当前是 auto 时，按实际观感取反后落到明确两态（避免按钮点了没反应）
 */
function toggleTheme() {
  settings.setTheme(isDark.value ? 'light' : 'dark')
}

/** 背景层内联样式：图片 + 不透明度 + 模糊 */
const bgStyle = computed(() => ({
  backgroundImage: `url("${settings.bgImageUrl}")`,
  opacity: String(settings.bgOpacity),
  filter: settings.bgBlur ? `blur(${settings.bgBlur}px)` : 'none'
}))

/** 主题变化（含 auto 下系统切换）立即生效 */
watch(isDark, syncTheme)

function onSystemThemeChange(event: MediaQueryListEvent) {
  systemDark.value = event.matches
}

// setup 阶段先同步一次，避免「深色用户刷新后闪一下浅色」
if (typeof window !== 'undefined') {
  systemDark.value = window.matchMedia('(prefers-color-scheme: dark)').matches
  syncTheme()
}

onMounted(() => {
  media = window.matchMedia('(prefers-color-scheme: dark)')
  systemDark.value = media.matches
  media.addEventListener('change', onSystemThemeChange)
  syncTheme()
})

onBeforeUnmount(() => {
  media?.removeEventListener('change', onSystemThemeChange)
  media = null
  // 退出登录后 AppShell 会被卸载、换成 LoginView（没有侧栏，也没有背景图层）：
  // 这两个类名若留在 <html> 上，登录页顶部会误用「折叠宽度 / 半透明档位」。
  if (typeof document !== 'undefined') {
    document.documentElement.classList.remove('cc-sidebar-collapsed', 'cc-has-bg')
  }
})
</script>
