<!--
  智能体切换器（聊天页侧栏顶部）
  ----------------------------------------------------------------------------
  补齐「多个智能体在聊天页并行切换会话」：此前只有从智能体管理页点卡片才能换
  Agent（要点两次页面跳转），聊天过程中想换个 Agent 干活非常别扭。

  关键行为（与多会话并行流配合）：
    · 切换 Agent **不 abort** 任何正在跑的后台流 —— 那些流按会话 key 分片存放，
      切走只是不再显示，切回来内容还在；
    · 每个 Agent 名称旁显示「N 个会话运行中」，让用户知道换过去之后别处还在干活；
    · 切换后会话列表按新 Agent 重新拉取（后端 /conversation/list 按 agentConfigId 过滤）。

  数据接缝：
    agents    -> Agent 列表（id / name / avatar / model / isDefault / runningCount）
    currentId -> 当前 Agent id
    emit('switch') -> 用户点了另一个 Agent，参数为 id
-->
<template>
  <div ref="rootEl" class="cc-agentpick">
    <button
      type="button"
      class="cc-agentpick__trigger"
      :class="{ 'is-open': open }"
      title="切换智能体"
      @click.stop="open = !open"
    >
      <span class="cc-agentpick__avatar">
        <img v-if="currentAvatarIsImage" :src="currentAvatar" :alt="currentName" />
        <template v-else>{{ currentInitial }}</template>
      </span>
      <span class="cc-agentpick__text">
        <span class="cc-agentpick__name">{{ currentName }}</span>
        <span class="cc-agentpick__model">{{ currentModel || '未配置模型' }}</span>
      </span>
      <svg class="cc-i cc-i--sm cc-agentpick__chev"><use href="#i-chev" /></svg>
    </button>

    <!-- 弹层：显隐用 .is-open 类（基础态 display:none，v-show 盖不住，见踩坑经验 216） -->
    <div class="cc-agentpick__pop" :class="{ 'is-open': open }">
      <div class="cc-agentpick__pop-title">切换智能体</div>
      <button
        v-for="a in agents"
        :key="a.id"
        type="button"
        class="cc-agentpick__item"
        :class="{ 'is-active': String(a.id) === String(currentId) }"
        @click="pick(a.id)"
      >
        <span class="cc-agentpick__avatar is-sm">
          <img v-if="isImageAvatar(a.avatar)" :src="a.avatar" :alt="a.name" />
          <template v-else>{{ textAvatar(a.avatar, a.name) }}</template>
        </span>
        <span class="cc-agentpick__item-text">
          <span class="cc-agentpick__item-name">
            {{ a.name }}
            <span v-if="a.isDefault" class="cc-agentpick__tag">默认</span>
          </span>
          <span class="cc-agentpick__item-model">{{ a.model || '未配置模型' }}</span>
        </span>
        <!-- 该 Agent 名下仍在后台跑的会话数：提示「切过去之后别处还在干活」 -->
        <span v-if="a.runningCount" class="cc-agentpick__running" :title="`${a.runningCount} 个会话正在后台运行`">
          {{ a.runningCount }}
        </span>
      </button>
      <div v-if="!agents.length" class="cc-agentpick__empty">还没有可用的智能体</div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { isImageAvatar } from '../utils/avatar'

/**
 * 文本头像优先用 Agent 自己配的 emoji / 单字，没配才退回名字首字。
 * 不用 avatarFallback：它取的是「传入字符串的首字符」，emoji 会截断代理对变乱码。
 */
function textAvatar(avatar: string | undefined, name: string): string {
  const text = (avatar || '').trim()
  if (text) return text
  return (name || '?').slice(0, 1)
}

export interface AgentPickerItem {
  id: number | string
  name: string
  avatar?: string
  model?: string
  isDefault?: boolean
  /** 该 Agent 名下正在后台运行的会话数（0 / 不传不显示） */
  runningCount?: number
}

const props = withDefaults(
  defineProps<{
    agents?: AgentPickerItem[]
    currentId?: number | string
  }>(),
  { agents: () => [], currentId: '' }
)

const emit = defineEmits<{ (e: 'switch', id: number | string): void }>()

const open = ref(false)
const rootEl = ref<HTMLElement | null>(null)

const current = computed(() => props.agents.find(a => String(a.id) === String(props.currentId)))
const currentName = computed(() => current.value?.name || '未选择智能体')
const currentModel = computed(() => current.value?.model || '')
const currentAvatar = computed(() => current.value?.avatar || '')
const currentAvatarIsImage = computed(() => isImageAvatar(currentAvatar.value))
const currentInitial = computed(() => textAvatar(currentAvatar.value, currentName.value))

function pick(id: number | string) {
  open.value = false
  if (String(id) === String(props.currentId)) return
  emit('switch', id)
}

function onDocClick(ev: MouseEvent) {
  if (!open.value) return
  const target = ev.target as Node | null
  if (rootEl.value && target && rootEl.value.contains(target)) return
  open.value = false
}
function onDocKeydown(ev: KeyboardEvent) {
  if (ev.key === 'Escape') open.value = false
}

onMounted(() => {
  document.addEventListener('click', onDocClick)
  document.addEventListener('keydown', onDocKeydown)
})
onBeforeUnmount(() => {
  document.removeEventListener('click', onDocClick)
  document.removeEventListener('keydown', onDocKeydown)
})
</script>
