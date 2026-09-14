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

/** 测试未保存连接（新增弹窗内先测试再保存） */
export function testDbConnection(data: DbConnectionConfig) {
  return request<{ message: string }>('/db-connections/test', {
    method: 'POST',
    body: JSON.stringify(data)
  })
}

/** 测试已保存连接（只传 id，密码由服务端解密） */
export function testSavedDbConnection(id: number) {
  return request<{ message: string }>(`/db-connections/${id}/test`, { method: 'POST' })
}
