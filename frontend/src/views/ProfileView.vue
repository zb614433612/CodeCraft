<template>
  <div class="profile-page">
    <!-- ===== 头部卡片 ===== -->
    <div class="page-header">
      <div class="header-left">
        <div class="header-icon-box">
          <span class="header-icon">👤</span>
        </div>
        <div class="header-text">
          <h2 class="header-title">个人信息</h2>
          <p class="header-subtitle">查看和修改您的个人资料与账户密码</p>
        </div>
      </div>
      <div class="header-right">
        <div class="role-badge">
          <a-tag :color="profileData.role === 'admin' ? 'red' : 'blue'" class="role-tag">
            {{ profileData.role === 'admin' ? '管理员' : '普通用户' }}
          </a-tag>
        </div>
      </div>
    </div>

    <!-- ===== 内容区域 ===== -->
    <div class="profile-content">
      <!-- 基本信息卡片 -->
      <div class="profile-card">
        <div class="card-header">
          <span class="card-header-icon">📝</span>
          <span class="card-header-title">基本信息</span>
        </div>

        <div class="card-body">
          <!-- 头像区域 - 增强版 -->
          <div class="avatar-section-enhanced">
            <div class="profile-avatar">
              <span class="avatar-letter">{{ profileData.username?.charAt(0).toUpperCase() || 'U' }}</span>
              <div class="avatar-ring"></div>
            </div>
            <div class="avatar-details">
              <div class="display-name">{{ profileData.nickname || profileData.username || '用户' }}</div>
              <div class="display-username">@{{ profileData.username }}</div>
              <div class="display-meta">
                <span class="meta-dot" :class="profileData.email ? 'has-value' : 'empty'"></span>
                {{ profileData.email ? '已绑定邮箱' : '未绑定邮箱' }}
                <span class="meta-sep">·</span>
                <span class="meta-dot" :class="profileData.phone ? 'has-value' : 'empty'"></span>
                {{ profileData.phone ? '已绑定手机' : '未绑定手机' }}
              </div>
            </div>
          </div>

          <a-form
            ref="profileFormRef"
            :model="profileData"
            :label-col="{ span: 4 }"
            :wrapper-col="{ span: 18 }"
            class="profile-form"
          >
            <a-form-item label="用户名">
              <div class="readonly-field">
                <UserOutlined class="field-icon" />
                {{ profileData.username }}
              </div>
            </a-form-item>

            <a-form-item label="昵称" name="nickname">
              <a-input
                v-model:value="profileData.nickname"
                placeholder="请输入昵称"
                :maxLength="50"
                size="large"
              />
            </a-form-item>

            <a-form-item label="邮箱" name="email">
              <a-input
                v-model:value="profileData.email"
                placeholder="请输入邮箱地址"
                :maxLength="100"
                size="large"
              >
                <template #prefix><MailOutlined /></template>
              </a-input>
            </a-form-item>

            <a-form-item label="手机号" name="phone">
              <a-input
                v-model:value="profileData.phone"
                placeholder="请输入手机号码"
                :maxLength="20"
                size="large"
              >
                <template #prefix><PhoneOutlined /></template>
              </a-input>
            </a-form-item>

            <a-form-item :wrapper-col="{ offset: 4, span: 18 }" class="form-actions">
              <a-button
                type="primary"
                :loading="profileSaving"
                @click="handleSaveProfile"
                class="save-profile-btn"
              >
                <template #icon><SaveOutlined /></template>
                保存信息
              </a-button>
            </a-form-item>
          </a-form>
        </div>
      </div>

      <!-- 修改密码卡片 -->
      <div class="profile-card">
        <div class="card-header">
          <span class="card-header-icon">🔒</span>
          <span class="card-header-title">修改密码</span>
        </div>

        <div class="card-body">
          <a-form
            ref="passwordFormRef"
            :model="passwordData"
            :rules="passwordRules"
            :label-col="{ span: 4 }"
            :wrapper-col="{ span: 18 }"
            class="profile-form"
          >
            <a-form-item label="旧密码" name="oldPassword">
              <a-input-password
                v-model:value="passwordData.oldPassword"
                placeholder="请输入当前密码"
                size="large"
              >
                <template #prefix><LockOutlined /></template>
              </a-input-password>
            </a-form-item>

            <a-form-item label="新密码" name="newPassword">
              <a-input-password
                v-model:value="passwordData.newPassword"
                placeholder="请输入新密码（至少6位）"
                size="large"
              >
                <template #prefix><KeyOutlined /></template>
              </a-input-password>
            </a-form-item>

            <a-form-item label="确认密码" name="confirmPassword">
              <a-input-password
                v-model:value="passwordData.confirmPassword"
                placeholder="请再次输入新密码"
                size="large"
              >
                <template #prefix><SafetyOutlined /></template>
              </a-input-password>
            </a-form-item>

            <a-form-item :wrapper-col="{ offset: 4, span: 18 }" class="form-actions">
              <a-button
                type="primary"
                :loading="passwordSaving"
                @click="handleChangePassword"
                class="save-profile-btn password-btn"
              >
                <template #icon><CheckCircleOutlined /></template>
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
import {
  UserOutlined,
  MailOutlined,
  PhoneOutlined,
  LockOutlined,
  KeyOutlined,
  SafetyOutlined,
  SaveOutlined,
  CheckCircleOutlined
} from '@ant-design/icons-vue'
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

const profileSaving = ref(false)
const passwordSaving = ref(false)
const profileFormRef = ref<any>(null)
const passwordFormRef = ref<any>(null)

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
/* ================================================================
   整体布局
   ================================================================ */
.profile-page {
  padding: 20px 24px 32px;
  background: #f7f9fc;
  height: 100%;
  overflow-y: auto;
  box-sizing: border-box;
  display: flex;
  flex-direction: column;
  gap: 16px;
}

/* ================================================================
   头部卡片
   ================================================================ */
.page-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 20px 24px;
  background: #fff;
  border-radius: 12px;
  border: 1px solid #eef2f7;
  box-shadow: 0 1px 3px rgba(0, 0, 0, 0.04);
  flex-shrink: 0;
}

.header-left {
  display: flex;
  align-items: center;
  gap: 14px;
}

.header-icon-box {
  width: 44px;
  height: 44px;
  border-radius: 10px;
  background: linear-gradient(135deg, #e0f2fe, #bae6fd);
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
}

.header-icon {
  font-size: 22px;
  line-height: 1;
}

.header-title {
  margin: 0;
  font-size: 18px;
  font-weight: 700;
  color: #1a202c;
  letter-spacing: -0.3px;
}

.header-subtitle {
  margin: 2px 0 0;
  font-size: 13px;
  color: #94a3b8;
}

.header-right {
  flex-shrink: 0;
}

.role-tag {
  font-size: 13px !important;
  padding: 2px 12px !important;
  border-radius: 6px !important;
  font-weight: 600;
}

/* ================================================================
   内容区域
   ================================================================ */
.profile-content {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

/* ================================================================
   卡片
   ================================================================ */
.profile-card {
  background: #fff;
  border-radius: 12px;
  border: 1px solid #eef2f7;
  box-shadow: 0 1px 3px rgba(0, 0, 0, 0.04);
  overflow: hidden;
}

.card-header {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 16px 24px;
  border-bottom: 1px solid #f1f5f9;
  background: #fafbfc;
}

.card-header-icon {
  font-size: 18px;
  line-height: 1;
}

.card-header-title {
  font-size: 15px;
  font-weight: 600;
  color: #1a202c;
}

.card-body {
  padding: 24px;
}

/* ================================================================
   头像区域 - 增强版
   ================================================================ */
.avatar-section-enhanced {
  display: flex;
  align-items: center;
  gap: 20px;
  margin-bottom: 28px;
  padding: 20px 24px;
  background: linear-gradient(135deg, #f8fafd, #f0f4ff);
  border-radius: 12px;
  border: 1px solid #eef2f7;
}

.profile-avatar {
  position: relative;
  width: 72px;
  height: 72px;
  border-radius: 50%;
  background: linear-gradient(135deg, #667eea, #764ba2);
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
  box-shadow: 0 4px 16px rgba(102, 126, 234, 0.35);
}

.avatar-letter {
  color: white;
  font-size: 28px;
  font-weight: 700;
  line-height: 1;
  z-index: 1;
}

.avatar-ring {
  position: absolute;
  inset: -4px;
  border-radius: 50%;
  border: 2px solid rgba(102, 126, 234, 0.2);
}

.avatar-details {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.display-name {
  font-size: 20px;
  font-weight: 700;
  color: #1a202c;
}

.display-username {
  font-size: 13px;
  color: #94a3b8;
  font-family: 'SF Mono', 'Monaco', 'Consolas', monospace;
}

.display-meta {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 12px;
  color: #94a3b8;
  margin-top: 2px;
}

.meta-dot {
  width: 7px;
  height: 7px;
  border-radius: 50%;
  display: inline-block;
}

.meta-dot.has-value {
  background: #22c55e;
  box-shadow: 0 0 0 3px rgba(34, 197, 94, 0.15);
}

.meta-dot.empty {
  background: #c0c8d4;
}

.meta-sep {
  color: #dce4f0;
}

/* ================================================================
   表单
   ================================================================ */
.profile-form {
  max-width: 600px;
}

.profile-form :deep(.ant-form-item) {
  margin-bottom: 18px;
}

.profile-form :deep(.ant-form-item-label > label) {
  font-size: 13px;
  font-weight: 600;
  color: #475569;
}

.readonly-field {
  display: flex;
  align-items: center;
  gap: 8px;
  color: #475569;
  font-size: 14px;
  font-weight: 500;
  line-height: 40px;
  padding: 0 12px;
  background: #f8fafc;
  border-radius: 8px;
  border: 1px solid #eef2f7;
}

.field-icon {
  font-size: 15px;
  color: #94a3b8;
}

.form-actions {
  margin-top: 24px !important;
  padding-top: 20px;
  border-top: 1px solid #f1f5f9;
}

.save-profile-btn {
  height: 40px;
  border-radius: 8px;
  padding: 0 24px;
  font-weight: 600;
  font-size: 14px;
  box-shadow: 0 2px 8px rgba(22, 119, 255, 0.2);
  display: inline-flex;
  align-items: center;
  gap: 6px;
  transition: all 0.2s ease;
}

.save-profile-btn:hover {
  box-shadow: 0 4px 14px rgba(22, 119, 255, 0.3);
  transform: translateY(-1px);
}

.password-btn {
  box-shadow: 0 2px 8px rgba(34, 197, 94, 0.2);
  background: linear-gradient(135deg, #22c55e, #16a34a);
  border-color: #22c55e;
}

.password-btn:hover {
  background: linear-gradient(135deg, #16a34a, #15803d) !important;
  border-color: #16a34a !important;
  box-shadow: 0 4px 14px rgba(34, 197, 94, 0.35);
}

/* ================================================================
   滚动条
   ================================================================ */
.profile-page::-webkit-scrollbar {
  width: 5px;
}

.profile-page::-webkit-scrollbar-thumb {
  background: #dce4f0;
  border-radius: 3px;
}

.profile-page::-webkit-scrollbar-thumb:hover {
  background: #c0c8d4;
}

/* ================================================================
   暗色模式
   ================================================================ */
[data-theme="dark"] .profile-page {
  background: #121418;
}

[data-theme="dark"] .page-header,
[data-theme="dark"] .profile-card {
  background: #1a1d22;
  border-color: #2a2d33;
}

[data-theme="dark"] .card-header {
  background: #1e2126;
  border-bottom-color: #2a2d33;
}

[data-theme="dark"] .header-title,
[data-theme="dark"] .card-header-title,
[data-theme="dark"] .display-name {
  color: #e4e6ea;
}

[data-theme="dark"] .header-subtitle,
[data-theme="dark"] .display-username,
[data-theme="dark"] .display-meta {
  color: #8b8f98;
}

[data-theme="dark"] .header-icon-box {
  background: linear-gradient(135deg, #1a2838, #162230);
}

[data-theme="dark"] .avatar-section-enhanced {
  background: linear-gradient(135deg, #1a1d24, #181c28);
  border-color: #2a2d33;
}

[data-theme="dark"] .readonly-field {
  background: #141619;
  border-color: #2a2d33;
  color: #c4c8ce;
}

[data-theme="dark"] .field-icon {
  color: #8b8f98;
}

[data-theme="dark"] .form-actions {
  border-top-color: #2a2d33;
}

[data-theme="dark"] :deep(.ant-form-item-label > label) {
  color: #8b8f98;
}

[data-theme="dark"] :deep(.ant-input),
[data-theme="dark"] :deep(.ant-input-password) {
  background: #141619 !important;
  border-color: #2a2d33 !important;
  color: #e4e6ea !important;
}
</style>
