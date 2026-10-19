/**
 * 任务清单面板（v2，F12 已补优先级 / 依赖 / 阻塞态）
 * ----------------------------------------------------------------------------
 * 对应后端 task_manager 工具的输出（T1/T2/T3…），默认收起，避免长期占用消息区高度。
 *
 * 注意：v3 聊天页已把任务看板改成「状态栏按钮 + 上弹浮层」（见 ChatView 的 .cc-taskpop），
 * 本组件当前**只在需要内嵌展示时使用**，类型定义与统计口径统一收敛到 utils/task-list，
 * 避免浮层与内嵌面板两套逻辑漂移。
 *
 * 展示规则：
 *   - 头部一行显示进度（已完成 / 总数 / 阻塞数）+ 当前进行中的任务标题；
 *   - 优先级用行左侧色条表达（HIGH 红 / MEDIUM 橙 / LOW 灰）；
 *   - 前置依赖未完成时显示「等待 T1、T3」并置灰，状态文字变「阻塞中」。
 -->
<template>
  <div class="cc-task-panel" :class="{ 'is-open': open }">
    <div class="cc-task-panel__head" @click="open = !open">
      <svg class="cc-i cc-i--sm"><use href="#i-task" /></svg>
      任务清单
      <span v-if="!open && doingItem" class="cc-task-panel__progress" style="flex: 1; text-align: left; margin-left: 8px; color: var(--text-3)">
        {{ doingItem.title }}
      </span>
      <span v-if="stats.blocked" class="cc-task-panel__blocked">{{ stats.blocked }} 阻塞</span>
      <span class="cc-task-panel__progress">{{ stats.done }} / {{ stats.total }}</span>
      <svg class="cc-i cc-i--sm cc-proc__caret" :style="{ transform: open ? 'rotate(90deg)' : '', marginLeft: '6px' }">
        <use href="#i-chev" />
      </svg>
    </div>

    <div class="cc-task-panel__body">
      <div
        v-for="item in items"
        :key="item.id"
        class="cc-task-item"
        :class="{
          'is-done': item.status === 'done',
          'is-doing': item.status === 'doing',
          'is-blocked': isBlocked(item, items)
        }"
      >
        <span class="cc-task-item__prio" :class="`is-${(item.priority || 'none').toLowerCase()}`" />
        <span class="cc-task-item__box">{{ item.status === 'done' ? '✓' : '' }}</span>
        <span>{{ item.id }} · {{ item.title }}</span>
        <span v-if="blockedBy(item, items).length" class="cc-task-item__wait">
          等待 {{ blockedBy(item, items).join('、') }}
        </span>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, ref } from 'vue'
import { blockedBy, isBlocked, taskStats, type TaskItem } from '../utils/task-list'

const props = withDefaults(
  defineProps<{
    items?: TaskItem[]
    /** 是否默认展开 */
    defaultOpen?: boolean
  }>(),
  {
    items: () => [],
    defaultOpen: false
  }
)

const open = ref(props.defaultOpen)
const stats = computed(() => taskStats(props.items))
const doingItem = computed(() => props.items.find(i => i.status === 'doing'))
</script>
