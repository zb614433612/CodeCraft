<template>
  <div class="file-tree">
    <div class="ft-header" v-if="tree || loading">
      <span class="ft-current-path">{{ displayRoot || '加载中...' }}</span>
      <ReloadOutlined :class="['ft-reload', { loading: loading }]" @click="fetchTree" />
    </div>
    <div v-if="loading" class="ft-loading">
      <a-spin size="small" />
      <span>加载文件树...</span>
    </div>
    <div v-else-if="error" class="ft-error">{{ error }}</div>
    <div v-else-if="!tree" class="ft-empty">
      <span>请设置工作目录后点击「应用」</span>
    </div>
    <div v-else-if="isEmpty" class="ft-empty">
      <span>该目录下没有可显示的文件或文件夹</span>
    </div>
    <div v-else class="ft-tree">
      <TreeNode
        v-for="node in tree!.children!"
        :key="node.path"
        :node="node"
        :selected-path="selectedPath"
        @select="onSelect"
      />
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, watch } from 'vue'
import { getProjectTree, type ProjectTreeNode } from '@/api/project'
import { ReloadOutlined } from '@ant-design/icons-vue'
import TreeNode from './TreeNode.vue'

const emit = defineEmits<{
  select: [path: string, isDirectory: boolean]
}>()

const props = defineProps<{
  rootPath?: string
}>()

const tree = ref<ProjectTreeNode | null>(null)
const loading = ref(false)
const error = ref('')
const selectedPath = ref('')

const displayRoot = computed(() => {
  if (!tree.value?.path || tree.value.path === '.') return ''
  return tree.value.path
})

const isEmpty = computed(() => {
  return tree.value && (!tree.value.children || tree.value.children.length === 0)
})

const fetchTree = async () => {
  if (!props.rootPath) return
  loading.value = true
  error.value = ''
  try {
    const res = await getProjectTree(props.rootPath, 10)
    if (res.code === 200 && res.data) {
      tree.value = res.data
    } else {
      error.value = res.message || '获取文件树失败'
      tree.value = null
    }
  } catch (e: any) {
    error.value = e.message || '网络错误'
    tree.value = null
  } finally {
    loading.value = false
  }
}

// 监听 rootPath prop 变化，自动加载
watch(() => props.rootPath, (newPath) => {
  if (newPath) {
    tree.value = null
    error.value = ''
    fetchTree()
  }
})

defineExpose({ fetchTree })

const onSelect = (path: string, isDirectory: boolean) => {
  selectedPath.value = path
  emit('select', path, isDirectory)
}
</script>

<style scoped>
.file-tree {
  height: 100%;
  overflow-y: auto;
  font-size: 13px;
}
.ft-header {
  display: flex;
  align-items: center;
  gap: 4px;
  padding: 6px 8px;
  border-bottom: 1px solid #eef2f7;
  background: #f8fafc;
}
.ft-current-path {
  flex: 1;
  font-size: 11px;
  color: #8c8c8c;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.ft-reload {
  cursor: pointer;
  font-size: 14px;
  color: #8c8c8c;
  flex-shrink: 0;
  transition: color 0.2s, transform 0.2s;
}
.ft-reload:hover { color: #1890ff; }
.ft-reload.loading { animation: spin 1s linear infinite; color: #1890ff; }
@keyframes spin { from { transform: rotate(0deg); } to { transform: rotate(360deg); } }
.ft-loading {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  padding: 30px 12px;
  color: #8c8c8c;
}
.ft-error {
  padding: 20px 12px;
  color: #ff4d4f;
  text-align: center;
  font-size: 12px;
}
.ft-empty {
  padding: 30px 12px;
  text-align: center;
  color: #bbb;
  font-size: 12px;
}
.ft-tree {
  padding: 4px 0;
}
</style>
