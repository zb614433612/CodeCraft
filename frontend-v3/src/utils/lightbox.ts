/**
 * 图片放大查看（F14）—— 模块级单例
 * ----------------------------------------------------------------------------
 * 与 toast / confirm 同一套思路：任何组件（聊天输入区附件、历史文件选择器、
 * 文件详情抽屉、聊天消息里的图片）都可以直接 `openLightbox(images, i)`，
 * 不必层层透传 open / images / index 三个 prop，也不会出现「每个页面各挂一个灯箱」
 * 导致的 z-index 与键盘监听打架。
 *
 * 挂载点：`LightboxHost.vue`（在 App.vue 根部，与 ToastHost / ConfirmHost 并列）。
 */
import { shallowReactive } from 'vue'
import type { LightboxImage } from '../components/ImageLightbox.vue'

export interface LightboxState {
  open: boolean
  images: LightboxImage[]
  /** 当前显示第几张（0 起） */
  index: number
}

export const lightboxState = shallowReactive<LightboxState>({
  open: false,
  images: [],
  index: 0
})

/**
 * 打开灯箱。
 *
 * @param images 图片列表（单图也传数组；多图时可用 ← → 或两侧箭头切换）
 * @param startIndex 打开时定位到第几张（越界自动收敛到合法范围）
 */
export function openLightbox(images: LightboxImage[], startIndex = 0): void {
  const list = (images || []).filter(img => !!img?.src)
  if (!list.length) return
  lightboxState.images = list
  lightboxState.index = Math.min(Math.max(startIndex, 0), list.length - 1)
  lightboxState.open = true
}

/** 关闭灯箱（保留图片列表，便于淡出动画/再次打开） */
export function closeLightbox(): void {
  lightboxState.open = false
}
