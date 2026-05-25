<template>
  <div class="agent-panel">
    <div class="agent-menu-header">
      <RobotOutlined />
      <span>Agent</span>
    </div>
    <div
      v-for="agent in visibleList"
      :key="agent.id"
      :class="['agent-menu-item', { active: selectedId === agent.id }]"
      @click="switchAgent(agent)"
    >
      <span class="agent-avatar">{{ agent.avatar || '🤖' }}</span>
      <span class="agent-name">{{ agent.name }}</span>
    </div>
    <div v-if="agentList.length > 3" class="agent-expand-btn" @click="expandAll = !expandAll">
      <span>{{ expandAll ? '收起' : `展开全部 (${agentList.length})` }}</span>
      <DownOutlined v-if="!expandAll" />
      <UpOutlined v-else />
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, computed, onMounted } from 'vue'
import { RobotOutlined, DownOutlined, UpOutlined } from '@ant-design/icons-vue'
import { listAgentConfigs, updateAgentRuntime, type AgentConfig } from '@/api/agent-config'

const emit = defineEmits<{
  (e: 'change', agentId: number | null | undefined, agent: AgentConfig | null): void
}>()

const agentList = ref<AgentConfig[]>([])
const expandAll = ref(false)
const visibleList = computed(() => {
  if (expandAll.value || agentList.value.length <= 3) return agentList.value
  return agentList.value.slice(0, 3)
})
const selectedId = ref<number | undefined>(undefined)
const loading = ref(false)

const runtime = reactive({
  model: 'deepseek-v4-flash',
  thinkingMode: 'non-thinking',
  executionMode: 'manual',
  workDir: ''
})

let currentAgent: AgentConfig | null = null

const fetchAgents = async () => {
  loading.value = true
  try {
    const res = await listAgentConfigs()
    if (res.code === 200 && res.data) {
      agentList.value = res.data
      if (agentList.value.length > 0 && selectedId.value === undefined) {
        const defaultAgent = agentList.value.find(a => a.isDefault)
        const target = defaultAgent || agentList.value[0]
        switchAgent(target)
      }
    }
  } catch (e) {
    console.error('获取 Agent 列表失败:', e)
  } finally {
    loading.value = false
  }
}

const switchAgent = (agent: AgentConfig) => {
  if (selectedId.value === agent.id) return
  selectedId.value = agent.id
  currentAgent = agent
  runtime.model = agent.modelName || 'deepseek-v4-flash'
  runtime.thinkingMode = agent.thinkingMode || 'non-thinking'
  runtime.executionMode = agent.executionMode || 'manual'
  runtime.workDir = agent.workDir || ''
  emit('change', agent.id, agent)
}

const saveRuntime = async () => {
  if (!selectedId.value) return
  try {
    await updateAgentRuntime(selectedId.value, {
      modelName: runtime.model,
      thinkingMode: runtime.thinkingMode,
      executionMode: runtime.executionMode,
      workDir: runtime.workDir
    })
    if (currentAgent) {
      currentAgent.modelName = runtime.model
      currentAgent.thinkingMode = runtime.thinkingMode
      currentAgent.executionMode = runtime.executionMode
      currentAgent.workDir = runtime.workDir
    }
  } catch (e: any) {
    console.error('保存运行时配置失败:', e)
  }
}

onMounted(() => { fetchAgents() })

defineExpose({ refresh: fetchAgents, selectedId, runtime, saveRuntime })
</script>

<style scoped>
.agent-panel { padding: 0; margin: 0 8px 8px; }
.agent-menu-header {
  display: flex; align-items: center; gap: 6px;
  padding: 6px 12px; font-size: 11px; font-weight: 600;
  color: #8c8c8c; letter-spacing: 0.5px;
}
.agent-menu-item {
  display: flex; align-items: center; gap: 8px;
  padding: 6px 12px; margin: 1px 0; border-radius: 6px;
  cursor: pointer; transition: background .15s;
  font-size: 13px; color: #333;
}
.agent-menu-item:hover { background: #f0f2f5; }
.agent-menu-item.active { background: #e6f4ff; color: #1677ff; font-weight: 500; }
.agent-avatar { font-size: 16px; flex-shrink: 0; }
.agent-name { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.agent-expand-btn {
  display: flex; align-items: center; gap: 6px;
  padding: 5px 12px; margin: 2px 0; border-radius: 6px;
  cursor: pointer; font-size: 12px; color: #1677ff;
  transition: background .15s;
}
.agent-expand-btn:hover { background: #f0f5ff; }
</style>
