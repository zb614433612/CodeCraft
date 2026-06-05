<template>
  <div class="tree-node">
    <div
      :class="['tn-row', { active: selectedPath === node.path, compact: isPassThrough }]"
      @click="handleClick"
      @dblclick="handleDblClick"
    >
      <!-- 展开/折叠箭头（仅目录显示） -->
      <span class="tn-arrow" v-if="node.directory">
        <span v-if="expanded">▼</span>
        <span v-else>▶</span>
      </span>
      <!-- 文件/文件夹图标 -->
      <span class="tn-icon">
        <FolderOutlined v-if="node.directory" />
        <FileOutlined v-else />
      </span>
      <!-- 名称：紧凑模式下显示合并后的路径链 -->
      <span class="tn-name" :title="node.path">
        <template v-if="isPassThrough">
          <span v-for="(part, i) in compactParts" :key="i">
            <span class="tn-compact-seg">{{ part }}</span>
            <span v-if="i < compactParts.length - 1" class="tn-compact-sep">/</span>
          </span>
        </template>
        <template v-else>{{ node.name }}</template>
      </span>
      <!-- 紧凑模式提示 -->
      <span v-if="isPassThrough" class="tn-compact-dot" title="已折叠中间目录">…</span>
    </div>

    <!-- 子节点：紧凑模式下使用 resolvedNode 的 children -->
    <div
      v-if="node.directory && expanded && resolvedNode.children && resolvedNode.children.length > 0"
      class="tn-children"
    >
      <TreeNode
        v-for="child in resolvedNode.children"
        :key="child.path"
        :node="child"
        :selected-path="selectedPath"
        @select="(p, d) => $emit('select', p, d)"
        @dblclick="(p, d) => $emit('dblclick', p, d)"
      />
    </div>
    <div
      v-else-if="node.directory && expanded && (!resolvedNode.children || resolvedNode.children.length === 0)"
      class="tn-empty"
    >(空目录)</div>
  </div>
</template>

<script setup lang="ts">
import { ref, computed } from 'vue'
import { FolderOutlined, FileOutlined } from '@ant-design/icons-vue'
import type { ProjectTreeNode } from '@/api/project'

const props = defineProps<{
  node: ProjectTreeNode
  selectedPath: string
}>()

const emit = defineEmits<{
  select: [path: string, isDirectory: boolean]
  dblclick: [path: string, isDirectory: boolean]
}>()

// ─── 紧凑中间目录（类似 IDEA Compact Middle Packages）───

/** 判断节点是否为"透传目录"：仅含 1 个子目录，无其他文件/文件夹 */
function isPassThroughNode(node: ProjectTreeNode): boolean {
  return (
    node.directory === true &&
    Array.isArray(node.children) &&
    node.children.length === 1 &&
    node.children[0].directory === true
  )
}

/** 沿透传链收集所有节点名，返回名称数组 */
function collectCompactChain(node: ProjectTreeNode): string[] {
  const chain: string[] = []
  let current: ProjectTreeNode | undefined = node
  while (current && isPassThroughNode(current)) {
    chain.push(current.name)
    current = current.children![0]
  }
  if (current) {
    chain.push(current.name)
  }
  return chain
}

/** 返回链末端第一个非透传节点 */
function resolveCompactNode(node: ProjectTreeNode): ProjectTreeNode {
  let current = node
  while (isPassThroughNode(current)) {
    current = current.children![0]
  }
  return current
}

const isPassThrough = computed(() => isPassThroughNode(props.node))

const compactParts = computed(() => {
  if (!isPassThrough.value) return []
  return collectCompactChain(props.node)
})

const displayName = computed(() => {
  if (isPassThrough.value) {
    return compactParts.value.join(' / ')
  }
  return props.node.name
})

const resolvedNode = computed(() => {
  if (isPassThrough.value) {
    return resolveCompactNode(props.node)
  }
  return props.node
})

// ─── 展开/折叠 ───

const expanded = ref(false)

const handleClick = () => {
  if (props.node.directory) {
    expanded.value = !expanded.value
  }
  emit('select', props.node.path, props.node.directory)
}

const handleDblClick = () => {
  if (!props.node.directory) {
    emit('dblclick', props.node.path, props.node.directory)
  }
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
  color: var(--text-2, #333);
}
.tn-row:hover { background: var(--bg-hover, #f0f2f5); }
.tn-row.active { background: var(--accent-lt, #e6f4ff); color: var(--accent, #1677ff); }

.tn-arrow {
  width: 14px;
  text-align: center;
  font-size: 9px;
  color: var(--text-3, #999);
  flex-shrink: 0;
}

.tn-icon {
  font-size: 14px;
  color: #faad14;
  flex-shrink: 0;
}
.tn-row.active .tn-icon { color: var(--accent, #1677ff); }
.tn-icon :deep(.anticon-file) { color: var(--text-3, #8c8c8c); }

.tn-name {
  margin-left: 4px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-size: 12px;
}

/* 紧凑目录链的分隔符 */
.tn-compact-sep {
  color: var(--text-3, #999);
  margin: 0 1px;
  font-weight: 400;
}

/* 紧凑模式提示点 */
.tn-compact-dot {
  font-size: 10px;
  color: var(--text-3, #999);
  margin-left: 2px;
  flex-shrink: 0;
}

.tn-children { padding-left: 12px; }
.tn-empty { padding: 2px 8px 2px 26px; font-size: 11px; color: var(--text-3, #bbb); }

/* ===== 暗色模式适配 ===== */
[data-theme="dark"] .tn-row { color: #c9c9d9; }
[data-theme="dark"] .tn-row:hover { background: #2a2838; }
[data-theme="dark"] .tn-row.active { background: rgba(139, 92, 246, 0.15); color: #a78bfa; }
[data-theme="dark"] .tn-arrow { color: #6b6b80; }
[data-theme="dark"] .tn-row.active .tn-icon { color: #a78bfa; }
[data-theme="dark"] .tn-icon :deep(.anticon-file) { color: #6b6b80; }
[data-theme="dark"] .tn-empty { color: #6b6b80; }
[data-theme="dark"] .tn-compact-sep { color: #6b6b80; }
[data-theme="dark"] .tn-compact-dot { color: #6b6b80; }
</style>
