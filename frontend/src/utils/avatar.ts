/**
 * 头像工具：统一判断 avatar 值的类型（本地上传图片 URL / emoji / 文字）
 *
 * <p>约定：avatar 为本地头像 URL（/api/avatar/file/...）或 http(s)/data/blob 图片地址时按图片渲染；
 * 其余值（如默认 emoji 🤖）按文字渲染。后端 AvatarStore.URL_PREFIX 与此前缀保持一致。</p>
 */

/** 本地头像 URL 前缀（与后端 AvatarStore.URL_PREFIX 保持一致） */
export const AVATAR_URL_PREFIX = '/api/avatar/'

/** 判断 avatar 值是否为可展示的图片地址 */
export function isImageAvatar(avatar?: string | null): boolean {
  if (!avatar) return false
  const v = avatar.trim()
  return (
    v.startsWith(AVATAR_URL_PREFIX) ||
    v.startsWith('http://') ||
    v.startsWith('https://') ||
    v.startsWith('data:image/') ||
    v.startsWith('blob:')
  )
}

/**
 * 计算头像占位文字：avatar 若为 emoji/文字则原样返回，否则返回名称首字母（大写），再兜底 fallbackEmoji。
 */
export function avatarFallbackText(
  avatar?: string | null,
  name?: string | null,
  fallbackEmoji = '🤖'
): string {
  if (avatar && !isImageAvatar(avatar)) return avatar
  if (name && name.trim()) return name.trim().charAt(0).toUpperCase()
  return fallbackEmoji
}
