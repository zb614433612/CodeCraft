<template>
  <div class="diff-view" v-if="diffLines.length > 0">
    <div class="dv-header">
      <span class="dv-title">{{ title || '代码差异' }}</span>
      <span class="dv-stats">
        <span class="dv-add">+{{ added }}</span>
        <span class="dv-del">-{{ removed }}</span>
      </span>
    </div>
    <div class="dv-body">
      <div
        v-for="(line, i) in diffLines"
        :key="i"
        :class="['dv-line', line.type]"
      >
        <span class="dv-ln">{{ line.ln }}</span>
        <span class="dv-prefix">{{ line.prefix }}</span>
        <span class="dv-text" v-html="line.text" />
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'

const props = defineProps<{
  content: string
  title?: string
  language?: string
}>()

interface DiffLine {
  type: 'add' | 'del' | 'normal' | 'header'
  prefix: string
  text: string
  ln: string
}

const lines = computed(() => props.content.split('\n'))

const diffLines = computed(() => {
  const result: DiffLine[] = []
  let addedCount = 0
  let removedCount = 0
  let addLn = 0
  let delLn = 0

  for (const line of lines.value) {
    if (line.startsWith('@@')) {
      // hunk header: @@ -a,b +c,d @@ ...
      const match = line.match(/@@ -(\d+)[,\d]* \+(\d+)[,\d]* @@/)
      if (match) {
        delLn = parseInt(match[1]) - 1
        addLn = parseInt(match[2]) - 1
      }
      result.push({ type: 'header', prefix: '@@', text: line, ln: '' })
      continue
    }
    if (line.startsWith('---') || line.startsWith('+++')) {
      continue
    }
    if (line.startsWith('+')) {
      addLn++
      addedCount++
      result.push({ type: 'add', prefix: '+', text: highlightCode(line.slice(1)), ln: String(addLn) })
    } else if (line.startsWith('-')) {
      delLn++
      removedCount++
      result.push({ type: 'del', prefix: '-', text: highlightCode(line.slice(1)), ln: String(delLn) })
    } else {
      addLn++
      delLn++
      result.push({ type: 'normal', prefix: ' ', text: highlightCode(line), ln: String(delLn) })
    }
  }

  return result
})

const added = computed(() => diffLines.value.filter(l => l.type === 'add').length)
const removed = computed(() => diffLines.value.filter(l => l.type === 'del').length)

function highlightCode(text: string): string {
  const escaped = text
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
  return escaped
}
</script>

<style scoped>
.diff-view {
  border: 1px solid #e8e8e8;
  border-radius: 6px;
  overflow: hidden;
  font-family: 'SF Mono', 'Monaco', 'Menlo', 'Consolas', monospace;
  font-size: 12px;
  margin: 8px 0;
}
.dv-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 6px 12px;
  background: #fafafa;
  border-bottom: 1px solid #e8e8e8;
}
.dv-title { font-weight: 600; color: #262626; font-size: 12px; }
.dv-stats { display: flex; gap: 8px; font-size: 11px; }
.dv-add { color: #52c41a; font-weight: 600; }
.dv-del { color: #ff4d4f; font-weight: 600; }
.dv-body { overflow-x: auto; }
.dv-line {
  display: flex;
  line-height: 1.6;
  min-height: 20px;
}
.dv-line.add { background: #f6ffed; }
.dv-line.del { background: #fff2f0; }
.dv-line.header { background: #f0f5ff; color: #1677ff; }
.dv-ln {
  width: 36px;
  text-align: right;
  padding-right: 6px;
  color: #bbb;
  flex-shrink: 0;
}
.dv-prefix {
  width: 16px;
  text-align: center;
  flex-shrink: 0;
  font-weight: 600;
}
.dv-line.add .dv-prefix { color: #52c41a; }
.dv-line.del .dv-prefix { color: #ff4d4f; }
.dv-line.header .dv-prefix { color: #1677ff; }
.dv-text {
  flex: 1;
  white-space: pre;
  padding-left: 4px;
}
</style>
