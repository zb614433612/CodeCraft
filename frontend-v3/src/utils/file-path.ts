/**
 * 本地文件路径工具：消息里的路径链接 → 可真正读取的绝对路径。
 * ----------------------------------------------------------------------------
 * 背景（对齐旧版 frontend/views/CodeAssistantView.vue 的做法）：
 *   1) 后端 `/api/project/read` 对**相对路径**是用后端进程的 user.dir 拼接的，
 *      Electron 桌面端 user.dir 是 jar 所在目录，几乎一定读不到项目文件；
 *      所以必须在前端先拼成绝对路径再请求。
 *   2) LLM 输出的路径经常省略前缀（`api/appearance.ts`、`src/utils/x.ts`），
 *      拼出来的绝对路径不一定真实存在 → 交给后端 `/project/resolve` 按路径后缀
 *      在项目内匹配，命中后用候选重新组装。
 *   3) Windows 路径分隔符混用（`C:\a/b`）会让「同一文件」出现多个 key，
 *      统一转正斜杠后再拼接与比较。
 *
 * 旧版只有 `openLocalFileTab` 一条链路，v3 有「工作区标签」和「右侧抽屉预览」
 * 两条链路；两份实现各写一遍必然漂移，故收敛到本文件。
 */

import { resolveProjectFilePath } from '../api/project'

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

/** 路径解析结果 */
export interface ResolvedFileTarget {
  /** 消息里原始的路径（用于错误提示，保留用户/AI 看到的形态） */
  raw: string
  /** 可直接交给 /project/read、/project/raw 的路径（相对路径会拼上工作目录） */
  path: string
  /** 是否确定能找到该文件（绝对路径恒为 true；相对路径以后端候选为准） */
  found: boolean
  /** 后端按后缀匹配出的候选（相对路径，精确匹配在前） */
  candidates: string[]
  /** 是否调用了 /project/resolve 兜底（用于区分「路径本身不存在」与「解析服务异常」） */
  viaResolve: boolean
}

/**
 * 解析消息里的路径。
 *
 * - 绝对路径：规范化后直接返回（不再请求后端，点击要即时响应）；
 * - 相对路径：调 `/project/resolve` 按后缀匹配，命中则用第一候选拼绝对路径；
 * - 没有工作目录：原样返回并标记 found=false，调用方据此给出「请先设置工作目录」类提示，
 *   而不是向后端发一个必然失败的请求。
 */
export async function resolveFileTarget(rawPath: string, root: string): Promise<ResolvedFileTarget> {
  const raw = normalizeFsPath(rawPath).trim()
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
    /* 解析接口异常：退化为「拼好后直接尝试读取」，由调用方决定是否提示 */
  }

  return { raw, path: joinFsPath(base, raw), found: false, candidates: [], viaResolve: true }
}

/** 找不到文件时的统一提示（含工作目录与候选，便于用户自查） */
export function describeMissingFile(rawPath: string, root: string, candidates: string[] = []): string {
  const lines = [`未找到该文件：${rawPath}`]
  lines.push(`当前工作目录：${root || '未设置（可在文件树里选择项目目录）'}`)
  if (candidates.length > 1) {
    lines.push('项目内匹配到多个文件，请改用更完整的路径：')
    for (const c of candidates.slice(0, 5)) lines.push(`  · ${c}`)
  } else if (candidates.length === 1) {
    lines.push(`项目内候选：${candidates[0]}`)
  }
  return lines.join('\n')
}
