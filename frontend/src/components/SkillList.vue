<template>
  <div class="skill-sidebar">
    <div class="skill-header">
      <span class="skill-title">技能管理</span>
      <button class="refresh-btn" @click="refreshSkills">
        <ReloadOutlined />
      </button>
    </div>

    <div v-if="loading" class="loading-text">加载中...</div>

    <div v-else-if="error" class="error-text">{{ error }}</div>

    <div v-else-if="skills.length === 0" class="empty-text">
      暂无技能。AI 会在检测到重复性工作模式时自动创建技能。
    </div>

    <div v-else class="skill-list">
      <div
        v-for="skill in skills"
        :key="skill.id"
        class="skill-item"
      >
        <div class="skill-item-header">
          <span class="skill-name">{{ skill.name }}</span>
          <a-popconfirm
            title="确定删除此技能？"
            @confirm="handleDelete(skill.id)"
          >
            <a-button type="text" size="small" danger>
              <DeleteOutlined />
            </a-button>
          </a-popconfirm>
        </div>
        <div class="skill-desc">{{ skill.description }}</div>
        <div class="skill-meta">
          <span :class="['skill-confidence', confidenceClass(skill.confidence)]">
            {{ (skill.confidence * 100).toFixed(0) }}%
          </span>
          <span class="skill-count">使用 {{ skill.usageCount }} 次</span>
          <span class="skill-agent">{{ agentLabel(skill.agentType) }}</span>
        </div>
        <div class="skill-tools" v-if="skill.toolNames">
          工具：{{ formatToolNames(skill.toolNames) }}
        </div>
        <div class="skill-time">创建于 {{ formatTime(skill.createdAt) }}</div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { ReloadOutlined, DeleteOutlined } from '@ant-design/icons-vue'
import { listSkills, deleteSkill, type Skill } from '@/api/skill'
import { useUserStore } from '@/store/user'
import { message } from 'ant-design-vue'

const userStore = useUserStore()
const skills = ref<Skill[]>([])
const loading = ref(false)
const error = ref('')

onMounted(() => {
  refreshSkills()
})

function getUserId(): number | null {
  return userStore.userInfo?.userId || userStore.userInfo?.id || null
}

async function refreshSkills() {
  const userId = getUserId()
  if (!userId) {
    error.value = '未获取到用户信息'
    return
  }
  loading.value = true
  error.value = ''
  try {
    skills.value = await listSkills(userId)
  } catch (e: any) {
    error.value = '加载技能失败: ' + (e.message || '未知错误')
  } finally {
    loading.value = false
  }
}

async function handleDelete(id: number) {
  const userId = getUserId()
  if (!userId) return
  try {
    const result = await deleteSkill(id, userId)
    if (result.success) {
      message.success('技能已删除')
      await refreshSkills()
    } else {
      message.error(result.message || '删除失败')
    }
  } catch (e: any) {
    message.error('删除失败: ' + (e.message || '未知错误'))
  }
}

function confidenceClass(confidence: number): string {
  if (confidence >= 0.7) return 'confidence-high'
  if (confidence >= 0.4) return 'confidence-mid'
  return 'confidence-low'
}

function agentLabel(agentType: string): string {
  const labels: Record<string, string> = {
    code_assistant: '编码助手'
  }
  return labels[agentType] || agentType
}

function formatToolNames(toolNames: string): string {
  try {
    const tools = JSON.parse(toolNames)
    return Array.isArray(tools) ? tools.join(', ') : toolNames
  } catch {
    return toolNames
  }
}

function formatTime(timeStr: string): string {
  if (!timeStr) return ''
  try {
    const date = new Date(timeStr)
    return date.toLocaleDateString('zh-CN', {
      year: 'numeric',
      month: '2-digit',
      day: '2-digit',
      hour: '2-digit',
      minute: '2-digit'
    })
  } catch {
    return timeStr
  }
}
</script>

<style scoped>
.skill-sidebar {
  padding: 8px;
  font-size: 13px;
  color: #333;
}

.skill-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 12px;
}

.skill-title {
  font-weight: 600;
  font-size: 14px;
}

.refresh-btn {
  background: none;
  border: none;
  cursor: pointer;
  color: #666;
  padding: 4px;
  border-radius: 4px;
}

.refresh-btn:hover {
  background: #f0f0f0;
  color: #1890ff;
}

.loading-text,
.empty-text,
.error-text {
  color: #999;
  text-align: center;
  padding: 24px 8px;
  font-size: 13px;
}

.error-text {
  color: #ff4d4f;
}

.skill-list {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.skill-item {
  background: #fafafa;
  border: 1px solid #e8e8e8;
  border-radius: 6px;
  padding: 10px;
}

.skill-item-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.skill-name {
  font-weight: 600;
  font-size: 13px;
  color: #1a1a1a;
}

.skill-desc {
  margin-top: 4px;
  color: #666;
  font-size: 12px;
  line-height: 1.4;
}

.skill-meta {
  margin-top: 6px;
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 11px;
}

.skill-confidence {
  padding: 1px 6px;
  border-radius: 3px;
  font-weight: 500;
}

.confidence-high {
  background: #f6ffed;
  color: #52c41a;
  border: 1px solid #b7eb8f;
}

.confidence-mid {
  background: #fffbe6;
  color: #faad14;
  border: 1px solid #ffe58f;
}

.confidence-low {
  background: #fff2f0;
  color: #ff4d4f;
  border: 1px solid #ffccc7;
}

.skill-count {
  color: #999;
}

.skill-agent {
  color: #666;
  background: #f0f0f0;
  padding: 1px 5px;
  border-radius: 3px;
}

.skill-tools {
  margin-top: 4px;
  font-size: 11px;
  color: #999;
}

.skill-time {
  margin-top: 4px;
  font-size: 11px;
  color: #bbb;
}
</style>
