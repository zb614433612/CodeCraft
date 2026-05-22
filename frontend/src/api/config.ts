import { request } from '@/utils/http-client'

export interface ConfigItem {
  key: string
  value: string
}

/**
 * 获取配置项
 */
export async function getConfig(key: string) {
  return request<ConfigItem>(`/config/${key}`)
}

/**
 * 更新配置项
 */
export async function setConfig(key: string, value: string) {
  return request<void>(`/config/${key}`, {
    method: 'PUT',
    body: JSON.stringify({ value })
  })
}
