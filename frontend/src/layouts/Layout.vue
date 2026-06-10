<template>
  <div class="layout-container">
    <!-- 左侧菜单栏 -->
    <aside class="sidebar" :class="{ collapsed }">
      <div class="sidebar-header">
        <div class="app-brand">
          <img :src="appLogo" class="app-logo" :class="{ collapsed }" alt="CodeCraft" />
          <h2 class="app-title" :class="{ collapsed }">CodeCraft</h2>
        </div>
        <div class="header-actions">
          <div class="collapse-btn" @click="toggleCollapsed">
            <MenuFoldOutlined v-if="!collapsed" />
            <MenuUnfoldOutlined v-else />
          </div>
        </div>
      </div>
      <a-menu
        v-model:selectedKeys="selectedKeys"
        mode="inline"
        :inline-collapsed="collapsed"
        @click="handleMenuClick"
        class="app-menu"
      >
        <!-- 聊天 菜单组 -->
        <a-menu-item-group key="chat" title="聊天">
          <a-menu-item v-for="item in menuItems" :key="item.key" :title="item.label">
            <template #icon>
              <component :is="item.icon" />
            </template>
            {{ item.label }}
          </a-menu-item>
        </a-menu-item-group>
        <!-- 设置 菜单组 -->
        <a-menu-item-group v-if="settingMenuItems.length > 0" key="settings" title="设置">
          <a-menu-item v-for="item in settingMenuItems" :key="item.key" :title="item.label">
            <template #icon>
              <component :is="item.icon" />
            </template>
            {{ item.label }}
          </a-menu-item>
        </a-menu-item-group>
      </a-menu>
      <div class="sidebar-footer">
        <a-dropdown :trigger="['click']" placement="top">
          <div class="user-info" style="cursor: pointer; flex: 1;">
            <div class="user-avatar">
              {{ userStore.userInfo?.username?.charAt(0).toUpperCase() || 'U' }}
            </div>
            <div class="user-name">{{ userStore.userInfo?.username || '用户' }}</div>
          </div>
          <template #overlay>
            <a-menu>
              <!-- 管理页面（MANAGE 类型，动态加载，由角色菜单权限控制） -->
              <a-menu-item
                v-for="item in manageMenuItems"
                :key="item.key"
                @click="router.push(item.key)"
              >
                <component :is="item.icon" v-if="item.icon" />
                {{ item.label }}
              </a-menu-item>
              <a-menu-divider />
              <a-menu-item key="logout" :disabled="isLoggingOut" @click="handleLogout">
                <LogoutOutlined /> 注销登录
              </a-menu-item>
            </a-menu>
          </template>
        </a-dropdown>
        <!-- 主题切换 -->
        <div class="theme-toggle-footer" @click="toggleTheme" :title="themeTooltip">
          <span class="theme-emoji-footer">{{ resolvedTheme === 'dark' ? '☀️' : '🌙' }}</span>
        </div>
      </div>
    </aside>

    <!-- 主内容区域 -->
    <main class="main-content">
      <router-view />
    </main>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted, watch, computed } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { message } from 'ant-design-vue'
import { useUserStore } from '@/store/user'
import { useSettingsStore } from '@/store/settings'
import { logout } from '@/api/user'
import { getUserMenus } from '@/api/menu'
import {
  CodeOutlined,
  LogoutOutlined,
  MenuFoldOutlined,
  MenuUnfoldOutlined,
  RobotOutlined,
  MessageOutlined,
  LineChartOutlined,
  UserOutlined,
  FormOutlined,
  SafetyOutlined,
  SettingOutlined,
  ClockCircleOutlined,
  FileTextOutlined,
  LinkOutlined,
  ToolOutlined
} from '@ant-design/icons-vue'
import type { MenuProps } from 'ant-design-vue'
import appLogo from '@/assets/logo.svg'

const router = useRouter()
const route = useRoute()
const userStore = useUserStore()
const settingsStore = useSettingsStore()
const isLoggingOut = ref(false)
const collapsed = ref(false)

// 主题切换
const resolvedTheme = computed<'light' | 'dark'>(() => {
  const mode = settingsStore.getTheme()
  if (mode === 'auto') {
    return window.matchMedia('(prefers-color-scheme: dark)').matches ? 'dark' : 'light'
  }
  return mode as 'light' | 'dark'
})

const themeTooltip = computed(() => {
  return resolvedTheme.value === 'dark' ? '切换到亮色模式' : '切换到暗色模式'
})

function toggleTheme() {
  const current = settingsStore.getTheme()
  // 解析当前实际显示的主题（auto 时按系统偏好决定）
  const resolved: 'light' | 'dark' = current === 'auto'
    ? window.matchMedia('(prefers-color-scheme: dark)').matches ? 'dark' : 'light'
    : current as 'light' | 'dark'
  // 直接切换到相反模式，跳过 auto 循环
  settingsStore.setTheme(resolved === 'dark' ? 'light' : 'dark')
}

const toggleCollapsed = () => {
  collapsed.value = !collapsed.value
}

// 菜单项定义（动态加载）
const menuItems = ref<any[]>([])
const manageMenuItems = ref<any[]>([])
const settingMenuItems = ref<any[]>([])

// 图标名称到组件的映射
const iconMap: Record<string, any> = {
  RobotOutlined, MessageOutlined, LineChartOutlined,
  CodeOutlined, SettingOutlined, UserOutlined,
  SafetyOutlined, FormOutlined, ClockCircleOutlined,
  FileTextOutlined, LinkOutlined, ToolOutlined
}

// 加载左侧菜单和底部管理菜单
const loadMenus = async () => {
  try {
    // 加载左侧 LINK 菜单
    const linkRes = await getUserMenus('LINK')
    if (linkRes.code === 200 && linkRes.data) {
      menuItems.value = linkRes.data.map(m => ({
        key: m.path,
        icon: iconMap[m.icon as string] || RobotOutlined,
        label: m.name
      }))
    }
    // 加载左侧 SETTING 菜单
    const settingRes = await getUserMenus('SETTING')
    if (settingRes.code === 200 && settingRes.data) {
      settingMenuItems.value = settingRes.data.map(m => ({
        key: m.path,
        icon: iconMap[m.icon as string] || RobotOutlined,
        label: m.name
      }))
    }
    // 加载底部 MANAGE 菜单
    const manageRes = await getUserMenus('MANAGE')
    if (manageRes.code === 200 && manageRes.data) {
      manageMenuItems.value = manageRes.data.map(m => ({
        key: m.path,
        icon: iconMap[m.icon as string] || null,
        label: m.name
      }))
    }
  } catch (e) {
    console.error('加载菜单失败', e)
    menuItems.value = []
    manageMenuItems.value = []
    settingMenuItems.value = []
  }
}

// 当前选中的菜单键，根据路由路径自动匹配
const selectedKeys = ref<string[]>([route.path])

// 监听路由变化，更新选中状态
watch(() => route.path, (newPath, oldPath) => {
  selectedKeys.value = [newPath]
  // 从菜单权限管理页面离开时重新加载菜单（权限可能已变更）
  if (oldPath === '/menu-permission') {
    loadMenus()
  }
})

// 菜单点击处理
const handleMenuClick: MenuProps['onClick'] = ({ key }) => {
  router.push(key as string)
}

// 退出登录
const handleLogout = async () => {
  if (isLoggingOut.value) return

  isLoggingOut.value = true
  try {
    const response = await logout()
    if (response.code === 200) {
      message.success('已退出登录')
    } else {
      console.warn('注销接口返回非200状态码:', response.code, response.message)
      message.info('已退出本地登录')
    }
  } catch (error: any) {
    console.error('调用注销接口失败:', error)
    message.info('已退出本地登录')
  } finally {
    userStore.clearUserInfo()
    router.push('/login')
    isLoggingOut.value = false
  }
}

onMounted(() => {
  // 加载左侧菜单
  loadMenus()
  // 确保默认选中AI助手
  if (route.path === '/') {
    router.push('/code-assistant')
  }
})
</script>

<style scoped>
/* ============================================================
   Layout Sidebar v2 — 现代侧边栏 · Bento卡片 · 暗色增强 · 微交互
   设计语言对齐 P2P Panel v3
   ============================================================ */

/* ---------- CSS 变量 ---------- */
.layout-container {
  --accent: #8b5cf6;
  --accent-lt: rgba(139, 92, 246, 0.08);
  --accent-md: rgba(139, 92, 246, 0.18);
  --accent-dk: #7c3aed;
  --accent-glow: rgba(139, 92, 246, 0.3);

  --bg-root: #f5f3fa;
  --bg-card: #ffffff;
  --bg-hover: rgba(139, 92, 246, 0.04);
  --text-1: #1a1a2e;
  --text-2: #5c5c78;
  --text-3: #9696aa;
  --text-4: #b8b8c8;
  --border: #e8e5f0;
  --shadow-sm: 0 1px 3px rgba(0, 0, 0, 0.04);
  --shadow-md: 0 4px 12px rgba(0, 0, 0, 0.06);
  --shadow-lg: 0 8px 30px rgba(0, 0, 0, 0.08);
  --green: #10b981;
  --radius: 14px;
  --radius-sm: 10px;
  --radius-xs: 6px;

  display: flex;
  height: 100vh;
  background-color: var(--bg-root);
  font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', 'PingFang SC', 'Microsoft YaHei', sans-serif;
}

/* ============ 侧边栏容器 ============ */
.sidebar {
  width: 260px;
  background: var(--bg-card);
  border-right: 1px solid var(--border);
  display: flex;
  flex-direction: column;
  height: 100%;
  transition: width 0.3s cubic-bezier(0.16, 1, 0.3, 1);
  overflow: hidden;
  flex-shrink: 0;
  box-shadow: var(--shadow-sm);
  position: relative;
  z-index: 10;
}

.sidebar.collapsed {
  width: 80px;
}

/* ============ 侧边栏头部 ============ */
.sidebar-header {
  padding: 18px 16px;
  border-bottom: 1px solid var(--border);
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  flex-shrink: 0;
}

.header-actions {
  display: flex;
  align-items: center;
  gap: 4px;
  flex-shrink: 0;
}

.app-brand {
  display: flex;
  align-items: center;
  gap: 10px;
  overflow: hidden;
  flex: 1;
  min-width: 0;
}

/* Logo 图标 */
.app-logo {
  width: 28px;
  height: 28px;
  flex-shrink: 0;
  border-radius: 6px;
  transition: width 0.2s ease, height 0.2s ease;
}

.app-logo.collapsed {
  width: 24px;
  height: 24px;
}

/* 应用标题 */
.app-title {
  font-size: 18px;
  font-weight: 700;
  color: var(--text-1);
  margin: 0;
  white-space: nowrap;
  transition: opacity 0.25s ease, width 0.25s ease;
  letter-spacing: -0.2px;
  overflow: hidden;
}

.app-title.collapsed {
  opacity: 0;
  width: 0;
  margin: 0;
}

/* 折叠时 logo 也隐藏 */
.app-logo.collapsed {
  opacity: 0;
  width: 0;
  margin: 0;
  transition: opacity 0.25s ease, width 0.25s ease;
}

/* 折叠时 brand 区域缩小为 0 */
.sidebar.collapsed .app-brand {
  flex: 0;
  gap: 0;
  overflow: hidden;
}

/* 折叠按钮 */
.collapse-btn {
  cursor: pointer;
  font-size: 16px;
  color: var(--text-3);
  display: flex;
  align-items: center;
  justify-content: center;
  width: 32px;
  height: 32px;
  border-radius: var(--radius-xs);
  transition: all 0.2s ease;
  flex-shrink: 0;
}

.collapse-btn:hover {
  color: var(--accent);
  background: var(--accent-lt);
}

/* ============ 菜单区域 ============ */
.app-menu {
  flex: 1;
  border-right: none;
  padding: 8px 10px;
  overflow-y: auto;
  overflow-x: hidden;
}

/* 自定义滚动条 */
.app-menu::-webkit-scrollbar {
  width: 4px;
}

.app-menu::-webkit-scrollbar-track {
  background: transparent;
}

.app-menu::-webkit-scrollbar-thumb {
  background: #dcd8ea;
  border-radius: 2px;
}

.app-menu::-webkit-scrollbar-thumb:hover {
  background: #c4bce0;
}

/* ============ 菜单分组标题 ============ */
:deep(.ant-menu-item-group-title) {
  text-align: left !important;
  padding: 8px 16px 4px !important;
  font-size: 11px !important;
  font-weight: 700 !important;
  text-transform: uppercase;
  letter-spacing: 0.8px;
  color: var(--text-3) !important;
  transition: all 0.25s ease;
}

/* ============ 菜单项 ============ */
/* 重置 ant-design-vue 菜单样式 */
:deep(.ant-menu) {
  background: transparent;
  color: var(--text-2);
  border-right: none;
}

:deep(.ant-menu:not(.ant-menu-horizontal) .ant-menu-item-selected) {
  background: linear-gradient(135deg, var(--accent-lt), rgba(139, 92, 246, 0.12));
  color: var(--accent-dk);
}

:deep(.ant-menu-item) {
  margin: 2px 0 !important;
  border-radius: var(--radius-sm) !important;
  padding: 0 14px !important;
  height: 42px !important;
  line-height: 42px !important;
  font-size: 13.5px;
  font-weight: 500;
  color: var(--text-2) !important;
  transition: all 0.2s cubic-bezier(0.16, 1, 0.3, 1) !important;
  position: relative;
  border: 1px solid transparent;
  width: auto !important;
}

/* 左侧指示条（对齐 P2P peer-card） */
:deep(.ant-menu-item)::before {
  content: '';
  position: absolute;
  left: 4px;
  top: 10px;
  bottom: 10px;
  width: 3px;
  border-radius: 0 3px 3px 0;
  background: transparent;
  transition: background 0.2s ease;
}

:deep(.ant-menu-item:hover) {
  background: var(--bg-hover) !important;
  border-color: var(--accent-md);
  color: var(--text-1) !important;
  box-shadow: var(--shadow-sm);
}

:deep(.ant-menu-item:hover)::before {
  background: var(--accent);
}

:deep(.ant-menu-item-selected) {
  background: linear-gradient(135deg, var(--accent-lt), rgba(99, 102, 241, 0.06)) !important;
  border-color: var(--accent-md) !important;
  color: var(--accent-dk) !important;
  font-weight: 600 !important;
  box-shadow: var(--shadow-md);
}

:deep(.ant-menu-item-selected)::before {
  background: var(--accent);
}

/* 菜单项图标 */
:deep(.ant-menu-item .anticon) {
  font-size: 17px;
  transition: all 0.2s ease;
}

:deep(.ant-menu-item-selected .anticon) {
  color: var(--accent);
}

/* ============ 折叠状态下的菜单 ============ */
/* 折叠菜单样式统一由下方非 scoped 全局 <style> 块管理，确保覆盖 Ant Design 内置样式 */

/* ============ 底部用户区域 ============ */
.sidebar-footer {
  padding: 12px;
  border-top: 1px solid var(--border);
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  flex-shrink: 0;
  background: var(--bg-card);
}

.sidebar.collapsed .sidebar-footer {
  justify-content: center;
  padding: 12px 4px;
}

/* 底部主题切换按钮 */
.theme-toggle-footer {
  cursor: pointer;
  width: 34px;
  height: 34px;
  border-radius: 10px;
  display: flex;
  align-items: center;
  justify-content: center;
  transition: all 0.2s ease;
  color: var(--text-3);
  flex-shrink: 0;
}

.theme-toggle-footer:hover {
  color: var(--accent);
  background: var(--accent-lt);
}

.theme-emoji-footer {
  font-size: 18px;
  line-height: 1;
}

/* 折叠时隐藏主题按钮 */
.sidebar.collapsed .theme-toggle-footer {
  display: none;
}

.user-info {
  display: flex;
  align-items: center;
  overflow: hidden;
  border-radius: var(--radius-sm);
  padding: 6px 10px;
  transition: all 0.2s ease;
  border: 1px solid transparent;
}

.user-info:hover {
  background: var(--bg-hover);
  border-color: var(--border);
}

.sidebar.collapsed .user-info {
  padding: 6px;
  justify-content: center;
}

.sidebar.collapsed .user-name {
  display: none;
}

/* 用户头像 */
.user-avatar {
  width: 34px;
  height: 34px;
  background: linear-gradient(135deg, #8b5cf6, #7c3aed);
  border-radius: 10px;
  display: flex;
  align-items: center;
  justify-content: center;
  color: white;
  font-weight: 700;
  font-size: 14px;
  margin-right: 10px;
  flex-shrink: 0;
  box-shadow: 0 2px 8px rgba(139, 92, 246, 0.3);
  transition: all 0.2s ease;
}

.user-info:hover .user-avatar {
  transform: scale(1.05);
  box-shadow: 0 3px 12px rgba(139, 92, 246, 0.4);
}

.sidebar.collapsed .user-avatar {
  margin-right: 0;
}

/* 用户名 */
.user-name {
  font-weight: 600;
  font-size: 13px;
  color: var(--text-1);
  max-width: 140px;
  flex: 1;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  text-align: left;
}

/* ============ 主内容区域 ============ */
.main-content {
  flex: 1;
  overflow-y: auto !important;
  min-height: 0;
  background: var(--bg-root);
}

/* ============ 暗色模式 ============ */
[data-theme="dark"] .layout-container {
  --bg-root: #121117;
  --bg-card: #1a1925;
  --bg-hover: rgba(139, 92, 246, 0.06);
  --text-1: #e4e2f0;
  --text-2: #a09eb8;
  --text-3: #6a6880;
  --text-4: #525070;
  --border: #2a2838;
  --shadow-sm: 0 1px 3px rgba(0, 0, 0, 0.3);
  --shadow-md: 0 4px 12px rgba(0, 0, 0, 0.4);
  --shadow-lg: 0 8px 30px rgba(0, 0, 0, 0.5);
  --accent-glow: rgba(139, 92, 246, 0.25);
}

[data-theme="dark"] .sidebar {
  box-shadow: 1px 0 12px rgba(0, 0, 0, 0.3);
}

[data-theme="dark"] .app-menu::-webkit-scrollbar-thumb {
  background: #3a3850;
}

[data-theme="dark"] .app-menu::-webkit-scrollbar-thumb:hover {
  background: #4a4860;
}

[data-theme="dark"] :deep(.ant-menu-item) {
  color: var(--text-2) !important;
}

[data-theme="dark"] :deep(.ant-menu-item:hover) {
  background: rgba(255, 255, 255, 0.03) !important;
}

[data-theme="dark"] :deep(.ant-menu-item-selected) {
  background: linear-gradient(135deg, rgba(139, 92, 246, 0.15), rgba(99, 102, 241, 0.08)) !important;
  color: #c4b5fd !important;
}

[data-theme="dark"] :deep(.ant-menu-item-selected .anticon) {
  color: #a78bfa;
}

/* ============ 响应式设计 ============ */
@media (max-width: 768px) {
  .sidebar {
    width: 100%;
    height: auto;
    border-right: none;
    border-bottom: 1px solid var(--border);
    box-shadow: var(--shadow-md);
  }

  .sidebar.collapsed {
    width: 100%;
  }

  .layout-container {
    flex-direction: column;
  }

  .main-content {
    flex: 1;
    overflow-y: auto !important;
    min-height: 0;
  }

  .sidebar-header {
    padding: 12px 16px;
  }
}

/* ============ 过渡动效 ============ */
/* 菜单项滑入动画 */
:deep(.ant-menu-item) {
  animation: menuItemFadeIn 0.3s cubic-bezier(0.16, 1, 0.3, 1);
}

@keyframes menuItemFadeIn {
  from {
    opacity: 0;
    transform: translateX(-8px);
  }
  to {
    opacity: 1;
    transform: translateX(0);
  }
}
</style>

<!-- 折叠菜单全局样式（非 scoped，选择器优先级匹配 Ant Design 内置样式） -->
<style>
/* 折叠：菜单项居中 + 卡片化（4级选择器匹配 Ant Design 深度） */
.ant-menu.ant-menu-inline-collapsed .ant-menu-item-group .ant-menu-item-group-list .ant-menu-item,
.ant-menu.ant-menu-inline-collapsed > .ant-menu-item {
  width: 52px !important;
  margin: 4px auto !important;
  height: 44px !important;
  line-height: 44px !important;
  border-radius: 10px !important;
  padding: 0 !important;
  display: flex !important;
  align-items: center !important;
  justify-content: center !important;
  text-align: center !important;
  overflow: visible !important;
  border-color: transparent !important;
}

/* 折叠：图标放大 */
.ant-menu.ant-menu-inline-collapsed .ant-menu-item .anticon {
  font-size: 20px !important;
  margin: 0 !important;
}

/* 折叠：隐藏文字 */
.ant-menu.ant-menu-inline-collapsed .ant-menu-item .ant-menu-title-content {
  display: none !important;
}

/* 折叠：隐藏指示条（:before 伪元素） */
.ant-menu.ant-menu-inline-collapsed .ant-menu-item::before {
  display: none !important;
  content: none !important;
}

/* 折叠：隐藏分组标题 */
.ant-menu.ant-menu-inline-collapsed .ant-menu-item-group-title {
  opacity: 0 !important;
  height: 0 !important;
  min-height: 0 !important;
  padding: 0 !important;
  margin: 0 !important;
  overflow: hidden !important;
  border: none !important;
}

/* 折叠：选中项紫色实心渐变 + 白色图标 */
.ant-menu.ant-menu-inline-collapsed .ant-menu-item.ant-menu-item-selected {
  background: linear-gradient(135deg, #8b5cf6, #7c3aed) !important;
  box-shadow: 0 4px 14px rgba(139, 92, 246, 0.35) !important;
  border-color: transparent !important;
}

.ant-menu.ant-menu-inline-collapsed .ant-menu-item.ant-menu-item-selected .anticon {
  color: #fff !important;
}

/* 折叠：分组间细线分隔 */
.ant-menu.ant-menu-inline-collapsed .ant-menu-item-group:not(:last-child)::after {
  content: '';
  display: block;
  width: 24px;
  height: 1px;
  background: #e8e5f0;
  margin: 8px auto;
  border-radius: 1px;
}

/* 暗色模式 - 折叠菜单 */
[data-theme="dark"] .ant-menu.ant-menu-inline-collapsed .ant-menu-item-group:not(:last-child)::after {
  background: #2a2838;
}
[data-theme="dark"] .ant-menu.ant-menu-inline-collapsed .ant-menu-item.ant-menu-item-selected {
  box-shadow: 0 4px 16px rgba(139, 92, 246, 0.5);
}
</style>
