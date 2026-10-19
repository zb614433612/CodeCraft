<!--
  菜单权限页（v2）
  ----------------------------------------------------------------------------
  布局：页面头（刷新）→ 内联统计 → 双栏
        左栏「菜单项」：按 menu_type 分组（LINK/AI/TOOL/SYSTEM/MANAGE/SETTING）+ 行内编辑 / 删除 + 新增
        右栏「角色权限」：角色 chips + 菜单勾选 + 保存分配 + 新增角色
  数据接缝：
    menus            -> GET  /api/menu/tree?type=（管理员）
    roles            -> GET  /api/role/list（管理员）
    roleMenus        -> GET  /api/role/menus/{roleId}（按角色惰性拉取，父级缓存为 map）
    emit('save-menu')-> POST /api/menu/create 或 PUT /api/menu/update
    emit('delete-menu')-> DELETE /api/menu/delete/{id}（sys_role_menu 级联删除）
    emit('save-role')-> POST /api/role/create 或 PUT /api/role/update
    emit('save-assign')-> POST /api/role/assign-menus { roleId, menuIds }（全量覆盖）
    emit('refresh')  -> 重新拉取菜单与角色
  约束：
    1) assign-menus 是全量覆盖语义，因此保存时提交当前勾选集合（不是增量）；
    2) 后端无「删除角色」以外的批量接口，角色删除由父级在 chips 的长按 / 菜单处理，本页只做分配；
    3) 勾选状态按「当前角色」独立维护，切换角色时重新同步。
-->
<template>
  <div class="cc-page-scroll cc-mp">
    <div class="cc-page-inner">
      <!-- 页面头 -->
      <div class="cc-page-head">
        <div class="cc-page-head__icon">
          <svg class="cc-i"><use href="#i-lock" /></svg>
        </div>
        <div>
          <h1 class="cc-page-head__title">菜单权限</h1>
          <p class="cc-page-head__sub">维护侧栏菜单项，并按角色分配可见菜单（接口：/api/menu、/api/role，需管理员）</p>
        </div>
        <div class="cc-page-head__actions">
          <button class="cc-btn" @click="$emit('refresh')">
            <svg class="cc-i cc-i--sm"><use href="#i-refresh" /></svg>刷新
          </button>
        </div>
      </div>

      <!-- 统计 -->
      <div class="cc-stats-inline" style="margin-bottom: var(--sp-4)">
        <span>菜单项 <b>{{ menus.length }}</b></span><span class="cc-dot" />
        <span>可见 <b>{{ visibleCount }}</b></span><span class="cc-dot" />
        <span>角色 <b>{{ roles.length }}</b></span><span class="cc-dot" />
        <span>当前角色已分配 <b>{{ assignedCount }}</b></span>
      </div>

      <!-- 加载中 / 接口失败提示（内联样式，不新增 CSS 类） -->
      <div
        v-if="loading || error"
        :style="{
          marginBottom: 'var(--sp-3)',
          fontSize: 'var(--fs-sm)',
          color: error ? 'var(--danger)' : 'var(--text-3)'
        }"
      >
        {{ error || '正在加载菜单与角色…' }}
      </div>

      <div class="cc-mp-grid">
        <!-- ========== 左栏：菜单项 ========== -->
        <section class="cc-mp-panel">
          <div class="cc-mp-panel__head">
            <span class="cc-mp-panel__title">菜单项</span>
            <span class="cc-mp-panel__count">{{ menus.length }}</span>
            <div class="cc-mp-panel__head-actions">
              <button class="cc-btn cc-btn--primary" @click="openCreateMenu">
                <svg class="cc-i cc-i--sm"><use href="#i-plus" /></svg>新增菜单
              </button>
            </div>
          </div>
          <div class="cc-mp-panel__body">
            <div v-for="group in menuGroups" :key="group.type" class="cc-mp-group">
              <div class="cc-mp-group__head">
                <span class="cc-mp-type" :class="'is-' + menuTypeTone(group.type)">{{ group.type }}</span>
                <span>{{ group.title }}</span>
                <span class="cc-mp-group__count">{{ group.items.length }} 项</span>
              </div>
              <MenuRow
                v-for="menu in group.items"
                :key="menu.id"
                :menu="menu"
                @edit="openEditMenu"
                @delete="$emit('delete-menu', $event)"
              />
            </div>
          </div>
        </section>

        <!-- ========== 右栏：角色权限 ========== -->
        <section class="cc-mp-panel">
          <div class="cc-mp-panel__head">
            <span class="cc-mp-panel__title">角色权限</span>
            <div class="cc-mp-panel__head-actions">
              <button class="cc-btn" @click="openCreateRole">
                <svg class="cc-i cc-i--sm"><use href="#i-shield" /></svg>新增角色
              </button>
            </div>
          </div>
          <div class="cc-mp-panel__body">
            <div class="cc-mp-roles">
              <button
                v-for="role in roles"
                :key="role.id"
                class="cc-mp-role"
                :class="{ 'is-on': role.id === activeRoleId }"
                @click="selectRole(role.id)"
              >
                {{ role.name }}
                <span class="cc-mp-role__count">{{ (roleMenus[role.id] || []).length }}</span>
              </button>
            </div>

            <div class="cc-mp-checks">
              <div v-for="group in menuGroups" :key="'check-' + group.type" class="cc-mp-group">
                <div class="cc-mp-group__head">
                  <span class="cc-mp-type" :class="'is-' + menuTypeTone(group.type)">{{ group.type }}</span>
                  <span>{{ group.title }}</span>
                  <span class="cc-mp-group__count">{{ groupHitCount(group) }}/{{ group.items.length }}</span>
                </div>
                <label
                  v-for="menu in group.items"
                  :key="'c-' + menu.id"
                  class="cc-mp-check"
                  :class="{ 'is-on': checkedIds.includes(menu.id) }"
                >
                  <input
                    type="checkbox"
                    :checked="checkedIds.includes(menu.id)"
                    @change="toggleMenu(menu.id, ($event.target as HTMLInputElement).checked)"
                  />
                  <span class="cc-mp-check__main">
                    <span class="cc-mp-check__name">{{ menu.name }}</span>
                    <span class="cc-mp-check__path">{{ menu.path || '—' }}</span>
                  </span>
                </label>
              </div>
              <div v-if="!menus.length" class="cc-mp-empty">暂无菜单项，请先在左栏新增</div>
            </div>
          </div>
          <div class="cc-mp-panel__foot">
            <span>已选 {{ checkedIds.length }} / {{ menus.length }}{{ activeRole ? '（' + activeRole.name + '）' : '' }}</span>
            <button class="cc-btn cc-btn--primary" :disabled="!activeRoleId" @click="saveAssign">
              <svg class="cc-i cc-i--sm"><use href="#i-check" /></svg>保存分配
            </button>
          </div>
        </section>
      </div>
    </div>

    <!-- 遮罩 + 两个抽屉 -->
    <div v-if="menuDrawerOpen || roleDrawerOpen" class="cc-mask is-open" @click="closeAll" />
    <MenuEditDrawer v-model:open="menuDrawerOpen" :menu="editingMenu" @save="onSaveMenu" />
    <RoleEditDrawer v-model:open="roleDrawerOpen" :role="editingRole" @save="onSaveRole" />
  </div>
</template>

<script setup lang="ts">
import { computed, ref, watch, onMounted } from 'vue'
import MenuRow, { type MenuItem } from '../components/MenuRow.vue'
import MenuEditDrawer, { type MenuSavePayload } from '../components/MenuEditDrawer.vue'
import RoleEditDrawer, { type RoleItem, type RoleSavePayload } from '../components/RoleEditDrawer.vue'
import { groupMenusByType, menuTypeTone } from '../utils/menu-permission'

const props = withDefaults(
  defineProps<{
    menus?: MenuItem[]
    roles?: RoleItem[]
    /** 角色 id → 已分配 menuId 列表（父级缓存，GET /api/role/menus/{roleId}） */
    roleMenus?: Record<number, number[]>
    /** 首次加载中（菜单树 + 角色 + 各角色菜单） */
    loading?: boolean
    /** 接口失败原因（非空时展示提示条） */
    error?: string
    /**
     * 进入页面时的数据加载（由 App.vue 的 PAGE_BINDINGS 注入）
     * 页面随路由真实挂载，所以在这里调一次即可；重复进页由父级的「已加载 / 加载中 / 已失败」跳过
     */
    load?: () => void
  }>(),
  {
    menus: () => [],
    roles: () => [],
    roleMenus: () => ({}),
    loading: false,
    error: ''
  }
)

const emit = defineEmits<{
  (e: 'save-menu', payload: MenuSavePayload): void
  (e: 'delete-menu', menu: MenuItem): void
  (e: 'save-role', payload: RoleSavePayload): void
  (e: 'save-assign', payload: { roleId: number; menuIds: number[] }): void
  (e: 'refresh'): void
}>()

const menuGroups = computed(() => groupMenusByType(props.menus))
const visibleCount = computed(() => props.menus.filter(m => m.visible !== 0 && m.status !== 0).length)

const activeRoleId = ref<number>(props.roles[0]?.id ?? 0)
const checkedIds = ref<number[]>([])

const activeRole = computed(() => props.roles.find(r => r.id === activeRoleId.value) || null)
const assignedCount = computed(() => (props.roleMenus[activeRoleId.value] || []).length)

/** 角色列表变化时保证选中项有效 */
watch(
  () => props.roles,
  list => {
    if (!list.length) {
      activeRoleId.value = 0
      return
    }
    if (!list.some(r => r.id === activeRoleId.value)) activeRoleId.value = list[0].id
  },
  { immediate: true }
)

/** 当前角色的勾选集合（后端 roleMenus 变化或切角色时同步） */
watch(
  [() => props.roleMenus, activeRoleId],
  () => {
    checkedIds.value = [...(props.roleMenus[activeRoleId.value] || [])]
  },
  { immediate: true, deep: true }
)

function selectRole(roleId: number) {
  activeRoleId.value = roleId
}

function toggleMenu(menuId: number, checked: boolean) {
  if (checked) {
    if (!checkedIds.value.includes(menuId)) checkedIds.value = [...checkedIds.value, menuId]
  } else {
    checkedIds.value = checkedIds.value.filter(id => id !== menuId)
  }
}

function groupHitCount(group: { items: MenuItem[] }) {
  return group.items.filter(menu => checkedIds.value.includes(menu.id)).length
}

function saveAssign() {
  if (!activeRoleId.value) return
  emit('save-assign', { roleId: activeRoleId.value, menuIds: [...checkedIds.value] })
}

/* ---------- 菜单抽屉 ---------- */
const menuDrawerOpen = ref(false)
const editingMenu = ref<MenuItem | null>(null)

function openCreateMenu() {
  editingMenu.value = null
  menuDrawerOpen.value = true
}

function openEditMenu(menu: MenuItem) {
  editingMenu.value = menu
  menuDrawerOpen.value = true
}

function onSaveMenu(payload: MenuSavePayload) {
  emit('save-menu', payload)
  menuDrawerOpen.value = false
}

/* ---------- 角色抽屉 ---------- */
const roleDrawerOpen = ref(false)
const editingRole = ref<RoleItem | null>(null)

function openCreateRole() {
  editingRole.value = null
  roleDrawerOpen.value = true
}

function onSaveRole(payload: RoleSavePayload) {
  emit('save-role', payload)
  roleDrawerOpen.value = false
}

function closeAll() {
  menuDrawerOpen.value = false
  roleDrawerOpen.value = false
}

/** 抽屉关闭后清空编辑对象 */
watch([menuDrawerOpen, roleDrawerOpen], ([menuOpen, roleOpen]) => {
  if (!menuOpen) editingMenu.value = null
  if (!roleOpen) editingRole.value = null
})

/**
 * 「进入页面才拉数据」的触发点（原 App.vue 的 PAGE_LOADERS 已下沉至此）
 * 父级通过 props.load 注入具体加载函数，组件只负责在最合适的时机调用它。
 */
onMounted(() => props.load?.())
</script>
