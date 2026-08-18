<template>
  <div class="provider-config-root">
    <!-- ============ 页面头部 ============ -->
    <header class="page-header">
      <div class="header-left">
        <div class="header-icon">
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
            <rect x="2" y="3" width="20" height="14" rx="2" />
            <line x1="8" y1="21" x2="16" y2="21" />
            <line x1="12" y1="17" x2="12" y2="21" />
          </svg>
        </div>
        <div class="header-text">
          <h2 class="page-title">LLM Provider</h2>
          <span class="page-subtitle">{{ providerList.length }} 个平台</span>
        </div>
      </div>
      <a-button type="primary" size="large" class="btn-create" @click="openCreateModal">
        <PlusOutlined /> 新增 Provider
      </a-button>
    </header>

    <!-- ============ 内容区域 ============ -->
    <div class="agent-content">
      <div v-if="loading" class="state-wrapper">
        <div class="state-card"><a-spin size="large" /><span class="state-text">加载中...</span></div>
      </div>

      <div v-else-if="providerList.length === 0" class="state-wrapper">
        <div class="state-card empty-card">
          <div class="empty-icon">🔌</div>
          <h3 class="empty-title">还没有 LLM Provider</h3>
          <p class="empty-desc">配置一个新的 LLM 平台（DeepSeek / OpenAI / Claude 等）</p>
          <a-button type="primary" size="large" @click="openCreateModal"><PlusOutlined /> 新增 Provider</a-button>
        </div>
      </div>

      <div v-else class="agent-grid">
        <div
          v-for="p in providerList" :key="p.id"
          :class="['agent-card']"
        >
          <div class="card-top">
            <div class="card-avatar"
                 :style="{ background: avatarGradient(p.id, p.name) }">
              <span class="avatar-emoji">{{ providerEmoji(p.code) }}</span>
            </div>
            <div class="card-info">
              <div class="card-name-row">
                <span class="card-name">{{ p.name }}</span>
                <span class="badge-code">{{ p.code }}</span>
              </div>
              <span class="card-desc">{{ p.baseUrl }}</span>
            </div>
          </div>

          <div class="card-tools" v-if="p.parsedModelList && p.parsedModelList.length > 0">
            <div class="tools-scroll">
              <span v-for="m in p.parsedModelList.slice(0, 5)" :key="m" class="tool-chip">{{ m }}</span>
              <span v-if="p.parsedModelList.length > 5" class="tool-chip tool-more">+{{ p.parsedModelList.length - 5 }}</span>
            </div>
          </div>

          <div class="card-footer">
            <div class="card-meta">
              <span class="meta-item template-badge" :style="templateStyle(p.requestTemplate || p.code)">
                {{ templateLabel(p.requestTemplate || p.code) }}
              </span>
              <span v-if="p.enabled === 0" class="meta-item meta-disabled">已禁用</span>
            </div>
            <div class="card-actions">
              <a-button size="small" type="text" class="btn-action btn-edit"
                        @click="openEditModal(p)" title="编辑"><EditOutlined /></a-button>
              <a-popconfirm title="确定要删除这个 Provider 吗？" ok-text="删除" ok-type="danger" cancel-text="取消"
                            @confirm="handleDelete(p.id!)">
                <a-button size="small" type="text" danger class="btn-action btn-delete" title="删除">
                  <DeleteOutlined />
                </a-button>
              </a-popconfirm>
            </div>
          </div>
        </div>
      </div>
    </div>

    <!-- ============ 弹窗（与 AgentConfig 样式一致） ============ -->
    <Teleport to="body">
      <Transition name="modal-fade">
        <div v-if="modalVisible" class="modal-overlay" @click.self="handleCancel">
          <div class="modal-container">
            <div class="modal-header">
              <div class="modal-header-icon">{{ isEditing ? '✏️' : '✨' }}</div>
              <div class="modal-header-text">
                <h3 class="modal-title">{{ isEditing ? '编辑 Provider' : '新增 Provider' }}</h3>
                <span class="modal-subtitle">{{ isEditing ? '修改 LLM 平台配置' : '接入一个新的 LLM 平台' }}</span>
              </div>
              <button class="modal-close" @click="handleCancel" title="关闭">✕</button>
            </div>

            <div class="modal-body">
              <a-form ref="formRef" :model="formData" :label-col="{ span: 5 }" :wrapper-col="{ span: 19 }" class="agent-form">
                <a-form-item label="编码" required>
                  <a-input v-model:value="formData.code" placeholder="如 deepseek / openai / ollama" :maxLength="30"
                           :disabled="isEditing" size="large" class="form-input" />
                  <div class="form-hint" style="margin-top:2px">唯一标识，创建后不可修改</div>
                </a-form-item>
                <a-form-item label="名称" required>
                  <a-input v-model:value="formData.name" placeholder="如 DeepSeek / OpenAI" :maxLength="100" size="large" class="form-input" />
                </a-form-item>
                <a-form-item label="Base URL" required>
                  <a-input v-model:value="formData.baseUrl" placeholder="https://api.deepseek.com" :maxLength="300" class="form-input" />
                </a-form-item>
                <a-form-item label="API Key">
                  <a-input-password v-model:value="formData.apiKey" placeholder="留空则使用系统配置的 Key" :maxLength="200" class="form-input" />
                </a-form-item>
                <a-form-item label="默认模型">
                  <a-input v-model:value="formData.defaultModel" placeholder="如 deepseek-v4-pro" :maxLength="100" class="form-input" />
                </a-form-item>
                <a-form-item label="模型列表">
                  <a-textarea v-model:value="modelListText" placeholder='JSON 数组：["model-a","model-b"]' :rows="3" :maxLength="1000" class="form-textarea" />
                  <div class="form-hint" style="margin-top:2px">JSON 数组格式，前端模型选择器据此动态渲染</div>
                </a-form-item>
                <a-form-item label="请求模板">
                  <a-select v-model:value="formData.requestTemplate" class="form-input" :getPopupContainer="trigger => trigger.parentElement">
                    <a-select-option value="deepseek">DeepSeek（兼容 OpenAI）</a-select-option>
                    <a-select-option value="openai">OpenAI</a-select-option>
                    <a-select-option value="minimax">MiniMax</a-select-option>
                    <a-select-option value="anthropic">Anthropic (Claude)</a-select-option>
                    <a-select-option value="ollama">Ollama（本地）</a-select-option>
                    <a-select-option value="custom">自定义</a-select-option>
                  </a-select>
                </a-form-item>
              </a-form>
            </div>

            <div class="modal-footer">
              <a-button size="large" @click="handleCancel" class="btn-cancel">取消</a-button>
              <a-button type="primary" size="large" :loading="modalSaving" @click="handleSave" class="btn-save">
                {{ isEditing ? '保存修改' : '创建 Provider' }}
              </a-button>
            </div>
          </div>
        </div>
      </Transition>
    </Teleport>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, onMounted, watch } from 'vue'
import { message } from 'ant-design-vue'
import { PlusOutlined, EditOutlined, DeleteOutlined } from '@ant-design/icons-vue'
import { listProviders, createProvider, updateProvider, deleteProvider, parseModelList, type ProviderConfig } from '@/api/llm-provider'

const providerList = ref<ProviderConfig[]>([])
const loading = ref(false)
const modalVisible = ref(false)
const isEditing = ref(false)
const editingId = ref<number | null>(null)
const modalSaving = ref(false)
const modelListText = ref('')

const getDefaultForm = (): Partial<ProviderConfig> => ({
  code: '', name: '', baseUrl: '', apiKey: '', defaultModel: '', modelList: '[]', requestTemplate: 'deepseek'
})

const formData = reactive<Partial<ProviderConfig>>(getDefaultForm())

// 监听 modelListText 变化 → 同步到 formData.modelList
watch(modelListText, (val) => {
  formData.modelList = val
})

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

function providerEmoji(code: string): string {
  const map: Record<string, string> = { deepseek: '🔍', openai: '🤖', anthropic: '🧠', ollama: '🦙', mimo: '📱', minimax: '🔮', custom: '⚙️' }
  return map[code] || '🔌'
}

/** 模板类型 → 中文标签 */
function templateLabel(template: string): string {
  const map: Record<string, string> = {
    deepseek: 'DeepSeek',
    openai: 'OpenAI',
    anthropic: 'Claude',
    ollama: 'Ollama',
    custom: '自定义',
    mimo: 'MiMo',
    minimax: 'MiniMax',
    qwen: '通义千问',
    glm: '智谱 GLM',
    moonshot: 'Moonshot',
  }
  return map[template] || template
}

/** 模板类型 → 内联样式（替代 scoped 动态 class，避免 Vite 树摇丢失） */
function templateStyle(template: string): Record<string, string> {
  const colors: Record<string, { bg: string; color: string; border: string }> = {
    deepseek: { bg: 'rgba(139,92,246,0.1)', color: '#7c3aed', border: '1px solid rgba(139,92,246,0.25)' },
    openai: { bg: 'rgba(16,185,129,0.1)', color: '#059669', border: '1px solid rgba(16,185,129,0.25)' },
    anthropic: { bg: 'rgba(245,158,11,0.1)', color: '#d97706', border: '1px solid rgba(245,158,11,0.25)' },
    ollama: { bg: 'rgba(59,130,246,0.1)', color: '#2563eb', border: '1px solid rgba(59,130,246,0.25)' },
    custom: { bg: 'rgba(107,114,128,0.1)', color: '#6b7280', border: '1px solid rgba(107,114,128,0.25)' },
    mimo: { bg: 'rgba(255,105,0,0.1)', color: '#ff6900', border: '1px solid rgba(255,105,0,0.25)' },
    minimax: { bg: 'rgba(74,54,224,0.1)', color: '#4a36e0', border: '1px solid rgba(74,54,224,0.25)' },
    qwen: { bg: 'rgba(6,182,212,0.1)', color: '#0891b2', border: '1px solid rgba(6,182,212,0.25)' },
    glm: { bg: 'rgba(6,182,212,0.1)', color: '#0891b2', border: '1px solid rgba(6,182,212,0.25)' },
    moonshot: { bg: 'rgba(6,182,212,0.1)', color: '#0891b2', border: '1px solid rgba(6,182,212,0.25)' },
  }
  const c = colors[template] || colors.custom
  return {
    background: c.bg,
    color: c.color,
    border: c.border,
    fontWeight: '700',
    padding: '3px 10px',
    borderRadius: '10px',
    fontSize: '10px',
    whiteSpace: 'nowrap',
    flexShrink: '0',
  }
}

async function fetchList() {
  loading.value = true
  try {
    const res = await listProviders()
    if (res.code === 200 && res.data) providerList.value = res.data
  } catch (e: any) {
    message.error(e.message || '获取 Provider 列表失败')
  } finally { loading.value = false }
}

function openCreateModal() {
  isEditing.value = false; editingId.value = null
  // ★ 先删除所有可能残留的旧字段，再合入默认值（Object.assign 只合并，不会删除 target 中的多余属性）
  Object.keys(formData).forEach(k => delete (formData as any)[k])
  Object.assign(formData, getDefaultForm())
  modelListText.value = '[]'
  modalVisible.value = true
}

function openEditModal(p: ProviderConfig) {
  isEditing.value = true; editingId.value = p.id!
  Object.assign(formData, { ...p })
  modelListText.value = p.modelList || '[]'
  modalVisible.value = true
}

function handleCancel() { modalVisible.value = false }

async function handleSave() {
  if (!formData.code?.trim()) { message.warning('请输入 Provider 编码'); return }
  if (!formData.name?.trim()) { message.warning('请输入名称'); return }
  if (!formData.baseUrl?.trim()) { message.warning('请输入 Base URL'); return }
  // 校验 modelList
  try { JSON.parse(modelListText.value || '[]') } catch { message.warning('模型列表不是合法的 JSON 数组'); return }
  formData.modelList = modelListText.value

  modalSaving.value = true
  try {
    const payload = { ...formData }
    if (isEditing.value && editingId.value) {
      await updateProvider(editingId.value, payload)
      message.success('Provider 已更新')
    } else {
      await createProvider(payload)
      message.success('Provider 已创建')
    }
    modalVisible.value = false
    await fetchList()
  } catch (e: any) {
    message.error(e.message || '操作失败')
  } finally { modalSaving.value = false }
}

async function handleDelete(id: number) {
  try {
    await deleteProvider(id)
    message.success('Provider 已删除')
    await fetchList()
  } catch (e: any) { message.error(e.message || '删除失败') }
}

onMounted(() => fetchList())
</script>

<style scoped>
/* ============================================================
   Provider Config View — 复用 AgentConfig CSS 体系
   ============================================================ */

.agent-config-root {
  --accent: #8b5cf6; --accent-lt: rgba(139,92,246,0.06); --accent-md: rgba(139,92,246,0.15);
  --accent-dk: #7c3aed; --accent-glow: rgba(139,92,246,0.25);
  --default: #3b82f6; --default-lt: rgba(59,130,246,0.06); --default-md: rgba(59,130,246,0.15);
  --default-glow: rgba(59,130,246,0.2);
  --bg-root: #f5f3fa; --bg-card: #ffffff; --bg-hover: rgba(139,92,246,0.03);
  --text-1: #1a1a2e; --text-2: #5c5c78; --text-3: #9696aa; --text-4: #b8b8c8;
  --border: #e8e5f0; --border-lt: #f0edf6;
  --shadow-sm: 0 1px 3px rgba(0,0,0,0.04); --shadow-md: 0 4px 12px rgba(0,0,0,0.06);
  --shadow-lg: 0 8px 30px rgba(0,0,0,0.08); --green: #10b981; --red: #ef4444;
  --radius: 14px; --radius-sm: 10px; --radius-xs: 6px;
  height: 100%; display: flex; flex-direction: column; background: var(--bg-root);
  font-size: 14px; color: var(--text-1);
  font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', 'PingFang SC', 'Microsoft YaHei', sans-serif;
  overflow-y: auto;
}
.page-header { display: flex; align-items: center; justify-content: space-between; gap: 16px; padding: 20px 28px; margin: 20px 24px 0; background: var(--bg-card); border-radius: var(--radius); border: 1px solid var(--border); box-shadow: var(--shadow-sm); flex-shrink: 0; }
.header-left { display: flex; align-items: center; gap: 14px; }
.header-icon { width: 44px; height: 44px; border-radius: 12px; background: linear-gradient(135deg, var(--accent), var(--accent-dk)); display: flex; align-items: center; justify-content: center; color: #fff; box-shadow: 0 3px 12px var(--accent-glow); flex-shrink: 0; }
.header-icon svg { width: 22px; height: 22px; }
.header-text { display: flex; flex-direction: column; gap: 2px; }
.page-title { margin: 0; font-size: 22px; font-weight: 800; color: var(--text-1); letter-spacing: -0.3px; line-height: 1.2; }
.page-subtitle { font-size: 13px; color: var(--text-3); font-weight: 500; }
.btn-create { border-radius: var(--radius-sm) !important; font-weight: 700 !important; font-size: 14px !important; padding: 0 24px !important; height: 42px !important; box-shadow: 0 2px 8px var(--accent-glow); transition: all 0.25s ease; }
.btn-create:hover { transform: translateY(-1px); box-shadow: 0 6px 20px var(--accent-glow); }
.agent-content { flex: 1; padding: 20px 24px; min-height: 0; overflow-y: auto; }
.agent-content::-webkit-scrollbar { width: 4px; } .agent-content::-webkit-scrollbar-thumb { background: #dcd8ea; border-radius: 2px; }
.state-wrapper { display: flex; align-items: center; justify-content: center; min-height: 400px; }
.state-card { display: flex; flex-direction: column; align-items: center; gap: 12px; padding: 48px; background: var(--bg-card); border-radius: var(--radius); border: 1px solid var(--border); box-shadow: var(--shadow-sm); }
.state-text { font-size: 14px; color: var(--text-3); }
.empty-card { padding: 60px 80px; gap: 16px; }
.empty-icon { font-size: 48px; }
.empty-title { margin: 0; font-size: 20px; font-weight: 700; color: var(--text-1); }
.empty-desc { margin: 0; font-size: 14px; color: var(--text-3); max-width: 320px; text-align: center; line-height: 1.6; }
.agent-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(340px, 1fr)); gap: 16px; }
.agent-card { background: var(--bg-card); border-radius: var(--radius); border: 1.5px solid var(--border); padding: 20px; display: flex; flex-direction: column; gap: 14px; transition: all 0.25s cubic-bezier(0.16, 1, 0.3, 1); position: relative; box-shadow: var(--shadow-sm); }
.agent-card:hover { box-shadow: var(--shadow-lg); border-color: var(--accent-md); transform: translateY(-2px); }
.agent-card.is-default { border-color: var(--default-md); background: linear-gradient(135deg, var(--default-lt), rgba(59,130,246,0.02)); }
.card-top { display: flex; align-items: flex-start; gap: 14px; }
.card-avatar { width: 52px; height: 52px; border-radius: 14px; display: flex; align-items: center; justify-content: center; flex-shrink: 0; box-shadow: 0 3px 10px rgba(0,0,0,0.1); transition: all 0.25s ease; }
.agent-card:hover .card-avatar { transform: scale(1.05); box-shadow: 0 4px 14px rgba(0,0,0,0.15); }
.avatar-emoji { font-size: 26px; line-height: 1; }
.card-avatar.avatar-default { box-shadow: 0 3px 12px var(--default-glow); }
.card-info { flex: 1; min-width: 0; }
.card-name-row { display: flex; align-items: center; gap: 8px; margin-bottom: 4px; }
.card-name { font-size: 16px; font-weight: 700; color: var(--text-1); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.badge-default { font-size: 10px; font-weight: 700; padding: 2px 10px; border-radius: 12px; background: linear-gradient(135deg, #3b82f6, #2563eb); color: #fff; flex-shrink: 0; box-shadow: 0 2px 6px rgba(59,130,246,0.25); }
.badge-code { font-size: 10px; font-weight: 700; padding: 2px 10px; border-radius: 12px; background: var(--accent-lt); color: var(--accent-dk); border: 1px solid var(--accent-md); flex-shrink: 0; }
.card-desc { font-size: 13px; color: var(--text-3); display: -webkit-box; -webkit-line-clamp: 1; -webkit-box-orient: vertical; overflow: hidden; line-height: 1.5; }
.card-tools { padding: 6px 0; } .tools-scroll { display: flex; flex-wrap: wrap; gap: 6px; }
.tool-chip { font-size: 11px; font-weight: 500; font-family: 'SF Mono', 'Consolas', monospace; padding: 4px 10px; border-radius: 6px; background: var(--bg-hover); color: var(--text-2); border: 1px solid var(--border-lt); transition: all 0.2s ease; white-space: nowrap; }
.tool-chip:hover { background: var(--accent-lt); border-color: var(--accent-md); color: var(--accent-dk); }
.tool-chip.tool-more { background: var(--accent-lt); border-color: var(--accent-md); color: var(--accent); font-weight: 700; cursor: default; }
.card-footer { display: flex; align-items: center; justify-content: space-between; padding-top: 8px; border-top: 1px solid var(--border-lt); gap: 12px; }
.card-meta { display: flex; align-items: center; gap: 12px; flex: 1; min-width: 0; overflow: hidden; }
.meta-item { font-size: 11px; color: var(--text-3); display: flex; align-items: center; gap: 4px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.meta-disabled { color: #ef4444; font-weight: 600; }
.card-actions { display: flex; gap: 2px; flex-shrink: 0; }
.btn-action { width: 32px; height: 32px; border-radius: 8px !important; display: flex; align-items: center; justify-content: center; font-size: 15px; transition: all 0.2s ease; border: none !important; }
.btn-star:hover { background: rgba(245,158,11,0.1) !important; color: #f59e0b !important; }
.btn-edit:hover { background: var(--accent-lt) !important; color: var(--accent) !important; }
.btn-delete:hover { background: rgba(239,68,68,0.08) !important; color: var(--red) !important; }
.modal-overlay { position: fixed; inset: 0; z-index: 9999; background: rgba(0,0,0,0.55); backdrop-filter: blur(4px); display: flex; align-items: center; justify-content: center; padding: 24px; }
.modal-container { --modal-bg: #ffffff; --modal-text-1: #1a1a2e; --modal-text-2: #5c5c78; --modal-text-3: #9696aa; --modal-border: #e8e5f0; background: var(--modal-bg); border-radius: 18px; width: 680px; max-height: 85vh; display: flex; flex-direction: column; border: 1px solid var(--modal-border); box-shadow: 0 20px 60px rgba(0,0,0,0.2); color: var(--modal-text-1); }
.modal-header { display: flex; align-items: flex-start; gap: 14px; padding: 20px 24px 16px; border-bottom: 1px solid var(--modal-border); border-radius: 18px 18px 0 0; overflow: hidden; }
.modal-header-icon { font-size: 24px; flex-shrink: 0; width: 42px; height: 42px; border-radius: 12px; background: linear-gradient(135deg, rgba(139,92,246,0.08), rgba(139,92,246,0.04)); display: flex; align-items: center; justify-content: center; }
.modal-header-text { flex: 1; } .modal-title { margin: 0; font-size: 18px; font-weight: 700; color: var(--modal-text-1); line-height: 1.2; }
.modal-subtitle { font-size: 12px; color: var(--modal-text-3); margin-top: 2px; display: block; }
.modal-close { background: none; border: none; font-size: 20px; cursor: pointer; color: var(--modal-text-3); padding: 4px 10px; border-radius: 8px; transition: all 0.2s; flex-shrink: 0; }
.modal-close:hover { color: var(--modal-text-1); background: #f0edf6; }
.modal-body { flex: 1; overflow-y: auto; padding: 20px 24px; }
.modal-body::-webkit-scrollbar { width: 4px; } .modal-body::-webkit-scrollbar-thumb { background: #dcd8ea; border-radius: 2px; }
.modal-footer { display: flex; justify-content: flex-end; gap: 12px; padding: 16px 24px; border-top: 1px solid var(--modal-border); background: #faf9fc; border-radius: 0 0 18px 18px; overflow: hidden; }
.btn-cancel { border-radius: var(--radius-sm) !important; font-weight: 600 !important; }
.btn-save { border-radius: var(--radius-sm) !important; font-weight: 700 !important; padding: 0 28px !important; box-shadow: 0 2px 8px var(--accent-glow); }
.agent-form :deep(.ant-form-item) { margin-bottom: 18px; }
.agent-form :deep(.ant-form-item-label > label) { font-weight: 600; color: var(--modal-text-1); font-size: 13px; }
.form-input :deep(.ant-input) { border-radius: 8px; border-color: var(--border); transition: all 0.25s ease; }
.form-input :deep(.ant-input:hover) { border-color: var(--accent-md); }
.form-input :deep(.ant-input:focus) { border-color: var(--accent); box-shadow: 0 0 0 3px var(--accent-lt); }
.form-textarea :deep(textarea) { border-radius: 8px; border-color: var(--border); resize: vertical; }
.form-textarea :deep(textarea:hover) { border-color: var(--accent-md); }
.form-textarea :deep(textarea:focus) { border-color: var(--accent); box-shadow: 0 0 0 3px var(--accent-lt); }
.form-hint { font-size: 12px; color: var(--text-3); padding: 8px 12px; background: #faf9fc; border-radius: 8px; border: 1px dashed var(--border); }
.modal-fade-enter-active, .modal-fade-leave-active { transition: opacity 0.25s ease; }
.modal-fade-enter-active .modal-container, .modal-fade-leave-active .modal-container { transition: transform 0.25s cubic-bezier(0.16, 1, 0.3, 1); }
.modal-fade-enter-from, .modal-fade-leave-to { opacity: 0; }
.modal-fade-enter-from .modal-container { transform: scale(0.95) translateY(10px); }
.modal-fade-leave-to .modal-container { transform: scale(0.95) translateY(10px); }

[data-theme="dark"] .agent-config-root { --bg-root: #121117; --bg-card: #1a1925; --bg-hover: rgba(139,92,246,0.06); --text-1: #e4e2f0; --text-2: #a09eb8; --text-3: #6a6880; --text-4: #525070; --border: #2a2838; --border-lt: #222030; --shadow-sm: 0 1px 3px rgba(0,0,0,0.3); --shadow-md: 0 4px 12px rgba(0,0,0,0.4); --shadow-lg: 0 8px 30px rgba(0,0,0,0.5); --accent-lt: rgba(139,92,246,0.1); --accent-md: rgba(139,92,246,0.2); --accent-glow: rgba(139,92,246,0.2); --default-lt: rgba(59,130,246,0.1); --default-md: rgba(59,130,246,0.2); --default-glow: rgba(59,130,246,0.2); }
[data-theme="dark"] .agent-content::-webkit-scrollbar-thumb { background: #3a3850; }
[data-theme="dark"] .modal-body::-webkit-scrollbar-thumb { background: #3a3850; }
[data-theme="dark"] .agent-card.is-default { background: linear-gradient(135deg, rgba(59,130,246,0.06), rgba(30,29,45,0.5)); }
[data-theme="dark"] .tool-chip { background: rgba(139,92,246,0.06); border-color: #2a2838; }
[data-theme="dark"] .tool-chip:hover { background: rgba(139,92,246,0.12); color: #c4b5fd; }
[data-theme="dark"] .form-hint { background: #15141d; }
[data-theme="dark"] .modal-container { --modal-bg: #1e1d2c; --modal-text-1: #e4e2f0; --modal-text-2: #a09eb8; --modal-text-3: #7a7898; --modal-border: #363448; border-color: rgba(255,255,255,0.08); box-shadow: 0 20px 60px rgba(0,0,0,0.5); }
[data-theme="dark"] .modal-close:hover { background: #2a2838; }
[data-theme="dark"] .modal-footer { background: #1a1925; }
[data-theme="dark"] .modal-header-icon { background: linear-gradient(135deg, rgba(139,92,246,0.12), rgba(139,92,246,0.06)); }
[data-theme="dark"] .form-input :deep(.ant-input) { background: #1a1925; border-color: #363448; color: #e4e2f0; }
[data-theme="dark"] .form-input :deep(.ant-input:hover) { border-color: rgba(139,92,246,0.3); }
[data-theme="dark"] .form-input :deep(.ant-input:focus) { border-color: #8b5cf6; }
[data-theme="dark"] .form-textarea :deep(textarea) { background: #1a1925; border-color: #363448; color: #e4e2f0; }
[data-theme="dark"] .form-textarea :deep(textarea:hover) { border-color: rgba(139,92,246,0.3); }
[data-theme="dark"] .form-textarea :deep(textarea:focus) { border-color: #8b5cf6; }
@media (max-width: 768px) { .page-header { margin: 12px; padding: 16px; flex-direction: column; align-items: flex-start; gap: 12px; } .agent-content { padding: 12px; } .agent-grid { grid-template-columns: 1fr; } .modal-container { width: 94vw; max-height: 90vh; } }
</style>
