<template>
  <div class="layout-container">
    <!-- 左侧菜单栏 -->
    <aside class="sidebar" :class="{ collapsed }">
      <div class="sidebar-header">
        <div class="app-brand">
          <img :src="appLogo" class="app-logo" :class="{ collapsed }" alt="CodeCraft" />
          <h2 class="app-title" :class="{ collapsed }">CodeCraft</h2>
        </div>
        <div class="collapse-btn" @click="toggleCollapsed">
          <MenuFoldOutlined v-if="!collapsed" />
          <MenuUnfoldOutlined v-else />
        </div>
      </div>
      <a-menu
        v-model:selectedKeys="selectedKeys"
        mode="inline"
        :inline-collapsed="collapsed"
        @click="handleMenuClick"
        class="app-menu"
      >
        <!-- LINK 菜单 -->
        <a-menu-item v-for="item in menuItems" :key="item.key">
          <template #icon>
            <component :is="item.icon" />
          </template>
          {{ item.label }}
        </a-menu-item>
        <!-- SETTING 菜单（分隔线 + 菜单项） -->
        <template v-if="settingMenuItems.length > 0">
          <a-menu-divider />
          <a-menu-item v-for="item in settingMenuItems" :key="item.key">
            <template #icon>
              <component :is="item.icon" />
            </template>
            {{ item.label }}
          </a-menu-item>
        </template>
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
      </div>
    </aside>

    <!-- 主内容区域 -->
    <main class="main-content">
      <router-view />
    </main>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted, watch } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { message } from 'ant-design-vue'
import { useUserStore } from '@/store/user'
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
  FileTextOutlined
} from '@ant-design/icons-vue'
import type { MenuProps } from 'ant-design-vue'
import appLogo from '@/assets/logo.svg'

const router = useRouter()
const route = useRoute()
const userStore = useUserStore()
const isLoggingOut = ref(false)
const collapsed = ref(false)

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
  FileTextOutlined
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
.layout-container {
  display: flex;
  height: 100vh;
  background-color: #f7f9fc;
}

.sidebar {
  width: 240px;
  background: white;
  border-right: 1px solid #e8e8e8;
  display: flex;
  flex-direction: column;
  height: 100%;
  transition: width 0.25s ease;
  overflow: hidden;
  flex-shrink: 0;
}

.sidebar.collapsed {
  width: 80px;
}

.sidebar-header {
  padding: 20px 16px;
  border-bottom: 1px solid #e8e8e8;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}

.app-title {
  font-size: 20px;
  font-weight: 600;
  color: #1890ff;
  margin: 0;
  white-space: nowrap;
  transition: opacity 0.2s ease;
}

.app-title.collapsed {
  font-size: 14px;
  overflow: hidden;
  text-overflow: clip;
}

.app-brand {
  display: flex;
  align-items: center;
  gap: 10px;
  overflow: hidden;
  flex: 1;
  min-width: 0;
}

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

.collapse-btn {
  cursor: pointer;
  font-size: 18px;
  color: #8c8c8c;
  display: flex;
  align-items: center;
  justify-content: center;
  transition: color 0.2s;
  flex-shrink: 0;
}

.collapse-btn:hover {
  color: #1890ff;
}

.app-menu {
  flex: 1;
  border-right: none;
  padding: 10px 0;
}

.sidebar-footer {
  padding: 16px 12px;
  border-top: 1px solid #e8e8e8;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}

.collapsed .sidebar-footer {
  justify-content: center;
  padding: 16px 4px;
}

.user-info {
  display: flex;
  align-items: center;
  overflow: hidden;
}

.collapsed .user-name {
  display: none;
}

.user-avatar {
  width: 32px;
  height: 32px;
  background: linear-gradient(135deg, #667eea, #764ba2);
  border-radius: 50%;
  display: flex;
  align-items: center;
  justify-content: center;
  color: white;
  font-weight: bold;
  margin-right: 12px;
}

.user-name {
  font-weight: 500;
  color: #262626;
  max-width: 120px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.main-content {
  flex: 1;
  overflow: hidden;
  height: 100vh;
}

/* 响应式设计 */
@media (max-width: 768px) {
  .sidebar {
    width: 100%;
    height: auto;
    border-right: none;
    border-bottom: 1px solid #e8e8e8;
  }

  .layout-container {
    flex-direction: column;
  }

  .main-content {
    height: calc(100vh - 60px);
  }
}
</style>