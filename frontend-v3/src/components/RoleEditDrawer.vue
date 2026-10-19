<!--
  角色编辑抽屉（v2）
  ----------------------------------------------------------------------------
  与后端对应：
    POST   /api/role/create      新增角色
    PUT    /api/role/update      更新角色
    DELETE /api/role/delete/{id} 删除（由列表页触发，本组件不处理）
  数据接缝：
    v-model:open  -> 抽屉开关
    role          -> 编辑对象（null 表示新增）
    emit('save')  -> payload 交给父组件发请求
  约束：code 唯一且创建后不可修改（用户表 role 字段存的就是 code），因此编辑时禁用它。
-->
<template>
  <aside class="cc-drawer" :class="{ 'is-open': open }" style="--drawer-w: 480px">
    <DrawerResizer storage-key="role-edit" />
    <div class="cc-drawer__head">
      <div style="font-size: var(--fs-md); font-weight: 700">{{ isNew ? '新增角色' : '编辑角色' }}</div>
      <span class="cc-tag">{{ isNew ? 'new' : form.code }}</span>
      <button class="cc-icon-btn" style="margin-left: auto" title="关闭" @click="close">
        <svg class="cc-i cc-i--sm" style="transform: rotate(45deg)"><use href="#i-plus" /></svg>
      </button>
    </div>

    <div class="cc-drawer__body">
      <div class="cc-form">
        <div class="cc-field">
          <label class="cc-field__label">角色名称<em>*</em></label>
          <input ref="nameInput" v-model="form.name" class="cc-input" placeholder="如 只读成员" />
        </div>

        <div class="cc-field">
          <label class="cc-field__label">角色编码<em>*</em></label>
          <input v-model="form.code" class="cc-input" :disabled="!isNew" placeholder="如 viewer" />
          <div class="cc-field__hint">唯一标识，创建后不可修改；用户表的 role 字段存的就是它</div>
        </div>

        <div class="cc-field">
          <label class="cc-field__label">描述</label>
          <textarea v-model="form.description" class="cc-textarea" placeholder="一句话说明这个角色的权限边界" />
        </div>

        <div class="cc-switch-row">
          <div class="cc-switch-row__text">
            <div class="cc-switch-row__title">启用角色</div>
            <div class="cc-switch-row__desc">停用后不再参与菜单权限分配</div>
          </div>
          <span class="cc-switch" :class="{ 'is-on': form.status === 1 }" @click="form.status = form.status === 1 ? 0 : 1" />
        </div>
      </div>
    </div>

    <div class="cc-drawer__foot">
      <button class="cc-btn" @click="close">取消</button>
      <button class="cc-btn cc-btn--primary" @click="submit">保存</button>
    </div>
  </aside>
</template>

<script setup lang="ts">
import DrawerResizer from './DrawerResizer.vue'
import { computed, reactive, ref, watch } from 'vue'

/** 角色（对应后端 Role 实体 / sys_role 表） */
export interface RoleItem {
  id: number
  name: string
  code: string
  description?: string
  /** 1=启用，0=停用 */
  status: number
}

/** 保存载荷 */
export interface RoleSavePayload {
  id?: number
  name: string
  code: string
  description: string
  status: number
}

const props = withDefaults(
  defineProps<{
    open: boolean
    role?: RoleItem | null
  }>(),
  { role: null }
)

const emit = defineEmits<{
  (e: 'update:open', value: boolean): void
  (e: 'save', payload: RoleSavePayload): void
}>()

const nameInput = ref<HTMLInputElement | null>(null)

const form = reactive<RoleSavePayload>({
  name: '',
  code: '',
  description: '',
  status: 1
})

const isNew = computed(() => !props.role || !props.role.id)

watch(
  () => [props.open, props.role] as const,
  () => {
    if (!props.open) return
    const role = props.role
    form.id = role ? role.id : undefined
    form.name = role ? role.name : ''
    form.code = role ? role.code : ''
    form.description = role ? role.description || '' : ''
    form.status = role ? role.status : 1
    if (isNew.value) window.setTimeout(() => nameInput.value?.focus(), 60)
  },
  { immediate: true }
)

function close() {
  emit('update:open', false)
}

function submit() {
  if (!form.name.trim() || !form.code.trim()) return
  emit('save', {
    id: isNew.value ? undefined : form.id,
    name: form.name.trim(),
    code: form.code.trim(),
    description: form.description.trim(),
    status: form.status
  })
}
</script>
