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
 *
 * <h3>为什么解析要这么「啰嗦」（2026-10 修）</h3>
 * <p>曾经出现过「明明没加载技能，标签却显示 `命中技能：xxx`」的 bug。真因不是后端查名失败，
 * 而是<b>兜底通道扫了整个工具输出</b>：`skill-hit.ts` / `App.vue` / 文档里大量写着示例文本
 * 「【技能详情】xxx (ID:n)」，AI 读这些文件时，工具结果里就带着这行示例，正则便把它当成
 * 一次真实命中，把占位符 `xxx` 挂到了消息上。</p>
 * <p>现在两道闸门：① 「【技能详情】」必须出现在<b>行首</b>（真实输出固定第一行，
 * 源码/文档里的引用几乎都带前缀或包在引号里）；② 名字必须通过
 * {@link isPlausibleSkillName} 校验（挡掉 `xxx`、文件名、路径、代码片段）。</p>
 */

/** 明显是占位符 / 文档示例的名字，一律不当成技能名 */
const PLACEHOLDER_NAMES = new Set(['xxx', 'xx', 'x', 'name', 'skill', 'skillname', '技能名', '示例', '...', '…'])

/**
 * 清洗技能名：摘要体是 markdown（`> **🎯 命中技能:** 名称`），可能残留
 * 引用符号 / 加粗星号 / 前导冒号 / 反引号。标签上出现这些字符非常刺眼，统一在这里剥掉。
 */
function cleanSkillName(raw: string): string {
  return (raw || '')
    .replace(/^\s*[>*\-\s]+/, '')
    .replace(/\*+/g, '')
    .replace(/^[:：`'"]\s*/, '')
    .replace(/\s*[`'"]$/, '')
    .replace(/^[:：]\s*/, '')
    .trim()
}

/**
 * 名字是否像一个真实技能名（防「示例文本 / 文件名 / 路径」被误当成技能）。
 *
 * <p>`#12` 这种后端 ID 兜底单独放行 —— 它语义明确，由上层映射成「技能 #12」。
 * 其余情况宁可漏一个标签，也不要在界面上挂一个看不懂的占位符。</p>
 */
export function isPlausibleSkillName(raw: string): boolean {
  const name = cleanSkillName(raw)
  if (!name) return false
  // 后端查名失败时的 ID 兜底：保留，交给 App.vue 映射成真实名字
  if (/^#\d+$/.test(name)) return true
  if (name.length > 60) return false
  if (PLACEHOLDER_NAMES.has(name.toLowerCase())) return false
  // 路径 / 变量占位 / 代码片段：真实技能名不会带这些字符
  if (/[\\/{}<>`]/.test(name)) return false
  // 文件名（xxx.ts / App.vue / README.md）：来自「读源码」工具输出，不是技能
  if (/\.(ts|tsx|js|cjs|mjs|vue|java|kt|md|json|ya?ml|css|scss|html|xml|py|go|rs|sh|bat)$/i.test(name)) return false
  // 全是 x 的占位（xxx / XXXXX / Xxx）
  if (/^x{2,}$/i.test(name)) return false
  return true
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
     *
     * 再过一层 isPlausibleSkillName：这条通道只会收到后端摘要，理论上干净，
     * 但摘要正文来自技能名（用户可随意命名），同样不能无条件信任。
     */
    const matched = raw.match(/🎯\s*命中技能\s*[:：]?\s*\*{0,2}\s*([^\n]+)/)
    const name = cleanSkillName(matched ? matched[1] : '')
    if (isPlausibleSkillName(name) && !hits.includes(name)) hits.push(name)
  }
  return hits
}

/**
 * 从技能加载的工具输出里提取技能名。
 *
 * <p>`skill action=load` 的固定输出是**第一行**「【技能详情】名称 (ID:22)」，
 * 因此这里用 `m` 标志锚定行首：源码 / 文档中引用这个格式时几乎都带前缀
 * （`「【技能详情】xxx (ID:n)」`、`* 【技能详情】…`、`match(/【技能详情】/)`），
 * 锚定后自然不匹配 —— 这是「读到示例文本就误报命中」的根因修复。</p>
 */
export function parseSkillHitFromToolOutput(text: string): string | null {
  if (!text || !text.includes('【技能详情】')) return null
  /*
    允许的形态（整行）：
      【技能详情】代码审查 (ID:22)
      【技能详情】代码审查（ID：22）
      【技能详情】代码审查            ← 输出被截断时
    不允许：`xxx 【技能详情】…`、`text.includes('【技能详情】')` 这类非行首引用。
  */
  const LINE_RE = /^【技能详情】[ \t]*([^\n（(]{1,60}?)[ \t]*(?:[（(][ \t]*ID[ \t]*[:：][ \t]*\d+[ \t]*[)）])?[ \t]*$/m
  const matched = text.match(LINE_RE)
  if (!matched) return null
  const name = cleanSkillName(matched[1])
  return isPlausibleSkillName(name) ? name : null
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
