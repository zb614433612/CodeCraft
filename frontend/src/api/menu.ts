import { request } from '@/utils/http-client'

export interface MenuItem {
  id: number
  name: string
  path?: string
  icon?: string
  parentId?: number
  sortOrder: number
  permissionCode?: string
  menuType: string  // LINK 或 MANAGE
  visible: number
  status: number
}

export interface RoleItem {
  id: number
  name: string
  code: string
  description?: string
  status: number
}

// 获取菜单列表（管理员用）
export async function getMenuTree(type?: string) {
  let url = '/menu/tree'
  if (type) url += `?type=${type}`
  return request<MenuItem[]>(url)
}

// 创建菜单
export async function createMenu(data: Partial<MenuItem>) {
  return request<MenuItem>('/menu/create', {
    method: 'POST',
    body: JSON.stringify(data)
  })
}

// 更新菜单
export async function updateMenu(data: Partial<MenuItem>) {
  return request<void>('/menu/update', {
    method: 'PUT',
    body: JSON.stringify(data)
  })
}

// 删除菜单
export async function deleteMenu(id: number) {
  return request<void>(`/menu/delete/${id}`, { method: 'DELETE' })
}

// 获取当前用户可见菜单（Layout 用）
export async function getUserMenus(type: string = 'LINK') {
  return request<MenuItem[]>(`/menu/user-menus?type=${type}`)
}

// 获取角色列表
export async function getRoleList() {
  return request<RoleItem[]>('/role/list')
}

// 获取角色已分配的菜单ID列表
export async function getRoleMenuIds(roleId: number) {
  return request<number[]>(`/role/menus/${roleId}`)
}

// 为角色分配菜单
export async function assignMenusToRole(roleId: number, menuIds: number[]) {
  return request<void>('/role/assign-menus', {
    method: 'POST',
    body: JSON.stringify({ roleId, menuIds })
  })
}

// 创建角色
export async function createRole(data: Partial<RoleItem>) {
  return request<RoleItem>('/role/create', {
    method: 'POST',
    body: JSON.stringify(data)
  })
}

// 更新角色
export async function updateRole(data: Partial<RoleItem>) {
  return request<void>('/role/update', {
    method: 'PUT',
    body: JSON.stringify(data)
  })
}

// 删除角色
export async function deleteRole(id: number) {
  return request<void>(`/role/delete/${id}`, { method: 'DELETE' })
}
