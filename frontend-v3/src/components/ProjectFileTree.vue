<!--
  聊天页右侧抽屉 · 文件（文件树 + 文件编辑器）
  ----------------------------------------------------------------------------
  数据接缝：
    props.root        -> 当前 Agent 的工作目录（chatProjectRoot），作为默认根目录
    GET  /project/children?path=&includeFiles=true   目录懒加载
    GET  /project/read?path=                         读取文件文本
    POST /project/write { path, content }            保存

  可切换根目录（v3 补充）：
    抽屉里的文件夹不局限于 Agent 的工作目录——点顶部的路径条即可打开 DirectoryPicker，
    浏览任意本地目录（如只读的日志目录、另一个仓库）。切换只影响本面板的浏览范围，
    不改 Agent 配置；点「跟随 Agent」可退回 props.root。

  设计要点：
    1) 树是「扁平渲染 + 展开集合」而非递归组件：SFC 递归自身要写 name，
       扁平化后行高、缩进、键盘可达都更好控制；
    2) 目录内容按 path 缓存在 Map 里，折叠再展开不重复请求；
    3) 编辑区只支持文本文件；二进制/超大文件由后端 read 的返回值决定，
       读失败就在头部提示，不静默显示空白。
-->
<template>
  <div class="cc-ftree">
    <div class="cc-ftree__bar">
      <!-- 路径条本身就是「切换目录」入口：点击打开目录选择器 -->
      <button
        class="cc-ftree__root is-btn"
        :title="localRoot ? `${localRoot}（点击切换目录）` : '点击选择要浏览的目录'"
        @click="pickerOpen = true"
      >
        <svg class="cc-i cc-i--sm cc-ftree__root-icon"><use href="#i-folder" /></svg>
        <span class="cc-ftree__root-text">{{ localRoot || '选择目录' }}</span>
      </button>
      <button
        v-if="!isAgentRoot"
        class="cc-ftree__follow"
        title="回到当前 Agent 的工作目录"
        @click="followAgentRoot"
      >
        跟随 Agent
      </button>
      <button class="cc-icon-btn cc-icon-btn--sm" title="刷新文件树" @click="refresh">
        <svg class="cc-i cc-i--sm"><use href="#i-refresh" /></svg>
      </button>
    </div>

    <div v-if="!localRoot" class="cc-ftree__state">还没有选择目录，点上方路径条挑一个要浏览的目录</div>
    <div v-else-if="error" class="cc-ftree__state is-error">{{ error }}</div>

    <template v-else>
      <div class="cc-ftree__list" :class="{ 'is-editing': activeFile && !externalOpen }">
        <div
          v-for="row in rows"
          :key="row.path"
          class="cc-tree__row"
          :class="{ 'is-active': row.path === activeFile }"
          :style="{ paddingLeft: 6 + row.level * 14 + 'px' }"
          :title="row.path"
          @click="onRowClick(row)"
        >
          <svg v-if="row.directory" class="cc-i cc-i--sm cc-ftree__caret" :class="{ 'is-open': row.expanded }">
            <use href="#i-chev" />
          </svg>
          <svg v-else class="cc-i cc-i--sm cc-ftree__caret is-hidden"><use href="#i-chev" /></svg>
          <svg class="cc-i cc-i--sm" :style="{ color: row.directory ? 'var(--warn)' : 'var(--text-4)' }">
            <use :href="row.directory ? '#i-folder' : '#i-file'" />
          </svg>
          <span class="cc-ftree__name">{{ row.name }}</span>
        </div>
        <div v-if="loading" class="cc-ftree__state">加载中…</div>
        <div v-else-if="!rows.length" class="cc-ftree__state">该目录为空</div>
      </div>

      <div v-if="activeFile && !externalOpen" class="cc-ftree__editor">
        <div v-if="fileLoading" class="cc-ftree__state">读取中…</div>
        <div v-else-if="fileError" class="cc-ftree__state is-error">{{ fileError }}</div>
        <CodeEditor
          v-else
          v-model="fileContent"
          :path="activeFile"
          :original="fileOriginal"
          :saving="saving"
          show-close
          @save="save"
          @close="tryCloseEditor"
        />
      </div>
    </template>

    <DirectoryPicker
      v-model:open="pickerOpen"
      title="选择浏览的目录"
      :initial-path="localRoot"
      @pick="onPickRoot"
    />
  </div>
</template>

<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { getDirChildren, readProjectFile, writeProjectFile, type DirectoryEntry } from '../api/project'
import { toast } from '../utils/toast'
import { confirm } from '../utils/confirm'
import DirectoryPicker from './DirectoryPicker.vue'
import CodeEditor from './CodeEditor.vue'

const props = withDefaults(
  defineProps<{
    root?: string
    /**
     * 外部打开模式（P4 多标签用）：点文件时不就地打开编辑器，
     * 而是 emit('open-file') 交给父级开一个新标签。此时本组件退化为纯导航树。
     */
    externalOpen?: boolean
  }>(),
  { root: '', externalOpen: false }
)

const emit = defineEmits<{ (e: 'open-file', path: string): void }>()

interface TreeRow extends DirectoryEntry {
  level: number
  expanded: boolean
}

/** 当前浏览的根目录：默认跟随 Agent 工作目录，用户可在面板内改成任意目录 */
const localRoot = ref(props.root)
const pickerOpen = ref(false)
/** 是否仍跟随 Agent 工作目录（用于决定要不要显示「跟随 Agent」按钮） */
const isAgentRoot = computed(() => !localRoot.value || localRoot.value === props.root)

const rows = ref<TreeRow[]>([])
const loading = ref(false)
const error = ref('')
/** 目录内容缓存：key = 目录绝对路径 */
const childrenCache = new Map<string, DirectoryEntry[]>()
/** 当前展开的目录路径集合 */
const expanded = ref(new Set<string>())

const activeFile = ref('')
const fileContent = ref('')
/** 读取到的原始内容：与 fileContent 不一致即「未保存」（旧版用一个布尔 ref，切文件后容易漏重置） */
const fileOriginal = ref('')
const fileLoading = ref(false)
const fileError = ref('')
const saving = ref(false)
/** 未保存判定：内容比较，编辑器内部也用它点亮「未保存」徽章 */
const dirty = computed(() => fileContent.value !== fileOriginal.value)

/** 目录内容：命中缓存直接返回，否则请求一次并写入缓存 */
async function loadChildren(path: string): Promise<DirectoryEntry[]> {
  const cached = childrenCache.get(path)
  if (cached) return cached
  const res = await getDirChildren(path, true)
  const list = res.data ?? []
  childrenCache.set(path, list)
  return list
}

/** 按当前展开集合重建扁平行列表 */
async function rebuild() {
  if (!localRoot.value) {
    rows.value = []
    return
  }
  loading.value = true
  error.value = ''
  try {
    const out: TreeRow[] = []
    const walk = async (path: string, level: number) => {
      const list = await loadChildren(path)
      for (const entry of list) {
        const isExpanded = entry.directory && expanded.value.has(entry.path)
        out.push({ ...entry, level, expanded: isExpanded })
        if (isExpanded) await walk(entry.path, level + 1)
      }
    }
    await walk(localRoot.value, 0)
    rows.value = out
  } catch (err) {
    error.value = err instanceof Error ? err.message : '读取目录失败'
  } finally {
    loading.value = false
  }
}

/** 刷新：清空缓存与展开状态，从头再来（用户手动点刷新时用） */
function refresh() {
  childrenCache.clear()
  expanded.value = new Set()
  void rebuild()
}

/** 切换到用户在目录选择器里挑的目录 */
async function onPickRoot(path: string) {
  if (!path || path === localRoot.value) return
  // 换目录意味着当前编辑区会被卸载，先处理未保存的修改
  if (!(await tryCloseEditor())) return
  localRoot.value = path
  // 根目录已变，旧缓存与展开状态全部失效
  refresh()
}

/** 回到当前 Agent 的工作目录 */
function followAgentRoot() {
  if (!props.root) {
    toast.warning('当前 Agent 没有设置工作目录')
    return
  }
  void onPickRoot(props.root)
}

async function onRowClick(row: TreeRow) {
  if (!row.directory) {
    // 多标签模式：文件交回父级开标签，本面板只当导航树用
    if (props.externalOpen) {
      emit('open-file', row.path)
      return
    }
    await openFile(row.path)
    return
  }
  const next = new Set(expanded.value)
  if (next.has(row.path)) next.delete(row.path)
  else next.add(row.path)
  expanded.value = next
  await rebuild()
}

async function openFile(path: string) {
  if (activeFile.value === path && !fileError.value) return
  // 切到另一个文件前先给未保存的修改一次机会
  if (activeFile.value && activeFile.value !== path && dirty.value) {
    if (!(await tryCloseEditor())) return
  }
  activeFile.value = path
  fileLoading.value = true
  fileError.value = ''
  fileContent.value = ''
  fileOriginal.value = ''
  try {
    const res = await readProjectFile(path)
    // 后端读取失败时返回 Spring 默认错误页或空串，统一按「读不到」处理
    const content = typeof res.data === 'string' ? res.data : ''
    if (!content && res.code !== 200) {
      fileError.value = res.message || '文件内容为空或不可读取'
      return
    }
    fileContent.value = content
    fileOriginal.value = content
  } catch (err) {
    fileError.value = err instanceof Error ? err.message : '读取文件失败'
  } finally {
    fileLoading.value = false
  }
}

/** 无条件关闭编辑区（不做脏检查，调用方自行决定是否已确认过） */
function closeEditor() {
  activeFile.value = ''
  fileContent.value = ''
  fileOriginal.value = ''
  fileError.value = ''
}

/**
 * 关闭编辑区（带未保存确认）。
 * @returns true 表示可以继续（已关闭或本来就没打开），false 表示用户取消
 */
async function tryCloseEditor(): Promise<boolean> {
  if (!activeFile.value) return true
  if (dirty.value) {
    const ok = await confirm({
      title: '放弃未保存的修改？',
      content: `${activeFile.value} 有未保存的修改，继续操作会丢失这些改动。`,
      okText: '放弃修改',
      type: 'danger'
    })
    if (!ok) return false
  }
  closeEditor()
  return true
}

async function save() {
  if (!activeFile.value || saving.value) return
  saving.value = true
  try {
    await writeProjectFile(activeFile.value, fileContent.value)
    // 目录没变，但文件大小/内容变了，无需重拉树；把基线推到当前内容即清掉「未保存」
    fileOriginal.value = fileContent.value
    toast.success('已保存', { description: activeFile.value })
  } catch (err) {
    toast.error('保存失败', { description: err instanceof Error ? err.message : '请稍后重试' })
  } finally {
    saving.value = false
  }
}

/**
 * 切换 Agent（props.root 变化）时把浏览根目录重置回 Agent 的工作目录。
 * 用户在面板内手动切换过目录、但没换 Agent 的情况不受影响。
 */
watch(
  () => props.root,
  value => {
    // 换 Agent 是被动变化，弹确认框会打断切换流程，改为提示后直接丢弃
    if (dirty.value) toast.warning('已切换工作目录，未保存的编辑已丢弃')
    localRoot.value = value
    childrenCache.clear()
    expanded.value = new Set()
    closeEditor()
    void rebuild()
  },
  { immediate: true }
)
</script>
