<template>
  <div class="mcp-config-view">
    <div class="page-header">
      <div class="title-area">
        <h2 class="page-title">🛰️ MCP 服务器管理</h2>
        <span class="page-desc">配置 CodeCraft 作为 MCP Client 连接的外部服务器，其工具将自动注册供 AI 调用</span>
      </div>
      <div class="action-area">
        <a-button @click="loadList" :loading="loading" class="mr-8">
          <template #icon><ReloadOutlined /></template>
          刷新
        </a-button>
        <a-button type="primary" @click="openCreate">
          <template #icon><PlusOutlined /></template>
          新增服务器
        </a-button>
      </div>
    </div>

    <a-table
      :data-source="servers"
      :columns="columns"
      :loading="loading"
      row-key="id"
      :pagination="false"
      size="middle"
      class="server-table"
    >
      <template #bodyCell="{ column, record }">
        <!-- 类型 -->
        <template v-if="column.key === 'type'">
          <a-tag :color="record.type === 'http' ? 'blue' : 'purple'">{{ record.type.toUpperCase() }}</a-tag>
        </template>

        <!-- 连接信息 -->
        <template v-else-if="column.key === 'target'">
          <span v-if="record.type === 'http'" class="mono">{{ record.url }}</span>
          <span v-else class="mono">{{ record.command }}</span>
        </template>

        <!-- 权限档位 -->
        <template v-else-if="column.key === 'permissionLevel'">
          <a-tag :color="permissionColor(record.permissionLevel)">{{ permissionLabel(record.permissionLevel) }}</a-tag>
        </template>

        <!-- 启用状态 -->
        <template v-else-if="column.key === 'enabled'">
          <a-switch
            :checked="record.enabled === 1"
            size="small"
            @change="(checked: boolean) => toggleEnabled(record, checked)"
          />
        </template>

        <!-- 连接状态 -->
        <template v-else-if="column.key === 'status'">
          <a-tag :color="statusColor(record.status)">
            {{ statusLabel(record.status) }}
          </a-tag>
          <div v-if="record.status === 'FAILED' && record.errorMessage" class="error-msg" :title="record.errorMessage">
            {{ record.errorMessage }}
          </div>
        </template>

        <!-- 工具数 -->
        <template v-else-if="column.key === 'toolCount'">
          <span v-if="record.status === 'CONNECTED'">
            <b>{{ record.registeredToolCount }}</b>
            <span class="muted"> / {{ record.toolCount }}</span>
          </span>
          <span v-else class="muted">-</span>
        </template>

        <!-- 操作 -->
        <template v-else-if="column.key === 'action'">
          <a-space :size="4" wrap>
            <a-button
              v-if="record.status !== 'CONNECTED'"
              type="link" size="small" @click="handleConnect(record)"
            >连接</a-button>
            <a-popconfirm
              v-else
              title="断开后已注册的工具将被注销，确定断开？"
              @confirm="handleDisconnect(record)"
            >
              <a-button type="link" size="small" danger>断开</a-button>
            </a-popconfirm>
            <a-button type="link" size="small" @click="handleRefresh(record)">刷新</a-button>
            <a-button type="link" size="small" @click="openEdit(record)">编辑</a-button>
            <a-popconfirm title="确定删除该服务器配置？" @confirm="handleDelete(record)">
              <a-button type="link" size="small" danger>删除</a-button>
            </a-popconfirm>
          </a-space>
        </template>
      </template>
    </a-table>

    <!-- 新增/编辑弹窗 -->
    <a-modal
      v-model:open="modalOpen"
      :title="editingId ? '编辑 MCP 服务器' : '新增 MCP 服务器'"
      :confirm-loading="saving"
      width="640px"
      @ok="handleSave"
      @cancel="closeModal"
    >
      <a-form ref="formRef" :model="form" :rules="rules" :label-col="{ span: 5 }" :wrapper-col="{ span: 18 }">
        <a-form-item label="名称" name="name">
          <a-input v-model:value="form.name" placeholder="如 GitHub、文件系统" />
        </a-form-item>

        <a-form-item label="传输类型" name="type">
          <a-radio-group v-model:value="form.type">
            <a-radio value="http">Streamable HTTP</a-radio>
            <a-radio value="stdio">stdio（本地进程）</a-radio>
          </a-radio-group>
        </a-form-item>

        <a-form-item v-if="form.type === 'http'" label="端点 URL" name="url">
          <a-input v-model:value="form.url" placeholder="http://localhost:3001/mcp" />
        </a-form-item>

        <a-form-item v-else label="启动命令" name="command">
          <a-input v-model:value="form.command" placeholder="npx -y @modelcontextprotocol/server-github" />
        </a-form-item>

        <a-form-item label="请求头(JSON)" name="headers">
          <a-textarea
            v-model:value="form.headers"
            :rows="2"
            placeholder='{"Authorization":"Bearer xxx"}（可选，仅 http 类型生效）'
          />
          <span v-if="headersConfigured" class="muted tip-text">已配置请求头（出于安全不回显），重新填写将覆盖</span>
        </a-form-item>

        <a-form-item label="工具名前缀" name="toolPrefix">
          <a-input v-model:value="form.toolPrefix" placeholder="如 github_，留空则用服务器名小写 + _ " />
        </a-form-item>

        <a-form-item label="权限档位" name="permissionLevel">
          <a-select v-model:value="form.permissionLevel">
            <a-select-option value="SAFE">SAFE - 只读</a-select-option>
            <a-select-option value="DATA">DATA - 可写数据</a-select-option>
            <a-select-option value="HIGH_RISK">HIGH_RISK - 高危</a-select-option>
          </a-select>
        </a-form-item>

        <a-form-item label="启用" name="enabled">
          <a-switch v-model:checked="form.enabled" :checked-value="1" :un-checked-value="0" />
          <span class="muted tip-text">启用后保存时立即尝试连接</span>
        </a-form-item>

        <a-form-item label="自动注册" name="autoRegister">
          <a-switch v-model:checked="form.autoRegister" :checked-value="1" :un-checked-value="0" />
          <span class="muted tip-text">应用启动时自动连接并注册其工具</span>
        </a-form-item>
      </a-form>
    </a-modal>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, onMounted } from 'vue'
import { message } from 'ant-design-vue'
import { PlusOutlined, ReloadOutlined } from '@ant-design/icons-vue'
import type { FormInstance } from 'ant-design-vue'
import {
  listMcpServers, createMcpServer, updateMcpServer, deleteMcpServer,
  connectMcpServer, disconnectMcpServer, refreshMcpServer,
  type McpServerVO, type McpServerConfig
} from '@/api/mcp'

const loading = ref(false)
const servers = ref<McpServerVO[]>([])

// ==================== 列表加载 ====================
const loadList = async () => {
  loading.value = true
  try {
    const res = await listMcpServers()
    if (res.code === 200) {
      servers.value = res.data || []
    } else {
      message.error(res.message || '加载失败')
    }
  } catch (e) {
    message.error('加载失败: ' + (e as Error).message)
  } finally {
    loading.value = false
  }
}

// ==================== 表格列 ====================
const columns = [
  { title: '名称', dataIndex: 'name', key: 'name', width: 130 },
  { title: '类型', key: 'type', width: 80 },
  { title: '地址 / 命令', key: 'target', ellipsis: true },
  { title: '前缀', dataIndex: 'toolPrefix', key: 'toolPrefix', width: 110 },
  { title: '权限', key: 'permissionLevel', width: 100 },
  { title: '启用', key: 'enabled', width: 70 },
  { title: '状态', key: 'status', width: 120 },
  { title: '工具数', key: 'toolCount', width: 90 },
  { title: '操作', key: 'action', width: 200 }
]

// ==================== 状态展示 ====================
const statusLabel = (s: string) => ({ CONNECTED: '已连接', FAILED: '连接失败', DISABLED: '未连接' } as Record<string, string>)[s] || s
const statusColor = (s: string) => ({ CONNECTED: 'success', FAILED: 'error', DISABLED: 'default' } as Record<string, string>)[s] || 'default'
const permissionLabel = (p: string) => ({ SAFE: '只读', DATA: '可写数据', HIGH_RISK: '高危' } as Record<string, string>)[p] || p
const permissionColor = (p: string) => ({ SAFE: 'green', DATA: 'orange', HIGH_RISK: 'red' } as Record<string, string>)[p] || 'default'

// ==================== 连接操作 ====================
const handleConnect = async (record: McpServerVO) => {
  const res = await connectMcpServer(record.id)
  if (res.code === 200) {
    message.success(`连接完成，注册 ${res.data?.registeredToolCount ?? 0} 个工具`)
  } else {
    message.error(res.message || '连接失败')
  }
  loadList()
}

const handleDisconnect = async (record: McpServerVO) => {
  await disconnectMcpServer(record.id)
  message.success('已断开')
  loadList()
}

const handleRefresh = async (record: McpServerVO) => {
  const res = await refreshMcpServer(record.id)
  if (res.code === 200) {
    message.success('刷新完成')
  } else {
    message.error(res.message || '刷新失败')
  }
  loadList()
}

const handleDelete = async (record: McpServerVO) => {
  await deleteMcpServer(record.id)
  message.success('已删除')
  loadList()
}

const toggleEnabled = async (record: McpServerVO, checked: boolean) => {
  const res = await updateMcpServer(record.id, { ...record, enabled: checked ? 1 : 0 })
  if (res.code !== 200) {
    message.error(res.message || '更新失败')
  }
  loadList()
}

// ==================== 弹窗表单 ====================
const modalOpen = ref(false)
const saving = ref(false)
const editingId = ref<number | null>(null)
/** 编辑时是否已配置请求头（用于提示；明文不回显） */
const headersConfigured = ref(false)
const formRef = ref<FormInstance>()
const form = reactive<McpServerConfig>({
  name: '', type: 'http', url: '', command: '', headers: '',
  toolPrefix: '', permissionLevel: 'SAFE', enabled: 0, autoRegister: 1
})

const rules = {
  name: [{ required: true, message: '请输入服务器名称', trigger: 'blur' }],
  type: [{ required: true, message: '请选择传输类型', trigger: 'change' }],
  url: [{ required: true, message: '请输入端点 URL', trigger: 'blur' }],
  command: [{ required: true, message: '请输入启动命令', trigger: 'blur' }]
}

const openCreate = () => {
  editingId.value = null
  Object.assign(form, { name: '', type: 'http', url: '', command: '', headers: '', toolPrefix: '', permissionLevel: 'SAFE', enabled: 0, autoRegister: 1 })
  modalOpen.value = true
}

const openEdit = (record: McpServerVO) => {
  editingId.value = record.id
  Object.assign(form, {
    name: record.name, type: record.type, url: record.url || '', command: record.command || '',
    headers: '', toolPrefix: record.toolPrefix || '',
    permissionLevel: record.permissionLevel, enabled: record.enabled, autoRegister: record.autoRegister
  })
  // 请求头不回显（安全）：若已配置则提示，重新填写才会覆盖
  headersConfigured.value = !!record.hasHeaders
  modalOpen.value = true
}

const closeModal = () => {
  modalOpen.value = false
  editingId.value = null
}

const handleSave = async () => {
  try {
    await formRef.value?.validate()
  } catch {
    return
  }
  saving.value = true
  try {
    const res = editingId.value
      ? await updateMcpServer(editingId.value, { ...form })
      : await createMcpServer({ ...form })
    if (res.code === 200) {
      message.success(editingId.value ? '更新成功' : '创建成功')
      closeModal()
      loadList()
    } else {
      message.error(res.message || '保存失败')
    }
  } catch (e) {
    message.error('保存失败: ' + (e as Error).message)
  } finally {
    saving.value = false
  }
}

onMounted(loadList)
</script>

<style scoped>
.mcp-config-view {
  padding: 20px;
}

.page-header {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  margin-bottom: 16px;
}

.page-title {
  margin: 0;
  font-size: 20px;
}

.page-desc {
  color: #888;
  font-size: 13px;
}

.action-area {
  display: flex;
  align-items: center;
}

.mr-8 {
  margin-right: 8px;
}

.mono {
  font-family: 'JetBrains Mono', Consolas, monospace;
  font-size: 12px;
}

.muted {
  color: #999;
}

.tip-text {
  margin-left: 8px;
  font-size: 12px;
}

.error-msg {
  color: #f5222d;
  font-size: 12px;
  max-width: 220px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.server-table :deep(.ant-table-cell) {
  font-size: 13px;
}
</style>
