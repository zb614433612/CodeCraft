/**
 * 全局轻提示（Toast）
 * ----------------------------------------------------------------------------
 * 替代 ant-design-vue 的 `message`，零依赖、可在任意 ts/vue 中直接调用。
 *
 * 用法：
 *   import { toast } from '@/utils/toast'
 *   toast.success('保存成功')
 *   toast.error('删除失败', { description: '数据库连接超时' })
 *   const id = toast.loading('正在上传…')
 *   toast.update(id, { type: 'success', message: '上传完成' })   // loading → success
 *   toast.dismiss(id)
 *
 * 设计要点：
 *   1) 函数式调用（模块级单例状态），不依赖组件上下文，也不需 provide/inject；
 *   2) 渲染由 <ToastHost /> 负责（Teleport 到 body），App.vue 挂载一次即可；
 *   3) 同 type + 同文案在 1.2s 内重复出现时合并为一条并累计次数（避免批量失败刷屏）；
 *   4) loading 类型不自动关闭，必须 update/dismiss（与 antd message.loading 一致）；
 *   5) loading 之外的类型默认显示关闭按钮，鼠标可手动关掉。
 */

import { reactive } from 'vue'

export type ToastType = 'success' | 'error' | 'warning' | 'info' | 'loading'

export interface ToastOptions {
  /** 停留时长（毫秒）；0 表示不自动关闭。默认见 DEFAULT_DURATION */
  duration?: number
  /** 副文案（第二行小字，用于补充错误原因等） */
  description?: string
  /** 指定 id：同 id 的条目会被替换（也可与 update 配合） */
  id?: string
  /** 是否显示关闭按钮；默认 loading 不显示，其余显示 */
  closable?: boolean
}

export interface ToastItem {
  id: string
  type: ToastType
  message: string
  description?: string
  closable: boolean
  /** 重复出现次数（>1 时界面显示 ×N） */
  repeat: number
  /** 创建时间戳（毫秒） */
  createdAt: number
}

/** 同时最多展示的条数，超出时挤掉最旧的一条 */
const MAX_VISIBLE = 4
/** 去重时间窗（毫秒） */
const DEDUP_WINDOW = 1200

const DEFAULT_DURATION: Record<ToastType, number> = {
  success: 2400,
  error: 4500,
  warning: 3600,
  info: 2600,
  loading: 0
}

/** 渲染器读取的响应式列表（由 ToastHost.vue 消费） */
export const toastItems = reactive<ToastItem[]>([])

const timers = new Map<string, ReturnType<typeof setTimeout>>()
let seq = 0
let lastSignature = ''
let lastAt = 0

function clearTimer(id: string): void {
  const timer = timers.get(id)
  if (timer !== undefined) {
    clearTimeout(timer)
    timers.delete(id)
  }
}

function scheduleDismiss(item: ToastItem, duration: number): void {
  clearTimer(item.id)
  if (duration <= 0) return
  timers.set(
    item.id,
    setTimeout(() => {
      timers.delete(item.id)
      remove(item.id)
    }, duration)
  )
}

function remove(id: string): void {
  clearTimer(id)
  const index = toastItems.findIndex(item => item.id === id)
  if (index >= 0) toastItems.splice(index, 1)
}

function resolveDuration(type: ToastType, options: ToastOptions): number {
  if (options.duration !== undefined) return options.duration
  return DEFAULT_DURATION[type]
}

function push(type: ToastType, message: string, options: ToastOptions = {}): string {
  const duration = resolveDuration(type, options)
  const now = Date.now()
  const signature = `${type}\u0000${message}`

  // 1) 同 id：就地替换（loading → success 的常见场景）
  if (options.id) {
    const existing = toastItems.find(item => item.id === options.id)
    if (existing) {
      existing.type = type
      existing.message = message
      existing.description = options.description
      existing.closable = options.closable ?? type !== 'loading'
      existing.repeat = 1
      scheduleDismiss(existing, duration)
      lastSignature = signature
      lastAt = now
      return existing.id
    }
  }

  // 2) 短时间去重：同 type + 同文案合并计数（跳过 loading，避免吞掉独立流程）
  const canDedupe = type !== 'loading' && options.id === undefined
  if (canDedupe && signature === lastSignature && now - lastAt < DEDUP_WINDOW) {
    for (let i = toastItems.length - 1; i >= 0; i -= 1) {
      const item = toastItems[i]
      if (item.type === type && item.message === message) {
        item.repeat += 1
        item.createdAt = now
        scheduleDismiss(item, duration)
        lastAt = now
        return item.id
      }
    }
  }
  lastSignature = signature
  lastAt = now

  // 3) 新增
  const item: ToastItem = {
    id: options.id ?? `cc-toast-${(seq += 1)}`,
    type,
    message,
    description: options.description,
    closable: options.closable ?? type !== 'loading',
    repeat: 1,
    createdAt: now
  }
  toastItems.push(item)
  while (toastItems.length > MAX_VISIBLE) {
    remove(toastItems[0].id)
  }
  scheduleDismiss(item, duration)
  return item.id
}

export interface ToastUpdatePatch {
  type?: ToastType
  message?: string
  description?: string
  duration?: number
  closable?: boolean
}

export const toast = {
  success(message: string, options?: ToastOptions): string {
    return push('success', message, options)
  },
  error(message: string, options?: ToastOptions): string {
    return push('error', message, options)
  },
  warning(message: string, options?: ToastOptions): string {
    return push('warning', message, options)
  },
  info(message: string, options?: ToastOptions): string {
    return push('info', message, options)
  },
  /** 常驻提示，需自行 update/dismiss */
  loading(message: string, options?: ToastOptions): string {
    return push('loading', message, { ...options, closable: options?.closable ?? false })
  },
  /** 改造已有条目；id 不存在时按当前 type 新建 */
  update(id: string, patch: ToastUpdatePatch): string {
    const existing = toastItems.find(item => item.id === id)
    const type = patch.type ?? existing?.type ?? 'info'
    const message = patch.message ?? existing?.message ?? ''
    return push(type, message, {
      id,
      description: patch.description ?? existing?.description,
      duration: patch.duration,
      closable: patch.closable ?? existing?.closable
    })
  },
  /** 关闭指定条目；不传 id 时清空全部 */
  dismiss(id?: string): void {
    if (id === undefined) {
      toastItems.slice().forEach(item => remove(item.id))
      return
    }
    remove(id)
  }
}

/** 供 <ToastHost /> 内部使用 */
export function dismissToastItem(id: string): void {
  remove(id)
}

// 开发期手测入口：控制台执行 __ccToast.success('hi')
if (import.meta.env.DEV && typeof window !== 'undefined') {
  ;(window as unknown as Record<string, unknown>).__ccToast = toast
}
