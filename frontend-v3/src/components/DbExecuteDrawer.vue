<!--
  执行 SQL 抽屉（v2）
  ----------------------------------------------------------------------------
  管理页的「执行 SQL」入口：对指定外部库执行任意语句（宽松模式），支持多条语句
  按分号顺序执行，返回多结果块（SELECT 结果集 / WRITE 影响行数）。
  与后端的对应关系（DbConnectionController + SqlExecutor）：
    POST /api/db-connections/{id}/execute  body: { sql, maxRows }
    返回 SqlExecuteResult：
      dbLabel        目标库描述，如「外部库[订单库] mysql@192.168.1.100:3306/orders」
      elapsedMs      总耗时（毫秒）
      statementCount 实际执行的语句数
      results[]      每个语句一个结果块：
        sqlType=SELECT -> columns / rows / totalRows / truncated
        sqlType=WRITE  -> affectedRows
  约束：连接必须启用且归属当前用户可见（系统级共享或本人），否则后端返回 400 提示。
  数据接缝：
    v-model:open  -> 抽屉开关
    connection    -> 目标连接（来自 GET /api/db-connections）
    result/error  -> POST /{id}/execute 的返回（由父组件持有，组件只负责渲染）
-->
<template>
  <aside class="cc-drawer" :class="{ 'is-open': open }" style="--drawer-w: 720px">
    <DrawerResizer storage-key="db-execute" />
    <div class="cc-drawer__head">
      <div style="font-size: var(--fs-md); font-weight: 700">执行 SQL</div>
      <span v-if="connection" class="cc-tag">{{ connection.name }}</span>
      <button class="cc-icon-btn" style="margin-left: auto" title="关闭" @click="close">
        <svg class="cc-i cc-i--sm" style="transform: rotate(45deg)"><use href="#i-plus" /></svg>
      </button>
    </div>

    <div class="cc-drawer__body">
      <!-- 目标库描述：避免与系统库混淆 -->
      <div class="cc-db-url">{{ targetLabel }}</div>

      <div class="cc-form" style="margin-top: var(--sp-4)">
        <div class="cc-field">
          <label class="cc-field__label">SQL 语句</label>
          <textarea
            v-model="sql"
            class="cc-textarea cc-sql-editor"
            rows="7"
            placeholder="支持多条语句，用分号分隔按顺序执行"
            @keydown.ctrl.enter.prevent="run"
            @keydown.meta.enter.prevent="run"
          />
          <div class="cc-sql-bar">
            <span>Ctrl / ⌘ + Enter 执行</span>
            <span class="cc-dot" />
            <span>管理页为宽松模式：允许任意语句与多条语句</span>
            <span class="cc-sql-bar__right">
              <span>最大返回行数</span>
              <input v-model="maxRows" class="cc-input" style="width: 72px; height: 28px; padding: 0 8px" />
            </span>
          </div>
          <div v-if="maxRowsError" class="cc-field__hint" style="color: var(--danger)">{{ maxRowsError }}</div>
        </div>
      </div>

      <!-- 执行结果 -->
      <div v-if="error" class="cc-sql-result">
        <div class="cc-sql-error">{{ error }}</div>
      </div>

      <div v-else-if="result" class="cc-sql-result">
        <div class="cc-sql-result__head">
          <span>结果</span><span class="cc-dot" />
          <span>共 <b>{{ result.statementCount || result.results?.length || 0 }}</b> 条语句</span><span class="cc-dot" />
          <span>耗时 <b>{{ result.elapsedMs ?? 0 }} ms</b></span>
          <template v-if="result.dbLabel">
            <span class="cc-dot" /><span>{{ result.dbLabel }}</span>
          </template>
        </div>

        <div v-for="(block, i) in result.results || []" :key="i" class="cc-sql-block">
          <div class="cc-sql-block__cap">
            <span>#{{ (block.index ?? i) + 1 }}</span>
            <span class="cc-tag" :class="block.sqlType === 'WRITE' ? 'cc-tag--ok' : 'cc-tag--accent'">
              {{ block.sqlType || 'SELECT' }}
            </span>
            <span v-if="block.sqlType === 'WRITE'">写操作</span>
            <span v-else>
              {{ block.totalRows ?? block.rows?.length ?? 0 }} 行
              <template v-if="block.truncated">· 已达行数上限（结果可能不完整）</template>
              <template v-else>· 未截断</template>
            </span>
            <span v-if="block.columns?.length">{{ block.columns.length }} 列</span>
          </div>

          <!-- 写操作：只给影响行数 -->
          <div v-if="block.sqlType === 'WRITE'" class="cc-sql-write">
            <svg class="cc-i cc-i--sm" style="color: var(--ok)"><use href="#i-check" /></svg>
            影响行数 <b>{{ block.affectedRows ?? 0 }}</b> 行
          </div>

          <!-- 查询：表格（列名取自返回的 columns，按查询顺序） -->
          <div v-else-if="block.columns?.length" class="cc-sql-scroll">
            <table class="cc-sql-table">
              <thead>
                <tr>
                  <th v-for="col in block.columns" :key="col">{{ col }}</th>
                </tr>
              </thead>
              <tbody>
                <tr v-for="(row, r) in block.rows || []" :key="r">
                  <td
                    v-for="col in block.columns"
                    :key="col"
                    :class="cellClass(row[col])"
                  >
                    {{ formatCell(row[col]) }}
                  </td>
                </tr>
              </tbody>
            </table>
          </div>

          <div v-else class="cc-sql-write">该语句无结果集</div>
        </div>

        <div v-if="!(result.results || []).length" class="cc-sql-empty">执行成功，但没有任何结果块返回</div>
      </div>

      <div v-else class="cc-sql-result">
        <div class="cc-sql-empty">尚未执行，输入 SQL 后点右下角「执行」</div>
      </div>
    </div>

    <div class="cc-drawer__foot">
      <button class="cc-btn" @click="close">关闭</button>
      <button class="cc-btn cc-btn--primary" :disabled="!canRun" @click="run">
        <svg class="cc-i cc-i--sm"><use href="#i-play" /></svg>{{ running ? '执行中…' : '执行' }}
      </button>
    </div>
  </aside>
</template>

<script setup lang="ts">
import DrawerResizer from './DrawerResizer.vue'
import { computed, ref } from 'vue'
import type { DbConnectionItem } from './DbConnectionRow.vue'

/** 单条语句结果（对应后端 SqlExecuteResult.StatementResult） */
export interface SqlStatementResult {
  index?: number
  /** SELECT = 查询结果集 / WRITE = 写操作影响行数 */
  sqlType?: string
  columns?: string[]
  rows?: Array<Record<string, unknown>>
  totalRows?: number
  truncated?: boolean
  affectedRows?: number
}

/** 执行结果（对应后端 SqlExecuteResult） */
export interface SqlExecuteResult {
  dbLabel?: string
  elapsedMs?: number
  statementCount?: number
  results?: SqlStatementResult[]
}

const props = withDefaults(
  defineProps<{
    open: boolean
    connection?: DbConnectionItem | null
    result?: SqlExecuteResult | null
    error?: string
    running?: boolean
  }>(),
  {
    connection: null,
    result: null,
    error: '',
    running: false
  }
)

const emit = defineEmits<{
  (e: 'update:open', v: boolean): void
  (e: 'execute', payload: { id: number | string; sql: string; maxRows: number }): void
}>()

/** 初始示例 SQL：默认带上 LIMIT，避免误查大表 */
const sql = ref(
  ["SELECT id, order_no, amount, status, created_at", 'FROM orders', "WHERE status = 'PAID'", 'ORDER BY created_at DESC', 'LIMIT 5;'].join('\n')
)
const maxRows = ref('200')

/** 最大返回行数：后端默认 200、上限 1000 */
const maxRowsError = computed(() => {
  const raw = maxRows.value.trim()
  if (!raw) return ''
  if (!/^\d+$/.test(raw)) return '最大返回行数应为数字'
  const n = Number(raw)
  if (n < 1) return '至少返回 1 行'
  return n <= 1000 ? '' : '上限 1000 行（后端会按 1000 截断）'
})

const targetLabel = computed(() => {
  if (!props.connection) return '未选择连接'
  const c = props.connection
  const type = (c.dbType || 'mysql').toLowerCase()
  const hostPart = type === 'h2' ? c.databaseName || '' : `${c.host || ''}${c.port ? ':' + c.port : ''}/${c.databaseName || ''}`
  return `外部库[${c.name}] ${type}@${hostPart}`
})

const canRun = computed(() => Boolean(props.connection?.id != null) && sql.value.trim().length > 0 && !maxRowsError.value && !props.running)

/** NULL 与数字走不同样式，便于快速扫读结果集 */
function cellClass(value: unknown) {
  if (value === null || value === undefined) return 'is-null'
  return typeof value === 'number' ? 'is-num' : ''
}

function formatCell(value: unknown): string {
  if (value === null || value === undefined) return 'NULL'
  if (typeof value === 'object') return JSON.stringify(value)
  return String(value)
}

function run() {
  if (!canRun.value) return
  emit('execute', {
    id: props.connection!.id,
    sql: sql.value,
    maxRows: Number(maxRows.value.trim() || 200)
  })
}

function close() {
  emit('update:open', false)
}
</script>
