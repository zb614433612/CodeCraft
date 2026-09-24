<template>
  <div class="system-setting-page">
    <!-- ===== 头部卡片 ===== -->
    <div class="page-header">
      <div class="header-left">
        <div class="header-icon-box">
          <span class="header-icon">⚙️</span>
        </div>
        <div class="header-text">
          <h2 class="header-title">数据管理</h2>
          <p class="header-subtitle">查看和重置本机存储的应用数据</p>
        </div>
      </div>
    </div>

    <!-- ===== 内容区域 ===== -->
    <div class="setting-content">
      <!-- 数据说明卡片 -->
      <div class="setting-card">
        <div class="card-header">
          <span class="card-header-icon">📦</span>
          <span class="card-header-title">本机数据说明</span>
        </div>
        <div class="card-body">
          <ul class="data-desc-list">
            <li><b>数据库</b>：会话记录、聊天消息、技能、定时任务、用户账号、LLM Provider 配置等（H2 文件数据库）</li>
            <li><b>快照</b>：代码回滚快照文件（snapshots/ 目录）</li>
            <li><b>日志</b>：运行日志文件（logs/ 目录）</li>
            <li><b>其他</b>：P2P 接收文件（data/p2p/received）、附件暂存目录</li>
          </ul>
          <p class="data-desc-tip">
            💡 应用为纯本地单机应用，所有数据仅存储在本机，不会上传云端。
            升级应用版本不会影响这些数据，数据也不会被自动删除。
          </p>
        </div>
      </div>

      <!-- ===== 备份导出卡片 ===== -->
      <div class="setting-card">
        <div class="card-header">
          <span class="card-header-icon">📤</span>
          <span class="card-header-title">导出备份</span>
        </div>
        <div class="card-body">
          <p class="backup-desc">
            选择需要导出的数据类别，生成 JSON 备份文件；重装或迁移后通过「导入备份」一键恢复。
          </p>
          <a-checkbox-group v-model:value="exportTypes" class="export-options">
            <a-checkbox v-for="opt in exportOptions" :key="opt.key" :value="opt.key" class="export-option">
              {{ opt.label }}
              <span v-if="statsLoaded" class="export-count">（{{ stats[opt.key] ?? 0 }} 条）</span>
            </a-checkbox>
          </a-checkbox-group>
          <div class="backup-actions">
            <a-button
              type="primary"
              size="large"
              class="backup-btn export-btn"
              :loading="exporting"
              :disabled="exportTypes.length === 0"
              @click="handleExport"
            >
              <template #icon><DownloadOutlined /></template>
              导出备份
            </a-button>
            <a-button size="large" :disabled="exporting" @click="toggleSelectAll">
              {{ exportTypes.length === exportOptions.length ? '全不选' : '全选' }}
            </a-button>
          </div>
          <p class="backup-tip warn-tip">
            ⚠️ 备份文件包含 API Key、数据库连接密码等敏感凭证（用于恢复后免重新配置），请妥善保管，勿分享给他人。
          </p>
        </div>
      </div>

      <!-- ===== 备份导入卡片 ===== -->
      <div class="setting-card">
        <div class="card-header">
          <span class="card-header-icon">📥</span>
          <span class="card-header-title">导入备份</span>
        </div>
        <div class="card-body">
          <p class="backup-desc">
            选择此前导出的 JSON 备份文件，一键恢复 LLM 配置、智能体、技能、聊天记录、踩坑经验、MCP 服务器与数据库连接。
          </p>
          <div class="backup-actions">
            <input
              ref="importInputRef"
              type="file"
              accept=".json,application/json"
              style="display: none"
              @change="handleImportFileSelected"
            />
            <a-button size="large" :loading="importParsing" @click="triggerImportSelect">
              <template #icon><FolderOpenOutlined /></template>
              选择备份文件
            </a-button>
            <span v-if="importFile" class="import-file-name">{{ importFile.name }}（{{ formatFileSize(importFile.size) }}）</span>
          </div>

          <div v-if="importPreview" class="import-preview">
            <p class="import-preview-title">备份文件包含以下数据（导出时间：{{ importPreview.exportedAt || '未知' }}）：</p>
            <div class="import-preview-list">
              <span v-for="item in importPreview.items" :key="item.key" class="import-preview-item">
                {{ item.label }} <b>{{ item.count }}</b> 条
              </span>
            </div>
            <div class="backup-actions">
              <a-button
                type="primary"
                size="large"
                class="backup-btn import-btn"
                :loading="importing"
                @click="handleImport"
              >
                <template #icon><UploadOutlined /></template>
                开始导入
              </a-button>
              <a-button size="large" :disabled="importing" @click="clearImport">取消</a-button>
            </div>
          </div>

          <p class="backup-tip">
            💡 导入采用「合并」策略：相同 ID 的数据将覆盖更新，新数据将追加，不会清空现有数据；单条失败不影响其余数据导入。
          </p>
        </div>
      </div>

      <!-- 危险操作卡片 -->
      <div class="setting-card danger-card">
        <div class="card-header danger-header">
          <span class="card-header-icon">⚠️</span>
          <span class="card-header-title">危险操作</span>
        </div>
        <div class="card-body">
          <div class="danger-desc">
            <p class="danger-title">重置所有数据</p>
            <p class="danger-text">
              清空全部会话、消息、技能、定时任务、用户账号、LLM Provider 配置、快照、日志等数据，
              并恢复出厂默认状态（默认管理员 admin / 123456）。<b>此操作不可撤销！</b>
            </p>
          </div>
          <div class="danger-actions">
            <a-popconfirm
              title="此操作不可撤销，确定继续吗？"
              ok-text="继续"
              cancel-text="取消"
              @confirm="openConfirmModal"
            >
              <a-button type="primary" danger size="large" class="reset-btn">
                <template #icon><DeleteOutlined /></template>
                重置所有数据
              </a-button>
            </a-popconfirm>
          </div>
        </div>
      </div>
    </div>

    <!-- ===== 二次确认弹窗：输入"重置" ===== -->
    <a-modal
      v-model:open="confirmVisible"
      title="确认重置所有数据"
      :mask-closable="false"
      :closable="!resetting"
      :footer="null"
      class="reset-confirm-modal"
    >
      <div class="confirm-content">
        <p class="confirm-warn">
          即将清空本机全部应用数据并恢复出厂默认状态，此操作不可撤销。
          请先停止正在进行的 AI 任务，并确认已备份需要保留的数据。
        </p>
        <p class="confirm-tip">请输入 <b>重置</b> 以确认操作：</p>
        <a-input
          v-model:value="confirmText"
          placeholder="请输入「重置」"
          size="large"
          :disabled="resetting"
          class="confirm-input"
          @press-enter="handleConfirm"
        />
        <div class="confirm-actions">
          <a-button size="large" :disabled="resetting" @click="confirmVisible = false">取消</a-button>
          <a-button
            type="primary"
            danger
            size="large"
            :loading="resetting"
            :disabled="confirmText !== '重置'"
            class="confirm-btn"
            @click="handleConfirm"
          >
            确认重置
          </a-button>
        </div>
      </div>
    </a-modal>
  </div>
</template>

<script setup lang="ts">
import { ref, h, onMounted } from 'vue'
import { message, Modal } from 'ant-design-vue'
import { DeleteOutlined, DownloadOutlined, UploadOutlined, FolderOpenOutlined } from '@ant-design/icons-vue'
import { resetAllData, fetchDataStats, exportSystemData, importSystemData } from '@/api/system'
import type { DataImportResult } from '@/api/system'
import { useRouter } from 'vue-router'
import { useUserStore } from '@/store/user'

const router = useRouter()
const userStore = useUserStore()

const confirmVisible = ref(false)
const confirmText = ref('')
const resetting = ref(false)

function openConfirmModal() {
  confirmText.value = ''
  confirmVisible.value = true
}

async function handleConfirm() {
  if (confirmText.value !== '重置') {
    message.warning('请输入「重置」以确认操作')
    return
  }
  resetting.value = true
  try {
    await resetAllData()
    confirmVisible.value = false
    Modal.success({
      title: '重置完成',
      content: '所有数据已重置并恢复出厂默认状态。\n系统将使用默认管理员账号（admin / 123456）登录。',
      okText: '重新登录',
      onOk: () => {
        // 清除本地登录状态，跳转登录页
        userStore.clearUserInfo()
        router.push('/login')
      }
    })
  } catch (e: any) {
    message.error(e.message || '重置失败')
  } finally {
    resetting.value = false
  }
}

// ==================== 备份导出 ====================

/** 导出类别选项（key 与后端 DataTransferService.CATEGORIES 对应） */
const exportOptions = [
  { key: 'llmProvider', label: 'LLM 配置' },
  { key: 'agent', label: '智能体' },
  { key: 'skill', label: '技能' },
  { key: 'chat', label: '聊天记录' },
  { key: 'lesson', label: '踩坑经验' },
  { key: 'mcp', label: 'MCP 服务器' },
  { key: 'dbConnection', label: '数据库连接' }
]

const exportTypes = ref<string[]>(exportOptions.map((o) => o.key))
const stats = ref<Record<string, number>>({})
const statsLoaded = ref(false)
const exporting = ref(false)

onMounted(async () => {
  try {
    stats.value = await fetchDataStats()
    statsLoaded.value = true
  } catch {
    // 统计加载失败不阻塞页面（复选框不显示数量，仍可正常导出）
  }
})

function toggleSelectAll() {
  exportTypes.value = exportTypes.value.length === exportOptions.length ? [] : exportOptions.map((o) => o.key)
}

async function handleExport() {
  if (exportTypes.value.length === 0) {
    message.warning('请至少选择一个数据类别')
    return
  }
  exporting.value = true
  try {
    const { blob, filename } = await exportSystemData(exportTypes.value)
    const url = URL.createObjectURL(blob)
    const link = document.createElement('a')
    link.href = url
    link.download = filename
    document.body.appendChild(link)
    link.click()
    link.remove()
    URL.revokeObjectURL(url)
    message.success('备份文件已导出，请妥善保管')
  } catch (e: any) {
    message.error(e.message || '导出失败')
  } finally {
    exporting.value = false
  }
}

// ==================== 备份导入 ====================

const importInputRef = ref<HTMLInputElement>()
const importFile = ref<File | null>(null)
const importParsing = ref(false)
const importing = ref(false)
const importPreview = ref<{ exportedAt?: string; items: { key: string; label: string; count: number }[] } | null>(null)

function triggerImportSelect() {
  importInputRef.value?.click()
}

function formatFileSize(size: number): string {
  if (size < 1024) return `${size} B`
  if (size < 1024 * 1024) return `${(size / 1024).toFixed(1)} KB`
  return `${(size / 1024 / 1024).toFixed(1)} MB`
}

function handleImportFileSelected(ev: Event) {
  const input = ev.target as HTMLInputElement
  const file = input.files?.[0]
  input.value = '' // 清空以允许重复选择同一文件
  if (!file) return
  if (!file.name.toLowerCase().endsWith('.json')) {
    message.warning('请选择 .json 格式的备份文件')
    return
  }
  if (file.size > 64 * 1024 * 1024) {
    message.warning('备份文件超过 64MB 上限，无法导入')
    return
  }
  importParsing.value = true
  const reader = new FileReader()
  reader.onload = () => {
    try {
      const data = JSON.parse(String(reader.result || ''))
      if (data.app !== 'CodeCraft' || !data.categories) {
        throw new Error('不是有效的 CodeCraft 备份文件')
      }
      const items = Object.entries(data.categories as Record<string, any>).map(([key, cat]) => ({
        key,
        label: cat?.label || key,
        count: Object.values((cat?.tables || {}) as Record<string, any[]>).reduce(
          (sum: number, rows) => sum + (Array.isArray(rows) ? rows.length : 0),
          0
        )
      }))
      importFile.value = file
      importPreview.value = { exportedAt: data.exportedAt, items }
    } catch (e: any) {
      importFile.value = null
      importPreview.value = null
      message.error(e.message || '备份文件解析失败')
    } finally {
      importParsing.value = false
    }
  }
  reader.onerror = () => {
    importParsing.value = false
    message.error('文件读取失败')
  }
  reader.readAsText(file, 'utf-8')
}

function clearImport() {
  importFile.value = null
  importPreview.value = null
}

async function handleImport() {
  if (!importFile.value) return
  importing.value = true
  try {
    const res = await importSystemData(importFile.value)
    const result: DataImportResult = res.data
    // 结果明细：每类别一行
    const rows = (result.details || []).map((d) => {
      const inserted = d.tables.reduce((s, t) => s + t.inserted, 0)
      const updated = d.tables.reduce((s, t) => s + t.updated, 0)
      const failedCount = d.tables.reduce((s, t) => s + t.failed, 0)
      return h(
        'div',
        `${d.label}：新增 ${inserted} 条，更新 ${updated} 条` + (failedCount > 0 ? `，失败 ${failedCount} 条` : '')
      )
    })
    Modal.success({
      title: '导入完成',
      width: 480,
      content: h('div', { style: 'line-height: 1.9;' }, [
        h(
          'div',
          { style: 'font-weight: 600; margin-bottom: 6px;' },
          `共新增 ${result.inserted} 条，更新 ${result.updated} 条` +
            (result.failed > 0 ? `，失败 ${result.failed} 条` : '，全部成功')
        ),
        ...rows,
        h('div', { style: 'color: #94a3b8; font-size: 12px; margin-top: 10px;' },
          '部分数据（如聊天记录、技能）需重新打开对应页面或刷新后可见；MCP 服务器如需启用请到「MCP 服务器」页面连接。')
      ]),
      okText: '知道了'
    })
    clearImport()
    // 刷新数据统计
    try {
      stats.value = await fetchDataStats()
      statsLoaded.value = true
    } catch {
      // 忽略统计刷新失败
    }
  } catch (e: any) {
    message.error(e.message || '导入失败')
  } finally {
    importing.value = false
  }
}
</script>

<style scoped>
.system-setting-page {
  padding: 20px 24px 32px;
  background: #f7f9fc;
  height: 100%;
  overflow-y: auto;
  box-sizing: border-box;
  display: flex;
  flex-direction: column;
  gap: 16px;
}

/* 头部 */
.page-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 20px 24px;
  background: #fff;
  border-radius: 12px;
  border: 1px solid #eef2f7;
  box-shadow: 0 1px 3px rgba(0, 0, 0, 0.04);
  flex-shrink: 0;
}
.header-left { display: flex; align-items: center; gap: 14px; }
.header-icon-box {
  width: 44px; height: 44px; border-radius: 10px;
  background: linear-gradient(135deg, #e0f2fe, #bae6fd);
  display: flex; align-items: center; justify-content: center; flex-shrink: 0;
}
.header-icon { font-size: 22px; line-height: 1; }
.header-title { margin: 0; font-size: 18px; font-weight: 700; color: #1a202c; letter-spacing: -0.3px; }
.header-subtitle { margin: 2px 0 0; font-size: 13px; color: #94a3b8; }

/* 内容 */
.setting-content { display: flex; flex-direction: column; gap: 16px; }

/* 卡片 */
.setting-card {
  background: #fff;
  border-radius: 12px;
  border: 1px solid #eef2f7;
  box-shadow: 0 1px 3px rgba(0, 0, 0, 0.04);
  overflow: hidden;
}
.card-header {
  display: flex; align-items: center; gap: 10px;
  padding: 16px 24px;
  border-bottom: 1px solid #f1f5f9;
  background: #fafbfc;
}
.card-header-icon { font-size: 18px; line-height: 1; }
.card-header-title { font-size: 15px; font-weight: 600; color: #1a202c; }
.card-body { padding: 24px; }

/* 数据说明列表 */
.data-desc-list { margin: 0 0 16px; padding-left: 18px; display: flex; flex-direction: column; gap: 8px; }
.data-desc-list li { font-size: 14px; color: #475569; line-height: 1.6; }
.data-desc-list b { color: #1e293b; }
.data-desc-tip {
  margin: 0; padding: 12px 16px;
  background: #f0f9ff; border: 1px solid #bae6fd; border-radius: 8px;
  font-size: 13px; color: #0369a1; line-height: 1.6;
}

/* 危险操作卡片 */
.danger-card { border-color: #fecaca; }
.danger-header { background: #fef2f2; border-bottom-color: #fee2e2; }
.danger-header .card-header-title { color: #b91c1c; }
.danger-desc { margin-bottom: 20px; }
.danger-title { margin: 0 0 8px; font-size: 16px; font-weight: 700; color: #b91c1c; }
.danger-text { margin: 0; font-size: 14px; color: #64748b; line-height: 1.7; }
.danger-text b { color: #b91c1c; }
.danger-actions { display: flex; align-items: center; gap: 12px; }
.reset-btn {
  height: 42px; border-radius: 8px; padding: 0 24px;
  font-weight: 600; font-size: 14px;
  box-shadow: 0 2px 8px rgba(220, 38, 38, 0.25);
}

/* 确认弹窗 */
.confirm-content { padding: 4px 0; }
.confirm-warn {
  margin: 0 0 12px; padding: 12px 16px;
  background: #fef2f2; border: 1px solid #fee2e2; border-radius: 8px;
  font-size: 13px; color: #b91c1c; line-height: 1.7;
}
.confirm-tip { margin: 0 0 10px; font-size: 14px; color: #475569; }
.confirm-input { margin-bottom: 20px; }
.confirm-actions { display: flex; justify-content: flex-end; gap: 12px; }
.confirm-btn { min-width: 120px; }

/* ===== 备份导出 / 导入 ===== */
.backup-desc { margin: 0 0 4px; font-size: 14px; color: #475569; line-height: 1.7; }
.export-options { display: flex; flex-wrap: wrap; gap: 10px 28px; margin: 16px 0 20px; }
.export-option { font-size: 14px; color: #334155; }
.export-option .export-count { color: #94a3b8; font-size: 13px; }
.backup-actions { display: flex; align-items: center; gap: 12px; flex-wrap: wrap; }
.backup-btn {
  height: 42px; border-radius: 8px; padding: 0 24px;
  font-weight: 600; font-size: 14px;
}
.export-btn { box-shadow: 0 2px 8px rgba(22, 119, 255, 0.25); }
.import-file-name { font-size: 13px; color: #475569; }
.import-preview {
  margin-top: 16px; padding: 14px 16px;
  background: #f8fafc; border: 1px solid #e2e8f0; border-radius: 8px;
}
.import-preview-title { margin: 0 0 10px; font-size: 13px; color: #475569; }
.import-preview-list { display: flex; flex-wrap: wrap; gap: 8px 20px; margin-bottom: 14px; }
.import-preview-item { font-size: 13px; color: #334155; }
.import-preview-item b { color: #1677ff; }
.backup-tip {
  margin: 16px 0 0; padding: 12px 16px;
  background: #f0f9ff; border: 1px solid #bae6fd; border-radius: 8px;
  font-size: 13px; color: #0369a1; line-height: 1.6;
}
.warn-tip { background: #fffbeb; border-color: #fde68a; color: #92400e; }

/* 暗色模式 */
[data-theme="dark"] .system-setting-page { background: #121418; }
[data-theme="dark"] .page-header,
[data-theme="dark"] .setting-card { background: #1a1d22; border-color: #2a2d33; }
[data-theme="dark"] .card-header { background: #1e2126; border-bottom-color: #2a2d33; }
[data-theme="dark"] .header-title,
[data-theme="dark"] .card-header-title { color: #e4e6ea; }
[data-theme="dark"] .header-subtitle { color: #8b8f98; }
[data-theme="dark"] .header-icon-box { background: linear-gradient(135deg, #1a2838, #162230); }
[data-theme="dark"] .data-desc-list li { color: #a3a7b0; }
[data-theme="dark"] .data-desc-list b { color: #e4e6ea; }
[data-theme="dark"] .data-desc-tip { background: #0f2438; border-color: #1e3a5f; color: #7dd3fc; }
[data-theme="dark"] .danger-card { border-color: #4c1d1d; }
[data-theme="dark"] .danger-header { background: #2a1515; border-bottom-color: #3a1f1f; }
[data-theme="dark"] .danger-header .card-header-title { color: #fca5a5; }
[data-theme="dark"] .danger-title { color: #fca5a5; }
[data-theme="dark"] .danger-text { color: #a3a7b0; }
[data-theme="dark"] .danger-text b { color: #fca5a5; }
[data-theme="dark"] .confirm-warn { background: #2a1515; border-color: #3a1f1f; color: #fca5a5; }
[data-theme="dark"] .confirm-tip { color: #a3a7b0; }
[data-theme="dark"] .confirm-input :deep(.ant-input) { background: #141619; border-color: #2a2d33; color: #e4e6ea; }
[data-theme="dark"] .backup-desc { color: #a3a7b0; }
[data-theme="dark"] .export-option { color: #cbd5e1; }
[data-theme="dark"] .export-option .export-count { color: #8b8f98; }
[data-theme="dark"] .import-file-name { color: #a3a7b0; }
[data-theme="dark"] .import-preview { background: #14161a; border-color: #2a2d33; }
[data-theme="dark"] .import-preview-title { color: #a3a7b0; }
[data-theme="dark"] .import-preview-item { color: #cbd5e1; }
[data-theme="dark"] .import-preview-item b { color: #4096ff; }
[data-theme="dark"] .backup-tip { background: #0f2438; border-color: #1e3a5f; color: #7dd3fc; }
[data-theme="dark"] .warn-tip { background: #2a2113; border-color: #4d3b16; color: #fbbf24; }
[data-theme="dark"] .system-setting-page::-webkit-scrollbar-thumb { background: #3a3850; }
</style>
