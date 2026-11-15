/**
 * Provider 能力展示辅助（v5：<b>后端结论优先，本地规则仅作离线兜底</b>）
 * ----------------------------------------------------------------------------
 * ⚠️ 职责已变更（T10）：本文件<b>不再是能力的推导者</b>。
 *
 *   真相源 = 后端 ProviderCapabilityService（GET /api/llm-providers/capabilities）。
 *   判定顺序：① 后端缓存命中 → 直接用后端 state/hint（含凭证成对判定、Coding Plan 识别）；
 *            ② 后端不可达（离线 / 接口异常 / 旧后端）→ 落到下方本地规则，保证界面不空白。
 *
 * 为什么改成这样：旧实现把同一套规则在后端 + yml + ProviderRegistry + 本文件各写一遍，
 * 「改一处、漏三处」且漏掉不报错 —— 典型表现是「界面说能发图，实际图片被静默丢弃」，
 * 以及「界面说能查余额、点下去报缺凭证」。副本从 4 份收敛为 1 份。
 *
 * 本地兜底规则（仅在后端不可达时生效，与后端实现逐条对齐）：
 *   图片识别 —— on = 平台支持且模型确定多模态；warn = 平台支持但模型未配置/不支持；
 *               off = 平台协议不支持。
 *   联网搜索 —— on = 官方接口就绪；warn = 有接口但当前配置用不了（如 DeepSeek 走中转）；
 *               off = 平台无官方接口。注意 warn/off 都不代表「不能联网」。
 *   余额查询 —— on = 可直接查；warn = 有接口但缺凭证；off = 平台无此接口。
 *
 * 重要：列表接口 GET /api/llm-providers 会把密钥字段替换成 **** 掩码，
 *      掩码表示「已配置」，绝不能当空值处理；空串才表示未配置。
 *
 * ----------------------------------------------------------------------------
 * 后端新增 LLM 适配器时，正确做法是<b>只改后端 ProviderRegistry 一处</b>；
 * 下面的本地兜底规则可同步、也可不同步（它只在接口不可用时生效，不同步不会导致界面错误，
 * 最多退化为「旧结论」）。ProviderEditDrawer 的模板下拉也已改为从后端拉取。
 * ----------------------------------------------------------------------------
 */

import {
  getAllProviderCapabilities,
  getTemplateCapabilities,
  type RemoteCapability,
  type RemoteCapabilityItem
} from '../api/provider-capability'

/** 能力状态：on=具备，warn=支持但缺条件（缺凭证 / 模型未知或非多模态 / 被中转挡住），off=不支持 */
export type CapabilityState = 'on' | 'warn' | 'off'

/**
 * 后端下发的能力字段名（用于 {@link capabilityHintOf} 精确取文案）
 */
export type CapabilityField = 'vision' | 'search' | 'balance' | 'filesApi'

export interface ProviderCapabilities {
  /** 图片识别（多模态图片输入）：模型级三态 */
  vision: CapabilityState
  /** 联网搜索（平台官方搜索接口）：on=就绪，warn=本来有但当前配置用不了，off=平台无此接口 */
  search: CapabilityState
  /** 余额查询：on=可直接查，warn=支持接口但缺凭证，off=平台无此接口 */
  balance: CapabilityState
}

/** 支持图片输入的请求模板（协议层面具备；是否真的能发图还取决于默认模型） */
const VISION_TEMPLATES = [
  'volcengine',
  'ark',
  'doubao',
  'deepseek',
  'gpt',
  'openai',
  'anthropic',
  'qwen',
  'dashscope',
  'tongyi',
  'minimax',
  'mimo',
  'ollama',
  'custom'
]

/** 火山系模板（联网搜索、余额查询都按火山方舟的凭证规则走） */
export const VOLCENGINE_TEMPLATES = ['volcengine', 'ark', 'doubao']

/** 通义千问系模板（百炼 DashScope / QwenCloud；联网搜索走 enableSearch，余额走阿里云 AK/SK） */
export const QWEN_TEMPLATES = ['qwen', 'dashscope', 'tongyi']

/** MiniMax 系模板（含区域别名；联网搜索与额度都走 codingPlanKey） */
export const MINIMAX_TEMPLATES = ['minimax', 'minimax-cn', 'minimax-intl', 'minimax-global']

/** DeepSeek 支持图像的模型关键字（flash / vision / vl 系列，对齐后端判定） */
const DEEPSEEK_VISION_MODEL = /flash|vision|-vl/

/**
 * DeepSeek 官方工具接口的域名。
 *
 * <p>后端 {@code DeepSeekToolsApiClient.resolveOfficialConfig()} 的启用条件是
 * 「Provider 有 code + Base URL 主机严格等于该域名 + API Key 有效」，
 * 满足后 `/v1/tools/search` 免额外凭证即可联网搜索；换成第三方中转域名就拿不到这个能力。</p>
 */
const DEEPSEEK_OFFICIAL_HOST = 'api.deepseek.com'

/**
 * Base URL 是否指向 DeepSeek 官方域名。
 *
 * <p>用 URL 解析取 hostname 而非字符串包含，避免 `https://proxy.example.com/api.deepseek.com`
 * 这类路径里带域名的中转地址被误判成官方。</p>
 */
export function isDeepseekOfficialHost(baseUrl?: string | null): boolean {
  const raw = String(baseUrl || '').trim()
  if (!raw) return false
  try {
    const url = new URL(/^https?:\/\//i.test(raw) ? raw : `https://${raw}`)
    return url.hostname.toLowerCase() === DEEPSEEK_OFFICIAL_HOST
  } catch {
    return false
  }
}

/**
 * 按单个标识（code 或 template）判定模型是否支持图像。
 *
 * @returns 'on' / 'warn'；返回 null 表示该标识没有专门规则（落到「宽松放行」）
 */
function visionStateOf(key: string, model: string): CapabilityState | null {
  if (!key) return null
  /*
    ⚠️ 审查修复：判定改用「精确或前缀」而不是精确 includes。

    后端视觉规则是按前缀匹配的（ModelCapabilityRules.findVisionRule 用
    code.startsWith(alias)），而这里原先全是精确比较：
      · volcengine-custom / ark-prod 会漏掉火山规则 → 落到「通用兼容类」→ 返回 on
      · 于是 deepseek-v4-pro（纯文本）被显示成可发图 —— 正是本项目反复踩的坑
    前端只在「后端不可达」时才会走到这里，但那时用户更需要准确结论。
  */
  if (matchesAnyPrefix(key, ['deepseek'])) {
    return DEEPSEEK_VISION_MODEL.test(model) ? 'on' : 'warn'
  }
  if (matchesAnyPrefix(key, VOLCENGINE_TEMPLATES)) {
    // 方舟上的 deepseek 系已分化（实测 2026-11，与后端 ModelCapabilityConfig 逐条对齐）：
    //   deepseek-v4.1-flash / v4.1-flash-economy → 支持图像（图片真实计入 prompt_tokens）
    //   deepseek-v4-pro / v4-flash               → 纯文本（图片被静默丢弃，模型自称「没有图片」）
    // 其余豆包 seed / glm-5.3-flash（原生多模态）/ kimi-k3.1-pro（多模态旗舰）等支持图像。
    if (/v4-pro|v4-flash/.test(model)) return 'warn'
    if (model.startsWith('deepseek')) return /v4\.1/.test(model) ? 'on' : 'warn'
    return 'on'
  }
  if (matchesAnyPrefix(key, ['mimo', 'xiaomi-mimo'])) {
    // v2.5 系列与 Omni（全模态）支持图像，更早纯文本模型不支持
    return /v2\.5|vision|-vl|omni/.test(model) ? 'on' : 'warn'
  }
  if (matchesAnyPrefix(key, MINIMAX_TEMPLATES)) {
    // M2 / M2.1 / M2.5 / M2.7 为纯文本；M3 与 VL / abab-vl 支持图像
    if (/-m2/.test(model) || model.startsWith('m2')) return 'warn'
    return /m3|vision|-vl|abab/.test(model) ? 'on' : 'warn'
  }
  if (matchesAnyPrefix(key, QWEN_TEMPLATES)) {
    // Qwen-VL / Qwen3-VL / Omni 支持图像；Plus 系列自 qwen3.6-plus 起官方声明支持图片理解
    // （百炼文档：「推荐模型：qwen3.7-plus（支持图片理解）、qwen3.6-plus（支持图片理解）」）。
    // qwen-max 各代差异较大，不确定时不放行，避免「界面说能发图、实际图片被丢弃」。
    return /-vl|vl-|omni|vision|plus/.test(model) ? 'on' : 'warn'
  }
  if (key === 'ollama') {
    // 本地模型的视觉能力取决于权重文件：llava / *-vl / *vl* / vision / gemma3 / minicpm-v / moondream / llama4。
    // 后端对 ollama 是宽松放行（不拦截），这里只给前端提示，不改变后端行为。
    return /llava|vision|vl|gemma3|gemma4|minicpm|moondream|llama4|pixtral/.test(model) ? 'on' : 'warn'
  }
  return null
}

/**
 * 模型级图片识别能力判定。
 *
 * <p>判定顺序：平台协议 → 模型是否已知。后端在 {@code DeepSeekServiceImpl} 里用
 * {@code providerCode} 判定、未命中规则时宽松放行，这里同时对 code 与 template 求值并取更保守的结论，
 * 避免「自定义 code（如 volc-deepseek）+ 火山模板」这类配置漏报。</p>
 *
 * @param template 请求模板（协议真相源）
 * @param code     Provider 编码（后端模型防线实际使用的判定键）
 * @param model    默认模型名
 */
export function detectVision(
  template?: string | null,
  code?: string | null,
  model?: string | null
): CapabilityState {
  const tpl = String(template || '').trim().toLowerCase()
  const codeKey = String(code || '').trim().toLowerCase()
  const modelKey = String(model || '').trim().toLowerCase()

  // 先看两侧是否有「模型级专门规则」（deepseek / 火山 / qwen / minimax / mimo / ollama）
  const states = [visionStateOf(codeKey, modelKey), visionStateOf(tpl, modelKey)].filter(
    (state): state is CapabilityState => state !== null
  )
  if (!states.length) {
    // 无专门规则：再看是否属于「通用 OpenAI 兼容类」平台（含自定义前缀模板，如 openai-compatible）
    const generic = [tpl, codeKey].some(key =>
      key ? VISION_TEMPLATES.some(t => key === t || key.startsWith(t)) : false
    )
    if (!generic) return 'off' // 平台协议不支持图片输入
    // 通用兼容类：协议支持图片，能力取决于模型；未配模型时按后端「未知即放行」记 on
    return modelKey ? 'on' : 'warn'
  }
  // 平台支持图片但未配默认模型：后端宽松放行，前端提示「取决于模型」
  if (!modelKey) return 'warn'
  return states.includes('warn') ? 'warn' : 'on'
}

/** 解析 extraConfig（JSON 字符串）；非法 JSON 或非对象时返回空对象 */
export function parseExtraConfig(text?: string | null): Record<string, unknown> {
  if (!text) return {}
  try {
    const parsed = JSON.parse(text)
    return parsed && typeof parsed === 'object' ? (parsed as Record<string, unknown>) : {}
  } catch {
    return {}
  }
}

/**
 * 判断扩展配置里是否提供了某个凭证。
 * 字符串：非空即视为已提供（**** 为后端掩码，同样代表已配置）；布尔/数字：真值即视为开启。
 */
export function hasExtraValue(extra: Record<string, unknown>, keys: string[]): boolean {
  return keys.some(key => {
    const value = extra[key]
    if (typeof value === 'string') return value.trim() !== ''
    return Boolean(value)
  })
}

// ==================== 后端能力缓存（T10 核心） ====================

/**
 * 实例级档案（<b>带凭证判定</b>）：key = providerCode。
 *
 * <p>来自 {@code GET /api/llm-providers/capabilities}，state 已结合该实例的真实凭证算出，
 * 因此可能出现「火山 search=warn（缺 searchApiKey）」这类因人而异的结论。</p>
 */
const providerCapabilityCache = new Map<string, RemoteCapability>()

/**
 * 模板级档案（<b>不带凭证判定</b>）：key = template。
 *
 * <p>来自 {@code GET /api/llm-providers/templates}，state 表达的是「平台具备什么」，
 * 缺凭证时统一记 warn（含义是「配好就能用」），不含任何实例配置信息。</p>
 */
const templateCapabilityCache = new Map<string, RemoteCapability>()

/** 缓存是否已预热（未预热时 deriveCapabilities 会静默走本地兜底，不报错） */
let cachePrimed = false

/**
 * 把后端能力档案写入缓存。
 *
 * <h3>⚠️ 为什么必须分「实例级」与「模板级」两层（修复一个真实 bug）</h3>
 * <p>
 * 最初把两者塞进同一个 Map，实例档案同时按 {@code code} 与 {@code template} 建索引。
 * 后果是：<b>同一模板下有多个 Provider 时，template 键会被最后写入的那个实例覆盖</b>，
 * 而「新建 Provider」场景恰恰只拿得到 template（code 还没填）——
 * 于是预览区显示的是<b>别人那台实例的凭证状态</b>，例如：
 * </p>
 * <ul>
 *   <li>A 已配好火山的 AK/SK，B 没配；</li>
 *   <li>新建时选「火山方舟」模板 → 按 template 命中 B 的档案 → 显示「缺 secretKey」；</li>
 *   <li>正确答案应是「平台支持余额查询，配好 AK/SK 即可用」。</li>
 * </ul>
 * <p>
 * 分两层后职责互不越界：<b>实例级只按 code 索引，模板级只按 template 索引</b>，
 * 按 template 查永远拿不到实例数据，串台在结构上不可能发生。
 * </p>
 */
export function primeCapabilities(
  data: Record<string, RemoteCapability> | RemoteCapability[] | null | undefined
): void {
  if (!data) return
  const items: RemoteCapability[] = Array.isArray(data)
    ? data
    : Object.values(data)
  for (const item of items) {
    if (!item) continue
    const code = item.providerCode ? String(item.providerCode).trim().toLowerCase() : ''
    if (code) {
      // 有 providerCode = 实例级档案，只按 code 索引
      providerCapabilityCache.set(code, item)
      continue
    }
    const template = item.template ? String(item.template).trim().toLowerCase() : ''
    if (template) {
      // 无 providerCode = 模板级档案，只按 template 索引
      templateCapabilityCache.set(template, item)
    }
  }
  if (items.length > 0) {
    cachePrimed = true
  }
}

/**
 * 拉取并预热能力缓存（App 加载 Provider 列表后调用）。
 *
 * <h3>⚠️ 为什么两个请求分别 try（修复一个真实 bug）</h3>
 * <p>
 * 曾用 {@code Promise.all} 一次性 await：只要其中一个失败（例如旧后端没有
 * {@code /templates} 接口），另一个<b>成功的数据也进不了缓存</b> —— 整体退化为本地兜底，
 * 用户看到的全是旧结论。两个接口互相独立，必须各自容错。
 * </p>
 */
export async function refreshCapabilities(): Promise<void> {
  try {
    primeCapabilities(await getTemplateCapabilities())
  } catch {
    // 旧后端可能没有 /templates：静默，稍后按需再拉
  }
  try {
    primeCapabilities(await getAllProviderCapabilities())
  } catch {
    // 离线 / 未登录 / 接口异常：静默退化为本地兜底
  }
}

/** 缓存是否已就绪（可用于诊断「为什么界面显示的是旧结论」） */
export function isCapabilityCachePrimed(): boolean {
  return cachePrimed
}

/**
 * 按 code 或 template 取后端能力档案（未命中返回 null）。
 *
 * <p>查找顺序：<b>实例级精确 code</b> → <b>模板级精确 template</b> →
 * 实例级前缀 → 模板级前缀（兼容 {@code volcengine-custom} 这类自定义 code，
 * 与后端 {@code ProviderRegistry.findByCode} 的前缀语义一致）。</p>
 */
export function capabilityDetailOf(key?: string | null): RemoteCapability | null {
  const text = String(key || '').trim().toLowerCase()
  if (!text) return null
  // ① 实例级精确（带凭证判定，最具体）
  const instance = providerCapabilityCache.get(text)
  if (instance) return instance
  // ② 模板级精确
  const template = templateCapabilityCache.get(text)
  if (template) return template
  // ③ 前缀匹配：实例级优先（具体优先于笼统）
  for (const [cacheKey, item] of providerCapabilityCache) {
    if (cacheKey.length > 0 && text.startsWith(cacheKey)) {
      return item
    }
  }
  for (const [cacheKey, item] of templateCapabilityCache) {
    if (cacheKey.length > 0 && text.startsWith(cacheKey)) {
      return item
    }
  }
  return null
}

/**
 * 取某个能力的后端文案（hint）。
 *
 * <p>用在后端已给出精确结论的地方：如「缺 secretKey」「火山按 /api/coding 走次数套餐」，
 * 这类信息前端推导不出来。返回 {@code null} 表示后端没数据，调用方应回落到本地文案。</p>
 */
export function capabilityHintOf(
  key: string | undefined | null,
  field: CapabilityField
): string | null {
  const detail = capabilityDetailOf(key)
  if (!detail) return null
  const item: RemoteCapabilityItem | undefined = detail[field]
  const hint = item?.hint
  return hint && hint.trim() ? hint : null
}

/** 取某个能力的徽章短文案（后端已按平台差异定制过，如「联网搜索·通用抓取」） */
export function capabilityLabelOf(
  key: string | undefined | null,
  field: CapabilityField
): string | null {
  const detail = capabilityDetailOf(key)
  if (!detail) return null
  const label = detail[field]?.label
  return label && label.trim() ? label : null
}

/** 取某个能力缺失的凭证字段名（仅后端能精确知道，如 ['secretKey']） */
export function capabilityMissingKeys(
  key: string | undefined | null,
  field: CapabilityField
): string[] {
  const detail = capabilityDetailOf(key)
  return detail?.[field]?.missingKeys ?? []
}

/** 取后端判定的计费模式（QUOTA=套餐次数制不估价 / FREE=本地免费 / TOKEN=按 token 估价） */
export function pricingModeOf(key?: string | null): 'TOKEN' | 'QUOTA' | 'FREE' | null {
  return capabilityDetailOf(key)?.pricingMode ?? null
}

/**
 * 规整后端下发的 state 字符串。
 *
 * <p>后端枚举只有 on/warn/off 三个值，但接口可能被中继改写或返回旧版本字段，
 * 因此这里做一次防御：未知值一律按 {@code off}（保守，不谎称有能力）。</p>
 */
function normalizeState(value?: string | null): CapabilityState {
  if (value === 'on' || value === 'warn' || value === 'off') return value
  return 'off'
}

/**
 * 按「请求模板 + 默认模型 + 扩展配置 + Base URL」推导能力。
 *
 * <p><b>优先用后端结论</b>（按 code 查，再按 template 查）；两者都没有时落到本地规则兜底。</p>
 *
 * @param template   请求模板（后端 requestTemplate，空则回退 code）
 * @param code       Provider 编码（后端 providerCode）
 * @param extraConfig 扩展配置 JSON 字符串（密钥字段为 **** 掩码）
 * @param model      默认模型
 * @param baseUrl    Base URL，DeepSeek 官方搜索接口按它判域名
 */
export function deriveCapabilities(
  template?: string | null,
  code?: string | null,
  extraConfig?: string | null,
  model?: string | null,
  baseUrl?: string | null
): ProviderCapabilities {
  /*
    ① 后端结论优先：后端读的是 DB 原始配置，能判定「AK/SK 是否成对」「baseUrl 是否 /api/coding」
    这类前端推导不出来的信息，也会与后端真正的拦截逻辑保持一致。
    按 code 查（实例级，含凭证判定）→ 再按 template 查（模板级，新建预览）。
  */
  const remote = capabilityDetailOf(code) ?? capabilityDetailOf(template)
  if (remote) {
    return {
      vision: normalizeState(remote.vision?.state),
      search: normalizeState(remote.search?.state),
      balance: normalizeState(remote.balance?.state)
    }
  }

  /*
    ② 后端不可达时的本地兜底规则。

    ⚠️ 审查修复：这段代码自称「与后端实现逐条对齐」，实际有 4 处偏差，
    会把用户引向错误结论（虽然只在后端不可达时生效，但那时用户更需要准确信息）。
    逐条对齐后，降级态与在线态给出的判断才一致。

    ⚠️ 兜底规则的存在意义仅限于「后端没响应」，不要在这里扩展新逻辑 ——
    真正的规则真相源是后端 ProviderRegistry + ProviderCapabilityService。
  */
  const tpl = String(template || code || '').trim().toLowerCase()
  const codeKey = String(code || '').trim().toLowerCase()
  const extra = parseExtraConfig(extraConfig)

  const vision = detectVision(template, code, model)

  let search: CapabilityState = 'off'
  if (matchesAnyPrefix(tpl, VOLCENGINE_TEMPLATES)) {
    /*
      联网搜索控制在独立的搜索控制台，Key 与方舟 Ark Key 不通用。
      ⚠️ 修正：缺 Key 应为 warn 而不是 off。
      off 的语义是「平台没有这个接口」，而火山明明有接口、只是没配 Key；
      显示 off 会让用户以为「火山不能联网搜索」从而放弃配置。
    */
    search = hasExtraValue(extra, ['searchApiKey']) ? 'on' : 'warn'
  } else if (matchesAnyPrefix(tpl, QWEN_TEMPLATES)) {
    search = extra.enableSearch === true || hasExtraValue(extra, ['searchApiKey']) ? 'on' : 'warn'
  } else if (matchesAnyPrefix(tpl, MINIMAX_TEMPLATES)) {
    search = hasExtraValue(extra, ['codingPlanKey', 'coding_plan_key', 'searchApiKey']) ? 'on' : 'warn'
  } else if (matchesAnyPrefix(tpl, ['deepseek']) || matchesAnyPrefix(codeKey, ['deepseek'])) {
    // DeepSeek 官方 /v1/tools/search 免额外凭证，但要求 Base URL 主机是 api.deepseek.com。
    // 走第三方中转时官方接口拿不到，只剩通用抓取 —— 这正是原来把 deepseek 一律判成「不支持」的漏判点。
    search = isDeepseekOfficialHost(baseUrl) ? 'on' : 'warn'
  }

  let balance: CapabilityState = 'off'
  if (matchesAnyPrefix(tpl, ['deepseek']) || matchesAnyPrefix(codeKey, ['deepseek'])) {
    // DeepSeek 余额接口只需 API Key，无需额外凭证
    balance = 'on'
  } else if (matchesAnyPrefix(tpl, VOLCENGINE_TEMPLATES) || matchesAnyPrefix(tpl, QWEN_TEMPLATES)) {
    /*
      火山费用中心 / 阿里云账户余额：需要账号级 AK + SK（与模型 API Key 是两套凭证）。

      ⚠️ 修正：必须「成对」判定，不能用 hasExtraValue（任一存在即真）。
      只配了 accessKey 时后端查询必然失败，而前端显示 on（可查询）——
      用户点下去只会看到报错，且不知道该补 secretKey。后端 ProviderCapabilityService
      已改为成对校验并精确指出 missingKeys，这里对齐。
    */
    balance = hasPairedCredentials(extra, 'accessKey', 'secretKey') ? 'on' : 'warn'
  } else if (matchesAnyPrefix(tpl, MINIMAX_TEMPLATES)) {
    balance = hasExtraValue(extra, ['codingPlanKey', 'coding_plan_key']) ? 'on' : 'warn'
  } else if (matchesAnyPrefix(tpl, ['mimo', 'xiaomi-mimo']) || matchesAnyPrefix(codeKey, ['mimo', 'xiaomi-mimo'])) {
    balance = hasExtraValue(extra, ['mimoCookie']) ? 'on' : 'warn'
  }

  return { vision, search, balance }
}

/** 文本是否命中文案列表中的任一项（精确或前缀；与后端 ProviderRegistry 的前缀语义一致） */
function matchesAnyPrefix(text: string, prefixes: readonly string[]): boolean {
  if (!text) return false
  return prefixes.some(prefix => text === prefix || text.startsWith(prefix))
}

/**
 * 扩展配置里是否「成对提供了两个凭证」（键名大小写不敏感，值非空即视为已提供）。
 *
 * <p>对应后端 {@code ProviderCapabilityService} 的成对校验：只配一半时必须判 warn，
 * 否则界面显示「可查询」而点击必然失败。</p>
 */
function hasPairedCredentials(extra: Record<string, unknown>, keyA: string, keyB: string): boolean {
  const normalized = new Map<string, unknown>()
  for (const [rawKey, value] of Object.entries(extra)) {
    normalized.set(String(rawKey).trim().toLowerCase(), value)
  }
  const present = (name: string): boolean => {
    const value = normalized.get(name.toLowerCase())
    if (typeof value === 'string') return value.trim() !== ''
    return Boolean(value)
  }
  return present(keyA) && present(keyB)
}

/** 图片识别徽章的悬浮说明（三态各自的含义） */
export function visionHint(state: CapabilityState, model?: string | null): string {
  const name = String(model || '').trim()
  if (state === 'on') return `当前默认模型${name ? '（' + name + '）' : ''}支持图片输入`
  if (state === 'warn') {
    return name
      ? `平台支持图片，但默认模型「${name}」不支持图像理解，发图前请切换多模态模型`
      : '平台支持图片，但未配置默认模型，无法判断是否可发图'
  }
  return '该平台协议不支持图片输入'
}

/** 联网搜索徽章文案：warn 时补一句「只有通用抓取」，避免被读成「完全不能联网」 */
export function searchLabel(state: CapabilityState): string {
  return state === 'warn' ? '联网搜索·通用抓取' : '联网搜索'
}

/**
 * 联网搜索徽章的悬浮说明（三态各自的含义）。
 *
 * @param state    deriveCapabilities 推导出的 search 状态
 * @param opts.template / opts.code / opts.baseUrl 用于把 on 的成因说清楚
 */
export function searchHint(
  state: CapabilityState,
  opts: { template?: string | null; code?: string | null; baseUrl?: string | null } = {}
): string {
  const tpl = String(opts.template || opts.code || '').trim().toLowerCase()
  const codeKey = String(opts.code || '').trim().toLowerCase()
  const baseUrl = String(opts.baseUrl || '').trim()
  const isDeepseek = matchesAnyPrefix(codeKey, ['deepseek']) || matchesAnyPrefix(tpl, ['deepseek'])
  const isVolcengine = matchesAnyPrefix(tpl, VOLCENGINE_TEMPLATES)
  const isQwen = matchesAnyPrefix(tpl, QWEN_TEMPLATES)
  const isMiniMax = matchesAnyPrefix(tpl, MINIMAX_TEMPLATES)

  if (state === 'on') {
    if (isDeepseek) {
      return `Base URL 指向 ${DEEPSEEK_OFFICIAL_HOST}，DeepSeek 官方搜索接口免额外凭证可用`
    }
    if (isVolcengine) return '火山方舟搜索服务已配置（extraConfig.searchApiKey）'
    if (isQwen) return '通义千问联网搜索已开启（extraConfig.enableSearch 或 searchApiKey）'
    if (isMiniMax) return 'MiniMax 联网搜索已配置（extraConfig.codingPlanKey）'
    return '平台官方联网搜索接口已就绪'
  }

  /*
    ⚠️ 审查修复：warn 分支必须按平台给文案。

    旧实现无条件返回「当前 Base URL 不是 api.deepseek.com，DeepSeek 官方搜索接口不可用」——
    那是「只有 DeepSeek 会判 warn」时代的假设。现在火山缺 searchApiKey、千问未开 enableSearch、
    MiniMax 缺 codingPlanKey 也都是 warn，火山用户会莫名看到一段 DeepSeek 的说明，
    完全对不上自己的配置。每类平台各说各的缺失项与解决办法。
  */
  if (state === 'warn') {
    if (isVolcengine) {
      return '未配置火山方舟联网搜索 Key（extraConfig.searchApiKey，需在联网搜索控制台单独申请，'
        + '与方舟 Ark API Key 不通用）。未配置时联网靠通用抓取（Bing / 百度 / Jina / 本地），仍可正常使用'
    }
    if (isQwen) {
      return '通义千问联网搜索未开启，在扩展配置设置 {"enableSearch":true} 即可用；'
        + '未开启时联网靠通用抓取（Bing / 百度 / Jina / 本地），仍可正常使用'
    }
    if (isMiniMax) {
      return '未配置 MiniMax Coding Plan Key（extraConfig.codingPlanKey）；'
        + '未配置时联网靠通用抓取（Bing / 百度 / Jina / 本地），仍可正常使用'
    }
    if (isDeepseek) {
      return (
        `当前 Base URL${baseUrl ? '（' + baseUrl + '）' : ''}不是 ${DEEPSEEK_OFFICIAL_HOST}，` +
        'DeepSeek 官方搜索接口不可用，联网只能靠通用抓取（Bing / 百度 / Jina / 本地）'
      )
    }
    return '官方联网搜索接口暂不可用（多为缺凭证或域名不符），联网靠通用抓取（Bing / 百度 / Jina / 本地），仍可正常使用'
  }

  return '该平台未配置官方搜索接口，联网靠通用抓取（Bing / 百度 / Jina / 本地），仍可正常使用'
}

/** 请求模板 → 中文标签（与后端 requestTemplate 取值一致） */
export function templateLabel(template?: string | null): string {
  const map: Record<string, string> = {
    deepseek: 'DeepSeek',
    volcengine: '火山方舟',
    ark: '火山方舟',
    doubao: '豆包',
    qwen: '通义千问',
    dashscope: '通义千问',
    gpt: 'GPT',
    openai: 'OpenAI 兼容',
    anthropic: 'Claude',
    minimax: 'MiniMax',
    mimo: 'MiMo',
    ollama: 'Ollama',
    custom: '自定义'
  }
  return map[String(template || '').toLowerCase()] || template || '未指定'
}

/** 平台标识块文字：取编码前两位大写（deepseek → DE） */
export function providerInitial(code?: string | null): string {
  const text = String(code || '').trim()
  return text ? text.slice(0, 2).toUpperCase() : '?'
}

/** 平台标识块配色档位（与 app.css 的 .is-* 修饰类对应） */
export function providerIconTone(template?: string | null): string {
  const tpl = String(template || '').toLowerCase()
  if (VOLCENGINE_TEMPLATES.includes(tpl)) return 'is-volcengine'
  if (tpl === 'openai' || tpl === 'gpt') return 'is-openai'
  if (tpl === 'anthropic') return 'is-anthropic'
  if (tpl === 'ollama') return 'is-local'
  return ''
}
