<template>
  <div class="file-change-list">
    <div class="fcl-header">
      <FileSearchOutlined />
      <span class="fcl-title">{{ title || '文件修改清单' }}</span>
      <span class="fcl-count" v-if="files.length > 0">{{ files.length }} 个文件</span>
    </div>
    <div v-if="files.length === 0" class="fcl-empty">暂无文件改动</div>
    <div v-else class="fcl-body">
      <div
        v-for="(file, i) in files"
        :key="i"
        :class="['fcl-item', file.type]"
      >
        <span class="fcl-type-badge">{{ typeLabel(file.type) }}</span>
        <span class="fcl-path" :title="file.path">{{ file.path }}</span>
        <span v-if="file.summary" class="fcl-summary">— {{ file.summary }}</span>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { FileSearchOutlined } from '@ant-design/icons-vue'

export interface FileChange {
  path: string
  type: 'add' | 'modify' | 'delete' | 'rename'
  summary?: string
}

const props = defineProps<{
  files?: FileChange[]
  title?: string
}>()

const files = props.files || []

function typeLabel(type: string): string {
  switch (type) {
    case 'add': return '新增'
    case 'modify': return '修改'
    case 'delete': return '删除'
    case 'rename': return '重命名'
    default: return type
  }
}
</script>

<style scoped>
.file-change-list {
  border: 1px solid #e8e8e8;
  border-radius: 6px;
  overflow: hidden;
  font-size: 12px;
  margin: 8px 0;
  background: #fff;
}
.fcl-header {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 6px 12px;
  background: #fafafa;
  border-bottom: 1px solid #e8e8e8;
}
.fcl-title { font-weight: 600; color: #262626; font-size: 12px; }
.fcl-count { margin-left: auto; color: #8c8c8c; font-size: 11px; }
.fcl-empty { padding: 16px; text-align: center; color: #bbb; }
.fcl-body { padding: 4px 0; }
.fcl-item {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 4px 12px;
  transition: background 0.1s;
}
.fcl-item:hover { background: #fafafa; }
.fcl-type-badge {
  font-size: 10px;
  padding: 0 5px;
  border-radius: 3px;
  font-weight: 600;
  flex-shrink: 0;
  line-height: 18px;
}
.fcl-item.add .fcl-type-badge { background: #f6ffed; color: #52c41a; }
.fcl-item.modify .fcl-type-badge { background: #fff7e6; color: #fa8c16; }
.fcl-item.delete .fcl-type-badge { background: #fff2f0; color: #ff4d4f; }
.fcl-item.rename .fcl-type-badge { background: #f0f5ff; color: #1677ff; }
.fcl-path {
  font-family: 'SF Mono', 'Monaco', 'Menlo', monospace;
  color: #262626;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.fcl-summary {
  color: #8c8c8c;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  flex-shrink: 1;
  min-width: 0;
}
</style>
