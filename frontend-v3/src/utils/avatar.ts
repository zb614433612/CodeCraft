/**
 * 头像工具（v3）
 * ----------------------------------------------------------------------------
 * 后端返回的头像有两种形态：
 *   1) 图片 URL —— `POST /api/avatar` 上传后得到 `/api/avatar/file/<uuid>.png`；
 *      也允许用户直接填 http(s) 外链或 data:image 内联图；
 *   2) 纯文本 —— emoji 或单个汉字（内置 Agent「圆圆」就是这种）。
 *
 * <p>判定必须与后端一致，否则会把 emoji 塞进 `<img src>` 变成裂图。规则集中在
 * 这里，ChatMessage / AppSidebar / AgentCard 共用，避免三处各写一份正则漂移。</p>
 */

/** 头像是否为图片地址（可直接作为 `<img src>`） */
export function isImageAvatar(value?: string | null): boolean {
  const src = (value || '').trim()
  if (!src) return false
  return /^(https?:\/\/|data:image\/|\/api\/avatar\/|\/api\/appearance\/|\/api\/files\/)/i.test(src)
}

/**
 * 头像文字兜底：取首个字符并大写。
 * @param name 名称 / 用户名
 * @param fallback 名称也为空时的占位字符
 */
export function avatarFallback(name?: string | null, fallback = 'U'): string {
  const text = (name || '').trim()
  return text ? text.charAt(0).toUpperCase() : fallback
}
