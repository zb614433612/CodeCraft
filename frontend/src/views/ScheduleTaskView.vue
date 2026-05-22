<template>
  <div class="schedule-container">
    <div class="page-header">
      <h2 class="page-title">定时任务管理</h2>
      <a-button type="primary" @click="openCreateModal">
        <template #icon><plus-outlined /></template>
        新增任务
      </a-button>
    </div>

    <a-table
      :dataSource="taskList"
      :columns="columns"
      :loading="loading"
      rowKey="id"
      size="middle"
      :pagination="{ pageSize: 10 }"
    >
      <template #bodyCell="{ column, record }">
        <template v-if="column.key === 'agentType'">
          {{ agentTypeLabel(record.agentType) }}
        </template>
        <template v-if="column.key === 'schedule'">
          <span v-if="record.cronExpression">{{ record.cronExpression }}</span>
          <span v-else>{{ record.executeTime ? formatTime(record.executeTime) : '立即执行' }}</span>
        </template>
        <template v-if="column.key === 'status'">
          <a-switch
            :checked="record.status === 'ENABLED'"
            @change="(checked: boolean) => handleToggleStatus(record, checked)"
            size="small"
          />
        </template>
        <template v-if="column.key === 'executeCount'">
          {{ record.executeCount }}/{{ record.maxExecuteCount === 0 ? '不限' : record.maxExecuteCount }}
        </template>
        <template v-if="column.key === 'lastExecuteTime'">
          {{ record.lastExecuteTime ? formatTime(record.lastExecuteTime) : '-' }}
        </template>
        <template v-if="column.key === 'action'">
          <a-space>
            <a-button type="link" size="small" @click="openEditModal(record)">编辑</a-button>
            <a-button
              v-if="record.lastConversationId"
              type="link"
              size="small"
              @click="viewConversation(record)"
            >查看会话</a-button>
            <a-popconfirm title="确定删除该定时任务？" @confirm="handleDelete(record.id)">
              <a-button type="link" danger size="small">删除</a-button>
            </a-popconfirm>
          </a-space>
        </template>
      </template>
    </a-table>

    <!-- 新增/编辑弹窗 -->
    <a-modal
      v-model:open="modalVisible"
      :title="isEditing ? '编辑定时任务' : '新增定时任务'"
      @ok="handleModalOk"
      :confirmLoading="modalLoading"
      width="640px"
    >
      <a-form :model="formData" :label-col="{ span: 5 }" :wrapper-col="{ span: 17 }">
        <a-form-item label="任务名称">
          <a-input v-model:value="formData.name" placeholder="如：每日代码审查" />
        </a-form-item>
        <a-form-item label="Agent类型">
          <a-select v-model:value="formData.agentType">
            <a-select-option value="code_assistant">编码助手</a-select-option>
          </a-select>
        </a-form-item>
        <a-form-item label="执行方式">
          <a-radio-group v-model:value="scheduleType">
            <a-radio value="once">一次性</a-radio>
            <a-radio value="cron">周期（cron）</a-radio>
          </a-radio-group>
        </a-form-item>
        <a-form-item v-if="scheduleType === 'once'" label="执行时间">
          <a-date-picker
            v-model:value="executeDate"
            show-time
            value-format="YYYY-MM-DD HH:mm:ss"
            style="width: 100%"
          />
        </a-form-item>
        <a-form-item v-if="scheduleType === 'cron'" label="cron表达式">
          <a-input v-model:value="formData.cronExpression" placeholder="如：0 0 9 * * ?（每天9点）" />
        </a-form-item>
        <a-form-item label="任务指令">
          <a-textarea v-model:value="formData.instruction" placeholder="告诉 Agent 做什么..." :rows="4" :maxLength="2000" />
        </a-form-item>
        <a-form-item label="最大执行次数">
          <a-input-number v-model:value="formData.maxExecuteCount" :min="0" :max="9999" style="width: 120px" />
          <span class="form-hint">0=不限制</span>
        </a-form-item>
      </a-form>
    </a-modal>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { message } from 'ant-design-vue'
import { PlusOutlined } from '@ant-design/icons-vue'
import { useRouter } from 'vue-router'
import {
  getTaskList, createTask, updateTask, deleteTask,
  enableTask, disableTask,
  type ScheduleTaskItem, type CreateTaskParams
} from '@/api/schedule-task'

const router = useRouter()
const taskList = ref<ScheduleTaskItem[]>([])
const loading = ref(false)

const columns = [
  { title: '任务名称', dataIndex: 'name', key: 'name', width: 150 },
  { title: 'Agent', key: 'agentType', width: 100 },
  { title: '执行计划', key: 'schedule', width: 150 },
  { title: '状态', key: 'status', width: 70 },
  { title: '执行次数', key: 'executeCount', width: 100 },
  { title: '上次执行', key: 'lastExecuteTime', width: 160 },
  { title: '操作', key: 'action', width: 200, fixed: 'right' }
]

const agentTypeLabel = (type: string) => {
  const map: Record<string, string> = {
    code_assistant: '编码助手'
  }
  return map[type] || type
}

const formatTime = (t: string) => {
  if (!t) return '-'
  return new Date(t).toLocaleString('zh-CN', {
    year: 'numeric', month: '2-digit', day: '2-digit',
    hour: '2-digit', minute: '2-digit'
  })
}

const loadList = async () => {
  loading.value = true
  try {
    const res = await getTaskList()
    if (res.code === 200 && res.data) {
      taskList.value = res.data
    }
  } catch (e: any) {
    message.error(e.message || '加载失败')
  } finally {
    loading.value = false
  }
}

// 弹窗状态
const modalVisible = ref(false)
const modalLoading = ref(false)
const isEditing = ref(false)
const editingId = ref<number | null>(null)
const scheduleType = ref<'once' | 'cron'>('once')
const executeDate = ref<string>()

const formData = ref({
  name: '',
  agentType: 'code_assistant',
  instruction: '',
  cronExpression: '',
  maxExecuteCount: 0
})

const resetForm = () => {
  formData.value = { name: '', agentType: 'code_assistant', instruction: '', cronExpression: '', maxExecuteCount: 0 }
  scheduleType.value = 'once'
  executeDate.value = undefined
  isEditing.value = false
  editingId.value = null
}

const openCreateModal = () => {
  resetForm()
  modalVisible.value = true
}

const openEditModal = (item: ScheduleTaskItem) => {
  isEditing.value = true
  editingId.value = item.id
  formData.value = {
    name: item.name,
    agentType: item.agentType,
    instruction: item.instruction,
    cronExpression: item.cronExpression || '',
    maxExecuteCount: item.maxExecuteCount
  }
  if (item.cronExpression) {
    scheduleType.value = 'cron'
  } else {
    scheduleType.value = 'once'
    executeDate.value = item.executeTime || undefined
  }
  modalVisible.value = true
}

const handleModalOk = async () => {
  if (!formData.value.name.trim()) { message.warning('请输入任务名称'); return }
  if (!formData.value.instruction.trim()) { message.warning('请输入任务指令'); return }

  modalLoading.value = true
  try {
    const params: CreateTaskParams = {
      name: formData.value.name,
      agentType: formData.value.agentType,
      instruction: formData.value.instruction,
      maxExecuteCount: formData.value.maxExecuteCount
    }
    if (scheduleType.value === 'once') {
      params.executeTime = executeDate.value || undefined
    } else {
      params.cronExpression = formData.value.cronExpression || undefined
    }

    if (isEditing.value && editingId.value) {
      await updateTask({ id: editingId.value, ...params })
      message.success('任务已更新')
    } else {
      await createTask(params)
      message.success('任务已创建')
    }
    modalVisible.value = false
    loadList()
  } catch (e: any) {
    message.error(e.message || '操作失败')
  } finally {
    modalLoading.value = false
  }
}

const handleToggleStatus = async (item: ScheduleTaskItem, checked: boolean) => {
  try {
    if (checked) {
      await enableTask(item.id)
    } else {
      await disableTask(item.id)
    }
    item.status = checked ? 'ENABLED' : 'DISABLED'
    message.success(checked ? '任务已启用' : '任务已禁用')
  } catch (e: any) {
    message.error(e.message || '操作失败')
  }
}

const handleDelete = async (id: number) => {
  try {
    await deleteTask(id)
    message.success('任务已删除')
    loadList()
  } catch (e: any) {
    message.error(e.message || '删除失败')
  }
}

const viewConversation = (item: ScheduleTaskItem) => {
  const routeMap: Record<string, string> = {
    code_assistant: '/code-assistant'
  }
  const path = routeMap[item.agentType] || '/code-assistant'
  router.push(path)
}

onMounted(() => {
  loadList()
})
</script>

<style scoped>
.schedule-container {
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

.form-hint {
  margin-left: 8px;
  color: #8c8c8c;
  font-size: 12px;
}
</style>
