<template>
  <a-drawer
    :open="open"
    :width="780"
    :title="drawerTitle"
    @close="handleClose"
  >
    <!-- 连接信息条 -->
    <div class="conn-bar">
      <a-tag v-if="connection" :color="typeColor(connection.dbType)">{{ connection.dbType }}</a-tag>
      <span class="conn-addr">{{ address }}</span>
      <a-tag v-if="connection && connection.enabled !== 1" color="default">已停用（无法执行）</a-tag>
    </div>

    <!-- 工具栏 -->
    <div class="toolbar">
      <span class="toolbar-label">最大行数</span>
      <a-select v-model:value="maxRows" size="small" style="width: 90px">
        <a-select-option :value="100">100</a-select-option>
        <a-select-option :value="200">200</a-select-option>
        <a-select-option :value="500">500</a-select-option>
        <a-select-option :value="1000">1000</a-select-option>
      </a-select>
      <a-button size="small" @click="clearAll">清空</a-button>
      <span class="shortcut-hint">Ctrl + Enter 执行 · 支持多条语句（分号分隔，按顺序执行）</span>
    </div>

    <!-- SQL 编辑区 -->
    <a-textarea
      v-model:value="sqlText"
      class="sql-editor"
      :rows="10"
      :disabled="disabled"
      :placeholder="placeholder"
      @keydown="onEditorKeydown"
    />

    <!-- 执行按钮 -->
    <div class="actions">
      <a-button type="primary" :loading="executing" :disabled="disabled" @click="handleExecute">
        <template #icon><PlayCircleOutlined /></template>
        执行
      </a-button>
    </div>

    <!-- 结果区 -->
    <div v-if="errorMsg" class="result-area">
      <a-alert type="error" show-icon :message="errorMsg" />
    </div>
    <div v-else-if="result" class="result-area">
      <div class="result-summary">
        共执行 {{ result.statementCount ?? 0 }} 条语句 · 总耗时 {{ result.elapsedMs ?? 0 }}ms
        <span v-if="result.dbLabel" class="result-db">· {{ result.dbLabel }}</span>
      </div>
      <div v-for="item in result.results || []" :key="item.index" class="stmt-block">
        <div class="stmt-header">
          <span class="stmt-title">#{{ item.index + 1 }}</span>
          <template v-if="item.sqlType === 'SELECT'">
            <a-tag color="blue">查询</a-tag>
            <span class="stmt-meta">
              返回 {{ item.totalRows ?? 0 }} 行
              <span v-if="item.truncated" class="truncated-tip">（已达行数上限，结果可能不完整）</span>
            </span>
          </template>
          <template v-else>
            <a-tag color="orange">写操作</a-tag>
            <span class="stmt-meta">影响 {{ item.affectedRows ?? 0 }} 行</span>
          </template>
        </div>
        <template v-if="item.sqlType === 'SELECT'">
          <a-table
            v-if="item.rows && item.rows.length"
            :data-source="item.rows"
            :columns="buildColumns(item)"
            :pagination="false"
            size="small"
            :scroll="{ x: 'max-content', y: 320 }"
            :row-key="rowKeyFn"
          />
          <a-empty v-else description="查询无结果" />
        </template>
      </div>
    </div>
  </a-drawer>
</template>

<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { message, Modal } from 'ant-design-vue'
import { PlayCircleOutlined } from '@ant-design/icons-vue'
import {
  executeDbConnectionSql,
  type DbConnectionVO,
  type SqlExecuteResult,
  type SqlStatementResult
} from '@/api/db-connection'

const props = defineProps<{
  open: boolean
  connection: DbConnectionVO | null
}>()

const emit = defineEmits<{ (e: 'update:open', value: boolean): void }>()

const sqlText = ref('')
const maxRows = ref(200)
const executing = ref(false)
const result = ref<SqlExecuteResult | null>(null)
const errorMsg = ref('')

const disabled = computed(() => !props.connection || props.connection.enabled !== 1)

const drawerTitle = computed(() =>
  props.connection ? `执行 SQL — ${props.connection.name}` : '执行 SQL'
)

const address = computed(() => {
  const c = props.connection
  if (!c) return ''
  if (c.dbType === 'h2') return c.databaseName || ''
  return `${c.host || ''}:${c.port || '默认'}/${c.databaseName || ''}`
})

const placeholder = `输入 SQL，支持多条语句（分号分隔，按顺序执行）
例如：
SELECT * FROM users WHERE id = 1;
INSERT INTO t (name) VALUES ('a');`

const typeColor = (t: string) => (t === 'mysql' ? 'blue' : t === 'postgresql' ? 'purple' : 'green')

const rowKeyFn = (_record: Record<string, any>, index: number) => index

// 打开时保留上次 SQL（便于调整重跑），清空上次执行结果
watch(
  () => props.open,
  (visible) => {
    if (visible) {
      result.value = null
      errorMsg.value = ''
    }
  }
)

const handleClose = () => emit('update:open', false)

const clearAll = () => {
  sqlText.value = ''
  result.value = null
  errorMsg.value = ''
}

/** 危险语句检测：去注释、去引号内容后匹配关键字（误报仅多一次确认，安全向） */
const DANGER_PATTERN = /\b(UPDATE|DELETE|DROP|TRUNCATE|ALTER|GRANT|REVOKE)\b/i
const detectDanger = (sql: string): string | null => {
  const noComment = sql.replace(/\/\*[\s\S]*?\*\//g, ' ').replace(/--[^\n]*\n?/g, ' ')
  const noLiteral = noComment.replace(/'[^']*'|"[^"]*"/g, "''")
  const m = noLiteral.match(DANGER_PATTERN)
  return m ? m[1].toUpperCase() : null
}

const doExecute = async () => {
  if (!props.connection) return
  const sql = sqlText.value.trim()
  if (!sql) {
    message.warning('请输入 SQL 语句')
    return
  }
  executing.value = true
  errorMsg.value = ''
  try {
    const res = await executeDbConnectionSql(props.connection.id, sql, maxRows.value)
    result.value = res.data || null
  } catch (e: any) {
    result.value = null
    errorMsg.value = e?.message || '执行失败'
  } finally {
    executing.value = false
  }
}

const handleExecute = () => {
  if (executing.value || disabled.value) return
  const sql = sqlText.value.trim()
  if (!sql) {
    message.warning('请输入 SQL 语句')
    return
  }
  const danger = detectDanger(sql)
  if (danger) {
    Modal.confirm({
      title: '危险语句确认',
      content: `检测到 ${danger} 语句。该操作将直接作用于数据库且不可撤销，确认执行？`,
      okText: '确认执行',
      okType: 'danger',
      cancelText: '取消',
      onOk: () => doExecute()
    })
    return
  }
  doExecute()
}

const onEditorKeydown = (e: KeyboardEvent) => {
  if ((e.ctrlKey || e.metaKey) && e.key === 'Enter') {
    e.preventDefault()
    handleExecute()
  }
}

const buildColumns = (item: SqlStatementResult) => {
  const cols = item.columns || []
  return cols.map((col) => ({
    title: col,
    key: col,
    ellipsis: true,
    customRender: ({ record }: any) => formatCell(record[col])
  }))
}

const formatCell = (v: any) => (v === null || v === undefined ? '' : String(v))
</script>

<style scoped>
.conn-bar {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 12px;
}
.conn-addr {
  color: #666;
  font-size: 13px;
}
.toolbar {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 8px;
}
.toolbar-label {
  color: #666;
  font-size: 13px;
}
.shortcut-hint {
  margin-left: auto;
  color: #999;
  font-size: 12px;
}
.sql-editor :deep(textarea) {
  font-family: 'JetBrains Mono', Consolas, Monaco, monospace;
  font-size: 13px;
}
.actions {
  margin-top: 10px;
}
.result-area {
  margin-top: 16px;
}
.result-summary {
  margin-bottom: 8px;
  color: #555;
  font-size: 13px;
}
.result-db {
  color: #999;
}
.stmt-block {
  margin-bottom: 16px;
}
.stmt-header {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 6px;
}
.stmt-title {
  font-weight: 600;
  color: #333;
}
.stmt-meta {
  color: #666;
  font-size: 13px;
}
.truncated-tip {
  color: #d48806;
}
</style>
