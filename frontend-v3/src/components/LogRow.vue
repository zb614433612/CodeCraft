<!--
  日志行（v2）
  ----------------------------------------------------------------------------
  列：行号 | 时间戳 | 级别 | 正文（等宽字体，长行自动换行，续行保留原始缩进）
  语义：
    1) 错误 / 警告行整行淡色底 + 左侧色条，长列表里能一眼定位问题；
    2) 级别色只作用于徽章与色条，正文保持主文字色，避免整屏花；
    3) 续行（异常栈帧）不重复时间戳与级别，正文降低对比度。
  说明：tail 接口只返回纯文本行，行号用当前展示序号（index + 1）；
        search 接口返回真实行号与来源文件，优先使用后端值。
-->
<template>
  <div
    class="cc-log-row"
    :class="[levelClass, { 'is-cont': part.continuation }]"
    :title="title"
    @click="$emit('copy', line.text)"
  >
    <span class="cc-log-row__no">{{ line.lineNumber ?? index + 1 }}</span>
    <span class="cc-log-row__ts">{{ part.ts }}</span>
    <span class="cc-log-row__lvl" :class="levelClass">{{ part.level }}</span>
    <span class="cc-log-row__msg">{{ part.msg }}</span>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { splitLogLine } from '../utils/log-line'

/** 日志行（tail 模式只有 text；搜索模式带后端行号与来源文件） */
export interface LogLineItem {
  id: number | string
  text: string
  /** 后端文件行号（搜索模式） */
  lineNumber?: number
  /** 来源文件名（搜索模式） */
  fileName?: string
}

const props = defineProps<{
  line: LogLineItem
  /** 当前展示序号（从 0 开始），无 lineNumber 时用 index + 1 展示 */
  index: number
}>()

defineEmits<{ (e: 'copy', text: string): void }>()

const part = computed(() => splitLogLine(props.line.text))

const levelClass = computed(() => (part.value.level ? 'is-' + part.value.level.toLowerCase() : ''))

const title = computed(() => {
  const from = props.line.fileName
    ? `${props.line.fileName}:${props.line.lineNumber ?? ''}`
    : `第 ${props.line.lineNumber ?? props.index + 1} 行`
  return `${from} · 点击复制该行`
})
</script>
