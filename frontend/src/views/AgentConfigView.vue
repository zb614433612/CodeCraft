<template>
  <div class="agent-config-container">
    <div class="page-header">
      <h2 class="page-title">Agent 管理</h2>
      <a-button type="primary" @click="openCreateModal">
        <PlusOutlined /> 新建 Agent
      </a-button>
    </div>

    <div class="agent-content">
      <!-- 加载状态 -->
      <div v-if="loading" class="loading-wrapper">
        <a-spin size="large" tip="加载中..." />
      </div>

      <!-- 空状态 -->
      <div v-else-if="agentList.length === 0" class="empty-wrapper">
        <a-empty description="暂无 Agent 配置">
          <a-button type="primary" @click="openCreateModal">新建 Agent</a-button>
        </a-empty>
      </div>

      <!-- Agent 列表（卡片） -->
      <div v-else class="agent-list">
        <div
          v-for="agent in agentList"
          :key="agent.id"
          :class="['agent-card', { 'is-default': agent.isDefault }]"
        >
          <div class="card-top">
            <span class="agent-avatar">{{ agent.avatar || '🤖' }}</span>
            <div class="agent-info">
              <div class="agent-name-row">
                <span class="agent-name">{{ agent.name }}</span>
                <a-tag v-if="agent.isDefault" color="blue" class="default-badge">默认</a-tag>
              </div>
              <span class="agent-desc">{{ agent.description || '暂无描述' }}</span>
            </div>
          </div>

          <div class="card-tools" v-if="agent.tools && agent.tools.length > 0">
            <span class="tools-label">工具：</span>
            <a-tag v-for="tool in agent.tools.slice(0, 6)" :key="tool" size="small" class="tool-tag">
              {{ tool }}
            </a-tag>
            <a-tag v-if="agent.tools.length > 6" size="small" class="tool-tag tool-more">
              +{{ agent.tools.length - 6 }}
            </a-tag>
          </div>

          <div class="card-actions">
            <a-button
              v-if="!agent.isDefault"
              size="small"
              type="dashed"
              @click="handleSetDefault(agent)"
              :loading="settingDefaultId === agent.id"
            >
              设为默认
            </a-button>
            <a-button size="small" @click="openEditModal(agent)" :disabled="agent.isBuiltin">
              <EditOutlined /> 编辑
            </a-button>
            <a-popconfirm
              v-if="!agent.isBuiltin"
              title="确定要删除这个 Agent 吗？"
              ok-text="删除"
              ok-type="danger"
              cancel-text="取消"
              @confirm="handleDelete(agent.id)"
            >
              <a-button size="small" danger>
                <DeleteOutlined /> 删除
              </a-button>
            </a-popconfirm>
          </div>
        </div>
      </div>
    </div>

    <!-- 新建/编辑弹窗 -->
    <a-modal
      v-model:open="modalVisible"
      :title="isEditing ? '编辑 Agent' : '新建 Agent'"
      :confirm-loading="modalSaving"
      @ok="handleSave"
      @cancel="handleCancel"
      :width="680"
      ok-text="保存"
      cancel-text="取消"
      :destroy-on-close="true"
    >
      <a-form
        ref="formRef"
        :model="formData"
        :label-col="{ span: 4 }"
        :wrapper-col="{ span: 20 }"
        class="agent-form"
      >
        <a-form-item label="名称" required>
          <a-input v-model:value="formData.name" placeholder="输入 Agent 名称" :maxLength="50" />
        </a-form-item>

        <a-form-item label="描述">
          <a-input v-model:value="formData.description" placeholder="输入 Agent 描述" :maxLength="200" />
        </a-form-item>

        <a-form-item label="头像">
          <div class="avatar-picker">
            <div class="avatar-current" @click="showEmojiPicker = !showEmojiPicker">
              <span class="avatar-preview">{{ formData.avatar || '🤖' }}</span>
              <span class="avatar-hint">点击选择</span>
            </div>
            <div v-if="showEmojiPicker" class="emoji-grid">
              <span
                v-for="emoji in emojiList"
                :key="emoji"
                :class="['emoji-item', { selected: formData.avatar === emoji }]"
                @click="formData.avatar = emoji; showEmojiPicker = false"
              >{{ emoji }}</span>
            </div>
          </div>
        </a-form-item>

        <a-form-item label="系统提示词">
          <a-textarea
            v-model:value="formData.systemPrompt"
            placeholder="输入系统提示词..."
            :rows="5"
            :maxLength="5000"
            show-count
          />
        </a-form-item>

        <a-form-item label="工具选择">
          <div class="tools-checkbox-group">
            <a-checkbox
              v-for="tool in availableTools"
              :key="tool"
              :checked="formData.tools.includes(tool)"
              @change="(e: any) => toggleTool(tool, e.target.checked)"
              class="tool-checkbox"
            >
              {{ tool }}
            </a-checkbox>
          </div>
          <div class="tools-actions">
            <a-button size="small" type="link" @click="selectAllTools">全选</a-button>
            <a-button size="small" type="link" @click="formData.tools = []">清空</a-button>
          </div>
        </a-form-item>

        <a-form-item label="工作目录">
          <a-input v-model:value="formData.workDir" placeholder="输入工作目录路径（可选）" />
        </a-form-item>

      </a-form>
    </a-modal>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, onMounted } from 'vue'
import { message } from 'ant-design-vue'
import {
  PlusOutlined,
  EditOutlined,
  DeleteOutlined,
  FolderOpenOutlined
} from '@ant-design/icons-vue'
import {
  listAgentConfigs,
  createAgentConfig,
  updateAgentConfig,
  deleteAgentConfig,
  setDefaultAgentConfig,
  type AgentConfig
} from '@/api/agent-config'

// ===== 状态 =====
const agentList = ref<AgentConfig[]>([])
const loading = ref(false)
const modalVisible = ref(false)
const isEditing = ref(false)
const editingId = ref<number | null>(null)
const oldToolNames = ref<string[] | null>(null)
const modalSaving = ref(false)
const settingDefaultId = ref<number | null>(null)
const showEmojiPicker = ref(false)

// 常用 emoji 列表
const emojiList = [
  '🤖', '🐱', '🐶', '🦊', '🐰', '🐻', '🐼', '🐨',
  '🦄', '🐙', '🦀', '🐳', '🦋', '🌻', '🌟', '🔥',
  '💡', '🎯', '🚀', '⚡', '🎨', '🔧', '📚', '💻',
  '🧠', '👾', '🗿', '🤠', '😎', '🧙', '🦸', '🎭'
]

// 可用工具列表
const availableTools = [
  'web_search', 'web_fetch', 'read_file', 'write_file', 'edit_file',
  'glob_files', 'grep_search', 'read_project_tree', 'project_info',
  'run_command', 'run_server', 'ask_clarification', 'task_manager',
  'check_network', 'execute_sql', 'delete_file', 'http_request',
  'service_control', 'git_status', 'git_diff', 'git_log', 'git_add',
  'git_commit', 'git_branch', 'git_push', 'manage_skill',
  'report_skill_result', 'fork_agent', 'collect_agent', 'inspect_agent'
]

// 解析 toolNames JSON 字符串为 tools 数组
const parseToolNames = (toolNames?: string): string[] => {
  if (!toolNames) return []
  try { return JSON.parse(toolNames) as string[] } catch { return [] }
}

// 表单数据
const getDefaultForm = (): Partial<AgentConfig> => ({
  name: '',
  description: '',
  avatar: '🤖',
  systemPrompt: '',
  tools: [],
  workDir: '',
})

const formData = reactive<Partial<AgentConfig>>(getDefaultForm())

// ===== 数据加载 =====
const fetchList = async () => {
  loading.value = true
  try {
    const res = await listAgentConfigs()
    if (res.code === 200 && res.data) {
      agentList.value = res.data.map(a => ({
        ...a,
        tools: parseToolNames(a.toolNames)
      }))
    }
  } catch (e: any) {
    message.error(e.message || '获取 Agent 列表失败')
  } finally {
    loading.value = false
  }
}

// ===== 弹窗操作 =====
const openCreateModal = () => {
  isEditing.value = false
  editingId.value = null
  Object.assign(formData, getDefaultForm())
  showEmojiPicker.value = false
  modalVisible.value = true
}

const openEditModal = (agent: AgentConfig) => {
  if (agent.isBuiltin) {
    message.warning('内置 Agent 不允许修改')
    return
  }
  isEditing.value = true
  editingId.value = agent.id
  // 解析 toolNames JSON 字符串为 tools 数组
  let toolsArray: string[] = []
  if (agent.toolNames) {
    try { toolsArray = JSON.parse(agent.toolNames) } catch { toolsArray = [] }
  }
  // 记录编辑前的工具列表，用于保存后检测变更
  oldToolNames.value = [...toolsArray]
  Object.assign(formData, {
    name: agent.name,
    description: agent.description || '',
    avatar: agent.avatar || '🤖',
    systemPrompt: agent.systemPrompt || '',
    tools: toolsArray,
    workDir: agent.workDir || '',
  })
  showEmojiPicker.value = false
  modalVisible.value = true
}

const handleCancel = () => {
  modalVisible.value = false
  showEmojiPicker.value = false
}

const handleSave = async () => {
  if (!formData.name?.trim()) {
    message.warning('请输入 Agent 名称')
    return
  }

  modalSaving.value = true
  try {
    const payload: Partial<AgentConfig> = {
      name: formData.name.trim(),
      description: formData.description?.trim() || '',
      avatar: formData.avatar || '🤖',
      systemPrompt: formData.systemPrompt || '',
      toolNames: JSON.stringify(formData.tools || []),
      workDir: formData.workDir || '',
    }

    if (isEditing.value && editingId.value) {
      await updateAgentConfig(editingId.value, payload)
      // 检查工具列表是否变更，提醒用户可能影响关联技能
      const oldTools = oldToolNames.value || []
      const newTools = formData.tools || []
      const removed = oldTools.filter((t: string) => !newTools.includes(t))
      if (removed.length > 0) {
        message.warning(`已移除工具：${removed.join(', ')}。如果有关联技能使用了这些工具，技能可能受影响。`)
      } else {
        message.success('Agent 已更新')
      }
    } else {
      await createAgentConfig(payload)
      message.success('Agent 已创建')
    }

    modalVisible.value = false
    showEmojiPicker.value = false
    await fetchList()
  } catch (e: any) {
    message.error(e.message || '保存失败')
  } finally {
    modalSaving.value = false
  }
}

// ===== 删除 =====
const handleDelete = async (id: number) => {
  try {
    await deleteAgentConfig(id)
    message.success('Agent 已删除')
    await fetchList()
  } catch (e: any) {
    message.error(e.message || '删除失败')
  }
}

// ===== 设为默认 =====
const handleSetDefault = async (agent: AgentConfig) => {
  settingDefaultId.value = agent.id
  try {
    await setDefaultAgentConfig(agent.id)
    message.success(`已将「${agent.name}」设为默认 Agent`)
    await fetchList()
  } catch (e: any) {
    message.error(e.message || '设置失败')
  } finally {
    settingDefaultId.value = null
  }
}

// ===== 工具选择 =====
const toggleTool = (tool: string, checked: boolean) => {
  if (checked) {
    if (!formData.tools!.includes(tool)) {
      formData.tools!.push(tool)
    }
  } else {
    formData.tools = formData.tools!.filter(t => t !== tool)
  }
}

const selectAllTools = () => {
  formData.tools = [...availableTools]
}

// ===== 浏览工作目录 =====
const browseWorkDir = () => {
  // 浏览器环境：提示用户手动输入，或尝试调用 Electron API
  if ((window as any).electronAPI?.selectDirectory) {
    (window as any).electronAPI.selectDirectory().then((dir: string | null) => {
      if (dir) {
        formData.workDir = dir
      }
    })
  } else {
    message.info('请在输入框中输入工作目录路径')
  }
}

onMounted(() => {
  fetchList()
})
</script>

<style scoped>
.agent-config-container {
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

.agent-content {
  min-height: 200px;
}

.loading-wrapper {
  display: flex;
  justify-content: center;
  align-items: center;
  height: 300px;
}

.empty-wrapper {
  display: flex;
  justify-content: center;
  align-items: center;
  height: 300px;
  background: white;
  border-radius: 8px;
  box-shadow: 0 1px 4px rgba(0, 0, 0, 0.06);
}

/* Agent 卡片列表 */
.agent-list {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(360px, 1fr));
  gap: 16px;
}

.agent-card {
  background: white;
  border-radius: 8px;
  box-shadow: 0 1px 4px rgba(0, 0, 0, 0.06);
  padding: 20px;
  transition: box-shadow 0.2s, border-color 0.2s;
  border: 2px solid transparent;
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.agent-card:hover {
  box-shadow: 0 2px 8px rgba(0, 0, 0, 0.1);
}

.agent-card.is-default {
  border-color: #1890ff;
  background: #f0f7ff;
}

.card-top {
  display: flex;
  align-items: flex-start;
  gap: 12px;
}

.agent-avatar {
  font-size: 36px;
  line-height: 1;
  flex-shrink: 0;
}

.agent-info {
  flex: 1;
  min-width: 0;
}

.agent-name-row {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 4px;
}

.agent-name {
  font-size: 16px;
  font-weight: 600;
  color: #1a202c;
}

.default-badge {
  font-size: 11px;
}

.agent-desc {
  font-size: 13px;
  color: #8c8c8c;
  display: block;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.card-meta {
  display: flex;
  gap: 16px;
  flex-wrap: wrap;
}

.meta-item {
  font-size: 12px;
  color: #595959;
}

.meta-label {
  color: #8c8c8c;
}

.card-tools {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 4px;
}

.tools-label {
  font-size: 12px;
  color: #8c8c8c;
  margin-right: 4px;
}

.tool-tag {
  font-size: 11px;
  margin: 0;
}

.tool-more {
  cursor: default;
}

.card-actions {
  display: flex;
  gap: 8px;
  padding-top: 8px;
  border-top: 1px solid #f0f0f0;
}

/* 弹窗表单 */
.agent-form :deep(.ant-form-item) {
  margin-bottom: 16px;
}

.avatar-picker {
  position: relative;
}

.avatar-current {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  padding: 8px 14px;
  border: 1px dashed #d9d9d9;
  border-radius: 6px;
  cursor: pointer;
  transition: border-color 0.2s;
}

.avatar-current:hover {
  border-color: #1890ff;
}

.avatar-preview {
  font-size: 28px;
}

.avatar-hint {
  font-size: 12px;
  color: #8c8c8c;
}

.emoji-grid {
  position: absolute;
  top: 100%;
  left: 0;
  margin-top: 8px;
  display: grid;
  grid-template-columns: repeat(8, 1fr);
  gap: 4px;
  padding: 12px;
  background: white;
  border: 1px solid #d9d9d9;
  border-radius: 8px;
  box-shadow: 0 4px 12px rgba(0, 0, 0, 0.12);
  z-index: 10;
  max-height: 240px;
  overflow-y: auto;
}

.emoji-item {
  font-size: 22px;
  padding: 6px;
  text-align: center;
  cursor: pointer;
  border-radius: 6px;
  transition: background 0.15s;
}

.emoji-item:hover {
  background: #f0f5ff;
}

.emoji-item.selected {
  background: #e6f7ff;
  outline: 2px solid #1890ff;
}

.tools-checkbox-group {
  display: flex;
  flex-wrap: wrap;
  gap: 4px;
  max-height: 200px;
  overflow-y: auto;
  padding: 8px;
  background: #fafafa;
  border-radius: 6px;
  border: 1px solid #f0f0f0;
  margin-bottom: 4px;
}

.tool-checkbox {
  width: calc(33.33% - 4px);
  margin: 0;
  font-size: 12px;
}

.tools-actions {
  display: flex;
  gap: 8px;
}

/* 响应式 */
@media (max-width: 768px) {
  .agent-list {
    grid-template-columns: 1fr;
  }
}
</style>
