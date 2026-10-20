/**
 * 询问卡片「快捷选项」解析
 * ----------------------------------------------------------------------------
 * 后端 `ask_clarification` 下发的 question 是 **markdown 原文**，LLM 被要求用
 * `1. / 2. / 3.` 编号列出可选方向（见 AskClarificationTool 的参数说明）。
 * 这里把正文里的编号列表解析成可点击按钮，用户点一下即等于「回复该选项」，
 * 不用再手打「1」。
 *
 * 设计约束（都是踩过的坑）：
 *   1) **必须剔除围栏代码块**：diff / 命令片段里的 `1.` 不是选项，误判会让用户
 *      点到完全无关的命令；
 *   2) 只在「像选项」时返回：至少 2 项、最多 8 项、单项长度受限，
 *      避免把「实施步骤」列表整成按钮；
 *   3) 解析失败就返回空数组 —— 卡片仍保留原始文本框，功能不降级。
 *
 * 三种列表都支持，优先级递减（数字 → 字母 → 项目符号）：
 *   `1. RESTful - 简单通用` / `1、xxx` / `1) xxx`
 *   `A. 方案一` / `B、方案二`
 *   `- 方案一` / `* 方案二`
 */

/** 快捷选项（展示 + 回填） */
export interface AskOption {
  /** 稳定 key（序号 + 原文），用作 v-for key */
  key: string
  /** 展示用编号：1 / A / • */
  mark: string
  /** 展示用正文（已去 markdown 装饰，超长截断） */
  label: string
  /** 回填给后端的回答（保留编号 + 去装饰的完整语义） */
  answer: string
  /** 原始文本（tooltip 用，不截断） */
  raw: string
}

/** 单选项正文最大展示长度（超出截断，完整内容进 tooltip） */
const OPTION_LABEL_LIMIT = 160
/**
 * 可选项数量上限：再多就不像「让你选一个方向」，而更像任务/实施步骤清单。
 * 真实的方案选择通常 2~5 个，6 已经留了余量。
 */
const OPTION_MAX_COUNT = 6
/**
 * 选项平均长度上限（字符）：超过说明每项都是一段说明文字，
 * 多半是「实施步骤」而不是「选哪个方向」，此时不渲染按钮、保留文本框更安全。
 */
const OPTION_AVG_LIMIT = 90
/** 单个选项原文长度上限（过长说明是段落，不是选项） */
const OPTION_RAW_LIMIT = 400

/*
 * 列表项识别：
 *   `1. xxx` / `1) xxx`（英文点/右括号后必须有空白，否则 `1.5 倍` 会被当成选项）
 *   `1、xxx` / `1）xxx`（中文标点后允许没有空白，中文排版里两者都常见）
 */
const NUMBER_ITEM_RE = /^\s{0,3}(\d{1,2})\s*(?:[.．]\s+|[、,，)）]\s*)(.+)$/
const ALPHA_ITEM_RE = /^\s{0,3}([A-Za-z])\s*(?:[.．]\s+|[、,，)）]\s*)(.+)$/
const BULLET_ITEM_RE = /^\s{0,3}[-*+]\s+(.+)$/

/** 去掉行内 markdown 装饰：加粗 / 斜体 / 行内代码 / 链接 / 图片 */
function stripInlineMarkdown(text: string): string {
  return text
    .replace(/!\[([^\]]*)\]\([^)]*\)/g, '$1')
    .replace(/\[([^\]]+)\]\([^)]*\)/g, '$1')
    .replace(/`([^`]*)`/g, '$1')
    .replace(/(\*\*|__)(.*?)\1/g, '$2')
    .replace(/(?<![*_])([*_])([^*_]+)\1(?![*_])/g, '$2')
    .replace(/\s+/g, ' ')
    .trim()
}

/** 把围栏代码块（``` / ~~~）整段替换成空行，保留行号结构 */
function stripFencedCode(markdown: string): string {
  const lines = markdown.split('\n')
  const out: string[] = []
  let fence: string | null = null
  for (const line of lines) {
    const open = line.match(/^\s{0,3}(`{3,}|~{3,})/)
    if (fence) {
      // 关闭围栏：同字符且长度不短于开启标记
      if (open && open[1][0] === fence[0] && open[1].length >= fence.length) fence = null
      out.push('')
      continue
    }
    if (open) {
      fence = open[1]
      out.push('')
      continue
    }
    out.push(line)
  }
  return out.join('\n')
}

/** 从一组「编号 + 文本」候选中构造选项；不合格返回空数组 */
function buildOptions(items: Array<{ mark: string; text: string }>): AskOption[] {
  if (items.length < 2 || items.length > OPTION_MAX_COUNT) return []
  const options: AskOption[] = []
  for (const item of items) {
    const raw = item.text.trim()
    if (!raw || raw.length > OPTION_RAW_LIMIT) return []
    const plain = stripInlineMarkdown(raw)
    if (!plain) return []
    // 表格分隔行 / 分割线之类的伪列表项：直接放弃整组，避免出现「---」按钮
    if (/^[-=*_\s|]+$/.test(plain)) return []
    const label =
      plain.length > OPTION_LABEL_LIMIT ? plain.slice(0, OPTION_LABEL_LIMIT) + '…' : plain
    options.push({
      key: `${item.mark}-${raw.slice(0, 24)}`,
      mark: item.mark,
      label,
      // 回填保留编号：LLM 更容易把「1. xxx」对应回自己给出的方案
      answer: `${item.mark}. ${plain}`,
      raw
    })
  }
  // 平均长度超标 → 更像说明清单，不当作可点选项
  const avgLength = options.reduce((sum, opt) => sum + opt.label.length, 0) / options.length
  if (avgLength > OPTION_AVG_LIMIT) return []
  return options
}

/** 取「连续的一段列表项」：允许中间夹空行，遇到非列表正文即结束 */
function collectItems(
  lines: string[],
  re: RegExp,
  markOf: (m: RegExpMatchArray) => string,
  textOf: (m: RegExpMatchArray) => string
): Array<{ mark: string; text: string }> {
  let best: Array<{ mark: string; text: string }> = []
  let current: Array<{ mark: string; text: string }> = []
  let gap = 0

  const flush = () => {
    if (current.length > best.length) best = current
    current = []
    gap = 0
  }

  for (const line of lines) {
    const matched = line.match(re)
    if (matched) {
      current.push({ mark: markOf(matched), text: textOf(matched) })
      gap = 0
      continue
    }
    if (!line.trim()) {
      // 空行不断段（选项之间常有空行），连续两个空行才认为段落结束
      gap += 1
      if (gap >= 2 && current.length) flush()
      continue
    }
    // 续行（缩进）忽略；其它正文视为列表结束
    if (/^\s{2,}\S/.test(line) && current.length) continue
    if (current.length) flush()
  }
  flush()
  return best
}

/**
 * 从 ask_clarification 的 question 正文里解析可点击选项。
 *
 * @param markdown 后端下发的 question 原文（可含字面量 `\n`）
 * @returns 至少 2 项才返回；否则空数组（调用方保留文本框）
 */
export function parseAskOptions(markdown: string): AskOption[] {
  if (!markdown) return []
  // 后端偶发双重转义：字面量 \n 先还原成真换行，否则整段只有一行、永远解析不到列表
  const text = stripFencedCode(markdown.replace(/\\n/g, '\n'))
  const lines = text.split('\n')

  const numeric = collectItems(
    lines,
    NUMBER_ITEM_RE,
    m => m[1],
    m => m[2]
  )
  if (numeric.length >= 2) return buildOptions(numeric)

  const alpha = collectItems(
    lines,
    ALPHA_ITEM_RE,
    m => m[1].toUpperCase(),
    m => m[2]
  )
  if (alpha.length >= 2) return buildOptions(alpha)

  const bullet = collectItems(
    lines,
    BULLET_ITEM_RE,
    () => '•',
    m => m[1]
  )
  return buildOptions(bullet)
}
