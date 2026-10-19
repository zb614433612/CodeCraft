<!--
  踩坑经验 详情 / 补全抽屉（v2）
  ----------------------------------------------------------------------------
  右侧滑出（520px）。一个抽屉同时承担「看详情」与「补全解法」：
    1) 上半部为只读事实区（项目 / 工具 / 类别 / 错误码 / 类型·状态 / 来源 / 计数 / 时间 / 现象 / 目标）
       —— 保证在任何列表行点开都能先看清上下文；
    2) 下半部为可编辑字段（根因 / 解法 / 关键参数 / 适用条件 / 关键词）。
  与后端对齐的关键约定（LessonController PUT /lessons/{id}）：
    - 只更新非空字段：留空表示不改，不支持通过本接口清空某字段；
    - status 不由本接口修改：转正 / 隐藏仍由「有效 / 无效」反馈驱动（≥2 次有效转正，连续 5 次失败隐藏）；
    - 解法支持 {变量名} 占位符，配合 paramsJson 做参数级精确匹配。
  数据接缝：
    v-model:open  -> 抽屉开关
    lesson        -> 当前行数据（来自 GET /lessons）
    emit('save')  -> PUT /lessons/{id}（payload 只含非空字段）
-->
<template>
  <aside class="cc-drawer" :class="{ 'is-open': open }" style="--drawer-w: 520px">
    <DrawerResizer storage-key="lesson-edit" />
    <div class="cc-drawer__head">
      <div style="font-size: var(--fs-md); font-weight: 700">{{ isDraft ? '补全草稿经验' : '经验详情 / 补全' }}</div>
      <button class="cc-icon-btn" style="margin-left: auto" title="关闭" @click="close">
        <svg class="cc-i cc-i--sm" style="transform: rotate(45deg)"><use href="#i-plus" /></svg>
      </button>
    </div>

    <div class="cc-drawer__body">
      <!-- 只读事实区 -->
      <div class="cc-lesson-facts">
        <div class="cc-lesson-facts__item">
          <span class="cc-lesson-facts__label">项目</span>
          <span class="cc-lesson-facts__value">{{ lesson?.projectKey || '-' }}</span>
        </div>
        <div class="cc-lesson-facts__item">
          <span class="cc-lesson-facts__label">工具</span>
          <span class="cc-lesson-facts__value">{{ lesson?.toolName || '-' }}</span>
        </div>
        <div class="cc-lesson-facts__item">
          <span class="cc-lesson-facts__label">错误类别</span>
          <span class="cc-lesson-facts__value">{{ lesson?.errorCategory || '-' }}</span>
        </div>
        <div class="cc-lesson-facts__item">
          <span class="cc-lesson-facts__label">错误码</span>
          <span class="cc-lesson-facts__value is-mono">{{ lesson?.errorCode || '-' }}</span>
        </div>
        <div class="cc-lesson-facts__item">
          <span class="cc-lesson-facts__label">类型 / 状态</span>
          <span class="cc-lesson-facts__value">{{ typeText }} · {{ statusText }}</span>
        </div>
        <div class="cc-lesson-facts__item">
          <span class="cc-lesson-facts__label">来源</span>
          <span class="cc-lesson-facts__value">{{ sourceText }}</span>
        </div>
        <div class="cc-lesson-facts__item">
          <span class="cc-lesson-facts__label">命中 / 有效 / 失败</span>
          <span class="cc-lesson-facts__value">
            {{ lesson?.hitCount || 0 }} / {{ lesson?.successCount || 0 }} / {{ lesson?.failCount || 0 }}
          </span>
        </div>
        <div class="cc-lesson-facts__item">
          <span class="cc-lesson-facts__label">更新时间</span>
          <span class="cc-lesson-facts__value">{{ lesson?.updatedAt || '-' }}</span>
        </div>
        <div v-if="lesson?.goal" class="cc-lesson-facts__item cc-lesson-facts__item--wide">
          <span class="cc-lesson-facts__label">任务目标（弯路经验检索维度）</span>
          <span class="cc-lesson-facts__text">{{ lesson.goal }}</span>
        </div>
        <div v-if="lesson?.symptom" class="cc-lesson-facts__item cc-lesson-facts__item--wide">
          <span class="cc-lesson-facts__label">失败现象</span>
          <span class="cc-lesson-facts__text">{{ lesson.symptom }}</span>
        </div>
      </div>

      <!-- 可编辑字段 -->
      <div class="cc-form">
        <div class="cc-field">
          <label class="cc-field__label">根因</label>
          <textarea v-model="form.rootCause" class="cc-textarea" rows="2" placeholder="根因分析（留空表示不更新）" />
        </div>

        <div class="cc-field">
          <label class="cc-field__label">解法</label>
          <textarea
            v-model="form.solution"
            class="cc-textarea"
            rows="4"
            placeholder="解法，支持 {变量} 占位符（留空表示不更新）"
          />
          <div class="cc-field__hint">支持 {变量名} 占位符，检索命中后会替换为本次环境的实际值</div>
        </div>

        <div class="cc-field">
          <label class="cc-field__label">关键参数</label>
          <textarea
            v-model="form.paramsJson"
            class="cc-textarea"
            rows="3"
            placeholder='JSON 数组，如 [{"name":"缺失依赖","value":"starter-web"}]'
          />
          <div v-if="paramsError" class="cc-field__hint" style="color: var(--danger)">{{ paramsError }}</div>
          <div v-else class="cc-field__hint">JSON 数组，用于参数级精确匹配（同名参数值不一致时不会命中）</div>
        </div>

        <div class="cc-field">
          <label class="cc-field__label">适用条件</label>
          <input v-model="form.applicableCond" class="cc-input" placeholder="何时适用 / 何时不适用（留空表示不更新）" />
        </div>

        <div class="cc-field">
          <label class="cc-field__label">关键词</label>
          <input v-model="form.keywords" class="cc-input" placeholder="空格分隔的检索标签（留空表示不更新）" />
        </div>

        <div class="cc-field">
          <div class="cc-field__hint">
            留空表示不更新（后端只写入非空字段）；经验是否「已验证」由列表中的「有效 / 无效」反馈驱动，不在此处直接修改。
          </div>
        </div>
      </div>
    </div>

    <div class="cc-drawer__foot">
      <button class="cc-btn" @click="close">取消</button>
      <button class="cc-btn cc-btn--primary" :disabled="!canSave" @click="onSave">保存</button>
    </div>
  </aside>
</template>

<script setup lang="ts">
import DrawerResizer from './DrawerResizer.vue'
import { computed, reactive, watch } from 'vue'
import type { LessonItem } from './LessonRow.vue'

const props = withDefaults(
  defineProps<{
    open: boolean
    lesson?: LessonItem | null
  }>(),
  {
    lesson: null
  }
)

const emit = defineEmits<{
  (e: 'update:open', v: boolean): void
  (e: 'save', payload: Record<string, unknown>): void
}>()

/** 后端自动捕获草稿写入的占位解法：视为「无可用解法」 */
const PLACEHOLDER_SOLUTION = '(自动捕获，解法待 LLM 或人工补充)'

const TYPE_MAP: Record<string, string> = { FAILURE: '失败经验', DETOUR: '弯路经验' }
const SOURCE_MAP: Record<string, string> = {
  auto: '自动捕获',
  llm: 'LLM 记录',
  manual: '人工沉淀',
  review: '对话复盘'
}

const form = reactive({
  rootCause: '',
  solution: '',
  paramsJson: '',
  applicableCond: '',
  keywords: ''
})

/** 打开时回填：占位解法不回填，避免把占位符当成真实解法保存 */
watch(
  () => [props.open, props.lesson] as const,
  () => {
    if (!props.open) return
    const l = props.lesson
    form.rootCause = l?.rootCause || ''
    const solution = (l?.solution || '').trim()
    form.solution = solution === PLACEHOLDER_SOLUTION ? '' : solution
    form.paramsJson = l?.paramsJson || ''
    form.applicableCond = l?.applicableCond || ''
    form.keywords = l?.keywords || ''
  },
  { immediate: true }
)

const isDraft = computed(() => Number(props.lesson?.status) === 0)

const typeText = computed(() => TYPE_MAP[props.lesson?.type || ''] || '失败经验')

const statusText = computed(() => {
  switch (Number(props.lesson?.status)) {
    case 1: return '已验证'
    case 2: return '已隐藏'
    default: return '草稿'
  }
})

const sourceText = computed(() => SOURCE_MAP[props.lesson?.source || ''] || '未知来源')

/** 参数 JSON 合法性（留空视为不更新，不校验） */
const paramsError = computed(() => {
  const raw = form.paramsJson.trim()
  if (!raw) return ''
  try {
    const parsed = JSON.parse(raw)
    return Array.isArray(parsed) ? '' : '应为 JSON 数组，例如 [{"name":"参数名","value":"参数值"}]'
  } catch {
    return 'JSON 格式有误，请检查引号与逗号'
  }
})

/** 至少填写一个字段才允许保存（全空提交等于什么都没改） */
const canSave = computed(() => {
  if (paramsError.value) return false
  return Boolean(
    form.rootCause.trim() ||
    form.solution.trim() ||
    form.paramsJson.trim() ||
    form.applicableCond.trim() ||
    form.keywords.trim()
  )
})

function close() {
  emit('update:open', false)
}

function onSave() {
  if (!canSave.value) return
  // 只提交非空字段：与后端「只更新非空」语义一致
  const payload: Record<string, unknown> = { id: props.lesson?.id }
  if (form.rootCause.trim()) payload.rootCause = form.rootCause.trim()
  if (form.solution.trim()) payload.solution = form.solution.trim()
  if (form.paramsJson.trim()) payload.paramsJson = form.paramsJson.trim()
  if (form.applicableCond.trim()) payload.applicableCond = form.applicableCond.trim()
  if (form.keywords.trim()) payload.keywords = form.keywords.trim()
  emit('save', payload)
}
</script>
