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
import { ref } from 'vue'
import { message, Modal } from 'ant-design-vue'
import { DeleteOutlined } from '@ant-design/icons-vue'
import { resetAllData } from '@/api/system'
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
[data-theme="dark"] .system-setting-page::-webkit-scrollbar-thumb { background: #3a3850; }
</style>
