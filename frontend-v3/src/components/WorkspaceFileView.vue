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
      <button class="cc-btn cc-btn--ghost" @click="load">重试</button>
    </div>
    <CodeEditor
      v-else
      v-model="content"
      :path="path"
      :original="original"
      :saving="saving"
      @save="save"
    />
  </div>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import CodeEditor from './CodeEditor.vue'
import { readProjectFile, writeProjectFile } from '../api/project'
import { toast } from '../utils/toast'

const props = defineProps<{
  /** 文件绝对路径 */
  path: string
}>()

const emit = defineEmits<{ (e: 'dirty', value: boolean): void }>()

const loading = ref(false)
const error = ref('')
const content = ref('')
/** 读取到的原始内容：与 content 不同即为「未保存」 */
const original = ref('')
const saving = ref(false)

const dirty = computed(() => content.value !== original.value)

// 标签栏据此亮圆点；组件卸载时清掉，避免关标签后圆点残留在别的标签上
watch(dirty, value => emit('dirty', value), { immediate: true })
onBeforeUnmount(() => emit('dirty', false))

async function load() {
  loading.value = true
  error.value = ''
  try {
    const res = await readProjectFile(props.path)
    const text = typeof res.data === 'string' ? res.data : ''
    // 后端读取失败时返回 Spring 默认错误页或空串，统一按「读不到」处理
    if (!text && res.code !== 200) {
      error.value = res.message || '文件内容为空或不可读取'
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
  saving.value = true
  try {
    await writeProjectFile(props.path, content.value)
    original.value = content.value
    toast.success('已保存', { description: props.path })
  } catch (err) {
    toast.error('保存失败', { description: err instanceof Error ? err.message : '请稍后重试' })
  } finally {
    saving.value = false
  }
}

watch(() => props.path, load, { immediate: true })
</script>
