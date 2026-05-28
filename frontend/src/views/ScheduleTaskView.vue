<template>
  <div class="schedule-container">
    <!-- 统计卡片区 -->
    <div class="stats-row">
      <div class="stat-card stat-card--total">
        <div class="stat-icon">
          <ClockCircleOutlined />
        </div>
        <div class="stat-info">
          <span class="stat-value">{{ taskList.length }}</span>
          <span class="stat-label">任务总数</span>
        </div>
      </div>
      <div class="stat-card stat-card--enabled">
        <div class="stat-icon">
          <CheckCircleOutlined />
        </div>
        <div class="stat-info">
          <span class="stat-value">{{ enabledCount }}</span>
          <span class="stat-label">启用中</span>
        </div>
      </div>
      <div class="stat-card stat-card--disabled">
        <div class="stat-icon">
          <PauseCircleOutlined />
        </div>
        <div class="stat-info">
          <span class="stat-value">{{ disabledCount }}</span>
          <span class="stat-label">已禁用</span>
        </div>
      </div>
      <div class="stat-card stat-card--today">
        <div class="stat-icon">
          <FireOutlined />
        </div>
        <div class="stat-info">
          <span class="stat-value">{{ todayExecuteCount }}</span>
          <span class="stat-label">今日执行</span>
        </div>
      </div>
    </div>

    <!-- 主内容卡片 -->
    <div class="content-card">
      <!-- 卡片头部：标题 + 操作 -->
      <div class="card-header">
        <div class="card-header-left">
          <h2 class="card-title">定时任务管理</h2>
          <span class="card-subtitle">共 {{ filteredTaskList.length }} 个任务</span>
        </div>
        <div class="card-header-right">
          <a-select
            v-model:value="filterAgentId"
            placeholder="按 Agent 过滤"
            allowClear
            style="width: 200px"
            @change="loadList"
            class="filter-select"
          >
            <a-select-option v-for="a in agentList" :key="a.id" :value="a.id">
              {{ a.avatar }} {{ a.name }}
            </a-select-option>
          </a-select>
          <a-button type="primary" @click="openCreateModal">
            <template #icon><plus-outlined /></template>
            新增任务
          </a-button>
        </div>
      </div>

      <!-- 任务表格 -->
      <div class="card-body">
        <a-table
          :dataSource="filteredTaskList"
          :columns="columns"
          :loading="loading"
          rowKey="id"
          size="middle"
          :pagination="{ pageSize: 10, showSizeChanger: false, showTotal: (total: number) => `共 ${total} 条` }"
          :locale="{ emptyText: '暂无定时任务，点击右上角「新增任务」开始创建' }"
        >
          <template #bodyCell="{ column, record }">
            <!-- 任务名称 -->
            <template v-if="column.key === 'name'">
              <div class="task-name-cell">
                <span class="task-name-text">{{ record.name }}</span>
              </div>
            </template>

            <!-- Agent -->
            <template v-if="column.key === 'agentType'">
              <span class="agent-tag">
                {{ agentTypeLabel(record) }}
              </span>
            </template>

            <!-- 执行计划 -->
            <template v-if="column.key === 'schedule'">
              <div class="schedule-cell">
                <span v-if="record.cronExpression" class="schedule-cron">
                  <span class="schedule-icon cron-icon">⏱</span>
                  <code class="cron-text">{{ record.cronExpression }}</code>
                </span>
                <span v-else-if="record.executeTime" class="schedule-once">
                  <span class="schedule-icon once-icon">📅</span>
                  {{ formatTime(record.executeTime) }}
                </span>
                <span v-else class="schedule-immediate">
                  <span class="schedule-icon immediate-icon">⚡</span>
                  立即执行
                </span>
              </div>
            </template>

            <!-- 状态 -->
            <template v-if="column.key === 'status'">
              <div class="status-cell">
                <a-switch
                  :checked="record.status === 'ENABLED'"
                  @change="(checked: boolean) => handleToggleStatus(record, checked)"
                  size="small"
                  :class="{ 'switch-on': record.status === 'ENABLED' }"
                />
                <span :class="['status-dot', record.status === 'ENABLED' ? 'dot-on' : 'dot-off']"></span>
                <span class="status-text" :class="record.status === 'ENABLED' ? 'text-on' : 'text-off'">
                  {{ record.status === 'ENABLED' ? '启用' : '禁用' }}
                </span>
              </div>
            </template>

            <!-- 执行次数 -->
            <template v-if="column.key === 'executeCount'">
              <div class="count-cell">
                <span class="count-current">{{ record.executeCount }}</span>
                <span class="count-sep">/</span>
                <span class="count-max">{{ record.maxExecuteCount === 0 ? '不限' : record.maxExecuteCount }}</span>
              </div>
            </template>

            <!-- 上次执行 -->
            <template v-if="column.key === 'lastExecuteTime'">
              <span class="time-cell">{{ record.lastExecuteTime ? formatTime(record.lastExecuteTime) : '-' }}</span>
            </template>

            <!-- 操作 -->
            <template v-if="column.key === 'action'">
              <div class="action-cell">
                <a-button type="link" size="small" class="action-btn" @click="openEditModal(record)">编辑</a-button>
                <a-button
                  v-if="record.lastConversationId"
                  type="link"
                  size="small"
                  class="action-btn"
                  @click="viewConversation(record)"
                >查看会话</a-button>
                <a-popconfirm title="确定删除该定时任务？" @confirm="handleDelete(record.id)">
                  <a-button type="link" danger size="small" class="action-btn action-btn--danger">删除</a-button>
                </a-popconfirm>
              </div>
            </template>
          </template>
        </a-table>
      </div>
    </div>

    <!-- 新增/编辑弹窗 -->
    <a-modal
      v-model:open="modalVisible"
      :title="isEditing ? '编辑定时任务' : '新增定时任务'"
      @ok="handleModalOk"
      :confirmLoading="modalLoading"
      width="600px"
      :bodyStyle="{ padding: '24px' }"
      wrapClassName="schedule-modal"
    >
      <a-form :model="formData" :label-col="{ span: 5 }" :wrapper-col="{ span: 18 }" class="schedule-form">
        <a-form-item label="任务名称" required>
          <a-input v-model:value="formData.name" placeholder="如：每日代码审查" />
        </a-form-item>
        <a-form-item label="Agent">
          <a-select v-model:value="formData.agentConfigId" placeholder="选择 Agent" @change="onAgentSelect">
            <a-select-option v-for="a in agentList" :key="a.id" :value="a.id">{{ a.avatar }} {{ a.name }}</a-select-option>
          </a-select>
        </a-form-item>
        <a-form-item label="执行方式">
          <a-radio-group v-model:value="scheduleType" class="schedule-radio-group">
            <a-radio-button value="once">
              <span class="radio-label">📅 一次性</span>
            </a-radio-button>
            <a-radio-button value="cron">
              <span class="radio-label">⏱ 周期（Cron）</span>
            </a-radio-button>
          </a-radio-group>
        </a-form-item>
        <a-form-item v-if="scheduleType === 'once'" label="执行时间">
          <a-date-picker
            v-model:value="executeDate"
            show-time
            value-format="YYYY-MM-DD HH:mm:ss"
            style="width: 100%"
            placeholder="选择执行时间"
          />
        </a-form-item>
        <a-form-item v-if="scheduleType === 'cron'" label="Cron 表达式">
          <a-input v-model:value="formData.cronExpression" placeholder="0 0 9 * * ?（每天9点）">
            <template #suffix>
              <a-tooltip title="支持标准 Cron 表达式：秒 分 时 日 月 周">
                <QuestionCircleOutlined style="color: #8c8c8c; cursor: help" />
              </a-tooltip>
            </template>
          </a-input>
        </a-form-item>
        <a-form-item label="任务指令" required>
          <a-textarea
            v-model:value="formData.instruction"
            placeholder="告诉 Agent 做什么..."
            :rows="4"
            :maxLength="2000"
            showCount
          />
        </a-form-item>
        <a-form-item label="最大执行次数">
          <a-input-number v-model:value="formData.maxExecuteCount" :min="0" :max="9999" style="width: 130px" />
          <span class="form-hint">设为 0 表示不限制</span>
        </a-form-item>
      </a-form>
    </a-modal>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import { message } from 'ant-design-vue'
import {
  PlusOutlined,
  ClockCircleOutlined,
  CheckCircleOutlined,
  PauseCircleOutlined,
  FireOutlined,
  QuestionCircleOutlined
} from '@ant-design/icons-vue'
import { useRouter } from 'vue-router'
import {
  getTaskList, createTask, updateTask, deleteTask,
  enableTask, disableTask,
  type ScheduleTaskItem, type CreateTaskParams
} from '@/api/schedule-task'

import { listAgentConfigs, type AgentConfig } from '@/api/agent-config'

const router = useRouter()
const agentList = ref<AgentConfig[]>([])
const filterAgentId = ref<number | undefined>()
const taskList = ref<ScheduleTaskItem[]>([])
const filteredTaskList = computed(() => {
  if (!filterAgentId.value) return taskList.value
  return taskList.value.filter(t => t.agentConfigId === filterAgentId.value)
})
const loading = ref(false)

// 统计数据
const enabledCount = computed(() => taskList.value.filter(t => t.status === 'ENABLED').length)
const disabledCount = computed(() => taskList.value.filter(t => t.status === 'DISABLED').length)
const todayExecuteCount = computed(() => {
  const today = new Date().toDateString()
  return taskList.value.filter(t => {
    if (!t.lastExecuteTime) return false
    return new Date(t.lastExecuteTime).toDateString() === today
  }).length
})

const columns = [
  { title: '任务名称', dataIndex: 'name', key: 'name', width: 160 },
  { title: 'Agent', key: 'agentType', width: 120 },
  { title: '执行计划', key: 'schedule', width: 180 },
  { title: '状态', key: 'status', width: 100 },
  { title: '已执行 / 上限', key: 'executeCount', width: 120 },
  { title: '上次执行', key: 'lastExecuteTime', width: 160 },
  { title: '操作', key: 'action', width: 200, fixed: 'right' as const }
]

const agentTypeLabel = (record: ScheduleTaskItem) => {
  if (record.agentConfigId) {
    const a = agentList.value.find(x => x.id === record.agentConfigId)
    if (a) return (a.avatar || '') + ' ' + a.name
  }
  return (record as any).agentType || '编码助手'
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
  agentConfigId: undefined as number | undefined,
  instruction: '',
  cronExpression: '',
  maxExecuteCount: 0
})

const onAgentSelect = () => {}

const resetForm = () => {
  formData.value = { name: '', agentConfigId: undefined, instruction: '', cronExpression: '', maxExecuteCount: 0 }
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
    agentConfigId: item.agentConfigId,
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
      agentType: 'code_assistant',
      agentConfigId: formData.value.agentConfigId,
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

onMounted(async () => {
  try { const res = await listAgentConfigs(); if (res.code === 200) agentList.value = res.data } catch {}
  loadList()
})
</script>

<style scoped>
/* ===== 页面容器 ===== */
.schedule-container {
  padding: 24px;
  height: 100%;
  background: #f7f9fc;
  overflow-y: auto;
  display: flex;
  flex-direction: column;
  gap: 16px;
}

/* ===== 统计卡片区 ===== */
.stats-row {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 16px;
}

.stat-card {
  display: flex;
  align-items: center;
  gap: 14px;
  padding: 18px 20px;
  background: #fff;
  border-radius: 10px;
  border: 1px solid #eef2f7;
  box-shadow: 0 1px 2px rgba(0, 0, 0, 0.04);
  transition: all 0.2s ease;
  cursor: default;
}
.stat-card:hover {
  transform: translateY(-1px);
  box-shadow: 0 4px 12px rgba(0, 0, 0, 0.08);
}

.stat-icon {
  width: 44px;
  height: 44px;
  border-radius: 10px;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 20px;
  flex-shrink: 0;
}

.stat-card--total .stat-icon {
  background: #f0f5ff;
  color: #1677ff;
}
.stat-card--enabled .stat-icon {
  background: #f6ffed;
  color: #52c41a;
}
.stat-card--disabled .stat-icon {
  background: #f5f5f5;
  color: #8c8c8c;
}
.stat-card--today .stat-icon {
  background: #fff7e6;
  color: #fa8c16;
}

.stat-info {
  display: flex;
  flex-direction: column;
  gap: 2px;
}
.stat-value {
  font-size: 26px;
  font-weight: 700;
  color: #1a202c;
  line-height: 1.2;
  font-variant-numeric: tabular-nums;
}
.stat-label {
  font-size: 13px;
  color: #8c8c8c;
  font-weight: 400;
}

/* ===== 主内容卡片 ===== */
.content-card {
  flex: 1;
  background: #fff;
  border-radius: 10px;
  border: 1px solid #eef2f7;
  box-shadow: 0 1px 2px rgba(0, 0, 0, 0.04);
  display: flex;
  flex-direction: column;
  overflow: hidden;
}

.card-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 16px 20px;
  border-bottom: 1px solid #f0f0f0;
  flex-shrink: 0;
}

.card-header-left {
  display: flex;
  align-items: baseline;
  gap: 12px;
}

.card-title {
  margin: 0;
  font-size: 17px;
  font-weight: 600;
  color: #1a202c;
}

.card-subtitle {
  font-size: 13px;
  color: #8c8c8c;
}

.card-header-right {
  display: flex;
  align-items: center;
  gap: 10px;
}

.filter-select {
  border-radius: 6px;
}

.card-body {
  flex: 1;
  overflow: auto;
  padding: 0;
}

/* ===== 表格样式优化 ===== */
:deep(.ant-table) {
  font-size: 13px;
}

:deep(.ant-table-thead > tr > th) {
  background: #fafbfc;
  color: #595959;
  font-weight: 600;
  font-size: 12px;
  text-transform: uppercase;
  letter-spacing: 0.3px;
  border-bottom: 1px solid #f0f0f0;
  padding: 12px 16px;
}

:deep(.ant-table-tbody > tr > td) {
  padding: 12px 16px;
  border-bottom: 1px solid #f5f5f5;
}

:deep(.ant-table-tbody > tr:hover > td) {
  background: #fafbfc !important;
}

:deep(.ant-table-tbody > tr:last-child > td) {
  border-bottom: none;
}

:deep(.ant-pagination) {
  padding: 12px 20px;
}

/* ===== 任务名称 ===== */
.task-name-cell {
  display: flex;
  align-items: center;
}
.task-name-text {
  font-weight: 500;
  color: #1a202c;
}

/* ===== Agent 标签 ===== */
.agent-tag {
  display: inline-block;
  padding: 2px 10px;
  background: #f0f5ff;
  color: #1677ff;
  border-radius: 12px;
  font-size: 12px;
  font-weight: 500;
}

/* ===== 执行计划 ===== */
.schedule-cell {
  display: flex;
  align-items: center;
}

.schedule-icon {
  margin-right: 6px;
  font-size: 14px;
}

.cron-text {
  font-family: 'SF Mono', 'Monaco', 'Consolas', monospace;
  font-size: 12px;
  background: #f6f8fa;
  padding: 2px 8px;
  border-radius: 4px;
  color: #0550ae;
  border: 1px solid #e1e4e8;
}

.schedule-once {
  color: #595959;
  font-size: 13px;
}

.schedule-immediate {
  color: #fa8c16;
  font-size: 13px;
  font-weight: 500;
}

/* ===== 状态 ===== */
.status-cell {
  display: flex;
  align-items: center;
  gap: 6px;
}

.status-dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  flex-shrink: 0;
}
.dot-on {
  background: #52c41a;
  box-shadow: 0 0 4px rgba(82, 196, 26, 0.5);
}
.dot-off {
  background: #d9d9d9;
}

.status-text {
  font-size: 12px;
  font-weight: 500;
}
.text-on { color: #52c41a; }
.text-off { color: #8c8c8c; }

/* ===== 执行次数 ===== */
.count-cell {
  display: flex;
  align-items: center;
  gap: 2px;
  font-variant-numeric: tabular-nums;
}
.count-current {
  font-weight: 600;
  color: #1677ff;
  font-size: 14px;
}
.count-sep {
  color: #d9d9d9;
  margin: 0 2px;
}
.count-max {
  color: #8c8c8c;
  font-size: 12px;
}

/* ===== 上次执行 ===== */
.time-cell {
  color: #595959;
  font-size: 13px;
}

/* ===== 操作按钮 ===== */
.action-cell {
  display: flex;
  align-items: center;
  gap: 2px;
}

.action-btn {
  font-size: 12px !important;
  padding: 0 8px !important;
  height: 28px !important;
  border-radius: 4px !important;
  transition: all 0.15s !important;
}
.action-btn:hover {
  background: #f0f5ff !important;
}
.action-btn--danger:hover {
  background: #fff2f0 !important;
}

/* ===== 弹窗样式 ===== */
.schedule-form .ant-form-item {
  margin-bottom: 18px;
}

.schedule-radio-group {
  display: flex;
  gap: 0;
}
.schedule-radio-group :deep(.ant-radio-button-wrapper) {
  height: 36px;
  line-height: 34px;
  padding: 0 20px;
  font-size: 13px;
  border-radius: 0;
}

.radio-label {
  display: inline-flex;
  align-items: center;
  gap: 4px;
}

.form-hint {
  margin-left: 10px;
  color: #8c8c8c;
  font-size: 12px;
}

/* ===== 弹窗全局样式 ===== */
:deep(.schedule-modal .ant-modal-header) {
  border-bottom: 1px solid #f0f0f0;
  padding: 16px 24px;
}
:deep(.schedule-modal .ant-modal-title) {
  font-size: 16px;
  font-weight: 600;
  color: #1a202c;
}
:deep(.schedule-modal .ant-modal-footer) {
  border-top: 1px solid #f0f0f0;
  padding: 12px 24px;
}

/* ===== 响应式 ===== */
@media (max-width: 1024px) {
  .stats-row {
    grid-template-columns: repeat(2, 1fr);
  }
  .card-header {
    flex-direction: column;
    align-items: flex-start;
    gap: 12px;
  }
  .card-header-right {
    width: 100%;
    flex-wrap: wrap;
  }
}

@media (max-width: 640px) {
  .stats-row {
    grid-template-columns: 1fr;
  }
  .schedule-container {
    padding: 12px;
    gap: 12px;
  }
}

/* ===== 暗色模式 ===== */
[data-theme="dark"] .schedule-container {
  background: #121418;
}
/* 统计卡片 */
[data-theme="dark"] .stat-card {
  background: #1a1d22;
  border-color: #2a2d33;
}
[data-theme="dark"] .stat-card--total .stat-icon {
  background: rgba(22, 119, 255, 0.12);
  color: #5ba0ff;
}
[data-theme="dark"] .stat-card--enabled .stat-icon {
  background: rgba(82, 196, 26, 0.12);
  color: #73d13d;
}
[data-theme="dark"] .stat-card--disabled .stat-icon {
  background: rgba(140, 140, 140, 0.12);
  color: #a0a0a0;
}
[data-theme="dark"] .stat-card--today .stat-icon {
  background: rgba(250, 140, 22, 0.12);
  color: #ffa940;
}
[data-theme="dark"] .stat-value {
  color: #e4e6ea;
}
[data-theme="dark"] .stat-label {
  color: #8b8f98;
}
/* 主内容卡片 */
[data-theme="dark"] .content-card {
  background: #1a1d22;
  border-color: #2a2d33;
}
[data-theme="dark"] .card-header {
  border-bottom-color: #2a2d33;
}
[data-theme="dark"] .card-title {
  color: #e4e6ea;
}
[data-theme="dark"] .card-subtitle {
  color: #8b8f98;
}
/* 表格 */
[data-theme="dark"] :deep(.ant-table) {
  background: #1a1d22 !important;
  color: #e4e6ea !important;
}
[data-theme="dark"] :deep(.ant-table-thead > tr > th) {
  background: #1e2126 !important;
  color: #8b8f98 !important;
  border-bottom-color: #2a2d33 !important;
}
[data-theme="dark"] :deep(.ant-table-tbody > tr > td) {
  border-bottom-color: #2a2d33 !important;
  color: #e4e6ea !important;
  background: #1a1d22 !important;
}
[data-theme="dark"] :deep(.ant-table-tbody > tr:hover > td) {
  background: #1e2126 !important;
}
[data-theme="dark"] :deep(.ant-table-tbody > tr:nth-child(even) > td) {
  background: #1a1d22 !important;
}
[data-theme="dark"] :deep(.ant-empty-description) {
  color: #8b8f98;
}
/* 任务名称 */
[data-theme="dark"] .task-name-text {
  color: #e4e6ea;
}
/* Agent 标签 */
[data-theme="dark"] .agent-tag {
  background: rgba(22, 119, 255, 0.12);
  color: #5ba0ff;
}
/* 执行计划 */
[data-theme="dark"] .cron-text {
  background: #1e2126;
  color: #7eb8ff;
  border-color: #2a2d33;
}
[data-theme="dark"] .schedule-once {
  color: #b0b5bd;
}
[data-theme="dark"] .schedule-immediate {
  color: #ffa940;
}
/* 状态 */
[data-theme="dark"] .dot-off {
  background: #4a4d55;
}
[data-theme="dark"] .text-off {
  color: #8b8f98;
}
/* 执行次数 */
[data-theme="dark"] .count-sep {
  color: #4a4d55;
}
[data-theme="dark"] .count-max {
  color: #8b8f98;
}
/* 上次执行 */
[data-theme="dark"] .time-cell {
  color: #b0b5bd;
}
/* 操作按钮 */
[data-theme="dark"] .action-btn:hover {
  background: rgba(22, 119, 255, 0.1) !important;
}
[data-theme="dark"] .action-btn--danger:hover {
  background: rgba(255, 77, 79, 0.1) !important;
}
/* 弹窗 */
[data-theme="dark"] :deep(.schedule-modal .ant-modal-header) {
  border-bottom-color: #2a2d33;
}
[data-theme="dark"] :deep(.schedule-modal .ant-modal-title) {
  color: #e4e6ea;
}
[data-theme="dark"] :deep(.schedule-modal .ant-modal-footer) {
  border-top-color: #2a2d33;
}
[data-theme="dark"] :deep(.ant-modal-content) {
  background: #1a1d22;
}
[data-theme="dark"] .form-hint {
  color: #8b8f98;
}
/* Ant Design 组件穿透 */
[data-theme="dark"] :deep(.ant-input) {
  background: #141619;
  border-color: #2a2d33;
  color: #e4e6ea;
}
[data-theme="dark"] :deep(.ant-input-number) {
  background: #141619;
  border-color: #2a2d33;
  color: #e4e6ea;
}
[data-theme="dark"] :deep(.ant-select-selector) {
  background: #141619 !important;
  border-color: #2a2d33 !important;
  color: #e4e6ea !important;
}
[data-theme="dark"] :deep(.ant-select-dropdown) {
  background: #1a1d22;
}
[data-theme="dark"] :deep(.ant-select-item) {
  color: #e4e6ea;
}
[data-theme="dark"] :deep(.ant-select-item-option-selected) {
  background: rgba(22, 119, 255, 0.12);
}
[data-theme="dark"] :deep(.ant-pagination-item) {
  background: #1a1d22;
  border-color: #2a2d33;
}
[data-theme="dark"] :deep(.ant-pagination-item a) {
  color: #e4e6ea;
}
[data-theme="dark"] :deep(.ant-pagination-item-active) {
  border-color: #1677ff;
}
[data-theme="dark"] :deep(.ant-btn) {
  background: #1e2126;
  border-color: #2a2d33;
  color: #e4e6ea;
}
[data-theme="dark"] :deep(.ant-btn-primary) {
  background: #1677ff;
  border-color: #1677ff;
  color: #fff;
}
[data-theme="dark"] :deep(.ant-btn-link) {
  background: transparent;
  border-color: transparent;
}
[data-theme="dark"] :deep(.ant-switch) {
  background: #3a3d44;
}
[data-theme="dark"] :deep(.ant-switch-checked) {
  background: #52c41a;
}
[data-theme="dark"] :deep(.ant-radio-button-wrapper) {
  background: #1e2126;
  border-color: #2a2d33;
  color: #8b8f98;
}
[data-theme="dark"] :deep(.ant-radio-button-wrapper-checked) {
  background: rgba(22, 119, 255, 0.15);
  border-color: #1677ff;
  color: #5ba0ff;
}
[data-theme="dark"] :deep(.ant-popover-inner) {
  background: #1a1d22;
  color: #e4e6ea;
}
[data-theme="dark"] :deep(.ant-popconfirm .ant-popover-message-title) {
  color: #e4e6ea;
}
[data-theme="dark"] :deep(.ant-form-item-label > label) {
  color: #e4e6ea;
}
[data-theme="dark"] :deep(.ant-date-picker) {
  background: #141619;
  border-color: #2a2d33;
  color: #e4e6ea;
}
[data-theme="dark"] :deep(.ant-date-picker input) {
  color: #e4e6ea;
}
</style>

<!-- 暗色模式 — 表格全局强制覆盖（非 scoped，直接对抗 CSS-in-JS） -->
<style>
[data-theme="dark"] .ant-table {
  background: #1a1d22 !important;
  color: #e4e6ea !important;
}
[data-theme="dark"] .ant-table-thead > tr > th {
  background: #1e2126 !important;
  color: #8b8f98 !important;
  border-bottom: 1px solid #2a2d33 !important;
}
[data-theme="dark"] .ant-table-tbody > tr > td {
  background: #1a1d22 !important;
  border-bottom: 1px solid #2a2d33 !important;
  color: #e4e6ea !important;
}
[data-theme="dark"] .ant-table-tbody > tr:hover > td {
  background: #1e2126 !important;
}
[data-theme="dark"] .ant-table-tbody > tr:nth-child(even) > td {
  background: #1d2025 !important;
}
[data-theme="dark"] .ant-table-cell-row-hover {
  background: #1e2126 !important;
}
[data-theme="dark"] .ant-pagination-item {
  background: #141619 !important;
  border-color: #2a2d33 !important;
}
[data-theme="dark"] .ant-pagination-item a {
  color: #8b8f98 !important;
}
[data-theme="dark"] .ant-pagination-item-active {
  background: #4f7df3 !important;
}
[data-theme="dark"] .ant-pagination-item-active a {
  color: #fff !important;
}
[data-theme="dark"] .ant-pagination-prev .ant-pagination-item-link,
[data-theme="dark"] .ant-pagination-next .ant-pagination-item-link {
  background: #141619 !important;
  border-color: #2a2d33 !important;
  color: #8b8f98 !important;
}
[data-theme="dark"] .ant-empty-description {
  color: #8b8f98 !important;
}
</style>
