<!--
  技能行（v2）
  ----------------------------------------------------------------------------
  为什么用「单列行」而不是卡片网格：
    技能的核心信息是 名称 / 描述 / 触发词 / 置信度 / 使用次数，横向排布更省高度，
    且触发词 chips 能在同一行自然展开，网格卡片会把它们挤成多行。
  置信度语义（与后端一致）：confidence >= 0.1 才会被注入系统提示，低于该值视为「已隐藏」。
    展示分档：>=0.6 已激活（绿）· 0.1~0.6 验证中（黄）· <0.1 已隐藏（灰，进度条置灰）
  数据接缝：
    skill.*        -> GET /skills?userId=
    emit('edit')   -> 打开编辑抽屉
    emit('delete') -> DELETE /skills/{id}?userId=（页面二次确认后调用）
-->
<template>
  <div class="cc-skill-row">
    <!-- 图标：按技能名首字或图标字段展示 -->
    <div class="cc-skill-row__icon" :style="isHidden ? 'background: var(--card-2); color: var(--text-3)' : ''">
      <svg class="cc-i"><use :href="'#' + (skill.icon || 'i-skill')" /></svg>
    </div>

    <div class="cc-skill-row__main">
      <!-- 名称 + 作用域 + 状态 + 操作 -->
      <div class="cc-skill-row__title">
        <span class="cc-skill-name">{{ skill.name }}</span>
        <span v-if="skill.agentName" class="cc-tag cc-tag--accent">{{ skill.agentName }}</span>
        <span v-else class="cc-tag">全局</span>
        <span class="cc-tag" :class="statusTagClass">{{ statusText }}</span>

        <div class="cc-skill-row__actions" @click.stop>
          <button class="cc-icon-btn cc-icon-btn--sm" title="编辑" @click="$emit('edit', skill)">
            <svg class="cc-i cc-i--sm"><use href="#i-brush" /></svg>
          </button>
          <button class="cc-icon-btn cc-icon-btn--sm" title="删除" @click="$emit('delete', skill)">
            <svg class="cc-i cc-i--sm"><use href="#i-trash" /></svg>
          </button>
        </div>
      </div>

      <!-- 描述 -->
      <p class="cc-skill-row__desc">{{ skill.description || '暂无描述' }}</p>

      <!-- 触发词（超出 5 个折叠为 +N） -->
      <div v-if="triggerList.length" class="cc-skill-row__triggers">
        <span v-for="w in triggerList.slice(0, 5)" :key="w" class="cc-trigger">{{ w }}</span>
        <span v-if="triggerList.length > 5" class="cc-trigger cc-trigger--more">+{{ triggerList.length - 5 }}</span>
      </div>

      <!-- 置信度 + 使用次数 + 更新时间 -->
      <div class="cc-skill-row__foot">
        <span class="cc-confidence">
          <span class="cc-confidence__track">
            <span
              class="cc-confidence__fill"
              :class="{ 'is-low': isHidden }"
              :style="{ width: confidencePercent + '%' }"
            />
          </span>
          <span class="cc-confidence__value">{{ confidencePercent }}%</span>
        </span>
        <span class="cc-skill-meta">使用 <b>{{ skill.usageCount || 0 }}</b> 次</span>
        <span v-if="skill.updatedAt" class="cc-skill-meta">更新于 {{ formatTime(skill.updatedAt) }}</span>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'

export interface SkillItem {
  id: number | string
  name: string
  description?: string
  /** 置信度 0~1；后端默认 0.5 */
  confidence?: number
  /** 作用域：为空表示全局 */
  agentConfigId?: number | null
  agentName?: string
  /** JSON 数组字符串或数组 */
  triggerWords?: string | string[]
  usageCount?: number
  updatedAt?: string | number
  /** 图标 symbol id（默认 i-skill） */
  icon?: string
}

const props = defineProps<{ skill: SkillItem }>()

defineEmits<{
  (e: 'edit', skill: SkillItem): void
  (e: 'delete', skill: SkillItem): void
}>()

/** 置信度百分比（0~100） */
const confidencePercent = computed(() => Math.round((props.skill.confidence ?? 0.5) * 100))

/** 低于 0.1 视为已隐藏（后端不再注入） */
const isHidden = computed(() => (props.skill.confidence ?? 0.5) < 0.1)

/** 状态文案与配色：>=0.6 已激活 / 0.1~0.6 验证中 / <0.1 已隐藏 */
const statusText = computed(() => {
  const c = props.skill.confidence ?? 0.5
  if (c >= 0.6) return '已激活'
  if (c >= 0.1) return '验证中'
  return '已隐藏'
})

const statusTagClass = computed(() => {
  const c = props.skill.confidence ?? 0.5
  if (c >= 0.6) return 'cc-tag--ok'
  if (c >= 0.1) return 'cc-tag--warn'
  return ''
})

/** 触发词解析：兼容 JSON 字符串（"[\"a\",\"b\"]"）与数组 */
const triggerList = computed<string[]>(() => {
  const raw = props.skill.triggerWords
  if (!raw) return []
  if (Array.isArray(raw)) return raw.filter(Boolean)
  try {
    const parsed = JSON.parse(raw)
    return Array.isArray(parsed) ? parsed.filter(Boolean) : []
  } catch {
    // 兜底：按逗号分隔
    return String(raw).split(/[,，]/).map(s => s.trim()).filter(Boolean)
  }
})

/** 相对时间：今天 HH:mm / 昨天 / N 天前 */
function formatTime(value: string | number): string {
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return String(value)
  const now = new Date()
  const sameDay = date.toDateString() === now.toDateString()
  if (sameDay) {
    return `今天 ${String(date.getHours()).padStart(2, '0')}:${String(date.getMinutes()).padStart(2, '0')}`
  }
  const diffDays = Math.floor((now.getTime() - date.getTime()) / 86_400_000)
  if (diffDays === 1) return '昨天'
  if (diffDays < 7) return `${diffDays} 天前`
  if (diffDays < 30) return `${Math.floor(diffDays / 7)} 周前`
  return `${date.getMonth() + 1} 月 ${date.getDate()} 日`
}
</script>
