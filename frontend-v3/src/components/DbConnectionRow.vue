<!--
  数据库连接行（v2）
  ----------------------------------------------------------------------------
  信息层次：类型图标（MySQL / PostgreSQL / H2 三色区分）+ 连接名 + 类型 + 状态 + 归属
           → JDBC 连接串（等宽字体，可横向滚动，不撑破布局）
           → 元信息：用户名 / 密码状态 / AI 调用提示 / 更新时间
           → 测试连接的就地反馈（不弹 toast，结果直接落在行内）
  状态语义（与后端 DbConnection 实体一致）：
    enabled:     1=启用 0=停用（停用后 AI 工具与「执行 SQL」均不可访问，配置保留）
    hasPassword: 后端脱敏后的布尔标记，false 时提示未配置密码（h2 + sa 允许空密码）
    userId:      null=系统级共享（所有用户可见），非 null=归属某用户
  数据接缝：
    db.*              -> GET /api/db-connections（出参已脱敏，passwordEncrypted 置空）
    emit('test')      -> POST /api/db-connections/{id}/test
    emit('execute')   -> POST /api/db-connections/{id}/execute（打开执行 SQL 抽屉）
    emit('edit')      -> 打开编辑抽屉（PUT /api/db-connections/{id}）
    emit('delete')    -> DELETE /api/db-connections/{id}
    emit('toggle')    -> PUT /api/db-connections/{id}（enabled 取反后整体提交）
-->
<template>
  <div class="cc-db-row" :class="{ 'is-off': !isEnabled }">
    <div class="cc-db-row__icon" :class="iconMod">
      <svg class="cc-i"><use href="#i-db" /></svg>
    </div>

    <div class="cc-db-row__main">
      <!-- 连接名 + 类型 + 状态 + 归属 -->
      <div class="cc-db-row__title">
        <span class="cc-db-name">{{ db.name }}</span>
        <span class="cc-tag">{{ typeLabel }}</span>
        <span class="cc-status" :class="{ 'is-connected': isEnabled }">
          <span class="cc-status__dot" />{{ isEnabled ? '已启用' : '已停用' }}
        </span>
        <span class="cc-tag">{{ scopeText }}</span>
      </div>

      <!-- JDBC 连接串 -->
      <div class="cc-db-dsn">
        <span class="cc-db-dsn__scheme">{{ dsn.scheme }}</span>
        <span class="cc-db-dsn__path">{{ dsn.path }}</span>
        <span v-if="dsn.params" class="cc-db-dsn__params">?{{ dsn.params }}</span>
      </div>

      <!-- 元信息 -->
      <div class="cc-db-row__meta">
        <span v-if="db.username" class="cc-db-meta-item">
          <svg class="cc-i cc-i--sm"><use href="#i-users" /></svg>{{ db.username }}
        </span>
        <span class="cc-db-meta-item" :class="{ 'cc-db-pwd--none': !db.hasPassword }">
          <svg class="cc-i cc-i--sm"><use href="#i-lock" /></svg>{{ db.hasPassword ? '密码已配置' : '未配置密码' }}
        </span>
        <span>AI 侧调用：execute_sql(connection=<b>"{{ db.name }}"</b>)</span>
        <span v-if="!isEnabled">停用后 AI 与执行 SQL 均不可访问</span>
        <span v-else>更新于 {{ formatTime(db.updatedAt) }}</span>
      </div>

      <!-- 测试连接就地反馈 -->
      <div
        v-if="testState"
        class="cc-db-test"
        :class="{ 'is-ok': testState.status === 'ok', 'is-fail': testState.status === 'fail' }"
      >
        {{ testState.text }}
      </div>
    </div>

    <!-- 右侧：启用开关（常显）+ 操作（hover 显） -->
    <div class="cc-db-row__side">
      <span class="cc-switch" :class="{ 'is-on': isEnabled }" @click="$emit('toggle', db)" />
      <div class="cc-db-row__ops">
        <button class="cc-icon-btn cc-icon-btn--sm" title="测试连接" @click="$emit('test', db)">
          <svg class="cc-i cc-i--sm"><use href="#i-link" /></svg>
        </button>
        <button class="cc-icon-btn cc-icon-btn--sm" title="执行 SQL" @click="$emit('execute', db)">
          <svg class="cc-i cc-i--sm"><use href="#i-term" /></svg>
        </button>
        <button class="cc-icon-btn cc-icon-btn--sm" title="编辑" @click="$emit('edit', db)">
          <svg class="cc-i cc-i--sm"><use href="#i-brush" /></svg>
        </button>
        <button class="cc-icon-btn cc-icon-btn--sm" title="删除" @click="$emit('delete', db)">
          <svg class="cc-i cc-i--sm"><use href="#i-trash" /></svg>
        </button>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'

export interface DbConnectionItem {
  id: number | string
  /** 连接名称（execute_sql 的 connection 参数，名称或 id 均可） */
  name: string
  /** mysql / postgresql / h2 */
  dbType?: string
  /** 主机地址（h2 file 模式为空） */
  host?: string
  /** 端口（空则用各类型默认端口） */
  port?: number | null
  /** 数据库名（h2 file 模式为 .mv.db 文件路径） */
  databaseName?: string
  username?: string
  /** 后端脱敏字段：是否已配置密码（不回显明文） */
  hasPassword?: boolean
  /** JDBC URL 附加参数，如 useSSL=false&serverTimezone=Asia/Shanghai */
  extraParams?: string
  /** 1=启用 0=停用 */
  enabled?: number
  /** 归属用户（null=系统级共享） */
  userId?: number | null
  createdAt?: string
  updatedAt?: string
}

/** 测试连接的就地反馈状态（由父组件按连接 id 维护，组件只负责展示） */
export interface DbTestState {
  status: 'testing' | 'ok' | 'fail'
  text: string
}

const props = withDefaults(
  defineProps<{
    db: DbConnectionItem
    testState?: DbTestState | null
  }>(),
  {
    testState: null
  }
)

defineEmits<{
  (e: 'test', db: DbConnectionItem): void
  (e: 'execute', db: DbConnectionItem): void
  (e: 'edit', db: DbConnectionItem): void
  (e: 'delete', db: DbConnectionItem): void
  (e: 'toggle', db: DbConnectionItem): void
}>()

const TYPE_LABEL: Record<string, string> = {
  mysql: 'MySQL',
  postgresql: 'PostgreSQL',
  h2: 'H2'
}

/** 类型图标配色（与 app.css 的 .is-mysql / .is-postgresql / .is-h2 对应） */
const TYPE_ICON_MOD: Record<string, string> = {
  mysql: 'is-mysql',
  postgresql: 'is-postgresql',
  h2: 'is-h2'
}

const DEFAULT_PORT: Record<string, string> = {
  mysql: '3306',
  postgresql: '5432'
}

const dbType = computed(() => (props.db.dbType || 'mysql').toLowerCase())
const typeLabel = computed(() => TYPE_LABEL[dbType.value] || dbType.value)
const iconMod = computed(() => TYPE_ICON_MOD[dbType.value] || '')
const isEnabled = computed(() => Number(props.db.enabled) === 1)
const scopeText = computed(() => (props.db.userId == null ? '系统级' : '我的'))

/** 连接串拆三段渲染：协议前缀弱化、主机库名主色、参数弱化 */
const dsn = computed(() => {
  const name = props.db.databaseName || ''
  if (dbType.value === 'h2') {
    return { scheme: 'jdbc:h2:file:', path: name, params: props.db.extraParams || '' }
  }
  const port = props.db.port ? String(props.db.port) : DEFAULT_PORT[dbType.value] || ''
  return {
    scheme: `jdbc:${dbType.value}://`,
    path: `${props.db.host || ''}${port ? ':' + port : ''}/${name}`,
    params: props.db.extraParams || ''
  }
})

/** 相对时间：今天 HH:mm / 昨天 / N 天前 / 具体日期 */
function formatTime(value?: string): string {
  if (!value) return '-'
  const date = new Date(value.replace(/-/g, '/'))
  if (Number.isNaN(date.getTime())) return value
  const now = new Date()
  if (date.toDateString() === now.toDateString()) {
    return `今天 ${String(date.getHours()).padStart(2, '0')}:${String(date.getMinutes()).padStart(2, '0')}`
  }
  const diffDays = Math.floor((now.getTime() - date.getTime()) / 86_400_000)
  if (diffDays === 1) return '昨天'
  if (diffDays > 1 && diffDays < 7) return `${diffDays} 天前`
  return `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, '0')}-${String(date.getDate()).padStart(2, '0')}`
}
</script>
