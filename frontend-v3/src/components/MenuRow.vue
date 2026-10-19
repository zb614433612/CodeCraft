<!--
  菜单行（v2）
  ----------------------------------------------------------------------------
  信息层次：菜单名称 + 路由路径（等宽）
          → 权限标识（permissionCode，缺省留空）
          → 类型徽章（由父级分组头统一展示，行内不重复）
          → hover 显操作：编辑 / 删除
  约束：status=0 的行整行降饱和（与其它管理页一致）；visible=0 时在权限列标注「隐藏」。
-->
<template>
  <div class="cc-mp-menu" :class="{ 'is-off': menu.status !== 1 }">
    <div class="cc-mp-menu__main">
      <span class="cc-mp-menu__name">{{ menu.name }}</span>
      <span class="cc-mp-menu__path">{{ menu.path || '—' }}</span>
    </div>
    <span class="cc-mp-menu__perm">{{ permissionText }}</span>
    <div class="cc-mp-menu__ops">
      <button class="cc-icon-btn cc-icon-btn--sm" title="编辑" @click="$emit('edit', menu)">
        <svg class="cc-i cc-i--sm"><use href="#i-brush" /></svg>
      </button>
      <button class="cc-icon-btn cc-icon-btn--sm" title="删除" @click="$emit('delete', menu)">
        <svg class="cc-i cc-i--sm"><use href="#i-trash" /></svg>
      </button>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'

/** 菜单项（对应后端 Menu 实体 / sys_menu 表） */
export interface MenuItem {
  id: number
  name: string
  path?: string
  /** 旧前端图标组件名，如 UserOutlined */
  icon?: string
  parentId?: number | null
  sortOrder?: number
  permissionCode?: string
  /** LINK / AI / TOOL / SYSTEM / MANAGE / SETTING */
  menuType: string
  /** 1=可见，0=隐藏 */
  visible?: number
  /** 1=启用，0=停用 */
  status?: number
}

const props = defineProps<{
  menu: MenuItem
}>()

defineEmits<{
  (e: 'edit', menu: MenuItem): void
  (e: 'delete', menu: MenuItem): void
}>()

/** 权限列：优先权限码；隐藏时提示「隐藏」；都没有则留空 */
const permissionText = computed(() => {
  if (props.menu.permissionCode) return props.menu.permissionCode
  return props.menu.visible === 0 ? '隐藏' : ''
})
</script>
