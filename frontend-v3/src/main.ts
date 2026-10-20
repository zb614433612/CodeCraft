/**
 * 应用引导（v3 · 登录页版）
 * ----------------------------------------------------------------------------
 * 关键约定：
 *  · <b>显式登录</b>：后端 {@code TokenAuthenticationFilter} 要求所有 /api 请求带 token，
 *    未登录时路由守卫会把人挡到 {@code /login}，由 {@code views/LoginView.vue} 采集账号密码，
 *    调 {@code http-client.performLogin()} 换取 token（随机码 + 二次 MD5，后端零改动）。
 *  · <b>已登录才预热</b>：本地有 token 时并行拉一次身份（{@code GET /api/user/current} + profile），
 *    让首屏侧栏的权限与头像一次到位；没有 token 就不发任何请求（避免必然的 401）。
 *  · <b>预热失败不阻塞首屏</b>：{@code ensureIdentity()} 失败（后端未启动）只告警，
 *    不阻塞挂载，页面仍可渲染，具体接口调用时会给出明确错误。
 *  · <b>Pinia + 持久化</b>：user / settings / file-ref 三个 store 中，user 与 settings 声明了
 *    {@code persist}，需先注册 pinia-plugin-persistedstate，否则配置不生效。
 *  · <b>不引入 ant-design-vue</b>：旧 UI 组件在 L2/L4 用自建组件替换，这里立好边界，
 *    避免新代码又写回 antd 依赖。
 *  · 样式直接引用根目录 assets/（唯一真相源），与静态预览页 preview.html 共用。
 */
import { createApp } from 'vue'
import { createPinia } from 'pinia'
import piniaPluginPersistedstate from 'pinia-plugin-persistedstate'

import App from './App.vue'
import router from './router'
import { hasToken } from './utils/http-client'
import { ensureIdentity, refreshProfile } from './utils/session'

import '../assets/tokens.css'
import '../assets/app.css'
/* 动效层必须最后引入：它集中声明全站 keyframes 与入场动画，
 * 并要在组件样式（app.css）之后，才能覆盖个别组件的 transition 组合。 */
import '../assets/motion.css'

const app = createApp(App)

const pinia = createPinia()
pinia.use(piniaPluginPersistedstate)

app.use(pinia)
app.use(router)

/**
 * 已登录预热：与路由解析并行，等首屏渲染完成前大概率已拿到身份。
 *
 * <p>未登录时什么都不做 —— 路由守卫会直接把人挡到登录页，
 * 此时任何请求都只会得到 401 并把用户吓一跳。</p>
 */
if (hasToken()) {
  void ensureIdentity()
    .then(info => {
      if (!info) return
      /*
       * 展示字段（头像 / 昵称）每次启动对齐一次服务端。
       *
       * user store 是持久化的，若只靠 ensureIdentity 的缓存判断（有 role 就直接返回），
       * 用户在别处改了头像后，本机要等到清 localStorage 才会更新 —— 表现就是
       * 「头像没同步到侧栏」。这一步是尽力而为：失败静默（无头像时组件用昵称首字兜底）。
       */
      void refreshProfile()
    })
    .catch((error: unknown) => {
      const message = error instanceof Error ? error.message : String(error)
      console.warn('[session] 身份预热失败，管理页将按无权限处理：', message)
    })
}

/**
 * 等首个路由解析完成再挂载。
 * hash 模式的初始导航是异步的：若直接 mount，`route.path` 首帧是 '/'，
 * App.vue 会先渲染一次「该页面待实现（/）」占位，再跳到 /chat（或 /login），肉眼可见一次闪烁。
 */
void router.isReady().then(() => app.mount('#app'))
