<template>
  <div class="login-root" @click="handleBackgroundClick">
    <!-- 装饰几何图形 -->
    <div class="decor-layer">
      <div class="decor-shape shape-1"></div>
      <div class="decor-shape shape-2"></div>
      <div class="decor-shape shape-3"></div>
      <div class="decor-ring ring-1"></div>
      <div class="decor-ring ring-2"></div>
    </div>

    <!-- 登录卡片 -->
    <div class="login-card">
      <!-- Logo 区 -->
      <div class="card-logo">
        <img :src="appLogo" class="logo-img" alt="CodeCraft" />
      </div>

      <!-- 标题 -->
      <h1 class="card-title">欢迎回来</h1>
      <p class="card-subtitle">登录您的 AI 助手账户</p>

      <!-- 表单 -->
      <div class="card-form">
        <a-form
          :model="formState"
          name="login_form"
          @finish="onFinish"
          @finishFailed="onFinishFailed"
          autocomplete="off"
        >
          <a-form-item
            name="username"
            :rules="[{ required: true, message: '请输入用户名' }]"
          >
            <a-input
              v-model:value="formState.username"
              placeholder="用户名"
              size="large"
              @focus="handleInputFocus"
            >
              <template #prefix>
                <UserOutlined />
              </template>
            </a-input>
          </a-form-item>

          <a-form-item
            name="password"
            :rules="[{ required: true, message: '请输入密码' }]"
          >
            <a-input-password
              v-model:value="formState.password"
              placeholder="密码"
              size="large"
              @focus="handlePasswordFocus"
            >
              <template #prefix>
                <LockOutlined />
              </template>
            </a-input-password>
          </a-form-item>

          <a-form-item name="remember" class="remember-item">
            <a-checkbox v-model:checked="formState.remember">
              记住我
            </a-checkbox>
          </a-form-item>

          <a-form-item>
            <a-button
              type="primary"
              html-type="submit"
              size="large"
              :loading="loading"
              block
              class="btn-login"
            >
              登 录
            </a-button>
          </a-form-item>
        </a-form>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { UserOutlined, LockOutlined } from '@ant-design/icons-vue'
import { message } from 'ant-design-vue'
import { ref, reactive, onMounted } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { getRandomCode, login } from '@/api/user'
import { doubleMd5 } from '@/utils/md5'
import { useUserStore } from '@/store/user'
import appLogo from '@/assets/logo.svg'

interface FormState {
  username: string
  password: string
  remember: boolean
}

const formState = reactive<FormState>({
  username: '',
  password: '',
  remember: false
})

const loading = ref(false)
const isInputFocused = ref(false)
const isPasswordFocused = ref(false)

const handleInputFocus = () => {
  isInputFocused.value = true
}

const handlePasswordFocus = () => {
  isPasswordFocused.value = true
}

const handleBackgroundClick = (event: MouseEvent) => {
  const target = event.target as HTMLElement
  const isInput = target.closest('.ant-input-affix-wrapper') || target.closest('.ant-input') || target.closest('.card-form')
  if (!isInput) {
    isInputFocused.value = false
    isPasswordFocused.value = false
  }
}

const userStore = useUserStore()
const router = useRouter()
const route = useRoute()

onMounted(() => {
  if (userStore.rememberedUsername) {
    formState.username = userStore.rememberedUsername
    formState.remember = true
  }
})

const onFinish = async (values: any) => {
  console.log('Success:', values)
  loading.value = true

  try {
    const randomCodeResponse = await getRandomCode(values.username)
    const randomCode = randomCodeResponse.data
    console.log('获取到随机码:', randomCode)
    userStore.randomCode = randomCode

    const encryptedPassword = doubleMd5(values.password, randomCode)
    console.log('加密后密码:', encryptedPassword)

    const loginResponse = await login(values.username, encryptedPassword)

    if (loginResponse.code === 200 && loginResponse.data) {
      userStore.saveLoginResponse(loginResponse)

      if (formState.remember) {
        userStore.rememberedUsername = values.username
      }
    }

    message.success(loginResponse.message || '登录成功！')
    console.log('用户信息:', loginResponse.data)

    const redirectPath = route.query.redirect as string || '/code-assistant'
    router.push(redirectPath)

  } catch (error: any) {
    console.error('登录失败:', error)

    let errorMessage = error.message || '登录失败，请检查用户名和密码。'

    if (errorMessage.includes('网络连接失败') || errorMessage.includes('请求超时')) {
      errorMessage = '网络连接异常，请检查网络后重试'
    } else if (errorMessage.includes('用户名') || errorMessage.includes('密码') || errorMessage.includes('认证失败')) {
      errorMessage = '用户名或密码错误，请检查后重试'
    } else if (errorMessage.includes('状态码: 404')) {
      errorMessage = '服务器接口不存在，请联系管理员'
    } else if (errorMessage.includes('状态码: 500')) {
      errorMessage = '服务器内部错误，请联系管理员'
    } else if (errorMessage.includes('状态码: 403')) {
      errorMessage = '访问被拒绝，权限不足'
    }

    message.error(errorMessage)
  } finally {
    loading.value = false
  }
}

const onFinishFailed = (errorInfo: any) => {
  console.log('Failed:', errorInfo)
  message.error('请填写所有必填字段。')
}
</script>

<style scoped>
/* ============================================================
   Login View — 现代简洁卡片 · CSS变量 · 暗色模式 · 微交互
   风格参考 P2P Panel v3
   ============================================================ */

/* ---------- CSS 变量 ---------- */
.login-root {
  --accent: #8b5cf6;
  --accent-lt: rgba(139, 92, 246, 0.08);
  --accent-md: rgba(139, 92, 246, 0.18);
  --accent-dk: #7c3aed;
  --accent-glow: rgba(139, 92, 246, 0.3);

  --bg-root: #f5f3fa;
  --bg-card: #ffffff;
  --text-1: #1a1a2e;
  --text-2: #5c5c78;
  --text-3: #9696aa;
  --text-4: #b8b8c8;
  --border: #e8e5f0;
  --shadow-sm: 0 1px 3px rgba(0, 0, 0, 0.04);
  --shadow-md: 0 4px 12px rgba(0, 0, 0, 0.06);
  --shadow-lg: 0 8px 30px rgba(0, 0, 0, 0.08);
  --shadow-xl: 0 20px 60px rgba(0, 0, 0, 0.12);
  --radius: 16px;
  --radius-sm: 10px;

  min-height: 100vh;
  width: 100%;
  display: flex;
  align-items: center;
  justify-content: center;
  background: var(--bg-root);
  font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', 'PingFang SC', 'Microsoft YaHei', sans-serif;
  position: relative;
  overflow: hidden;
}

/* ---------- 装饰几何图形 ---------- */
.decor-layer {
  position: absolute;
  inset: 0;
  pointer-events: none;
  z-index: 0;
  overflow: hidden;
}

.decor-shape {
  position: absolute;
  border-radius: 50%;
  opacity: 0.12;
  filter: blur(60px);
}

.shape-1 {
  width: 400px;
  height: 400px;
  background: var(--accent);
  top: -120px;
  right: -100px;
  animation: decorFloat 8s ease-in-out infinite;
}

.shape-2 {
  width: 300px;
  height: 300px;
  background: #a78bfa;
  bottom: -80px;
  left: -80px;
  animation: decorFloat 10s ease-in-out infinite 1s;
}

.shape-3 {
  width: 200px;
  height: 200px;
  background: #c4b5fd;
  top: 50%;
  left: 50%;
  transform: translate(-50%, -50%);
  animation: decorFloat 12s ease-in-out infinite 2s;
}

@keyframes decorFloat {
  0%, 100% { transform: translate(0, 0) scale(1); }
  33% { transform: translate(20px, -20px) scale(1.05); }
  66% { transform: translate(-10px, 10px) scale(0.95); }
}

.decor-ring {
  position: absolute;
  border-radius: 50%;
  border: 1.5px solid rgba(139, 92, 246, 0.06);
}

.ring-1 {
  width: 500px;
  height: 500px;
  top: calc(50% - 250px);
  left: calc(50% - 250px);
  animation: ringSpin 30s linear infinite;
}

.ring-2 {
  width: 350px;
  height: 350px;
  top: calc(50% - 175px);
  left: calc(50% - 175px);
  border-color: rgba(139, 92, 246, 0.08);
  animation: ringSpin 25s linear infinite reverse;
}

@keyframes ringSpin {
  from { transform: rotate(0deg); }
  to { transform: rotate(360deg); }
}

/* ---------- 登录卡片 ---------- */
.login-card {
  position: relative;
  z-index: 10;
  width: 420px;
  background: var(--bg-card);
  border-radius: var(--radius);
  padding: 44px 40px 36px;
  box-shadow: var(--shadow-xl);
  border: 1px solid var(--border);
  backdrop-filter: blur(10px);
  transition: transform 0.3s ease, box-shadow 0.3s ease;
}

.login-card:hover {
  transform: translateY(-2px);
  box-shadow: 0 24px 70px rgba(139, 92, 246, 0.12);
}

/* ---------- Logo ---------- */
.card-logo {
  display: flex;
  justify-content: center;
  margin-bottom: 16px;
}

.logo-img {
  width: 72px;
  height: 72px;
  border-radius: 18px;
  box-shadow: 0 6px 24px rgba(0, 0, 0, 0.12);
  transition: transform 0.3s ease, box-shadow 0.3s ease;
  display: block;
}

.logo-img:hover {
  transform: scale(1.06);
  box-shadow: 0 8px 32px rgba(56, 189, 248, 0.25);
}

/* ---------- 标题 ---------- */
.card-title {
  font-size: 26px;
  font-weight: 800;
  color: var(--text-1);
  text-align: center;
  margin: 0 0 6px;
  letter-spacing: -0.3px;
}

.card-subtitle {
  font-size: 14px;
  color: var(--text-3);
  text-align: center;
  margin: 0 0 32px;
}

/* ---------- 表单 ---------- */
.card-form {
  /* 表单容器 */
}

.remember-item {
  margin-bottom: 8px;
}

/* ---------- 按钮 ---------- */
.btn-login {
  height: 48px !important;
  border-radius: var(--radius-sm) !important;
  font-weight: 700 !important;
  font-size: 15px !important;
  letter-spacing: 0.5px;
  background: linear-gradient(135deg, var(--accent), var(--accent-dk)) !important;
  border: none !important;
  box-shadow: 0 4px 14px var(--accent-glow) !important;
  transition: all 0.3s ease !important;
}

.btn-login:hover:not(:disabled) {
  transform: translateY(-2px) !important;
  box-shadow: 0 8px 24px rgba(139, 92, 246, 0.4) !important;
}

.btn-login:active:not(:disabled) {
  transform: translateY(0) !important;
}

/* ---------- 覆盖 Ant Design 输入框样式 ---------- */
:deep(.ant-input-affix-wrapper) {
  border-radius: var(--radius-sm) !important;
  border: 1.5px solid var(--border) !important;
  padding: 10px 14px !important;
  transition: all 0.3s ease !important;
  background: var(--bg-card) !important;
  box-shadow: none !important;
}

:deep(.ant-input-affix-wrapper:hover) {
  border-color: #c4b5fd !important;
}

:deep(.ant-input-affix-wrapper-focused) {
  border-color: var(--accent) !important;
  box-shadow: 0 0 0 3px var(--accent-lt) !important;
}

:deep(.ant-input) {
  font-size: 15px !important;
  color: var(--text-1) !important;
}

:deep(.ant-input::placeholder) {
  color: var(--text-4) !important;
}

:deep(.ant-input-prefix) {
  color: var(--text-3) !important;
  margin-right: 10px !important;
}

:deep(.ant-form-item) {
  margin-bottom: 18px;
}

:deep(.ant-checkbox-wrapper) {
  color: var(--text-2) !important;
  font-size: 14px;
}

:deep(.ant-checkbox-inner) {
  border-radius: 5px !important;
  border-color: var(--border) !important;
}

:deep(.ant-checkbox-checked .ant-checkbox-inner) {
  background: var(--accent) !important;
  border-color: var(--accent) !important;
}

:deep(.ant-form-item-explain-error) {
  font-size: 12px;
  margin-top: 4px;
}

/* ---------- 响应式 ---------- */
@media (max-width: 480px) {
  .login-card {
    width: 90%;
    padding: 32px 24px 28px;
    border-radius: 14px;
  }

  .card-title {
    font-size: 22px;
  }

  .shape-1 {
    width: 200px;
    height: 200px;
    top: -60px;
    right: -60px;
  }

  .shape-2 {
    width: 150px;
    height: 150px;
    bottom: -40px;
    left: -40px;
  }

  .decor-ring {
    display: none;
  }
}

/* ============ 暗色模式 ============ */
[data-theme="dark"] .login-root {
  --bg-root: #121117;
  --bg-card: #1a1925;
  --text-1: #e4e2f0;
  --text-2: #a09eb8;
  --text-3: #6a6880;
  --text-4: #525070;
  --border: #2a2838;
  --shadow-sm: 0 1px 3px rgba(0, 0, 0, 0.3);
  --shadow-md: 0 4px 12px rgba(0, 0, 0, 0.4);
  --shadow-lg: 0 8px 30px rgba(0, 0, 0, 0.5);
  --shadow-xl: 0 20px 60px rgba(0, 0, 0, 0.6);
}

[data-theme="dark"] .login-card {
  border-color: rgba(255, 255, 255, 0.06);
}

[data-theme="dark"] .decor-shape {
  opacity: 0.06;
}

[data-theme="dark"] :deep(.ant-input-affix-wrapper) {
  background: #1e1d2c !important;
}

[data-theme="dark"] .btn-login {
  box-shadow: 0 4px 14px rgba(139, 92, 246, 0.25) !important;
}

[data-theme="dark"] .btn-login:hover:not(:disabled) {
  box-shadow: 0 8px 24px rgba(139, 92, 246, 0.35) !important;
}

[data-theme="dark"] .logo-img {
  box-shadow: 0 6px 24px rgba(56, 189, 248, 0.12);
}

[data-theme="dark"] .logo-img:hover {
  box-shadow: 0 8px 32px rgba(56, 189, 248, 0.3);
}
</style>
