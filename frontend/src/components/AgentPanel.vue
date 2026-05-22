<template>
  <div class="agent-panel">
    <div class="agent-panel-header">
      <span class="agent-panel-title">🤖 子Agent</span>
      <span class="agent-count" v-if="agents.length > 0">{{ runningCount }}/{{ agents.length }} 运行中</span>
    </div>

    <div v-if="agents.length === 0" class="agent-empty">
      暂无子Agent。主Agent在需要时会自动创建。
    </div>

    <div v-else class="agent-list">
      <div
        v-for="agent in agents"
        :key="agent.agentId"
        :class="['agent-card', { expanded: expandedAgent === agent.agentId }]"
      >
        <!-- 卡片头部：状态灯 + 名称 + 展开按钮 -->
        <div class="agent-card-header" @click="toggleExpand(agent.agentId)">
          <span :class="['status-dot', agent.status]">
            {{ statusIcon(agent.status) }}
          </span>
          <span class="agent-name">{{ agent.name }}</span>
          <span class="agent-id-label">({{ agent.agentId }})</span>
          <span :class="['status-badge', agent.status]">{{ statusText(agent.status) }}</span>
          <span class="expand-icon">{{ expandedAgent === agent.agentId ? '▼' : '▶' }}</span>
        </div>

        <!-- 展开详情 -->
        <div v-if="expandedAgent === agent.agentId" class="agent-card-body">
          <!-- 技能信息 -->
          <div v-if="agent.skills && agent.skills.length > 0" class="agent-section">
            <div class="section-label">技能</div>
            <div class="skill-tags">
              <span v-for="skill in agent.skills" :key="skill" class="skill-tag">
                {{ skill }}
              </span>
            </div>
          </div>

          <!-- Thinking 事件 -->
          <div v-if="agent.events && agent.events.length > 0" class="agent-section">
            <div class="section-label">执行日志 ({{ agent.events.length }})</div>
            <div class="event-list">
              <div
                v-for="(event, idx) in agent.events"
                :key="idx"
                :class="['event-item', event.type]"
              >
                <!-- thinking 事件 -->
                <div v-if="event.type === 'thinking'" class="event-thinking">
                  <span class="event-icon">💭</span>
                  <span class="event-text">{{ event.content }}</span>
                </div>

                <!-- 工具调用事件 -->
                <div v-else-if="event.type === 'tool_call'" class="event-tool">
                  <span class="event-icon">🔧</span>
                  <span class="event-tool-name">{{ event.toolName }}</span>
                  <span v-if="event.filePath" class="event-file">{{ event.filePath }}</span>
                  <span :class="['event-result', event.result === 'OK' ? 'success' : '']">
                    {{ event.result }}
                  </span>
                </div>

                <!-- 技能匹配事件 -->
                <div v-else-if="event.type === 'skill_match'" class="event-skill">
                  <span class="event-icon">📋</span>
                  <span>匹配技能：</span>
                  <span v-for="(s, si) in event.skills" :key="si" class="skill-tag">
                    {{ s.name }} ({{ (s.confidence * 100).toFixed(0) }}%)
                  </span>
                </div>

                <!-- 状态变更事件 -->
                <div v-else-if="event.type === 'status_change'" class="event-status">
                  <span class="event-icon">{{ statusIcon(event.newStatus) }}</span>
                  <span>状态变更：{{ statusText(event.newStatus) }}</span>
                </div>

                <!-- 文本消息 -->
                <div v-else class="event-text-only">
                  <span class="event-text">{{ event.content }}</span>
                </div>
              </div>
            </div>
          </div>

          <!-- 空状态 -->
          <div v-else class="agent-section">
            <div class="section-label">执行日志</div>
            <div class="event-empty">等待事件...</div>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, computed } from 'vue'

/**
 * 子Agent事件条目
 */
export interface AgentEvent {
  type: 'thinking' | 'tool_call' | 'skill_match' | 'status_change' | 'text'
  content?: string
  toolName?: string
  filePath?: string
  result?: string
  skills?: Array<{ name: string; confidence: number }>
  newStatus?: string
}

/**
 * 子Agent状态数据
 */
export interface AgentInfo {
  agentId: string
  name: string
  status: 'running' | 'completed' | 'failed' | 'pending'
  skills?: string[]
  events: AgentEvent[]
}

const props = defineProps<{
  agents: AgentInfo[]
}>()

const expandedAgent = ref<string | null>(null)

const runningCount = computed(() => {
  return props.agents.filter(a => a.status === 'running' || a.status === 'pending').length
})

function toggleExpand(agentId: string) {
  expandedAgent.value = expandedAgent.value === agentId ? null : agentId
}

function statusIcon(status: string | undefined): string {
  switch (status) {
    case 'running':   return '🟡'
    case 'completed': return '✅'
    case 'failed':    return '❌'
    case 'pending':   return '⏳'
    default:          return '❓'
  }
}

function statusText(status: string | undefined): string {
  switch (status) {
    case 'running':   return '运行中'
    case 'completed': return '已完成'
    case 'failed':    return '失败'
    case 'pending':   return '排队中'
    default:          return '未知'
  }
}
</script>

<style scoped>
.agent-panel {
  font-size: 13px;
  color: #333;
  border-top: 1px solid #e8e8e8;
  padding: 8px;
}

.agent-panel-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 8px;
}

.agent-panel-title {
  font-weight: 600;
  font-size: 14px;
}

.agent-count {
  font-size: 11px;
  color: #999;
}

.agent-empty {
  color: #999;
  text-align: center;
  padding: 16px 8px;
  font-size: 12px;
}

.agent-list {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.agent-card {
  background: #fafafa;
  border: 1px solid #e8e8e8;
  border-radius: 6px;
  overflow: hidden;
}

.agent-card.expanded {
  border-color: #91d5ff;
}

.agent-card-header {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 8px 10px;
  cursor: pointer;
  user-select: none;
}

.agent-card-header:hover {
  background: #f0f5ff;
}

.status-dot {
  font-size: 14px;
  line-height: 1;
}

.agent-name {
  font-weight: 600;
  font-size: 13px;
  color: #1a1a1a;
}

.agent-id-label {
  font-size: 11px;
  color: #bbb;
  font-family: monospace;
}

.status-badge {
  margin-left: auto;
  font-size: 11px;
  padding: 1px 6px;
  border-radius: 3px;
  font-weight: 500;
}

.status-badge.running {
  background: #fffbe6;
  color: #d48806;
  border: 1px solid #ffe58f;
}

.status-badge.completed {
  background: #f6ffed;
  color: #389e0d;
  border: 1px solid #b7eb8f;
}

.status-badge.failed {
  background: #fff2f0;
  color: #cf1322;
  border: 1px solid #ffccc7;
}

.status-badge.pending {
  background: #f0f0f0;
  color: #8c8c8c;
  border: 1px solid #d9d9d9;
}

.expand-icon {
  font-size: 10px;
  color: #bbb;
  margin-left: 4px;
}

.agent-card-body {
  border-top: 1px solid #e8e8e8;
  padding: 8px 10px;
}

.agent-section {
  margin-bottom: 8px;
}

.agent-section:last-child {
  margin-bottom: 0;
}

.section-label {
  font-size: 11px;
  font-weight: 600;
  color: #666;
  margin-bottom: 4px;
  text-transform: uppercase;
  letter-spacing: 0.5px;
}

.skill-tags {
  display: flex;
  flex-wrap: wrap;
  gap: 4px;
}

.skill-tag {
  font-size: 11px;
  background: #e6f7ff;
  color: #1890ff;
  padding: 1px 6px;
  border-radius: 3px;
  border: 1px solid #91d5ff;
}

.event-list {
  max-height: 300px;
  overflow-y: auto;
  display: flex;
  flex-direction: column;
  gap: 3px;
}

.event-item {
  font-size: 12px;
  padding: 3px 6px;
  border-radius: 3px;
  line-height: 1.5;
}

.event-item.thinking {
  background: #f6f8fa;
  color: #666;
  font-style: italic;
}

.event-item.tool_call {
  background: #f0f5ff;
  color: #1a1a1a;
  font-family: monospace;
}

.event-item.skill_match {
  background: #fffbe6;
  color: #666;
}

.event-item.status_change {
  background: #f6ffed;
}

.event-icon {
  margin-right: 4px;
}

.event-tool-name {
  font-weight: 600;
  color: #1890ff;
}

.event-file {
  color: #8c8c8c;
  margin-left: 4px;
  font-size: 11px;
}

.event-result {
  margin-left: 4px;
  font-size: 11px;
}

.event-result.success {
  color: #389e0d;
}

.event-text {
  word-break: break-all;
}

.event-empty {
  font-size: 11px;
  color: #bbb;
  text-align: center;
  padding: 8px;
}
</style>
