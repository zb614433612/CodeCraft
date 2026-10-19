<!--
  用户编辑抽屉（v2）
  ----------------------------------------------------------------------------
  与后端对应：
    POST /api/user/create   创建（password 提交明文，后端 Md5Util 加密后入库）
    PUT  /api/user/update   更新（password 留空 = 不修改，后端继承原密码）
  数据接缝：
    v-model:open -> 抽屉开关
    user         -> 编辑对象（null 表示新增）
    emit('save') -> payload 交给父组件发请求
  校验：
    1) 用户名必填；创建时密码至少 6 位（编辑留空表示不改密码）；
    2) 角色取 sys_role.code，默认 user；管理员可访问用户管理 / 菜单权限 / 模型配置；
    3) 状态 1=启用、0=禁用（禁用后无法登录，历史数据保留）。
-->
<template>
  <aside class="cc-drawer" :class="{ 'is-open': open }" style="--drawer-w: 480px">
    <DrawerResizer storage-key="user-edit" />
    <div class="cc-drawer__head">
      <div style="font-size: var(--fs-md); font-weight: 700">{{ isNew ? '新增用户' : '编辑用户' }}</div>
      <span class="cc-tag">{{ isNew ? 'new' : form.username }}</span>
      <button class="cc-icon-btn" style="margin-left: auto" title="关闭" @click="close">
        <svg class="cc-i cc-i--sm" style="transform: rotate(45deg)"><use href="#i-plus" /></svg>
      </button>
    </div>

    <div class="cc-drawer__body">
      <div class="cc-form">
        <!-- 头像：上传即得 URL，随保存一起提交（取消则丢弃，不产生脏数据） -->
        <div class="cc-avatar-field">
          <AvatarUploader
            v-model="form.avatar"
            :name="form.nickname || form.username"
            :size="64"
            :disabled="avatarUploading"
            @uploading="avatarUploading = $event"
          />
          <div class="cc-avatar-field__meta">
            <div class="cc-avatar-field__title">头像</div>
            <div class="cc-avatar-field__desc">
              点击上传，支持 png / jpg / gif / webp / bmp，超过 200KB 自动压缩。<br />
              留空则显示昵称首字。
            </div>
          </div>
        </div>

        <div class="cc-field-row">
          <div class="cc-field">
            <label class="cc-field__label">用户名<em>*</em></label>
            <input ref="nameInput" v-model="form.username" class="cc-input" placeholder="登录账号，如 zhangsan" autocomplete="off" />
            <div class="cc-field__hint">全局唯一；修改后立即影响登录</div>
          </div>
          <div class="cc-field">
            <label class="cc-field__label">昵称</label>
            <input v-model="form.nickname" class="cc-input" placeholder="显示名称，默认取用户名" />
          </div>
        </div>

        <div class="cc-field">
          <label class="cc-field__label">密码<em v-if="isNew">*</em></label>
          <input
            v-model="form.password"
            class="cc-input"
            type="password"
            autocomplete="new-password"
            :placeholder="isNew ? '至少 6 位' : '留空表示不修改'"
          />
          <div class="cc-field__hint">
            {{ isNew ? '后端按 MD5 加密存储；创建后不可查看' : '填写则覆盖为新密码；留空保持原密码' }}
          </div>
          <div v-if="pwdError" class="cc-prov-cap is-warn">{{ pwdError }}</div>
        </div>

        <div class="cc-field">
          <label class="cc-field__label">角色</label>
          <div class="cc-seg" style="width: fit-content">
            <button
              v-for="opt in ROLE_OPTIONS"
              :key="opt.value"
              :class="{ 'is-active': form.role === opt.value }"
              @click="form.role = opt.value"
            >
              {{ opt.label }}
            </button>
          </div>
          <div class="cc-field__hint">管理员可访问用户管理、菜单权限与模型配置等页面</div>
        </div>

        <div class="cc-switch-row">
          <div class="cc-switch-row__text">
            <div class="cc-switch-row__title">启用账号</div>
            <div class="cc-switch-row__desc">禁用后无法登录，历史数据保留</div>
          </div>
          <span class="cc-switch" :class="{ 'is-on': form.status === 1 }" @click="form.status = form.status === 1 ? 0 : 1" />
        </div>

        <div class="cc-field-row">
          <div class="cc-field">
            <label class="cc-field__label">邮箱</label>
            <input v-model="form.email" class="cc-input" placeholder="name@example.com" />
          </div>
          <div class="cc-field">
            <label class="cc-field__label">手机号</label>
            <input v-model="form.phone" class="cc-input" placeholder="选填" />
          </div>
        </div>
      </div>
    </div>

    <div class="cc-drawer__foot">
      <button class="cc-btn" @click="close">取消</button>
      <button class="cc-btn cc-btn--primary" :disabled="!canSave" @click="submit">保存</button>
    </div>
  </aside>
</template>

<script setup lang="ts">
import DrawerResizer from './DrawerResizer.vue'
import AvatarUploader from './AvatarUploader.vue'
import { computed, reactive, ref, watch } from 'vue'
import type { UserItem } from './UserRow.vue'

/** 保存载荷：password 为空字符串表示不修改（对齐后端 updateUser 语义） */
export interface UserSavePayload {
  id?: number
  username: string
  nickname: string
  /** 明文密码；空串表示不修改 */
  password: string
  role: string
  status: number
  email: string
  phone: string
  /** 头像 URL（空串表示清除，由后端写入空值后列表回落首字） */
  avatar: string
}

const props = withDefaults(
  defineProps<{
    open: boolean
    /** 编辑对象；null 表示新增 */
    user?: UserItem | null
  }>(),
  { user: null }
)

const emit = defineEmits<{
  (e: 'update:open', value: boolean): void
  (e: 'save', payload: UserSavePayload): void
}>()

const ROLE_OPTIONS = [
  { label: '普通用户', value: 'user' },
  { label: '管理员', value: 'admin' }
]

const nameInput = ref<HTMLInputElement | null>(null)
/** 头像上传进行中：先禁用保存，避免把旧头像 URL 提交上去 */
const avatarUploading = ref(false)

const form = reactive<UserSavePayload>({
  username: '',
  nickname: '',
  password: '',
  role: 'user',
  status: 1,
  email: '',
  phone: '',
  avatar: ''
})

const isNew = computed(() => !props.user || !props.user.id)

const pwdError = computed(() => {
  if (!isNew.value) return ''
  if (!form.password) return ''
  return form.password.trim().length < 6 ? '密码至少 6 位' : ''
})

/**
 * 保存按钮可用性
 * ----------------------------------------------------------------------------
 * 与 `submit()` 的静默校验同源：点得动却没反应是最差体验，
 * 故把校验前移到按钮 disabled，用户一眼能看出「还差什么」。
 */
const canSave = computed(() => {
  if (!form.username.trim()) return false
  if (isNew.value && form.password.trim().length < 6) return false
  // 上传未落定就提交会把旧 URL 写回去，先卡住
  if (avatarUploading.value) return false
  return true
})

/** 打开 / 切换编辑对象时回填 */
watch(
  () => [props.open, props.user] as const,
  () => {
    if (!props.open) return
    const user = props.user
    form.username = user ? user.username : ''
    form.nickname = user ? user.nickname || '' : ''
    form.password = ''
    form.role = user ? user.role : 'user'
    form.status = user ? user.status : 1
    form.email = user ? user.email || '' : ''
    form.phone = user ? user.phone || '' : ''
    form.avatar = user ? user.avatar || '' : ''
    form.id = user ? user.id : undefined
    avatarUploading.value = false
    if (isNew.value) window.setTimeout(() => nameInput.value?.focus(), 60)
  },
  { immediate: true }
)

function close() {
  emit('update:open', false)
}

function submit() {
  if (!form.username.trim()) return
  if (isNew.value && form.password.trim().length < 6) return
  if (avatarUploading.value) return
  emit('save', {
    id: isNew.value ? undefined : form.id,
    username: form.username.trim(),
    nickname: form.nickname.trim(),
    password: form.password,
    role: form.role,
    status: form.status,
    email: form.email.trim(),
    phone: form.phone.trim(),
    avatar: form.avatar.trim()
  })
}
</script>
