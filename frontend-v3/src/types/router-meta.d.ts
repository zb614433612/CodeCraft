/**
 * vue-router 路由元信息类型增强
 *
 * <h3>为什么需要手写</h3>
 * vue-router 的 {@code RouteMeta} 默认是空接口（{@code interface RouteMeta extends Record<...>}），
 * 不增强的话 {@code to.meta.adminOnly} 只能按 {@code unknown} 处理，
 * 守卫里就得写 {@code (to.meta as any).adminOnly} —— 既丢类型又容易写错字段名。
 *
 * <p>增强后路由表与守卫共享同一份字段定义，{@code meta} 拼错字段名会在 typecheck 阶段直接报错。</p>
 */

import 'vue-router'

declare module 'vue-router' {
  interface RouteMeta {
    /** 页面标题：由 menu.config.ts 的 label 注入，afterEach 同步到 document.title */
    title?: string
    /**
     * 是否仅管理员可访问。
     *
     * <p>由 {@code MenuItem.adminOnly} 推导，路由守卫与侧栏菜单过滤共用同一份配置。</p>
     */
    adminOnly?: boolean
  }
}
