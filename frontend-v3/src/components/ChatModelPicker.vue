<!--
  模型与运行模式选择（v3 聊天页）
  ----------------------------------------------------------------------------
  聊天页 Composer 的「模型」按钮点开本弹层，共 5 组设置：
    1. Provider   —— LLM 提供商切换（对齐旧版 CodeAssistantView「更多设置 → Provider」）
    2. 模型        —— 随 Provider 联动，只列该 Provider 的 modelList
    3. 上下文模式  —— full（全量）/ compact（精简），对齐旧版「更多设置 → 上下文」
    4. 思考模式
    5. 执行模式

  数据来源：
    providers    来自「模型配置」页已启用 Provider（App.vue 的 agentProviderOptions 派生）
    providerCode 当前生效的 Provider（Agent 绑定值 / 本会话临时覆盖值）
    contextMode  来自 settingsStore.contextMode（persist 到 localStorage，另同步 sys_config）

  交互：
    应用 → emit('apply', { providerCode, model, thinkingMode, executionMode, contextMode })
         → 父组件把前 4 项写回 Agent 运行时、contextMode 写 settingsStore + sys_config
    切 Provider → 若当前模型不在新 Provider 模型表里，自动改成 defaultModel / 列表首项
                （只在用户手动切换时纠正，打开弹层回填不触发，避免改坏历史配置）

  布局（v2 视觉重构）：
    统一「标签 → 控件 → 说明」的纵向节奏，控件高度一律 34px、圆角一律 --r-sm，
    不再让原生 select / 圆角 chips / 分段按钮三种形态混排：
      · 下拉框自绘 chevron（原生箭头各系统观感不一，是「乱」的主要来源）
      · 模型列表改成整宽单选行（模型名普遍偏长，chips 会换行参差不齐）
      · 上下文模式改成等宽双卡片，说明直接可见，不再依赖 hover tooltip

  显隐：`.is-open` 类控制（基础态 display:none），与静态预览共用同一条 CSS 规则，
        不要改回 v-show（v-show 只清内联 display，覆盖不了样式表里的 display:none）。
-->
<template>
  <Teleport to="body">
    <div class="cc-modelpick" :class="{ 'is-open': open }">
      <div class="cc-modelpick__mask" @click="close" />
      <div class="cc-modelpick__box" role="dialog" aria-modal="true" aria-label="模型与运行模式">
        <div class="cc-modelpick__head">
          <span>模型与运行模式</span>
          <button class="cc-icon-btn cc-icon-btn--sm" title="关闭" @click="close">
            <svg class="cc-i cc-i--sm" style="transform: rotate(45deg)"><use href="#i-plus" /></svg>
          </button>
        </div>

        <div class="cc-modelpick__body">
          <!-- 1. Provider：决定后端用哪家 LLM（ChatRequest.providerCode 优先级高于 Agent 绑定） -->
          <div v-if="providers.length" class="cc-mpk">
            <div class="cc-mpk__label">提供商</div>
            <div class="cc-mpk__select">
              <select v-model="draftProvider" class="cc-select" @change="onProviderChange">
                <option v-for="p in providers" :key="p.code" :value="p.code">
                  {{ p.name || p.code }}<template v-if="p.isDefault">（默认）</template>
                </option>
              </select>
              <svg class="cc-i cc-i--sm cc-mpk__caret"><use href="#i-chev" /></svg>
            </div>
          </div>

          <!-- 2. 模型：只列当前 Provider 的 modelList，未配置时回退全局合集 -->
          <div class="cc-mpk">
            <div class="cc-mpk__label">
              模型
              <span v-if="activeProviderName" class="cc-mpk__sub">{{ activeProviderName }}</span>
            </div>
            <div class="cc-mpk__models">
              <button
                v-for="item in modelOptions"
                :key="item"
                class="cc-mpk__model"
                :class="{ 'is-active': item === draftModel }"
                :title="item"
                @click="draftModel = item"
              >
                <span class="cc-mpk__model-name">{{ item }}</span>
                <svg class="cc-i cc-i--sm cc-mpk__tick"><use href="#i-check" /></svg>
              </button>
              <div v-if="!modelOptions.length" class="cc-mpk__models-empty">
                当前提供商未配置模型列表，请先到「模型配置」页补 modelList
              </div>
            </div>
          </div>

          <!-- 3. 上下文模式：full 全量注入 / compact 精简历史工具调用与思考过程 -->
          <div class="cc-mpk">
            <div class="cc-mpk__label">上下文模式</div>
            <div class="cc-mpk__cards">
              <button
                v-for="opt in CONTEXT_MODES"
                :key="opt.value"
                class="cc-mpk__card"
                :class="{ 'is-active': draftContext === opt.value }"
                :title="opt.tip"
                @click="draftContext = opt.value"
              >
                <span class="cc-mpk__card-title">{{ opt.label }}</span>
                <span class="cc-mpk__card-desc">{{ opt.desc }}</span>
              </button>
            </div>
          </div>

          <div class="cc-mpk__row">
            <div class="cc-mpk">
              <div class="cc-mpk__label">思考模式</div>
              <div class="cc-mpk__select">
                <select v-model="draftThinking" class="cc-select">
                  <option value="">默认</option>
                  <option value="thinking">思考</option>
                  <option value="thinking_max">深度思考</option>
                  <option value="non-thinking">关闭思考</option>
                </select>
                <svg class="cc-i cc-i--sm cc-mpk__caret"><use href="#i-chev" /></svg>
              </div>
            </div>
            <div class="cc-mpk">
              <div class="cc-mpk__label">执行模式</div>
              <div class="cc-mpk__select">
                <select v-model="draftExecution" class="cc-select">
                  <option value="auto">自动执行</option>
                  <option value="manual">手动确认</option>
                </select>
                <svg class="cc-i cc-i--sm cc-mpk__caret"><use href="#i-chev" /></svg>
              </div>
            </div>
          </div>
        </div>

        <div class="cc-modelpick__foot">
          <button class="cc-btn" @click="close">取消</button>
          <button class="cc-btn cc-btn--primary" @click="apply">应用</button>
        </div>
      </div>
    </div>
  </Teleport>
</template>

<script setup lang="ts">
import { computed, ref, watch } from 'vue'

/** Provider 选项（与 App.vue 的 AgentProviderOption 结构兼容，只取本弹层需要的字段） */
export interface ModelPickerProvider {
  code: string
  name?: string
  models?: string[]
  defaultModel?: string
  isDefault?: boolean
}

/**
 * 上下文模式选项。
 * 值与后端 `ChatRequest.contextMode` / `sys_config.context_mode` 完全一致（full / compact），
 * 不要改成中文或驼峰，后端 `DeepSeekServiceImpl` 用 `"compact".equals(contextMode)` 精确判定。
 *
 * desc 会直接显示在卡片里（v2 说明文字从 tooltip 挪到明面上），控制在 10 字以内以保证单行。
 */
const CONTEXT_MODES = [
  {
    value: 'full',
    label: '全量',
    desc: '历史消息完整注入',
    tip: '全部历史消息完整注入（对齐旧版「📋 全量」）'
  },
  {
    value: 'compact',
    label: '精简',
    desc: '历史工具仅留摘要',
    tip: '历史轮次的工具调用只留摘要、思考过程移除，节省上下文'
  }
] as const

const props = withDefaults(
  defineProps<{
    open?: boolean
    /** Provider 列表（已启用），空数组时不渲染 Provider 下拉 */
    providers?: ModelPickerProvider[]
    /** 当前生效的 Provider code */
    providerCode?: string
    /** 兜底模型列表（Provider 未配置 modelList 时使用） */
    models?: string[]
    model?: string
    thinkingMode?: string
    executionMode?: string
    /** 上下文模式：full / compact */
    contextMode?: string
  }>(),
  {
    open: false,
    providers: () => [],
    providerCode: '',
    models: () => [],
    model: '',
    thinkingMode: '',
    executionMode: 'auto',
    contextMode: 'full'
  }
)

const emit = defineEmits<{
  (e: 'update:open', value: boolean): void
  (
    e: 'apply',
    payload: {
      providerCode: string
      model: string
      thinkingMode: string
      executionMode: string
      contextMode: string
    }
  ): void
}>()

const draftProvider = ref(props.providerCode)
const draftModel = ref(props.model)
const draftThinking = ref(props.thinkingMode)
const draftExecution = ref(props.executionMode)
const draftContext = ref(props.contextMode || 'full')

/** 当前选中的 Provider（找不到时返回 undefined，此时模型列表回退全局合集） */
const activeProvider = computed(() => props.providers.find(p => p.code === draftProvider.value))

const activeProviderName = computed(() => activeProvider.value?.name || '')

/**
 * 模型候选：
 *   1) 当前 Provider 配置了 modelList → 只列它（切 Provider 就等于换模型集）
 *   2) Provider 没配置 → 回退全部 Provider 合集（props.models）
 *   3) 当前已选模型始终插到首位，避免历史配置被静默替换成别的模型
 */
const modelOptions = computed<string[]>(() => {
  const own = activeProvider.value?.models ?? []
  const base = own.length ? own : props.models
  const current = draftModel.value
  if (!current) return [...base]
  if (base.includes(current)) return [...base]
  return [current, ...base]
})

/**
 * 切 Provider 后纠正模型。
 *
 * 只在用户手动切换时触发（@change），打开弹层回填不会走到这里 ——
 * 否则编辑一个「模型不属于当前 Provider」的历史配置时会被悄悄改掉。
 */
function onProviderChange() {
  const own = activeProvider.value?.models ?? []
  const pool = own.length ? own : props.models
  if (!pool.length) return
  if (pool.includes(draftModel.value)) return
  draftModel.value = activeProvider.value?.defaultModel || pool[0]
}

/** 每次打开都从父组件的当前值重新初始化，避免残留上次未应用的修改 */
watch(
  () => props.open,
  open => {
    if (!open) return
    draftProvider.value = props.providerCode || props.providers[0]?.code || ''
    draftModel.value = props.model || props.models[0] || ''
    draftThinking.value = props.thinkingMode || ''
    draftExecution.value = props.executionMode || 'auto'
    draftContext.value = props.contextMode || 'full'
  }
)

function close() {
  emit('update:open', false)
}

function apply() {
  emit('apply', {
    providerCode: draftProvider.value,
    model: draftModel.value,
    thinkingMode: draftThinking.value,
    executionMode: draftExecution.value,
    contextMode: draftContext.value
  })
  close()
}
</script>
