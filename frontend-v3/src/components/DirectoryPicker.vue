<!--
  目录选择器（v3）
  ----------------------------------------------------------------------------
  居中弹窗（Teleport 到 body），给「工作目录」「H2 库文件路径」等字段挑选本地绝对路径。
  数据全部来自后端，不自造目录结构：

    GET /api/project/drives                              → 盘符 / 根目录列表
    GET /api/project/children?path=…&includeFiles=false  → 指定目录下的子目录
      （两个接口返回同一结构 { name, path, directory }，path 统一为正斜杠绝对路径）

  交互：
    · 打开时若 initialPath 是绝对路径 → 直接定位到该目录（面包屑同步构建）；
      否则（空值 / 相对路径）→ 从「我的电脑」盘符列表开始；
    · 单击列表项 = 选中为「将使用」目标；双击 / 点右侧箭头 = 进入该目录；
    · 面包屑逐级回退，也可手输路径后回车或点「前往」直达；
    · 确认时优先返回选中的子目录，否则返回当前所在目录（因此「选中当前目录本身」
      也能成立，旧版 DirectoryBrowser 必须先进入子目录才能选，这里修掉了该限制）。

  数据接缝：
    v-model:open  -> 开关
    initialPath   -> 打开时定位的初始目录（通常是表单里已有的值）
    emit('pick')  -> 确认选择，参数为绝对路径

  注意：后端对「不存在 / 无权限」的目录返回空数组而不是报错，因此列表为空时无法
       区分「空目录」与「路径不可访问」，文案上两者一起提示。
-->
<template>
  <Teleport to="body">
    <!--
      显隐由 .is-open 类控制（不用 v-if / v-show）：
      静态预览的浮层 HTML 是常驻的、默认 display:none，Vue 侧靠同一个类显示，
      两边共用一条 CSS 规则（见踩坑经验 216：v-show 会恢复 CSS 默认值导致面板永不显示）。
    -->
    <div
      class="cc-dirpick"
      :class="{ 'is-open': open }"
      :aria-hidden="!open"
      role="dialog"
      aria-modal="true"
      aria-labelledby="dirPickTitle"
    >
        <div class="cc-dirpick__mask" @click="close" />

        <div class="cc-dirpick__panel">
          <!-- 头部 -->
          <div class="cc-dirpick__head">
            <svg class="cc-i cc-i--sm" style="color: var(--accent)"><use href="#i-folder" /></svg>
            <h2 id="dirPickTitle" class="cc-dirpick__title">{{ props.title }}</h2>
            <button class="cc-icon-btn cc-icon-btn--sm" title="关闭" @click="close">
              <svg class="cc-i cc-i--sm"><use href="#i-close" /></svg>
            </button>
          </div>

          <!-- 手输路径直达 -->
          <div class="cc-dirpick__jump">
            <input
              ref="pathInput"
              v-model="pathDraft"
              class="cc-input"
              spellcheck="false"
              placeholder="直接输入绝对路径，如 C:/Users/xxx/Desktop/project"
              @keydown.enter.prevent="jump"
            />
            <button class="cc-btn" :disabled="loading" @click="jump">前往</button>
          </div>

          <!-- 面包屑 -->
          <div class="cc-dirpick__crumbs">
            <button class="cc-dirpick__crumb" :class="{ 'is-current': !crumbs.length }" @click="goDrives">
              <svg class="cc-i cc-i--sm"><use href="#i-disk" /></svg>我的电脑
            </button>
            <template v-for="(c, i) in crumbs" :key="c.path">
              <svg class="cc-i cc-dirpick__sep"><use href="#i-chev" /></svg>
              <button
                class="cc-dirpick__crumb"
                :class="{ 'is-current': i === crumbs.length - 1 }"
                :title="c.path"
                @click="gotoCrumb(i)"
              >{{ c.name }}</button>
            </template>
          </div>

          <!-- 目录列表 -->
          <div class="cc-dirpick__list">
            <div v-if="loading" class="cc-dirpick__state">
              <svg class="cc-i cc-i--sm cc-spin"><use href="#i-refresh" /></svg>加载中…
            </div>
            <div v-else-if="error" class="cc-dirpick__state is-error">{{ error }}</div>
            <template v-else>
              <div
                v-for="entry in entries"
                :key="entry.path"
                class="cc-dirpick__item"
                :class="{ 'is-selected': selectedPath === entry.path }"
                role="button"
                tabindex="0"
                :title="entry.path"
                @click="selectEntry(entry)"
                @dblclick="enterDir(entry)"
                @keydown.enter.prevent="enterDir(entry)"
              >
                <svg class="cc-i cc-i--sm cc-dirpick__item-icon"><use href="#i-folder" /></svg>
                <span class="cc-dirpick__item-name">{{ entry.name }}</span>
                <span class="cc-dirpick__enter" title="进入该目录" @click.stop="enterDir(entry)">
                  <svg class="cc-i cc-i--sm"><use href="#i-chev" /></svg>
                </span>
              </div>
              <div v-if="!entries.length" class="cc-dirpick__state">
                {{ currentPath ? '该目录下没有子目录（也可能路径不可访问）' : '没有检测到可用的盘符' }}
              </div>
            </template>
          </div>

          <!-- 底部 -->
          <div class="cc-dirpick__foot">
            <div class="cc-dirpick__target" :title="targetPath">
              {{ targetPath ? '将使用：' + targetPath : '请选择一个目录' }}
            </div>
            <button class="cc-btn" @click="close">取消</button>
            <button class="cc-btn cc-btn--primary" :disabled="!targetPath" @click="confirm">
              <svg class="cc-i cc-i--sm"><use href="#i-check" /></svg>选择此目录
            </button>
          </div>
        </div>
    </div>
  </Teleport>
</template>

<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, ref, watch } from 'vue'
import { getDirChildren, getDrives, type DirectoryEntry } from '../api/project'

const props = withDefaults(
  defineProps<{
    open: boolean
    /** 打开时定位的初始目录；非绝对路径会被忽略（从盘符列表开始） */
    initialPath?: string
    /** 弹窗标题（不同入口语义不同：工作目录 / 浏览目录 …） */
    title?: string
  }>(),
  { initialPath: '', title: '选择工作目录' }
)

const emit = defineEmits<{
  (e: 'update:open', v: boolean): void
  (e: 'pick', path: string): void
}>()

/** 当前列表内容（盘符或子目录，由后端返回，已按「目录在前 + 名称」排序） */
const entries = ref<DirectoryEntry[]>([])
const loading = ref(false)
const error = ref('')
/** 当前所在目录；空串表示停留在「我的电脑」盘符列表 */
const currentPath = ref('')
/** 列表里手工选中的子目录（优先于 currentPath 作为返回目标） */
const selectedPath = ref('')
/** 手输框内容（进入新目录时同步回填） */
const pathDraft = ref('')
const pathInput = ref<HTMLInputElement | null>(null)

/* ============================ 路径工具 ============================ */

/**
 * 归一化路径：反斜杠转正斜杠、去尾部多余斜杠，但保留 Windows 盘符根（`C:/`）。
 * 后端返回的 path 已经是 `C:/Users/xxx` 形式，这里主要处理用户手输的情况。
 */
function normalizePath(raw: string): string {
  const p = raw.trim().replace(/\\/g, '/')
  if (!p) return ''
  const trimmed = p.replace(/\/+$/, '')
  // 'C:/' 去尾斜杠后变成 'C:'，补回来（否则后端把它当相对路径）
  return /^[A-Za-z]:$/.test(trimmed) ? trimmed + '/' : trimmed
}

/** 绝对路径判定：Windows 盘符开头或 Unix 根开头 */
function isAbsolutePath(p: string): boolean {
  return /^[A-Za-z]:\//.test(p) || p.startsWith('/')
}

/**
 * 拆解面包屑：`C:/Users/61443` → [{C:, C:/}, {Users, C:/Users}, {61443, C:/Users/61443}]
 *
 * <p>Windows 首段需要补斜杠（`C:` → `C:/`），Unix 则从 `/` 起累积；两套规则混在一处，
 * 避免后续再写一遍。</p>
 */
function buildCrumbs(path: string): Array<{ name: string; path: string }> {
  const p = normalizePath(path)
  if (!p) return []
  const unix = p.startsWith('/')
  const segs = p.split('/').filter(Boolean)
  const out: Array<{ name: string; path: string }> = []
  let acc = ''
  for (let i = 0; i < segs.length; i++) {
    if (unix) acc = acc + '/' + segs[i]
    else acc = i === 0 ? segs[i] + '/' : acc + segs[i]
    out.push({ name: segs[i], path: acc })
  }
  return out
}

const crumbs = computed(() => buildCrumbs(currentPath.value))
/** 最终返回的路径：优先用户在列表里选中的子目录 */
const targetPath = computed(() => selectedPath.value || currentPath.value)

/* ============================ 数据加载 ============================ */

/** 加载盘符列表（回到「我的电脑」） */
async function goDrives() {
  currentPath.value = ''
  selectedPath.value = ''
  pathDraft.value = ''
  loading.value = true
  error.value = ''
  try {
    const res = await getDrives()
    if (res.code === 200 && res.data) {
      entries.value = res.data
    } else {
      entries.value = []
      error.value = res.message || '获取盘符失败'
    }
  } catch (e) {
    entries.value = []
    error.value = (e as Error).message || '网络错误'
  } finally {
    loading.value = false
  }
}

/** 加载指定目录下的子目录 */
async function openPath(path: string) {
  const target = normalizePath(path)
  if (!target) return
  loading.value = true
  error.value = ''
  try {
    const res = await getDirChildren(target)
    if (res.code === 200 && res.data) {
      entries.value = res.data
      currentPath.value = target
      selectedPath.value = ''
      pathDraft.value = target
    } else {
      entries.value = []
      error.value = res.message || '获取目录列表失败'
    }
  } catch (e) {
    entries.value = []
    error.value = (e as Error).message || '网络错误'
  } finally {
    loading.value = false
  }
}

/**
 * 进入列表项对应的目录
 *
 * <p>注意：`/project/drives` 返回的盘符项 `directory` 是 **false**（后端用两参构造器
 * `new DirectoryEntry(name, path)`，`directory` 默认 false），所以不能一律用
 * `entry.directory` 拦截 —— 否则盘符永远进不去。这里只在「已在某个目录内」时按该字段判断。</p>
 */
function enterDir(entry: DirectoryEntry) {
  if (!entry.directory && currentPath.value) return
  void openPath(entry.path)
}

/** 单击列表项：选中为返回目标（不进入） */
function selectEntry(entry: DirectoryEntry) {
  selectedPath.value = entry.path
}

/** 面包屑回退到第 i 级 */
function gotoCrumb(i: number) {
  const c = crumbs.value[i]
  if (c) void openPath(c.path)
}

/** 手输路径直达 */
function jump() {
  const p = normalizePath(pathDraft.value)
  if (!p) return
  if (!isAbsolutePath(p)) {
    error.value = '请填写绝对路径，例如 C:/Users/xxx/Desktop/project'
    return
  }
  void openPath(p)
}

/* ============================ 打开 / 关闭 ============================ */

function onKeydown(e: KeyboardEvent) {
  if (e.key !== 'Escape') return
  e.stopPropagation()
  close()
}

/**
 * 打开 / 关闭的统一入口：
 *   打开 → 注册 ESC 监听 + 按初始路径定位（拿不到就退回盘符列表）+ 聚焦手输框
 *   关闭 → 注销 ESC 监听
 */
watch(
  () => props.open,
  v => {
    if (!v) {
      window.removeEventListener('keydown', onKeydown)
      return
    }
    window.addEventListener('keydown', onKeydown)
    const init = normalizePath(props.initialPath || '')
    if (init && isAbsolutePath(init)) void openPath(init)
    else void goDrives()
    // 手输是最快的导航方式，打开即聚焦
    void nextTick(() => pathInput.value?.focus())
  },
  { immediate: true }
)

onBeforeUnmount(() => window.removeEventListener('keydown', onKeydown))

function confirm() {
  const p = targetPath.value
  if (!p) return
  emit('pick', p)
  close()
}

function close() {
  emit('update:open', false)
}
</script>
