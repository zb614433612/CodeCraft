/**
 * 数据管理（备份导出 / 导入）工具
 * ----------------------------------------------------------------------------
 * 与后端 DataTransferService 保持一致：
 *   1) 7 个数据类别，顺序 = 导出顺序 = 导入顺序（父表先于子表，满足外键依赖）；
 *   2) 备份结构 { app:'CodeCraft', formatVersion:1, exportedAt, categories:{ key:{ label, tables:{ 表名:[行...] } } } }；
 *   3) 类别行数 = 类别下各表行数之和（后端 stats() 口径）。
 * 说明：lesson_norm_cache 是纯缓存表，后端不导出；敏感字段（API Key / 密码密文 / headers）原样导出。
 */

export const DATA_APP_TAG = 'CodeCraft'
export const DATA_FORMAT_VERSION = 1

export interface DataCategoryDef {
  key: string
  label: string
  /** 该类别包含的表（也是导入顺序，父表在前） */
  tables: string[]
  /** 一句话说明，用于卡片提示 */
  desc: string
}

/** 类别定义（顺序与后端 DataTransferService.CATEGORIES 一致） */
export const DATA_CATEGORIES: DataCategoryDef[] = [
  { key: 'llmProvider', label: 'LLM 配置', tables: ['llm_provider'], desc: '平台凭证、模型列表、扩展配置' },
  { key: 'agent', label: '智能体', tables: ['agent_config'], desc: '配置、提示词、工具集、Provider 绑定' },
  { key: 'skill', label: '技能', tables: ['skill'], desc: '触发词、指令、置信度' },
  {
    key: 'chat',
    label: '聊天记录',
    tables: ['conversation', 'conversation_message', 'conversation_compaction'],
    desc: '会话、消息、上下文压缩'
  },
  { key: 'lesson', label: '踩坑经验', tables: ['lesson', 'lesson_rule'], desc: '经验条目与归并规则（norm 缓存不导出）' },
  { key: 'mcp', label: 'MCP 服务器', tables: ['mcp_server'], desc: '服务器配置、权限、headers' },
  { key: 'dbConnection', label: '数据库连接', tables: ['db_connection'], desc: '外部库连接（密码密文）' }
]

/** 导入结果：单表统计（对齐后端 import-data 的 details[].tables[]） */
export interface DataImportTableDetail {
  table: string
  inserted: number
  updated: number
  failed: number
  errors?: string[]
}

/** 导入结果：单类别统计 */
export interface DataImportCategoryDetail {
  category: string
  label: string
  tables: DataImportTableDetail[]
}

/** 导入结果汇总 */
export interface DataImportResult {
  inserted: number
  updated: number
  failed: number
  details: DataImportCategoryDetail[]
}

/** 备份文件预览：单类别 */
export interface BackupPreviewItem {
  key: string
  label: string
  count: number
  tables: Array<{ table: string; count: number }>
}

/** 备份文件预览（前端解析，用于导入前确认） */
export interface BackupPreview {
  exportedAt: string
  formatVersion?: number
  items: BackupPreviewItem[]
  total: number
}

/** 类别行数（缺失按 0 处理） */
export function categoryCount(stats: Record<string, number> | undefined, key: string): number {
  return Number(stats?.[key] ?? 0) || 0
}

/** 全部类别总行数 */
export function totalCount(stats: Record<string, number> | undefined): number {
  return DATA_CATEGORIES.reduce((sum, cat) => sum + categoryCount(stats, cat.key), 0)
}

/** 解析备份文件（校验 app 标识；按类别汇总表级行数） */
export function parseBackupJson(text: string): BackupPreview {
  let bundle: Record<string, any>
  try {
    bundle = JSON.parse(text)
  } catch (e) {
    throw new Error(`JSON 解析失败：${(e as Error).message}`)
  }
  if (!bundle || bundle.app !== DATA_APP_TAG) {
    throw new Error('不是 CodeCraft 备份文件（缺少 app 标识）')
  }

  const categories = bundle.categories || {}
  const items: BackupPreviewItem[] = []
  let total = 0

  for (const cat of DATA_CATEGORIES) {
    const category = categories[cat.key]
    if (!category || !category.tables) continue
    const tables: Array<{ table: string; count: number }> = []
    let count = 0
    for (const table of Object.keys(category.tables)) {
      const rows = Array.isArray(category.tables[table]) ? category.tables[table].length : 0
      tables.push({ table, count: rows })
      count += rows
    }
    items.push({ key: cat.key, label: category.label || cat.label, count, tables })
    total += count
  }

  if (!items.length) throw new Error('备份文件不含可导入的数据类别')
  return { exportedAt: bundle.exportedAt || '未知', formatVersion: bundle.formatVersion, items, total }
}

/** 字节数格式化 */
export function formatDataSize(bytes: number): string {
  const units = ['B', 'KB', 'MB', 'GB']
  let n = bytes || 0
  let i = 0
  while (n >= 1024 && i < units.length - 1) {
    n /= 1024
    i += 1
  }
  return `${i === 0 ? n : n.toFixed(1)} ${units[i]}`
}

/** 备份文件名（与后端 codecraft-backup-yyyyMMdd-HHmmss.json 一致） */
export function backupFileName(date = new Date()): string {
  const p = (n: number) => String(n).padStart(2, '0')
  const stamp =
    `${date.getFullYear()}${p(date.getMonth() + 1)}${p(date.getDate())}` +
    `-${p(date.getHours())}${p(date.getMinutes())}${p(date.getSeconds())}`
  return `codecraft-backup-${stamp}.json`
}
