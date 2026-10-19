/**
 * 全局确认弹窗（Confirm）
 * ----------------------------------------------------------------------------
 * 替代 ant-design-vue 的 `Modal.confirm`，Promise 化、支持危险操作语义。
 *
 * 用法一（推荐，线性代码）：
 *   import { confirmDelete } from '@/utils/confirm'
 *   if (!(await confirmDelete('智能体', 'AI 助手'))) return
 *   await deleteAgent(id)
 *
 * 用法二（兼容旧 Modal.confirm 的 onOk 风格）：
 *   const ok = await confirm({
 *     title: '确认回滚代码',
 *     content: '将丢弃当前工作区改动。',
 *     type: 'danger',
 *     okText: '回滚',
 *     onOk: async () => { await rollback() }   // 抛错 → 弹窗保持打开
 *   })
 *
 * 设计要点：
 *   1) 函数式单例 + Promise，任意位置可 await；同时多次调用会排队串行展示；
 *   2) 渲染由 <ConfirmHost /> 负责（Teleport 到 body）；
 *   3) `onOk` 抛错时不关闭弹窗，由宿主用 toast 展示错误，用户可重试或取消；
 *   4) 默认聚焦「取消」按钮（危险操作防误触），ESC = 取消，Tab 焦点在弹窗内循环。
 */

import { shallowReactive } from 'vue'

export type ConfirmType = 'info' | 'warning' | 'danger'

export interface ConfirmOptions {
  /** 标题（必填） */
  title: string
  /** 正文说明，支持换行（用 \n） */
  content?: string
  /** 确认按钮文案，默认「确定」 */
  okText?: string
  /** 取消按钮文案，默认「取消」 */
  cancelText?: string
  /** 类型：danger 时确认按钮为红色实心 + 默认聚焦取消 */
  type?: ConfirmType
  /** 点击确认后执行（可异步）；抛错则弹窗保持打开 */
  onOk?: () => void | Promise<void>
  /** 点击取消/ESC/遮罩后执行 */
  onCancel?: () => void
}

interface ConfirmEntry {
  key: number
  options: ConfirmOptions
  resolve: (ok: boolean) => void
}

/** 渲染器状态（由 ConfirmHost.vue 消费） */
export const confirmState = shallowReactive<{
  current: ConfirmEntry | null
  loading: boolean
}>({
  current: null,
  loading: false
})

const queue: ConfirmEntry[] = []
let seq = 0

function pump(): void {
  if (confirmState.current || queue.length === 0) return
  confirmState.current = queue.shift() as ConfirmEntry
  confirmState.loading = false
}

/** 打开确认弹窗，返回用户是否确认 */
export function confirm(options: ConfirmOptions): Promise<boolean> {
  return new Promise<boolean>(resolve => {
    queue.push({ key: (seq += 1), options, resolve })
    pump()
  })
}

/**
 * 收口当前弹窗（由 ConfirmHost 调用）。
 * @param ok true=确认，false=取消
 */
export async function settleConfirm(ok: boolean): Promise<void> {
  const entry = confirmState.current
  if (!entry || confirmState.loading) return
  const { options, resolve } = entry

  if (ok && options.onOk) {
    confirmState.loading = true
    try {
      await options.onOk()
    } catch (error) {
      // 保持弹窗打开，调用方（宿主）负责提示；用户可重试或取消
      confirmState.loading = false
      throw error
    }
  } else if (!ok) {
    options.onCancel?.()
  }

  confirmState.current = null
  confirmState.loading = false
  resolve(ok)
  pump()
}

export interface ConfirmDeleteOptions {
  /** 补充说明，默认「此操作不可撤销。」 */
  hint?: string
  /** 确认按钮文案，默认「删除」 */
  okText?: string
}

/**
 * 删除类操作的确认弹窗（高频场景的语法糖）。
 * @param what 对象类型，如「智能体」「技能」「定时任务」
 * @param name 对象名称，缺省时文案为「该{what}」
 */
export function confirmDelete(
  what: string,
  name?: string,
  options: ConfirmDeleteOptions = {}
): Promise<boolean> {
  const target = name ? `「${name}」` : `该${what}`
  return confirm({
    title: `删除${what}`,
    content: `确定删除${target}吗？${options.hint ?? '此操作不可撤销。'}`,
    okText: options.okText ?? '删除',
    cancelText: '取消',
    type: 'danger'
  })
}

/** 供 <ConfirmHost /> 内部使用：清空队列与当前弹窗（如路由切换兜底） */
export function resetConfirm(): void {
  const pending = queue.splice(0, queue.length)
  pending.forEach(entry => entry.resolve(false))
  const current = confirmState.current
  confirmState.current = null
  confirmState.loading = false
  current?.resolve(false)
}

// 开发期手测入口：控制台执行 __ccConfirm({ title: 'hi' })
if (import.meta.env.DEV && typeof window !== 'undefined') {
  const scope = window as unknown as Record<string, unknown>
  scope.__ccConfirm = confirm
  scope.__ccConfirmDelete = confirmDelete
}
