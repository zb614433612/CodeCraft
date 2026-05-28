<template>
  <div class="menu-permission">
    <!-- ===== 头部卡片 ===== -->
    <div class="page-header">
      <div class="header-left">
        <div class="header-icon-box">
          <span class="header-icon">🗂️</span>
        </div>
        <div class="header-text">
          <h2 class="header-title">菜单权限管理</h2>
          <p class="header-subtitle">管理侧边栏菜单项，为不同角色分配菜单访问权限</p>
        </div>
      </div>
      <div class="header-right">
        <div class="stat-item">
          <span class="stat-num">{{ allMenus.length }}</span>
          <span class="stat-label">菜单项</span>
        </div>
        <div class="stat-divider"></div>
        <div class="stat-item">
          <span class="stat-num">{{ roleList.length }}</span>
          <span class="stat-label">角色数</span>
        </div>
      </div>
    </div>

    <!-- ===== 双栏布局 ===== -->
    <div class="content-wrapper">
      <!-- ========== 左栏：菜单项管理 ========== -->
      <div class="panel-card">
        <div class="panel-header">
          <div class="panel-header-left">
            <span class="panel-icon">📋</span>
            <span class="panel-title">菜单项管理</span>
            <a-tag color="blue" size="small" class="panel-count">{{ allMenus.length }}</a-tag>
          </div>
          <a-button type="primary" size="small" @click="openCreateModal" class="panel-action-btn">
            <template #icon><plus-outlined /></template>
            新增菜单
          </a-button>
        </div>

        <div class="panel-body">
          <!-- LINK 分组 -->
          <div class="menu-group">
            <div class="group-header">
              <span class="group-badge link">LINK</span>
              <span class="group-title">左侧菜单栏</span>
              <span class="group-count">{{ linkMenus.length }} 项</span>
            </div>
            <div v-for="item in linkMenus" :key="item.id" class="menu-item-row">
              <div class="menu-item-info">
                <span class="menu-name">{{ item.name }}</span>
                <span class="menu-path">{{ item.path }}</span>
              </div>
              <span class="menu-actions">
                <a-button type="link" size="small" @click="openEditModal(item)">编辑</a-button>
                <a-popconfirm title="确定删除该菜单项？" @confirm="handleDelete(item.id)">
                  <a-button type="link" danger size="small">删除</a-button>
                </a-popconfirm>
              </span>
            </div>
            <div v-if="linkMenus.length === 0" class="empty-hint">暂无左侧菜单项</div>
          </div>

          <!-- MANAGE 分组 -->
          <div class="menu-group">
            <div class="group-header">
              <span class="group-badge manage">MANAGE</span>
              <span class="group-title">管理页面</span>
              <span class="group-count">{{ manageMenus.length }} 项</span>
            </div>
            <div v-for="item in manageMenus" :key="item.id" class="menu-item-row">
              <div class="menu-item-info">
                <span class="menu-name">{{ item.name }}</span>
                <span class="menu-path">{{ item.path }}</span>
              </div>
              <span class="menu-actions">
                <a-button type="link" size="small" @click="openEditModal(item)">编辑</a-button>
                <a-popconfirm title="确定删除该菜单项？" @confirm="handleDelete(item.id)">
                  <a-button type="link" danger size="small">删除</a-button>
                </a-popconfirm>
              </span>
            </div>
            <div v-if="manageMenus.length === 0" class="empty-hint">暂无管理页面</div>
          </div>

          <!-- SETTING 分组 -->
          <div class="menu-group">
            <div class="group-header">
              <span class="group-badge setting">SETTING</span>
              <span class="group-title">设置</span>
              <span class="group-count">{{ settingMenus.length }} 项</span>
            </div>
            <div v-for="item in settingMenus" :key="item.id" class="menu-item-row">
              <div class="menu-item-info">
                <span class="menu-name">{{ item.name }}</span>
                <span class="menu-path">{{ item.path }}</span>
              </div>
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
      </div>

      <!-- ========== 右栏：角色菜单配置 ========== -->
      <div class="panel-card">
        <div class="panel-header">
          <div class="panel-header-left">
            <span class="panel-icon">🔐</span>
            <span class="panel-title">角色菜单配置</span>
          </div>
          <a-button type="link" size="small" @click="openRoleManageModal" class="panel-action-link">
            <setting-outlined /> 管理角色
          </a-button>
        </div>

        <div class="panel-body">
          <!-- 角色选择器 -->
          <div class="role-selector">
            <span class="role-label">选择角色：</span>
            <a-select
              v-model:value="selectedRoleId"
              style="flex: 1; max-width: 220px"
              placeholder="请选择角色"
              @change="loadRoleMenus"
            >
              <a-select-option v-for="role in roleList" :key="role.id" :value="role.id">
                {{ role.name }} <span class="role-code-hint">({{ role.code }})</span>
              </a-select-option>
            </a-select>
          </div>

          <div v-if="selectedRoleId" class="menu-checkbox-area">
            <a-checkbox-group v-model:value="checkedMenuIds" class="checkbox-list">
              <!-- LINK -->
              <div class="checkbox-section">
                <div class="checkbox-section-title">
                  <span class="section-badge link">LINK</span>
                  左侧菜单栏
                </div>
                <div v-for="item in linkMenus" :key="item.id" class="checkbox-row">
                  <a-checkbox :value="item.id">
                    <span class="checkbox-label">{{ item.name }}</span>
                    <span class="checkbox-path">{{ item.path }}</span>
                  </a-checkbox>
                </div>
                <div v-if="linkMenus.length === 0" class="empty-mini">暂无</div>
              </div>

              <!-- MANAGE -->
              <div class="checkbox-section">
                <div class="checkbox-section-title">
                  <span class="section-badge manage">MANAGE</span>
                  管理页面
                </div>
                <div v-for="item in manageMenus" :key="item.id" class="checkbox-row">
                  <a-checkbox :value="item.id">
                    <span class="checkbox-label">{{ item.name }}</span>
                    <span class="checkbox-path">{{ item.path }}</span>
                  </a-checkbox>
                </div>
                <div v-if="manageMenus.length === 0" class="empty-mini">暂无</div>
              </div>

              <!-- SETTING -->
              <div class="checkbox-section">
                <div class="checkbox-section-title">
                  <span class="section-badge setting">SETTING</span>
                  设置
                </div>
                <div v-for="item in settingMenus" :key="item.id" class="checkbox-row">
                  <a-checkbox :value="item.id">
                    <span class="checkbox-label">{{ item.name }}</span>
                    <span class="checkbox-path">{{ item.path }}</span>
                  </a-checkbox>
                </div>
                <div v-if="settingMenus.length === 0" class="empty-mini">暂无</div>
              </div>
            </a-checkbox-group>

            <div class="save-area">
              <a-button type="primary" :loading="saving" @click="handleSave" class="save-btn">
                <template #icon><check-outlined /></template>
                保存配置
              </a-button>
            </div>
          </div>

          <div v-else class="no-role-hint">
            <span class="no-role-icon">👆</span>
            <p>请先选择一个角色</p>
          </div>
        </div>
      </div>
    </div>

    <!-- ===== 新增/编辑菜单弹窗 ===== -->
    <a-modal
      v-model:open="modalVisible"
      :title="null"
      @ok="handleModalOk"
      :confirmLoading="modalLoading"
      :width="520"
      :destroy-on-close="true"
      :body-style="{ padding: 0 }"
      wrap-class-name="menu-modal-wrap"
    >
      <template #footer>
        <div class="modal-footer">
          <a-button @click="modalVisible = false" class="modal-cancel-btn">取消</a-button>
          <a-button type="primary" :loading="modalLoading" @click="handleModalOk" class="modal-save-btn">
            {{ isEditing ? '保存修改' : '创建菜单' }}
          </a-button>
        </div>
      </template>

      <div class="modal-header">
        <div class="modal-header-icon">
          {{ isEditing ? '✏️' : '➕' }}
        </div>
        <div class="modal-header-text">
          <h3>{{ isEditing ? '编辑菜单' : '新增菜单' }}</h3>
          <p>{{ isEditing ? '修改菜单项的路由与显示信息' : '创建一个新的侧边栏菜单项' }}</p>
        </div>
      </div>

      <div class="modal-body">
        <a-form :model="formData" :label-col="{ span: 4 }" :wrapper-col="{ span: 19 }">
          <a-form-item label="菜单名称">
            <a-input v-model:value="formData.name" placeholder="请输入菜单名称" size="large" />
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
      </div>
    </a-modal>

    <!-- ===== 角色管理弹窗 ===== -->
    <a-modal
      v-model:open="roleManageVisible"
      :title="null"
      :footer="null"
      :width="560"
      :destroy-on-close="true"
      :body-style="{ padding: 0 }"
      wrap-class-name="role-modal-wrap"
    >
      <div class="modal-header">
        <div class="modal-header-icon">👥</div>
        <div class="modal-header-text">
          <h3>角色管理</h3>
          <p>管理系统角色，支持创建、编辑和删除</p>
        </div>
      </div>

      <div class="modal-body">
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
          class="role-table"
        >
          <template #bodyCell="{ column, record }">
            <template v-if="column.key === 'action'">
              <a-space :size="4">
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
      </div>
    </a-modal>

    <!-- ===== 新增/编辑角色弹窗 ===== -->
    <a-modal
      v-model:open="roleFormVisible"
      :title="null"
      @ok="handleRoleFormOk"
      :confirmLoading="roleFormLoading"
      :width="480"
      :destroy-on-close="true"
      :body-style="{ padding: 0 }"
      wrap-class-name="role-modal-wrap"
    >
      <template #footer>
        <div class="modal-footer">
          <a-button @click="roleFormVisible = false" class="modal-cancel-btn">取消</a-button>
          <a-button type="primary" :loading="roleFormLoading" @click="handleRoleFormOk" class="modal-save-btn">
            {{ isEditingRole ? '保存修改' : '创建角色' }}
          </a-button>
        </div>
      </template>

      <div class="modal-header">
        <div class="modal-header-icon">
          {{ isEditingRole ? '✏️' : '✨' }}
        </div>
        <div class="modal-header-text">
          <h3>{{ isEditingRole ? '编辑角色' : '新增角色' }}</h3>
          <p>{{ isEditingRole ? '修改角色名称和描述信息' : '创建一个新的系统角色' }}</p>
        </div>
      </div>

      <div class="modal-body">
        <a-form :model="roleFormData" :label-col="{ span: 4 }" :wrapper-col="{ span: 19 }">
          <a-form-item label="角色名称">
            <a-input v-model:value="roleFormData.name" placeholder="如：运维人员" size="large" />
          </a-form-item>
          <a-form-item label="角色编码">
            <a-input v-model:value="roleFormData.code" placeholder="如：operator" :disabled="isEditingRole" />
          </a-form-item>
          <a-form-item label="角色描述">
            <a-input v-model:value="roleFormData.description" placeholder="角色描述（可选）" />
          </a-form-item>
        </a-form>
      </div>
    </a-modal>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import { message } from 'ant-design-vue'
import { PlusOutlined, SettingOutlined, CheckOutlined } from '@ant-design/icons-vue'
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
/* ================================================================
   整体布局
   ================================================================ */
.menu-permission {
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
  background: linear-gradient(135deg, #fef3c7, #fde68a);
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
   双栏布局
   ================================================================ */
.content-wrapper {
  display: flex;
  gap: 16px;
  flex: 1;
  min-height: 0;
}

.panel-card {
  flex: 1;
  background: #fff;
  border-radius: 12px;
  border: 1px solid #eef2f7;
  box-shadow: 0 1px 3px rgba(0, 0, 0, 0.04);
  display: flex;
  flex-direction: column;
  min-width: 0;
  overflow: hidden;
}

/* ================================================================
   面板头部
   ================================================================ */
.panel-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 14px 20px;
  border-bottom: 1px solid #f1f5f9;
  flex-shrink: 0;
}

.panel-header-left {
  display: flex;
  align-items: center;
  gap: 8px;
}

.panel-icon {
  font-size: 16px;
  line-height: 1;
}

.panel-title {
  font-size: 14px;
  font-weight: 600;
  color: #1a202c;
}

.panel-count {
  font-size: 11px !important;
  border-radius: 4px !important;
}

.panel-action-btn {
  height: 32px;
  border-radius: 6px;
  font-weight: 600;
  font-size: 12px;
  padding: 0 14px;
}

.panel-action-link {
  font-size: 13px;
}

/* ================================================================
   面板内容区
   ================================================================ */
.panel-body {
  flex: 1;
  overflow-y: auto;
  padding: 8px 0;
}

/* ================================================================
   菜单分组
   ================================================================ */
.menu-group {
  padding: 8px 16px 12px;
}

.menu-group + .menu-group {
  border-top: 1px solid #f8fafc;
}

.group-header {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 8px;
  padding: 4px 0;
}

.group-badge {
  font-size: 10px;
  font-weight: 700;
  padding: 1px 6px;
  border-radius: 4px;
  letter-spacing: 0.5px;
  line-height: 18px;
}

.group-badge.link {
  background: #eef2ff;
  color: #6366f1;
}

.group-badge.manage {
  background: #fef3c7;
  color: #d97706;
}

.group-badge.setting {
  background: #f0fdf4;
  color: #16a34a;
}

.group-title {
  font-size: 13px;
  font-weight: 600;
  color: #334155;
}

.group-count {
  font-size: 11px;
  color: #94a3b8;
  margin-left: auto;
}

/* ================================================================
   菜单项行
   ================================================================ */
.menu-item-row {
  display: flex;
  align-items: center;
  padding: 8px 10px;
  border-radius: 8px;
  transition: all 0.15s ease;
  gap: 12px;
  border: 1px solid transparent;
}

.menu-item-row:hover {
  background: #f8fafd;
  border-color: #eef2f7;
}

.menu-item-info {
  flex: 1;
  min-width: 0;
  display: flex;
  align-items: center;
  gap: 10px;
}

.menu-name {
  font-weight: 600;
  font-size: 13px;
  color: #1e293b;
  white-space: nowrap;
}

.menu-path {
  color: #94a3b8;
  font-size: 12px;
  font-family: 'SF Mono', 'Monaco', 'Consolas', monospace;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.menu-actions {
  white-space: nowrap;
  flex-shrink: 0;
}

.empty-hint {
  padding: 20px;
  text-align: center;
  color: #c0c8d4;
  font-size: 13px;
}

/* ================================================================
   右侧：角色选择器
   ================================================================ */
.role-selector {
  padding: 16px 20px;
  display: flex;
  align-items: center;
  gap: 10px;
}

.role-label {
  font-size: 13px;
  font-weight: 600;
  color: #334155;
  white-space: nowrap;
}

.role-code-hint {
  color: #94a3b8;
  font-size: 11px;
}

/* ================================================================
   菜单勾选区
   ================================================================ */
.menu-checkbox-area {
  padding: 0 20px 20px;
  flex: 1;
  overflow-y: auto;
}

.checkbox-list {
  display: flex;
  flex-direction: column;
}

.checkbox-section {
  margin-bottom: 16px;
}

.checkbox-section-title {
  font-size: 12px;
  font-weight: 600;
  color: #64748b;
  display: flex;
  align-items: center;
  gap: 6px;
  margin-bottom: 6px;
  padding-bottom: 6px;
  border-bottom: 1px dashed #f1f5f9;
}

.section-badge {
  font-size: 9px;
  font-weight: 700;
  padding: 1px 5px;
  border-radius: 3px;
  letter-spacing: 0.5px;
  line-height: 16px;
}

.section-badge.link {
  background: #eef2ff;
  color: #6366f1;
}

.section-badge.manage {
  background: #fef3c7;
  color: #d97706;
}

.section-badge.setting {
  background: #f0fdf4;
  color: #16a34a;
}

.checkbox-row {
  padding: 5px 0 5px 4px;
}

.checkbox-label {
  font-size: 13px;
  color: #334155;
}

.checkbox-path {
  color: #94a3b8;
  font-size: 11px;
  margin-left: 6px;
  font-family: 'SF Mono', 'Monaco', 'Consolas', monospace;
}

.empty-mini {
  color: #c0c8d4;
  font-size: 12px;
  padding: 4px 0;
}

/* 保存按钮区 */
.save-area {
  margin-top: 20px;
  padding-top: 16px;
  border-top: 1px solid #f1f5f9;
  display: flex;
  justify-content: center;
}

.save-btn {
  height: 38px;
  border-radius: 8px;
  padding: 0 28px;
  font-weight: 600;
  box-shadow: 0 2px 6px rgba(22, 119, 255, 0.2);
  display: inline-flex;
  align-items: center;
  gap: 6px;
}

/* 无角色提示 */
.no-role-hint {
  flex: 1;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  color: #c0c8d4;
  gap: 8px;
}

.no-role-icon {
  font-size: 32px;
  line-height: 1;
}

.no-role-hint p {
  font-size: 14px;
  margin: 0;
}

/* ================================================================
   弹窗通用样式
   ================================================================ */
.menu-modal-wrap :deep(.ant-modal-content),
.role-modal-wrap :deep(.ant-modal-content) {
  border-radius: 14px;
  overflow: hidden;
  box-shadow: 0 20px 60px rgba(0, 0, 0, 0.12);
}

.menu-modal-wrap :deep(.ant-modal-header),
.role-modal-wrap :deep(.ant-modal-header) {
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
  max-height: 50vh;
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

/* 角色管理头部 */
.role-manage-header {
  margin-bottom: 12px;
  text-align: right;
}

/* 角色表格 */
.role-table :deep(.ant-table-thead > tr > th) {
  background: #fafbfc;
  color: #64748b;
  font-weight: 600;
  font-size: 12px;
}

.role-table :deep(.ant-table-tbody > tr:hover > td) {
  background: #f8fafd;
}

/* ================================================================
   滚动条
   ================================================================ */
.menu-permission::-webkit-scrollbar,
.panel-body::-webkit-scrollbar,
.menu-checkbox-area::-webkit-scrollbar,
.modal-body::-webkit-scrollbar {
  width: 5px;
}

.menu-permission::-webkit-scrollbar-thumb,
.panel-body::-webkit-scrollbar-thumb,
.menu-checkbox-area::-webkit-scrollbar-thumb,
.modal-body::-webkit-scrollbar-thumb {
  background: #dce4f0;
  border-radius: 3px;
}

.menu-permission::-webkit-scrollbar-thumb:hover,
.panel-body::-webkit-scrollbar-thumb:hover,
.menu-checkbox-area::-webkit-scrollbar-thumb:hover,
.modal-body::-webkit-scrollbar-thumb:hover {
  background: #c0c8d4;
}

/* ================================================================
   暗色模式
   ================================================================ */
[data-theme="dark"] .menu-permission {
  background: #121418;
}

[data-theme="dark"] .page-header,
[data-theme="dark"] .panel-card {
  background: #1a1d22;
  border-color: #2a2d33;
}

[data-theme="dark"] .header-title,
[data-theme="dark"] .stat-num,
[data-theme="dark"] .panel-title,
[data-theme="dark"] .group-title,
[data-theme="dark"] .menu-name,
[data-theme="dark"] .checkbox-label {
  color: #e4e6ea;
}

[data-theme="dark"] .header-subtitle,
[data-theme="dark"] .stat-label,
[data-theme="dark"] .group-count,
[data-theme="dark"] .menu-path,
[data-theme="dark"] .checkbox-path,
[data-theme="dark"] .role-code-hint {
  color: #8b8f98;
}

[data-theme="dark"] .header-icon-box {
  background: linear-gradient(135deg, #2d2410, #241e0c);
}

[data-theme="dark"] .stat-divider {
  background: #2a2d33;
}

[data-theme="dark"] .panel-header {
  border-bottom-color: #2a2d33;
}

[data-theme="dark"] .menu-group + .menu-group {
  border-top-color: #1e2126;
}

[data-theme="dark"] .menu-item-row:hover {
  background: #1e2126;
  border-color: #2a2d33;
}

[data-theme="dark"] .empty-hint,
[data-theme="dark"] .empty-mini,
[data-theme="dark"] .no-role-hint {
  color: #6b6f78;
}

[data-theme="dark"] .role-selector {
  border-bottom-color: #2a2d33;
}

[data-theme="dark"] .role-label {
  color: #c4c8ce;
}

[data-theme="dark"] .checkbox-section-title {
  color: #8b8f98;
  border-bottom-color: #2a2d33;
}

[data-theme="dark"] .save-area {
  border-top-color: #2a2d33;
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

[data-theme="dark"] .role-table :deep(.ant-table-thead > tr > th) {
  background: #1e2126 !important;
  color: #8b8f98 !important;
}

[data-theme="dark"] .role-table :deep(.ant-table-tbody > tr > td) {
  border-bottom-color: #2a2d33 !important;
  color: #c4c8ce !important;
  background: #1a1d22 !important;
}

[data-theme="dark"] .role-table :deep(.ant-table-tbody > tr:hover > td) {
  background: #1e2126 !important;
}

[data-theme="dark"] :deep(.ant-input),
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
[data-theme="dark"] .role-table .ant-table {
  background: #1a1d22 !important;
}
[data-theme="dark"] .role-table .ant-table-thead > tr > th {
  background: #1e2126 !important;
  color: #8b8f98 !important;
}
[data-theme="dark"] .role-table .ant-table-tbody > tr > td {
  background: #1a1d22 !important;
  border-bottom-color: #2a2d33 !important;
  color: #c4c8ce !important;
}
[data-theme="dark"] .role-table .ant-table-tbody > tr:hover > td {
  background: #1e2126 !important;
}
</style>
