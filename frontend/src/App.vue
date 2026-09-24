<script setup lang="ts">
import { RouterLink, RouterView, useRoute } from 'vue-router'
import { computed, watch, onMounted } from 'vue'
import { useSettingsStore } from '@/store/settings'
import { theme } from 'ant-design-vue'

const route = useRoute()
const hideLayout = computed(() => route.meta.hideLayout || false)

// 是否运行在 Electron 桌面端：用于启用「透明自定义标题栏」的拖拽区与布局顶部留白
const isElectron = typeof window !== 'undefined' && !!(window as any).electronAPI

const settingsStore = useSettingsStore()

// 当前解析后的主题 ('light' | 'dark')
const resolvedTheme = computed<'light' | 'dark'>(() => {
  const mode = settingsStore.getTheme()
  if (mode === 'auto') {
    return window.matchMedia('(prefers-color-scheme: dark)').matches ? 'dark' : 'light'
  }
  return mode as 'light' | 'dark'
})

// Ant Design 主题配置
const antTheme = computed(() => ({
  algorithm: resolvedTheme.value === 'dark' ? theme.darkAlgorithm : theme.defaultAlgorithm,
  token: {
    colorPrimary: '#8b5cf6',
    borderRadius: 8
  }
}))

// 应用主题到 <html> 元素，并同步 Electron 标题栏
function applyTheme(mode: string) {
  let resolved: 'light' | 'dark'
  if (mode === 'auto') {
    resolved = window.matchMedia('(prefers-color-scheme: dark)').matches ? 'dark' : 'light'
  } else {
    resolved = mode as 'light' | 'dark'
  }
  document.documentElement.setAttribute('data-theme', resolved)

  // 通知 Electron 主进程更新标题栏颜色（浏览器环境下该 API 不存在，静默忽略）
  const api = (window as any).electronAPI
  if (api?.setTheme) {
    api.setTheme(mode)
  }
}

// 背景层样式（背景图 URL / 不透明度 / 模糊，均由 settingsStore 驱动）
const bgStyle = computed(() => ({
  backgroundImage: settingsStore.bgImageUrl ? `url("${settingsStore.bgImageUrl}")` : 'none',
  opacity: settingsStore.bgOpacity,
  filter: settingsStore.bgBlur > 0 ? `blur(${settingsStore.bgBlur}px)` : 'none'
}))

// 应用外观：有背景图时给 <html> 加 has-app-bg（触发面板半透明，让背景图透出），并同步窗口整体透明度
function applyAppearance() {
  document.documentElement.classList.toggle('has-app-bg', !!settingsStore.bgImageUrl)
  const api = (window as any).electronAPI
  if (api?.setWindowOpacity) {
    api.setWindowOpacity(settingsStore.windowOpacity)
  }
}

// 监听外观相关字段变化，实时生效
watch(
  () => [settingsStore.bgImageUrl, settingsStore.bgOpacity, settingsStore.bgBlur, settingsStore.windowOpacity],
  () => applyAppearance()
)

// 初始化主题
onMounted(() => {
  // Electron 桌面端：标记 <html>，供全局样式启用标题栏顶部留白（32px，见 style.css）
  if (isElectron) {
    document.documentElement.classList.add('is-electron')
  }
  applyTheme(settingsStore.getTheme())
  // 应用外观（背景图 / 窗口整体透明度）——启动时恢复上次设置
  applyAppearance()

  // 监听系统主题变化（仅 auto 模式下生效）
  window.matchMedia('(prefers-color-scheme: dark)').addEventListener('change', () => {
    if (settingsStore.getTheme() === 'auto') {
      applyTheme('auto')
    }
  })
})

// 监听 store 中的主题变化
watch(() => settingsStore.theme, (newTheme) => {
  applyTheme(newTheme)
})
</script>

<template>
  <a-config-provider :theme="antTheme">
    <!-- 全局背景层：由「外观设置」上传的窗口背景图（位于内容之下） -->
    <div v-if="settingsStore.bgImageUrl" class="app-background" :style="bgStyle"></div>
    <!-- Electron 桌面端：透明标题栏拖拽区（替代被隐藏的系统标题栏，可拖动窗口；背景透出下层内容） -->
    <div v-if="isElectron" class="electron-titlebar-drag"></div>
    <div id="app" :class="{ 'full-screen': hideLayout }">
      <template v-if="!hideLayout">
        <header>
          <nav>
            <RouterLink to="/code-assistant">首页</RouterLink>
            <RouterLink to="/login">登录</RouterLink>
          </nav>
        </header>
      </template>
      <main>
        <RouterView />
      </main>
    </div>
  </a-config-provider>
</template>

<style>
/* 全局背景层（外观设置：窗口背景图，位于内容之下） */
.app-background {
  position: fixed;
  inset: 0;
  z-index: 0;
  pointer-events: none;
  background-size: cover;
  background-position: center;
  background-repeat: no-repeat;
  transition: opacity 0.2s ease, filter 0.2s ease;
}

/* Electron 桌面端：透明自定义标题栏拖拽区（高 32px，与主进程 titleBarOverlay.height 一致）。
   整条可拖拽窗口；透明背景露出下层（背景图 / 主题底色）；右侧窗口控制按钮由系统覆盖层绘制于其上 */
.electron-titlebar-drag {
  position: fixed;
  top: 0;
  left: 0;
  right: 0;
  height: 32px;
  -webkit-app-region: drag;
  app-region: drag;
  z-index: 100;
}

#app {
  position: relative;
  z-index: 1;
  font-family: Avenir, Helvetica, Arial, sans-serif;
  -webkit-font-smoothing: antialiased;
  -moz-osx-font-smoothing: grayscale;
  text-align: center;
  color: #2c3e50;
}

#app:not(.full-screen) {
  margin-top: 60px;
}

#app.full-screen {
  margin: 0 !important;
  padding: 0 !important;
  width: 100vw !important;
  height: 100vh !important;
  max-width: none !important;
  border: none !important;
  overflow: hidden !important;
  position: relative !important;
}

#app.full-screen main {
  margin: 0 !important;
  padding: 0 !important;
  width: 100% !important;
  height: 100% !important;
  overflow: hidden !important;
}

nav {
  padding: 30px;
}
nav a {
  font-weight: bold;
  color: #2c3e50;
  margin: 0 10px;
}
nav a.router-link-exact-active {
  color: #42b983;
}
</style>
