/**
 * 路由（v3 · 登录页版）
 * ----------------------------------------------------------------------------
 * 约定：
 *  1) /chat 为真实聊天页，其余业务路径先挂占位组件；
 *  2) 一级导航与「更多」面板的所有 key 都从 menu.config.ts 自动生成路由，
 *     新增页面只需在 menu.config.ts 里加一项 + 在下方 ROUTE_COMPONENTS 里登记组件；
 *  3) 使用 hash 模式，桌面端（Electron file:// 场景）无需额外配置。
 *
 * <h3>权限模型</h3>
 * <ul>
 *   <li><b>两层判定</b>：第一层「有没有 token」（{@code hasToken()}，同步）；
 *       第二层「是不是管理员」（{@code meta.adminOnly} + {@code ensureIdentity()}）；</li>
 *   <li><b>未登录一律跳 /login</b>：并把原地址塞进 {@code ?redirect=}，登录成功后原路返回；</li>
 *   <li><b>已登录禁入 /login</b>：手动输地址回到登录页会直接弹回首页，避免「看起来像掉线」；</li>
 *   <li><b>meta.adminOnly 唯一开关</b>：由 menu.config.ts 的 {@code MenuItem.adminOnly} 推导，
 *       菜单过滤与路由拦截共用同一份配置，避免两处漂移；</li>
 *   <li><b>后端不可用时放行</b>：拿不到身份（后端未启动）不阻断导航，页面自身展示错误态 ——
 *       否则整个应用会因一个 401 彻底不可用。</li>
 * </ul>
 *
 * <h3>401 的统一出口</h3>
 * <p>http-client 不认识 router（router → session → http-client 会成环），因此它只在
 * token 失效时调用 {@link setUnauthorizedHandler} 注册的回调，由本文件负责清会话 + 跳登录页。</p>
 */
import { createRouter, createWebHashHistory, type RouteRecordRaw } from 'vue-router'
import { PRIMARY_NAV, MORE_GROUPS } from '../menu.config'
import { clearSession, ensureIdentity, peekIdentity } from '../utils/session'
import { hasToken, setUnauthorizedHandler } from '../utils/http-client'
import { toast } from '../utils/toast'

/** 已实现的页面组件登记表 */
const ROUTE_COMPONENTS: Record<string, () => Promise<unknown>> = {
  '/chat': () => import('../views/ChatView.vue'),
  '/agents': () => import('../views/AgentListView.vue'),
  '/skills': () => import('../views/SkillListView.vue'),
  '/mcp': () => import('../views/McpListView.vue'),
  '/tasks/schedule': () => import('../views/ScheduleTaskListView.vue'),
  '/lessons': () => import('../views/LessonListView.vue'),
  '/databases': () => import('../views/DbConnectionListView.vue'),
  '/files': () => import('../views/FileListView.vue'),
  '/p2p': () => import('../views/P2pListView.vue'),
  '/providers': () => import('../views/ProviderListView.vue'),
  '/logs': () => import('../views/LogListView.vue'),
  '/data': () => import('../views/DataManageView.vue'),
  '/users': () => import('../views/UserListView.vue'),
  '/menu-permission': () => import('../views/MenuPermissionView.vue'),
  '/appearance': () => import('../views/AppearanceView.vue'),
  '/settings': () => import('../views/SettingsView.vue'),
  '/profile': () => import('../views/ProfileView.vue'),
  '/about': () => import('../views/AboutView.vue')
}

/**
 * 重复入口别名：与一级导航指向同一实现，避免出现两个「同一个页面」。
 * （menu.config 的「更多」面板保留这些文案入口，点击后落到真实页面）
 */
const ROUTE_REDIRECTS: Record<string, string> = {
  '/agents/config': '/agents',
  '/skills/manage': '/skills'
}

/** 应用名（document.title 后缀） */
const APP_NAME = 'CodeCraft'

/** 首页（无权访问 / 登录成功后的默认落地页） */
const HOME_PATH = '/chat'

/** 登录页路径 */
const LOGIN_PATH = '/login'

/** 占位组件：未实现页面统一展示 */
const Placeholder = {
  name: 'PlaceholderView',
  template: `<div style="padding:48px; color: var(--text-3); font-size: 13px;">该页面待实现（先定结构，后接功能）</div>`
}

/** 由菜单配置生成全部业务路由 */
function buildRoutes(): RouteRecordRaw[] {
  const menuItems = [...PRIMARY_NAV, ...MORE_GROUPS.flatMap(g => g.items)]
  /** 同一个 key 可能在「一级导航」与「更多」里各出现一次，这里去重 */
  const uniqueItems = Array.from(new Map(menuItems.map(item => [item.key, item])).values())

  const routes: RouteRecordRaw[] = uniqueItems.map(item => {
    const redirect = ROUTE_REDIRECTS[item.key]
    /** 权限开关与菜单同源：MenuItem.adminOnly → meta.adminOnly */
    const meta = { title: item.label, adminOnly: Boolean(item.adminOnly) }
    if (redirect) {
      return { path: item.key, redirect, meta } as RouteRecordRaw
    }
    return {
      path: item.key,
      component: ROUTE_COMPONENTS[item.key] || Placeholder,
      meta
    } as RouteRecordRaw
  })

  return [
    // 登录页不写进 menu.config：它是守卫的落地页，不是可导航的业务页面
    { path: LOGIN_PATH, component: () => import('../views/LoginView.vue'), meta: { title: '登录' } },
    { path: '/', redirect: HOME_PATH },
    ...routes,
    // 兜底：未知路径回聊天页（未登录时会被守卫再转到登录页）
    { path: '/:pathMatch(.*)*', redirect: HOME_PATH }
  ]
}

const router = createRouter({
  history: createWebHashHistory(),
  routes: buildRoutes()
})

/**
 * 登录态与权限守卫。
 *
 * <p>三层顺序不能换：先放行/拦截登录页，再判 token，最后判角色 ——
 * 反过来会让「未登录访问 admin 页」先打一次 /user/current 拿到 401。</p>
 */
router.beforeEach(async (to) => {
  // 1) 登录页：已登录就不该再看到它
  if (to.path === LOGIN_PATH) {
    return hasToken() ? { path: HOME_PATH } : true
  }

  // 2) 未登录：任何业务页面都挡回登录页，并记住原地址（登录后原路返回）
  if (!hasToken()) {
    const redirect = to.fullPath && to.fullPath !== '/' ? to.fullPath : ''
    return { path: LOGIN_PATH, query: redirect ? { redirect } : undefined }
  }

  // 3) 角色权限：只拦截 meta.adminOnly
  if (to.meta.adminOnly !== true) return true

  // 3.1) 本地快照命中：直接判定（零网络开销）
  try {
    const cached = peekIdentity()
    if (cached) {
      if (cached.role === 'admin') return true
      toast.warning('权限不足，需要管理员权限')
      return { path: HOME_PATH }
    }
  } catch {
    // pinia 尚未激活（极端时序）时跳过快照，交给下面的异步拉取
  }

  // 3.2) 快照缺失：拉一次身份（内部有并发去重与失败冷却）
  const identity = await ensureIdentity()
  if (!identity) {
    // 后端不可用 / 身份未知：放行让页面展示自身错误态，避免整站不可用
    return true
  }
  if (identity.role === 'admin') return true

  toast.warning('权限不足，需要管理员权限')
  return { path: HOME_PATH }
})

/**
 * token 失效统一出口（由 http-client 在 401 时触发）。
 *
 * <p>只做两件事：清会话、带原地址回登录页。放在这里而不是 http-client 内部，
 * 是因为 http-client 引入 router 会形成 router → session → http-client → router 的循环依赖。</p>
 *
 * <p><b>为什么要 catch + 改 hash 兜底</b>：`router.replace` 返回 Promise，
 * 并发导航（例如同时有别的 push / 用户点菜单）会打断本次跳转，导航被打断后
 * 用户仍停在业务页 —— 观感就是「提示登录过期，但没退出」。这里在失败时直接改
 * hash（hash 路由下等价于一次导航），不依赖 router 的导航队列。</p>
 */
setUnauthorizedHandler(() => {
  clearSession()
  const current = router.currentRoute.value
  if (current.path === LOGIN_PATH) return
  const redirect = current.fullPath && current.fullPath !== '/' ? current.fullPath : ''
  const target = { path: LOGIN_PATH, query: redirect ? { redirect } : undefined }
  /** 兜底跳转：不依赖 router 的导航队列，hash 模式下等价于一次正常导航 */
  const fallback = () => {
    const query = redirect ? `?redirect=${encodeURIComponent(redirect)}` : ''
    window.location.hash = `#${LOGIN_PATH}${query}`
  }
  router
    .replace(target)
    .then(failure => {
      /*
        vue-router 4 在「导航被打断」时**不抛错**，而是 resolve 一个 NavigationFailure。
        并发场景很常见：401 风暴恰逢用户点了菜单 / 流式事件触发了 replace。
        不处理的话用户就停在被取消的页面上，又是「提示过期但没退出」。
      */
      if (failure) fallback()
    })
    .catch(() => fallback())
})

/** 同步页面标题：`页面名 · CodeCraft` */
router.afterEach((to) => {
  const title = typeof to.meta.title === 'string' ? to.meta.title : ''
  document.title = title ? `${title} · ${APP_NAME}` : APP_NAME
})

export default router
