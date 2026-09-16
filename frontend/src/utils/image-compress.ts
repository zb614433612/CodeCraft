/**
 * 图片上传预压缩工具（P3 收尾）
 *
 * <p>目标：粘贴/上传大图（高分辨率截图、相册照片）前做客户端压缩——
 * 减少上传耗时与远端存储占用，并让图片尺寸对视觉模型更友好。</p>
 *
 * <p>策略（保守、可选、绝不阻断）：</p>
 * <ul>
 *   <li>触发条件：文件大小超阈值（默认 1.5MB）或长边超上限（默认 2048px）才压缩，其余原样返回；</li>
 *   <li>压缩方式：canvas 等比缩放 + JPEG（quality 默认 0.85）；透明区填充白底（JPEG 无透明通道）；</li>
 *   <li>安全护栏：压缩后未变小 → 用原图；GIF 不压（保留动图）；任一步失败 → 原图（不阻断上传）；</li>
 *   <li>输出文件名扩展名替换为 .jpg（后端以魔数判定 MIME，扩展名仅展示用）。</li>
 * </ul>
 */

export interface ImageCompressOptions {
  /** 触发压缩的文件大小阈值（字节）；默认 1.5MB */
  thresholdBytes?: number
  /** 压缩后长边上限（像素）；默认 2048 */
  maxLongEdge?: number
  /** JPEG 输出质量（0~1）；默认 0.85 */
  quality?: number
}

const DEFAULT_THRESHOLD_BYTES = 1.5 * 1024 * 1024
const DEFAULT_MAX_LONG_EDGE = 2048
const DEFAULT_QUALITY = 0.85

/**
 * 按需压缩图片（不满足触发条件 / 压缩失败时返回原文件）
 */
export async function compressImageIfNeeded(
  file: File,
  options: ImageCompressOptions = {}
): Promise<File> {
  try {
    if (!file.type.startsWith('image/')) return file
    // GIF 不压缩（canvas 会丢失动画帧）
    if (file.type === 'image/gif') return file

    const threshold = options.thresholdBytes ?? DEFAULT_THRESHOLD_BYTES
    const maxLongEdge = options.maxLongEdge ?? DEFAULT_MAX_LONG_EDGE
    const quality = options.quality ?? DEFAULT_QUALITY

    const decoded = await decodeImage(file)
    if (!decoded) return file
    const { width, height } = decoded

    // 双条件均未超限 → 原样返回（省一次编码开销）
    if (file.size <= threshold && Math.max(width, height) <= maxLongEdge) {
      decoded.dispose()
      return file
    }

    // 目标尺寸（仅等比缩小，不放大）
    const longEdge = Math.max(width, height)
    const scale = longEdge > maxLongEdge ? maxLongEdge / longEdge : 1
    const targetW = Math.max(1, Math.round(width * scale))
    const targetH = Math.max(1, Math.round(height * scale))

    const canvas = document.createElement('canvas')
    canvas.width = targetW
    canvas.height = targetH
    const ctx = canvas.getContext('2d')
    if (!ctx) {
      decoded.dispose()
      return file
    }
    // JPEG 无透明通道：透明区填充白底（避免变黑）
    ctx.fillStyle = '#FFFFFF'
    ctx.fillRect(0, 0, targetW, targetH)
    ctx.drawImage(decoded.source, 0, 0, targetW, targetH)
    decoded.dispose()

    const blob = await canvasToBlob(canvas, 'image/jpeg', quality)
    if (!blob) return file
    // 压缩后反而更大（如小尺寸高质量原图）→ 用原图
    if (blob.size >= file.size) return file

    const baseName = file.name.replace(/\.[^./\\]+$/, '')
    const newName = `${baseName || 'image'}.jpg`
    console.info(
      `[image-compress] 已压缩: ${file.name} ${formatSize(file.size)} → ${newName} ${formatSize(blob.size)} ` +
        `(${width}x${height} → ${targetW}x${targetH})`
    )
    return new File([blob], newName, { type: 'image/jpeg', lastModified: Date.now() })
  } catch (e) {
    // 任何失败 → 原图（压缩是可选项，绝不阻断上传）
    console.warn('[image-compress] 压缩失败，使用原图:', e)
    return file
  }
}

interface DecodedImage {
  source: CanvasImageSource
  width: number
  height: number
  dispose: () => void
}

/** 解码图片（createImageBitmap 优先；不支持时回退 Image + objectURL） */
async function decodeImage(file: File): Promise<DecodedImage | null> {
  if (typeof createImageBitmap === 'function') {
    try {
      const bitmap = await createImageBitmap(file)
      return {
        source: bitmap,
        width: bitmap.width,
        height: bitmap.height,
        dispose: () => bitmap.close()
      }
    } catch {
      // 落到 Image 回退（如个别格式解码差异）
    }
  }
  return await new Promise<DecodedImage | null>((resolve) => {
    const url = URL.createObjectURL(file)
    const img = new Image()
    img.onload = () =>
      resolve({
        source: img,
        width: img.naturalWidth,
        height: img.naturalHeight,
        dispose: () => URL.revokeObjectURL(url)
      })
    img.onerror = () => {
      URL.revokeObjectURL(url)
      resolve(null)
    }
    img.src = url
  })
}

/** canvas.toBlob 的 Promise 包装 */
function canvasToBlob(
  canvas: HTMLCanvasElement,
  type: string,
  quality: number
): Promise<Blob | null> {
  return new Promise((resolve) => {
    canvas.toBlob((blob) => resolve(blob), type, quality)
  })
}

function formatSize(bytes: number): string {
  if (bytes < 1024) return `${bytes}B`
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)}KB`
  return `${(bytes / 1024 / 1024).toFixed(2)}MB`
}
