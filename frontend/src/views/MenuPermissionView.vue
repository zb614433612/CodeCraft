<template>
  <div class="menu-permission-container">
    <div class="page-header">
      <h2 class="page-title">菜单权限管理</h2>
    </div>

    <div class="content-wrapper">
      <!-- ========== 左栏：菜单项管理 ========== -->
      <div class="left-panel">
        <div class="panel-header">
          <span class="panel-title">菜单项管理</span>
          <a-button type="primary" size="small" @click="openCreateModal">
            <template #icon><plus-outlined /></template>
            新增菜单
          </a-button>
        </div>

        <div class="menu-group">
          <div class="group-title">📌 左侧菜单栏 (LINK)</div>
          <div v-for="item in linkMenus" :key="item.id" class="menu-item-row">
            <span class="menu-name">{{ item.name }}</span>
            <span class="menu-path">{{ item.path }}</span>
            <span class="menu-actions">
              <a-button type="link" size="small" @click="openEditModal(item)">编辑</a-button>
              <a-popconfirm title="确定删除该菜单项？" @confirm="handleDelete(item.id)">
                <a-button type="link" danger size="small">删除</a-button>
              </a-popconfirm>
            </span>
          </div>
          <div v-if="linkMenus.length === 0" class="empty-hint">暂无左侧菜单项</div>
        </div>

        <div class="menu-group">
          <div class="group-title">🔧 管理页面 (MANAGE)</div>
          <div v-for="item in manageMenus" :key="item.id" class="menu-item-row">
            <span class="menu-name">{{ item.name }}</span>
            <span class="menu-path">{{ item.path }}</span>
            <span class="menu-actions">
              <a-button type="link" size="small" @click="openEditModal(item)">编辑</a-button>
              <a-popconfirm title="确定删除该菜单项？" @confirm="handleDelete(item.id)">
                <a-button type="link" danger size="small">删除</a-button>
              </a-popconfirm>
            </span>
          </div>
          <div v-if="manageMenus.length === 0" class="empty-hint">暂无管理页面</div>
        </div>

        <div class="menu-group">
          <div class="group-title">⚙ 设置 (SETTING)</div>
          <div v-for="item in settingMenus" :key="item.id" class="menu-item-row">
            <span class="menu-name">{{ item.name }}</span>
            <span class="menu-path">{{ item.path }}</span>
            <span class="menu-actions">
              <a-button type="link" size="small" @click="openEditModal(item)">编辑</a-button>
              <a-popconfirm title="确定删除该菜单项？" @confirm="handleDelete(item.id)">
                <a-button type="link" danger size="small">删除</a-button>
              </a-popconfirm>
            </span>
          </div>
          <div v-if="settingMenus.length === 0" class="empty-hint">暂无设置菜单</div>
        </div>
      </div>

      <!-- ========== 右栏：角色菜单配置 ========== -->
      <div class="right-panel">
        <div class="panel-header">
          <span class="panel-title">角色菜单配置</span>
        </div>

        <div class="role-selector">
          <span class="role-label">选择角色：</span>
          <a-select
            v-model:value="selectedRoleId"
            style="width: 200px"
            placeholder="请选择角色"
            @change="loadRoleMenus"
          >
            <a-select-option v-for="role in roleList" :key="role.id" :value="role.id">
              {{ role.name }} ({{ role.code }})
            </a-select-option>
          </a-select>
          <a-button type="link" size="small" @click="openRoleManageModal">
            <setting-outlined /> 管理角色
          </a-button>
        </div>

        <div v-if="selectedRoleId" class="menu-checkbox-area">
          <a-checkbox-group v-model:value="checkedMenuIds">
            <div class="checkbox-section">
              <div class="checkbox-group-title">📌 左侧菜单栏</div>
              <div v-for="item in linkMenus" :key="item.id" class="checkbox-row">
                <a-checkbox :value="item.id">
                  {{ item.name }} <span class="checkbox-path">{{ item.path }}</span>
                </a-checkbox>
              </div>
            </div>

            <div class="checkbox-section">
              <div class="checkbox-group-title">🔧 管理页面</div>
              <div v-for="item in manageMenus" :key="item.id" class="checkbox-row">
                <a-checkbox :value="item.id">
                  {{ item.name }} <span class="checkbox-path">{{ item.path }}</span>
                </a-checkbox>
              </div>
            </div>

            <div class="checkbox-section">
              <div class="checkbox-group-title">⚙ 设置</div>
              <div v-for="item in settingMenus" :key="item.id" class="checkbox-row">
                <a-checkbox :value="item.id">
                  {{ item.name }} <span class="checkbox-path">{{ item.path }}</span>
                </a-checkbox>
              </div>
            </div>
          </a-checkbox-group>

          <div class="save-area">
            <a-button type="primary" :loading="saving" @click="handleSave">保存配置</a-button>
          </div>
        </div>

        <div v-else class="no-role-hint">请先选择角色</div>
      </div>
    </div>

    <!-- 新增/编辑菜单弹窗 -->
    <a-modal
      v-model:open="modalVisible"
      :title="isEditing ? '编辑菜单' : '新增菜单'"
      @ok="handleModalOk"
      :confirmLoading="modalLoading"
    >
      <a-form :model="formData" :label-col="{ span: 5 }" :wrapper-col="{ span: 17 }">
        <a-form-item label="菜单名称">
          <a-input v-model:value="formData.name" placeholder="请输入菜单名称" />
        </a-form-item>
        <a-form-item label="路由路径">
          <a-input v-model:value="formData.path" placeholder="/xxx" />
        </a-form-item>
        <a-form-item label="图标名称">
          <a-input v-model:value="formData.icon" placeholder="RobotOutlined" />
        </a-form-item>
        <a-form-item label="菜单类型">
          <a-select v-model:value="formData.menuType">
            <a-select-option value="LINK">左侧菜单栏</a-select-option>
            <a-select-option value="MANAGE">管理页面</a-select-option>
            <a-select-option value="SETTING">设置菜单</a-select-option>
          </a-select>
        </a-form-item>
        <a-form-item label="排序号">
          <a-input-number v-model:value="formData.sortOrder" :min="1" :max="999" />
        </a-form-item>
      </a-form>
    </a-modal>

    <!-- 角色管理弹窗 -->
    <a-modal
      v-model:open="roleManageVisible"
      title="角色管理"
      :footer="null"
      width="520px"
    >
      <div class="role-manage-header">
        <a-button type="primary" size="small" @click="openRoleCreateModal">
          <template #icon><plus-outlined /></template>
          新增角色
        </a-button>
      </div>
      <a-table
        :dataSource="roleList"
        :columns="roleColumns"
        rowKey="id"
        size="small"
        :pagination="false"
      >
        <template #bodyCell="{ column, record }">
          <template v-if="column.key === 'action'">
            <a-space>
              <a-button type="link" size="small" @click="openRoleEditModal(record)">编辑</a-button>
              <a-popconfirm
                title="确定删除该角色？删除后该角色的菜单权限配置将丢失。"
                @confirm="handleRoleDelete(record.id)"
              >
                <a-button type="link" danger size="small">删除</a-button>
              </a-popconfirm>
            </a-space>
          </template>
        </template>
      </a-table>
    </a-modal>

    <!-- 新增/编辑角色弹窗 -->
    <a-modal
      v-model:open="roleFormVisible"
      :title="isEditingRole ? '编辑角色' : '新增角色'"
      @ok="handleRoleFormOk"
      :confirmLoading="roleFormLoading"
    >
      <a-form :model="roleFormData" :label-col="{ span: 5 }" :wrapper-col="{ span: 17 }">
        <a-form-item label="角色名称">
          <a-input v-model:value="roleFormData.name" placeholder="如：运维人员" />
        </a-form-item>
        <a-form-item label="角色编码">
          <a-input v-model:value="roleFormData.code" placeholder="如：operator" :disabled="isEditingRole" />
        </a-form-item>
        <a-form-item label="角色描述">
          <a-input v-model:value="roleFormData.description" placeholder="角色描述（可选）" />
        </a-form-item>
      </a-form>
    </a-modal>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import { message } from 'ant-design-vue'
import { PlusOutlined, SettingOutlined } from '@ant-design/icons-vue'
import {
  getMenuTree, createMenu, updateMenu, deleteMenu,
  getRoleList, getRoleMenuIds, assignMenusToRole,
  createRole, updateRole, deleteRole,
  type MenuItem, type RoleItem
} from '@/api/menu'

// === 左侧：菜单项管理 ===
const allMenus = ref<MenuItem[]>([])
const loading = ref(false)

const linkMenus = computed(() => allMenus.value.filter(m => m.menuType === 'LINK'))
const manageMenus = computed(() => allMenus.value.filter(m => m.menuType === 'MANAGE'))
const settingMenus = computed(() => allMenus.value.filter(m => m.menuType === 'SETTING'))

const loadAllMenus = async () => {
  loading.value = true
  try {
    const res = await getMenuTree()
    if (res.code === 200 && res.data) {
      allMenus.value = res.data
    }
  } catch (e: any) {
    message.error(e.message || '加载菜单失败')
  } finally {
    loading.value = false
  }
}

// === 新增/编辑菜单弹窗 ===
const modalVisible = ref(false)
const modalLoading = ref(false)
const isEditing = ref(false)
const editingId = ref<number | null>(null)

const formData = ref({
  name: '',
  path: '',
  icon: '',
  menuType: 'LINK',
  sortOrder: 1
})

const resetForm = () => {
  formData.value = { name: '', path: '', icon: '', menuType: 'LINK', sortOrder: 1 }
  editingId.value = null
  isEditing.value = false
}

const openCreateModal = () => {
  resetForm()
  modalVisible.value = true
}

const openEditModal = (item: MenuItem) => {
  isEditing.value = true
  editingId.value = item.id
  formData.value = {
    name: item.name,
    path: item.path || '',
    icon: item.icon || '',
    menuType: item.menuType,
    sortOrder: item.sortOrder
  }
  modalVisible.value = true
}

const handleModalOk = async () => {
  if (!formData.value.name.trim()) {
    message.warning('请输入菜单名称')
    return
  }
  modalLoading.value = true
  try {
    if (isEditing.value && editingId.value) {
      await updateMenu({ id: editingId.value, ...formData.value })
      message.success('菜单已更新')
    } else {
      await createMenu(formData.value)
      message.success('菜单已创建')
    }
    modalVisible.value = false
    loadAllMenus()
  } catch (e: any) {
    message.error(e.message || '操作失败')
  } finally {
    modalLoading.value = false
  }
}

const handleDelete = async (id: number) => {
  try {
    await deleteMenu(id)
    message.success('菜单已删除')
    loadAllMenus()
  } catch (e: any) {
    message.error(e.message || '删除失败')
  }
}

// === 右侧：角色菜单配置 ===
const roleList = ref<RoleItem[]>([])
const selectedRoleId = ref<number | undefined>(undefined)
const checkedMenuIds = ref<number[]>([])
const saving = ref(false)

const loadRoleList = async () => {
  try {
    const res = await getRoleList()
    if (res.code === 200 && res.data) {
      roleList.value = res.data
    }
  } catch (e: any) {
    message.error(e.message || '加载角色列表失败')
  }
}

const loadRoleMenus = async () => {
  if (!selectedRoleId.value) return
  try {
    const res = await getRoleMenuIds(selectedRoleId.value)
    if (res.code === 200 && res.data) {
      checkedMenuIds.value = res.data
    }
  } catch (e: any) {
    message.error(e.message || '加载角色菜单失败')
  }
}

const handleSave = async () => {
  if (!selectedRoleId.value) return
  saving.value = true
  try {
    await assignMenusToRole(selectedRoleId.value, checkedMenuIds.value)
    message.success('菜单权限配置已保存')
  } catch (e: any) {
    message.error(e.message || '保存失败')
  } finally {
    saving.value = false
  }
}

// === 角色管理 ===
const roleManageVisible = ref(false)
const roleFormVisible = ref(false)
const roleFormLoading = ref(false)
const isEditingRole = ref(false)
const editingRoleId = ref<number | null>(null)

const roleFormData = ref({
  name: '',
  code: '',
  description: ''
})

const roleColumns = [
  { title: '角色名称', dataIndex: 'name', key: 'name' },
  { title: '角色编码', dataIndex: 'code', key: 'code' },
  { title: '描述', dataIndex: 'description', key: 'description', ellipsis: true },
  { title: '操作', key: 'action', width: 120 }
]

const openRoleManageModal = () => {
  loadRoleList()
  roleManageVisible.value = true
}

const resetRoleForm = () => {
  roleFormData.value = { name: '', code: '', description: '' }
  isEditingRole.value = false
  editingRoleId.value = null
}

const openRoleCreateModal = () => {
  resetRoleForm()
  roleFormVisible.value = true
}

const openRoleEditModal = (role: RoleItem) => {
  isEditingRole.value = true
  editingRoleId.value = role.id
  roleFormData.value = {
    name: role.name,
    code: role.code,
    description: role.description || ''
  }
  roleFormVisible.value = true
}

const handleRoleFormOk = async () => {
  if (!roleFormData.value.name.trim() || !roleFormData.value.code.trim()) {
    message.warning('请填写角色名称和编码')
    return
  }
  roleFormLoading.value = true
  try {
    if (isEditingRole.value && editingRoleId.value) {
      await updateRole({ id: editingRoleId.value, ...roleFormData.value })
      message.success('角色已更新')
    } else {
      await createRole(roleFormData.value)
      message.success('角色已创建')
    }
    roleFormVisible.value = false
    loadRoleList()
    // 如果当前选中的角色被编辑了，刷新菜单配置
    if (selectedRoleId.value && roleList.value.find(r => r.id === selectedRoleId.value)) {
      loadRoleMenus()
    }
  } catch (e: any) {
    message.error(e.message || '操作失败')
  } finally {
    roleFormLoading.value = false
  }
}

const handleRoleDelete = async (id: number) => {
  try {
    await deleteRole(id)
    message.success('角色已删除')
    loadRoleList()
    // 如果删除了当前选中的角色，清空选中
    if (selectedRoleId.value === id) {
      selectedRoleId.value = undefined
      checkedMenuIds.value = []
    }
  } catch (e: any) {
    message.error(e.message || '删除失败')
  }
}

onMounted(() => {
  loadAllMenus()
  loadRoleList()
})
</script>

<style scoped>
.menu-permission-container {
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

.content-wrapper {
  display: flex;
  gap: 16px;
  min-height: 500px;
}

.left-panel,
.right-panel {
  flex: 1;
  background: white;
  border-radius: 8px;
  box-shadow: 0 1px 4px rgba(0, 0, 0, 0.06);
  display: flex;
  flex-direction: column;
}

.panel-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 16px 20px;
  border-bottom: 1px solid #f0f0f0;
}

.panel-title {
  font-size: 15px;
  font-weight: 500;
  color: #1a202c;
}

.menu-group {
  padding: 12px 20px;
}

.group-title {
  font-size: 13px;
  font-weight: 500;
  color: #595959;
  margin-bottom: 8px;
  padding: 4px 0;
}

.menu-item-row {
  display: flex;
  align-items: center;
  padding: 8px 8px;
  border-radius: 4px;
  transition: background 0.2s;
}
.menu-item-row:hover {
  background: #f5f5f5;
}

.menu-name {
  width: 100px;
  font-weight: 500;
  color: #262626;
}

.menu-path {
  flex: 1;
  color: #8c8c8c;
  font-size: 12px;
}

.menu-actions {
  white-space: nowrap;
}

.empty-hint {
  padding: 16px;
  text-align: center;
  color: #bfbfbf;
  font-size: 13px;
}

/* 右侧角色配置 */
.role-selector {
  padding: 16px 20px;
  display: flex;
  align-items: center;
  gap: 8px;
  border-bottom: 1px solid #f5f5f5;
}

.role-label {
  font-size: 14px;
  color: #262626;
  white-space: nowrap;
}

.menu-checkbox-area {
  padding: 16px 20px;
  flex: 1;
}

.checkbox-section {
  margin-bottom: 16px;
}

.checkbox-group-title {
  font-size: 13px;
  font-weight: 500;
  color: #595959;
  margin-bottom: 8px;
  padding-bottom: 4px;
  border-bottom: 1px dashed #f0f0f0;
}

.checkbox-row {
  padding: 6px 0;
}

.checkbox-path {
  color: #8c8c8c;
  font-size: 12px;
  margin-left: 8px;
}

.save-area {
  margin-top: 20px;
  padding-top: 16px;
  border-top: 1px solid #f5f5f5;
}

.no-role-hint {
  padding: 40px;
  text-align: center;
  color: #bfbfbf;
}

.role-manage-header {
  margin-bottom: 12px;
  text-align: right;
}
</style>
