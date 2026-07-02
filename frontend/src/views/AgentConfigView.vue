<template>
  <div class="agent-config-root">
    <!-- ============ 页面头部 ============ -->
    <header class="page-header">
      <div class="header-left">
        <div class="header-icon">
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
            <circle cx="12" cy="8" r="4"/>
            <path d="M5 20c0-4 4-7 8-7s8 3 8 7"/>
            <line x1="12" y1="3" x2="12" y2="5"/>
          </svg>
        </div>
        <div class="header-text">
          <h2 class="page-title">Agent 管理</h2>
          <span class="page-subtitle">{{ agentList.length }} 个 Agent</span>
        </div>
      </div>
      <a-button type="primary" size="large" class="btn-create" @click="openCreateModal">
        <PlusOutlined /> 新建 Agent
      </a-button>
    </header>

    <!-- ============ 内容区域 ============ -->
    <div class="agent-content">
      <!-- 加载状态 -->
      <div v-if="loading" class="state-wrapper">
        <div class="state-card">
          <a-spin size="large" />
          <span class="state-text">加载中...</span>
        </div>
      </div>

      <!-- 空状态 -->
      <div v-else-if="agentList.length === 0" class="state-wrapper">
        <div class="state-card empty-card">
          <div class="empty-illustration">
            <div class="empty-orb orb-1"></div>
            <div class="empty-orb orb-2"></div>
            <div class="empty-icon">🤖</div>
          </div>
          <h3 class="empty-title">还没有 Agent</h3>
          <p class="empty-desc">创建你的第一个 AI Agent，让它帮你完成各种任务</p>
          <a-button type="primary" size="large" class="btn-create" @click="openCreateModal">
            <PlusOutlined /> 新建 Agent
          </a-button>
        </div>
      </div>

      <!-- Agent 卡片列表 -->
      <div v-else class="agent-grid">
        <div
          v-for="agent in agentList"
          :key="agent.id"
          :class="['agent-card', {
            'is-default': agent.isDefault,
            'is-builtin': agent.isBuiltin
          }]"
        >
          <!-- 卡片顶部：头像 + 信息 -->
          <div class="card-top">
            <div
              class="card-avatar"
              :class="{ 'avatar-default': agent.isDefault }"
              :style="{ background: avatarGradient(agent.id, agent.name) }"
            >
              <span class="avatar-emoji">{{ agent.avatar || '🤖' }}</span>
            </div>
            <div class="card-info">
              <div class="card-name-row">
                <span class="card-name">{{ agent.name }}</span>
                <span v-if="agent.isDefault" class="badge-default">默认</span>
                <span v-if="agent.isBuiltin" class="badge-builtin">内置</span>
              </div>
              <span class="card-desc">{{ agent.description || '暂无描述' }}</span>
            </div>
          </div>

          <!-- 工具标签区 -->
          <div class="card-tools" v-if="agent.tools && agent.tools.length > 0">
            <div class="tools-scroll">
              <span v-for="tool in agent.tools.slice(0, 8)" :key="tool" class="tool-chip">
                {{ tool }}
              </span>
              <span v-if="agent.tools.length > 8" class="tool-chip tool-more">
                +{{ agent.tools.length - 8 }}
              </span>
            </div>
          </div>

          <!-- 卡片底部操作 -->
          <div class="card-footer">
            <div class="card-meta">
              <span class="meta-item" v-if="agent.workDir" title="工作目录">
                <FolderOpenOutlined /> {{ agent.workDir }}
              </span>
              <span class="meta-item" v-if="agent.tools">
                <ToolOutlined /> {{ agent.tools.length }} 个工具
              </span>
            </div>
            <div class="card-actions">
              <a-button
                v-if="!agent.isDefault"
                size="small"
                type="text"
                class="btn-action btn-star"
                @click="handleSetDefault(agent)"
                :loading="settingDefaultId === agent.id"
                title="设为默认"
              >
                <StarOutlined />
              </a-button>
               <a-button
                size="small"
                type="text"
                class="btn-action btn-edit"
                @click="openEditModal(agent)"
                title="编辑"
              >
                <EditOutlined />
              </a-button>
              <a-popconfirm
                v-if="!agent.isBuiltin"
                title="确定要删除这个 Agent 吗？"
                ok-text="删除"
                ok-type="danger"
                cancel-text="取消"
                @confirm="handleDelete(agent.id)"
              >
                <a-button
                  size="small"
                  type="text"
                  danger
                  class="btn-action btn-delete"
                  title="删除"
                >
                  <DeleteOutlined />
                </a-button>
              </a-popconfirm>
            </div>
          </div>
        </div>
      </div>
    </div>

    <!-- ============ 新建/编辑弹窗（Teleport 到 body，参照 P2P 弹窗设计） ============ -->
    <Teleport to="body">
      <Transition name="modal-fade">
        <div v-if="modalVisible" class="modal-overlay" @click.self="handleCancel">
          <div class="modal-container">
            <!-- 弹窗头部 -->
            <div class="modal-header">
              <div class="modal-header-icon">
                {{ isEditing ? '✏️' : '✨' }}
              </div>
              <div class="modal-header-text">
                <h3 class="modal-title">{{ isEditing ? '编辑 Agent' : '新建 Agent' }}</h3>
                <span class="modal-subtitle">{{ isEditing ? '修改 Agent 配置信息' : '创建一个新的 AI Agent' }}</span>
              </div>
              <button class="modal-close" @click="handleCancel" title="关闭">✕</button>
            </div>

            <!-- 弹窗主体 -->
            <div class="modal-body">
                <a-form
                  ref="formRef"
                  :model="formData"
                  :label-col="{ span: 6 }"
                  :wrapper-col="{ span: 18 }"
                  class="agent-form"
                >
                <!-- 名称 -->
                <a-form-item label="名称" required>
                  <a-input
                    v-model:value="formData.name"
                    placeholder="输入 Agent 名称"
                    :maxLength="50"
                    size="large"
                    class="form-input"
                  />
                </a-form-item>

                <!-- 描述 -->
                <a-form-item label="描述">
                  <a-input
                    v-model:value="formData.description"
                    placeholder="简要描述 Agent 的功能和用途"
                    :maxLength="200"
                    class="form-input"
                  />
                </a-form-item>

                <!-- 头像选择 -->
                <a-form-item label="头像">
                  <div class="avatar-picker">
                    <div class="avatar-current" @click="showEmojiPicker = !showEmojiPicker">
                      <span class="avatar-preview">{{ formData.avatar || '🤖' }}</span>
                      <span class="avatar-hint">点击选择头像</span>
                      <span class="avatar-arrow" :class="{ open: showEmojiPicker }">▾</span>
                    </div>
                    <Transition name="picker-drop">
                      <div v-if="showEmojiPicker" class="emoji-grid">
                        <span
                          v-for="emoji in emojiList"
                          :key="emoji"
                          :class="['emoji-item', { selected: formData.avatar === emoji }]"
                          @click="formData.avatar = emoji; showEmojiPicker = false"
                        >{{ emoji }}</span>
                      </div>
                    </Transition>
                  </div>
                </a-form-item>

                <!-- 系统提示词 -->
                <a-form-item label="系统提示词">
                  <a-textarea
                    v-model:value="formData.systemPrompt"
                    :placeholder="isUsingDefaultPrompt ? '（使用默认系统提示词，编辑后保存将覆盖默认值）' : '输入系统提示词，定义 Agent 的角色和行为...'"
                    :rows="4"
                    :maxLength="5000"
                    show-count
                    class="form-textarea"
                  />
                  <div class="form-hint" style="margin-top: 4px;">留空则使用后台内置的默认系统提示词</div>
                </a-form-item>

                <!-- 工具选择 -->
                <a-form-item label="工具选择">
                  <div v-if="isToolNamesNull" class="form-hint" style="margin-bottom: 8px;">当前使用默认工具集，选择工具后将覆盖默认值</div>
                  <div v-if="loadingTools" class="form-hint">加载工具列表中...</div>
                  <div v-else class="tools-panel">
                    <a-collapse
                      v-model:activeKey="activeCategoryKeys"
                      :bordered="false"
                      ghost
                      class="tools-collapse"
                    >
                      <a-collapse-panel
                        v-for="cat in toolCategories"
                        :key="cat.code"
                        :header="cat.label"
                      >
                        <template #extra>
                          <span class="cat-count">{{ cat.tools.length }}</span>
                        </template>
                        <div class="tools-group">
                          <div
                            v-for="tool in cat.tools"
                            :key="tool.name"
                            :class="['tool-item', { 'tool-selected': formData.tools?.includes(tool.name) }]"
                            @click="toggleTool(tool.name, !formData.tools?.includes(tool.name))"
                          >
                            <a-checkbox
                              :checked="formData.tools?.includes(tool.name)"
                              @change="(e: any) => toggleTool(tool.name, e.target.checked)"
                              class="tool-checkbox"
                              @click.stop
                            />
                            <div class="tool-info">
                              <span class="tool-name">{{ tool.name }}</span>
                              <span class="tool-desc">{{ tool.description }}</span>
                            </div>
                            <span v-if="tool.risk === 'high'" class="tool-risk" title="高危操作">⚠️</span>
                          </div>
                        </div>
                      </a-collapse-panel>
                    </a-collapse>
                    <div class="tools-footer">
                      <span class="tools-selected-count">已选 {{ formData.tools?.length || 0 }} 个工具</span>
                      <div class="tools-footer-actions">
                        <a-button size="small" type="link" @click="selectAllTools">全选</a-button>
                        <a-button size="small" type="link" @click="formData.tools = []">清空</a-button>
                      </div>
                    </div>
                  </div>
                </a-form-item>

                <!-- 采样温度 -->
                <a-form-item label="采样温度">
                  <div class="temperature-wrapper">
                    <a-slider
                      v-model:value="formData.temperature"
                      :min="0"
                      :max="2"
                      :step="0.1"
                      :marks="{ 0: '0', 0.5: '0.5', 1: '1', 1.5: '1.5', 2: '2' }"
                      class="temperature-slider"
                    />
                    <span class="temperature-value">{{ formData.temperature ?? 0.3 }}</span>
                  </div>
                  <div class="form-hint" style="margin-top: 2px;">控制输出随机性：0=确定，2=最大随机，默认 0.3</div>
                </a-form-item>

                <!-- 工作目录 -->
                <a-form-item label="工作目录">
                  <a-input
                    v-model:value="formData.workDir"
                    placeholder="输入工作目录路径（可选）"
                    class="form-input"
                  >
                    <template #suffix>
                      <FolderOpenOutlined class="input-suffix-icon" @click="browseWorkDir" />
                    </template>
                  </a-input>
                </a-form-item>
              </a-form>

              <!-- ★ 角色性格配置（独立 toggle，不与工具分类 collapse 共享 activeKey） -->
              <div class="char-section">
                <button type="button" class="char-toggle" @click="showCharForm = !showCharForm">
                  <span class="char-toggle-icon">{{ showCharForm ? '▼' : '▶' }}</span>
                  <span>🎭 角色性格（可选）</span>
                </button>
                <div v-show="showCharForm" class="char-body">
                  <div class="char-form-grid">
                    <div class="char-field">
                      <label class="char-label">姓名</label>
                      <a-input v-model:value="formData.charName" placeholder="如：小柔" />
                    </div>
                    <div class="char-field">
                      <label class="char-label">物种</label>
                      <a-select v-model:value="formData.charSpecies" placeholder="请选择" :getPopupContainer="trigger => trigger.parentElement">
                        <a-select-option value="人">👤 人类</a-select-option>
                        <a-select-option value="猫娘">🐱 猫娘</a-select-option>
                        <a-select-option value="狗娘">🐶 狗娘</a-select-option>
                        <a-select-option value="精灵">🧝 精灵</a-select-option>
                        <a-select-option value="机器人">🤖 机器人</a-select-option>
                        <a-select-option value="自定义">✨ 自定义</a-select-option>
                      </a-select>
                    </div>
                    <div class="char-field">
                      <label class="char-label">性别</label>
                      <a-select v-model:value="formData.charGender" placeholder="请选择" :getPopupContainer="trigger => trigger.parentElement">
                        <a-select-option value="男">♂️ 男</a-select-option>
                        <a-select-option value="女">♀️ 女</a-select-option>
                        <a-select-option value="无性">⚪ 无性</a-select-option>
                        <a-select-option value="其他">🌈 其他</a-select-option>
                      </a-select>
                    </div>
                    <div class="char-field">
                      <label class="char-label">年龄</label>
                      <a-input-number v-model:value="formData.charAge" :min="1" :max="9999" placeholder="年龄" style="width:100%" />
                    </div>
                    <div class="char-field char-field-wide">
                      <label class="char-label">性格</label>
                      <a-textarea v-model:value="formData.charPersonality" placeholder="如：温柔体贴，善解人意" :rows="2" :maxLength="500" />
                    </div>
                    <div class="char-field">
                      <label class="char-label">称呼</label>
                      <a-input v-model:value="formData.charGreeting" placeholder="用户对你的称呼，如：主人、哥哥" />
                    </div>
                    <div class="char-field char-field-wide">
                      <label class="char-label">背景</label>
                      <a-textarea v-model:value="formData.charBackground" placeholder="如：来自未来世界的 AI 助手..." :rows="3" :maxLength="1000" />
                    </div>
                    <div class="char-field">
                      <label class="char-label">喜好</label>
                      <a-input v-model:value="formData.charLikes" placeholder="如：看书、听音乐、帮助人类" />
                    </div>
                    <div class="char-field char-field-wide">
                      <label class="char-label">风格</label>
                      <a-textarea v-model:value="formData.charStyle" placeholder="如：说话温柔，喜欢用表情符号" :rows="2" :maxLength="500" />
                    </div>
                  </div>
                </div>
              </div>
            </div>

            <!-- 弹窗底部 -->
            <div class="modal-footer">
              <a-button size="large" @click="handleCancel" class="btn-cancel">取消</a-button>
              <a-button
                type="primary"
                size="large"
                :loading="modalSaving"
                @click="handleSave"
                class="btn-save"
              >
                {{ isEditing ? '保存修改' : '创建 Agent' }}
              </a-button>
            </div>
          </div>
        </div>
      </Transition>
    </Teleport>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, onMounted } from 'vue'
import { message } from 'ant-design-vue'
import {
  PlusOutlined,
  EditOutlined,
  DeleteOutlined,
  FolderOpenOutlined,
  StarOutlined,
  ToolOutlined,
} from '@ant-design/icons-vue'
import {
  listAgentConfigs,
  createAgentConfig,
  updateAgentConfig,
  deleteAgentConfig,
  setDefaultAgentConfig,
  type AgentConfig
} from '@/api/agent-config'
import { listToolRegistry, type ToolCategory } from '@/api/tools'

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
const toolCategories = ref<ToolCategory[]>([])
const loadingTools = ref(false)
const allToolNames = ref<string[]>([])
const activeCategoryKeys = ref<string[]>([])
const showCharForm = ref(false)
// ★ 标记原始 systemPrompt 是否为 null（表示使用内置默认提示词）
const isUsingDefaultPrompt = ref(false)
// ★ 标记原始 toolNames 是否为 null（表示使用内置默认工具集）
const isToolNamesNull = ref(false)

// 常用 emoji 列表
const emojiList = [
  '🤖', '🐱', '🐶', '🦊', '🐰', '🐻', '🐼', '🐨',
  '🦄', '🐙', '🦀', '🐳', '🦋', '🌻', '🌟', '🔥',
  '💡', '🎯', '🚀', '⚡', '🎨', '🔧', '📚', '💻',
  '🧠', '👾', '🗿', '🤠', '😎', '🧙', '🦸', '🎭'
]

// ===== 渐变色头像（对齐 P2P 设计） =====
const avatarGradients = [
  'linear-gradient(135deg, #8b5cf6, #7c3aed)',
  'linear-gradient(135deg, #f59e0b, #f97316)',
  'linear-gradient(135deg, #10b981, #059669)',
  'linear-gradient(135deg, #3b82f6, #2563eb)',
  'linear-gradient(135deg, #ec4899, #db2777)',
  'linear-gradient(135deg, #06b6d4, #0891b2)',
  'linear-gradient(135deg, #84cc16, #65a30d)',
  'linear-gradient(135deg, #f43f5e, #e11d48)',
]
function avatarGradient(id?: number, name?: string): string {
  const str = String(id ?? 0) + (name ?? '')
  let hash = 0
  for (let i = 0; i < str.length; i++) {
    hash = ((hash << 5) - hash) + str.charCodeAt(i)
    hash |= 0
  }
  return avatarGradients[Math.abs(hash) % avatarGradients.length]
}

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
  temperature: 0.3,
  workDir: '',
  // 角色性格字段
  charName: '', charSpecies: '', charGender: '', charAge: undefined as number | undefined,
  charPersonality: '', charGreeting: '', charBackground: '', charLikes: '', charStyle: '',
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

// ===== 工具注册表加载 =====
const fetchToolRegistry = async () => {
  loadingTools.value = true
  try {
    const res = await listToolRegistry()
    if (res.code === 200 && res.data?.categories) {
      toolCategories.value = res.data.categories
      allToolNames.value = res.data.categories.flatMap(c => c.tools.map(t => t.name))
      activeCategoryKeys.value = res.data.categories.map(c => c.code)
    }
  } catch (e: any) {
    console.warn('获取工具注册表失败:', e.message)
  } finally {
    loadingTools.value = false
  }
}

// ===== 弹窗操作 =====
const openCreateModal = () => {
  isEditing.value = false
  editingId.value = null
  // ★ 用 getDefaultForm 覆盖所有字段（不 delete reactive key，避免破坏响应式）
  const defaults = getDefaultForm()
  for (const key of Object.keys(defaults)) {
    (formData as any)[key] = (defaults as any)[key]
  }
  // 清理角色性格中可能在编辑时残留的额外字段
  const charKeys = ['charName','charSpecies','charGender','charAge','charPersonality','charGreeting','charBackground','charLikes','charStyle']
  for (const k of charKeys) { (formData as any)[k] = (defaults as any)[k] || '' }
  showEmojiPicker.value = false
  showCharForm.value = false
  modalVisible.value = true
}

const openEditModal = (agent: AgentConfig) => {
  isEditing.value = true
  editingId.value = agent.id
  // ★ 记录原始 toolNames 是否为 null（表示使用内置默认工具集）
  isToolNamesNull.value = !agent.toolNames
  let toolsArray: string[] = []
  if (agent.toolNames) {
    try { toolsArray = JSON.parse(agent.toolNames) } catch { toolsArray = [] }
  }
  oldToolNames.value = [...toolsArray]
  // ★ 记录原始 systemPrompt 是否为 null（表示使用内置默认提示词）
  isUsingDefaultPrompt.value = !agent.systemPrompt
  Object.assign(formData, {
    name: agent.name,
    description: agent.description || '',
    avatar: agent.avatar || '🤖',
    systemPrompt: agent.systemPrompt || '',
    tools: toolsArray,
    temperature: agent.temperature ?? 0.3,
    workDir: agent.workDir || '',
  })
  // ★ 解析角色性格配置 JSON
  if (agent.characterProfile) {
    try {
      const cp = JSON.parse(agent.characterProfile)
      // ★ 修复："{}" 表示无性格，不展开面板也不覆盖表单
      if (Object.keys(cp).length > 0) {
        Object.assign(formData, {
          charName: cp.name || '', charSpecies: cp.species || '', charGender: cp.gender || '',
          charAge: cp.age || undefined, charPersonality: cp.personality || '',
          charGreeting: cp.greeting || '', charBackground: cp.background || '',
          charLikes: cp.likes || '', charStyle: cp.style || '',
        })
        showCharForm.value = true
      } else {
        showCharForm.value = false
      }
    } catch { showCharForm.value = false }
  } else {
    showCharForm.value = false
  }
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
    // ★ 修复：systemPrompt 为空时发送 null（保持数据库 NULL，使用内置默认提示词）
    //   不要发空字符串，否则后端会覆盖数据库中的 NULL
    const systemPromptVal = formData.systemPrompt?.trim()
    // ★ 修复：tools 为空且原始值为 null 时发送 null（保持数据库 NULL，使用内置默认工具集）
    //   不要发 "[]"，否则后端会覆盖数据库中的 NULL，导致 LLM 无工具可用
    const toolsVal = (formData.tools && formData.tools.length > 0) ? formData.tools : null

    const payload: Partial<AgentConfig> = {
      name: formData.name.trim(),
      description: formData.description?.trim() || '',
      avatar: formData.avatar || '🤖',
      systemPrompt: systemPromptVal || null,
      toolNames: toolsVal ? JSON.stringify(toolsVal) : null,
      temperature: formData.temperature ?? 0.3,
      workDir: formData.workDir || '',
    }
    // ★ 角色性格 → JSON 字符串
    const cp: Record<string, any> = {}
    if (formData.charName) cp.name = formData.charName
    if (formData.charSpecies) cp.species = formData.charSpecies
    if (formData.charGender) cp.gender = formData.charGender
    if (formData.charAge) cp.age = formData.charAge
    if (formData.charPersonality) cp.personality = formData.charPersonality
    if (formData.charGreeting) cp.greeting = formData.charGreeting
    if (formData.charBackground) cp.background = formData.charBackground
    if (formData.charLikes) cp.likes = formData.charLikes
    if (formData.charStyle) cp.style = formData.charStyle
    // ★ 修复：清空性格时发送 "{}"（运行时视为无性格），不再用 undefined 导致后端无法区分清除行为
    payload.characterProfile = Object.keys(cp).length > 0 ? JSON.stringify(cp) : "{}"

    if (isEditing.value && editingId.value) {
      await updateAgentConfig(editingId.value, payload)
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
  formData.tools = [...allToolNames.value]
}

// ===== 浏览工作目录 =====
const browseWorkDir = () => {
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
  fetchToolRegistry()
})
</script>

<style scoped>
/* ============================================================
   Agent Config View v3 — Bento卡片 · 暗色增强 · 微交互
   设计语言对齐 Layout v2 / P2P Panel v3
   ============================================================ */

/* ---------- CSS 变量 ---------- */
.agent-config-root {
  --accent: #8b5cf6;
  --accent-lt: rgba(139,92,246,0.06);
  --accent-md: rgba(139,92,246,0.15);
  --accent-dk: #7c3aed;
  --accent-glow: rgba(139,92,246,0.25);

  /* 默认色系 */
  --default: #3b82f6;
  --default-lt: rgba(59,130,246,0.06);
  --default-md: rgba(59,130,246,0.15);
  --default-glow: rgba(59,130,246,0.2);

  --bg-root: #f5f3fa;
  --bg-card: #ffffff;
  --bg-hover: rgba(139,92,246,0.03);
  --text-1: #1a1a2e;
  --text-2: #5c5c78;
  --text-3: #9696aa;
  --text-4: #b8b8c8;
  --border: #e8e5f0;
  --border-lt: #f0edf6;
  --shadow-sm: 0 1px 3px rgba(0,0,0,0.04);
  --shadow-md: 0 4px 12px rgba(0,0,0,0.06);
  --shadow-lg: 0 8px 30px rgba(0,0,0,0.08);
  --green: #10b981;
  --red: #ef4444;
  --radius: 14px;
  --radius-sm: 10px;
  --radius-xs: 6px;

  height: 100%;
  display: flex;
  flex-direction: column;
  background: var(--bg-root);
  font-size: 14px;
  color: var(--text-1);
  font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', 'PingFang SC', 'Microsoft YaHei', sans-serif;
  overflow-y: auto;
}

/* ============ 页面头部 ============ */
.page-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  padding: 20px 28px;
  margin: 20px 24px 0;
  background: var(--bg-card);
  border-radius: var(--radius);
  border: 1px solid var(--border);
  box-shadow: var(--shadow-sm);
  flex-shrink: 0;
  backdrop-filter: blur(10px);
}
.header-left {
  display: flex;
  align-items: center;
  gap: 14px;
}
.header-icon {
  width: 44px;
  height: 44px;
  border-radius: 12px;
  background: linear-gradient(135deg, var(--accent), var(--accent-dk));
  display: flex;
  align-items: center;
  justify-content: center;
  color: #fff;
  box-shadow: 0 3px 12px var(--accent-glow);
  flex-shrink: 0;
}
.header-icon svg {
  width: 22px;
  height: 22px;
}
.header-text {
  display: flex;
  flex-direction: column;
  gap: 2px;
}
.page-title {
  margin: 0;
  font-size: 22px;
  font-weight: 800;
  color: var(--text-1);
  letter-spacing: -0.3px;
  line-height: 1.2;
}
.page-subtitle {
  font-size: 13px;
  color: var(--text-3);
  font-weight: 500;
}
.btn-create {
  border-radius: var(--radius-sm) !important;
  font-weight: 700 !important;
  font-size: 14px !important;
  padding: 0 24px !important;
  height: 42px !important;
  box-shadow: 0 2px 8px var(--accent-glow);
  transition: all 0.25s ease;
}
.btn-create:hover {
  transform: translateY(-1px);
  box-shadow: 0 6px 20px var(--accent-glow);
}

/* ============ 内容区域 ============ */
.agent-content {
  flex: 1;
  padding: 20px 24px;
  min-height: 0;
  overflow-y: auto;
}
.agent-content::-webkit-scrollbar { width: 4px; }
.agent-content::-webkit-scrollbar-thumb { background: #dcd8ea; border-radius: 2px; }

/* ============ 状态卡片 ============ */
.state-wrapper {
  display: flex;
  align-items: center;
  justify-content: center;
  min-height: 400px;
}
.state-card {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 12px;
  padding: 48px;
  background: var(--bg-card);
  border-radius: var(--radius);
  border: 1px solid var(--border);
  box-shadow: var(--shadow-sm);
}
.state-text {
  font-size: 14px;
  color: var(--text-3);
}

/* 空状态 */
.empty-card {
  padding: 60px 80px;
  gap: 16px;
}
.empty-illustration {
  position: relative;
  width: 100px;
  height: 100px;
  display: flex;
  align-items: center;
  justify-content: center;
  margin-bottom: 8px;
}
.empty-orb {
  position: absolute;
  border-radius: 50%;
  animation: orbFloat 3s ease-in-out infinite;
}
.empty-orb.orb-1 {
  width: 90px; height: 90px;
  background: radial-gradient(circle, var(--accent), transparent);
  opacity: 0.15;
  animation-delay: 0s;
}
.empty-orb.orb-2 {
  width: 60px; height: 60px;
  background: radial-gradient(circle, #a78bfa, transparent);
  opacity: 0.12;
  animation-delay: 0.8s;
  top: 10px; right: 5px;
}
@keyframes orbFloat {
  0%,100% { transform: translateY(0) scale(1); }
  50% { transform: translateY(-6px) scale(1.06); }
}
.empty-icon {
  font-size: 48px;
  z-index: 1;
  filter: drop-shadow(0 4px 8px rgba(0,0,0,0.1));
  animation: heroBounce 2s ease-in-out infinite;
}
@keyframes heroBounce {
  0%,100% { transform: translateY(0); }
  30% { transform: translateY(-5px); }
  50% { transform: translateY(0); }
}
.empty-title {
  margin: 0;
  font-size: 20px;
  font-weight: 700;
  color: var(--text-1);
}
.empty-desc {
  margin: 0;
  font-size: 14px;
  color: var(--text-3);
  max-width: 320px;
  text-align: center;
  line-height: 1.6;
}

/* ============ Agent 卡片网格 ============ */
.agent-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(340px, 1fr));
  gap: 16px;
}

/* ============ Agent 卡片 ============ */
.agent-card {
  background: var(--bg-card);
  border-radius: var(--radius);
  border: 1.5px solid var(--border);
  padding: 20px;
  display: flex;
  flex-direction: column;
  gap: 14px;
  transition: all 0.25s cubic-bezier(0.16, 1, 0.3, 1);
  position: relative;
  box-shadow: var(--shadow-sm);
  animation: cardSlideIn 0.35s cubic-bezier(0.16, 1, 0.3, 1);
}
@keyframes cardSlideIn {
  from { opacity: 0; transform: translateY(12px) scale(0.98); }
  to { opacity: 1; transform: translateY(0) scale(1); }
}
.agent-card:hover {
  box-shadow: var(--shadow-lg);
  border-color: var(--accent-md);
  transform: translateY(-2px);
}
.agent-card.is-default {
  border-color: var(--default-md);
  background: linear-gradient(135deg, var(--default-lt), rgba(59,130,246,0.02));
}
.agent-card.is-default:hover {
  border-color: var(--default);
  box-shadow: 0 6px 24px var(--default-glow);
}
.agent-card.is-builtin {
  background: linear-gradient(135deg, rgba(139,92,246,0.02), var(--bg-card));
}

/* 卡片顶部 */
.card-top {
  display: flex;
  align-items: flex-start;
  gap: 14px;
}
.card-avatar {
  width: 52px;
  height: 52px;
  border-radius: 14px;
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
  box-shadow: 0 3px 10px rgba(0,0,0,0.1);
  transition: all 0.25s ease;
}
.agent-card:hover .card-avatar {
  transform: scale(1.05);
  box-shadow: 0 4px 14px rgba(0,0,0,0.15);
}
.avatar-emoji {
  font-size: 26px;
  line-height: 1;
  filter: drop-shadow(0 1px 2px rgba(0,0,0,0.15));
}
.card-avatar.avatar-default {
  box-shadow: 0 3px 12px var(--default-glow);
}
.card-info {
  flex: 1;
  min-width: 0;
}
.card-name-row {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 4px;
}
.card-name {
  font-size: 16px;
  font-weight: 700;
  color: var(--text-1);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.badge-default {
  font-size: 10px;
  font-weight: 700;
  padding: 2px 10px;
  border-radius: 12px;
  background: linear-gradient(135deg, #3b82f6, #2563eb);
  color: #fff;
  flex-shrink: 0;
  box-shadow: 0 2px 6px rgba(59,130,246,0.25);
}
.badge-builtin {
  font-size: 10px;
  font-weight: 700;
  padding: 2px 10px;
  border-radius: 12px;
  background: var(--accent-lt);
  color: var(--accent-dk);
  border: 1px solid var(--accent-md);
  flex-shrink: 0;
}
.card-desc {
  font-size: 13px;
  color: var(--text-3);
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
  line-height: 1.5;
}

/* 工具标签区 */
.card-tools {
  padding: 6px 0;
}
.tools-scroll {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}
.tool-chip {
  font-size: 11px;
  font-weight: 500;
  font-family: 'SF Mono', 'Consolas', 'Fira Code', monospace;
  padding: 4px 10px;
  border-radius: 6px;
  background: var(--bg-hover);
  color: var(--text-2);
  border: 1px solid var(--border-lt);
  transition: all 0.2s ease;
  white-space: nowrap;
}
.tool-chip:hover {
  background: var(--accent-lt);
  border-color: var(--accent-md);
  color: var(--accent-dk);
}
.tool-chip.tool-more {
  background: var(--accent-lt);
  border-color: var(--accent-md);
  color: var(--accent);
  font-weight: 700;
  cursor: default;
}

/* 卡片底部 */
.card-footer {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding-top: 8px;
  border-top: 1px solid var(--border-lt);
  gap: 12px;
}
.card-meta {
  display: flex;
  align-items: center;
  gap: 12px;
  flex: 1;
  min-width: 0;
  overflow: hidden;
}
.meta-item {
  font-size: 11px;
  color: var(--text-3);
  display: flex;
  align-items: center;
  gap: 4px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.card-actions {
  display: flex;
  gap: 2px;
  flex-shrink: 0;
}
.btn-action {
  width: 32px;
  height: 32px;
  border-radius: 8px !important;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 15px;
  transition: all 0.2s ease;
  border: none !important;
}
.btn-star:hover {
  background: rgba(245,158,11,0.1) !important;
  color: #f59e0b !important;
}
.btn-edit:hover {
  background: var(--accent-lt) !important;
  color: var(--accent) !important;
}
.btn-delete:hover {
  background: rgba(239,68,68,0.08) !important;
  color: var(--red) !important;
}

/* ============ 弹窗 ============ */
.modal-overlay {
  position: fixed;
  inset: 0;
  z-index: 9999;
  background: rgba(0,0,0,0.55);
  backdrop-filter: blur(4px);
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 24px;
}
.modal-container {
  --modal-bg: #ffffff;
  --modal-text-1: #1a1a2e;
  --modal-text-2: #5c5c78;
  --modal-text-3: #9696aa;
  --modal-border: #e8e5f0;

  background: var(--modal-bg);
  border-radius: 18px;
  width: 740px;
  max-height: 88vh;
  display: flex;
  flex-direction: column;
  box-shadow: 0 20px 60px rgba(0,0,0,0.2), 0 0 0 1px rgba(0,0,0,0.05);
  color: var(--modal-text-1);
}

/* 弹窗头部 */
.modal-header {
  display: flex;
  align-items: flex-start;
  gap: 14px;
  padding: 20px 24px 16px;
  border-bottom: 1px solid var(--modal-border);
  border-radius: 18px 18px 0 0;
  flex-shrink: 0;
}
.modal-header-icon {
  font-size: 24px;
  flex-shrink: 0;
  width: 42px;
  height: 42px;
  border-radius: 12px;
  background: linear-gradient(135deg, rgba(139,92,246,0.08), rgba(139,92,246,0.04));
  display: flex;
  align-items: center;
  justify-content: center;
}
.modal-header-text {
  flex: 1;
}
.modal-title {
  margin: 0;
  font-size: 18px;
  font-weight: 700;
  color: var(--modal-text-1);
  line-height: 1.2;
}
.modal-subtitle {
  font-size: 12px;
  color: var(--modal-text-3);
  margin-top: 2px;
  display: block;
}
.modal-close {
  background: none;
  border: none;
  font-size: 20px;
  cursor: pointer;
  color: var(--modal-text-3);
  padding: 4px 10px;
  border-radius: 8px;
  transition: all 0.2s;
  flex-shrink: 0;
  line-height: 1;
}
.modal-close:hover {
  color: var(--modal-text-1);
  background: #f0edf6;
}

/* 弹窗主体 */
.modal-body {
  flex: 1;
  overflow-y: auto;
  padding: 20px 24px;
}
.modal-body::-webkit-scrollbar {
  width: 5px;
}
.modal-body::-webkit-scrollbar-thumb {
  background: #dcd8ea;
  border-radius: 3px;
}
.modal-body::-webkit-scrollbar-track {
  background: transparent;
}

/* 弹窗底部 */
.modal-footer {
  display: flex;
  justify-content: flex-end;
  gap: 12px;
  padding: 16px 24px;
  border-top: 1px solid var(--modal-border);
  background: #faf9fc;
  border-radius: 0 0 18px 18px;
  flex-shrink: 0;
}
.btn-cancel {
  border-radius: var(--radius-sm) !important;
  font-weight: 600 !important;
}
.btn-save {
  border-radius: var(--radius-sm) !important;
  font-weight: 700 !important;
  padding: 0 28px !important;
  box-shadow: 0 2px 8px var(--accent-glow);
}

/* ---------- 表单样式 ---------- */
.agent-form :deep(.ant-form-item) {
  margin-bottom: 14px;
}
.agent-form :deep(.ant-form-item-label > label) {
  font-weight: 600;
  color: var(--modal-text-1);
  font-size: 13px;
}
.form-input :deep(.ant-input) {
  border-radius: 8px;
  border-color: var(--border);
  transition: all 0.25s ease;
}
.form-input :deep(.ant-input:hover) {
  border-color: var(--accent-md);
}
.form-input :deep(.ant-input:focus) {
  border-color: var(--accent);
  box-shadow: 0 0 0 3px var(--accent-lt);
}
.form-textarea :deep(textarea) {
  border-radius: 8px;
  border-color: var(--border);
  resize: vertical;
}
.form-textarea :deep(textarea:hover) {
  border-color: var(--accent-md);
}
.form-textarea :deep(textarea:focus) {
  border-color: var(--accent);
  box-shadow: 0 0 0 3px var(--accent-lt);
}
.input-suffix-icon {
  cursor: pointer;
  color: var(--text-3);
  transition: color 0.2s;
}
.input-suffix-icon:hover {
  color: var(--accent);
}

/* ---------- 头像选择器 ---------- */
.avatar-picker {
  position: relative;
}
.avatar-current {
  display: inline-flex;
  align-items: center;
  gap: 10px;
  padding: 10px 16px;
  border: 2px dashed var(--border);
  border-radius: 10px;
  cursor: pointer;
  transition: all 0.2s ease;
  background: var(--bg-card);
}
.avatar-current:hover {
  border-color: var(--accent-md);
  background: var(--accent-lt);
}
.avatar-preview {
  font-size: 30px;
  line-height: 1;
}
.avatar-hint {
  font-size: 12px;
  color: var(--text-3);
  font-weight: 500;
}
.avatar-arrow {
  font-size: 12px;
  color: var(--text-3);
  margin-left: auto;
  transition: transform 0.3s ease;
}
.avatar-arrow.open {
  transform: rotate(180deg);
}
.emoji-grid {
  position: absolute;
  top: calc(100% + 8px);
  left: 0;
  display: grid;
  grid-template-columns: repeat(8, 1fr);
  gap: 4px;
  padding: 12px;
  background: var(--bg-card);
  border: 1px solid var(--border);
  border-radius: 12px;
  box-shadow: var(--shadow-lg);
  z-index: 20;
  max-height: 240px;
  overflow-y: auto;
}
.emoji-item {
  font-size: 22px;
  padding: 7px;
  text-align: center;
  cursor: pointer;
  border-radius: 8px;
  transition: all 0.15s ease;
  border: 2px solid transparent;
}
.emoji-item:hover {
  background: var(--accent-lt);
  border-color: var(--accent-md);
  transform: scale(1.15);
}
.emoji-item.selected {
  background: linear-gradient(135deg, var(--accent-lt), rgba(139,92,246,0.1));
  border-color: var(--accent);
  box-shadow: 0 0 0 2px var(--accent-glow);
}

/* ---------- 工具选择面板 ---------- */
.tools-panel {
  border: 1px solid var(--border);
  border-radius: 10px;
  overflow: hidden;
  background: #faf9fc;
}
.tools-collapse :deep(.ant-collapse-header) {
  padding: 10px 14px !important;
  font-weight: 600;
  font-size: 13px;
  color: var(--text-1);
}
.tools-collapse :deep(.ant-collapse-content-box) {
  padding: 4px 10px 10px !important;
}
.cat-count {
  font-size: 11px;
  background: var(--accent-lt);
  color: var(--accent);
  padding: 2px 8px;
  border-radius: 10px;
  font-weight: 700;
}
.tools-group {
  display: flex;
  flex-direction: column;
  gap: 2px;
}
.tool-item {
  display: flex;
  align-items: center;
  padding: 6px 10px;
  gap: 8px;
  border-radius: 6px;
  cursor: pointer;
  transition: all 0.15s ease;
  border: 1px solid transparent;
}
.tool-item:hover {
  background: var(--accent-lt);
  border-color: var(--accent-md);
}
.tool-item.tool-selected {
  background: linear-gradient(135deg, rgba(139,92,246,0.06), rgba(139,92,246,0.02));
  border-color: var(--accent-md);
}
.tool-checkbox {
  margin: 0;
  flex-shrink: 0;
}
.tool-info {
  flex: 1;
  min-width: 0;
  display: flex;
  align-items: center;
  gap: 8px;
}
.tool-name {
  font-family: 'SF Mono', 'Consolas', 'Fira Code', monospace;
  font-size: 12px;
  font-weight: 600;
  color: var(--text-1);
  min-width: 120px;
  flex-shrink: 0;
}
.tool-desc {
  font-size: 11px;
  color: var(--text-3);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.tool-risk {
  font-size: 13px;
  cursor: help;
  flex-shrink: 0;
}
.tools-footer {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 8px 14px;
  border-top: 1px solid var(--border-lt);
  background: #fff;
}
.tools-selected-count {
  font-size: 12px;
  color: var(--text-3);
  font-weight: 500;
}
.tools-footer-actions {
  display: flex;
  gap: 4px;
}

.form-hint {
  font-size: 12px;
  color: var(--text-3);
  padding: 8px 12px;
  background: #faf9fc;
  border-radius: 8px;
  border: 1px dashed var(--border);
}

/* ===== 角色性格配置（独立 toggle + Grid 布局，不使用 a-collapse） ===== */
.char-section {
  margin-top: 8px;
  border: 1px solid var(--border);
  border-radius: 10px;
  overflow: hidden;
}
.char-toggle {
  display: flex;
  align-items: center;
  gap: 8px;
  width: 100%;
  padding: 12px 16px;
  border: none;
  background: var(--bg-hover, #faf9fc);
  font-size: 14px;
  font-weight: 600;
  color: var(--text-1);
  cursor: pointer;
  transition: background 0.2s;
  text-align: left;
}
.char-toggle:hover {
  background: rgba(139,92,246,0.08);
}
.char-toggle-icon {
  font-size: 11px;
  transition: transform 0.2s;
  width: 14px;
  display: inline-block;
  color: var(--text-3);
}
.char-body {
  padding: 16px 18px;
}
.char-form-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 14px 16px;
}
.char-field {
  display: flex;
  flex-direction: column;
  gap: 5px;
}
.char-field-wide {
  grid-column: 1 / -1;
}
.char-label {
  font-size: 13px;
  font-weight: 600;
  color: var(--text-1);
}
.char-field :deep(.ant-input),
.char-field :deep(.ant-input-number),
.char-field :deep(.ant-select-selector),
.char-field :deep(textarea) {
  border-radius: 8px;
}
[data-theme="dark"] .char-label { color: #e4e2f0; }
[data-theme="dark"] .char-toggle {
  background: rgba(255,255,255,0.03);
  color: #e4e2f0;
}
[data-theme="dark"] .char-toggle:hover {
  background: rgba(139,92,246,0.1);
}
[data-theme="dark"] .char-section {
  border-color: var(--modal-border, #363448);
}

/* ============ 弹窗过渡动画 ============ */
.modal-fade-enter-active,
.modal-fade-leave-active {
  transition: opacity 0.25s ease;
}
.modal-fade-enter-active .modal-container,
.modal-fade-leave-active .modal-container {
  transition: transform 0.25s cubic-bezier(0.16, 1, 0.3, 1);
}
.modal-fade-enter-from,
.modal-fade-leave-to {
  opacity: 0;
}
.modal-fade-enter-from .modal-container {
  transform: scale(0.95) translateY(10px);
}
.modal-fade-leave-to .modal-container {
  transform: scale(0.95) translateY(10px);
}

/* emoji picker 过渡 */
.picker-drop-enter-active,
.picker-drop-leave-active {
  transition: all 0.2s cubic-bezier(0.16, 1, 0.3, 1);
}
.picker-drop-enter-from,
.picker-drop-leave-to {
  opacity: 0;
  transform: translateY(-6px) scale(0.95);
}

/* ============ 暗色模式 ============ */
[data-theme="dark"] .agent-config-root {
  --bg-root: #121117;
  --bg-card: #1a1925;
  --bg-hover: rgba(139,92,246,0.06);
  --text-1: #e4e2f0;
  --text-2: #a09eb8;
  --text-3: #6a6880;
  --text-4: #525070;
  --border: #2a2838;
  --border-lt: #222030;
  --shadow-sm: 0 1px 3px rgba(0,0,0,0.3);
  --shadow-md: 0 4px 12px rgba(0,0,0,0.4);
  --shadow-lg: 0 8px 30px rgba(0,0,0,0.5);
  --accent-lt: rgba(139,92,246,0.1);
  --accent-md: rgba(139,92,246,0.2);
  --accent-glow: rgba(139,92,246,0.2);
  --default-lt: rgba(59,130,246,0.1);
  --default-md: rgba(59,130,246,0.2);
  --default-glow: rgba(59,130,246,0.2);
}
[data-theme="dark"] .agent-content::-webkit-scrollbar-thumb { background: #3a3850; }
[data-theme="dark"] .modal-body::-webkit-scrollbar-thumb { background: #3a3850; }
/* 页面主容器 + emoji 选择器滚动条 */
.agent-page::-webkit-scrollbar { width: 5px; }
.agent-page::-webkit-scrollbar-track { background: transparent; }
.agent-page::-webkit-scrollbar-thumb { background: #dcd8ea; border-radius: 3px; }
.emoji-grid::-webkit-scrollbar { width: 4px; }
.emoji-grid::-webkit-scrollbar-thumb { background: #dcd8ea; border-radius: 2px; }
[data-theme="dark"] .agent-page::-webkit-scrollbar-thumb { background: #3a3850; }
[data-theme="dark"] .emoji-grid::-webkit-scrollbar-thumb { background: #3a3850; }
[data-theme="dark"] .agent-card.is-default {
  background: linear-gradient(135deg, rgba(59,130,246,0.06), rgba(30,29,45,0.5));
}
[data-theme="dark"] .agent-card.is-builtin {
  background: linear-gradient(135deg, rgba(139,92,246,0.04), #1a1925);
}
[data-theme="dark"] .tool-chip {
  background: rgba(139,92,246,0.06);
  border-color: #2a2838;
}
[data-theme="dark"] .tool-chip:hover {
  background: rgba(139,92,246,0.12);
  color: #c4b5fd;
}
[data-theme="dark"] .tools-panel {
  background: #15141d;
}
[data-theme="dark"] .tools-footer {
  background: #1a1925;
}
[data-theme="dark"] .form-hint {
  background: #15141d;
}
[data-theme="dark"] .modal-container {
  --modal-bg: #1e1d2c;
  --modal-text-1: #e4e2f0;
  --modal-text-2: #a09eb8;
  --modal-text-3: #7a7898;
  --modal-border: #363448;
  border-color: rgba(255,255,255,0.08);
  box-shadow: 0 20px 60px rgba(0,0,0,0.5), 0 0 0 1px rgba(255,255,255,0.04);
}
[data-theme="dark"] .modal-close:hover { background: #2a2838; }
[data-theme="dark"] .modal-footer { background: #1a1925; }
[data-theme="dark"] .modal-header-icon {
  background: linear-gradient(135deg, rgba(139,92,246,0.12), rgba(139,92,246,0.06));
}
[data-theme="dark"] .avatar-current { background: #1a1925; }
[data-theme="dark"] .emoji-grid { background: #1e1d2c; border-color: #363448; }
[data-theme="dark"] .form-input :deep(.ant-input) { background: #1a1925; border-color: #363448; color: #e4e2f0; }
[data-theme="dark"] .form-input :deep(.ant-input:hover) { border-color: rgba(139,92,246,0.3); }
[data-theme="dark"] .form-input :deep(.ant-input:focus) { border-color: #8b5cf6; }
[data-theme="dark"] .form-textarea :deep(textarea) { background: #1a1925; border-color: #363448; color: #e4e2f0; }
[data-theme="dark"] .form-textarea :deep(textarea:hover) { border-color: rgba(139,92,246,0.3); }
[data-theme="dark"] .form-textarea :deep(textarea:focus) { border-color: #8b5cf6; }

/* ============ 采样温度滑块 ============ */
.temperature-wrapper {
  display: flex;
  align-items: center;
  gap: 16px;
}
.temperature-slider {
  flex: 1;
}
.temperature-value {
  min-width: 36px;
  font-size: 16px;
  font-weight: 700;
  color: #8b5cf6;
  text-align: center;
}

/* ============ 响应式 ============ */
@media (max-width: 768px) {
  .page-header {
    margin: 12px;
    padding: 16px;
    flex-direction: column;
    align-items: flex-start;
    gap: 12px;
  }
  .agent-content {
    padding: 12px;
  }
  .agent-grid {
    grid-template-columns: 1fr;
  }
  .modal-container {
    width: 94vw;
    max-height: 90vh;
  }
}
</style>
