<template>
  <div class="file-editor">
    <div class="fe-header">
      <div class="fe-file-info">
        <FileOutlined class="fe-file-icon" />
        <span class="fe-file-path">{{ filePath }}</span>
        <span class="fe-lang-badge">{{ languageLabel }}</span>
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
      <div class="fe-editor-wrapper">
        <pre class="fe-highlight-layer" ref="highlightRef"><code class="hljs" v-html="highlightedCode"></code></pre>
        <textarea
          ref="textareaRef"
          class="fe-textarea"
          :value="content"
          @input="handleInput"
          @scroll="syncScroll"
          @keydown.tab.prevent="handleTab"
          @keydown="handleKeyDown"
          @click="updateCursor"
          @keyup="updateCursor"
          spellcheck="false"
          wrap="off"
        ></textarea>
      </div>
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
import hljs from 'highlight.js'

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
const highlightRef = ref<HTMLElement | null>(null)
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

// 语言检测（hljs 注册名）
const detectLanguage = (filePath: string): string => {
  const ext = filePath.split('.').pop()?.toLowerCase() || ''
  const extMap: Record<string, string> = {
    ts: 'typescript', tsx: 'typescript', js: 'javascript', jsx: 'javascript',
    vue: 'vue', java: 'java', py: 'python', css: 'css', scss: 'scss',
    less: 'less', html: 'html', htm: 'html', json: 'json', xml: 'xml',
    yml: 'yaml', yaml: 'yaml', md: 'markdown', sql: 'sql',
    sh: 'bash', bash: 'bash', zsh: 'bash', go: 'go', rs: 'rust',
    c: 'c', cpp: 'cpp', h: 'c', hpp: 'cpp', rb: 'ruby', php: 'php',
    swift: 'swift', kt: 'kotlin', kts: 'kotlin', dart: 'dart',
    dockerfile: 'dockerfile', gradle: 'gradle', properties: 'properties',
    txt: 'plaintext', gitignore: 'plaintext', env: 'plaintext',
    svelte: 'html',
  }
  return extMap[ext] || ext || 'plaintext'
}

const languageId = computed(() => detectLanguage(props.filePath))

const languageLabel = computed(() => {
  const labelMap: Record<string, string> = {
    typescript: 'TypeScript', javascript: 'JavaScript', java: 'Java',
    python: 'Python', css: 'CSS', scss: 'SCSS', less: 'Less',
    html: 'HTML', json: 'JSON', xml: 'XML', yaml: 'YAML',
    markdown: 'Markdown', sql: 'SQL', bash: 'Bash',
    go: 'Go', rust: 'Rust', c: 'C', cpp: 'C++', ruby: 'Ruby',
    php: 'PHP', swift: 'Swift', kotlin: 'Kotlin', dart: 'Dart',
    dockerfile: 'Dockerfile', gradle: 'Gradle', properties: 'Properties',
    plaintext: 'Text',
  }
  return labelMap[languageId.value] || languageId.value.toUpperCase() || 'Text'
})

// 语法高亮渲染
const highlightedCode = computed(() => {
  const code = editContent.value || ''
  if (!code) return ''
  try {
    const lang = languageId.value
    const isAutoDetect = lang === 'plaintext'
    let result: string
    if (isAutoDetect) {
      const auto = hljs.highlightAuto(code)
      result = auto.value
    } else {
      if (hljs.getLanguage(lang)) {
        result = hljs.highlight(code, { language: lang }).value
      } else {
        result = hljs.highlightAuto(code).value
      }
    }
    // 在行末添加换行标记保证空行也被渲染
    return result
  } catch {
    // 高亮失败时转义输出
    return escapeHtml(code)
  }
})

function escapeHtml(text: string): string {
  return text
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
}

// 滚动同步：textarea 和 highlight 层保持同步
const syncScroll = () => {
  const textarea = textareaRef.value
  const highlight = highlightRef.value?.parentElement
  if (textarea && highlight) {
    highlight.scrollTop = textarea.scrollTop
    highlight.scrollLeft = textarea.scrollLeft
  }
}

const handleInput = (_e: Event) => {
  const target = _e.target as HTMLTextAreaElement
  editContent.value = target.value
  emit('contentChange', props.filePath, editContent.value)
  updateCursor()
}

const handleTab = () => {
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
    updateCursor()
  })
}

const handleKeyDown = () => {
  // 在下次 tick 同步 scroll，确保 textarea 已更新滚动位置
  nextTick(() => syncScroll())
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
  overflow: hidden;
  position: relative;
}
.fe-gutter {
  width: 50px;
  flex-shrink: 0;
  background: #1e1e1e;
  border-right: 1px solid #2d2d2d;
  padding: 0;
  user-select: none;
  overflow: hidden;
  padding-top: 8px;
}
.fe-line-num {
  text-align: right;
  padding: 0 10px 0 0;
  font-size: 12px;
  line-height: 1.5;
  color: #858585;
  min-height: 18px;
  font-family: inherit;
  direction: ltr;
}

/* 叠加层编辑器 */
.fe-editor-wrapper {
  flex: 1;
  position: relative;
  overflow: hidden;
}
.fe-highlight-layer {
  position: absolute;
  top: 0;
  left: 0;
  right: 0;
  bottom: 0;
  margin: 0;
  padding: 8px 12px;
  font-size: 13px;
  line-height: 1.5;
  font-family: inherit;
  white-space: pre;
  tab-size: 2;
  background: #1e1e1e;
  color: #d4d4d4;
  overflow: auto;
  pointer-events: none;
  border: none;
  text-align: left;
}
.fe-highlight-layer code {
  font-family: inherit;
  font-size: inherit;
  line-height: inherit;
  background: transparent !important;
}
.fe-textarea {
  position: relative;
  z-index: 1;
  width: 100%;
  height: 100%;
  border: none;
  outline: none;
  resize: none;
  padding: 8px 12px;
  font-size: 13px;
  line-height: 1.5;
  font-family: inherit;
  tab-size: 2;
  white-space: pre;
  overflow: auto;
  background: transparent;
  color: transparent;
  caret-color: #aeafad;
  -webkit-text-fill-color: transparent;
  text-align: left;
}
.fe-textarea::selection {
  background: #264f78;
  -webkit-text-fill-color: transparent;
}
.fe-textarea::-moz-selection {
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

<style>
/* 全局 hljs 暗色主题覆盖 - 确保高亮层中的代码有颜色 */
.fe-highlight-layer .hljs {
  background: transparent !important;
  color: #d4d4d4;
  padding: 0;
}
.fe-highlight-layer .hljs-keyword { color: #569cd6; }
.fe-highlight-layer .hljs-string { color: #ce9178; }
.fe-highlight-layer .hljs-number { color: #b5cea8; }
.fe-highlight-layer .hljs-comment { color: #6a9955; font-style: italic; }
.fe-highlight-layer .hljs-built_in { color: #4ec9b0; }
.fe-highlight-layer .hljs-type { color: #4ec9b0; }
.fe-highlight-layer .hljs-literal { color: #569cd6; }
.fe-highlight-layer .hljs-attr { color: #9cdcfe; }
.fe-highlight-layer .hljs-attribute { color: #9cdcfe; }
.fe-highlight-layer .hljs-title { color: #dcdcaa; }
.fe-highlight-layer .hljs-title.function_ { color: #dcdcaa; }
.fe-highlight-layer .hljs-title.class_ { color: #4ec9b0; }
.fe-highlight-layer .hljs-property { color: #9cdcfe; }
.fe-highlight-layer .hljs-selector-tag { color: #569cd6; }
.fe-highlight-layer .hljs-selector-class { color: #d7ba7d; }
.fe-highlight-layer .hljs-selector-id { color: #d7ba7d; }
.fe-highlight-layer .hljs-tag { color: #569cd6; }
.fe-highlight-layer .hljs-name { color: #569cd6; }
.fe-highlight-layer .hljs-variable { color: #9cdcfe; }
.fe-highlight-layer .hljs-template-variable { color: #9cdcfe; }
.fe-highlight-layer .hljs-template-tag { color: #569cd6; }
.fe-highlight-layer .hljs-deletion { background: #400; }
.fe-highlight-layer .hljs-addition { background: #040; }
.fe-highlight-layer .hljs-meta { color: #d4d4d4; }
.fe-highlight-layer .hljs-meta .hljs-keyword { color: #569cd6; }
.fe-highlight-layer .hljs-doctag { color: #608b4e; }
.fe-highlight-layer .hljs-regexp { color: #d16969; }
.fe-highlight-layer .hljs-link { color: #569cd6; text-decoration: underline; }
.fe-highlight-layer .hljs-symbol { color: #569cd6; }
.fe-highlight-layer .hljs-bullet { color: #d7ba7d; }
.fe-highlight-layer .hljs-code { color: #d4d4d4; }
.fe-highlight-layer .hljs-emphasis { font-style: italic; }
.fe-highlight-layer .hljs-strong { font-weight: bold; }
.fe-highlight-layer .hljs-formula { color: #d4d4d4; }
.fe-highlight-layer .hljs-params { color: #9cdcfe; }
.fe-highlight-layer .hljs-section { color: #dcdcaa; font-weight: bold; }
.fe-highlight-layer .hljs-quote { color: #6a9955; font-style: italic; }
</style>
