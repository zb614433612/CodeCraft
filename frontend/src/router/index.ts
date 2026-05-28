import { createRouter, createWebHistory, type RouteLocationNormalized } from 'vue-router'
import { useUserStore } from '@/store/user'
import { message } from 'ant-design-vue'
import Layout from '@/layouts/Layout.vue'

const router = createRouter({
  history: createWebHistory(import.meta.env.BASE_URL),
  routes: [
    {
      path: '/',
      redirect: '/code-assistant'
    },
    {
      path: '/login',
      name: 'login',
      component: () => import('@/views/LoginView.vue'),
      meta: { hideLayout: true, requiresGuest: true } // 仅允许未登录用户访问
    },
    {
      path: '/',
      component: Layout,
      meta: { requiresAuth: true, hideLayout: true },
      children: [

        {
          path: 'code-assistant',
          name: 'code-assistant',
          component: () => import('@/views/CodeAssistantView.vue')
        },
        {
          path: 'user-management',
          name: 'user-management',
          component: () => import('@/views/UserManageView.vue'),
          meta: { requiresAdmin: true }
        },
        {
          path: 'profile',
          name: 'profile',
          component: () => import('@/views/ProfileView.vue')
        },
        {
          path: 'menu-permission',
          name: 'menu-permission',
          component: () => import('@/views/MenuPermissionView.vue'),
          meta: { requiresAdmin: true }
        },
        {
          path: 'config',
          name: 'config',
          component: () => import('@/views/ConfigView.vue')
        },
        {
          path: 'logs',
          name: 'logs',
          component: () => import('@/views/LogView.vue')
        },
        {
          path: 'schedule-tasks',
          name: 'schedule-tasks',
          component: () => import('@/views/ScheduleTaskView.vue'),
          meta: { requiresAdmin: true }
        },
        {
          path: 'skill-manage',
          name: 'skill-manage',
          component: () => import('@/views/SkillManageView.vue')
        },
        {
          path: 'agent-config',
          name: 'agent-config',
          component: () => import('@/views/AgentConfigView.vue')
        },
        {
          path: 'p2p',
          name: 'p2p',
          component: () => import('@/components/P2pPanel.vue')
        },

      ]
    }
  ]
})

// 路由守卫
router.beforeEach((to: RouteLocationNormalized) => {
  const userStore = useUserStore()
  const isLoggedIn = userStore.isLoggedIn

  // 检查路由是否需要认证
  if (to.meta.requiresAuth && !isLoggedIn) {
    // 重定向到登录页面，并保存目标路由用于登录后跳转
    return {
      path: '/login',
      query: { redirect: to.fullPath }
    }
  }

  // 检查路由是否仅允许未登录用户访问
  if (to.meta.requiresGuest && isLoggedIn) {
    // 已登录用户访问登录页面，重定向到首页
      return { path: '/code-assistant' }
  }

  // 检查路由是否需要管理员权限
  if (to.meta.requiresAdmin) {
    if (!userStore.isAdmin) {
      message.warning('权限不足，需要管理员权限')
      return { path: '/code-assistant' }
    }
  }
})

export default router