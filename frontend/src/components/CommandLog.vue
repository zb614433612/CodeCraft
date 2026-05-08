<template>
  <div class="command-log">
    <div class="cl-header">
      <CodeOutlined />
      <span class="cl-title">{{ title || '命令执行' }}</span>
      <span v-if="exitCode !== undefined" :class="['cl-status', exitCode === 0 ? 'success' : 'fail']">
        {{ exitCode === 0 ? '✓ 成功' : '✗ 失败' }}
      </span>
    </div>
    <div class="cl-body">
      <div class="cl-command" v-if="command">
        <span class="cl-prompt">$</span>
        <span class="cl-cmd-text">{{ command }}</span>
      </div>
      <div class="cl-output" v-if="output">
        <div v-for="(line, i) in outputLines" :key="i" :class="['cl-line', getLineClass(line)]">
          <span class="cl-line-text" v-html="escapeHtml(line)"></span>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { CodeOutlined } from '@ant-design/icons-vue'

const props = defineProps<{
  command?: string
  output?: string
  exitCode?: number
  title?: string
}>()

const outputLines = computed(() => {
  if (!props.output) return []
  return props.output.split('\n').filter(l => l !== '')
})

function getLineClass(line: string): string {
  if (/^(error|fatal|fail|exception|at\s)/i.test(line)) return 'error'
  if (/^(warning|warn)/i.test(line)) return 'warn'
  if (/^(success|ok|✓|√)/i.test(line)) return 'success'
  return ''
}

function escapeHtml(text: string): string {
  return text
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
}
</script>

<style scoped>
.command-log {
  border: 1px solid #30363d;
  border-radius: 6px;
  overflow: hidden;
  font-family: 'SF Mono', 'Monaco', 'Menlo', 'Consolas', monospace;
  font-size: 12px;
  margin: 8px 0;
  background: #0d1117;
  color: #c9d1d9;
}
.cl-header {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 6px 12px;
  background: #161b22;
  border-bottom: 1px solid #30363d;
}
.cl-title { font-weight: 600; font-size: 12px; color: #c9d1d9; }
.cl-status { margin-left: auto; font-size: 11px; font-weight: 600; }
.cl-status.success { color: #3fb950; }
.cl-status.fail { color: #f85149; }
.cl-body { padding: 8px 0; }
.cl-command {
  display: flex;
  gap: 6px;
  padding: 2px 12px 6px;
  border-bottom: 1px solid #21262d;
  margin-bottom: 4px;
}
.cl-prompt { color: #3fb950; font-weight: 600; user-select: none; }
.cl-cmd-text { color: #c9d1d9; }
.cl-output { padding: 0 12px; }
.cl-line { line-height: 1.6; }
.cl-line.error { color: #f85149; }
.cl-line.warn { color: #d29922; }
.cl-line.success { color: #3fb950; }
</style>
