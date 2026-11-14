/**
 * 本地文件路径工具：消息里的路径链接 → 可真正读取的绝对路径。
 * ----------------------------------------------------------------------------
 * 背景（对齐旧版 frontend/views/CodeAssistantView.vue 的做法）：
 *   1) 后端 `/api/project/read` 对**相对路径**是用后端进程的 user.dir 拼接的，
 *      Electron 桌面端 user.dir 是 jar 所在目录，几乎一定读不到项目文件；
 *      所以必须在前端先拼成绝对路径再请求。
 *   2) LLM 输出的路径经常省略前缀（`api/appearance.ts`、`.../utils/x.ts`），
 *      拼出来的绝对路径不一定真实存在 → 先交给后端 `/project/resolve` 按路径后缀匹配，
 *      仍不命中再退到 `/project/search` 按文件名模糊查找。
 *   3) Windows 路径分隔符混用（`C:\a/b`）会让「同一文件」出现多个 key，
 *      统一转正斜杠后再拼接与比较。
 *   4) LLM 还会写「带行号」的路径（`src/App.vue:12`）或包裹引号（`"src/App.vue"`），
 *      这些都不是真实路径，必须先清洗再解析（见 {@link cleanFileToken}）。
 *
 * 旧版只有 `openLocalFileTab` 一条链路，v3 有「工作区标签」和「右侧抽屉预览」
 * 两条链路；两份实现各写一遍必然漂移，故收敛到本文件。
 */

import { resolveProjectFilePath, searchProjectFiles } from '../api/project'

/** 反斜杠统一正斜杠（拼接 / 去重 / 展示都用同一形态） */
export function normalizeFsPath(p: string): string {
  return String(p || '').replace(/\\/g, '/')
}

/** 是否绝对路径（Windows 盘符 / UNC / Unix） */
export function isAbsoluteFsPath(p: string): boolean {
  const path = String(p || '')
  return /^[a-zA-Z]:[\\/]/.test(path) || path.startsWith('/') || path.startsWith('\\\\')
}

/** 拼接根目录与相对路径（统一输出正斜杠，避免 `C:\a/b` 混用） */
export function joinFsPath(root: string, rel: string): string {
  const base = normalizeFsPath(root).replace(/\/+$/, '')
  const tail = normalizeFsPath(rel).replace(/^\/+/, '')
  return base ? `${base}/${tail}` : tail
}

/** 把路径转成绝对路径（已是绝对路径则只做规范化） */
export function absolutizePath(path: string, root: string): string {
  const normalized = normalizeFsPath(path).trim()
  if (isAbsoluteFsPath(normalized)) return normalized
  return root ? joinFsPath(root, normalized) : normalized
}

/** 取文件名（用于标签标题 / 提示） */
export function baseNameOf(path: string): string {
  const normalized = normalizeFsPath(path).replace(/\/+$/, '')
  return normalized.split('/').pop() || normalized
}

/* ============================ 路径清洗 ============================ */

/**
 * 路径末尾的标点：中文句读 + 英文标点 + 成对括号的右半边。
 * 只用于「纯文本里识别出的路径」，链接 href 本身不会带这些。
 */
const TRAILING_PUNCT_RE = /[.,;:!?、，。；：！？)）\]】》»"'`]+$/

/**
 * 行号 / 列号后缀：`src/App.vue:12`、`src/App.vue:12:5`。
 * 盘符形如 `C:`，冒号后面紧跟的是分隔符而不是数字，不会被这条规则误伤。
 */
const LINE_SUFFIX_RE = /:\d+(?::\d+)?$/

/**
 * 清洗 LLM 输出里的文件路径 token，得到「可以直接去解析」的形态。
 *
 * <p>处理项（顺序即生效顺序）：
 * <ol>
 *   <li>去首尾空白</li>
 *   <li>去包裹的引号 / 反引号 / 尖括号：`"src/App.vue"` → `src/App.vue`</li>
 *   <li>去 `file://` 前缀</li>
 *   <li>反斜杠转正斜杠</li>
 *   <li>去行号 / 列号后缀：`src/App.vue:12` → `src/App.vue`</li>
 *   <li>去省略号前缀：`.../src/App.vue`、`…/src/App.vue` → `src/App.vue`</li>
 *   <li>去尾部标点：`src/App.vue。` → `src/App.vue`</li>
 * </ol>
 *
 * <p>注意：**不**展开 `~`（渲染进程不知道用户主目录），带 `~` 的路径会解析失败，
 * 由调用方给出「路径不在工作目录 / 请改用完整路径」的提示。</p>
 */
export function cleanFileToken(raw: string): string {
  let p = String(raw || '').trim()
  if (!p) return ''
  p = p.replace(/^[`'"<]+/, '').replace(/[`'">]+$/, '')
  p = p.replace(/^file:\/\//i, '')
  p = p.replace(/\\/g, '/')
  p = p.replace(LINE_SUFFIX_RE, '')
  p = p.replace(/^(?:\.{2,}|…)\/+/, '')
  p = p.replace(TRAILING_PUNCT_RE, '')
  return p.trim()
}

/* ============================ 路径解析 ============================ */

/** 路径解析结果 */
export interface ResolvedFileTarget {
  /** 消息里原始的路径（用于错误提示，保留用户/AI 看到的形态） */
  raw: string
  /** 可直接交给 /project/read、/project/raw 的路径（相对路径会拼上工作目录） */
  path: string
  /** 是否确定能找到该文件（绝对路径恒为 true；相对路径以后端候选为准） */
  found: boolean
  /** 后端匹配出的候选（相对路径，精确匹配在前） */
  candidates: string[]
  /** 是否调用了后端解析（用于区分「路径本身不存在」与「解析服务异常」） */
  viaResolve: boolean
}

/**
 * 按文件名在项目内搜索候选（路径解析失败时的兜底）。
 *
 * <p>只取原路径的最后一段作为关键字：LLM 写 `.../utils/markdown.ts` 或
 * `极长省略/api/appearance.ts` 时，按文件名找比按整段路径后缀匹配成功率高得多。</p>
 *
 * @param rawPath 原始路径（内部会先清洗）
 * @param root    项目根目录
 * @param limit   候选数量上限
 */
export async function searchProjectFileCandidates(
  rawPath: string,
  root: string,
  limit = 20
): Promise<string[]> {
  const base = normalizeFsPath(root || '').trim().replace(/\/+$/, '')
  const token = cleanFileToken(rawPath)
  if (!base || !token) return []
  const name = baseNameOf(token)
  const keyword = name || token
  try {
    const res = await searchProjectFiles(base, keyword, limit)
    return (res?.data || []).filter((c): c is string => typeof c === 'string' && !!c)
  } catch {
    // 搜索接口异常不应阻断「直接用原路径试一试」的主流程
    return []
  }
}

/**
 * 解析消息里的路径。
 *
 * <p>解析顺序（越靠前越精确，逐级降级）：
 * <ol>
 *   <li>清洗 token（去行号 / 引号 / 省略号前缀 / 尾部标点）</li>
 *   <li>绝对路径：规范化后直接返回（不在工作目录也能打开，由后端按绝对路径读）</li>
 *   <li>相对路径 → `/project/resolve` 后缀匹配（`api/x.ts` 能精确命中 `src/api/x.ts`）</li>
 *   <li>仍不命中 → `/project/search` 按文件名模糊查找（应对省略号、大小写、目录改名）</li>
 *   <li>都没有 → found=false，并回传已找到的候选供调用方展示</li>
 * </ol>
 *
 * <p>没有工作目录时原样返回并标记 found=false，调用方据此给出「请先设置工作目录」类提示，
 * 而不是向后端发一个必然失败的请求。</p>
 */
export async function resolveFileTarget(rawPath: string, root: string): Promise<ResolvedFileTarget> {
  const raw = cleanFileToken(rawPath)
  if (!raw) return { raw: '', path: '', found: false, candidates: [], viaResolve: false }
  if (isAbsoluteFsPath(raw)) return { raw, path: raw, found: true, candidates: [], viaResolve: false }

  const base = normalizeFsPath(root || '').trim().replace(/\/+$/, '')
  if (!base) {
    return { raw, path: raw, found: false, candidates: [], viaResolve: false }
  }

  try {
    const res = await resolveProjectFilePath(base, raw)
    const candidates = (res?.data || []).filter((c): c is string => typeof c === 'string' && !!c)
    if (candidates.length) {
      return { raw, path: joinFsPath(base, candidates[0]), found: true, candidates, viaResolve: true }
    }
  } catch {
    /* 解析接口异常：继续走「按文件名搜索」兜底，最终失败也不抛给用户 */
  }

  const searched = await searchProjectFileCandidates(raw, base)
  if (searched.length) {
    return { raw, path: joinFsPath(base, searched[0]), found: true, candidates: searched, viaResolve: true }
  }

  return { raw, path: joinFsPath(base, raw), found: false, candidates: [], viaResolve: true }
}

/** 找不到文件时的统一提示（含工作目录与候选，便于用户自查） */
export function describeMissingFile(rawPath: string, root: string, candidates: string[] = []): string {
  const lines = [`未找到该文件：${cleanFileToken(rawPath) || rawPath}`]
  lines.push(`当前工作目录：${root || '未设置（可在文件树里选择项目目录）'}`)
  if (candidates.length > 1) {
    lines.push('项目内匹配到多个文件，请改用更完整的路径：')
    for (const c of candidates.slice(0, 5)) lines.push(`  · ${c}`)
  } else if (candidates.length === 1) {
    lines.push(`项目内候选：${candidates[0]}`)
  } else {
    lines.push('可能原因：路径省略了前缀 / 文件不在当前工作目录 / 文件已被移动或删除')
    lines.push('可以试试：右侧文件树搜索框按文件名查找，或用「用默认程序打开」直接查看')
  }
  return lines.join('\n')
}
