/**
 * 菜单与导航配置（v2 前端重写）
 * ----------------------------------------------------------------------------
 * 设计原则：
 *  1) 一级导航常驻 ≤ 5 项（对话 / 智能体 / 技能 / 任务 / 设置）；
 *  2) 其余管理页面全部收进侧栏底部「更多」弹出面板，按业务域分组；
 *  3) 新增页面时只改本文件 + router/index.ts，无需改动侧栏组件。
 *
 * 图标：使用 assets 中的内联 symbol id（如 "i-chat"），
 *       由 AppIcons.vue 统一渲染 <symbol> 定义。
 */

export interface MenuItem {
  /** 路由 path（同时作为选中态 key） */
  key: string
  label: string
  /** 图标 symbol id，如 "i-chat" */
  icon: string
  /** 右侧计数（可选，如待办任务数） */
  count?: number
  /**
   * 是否仅管理员可见 / 可访问。
   *
   * <p>依据：后端真正校验 {@code userRole === 'admin'} 的 Controller 有 6 个 ——
   * LLMProvider / Menu / Role / ScheduleTask / System / User。前端允许<b>更严格</b>
   * （把 MCP、数据库连接这类含敏感凭证的页面一并收口），但不允许更宽松。</p>
   *
   * <p>消费方：侧栏菜单过滤（AppSidebar）、设置聚合页（SettingsView）、
   * 路由守卫（router/index.ts 的 meta.adminOnly）。</p>
   */
  adminOnly?: boolean
}

export interface MenuGroup {
  title: string
  items: MenuItem[]
}

/** 一级导航（常驻侧栏） */
export const PRIMARY_NAV: MenuItem[] = [
  { key: '/chat', label: '对话', icon: 'i-chat' },
  { key: '/agents', label: '智能体', icon: 'i-agent' },
  { key: '/skills', label: '技能', icon: 'i-skill' },
  // 「任务」直接落到定时任务页（后端无独立 /api/tasks 接口，任务面板在聊天页内）
  // 定时任务的后端接口全部 checkAdmin，故整项仅管理员可见
  { key: '/tasks/schedule', label: '任务', icon: 'i-task', adminOnly: true },
  { key: '/settings', label: '设置', icon: 'i-cog' }
]

/** 「更多」面板：二级页面按业务域分组 */
export const MORE_GROUPS: MenuGroup[] = [
  {
    title: '能力',
    items: [
      { key: '/lessons', label: '踩坑经验', icon: 'i-book' },
      // 后端 MCP 接口未做角色校验；这里是前端收口（工具注册含凭证，普通用户不应自行改）
      { key: '/mcp', label: 'MCP 配置', icon: 'i-link', adminOnly: true },
      { key: '/tasks/schedule', label: '定时任务', icon: 'i-clock', adminOnly: true }
    ]
  },
  {
    title: '数据与运行',
    items: [
      { key: '/files', label: '文件管理', icon: 'i-file' },
      // 同上：数据库连接存有明密文凭证，前端收口为管理员页
      { key: '/databases', label: '数据库连接', icon: 'i-db', adminOnly: true },
      { key: '/logs', label: '运行日志', icon: 'i-log' },
      // SystemController 的 data-stats / export / import / reset 均 checkAdmin
      { key: '/data', label: '数据管理', icon: 'i-disk', adminOnly: true }
    ]
  },
  {
    title: '协作与安全',
    items: [
      { key: '/p2p', label: '设备互联', icon: 'i-link' },
      { key: '/users', label: '用户管理', icon: 'i-users', adminOnly: true },
      { key: '/menu-permission', label: '菜单权限', icon: 'i-lock', adminOnly: true }
    ]
  },
  {
    title: '系统配置',
    items: [
      // 个人信息：侧栏用户菜单与「设置」页双入口（后端 /user/profile 只操作当前登录用户，无角色校验）
      { key: '/profile', label: '个人信息', icon: 'i-users' },
      { key: '/providers', label: '模型配置（Provider）', icon: 'i-cog', adminOnly: true },
      // 外观只影响本机观感（主题 / 背景 / 透明度），后端 AppearanceController 无角色校验
      { key: '/appearance', label: '外观设置', icon: 'i-brush' },
      // 版本与更新（F06）：版本号任何登录用户都可看；更新设置仅桌面端可改（浏览器环境自动降级为只读）
      { key: '/about', label: '版本与更新', icon: 'i-refresh' }
    ]
  }
]

/** 会话列表分组（由接口按时间归类后传入组件） */
export interface ConversationGroup {
  title: string
  items: ConversationItem[]
}

export interface ConversationItem {
  id: string
  title: string
  /** 有后台任务运行时展示小圆点（本页正在消费该会话的 SSE 流） */
  running?: boolean
  /** 该会话在等待 ask_user 回执（展示黄色感叹号，点进去才看得到审批卡片） */
  pending?: boolean
  /** 该会话的 task_manager 清单进度：已完成数（没有清单时不传） */
  taskDone?: number
  /** 该会话的 task_manager 清单进度：总数（0 / 不传表示没有清单） */
  taskTotal?: number
}

/**
 * 按时间把会话列表归类为「今天 / 昨天 / 更早」。
 * 完整实现可直接用接口返回的 updatedAt 字段；此处给前端兜底逻辑。
 */
export function groupConversationsByTime(
  list: Array<{
    id: string
    title: string
    updatedAt?: number | string
    running?: boolean
    pending?: boolean
    taskDone?: number
    taskTotal?: number
  }>
): ConversationGroup[] {
  const now = new Date()
  const startOfToday = new Date(now.getFullYear(), now.getMonth(), now.getDate()).getTime()
  const startOfYesterday = startOfToday - 24 * 3600 * 1000

  const today: ConversationItem[] = []
  const yesterday: ConversationItem[] = []
  const earlier: ConversationItem[] = []

  for (const item of list) {
    const ts = item.updatedAt ? new Date(item.updatedAt).getTime() : Date.now()
    const target = ts >= startOfToday ? today : ts >= startOfYesterday ? yesterday : earlier
    target.push({
      id: item.id,
      title: item.title,
      running: item.running,
      pending: item.pending,
      taskDone: item.taskDone,
      taskTotal: item.taskTotal
    })
  }

  return [
    { title: '今天', items: today },
    { title: '昨天', items: yesterday },
    { title: '更早', items: earlier }
  ].filter(g => g.items.length > 0)
}
