<template>
  <div class="login-container" @click="handleBackgroundClick">
    <!-- 太阳系背景 -->
    <div class="solar-system" :class="{ 'zoom-to-earth': isInputFocused }">
      <div class="sun"></div>
      <div class="orbit mercury-orbit">
        <div class="planet mercury"></div>
      </div>
      <div class="orbit venus-orbit">
        <div class="planet venus"></div>
      </div>
      <div class="orbit earth-orbit">
        <div class="planet earth"></div>
        <div class="moon-orbit">
          <div class="moon"></div>
        </div>
      </div>
      <div class="orbit mars-orbit">
        <div class="planet mars"></div>
      </div>
      <div class="orbit jupiter-orbit">
        <div class="planet jupiter"></div>
      </div>
      <div class="orbit saturn-orbit">
        <div class="planet saturn">
          <div class="ring"></div>
        </div>
      </div>
    </div>

    <!-- 登录框 -->
    <div class="login-box">
      <!-- AI机器人 -->
      <div class="ai-robots">
        <div class="robot left-robot" :class="{ 'eyes-closed': isPasswordFocused }">
          <div class="robot-body">
            <div class="head">
              <div class="eyes">
                <div class="eye left-eye"></div>
                <div class="eye right-eye"></div>
              </div>
              <div class="antenna"></div>
            </div>
            <div class="body"></div>
            <div class="arm left-arm"></div>
            <div class="arm right-arm"></div>
            <div class="leg left-leg"></div>
            <div class="leg right-leg"></div>
          </div>
        </div>
        <div class="robot right-robot" :class="{ 'eyes-closed': isPasswordFocused }">
          <div class="robot-body">
            <div class="head">
              <div class="eyes">
                <div class="eye left-eye"></div>
                <div class="eye right-eye"></div>
              </div>
              <div class="antenna"></div>
            </div>
            <div class="body"></div>
            <div class="arm left-arm"></div>
            <div class="arm right-arm"></div>
            <div class="leg left-leg"></div>
            <div class="leg right-leg"></div>
          </div>
        </div>
      </div>

      <div class="login-form">
        <h1 class="login-title">AI助手登录</h1>
        <p class="login-subtitle">欢迎来到AI协作的未来</p>

        <a-form
          :model="formState"
          name="login_form"
          @finish="onFinish"
          @finishFailed="onFinishFailed"
          autocomplete="off"
        >
          <a-form-item
            name="username"
            :rules="[{ required: true, message: '请输入用户名！' }]"
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
            :rules="[{ required: true, message: '请输入密码！' }]"
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

          <a-form-item name="remember">
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
            >
              登录
            </a-button>
          </a-form-item>

          <div class="login-footer">
            <a href="#">忘记密码？</a>
            <span>|</span>
            <a href="#">创建账户</a>
          </div>
        </a-form>
      </div>
    </div>

    <!-- 地球特写视图（缩放时显示） -->
    <div class="earth-closeup" :class="{ 'visible': isInputFocused }">
      <div class="earth-detail">
        <div class="earth-globe">
          <div class="continents">
            <div class="continent asia"></div>
            <div class="continent europe"></div>
            <div class="continent africa"></div>
            <div class="continent americas"></div>
            <div class="continent australia"></div>
          </div>
          <div class="clouds"></div>
        </div>
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
  // 检查点击目标是否是输入框或表单内部元素
  const target = event.target as HTMLElement
  const isInput = target.closest('.ant-input-affix-wrapper') || target.closest('.ant-input') || target.closest('.login-form') || target.closest('.ai-robots')
  if (!isInput) {
    isInputFocused.value = false
    isPasswordFocused.value = false
  }
}

const userStore = useUserStore()
const router = useRouter()
const route = useRoute()

// 页面加载时从store中恢复记住的用户名
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
    // 1. 获取随机码
    const randomCodeResponse = await getRandomCode(values.username)
    const randomCode = randomCodeResponse.data
    console.log('获取到随机码:', randomCode)
    // 设置随机码到store
    userStore.randomCode = randomCode

    // 2. 双重MD5加密密码
    const encryptedPassword = doubleMd5(values.password, randomCode)
    console.log('加密后密码:', encryptedPassword)

    // 3. 调用登录接口
    const loginResponse = await login(values.username, encryptedPassword)

    // 4. 保存用户信息
    if (loginResponse.code === 200 && loginResponse.data) {
      userStore.saveLoginResponse(loginResponse)

      // 如果用户选择了"记住我"，保存用户名
      if (formState.remember) {
        userStore.rememberedUsername = values.username
      }
    }

    // 5. 登录成功提示
    message.success(loginResponse.message || '登录成功！')
    console.log('用户信息:', loginResponse.data)

    // 6. 跳转到目标页面或首页
    const redirectPath = route.query.redirect as string || '/ai-assistant'
    router.push(redirectPath)

  } catch (error: any) {
    console.error('登录失败:', error)

    // 根据错误类型显示不同的提示信息
    let errorMessage = error.message || '登录失败，请检查用户名和密码。'

    // 常见错误类型分类
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
.login-container {
  min-height: 100vh;
  width: 100%;
  height: 100%;
  display: flex;
  align-items: center;
  justify-content: center;
  background: #000;
  overflow: hidden;
  position: relative;
  font-family: 'Segoe UI', Tahoma, Geneva, Verdana, sans-serif;
  margin: 0;
  padding: 0;
}

/* 星空背景 */
.login-container::before {
  content: '';
  position: absolute;
  top: 0;
  left: 0;
  width: 100%;
  height: 100%;
  background-image:
    radial-gradient(1px 1px at 10% 20%, rgba(255, 255, 255, 0.8), transparent),
    radial-gradient(1px 1px at 20% 35%, rgba(255, 255, 255, 0.9), transparent),
    radial-gradient(1px 1px at 30% 15%, rgba(255, 255, 255, 0.7), transparent),
    radial-gradient(1px 1px at 40% 65%, rgba(255, 255, 255, 0.8), transparent),
    radial-gradient(1px 1px at 50% 40%, rgba(255, 255, 255, 0.9), transparent),
    radial-gradient(1px 1px at 60% 80%, rgba(255, 255, 255, 0.7), transparent),
    radial-gradient(1px 1px at 70% 25%, rgba(255, 255, 255, 0.8), transparent),
    radial-gradient(1px 1px at 80% 55%, rgba(255, 255, 255, 0.9), transparent),
    radial-gradient(1px 1px at 90% 10%, rgba(255, 255, 255, 0.7), transparent),
    radial-gradient(2px 2px at 15% 75%, rgba(255, 255, 255, 1), transparent),
    radial-gradient(2px 2px at 25% 45%, rgba(255, 255, 255, 1), transparent),
    radial-gradient(2px 2px at 35% 85%, rgba(255, 255, 255, 1), transparent),
    radial-gradient(2px 2px at 45% 30%, rgba(255, 255, 255, 1), transparent),
    radial-gradient(2px 2px at 55% 60%, rgba(255, 255, 255, 1), transparent),
    radial-gradient(2px 2px at 65% 20%, rgba(255, 255, 255, 1), transparent),
    radial-gradient(2px 2px at 75% 70%, rgba(255, 255, 255, 1), transparent),
    radial-gradient(2px 2px at 85% 40%, rgba(255, 255, 255, 1), transparent),
    radial-gradient(2px 2px at 95% 90%, rgba(255, 255, 255, 1), transparent),
    radial-gradient(3px 3px at 5% 50%, rgba(255, 255, 255, 1), transparent),
    radial-gradient(3px 3px at 95% 50%, rgba(255, 255, 255, 1), transparent);
  background-repeat: no-repeat;
  animation: twinkle 4s infinite alternate;
  z-index: 0;
}

@keyframes twinkle {
  0%, 100% { opacity: 0.7; }
  50% { opacity: 1; }
}

/* 银河系星星特效 */
.login-container::after {
  content: '';
  position: absolute;
  top: 0;
  left: 0;
  width: 100%;
  height: 100%;
  background-image:
    radial-gradient(0.5px 0.5px at 5% 10%, rgba(255, 255, 255, 0.9), transparent),
    radial-gradient(0.5px 0.5px at 10% 30%, rgba(255, 255, 255, 0.8), transparent),
    radial-gradient(0.5px 0.5px at 15% 50%, rgba(255, 255, 255, 0.9), transparent),
    radial-gradient(0.5px 0.5px at 20% 70%, rgba(255, 255, 255, 0.7), transparent),
    radial-gradient(0.5px 0.5px at 25% 90%, rgba(255, 255, 255, 0.8), transparent),
    radial-gradient(0.5px 0.5px at 30% 15%, rgba(255, 255, 255, 0.9), transparent),
    radial-gradient(0.5px 0.5px at 35% 35%, rgba(255, 255, 255, 0.7), transparent),
    radial-gradient(0.5px 0.5px at 40% 55%, rgba(255, 255, 255, 0.8), transparent),
    radial-gradient(0.5px 0.5px at 45% 75%, rgba(255, 255, 255, 0.9), transparent),
    radial-gradient(0.5px 0.5px at 50% 95%, rgba(255, 255, 255, 0.7), transparent),
    radial-gradient(0.5px 0.5px at 55% 20%, rgba(255, 255, 255, 0.8), transparent),
    radial-gradient(0.5px 0.5px at 60% 40%, rgba(255, 255, 255, 0.9), transparent),
    radial-gradient(0.5px 0.5px at 65% 60%, rgba(255, 255, 255, 0.7), transparent),
    radial-gradient(0.5px 0.5px at 70% 80%, rgba(255, 255, 255, 0.8), transparent),
    radial-gradient(0.5px 0.5px at 75% 25%, rgba(255, 255, 255, 0.9), transparent),
    radial-gradient(0.5px 0.5px at 80% 45%, rgba(255, 255, 255, 0.7), transparent),
    radial-gradient(0.5px 0.5px at 85% 65%, rgba(255, 255, 255, 0.8), transparent),
    radial-gradient(0.5px 0.5px at 90% 85%, rgba(255, 255, 255, 0.9), transparent),
    radial-gradient(0.5px 0.5px at 95% 5%, rgba(255, 255, 255, 0.7), transparent),
    radial-gradient(1px 1px at 7% 25%, rgba(255, 255, 255, 1), transparent),
    radial-gradient(1px 1px at 14% 45%, rgba(255, 255, 255, 1), transparent),
    radial-gradient(1px 1px at 21% 65%, rgba(255, 255, 255, 1), transparent),
    radial-gradient(1px 1px at 28% 85%, rgba(255, 255, 255, 1), transparent),
    radial-gradient(1px 1px at 35% 15%, rgba(255, 255, 255, 1), transparent),
    radial-gradient(1px 1px at 42% 35%, rgba(255, 255, 255, 1), transparent),
    radial-gradient(1px 1px at 49% 55%, rgba(255, 255, 255, 1), transparent),
    radial-gradient(1px 1px at 56% 75%, rgba(255, 255, 255, 1), transparent),
    radial-gradient(1px 1px at 63% 95%, rgba(255, 255, 255, 1), transparent),
    radial-gradient(1px 1px at 70% 20%, rgba(255, 255, 255, 1), transparent),
    radial-gradient(1px 1px at 77% 40%, rgba(255, 255, 255, 1), transparent),
    radial-gradient(1px 1px at 84% 60%, rgba(255, 255, 255, 1), transparent),
    radial-gradient(1px 1px at 91% 80%, rgba(255, 255, 255, 1), transparent),
    radial-gradient(1px 1px at 98% 10%, rgba(255, 255, 255, 1), transparent),
    radial-gradient(1.5px 1.5px at 3% 50%, rgba(255, 255, 255, 1), transparent),
    radial-gradient(1.5px 1.5px at 13% 70%, rgba(255, 255, 255, 1), transparent),
    radial-gradient(1.5px 1.5px at 23% 90%, rgba(255, 255, 255, 1), transparent),
    radial-gradient(1.5px 1.5px at 33% 30%, rgba(255, 255, 255, 1), transparent),
    radial-gradient(1.5px 1.5px at 43% 50%, rgba(255, 255, 255, 1), transparent),
    radial-gradient(1.5px 1.5px at 53% 70%, rgba(255, 255, 255, 1), transparent),
    radial-gradient(1.5px 1.5px at 63% 90%, rgba(255, 255, 255, 1), transparent),
    radial-gradient(1.5px 1.5px at 73% 20%, rgba(255, 255, 255, 1), transparent),
    radial-gradient(1.5px 1.5px at 83% 40%, rgba(255, 255, 255, 1), transparent),
    radial-gradient(1.5px 1.5px at 93% 60%, rgba(255, 255, 255, 1), transparent),
    radial-gradient(2px 2px at 8% 80%, rgba(255, 255, 255, 1), transparent),
    radial-gradient(2px 2px at 18% 10%, rgba(255, 255, 255, 1), transparent),
    radial-gradient(2px 2px at 28% 30%, rgba(255, 255, 255, 1), transparent),
    radial-gradient(2px 2px at 38% 50%, rgba(255, 255, 255, 1), transparent),
    radial-gradient(2px 2px at 48% 70%, rgba(255, 255, 255, 1), transparent),
    radial-gradient(2px 2px at 58% 90%, rgba(255, 255, 255, 1), transparent),
    radial-gradient(2px 2px at 68% 15%, rgba(255, 255, 255, 1), transparent),
    radial-gradient(2px 2px at 78% 35%, rgba(255, 255, 255, 1), transparent),
    radial-gradient(2px 2px at 88% 55%, rgba(255, 255, 255, 1), transparent),
    radial-gradient(2px 2px at 98% 75%, rgba(255, 255, 255, 1), transparent);
  background-repeat: no-repeat;
  animation: twinkle 6s infinite alternate;
  z-index: 0;
  opacity: 0.6;
}

/* 太阳系样式 */
.solar-system {
  position: absolute;
  width: 100%;
  height: 100%;
  transition: transform 2s cubic-bezier(0.34, 1.56, 0.64, 1);
  transform: scale(0.6);
  z-index: 1;
}

.solar-system.zoom-to-earth {
  transform: scale(1.8);
  opacity: 1;
}

.sun {
  position: absolute;
  top: 50%;
  left: 50%;
  width: 60px;
  height: 60px;
  background: radial-gradient(circle, #ffd700, #ff8c00);
  border-radius: 50%;
  box-shadow: 0 0 60px #ff8c00, 0 0 100px #ff4500;
  transform: translate(-50%, -50%);
}

.orbit {
  position: absolute;
  top: 50%;
  left: 50%;
  border: 1px solid rgba(255, 255, 255, 0.1);
  border-radius: 50%;
  transform: translate(-50%, -50%);
}

.mercury-orbit {
  width: 120px;
  height: 120px;
  animation: orbit 8s linear infinite;
}

.venus-orbit {
  width: 180px;
  height: 180px;
  animation: orbit 12s linear infinite;
}

.earth-orbit {
  width: 240px;
  height: 240px;
  animation: orbit 15s linear infinite;
}

.mars-orbit {
  width: 300px;
  height: 300px;
  animation: orbit 20s linear infinite;
}

.jupiter-orbit {
  width: 380px;
  height: 380px;
  animation: orbit 30s linear infinite;
}

.saturn-orbit {
  width: 460px;
  height: 460px;
  animation: orbit 40s linear infinite;
}

.planet {
  position: absolute;
  border-radius: 50%;
  top: 0;
  left: 50%;
  transform: translateX(-50%);
}

.mercury {
  width: 10px;
  height: 10px;
  background: linear-gradient(45deg, #a9a9a9, #696969);
}

.venus {
  width: 15px;
  height: 15px;
  background: linear-gradient(45deg, #ffb347, #ffcc33);
}

.earth {
  width: 20px;
  height: 20px;
  background: linear-gradient(45deg, #1e90ff, #32cd32);
  box-shadow: 0 0 10px #1e90ff;
}

.moon-orbit {
  position: absolute;
  width: 40px;
  height: 40px;
  border: 1px solid rgba(255, 255, 255, 0.05);
  border-radius: 50%;
  top: -10px;
  left: 50%;
  transform: translateX(-50%);
  animation: orbit 3s linear infinite;
}

.moon {
  position: absolute;
  width: 5px;
  height: 5px;
  background: #d3d3d3;
  border-radius: 50%;
  top: 0;
  left: 50%;
  transform: translateX(-50%);
}

.mars {
  width: 18px;
  height: 18px;
  background: linear-gradient(45deg, #ff4500, #cd5c5c);
}

.jupiter {
  width: 35px;
  height: 35px;
  background: linear-gradient(45deg, #ffa500, #deb887);
}

.saturn {
  width: 30px;
  height: 30px;
  background: linear-gradient(45deg, #f4a460, #d2b48c);
  position: relative;
}

.ring {
  position: absolute;
  width: 60px;
  height: 15px;
  background: linear-gradient(90deg, transparent, #f5deb3, transparent);
  border-radius: 50%;
  top: 50%;
  left: 50%;
  transform: translate(-50%, -50%) rotateX(60deg);
  opacity: 0.7;
}

@keyframes orbit {
  from { transform: translate(-50%, -50%) rotate(0deg); }
  to { transform: translate(-50%, -50%) rotate(360deg); }
}

/* 地球特写视图 */
.earth-closeup {
  position: absolute;
  top: 50%;
  left: 50%;
  transform: translate(-50%, -50%) scale(0);
  opacity: 0;
  transition: all 2s ease-in-out;
  z-index: 1;
  pointer-events: none;
}

.earth-closeup.visible {
  transform: translate(-50%, -50%) scale(0);
  opacity: 0;
}

.earth-detail {
  position: relative;
  width: 300px;
  height: 300px;
}

.earth-globe {
  width: 100%;
  height: 100%;
  background: radial-gradient(circle at 30% 30%, #1e90ff, #00008b);
  border-radius: 50%;
  position: relative;
  overflow: hidden;
  box-shadow: 0 0 60px rgba(30, 144, 255, 0.5);
}

.continents {
  position: absolute;
  width: 100%;
  height: 100%;
}

.continent {
  position: absolute;
  background: linear-gradient(45deg, #32cd32, #228b22);
  border-radius: 10px;
}

.continent.asia {
  top: 20%;
  left: 60%;
  width: 80px;
  height: 60px;
}

.continent.europe {
  top: 30%;
  left: 50%;
  width: 40px;
  height: 30px;
}

.continent.africa {
  top: 40%;
  left: 50%;
  width: 50px;
  height: 70px;
}

.continent.americas {
  top: 35%;
  left: 20%;
  width: 60px;
  height: 100px;
}

.continent.australia {
  top: 60%;
  left: 75%;
  width: 50px;
  height: 40px;
}

.clouds {
  position: absolute;
  width: 100%;
  height: 100%;
  background: radial-gradient(circle at 40% 40%, rgba(255, 255, 255, 0.3), transparent 70%);
  border-radius: 50%;
  animation: clouds-rotate 20s linear infinite;
}

@keyframes clouds-rotate {
  from { transform: rotate(0deg); }
  to { transform: rotate(360deg); }
}

/* 登录框样式 */
.login-box {
  position: relative;
  z-index: 10;
  background: rgba(255, 255, 255, 0.95);
  border-radius: 20px;
  padding: 40px;
  width: 420px;
  box-shadow: 0 20px 60px rgba(0, 0, 0, 0.3);
  backdrop-filter: blur(10px);
  border: 1px solid rgba(255, 255, 255, 0.2);
  margin: 20px;
}

/* AI机器人样式 */
.ai-robots {
  position: absolute;
  top: -80px;
  left: 0;
  right: 0;
  display: flex;
  justify-content: space-between;
  padding: 0 30px;
}

.robot {
  width: 120px;
  height: 120px;
  position: relative;
  transition: transform 0.3s ease;
}

.robot:hover {
  transform: translateY(-10px);
}

.robot-body {
  position: relative;
  width: 100%;
  height: 100%;
}

.head {
  position: absolute;
  top: 0;
  left: 50%;
  transform: translateX(-50%);
  width: 50px;
  height: 50px;
  background: linear-gradient(135deg, #667eea, #764ba2);
  border-radius: 50% 50% 40% 40%;
  border: 3px solid #4a5568;
}

.eyes {
  position: absolute;
  top: 15px;
  left: 50%;
  transform: translateX(-50%);
  display: flex;
  gap: 10px;
}

.eye {
  width: 12px;
  height: 12px;
  background: #ffffff;
  border-radius: 50%;
  position: relative;
  overflow: hidden;
  transition: height 0.3s ease;
}

.eye::after {
  content: '';
  position: absolute;
  top: 3px;
  left: 3px;
  width: 6px;
  height: 6px;
  background: #2d3748;
  border-radius: 50%;
}

.robot.eyes-closed .eye {
  height: 3px;
  border-radius: 3px;
}

.robot.eyes-closed .eye::after {
  display: none;
}

.antenna {
  position: absolute;
  top: -10px;
  left: 50%;
  transform: translateX(-50%);
  width: 4px;
  height: 15px;
  background: #4a5568;
  border-radius: 2px;
}

.antenna::after {
  content: '';
  position: absolute;
  top: -5px;
  left: 50%;
  transform: translateX(-50%);
  width: 10px;
  height: 10px;
  background: #ff6b6b;
  border-radius: 50%;
  animation: antenna-blink 2s infinite;
}

@keyframes antenna-blink {
  0%, 100% { opacity: 0.3; }
  50% { opacity: 1; }
}

.body {
  position: absolute;
  top: 45px;
  left: 50%;
  transform: translateX(-50%);
  width: 60px;
  height: 50px;
  background: linear-gradient(135deg, #667eea, #764ba2);
  border-radius: 15px;
  border: 3px solid #4a5568;
}

.arm {
  position: absolute;
  top: 50px;
  width: 25px;
  height: 40px;
  background: linear-gradient(135deg, #667eea, #764ba2);
  border: 2px solid #4a5568;
  border-radius: 12px;
}

.left-arm {
  left: 10px;
  transform: rotate(30deg);
}

.right-arm {
  right: 10px;
  transform: rotate(-30deg);
}

.leg {
  position: absolute;
  top: 90px;
  width: 20px;
  height: 40px;
  background: linear-gradient(135deg, #667eea, #764ba2);
  border: 2px solid #4a5568;
  border-radius: 10px;
}

.left-leg {
  left: 35px;
  transform: rotate(10deg);
}

.right-leg {
  right: 35px;
  transform: rotate(-10deg);
}

/* 表单样式 */
.login-form {
  margin-top: 40px;
}

.login-title {
  font-size: 28px;
  font-weight: 700;
  color: #2d3748;
  text-align: center;
  margin-bottom: 8px;
  background: linear-gradient(135deg, #667eea, #764ba2);
  -webkit-background-clip: text;
  -webkit-text-fill-color: transparent;
  background-clip: text;
}

.login-subtitle {
  text-align: center;
  color: #718096;
  margin-bottom: 30px;
  font-size: 14px;
}

:deep(.ant-input-affix-wrapper) {
  border-radius: 10px;
  border: 2px solid #e2e8f0;
  padding: 12px 16px;
  transition: all 0.3s;
}

:deep(.ant-input-affix-wrapper:hover),
:deep(.ant-input-affix-wrapper-focused) {
  border-color: #667eea;
  box-shadow: 0 0 0 2px rgba(102, 126, 234, 0.1);
}

:deep(.ant-input) {
  font-size: 16px;
}

:deep(.ant-btn) {
  height: 48px;
  border-radius: 10px;
  font-weight: 600;
  font-size: 16px;
  background: linear-gradient(135deg, #667eea, #764ba2);
  border: none;
  transition: all 0.3s;
}

:deep(.ant-btn:hover) {
  transform: translateY(-2px);
  box-shadow: 0 10px 20px rgba(102, 126, 234, 0.3);
}

:deep(.ant-checkbox-wrapper) {
  color: #4a5568;
}

.login-footer {
  text-align: center;
  margin-top: 20px;
  padding-top: 20px;
  border-top: 1px solid #e2e8f0;
  color: #718096;
}

.login-footer a {
  color: #667eea;
  text-decoration: none;
  margin: 0 10px;
  transition: color 0.3s;
}

.login-footer a:hover {
  color: #764ba2;
  text-decoration: underline;
}

.login-footer span {
  color: #cbd5e0;
  margin: 0 10px;
}

/* 响应式设计 */
@media (max-width: 480px) {
  .login-box {
    width: 90%;
    padding: 30px 20px;
  }

  .ai-robots {
    top: -60px;
    padding: 0 20px;
  }

  .robot {
    width: 80px;
    height: 80px;
  }

  .earth-detail {
    width: 200px;
    height: 200px;
  }
}
</style>