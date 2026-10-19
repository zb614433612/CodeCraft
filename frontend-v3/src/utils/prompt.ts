/**
 * 全局输入弹窗（Prompt）
 * ----------------------------------------------------------------------------
 * 补齐 Confirm 无法表达的场景：需要用户输入一段文本（如「会话重命名」）。
 *
 * 用法：
 *   import { prompt } from '@/utils/prompt'
 *   const name = await prompt({ title: '重命名会话', value: 当前名, validate: v => v ? '' : '名称不能为空' })
 *   if (name === null) return   // 用户取消
 *
 * 设计要点：
 *   1) 返回 Promise<string | null>，null 表示取消；
 *   2) 同一时刻只展示一个（重命名是单发操作，无需队列）；
 *   3) 渲染由 <PromptHost /> 负责（Teleport 到 body），App.vue 挂载一次。
 */

import { shallowReactive } from 'vue'

export interface PromptOptions {
  /** 标题（必填） */
  title: string
  /** 输入框上方的说明文案 */
  label?: string
  /** 初始值（通常传当前名称） */
  value?: string
  /** 占位文案 */
  placeholder?: string
  /** 确认按钮文案，默认「确定」 */
  okText?: string
  /** 取消按钮文案，默认「取消」 */
  cancelText?: string
  /**
   * 校验函数：返回非空字符串表示不通过（作为错误文案展示，弹窗保持打开）。
   * 不传则只拦截「全空白」输入。
   */
  validate?: (value: string) => string
}

interface PromptEntry extends PromptOptions {
  resolve: (value: string | null) => void
}

/** 渲染器状态（由 PromptHost.vue 消费） */
export const promptState = shallowReactive<{ current: PromptEntry | null }>({
  current: null
})

/**
 * 打开输入弹窗。
 * @returns 用户确认时返回去除首尾空白的文本；取消/ESC/点遮罩返回 null
 */
export function prompt(options: PromptOptions): Promise<string | null> {
  return new Promise<string | null>(resolve => {
    // 已有弹窗未收口时先取消它，避免 Promise 永久悬挂
    promptState.current?.resolve(null)
    promptState.current = { ...options, resolve }
  })
}

/** 收口当前弹窗（供 PromptHost 调用） */
export function settlePrompt(value: string | null): void {
  const entry = promptState.current
  if (!entry) return
  promptState.current = null
  entry.resolve(value === null ? null : value.trim())
}

/** 清空队列与当前弹窗（路由切换等兜底场景） */
export function resetPrompt(): void {
  const entry = promptState.current
  promptState.current = null
  entry?.resolve(null)
}

// 开发期手测入口：控制台执行 __ccPrompt({ title: 'hi' })
if (import.meta.env.DEV && typeof window !== 'undefined') {
  const scope = window as unknown as Record<string, unknown>
  scope.__ccPrompt = prompt
}
