<template>
  <div class="right-toolbar-wrapper">
    <!-- 竖排图标按钮栏 -->
    <div class="right-toolbar-strip">
      <div
        v-for="item in toolbarItems"
        :key="item.key"
        :class="['toolbar-btn', { active: activePanel === item.key }]"
        @click="togglePanel(item.key)"
        :title="item.label"
      >
        <component :is="item.icon" />
        <span class="toolbar-btn-label">{{ item.label }}</span>
      </div>
    </div>

    <!-- 抽屉面板 -->
    <transition name="drawer-slide">
      <div
        v-if="activePanel"
        class="right-drawer"
        :style="{ width: drawerWidth + 'px' }"
      >
        <!-- 拖拽调整宽度的手柄 -->
        <div
          class="drawer-resize-handle"
          @mousedown.prevent="startResize"
        ></div>

        <!-- 面板头部 -->
        <div class="drawer-header">
          <span class="drawer-title">{{ activeItem?.label }}</span>
          <span class="drawer-close" @click="closePanel">×</span>
        </div>

        <!-- 面板内容 -->
        <div class="drawer-body">
          <!-- 文件面板 -->
          <div v-if="activePanel === 'files'" class="drawer-panel-content filetree-panel">
            <div class="project-root-bar">
              <span class="project-root-label">工作目录</span>
              <div class="project-root-row">
                <a-input
                  :value="workDir"
                  placeholder="选择目录"
                  size="small"
                  readonly
                />
                <a-button size="small" type="primary" @click="$emit('selectProjectRoot')">
                  <FolderOpenOutlined />
                </a-button>
              </div>
            </div>
            <FileTree
              :root-path="fileTreeLoadPath"
              @select="(p: string, d: boolean) => $emit('fileSelect', p, d)"
              @dblclick="(p: string, d: boolean) => $emit('fileDblClick', p, d)"
            />
          </div>

          <!-- Git 面板 -->
          <div v-if="activePanel === 'git'" class="drawer-panel-content git-panel">
            <GitSidebar
              :project-root="projectRoot"
              @file-dblclick="(fp: string, pr: string) => $emit('gitFileDblClick', fp, pr)"
            />
          </div>

          <!-- 技能面板 -->
          <div v-if="activePanel === 'skills'" class="drawer-panel-content skill-panel">
            <SkillList :agent-config-id="agentConfigId" />
          </div>

        </div>
      </div>
    </transition>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onBeforeUnmount, h, type Component } from 'vue'
import {
  FolderOpenOutlined,
  BranchesOutlined,
  ThunderboltOutlined
} from '@ant-design/icons-vue'
import FileTree from '@/components/FileTree.vue'
import GitSidebar from '@/components/GitSidebar.vue'
import SkillList from '@/components/SkillList.vue'

const props = defineProps<{
  projectRoot?: string
  workDir?: string
  fileTreeLoadPath?: string
  agentConfigId?: number | null
}>()

const emit = defineEmits<{
  selectProjectRoot: []
  fileSelect: [path: string, isDirectory: boolean]
  fileDblClick: [path: string, isDirectory: boolean]
  gitFileDblClick: [filePath: string, projectRoot: string]
}>()

interface ToolbarItem {
  key: string
  label: string
  icon: Component
}

const toolbarItems: ToolbarItem[] = [
  { key: 'files', label: '文件', icon: FolderOpenOutlined },
  { key: 'git', label: 'Git', icon: BranchesOutlined },
  { key: 'skills', label: '技能', icon: ThunderboltOutlined }
]

const activePanel = ref<string | null>(null)
const drawerWidth = ref(340)

const activeItem = computed(() => toolbarItems.find(t => t.key === activePanel.value))

const togglePanel = (key: string) => {
  if (activePanel.value === key) {
    activePanel.value = null
  } else {
    activePanel.value = key
  }
}

const closePanel = () => {
  activePanel.value = null
}

// ===== 拖拽调整抽屉宽度 =====
let resizeStartX = 0
let resizeStartWidth = 0

const startResize = (e: MouseEvent) => {
  resizeStartX = e.clientX
  resizeStartWidth = drawerWidth.value
  document.addEventListener('mousemove', onResize)
  document.addEventListener('mouseup', stopResize)
}

const onResize = (e: MouseEvent) => {
  const delta = resizeStartX - e.clientX // 向左拖增大宽度
  const newWidth = resizeStartWidth + delta
  const minW = 240
  const maxW = Math.min(window.innerWidth * 0.55, 600)
  drawerWidth.value = Math.max(minW, Math.min(maxW, newWidth))
}

const stopResize = () => {
  document.removeEventListener('mousemove', onResize)
  document.removeEventListener('mouseup', stopResize)
}

onBeforeUnmount(() => {
  document.removeEventListener('mousemove', onResize)
  document.removeEventListener('mouseup', stopResize)
})
</script>

<style scoped>
/* ===== 右侧工具栏容器 ===== */
.right-toolbar-wrapper {
  display: flex;
  flex-direction: row-reverse;
  height: 100%;
  flex-shrink: 0;
  position: relative;
  z-index: 20;
}

/* ===== 竖排图标按钮栏 ===== */
.right-toolbar-strip {
  width: 44px;
  background: var(--bg-card, #ffffff);
  border-left: 1px solid var(--border, #e8e5f0);
  display: flex;
  flex-direction: column;
  align-items: center;
  padding: 10px 0;
  gap: 4px;
  flex-shrink: 0;
}

.toolbar-btn {
  width: 36px;
  height: 36px;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  border-radius: 10px;
  cursor: pointer;
  color: var(--text-3, #9696aa);
  font-size: 20px;
  transition: all 0.2s cubic-bezier(0.16, 1, 0.3, 1);
  position: relative;
  user-select: none;
  background: transparent;
  border: none;
  outline: none;
}

.toolbar-btn:hover {
  color: var(--accent, #8b5cf6);
  background: var(--accent-lt, rgba(139, 92, 246, 0.08));
}

.toolbar-btn.active {
  color: var(--accent, #8b5cf6);
  background: var(--accent-lt, rgba(139, 92, 246, 0.12));
}

.toolbar-btn.active::before {
  content: '';
  position: absolute;
  left: 2px;
  top: 8px;
  bottom: 8px;
  width: 3px;
  background: var(--accent, #8b5cf6);
  border-radius: 0 3px 3px 0;
}

.toolbar-btn-label {
  font-size: 9px;
  line-height: 1;
  margin-top: 1px;
  font-weight: 500;
  white-space: nowrap;
}

/* ===== 抽屉面板 ===== */
.right-drawer {
  background: var(--bg-card, #ffffff);
  border-left: 1px solid var(--border, #e8e5f0);
  display: flex;
  flex-direction: column;
  height: 100%;
  position: relative;
  box-shadow: -4px 0 20px rgba(0, 0, 0, 0.06);
  overflow: hidden;
}

/* 拖拽调整宽度手柄 */
.drawer-resize-handle {
  position: absolute;
  left: 0;
  top: 0;
  bottom: 0;
  width: 5px;
  cursor: ew-resize;
  z-index: 10;
  transition: background 0.15s;
}

.drawer-resize-handle:hover {
  background: rgba(139, 92, 246, 0.3);
}

/* 抽屉头部 */
.drawer-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 10px 14px;
  border-bottom: 1px solid var(--border, #e8e5f0);
  flex-shrink: 0;
  background: var(--bg-root, #f5f3fa);
  user-select: none;
}

.drawer-title {
  font-size: 13px;
  font-weight: 700;
  color: var(--text-1, #1a1a2e);
}

.drawer-close {
  width: 24px;
  height: 24px;
  display: flex;
  align-items: center;
  justify-content: center;
  border-radius: 6px;
  cursor: pointer;
  font-size: 18px;
  color: var(--text-3, #9696aa);
  transition: all 0.15s;
  line-height: 1;
}

.drawer-close:hover {
  background: rgba(239, 68, 68, 0.1);
  color: #ef4444;
}

/* 抽屉内容 */
.drawer-body {
  flex: 1;
  overflow: hidden;
  display: flex;
  flex-direction: column;
}

.drawer-panel-content {
  flex: 1;
  overflow-y: auto;
  display: flex;
  flex-direction: column;
  min-height: 0;
}

/* 自定义滚动条 */
.drawer-panel-content::-webkit-scrollbar {
  width: 4px;
}

.drawer-panel-content::-webkit-scrollbar-thumb {
  background: #dcd8ea;
  border-radius: 2px;
}

/* 文件面板的工作目录栏 */
.project-root-bar {
  padding: 10px 14px;
  border-bottom: 1px solid var(--border, #e8e5f0);
  background: var(--bg-card, #ffffff);
  flex-shrink: 0;
}

.project-root-label {
  font-size: 11px;
  font-weight: 700;
  color: var(--text-3, #9696aa);
  text-transform: uppercase;
  letter-spacing: 0.5px;
  margin-bottom: 6px;
  display: block;
}

.project-root-row {
  display: flex;
  gap: 6px;
  align-items: center;
}

.project-root-row :deep(.ant-input) {
  font-size: 12px;
}

/* ===== 抽屉滑入/滑出动画 ===== */
.drawer-slide-enter-active {
  animation: drawerSlideIn 0.25s cubic-bezier(0.16, 1, 0.3, 1);
}

.drawer-slide-leave-active {
  animation: drawerSlideIn 0.2s cubic-bezier(0.16, 1, 0.3, 1) reverse;
}

@keyframes drawerSlideIn {
  from {
    width: 0 !important;
    opacity: 0;
  }
  to {
    opacity: 1;
  }
}

/* ===== 暗色模式适配 ===== */
[data-theme="dark"] .right-toolbar-strip {
  background: #1a1925;
  border-color: #2a2838;
}

[data-theme="dark"] .right-drawer {
  background: #1a1925;
  border-color: #2a2838;
}

[data-theme="dark"] .drawer-header {
  background: #121117;
  border-color: #2a2838;
}

[data-theme="dark"] .project-root-bar {
  background: #1a1925;
  border-color: #2a2838;
}

[data-theme="dark"] .toolbar-btn:hover {
  background: rgba(139, 92, 246, 0.1);
}

[data-theme="dark"] .toolbar-btn.active {
  background: rgba(139, 92, 246, 0.1);
}

[data-theme="dark"] .drawer-resize-handle:hover {
  background: rgba(139, 92, 246, 0.2);
}

[data-theme="dark"] .drawer-panel-content::-webkit-scrollbar-thumb {
  background: #3a3850;
}
</style>
