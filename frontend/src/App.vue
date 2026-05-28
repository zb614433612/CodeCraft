<script setup lang="ts">
import { RouterLink, RouterView, useRoute } from 'vue-router'
import { computed, watch, onMounted } from 'vue'
import { useSettingsStore } from '@/store/settings'
import { theme } from 'ant-design-vue'

const route = useRoute()
const hideLayout = computed(() => route.meta.hideLayout || false)

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

// 应用主题到 <html> 元素
function applyTheme(mode: string) {
  let resolved: 'light' | 'dark'
  if (mode === 'auto') {
    resolved = window.matchMedia('(prefers-color-scheme: dark)').matches ? 'dark' : 'light'
  } else {
    resolved = mode as 'light' | 'dark'
  }
  document.documentElement.setAttribute('data-theme', resolved)
}

// 初始化主题
onMounted(() => {
  applyTheme(settingsStore.getTheme())

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
#app {
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
