<template>
  <div class="profile-container">
    <!-- 页面头部 -->
    <div class="page-header">
      <h2 class="page-title">个人信息</h2>
    </div>

    <div class="profile-content">
      <!-- 基本信息卡片 -->
      <div class="profile-card">
        <div class="card-title">基本信息</div>
        <div class="card-body">
          <!-- 头像区域 -->
          <div class="avatar-section">
            <div class="profile-avatar">
              {{ profileData.username?.charAt(0).toUpperCase() || 'U' }}
            </div>
            <div class="avatar-info">
              <div class="display-name">{{ profileData.nickname || profileData.username || '用户' }}</div>
              <a-tag :color="profileData.role === 'admin' ? 'red' : 'blue'">
                {{ profileData.role === 'admin' ? '管理员' : '普通用户' }}
              </a-tag>
            </div>
          </div>

          <a-form
            ref="profileFormRef"
            :model="profileData"
            :label-col="{ span: 4 }"
            :wrapper-col="{ span: 16 }"
            class="profile-form"
          >
            <a-form-item label="用户名">
              <span class="readonly-field">{{ profileData.username }}</span>
            </a-form-item>

            <a-form-item label="昵称" name="nickname">
              <a-input
                v-model:value="profileData.nickname"
                placeholder="请输入昵称"
                :maxLength="50"
              />
            </a-form-item>

            <a-form-item label="邮箱" name="email">
              <a-input
                v-model:value="profileData.email"
                placeholder="请输入邮箱"
                :maxLength="100"
              />
            </a-form-item>

            <a-form-item label="手机号" name="phone">
              <a-input
                v-model:value="profileData.phone"
                placeholder="请输入手机号"
                :maxLength="20"
              />
            </a-form-item>

            <a-form-item :wrapper-col="{ offset: 4, span: 16 }">
              <a-button
                type="primary"
                :loading="profileSaving"
                @click="handleSaveProfile"
              >
                保存信息
              </a-button>
            </a-form-item>
          </a-form>
        </div>
      </div>

      <!-- 修改密码卡片 -->
      <div class="profile-card">
        <div class="card-title">修改密码</div>
        <div class="card-body">
          <a-form
            ref="passwordFormRef"
            :model="passwordData"
            :rules="passwordRules"
            :label-col="{ span: 4 }"
            :wrapper-col="{ span: 16 }"
            class="profile-form"
          >
            <a-form-item label="旧密码" name="oldPassword">
              <a-input-password
                v-model:value="passwordData.oldPassword"
                placeholder="请输入旧密码"
              />
            </a-form-item>

            <a-form-item label="新密码" name="newPassword">
              <a-input-password
                v-model:value="passwordData.newPassword"
                placeholder="请输入新密码（至少6位）"
              />
            </a-form-item>

            <a-form-item label="确认密码" name="confirmPassword">
              <a-input-password
                v-model:value="passwordData.confirmPassword"
                placeholder="请再次输入新密码"
              />
            </a-form-item>

            <a-form-item :wrapper-col="{ offset: 4, span: 16 }">
              <a-button
                type="primary"
                :loading="passwordSaving"
                @click="handleChangePassword"
              >
                修改密码
              </a-button>
            </a-form-item>
          </a-form>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, onMounted } from 'vue'
import { message } from 'ant-design-vue'
import { getProfile, updateProfile, changePassword } from '@/api/user-admin'

// 个人信息数据
const profileData = reactive({
  username: '',
  nickname: '',
  role: '',
  email: '',
  phone: ''
})

// 密码表单数据
const passwordData = reactive({
  oldPassword: '',
  newPassword: '',
  confirmPassword: ''
})

// 加载状态
const profileSaving = ref(false)
const passwordSaving = ref(false)
const profileFormRef = ref<any>(null)
const passwordFormRef = ref<any>(null)

// 密码校验规则
const passwordRules = {
  oldPassword: [
    { required: true, message: '请输入旧密码', trigger: 'blur' }
  ],
  newPassword: [
    { required: true, message: '请输入新密码', trigger: 'blur' },
    { min: 6, max: 32, message: '密码长度6-32个字符', trigger: 'blur' }
  ],
  confirmPassword: [
    { required: true, message: '请再次输入新密码', trigger: 'blur' },
    {
      validator: (_rule: any, value: string) => {
        if (value && value !== passwordData.newPassword) {
          return Promise.reject('两次输入的密码不一致')
        }
        return Promise.resolve()
      },
      trigger: 'blur'
    }
  ]
}

// 加载个人信息
const loadProfile = async () => {
  try {
    const response = await getProfile()
    if (response.code === 200 && response.data) {
      profileData.username = response.data.username || ''
      profileData.nickname = response.data.nickname || ''
      profileData.role = response.data.role || 'user'
      profileData.email = response.data.email || ''
      profileData.phone = response.data.phone || ''
    }
  } catch (error: any) {
    message.error(error.message || '加载个人信息失败')
  }
}

// 保存个人信息
const handleSaveProfile = async () => {
  try {
    await profileFormRef.value?.validate()
  } catch {
    return
  }

  profileSaving.value = true
  try {
    await updateProfile({
      nickname: profileData.nickname,
      email: profileData.email,
      phone: profileData.phone
    })
    message.success('个人信息已更新')
  } catch (error: any) {
    message.error(error.message || '保存失败')
  } finally {
    profileSaving.value = false
  }
}

// 修改密码
const handleChangePassword = async () => {
  try {
    await passwordFormRef.value?.validate()
  } catch {
    return
  }

  passwordSaving.value = true
  try {
    await changePassword({
      oldPassword: passwordData.oldPassword,
      newPassword: passwordData.newPassword
    })
    message.success('密码修改成功，下次登录请使用新密码')
    // 清空密码输入
    passwordData.oldPassword = ''
    passwordData.newPassword = ''
    passwordData.confirmPassword = ''
  } catch (error: any) {
    message.error(error.message || '密码修改失败')
  } finally {
    passwordSaving.value = false
  }
}

onMounted(() => {
  loadProfile()
})
</script>

<style scoped>
.profile-container {
  padding: 24px;
  height: 100%;
  background: #f5f7fa;
  overflow-y: auto;
}

.page-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 16px;
  padding: 16px 20px;
  background: white;
  border-radius: 8px;
  box-shadow: 0 1px 4px rgba(0, 0, 0, 0.06);
}

.page-title {
  margin: 0;
  font-size: 20px;
  font-weight: 600;
  color: #1a202c;
}

.profile-content {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.profile-card {
  background: white;
  border-radius: 8px;
  box-shadow: 0 1px 4px rgba(0, 0, 0, 0.06);
  overflow: hidden;
}

.card-title {
  padding: 16px 20px;
  font-size: 16px;
  font-weight: 500;
  color: #1a202c;
  border-bottom: 1px solid #f0f0f0;
}

.card-body {
  padding: 20px;
}

.avatar-section {
  display: flex;
  align-items: center;
  gap: 16px;
  margin-bottom: 24px;
  padding-bottom: 20px;
  border-bottom: 1px solid #f5f5f5;
}

.profile-avatar {
  width: 64px;
  height: 64px;
  border-radius: 50%;
  background: linear-gradient(135deg, #667eea, #764ba2);
  display: flex;
  align-items: center;
  justify-content: center;
  color: white;
  font-size: 24px;
  font-weight: bold;
  flex-shrink: 0;
}

.avatar-info {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.display-name {
  font-size: 18px;
  font-weight: 600;
  color: #1a202c;
}

.profile-form {
  max-width: 560px;
}

.readonly-field {
  color: #595959;
  line-height: 32px;
}
</style>
