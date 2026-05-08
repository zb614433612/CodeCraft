import { createRouter, createWebHistory, type RouteLocationNormalized } from 'vue-router'
import { useUserStore } from '@/store/user'
import Layout from '@/layouts/Layout.vue'

const router = createRouter({
  history: createWebHistory(import.meta.env.BASE_URL),
  routes: [
    {
      path: '/',
      redirect: '/ai-assistant'
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
          path: 'ai-assistant',
          name: 'ai-assistant',
          component: () => import('@/views/HomeView.vue')
        },
        {
          path: 'chat-assistant',
          name: 'chat-assistant',
          component: () => import('@/views/ChatAssistantView.vue')
        },
        {
          path: 'stock-assistant',
          name: 'stock-assistant',
          component: () => import('@/views/StockAssistantView.vue')
        },
        {
          path: 'code-assistant',
          name: 'code-assistant',
          component: () => import('@/views/CodeAssistantView.vue')
        }
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
    return { path: '/ai-assistant' }
  }
})

export default router