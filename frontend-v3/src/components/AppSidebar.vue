<!--
  侧栏（v2）
  ----------------------------------------------------------------------------
  结构：品牌区 → 智能体切换器（仅聊天页）→ 新建会话 → 一级导航（≤5 项）
        → 会话分组列表 → 底部用户区
  二级管理页面统一从一级导航「设置」进入（SettingsView 读 menu.config 的
  MORE_GROUPS 渲染全部入口），侧栏不再重复挂一份「更多」菜单。
  折叠态：由 AppShell 的 `.cc-shell.is-collapsed` 驱动，样式见 app.css 第 4 节；
  折叠后只留图标，文字类节点全部隐藏，交互靠 title 提示。
  样式：assets/app.css 中 `.cc-sidebar` / `.cc-nav` / `.cc-conv` / `.cc-user` 等。
-->
<template>
  <aside class="cc-sidebar">
    <!-- 品牌区 -->
    <div class="cc-sidebar__head">
      <div class="cc-logo">C</div>
      <div class="cc-brand">CodeCraft</div>
      <button
        class="cc-icon-btn"
        :title="collapsed ? '展开侧栏' : '折叠侧栏'"
        @click="$emit('toggle-collapse')"
      >
        <svg class="cc-i cc-i--sm"><use href="#i-fold" /></svg>
      </button>
    </div>

    <!-- 智能体切换器：只在聊天页出现（管理页没有「当前 Agent」概念，切了会误导） -->
    <ChatAgentPicker
      v-if="inChat"
      :agents="agents"
      :current-id="currentAgentId"
      @switch="$emit('switch-agent', $event)"
    />

    <!-- 新建会话
         必须用 <button>：全局重置只给 button 加了 cursor:pointer，div 悬停没有手指光标，
         也拿不到 Tab 聚焦与 Enter/Space 激活。折叠态文字隐藏，靠 title 兜住可读性。 -->
    <button
      type="button"
      class="cc-new-chat"
      :title="collapsed ? '新建会话' : undefined"
      @click="$emit('new-chat')"
    >
      <svg class="cc-i cc-i--sm"><use href="#i-plus" /></svg><span>新建会话</span>
    </button>

    <!-- 一级导航（折叠态只剩图标，故用 title 兜住可读性） -->
    <nav class="cc-nav">
      <div
        v-for="item in primaryNav"
        :key="item.key"
        class="cc-nav__item"
        :class="{ 'is-active': item.key === activeKey }"
        :title="item.label"
        @click="$emit('select-menu', item.key)"
      >
        <svg class="cc-i"><use :href="'#' + item.icon" /></svg>
        <span>{{ item.label }}</span>
        <span v-if="item.count" class="cc-nav__count">{{ item.count }}</span>
      </div>
    </nav>

    <!-- 会话搜索：只在已加载的会话列表里做本地过滤（会话按 Agent 拉取，量级有限，无需后端接口） -->
    <div class="cc-conv-search">
      <svg class="cc-i cc-i--sm cc-conv-search__icon"><use href="#i-search" /></svg>
      <input
        v-model="convQuery"
        class="cc-conv-search__input"
        type="text"
        placeholder="搜索会话"
        @keydown.esc="convQuery = ''"
      />
      <button v-if="convQuery" class="cc-conv-search__clear" title="清空" @click="convQuery = ''">×</button>
    </div>

    <!-- 会话列表（按时间分组）
         选中态只在聊天页生效：切到管理页后不再残留高亮，避免「以为还在对话里」 -->
    <div class="cc-conv-list" @scroll="closeConvMenu">
      <template v-for="group in filteredGroups" :key="group.title">
        <div class="cc-conv-group">{{ group.title }}</div>
        <div v-for="conv in group.items" :key="conv.id" class="cc-conv-item" :data-conv-id="conv.id">
          <div
            class="cc-conv"
            :class="{ 'is-active': inChat && conv.id === activeConvId, 'is-menu-open': convMenuId === conv.id }"
            @click="onSelectConv(conv.id)"
          >
            <span class="cc-conv__name" :title="conv.title">{{ conv.title }}</span>
            <!--
              任务清单进度徽章（多任务并行）：别的会话在跑任务时，只有这里能看出它完成了多少。
              与运行圆点/感叹号并列：徽章表达「有多少活」，圆点表达「是否在动」。
            -->
            <span
              v-if="conv.taskTotal"
              class="cc-conv__tasks"
              :title="`任务清单：${conv.taskDone} / ${conv.taskTotal} 已完成`"
            >{{ conv.taskDone }}/{{ conv.taskTotal }}</span>
            <!--
              会话状态：等待确认优先于运行中（同一会话两者可能同时为真，
              但「需要你动手」比「还在跑」更紧急，只显示一个图标避免挤在一起）
            -->
            <span v-if="conv.pending" class="cc-conv__pending" title="等待你的确认">!</span>
            <span v-else-if="conv.running" class="cc-conv__dot" title="后台任务运行中" />
            <button class="cc-conv__more" title="更多操作" @click.stop="toggleConvMenu(conv.id, $event)">
              <svg class="cc-i cc-i--sm"><use href="#i-more" /></svg>
            </button>
          </div>
        </div>
      </template>

      <!-- 空态：区分「一条都没有」与「搜索无结果」，后者提示清空关键词 -->
      <div
        v-if="filteredGroups.length === 0"
        style="padding: 24px 10px; font-size: 12.5px; color: var(--text-4); text-align: center"
      >
        {{ convQuery ? `没有匹配「${convQuery}」的会话` : '暂无会话，点击「新建会话」开始' }}
      </div>
    </div>

    <!--
      底部：用户信息
      --------------------------------------------------------------------------
      这里曾经还有一个「更多」入口，弹出面板承载的全部二级页面（踩坑经验 / MCP /
      文件 / 数据库 / 日志 / 设备互联 / 用户 / 模型配置 / 外观 …）与「设置」聚合页
      完全同源（都读 menu.config 的 MORE_GROUPS），等于同一份菜单挂了两处入口。
      现统一收口到「设置」页，侧栏只保留一级导航。
    -->
    <div class="cc-sidebar__foot">
      <div class="cc-user" :class="{ 'is-open': userMenuOpen }">
        <button
          type="button"
          class="cc-user__main"
          :title="collapsed ? userName : '账号菜单'"
          :aria-expanded="userMenuOpen"
          @click.stop="toggleUserMenu"
        >
          <span class="cc-user__avatar">
            <img v-if="userAvatarIsImage" :src="userAvatar" :alt="userName" />
            <template v-else>{{ userInitial }}</template>
          </span>
          <span class="cc-user__name">{{ userName }}</span>
          <svg class="cc-i cc-i--sm cc-user__chev"><use href="#i-chev" /></svg>
        </button>

        <!--
          账号菜单：向上弹出（用户区在侧栏底部）。
          用 fixed 定位 + 点击时算坐标，而不是 absolute：
          折叠态侧栏只有 64px 宽，absolute 会被裁掉；展开态也可能撞上侧栏的 overflow。
        -->
        <div class="cc-user-menu" :class="{ 'is-open': userMenuOpen }" :style="userMenuStyle">
          <button type="button" class="cc-user-menu__item" @click="onUserMenu('profile')">
            <svg class="cc-i cc-i--sm"><use href="#i-users" /></svg>个人信息
          </button>
          <button type="button" class="cc-user-menu__item" @click="onUserMenu('appearance')">
            <svg class="cc-i cc-i--sm"><use href="#i-brush" /></svg>外观设置
          </button>
          <button type="button" class="cc-user-menu__item" @click="onUserMenu('theme')">
            <svg class="cc-i cc-i--sm"><use :href="dark ? '#i-sun' : '#i-moon'" /></svg>
            {{ dark ? '切换到浅色' : '切换到深色' }}
          </button>
          <div class="cc-user-menu__sep" />
          <button type="button" class="cc-user-menu__item" @click="onUserMenu('version')">
            <svg class="cc-i cc-i--sm"><use href="#i-sync" /></svg>切换到经典版
          </button>
          <button type="button" class="cc-user-menu__item is-danger" @click="onUserMenu('logout')">
            <svg class="cc-i cc-i--sm"><use href="#i-logout" /></svg>退出登录
          </button>
        </div>
      </div>
    </div>

    <!--
      会话操作菜单（重命名 / 导出 / 删除）
      --------------------------------------------------------------------------
      全列表共用一个实例 + Teleport 到 body，而不是每条会话项里各挂一份。

      为什么必须 Teleport：
        「外观设置 → 窗口背景图」开启后 `.cc-shell.has-bg .cc-sidebar` 会带
        backdrop-filter —— 该属性会把侧栏变成 fixed 后代的 containing block，
        于是菜单按视口算出来的 top/right 全被当成「相对侧栏」的偏移，整块跑到
        侧栏左侧之外并被裁掉；用户看到的现象就是「三个点能展开，但重命名/删除点不到」。
        挂到 body 后坐标回到纯视口坐标，不受任何祖先的 backdrop-filter / overflow 影响。

      单例（v-if + convMenuId）而不是逐项 Teleport：会话可能上百条，
      每条挂一个 Teleport 容器纯属浪费；同一时刻本来就只允许展开一个。
    -->
    <Teleport to="body">
      <div v-if="convMenuId" class="cc-conv-menu is-open" :style="convMenuStyle" @click.stop>
        <button type="button" class="cc-conv-menu__item" @click.stop="onRenameConv(convMenuId)">
          <svg class="cc-i cc-i--sm"><use href="#i-brush" /></svg>
          重命名
        </button>
        <button type="button" class="cc-conv-menu__item" @click.stop="onExportConv(convMenuId)">
          <svg class="cc-i cc-i--sm"><use href="#i-disk" /></svg>
          导出 Markdown
        </button>
        <button type="button" class="cc-conv-menu__item is-danger" @click.stop="onDeleteConv(convMenuId)">
          <svg class="cc-i cc-i--sm"><use href="#i-trash" /></svg>
          删除
        </button>
      </div>
    </Teleport>

  </aside>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { isImageAvatar } from '../utils/avatar'
import ChatAgentPicker, { type AgentPickerItem } from './ChatAgentPicker.vue'
import { PRIMARY_NAV, type ConversationGroup, type MenuItem } from '../menu.config'

const props = withDefaults(
  defineProps<{
    /** 当前选中的菜单 key（路由 path） */
    activeKey?: string
    /** 会话分组（建议由 ChatView 用 groupConversationsByTime 生成） */
    groups?: ConversationGroup[]
    /** 当前会话 id */
    activeConvId?: string
    /** 用户名（用于底部头像首字与名称） */
    userName?: string
    /** 当前用户头像（图片 URL / emoji / 单字）；为空时用昵称首字 */
    userAvatar?: string
    /** 是否暗色（决定主题按钮图标） */
    dark?: boolean
    /**
     * 当前用户是否管理员。
     *
     * <p>默认 false（最小权限）：身份尚未确认时先不显示管理员入口，
     * 与路由守卫 {@code meta.adminOnly} 共用 menu.config 的同一份标记。</p>
     */
    isAdmin?: boolean
    /** 智能体切换器数据（id / name / avatar / model / isDefault / runningCount） */
    agents?: AgentPickerItem[]
    /** 当前选中的智能体 id */
    currentAgentId?: number | string
    /**
     * 侧栏是否已折叠。
     *
     * <p>组件只拿它决定折叠按钮的 title（「展开 / 折叠侧栏」）；
     * 真正的布局收窄由 AppShell 的 `.cc-shell.is-collapsed` 类负责。</p>
     */
    collapsed?: boolean
  }>(),
  {
    activeKey: '/chat',
    groups: () => [],
    activeConvId: '',
    userName: '用户',
    userAvatar: '',
    dark: false,
    isAdmin: false,
    agents: () => [],
    currentAgentId: '',
    collapsed: false
  }
)

const emit = defineEmits<{
  (e: 'select-menu', key: string): void
  (e: 'select-conv', id: string): void
  (e: 'rename-conv', id: string): void
  (e: 'export-conv', id: string): void
  (e: 'delete-conv', id: string): void
  (e: 'new-chat'): void
  (e: 'toggle-collapse'): void
  (e: 'toggle-theme'): void
  /** 切换智能体（聊天页侧栏顶部） */
  (e: 'switch-agent', id: number | string): void
  /** 账号菜单：打开个人信息页 */
  (e: 'open-profile'): void
  /** 账号菜单：退出登录（确认框与后端注销由父级负责） */
  (e: 'logout'): void
  /** 账号菜单：切换到经典版（v2 页面，由父级写偏好标记并跳转） */
  (e: 'switch-version'): void
}>()

/** 是否处于聊天页：会话高亮只在聊天页表达「当前正在看这条对话」 */
const inChat = computed(() => props.activeKey === '/chat')

/** 会话搜索关键词（本地过滤，不请求接口） */
const convQuery = ref('')

/**
 * 过滤后的会话分组。
 *
 * <p>命中为空的组一并剔除：否则搜索「张三」后会留下「今天 / 昨天 / 更早」三个空标题，
 * 看起来像列表坏了。搜索无结果时由模板里的空态文案单独提示。</p>
 */
const filteredGroups = computed<ConversationGroup[]>(() => {
  const keyword = convQuery.value.trim().toLowerCase()
  if (!keyword) return props.groups
  return props.groups
    .map<ConversationGroup>(group => ({
      title: group.title,
      items: group.items.filter(item => (item.title || '').toLowerCase().includes(keyword))
    }))
    .filter(group => group.items.length > 0)
})

/** 当前展开操作菜单的会话 id（同时只开一个） */
const convMenuId = ref('')
/** 菜单的视口坐标（fixed 定位，避免被 .cc-conv-list 的 overflow 裁剪） */
const convMenuPos = ref({ top: 0, right: 0 })

const convMenuStyle = computed(() => ({
  top: `${convMenuPos.value.top}px`,
  right: `${convMenuPos.value.right}px`
}))

/**
 * 会话操作菜单的估算尺寸（与 app.css 里 `.cc-conv-menu` 的 min-width / 3 项高度对齐）。
 * 只用于「向下展开会不会掉出视口」的判断，取略大的值即可，不必精确测量。
 */
const CONV_MENU_W = 132
const CONV_MENU_H = 118

function toggleConvMenu(id: string, event?: MouseEvent) {
  if (convMenuId.value === id) {
    convMenuId.value = ''
    return
  }
  const button = (event?.currentTarget as HTMLElement | null) ?? null
  if (button) {
    const rect = button.getBoundingClientRect()
    /*
     * right = 视口右缘到按钮右缘的距离（菜单右边缘对齐按钮）。
     * 再夹一道：窗口很窄时菜单可能被推到视口左侧之外，这时改成贴左 8px；
     * 列表最后几条会话靠近窗口底部，向下展开会被视口截断 —— 翻到按钮上方。
     */
    const right = Math.max(8, Math.min(window.innerWidth - rect.right, window.innerWidth - CONV_MENU_W - 8))
    const below = rect.bottom + 4
    const top =
      below + CONV_MENU_H > window.innerHeight - 8
        ? Math.max(8, rect.top - 4 - CONV_MENU_H)
        : below
    convMenuPos.value = { top, right }
  }
  convMenuId.value = id
}

function closeConvMenu() {
  convMenuId.value = ''
}

/** 点会话项：收起菜单并交给父级切换（父级负责在非聊天页时跳回聊天页） */
function onSelectConv(id: string) {
  convMenuId.value = ''
  emit('select-conv', id)
}

function onRenameConv(id: string) {
  convMenuId.value = ''
  emit('rename-conv', id)
}

function onExportConv(id: string) {
  convMenuId.value = ''
  emit('export-conv', id)
}

function onDeleteConv(id: string) {
  convMenuId.value = ''
  emit('delete-conv', id)
}

/**
 * 一级导航：过滤掉当前角色不可见的项。
 * 与路由守卫共用 menu.config 的 adminOnly 标记，避免「菜单能点进去却被弹回」的割裂体验。
 */
const primaryNav = computed<MenuItem[]>(() =>
  PRIMARY_NAV.filter(item => !item.adminOnly || props.isAdmin)
)

const userInitial = computed(() => (props.userName || 'U').charAt(0).toUpperCase())

/**
 * 用户头像是否为图片地址。
 * 判定与 ChatMessage / AgentCard 共用 utils/avatar，避免三处正则漂移
 * （emoji 被塞进 `<img src>` 会变成裂图）。
 */
const userAvatarIsImage = computed(() => isImageAvatar(props.userAvatar))

/**
 * 账号菜单（个人信息 / 外观 / 主题 / 退出登录）。
 *
 * <p>用户区原先摆着两个孤零零的图标按钮（外观、主题），加上「个人信息」「退出登录」
 * 会变成四个，268px 侧栏挤、折叠态更挤。改成「点头像/名字弹菜单」：
 * 折叠态只剩一个头像，菜单向上弹出，位置在点击时按按钮矩形算（fixed 定位，不会被裁剪）。</p>
 */
const userMenuOpen = ref(false)
const userMenuPos = ref({ bottom: 0, left: 0 })

const userMenuStyle = computed(() => ({
  bottom: `${userMenuPos.value.bottom}px`,
  left: `${userMenuPos.value.left}px`
}))

function toggleUserMenu(event: MouseEvent) {
  if (userMenuOpen.value) {
    userMenuOpen.value = false
    return
  }
  const button = event.currentTarget as HTMLElement | null
  if (!button) return
  const rect = button.getBoundingClientRect()
  // 菜单向上展开：bottom 用「视口高度 - 按钮顶边」换算，left 对齐按钮左边
  userMenuPos.value = {
    bottom: Math.max(8, window.innerHeight - rect.top + 6),
    left: Math.max(8, Math.min(rect.left, window.innerWidth - 200))
  }
  userMenuOpen.value = true
}

/** 菜单项点击：先收起菜单再派发，避免菜单停留在新页面上 */
function onUserMenu(action: 'profile' | 'appearance' | 'theme' | 'version' | 'logout') {
  userMenuOpen.value = false
  if (action === 'profile') emit('open-profile')
  else if (action === 'appearance') emit('select-menu', '/appearance')
  else if (action === 'theme') emit('toggle-theme')
  else if (action === 'version') emit('switch-version')
  else emit('logout')
}

/**
 * 点击菜单外部时收起会话操作菜单。
 *
 * <p>触发按钮自身（`@click.stop` 已做 toggle）与菜单内部不视为「外部」：
 * 事件冒泡到 document 时，按钮的 toggle 已把 convMenuId 设为当前会话，
 * 若此处再置空就会出现「点了没反应」。用 closest 判断可以一并覆盖图标子节点。</p>
 */
function onDocumentClick(event: MouseEvent) {
  const target = event.target as Element | null
  if (convMenuId.value && !target?.closest('.cc-conv-menu') && !target?.closest('.cc-conv__more')) {
    convMenuId.value = ''
  }
  // 账号菜单同理：按钮与菜单内部不关（按钮自己 toggle），其余位置一律收起
  if (userMenuOpen.value && !target?.closest('.cc-user-menu') && !target?.closest('.cc-user__main')) {
    userMenuOpen.value = false
  }
}

/** ESC 收起会话菜单与账号菜单（与抽屉/确认框的键位习惯保持一致） */
function onDocumentKeydown(event: KeyboardEvent) {
  if (event.key !== 'Escape') return
  convMenuId.value = ''
  userMenuOpen.value = false
}

onMounted(() => {
  document.addEventListener('click', onDocumentClick)
  document.addEventListener('keydown', onDocumentKeydown)
  // 菜单坐标是「打开那一刻」算的视口坐标：窗口一改尺寸就对不上按钮了，直接收起更稳
  window.addEventListener('resize', closeConvMenu)
})

onBeforeUnmount(() => {
  document.removeEventListener('click', onDocumentClick)
  document.removeEventListener('keydown', onDocumentKeydown)
  window.removeEventListener('resize', closeConvMenu)
})
</script>
