<!--
  定时任务行（v2）
  ----------------------------------------------------------------------------
  信息层次：时钟图标 + 名称 + 执行方式 + 状态  →  调度描述（中文 + cron 原文）+ Agent
            →  执行进度（已执行/上限）+ 上次执行 + 查看会话
  调度类型判定（与后端字段一致）：
    cronExpression 有值 -> 周期任务（Cron）
    executeTime 有值    -> 一次性任务
    都为空              -> 立即执行（历史数据兜底）
  数据接缝：
    task.*              -> GET /schedule-task/list
    emit('toggle-status', enabled) -> enable / disable 接口
    emit('run')         -> 立即执行（新增接口，或复用聊天发起一次会话）
    emit('view-conversation') -> 跳转到 lastConversationId 对应会话
-->
<template>
  <div class="cc-sched-row" :class="{ 'is-off': !enabled }">
    <div class="cc-sched-row__icon">
      <svg class="cc-i"><use href="#i-clock" /></svg>
    </div>

    <div class="cc-sched-row__main">
      <!-- 名称 + 执行方式 + 状态 -->
      <div class="cc-sched-row__title">
        <span class="cc-sched-name">{{ task.name }}</span>
        <span class="cc-tag" :class="typeTagClass">{{ typeLabel }}</span>
        <span class="cc-status" :class="{ 'is-connected': enabled }">
          <span class="cc-status__dot" />{{ enabled ? '启用中' : '已禁用' }}
        </span>
      </div>

      <!-- 调度描述 -->
      <div class="cc-sched-plan">
        <span>{{ planText }}</span>
        <template v-if="task.cronExpression">
          <span class="cc-sched-plan__sep">·</span>
          <code>{{ task.cronExpression }}</code>
        </template>
        <span class="cc-sched-plan__sep">·</span>
        <span>{{ agentName }}</span>
      </div>

      <!-- 执行进度 / 上次执行 -->
      <div class="cc-sched-row__meta">
        <span v-if="hasLimit" class="cc-sched-progress">
          <span class="cc-sched-progress__track">
            <span class="cc-sched-progress__fill" :style="{ width: progressPercent + '%' }" />
          </span>
          <span>执行 <b>{{ task.executeCount || 0 }}</b> / {{ task.maxExecuteCount }} 次</span>
        </span>
        <span v-else>已执行 <b>{{ task.executeCount || 0 }}</b> 次（不限次数）</span>

        <span>上次执行 {{ task.lastExecuteTime ? formatTime(task.lastExecuteTime) : '-' }}</span>

        <span v-if="task.lastConversationId" class="cc-sched-link" @click="$emit('view-conversation', task)">
          查看会话
        </span>
      </div>
    </div>

    <!-- 右侧：开关 + 立即执行 + hover 操作 -->
    <div class="cc-sched-row__side">
      <div
        class="cc-switch"
        :class="{ 'is-on': enabled }"
        :title="enabled ? '已启用' : '已停用'"
        @click="$emit('toggle-status', !enabled)"
      />

      <!-- 立即执行：POST /schedule-task/run/{id}（建会话 + 异步跑指令，不打乱调度计划） -->
      <button
        class="cc-text-btn"
        :disabled="runBusy"
        :title="runBusy ? '正在触发执行…' : '立刻执行一次，不影响定时计划'"
        @click="$emit('run', task)"
      >
        <svg class="cc-i cc-i--sm"><use href="#i-play" /></svg>{{ runBusy ? '执行中…' : '立即执行' }}
      </button>

      <div class="cc-sched-row__ops">
        <button class="cc-icon-btn cc-icon-btn--sm" title="编辑" @click="$emit('edit', task)">
          <svg class="cc-i cc-i--sm"><use href="#i-brush" /></svg>
        </button>
        <button class="cc-icon-btn cc-icon-btn--sm" title="删除" @click="$emit('delete', task)">
          <svg class="cc-i cc-i--sm"><use href="#i-trash" /></svg>
        </button>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'

export interface ScheduleTaskItem {
  id: number | string
  name: string
  agentConfigId?: number | null
  /** Agent 显示名（列表接口可直接返回，或由页面用 agentList 映射） */
  agentName?: string
  /** 周期任务表达式 */
  cronExpression?: string
  /** 一次性任务执行时间 */
  executeTime?: string
  instruction?: string
  /** 0 表示不限 */
  maxExecuteCount?: number
  executeCount?: number
  status?: 'ENABLED' | 'DISABLED' | string
  lastExecuteTime?: string
  lastConversationId?: string | number
}

const props = withDefaults(
  defineProps<{
    task: ScheduleTaskItem
    /** 该行正在「立即执行」（按钮转 loading 并禁用，防止重复触发） */
    runBusy?: boolean
  }>(),
  { runBusy: false }
)

defineEmits<{
  (e: 'edit', task: ScheduleTaskItem): void
  (e: 'delete', task: ScheduleTaskItem): void
  (e: 'toggle-status', enabled: boolean): void
  (e: 'run', task: ScheduleTaskItem): void
  (e: 'view-conversation', task: ScheduleTaskItem): void
}>()

const enabled = computed(() => props.task.status === 'ENABLED')

/** 执行方式：cron / 一次性 / 立即 */
const scheduleType = computed<'cron' | 'once' | 'immediate'>(() => {
  if (props.task.cronExpression) return 'cron'
  if (props.task.executeTime) return 'once'
  return 'immediate'
})

const typeLabel = computed(() => {
  switch (scheduleType.value) {
    case 'cron': return '周期'
    case 'once': return '一次性'
    default: return '立即'
  }
})

const typeTagClass = computed(() => (scheduleType.value === 'cron' ? 'cc-tag--accent' : ''))

const agentName = computed(() => props.task.agentName || '默认 Agent')

/** 是否设置了执行上限 */
const hasLimit = computed(() => Number(props.task.maxExecuteCount) > 0)

/** 进度百分比（无上限时不展示进度条） */
const progressPercent = computed(() => {
  const max = Number(props.task.maxExecuteCount) || 0
  if (max <= 0) return 0
  const done = Number(props.task.executeCount) || 0
  return Math.min(100, Math.round((done / max) * 100))
})

/** 调度描述：cron 尽量翻译成中文，无法识别时只展示表达式 */
const planText = computed(() => {
  if (scheduleType.value === 'cron') {
    return describeCron(props.task.cronExpression || '') || '按 Cron 表达式执行'
  }
  if (scheduleType.value === 'once') {
    return `${formatTime(props.task.executeTime || '')} 执行`
  }
  return '立即执行（无调度）'
})

const WEEK_MAP: Record<string, string> = {
  MON: '周一', TUE: '周二', WED: '周三', THU: '周四', FRI: '周五', SAT: '周六', SUN: '周日'
}

/**
 * 常用 cron 的中文描述（六位：秒 分 时 日 月 周）。
 * 只覆盖常见模式，识别不了返回空字符串，由调用方兜底展示原文。
 */
function describeCron(expr: string): string {
  const parts = expr.trim().split(/\s+/)
  if (parts.length < 6) return ''
  const [, min, hour, dom, , dow] = parts
  const pad = (v: string) => v.padStart(2, '0')

  // 每 N 分钟 / 每分钟
  if (min.startsWith('*/') && hour === '*') return `每 ${min.slice(2)} 分钟`
  if (min === '*' && hour === '*') return '每分钟'

  const time = `${pad(hour === '*' ? '0' : hour)}:${pad(min === '*' ? '0' : min)}`

  // 每小时
  if (hour === '*' && dom === '*') return `每小时第 ${min} 分`
  // 每周
  if (dow !== '?' && dow !== '*' && dow !== '') {
    const days = dow.split(',').map(d => WEEK_MAP[d.toUpperCase()] || d).join('、')
    return `每${days} ${time}`
  }
  // 每月
  if (dom !== '*' && dom !== '?' && dom !== '') return `每月 ${dom} 号 ${time}`
  // 每天
  if (dom === '*' && (dow === '?' || dow === '*')) return `每天 ${time}`

  return ''
}

/** 相对时间：今天 HH:mm / 昨天 / N 天前 / 具体日期 */
function formatTime(value: string): string {
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
  return `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, '0')}-${String(date.getDate()).padStart(2, '0')} ${String(date.getHours()).padStart(2, '0')}:${String(date.getMinutes()).padStart(2, '0')}`
}
</script>
