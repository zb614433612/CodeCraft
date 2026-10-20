/**
 * 技能命中解析（技能命中标签）
 * ----------------------------------------------------------------------------
 * 后端在 LLM **真正执行** `skill(action=load)` 时会给出两个信号，本模块统一解析：
 *   1) 主通道：`tool_call_start.summaries` 里的一条「🎯 命中技能：xxx」摘要；
 *   2) 兜底通道：工具输出正文里的「【技能详情】xxx (ID:n)」——历史回放也靠它。
 *
 * 为什么不能拿「技能目录匹配」当命中：v3 已把技能目录全量注入系统提示词，
 * 「匹配到」不等于「真的执行了」；只有 load 动作发生才算命中（与经典版同口径，
 * 否则界面上会挂一堆没被用过的技能，反而制造噪音）。
 */

/** 从 tool_call_start.summaries 提取技能名（后端生成「🎯 命中技能：xxx」，可能带 ** 加粗） */
export function parseSkillHitsFromSummaries(summaries: string[]): string[] {
  const hits: string[] = []
  for (const raw of summaries || []) {
    if (!raw) continue
    const matched = raw.match(/🎯\s*命中技能[:：]?\*{0,2}\s*(.+)/)
    if (!matched) continue
    // 去掉尾部残留的 ** 与空白（后端有时整体加粗）
    const name = matched[1].replace(/\*+\s*$/, '').trim()
    if (name && !hits.includes(name)) hits.push(name)
  }
  return hits
}

/** 从技能加载的工具输出里提取技能名（`skill action=load` 固定输出「【技能详情】xxx (ID:n)」） */
export function parseSkillHitFromToolOutput(text: string): string | null {
  if (!text || !text.includes('【技能详情】')) return null
  const matched = text.match(/【技能详情】(.+?)\s*\(ID:/)
  const name = matched ? matched[1].trim() : ''
  return name || null
}

/** 从一组工具输出里收集技能命中（历史回放用；保持出现顺序并去重） */
export function collectSkillHits(texts: Array<string | undefined | null>): string[] {
  const hits: string[] = []
  for (const text of texts) {
    const name = parseSkillHitFromToolOutput(text || '')
    if (name && !hits.includes(name)) hits.push(name)
  }
  return hits
}

/**
 * 合并技能命中（返回新数组，调用方直接写回响应式字段）。
 *
 * <p>用「返回新数组」而不是就地 push：assistant 是响应式代理，
 * 数组原地 push 在深层监听下也能触发，但新数组能让「同一 tick 内多次合并」更可预测。</p>
 */
export function mergeSkillHits(current: string[] | undefined, next: string[]): string[] {
  const out = [...(current ?? [])]
  for (const name of next) {
    if (name && !out.includes(name)) out.push(name)
  }
  return out
}
