/**
 * 工具目录（v3 / F18）
 * ----------------------------------------------------------------------------
 * 智能体「工具集」选择的数据源：后端 `GET /api/tools/registry` 返回按
 * `OperationCategory` 分组、带中文描述与风险等级的工具清单。
 *
 * 为什么不能在组件里写死选项：
 *   后端 `AgentConfig.tool_names` 存的是 **工具名 JSON 数组**（如
 *   `["file_writer","command"]`），`DeepSeekServiceImpl` 用 `objectMapper.readValue`
 *   解析后逐项与 `ToolRegistry` 比对。写死的「文件读写 / Git / 网络搜索」这类假
 *   key 存进去一个都匹配不上，等于把该 Agent 的工具全删了 —— 只能从注册表取真名。
 *
 * 风险等级用于 UI 标记（high 用警示色），直接透传后端 `ToolPermission` 判定结果。
 */

import type { ToolRegistryData } from '../api/tools'

export type ToolRisk = 'low' | 'medium' | 'high'

/** 单个工具选项 */
export interface ToolOption {
  /** 工具名（后端 `Tool.getName()`，即 tool_names 数组元素） */
  key: string
  /** chip 上的短文案（由 description 精简） */
  label: string
  /** 完整描述，用作 hover 提示 */
  description: string
  /** 所属分组 code（OperationCategory） */
  group: string
  /** 风险等级 */
  risk: ToolRisk
}

/** 一个分组（对应一个 OperationCategory） */
export interface ToolGroup {
  code: string
  label: string
  options: ToolOption[]
}

/** 后端返回的分类标签形如「📖 文件读取」，chip 分组头不需要 emoji 前缀 */
function cleanGroupLabel(label: string, fallback: string): string {
  const text = (label || '').replace(/^[\p{Extended_Pictographic}\uFE0F\s]+/u, '').trim()
  return text || fallback || '其他'
}

/**
 * 从完整描述里截出适合 chip 的短名
 *
 * <p>后端描述常带补充说明，例如
 * `聊天附件读取 — 支持文本/PDF/Word/Excel，…` 或 `文件探索（读/搜/树）`。
 * chip 只保留主名称：去掉破折号后的补充段与括注；仍过长则截断加省略号。
 * 完整描述留在 `description` 里做 hover 提示。</p>
 */
export function shortToolLabel(description: string, name: string): string {
  const raw = (description || '').trim()
  if (!raw) return name
  const main = raw.split(/\s*[—–]\s*|\s*[（(]/)[0]?.trim() || raw
  return main.length > 14 ? `${main.slice(0, 14)}…` : main
}

/** 注册表响应 → 分组选项（空/异常输入返回空数组，由调用方决定是否回退） */
export function toToolGroups(data?: ToolRegistryData | null): ToolGroup[] {
  const categories = data?.categories
  if (!Array.isArray(categories)) return []
  const groups: ToolGroup[] = []
  for (const category of categories) {
    const options = (category.tools || [])
      .filter((tool) => !!tool?.name)
      .map<ToolOption>((tool) => ({
        key: tool.name,
        label: shortToolLabel(tool.description, tool.name),
        description: tool.description || tool.name,
        group: category.code,
        risk: (tool.risk as ToolRisk) || 'low'
      }))
    if (!options.length) continue
    groups.push({
      code: category.code,
      label: cleanGroupLabel(category.label, category.code),
      options
    })
  }
  return groups
}

/** 拍平成一层（搜索、全选、已选统计都用它） */
export function flattenToolOptions(groups: ToolGroup[]): ToolOption[] {
  return groups.flatMap((group) => group.options)
}

/**
 * 解析后端 `AgentConfig.tool_names`
 *
 * <p>标准形态是 JSON 数组字符串（`["file_writer","command"]`，`DeepSeekServiceImpl`
 * 用 `objectMapper.readValue` 解析），但历史数据里也存在逗号串。空串 / `[]` / null
 * 都表示「全部工具」—— 后端解析到空值时会回退 `deepSeekConfig` 的默认工具集。</p>
 *
 * <p>之前这里直接按逗号切分 JSON，导致 `["file_writer"` 这类碎片被当成工具名，
 * 卡片上的「N 个工具」永远显示错数。</p>
 */
export function parseToolNames(raw?: string | null): string[] {
  const text = (raw || '').trim()
  if (!text) return []
  if (text.startsWith('[')) {
    try {
      const parsed: unknown = JSON.parse(text)
      if (Array.isArray(parsed)) {
        return parsed.map((item) => String(item).trim()).filter(Boolean)
      }
    } catch {
      // 不是合法 JSON → 落到逗号串兜底
    }
  }
  return text
    .split(',')
    .map((item) => item.trim().replace(/^["']|["']$/g, ''))
    .filter(Boolean)
}

/**
 * 兜底分组：注册表拉取失败时使用
 *
 * <p>**必须是真实工具名**（本表是 2026-10 注册表快照）—— 假 key 存进 tool_names
 * 会让 Agent 一个工具都调不出来，比「看不到选项」严重得多。</p>
 */
export const FALLBACK_TOOL_GROUPS: ToolGroup[] = [
  {
    code: 'READ',
    label: '文件读取',
    options: [
      { key: 'chat_attachment', label: '聊天附件读取', description: '聊天附件读取', group: 'READ', risk: 'low' },
      { key: 'file_explorer', label: '文件探索', description: '文件探索（读/搜/树）', group: 'READ', risk: 'low' },
      { key: 'project_info', label: '获取项目信息', description: '获取项目信息', group: 'READ', risk: 'low' },
      { key: 'query_tool_history', label: '查询历史工具调用', description: '查询历史工具调用的详细结果', group: 'READ', risk: 'low' }
    ]
  },
  {
    code: 'WRITE',
    label: '文件写入',
    options: [
      { key: 'file_writer', label: '文件写入', description: '文件写入/编辑/删除', group: 'WRITE', risk: 'medium' }
    ]
  },
  {
    code: 'EXECUTE',
    label: '命令执行',
    options: [
      { key: 'command', label: '命令执行', description: '命令执行与服务管理', group: 'EXECUTE', risk: 'high' }
    ]
  },
  {
    code: 'NETWORK',
    label: '网络请求',
    options: [
      { key: 'web_search', label: '网络搜索', description: '网络搜索', group: 'NETWORK', risk: 'low' },
      { key: 'check_network', label: '检查网络连通性', description: '检查网络连通性', group: 'NETWORK', risk: 'low' },
      { key: 'http_request', label: '发送HTTP请求', description: '发送HTTP请求', group: 'NETWORK', risk: 'low' },
      { key: 'web_fetch', label: '获取网页内容', description: '获取网页内容', group: 'NETWORK', risk: 'low' }
    ]
  },
  {
    code: 'DATABASE',
    label: '数据库',
    options: [
      { key: 'execute_sql', label: '执行SQL语句', description: '执行SQL语句', group: 'DATABASE', risk: 'high' }
    ]
  },
  {
    code: 'GIT',
    label: 'Git 操作',
    options: [
      { key: 'git_query', label: '查询Git状态与历史', description: '查询Git仓库状态与历史', group: 'GIT', risk: 'low' },
      { key: 'git_submit', label: 'Git提交与同步', description: 'Git提交、远程同步与撤销操作', group: 'GIT', risk: 'medium' },
      { key: 'git_stash', label: '管理Git储藏', description: '管理Git储藏(stash)', group: 'GIT', risk: 'medium' },
      { key: 'git_branch', label: '管理Git分支', description: '管理Git分支', group: 'GIT', risk: 'medium' },
      { key: 'git_remote', label: '管理Git远程', description: '管理Git远程仓库配置', group: 'GIT', risk: 'medium' },
      { key: 'git_tag', label: '管理Git标签', description: '管理Git标签(tag)', group: 'GIT', risk: 'medium' }
    ]
  },
  {
    code: 'SKILL',
    label: '技能管理',
    options: [
      { key: 'skill', label: '技能管理', description: '技能管理（创建/编辑/删除/查看/反馈）', group: 'SKILL', risk: 'medium' },
      { key: 'lesson', label: '踩坑经验管理', description: '踩坑经验管理（记录/检索/反馈）', group: 'SKILL', risk: 'medium' }
    ]
  },
  {
    code: 'COMMUNICATION',
    label: '用户交互',
    options: [
      { key: 'ask_clarification', label: '向用户提问', description: '向用户提问', group: 'COMMUNICATION', risk: 'low' }
    ]
  },
  {
    code: 'ADMIN',
    label: '管理操作',
    options: [
      { key: 'agent', label: '多Agent协作', description: '多Agent协作（创建/收集/查看）', group: 'ADMIN', risk: 'low' },
      { key: 'agent_invoke', label: '智能体互相调用', description: '智能体互相调用', group: 'ADMIN', risk: 'medium' },
      { key: 'mcp_server_manager', label: 'MCP服务器管理', description: '创建和管理MCP服务器配置', group: 'ADMIN', risk: 'medium' },
      { key: 'task_manager', label: '管理后台任务', description: '管理后台任务', group: 'ADMIN', risk: 'medium' },
      { key: 'schedule_task', label: '定时任务', description: '创建和管理定时任务', group: 'ADMIN', risk: 'medium' }
    ]
  },
  {
    code: 'DESKTOP',
    label: '桌面自动化',
    options: [
      { key: 'screen_capture', label: '桌面截图', description: '桌面截图', group: 'DESKTOP', risk: 'high' },
      { key: 'desktop_control', label: '桌面键鼠模拟', description: '桌面键鼠模拟', group: 'DESKTOP', risk: 'high' }
    ]
  }
]
