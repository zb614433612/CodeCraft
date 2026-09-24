import type { ApiResponse } from '@/store/user'
import { request } from '@/utils/http-client'

export interface UploadBackgroundResult {
  /** 背景图相对 URL，形如 /api/appearance/file/xxx.png */
  url: string
}

/**
 * 上传窗口背景图（本地存储，非云端）
 * <p>POST /api/appearance/background（multipart），返回可访问的相对 URL，写入 settingsStore.bgImageUrl。</p>
 */
export async function uploadBackground(file: File): Promise<ApiResponse<UploadBackgroundResult>> {
  const form = new FormData()
  form.append('file', file)
  return request<UploadBackgroundResult>('/appearance/background', {
    method: 'POST',
    body: form
  })
}
