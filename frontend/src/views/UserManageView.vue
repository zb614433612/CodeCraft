<template>
  <div class="user-manage">
    <!-- ===== 头部卡片 ===== -->
    <div class="page-header">
      <div class="header-left">
        <div class="header-icon-box">
          <span class="header-icon">👥</span>
        </div>
        <div class="header-text">
          <h2 class="header-title">用户管理</h2>
          <p class="header-subtitle">管理系统用户账号，包括创建、编辑、删除以及角色分配</p>
        </div>
      </div>
      <div class="header-right">
        <div class="stat-item">
          <span class="stat-num">{{ total }}</span>
          <span class="stat-label">用户总数</span>
        </div>
        <div class="stat-divider"></div>
        <div class="stat-item">
          <span class="stat-num">{{ activeUserCount }}</span>
          <span class="stat-label">已启用</span>
        </div>
      </div>
    </div>

    <!-- ===== 工具栏 ===== -->
    <div class="toolbar-card">
      <div class="toolbar-left">
        <div class="search-box">
          <SearchOutlined class="search-icon" />
          <input
            v-model="searchKeyword"
            placeholder="搜索用户名、昵称或邮箱..."
            class="search-input"
            @keyup.enter="handleSearch"
          />
          <CloseCircleFilled
            v-if="searchKeyword"
            class="search-clear"
            @click="searchKeyword = ''; handleSearch()"
          />
        </div>
      </div>
      <div class="toolbar-right">
        <a-button type="primary" @click="openCreateModal" class="create-btn">
          <template #icon><PlusOutlined /></template>
          新增用户
        </a-button>
      </div>
    </div>

    <!-- ===== 内容区 - 表格卡片 ===== -->
    <div class="content-card">
      <div v-if="loading && userList.length === 0" class="state-box">
        <a-spin size="large" />
        <p class="state-text">加载用户列表中...</p>
      </div>

      <div v-else-if="userList.length === 0" class="state-box empty-state">
        <div class="empty-illustration">
          <span class="empty-icon">📭</span>
        </div>
        <p class="empty-title">暂无用户数据</p>
        <p class="empty-desc">还没有任何用户，点击上方「新增用户」按钮创建第一个账号</p>
      </div>

      <a-table
        v-else
        :dataSource="userList"
        :columns="columns"
        :loading="loading"
        :pagination="paginationConfig"
        rowKey="id"
        @change="handleTableChange"
        size="middle"
        class="user-table"
      >
        <template #bodyCell="{ column, record }">
          <template v-if="column.key === 'role'">
            <a-tag :color="record.role === 'admin' ? 'red' : 'blue'">
              {{ getRoleName(record.role) }}
            </a-tag>
          </template>

          <template v-if="column.key === 'status'">
            <span class="status-dot" :class="record.status === 1 ? 'active' : 'inactive'"></span>
            <span class="status-text">{{ record.status === 1 ? '启用' : '禁用' }}</span>
          </template>

          <template v-if="column.key === 'createTime'">
            {{ formatTime(record.createTime) }}
          </template>

          <template v-if="column.key === 'action'">
            <a-space :size="4">
              <a-button type="link" size="small" @click="openEditModal(record)" class="action-link">编辑</a-button>
              <a-popconfirm
                title="确定要删除该用户吗？"
                ok-text="确定"
                cancel-text="取消"
                @confirm="handleDelete(record.id)"
              >
                <a-button
                  type="link"
                  danger
                  size="small"
                  :disabled="record.id === currentUserId"
                  class="action-link"
                >删除</a-button>
              </a-popconfirm>
            </a-space>
          </template>
        </template>
      </a-table>
    </div>

    <!-- ===== 新增/编辑用户弹窗 ===== -->
    <a-modal
      v-model:open="modalVisible"
      :title="null"
      :confirmLoading="modalConfirmLoading"
      @ok="handleModalOk"
      @cancel="handleModalCancel"
      :width="540"
      :destroy-on-close="true"
      :body-style="{ padding: 0 }"
      wrap-class-name="user-modal-wrap"
    >
      <template #footer>
        <div class="modal-footer">
          <a-button @click="handleModalCancel" class="modal-cancel-btn">取消</a-button>
          <a-button type="primary" :loading="modalConfirmLoading" @click="handleModalOk" class="modal-save-btn">
            {{ isEditing ? '保存修改' : '创建用户' }}
          </a-button>
        </div>
      </template>

      <div class="modal-header">
        <div class="modal-header-icon">
          {{ isEditing ? '✏️' : '✨' }}
        </div>
        <div class="modal-header-text">
          <h3>{{ isEditing ? '编辑用户' : '新增用户' }}</h3>
          <p>{{ isEditing ? '修改用户的角色、联系方式和状态' : '创建一个新的系统用户账号' }}</p>
        </div>
      </div>

      <div class="modal-body">
        <a-form
          ref="formRef"
          :model="formData"
          :rules="formRules"
          :label-col="{ span: 4 }"
          :wrapper-col="{ span: 19 }"
          class="user-form"
        >
          <div class="form-section">
            <div class="form-section-title">账号信息</div>
            <a-form-item label="用户名" name="username">
              <a-input
                v-model:value="formData.username"
                :disabled="isEditing"
                placeholder="请输入用户名"
                size="large"
              />
            </a-form-item>

            <a-form-item
              :label="isEditing ? '新密码' : '密码'"
              name="password"
              :rules="isEditing ? editPasswordRules : passwordRules"
            >
              <a-input-password
                v-model:value="formData.password"
                :placeholder="isEditing ? '留空则不修改密码' : '请输入密码'"
                size="large"
              />
            </a-form-item>
          </div>

          <div class="form-section">
            <div class="form-section-title">个人资料</div>
            <a-form-item label="昵称" name="nickname">
              <a-input v-model:value="formData.nickname" placeholder="请输入昵称" />
            </a-form-item>

            <a-form-item label="角色" name="role">
              <a-select v-model:value="formData.role" placeholder="请选择角色">
                <a-select-option v-for="role in roleOptions" :key="role.code" :value="role.code">
                  {{ role.name }}
                </a-select-option>
              </a-select>
            </a-form-item>

            <a-form-item label="邮箱" name="email">
              <a-input v-model:value="formData.email" placeholder="请输入邮箱" />
            </a-form-item>

            <a-form-item label="手机号" name="phone">
              <a-input v-model:value="formData.phone" placeholder="请输入手机号" />
            </a-form-item>

            <a-form-item label="状态" name="status" v-if="isEditing">
              <a-switch
                :checked="formData.status === 1"
                @change="(checked: boolean) => formData.status = checked ? 1 : 0"
                checked-children="启用"
                un-checked-children="禁用"
              />
            </a-form-item>
          </div>
        </a-form>
      </div>
    </a-modal>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, computed, onMounted } from 'vue'
import { message } from 'ant-design-vue'
import type { TablePaginationConfig } from 'ant-design-vue'
import { PlusOutlined, SearchOutlined, CloseCircleFilled } from '@ant-design/icons-vue'
import { useUserStore } from '@/store/user'
import {
  getUserList,
  createUser,
  updateUser,
  deleteUser,
  type UserItem,
  type CreateUserParams,
  type UpdateUserParams
} from '@/api/user-admin'
import { getRoleList, type RoleItem } from '@/api/menu'

const userStore = useUserStore()
const currentUserId = computed(() => userStore.userInfo?.id || userStore.userInfo?.userId)

// 已启用用户数
const activeUserCount = computed(() => userList.value.filter(u => u.status === 1).length)

// 角色选项列表
const roleOptions = ref<RoleItem[]>([])

const loadRoleOptions = async () => {
  try {
    const res = await getRoleList()
    if (res.code === 200 && res.data) {
      roleOptions.value = res.data
    }
  } catch {
    // 静默失败
  }
}

const getRoleName = (code: string) => {
  const role = roleOptions.value.find(r => r.code === code)
  return role ? role.name : code
}

const getDefaultRoleCode = () => {
  return roleOptions.value.length > 0 ? roleOptions.value[0].code : 'user'
}

// 列表数据
const userList = ref<UserItem[]>([])
const loading = ref(false)
const searchKeyword = ref('')
const currentPage = ref(1)
const pageSize = ref(10)
const total = ref(0)

const columns = [
  { title: '用户名', dataIndex: 'username', key: 'username', width: 120 },
  { title: '昵称', dataIndex: 'nickname', key: 'nickname', width: 110 },
  { title: '角色', key: 'role', width: 100 },
  { title: '邮箱', dataIndex: 'email', key: 'email', width: 180, ellipsis: true },
  { title: '手机号', dataIndex: 'phone', key: 'phone', width: 130 },
  { title: '状态', key: 'status', width: 90 },
  { title: '创建时间', key: 'createTime', width: 170 },
  { title: '操作', key: 'action', width: 130, fixed: 'right' }
]

const paginationConfig = computed(() => ({
  current: currentPage.value,
  pageSize: pageSize.value,
  total: total.value,
  showSizeChanger: true,
  showQuickJumper: true,
  pageSizeOptions: ['10', '20', '50'],
  showTotal: (total: number) => `共 ${total} 条`
}))

// 模态框
const modalVisible = ref(false)
const modalConfirmLoading = ref(false)
const isEditing = ref(false)
const editingId = ref<number | null>(null)
const formRef = ref<any>(null)

const formData = reactive({
  username: '',
  password: '',
  nickname: '',
  role: 'user',
  email: '',
  phone: '',
  status: 1
})

const formRules = {
  username: [
    { required: true, message: '请输入用户名', trigger: 'blur' },
    { min: 3, max: 50, message: '用户名长度3-50个字符', trigger: 'blur' }
  ],
  role: [
    { required: true, message: '请选择角色', trigger: 'change' }
  ]
}

const passwordRules = [
  { required: true, message: '请输入密码', trigger: 'blur' },
  { min: 6, max: 32, message: '密码长度6-32个字符', trigger: 'blur' }
]

const editPasswordRules = [
  { min: 6, max: 32, message: '密码长度6-32个字符', trigger: 'blur' }
]

const fetchUserList = async () => {
  loading.value = true
  try {
    const response = await getUserList(currentPage.value, pageSize.value, searchKeyword.value)
    if (response.code === 200 && response.data) {
      userList.value = response.data.records || []
      total.value = response.data.total
    }
  } catch (error: any) {
    message.error(error.message || '获取用户列表失败')
  } finally {
    loading.value = false
  }
}

const handleSearch = () => {
  currentPage.value = 1
  fetchUserList()
}

const handleTableChange = (pag: TablePaginationConfig) => {
  currentPage.value = pag.current || 1
  pageSize.value = pag.pageSize || 10
  fetchUserList()
}

const openCreateModal = () => {
  isEditing.value = false
  editingId.value = null
  resetForm()
  formData.role = getDefaultRoleCode()
  formData.status = 1
  modalVisible.value = true
}

const openEditModal = (record: UserItem) => {
  isEditing.value = true
  editingId.value = record.id
  formData.username = record.username
  formData.password = ''
  formData.nickname = record.nickname || ''
  formData.role = record.role || getDefaultRoleCode()
  formData.email = record.email || ''
  formData.phone = record.phone || ''
  formData.status = record.status
  modalVisible.value = true
}

const resetForm = () => {
  formData.username = ''
  formData.password = ''
  formData.nickname = ''
  formData.role = getDefaultRoleCode()
  formData.email = ''
  formData.phone = ''
  formData.status = 1
}

const handleModalOk = async () => {
  try {
    await formRef.value?.validate()

    modalConfirmLoading.value = true

    if (isEditing.value && editingId.value) {
      const params: UpdateUserParams = {
        id: editingId.value,
        nickname: formData.nickname,
        role: formData.role,
        email: formData.email,
        phone: formData.phone,
        status: formData.status
      }
      if (formData.password) {
        params.password = formData.password
      }
      await updateUser(params)
      message.success('用户更新成功')
    } else {
      const params: CreateUserParams = {
        username: formData.username,
        password: formData.password,
        nickname: formData.nickname,
        role: formData.role,
        email: formData.email,
        phone: formData.phone
      }
      await createUser(params)
      message.success('用户创建成功')
    }

    modalVisible.value = false
    fetchUserList()
  } catch (error: any) {
    if (error.errorFields) return
    message.error(error.message || '操作失败')
  } finally {
    modalConfirmLoading.value = false
  }
}

const handleModalCancel = () => {
  modalVisible.value = false
}

const handleDelete = async (id: number) => {
  try {
    await deleteUser(id)
    message.success('用户已删除')
    fetchUserList()
  } catch (error: any) {
    message.error(error.message || '删除失败')
  }
}

const formatTime = (timeStr: string) => {
  if (!timeStr) return '-'
  const date = new Date(timeStr)
  return date.toLocaleString('zh-CN', {
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit'
  })
}

onMounted(() => {
  fetchUserList()
  loadRoleOptions()
})
</script>

<style scoped>
/* ================================================================
   整体布局
   ================================================================ */
.user-manage {
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
  background: linear-gradient(135deg, #eef2ff, #e0e7ff);
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
  display: flex;
  align-items: center;
  gap: 16px;
  flex-shrink: 0;
}

.stat-item {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 2px;
}

.stat-num {
  font-size: 22px;
  font-weight: 700;
  color: #1a202c;
  font-variant-numeric: tabular-nums;
}

.stat-label {
  font-size: 11px;
  color: #94a3b8;
  font-weight: 500;
}

.stat-divider {
  width: 1px;
  height: 32px;
  background: #eef2f7;
}

/* ================================================================
   工具栏
   ================================================================ */
.toolbar-card {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 14px 20px;
  background: #fff;
  border-radius: 12px;
  border: 1px solid #eef2f7;
  box-shadow: 0 1px 3px rgba(0, 0, 0, 0.04);
  flex-shrink: 0;
  gap: 16px;
  flex-wrap: wrap;
}

.toolbar-left {
  display: flex;
  align-items: center;
  gap: 12px;
  flex: 1;
  min-width: 0;
}

.toolbar-right {
  flex-shrink: 0;
}

/* 自定义搜索框 */
.search-box {
  position: relative;
  width: 280px;
  flex-shrink: 0;
}

.search-icon {
  position: absolute;
  left: 10px;
  top: 50%;
  transform: translateY(-50%);
  font-size: 14px;
  color: #94a3b8;
  pointer-events: none;
}

.search-input {
  width: 100%;
  height: 34px;
  padding: 0 32px 0 32px;
  border: 1px solid #e2e8f0;
  border-radius: 8px;
  font-size: 13px;
  color: #1a202c;
  background: #fafbfc;
  outline: none;
  transition: all 0.2s ease;
  box-sizing: border-box;
  font-family: inherit;
}

.search-input::placeholder {
  color: #c0c8d4;
}

.search-input:focus {
  border-color: #1677ff;
  background: #fff;
  box-shadow: 0 0 0 3px rgba(22, 119, 255, 0.08);
}

.search-clear {
  position: absolute;
  right: 10px;
  top: 50%;
  transform: translateY(-50%);
  font-size: 14px;
  color: #c0c8d4;
  cursor: pointer;
  transition: color 0.15s;
}

.search-clear:hover {
  color: #64748b;
}

.create-btn {
  height: 36px;
  border-radius: 8px;
  font-weight: 600;
  padding: 0 18px;
  display: inline-flex;
  align-items: center;
  gap: 6px;
  box-shadow: 0 2px 6px rgba(22, 119, 255, 0.2);
  transition: all 0.2s ease;
}

.create-btn:hover {
  box-shadow: 0 4px 12px rgba(22, 119, 255, 0.3);
  transform: translateY(-1px);
}

/* ================================================================
   内容卡片
   ================================================================ */
.content-card {
  flex: 1;
  background: #fff;
  border-radius: 12px;
  border: 1px solid #eef2f7;
  box-shadow: 0 1px 3px rgba(0, 0, 0, 0.04);
  overflow: hidden;
  display: flex;
  flex-direction: column;
  min-height: 200px;
}

/* ================================================================
   状态占位
   ================================================================ */
.state-box {
  flex: 1;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  padding: 60px 32px;
  gap: 12px;
}

.state-text {
  color: #94a3b8;
  font-size: 14px;
  margin: 0;
}

.empty-state {
  gap: 8px;
}

.empty-illustration {
  margin-bottom: 8px;
}

.empty-icon {
  font-size: 48px;
  line-height: 1;
  display: block;
}

.empty-title {
  margin: 0;
  font-size: 16px;
  font-weight: 600;
  color: #475569;
}

.empty-desc {
  margin: 0;
  font-size: 13px;
  color: #94a3b8;
  text-align: center;
  max-width: 340px;
}

/* ================================================================
   表格
   ================================================================ */
.user-table :deep(.ant-table) {
  border-radius: 0;
}

.user-table :deep(.ant-table-thead > tr > th) {
  background: #fafbfc;
  color: #64748b;
  font-weight: 600;
  font-size: 12px;
  text-transform: uppercase;
  letter-spacing: 0.5px;
  border-bottom: 1px solid #eef2f7;
}

.user-table :deep(.ant-table-tbody > tr > td) {
  border-bottom: 1px solid #f8fafc;
  color: #334155;
}

.user-table :deep(.ant-table-tbody > tr:hover > td) {
  background: #f8fafd;
}

/* 状态指示点 */
.status-dot {
  display: inline-block;
  width: 7px;
  height: 7px;
  border-radius: 50%;
  margin-right: 6px;
  vertical-align: middle;
}

.status-dot.active {
  background: #22c55e;
  box-shadow: 0 0 0 3px rgba(34, 197, 94, 0.15);
}

.status-dot.inactive {
  background: #c0c8d4;
}

.status-text {
  font-size: 13px;
  color: #64748b;
  vertical-align: middle;
}

/* 操作链接 */
.action-link {
  font-size: 13px !important;
  font-weight: 500;
  padding: 0 6px !important;
  border-radius: 4px;
  transition: all 0.15s;
}

.action-link:hover {
  background: #f0f5ff !important;
}

/* ================================================================
   弹窗样式
   ================================================================ */
.user-modal-wrap :deep(.ant-modal-content) {
  border-radius: 14px;
  overflow: hidden;
  box-shadow: 0 20px 60px rgba(0, 0, 0, 0.12);
}

.user-modal-wrap :deep(.ant-modal-header) {
  display: none;
}

.modal-header {
  display: flex;
  align-items: center;
  gap: 14px;
  padding: 24px 28px 20px;
  border-bottom: 1px solid #f1f5f9;
  background: linear-gradient(180deg, #fafbfc 0%, #fff 100%);
}

.modal-header-icon {
  font-size: 28px;
  width: 48px;
  height: 48px;
  border-radius: 12px;
  background: linear-gradient(135deg, #eef2ff, #e0e7ff);
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
}

.modal-header-text h3 {
  margin: 0 0 2px;
  font-size: 17px;
  font-weight: 700;
  color: #1a202c;
}

.modal-header-text p {
  margin: 0;
  font-size: 13px;
  color: #94a3b8;
}

.modal-body {
  padding: 20px 28px 8px;
  max-height: 55vh;
  overflow-y: auto;
}

.modal-footer {
  display: flex;
  justify-content: flex-end;
  gap: 10px;
}

.modal-cancel-btn {
  height: 38px;
  border-radius: 8px;
  padding: 0 20px;
  font-weight: 500;
}

.modal-save-btn {
  height: 38px;
  border-radius: 8px;
  padding: 0 24px;
  font-weight: 600;
  box-shadow: 0 2px 6px rgba(22, 119, 255, 0.2);
}

/* 表单分组 */
.form-section {
  margin-bottom: 20px;
}

.form-section-title {
  font-size: 13px;
  font-weight: 700;
  color: #64748b;
  text-transform: uppercase;
  letter-spacing: 0.5px;
  padding: 0 0 8px 2px;
  margin-bottom: 8px;
  border-bottom: 1px solid #f1f5f9;
}

/* ================================================================
   滚动条
   ================================================================ */
.user-manage::-webkit-scrollbar,
.modal-body::-webkit-scrollbar {
  width: 5px;
}

.user-manage::-webkit-scrollbar-thumb,
.modal-body::-webkit-scrollbar-thumb {
  background: #dce4f0;
  border-radius: 3px;
}

.user-manage::-webkit-scrollbar-thumb:hover,
.modal-body::-webkit-scrollbar-thumb:hover {
  background: #c0c8d4;
}

/* ================================================================
   暗色模式
   ================================================================ */
[data-theme="dark"] .user-manage {
  background: #121418;
}

[data-theme="dark"] .page-header,
[data-theme="dark"] .toolbar-card,
[data-theme="dark"] .content-card {
  background: #1a1d22;
  border-color: #2a2d33;
}

[data-theme="dark"] .header-title,
[data-theme="dark"] .stat-num,
[data-theme="dark"] .empty-title {
  color: #e4e6ea;
}

[data-theme="dark"] .header-subtitle,
[data-theme="dark"] .stat-label,
[data-theme="dark"] .state-text,
[data-theme="dark"] .empty-desc {
  color: #8b8f98;
}

[data-theme="dark"] .header-icon-box {
  background: linear-gradient(135deg, #1e2440, #1a1f35);
}

[data-theme="dark"] .stat-divider {
  background: #2a2d33;
}

[data-theme="dark"] .search-input {
  background: #141619;
  border-color: #2a2d33;
  color: #e4e6ea;
}

[data-theme="dark"] .search-input:focus {
  background: #1a1d22;
  border-color: #4f7df3;
}

[data-theme="dark"] .search-icon,
[data-theme="dark"] .search-clear {
  color: #6b6f78;
}

[data-theme="dark"] .user-table :deep(.ant-table-thead > tr > th) {
  background: #1e2126 !important;
  color: #8b8f98 !important;
  border-bottom-color: #2a2d33 !important;
}

[data-theme="dark"] .user-table :deep(.ant-table-tbody > tr > td) {
  border-bottom-color: #1e2126 !important;
  color: #c4c8ce !important;
  background: #1a1d22 !important;
}

[data-theme="dark"] .user-table :deep(.ant-table-tbody > tr:hover > td) {
  background: #1e2126 !important;
}

[data-theme="dark"] .user-table :deep(.ant-table) {
  background: #1a1d22 !important;
}

[data-theme="dark"] .user-table :deep(.ant-pagination-item-active) {
  border-color: #4f7df3;
}

[data-theme="dark"] .user-table :deep(.ant-pagination-item-active a) {
  color: #4f7df3;
}

[data-theme="dark"] .action-link:hover {
  background: #1e2440 !important;
}

[data-theme="dark"] .status-text {
  color: #8b8f98;
}

[data-theme="dark"] .modal-header {
  background: linear-gradient(180deg, #1e2126 0%, #1a1d22 100%);
  border-bottom-color: #2a2d33;
}

[data-theme="dark"] .modal-header-text h3 {
  color: #e4e6ea;
}

[data-theme="dark"] .modal-header-text p {
  color: #8b8f98;
}

[data-theme="dark"] .modal-header-icon {
  background: linear-gradient(135deg, #1e2440, #1a1f35);
}

[data-theme="dark"] .form-section-title {
  color: #8b8f98;
  border-bottom-color: #2a2d33;
}

[data-theme="dark"] :deep(.ant-input),
[data-theme="dark"] :deep(.ant-input-password),
[data-theme="dark"] :deep(.ant-select-selector) {
  background: #141619 !important;
  border-color: #2a2d33 !important;
  color: #e4e6ea !important;
}

[data-theme="dark"] :deep(.ant-modal-content) {
  background: #1a1d22;
}
</style>

<!-- 暗色模式 — 表格全局强制覆盖 -->
<style>
[data-theme="dark"] .user-manage .ant-table {
  background: #1a1d22 !important;
}
[data-theme="dark"] .user-manage .ant-table-thead > tr > th {
  background: #1e2126 !important;
  color: #8b8f98 !important;
  border-bottom: 1px solid #2a2d33 !important;
}
[data-theme="dark"] .user-manage .ant-table-tbody > tr > td {
  background: #1a1d22 !important;
  border-bottom: 1px solid #2a2d33 !important;
  color: #c4c8ce !important;
}
[data-theme="dark"] .user-manage .ant-table-tbody > tr:hover > td {
  background: #1e2126 !important;
}
[data-theme="dark"] .user-manage .ant-table-cell-row-hover {
  background: #1e2126 !important;
}
</style>
