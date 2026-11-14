/**
 * Token估算工具
 * 根据DeepSeek等大模型的典型换算关系估算文本的Token数量
 * 注意：这是近似估算，实际Token数量可能因模型词表、编码方式等因素有所不同
 */

/**
 * 估算文本的Token数量
 * @param text 输入文本
 * @returns 估算的Token数量（四舍五入到整数）
 */
export function estimateTokenCount(text: string): number {
  if (!text || text.length === 0) {
    return 0
  }

  let totalTokens = 0

  // 预处理：压缩连续空格和换行符，将它们视为单个空格
  // 同时将换行符转换为空格以便统一处理
  const normalizedText = text.replace(/\s+/g, ' ')
  const normalizedLength = normalizedText.length

  // 辅助函数：判断字符类型
  const isChineseChar = (char: string): boolean => {
    const code = char.charCodeAt(0)
    // 基本汉字（CJK统一表意文字）范围：0x4E00-0x9FFF
    return code >= 0x4e00 && code <= 0x9fff
  }

  const isEnglishLetter = (char: string): boolean => {
    const code = char.charCodeAt(0)
    return (code >= 0x41 && code <= 0x5a) || (code >= 0x61 && code <= 0x7a)
  }

  const isDigit = (char: string): boolean => {
    const code = char.charCodeAt(0)
    return code >= 0x30 && code <= 0x39
  }

  const isPunctuation = (char: string): boolean => {
    // 常见中英文标点符号
    const punctRegex = /[，。！？；："'「」『』《》〈〉、.!,?;:"'(){}\[\]【】]/
    return punctRegex.test(char)
  }

  const isSpace = (char: string): boolean => {
    return char === ' '
  }

  const isEmoji = (char: string): boolean => {
    // 简单emoji检测（不完整）
    const code = char.charCodeAt(0)
    return code >= 0x1f600 && code <= 0x1f64f || // 表情符号
           code >= 0x1f300 && code <= 0x1f5ff || // 杂项符号和象形文字
           code >= 0x1f680 && code <= 0x1f6ff || // 交通和地图符号
           code >= 0x2600 && code <= 0x26ff ||   // 杂项符号
           code >= 0x2700 && code <= 0x27bf ||   // 装饰符号
           code >= 0xfe00 && code <= 0xfe0f ||   // 变体选择器
           code >= 0x1f900 && code <= 0x1f9ff || // 补充符号和象形文字
           code >= 0x1f1e6 && code <= 0x1f1ff    // 区域指示符号
  }

  // 遍历归一化文本
  let j = 0
  while (j < normalizedLength) {
    const char = normalizedText.charAt(j)

    if (isSpace(char)) {
      // 空格：1 Token（已经压缩过连续空格，所以每个空格单独计算）
      totalTokens += 1
      j++
    } else if (isChineseChar(char)) {
      // 汉字：0.65 Token（取中间值）
      totalTokens += 0.65
      j++
    } else if (isEnglishLetter(char)) {
      // 英文单词：需要识别连续字母作为一个单词
      let wordLength = 1
      while (j + wordLength < normalizedLength && isEnglishLetter(normalizedText.charAt(j + wordLength))) {
        wordLength++
      }
      // 每个英文单词约1.3 Token
      totalTokens += 1.3
      j += wordLength
    } else if (isDigit(char)) {
      // 数字：连续数字作为一个整体
      let digitLength = 1
      while (j + digitLength < normalizedLength && isDigit(normalizedText.charAt(j + digitLength))) {
        digitLength++
      }
      // 数字位数 × 0.8 Token
      totalTokens += digitLength * 0.8
      j += digitLength
    } else if (isPunctuation(char)) {
      // 标点符号：1 Token
      totalTokens += 1
      j++
    } else if (isEmoji(char)) {
      // 表情符号：保守估算2 Token
      totalTokens += 2
      j++
    } else {
      // 其他字符（如特殊符号、其他语言文字等）：默认0.5 Token
      totalTokens += 0.5
      j++
    }
  }

  // 返回四舍五入的整数
  return Math.round(totalTokens)
}

/**
 * 批量估算消息Token数量（不计算思考过程）
 * @param messages 消息数组，每个消息包含content和可选的thinking/reasoning字段
 * @returns 每条消息的Token数量数组和总Token数
 */
export function estimateMessageTokens(messages: Array<{content: string, reasoning?: string | null}>): {
  messageTokens: number[],
  totalTokens: number
} {
  const messageTokens: number[] = []
  let totalTokens = 0

  for (const msg of messages) {
    // 只计算content，不计算thinking/reasoning
    const tokens = estimateTokenCount(msg.content)
    messageTokens.push(tokens)
    totalTokens += tokens
  }

  return { messageTokens, totalTokens }
}

/**
 * 格式化Token数量显示（使用K单位）
 * @param tokenCount Token数量
 * @returns 格式化后的字符串，例如：42, 999, 1.2K, 2.5K, 10K
 */
export function formatTokenCount(tokenCount: number | undefined): string {
  if (tokenCount === undefined || tokenCount === null) {
    return '0'
  }

  if (tokenCount < 1000) {
    return tokenCount.toString()
  }

  // 转换为K单位，保留一位小数
  const kValue = tokenCount / 1000
  // 如果小数部分为0，则显示整数
  if (kValue % 1 === 0) {
    return `${kValue.toFixed(0)}K`
  }
  // 保留一位小数
  return `${kValue.toFixed(1)}K`
}

/* ============================ 真实用量展示（成本 / 缓存 / token） ============================ */

/**
 * 真实 token 数缩写（K / M）
 *
 * <p>与 {@link formatTokenCount} 的区别：那个是「上下文估算」口径，只到 K，
 * 而一次工具循环的真实输入动辄几十万，必须支持 M 才不出现「1234.5K」这种读不出量级的写法。</p>
 */
export function formatUsageTokens(count: number | undefined): string {
  const n = Math.max(0, Math.round(count || 0))
  if (n >= 1_000_000) return `${(n / 1_000_000).toFixed(n >= 10_000_000 ? 0 : 1)}M`
  if (n >= 1000) return `${(n / 1000).toFixed(n >= 100_000 ? 0 : 1)}K`
  return String(n)
}

/**
 * 金额展示：¥0.0123
 *
 * <p>极小值显示 {@code <¥0.0001} 而不是「¥0.0000」：单轮命中缓存的成本常在 1e-5 量级，
 * 直接按 4 位小数会全部变成 0，看起来像「没花钱 / 统计坏了」。</p>
 */
export function formatCost(cost: number | undefined): string {
  const n = Number.isFinite(cost as number) ? (cost as number) : 0
  if (n <= 0) return '¥0'
  if (n < 0.0001) return '<¥0.0001'
  if (n < 1) return `¥${n.toFixed(4)}`
  return `¥${n.toFixed(2)}`
}

/**
 * 缓存命中率展示：98%
 *
 * <p>负数（后端约定的 -1）表示 Provider 没返回缓存字段，显示「—」而不是 0%：
 * 「0%」会被读成「缓存完全没命中」，是错误结论。</p>
 */
export function formatHitRate(rate: number | undefined): string {
  if (rate === undefined || rate === null || rate < 0) return '—'
  return `${Math.round(rate * 100)}%`
}

/**
 * 该批用量数据「能不能算出金额」。
 *
 * <p>后端只在 {@code llm.pricing.models} 里配置过单价的模型才计算费用，未配置时
 * {@code cost_estimate} 落 NULL。若前端照样渲染 {@code ¥0}，会被读成「这个 Provider 免费」——
 * 错误的成本比没有成本更危险。因此按「有单价记录条数」判定，为 0 就不显示金额。</p>
 *
 * @param pricedRequestCount 有单价、可估算成本的请求条数
 * @param costEstimate       估算费用（元）
 */
export function isCostAvailable(
  pricedRequestCount: number | undefined,
  costEstimate: number | undefined
): boolean {
  if (pricedRequestCount === undefined || pricedRequestCount === null) {
    // 旧后端没有该字段：保持原有行为，避免升级瞬间所有金额消失
    return Number.isFinite(costEstimate as number)
  }
  return pricedRequestCount > 0
}