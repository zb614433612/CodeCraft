<!--
  数据库连接 新建 / 编辑抽屉（v2）
  ----------------------------------------------------------------------------
  右侧滑出（560px）。字段与后端 DbConnection 实体一一对应：
    名称（execute_sql 的 connection 参数）/ 类型 / 主机 / 端口 / 库名 / 用户名 / 密码 / 附加参数 / 启用
  与后端对齐的关键约定（DbConnectionController + DbConnectionService.validate）：
    - 名称、类型、库名必填；非 h2 时主机必填；
    - 新建时密码必填（用户名空或 sa 的 h2 场景除外）；
    - 编辑时 passwordPlain 留空 = 保留原密码（后端只在非空时重新加密）；
    - 类型仅支持 mysql / postgresql / h2，后端统一转小写；
    - h2 为 file 模式：无主机端口，库名填 .mv.db 文件路径。
  数据接缝：
    v-model:open  -> 抽屉开关
    connection    -> 当前编辑行（来自 GET /api/db-connections，密码不回显）
    emit('test')  -> POST /api/db-connections/test（未保存）/ POST /api/db-connections/{id}/test（已保存）
    emit('save')  -> POST /api/db-connections（新建）/ PUT /api/db-connections/{id}（编辑）
-->
<template>
  <aside class="cc-drawer" :class="{ 'is-open': open }" style="--drawer-w: 560px">
    <DrawerResizer storage-key="db-connection-edit" />
    <div class="cc-drawer__head">
      <div style="font-size: var(--fs-md); font-weight: 700">{{ isNew ? '新建数据库连接' : '编辑数据库连接' }}</div>
      <button class="cc-icon-btn" style="margin-left: auto" title="关闭" @click="close">
        <svg class="cc-i cc-i--sm" style="transform: rotate(45deg)"><use href="#i-plus" /></svg>
      </button>
    </div>

    <div class="cc-drawer__body">
      <div class="cc-form">
        <div class="cc-field">
          <label class="cc-field__label">连接名称<em>*</em></label>
          <input v-model="form.name" class="cc-input" placeholder="如：订单库 / 报表库" />
          <div class="cc-field__hint">AI 侧通过 execute_sql(connection="订单库") 或连接 id 指定该库</div>
        </div>

        <div class="cc-field">
          <label class="cc-field__label">数据库类型<em>*</em></label>
          <div class="cc-db-type">
            <button
              v-for="opt in TYPE_OPTIONS"
              :key="opt.value"
              class="cc-db-type__item"
              :class="{ 'is-on': form.dbType === opt.value }"
              @click="setType(opt.value)"
            >
              <span class="cc-db-type__dot" />{{ opt.label }}
            </button>
          </div>
        </div>

        <div v-if="!isH2" class="cc-field-row">
          <div class="cc-field">
            <label class="cc-field__label">主机地址<em>*</em></label>
            <input v-model="form.host" class="cc-input" placeholder="IP 或域名" />
          </div>
          <div class="cc-field">
            <label class="cc-field__label">端口</label>
            <input v-model="form.port" class="cc-input" placeholder="留空用默认端口" />
            <div v-if="portError" class="cc-field__hint" style="color: var(--danger)">{{ portError }}</div>
          </div>
        </div>

        <div class="cc-field">
          <label class="cc-field__label">数据库名<em>*</em></label>
          <input v-model="form.databaseName" class="cc-input" :placeholder="isH2 ? '如 ~/.codecraft/data/tracking' : '如 orders'" />
          <div class="cc-field__hint">{{ databaseHint }}</div>
        </div>

        <div class="cc-field-row">
          <div class="cc-field">
            <label class="cc-field__label">用户名</label>
            <input v-model="form.username" class="cc-input" placeholder="H2 默认 sa" />
          </div>
          <div class="cc-field">
            <label class="cc-field__label">密码</label>
            <input
              v-model="form.passwordPlain"
              class="cc-input"
              type="password"
              :placeholder="isNew ? '留空表示空密码' : '留空表示不修改'"
            />
          </div>
        </div>

        <div class="cc-field">
          <div class="cc-field__hint">
            密码以 AES-GCM 密文入库，列表与接口均不回显明文；编辑时留空表示保留原密码。
          </div>
        </div>

        <div class="cc-field">
          <label class="cc-field__label">JDBC 附加参数</label>
          <input v-model="form.extraParams" class="cc-input" placeholder="如 useSSL=false&amp;serverTimezone=Asia/Shanghai" />
        </div>

        <div class="cc-field">
          <label class="cc-field__label">连接串预览</label>
          <div class="cc-db-url">{{ jdbcUrl }}</div>
        </div>

        <div class="cc-switch-row">
          <div class="cc-switch-row__text">
            <div class="cc-switch-row__title">启用该连接</div>
            <div class="cc-switch-row__desc">停用后 AI 工具与「执行 SQL」均无法访问，配置保留</div>
          </div>
          <span class="cc-switch" :class="{ 'is-on': form.enabled === 1 }" @click="form.enabled = form.enabled === 1 ? 0 : 1" />
        </div>

        <!-- 测试连接的就地反馈 -->
        <div
          v-if="testState"
          class="cc-db-test"
          :class="{ 'is-ok': testState.status === 'ok', 'is-fail': testState.status === 'fail' }"
        >
          {{ testState.text }}
        </div>

        <div v-if="missingText" class="cc-field">
          <div class="cc-field__hint" style="color: var(--danger)">{{ missingText }}</div>
        </div>
      </div>
    </div>

    <div class="cc-drawer__foot">
      <button class="cc-btn" :disabled="!canTest" @click="onTest">
        <svg class="cc-i cc-i--sm"><use href="#i-link" /></svg>测试连接
      </button>
      <button class="cc-btn" @click="close">取消</button>
      <button class="cc-btn cc-btn--primary" :disabled="!canSave" @click="onSave">保存</button>
    </div>
  </aside>
</template>

<script setup lang="ts">
import DrawerResizer from './DrawerResizer.vue'
import { computed, reactive, watch } from 'vue'
import type { DbConnectionItem, DbTestState } from './DbConnectionRow.vue'

type DbType = 'mysql' | 'postgresql' | 'h2'

const props = withDefaults(
  defineProps<{
    open: boolean
    /** 当前编辑行；null 表示新建 */
    connection?: DbConnectionItem | null
    /** 测试连接反馈（由父组件按请求结果回填） */
    testState?: DbTestState | null
  }>(),
  {
    connection: null,
    testState: null
  }
)

const emit = defineEmits<{
  (e: 'update:open', v: boolean): void
  (e: 'save', payload: Record<string, unknown>): void
  (e: 'test', payload: Record<string, unknown>): void
}>()

const TYPE_OPTIONS: Array<{ label: string; value: DbType }> = [
  { label: 'MySQL', value: 'mysql' },
  { label: 'PostgreSQL', value: 'postgresql' },
  { label: 'H2', value: 'h2' }
]

/** 各类型默认端口（h2 为 file 模式，无端口） */
const DEFAULT_PORT: Record<DbType, string> = {
  mysql: '3306',
  postgresql: '5432',
  h2: ''
}

const form = reactive({
  name: '',
  dbType: 'mysql' as DbType,
  host: '',
  port: '3306',
  databaseName: '',
  username: '',
  passwordPlain: '',
  extraParams: '',
  enabled: 1
})

const isNew = computed(() => !props.connection)
const isH2 = computed(() => form.dbType === 'h2')

/** 打开时回填（密码永不回填，后端不回显明文） */
watch(
  () => [props.open, props.connection] as const,
  () => {
    if (!props.open) return
    const c = props.connection
    form.name = c?.name || ''
    form.dbType = ((c?.dbType || 'mysql').toLowerCase() as DbType) || 'mysql'
    form.host = c?.host || ''
    form.port = c?.port ? String(c.port) : DEFAULT_PORT[form.dbType]
    form.databaseName = c?.databaseName || ''
    form.username = c?.username || ''
    form.passwordPlain = ''
    form.extraParams = c?.extraParams || ''
    form.enabled = c && Number(c.enabled) === 0 ? 0 : 1
  },
  { immediate: true }
)

/** 切换类型：h2 清空主机端口，其余类型带出默认端口 */
function setType(type: DbType) {
  form.dbType = type
  if (type === 'h2') {
    form.port = ''
  } else {
    form.port = DEFAULT_PORT[type]
  }
}

const databaseHint = computed(() =>
  isH2.value ? 'H2 file 模式填 .mv.db 文件路径（无主机端口）' : form.dbType === 'postgresql' ? 'PostgreSQL 默认端口 5432' : 'MySQL 默认端口 3306'
)

/** 端口格式校验（留空视为使用默认端口） */
const portError = computed(() => {
  const raw = form.port.trim()
  if (!raw) return ''
  if (!/^\d+$/.test(raw)) return '端口应为数字'
  const n = Number(raw)
  return n > 0 && n <= 65535 ? '' : '端口范围 1~65535'
})

/** JDBC 连接串预览（拼接规则与后端 DbConnectionManager 一致） */
const jdbcUrl = computed(() => {
  const name = form.databaseName.trim() || '<数据库名>'
  let url: string
  if (isH2.value) {
    url = `jdbc:h2:file:${name}`
  } else {
    const host = form.host.trim() || '<主机>'
    const port = form.port.trim()
    url = `jdbc:${form.dbType}://${host}${port ? ':' + port : ''}/${name}`
  }
  const extra = form.extraParams.trim()
  return extra ? `${url}?${extra}` : url
})

/** 必填项缺什么（逐项提示，而不是笼统「请填写完整」） */
const missingText = computed(() => {
  if (!form.name.trim()) return '连接名称必填：AI 用该名称作为 execute_sql 的 connection 参数'
  if (!isH2.value && !form.host.trim()) return '主机地址必填（H2 file 模式除外）'
  if (!form.databaseName.trim()) return isH2.value ? '请填写 H2 数据库文件路径' : '数据库名必填'
  // 与后端一致：用户名非空且非 sa 时才要求密码（仅新建时校验）
  const needPassword = isNew.value && form.username.trim() && form.username.trim() !== 'sa'
  if (needPassword && !form.passwordPlain) return '新建连接时密码必填（用户名为空或 sa 的 H2 场景除外）'
  return ''
})

const canSave = computed(() => !missingText.value && !portError.value)
const canTest = computed(() => Boolean(form.name.trim() && form.databaseName.trim() && (isH2.value || form.host.trim())) && !portError.value)

/** 表单 → 接口 payload（passwordPlain 留空不提交 = 不修改密码） */
function buildPayload(): Record<string, unknown> {
  const payload: Record<string, unknown> = {
    name: form.name.trim(),
    dbType: form.dbType,
    host: isH2.value ? null : form.host.trim(),
    port: form.port.trim() ? Number(form.port.trim()) : null,
    databaseName: form.databaseName.trim(),
    username: form.username.trim() || null,
    extraParams: form.extraParams.trim() || null,
    enabled: form.enabled
  }
  if (props.connection?.id != null) payload.id = props.connection.id
  if (form.passwordPlain) payload.passwordPlain = form.passwordPlain
  return payload
}

function onTest() {
  if (!canTest.value) return
  emit('test', buildPayload())
}

function onSave() {
  if (!canSave.value) return
  emit('save', buildPayload())
}

function close() {
  emit('update:open', false)
}
</script>
