<template>
  <div class="skill-manage-container">
    <div class="page-header">
      <h2 class="page-title">技能管理</h2>
    </div>
    <div class="skill-content">
      <div class="toolbar">
        <a-select v-model:value="filterAgentId" placeholder="按Agent过滤" allowClear style="width:220px" @change="fetchList">
          <a-select-option v-for="a in agents" :key="a.id" :value="a.id">{{ a.avatar }} {{ a.name }}</a-select-option>
        </a-select>
        <a-button type="primary" @click="openCreate"><PlusOutlined /> 新建技能</a-button>
      </div>

      <!-- 卡片列表 -->
      <div v-if="loading" class="loading-text">加载中...</div>
      <div v-else-if="skills.length === 0" class="empty-text">暂无技能，点击上方按钮创建</div>
      <div v-else class="skill-card-list">
        <div v-for="skill in skills" :key="skill.id" class="skill-card">
          <div class="card-body">
            <div class="card-info">
              <div class="card-title-row">
                <span class="card-name">{{ skill.name }}</span>
                <a-tag v-if="skill.agentConfigId" color="purple" size="small">{{ getAgentName(skill.agentConfigId) }}</a-tag>
                <a-tag v-else color="default" size="small">全局</a-tag>
              </div>
              <span class="card-desc">{{ skill.description || '暂无描述' }}</span>
              <div class="card-meta">
                <span>置信度 {{ (skill.confidence || 0.5 * 100).toFixed(0) }}%</span>
                <span>使用 {{ skill.usageCount || 0 }} 次</span>
                <span v-if="skill.triggerWords">触发词：{{ formatTriggerPreview(skill.triggerWords) }}</span>
              </div>
            </div>
            <div class="card-actions">
              <a-button size="small" @click="openEdit(skill)"><EditOutlined /> 编辑</a-button>
              <a-popconfirm title="确定删除？" @confirm="handleDelete(skill.id)">
                <a-button size="small" danger><DeleteOutlined /> 删除</a-button>
              </a-popconfirm>
            </div>
          </div>
        </div>
      </div>
    </div>

    <!-- 新建/编辑弹窗 -->
    <a-modal
      v-model:open="modalVisible"
      :title="isEdit ? '编辑技能' : '新建技能'"
      :confirm-loading="saving"
      @ok="handleSave"
      @cancel="handleCancel"
      :width="680"
      ok-text="保存"
      cancel-text="取消"
      :destroy-on-close="true"
    >
      <a-form
        ref="formRef"
        :model="form"
        :label-col="{ span: 4 }"
        :wrapper-col="{ span: 20 }"
        class="skill-form"
      >
        <a-form-item label="名称" required>
          <a-input v-model:value="form.name" placeholder="技能名称" :maxLength="50" />
        </a-form-item>

        <a-form-item label="描述" required>
          <a-input v-model:value="form.description" placeholder="简要描述技能功能" :maxLength="200" />
        </a-form-item>

        <a-form-item label="关联Agent">
          <a-select v-model:value="form.agentConfigId" placeholder="选择关联Agent（可选）" allowClear @change="onAgentChange">
            <a-select-option v-for="a in agents" :key="a.id" :value="a.id">{{ a.avatar }} {{ a.name }}</a-select-option>
          </a-select>
        </a-form-item>

        <a-form-item label="工具选择" required>
          <div v-if="!selectedAgentTools.length" class="form-hint">请先选择关联Agent</div>
          <div v-else class="tools-checkbox-group">
            <a-checkbox
              v-for="tool in selectedAgentTools"
              :key="tool"
              :checked="formTools.includes(tool)"
              @change="(e: any) => toggleTool(tool, e.target.checked)"
              class="tool-checkbox"
            >{{ tool }}</a-checkbox>
          </div>
          <div v-if="selectedAgentTools.length" class="tools-actions">
            <a-button size="small" type="link" @click="selectAllTools">全选</a-button>
            <a-button size="small" type="link" @click="formTools = []">清空</a-button>
          </div>
        </a-form-item>

        <a-form-item label="触发词">
          <a-input v-model:value="triggerInput" placeholder='多个用逗号分隔，如：天气,气温,温度,预报' :maxLength="500" />
          <div class="form-hint">💡 用户消息中包含这些词时自动匹配此技能，用逗号分隔</div>
        </a-form-item>

        <a-form-item label="指令" required>
          <a-textarea v-model:value="form.instructions" placeholder="详细的执行步骤说明..." :rows="4" :maxLength="5000" show-count />
        </a-form-item>
      </a-form>
    </a-modal>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import { message } from 'ant-design-vue'
import { PlusOutlined, EditOutlined, DeleteOutlined } from '@ant-design/icons-vue'
import { listSkills, createSkill, updateSkill, deleteSkillApi, type SkillData } from '@/api/skill'
import { listAgentConfigs, type AgentConfig } from '@/api/agent-config'
import { useUserStore } from '@/store/user'

const AVAILABLE_TOOLS = [
  'web_search', 'web_fetch', 'read_file', 'write_file', 'edit_file',
  'glob_files', 'grep_search', 'read_project_tree', 'project_info',
  'run_command', 'run_server', 'ask_clarification', 'task_manager',
  'check_network', 'execute_sql', 'delete_file', 'http_request',
  'service_control', 'git_status', 'git_diff', 'git_log', 'git_add',
  'git_commit', 'git_branch', 'git_push', 'manage_skill',
  'report_skill_result', 'fork_agent', 'collect_agent', 'inspect_agent'
]

const userStore = useUserStore()
const agents = ref<AgentConfig[]>([])
const skills = ref<SkillData[]>([])
const loading = ref(false)
const filterAgentId = ref<number | undefined>()
const modalVisible = ref(false)
const isEdit = ref(false)
const saving = ref(false)

const form = ref({ name: '', description: '', agentConfigId: undefined as number | undefined, instructions: '' })
const formTools = ref<string[]>([])
const triggerInput = ref('')
const editId = ref<number | null>(null)

const userId = () => userStore.userInfo?.userId || userStore.userInfo?.id || 1

// 当前选中Agent的工具列表
const selectedAgentTools = computed(() => {
  if (!form.value.agentConfigId) return []
  const agent = agents.value.find(a => a.id === form.value.agentConfigId)
  if (!agent) return []
  // 如果Agent配置了工具列表就用它的，否则（默认编码助手等）用全部工具
  if (agent.toolNames) {
    try { const tools = JSON.parse(agent.toolNames) as string[]; return tools.length > 0 ? tools : AVAILABLE_TOOLS }
    catch { return AVAILABLE_TOOLS }
  }
  return AVAILABLE_TOOLS
})

const onAgentChange = () => { formTools.value = [] }

const toggleTool = (tool: string, checked: boolean) => {
  if (checked) { if (!formTools.value.includes(tool)) formTools.value.push(tool) }
  else { formTools.value = formTools.value.filter(t => t !== tool) }
}
const selectAllTools = () => { formTools.value = [...selectedAgentTools.value] }

const getAgentName = (id?: number) => agents.value.find(a => a.id === id)?.name || '全局'

const formatTriggerPreview = (json: string) => {
  try { const arr = JSON.parse(json); return arr.slice(0, 4).join(', ') + (arr.length > 4 ? '...' : '') }
  catch { return json }
}

const fetchList = async () => {
  loading.value = true
  try {
    const res = await listSkills(userId(), filterAgentId.value)
    if (res.code === 200) skills.value = res.data
  } catch (e: any) { message.error(e.message) }
  finally { loading.value = false }
}

const resetForm = () => {
  form.value = { name: '', description: '', agentConfigId: undefined, instructions: '' }
  formTools.value = []
  triggerInput.value = ''
}

const openCreate = () => {
  isEdit.value = false; editId.value = null
  resetForm()
  modalVisible.value = true
}

const openEdit = (record: SkillData) => {
  isEdit.value = true; editId.value = record.id!
  form.value = {
    name: record.name,
    description: record.description,
    agentConfigId: record.agentConfigId,
    instructions: record.instructions
  }
  // 解析工具列表
  if (record.toolNames) {
    try { formTools.value = JSON.parse(record.toolNames) } catch { formTools.value = [] }
  } else { formTools.value = [] }
  // 解析触发词
  if (record.triggerWords) {
    try { triggerInput.value = (JSON.parse(record.triggerWords) as string[]).join(', ') } catch { triggerInput.value = record.triggerWords }
  } else { triggerInput.value = '' }
  modalVisible.value = true
}

const handleSave = async () => {
  if (!form.value.name.trim()) { message.warning('请输入技能名称'); return }
  if (!form.value.description.trim()) { message.warning('请输入技能描述'); return }
  if (!form.value.instructions.trim()) { message.warning('请输入技能指令'); return }
  if (formTools.value.length === 0) { message.warning('请选择至少一个工具'); return }
  saving.value = true
  try {
    const triggerWords = triggerInput.value.trim()
      ? JSON.stringify(triggerInput.value.split(',').map(s => s.trim()).filter(Boolean))
      : '[]'
    const payload = {
      name: form.value.name.trim(),
      description: form.value.description.trim(),
      toolNames: JSON.stringify(formTools.value),
      instructions: form.value.instructions.trim(),
      triggerWords,
      agentConfigId: form.value.agentConfigId,
      userId: userId(),
      agentType: 'code_assistant'
    }
    if (isEdit.value && editId.value) {
      await updateSkill(editId.value, payload)
    } else {
      await createSkill(payload)
    }
    modalVisible.value = false
    await fetchList()
    message.success(isEdit.value ? '已更新' : '已创建')
  } catch (e: any) { message.error(e.message) }
  finally { saving.value = false }
}

const handleCancel = () => { modalVisible.value = false }

const handleDelete = async (id: number) => {
  try { await deleteSkillApi(id, userId()); message.success('已删除'); await fetchList() }
  catch (e: any) { message.error(e.message) }
}

onMounted(async () => {
  try { const res = await listAgentConfigs(); if (res.code === 200) agents.value = res.data } catch {}
  await fetchList()
})
</script>

<style scoped>
.skill-manage-container { padding: 24px; background: #f5f7fa; min-height: 100vh; }
.page-header { margin-bottom: 16px; padding: 16px 20px; background: white; border-radius: 8px; box-shadow: 0 1px 4px rgba(0,0,0,0.06); }
.page-title { margin: 0; font-size: 20px; font-weight: 600; color: #1a202c; }
.skill-content { background: white; border-radius: 8px; padding: 16px; box-shadow: 0 1px 4px rgba(0,0,0,0.06); }
.toolbar { display: flex; gap: 12px; margin-bottom: 16px; justify-content: space-between; }
.loading-text, .empty-text { text-align: center; padding: 40px; color: #999; font-size: 14px; }

.skill-card-list { display: flex; flex-direction: column; gap: 12px; }
.skill-card { background: #fafbfc; border: 1px solid #f0f0f0; border-radius: 8px; padding: 16px; transition: box-shadow .15s; }
.skill-card:hover { box-shadow: 0 2px 8px rgba(0,0,0,0.06); }
.card-body { display: flex; justify-content: space-between; align-items: flex-start; gap: 12px; }
.card-info { flex: 1; min-width: 0; }
.card-title-row { display: flex; align-items: center; gap: 8px; margin-bottom: 4px; }
.card-name { font-size: 15px; font-weight: 500; color: #1a202c; }
.card-desc { display: block; font-size: 13px; color: #8c8c8c; margin-bottom: 6px; }
.card-meta { display: flex; gap: 16px; font-size: 12px; color: #999; }
.card-actions { display: flex; gap: 8px; flex-shrink: 0; }

.tools-checkbox-group { display: flex; flex-wrap: wrap; gap: 4px 8px; max-height: 180px; overflow-y: auto; padding: 4px 0; }
.tool-checkbox { font-size: 12px; margin-right: 0; }
.tools-actions { margin-top: 6px; }
.form-hint { font-size: 12px; color: #8c8c8c; margin-top: 4px; }
</style>
