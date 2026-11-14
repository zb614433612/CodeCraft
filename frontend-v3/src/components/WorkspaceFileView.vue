<!--
  工作区 · 文件标签（P4 · F02）
  ----------------------------------------------------------------------------
  职责单一：读一个文件、存一个文件、把「有没有未保存的修改」上报给标签栏。

  为什么不复用 ProjectFileTree 里的编辑逻辑：
    文件树是「树 + 就地编辑」的一体结构，而多标签要求「一个标签一个文件」，
    两者状态模型不同（树里切换文件会丢编辑态，标签里每个文件各自独立）。
    读写接口完全一致（/project/read、/project/write），这里只是换了个承载方式。
-->
<template>
  <div class="cc-wsv">
    <div v-if="loading" class="cc-wsv__state">读取中…</div>
    <div v-else-if="error" class="cc-wsv__state is-error">
      <p class="cc-wsv__state-text">{{ error }}</p>
      <div class="cc-wsv__state-ops">
        <button class="cc-btn cc-btn--ghost" @click="load">重试</button>
        <!--
          解析失败时也保留「用系统程序打开」：
          路径可能是工作目录之外的绝对路径，本应用解析不到但系统能打开；
          或者 AI 写的路径少了前缀，用户想自己在资源管理器里确认。
        -->
        <button v-if="canSystem" class="cc-btn cc-btn--ghost" @click="openInSystem">用默认程序打开</button>
        <button v-if="canReveal" class="cc-btn cc-btn--ghost" @click="revealInFolder">在文件夹中显示</button>
      </div>
    </div>
    <CodeEditor
      v-else
      v-model="content"
      :path="path"
      :original="original"
      :saving="saving"
      @save="save"
    >
      <!-- 桌面端专属：用系统关联程序打开 / 在资源管理器中定位（浏览器环境不渲染） -->
      <template #actions>
        <button
          v-if="canSystem"
          class="cc-icon-btn cc-icon-btn--sm"
          title="用默认程序打开（VS Code 等，由文件关联决定）"
          @click="openInSystem"
        >
          <svg class="cc-i cc-i--sm"><use href="#i-link" /></svg>
        </button>
        <button
          v-if="canReveal"
          class="cc-icon-btn cc-icon-btn--sm"
          title="在文件夹中显示"
          @click="revealInFolder"
        >
          <svg class="cc-i cc-i--sm"><use href="#i-folder" /></svg>
        </button>
      </template>
    </CodeEditor>
  </div>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import CodeEditor from './CodeEditor.vue'
import { readProjectFile, writeProjectFile } from '../api/project'
import { describeMissingFile, resolveFileTarget } from '../utils/file-path'
import { canOpenWithSystem, canRevealInFolder, openPathWithSystem, revealInFileManager } from '../utils/shell'
import { toast } from '../utils/toast'

const props = defineProps<{
  /** 文件路径：允许是 AI 输出里的相对路径，内部按工作目录解析成绝对路径后再读写 */
  path: string
  /** 当前 Agent 工作目录（解析相对路径的基准，与文件树 / 终端一致） */
  root?: string
}>()

const emit = defineEmits<{ (e: 'dirty', value: boolean): void }>()

const loading = ref(false)
const error = ref('')
const content = ref('')
/** 读取到的原始内容：与 content 不同即为「未保存」 */
const original = ref('')
const saving = ref(false)
/** 解析后的真实路径（读 / 写都用它，避免相对路径被后端按 user.dir 拼接） */
const resolvedPath = ref('')

/**
 * 交给系统壳的路径：优先用解析后的路径。
 * 相对路径只有拼上工作目录才可能被系统打开（主进程只接受绝对路径），
 * 所以这里绝不能直接传 props.path。
 */
const targetPath = computed(() => resolvedPath.value || props.path)

/** 桌面端专属能力判定（浏览器环境两者皆 false → 对应按钮不渲染） */
const canSystem = canOpenWithSystem()
const canReveal = canRevealInFolder()

/** 用系统默认程序打开（VS Code / 系统编辑器，取决于文件关联） */
async function openInSystem() {
  const ok = await openPathWithSystem(targetPath.value)
  if (!ok) {
    toast.warning('无法用默认程序打开', {
      description: '文件不存在、路径不是绝对路径，或系统没有关联程序'
    })
  }
}

/** 在资源管理器 / Finder 中定位该文件 */
async function revealInFolder() {
  const ok = await revealInFileManager(targetPath.value)
  if (!ok) {
    toast.warning('无法在文件夹中定位', { description: '文件不存在或路径不是绝对路径' })
  }
}

const dirty = computed(() => content.value !== original.value)

// 标签栏据此亮圆点；组件卸载时清掉，避免关标签后圆点残留在别的标签上
watch(dirty, value => emit('dirty', value), { immediate: true })
onBeforeUnmount(() => emit('dirty', false))

async function load() {
  loading.value = true
  error.value = ''
  try {
    /*
      与旧版 openLocalFileTab 一致：先拼绝对路径再读。
      后端 /api/project/read 对相对路径是用后端进程 user.dir 拼的，
      Electron 桌面端那是 jar 所在目录 —— 直接从消息里拿相对路径去读必然失败。
    */
    const target = await resolveFileTarget(props.path, props.root || '')
    resolvedPath.value = target.path
    if (!target.found) {
      error.value = describeMissingFile(target.raw || props.path, props.root || '', target.candidates)
      return
    }
    const res = await readProjectFile(target.path)
    const text = typeof res.data === 'string' ? res.data : ''
    // 后端读取失败时返回 Spring 默认错误页或空串，统一按「读不到」处理
    if (!text && res.code !== 200) {
      error.value = `${describeMissingFile(target.raw || props.path, props.root || '', target.candidates)}\n后端返回：${res.message || '文件内容为空或不可读取'}`
      return
    }
    content.value = text
    original.value = text
  } catch (err) {
    error.value = err instanceof Error ? err.message : '读取文件失败'
  } finally {
    loading.value = false
  }
}

async function save() {
  if (saving.value) return
  const target = resolvedPath.value || props.path
  saving.value = true
  try {
    await writeProjectFile(target, content.value)
    original.value = content.value
    toast.success('已保存', { description: target })
  } catch (err) {
    toast.error('保存失败', { description: err instanceof Error ? err.message : '请稍后重试' })
  } finally {
    saving.value = false
  }
}

watch(() => [props.path, props.root], load, { immediate: true })
</script>
