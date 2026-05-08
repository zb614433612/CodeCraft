<template>
  <div class="tree-node">
    <div
      :class="['tn-row', { active: selectedPath === node.path }]"
      @click="handleClick"
    >
      <span class="tn-arrow" v-if="node.directory">
        <span v-if="expanded">▼</span>
        <span v-else>▶</span>
      </span>
      <span class="tn-icon">
        <FolderOutlined v-if="node.directory" />
        <FileOutlined v-else />
      </span>
      <span class="tn-name" :title="node.name">{{ node.name }}</span>
    </div>
    <div v-if="node.directory && expanded && node.children && node.children.length > 0" class="tn-children">
      <TreeNode
        v-for="child in node.children"
        :key="child.path"
        :node="child"
        :selected-path="selectedPath"
        @select="(p, d) => $emit('select', p, d)"
      />
    </div>
    <div v-else-if="node.directory && expanded" class="tn-empty">(空目录)</div>
  </div>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import { FolderOutlined, FileOutlined } from '@ant-design/icons-vue'
import type { ProjectTreeNode } from '@/api/project'

const props = defineProps<{
  node: ProjectTreeNode
  selectedPath: string
}>()

const emit = defineEmits<{
  select: [path: string, isDirectory: boolean]
}>()

const expanded = ref(false)

const handleClick = () => {
  if (props.node.directory) {
    expanded.value = !expanded.value
  }
  emit('select', props.node.path, props.node.directory)
}
</script>

<style scoped>
.tree-node { user-select: none; }
.tn-row {
  display: flex;
  align-items: center;
  gap: 2px;
  padding: 3px 8px;
  cursor: pointer;
  border-radius: 4px;
  transition: background 0.1s;
}
.tn-row:hover { background: #f0f2f5; }
.tn-row.active { background: #e6f4ff; color: #1677ff; }
.tn-arrow { width: 14px; text-align: center; font-size: 9px; color: #999; flex-shrink: 0; }
.tn-icon { font-size: 14px; color: #faad14; flex-shrink: 0; }
.tn-row.active .tn-icon { color: #1677ff; }
.tn-icon .anticon-file { color: #8c8c8c; }
.tn-name { margin-left: 4px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; font-size: 12px; }
.tn-children { padding-left: 12px; }
.tn-empty { padding: 2px 8px 2px 26px; font-size: 11px; color: #bbb; }
</style>
