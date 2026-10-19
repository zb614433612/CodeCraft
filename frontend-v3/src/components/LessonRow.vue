<!--
  踩坑经验行（v2）
  ----------------------------------------------------------------------------
  信息层次：类型图标（失败 / 弯路）+ 错误码 + 类别 + 状态 + 来源
           → （弯路才有）任务目标
           → 失败现象（最多两行）
           → 解法（草稿无解法时提示补全）
           → 元信息：项目 / 工具 / 命中·有效·失败 / 更新时间
  状态语义（与后端 Lesson 常量一致）：
    status: 0=草稿（自动捕获待验证） 1=有效（已验证） 2=隐藏（连续失败降级）
    type:   FAILURE=失败经验  DETOUR=弯路经验
    source: auto=自动捕获 / llm=LLM 记录 / manual=人工沉淀 / review=对话复盘
  数据接缝：
    lesson.*             -> GET /lessons（分页）
    emit('edit')         -> 打开详情 / 补全抽屉（PUT /lessons/{id}）
    emit('feedback', bool) -> POST /lessons/{id}/feedback?effective=
    emit('delete')       -> DELETE /lessons/{id}
  「无可用解法」判定与后端 Lesson.hasUsableSolution() 对齐：空 / 空白 / 占位符都视为待补全。
-->
<template>
  <div class="cc-lesson-row" :class="{ 'is-off': isHidden }">
    <div class="cc-lesson-row__icon" :class="{ 'is-detour': isDetour }">
      <svg class="cc-i"><use :href="isDetour ? '#i-branch' : '#i-bug'" /></svg>
    </div>

    <div class="cc-lesson-row__main">
      <!-- 错误码 + 类别 + 类型 + 状态 + 来源 -->
      <div class="cc-lesson-row__title">
        <code class="cc-lesson-code">{{ lesson.errorCode || '（无错误码）' }}</code>
        <span v-if="lesson.errorCategory" class="cc-tag">{{ lesson.errorCategory }}</span>
        <span v-if="isDetour" class="cc-tag cc-tag--accent">弯路</span>
        <span class="cc-status" :class="statusMod">
          <span class="cc-status__dot" />{{ statusText }}
        </span>
        <span class="cc-tag">{{ sourceText }}</span>
      </div>

      <!-- 弯路经验：任务目标（检索维度） -->
      <div v-if="isDetour && lesson.goal" class="cc-lesson-solution">
        <span class="cc-lesson-solution__label">目标</span>
        <span>{{ lesson.goal }}</span>
      </div>

      <!-- 失败现象 -->
      <div v-if="lesson.symptom" class="cc-lesson-symptom">{{ lesson.symptom }}</div>

      <!-- 解法 / 待补全 -->
      <div v-if="hasSolution" class="cc-lesson-solution">
        <span class="cc-lesson-solution__label">解法</span>
        <span>{{ lesson.solution }}</span>
      </div>
      <div v-else class="cc-lesson-solution is-empty">
        <span class="cc-lesson-solution__label">解法</span>
        <span>尚未补全，点击右侧「补全」写入根因与解法后，该经验才会被检索注入</span>
      </div>

      <!-- 元信息 -->
      <div class="cc-lesson-row__meta">
        <span v-if="lesson.projectKey" class="cc-lesson-meta-item">
          <svg class="cc-i cc-i--sm"><use href="#i-file" /></svg>{{ lesson.projectKey }}
        </span>
        <span v-if="lesson.toolName" class="cc-lesson-meta-item">
          <svg class="cc-i cc-i--sm"><use href="#i-term" /></svg>{{ lesson.toolName }}
        </span>
        <span>
          命中 <b>{{ lesson.hitCount || 0 }}</b>
          · 有效 <b class="cc-lesson-count--ok">{{ lesson.successCount || 0 }}</b>
          · 失败 <b :class="{ 'cc-lesson-count--fail': (lesson.failCount || 0) > 0 }">{{ lesson.failCount || 0 }}</b>
        </span>
        <span v-if="isHidden">连续失败已降级，不再注入</span>
        <span v-else>更新于 {{ formatTime(lesson.updatedAt) }}</span>
      </div>
    </div>

    <!-- 右侧：待补全标签 + hover 操作 -->
    <div class="cc-lesson-row__side">
      <span v-if="!hasSolution" class="cc-lesson-todo" @click="$emit('edit', lesson)">待补全</span>

      <div class="cc-lesson-row__ops">
        <button class="cc-icon-btn cc-icon-btn--sm" title="标记有效" @click="$emit('feedback', lesson, true)">
          <svg class="cc-i cc-i--sm"><use href="#i-check" /></svg>
        </button>
        <button class="cc-icon-btn cc-icon-btn--sm" title="标记无效" @click="$emit('feedback', lesson, false)">
          <svg class="cc-i cc-i--sm" style="transform: rotate(45deg)"><use href="#i-plus" /></svg>
        </button>
        <button class="cc-icon-btn cc-icon-btn--sm" title="补全 / 编辑" @click="$emit('edit', lesson)">
          <svg class="cc-i cc-i--sm"><use href="#i-brush" /></svg>
        </button>
        <button class="cc-icon-btn cc-icon-btn--sm" title="删除" @click="$emit('delete', lesson)">
          <svg class="cc-i cc-i--sm"><use href="#i-trash" /></svg>
        </button>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'

export interface LessonItem {
  id: number | string
  /** 项目标识（隔离第一维度，取项目根目录名） */
  projectKey?: string
  toolName?: string
  /** COMPILE / DEPENDENCY / NETWORK / AUTH / MCP_HANDSHAKE / SQL / PARAM / ENV / OTHER */
  errorCategory?: string
  /** 归一化错误码，如 NoClassDefFoundError / ECONNREFUSED / 401 */
  errorCode?: string
  symptom?: string
  rootCause?: string
  /** 解法，支持 {变量} 占位符 */
  solution?: string
  /** 变量参数 JSON 字符串 */
  paramsJson?: string
  applicableCond?: string
  keywords?: string
  /** 0=草稿 1=有效 2=隐藏 */
  status?: number
  /** auto / llm / manual / review */
  source?: string
  /** FAILURE=失败经验 DETOUR=弯路经验 */
  type?: string
  /** DETOUR 专用：任务目标 */
  goal?: string
  hitCount?: number
  successCount?: number
  failCount?: number
  createdAt?: string
  updatedAt?: string
}

const props = defineProps<{ lesson: LessonItem }>()

defineEmits<{
  (e: 'edit', lesson: LessonItem): void
  (e: 'feedback', lesson: LessonItem, effective: boolean): void
  (e: 'delete', lesson: LessonItem): void
}>()

/** 后端自动捕获草稿写入的占位解法：视为「无可用解法」 */
const PLACEHOLDER_SOLUTION = '(自动捕获，解法待 LLM 或人工补充)'

const isDetour = computed(() => props.lesson.type === 'DETOUR')
const isHidden = computed(() => Number(props.lesson.status) === 2)

const statusText = computed(() => {
  switch (Number(props.lesson.status)) {
    case 1: return '已验证'
    case 2: return '已隐藏'
    default: return '草稿'
  }
})

const statusMod = computed(() => {
  switch (Number(props.lesson.status)) {
    case 1: return 'is-connected'
    case 2: return ''
    default: return 'is-draft'
  }
})

const SOURCE_MAP: Record<string, string> = {
  auto: '自动捕获',
  llm: 'LLM 记录',
  manual: '人工沉淀',
  review: '对话复盘'
}

const sourceText = computed(() => SOURCE_MAP[props.lesson.source || ''] || '未知来源')

/** 与后端 Lesson.hasUsableSolution() 对齐 */
const hasSolution = computed(() => {
  const solution = (props.lesson.solution || '').trim()
  return solution.length > 0 && solution !== PLACEHOLDER_SOLUTION
})

/** 相对时间：今天 HH:mm / 昨天 / N 天前 / 具体日期 */
function formatTime(value?: string): string {
  if (!value) return '-'
  const date = new Date(value.replace(/-/g, '/'))
  if (Number.isNaN(date.getTime())) return value
  const now = new Date()
  if (date.toDateString() === now.toDateString()) {
    return `今天 ${String(date.getHours()).padStart(2, '0')}:${String(date.getMinutes()).padStart(2, '0')}`
  }
  const diffDays = Math.floor((now.getTime() - date.getTime()) / 86_400_000)
  if (diffDays === 1) return '昨天'
  if (diffDays > 1 && diffDays < 7) return `${diffDays} 天前`
  return `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, '0')}-${String(date.getDate()).padStart(2, '0')}`
}
</script>
