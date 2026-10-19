<!--
  登录页（v3）
  ----------------------------------------------------------------------------
  布局：全屏居中卡片，不套 AppShell（没有侧栏 / 状态栏 / 会话列表），
        路由守卫判定「本地无 token」时把任何页面都导向这里。
  数据接缝：
    performLogin(username, plainPassword)  -> POST /user/random-code + POST /user/login
      后端登录是两步：先取 4 位随机码，再提交 MD5(MD5(明文密码) + 随机码)，
      二次加密在 http-client 内部完成，本页只提交明文。
    userStore.saveLoginResponse()          -> 写入 userInfo 并把 token 交给 http-client
    ensureIdentity(true)                   -> GET /user/current + GET /user/profile（补头像昵称）
    emit 无：登录成功后直接 router.replace(redirect || '/chat')
  说明：
    1) 「记住用户名」只存用户名（userStore.rememberedUsername，已持久化），不存密码；
    2) 登录页在 AppShell 之外，所以主题要在本页自己同步一次 data-theme，
       否则用户上次选了深色，登录页会闪一下浅色；
    3) 错误提示就地展示在卡片内（不用 Toast），登录失败是最需要「停在原地重试」的场景。
-->
<template>
  <div class="cc-login">
    <AppIcons />

    <div class="cc-login__card">
      <!-- 品牌 -->
      <div class="cc-login__brand">
        <div class="cc-login__logo">C</div>
        <div class="cc-login__brand-text">
          <div class="cc-login__name">CodeCraft</div>
          <div class="cc-login__ver">AI 编程助手</div>
        </div>
      </div>

      <h1 class="cc-login__title">欢迎回来</h1>
      <p class="cc-login__sub">登录后继续使用你的智能体工作台</p>

      <form class="cc-login__form" @submit.prevent="onLogin">
        <!-- 用户名 -->
        <div class="cc-login__field">
          <span class="cc-login__label">用户名</span>
          <div class="cc-login__wrap" :class="{ 'is-focus': focusField === 'username' }">
            <svg class="cc-i cc-i--sm cc-login__icon"><use href="#i-users" /></svg>
            <input
              v-model="form.username"
              class="cc-login__input"
              type="text"
              autocomplete="username"
              spellcheck="false"
              placeholder="请输入用户名"
              :disabled="loading"
              @focus="focusField = 'username'"
              @blur="focusField = ''"
              @input="errorText = ''"
            />
          </div>
        </div>

        <!-- 密码 -->
        <div class="cc-login__field">
          <span class="cc-login__label">密码</span>
          <div class="cc-login__wrap" :class="{ 'is-focus': focusField === 'password' }">
            <svg class="cc-i cc-i--sm cc-login__icon"><use href="#i-lock" /></svg>
            <input
              v-model="form.password"
              class="cc-login__input"
              :type="showPassword ? 'text' : 'password'"
              autocomplete="current-password"
              placeholder="请输入密码"
              :disabled="loading"
              @focus="focusField = 'password'"
              @blur="focusField = ''"
              @input="errorText = ''"
            />
            <button
              type="button"
              class="cc-login__eye"
              :title="showPassword ? '隐藏密码' : '显示密码'"
              tabindex="-1"
              @click="showPassword = !showPassword"
            >
              <svg class="cc-i cc-i--sm"><use :href="showPassword ? '#i-eye-off' : '#i-eye'" /></svg>
            </button>
          </div>
        </div>

        <!-- 记住用户名 -->
        <label class="cc-login__remember">
          <input v-model="form.remember" type="checkbox" :disabled="loading" />
          <span class="cc-login__checkbox" aria-hidden="true">
            <svg class="cc-i cc-i--sm"><use href="#i-check" /></svg>
          </span>
          <span>记住用户名</span>
        </label>

        <!-- 错误提示（就地展示，不弹 Toast：登录失败需要停在原地改） -->
        <div v-if="errorText" class="cc-login__error" role="alert">
          <svg class="cc-i cc-i--sm"><use href="#i-alert" /></svg>
          <span>{{ errorText }}</span>
        </div>

        <button type="submit" class="cc-btn cc-btn--primary cc-login__submit" :disabled="loading">
          <svg v-if="loading" class="cc-i cc-i--sm cc-login__spin"><use href="#i-refresh" /></svg>
          {{ loading ? '登录中…' : '登 录' }}
        </button>
      </form>

      <div class="cc-login__tip">
        初始账号 <b>admin</b> / 初始密码 <b>123456</b>，登录后可在「个人信息」中修改
      </div>
    </div>

    <div class="cc-login__foot">CodeCraft v3 · 本地运行的 AI 编程助手</div>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref, watchEffect } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import AppIcons from '../components/AppIcons.vue'
import { performLogin } from '../utils/http-client'
import { ensureIdentity } from '../utils/session'
import { useUserStore } from '../store/user'
import { useSettingsStore } from '../store/settings'
import { toast } from '../utils/toast'

const route = useRoute()
const router = useRouter()
const userStore = useUserStore()
const settings = useSettingsStore()

/** 登录成功后的落地页（守卫会把被拦截的原始地址塞进 redirect） */
const HOME_PATH = '/chat'

const form = reactive({
  username: '',
  password: '',
  remember: false
})

const loading = ref(false)
const errorText = ref('')
const showPassword = ref(false)
const focusField = ref('')

/**
 * 主题同步：登录页在 AppShell 之外，没人替它设置 data-theme。
 * 不补这一步，上次选了深色的用户会在登录页先看到一片白。
 */
const prefersDark =
  typeof window !== 'undefined' && window.matchMedia('(prefers-color-scheme: dark)').matches
const isDark = computed(
  () => settings.theme === 'dark' || (settings.theme === 'auto' && prefersDark)
)
watchEffect(() => {
  if (typeof document === 'undefined') return
  document.documentElement.setAttribute('data-theme', isDark.value ? 'dark' : 'light')
})

onMounted(() => {
  // 记住的用户名回填（只回填用户名，密码永远不落盘）
  if (userStore.rememberedUsername) {
    form.username = userStore.rememberedUsername
    form.remember = true
  }
})

/**
 * 把后端/网络错误翻译成一句人话。
 *
 * <p>后端登录失败会抛 RuntimeException（「密码错误」「用户不存在」…），而项目没有全局异常
 * 处理器，Spring 会把它渲染成 HTTP 500 的默认错误页 —— 前端拿到的是
 * 「服务异常 HTTP 500 Internal Server Error」，看不出到底哪里错。两条一起处理：</p>
 * <ul>
 *   <li>业务消息（密码错误 / 用户不存在）→ 统一压成「用户名或密码不正确」，
 *       不区分「用户不存在」与「密码错」，避免把账号是否存在告诉别人；</li>
 *   <li>HTTP 5xx（后端已完成请求但抛异常）→ 正常运行时只可能是凭证问题，
 *       同样按凭证提示；真正的网络不通由下面的「网络连接失败」分支处理。</li>
 * </ul>
 */
function friendlyError(raw: string): string {
  const msg = (raw || '').trim()
  if (!msg) return '登录失败，请重试'
  if (/密码错误|用户不存在|认证失败|未授权/.test(msg)) return '用户名或密码不正确'
  if (/Internal Server Error|服务异常 HTTP 5\d\d/.test(msg)) return '用户名或密码不正确'
  if (/随机码/.test(msg)) return '登录超时，请重新点击登录'
  if (/网络连接失败|请求超时|Failed to fetch/i.test(msg)) {
    return '无法连接后端服务，请确认服务已启动（默认 8084 端口）'
  }
  if (/已被禁用/.test(msg)) return '该账号已被禁用，请联系管理员'
  return msg
}

async function onLogin() {
  if (loading.value) return
  errorText.value = ''

  const username = form.username.trim()
  if (!username) {
    errorText.value = '请输入用户名'
    return
  }
  if (!form.password) {
    errorText.value = '请输入密码'
    return
  }

  loading.value = true
  try {
    // 两步登录（随机码 + 二次 MD5）在 http-client.performLogin 内部完成
    const result = await performLogin(username, form.password)

    // 写入 store 与 http-client（token 已由 performLogin 落盘，这里同步 store 镜像）
    userStore.saveLoginResponse({ code: 200, data: { ...result } })

    // 记住用户名（只存用户名）
    if (form.remember) userStore.setRememberedUsername(username)
    else userStore.clearRememberedUsername()

    /*
     * 补头像 / 昵称 / 邮箱：/user/login 已返回 nickname，但头像是 /user/profile 才有。
     * 失败不阻塞登录（页面顶多显示昵称首字当头像），所以这里不 catch 到外层。
     */
    await ensureIdentity(true)

    toast.success(`欢迎回来，${result.nickname || result.username}`)

    const redirect = typeof route.query.redirect === 'string' ? route.query.redirect : ''
    // 只接受站内路径，避免被构造成 //evil.com 这类开放重定向
    const target = redirect.startsWith('/') && !redirect.startsWith('//') ? redirect : HOME_PATH
    await router.replace(target)
  } catch (error) {
    errorText.value = friendlyError(error instanceof Error ? error.message : String(error))
  } finally {
    loading.value = false
  }
}
</script>
