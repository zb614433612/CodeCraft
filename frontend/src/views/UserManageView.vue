<template>
  <div class="user-manage-container">
    <!-- 页面头部 -->
    <div class="page-header">
      <h2 class="page-title">用户管理</h2>
      <a-button type="primary" @click="openCreateModal">
        <template #icon><plus-outlined /></template>
        新增用户
      </a-button>
    </div>

    <!-- 搜索区域 -->
    <div class="search-bar">
      <a-input-search
        v-model:value="searchKeyword"
        placeholder="搜索用户名..."
        style="width: 300px"
        @search="handleSearch"
        @pressEnter="handleSearch"
      />
    </div>

    <!-- 用户列表表格 -->
    <a-table
      :dataSource="userList"
      :columns="columns"
      :loading="loading"
      :pagination="paginationConfig"
      rowKey="id"
      @change="handleTableChange"
      size="middle"
    >
      <!-- 角色列 - 自定义渲染 -->
      <template #bodyCell="{ column, record }">
        <template v-if="column.key === 'role'">
          <a-tag :color="record.role === 'admin' ? 'red' : 'blue'">
            {{ getRoleName(record.role) }}
          </a-tag>
        </template>

        <!-- 状态列 -->
        <template v-if="column.key === 'status'">
          <a-badge :status="record.status === 1 ? 'success' : 'default'" />
          {{ record.status === 1 ? '启用' : '禁用' }}
        </template>

        <!-- 时间列 -->
        <template v-if="column.key === 'createTime'">
          {{ formatTime(record.createTime) }}
        </template>

        <!-- 操作列 -->
        <template v-if="column.key === 'action'">
          <a-space>
            <a-button type="link" size="small" @click="openEditModal(record)">
              编辑
            </a-button>
            <a-popconfirm
              title="确定要删除该用户吗？"
              ok-text="确定"
              cancel-text="取消"
              @confirm="handleDelete(record.id)"
            >
              <a-button type="link" danger size="small" :disabled="record.id === currentUserId">
                删除
              </a-button>
            </a-popconfirm>
          </a-space>
        </template>
      </template>
    </a-table>

    <!-- 新增/编辑用户弹窗 -->
    <a-modal
      v-model:open="modalVisible"
      :title="isEditing ? '编辑用户' : '新增用户'"
      :confirmLoading="modalConfirmLoading"
      @ok="handleModalOk"
      @cancel="handleModalCancel"
    >
      <a-form
        ref="formRef"
        :model="formData"
        :rules="formRules"
        :label-col="{ span: 5 }"
        :wrapper-col="{ span: 18 }"
      >
        <a-form-item label="用户名" name="username">
          <a-input
            v-model:value="formData.username"
            :disabled="isEditing"
            placeholder="请输入用户名"
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
          />
        </a-form-item>

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
      </a-form>
    </a-modal>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, computed, onMounted } from 'vue'
import { message } from 'ant-design-vue'
import type { TablePaginationConfig } from 'ant-design-vue'
import { PlusOutlined } from '@ant-design/icons-vue'
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

// 角色选项列表（从角色管理加载）
const roleOptions = ref<RoleItem[]>([])

const loadRoleOptions = async () => {
  try {
    const res = await getRoleList()
    if (res.code === 200 && res.data) {
      roleOptions.value = res.data
    }
  } catch {
    // 静默失败，不影响主流程
  }
}

// 根据角色编码获取角色名称
const getRoleName = (code: string) => {
  const role = roleOptions.value.find(r => r.code === code)
  return role ? role.name : code
}

// 获取默认角色编码（取角色列表第一个，兜底返回 'user'）
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

// 表格列定义
const columns = [
  { title: '用户名', dataIndex: 'username', key: 'username', width: 120 },
  { title: '昵称', dataIndex: 'nickname', key: 'nickname', width: 120 },
  { title: '角色', key: 'role', width: 100 },
  { title: '邮箱', dataIndex: 'email', key: 'email', width: 180, ellipsis: true },
  { title: '手机号', dataIndex: 'phone', key: 'phone', width: 130 },
  { title: '状态', key: 'status', width: 80 },
  { title: '创建时间', key: 'createTime', width: 170 },
  { title: '操作', key: 'action', width: 120, fixed: 'right' }
]

// 分页配置
const paginationConfig = computed(() => ({
  current: currentPage.value,
  pageSize: pageSize.value,
  total: total.value,
  showSizeChanger: true,
  showQuickJumper: true,
  pageSizeOptions: ['10', '20', '50'],
  showTotal: (total: number) => `共 ${total} 条`
}))

// 模态框状态
const modalVisible = ref(false)
const modalConfirmLoading = ref(false)
const isEditing = ref(false)
const editingId = ref<number | null>(null)
const formRef = ref<any>(null)

// 表单数据
const formData = reactive({
  username: '',
  password: '',
  nickname: '',
  role: 'user',
  email: '',
  phone: '',
  status: 1
})

// 表单校验规则
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

// 加载用户列表
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

// 搜索
const handleSearch = () => {
  currentPage.value = 1
  fetchUserList()
}

// 表格变化（分页、排序等）
const handleTableChange = (pag: TablePaginationConfig) => {
  currentPage.value = pag.current || 1
  pageSize.value = pag.pageSize || 10
  fetchUserList()
}

// 打开新增弹窗
const openCreateModal = () => {
  isEditing.value = false
  editingId.value = null
  resetForm()
  formData.role = getDefaultRoleCode()
  formData.status = 1
  modalVisible.value = true
}

// 打开编辑弹窗
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

// 重置表单
const resetForm = () => {
  formData.username = ''
  formData.password = ''
  formData.nickname = ''
  formData.role = getDefaultRoleCode()
  formData.email = ''
  formData.phone = ''
  formData.status = 1
}

// 提交表单
const handleModalOk = async () => {
  try {
    // 表单校验
    await formRef.value?.validate()

    modalConfirmLoading.value = true

    if (isEditing.value && editingId.value) {
      // 编辑模式
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
      // 新增模式
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
    if (error.errorFields) {
      // 表单校验失败，忽略（错误信息已显示在表单上）
      return
    }
    message.error(error.message || '操作失败')
  } finally {
    modalConfirmLoading.value = false
  }
}

// 取消弹窗
const handleModalCancel = () => {
  modalVisible.value = false
}

// 删除用户
const handleDelete = async (id: number) => {
  try {
    await deleteUser(id)
    message.success('用户已删除')
    fetchUserList()
  } catch (error: any) {
    message.error(error.message || '删除失败')
  }
}

// 格式化时间
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
.user-manage-container {
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

.search-bar {
  margin-bottom: 16px;
  padding: 12px 20px;
  background: white;
  border-radius: 8px;
  box-shadow: 0 1px 4px rgba(0, 0, 0, 0.06);
}

/* 表格容器 */
:deep(.ant-table-wrapper) {
  background: white;
  border-radius: 8px;
  box-shadow: 0 1px 4px rgba(0, 0, 0, 0.06);
  padding: 0;
}

:deep(.ant-table) {
  border-radius: 8px;
}
</style>
