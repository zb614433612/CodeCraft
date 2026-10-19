<!--
  审批 / 询问卡片（v3 接线版）
  ----------------------------------------------------------------------------
  对应后端 SSE `ask_user` 事件，三种 askType 决定交互形态：
    permission        工具授权     → 同意 / 本会话全部同意 / 拒绝 / 自定义指令
    clarification     向用户提问   → 必须输入文本回答（或跳过）
    iteration_confirm 任务迭代确认 → 结束任务 / 继续 +10 / +20 / +50 次

  正文渲染：
    后端 question 是 markdown 原文（可能含工具详情、文件 diff 片段、代码块），
    这里统一走 `renderMarkdown` 渲染后 v-html 输出，代码替换行才有红绿高亮。
    同时先做一次「字面量 \n → 真换行」归一（AI 双重转义时会整段挤成一行）。

  数据接缝：
    title / description / command / from  ← ApprovalPayload（chat-mapper 生成）
    askType                               ← 后端事件体，缺省按 permission 处理
  事件：
    approve / approve-all / reject  → submitAnswer(uuid, action) 的三种 action
    answer(text)                    → submitAnswer(uuid, text, 'custom')
    continue(n)                     → 迭代确认扩容：submitAnswer(uuid, 'n', 'approve')
                                      （后端把 answer 当扩容次数解析，与旧版语义一致）
-->
<template>
  <div class="cc-approval">
    <div class="cc-approval__head">
      <svg class="cc-i cc-approval__icon"><use href="#i-shield" /></svg>
      {{ title }}
      <span v-if="from" class="cc-approval__from">来自 {{ from }}</span>
    </div>

    <!-- 问题正文：markdown（含代码块 / diff 红绿 / 列表 / 表格） -->
    <div v-if="description" class="cc-approval__desc cc-md" v-html="descHtml" />
    <div v-if="command" class="cc-approval__cmd">{{ command }}</div>

    <!-- clarification：AI 在提问，必须文本回答 -->
    <template v-if="isQuestion">
      <textarea
        v-model="text"
        class="cc-approval__input"
        rows="2"
        placeholder="输入你的回答，Enter 提交（Shift+Enter 换行）"
        @keydown.enter.exact.prevent="submitText"
      />
      <div class="cc-approval__actions">
        <button class="cc-btn cc-btn--primary" :disabled="!text.trim()" @click="submitText">发送回答</button>
        <button class="cc-btn cc-btn--ghost" @click="$emit('reject')">跳过此问题</button>
      </div>
    </template>

    <!-- iteration_confirm：结束 or 继续（并指定本次扩容的迭代次数） -->
    <template v-else-if="isIteration">
      <div class="cc-approval__actions">
        <button class="cc-btn cc-btn--danger" @click="$emit('reject')">结束任务</button>
        <button
          v-for="step in ITERATION_STEPS"
          :key="step"
          class="cc-btn"
          :title="`再把迭代上限提高 ${step} 次，从当前进度继续执行`"
          @click="$emit('continue', step)"
        >
          继续 +{{ step }} 次
        </button>
      </div>
      <div class="cc-approval__hint">
        选择「继续」后任务会从当前进度接着执行；选「结束任务」则终止本次循环并保留已产出的结果。
      </div>
    </template>

    <!-- permission：工具授权 -->
    <template v-else>
      <textarea
        v-model="text"
        class="cc-approval__input"
        rows="2"
        placeholder="或输入其他指令（可选，Enter 发送）"
        @keydown.enter.exact.prevent="submitText"
      />
      <div class="cc-approval__actions">
        <button class="cc-btn cc-btn--primary" @click="$emit('approve')">同意</button>
        <button class="cc-btn" @click="$emit('approve-all')">本会话全部同意</button>
        <button v-if="text.trim()" class="cc-btn" @click="submitText">发送指令</button>
        <button class="cc-btn cc-btn--ghost" @click="$emit('reject')">拒绝</button>
      </div>
    </template>
  </div>
</template>

<script setup lang="ts">
import { computed, ref } from 'vue'
import { renderMarkdown } from '../utils/markdown'

/** 迭代确认的扩容档位（与后端「迭代上限 + N」语义对齐） */
const ITERATION_STEPS = [10, 20, 50] as const

const props = withDefaults(
  defineProps<{
    /** 卡片标题，如「需要确认：执行工具」 */
    title?: string
    /** 说明文本（后端 question 原文，markdown） */
    description?: string
    /** 命令/参数摘要（等宽展示） */
    command?: string
    /** 发起方 Agent 名称 */
    from?: string
    /** 后端 askType：permission / clarification / iteration_confirm */
    askType?: string
  }>(),
  {
    title: '需要确认',
    description: '',
    command: '',
    from: '',
    askType: 'permission'
  }
)

const emit = defineEmits<{
  (e: 'approve'): void
  (e: 'approve-all'): void
  (e: 'reject'): void
  (e: 'answer', text: string): void
  /** 迭代确认：继续执行，参数为本次扩容的迭代次数 */
  (e: 'continue', additional: number): void
}>()

/** 提问类（必须文本回答） */
const isQuestion = computed(() => props.askType === 'clarification')
/** 任务迭代确认（结束 / 继续 +N 次，没有「全部同意」语义） */
const isIteration = computed(() => props.askType === 'iteration_confirm')

const text = ref('')

/**
 * 问题正文 HTML。
 * 后端可能下发双重转义的字面量 `\n`，先归一成真换行，否则整段挤成一行、
 * 代码块围栏也会失效；再做一次 markdown 渲染（含代码高亮 / diff 红绿）。
 */
const descHtml = computed(() => {
  const raw = (props.description || '').replace(/\\n/g, '\n')
  return renderMarkdown(raw)
})

function submitText() {
  const value = text.value.trim()
  if (!value) return
  emit('answer', value)
  text.value = ''
}
</script>
