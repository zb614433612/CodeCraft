<template>
  <div class="skill-manage">
    <!-- ===== 头部卡片 ===== -->
    <div class="page-header">
      <div class="header-left">
        <div class="header-icon-box">
          <span class="header-icon">🔧</span>
        </div>
        <div class="header-text">
          <h2 class="header-title">技能管理</h2>
          <p class="header-subtitle">管理和配置编码助手 AI 的技能库，自定义触发规则与工具组合</p>
        </div>
      </div>
      <div class="header-right">
        <div class="stat-item">
          <span class="stat-num">{{ skills.length }}</span>
          <span class="stat-label">技能总数</span>
        </div>
        <div class="stat-divider"></div>
        <div class="stat-item">
          <span class="stat-num">{{ activeSkillCount }}</span>
          <span class="stat-label">已激活</span>
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
            placeholder="搜索技能名称、描述或触发词..."
            class="search-input"
          />
          <CloseCircleFilled
            v-if="searchKeyword"
            class="search-clear"
            @click="searchKeyword = ''"
          />
        </div>
        <a-select
          v-model:value="filterAgentId"
          placeholder="全部 Agent"
          allowClear
          style="width: 180px"
          @change="fetchList"
        >
          <a-select-option v-for="a in agents" :key="a.id" :value="a.id">
            {{ a.avatar }} {{ a.name }}
          </a-select-option>
        </a-select>
      </div>
      <div class="toolbar-right">
        <a-button type="primary" @click="openCreate" class="create-btn">
          <template #icon><PlusOutlined /></template>
          新建技能
        </a-button>
      </div>
    </div>

    <!-- ===== 内容区 ===== -->
    <div class="content-card">
      <!-- 加载中 -->
      <div v-if="loading" class="state-box">
        <a-spin size="large" />
        <p class="state-text">加载技能列表中...</p>
      </div>

      <!-- 初始空状态 -->
      <div v-else-if="skills.length === 0" class="state-box empty-state">
        <div class="empty-illustration">
          <span class="empty-icon">📦</span>
        </div>
        <p class="empty-title">还没有技能</p>
        <p class="empty-desc">点击上方「新建技能」按钮，为编码助手创建第一个技能吧</p>
        <a-button type="primary" @click="openCreate" class="empty-cta">
          <PlusOutlined /> 立即创建
        </a-button>
      </div>

      <!-- 搜索/过滤后无结果 -->
      <div v-else-if="filteredSkills.length === 0" class="state-box empty-state">
        <div class="empty-illustration">
          <span class="empty-icon">🔍</span>
        </div>
        <p class="empty-title">未找到匹配的技能</p>
        <p class="empty-desc">尝试调整搜索关键词或切换过滤条件</p>
        <a-button @click="clearFilters" class="empty-cta">清除筛选</a-button>
      </div>

      <!-- 技能卡片列表 -->
      <div v-else class="skill-list">
        <TransitionGroup name="card-list" tag="div">
          <div
            v-for="skill in filteredSkills"
            :key="skill.id"
            class="skill-card"
            :class="getConfidenceClass(skill.confidence)"
          >
            <!-- 左侧状态指示条 -->
            <div
              class="card-accent"
              :style="{ background: getConfidenceGradient(skill.confidence) }"
            ></div>

            <div class="card-body">
              <!-- 上半部分：信息 + 操作 -->
              <div class="card-top">
                <div class="card-info">
                  <div class="card-title-row">
                    <span class="card-name" :title="skill.name">{{ skill.name }}</span>
                    <span
                      class="confidence-pill"
                      :style="confidencePillStyle(skill.confidence)"
                    >
                      {{ ((skill.confidence ?? 0.5) * 100).toFixed(0) }}%
                    </span>
                    <a-tag
                      v-if="skill.agentConfigId"
                      color="purple"
                      size="small"
                      class="scope-tag"
                    >{{ getAgentName(skill.agentConfigId) }}</a-tag>
                    <a-tag v-else color="blue" size="small" class="scope-tag">全局</a-tag>
                  </div>
                  <p class="card-desc">{{ skill.description || '暂无描述' }}</p>
                </div>

                <div class="card-actions">
                  <a-button
                    size="small"
                    class="action-btn"
                    @click="openEdit(skill)"
                    title="编辑技能"
                  >
                    <EditOutlined />
                  </a-button>
                  <a-popconfirm
                    title="确定删除该技能？此操作不可撤销"
                    @confirm="handleDelete(skill.id)"
                    ok-text="确定"
                    cancel-text="取消"
                    ok-type="danger"
                  >
                    <a-button size="small" class="action-btn action-delete" title="删除技能">
                      <DeleteOutlined />
                    </a-button>
                  </a-popconfirm>
                </div>
              </div>

              <!-- 下半部分：置信度条 + meta -->
              <div class="card-bottom">
                <div class="confidence-track">
                  <div
                    class="confidence-fill"
                    :style="{
                      width: ((skill.confidence ?? 0.5) * 100) + '%',
                      background: getConfidenceGradient(skill.confidence)
                    }"
                  >
                    <span
                      v-if="(skill.confidence ?? 0.5) >= 0.6"
                      class="confidence-label"
                    >{{ ((skill.confidence ?? 0.5) * 100).toFixed(0) }}%</span>
                  </div>
                </div>

                <div class="card-meta">
                  <span class="meta-item">
                    <span class="meta-dot" style="background:#1677ff"></span>
                    使用 {{ skill.usageCount || 0 }} 次
                  </span>

                  <span
                    v-if="skill.triggerWords && getTriggerWordsArr(skill.triggerWords).length > 0"
                    class="meta-item meta-triggers"
                  >
                    <span class="meta-dot" style="background:#52c41a"></span>
                    <span class="trigger-chips">
                      <span
                        v-for="(w, i) in getTriggerWordsArr(skill.triggerWords).slice(0, 5)"
                        :key="i"
                        class="trigger-chip"
                      >{{ w }}</span>
                      <span
                        v-if="getTriggerWordsArr(skill.triggerWords).length > 5"
                        class="trigger-chip trigger-more"
                      >+{{ getTriggerWordsArr(skill.triggerWords).length - 5 }}</span>
                    </span>
                  </span>

                  <span v-if="skill.updatedAt" class="meta-item meta-time">
                    <span class="meta-dot" style="background:#94a3b8"></span>
                    {{ formatTime(skill.updatedAt) }}
                  </span>
                </div>
              </div>
            </div>
          </div>
        </TransitionGroup>
      </div>
    </div>

    <!-- ===== 新建/编辑弹窗 ===== -->
    <a-modal
      v-model:open="modalVisible"
      :title="null"
      :confirm-loading="saving"
      @ok="handleSave"
      @cancel="handleCancel"
      :width="720"
      :destroy-on-close="true"
      :body-style="{ padding: 0 }"
      wrap-class-name="skill-modal-wrap"
    >
      <template #footer>
        <div class="modal-footer">
          <a-button @click="handleCancel" class="modal-cancel-btn">取消</a-button>
          <a-button type="primary" @click="handleSave" :loading="saving" class="modal-save-btn">
            {{ isEdit ? '保存修改' : '创建技能' }}
          </a-button>
        </div>
      </template>

      <!-- 弹窗头部 -->
      <div class="modal-header">
        <div class="modal-header-icon">
          {{ isEdit ? '✏️' : '✨' }}
        </div>
        <div class="modal-header-text">
          <h3>{{ isEdit ? '编辑技能' : '新建技能' }}</h3>
          <p>{{ isEdit ? '修改技能配置信息' : '创建一个新的编码助手技能' }}</p>
        </div>
      </div>

      <!-- 弹窗内容 -->
      <div class="modal-body">
        <a-form
          ref="formRef"
          :model="form"
          :label-col="{ span: 3 }"
          :wrapper-col="{ span: 21 }"
          class="skill-form"
        >
          <!-- 基本信息 -->
          <div class="form-section">
            <div class="form-section-title">基本信息</div>
            <a-form-item label="名称" required>
              <a-input
                v-model:value="form.name"
                placeholder="给技能起个简洁的名字"
                :maxLength="50"
                size="large"
              />
            </a-form-item>
            <a-form-item label="描述" required>
              <a-input
                v-model:value="form.description"
                placeholder="简要描述这个技能的功能和用途"
                :maxLength="200"
              />
            </a-form-item>
          </div>

          <!-- Agent & 工具 -->
          <div class="form-section">
            <div class="form-section-title">关联配置</div>
            <a-form-item label="Agent">
              <a-select
                v-model:value="form.agentConfigId"
                placeholder="选择关联的 Agent（不选则为全局技能）"
                allowClear
                @change="onAgentChange"
              >
                <a-select-option v-for="a in agents" :key="a.id" :value="a.id">
                  {{ a.avatar }} {{ a.name }}
                </a-select-option>
              </a-select>
            </a-form-item>

            <a-form-item label="工具" required>
              <div v-if="loadingTools" class="form-hint">
                <a-spin size="small" /> 加载工具列表中...
              </div>
              <div v-else-if="!form.agentConfigId" class="form-hint">
                💡 请先选择关联 Agent，或留空使用全局工具集
              </div>
              <div v-else-if="!selectedAgentToolCategories.length" class="form-hint">
                ⚠️ 该 Agent 没有可用工具，请检查 Agent 配置
              </div>
              <div v-else class="tools-panel">
                <div class="tools-header">
                  <span class="tools-count">已选 {{ formTools.length }} 个工具</span>
                  <div class="tools-header-actions">
                    <a-button size="small" type="link" @click="selectAllTools">全选</a-button>
                    <span class="tools-divider">|</span>
                    <a-button size="small" type="link" danger @click="formTools = []">清空</a-button>
                  </div>
                </div>
                <a-collapse :bordered="false" ghost class="tools-collapse">
                  <a-collapse-panel
                    v-for="cat in selectedAgentToolCategories"
                    :key="cat.code"
                  >
                    <template #header>
                      <span class="collapse-cat-label">{{ cat.label }}</span>
                      <span class="collapse-cat-count">{{ cat.tools.length }}</span>
                    </template>
                    <div class="tool-items">
                      <div
                        v-for="tool in cat.tools"
                        :key="tool.name"
                        class="tool-item"
                        :class="{ 'tool-item--checked': formTools.includes(tool.name) }"
                      >
                        <a-checkbox
                          :checked="formTools.includes(tool.name)"
                          @change="(e: any) => toggleTool(tool.name, e.target.checked)"
                        >
                          <span class="tool-name">{{ tool.name }}</span>
                        </a-checkbox>
                        <span class="tool-desc">{{ tool.description }}</span>
                        <span v-if="tool.risk === 'high'" class="tool-risk" title="高危操作">⚠️</span>
                      </div>
                    </div>
                  </a-collapse-panel>
                </a-collapse>
              </div>
            </a-form-item>
          </div>

          <!-- 触发与指令 -->
          <div class="form-section">
            <div class="form-section-title">触发规则 & 指令</div>
            <a-form-item label="触发词">
              <div>
                <a-input
                  v-model:value="triggerInput"
                  placeholder='多个触发词用逗号分隔，如：天气,气温,温度,预报'
                  :maxLength="500"
                />
                <div class="form-hint-inline">
                  💡 用户消息中包含这些关键词时，系统会自动匹配并激活此技能
                </div>
              </div>
            </a-form-item>
            <a-form-item label="指令" required>
              <a-textarea
                v-model:value="form.instructions"
                placeholder="详细的执行步骤说明，越详细技能执行成功率越高..."
                :rows="5"
                :maxLength="5000"
                show-count
              />
            </a-form-item>
          </div>
        </a-form>
      </div>
    </a-modal>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import { message } from 'ant-design-vue'
import {
  PlusOutlined,
  EditOutlined,
  DeleteOutlined,
  SearchOutlined,
  CloseCircleFilled
} from '@ant-design/icons-vue'
import { listSkills, createSkill, updateSkill, deleteSkillApi, type SkillData } from '@/api/skill'
import { listAgentConfigs, type AgentConfig } from '@/api/agent-config'
import { listToolRegistry, type ToolCategory } from '@/api/tools'
import { useUserStore } from '@/store/user'

// ===== 基础状态 =====
const userStore = useUserStore()
const agents = ref<AgentConfig[]>([])
const toolCategories = ref<ToolCategory[]>([])
const loadingTools = ref(false)
const skills = ref<SkillData[]>([])
const loading = ref(false)
const filterAgentId = ref<number | undefined>()
const modalVisible = ref(false)
const isEdit = ref(false)
const saving = ref(false)
const searchKeyword = ref('')

const form = ref({
  name: '',
  description: '',
  agentConfigId: undefined as number | undefined,
  instructions: ''
})
const formTools = ref<string[]>([])
const triggerInput = ref('')
const editId = ref<number | null>(null)

const userId = () => userStore.userInfo?.userId || userStore.userInfo?.id || 1

// ===== 计算属性 =====

// 已激活技能（置信度>=40%）
const activeSkillCount = computed(() =>
  skills.value.filter(s => (s.confidence ?? 0.5) >= 0.4).length
)

// 前端搜索过滤
const filteredSkills = computed(() => {
  if (!searchKeyword.value.trim()) return skills.value
  const kw = searchKeyword.value.trim().toLowerCase()
  return skills.value.filter(s => {
    if (s.name.toLowerCase().includes(kw)) return true
    if (s.description?.toLowerCase().includes(kw)) return true
    if (s.triggerWords) {
      try {
        const words: string[] = JSON.parse(s.triggerWords)
        if (words.some(w => w.toLowerCase().includes(kw))) return true
      } catch { /* ignore */ }
    }
    return false
  })
})

const allToolNamesFlat = computed(() =>
  toolCategories.value.flatMap(c => c.tools.map(t => t.name))
)

const selectedAgentToolCategories = computed(() => {
  if (!form.value.agentConfigId) return []
  const agent = agents.value.find(a => a.id === form.value.agentConfigId)
  if (!agent) return []

  let agentToolNames: string[] = []
  if (agent.toolNames) {
    try {
      const tools = JSON.parse(agent.toolNames) as string[]
      agentToolNames = tools.length > 0 ? tools : allToolNamesFlat.value
    } catch {
      agentToolNames = allToolNamesFlat.value
    }
  } else {
    agentToolNames = allToolNamesFlat.value
  }

  return toolCategories.value
    .map(cat => ({
      ...cat,
      tools: cat.tools.filter(t => agentToolNames.includes(t.name))
    }))
    .filter(cat => cat.tools.length > 0)
})

// ===== 辅助函数 =====

const getConfidenceColor = (conf?: number): string => {
  const c = conf ?? 0.5
  if (c >= 0.8) return '#22c55e'
  if (c >= 0.6) return '#84cc16'
  if (c >= 0.4) return '#f59e0b'
  return '#ef4444'
}

const getConfidenceGradient = (conf?: number): string => {
  const c = conf ?? 0.5
  if (c >= 0.8) return 'linear-gradient(135deg, #22c55e, #16a34a)'
  if (c >= 0.6) return 'linear-gradient(135deg, #84cc16, #65a30d)'
  if (c >= 0.4) return 'linear-gradient(135deg, #f59e0b, #d97706)'
  return 'linear-gradient(135deg, #ef4444, #dc2626)'
}

const getConfidenceClass = (conf?: number): string => {
  const c = conf ?? 0.5
  if (c >= 0.8) return 'conf-high'
  if (c >= 0.6) return 'conf-good'
  if (c >= 0.4) return 'conf-mid'
  return 'conf-low'
}

const confidencePillStyle = (conf?: number) => {
  const color = getConfidenceColor(conf)
  return {
    background: `${color}18`,
    color: color,
    border: `1px solid ${color}30`
  }
}

const confidenceStyle = (conf?: number) => {
  const color = getConfidenceColor(conf)
  return {
    background: `${color}15`,
    color: color,
    borderColor: `${color}40`
  }
}

const getTriggerWordsArr = (json: string): string[] => {
  try {
    const arr = JSON.parse(json)
    return Array.isArray(arr) ? arr : []
  } catch {
    return []
  }
}

const getAgentName = (id?: number): string =>
  agents.value.find(a => a.id === id)?.name || '未知 Agent'

const formatTime = (timestamp: string | number): string => {
  if (!timestamp) return ''
  const d = new Date(timestamp)
  const now = new Date()
  const isToday = d.toDateString() === now.toDateString()
  const time = `${String(d.getHours()).padStart(2, '0')}:${String(d.getMinutes()).padStart(2, '0')}`
  if (isToday) return `今天 ${time}`
  return `${d.getMonth() + 1}/${d.getDate()} ${time}`
}

const clearFilters = () => {
  searchKeyword.value = ''
  filterAgentId.value = undefined
  fetchList()
}

// ===== 原有逻辑（不变） =====

const fetchToolRegistry = async () => {
  loadingTools.value = true
  try {
    const res = await listToolRegistry()
    if (res.code === 200 && res.data?.categories) {
      toolCategories.value = res.data.categories
    }
  } catch (e: any) {
    console.warn('获取工具注册表失败:', e.message)
  } finally {
    loadingTools.value = false
  }
}

const onAgentChange = () => {
  formTools.value = []
}

const toggleTool = (tool: string, checked: boolean) => {
  if (checked) {
    if (!formTools.value.includes(tool)) formTools.value.push(tool)
  } else {
    formTools.value = formTools.value.filter(t => t !== tool)
  }
}

const selectAllTools = () => {
  formTools.value = selectedAgentToolCategories.value.flatMap(c => c.tools.map(t => t.name))
}

const fetchList = async () => {
  loading.value = true
  try {
    const res = await listSkills(userId(), filterAgentId.value)
    if (res.code === 200) {
      const sorted = [...res.data].sort((a: SkillData, b: SkillData) => {
        const confA = a.confidence ?? 0.5
        const confB = b.confidence ?? 0.5
        if (confB !== confA) return confB - confA
        return (a.createdAt || '').localeCompare(b.createdAt || '')
      })
      skills.value = sorted
    }
  } catch (e: any) {
    message.error(e.message || '加载技能列表失败')
  } finally {
    loading.value = false
  }
}

const resetForm = () => {
  form.value = { name: '', description: '', agentConfigId: undefined, instructions: '' }
  formTools.value = []
  triggerInput.value = ''
}

const openCreate = () => {
  isEdit.value = false
  editId.value = null
  resetForm()
  modalVisible.value = true
}

const openEdit = (record: SkillData) => {
  isEdit.value = true
  editId.value = record.id!
  form.value = {
    name: record.name,
    description: record.description,
    agentConfigId: record.agentConfigId,
    instructions: record.instructions
  }
  if (record.toolNames) {
    try {
      formTools.value = JSON.parse(record.toolNames)
    } catch {
      formTools.value = []
    }
  } else {
    formTools.value = []
  }
  if (record.triggerWords) {
    try {
      triggerInput.value = (JSON.parse(record.triggerWords) as string[]).join(', ')
    } catch {
      triggerInput.value = record.triggerWords
    }
  } else {
    triggerInput.value = ''
  }
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
    message.success(isEdit.value ? '技能已更新' : '技能已创建')
  } catch (e: any) {
    message.error(e.message || '操作失败')
  } finally {
    saving.value = false
  }
}

const handleCancel = () => {
  modalVisible.value = false
}

const handleDelete = async (id: number) => {
  try {
    await deleteSkillApi(id, userId())
    message.success('技能已删除')
    await fetchList()
  } catch (e: any) {
    message.error(e.message || '删除失败')
  }
}

onMounted(async () => {
  try {
    const res = await listAgentConfigs()
    if (res.code === 200) agents.value = res.data
  } catch { /* ignore */ }
  await fetchList()
  fetchToolRegistry()
})
</script>

<style scoped>
/* ================================================================
   整体布局
   ================================================================ */
.skill-manage {
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
  width: 260px;
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

.empty-cta {
  margin-top: 8px;
  height: 36px;
  border-radius: 8px;
  font-weight: 500;
}

/* ================================================================
   技能卡片列表
   ================================================================ */
.skill-list {
  flex: 1;
  overflow-y: auto;
  padding: 12px;
  display: flex;
  flex-direction: column;
  gap: 8px;
  min-height: 0;
}

/* TransitionGroup */
.card-list-enter-active,
.card-list-leave-active {
  transition: all 0.3s ease;
}
.card-list-enter-from {
  opacity: 0;
  transform: translateY(-8px);
}
.card-list-leave-to {
  opacity: 0;
  transform: translateX(20px);
}

/* 单张技能卡片 */
.skill-card {
  display: flex;
  background: #fff;
  border: 1px solid #eef2f7;
  border-radius: 10px;
  overflow: hidden;
  transition: all 0.2s ease;
  cursor: default;
}

.skill-card:hover {
  box-shadow: 0 4px 16px rgba(0, 0, 0, 0.06);
  border-color: #dce4f0;
  transform: translateY(-1px);
}

/* 左侧状态条 */
.card-accent {
  width: 4px;
  flex-shrink: 0;
  transition: opacity 0.2s;
}

.card-body {
  flex: 1;
  padding: 14px 18px;
  display: flex;
  flex-direction: column;
  gap: 12px;
  min-width: 0;
}

/* 卡片上半部分 */
.card-top {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
}

.card-info {
  flex: 1;
  min-width: 0;
}

.card-title-row {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
  margin-bottom: 4px;
}

.card-name {
  font-size: 15px;
  font-weight: 600;
  color: #1a202c;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  max-width: 200px;
}

/* 置信度胶囊 */
.confidence-pill {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  font-size: 11px;
  font-weight: 700;
  padding: 2px 8px;
  border-radius: 12px;
  white-space: nowrap;
  font-variant-numeric: tabular-nums;
  flex-shrink: 0;
}

.scope-tag {
  font-size: 11px !important;
  line-height: 20px !important;
  border-radius: 4px !important;
}

.card-desc {
  margin: 0;
  font-size: 13px;
  color: #64748b;
  line-height: 1.5;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
}

/* 操作按钮 */
.card-actions {
  display: flex;
  gap: 6px;
  flex-shrink: 0;
}

.action-btn {
  width: 32px;
  height: 32px;
  border-radius: 8px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  border: 1px solid #e2e8f0;
  background: #fff;
  color: #64748b;
  transition: all 0.15s ease;
}

.action-btn:hover {
  border-color: #1677ff;
  color: #1677ff;
  background: #f0f5ff;
}

.action-delete:hover {
  border-color: #ef4444 !important;
  color: #ef4444 !important;
  background: #fef2f2 !important;
}

/* 卡片下半部分 */
.card-bottom {
  display: flex;
  flex-direction: column;
  gap: 10px;
}

/* 置信度进度条 */
.confidence-track {
  width: 100%;
  height: 6px;
  background: #f1f5f9;
  border-radius: 3px;
  overflow: hidden;
}

.confidence-fill {
  height: 100%;
  border-radius: 3px;
  transition: width 0.5s ease;
  display: flex;
  align-items: center;
  justify-content: flex-end;
  min-width: 0;
}

.confidence-label {
  font-size: 9px;
  color: #fff;
  font-weight: 700;
  padding-right: 4px;
  white-space: nowrap;
  text-shadow: 0 1px 2px rgba(0, 0, 0, 0.2);
}

/* 卡片 meta 信息 */
.card-meta {
  display: flex;
  align-items: center;
  gap: 16px;
  flex-wrap: wrap;
}

.meta-item {
  display: flex;
  align-items: center;
  gap: 5px;
  font-size: 12px;
  color: #94a3b8;
}

.meta-dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  flex-shrink: 0;
}

.meta-triggers {
  flex: 1;
  min-width: 0;
  overflow: hidden;
}

.trigger-chips {
  display: flex;
  gap: 4px;
  flex-wrap: wrap;
  overflow: hidden;
}

.trigger-chip {
  display: inline-flex;
  align-items: center;
  padding: 1px 7px;
  background: #f1f5f9;
  border: 1px solid #e2e8f0;
  border-radius: 10px;
  font-size: 11px;
  color: #475569;
  white-space: nowrap;
  font-family: 'SF Mono', 'Monaco', 'Consolas', monospace;
}

.trigger-more {
  background: #fef3c7;
  border-color: #fcd34d;
  color: #92400e;
  font-weight: 600;
}

.meta-time {
  flex-shrink: 0;
}

/* ================================================================
   弹窗样式
   ================================================================ */
.skill-modal-wrap :deep(.ant-modal-content) {
  border-radius: 14px;
  overflow: hidden;
  box-shadow: 0 20px 60px rgba(0, 0, 0, 0.12);
}

.skill-modal-wrap :deep(.ant-modal-header) {
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
  max-height: 60vh;
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

.form-hint {
  color: #94a3b8;
  font-size: 13px;
  padding: 12px;
  background: #fafbfc;
  border-radius: 8px;
  border: 1px dashed #e2e8f0;
  text-align: center;
}

.form-hint-inline {
  font-size: 12px;
  color: #94a3b8;
  margin-top: 5px;
}

/* 工具面板 */
.tools-panel {
  border: 1px solid #eef2f7;
  border-radius: 10px;
  overflow: hidden;
  background: #fafbfc;
}

.tools-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 8px 14px;
  background: #f8fafc;
  border-bottom: 1px solid #eef2f7;
}

.tools-count {
  font-size: 12px;
  font-weight: 600;
  color: #475569;
}

.tools-header-actions {
  display: flex;
  align-items: center;
  gap: 2px;
}

.tools-divider {
  color: #e2e8f0;
  font-size: 12px;
}

.tools-collapse {
  max-height: 300px;
  overflow-y: auto;
}

.tools-collapse :deep(.ant-collapse-header) {
  padding: 8px 14px !important;
  font-size: 13px;
  font-weight: 600;
  align-items: center !important;
}

.tools-collapse :deep(.ant-collapse-content-box) {
  padding: 4px 8px 10px 14px !important;
}

.collapse-cat-label {
  flex: 1;
}

.collapse-cat-count {
  font-size: 11px;
  color: #94a3b8;
  background: #f1f5f9;
  padding: 1px 7px;
  border-radius: 8px;
  font-weight: 500;
  margin-right: 8px;
}

.tool-items {
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.tool-item {
  display: flex;
  align-items: center;
  padding: 4px 6px;
  border-radius: 6px;
  gap: 8px;
  transition: background 0.12s;
}

.tool-item:hover {
  background: #f0f5ff;
}

.tool-item--checked {
  background: #f0f5ff;
}

.tool-name {
  font-family: 'SF Mono', 'Monaco', 'Consolas', monospace;
  font-size: 12px;
  color: #1a202c;
  font-weight: 500;
}

.tool-desc {
  font-size: 12px;
  color: #94a3b8;
  flex: 1;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.tool-risk {
  font-size: 13px;
  cursor: help;
  flex-shrink: 0;
}

/* ================================================================
   滚动条
   ================================================================ */
.skill-list::-webkit-scrollbar,
.modal-body::-webkit-scrollbar,
.tools-collapse::-webkit-scrollbar {
  width: 5px;
}

.skill-list::-webkit-scrollbar-thumb,
.modal-body::-webkit-scrollbar-thumb,
.tools-collapse::-webkit-scrollbar-thumb {
  background: #dce4f0;
  border-radius: 3px;
}

.skill-list::-webkit-scrollbar-thumb:hover,
.modal-body::-webkit-scrollbar-thumb:hover,
.tools-collapse::-webkit-scrollbar-thumb:hover {
  background: #c0c8d4;
}

/* ================================================================
   暗色模式
   ================================================================ */

/* 页面背景 */
[data-theme="dark"] .skill-manage {
  background: #121418;
}

/* 头部卡片 */
[data-theme="dark"] .page-header {
  background: #1a1d22;
  border-color: #2a2d33;
  box-shadow: 0 1px 3px rgba(0, 0, 0, 0.2);
}

[data-theme="dark"] .header-icon-box {
  background: linear-gradient(135deg, #1e2130, #252840);
}

[data-theme="dark"] .header-title {
  color: #e4e6ea;
}

[data-theme="dark"] .header-subtitle {
  color: #8b8f98;
}

[data-theme="dark"] .stat-num {
  color: #e4e6ea;
}

[data-theme="dark"] .stat-label {
  color: #8b8f98;
}

[data-theme="dark"] .stat-divider {
  background: #2a2d33;
}

/* 工具栏 */
[data-theme="dark"] .toolbar-card {
  background: #1a1d22;
  border-color: #2a2d33;
  box-shadow: 0 1px 3px rgba(0, 0, 0, 0.2);
}

[data-theme="dark"] .search-icon {
  color: #8b8f98;
}

[data-theme="dark"] .search-input {
  border-color: #2a2d33;
  color: #e4e6ea;
  background: #141619;
}

[data-theme="dark"] .search-input::placeholder {
  color: #8b8f98;
}

[data-theme="dark"] .search-input:focus {
  border-color: #1677ff;
  background: #1a1d22;
  box-shadow: 0 0 0 3px rgba(22, 119, 255, 0.15);
}

[data-theme="dark"] .search-clear {
  color: #8b8f98;
}

[data-theme="dark"] .search-clear:hover {
  color: #e4e6ea;
}

/* 内容卡片 */
[data-theme="dark"] .content-card {
  background: #1a1d22;
  border-color: #2a2d33;
  box-shadow: 0 1px 3px rgba(0, 0, 0, 0.2);
}

/* 状态占位 */
[data-theme="dark"] .state-text {
  color: #8b8f98;
}

[data-theme="dark"] .empty-title {
  color: #e4e6ea;
}

[data-theme="dark"] .empty-desc {
  color: #8b8f98;
}

/* 技能卡片 */
[data-theme="dark"] .skill-card {
  background: #1a1d22;
  border-color: #2a2d33;
}

[data-theme="dark"] .skill-card:hover {
  border-color: #3a3d44;
  box-shadow: 0 4px 16px rgba(0, 0, 0, 0.25);
}

[data-theme="dark"] .card-name {
  color: #e4e6ea;
}

[data-theme="dark"] .card-desc {
  color: #8b8f98;
}

/* 操作按钮 */
[data-theme="dark"] .action-btn {
  border-color: #2a2d33;
  background: #1a1d22;
  color: #8b8f98;
}

[data-theme="dark"] .action-btn:hover {
  border-color: #1677ff;
  color: #1677ff;
  background: rgba(22, 119, 255, 0.1);
}

[data-theme="dark"] .action-delete:hover {
  background: rgba(239, 68, 68, 0.1) !important;
}

/* 置信度进度条 */
[data-theme="dark"] .confidence-track {
  background: #1e2126;
}

/* Meta 信息 */
[data-theme="dark"] .meta-item {
  color: #8b8f98;
}

[data-theme="dark"] .trigger-chip {
  background: #1e2126;
  border-color: #2a2d33;
  color: #8b8f98;
}

[data-theme="dark"] .trigger-more {
  background: rgba(251, 191, 36, 0.12);
  border-color: rgba(251, 191, 36, 0.3);
  color: #fbbf24;
}

/* 弹窗样式 */
[data-theme="dark"] .skill-modal-wrap :deep(.ant-modal-content) {
  background: #1a1d22;
  box-shadow: 0 20px 60px rgba(0, 0, 0, 0.4);
}

[data-theme="dark"] .modal-header {
  border-bottom-color: #2a2d33;
  background: linear-gradient(180deg, #1e2126 0%, #1a1d22 100%);
}

[data-theme="dark"] .modal-header-icon {
  background: linear-gradient(135deg, #1e2130, #252840);
}

[data-theme="dark"] .modal-header-text h3 {
  color: #e4e6ea;
}

[data-theme="dark"] .modal-header-text p {
  color: #8b8f98;
}

/* 表单 */
[data-theme="dark"] .form-section-title {
  color: #8b8f98;
  border-bottom-color: #2a2d33;
}

[data-theme="dark"] .form-hint {
  color: #8b8f98;
  background: #141619;
  border-color: #2a2d33;
}

[data-theme="dark"] .form-hint-inline {
  color: #8b8f98;
}

/* 工具面板 */
[data-theme="dark"] .tools-panel {
  border-color: #2a2d33;
  background: #141619;
}

[data-theme="dark"] .tools-header {
  background: #1e2126;
  border-bottom-color: #2a2d33;
}

[data-theme="dark"] .tools-count {
  color: #e4e6ea;
}

[data-theme="dark"] .tools-divider {
  color: #2a2d33;
}

[data-theme="dark"] .collapse-cat-count {
  color: #8b8f98;
  background: #1e2126;
}

/* 工具列表项 */
[data-theme="dark"] .tool-item:hover {
  background: rgba(22, 119, 255, 0.08);
}

[data-theme="dark"] .tool-item--checked {
  background: rgba(22, 119, 255, 0.08);
}

[data-theme="dark"] .tool-name {
  color: #e4e6ea;
}

[data-theme="dark"] .tool-desc {
  color: #8b8f98;
}

/* Ant Design 组件穿透 */
[data-theme="dark"] :deep(.ant-input) {
  background: #141619;
  border-color: #2a2d33;
  color: #e4e6ea;
}

[data-theme="dark"] :deep(.ant-input:hover) {
  border-color: #3a3d44;
}

[data-theme="dark"] :deep(.ant-input:focus) {
  border-color: #1677ff;
}

[data-theme="dark"] :deep(.ant-select-selector) {
  background: #141619 !important;
  border-color: #2a2d33 !important;
  color: #e4e6ea !important;
}

[data-theme="dark"] :deep(.ant-select-arrow) {
  color: #8b8f98;
}

[data-theme="dark"] :deep(.ant-empty-description) {
  color: #8b8f98;
}

[data-theme="dark"] :deep(.ant-tag) {
  border-color: #2a2d33;
  background: #1e2126;
  color: #8b8f98;
}

[data-theme="dark"] :deep(.ant-modal-content) {
  background: #1a1d22;
}

[data-theme="dark"] :deep(.ant-collapse-header) {
  color: #e4e6ea;
}

[data-theme="dark"] :deep(.ant-collapse-content-box) {
  background: #141619;
}

/* 滚动条 */
[data-theme="dark"] .skill-list::-webkit-scrollbar-thumb,
[data-theme="dark"] .modal-body::-webkit-scrollbar-thumb,
[data-theme="dark"] .tools-collapse::-webkit-scrollbar-thumb {
  background: #2a2d33;
}

[data-theme="dark"] .skill-list::-webkit-scrollbar-thumb:hover,
[data-theme="dark"] .modal-body::-webkit-scrollbar-thumb:hover,
[data-theme="dark"] .tools-collapse::-webkit-scrollbar-thumb:hover {
  background: #3a3d44;
}
</style>
