<template>
  <div class="layout-container">
    <!-- 左侧菜单栏 -->
    <aside class="sidebar" :class="{ collapsed }">
      <div class="sidebar-header">
        <h2 class="app-title" :class="{ collapsed }">zb-agent</h2>
        <div class="collapse-btn" @click="toggleCollapsed">
          <MenuFoldOutlined v-if="!collapsed" />
          <MenuUnfoldOutlined v-else />
        </div>
      </div>
      <a-menu
        v-model:selectedKeys="selectedKeys"
        mode="inline"
        :items="menuItems"
        :inline-collapsed="collapsed"
        @click="handleMenuClick"
        class="app-menu"
      />
      <div class="sidebar-footer">
        <div class="user-info">
          <div class="user-avatar">
            {{ userStore.userInfo?.username?.charAt(0).toUpperCase() || 'U' }}
          </div>
          <div class="user-name">{{ userStore.userInfo?.username || '用户' }}</div>
        </div>
        <a-button type="text" :loading="isLoggingOut" @click="handleLogout">
          <logout-outlined />
        </a-button>
      </div>
    </aside>

    <!-- 主内容区域 -->
    <main class="main-content">
      <router-view />
    </main>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted, watch, h } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { message } from 'ant-design-vue'
import { useUserStore } from '@/store/user'
import { logout } from '@/api/user'
import {
  RobotOutlined,
  MessageOutlined,
  LineChartOutlined,
  CodeOutlined,
  LogoutOutlined,
  MenuFoldOutlined,
  MenuUnfoldOutlined
} from '@ant-design/icons-vue'
import type { MenuProps } from 'ant-design-vue'

const router = useRouter()
const route = useRoute()
const userStore = useUserStore()
const isLoggingOut = ref(false)
const collapsed = ref(false)

const toggleCollapsed = () => {
  collapsed.value = !collapsed.value
}

// 菜单项定义
const menuItems = computed(() => [
  {
    key: '/ai-assistant',
    icon: () => h(RobotOutlined),
    label: 'AI助手'
  },
  {
    key: '/chat-assistant',
    icon: () => h(MessageOutlined),
    label: '聊天助手'
  },
  {
    key: '/stock-assistant',
    icon: () => h(LineChartOutlined),
    label: '股票助手'
  },
  {
    key: '/code-assistant',
    icon: () => h(CodeOutlined),
    label: '编码助手'
  }
])

// 当前选中的菜单键，根据路由路径自动匹配
const selectedKeys = ref<string[]>([route.path])

// 监听路由变化，更新选中状态
watch(() => route.path, (newPath) => {
  selectedKeys.value = [newPath]
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
  // 确保默认选中AI助手
  if (route.path === '/') {
    router.push('/ai-assistant')
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