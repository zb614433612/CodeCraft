import { request } from '@/utils/http-client'

/** 数据库连接配置（列表出参：密码不回显，仅 hasPassword 标记） */
export interface DbConnectionVO {
  id: number
  name: string
  dbType: 'mysql' | 'postgresql' | 'h2'
  host?: string
  port?: number
  databaseName?: string
  username?: string
  hasPassword?: boolean
  extraParams?: string
  enabled: number
  createdAt?: string
  updatedAt?: string
}

/** 数据库连接配置（提交用：passwordPlain 明文仅提交时传，更新留空=不修改密码） */
export interface DbConnectionConfig {
  id?: number
  name: string
  dbType: 'mysql' | 'postgresql' | 'h2'
  host?: string
  port?: number
  databaseName?: string
  username?: string
  passwordPlain?: string
  extraParams?: string
  enabled: number
}

/** 查询可见连接列表 */
export function listDbConnections() {
  return request<DbConnectionVO[]>('/db-connections')
}

/** 新增连接 */
export function createDbConnection(data: DbConnectionConfig) {
  return request<DbConnectionVO>('/db-connections', {
    method: 'POST',
    body: JSON.stringify(data)
  })
}

/** 更新连接 */
export function updateDbConnection(id: number, data: DbConnectionConfig) {
  return request<DbConnectionVO>(`/db-connections/${id}`, {
    method: 'PUT',
    body: JSON.stringify(data)
  })
}

/** 删除连接 */
export function deleteDbConnection(id: number) {
  return request<void>(`/db-connections/${id}`, { method: 'DELETE' })
}

/** 测试未保存连接（新增弹窗内先测试再保存；成功信息在 message 字段） */
export function testDbConnection(data: DbConnectionConfig) {
  return request<void>('/db-connections/test', {
    method: 'POST',
    body: JSON.stringify(data)
  })
}

/** 测试已保存连接（只传 id，密码由服务端解密；成功信息在 message 字段） */
export function testSavedDbConnection(id: number) {
  return request<void>(`/db-connections/${id}/test`, { method: 'POST' })
}

/** 单条语句的执行结果（SELECT 返回行数据；WRITE 返回影响行数） */
export interface SqlStatementResult {
  /** 语句序号（从 0 开始） */
  index: number
  /** 结果类型：SELECT=查询结果集 / WRITE=写操作 */
  sqlType: 'SELECT' | 'WRITE'
  /** SELECT 列名（按查询顺序） */
  columns?: string[]
  /** SELECT 行数据 */
  rows?: Record<string, any>[]
  /** SELECT 返回行数 */
  totalRows?: number
  /** 是否受最大行数限制（结果可能不完整） */
  truncated?: boolean
  /** WRITE 影响行数 */
  affectedRows?: number
}

/** 执行 SQL 结果（支持多条语句，按执行顺序每个语句一个结果块） */
export interface SqlExecuteResult {
  /** 目标库描述 */
  dbLabel?: string
  /** 总耗时（毫秒） */
  elapsedMs?: number
  /** 实际执行的语句数 */
  statementCount?: number
  /** 各语句结果（按执行顺序） */
  results?: SqlStatementResult[]
}

/**
 * 执行 SQL（管理页「执行 SQL」）
 * 支持多条语句（分号分隔、按顺序执行）；每个查询结果集按 maxRows 截断（默认 200，上限 1000）。
 * 执行可能较久，单独放宽超时至 60s。
 */
export function executeDbConnectionSql(id: number, sql: string, maxRows?: number) {
  return request<SqlExecuteResult>(
    `/db-connections/${id}/execute`,
    { method: 'POST', body: JSON.stringify({ sql, maxRows }) },
    60000
  )
}
