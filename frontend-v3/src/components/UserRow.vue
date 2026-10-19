<!--
  用户管理行（v2）
  ----------------------------------------------------------------------------
  信息层次：头像块（管理员橙色 / 普通用户紫色）+ 昵称 + @用户名 + 角色标签 + 本人标记
          → 邮箱 · 手机（缺省显示「未填」）
          → 状态点（启用 / 禁用）+ 创建时间
          → hover 显操作：编辑 / 启停 / 删除
  约束（对齐后端 UserController）：
    1) 后端 listUsers 会把 password 置空，因此本组件不持有任何密码字段；
    2) DELETE /api/user/delete/{id} 拒绝删除自己（400），故「删除 / 禁用」对本人禁用；
    3) status 语义：1=启用、0=禁用；role 存的是 sys_role.code（admin / user / 自定义）。
-->
<template>
  <div class="cc-user-row" :class="{ 'is-off': !enabled }">
    <div class="cc-user-row__avatar" :class="{ 'is-admin': isAdmin }">
      <img v-if="isImageAvatar" :src="user.avatar" alt="" />
      <template v-else>{{ initial }}</template>
    </div>

    <div class="cc-user-row__main">
      <div class="cc-user-row__title">
        <span class="cc-user-row__name">{{ user.nickname || user.username }}</span>
        <span class="cc-user-row__login">@{{ user.username }}</span>
        <span class="cc-tag" :class="{ 'cc-tag--warn': isAdmin }">{{ roleLabel }}</span>
        <span v-if="isSelf" class="cc-tag cc-tag--accent">本人</span>
      </div>
      <div class="cc-user-row__meta">
        <span>
          <svg class="cc-i cc-i--sm"><use href="#i-users" /></svg>{{ user.email || '未填邮箱' }}
        </span>
        <span>
          <svg class="cc-i cc-i--sm"><use href="#i-term" /></svg>{{ user.phone || '未填手机' }}
        </span>
      </div>
    </div>

    <div class="cc-user-row__side">
      <span class="cc-status" :class="{ 'is-connected': enabled }">
        <span class="cc-status__dot" />{{ enabled ? '已启用' : '已禁用' }}
      </span>
      <span class="cc-user-row__time">创建 {{ user.createTime || '-' }}</span>
    </div>

    <div class="cc-user-row__ops">
      <button class="cc-icon-btn cc-icon-btn--sm" title="编辑" @click="$emit('edit', user)">
        <svg class="cc-i cc-i--sm"><use href="#i-brush" /></svg>
      </button>
      <button
        class="cc-icon-btn cc-icon-btn--sm"
        :title="enabled ? '禁用' : '启用'"
        :disabled="isSelf"
        @click="$emit('toggle', user)"
      >
        <svg class="cc-i cc-i--sm"><use :href="enabled ? '#i-stop' : '#i-play'" /></svg>
      </button>
      <button
        class="cc-icon-btn cc-icon-btn--sm"
        title="删除"
        :disabled="isSelf"
        @click="$emit('delete', user)"
      >
        <svg class="cc-i cc-i--sm"><use href="#i-trash" /></svg>
      </button>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'

/** 用户（对应后端 User 实体；password 永远为空——列表接口已脱敏） */
export interface UserItem {
  id: number
  username: string
  nickname?: string
  /** sys_role.code：admin / user / 自定义角色编码 */
  role: string
  email?: string
  phone?: string
  avatar?: string
  /** 1=启用，0=禁用 */
  status: number
  createTime?: string
  updateTime?: string
}

const props = withDefaults(
  defineProps<{
    user: UserItem
    /** 当前登录用户 id（用于禁用「删除 / 禁用自己」） */
    selfId?: number
  }>(),
  { selfId: 0 }
)

defineEmits<{
  (e: 'edit', user: UserItem): void
  (e: 'toggle', user: UserItem): void
  (e: 'delete', user: UserItem): void
}>()

const enabled = computed(() => props.user.status === 1)
const isAdmin = computed(() => props.user.role === 'admin')
const isSelf = computed(() => props.user.id === props.selfId)

const roleLabel = computed(() => {
  if (props.user.role === 'admin') return '管理员'
  if (props.user.role === 'user') return '普通用户'
  return props.user.role
})

const initial = computed(() => {
  const name = String(props.user.nickname || props.user.username || 'U').trim()
  return name.charAt(0).toUpperCase()
})

/** 头像支持图片 URL 或空（空则用首字母） */
const isImageAvatar = computed(() => {
  const avatar = props.user.avatar || ''
  return avatar.startsWith('http') || avatar.startsWith('/') || avatar.startsWith('data:')
})
</script>
