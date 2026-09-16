<template>
  <div class="db-connection-view">
    <div class="page-header">
      <h2>外部数据库连接</h2>
      <span class="page-desc">配置业务数据库（MySQL / PostgreSQL / H2），AI 的 execute_sql 工具可通过 connection 参数指定连接执行 SQL。密码加密存储，仅显示是否已配置。</span>
      <a-button type="primary" @click="openCreate">
        <template #icon><PlusOutlined /></template>
        新增连接
      </a-button>
    </div>

    <a-table
      :data-source="connections"
      :loading="loading"
      :pagination="false"
      row-key="id"
      size="middle"
    >
      <a-table-column title="名称" data-index="name" width="140">
        <template #default="{ record }">
          <span>{{ record.name }}</span>
          <a-tag v-if="record.enabled !== 1" color="default" style="margin-left: 6px">停用</a-tag>
        </template>
      </a-table-column>
      <a-table-column title="类型" width="100">
        <template #default="{ record }">
          <a-tag :color="typeColor(record.dbType)">{{ record.dbType }}</a-tag>
        </template>
      </a-table-column>
      <a-table-column title="地址" min-width="220">
        <template #default="{ record }">
          <span v-if="record.dbType === 'h2'">{{ record.databaseName }}</span>
          <span v-else>{{ record.host }}:{{ record.port || '默认' }}/{{ record.databaseName }}</span>
        </template>
      </a-table-column>
      <a-table-column title="用户名" data-index="username" width="120">
        <template #default="{ record }">{{ record.username || '-' }}</template>
      </a-table-column>
      <a-table-column title="密码" width="90">
        <template #default="{ record }">
          <a-tag v-if="record.hasPassword" color="green">已配置</a-tag>
          <span v-else style="color: #999">无</span>
        </template>
      </a-table-column>
      <a-table-column title="操作" width="220">
        <template #default="{ record }">
          <a-space>
            <a-button size="small" @click="handleTest(record)">测试连接</a-button>
            <a-button size="small" type="link" @click="openEdit(record)">编辑</a-button>
            <a-popconfirm title="确认删除该数据库连接？" @confirm="handleDelete(record.id)">
              <a-button size="small" type="link" danger>删除</a-button>
            </a-popconfirm>
          </a-space>
        </template>
      </a-table-column>
    </a-table>

    <!-- 新增/编辑弹窗 -->
    <a-modal
      v-model:open="modalOpen"
      :title="editing ? '编辑连接' : '新增连接'"
      :confirm-loading="submitting"
      @ok="handleSubmit"
      @cancel="closeModal"
    >
      <a-form :model="form" layout="vertical">
        <a-form-item label="连接名称" required>
          <a-input v-model:value="form.name" placeholder="如：订单库（execute_sql connection 参数用）" />
        </a-form-item>
        <a-form-item label="数据库类型" required>
          <a-select v-model:value="form.dbType">
            <a-select-option value="mysql">MySQL</a-select-option>
            <a-select-option value="postgresql">PostgreSQL</a-select-option>
            <a-select-option value="h2">H2（文件库）</a-select-option>
          </a-select>
        </a-form-item>
        <template v-if="form.dbType !== 'h2'">
          <a-form-item label="主机地址" required>
            <a-input v-model:value="form.host" placeholder="如：192.168.1.100" />
          </a-form-item>
          <a-row :gutter="12">
            <a-col :span="10">
              <a-form-item label="端口">
                <a-input-number v-model:value="form.port" :min="1" :max="65535" style="width: 100%"
                                :placeholder="form.dbType === 'postgresql' ? '5432' : '3306'" />
              </a-form-item>
            </a-col>
            <a-col :span="14">
              <a-form-item label="数据库名" required>
                <a-input v-model:value="form.databaseName" placeholder="数据库名" />
              </a-form-item>
            </a-col>
          </a-row>
        </template>
        <template v-else>
          <a-form-item label="数据库文件路径（.mv.db 文件，不含扩展名或含均可）" required>
            <a-input v-model:value="form.databaseName" placeholder="如：D:/data/orders.mv.db" />
          </a-form-item>
        </template>
        <a-form-item label="用户名">
          <a-input v-model:value="form.username" placeholder="MySQL/PostgreSQL 必填；H2 默认 sa" />
        </a-form-item>
        <a-form-item label="密码">
          <a-input-password v-model:value="form.passwordPlain" :placeholder="editing ? '留空表示不修改密码' : '连接密码'" />
        </a-form-item>
        <a-form-item label="附加参数（JDBC URL 查询参数）">
          <a-input v-model:value="form.extraParams" placeholder="如：useSSL=false&serverTimezone=Asia/Shanghai（H2 用分号分隔参数）" />
        </a-form-item>
        <a-form-item label="启用">
          <a-switch v-model:checked="formEnabled" />
        </a-form-item>
      </a-form>
      <div v-if="testResult" style="margin-top: -8px; margin-bottom: 8px">
        <a-alert :type="testOk ? 'success' : 'error'" :message="testResult" show-icon />
      </div>
      <template #footer>
        <div style="display: flex; justify-content: space-between; align-items: center">
          <a-button :loading="testing" @click="doTest">测试连接</a-button>
          <span>
            <a-button style="margin-right: 8px" @click="closeModal">取消</a-button>
            <a-button type="primary" :loading="submitting" @click="handleSubmit">保存</a-button>
          </span>
        </div>
      </template>
    </a-modal>
  </div>
</template>

<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { message } from 'ant-design-vue'
import { PlusOutlined } from '@ant-design/icons-vue'
import {
  listDbConnections, createDbConnection, updateDbConnection, deleteDbConnection,
  testDbConnection, testSavedDbConnection,
  type DbConnectionVO, type DbConnectionConfig
} from '@/api/db-connection'

const loading = ref(false)
const connections = ref<DbConnectionVO[]>([])
const modalOpen = ref(false)
const submitting = ref(false)
const testing = ref(false)
const editing = ref(false)
const testResult = ref('')
const testOk = ref(false)
const formEnabled = ref(true)

const emptyForm = (): DbConnectionConfig => ({
  name: '',
  dbType: 'mysql',
  host: '',
  port: undefined,
  databaseName: '',
  username: '',
  passwordPlain: '',
  extraParams: '',
  enabled: 1
})
const form = reactive<DbConnectionConfig>(emptyForm())

const typeColor = (t: string) => (t === 'mysql' ? 'blue' : t === 'postgresql' ? 'purple' : 'green')

const loadList = async () => {
  loading.value = true
  try {
    const res = await listDbConnections()
    connections.value = res.data || []
  } catch (e: any) {
    message.error(e?.message || '加载失败')
  } finally {
    loading.value = false
  }
}

const openCreate = () => {
  editing.value = false
  Object.assign(form, emptyForm())
  formEnabled.value = true
  testResult.value = ''
  modalOpen.value = true
}

const openEdit = (record: DbConnectionVO) => {
  editing.value = true
  Object.assign(form, {
    id: record.id,
    name: record.name,
    dbType: record.dbType,
    host: record.host || '',
    port: record.port,
    databaseName: record.databaseName || '',
    username: record.username || '',
    passwordPlain: '',
    extraParams: record.extraParams || '',
    enabled: record.enabled ?? 1
  })
  formEnabled.value = record.enabled === 1
  testResult.value = ''
  modalOpen.value = true
}

const closeModal = () => {
  modalOpen.value = false
  testResult.value = ''
}

const handleTest = async (record: DbConnectionVO) => {
  try {
    await testSavedDbConnection(record.id)
    message.success('连接成功')
  } catch (e: any) {
    message.error(e?.message || '连接失败')
  }
}

const doTest = async () => {
  if (!form.name?.trim()) {
    message.warning('请先填写连接名称')
    return
  }
  if (form.dbType !== 'h2' && !form.username?.trim()) {
    message.warning('请先填写用户名')
    return
  }
  testing.value = true
  testResult.value = ''
  const payload = { ...form, enabled: formEnabled.value ? 1 : 0 }
  try {
    const res = await testDbConnection(payload)
    testOk.value = true
    testResult.value = res.message || '连接成功'
  } catch (e: any) {
    testOk.value = false
    // 失败信息来自后端（request 封装在 code!==200 时抛出后端 message，已含"连接失败:"上下文）
    testResult.value = e?.message || '连接失败'
  } finally {
    testing.value = false
  }
}

const handleSubmit = async () => {
  if (!form.name?.trim()) {
    message.warning('请输入连接名称')
    return
  }
  if (!form.dbType) {
    message.warning('请选择数据库类型')
    return
  }
  if (form.dbType !== 'h2' && !form.host?.trim()) {
    message.warning('请输入主机地址')
    return
  }
  if (!form.databaseName?.trim()) {
    message.warning(form.dbType === 'h2' ? '请输入数据库文件路径' : '请输入数据库名')
    return
  }
  if (form.dbType !== 'h2' && !form.username?.trim()) {
    message.warning('请输入用户名')
    return
  }
  submitting.value = true
  try {
    const payload = { ...form, enabled: formEnabled.value ? 1 : 0 }
    if (editing.value) {
      await updateDbConnection(form.id!, payload)
    } else {
      await createDbConnection(payload)
    }
    message.success(editing.value ? '连接已更新' : '连接已创建')
    modalOpen.value = false
    loadList()
  } catch (e: any) {
    message.error(e?.message || '保存失败')
  } finally {
    submitting.value = false
  }
}

const handleDelete = async (id: number) => {
  try {
    await deleteDbConnection(id)
    message.success('已删除')
    loadList()
  } catch (e: any) {
    message.error(e?.message || '删除失败')
  }
}

onMounted(loadList)
</script>

<style scoped>
.db-connection-view {
  padding: 20px;
}
.page-header {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 16px;
}
.page-header h2 {
  margin: 0;
}
.page-desc {
  flex: 1;
  color: #888;
  font-size: 12px;
}
</style>
