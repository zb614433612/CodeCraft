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
      <div class="fe-gutter" ref="gutterRef">
        <div class="fe-gutter-inner">
          <div
            v-for="n in lineCount"
            :key="n"
            :class="['fe-line-num', getHighlightClass(n)]"
          >{{ n }}</div>
        </div>
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
  /** 高亮标记：红/绿行号，add 类型可附带 content */
  highlightLines?: { line: number; type: 'remove' | 'add'; content?: string }[]
}>()

const emit = defineEmits<{
  save: [path: string, content: string]
  contentChange: [path: string, content: string]
}>()

const textareaRef = ref<HTMLTextAreaElement | null>(null)
const highlightRef = ref<HTMLElement | null>(null)
const gutterRef = ref<HTMLElement | null>(null)
const isSaving = ref(false)
const saveMessage = ref('')
const saveMsgType = ref<'success' | 'error'>('success')
const cursorLine = ref(1)
const cursorColumn = ref(1)

// 本地编辑的内容
const editContent = ref(props.content)

// 同步 props.content 变化到 editContent（规范化换行符）
watch(() => props.content, (val) => {
  if (val !== undefined && val !== null) {
    editContent.value = val.replace(/\r\n/g, '\n').replace(/\r/g, '\n')
  }
})

const content = computed(() => editContent.value)

const isDirty = computed(() => {
  return editContent.value !== (props.originalContent ?? props.content)
})

const lineCount = computed(() => {
  if (!editContent.value) return 1
  // ★ 规范化换行符，与 highlightedCode 保持一致
  return editContent.value.replace(/\r\n/g, '\n').replace(/\r/g, '\n').split('\n').length
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

// ★ 行高亮集
const diffRemoveSet = computed(() => {
  const set = new Set<number>()
  if (props.highlightLines) {
    for (const h of props.highlightLines) {
      if (h.type === 'remove') set.add(h.line)
    }
  }
  return set
})
const diffAddSet = computed(() => {
  const set = new Set<number>()
  if (props.highlightLines) {
    for (const h of props.highlightLines) {
      if (h.type === 'add') set.add(h.line)
    }
  }
  return set
})
const getHighlightClass = (lineNum: number): string => {
  if (diffRemoveSet.value.has(lineNum)) return 'fe-line-remove'
  if (diffAddSet.value.has(lineNum)) return 'fe-line-add'
  return ''
}

// 语法高亮渲染（逐行高亮 + 行级红绿标记）
const highlightedCode = computed(() => {
  const code = editContent.value || ''
  if (!code) return ''
  try {
    const lang = languageId.value
    const normalizedCode = code.replace(/\r\n/g, '\n').replace(/\r/g, '\n')
    const lines = normalizedCode.split('\n')
    const result = lines.map((line, i) => {
      const lineNum = i + 1
      let hlClass = ''
      if (diffRemoveSet.value.has(lineNum)) hlClass = ' fe-line-remove'
      else if (diffAddSet.value.has(lineNum)) hlClass = ' fe-line-add'

      if (line === '') {
        return `<span class="fe-code-line${hlClass}">&nbsp;</span>`
      }
      let html: string
      try {
        if (lang === 'plaintext') {
          html = escapeHtml(line)
        } else {
          html = hljs.highlight(line, { language: lang }).value
        }
      } catch {
        html = escapeHtml(line)
      }
      html = html.replace(/\r?\n/g, '')
      if (!html) html = '&nbsp;'
      return `<span class="fe-code-line${hlClass}">${html}</span>`
    }).join('')
    return result
  } catch {
    return escapeHtml(code)
  }
})

function escapeHtml(text: string): string {
  return text
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
}

// 滚动同步：textarea → highlight + gutter
const syncScroll = () => {
  const textarea = textareaRef.value
  const highlight = highlightRef.value
  const gutter = gutterRef.value
  if (!textarea) return
  if (highlight) {
    highlight.scrollTop = textarea.scrollTop
    highlight.scrollLeft = textarea.scrollLeft
  }
  if (gutter) {
    gutter.scrollTop = textarea.scrollTop
  }
}

const handleInput = (_e: Event) => {
  const target = _e.target as HTMLTextAreaElement
  // ★ 规范化换行符，确保与高亮层一致
  editContent.value = target.value.replace(/\r\n/g, '\n').replace(/\r/g, '\n')
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
  min-height: 0;
}
.fe-gutter {
  width: 50px;
  flex-shrink: 0;
  background: #1e1e1e;
  border-right: 1px solid #2d2d2d;
  padding: 0;
  user-select: none;
  overflow: hidden;
  position: relative;
}
.fe-gutter-inner {
  padding-top: 8px;
  min-height: 100%;
}
.fe-line-num {
  text-align: right;
  padding: 0 10px 0 0;
  font-size: 12px;
  line-height: 20px;
  color: #858585;
  min-height: 20px;
  font-family: inherit;
  direction: ltr;
}

/* 叠加层编辑器 - 绝对定位，textarea 驱动滚动 */
.fe-editor-wrapper {
  flex: 1;
  position: relative;
  min-height: 0;
  min-width: 0;
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
  line-height: 20px;
  font-family: inherit;
  white-space: pre;
  tab-size: 2;
  background: #1e1e1e;
  overflow: hidden;
  pointer-events: none;
  user-select: none;
  -webkit-user-select: none;
  border: none;
  text-align: left;
  box-sizing: border-box;
  letter-spacing: normal;
  word-spacing: normal;
  font-variant-ligatures: none;
  font-kerning: none;
  -webkit-font-smoothing: antialiased;
  -moz-osx-font-smoothing: grayscale;
  text-rendering: optimizeSpeed;
}
.fe-highlight-layer code {
  display: block;
  padding: 0;
  margin: 0;
  font-family: inherit;
  font-size: inherit;
  line-height: inherit;
  background: transparent !important;
  letter-spacing: normal;
  word-spacing: normal;
  font-variant-ligatures: none;
  font-kerning: none;
}
.fe-textarea {
  position: absolute;
  z-index: 1;
  top: 0;
  left: 0;
  right: 0;
  bottom: 0;
  border: none;
  outline: none;
  resize: none;
  padding: 8px 12px;
  font-size: 13px;
  line-height: 20px;
  font-family: inherit;
  tab-size: 2;
  white-space: pre;
  overflow: auto;
  background: transparent;
  color: transparent;
  caret-color: #aeafad;
  text-align: left;
  box-sizing: border-box;
  letter-spacing: normal;
  word-spacing: normal;
  font-variant-ligatures: none;
  font-kerning: none;
  -webkit-font-smoothing: antialiased;
  -moz-osx-font-smoothing: grayscale;
  text-rendering: optimizeSpeed;
}
/* ::selection 移入下方非 scoped 块以避免 scoped 属性选择器导致伪元素失效 */

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
/* ===== 全局样式（v-html 动态内容必须用非 scoped 样式） ===== */

/* 行号区的红绿标记（template 元素，scoped 也 OK，但放这里统一管理） */
.fe-line-num.fe-line-remove { background: rgba(255, 77, 79, 0.25); color: #ff4d4f; }
.fe-line-num.fe-line-add { background: rgba(82, 196, 26, 0.2); color: #52c41a; }

/* 代码区行级红绿标记（v-html 动态内容，须在全局样式中） */
.fe-code-line {
  display: block;
  min-height: 20px;
  line-height: 20px;
}
.fe-code-line.fe-line-remove { background: rgba(255, 77, 79, 0.25); }
.fe-code-line.fe-line-add { background: rgba(82, 196, 26, 0.2); }

/* hljs 暗色主题 */
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

/* ===== 亮色模式覆盖 ===== */
[data-theme="light"] .file-editor {
  background: #ffffff;
  color: #1e1e1e;
}
[data-theme="light"] .fe-textarea { color: transparent; caret-color: #333333; }
[data-theme="light"] .fe-header {
  background: #f3f3f3;
  border-bottom-color: #e0e0e0;
}
[data-theme="light"] .fe-file-path { color: #333333; }
[data-theme="light"] .fe-lang-badge {
  background: #e8e8e8;
  color: #666666;
  border-color: #d0d0d0;
}
[data-theme="light"] .fe-gutter {
  background: #fafafa;
  border-right-color: #e0e0e0;
}
[data-theme="light"] .fe-line-num { color: #999999; }
[data-theme="light"] .fe-highlight-layer {
  background: #ffffff;
}
[data-theme="light"] .fe-highlight-layer .hljs {
  color: #1e1e1e;
}
[data-theme="light"] .fe-highlight-layer .hljs-keyword { color: #0000ff; }
[data-theme="light"] .fe-highlight-layer .hljs-string { color: #a31515; }
[data-theme="light"] .fe-highlight-layer .hljs-number { color: #098658; }
[data-theme="light"] .fe-highlight-layer .hljs-comment { color: #008000; }
[data-theme="light"] .fe-highlight-layer .hljs-built_in { color: #267f99; }
[data-theme="light"] .fe-highlight-layer .hljs-type { color: #267f99; }
[data-theme="light"] .fe-highlight-layer .hljs-literal { color: #0000ff; }
[data-theme="light"] .fe-highlight-layer .hljs-attr { color: #0451a5; }
[data-theme="light"] .fe-highlight-layer .hljs-attribute { color: #0451a5; }
[data-theme="light"] .fe-highlight-layer .hljs-title { color: #795e26; }
[data-theme="light"] .fe-highlight-layer .hljs-title.function_ { color: #795e26; }
[data-theme="light"] .fe-highlight-layer .hljs-title.class_ { color: #267f99; }
[data-theme="light"] .fe-highlight-layer .hljs-property { color: #0451a5; }
[data-theme="light"] .fe-highlight-layer .hljs-selector-tag { color: #800000; }
[data-theme="light"] .fe-highlight-layer .hljs-selector-class { color: #800000; }
[data-theme="light"] .fe-highlight-layer .hljs-selector-id { color: #800000; }
[data-theme="light"] .fe-highlight-layer .hljs-tag { color: #800000; }
[data-theme="light"] .fe-highlight-layer .hljs-name { color: #800000; }
[data-theme="light"] .fe-highlight-layer .hljs-variable { color: #001080; }
[data-theme="light"] .fe-highlight-layer .hljs-template-variable { color: #001080; }
[data-theme="light"] .fe-highlight-layer .hljs-template-tag { color: #800000; }
[data-theme="light"] .fe-highlight-layer .hljs-deletion { background: #fee; }
[data-theme="light"] .fe-highlight-layer .hljs-addition { background: #efe; }
[data-theme="light"] .fe-highlight-layer .hljs-meta { color: #1e1e1e; }
[data-theme="light"] .fe-highlight-layer .hljs-meta .hljs-keyword { color: #0000ff; }
[data-theme="light"] .fe-highlight-layer .hljs-doctag { color: #008000; }
[data-theme="light"] .fe-highlight-layer .hljs-regexp { color: #811f3f; }
[data-theme="light"] .fe-highlight-layer .hljs-link { color: #0000ff; }
[data-theme="light"] .fe-highlight-layer .hljs-symbol { color: #0000ff; }
[data-theme="light"] .fe-highlight-layer .hljs-bullet { color: #0451a5; }
[data-theme="light"] .fe-highlight-layer .hljs-code { color: #1e1e1e; }
[data-theme="light"] .fe-highlight-layer .hljs-emphasis { font-style: italic; }
[data-theme="light"] .fe-highlight-layer .hljs-strong { font-weight: bold; }
[data-theme="light"] .fe-highlight-layer .hljs-formula { color: #1e1e1e; }
[data-theme="light"] .fe-highlight-layer .hljs-params { color: #001080; }
[data-theme="light"] .fe-highlight-layer .hljs-section { color: #795e26; }
[data-theme="light"] .fe-highlight-layer .hljs-quote { color: #008000; }
[data-theme="light"] .fe-file-icon { color: #0078d4; }
[data-theme="light"] .fe-line-num.fe-line-remove { background: rgba(255, 77, 79, 0.15); }
[data-theme="light"] .fe-line-num.fe-line-add { background: rgba(82, 196, 26, 0.12); }
[data-theme="light"] .fe-code-line.fe-line-remove { background: rgba(255, 77, 79, 0.10); }
[data-theme="light"] .fe-code-line.fe-line-add { background: rgba(82, 196, 26, 0.10); }
/* ★ 暗色模式（默认）textarea 选中：半透明背景让底层高亮文字透出，text 设为 transparent 避免和 pre 层叠加产生重影 */
.fe-textarea::selection { background: rgba(38, 79, 120, 0.35); color: transparent; -webkit-text-fill-color: transparent; }
/* 高亮层兜底：即使被选中也不显示背景（避免和textarea selection叠加） */
.fe-highlight-layer::selection,
.fe-highlight-layer *::selection { background: transparent; color: inherit; }

[data-theme="light"] .fe-textarea::selection { background: rgba(173, 214, 255, 0.35); color: transparent; -webkit-text-fill-color: transparent; }
[data-theme="light"] .fe-highlight-layer::selection,
[data-theme="light"] .fe-highlight-layer *::selection { background: transparent; color: inherit; }
[data-theme="light"] .fe-footer { background: #0078d4; }
</style>
