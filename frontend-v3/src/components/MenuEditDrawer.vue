<!--
  菜单编辑抽屉（v2）
  ----------------------------------------------------------------------------
  与后端对应：
    POST /api/menu/create   新增（menuType 与 schema.sql 枚举一致）
    PUT  /api/menu/update   更新
  数据接缝：
    v-model:open  -> 抽屉开关
    menu          -> 编辑对象（null 表示新增）
    emit('save')  -> payload 交给父组件发请求
  校验：名称必填；排序号与父级 ID 必须为数字（留空 = 0 / 顶级）；menuType 仅限 6 个枚举值。
-->
<template>
  <aside class="cc-drawer" :class="{ 'is-open': open }" style="--drawer-w: 520px">
    <DrawerResizer storage-key="menu-edit" />
    <div class="cc-drawer__head">
      <div style="font-size: var(--fs-md); font-weight: 700">{{ isNew ? '新增菜单' : '编辑菜单' }}</div>
      <span class="cc-tag">{{ isNew ? 'new' : '#' + form.id }}</span>
      <button class="cc-icon-btn" style="margin-left: auto" title="关闭" @click="close">
        <svg class="cc-i cc-i--sm" style="transform: rotate(45deg)"><use href="#i-plus" /></svg>
      </button>
    </div>

    <div class="cc-drawer__body">
      <div class="cc-form">
        <div class="cc-field-row">
          <div class="cc-field">
            <label class="cc-field__label">菜单名称<em>*</em></label>
            <input ref="nameInput" v-model="form.name" class="cc-input" placeholder="如 用户管理" />
          </div>
          <div class="cc-field">
            <label class="cc-field__label">路由路径</label>
            <input v-model="form.path" class="cc-input" placeholder="/user-management" />
          </div>
        </div>

        <div class="cc-field-row">
          <div class="cc-field">
            <label class="cc-field__label">图标名称</label>
            <input v-model="form.icon" class="cc-input" placeholder="如 UserOutlined" />
            <div class="cc-field__hint">旧前端图标组件名；新前端可留空</div>
          </div>
          <div class="cc-field">
            <label class="cc-field__label">权限标识</label>
            <input v-model="form.permissionCode" class="cc-input" placeholder="如 user:manage" />
          </div>
        </div>

        <div class="cc-field">
          <label class="cc-field__label">菜单类型</label>
          <select v-model="form.menuType" class="cc-select">
            <option v-for="meta in MENU_TYPE_META" :key="meta.type" :value="meta.type">
              {{ meta.type }} · {{ meta.title }}
            </option>
          </select>
          <div class="cc-field__hint">决定左栏分组与侧栏归属；与后端 menu_type 枚举一致</div>
        </div>

        <div class="cc-field-row">
          <div class="cc-field">
            <label class="cc-field__label">父级菜单 ID</label>
            <input v-model="form.parentId" class="cc-input" placeholder="留空表示顶级菜单" />
          </div>
          <div class="cc-field">
            <label class="cc-field__label">排序号</label>
            <input v-model="form.sortOrder" class="cc-input" placeholder="0" />
          </div>
        </div>

        <div class="cc-switch-row">
          <div class="cc-switch-row__text">
            <div class="cc-switch-row__title">在侧栏可见</div>
            <div class="cc-switch-row__desc">关闭后菜单仍存在，但不渲染到导航</div>
          </div>
          <span class="cc-switch" :class="{ 'is-on': form.visible === 1 }" @click="form.visible = form.visible === 1 ? 0 : 1" />
        </div>

        <div class="cc-switch-row">
          <div class="cc-switch-row__text">
            <div class="cc-switch-row__title">启用</div>
            <div class="cc-switch-row__desc">停用后角色无法获得该菜单权限</div>
          </div>
          <span class="cc-switch" :class="{ 'is-on': form.status === 1 }" @click="form.status = form.status === 1 ? 0 : 1" />
        </div>
      </div>
    </div>

    <div class="cc-drawer__foot">
      <button class="cc-btn" @click="close">取消</button>
      <button class="cc-btn cc-btn--primary" :disabled="!canSave" @click="submit">保存</button>
    </div>
  </aside>
</template>

<script setup lang="ts">
import DrawerResizer from './DrawerResizer.vue'
import { computed, reactive, ref, watch } from 'vue'
import type { MenuItem } from './MenuRow.vue'
import { MENU_TYPE_META } from '../utils/menu-permission'

/** 保存载荷（parentId / sortOrder 为字符串输入，由父级或后端转数字） */
export interface MenuSavePayload {
  id?: number
  name: string
  path: string
  icon: string
  permissionCode: string
  menuType: string
  parentId: string
  sortOrder: string
  visible: number
  status: number
}

const props = withDefaults(
  defineProps<{
    open: boolean
    menu?: MenuItem | null
  }>(),
  { menu: null }
)

const emit = defineEmits<{
  (e: 'update:open', value: boolean): void
  (e: 'save', payload: MenuSavePayload): void
}>()

const nameInput = ref<HTMLInputElement | null>(null)

const form = reactive<MenuSavePayload>({
  name: '',
  path: '',
  icon: '',
  permissionCode: '',
  menuType: 'MANAGE',
  parentId: '',
  sortOrder: '0',
  visible: 1,
  status: 1
})

const isNew = computed(() => !props.menu || !props.menu.id)

/**
 * 保存按钮可用性
 * ----------------------------------------------------------------------------
 * 菜单名称是唯一必填项（path / icon / permissionCode 后端允许为空），
 * 与 `submit()` 的静默校验同源；前移到 disabled 后用户能直接看出还差必填项。
 */
const canSave = computed(() => form.name.trim().length > 0)

watch(
  () => [props.open, props.menu] as const,
  () => {
    if (!props.open) return
    const menu = props.menu
    form.id = menu ? menu.id : undefined
    form.name = menu ? menu.name : ''
    form.path = menu ? menu.path || '' : ''
    form.icon = menu ? menu.icon || '' : ''
    form.permissionCode = menu ? menu.permissionCode || '' : ''
    form.menuType = menu ? menu.menuType : 'MANAGE'
    form.parentId = menu && menu.parentId ? String(menu.parentId) : ''
    form.sortOrder = menu ? String(menu.sortOrder ?? 0) : '0'
    form.visible = menu ? (menu.visible ?? 1) : 1
    form.status = menu ? (menu.status ?? 1) : 1
    if (isNew.value) window.setTimeout(() => nameInput.value?.focus(), 60)
  },
  { immediate: true }
)

function close() {
  emit('update:open', false)
}

function submit() {
  if (!form.name.trim()) return
  emit('save', {
    id: isNew.value ? undefined : form.id,
    name: form.name.trim(),
    path: form.path.trim(),
    icon: form.icon.trim(),
    permissionCode: form.permissionCode.trim(),
    menuType: form.menuType,
    parentId: form.parentId.trim(),
    sortOrder: form.sortOrder.trim(),
    visible: form.visible,
    status: form.status
  })
}
</script>
