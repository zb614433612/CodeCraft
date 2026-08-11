<template>
  <div class="lesson-manage">
    <!-- ===== 头部卡片 ===== -->
    <div class="page-header">
      <div class="header-left">
        <div class="header-icon-box">
          <span class="header-icon">📚</span>
        </div>
        <div class="header-text">
          <h2 class="header-title">踩坑经验</h2>
          <p class="header-subtitle">成长体系：管理 AI 执行过程中沉淀的失败经验与解法，按项目隔离、按需检索</p>
        </div>
      </div>
      <div class="header-right">
        <div class="stat-item">
          <span class="stat-num">{{ stats.total ?? 0 }}</span>
          <span class="stat-label">经验总数</span>
        </div>
        <div class="stat-divider"></div>
        <div class="stat-item">
          <span class="stat-num" style="color: #52c41a">{{ stats.activeCount ?? 0 }}</span>
          <span class="stat-label">已验证</span>
        </div>
        <div class="stat-divider"></div>
        <div class="stat-item">
          <span class="stat-num" style="color: #faad14">{{ stats.draftCount ?? 0 }}</span>
          <span class="stat-label">草稿中</span>
        </div>
      </div>
    </div>

    <!-- ===== 统计看板 ===== -->
    <div class="stats-grid">
      <div class="stat-card stat-card--total">
        <div class="stat-card-icon">🗂️</div>
        <div class="stat-card-body">
          <span class="stat-card-num">{{ stats.total ?? 0 }}</span>
          <span class="stat-card-label">总经验</span>
        </div>
      </div>
      <div class="stat-card stat-card--active">
        <div class="stat-card-icon">✅</div>
        <div class="stat-card-body">
          <span class="stat-card-num">{{ stats.activeCount ?? 0 }}</span>
          <span class="stat-card-label">有效经验</span>
        </div>
      </div>
      <div class="stat-card stat-card--draft">
        <div class="stat-card-icon">📝</div>
        <div class="stat-card-body">
          <span class="stat-card-num">{{ stats.draftCount ?? 0 }}</span>
          <span class="stat-card-label">草稿待验证</span>
        </div>
      </div>
      <div class="stat-card stat-card--hidden">
        <div class="stat-card-icon">🙈</div>
        <div class="stat-card-body">
          <span class="stat-card-num">{{ stats.hiddenCount ?? 0 }}</span>
          <span class="stat-card-label">已隐藏</span>
        </div>
      </div>
      <div class="stat-card stat-card--hit">
        <div class="stat-card-icon">🎯</div>
        <div class="stat-card-body">
          <span class="stat-card-num">{{ stats.totalHits ?? 0 }}</span>
          <span class="stat-card-label">累计命中</span>
        </div>
      </div>
      <div class="stat-card stat-card--rate">
        <div class="stat-card-icon">📈</div>
        <div class="stat-card-body">
          <span class="stat-card-num">{{ stats.successRate ?? 0 }}<span class="stat-card-unit">%</span></span>
          <span class="stat-card-label">验证成功率</span>
        </div>
      </div>
      <div class="stat-card stat-card--recent">
        <div class="stat-card-icon">🆕</div>
        <div class="stat-card-body">
          <span class="stat-card-num">{{ stats.recent7d ?? 0 }}</span>
          <span class="stat-card-label">近7天新增</span>
        </div>
      </div>
      <div class="stat-card stat-card--source">
        <div class="stat-card-icon">🌱</div>
        <div class="stat-card-body">
          <span class="stat-card-num">{{ (stats.autoCount ?? 0) + (stats.llmCount ?? 0) + (stats.manualCount ?? 0) }}</span>
          <span class="stat-card-label">
            自动{{ stats.autoCount ?? 0 }} / LLM{{ stats.llmCount ?? 0 }} / 人工{{ stats.manualCount ?? 0 }}
          </span>
        </div>
      </div>
    </div>

    <!-- ===== 工具栏 ===== -->
    <div class="toolbar-card">
      <div class="toolbar-left">
        <a-input
          v-model:value="query.projectKey"
          placeholder="项目 Key（留空=全部项目）"
          class="toolbar-input toolbar-input--project"
          @press-enter="handleSearch"
          allow-clear
        />
        <a-select
          v-model:value="query.status"
          placeholder="全部状态"
          allow-clear
          style="width: 130px"
          @change="handleSearch"
        >
          <a-select-option :value="0">草稿</a-select-option>
          <a-select-option :value="1">有效</a-select-option>
          <a-select-option :value="2">隐藏</a-select-option>
        </a-select>
        <a-input
          v-model:value="query.toolName"
          placeholder="工具名（如 command）"
          class="toolbar-input"
          @press-enter="handleSearch"
          allow-clear
        />
        <a-input
          v-model:value="query.errorCode"
          placeholder="错误码（如 NoClassDefFoundError）"
          class="toolbar-input toolbar-input--code"
          @press-enter="handleSearch"
          allow-clear
        />
        <a-button type="primary" @click="handleSearch">
          <template #icon><SearchOutlined /></template>
          查询
        </a-button>
        <a-button @click="handleReset">
          <template #icon><ReloadOutlined /></template>
          重置
        </a-button>
      </div>
      <div class="toolbar-right">
        <a-button @click="handleRefresh" :loading="loading">
          <template #icon><SyncOutlined /></template>
          刷新
        </a-button>
      </div>
    </div>

    <!-- ===== 内容区 ===== -->
    <div class="content-card">
      <a-table
        :columns="columns"
        :data-source="lessons"
        :loading="loading"
        :pagination="pagination"
        row-key="id"
        size="middle"
        @change="handleTableChange"
        :scroll="{ x: 1100 }"
      >
        <!-- 类别 -->
        <template #bodyCell="{ column, record }">
          <template v-if="column.key === 'errorCategory'">
            <a-tag :color="categoryColor(record.errorCategory)">{{ record.errorCategory }}</a-tag>
          </template>
          <!-- 状态 -->
          <template v-else-if="column.key === 'status'">
            <a-tag :color="statusColor(record.status)">
              {{ statusText(record.status) }}
            </a-tag>
          </template>
          <!-- 现象 -->
          <template v-else-if="column.key === 'symptom'">
            <span class="cell-ellipsis" :title="record.symptom">{{ record.symptom || '-' }}</span>
          </template>
          <!-- 解法 -->
          <template v-else-if="column.key === 'solution'">
            <span class="cell-ellipsis cell-solution" :title="record.solution">{{ record.solution || '-' }}</span>
          </template>
          <!-- 统计 -->
          <template v-else-if="column.key === 'stats'">
            <span class="stats-inline">
              <span class="stats-hit" title="命中次数">🎯{{ record.hitCount ?? 0 }}</span>
              <span class="stats-ok" title="有效次数">✅{{ record.successCount ?? 0 }}</span>
              <span class="stats-fail" title="失败次数">❌{{ record.failCount ?? 0 }}</span>
            </span>
          </template>
          <!-- 来源 -->
          <template v-else-if="column.key === 'source'">
            <a-tag :color="sourceColor(record.source)">{{ sourceText(record.source) }}</a-tag>
          </template>
          <!-- 操作 -->
          <template v-else-if="column.key === 'action'">
            <span class="action-btns">
              <a-button type="link" size="small" @click="openDetail(record)">详情</a-button>
              <a-button type="link" size="small" @click="openEdit(record)">编辑</a-button>
              <a-popconfirm title="确认该经验验证有效？" @confirm="handleFeedback(record, true)" ok-text="确认" cancel-text="取消">
                <a-button type="link" size="small" class="action-ok">有效</a-button>
              </a-popconfirm>
              <a-popconfirm title="确认该经验验证无效？（连续 5 次失败将自动隐藏）" @confirm="handleFeedback(record, false)" ok-text="确认" cancel-text="取消">
                <a-button type="link" size="small" class="action-fail">无效</a-button>
              </a-popconfirm>
              <a-popconfirm title="确定删除该经验？此操作不可撤销" @confirm="handleDelete(record.id)" ok-text="确定" cancel-text="取消" ok-type="danger">
                <a-button type="link" size="small" danger>删除</a-button>
              </a-popconfirm>
            </span>
          </template>
        </template>
        <!-- 空状态 -->
        <template #emptyText>
          <div class="empty-state">
            <div class="empty-illustration"><span class="empty-icon">📭</span></div>
            <p class="empty-title">暂无踩坑经验</p>
            <p class="empty-desc">工具执行失败时会自动捕获草稿经验，也可通过 LLM 的 lesson 工具主动记录</p>
          </div>
        </template>
      </a-table>
    </div>

    <!-- ===== 详情抽屉 ===== -->
    <a-drawer v-model:open="detailVisible" title="经验详情" :width="560">
      <a-descriptions v-if="detail" :column="1" bordered size="small">
        <a-descriptions-item label="ID">{{ detail.id }}</a-descriptions-item>
        <a-descriptions-item label="项目">{{ detail.projectKey }}</a-descriptions-item>
        <a-descriptions-item label="工具">{{ detail.toolName }}</a-descriptions-item>
        <a-descriptions-item label="类别">
          <a-tag :color="categoryColor(detail.errorCategory)">{{ detail.errorCategory }}</a-tag>
          <a-tag color="blue">{{ detail.errorCode }}</a-tag>
        </a-descriptions-item>
        <a-descriptions-item label="状态">
          <a-tag :color="statusColor(detail.status)">{{ statusText(detail.status) }}</a-tag>
          <a-tag :color="sourceColor(detail.source)">{{ sourceText(detail.source) }}</a-tag>
        </a-descriptions-item>
        <a-descriptions-item label="现象">{{ detail.symptom || '-' }}</a-descriptions-item>
        <a-descriptions-item label="根因">{{ detail.rootCause || '-' }}</a-descriptions-item>
        <a-descriptions-item label="解法">{{ detail.solution || '-' }}</a-descriptions-item>
        <a-descriptions-item label="关键参数">
          <pre class="detail-params">{{ formatParams(detail.paramsJson) }}</pre>
        </a-descriptions-item>
        <a-descriptions-item label="适用条件">{{ detail.applicableCond || '-' }}</a-descriptions-item>
        <a-descriptions-item label="关键词">{{ detail.keywords || '-' }}</a-descriptions-item>
        <a-descriptions-item label="指纹">{{ detail.errorSignature || '-' }}</a-descriptions-item>
        <a-descriptions-item label="统计">
          🎯命中 {{ detail.hitCount ?? 0 }} 次 ｜ ✅有效 {{ detail.successCount ?? 0 }} 次 ｜ ❌失败 {{ detail.failCount ?? 0 }} 次
        </a-descriptions-item>
        <a-descriptions-item label="创建时间">{{ formatTime(detail.createdAt) }}</a-descriptions-item>
        <a-descriptions-item label="更新时间">{{ formatTime(detail.updatedAt) }}</a-descriptions-item>
      </a-descriptions>
    </a-drawer>

    <!-- ===== 编辑弹窗 ===== -->
    <a-modal
      v-model:open="editVisible"
      title="编辑踩坑经验"
      :confirm-loading="saving"
      @ok="handleSave"
      @cancel="editVisible = false"
      :width="720"
      ok-text="保存"
      cancel-text="取消"
    >
      <a-form ref="editFormRef" :model="editForm" :label-col="{ span: 4 }" :wrapper-col="{ span: 20 }" layout="horizontal">
        <a-form-item label="根因">
          <a-textarea v-model:value="editForm.rootCause" :rows="2" placeholder="根因分析（留空不更新）" />
        </a-form-item>
        <a-form-item label="解法">
          <a-textarea v-model:value="editForm.solution" :rows="3" placeholder="解法，支持 {变量} 占位符（留空不更新）" />
        </a-form-item>
        <a-form-item label="关键参数">
          <a-textarea v-model:value="editForm.paramsJson" :rows="3" placeholder='JSON 数组，如 [{"name":"缺失依赖","value":"starter-web"}]' />
        </a-form-item>
        <a-form-item label="适用条件">
          <a-input v-model:value="editForm.applicableCond" placeholder="何时适用/不适用（留空不更新）" />
        </a-form-item>
        <a-form-item label="关键词">
          <a-input v-model:value="editForm.keywords" placeholder="空格分隔的检索标签（留空不更新）" />
        </a-form-item>
      </a-form>
    </a-modal>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, onMounted, computed } from 'vue'
import { message } from 'ant-design-vue'
import { SearchOutlined, ReloadOutlined, SyncOutlined } from '@ant-design/icons-vue'
import {
  pageLessons,
  getLesson,
  getLessonStats,
  updateLesson,
  feedbackLesson,
  deleteLesson,
  type LessonData,
  type LessonStats
} from '@/api/lesson'

// ===== 状态 =====
const loading = ref(false)
const saving = ref(false)
const lessons = ref<LessonData[]>([])
const stats = ref<LessonStats>({} as LessonStats)
const total = ref(0)

const query = reactive({
  projectKey: '',
  status: undefined as number | undefined,
  toolName: '',
  errorCode: ''
})

const pagination = computed(() => ({
  current: page.value,
  pageSize: size.value,
  total: total.value,
  showSizeChanger: true,
  showTotal: (t: number) => `共 ${t} 条`
}))

const page = ref(1)
const size = ref(10)

// 详情/编辑
const detailVisible = ref(false)
const detail = ref<LessonData | null>(null)
const editVisible = ref(false)
const editForm = reactive({
  rootCause: '',
  solution: '',
  paramsJson: '',
  applicableCond: '',
  keywords: ''
})
const editingId = ref<number | null>(null)

// ===== 列定义 =====
const columns = [
  { title: 'ID', dataIndex: 'id', key: 'id', width: 70 },
  { title: '类别', dataIndex: 'errorCategory', key: 'errorCategory', width: 110 },
  { title: '错误码', dataIndex: 'errorCode', key: 'errorCode', width: 170, ellipsis: true },
  { title: '工具', dataIndex: 'toolName', key: 'toolName', width: 100 },
  { title: '现象', dataIndex: 'symptom', key: 'symptom', width: 200 },
  { title: '解法', dataIndex: 'solution', key: 'solution', width: 220 },
  { title: '状态', dataIndex: 'status', key: 'status', width: 90 },
  { title: '统计', dataIndex: 'stats', key: 'stats', width: 120 },
  { title: '来源', dataIndex: 'source', key: 'source', width: 90 },
  { title: '更新时间', dataIndex: 'updatedAt', key: 'updatedAt', width: 150 },
  { title: '操作', dataIndex: 'action', key: 'action', width: 190, fixed: 'right' as const }
]

// ===== 加载 =====
async function loadStats() {
  try {
    const data = await getLessonStats(query.projectKey || undefined)
    stats.value = data.data ?? ({} as LessonStats)
  } catch (e) {
    // 统计失败不阻塞列表
    console.warn('加载统计失败', e)
  }
}

async function fetchList() {
  loading.value = true
  try {
    const data = await pageLessons({
      projectKey: query.projectKey || undefined,
      status: query.status,
      toolName: query.toolName || undefined,
      errorCode: query.errorCode || undefined,
      page: page.value,
      size: size.value
    })
    lessons.value = data.data?.items ?? []
    total.value = data.data?.total ?? 0
  } catch (e) {
    message.error('加载经验列表失败')
    console.error(e)
  } finally {
    loading.value = false
  }
}

function loadAll() {
  fetchList()
  loadStats()
}

onMounted(loadAll)

// ===== 操作 =====
function handleSearch() {
  page.value = 1
  loadAll()
}

function handleReset() {
  query.projectKey = ''
  query.status = undefined
  query.toolName = ''
  query.errorCode = ''
  page.value = 1
  loadAll()
}

function handleRefresh() {
  loadAll()
}

function handleTableChange(pag: any) {
  page.value = pag.current ?? 1
  size.value = pag.pageSize ?? 10
  fetchList()
}

async function openDetail(record: LessonData) {
  try {
    const data = await getLesson(record.id!)
    detail.value = data.data ?? null
    detailVisible.value = true
  } catch (e) {
    message.error('加载详情失败')
  }
}

function openEdit(record: LessonData) {
  editingId.value = record.id!
  editForm.rootCause = record.rootCause || ''
  editForm.solution = record.solution || ''
  editForm.paramsJson = record.paramsJson && record.paramsJson !== '[]' ? record.paramsJson : ''
  editForm.applicableCond = record.applicableCond || ''
  editForm.keywords = record.keywords || ''
  editVisible.value = true
}

async function handleSave() {
  if (editingId.value === null) return
  saving.value = true
  try {
    await updateLesson(editingId.value, {
      rootCause: editForm.rootCause,
      solution: editForm.solution,
      paramsJson: editForm.paramsJson,
      applicableCond: editForm.applicableCond,
      keywords: editForm.keywords
    })
    message.success('经验已更新')
    editVisible.value = false
    fetchList()
  } catch (e) {
    message.error('更新失败')
    console.error(e)
  } finally {
    saving.value = false
  }
}

async function handleFeedback(record: LessonData, effective: boolean) {
  try {
    const msg = await feedbackLesson(record.id!, effective)
    message.success(effective ? '已标记为有效' : '已标记为无效')
    fetchList()
    loadStats()
  } catch (e) {
    message.error('反馈失败')
    console.error(e)
  }
}

async function handleDelete(id: number) {
  try {
    await deleteLesson(id)
    message.success('经验已删除')
    fetchList()
    loadStats()
  } catch (e) {
    message.error('删除失败')
    console.error(e)
  }
}

// ===== 展示辅助 =====
function statusText(status?: number) {
  if (status === 1) return '有效'
  if (status === 2) return '隐藏'
  return '草稿'
}

function statusColor(status?: number) {
  if (status === 1) return 'green'
  if (status === 2) return 'red'
  return 'orange'
}

function categoryColor(category?: string) {
  const map: Record<string, string> = {
    COMPILE: 'blue',
    DEPENDENCY: 'purple',
    NETWORK: 'cyan',
    AUTH: 'red',
    MCP_HANDSHAKE: 'geekblue',
    SQL: 'gold',
    PARAM: 'magenta',
    ENV: 'lime',
    OTHER: 'default'
  }
  return map[category || 'OTHER'] || 'default'
}

function sourceText(source?: string) {
  if (source === 'llm') return 'LLM'
  if (source === 'manual') return '人工'
  return '自动'
}

function sourceColor(source?: string) {
  if (source === 'llm') return 'blue'
  if (source === 'manual') return 'purple'
  return 'cyan'
}

function formatTime(time?: string) {
  if (!time) return '-'
  return time.replace('T', ' ').substring(0, 19)
}

function formatParams(paramsJson?: string) {
  if (!paramsJson || paramsJson === '[]') return '-'
  try {
    const arr = JSON.parse(paramsJson)
    return arr.map((p: any) => `${p.name} = ${p.value}`).join('\n')
  } catch {
    return paramsJson
  }
}
</script>

<style scoped>
.lesson-manage {
  padding: 20px 24px;
  display: flex;
  flex-direction: column;
  gap: 16px;
  min-height: 100%;
}

/* ===== 头部 ===== */
.page-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
  border-radius: 12px;
  padding: 20px 24px;
  color: #fff;
  box-shadow: 0 4px 16px rgba(102, 126, 234, 0.25);
}
.header-left {
  display: flex;
  align-items: center;
  gap: 14px;
}
.header-icon-box {
  width: 52px;
  height: 52px;
  border-radius: 12px;
  background: rgba(255, 255, 255, 0.2);
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 26px;
}
.header-title {
  margin: 0;
  font-size: 20px;
  font-weight: 600;
}
.header-subtitle {
  margin: 4px 0 0;
  font-size: 13px;
  opacity: 0.85;
}
.header-right {
  display: flex;
  align-items: center;
  gap: 18px;
}
.stat-item {
  text-align: center;
}
.stat-num {
  display: block;
  font-size: 22px;
  font-weight: 700;
  line-height: 1.2;
}
.stat-label {
  font-size: 12px;
  opacity: 0.85;
}
.stat-divider {
  width: 1px;
  height: 32px;
  background: rgba(255, 255, 255, 0.3);
}

/* ===== 统计看板 ===== */
.stats-grid {
  display: grid;
  grid-template-columns: repeat(8, 1fr);
  gap: 12px;
}
.stat-card {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 14px 16px;
  border-radius: 10px;
  background: #fff;
  border: 1px solid #eef0f4;
  transition: transform 0.2s;
}
.stat-card:hover {
  transform: translateY(-2px);
}
.stat-card-icon {
  font-size: 22px;
}
.stat-card-num {
  font-size: 20px;
  font-weight: 700;
  line-height: 1.1;
}
.stat-card-unit {
  font-size: 12px;
  font-weight: 400;
  margin-left: 2px;
}
.stat-card-label {
  display: block;
  font-size: 11px;
  color: #8b8f98;
  margin-top: 2px;
}

/* ===== 工具栏 ===== */
.toolbar-card {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  background: #fff;
  border-radius: 10px;
  border: 1px solid #eef0f4;
  padding: 12px 16px;
  flex-wrap: wrap;
}
.toolbar-left {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}
.toolbar-input {
  width: 180px;
}
.toolbar-input--project {
  width: 140px;
}
.toolbar-input--code {
  width: 220px;
}

/* ===== 内容区 ===== */
.content-card {
  background: #fff;
  border-radius: 10px;
  border: 1px solid #eef0f4;
  padding: 4px;
  flex: 1;
}
.cell-ellipsis {
  display: inline-block;
  max-width: 100%;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  vertical-align: bottom;
}
.cell-solution {
  color: #1677ff;
}
.stats-inline {
  display: inline-flex;
  gap: 6px;
  font-size: 12px;
}
.stats-hit { color: #8b8f98; }
.stats-ok { color: #52c41a; }
.stats-fail { color: #ff4d4f; }
.action-btns {
  display: inline-flex;
  align-items: center;
  white-space: nowrap;
}
.action-ok { color: #52c41a !important; }
.action-fail { color: #faad14 !important; }

/* ===== 空状态 ===== */
.empty-state {
  padding: 40px 0;
  text-align: center;
}
.empty-illustration {
  width: 64px;
  height: 64px;
  margin: 0 auto 12px;
  border-radius: 50%;
  background: #f5f7fa;
  display: flex;
  align-items: center;
  justify-content: center;
}
.empty-icon {
  font-size: 30px;
}
.empty-title {
  font-size: 15px;
  font-weight: 600;
  margin: 0 0 4px;
}
.empty-desc {
  font-size: 13px;
  color: #8b8f98;
  margin: 0;
}

/* ===== 详情 ===== */
.detail-params {
  margin: 0;
  font-size: 12px;
  white-space: pre-wrap;
  word-break: break-all;
  background: #f5f7fa;
  border-radius: 6px;
  padding: 8px 10px;
}

/* ===== 深色主题 ===== */
[data-theme="dark"] .stat-card,
[data-theme="dark"] .toolbar-card,
[data-theme="dark"] .content-card {
  background: #1a1d22;
  border-color: #2a2d33;
}
[data-theme="dark"] .stat-card-label {
  color: #8b8f98;
}
[data-theme="dark"] .empty-illustration {
  background: #1e2126;
}
[data-theme="dark"] .detail-params {
  background: #141619;
}
[data-theme="dark"] .cell-ellipsis {
  color: #e4e6ea;
}

/* ===== 响应式 ===== */
@media (max-width: 1400px) {
  .stats-grid {
    grid-template-columns: repeat(4, 1fr);
  }
}
@media (max-width: 768px) {
  .stats-grid {
    grid-template-columns: repeat(2, 1fr);
  }
  .page-header {
    flex-direction: column;
    align-items: flex-start;
    gap: 12px;
  }
}
</style>
