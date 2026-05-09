<template>
  <a-modal
    :open="visible"
    title="选择工作目录"
    width="560px"
    :footer="null"
    @cancel="emit('close')"
    destroyOnClose
  >
    <div class="dir-browser">
      <!-- 路径导航 / 当前位置 -->
      <div class="dir-nav">
        <a-button size="small" type="link" @click="goToDrives" :disabled="pathStack.length === 0">
          我的电脑
        </a-button>
        <template v-for="(seg, idx) in pathStack" :key="idx">
          <span class="dir-sep">/</span>
          <a-button
            size="small"
            type="link"
            @click="navigateTo(idx)"
          >{{ seg.name }}</a-button>
        </template>
      </div>

      <!-- 当前路径完整显示 -->
      <div class="dir-current-path">{{ currentPath || '(根目录)' }}</div>

      <!-- 目录列表 -->
      <div class="dir-list" ref="listRef">
        <div v-if="loading" class="dir-loading">
          <a-spin size="small" />
          <span>加载中...</span>
        </div>
        <div v-else-if="error" class="dir-error">{{ error }}</div>
        <div
          v-for="entry in entries"
          :key="entry.path"
          :class="['dir-item', { selected: selectedPath === entry.path }]"
          @click="selectDir(entry)"
          @dblclick="enterDir(entry)"
        >
          <span class="dir-item-icon"><FolderOutlined /></span>
          <span class="dir-item-name">{{ entry.name }}</span>
          <span class="dir-item-arrow" @click.stop="enterDir(entry)">></span>
        </div>
        <div v-if="!loading && entries.length === 0" class="dir-empty">
          该目录下没有子目录
        </div>
      </div>

      <!-- 底部操作栏 -->
      <div class="dir-footer">
        <span class="dir-footer-path" :title="selectedPath">
          {{ selectedPath ? '已选: ' + selectedPath : '请选择一个目录' }}
        </span>
        <div class="dir-footer-actions">
          <a-button @click="emit('close')">取消</a-button>
          <a-button type="primary" :disabled="!selectedPath" @click="confirmSelect">确认选择</a-button>
        </div>
      </div>
    </div>
  </a-modal>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { FolderOutlined } from '@ant-design/icons-vue'
import { getDrives, getDirChildren, type DirectoryEntry } from '@/api/project'

const emit = defineEmits<{
  select: [path: string]
  close: []
}>()

defineProps<{
  visible: boolean
}>()

// 当前显示的目录条目
const entries = ref<DirectoryEntry[]>([])
const loading = ref(false)
const error = ref('')
const selectedPath = ref('')

// 路径导航栈：存储每一级的 { name, path }
const pathStack = ref<{ name: string; path: string }[]>([])
const currentPath = ref('')

// 加载盘符
const loadDrives = async () => {
  loading.value = true
  error.value = ''
  try {
    const res = await getDrives()
    if (res.code === 200 && res.data) {
      entries.value = res.data
    } else {
      error.value = res.message || '获取盘符失败'
    }
  } catch (e: any) {
    error.value = e.message || '网络错误'
  } finally {
    loading.value = false
  }
}

// 加载指定路径下的子目录
const loadChildren = async (path: string) => {
  loading.value = true
  error.value = ''
  try {
    const res = await getDirChildren(path)
    if (res.code === 200 && res.data) {
      entries.value = res.data
    } else {
      error.value = res.message || '获取目录列表失败'
    }
  } catch (e: any) {
    error.value = e.message || '网络错误'
  } finally {
    loading.value = false
  }
}

// 选择一个目录（单击）
const selectDir = (entry: DirectoryEntry) => {
  selectedPath.value = entry.path
}

// 进入目录（双击或点击箭头）
const enterDir = async (entry: DirectoryEntry) => {
  pathStack.value.push({ name: entry.name, path: entry.path })
  currentPath.value = entry.path
  selectedPath.value = entry.path
  await loadChildren(entry.path)
}

// 导航到盘符列表
const goToDrives = () => {
  pathStack.value = []
  currentPath.value = ''
  selectedPath.value = ''
  loadDrives()
}

// 导航到面包屑的指定层级
const navigateTo = (index: number) => {
  const target = pathStack.value[index]
  pathStack.value = pathStack.value.slice(0, index + 1)
  currentPath.value = target.path
  selectedPath.value = target.path
  loadChildren(target.path)
}

// 确认选择
const confirmSelect = () => {
  if (selectedPath.value) {
    emit('select', selectedPath.value)
  }
}

onMounted(() => {
  loadDrives()
})
</script>

<style scoped>
.dir-browser {
  display: flex;
  flex-direction: column;
  height: 420px;
}
.dir-nav {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 2px;
  padding: 4px 0 8px;
  border-bottom: 1px solid #f0f0f0;
  min-height: 32px;
}
.dir-nav .ant-btn-link {
  padding: 0 4px;
  height: auto;
  font-size: 12px;
}
.dir-nav .ant-btn-link:first-child {
  font-weight: 500;
}
.dir-sep {
  color: #bbb;
  font-size: 12px;
}
.dir-current-path {
  font-size: 11px;
  color: #999;
  padding: 4px 0 8px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.dir-list {
  flex: 1;
  overflow-y: auto;
  border: 1px solid #f0f0f0;
  border-radius: 4px;
  background: #fafafa;
}
.dir-loading,
.dir-error,
.dir-empty {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  height: 100%;
  color: #999;
  font-size: 13px;
}
.dir-error {
  color: #ff4d4f;
}
.dir-item {
  display: flex;
  align-items: center;
  padding: 6px 10px;
  cursor: pointer;
  transition: background 0.15s;
  border-bottom: 1px solid #f5f5f5;
}
.dir-item:last-child {
  border-bottom: none;
}
.dir-item:hover {
  background: #e6f4ff;
}
.dir-item.selected {
  background: #bae0ff;
}
.dir-item-icon {
  color: #faad14;
  margin-right: 8px;
  font-size: 14px;
  flex-shrink: 0;
}
.dir-item-name {
  flex: 1;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-size: 13px;
}
.dir-item-arrow {
  color: #bbb;
  font-size: 12px;
  padding: 0 4px;
  cursor: pointer;
  flex-shrink: 0;
}
.dir-item-arrow:hover {
  color: #1890ff;
}
.dir-footer {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 10px 0 0;
  gap: 12px;
}
.dir-footer-path {
  flex: 1;
  font-size: 11px;
  color: #888;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.dir-footer-actions {
  display: flex;
  gap: 8px;
  flex-shrink: 0;
}
</style>
