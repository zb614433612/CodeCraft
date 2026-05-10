<template>
  <div class="file-editor">
    <div class="fe-header">
      <div class="fe-file-info">
        <FileOutlined class="fe-file-icon" />
        <span class="fe-file-path">{{ filePath }}</span>
        <span class="fe-lang-badge">{{ language }}</span>
        <span v-if="isDirty" class="fe-dirty-badge">已修改</span>
      </div>
      <div class="fe-actions">
        <span v-if="saveMessage" :class="['fe-save-msg', saveMsgType]">{{ saveMessage }}</span>
        <a-button type="primary" size="small" @click="handleSave" :loading="isSaving" :disabled="!isDirty">
          <template #icon><SaveOutlined /></template>
          保存
        </a-button>
      </div>
    </div>
    <div class="fe-body">
      <div class="fe-gutter">
        <div v-for="n in lineCount" :key="n" class="fe-line-num">{{ n }}</div>
      </div>
      <textarea
        ref="textareaRef"
        class="fe-textarea"
        :value="content"
        @input="handleInput"
        @keydown.tab.prevent="handleTab"
        spellcheck="false"
        wrap="off"
      ></textarea>
    </div>
    <div class="fe-footer">
      <span class="fe-footer-item">行 {{ cursorLine }}, 列 {{ cursorColumn }}</span>
      <span class="fe-footer-item">文件大小: {{ fileSize }}</span>
      <span class="fe-footer-item">{{ totalLines }} 行</span>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, watch, nextTick } from 'vue'
import { FileOutlined, SaveOutlined } from '@ant-design/icons-vue'
import { writeProjectFile } from '@/api/project'
import { message } from 'ant-design-vue'

const props = defineProps<{
  filePath: string
  content: string
  originalContent?: string
}>()

const emit = defineEmits<{
  save: [path: string, content: string]
  contentChange: [path: string, content: string]
}>()

const textareaRef = ref<HTMLTextAreaElement | null>(null)
const isSaving = ref(false)
const saveMessage = ref('')
const saveMsgType = ref<'success' | 'error'>('success')
const cursorLine = ref(1)
const cursorColumn = ref(1)

// 本地编辑的内容
const editContent = ref(props.content)

// 同步 props.content 变化到 editContent
watch(() => props.content, (val) => {
  if (val !== undefined && val !== null) {
    editContent.value = val
  }
})

const content = computed(() => editContent.value)

const isDirty = computed(() => {
  return editContent.value !== (props.originalContent ?? props.content)
})

const lineCount = computed(() => {
  if (!editContent.value) return 1
  return editContent.value.split('\n').length
})

const totalLines = computed(() => lineCount.value)

const fileSize = computed(() => {
  const bytes = new Blob([editContent.value || '']).size
  if (bytes < 1024) return bytes + ' B'
  if (bytes < 1024 * 1024) return (bytes / 1024).toFixed(1) + ' KB'
  return (bytes / (1024 * 1024)).toFixed(1) + ' MB'
})

// 语言检测
const language = computed(() => {
  const ext = props.filePath.split('.').pop()?.toLowerCase() || ''
  const langMap: Record<string, string> = {
    ts: 'TypeScript',
    tsx: 'TSX',
    js: 'JavaScript',
    jsx: 'JSX',
    vue: 'Vue',
    java: 'Java',
    py: 'Python',
    css: 'CSS',
    scss: 'SCSS',
    less: 'Less',
    html: 'HTML',
    json: 'JSON',
    xml: 'XML',
    yml: 'YAML',
    yaml: 'YAML',
    md: 'Markdown',
    sql: 'SQL',
    sh: 'Shell',
    bash: 'Bash',
    go: 'Go',
    rs: 'Rust',
    c: 'C',
    cpp: 'C++',
    h: 'C/C++ Header',
    rb: 'Ruby',
    php: 'PHP',
    swift: 'Swift',
    kt: 'Kotlin',
    dart: 'Dart',
    dockerfile: 'Dockerfile',
    gradle: 'Gradle',
    properties: 'Properties',
    txt: 'Text',
  }
  return langMap[ext] || ext.toUpperCase() || 'Text'
})

const handleInput = (_e: Event) => {
  const target = _e.target as HTMLTextAreaElement
  editContent.value = target.value
  emit('contentChange', props.filePath, editContent.value)
  updateCursor()
}

const handleTab = (_e: KeyboardEvent) => {
  const textarea = textareaRef.value
  if (!textarea) return
  const start = textarea.selectionStart
  const end = textarea.selectionEnd
  const val = editContent.value
  const newVal = val.substring(0, start) + '  ' + val.substring(end)
  editContent.value = newVal
  emit('contentChange', props.filePath, editContent.value)
  nextTick(() => {
    textarea.selectionStart = textarea.selectionEnd = start + 2
  })
}

const updateCursor = () => {
  const textarea = textareaRef.value
  if (!textarea) return
  const val = editContent.value.substring(0, textarea.selectionStart)
  const lines = val.split('\n')
  cursorLine.value = lines.length
  cursorColumn.value = lines[lines.length - 1].length + 1
}

const handleSave = async () => {
  isSaving.value = true
  saveMessage.value = ''
  try {
    const res = await writeProjectFile(props.filePath, editContent.value)
    if (res.code === 200) {
      emit('save', props.filePath, editContent.value)
      saveMessage.value = '已保存'
      saveMsgType.value = 'success'
      setTimeout(() => { saveMessage.value = '' }, 2000)
    } else {
      throw new Error(res.message || '保存失败')
    }
  } catch (e: any) {
    saveMessage.value = '保存失败: ' + (e.message || '未知错误')
    saveMsgType.value = 'error'
    message.error(saveMessage.value)
  } finally {
    isSaving.value = false
  }
}

// 当前已通过 @input 和 selectionchange 更新光标位置
// 实际光标更新由 updateCursor 在每次输入时调用
</script>

<style scoped>
.file-editor {
  display: flex;
  flex-direction: column;
  height: 100%;
  background: #1e1e1e;
  color: #d4d4d4;
  font-family: 'SF Mono', 'Monaco', 'Menlo', 'Consolas', 'Courier New', monospace;
}

/* 头部 */
.fe-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 6px 16px;
  background: #252526;
  border-bottom: 1px solid #3c3c3c;
  flex-shrink: 0;
}
.fe-file-info {
  display: flex;
  align-items: center;
  gap: 8px;
  min-width: 0;
}
.fe-file-icon {
  color: #569cd6;
  font-size: 14px;
  flex-shrink: 0;
}
.fe-file-path {
  font-size: 12px;
  color: #cccccc;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.fe-lang-badge {
  font-size: 10px;
  padding: 1px 6px;
  border-radius: 3px;
  background: #2d2d2d;
  color: #858585;
  border: 1px solid #3c3c3c;
  flex-shrink: 0;
}
.fe-dirty-badge {
  font-size: 10px;
  padding: 1px 6px;
  border-radius: 3px;
  background: #fff3cd;
  color: #856404;
  flex-shrink: 0;
}
.fe-actions {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-shrink: 0;
}
.fe-save-msg {
  font-size: 11px;
  transition: opacity 0.3s;
}
.fe-save-msg.success { color: #4ec9b0; }
.fe-save-msg.error { color: #f48771; }

/* 编辑器主体 */
.fe-body {
  flex: 1;
  display: flex;
  overflow: auto;
  position: relative;
}
.fe-gutter {
  width: 50px;
  flex-shrink: 0;
  background: #1e1e1e;
  border-right: 1px solid #2d2d2d;
  padding: 8px 0;
  user-select: none;
  overflow: hidden;
}
.fe-line-num {
  text-align: right;
  padding: 0 10px 0 0;
  font-size: 12px;
  line-height: 1.5;
  color: #858585;
  min-height: 18px;
  font-family: inherit;
}
.fe-textarea {
  flex: 1;
  border: none;
  outline: none;
  resize: none;
  padding: 8px 12px;
  font-size: 13px;
  line-height: 1.5;
  font-family: inherit;
  background: #1e1e1e;
  color: #d4d4d4;
  tab-size: 2;
  white-space: pre;
  overflow: visible;
}
.fe-textarea::selection {
  background: #264f78;
}

/* 底部状态栏 */
.fe-footer {
  display: flex;
  align-items: center;
  gap: 16px;
  padding: 3px 16px;
  background: #007acc;
  color: white;
  font-size: 11px;
  flex-shrink: 0;
}
.fe-footer-item {
  white-space: nowrap;
}
</style>
