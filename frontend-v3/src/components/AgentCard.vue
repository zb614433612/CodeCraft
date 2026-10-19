<!--
  Agent 卡片（v3）
  ----------------------------------------------------------------------------
  展示一个 Agent 的核心信息，操作按钮 hover 才出现（保持首屏干净）。
  信息层次：头像 + 名称 + 标签 → 模型/执行模式 → 描述（2 行截断）→ 工作目录 + 工具数
  数据接缝：
    agent.id / name / description / avatar   -> Agent 配置接口
    agent.model / executionMode              -> 运行时配置（可来自默认 Provider）
    agent.toolsCount / workDir               -> 工具集与工作目录
    agent.isDefault / isBuiltin              -> 标签展示 + 菜单项的启用状态
  交互：
    edit        -> 打开编辑抽屉
    set-default -> 设为默认智能体（PUT /api/agent-configs/{id}/default）
    delete      -> 删除智能体（DELETE /api/agent-configs/{id}）
    select      -> 点击卡片进入该 Agent 的会话
  说明：
    1) 后端除 CRUD 外只有「设为默认」一个独立操作接口，没有复制、没有启用/停用，
       因此「更多」菜单固定两项：设为默认 / 删除。
    2) 内置智能体不可删除（后端会拒绝），菜单项直接 disabled，不让用户白点一次。
-->
<template>
  <article
    class="cc-agent-card"
    :class="{ 'is-menu-open': menuOpen }"
    @click="$emit('select', agent.id)"
  >
    <div class="cc-agent-card__head">
      <!-- 头像：图片优先，否则展示 emoji/首字 -->
      <div class="cc-agent-avatar">
        <img v-if="isImageAvatar" :src="agent.avatar" :alt="agent.name" />
        <span v-else>{{ agent.avatar || agent.name.charAt(0) }}</span>
      </div>

      <div style="min-width: 0; flex: 1">
        <div class="cc-agent-card__title">
          <span class="cc-agent-name">{{ agent.name }}</span>
          <span v-if="agent.isDefault" class="cc-tag cc-tag--accent">默认</span>
          <span v-if="agent.isBuiltin" class="cc-tag">内置</span>
          <span v-else class="cc-tag">自定义</span>
          <!-- 角色性格是可选装饰项，仅在配置了内容时标记，避免每张卡片都挂一个灰标签 -->
          <span
            v-if="agent.hasCharacter"
            class="cc-tag cc-tag--warn"
            title="已配置角色性格，运行时注入系统提示词"
            >🎭 性格</span
          >
        </div>
        <div style="margin-top: 4px; font-size: var(--fs-xs); color: var(--text-3)">
          {{ modelLabel }}<template v-if="executionLabel"> · {{ executionLabel }}</template>
        </div>
      </div>

      <div class="cc-agent-card__actions" @click.stop>
        <button class="cc-icon-btn cc-icon-btn--sm" title="编辑" @click="$emit('edit', agent)">
          <svg class="cc-i cc-i--sm"><use href="#i-brush" /></svg>
        </button>
        <button
          ref="menuBtn"
          class="cc-icon-btn cc-icon-btn--sm"
          :class="{ 'is-active': menuOpen }"
          title="更多"
          aria-haspopup="menu"
          :aria-expanded="menuOpen"
          @click="toggleMenu"
        >
          <svg class="cc-i cc-i--sm"><use href="#i-more" /></svg>
        </button>
      </div>
    </div>

    <p class="cc-agent-desc">{{ agent.description || '暂无描述' }}</p>

    <div class="cc-agent-meta">
      <span class="cc-agent-meta__path" :title="agent.workDir || '未设置工作目录'">
        <svg class="cc-i cc-i--sm"><use href="#i-file" /></svg>{{ agent.workDir || '未设置工作目录' }}
      </span>
      <span class="cc-tag">{{ toolsLabel }}</span>
    </div>

    <!--
      「更多」下拉菜单。
      注意：显隐必须走 is-open 类（app.css 里 .cc-agent-menu 基础态是 display:none），
      不要改成 v-show —— v-show 只清空内联 display，覆盖不了样式表的 display:none。
    -->
    <div
      ref="menuEl"
      class="cc-agent-menu"
      :class="{ 'is-open': menuOpen }"
      role="menu"
      @click.stop
    >
      <button
        class="cc-agent-menu__item"
        role="menuitem"
        :disabled="agent.isDefault"
        @click="onSetDefault"
      >
        <svg class="cc-i cc-i--sm"><use href="#i-check" /></svg>
        <span>{{ agent.isDefault ? '已是默认智能体' : '设为默认' }}</span>
      </button>
      <button
        class="cc-agent-menu__item is-danger"
        role="menuitem"
        :disabled="agent.isBuiltin"
        :title="agent.isBuiltin ? '内置智能体不可删除' : '删除后不可恢复'"
        @click="onDelete"
      >
        <svg class="cc-i cc-i--sm"><use href="#i-trash" /></svg>
        <span>删除</span>
      </button>
    </div>
  </article>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { isImageAvatar as isImageAvatarUrl } from '../utils/avatar'

export interface AgentItem {
  id: number | string
  name: string
  description?: string
  /** emoji 或图片 URL */
  avatar?: string
  model: string
  /**
   * 绑定的 Provider code（deepseek / volcengine / …）。
   * 后端 `AgentConfig.providerCode` 可为空，空表示跟随默认 Provider。
   */
  providerCode?: string
  executionMode?: string
  toolsCount?: number
  workDir?: string
  isDefault?: boolean
  isBuiltin?: boolean
  /** 是否已配置角色性格（由 characterProfile 是否为空推导，非后端字段） */
  hasCharacter?: boolean
}

const props = defineProps<{ agent: AgentItem }>()

const emit = defineEmits<{
  (e: 'edit', agent: AgentItem): void
  /** 设为默认（后端 PUT /agent-configs/{id}/default） */
  (e: 'set-default', agent: AgentItem): void
  /** 删除（后端 DELETE /agent-configs/{id}；内置智能体会被后端拒绝） */
  (e: 'delete', agent: AgentItem): void
  (e: 'select', id: number | string): void
}>()

/** 头像是否为图片 URL（统一走 utils/avatar，避免与侧栏 / 聊天气泡的正则漂移） */
const isImageAvatar = computed(() => isImageAvatarUrl(props.agent.avatar))

/**
 * 模型展示文案
 *
 * <p>带上 Provider code 是为了消除歧义：不同 Provider 可能有同名模型（如自定义平台
 * 复用 `deepseek-chat`），只显示模型名会让人误判实际请求打到哪家。</p>
 */
const modelLabel = computed(() => {
  const model = props.agent.model || '未指定模型'
  const code = (props.agent.providerCode || '').trim()
  return code ? `${code} · ${model}` : model
})

/**
 * 执行模式中文标签
 *
 * <p>后端存的是枚举值 `auto` / `manual`，直接渲染会让卡片出现「deepseek-flash · auto」；
 * 转换只放在展示层，抽屉回填仍拿原始值，避免编辑时下拉框匹配不上选项。</p>
 */
const executionLabel = computed(() => {
  const mode = props.agent.executionMode
  if (!mode) return ''
  if (mode === 'auto') return '自动执行'
  if (mode === 'manual') return '手动确认'
  return mode
})

/**
 * 工具集标签
 *
 * <p>后端 `toolNames` 为空（null 或空串）表示「拥有全部工具」，这是内置 Agent 的默认状态；
 * 直接显示「0 个工具」会误导，故零值时改为「全部工具」。</p>
 */
const toolsLabel = computed(() => (props.agent.toolsCount ? `${props.agent.toolsCount} 个工具` : '全部工具'))

/* ---------------- 「更多」下拉菜单 ---------------- */
const menuOpen = ref(false)
const menuBtn = ref<HTMLButtonElement | null>(null)
const menuEl = ref<HTMLDivElement | null>(null)

function toggleMenu() {
  menuOpen.value = !menuOpen.value
}

function closeMenu() {
  menuOpen.value = false
}

/**
 * 菜单项点击统一先关菜单再派发事件。
 *
 * <p>事件派发后父组件可能立刻重新拉列表，此时卡片会被重建；先关菜单可以避免
 * 「菜单还开着但卡片数据已换」的闪烁。</p>
 */
function onSetDefault() {
  closeMenu()
  if (props.agent.isDefault) return
  emit('set-default', props.agent)
}

function onDelete() {
  closeMenu()
  if (props.agent.isBuiltin) return
  emit('delete', props.agent)
}

/**
 * 点击「更多」按钮与菜单以外区域 / 按 ESC 关闭。
 *
 * <p>每个卡片都注册监听是为了让多卡片天然互斥：点开 B 的菜单时，A 的监听会发现
 * 点击目标不在自己范围内从而自动关闭，无需父组件维护「当前打开的是哪张卡」。</p>
 */
function onDocumentClick(event: MouseEvent) {
  if (!menuOpen.value) return
  const target = event.target
  if (!(target instanceof Node)) return
  if (menuEl.value?.contains(target) || menuBtn.value?.contains(target)) return
  closeMenu()
}

function onDocumentKeydown(event: KeyboardEvent) {
  if (event.key === 'Escape' && menuOpen.value) closeMenu()
}

onMounted(() => {
  document.addEventListener('click', onDocumentClick)
  document.addEventListener('keydown', onDocumentKeydown)
})

onBeforeUnmount(() => {
  document.removeEventListener('click', onDocumentClick)
  document.removeEventListener('keydown', onDocumentKeydown)
})
</script>
