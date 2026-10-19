<!--
  个人信息页（v3）
  ----------------------------------------------------------------------------
  布局：页面头 → 内联统计 → 两列卡片（账号资料 / 修改密码）
  数据接缝（全部自包含，父级只接 changed 事件做全局同步）：
    GET  /api/user/profile        -> getProfile()      读取当前登录用户资料
    PUT  /api/user/profile        -> updateProfile()   昵称 / 邮箱 / 手机号 / 头像
    POST /api/avatar              -> AvatarUploader 内部上传，拿回 /api/avatar/file/<uuid>.<ext>
    PUT  /api/user/password       -> changePassword()  旧密码 + 新密码
    POST /user/random-code|login  -> performLogin()    改密后自动重登（见下）
  两个容易踩的后端语义：
    1) 改密码接口直接拿数据库里的 MD5 与传入值比较（不做加密），
       所以 oldPassword / newPassword 都必须传 MD5(明文)，而不是明文；
    2) 改密成功后后端会 invalidateUserTokens(userId)，当前 token 立刻失效 ——
       必须马上用新密码重新登录，否则下一次请求就会 401 把用户踢到登录页。
  头像「上传即保存」：上传完立即 PUT profile，避免用户以为改了但其实没保存；
  昵称/邮箱/手机号则走「保存信息」按钮（可一次性撤销）。
-->
<template>
  <div class="cc-page-scroll cc-profile">
    <div class="cc-page-inner">
      <!-- 页面头 -->
      <div class="cc-page-head">
        <div class="cc-page-head__icon">
          <svg class="cc-i"><use href="#i-users" /></svg>
        </div>
        <div>
          <h1 class="cc-page-head__title">个人信息</h1>
          <p class="cc-page-head__sub">
            账号资料与登录密码；昵称与头像会同步到侧栏和聊天气泡
          </p>
        </div>
        <div class="cc-page-head__actions">
          <button class="cc-btn" :disabled="loading" @click="load">
            <svg class="cc-i cc-i--sm"><use href="#i-refresh" /></svg>刷新
          </button>
        </div>
      </div>

      <!-- 统计 -->
      <div class="cc-stats-inline" style="margin-bottom: var(--sp-4)">
        <span>账号 <b>{{ form.username || '—' }}</b></span><span class="cc-dot" />
        <span>角色 <b>{{ roleLabel }}</b></span><span class="cc-dot" />
        <span>邮箱 <b>{{ form.email ? '已绑定' : '未绑定' }}</b></span><span class="cc-dot" />
        <span>手机 <b>{{ form.phone ? '已绑定' : '未绑定' }}</b></span>
      </div>

      <div v-if="error" class="cc-profile-error" role="alert">
        <svg class="cc-i cc-i--sm"><use href="#i-alert" /></svg>
        <span>{{ error }}</span>
      </div>

      <div class="cc-profile-grid">
        <!-- ========== 账号资料 ========== -->
        <section class="cc-profile-card">
          <div class="cc-profile-card__head">
            <div class="cc-profile-card__icon">
              <svg class="cc-i cc-i--sm"><use href="#i-users" /></svg>
            </div>
            <div>
              <div class="cc-profile-card__title">账号资料</div>
              <div class="cc-profile-card__desc">用户名不可修改，昵称可随时调整</div>
            </div>
          </div>

          <!-- 头像 + 身份摘要 -->
          <div class="cc-profile-avatar">
            <AvatarUploader
              v-model="form.avatar"
              :name="form.nickname || form.username"
              :size="76"
              :disabled="saving"
              @uploading="avatarUploading = $event"
              @uploaded="onAvatarUploaded"
            />
            <div class="cc-profile-avatar__meta">
              <div class="cc-profile-avatar__name">{{ form.nickname || form.username || '未登录' }}</div>
              <div class="cc-profile-avatar__account">@{{ form.username || '—' }}</div>
              <div class="cc-profile-avatar__tags">
                <span class="cc-tag" :class="form.role === 'admin' ? 'cc-tag--accent' : ''">
                  {{ roleLabel }}
                </span>
                <span v-if="form.createTime" class="cc-profile-avatar__since">
                  创建于 {{ form.createTime }}
                </span>
              </div>
            </div>
          </div>

          <div class="cc-form">
            <div class="cc-field">
              <span class="cc-field__label">用户名</span>
              <input class="cc-input" :value="form.username" disabled />
              <div class="cc-field__hint">登录账号，创建后不可修改</div>
            </div>

            <div class="cc-field">
              <span class="cc-field__label">昵称</span>
              <input
                v-model="form.nickname"
                class="cc-input"
                type="text"
                maxlength="50"
                placeholder="显示在侧栏与聊天气泡"
                :disabled="saving"
              />
            </div>

            <div class="cc-field-row">
              <div class="cc-field">
                <span class="cc-field__label">邮箱</span>
                <input
                  v-model="form.email"
                  class="cc-input"
                  type="email"
                  maxlength="100"
                  placeholder="name@example.com"
                  :disabled="saving"
                />
              </div>
              <div class="cc-field">
                <span class="cc-field__label">手机号</span>
                <input
                  v-model="form.phone"
                  class="cc-input"
                  type="tel"
                  maxlength="20"
                  placeholder="选填"
                  :disabled="saving"
                />
              </div>
            </div>
          </div>

          <div class="cc-profile-actions">
            <button
              class="cc-btn cc-btn--primary"
              :disabled="saving || avatarUploading || !dirty"
              @click="onSaveProfile"
            >
              <svg v-if="saving" class="cc-i cc-i--sm cc-login__spin"><use href="#i-refresh" /></svg>
              {{ saving ? '保存中…' : '保存信息' }}
            </button>
            <button class="cc-btn" :disabled="saving || !dirty" @click="resetForm">还原</button>
            <span class="cc-profile-actions__hint">头像上传后立即生效，其余改动需点「保存信息」</span>
          </div>
        </section>

        <!-- ========== 修改密码 ========== -->
        <section class="cc-profile-card">
          <div class="cc-profile-card__head">
            <div class="cc-profile-card__icon">
              <svg class="cc-i cc-i--sm"><use href="#i-lock" /></svg>
            </div>
            <div>
              <div class="cc-profile-card__title">修改密码</div>
              <div class="cc-profile-card__desc">修改成功后会自动用新密码登录</div>
            </div>
          </div>

          <div class="cc-form">
            <div class="cc-field">
              <span class="cc-field__label">当前密码<em>*</em></span>
              <div class="cc-profile-pwd">
                <input
                  v-model="pwd.oldPassword"
                  class="cc-input"
                  :type="showOld ? 'text' : 'password'"
                  autocomplete="current-password"
                  placeholder="请输入当前密码"
                  :disabled="passwordSaving"
                />
                <button
                  type="button"
                  class="cc-profile-eye"
                  :title="showOld ? '隐藏密码' : '显示密码'"
                  tabindex="-1"
                  @click="showOld = !showOld"
                >
                  <svg class="cc-i cc-i--sm"><use :href="showOld ? '#i-eye-off' : '#i-eye'" /></svg>
                </button>
              </div>
            </div>

            <div class="cc-field">
              <span class="cc-field__label">新密码<em>*</em></span>
              <div class="cc-profile-pwd">
                <input
                  v-model="pwd.newPassword"
                  class="cc-input"
                  :type="showNew ? 'text' : 'password'"
                  autocomplete="new-password"
                  placeholder="至少 6 位"
                  :disabled="passwordSaving"
                />
                <button
                  type="button"
                  class="cc-profile-eye"
                  :title="showNew ? '隐藏密码' : '显示密码'"
                  tabindex="-1"
                  @click="showNew = !showNew"
                >
                  <svg class="cc-i cc-i--sm"><use :href="showNew ? '#i-eye-off' : '#i-eye'" /></svg>
                </button>
              </div>
            </div>

            <div class="cc-field">
              <span class="cc-field__label">确认新密码<em>*</em></span>
              <div class="cc-profile-pwd">
                <input
                  v-model="pwd.confirmPassword"
                  class="cc-input"
                  :type="showNew ? 'text' : 'password'"
                  autocomplete="new-password"
                  placeholder="再次输入新密码"
                  :disabled="passwordSaving"
                />
              </div>
              <div v-if="pwdHint" class="cc-field__hint" :class="{ 'is-warn': pwdWarn }">{{ pwdHint }}</div>
            </div>
          </div>

          <div class="cc-profile-note">
            密码以 MD5 二次加密后传输；修改成功会作废当前登录令牌，本页会自动用新密码重新登录。
          </div>

          <div class="cc-profile-actions">
            <button
              class="cc-btn cc-btn--primary"
              :disabled="passwordSaving || !passwordReady"
              @click="onChangePassword"
            >
              <svg v-if="passwordSaving" class="cc-i cc-i--sm cc-login__spin"><use href="#i-refresh" /></svg>
              {{ passwordSaving ? '处理中…' : '修改密码' }}
            </button>
          </div>
        </section>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import AvatarUploader from '../components/AvatarUploader.vue'
import { changePassword, getProfile, updateProfile } from '../api/user-admin'
import { performLogin } from '../utils/http-client'
import { md5 } from '../utils/md5'
import { useUserStore } from '../store/user'
import { toast } from '../utils/toast'

const emit = defineEmits<{
  /** 资料或头像已变更：父级据此 refreshProfile()，让侧栏 / 聊天气泡同步 */
  (e: 'changed'): void
}>()

const router = useRouter()
const userStore = useUserStore()

/** 页面数据（用户名 / 角色 / 创建时间只读，其余可编辑） */
const form = reactive({
  id: 0,
  username: '',
  nickname: '',
  role: '',
  email: '',
  phone: '',
  avatar: '',
  createTime: ''
})

/** 上一次保存成功的快照：用于「有无改动」判断与「还原」 */
const origin = reactive({ nickname: '', email: '', phone: '', avatar: '' })

const loading = ref(false)
const saving = ref(false)
const passwordSaving = ref(false)
const avatarUploading = ref(false)
const error = ref('')

/** 密码表单 */
const pwd = reactive({ oldPassword: '', newPassword: '', confirmPassword: '' })
const showOld = ref(false)
const showNew = ref(false)

const roleLabel = computed(() => {
  if (form.role === 'admin') return '管理员'
  if (form.role === 'user') return '普通用户'
  return form.role || '—'
})

/** 是否有未保存的资料改动 */
const dirty = computed(
  () =>
    form.nickname !== origin.nickname ||
    form.email !== origin.email ||
    form.phone !== origin.phone ||
    form.avatar !== origin.avatar
)

/** 新密码与确认密码的一致性提示（满足条件时才真正提交） */
const pwdWarn = computed(() => {
  if (!pwd.newPassword && !pwd.confirmPassword) return false
  if (pwd.newPassword && pwd.newPassword.length < 6) return true
  if (pwd.confirmPassword && pwd.newPassword !== pwd.confirmPassword) return true
  return false
})

const pwdHint = computed(() => {
  if (!pwd.newPassword && !pwd.confirmPassword) return ''
  if (pwd.newPassword && pwd.newPassword.length < 6) return '新密码至少 6 位'
  if (pwd.confirmPassword && pwd.newPassword !== pwd.confirmPassword) return '两次输入的新密码不一致'
  return ''
})

const passwordReady = computed(
  () =>
    !!pwd.oldPassword &&
    !!pwd.newPassword &&
    pwd.newPassword.length >= 6 &&
    pwd.newPassword === pwd.confirmPassword &&
    pwd.newPassword !== pwd.oldPassword
)

function readError(err: unknown, fallback: string): string {
  const msg = err instanceof Error ? err.message : String(err || '')
  return msg.trim() || fallback
}

/** 后端 LocalDateTime 序列化成 ISO 串，压成 `YYYY-MM-DD HH:mm` 便于阅读 */
function formatTime(value?: string | null): string {
  if (!value) return ''
  const text = String(value).replace(/-/g, '/').replace('T', ' ')
  const date = new Date(text)
  if (Number.isNaN(date.getTime())) return String(value).slice(0, 16).replace('T', ' ')
  const pad = (n: number) => String(n).padStart(2, '0')
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())} ${pad(date.getHours())}:${pad(date.getMinutes())}`
}

/** 拉取当前登录用户资料 */
async function load() {
  if (loading.value) return
  loading.value = true
  error.value = ''
  try {
    const res = await getProfile()
    const data = res.data
    if (!data) {
      error.value = '未获取到用户资料'
      return
    }
    form.id = data.id ?? 0
    form.username = data.username || ''
    form.nickname = data.nickname || ''
    form.role = data.role || ''
    form.email = data.email || ''
    form.phone = data.phone || ''
    form.avatar = data.avatar || ''
    form.createTime = formatTime(data.createTime)
    Object.assign(origin, {
      nickname: form.nickname,
      email: form.email,
      phone: form.phone,
      avatar: form.avatar
    })
  } catch (err) {
    error.value = readError(err, '加载个人信息失败，请确认后端服务已启动')
  } finally {
    loading.value = false
  }
}

/** 还原到上次保存的状态 */
function resetForm() {
  form.nickname = origin.nickname
  form.email = origin.email
  form.phone = origin.phone
  form.avatar = origin.avatar
}

function validateProfile(): string {
  if (form.nickname.trim().length > 50) return '昵称最多 50 个字符'
  const email = form.email.trim()
  if (email && !/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(email)) return '邮箱格式不正确'
  const phone = form.phone.trim()
  if (phone && !/^[\d+\-\s()]{5,20}$/.test(phone)) return '手机号格式不正确'
  return ''
}

/** 保存昵称 / 邮箱 / 手机号 / 头像 */
async function onSaveProfile() {
  if (saving.value) return
  const invalid = validateProfile()
  if (invalid) {
    toast.warning(invalid)
    return
  }

  saving.value = true
  try {
    await updateProfile({
      nickname: form.nickname.trim(),
      email: form.email.trim(),
      phone: form.phone.trim(),
      avatar: form.avatar
    })
    Object.assign(origin, {
      nickname: form.nickname.trim(),
      email: form.email.trim(),
      phone: form.phone.trim(),
      avatar: form.avatar
    })
    // 本地回写 + 通知父级刷新身份快照，侧栏名字/头像立即变化
    form.nickname = origin.nickname
    form.email = origin.email
    form.phone = origin.phone
    emit('changed')
    toast.success('个人信息已更新')
  } catch (err) {
    toast.error(readError(err, '保存失败'))
  } finally {
    saving.value = false
  }
}

/**
 * 头像上传完成：立即写库。
 *
 * <p>AvatarUploader 只负责拿到 URL，不写库；但头像这种「所见即所得」的字段
 * 若还要再点一次保存，用户很容易漏点后以为没生效，所以这里直接落库。</p>
 */
async function onAvatarUploaded(url: string) {
  try {
    await updateProfile({ avatar: url })
    origin.avatar = url
    form.avatar = url
    emit('changed')
    toast.success('头像已更新')
  } catch (err) {
    // 写库失败就把本地值退回旧头像，避免界面显示成功但服务端没变
    form.avatar = origin.avatar
    toast.error(readError(err, '头像保存失败'))
  }
}

/**
 * 修改密码。
 *
 * <p>关键点（后端实现决定）：</p>
 * <ol>
 *   <li>{@code UserServiceImpl.changePassword} 直接拿库里的 MD5 与传入值比较，
 *       <b>不做加密</b>，所以这里必须传 {@code md5(明文)}；</li>
 *   <li>改完会 {@code invalidateUserTokens(userId)}，当前 token 立刻失效 ——
 *       紧接着用新密码重新登录，否则下一次请求就 401 被踢到登录页。</li>
 * </ol>
 */
async function onChangePassword() {
  if (passwordSaving.value) return
  if (!passwordReady.value) {
    toast.warning(pwdHint.value || '请完整填写密码')
    return
  }

  passwordSaving.value = true
  const newPlain = pwd.newPassword
  const username = form.username

  try {
    await changePassword({
      oldPassword: md5(pwd.oldPassword),
      newPassword: md5(newPlain)
    })
  } catch (err) {
    passwordSaving.value = false
    toast.error(readError(err, '密码修改失败'))
    return
  }

  // 后端已作废所有 token：清空输入并立刻重新登录
  pwd.oldPassword = ''
  pwd.newPassword = ''
  pwd.confirmPassword = ''

  try {
    const result = await performLogin(username, newPlain)
    userStore.saveLoginResponse({ code: 200, data: { ...result } })
    emit('changed')
    toast.success('密码已修改，已自动重新登录')
  } catch (err) {
    toast.warning('密码已修改，请使用新密码重新登录', {
      description: readError(err, '自动登录失败')
    })
    await router.replace({ path: '/login', query: { redirect: '/profile' } })
  } finally {
    passwordSaving.value = false
  }
}

onMounted(() => {
  void load()
})
</script>
