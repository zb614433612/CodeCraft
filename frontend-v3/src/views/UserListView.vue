<!--
  用户管理页（v2）
  ----------------------------------------------------------------------------
  布局：页面头（刷新 / 新增）→ 内联统计 → 工具栏（搜索 + 角色 + 状态）→ 用户行列表 → 分页 → 编辑抽屉
  数据接缝：
    users               -> GET  /api/user/list?page&pageSize&search（管理员；password 已脱敏）
    emit('search')      -> 关键词搜索（后端 search 参数，按用户名 / 昵称 / 邮箱模糊匹配）
    emit('filter-change')-> 角色 / 状态筛选（演示为本地过滤，真实实现可加查询参数）
    emit('page-change') -> 分页（PageResult.page / totalPages）
    emit('save')        -> POST /api/user/create 或 PUT /api/user/update
    emit('delete')      -> DELETE /api/user/delete/{id}
    emit('toggle')      -> PUT /api/user/update（只提交 id + status）
    emit('refresh')     -> 重新拉取列表
  约束：
    1) 后端拒绝删除自己（400），故 selfId 对应的行禁用「删除 / 禁用」；
    2) 列表接口是分页的（PageResult），因此统计口径标注「本页」，总数用 total；
    3) 新建用户密码提交明文，后端 MD5 加密后入库。
-->
<template>
  <div class="cc-page-scroll cc-users">
    <div class="cc-page-inner">
      <!-- 页面头 -->
      <div class="cc-page-head">
        <div class="cc-page-head__icon">
          <svg class="cc-i"><use href="#i-users" /></svg>
        </div>
        <div>
          <h1 class="cc-page-head__title">用户管理</h1>
          <p class="cc-page-head__sub">管理系统账号、角色与启用状态（接口：/api/user，需管理员）</p>
        </div>
        <div class="cc-page-head__actions">
          <button class="cc-btn" @click="$emit('refresh')">
            <svg class="cc-i cc-i--sm"><use href="#i-refresh" /></svg>刷新
          </button>
          <button class="cc-btn cc-btn--primary" @click="openCreate">
            <svg class="cc-i cc-i--sm"><use href="#i-plus" /></svg>新增用户
          </button>
        </div>
      </div>

      <!-- 统计 -->
      <div class="cc-stats-inline" style="margin-bottom: var(--sp-4)">
        <span>用户总数 <b>{{ total || users.length }}</b></span><span class="cc-dot" />
        <span>本页已启用 <b>{{ activeCount }}</b></span><span class="cc-dot" />
        <span>本页管理员 <b>{{ adminCount }}</b></span><span class="cc-dot" />
        <span>本页普通用户 <b>{{ memberCount }}</b></span>
      </div>

      <!-- 加载中 / 接口失败提示（内联样式，不新增 CSS 类） -->
      <div
        v-if="loading || error"
        :style="{
          marginBottom: 'var(--sp-3)',
          fontSize: 'var(--fs-sm)',
          color: error ? 'var(--danger)' : 'var(--text-3)'
        }"
      >
        {{ error || '正在加载用户列表…' }}
      </div>

      <!-- 工具栏 -->
      <div class="cc-toolbar">
        <label class="cc-search">
          <svg class="cc-i cc-i--sm"><use href="#i-search" /></svg>
          <input v-model="keyword" placeholder="搜索用户名 / 昵称 / 邮箱" @keyup.enter="applySearch" />
        </label>
        <div class="cc-seg">
          <button
            v-for="opt in ROLE_FILTERS"
            :key="opt.value"
            :class="{ 'is-active': roleFilter === opt.value }"
            @click="setRoleFilter(opt.value)"
          >
            {{ opt.label }}
          </button>
        </div>
        <div class="cc-seg">
          <button
            v-for="opt in STATUS_FILTERS"
            :key="opt.value"
            :class="{ 'is-active': statusFilter === opt.value }"
            @click="setStatusFilter(opt.value)"
          >
            {{ opt.label }}
          </button>
        </div>
        <div style="margin-left: auto; font-size: var(--fs-sm); color: var(--text-3)">
          匹配 <b style="color: var(--text-1)">{{ filteredUsers.length }}</b> 条
        </div>
      </div>

      <!-- 列表 -->
      <div class="cc-user-list">
        <UserRow
          v-for="user in filteredUsers"
          :key="user.id"
          :user="user"
          :self-id="selfId"
          @edit="openEdit"
          @toggle="$emit('toggle', $event)"
          @delete="confirmDelete"
        />
      </div>

      <!-- 分页（PageResult 分页；单页时隐藏） -->
      <div v-if="totalPages > 1" class="cc-pager">
        <button class="cc-btn" :disabled="currentPage <= 1" @click="$emit('page-change', currentPage - 1)">上一页</button>
        <span>第 {{ currentPage }} / {{ totalPages }} 页</span>
        <button class="cc-btn" :disabled="currentPage >= totalPages" @click="$emit('page-change', currentPage + 1)">下一页</button>
      </div>

      <!-- 空状态 -->
      <div v-if="!filteredUsers.length" class="cc-agent-empty">
        <div class="cc-agent-empty__icon">
          <svg class="cc-i" style="width: 28px; height: 28px"><use href="#i-users" /></svg>
        </div>
        <div style="font-size: var(--fs-base); font-weight: 600; color: var(--text-1)">
          {{ users.length ? '没有匹配的用户' : '还没有任何用户' }}
        </div>
        <div style="font-size: var(--fs-sm); color: var(--text-3)">
          {{ users.length ? '换个关键词，或清空角色 / 状态筛选' : '点击「新增用户」创建第一个账号' }}
        </div>
        <button v-if="!users.length" class="cc-btn cc-btn--primary" @click="openCreate">
          <svg class="cc-i cc-i--sm"><use href="#i-plus" /></svg>新增用户
        </button>
      </div>
    </div>

    <!-- 遮罩 + 编辑抽屉 -->
    <div v-if="drawerOpen" class="cc-mask is-open" @click="drawerOpen = false" />
    <UserEditDrawer
      v-model:open="drawerOpen"
      :user="editing"
      @save="onSave"
    />
  </div>
</template>

<script setup lang="ts">
import { computed, ref, watch, onMounted } from 'vue'
import UserRow, { type UserItem } from '../components/UserRow.vue'
import UserEditDrawer, { type UserSavePayload } from '../components/UserEditDrawer.vue'

type RoleFilter = '' | 'admin' | 'user'
type StatusFilter = '' | '1' | '0'

const props = withDefaults(
  defineProps<{
    users?: UserItem[]
    /** PageResult.total（不受当前页影响） */
    total?: number
    page?: number
    pageSize?: number
    /** 当前登录用户 id（禁用「删除 / 禁用本人」） */
    selfId?: number
    /** 列表请求中（GET /user/list） */
    loading?: boolean
    /** 接口失败原因（非空时展示提示条） */
    error?: string
    /**
     * 进入页面时的数据加载（由 App.vue 的 PAGE_BINDINGS 注入）
     * 页面随路由真实挂载，所以在这里调一次即可；重复进页由父级的「已加载 / 加载中 / 已失败」跳过
     */
    load?: () => void
  }>(),
  {
    users: () => [],
    total: 0,
    page: 1,
    pageSize: 10,
    selfId: 0,
    loading: false,
    error: ''
  }
)

const emit = defineEmits<{
  (e: 'search', keyword: string): void
  (e: 'filter-change', payload: { role: string; status: string }): void
  (e: 'page-change', page: number): void
  (e: 'save', payload: UserSavePayload): void
  (e: 'delete', user: UserItem): void
  (e: 'toggle', user: UserItem): void
  (e: 'refresh'): void
}>()

const ROLE_FILTERS: Array<{ label: string; value: RoleFilter }> = [
  { label: '全部', value: '' },
  { label: '管理员', value: 'admin' },
  { label: '普通用户', value: 'user' }
]

const STATUS_FILTERS: Array<{ label: string; value: StatusFilter }> = [
  { label: '全部状态', value: '' },
  { label: '已启用', value: '1' },
  { label: '已禁用', value: '0' }
]

const keyword = ref('')
const roleFilter = ref<RoleFilter>('')
const statusFilter = ref<StatusFilter>('')
const drawerOpen = ref(false)
const editing = ref<UserItem | null>(null)

const currentPage = computed(() => props.page || 1)
const totalPages = computed(() => {
  const size = props.pageSize || 10
  return Math.max(1, Math.ceil((props.total || 0) / size))
})

/** 演示环境为本地过滤；接真实接口后改为由后端 search / 筛选参数返回 */
const filteredUsers = computed(() => {
  const kw = keyword.value.trim().toLowerCase()
  return props.users.filter(user => {
    if (roleFilter.value && user.role !== roleFilter.value) return false
    if (statusFilter.value !== '' && String(user.status) !== statusFilter.value) return false
    if (!kw) return true
    const hay = [user.username, user.nickname, user.email, user.phone].join(' ').toLowerCase()
    return hay.includes(kw)
  })
})

const activeCount = computed(() => props.users.filter(u => u.status === 1).length)
const adminCount = computed(() => props.users.filter(u => u.role === 'admin').length)
const memberCount = computed(() => props.users.filter(u => u.role !== 'admin').length)

function applySearch() {
  emit('search', keyword.value.trim())
}

function setRoleFilter(value: RoleFilter) {
  roleFilter.value = value
  emit('filter-change', { role: value, status: statusFilter.value })
}

function setStatusFilter(value: StatusFilter) {
  statusFilter.value = value
  emit('filter-change', { role: roleFilter.value, status: value })
}

function openCreate() {
  editing.value = null
  drawerOpen.value = true
}

function openEdit(user: UserItem) {
  editing.value = user
  drawerOpen.value = true
}

function onSave(payload: UserSavePayload) {
  emit('save', payload)
  drawerOpen.value = false
}

function confirmDelete(user: UserItem) {
  emit('delete', user)
}

/** 抽屉关闭后清空编辑对象，避免下次打开闪现旧数据 */
watch(drawerOpen, open => {
  if (!open) editing.value = null
})

/**
 * 「进入页面才拉数据」的触发点（原 App.vue 的 PAGE_LOADERS 已下沉至此）
 * 父级通过 props.load 注入具体加载函数，组件只负责在最合适的时机调用它。
 */
onMounted(() => props.load?.())
</script>
