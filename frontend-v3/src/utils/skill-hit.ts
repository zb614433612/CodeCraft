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
 *
 * 关于「只拿到 #id」：后端 `buildSkillHitSummary` 在技能查名失败时回传 `#12`，
 * 本模块原样保留（不猜、不丢），由 App.vue 用技能表把编号映射回名字，
 * 映射不到时 ChatMessage 显示成「技能 #12」——语义明确，不是一串裸数字。
 */

/**
 * 清洗技能名：摘要体是 markdown（`> **🎯 命中技能:** 名称`），可能残留
 * 引用符号 / 加粗星号 / 前导冒号。标签上出现这些字符非常刺眼，统一在这里剥掉。
 */
function cleanSkillName(raw: string): string {
  return (raw || '')
    .replace(/^\s*[>*\-\s]+/, '')
    .replace(/\*+/g, '')
    .replace(/^[:：]\s*/, '')
    .trim()
}

/** 从 tool_call_start.summaries 提取技能名（后端生成「🎯 命中技能：xxx」，可能带 ** 加粗） */
export function parseSkillHitsFromSummaries(summaries: string[]): string[] {
  const hits: string[] = []
  for (const raw of summaries || []) {
    if (!raw) continue
    /*
     * 宽松匹配：冒号可有可无、`**` 可在冒号前也可在名字两侧。
     * 例：`> **🎯 命中技能:** 代码审查` / `🎯 命中技能：**代码审查**` / `🎯 命中技能 代码审查`
     * 用 `[^\n]+` 而非 `(.+)$`：摘要体尾部常带 `\n\n`，JS 的 `$` 不匹配「换行前」，
     * 加了锚点反而整条摘要都解析不出来。
     */
    const matched = raw.match(/🎯\s*命中技能\s*[:：]?\s*\*{0,2}\s*([^\n]+)/)
    const name = cleanSkillName(matched ? matched[1] : '')
    if (name && !hits.includes(name)) hits.push(name)
  }
  return hits
}

/** 从技能加载的工具输出里提取技能名（`skill action=load` 固定输出「【技能详情】xxx (ID:n)」） */
export function parseSkillHitFromToolOutput(text: string): string | null {
  if (!text || !text.includes('【技能详情】')) return null
  // 标准格式：`【技能详情】名称 (ID:12)`；全角括号/全角冒号一并兼容（不同模型回传不一致）
  const matched =
    text.match(/【技能详情】\s*(.+?)\s*[（(]\s*ID\s*[:：]\s*\d+\s*[)）]/i) ||
    // 兜底：输出被截断、只剩「【技能详情】名称」时取该行剩余部分
    text.match(/【技能详情】\s*([^\n（(]+)/)
  const name = cleanSkillName(matched ? matched[1] : '')
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
