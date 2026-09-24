import type { ApiResponse } from '@/store/user'
import { request } from '@/utils/http-client'

export interface UploadAvatarResult {
  /** 头像图片相对 URL，形如 /api/avatar/file/xxx.png */
  url: string
}

/**
 * 上传头像（本地存储，非云端）
 * <p>POST /api/avatar（multipart），返回可访问的相对 URL，供写入 user.avatar / agent.avatar。</p>
 */
export async function uploadAvatar(file: File): Promise<ApiResponse<UploadAvatarResult>> {
  const form = new FormData()
  form.append('file', file)
  return request<UploadAvatarResult>('/avatar', {
    method: 'POST',
    body: form
  })
}
