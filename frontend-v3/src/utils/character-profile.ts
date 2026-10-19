/**
 * 角色性格配置（characterProfile）
 * ----------------------------------------------------------------------------
 * 后端 `AgentConfig.characterProfile` 是一个 **JSON 字符串**，由
 * `CharacterPromptUtil.buildCharacterSection()` 解析后追加到系统提示词末尾：
 *
 *     请扮演以下角色设定：
 *       姓名: 小柔
 *       物种: 猫娘
 *       ...
 *
 * 因此字段名必须与后端 `appendCharField(sb, root, "name"|"species"|...)` 严格一致，
 * 少一个字段不会报错，但该字段的性格设定会静默丢失。
 *
 * 后端的空值语义（两处都判）：
 *   - `null` / `""` / `"{}"` → 视为「无性格」，不注入任何内容；
 *   - 只有至少一个字段非空时才生成提示词片段。
 * 故前端清空性格时必须提交 `"{}"` 而不是 `undefined` —— 后者在
 * `AgentConfigServiceImpl.update()` 里是 `if (updated.getCharacterProfile() != null)`，
 * 传 undefined 等于「不修改」，性格永远清不掉。
 */

/** 角色性格（与后端 9 个字段一一对应） */
export interface CharacterProfile {
  /** name → 姓名 */
  name?: string
  /** species → 物种 */
  species?: string
  /** gender → 性别 */
  gender?: string
  /** age → 年龄（后端 asText() 取值，数字与字符串等价，这里统一存 number） */
  age?: number | null
  /** personality → 性格 */
  personality?: string
  /** greeting → 对你的称呼 */
  greeting?: string
  /** background → 背景 */
  background?: string
  /** likes → 喜好 */
  likes?: string
  /** style → 说话风格 */
  style?: string
}

/** 字段元信息：抽屉按此渲染，避免模板里重复 9 段近乎相同的结构 */
export interface CharacterFieldMeta {
  key: keyof CharacterProfile
  label: string
  placeholder: string
  /** input=单行 / textarea=多行 / select=下拉 / number=数字 */
  type: 'input' | 'select' | 'number' | 'textarea'
  /** 下拉选项（type=select 时使用） */
  options?: Array<{ value: string; label: string }>
  /** 是否独占一整行（长文本字段） */
  wide?: boolean
  /** textarea 行数 */
  rows?: number
  maxLength?: number
}

/** 物种选项（与旧版 AgentConfigView 的取值保持一致，后端只透传字符串，不做枚举校验） */
export const CHARACTER_SPECIES_OPTIONS: Array<{ value: string; label: string }> = [
  { value: '人', label: '👤 人类' },
  { value: '猫娘', label: '🐱 猫娘' },
  { value: '狗娘', label: '🐶 狗娘' },
  { value: '精灵', label: '🧝 精灵' },
  { value: '机器人', label: '🤖 机器人' },
  { value: '自定义', label: '✨ 自定义' }
]

/** 性别选项 */
export const CHARACTER_GENDER_OPTIONS: Array<{ value: string; label: string }> = [
  { value: '男', label: '♂ 男' },
  { value: '女', label: '♀ 女' },
  { value: '无性', label: '⚪ 无性' },
  { value: '其他', label: '🌈 其他' }
]

/**
 * 字段渲染顺序
 *
 * <p>前 4 项是短字段（两列并排），后 5 项是长文本（独占一行），
 * 与后端 `buildCharacterSection` 的输出顺序一致，便于对照排查。</p>
 */
export const CHARACTER_FIELDS: CharacterFieldMeta[] = [
  { key: 'name', label: '姓名', placeholder: '如：小柔', type: 'input', maxLength: 50 },
  { key: 'species', label: '物种', placeholder: '请选择', type: 'select', options: CHARACTER_SPECIES_OPTIONS },
  { key: 'gender', label: '性别', placeholder: '请选择', type: 'select', options: CHARACTER_GENDER_OPTIONS },
  { key: 'age', label: '年龄', placeholder: '如：18', type: 'number' },
  { key: 'personality', label: '性格', placeholder: '如：温柔体贴，善解人意', type: 'textarea', wide: true, rows: 2, maxLength: 500 },
  { key: 'greeting', label: '称呼', placeholder: '用户对你的称呼，如：主人、哥哥', type: 'input', maxLength: 50 },
  { key: 'background', label: '背景', placeholder: '如：来自未来世界的 AI 助手…', type: 'textarea', wide: true, rows: 3, maxLength: 1000 },
  { key: 'likes', label: '喜好', placeholder: '如：看书、听音乐、帮助人类', type: 'input', maxLength: 200 },
  { key: 'style', label: '说话风格', placeholder: '如：说话温柔，喜欢用表情符号', type: 'textarea', wide: true, rows: 2, maxLength: 500 }
]

/** 空性格对象（新建抽屉的初始值） */
export function createEmptyCharacterProfile(): CharacterProfile {
  return {}
}

/**
 * 编辑表单用的性格状态
 *
 * <p>为什么单独一层：表单控件（尤其是 `v-model.number` 与 `<select>`）统一用
 * 「空串 = 未填写」表达，比 `undefined` / `null` 更好处理清空操作；
 * 提交前再由 `characterFormToProfile` 收敛成后端 JSON 需要的形态。</p>
 */
export interface CharacterFormState {
  name: string
  species: string
  gender: string
  /** 空串表示未填（不用 0，避免与「年龄 0」混淆） */
  age: string
  personality: string
  greeting: string
  background: string
  likes: string
  style: string
}

/** 由性格对象（可能为空）生成表单状态 */
export function createCharacterFormState(profile?: CharacterProfile): CharacterFormState {
  const p = profile || {}
  return {
    name: p.name || '',
    species: p.species || '',
    gender: p.gender || '',
    age: p.age ? String(p.age) : '',
    personality: p.personality || '',
    greeting: p.greeting || '',
    background: p.background || '',
    likes: p.likes || '',
    style: p.style || ''
  }
}

/** 表单状态 → 性格对象（枚举短字段顺手 trim） */
export function characterFormToProfile(state: CharacterFormState): CharacterProfile {
  const age = Number(state.age)
  return {
    name: state.name.trim(),
    species: state.species.trim(),
    gender: state.gender.trim(),
    age: Number.isFinite(age) && age > 0 ? age : undefined,
    personality: state.personality.trim(),
    greeting: state.greeting.trim(),
    background: state.background.trim(),
    likes: state.likes.trim(),
    style: state.style.trim()
  }
}

/**
 * 解析后端返回的 characterProfile JSON 字符串
 *
 * <p>容错策略：任何异常（null / 空串 / `"{}"` / 非法 JSON / 非对象）都返回空对象，
 * 不抛错、不打日志。原因：性格是可选装饰项，解析失败不应该阻断整个编辑抽屉的打开。</p>
 */
export function parseCharacterProfile(raw?: string | null): CharacterProfile {
  if (!raw) return {}
  const text = raw.trim()
  if (!text || text === '{}' || text === 'null') return {}
  try {
    const parsed = JSON.parse(text)
    if (!parsed || typeof parsed !== 'object' || Array.isArray(parsed)) return {}
    const source = parsed as Record<string, unknown>
    const result: CharacterProfile = {}
    for (const field of CHARACTER_FIELDS) {
      const value = source[field.key as string]
      if (value === undefined || value === null) continue
      if (field.type === 'number') {
        const num = Number(value)
        if (Number.isFinite(num) && num > 0) result.age = num
        continue
      }
      const str = String(value).trim()
      if (str) (result as Record<string, unknown>)[field.key as string] = str
    }
    return result
  } catch {
    return {}
  }
}

/**
 * 序列化性格为后端可接受的 JSON 字符串
 *
 * <p>无有效字段时返回 `"{}"` 而非空串：后端 `update()` 用 `!= null` 判定是否覆盖，
 * 空串会被写入数据库，但 `buildCharacterSection` 对空串与 `"{}"` 的处理一致（都不注入），
 * 选 `"{}"` 是为了与旧版前端的清空语义保持一致，避免历史数据出现两种「空」形态。</p>
 */
export function serializeCharacterProfile(profile: CharacterProfile): string {
  const output: Record<string, unknown> = {}
  for (const field of CHARACTER_FIELDS) {
    const value = profile[field.key]
    if (field.type === 'number') {
      const num = Number(value)
      if (Number.isFinite(num) && num > 0) output[field.key as string] = num
      continue
    }
    const str = typeof value === 'string' ? value.trim() : ''
    if (str) output[field.key as string] = str
  }
  return Object.keys(output).length ? JSON.stringify(output) : '{}'
}

/** 是否有实际性格内容（`"{}"` / 空串 / null 都算无） */
export function hasCharacterProfile(raw?: string | null): boolean {
  return Object.keys(parseCharacterProfile(raw)).length > 0
}

/**
 * 一行摘要（卡片 / 提示文案用）
 *
 * <p>只取最能体现角色的 3 项：姓名、物种、性格，避免卡片被长文本撑高。</p>
 */
export function characterSummary(profile: CharacterProfile): string {
  const parts = [profile.name, profile.species, profile.personality].filter(Boolean) as string[]
  return parts.join(' · ')
}
