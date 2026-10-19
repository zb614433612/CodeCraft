/**
 * 菜单权限工具（v2）
 * ----------------------------------------------------------------------------
 * 与后端对齐：
 *   Menu.menuType 枚举 —— LINK=对话 / AI=AI能力 / TOOL=数据与工具 / SYSTEM=系统 /
 *                        MANAGE=管理页面 / SETTING=设置（见 schema.sql 注释）
 *   visible=1 可见、status=1 启用；Role.code 唯一且与 user.role 对应
 * 说明：分组顺序固定为「对话 → AI → 工具 → 系统 → 管理 → 设置」，与旧前端菜单权限页一致。
 */

export type MenuType = 'LINK' | 'AI' | 'TOOL' | 'SYSTEM' | 'MANAGE' | 'SETTING'

export interface MenuTypeMeta {
  type: MenuType
  title: string
}

/** 分组顺序（左栏与右栏勾选共用，保证两处排序一致） */
export const MENU_TYPE_META: MenuTypeMeta[] = [
  { type: 'LINK', title: '对话' },
  { type: 'AI', title: 'AI 能力' },
  { type: 'TOOL', title: '数据与工具' },
  { type: 'SYSTEM', title: '系统' },
  { type: 'MANAGE', title: '管理页面' },
  { type: 'SETTING', title: '设置' }
]

/** 菜单类型 → 样式后缀（.cc-mp-type.is-xxx） */
export function menuTypeTone(type: string): string {
  return String(type || 'setting').toLowerCase()
}

/** 菜单类型 → 中文标题 */
export function menuTypeTitle(type: string): string {
  const hit = MENU_TYPE_META.find(meta => meta.type === type)
  return hit ? hit.title : type
}

/** 按类型分组（组内按 sortOrder 升序，组间按 MENU_TYPE_META 顺序） */
export function groupMenusByType<T extends { menuType: string; sortOrder?: number }>(
  menus: T[]
): Array<{ type: MenuType; title: string; items: T[] }> {
  return MENU_TYPE_META.map(meta => ({
    type: meta.type,
    title: meta.title,
    items: menus
      .filter(menu => menu.menuType === meta.type)
      .slice()
      .sort((a, b) => (a.sortOrder || 0) - (b.sortOrder || 0))
  }))
}
