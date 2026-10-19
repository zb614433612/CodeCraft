/**
 * Provider 能力推导与展示辅助（v4）
 * ----------------------------------------------------------------------------
 * 后端 ProviderConfig 没有 capability 字段，页面上的「图片识别 / 联网搜索 / 余额查询」
 * 全部由「请求模板 requestTemplate + 默认模型 defaultModel + 扩展配置 extraConfig + Base URL」
 * 在前端推导，规则与后端实现逐条对齐：
 *
 *   图片识别 —— 三态，判定以「模型级」为准（对齐后端 FileVisionService.supportsVisionModel）：
 *               on   = 平台协议支持图片输入，且当前默认模型确定是多模态模型；
 *               warn = 平台支持，但默认模型未配置或不支持图像（如 deepseek-chat / qwen-plus / MiniMax-M2）；
 *               off  = 平台协议不支持图片输入（当前所有已适配模板都支持，保留兜底）。
 *               各平台协议差异由后端适配器抹平：火山把 detail=auto 归一为 extraConfig.imageDetail
 *               （缺省 high）；Anthropic 转 image/source；Qwen / MiniMax / MiMo 剔除未声明的 detail；
 *               Ollama 把 content 数组转 messages[].images[]（纯 base64）；OpenAI 公共契约原样透传。
 *   联网搜索 —— 三态，判定「是否已具备平台官方搜索接口」：
 *               on   = 官方接口就绪。火山需 extraConfig.searchApiKey（或环境变量）；
 *                      千问走对话级 enableSearch；MiniMax 用 codingPlanKey（兼容 searchApiKey 别名）；
 *                      DeepSeek 免额外凭证，但要求 Base URL 主机是 api.deepseek.com；
 *               warn = 平台本来有官方搜索接口，但当前配置用不了（如 DeepSeek 走了第三方中转），
 *                      联网只剩通用抓取（Bing / 百度 / Jina / 本地抓取）；
 *               off  = 该平台没有官方搜索接口，同样只能靠通用抓取。
 *               注意：warn / off 都不代表「不能联网」，只是拿不到平台侧的搜索结果。
 *   余额查询 —— ProviderBalanceService 支持：deepseek（官方 /user/balance，免额外凭证）、
 *               volcengine（费用中心 QueryBalanceAcct，需账号级 accessKey + secretKey）、
 *               qwen（阿里云账户余额，需 AK + SK）、minimax（Token Plan 额度，需 codingPlanKey）、
 *               mimo（需 mimoCookie）。
 *
 * 重要：列表接口 GET /api/llm-providers 会把密钥类字段替换成 **** 掩码，
 *      掩码表示「已配置」，绝不能当空值处理；空串才表示未配置。
 */

/** 能力状态：on=具备，warn=支持但缺条件（缺凭证 / 模型未知或非多模态 / 被中转挡住），off=不支持 */
export type CapabilityState = 'on' | 'warn' | 'off'

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
  if (key === 'deepseek') {
    return DEEPSEEK_VISION_MODEL.test(model) ? 'on' : 'warn'
  }
  if (VOLCENGINE_TEMPLATES.includes(key)) {
    // 方舟上的 deepseek 系是纯文本；豆包 seed 系列与 vision / vl / vlm 命名支持图像
    if (model.startsWith('deepseek')) return 'warn'
    return 'on'
  }
  if (key === 'mimo' || key === 'xiaomi-mimo') {
    return /v2\.5|vision|-vl/.test(model) ? 'on' : 'warn'
  }
  if (key === 'minimax' || key.startsWith('minimax')) {
    // M2 / M2.1 / M2.5 / M2.7 为纯文本；M3 与 VL / abab-vl 支持图像
    if (/-m2/.test(model) || model.startsWith('m2')) return 'warn'
    return /m3|vision|-vl|abab/.test(model) ? 'on' : 'warn'
  }
  if (key === 'qwen' || key === 'dashscope' || key === 'tongyi') {
    // Qwen-VL / Qwen3-VL / Omni 支持图像；qwen-plus / qwen-max 等纯文本不支持
    return /-vl|vl-|omni|vision/.test(model) ? 'on' : 'warn'
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

/**
 * 按「请求模板 + 默认模型 + 扩展配置 + Base URL」推导能力。
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
  const tpl = String(template || code || '').toLowerCase()
  const codeKey = String(code || '').trim().toLowerCase()
  const extra = parseExtraConfig(extraConfig)

  const vision = detectVision(template, code, model)

  let search: CapabilityState = 'off'
  if (VOLCENGINE_TEMPLATES.includes(tpl)) {
    // 联网搜索控制在独立的搜索控制台，Key 与方舟 Ark Key 不通用
    search = hasExtraValue(extra, ['searchApiKey']) ? 'on' : 'off'
  } else if (tpl === 'qwen' || tpl === 'dashscope' || tpl === 'tongyi') {
    search = extra.enableSearch === true || hasExtraValue(extra, ['searchApiKey']) ? 'on' : 'off'
  } else if (tpl === 'minimax' || tpl.startsWith('minimax')) {
    search = hasExtraValue(extra, ['codingPlanKey', 'coding_plan_key', 'searchApiKey']) ? 'on' : 'off'
  } else if (codeKey === 'deepseek' || tpl === 'deepseek') {
    // DeepSeek 官方 /v1/tools/search 免额外凭证，但要求 Base URL 主机是 api.deepseek.com。
    // 走第三方中转时官方接口拿不到，只剩通用抓取 —— 这正是原来把 deepseek 一律判成「不支持」的漏判点。
    search = isDeepseekOfficialHost(baseUrl) ? 'on' : 'warn'
  }

  let balance: CapabilityState = 'off'
  if (tpl === 'deepseek' || code === 'deepseek') {
    // DeepSeek 余额接口只需 API Key，无需额外凭证
    balance = 'on'
  } else if (VOLCENGINE_TEMPLATES.includes(tpl) || tpl === 'qwen' || tpl === 'dashscope') {
    // 火山费用中心 / 阿里云账户余额：需要账号级 AK + SK（与模型 API Key 是两套凭证）
    balance = hasExtraValue(extra, ['accessKey', 'secretKey']) ? 'on' : 'warn'
  } else if (tpl === 'minimax' || tpl.startsWith('minimax')) {
    balance = hasExtraValue(extra, ['codingPlanKey', 'coding_plan_key']) ? 'on' : 'warn'
  } else if (tpl === 'mimo' || code === 'mimo') {
    balance = hasExtraValue(extra, ['mimoCookie']) ? 'on' : 'warn'
  }

  return { vision, search, balance }
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
  const tpl = String(opts.template || opts.code || '').toLowerCase()
  const codeKey = String(opts.code || '').trim().toLowerCase()
  const baseUrl = String(opts.baseUrl || '').trim()

  if (state === 'on') {
    if (codeKey === 'deepseek' || tpl === 'deepseek') {
      return `Base URL 指向 ${DEEPSEEK_OFFICIAL_HOST}，DeepSeek 官方搜索接口免额外凭证可用`
    }
    if (VOLCENGINE_TEMPLATES.includes(tpl)) return '火山方舟搜索服务已配置（extraConfig.searchApiKey）'
    if (tpl === 'qwen' || tpl === 'dashscope' || tpl === 'tongyi') {
      return '通义千问联网搜索已开启（extraConfig.enableSearch 或 searchApiKey）'
    }
    if (tpl === 'minimax' || tpl.startsWith('minimax')) {
      return 'MiniMax 联网搜索已配置（extraConfig.codingPlanKey）'
    }
    return '平台官方联网搜索接口已就绪'
  }

  if (state === 'warn') {
    return (
      `当前 Base URL${baseUrl ? '（' + baseUrl + '）' : ''}不是 ${DEEPSEEK_OFFICIAL_HOST}，` +
      'DeepSeek 官方搜索接口不可用，联网只能靠通用抓取（Bing / 百度 / Jina / 本地）'
    )
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
