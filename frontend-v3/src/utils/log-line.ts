/**
 * 日志行解析（v3）
 * ----------------------------------------------------------------------------
 * 真实日志有两种常见 pattern（同一份 app.log 里可能混排，取决于 logback 配置）：
 *   A) Spring Boot 默认：`2026-10-18 09:00:01.118  INFO 20413 --- [main] c.e.a.App : Started`
 *   B) logback 简版　　：`2026-10-18 21:15:33.887 [http-nio-8084-exec-3] INFO  com.example.App - 生成随机码`
 *
 * v2 的判定是「时间戳之后的第一个 token 为级别」，在 B 格式下第一个 token 是 `[...]` 线程名，
 * 导致级别恒为空 —— 彩色标记、级别筛选、错误统计全部失效。v3 改为在时间戳后的头部片段内
 * 查找「前后均为分隔符」的级别词，两种格式都能命中。
 *
 * 与后端对齐：`LogService.search` 用的是 `line.contains(" LEVEL ")` 之类的宽松判定，
 * 因此后端的 `level` 过滤对 B 格式同样有效，前端只需把展示与本地筛选口径补齐即可。
 *
 * 判定规则：
 *   1) 只扫描时间戳后前 {@link HEAD_LIMIT} 个字符，避免正文里出现 "ERROR" 之类被误判；
 *   2) 级别词前后必须是行首 / 空白 / `]`，避免 logger 名（ErrorCodeHandler）被当成级别；
 *   3) 无时间戳也无级别的行视为「续行」（典型是异常栈帧，保留原始缩进）。
 */

export type LogLevel = 'ERROR' | 'WARN' | 'INFO' | 'DEBUG' | 'TRACE' | ''

export interface LogLinePart {
  /** 行首时间戳（yyyy-MM-dd HH:mm:ss[.SSS]），无则为空 */
  ts: string
  /** 日志级别，无法识别为空 */
  level: LogLevel
  /** 去掉时间戳与级别后的正文（续行保留原始缩进） */
  msg: string
  /** 是否为续行（无时间戳且无级别，例如异常栈帧） */
  continuation: boolean
}

/** 行首时间戳：yyyy-MM-dd HH:mm:ss[.SSS] */
const TS_RE = /^(\d{4}-\d{2}-\d{2}[ T]\d{2}:\d{2}:\d{2}(?:[.,]\d{1,3})?)\s+/

const LEVELS: LogLevel[] = ['ERROR', 'WARN', 'INFO', 'DEBUG', 'TRACE']

/** 级别词搜索窗口（字符数） */
const HEAD_LIMIT = 100

/**
 * 级别词：前导分隔符（行首 / 空白 / `]`）+ 级别 + 后置分隔符（空白 / `]` / 行尾）
 *
 * <p>前导加 `]` 是为了命中 B 格式的 `[thread] INFO`；不加 `[` 前导是为了避免
 * 把 `[INFO]` 这类日志框架前缀误拆。</p>
 */
const HEAD_LEVEL_RE = /(?:^|[\s\]])(TRACE|DEBUG|INFO|WARN|ERROR)(?=[\s\]]|$)/

/** 在头部片段中定位级别词（返回在原文中的下标区间） */
function findLevel(text: string): { level: LogLevel; start: number; end: number } {
  const head = text.slice(0, HEAD_LIMIT)
  const m = head.match(HEAD_LEVEL_RE)
  if (!m) return { level: '', start: -1, end: -1 }
  const level = m[1] as LogLevel
  // m[0] 含前导分隔符，级别词真实起点 = 匹配起点 + 前导分隔符长度
  const start = (m.index ?? 0) + m[0].length - m[1].length
  return { level, start, end: start + level.length }
}

/**
 * 移除级别词并拼接上下文
 *
 * <p>只压缩「移除点」两侧的空白，正文内部的连续空白原样保留
 * （表格化日志、缩进对齐都依赖这些空格）。</p>
 */
function buildMessage(text: string, start: number, end: number): string {
  if (start < 0) return text.trimEnd()
  const before = text.slice(0, start).replace(/[ \t]+$/, '')
  const after = text.slice(end).replace(/^[ \t]+/, '')
  if (!before) return after.trimEnd()
  if (!after) return before.trimEnd()
  return `${before} ${after}`
}

/** 解析单行日志 */
export function splitLogLine(text: string): LogLinePart {
  const raw = text == null ? '' : String(text)
  const tsMatch = raw.match(TS_RE)

  // 无时间戳：可能是续行（异常栈帧），也可能本身就是级别开头的行
  if (!tsMatch) {
    const hit = findLevel(raw)
    if (!hit.level) {
      return { ts: '', level: '', msg: raw, continuation: true }
    }
    return {
      ts: '',
      level: hit.level,
      msg: buildMessage(raw, hit.start, hit.end),
      continuation: false
    }
  }

  const rest = raw.slice(tsMatch[0].length)
  const hit = findLevel(rest)
  return {
    ts: tsMatch[1],
    level: hit.level,
    msg: buildMessage(rest, hit.start, hit.end),
    continuation: false
  }
}

/** 统计一组日志行的错误 / 警告数（页面统计与行内徽章同一口径） */
export function countLogLevels(lines: Array<{ text: string }>): { error: number; warn: number } {
  let error = 0
  let warn = 0
  for (const line of lines) {
    const level = splitLogLine(line.text).level
    if (level === 'ERROR') error += 1
    else if (level === 'WARN') warn += 1
  }
  return { error, warn }
}

/** 级别筛选项（与后端 LEVEL_KEYWORDS 对齐；TRACE 太少见，不占工具栏位置） */
export const LOG_LEVEL_FILTERS: Array<{ label: string; value: string }> = [
  { label: '全部', value: '' },
  { label: 'ERROR', value: 'ERROR' },
  { label: 'WARN', value: 'WARN' },
  { label: 'INFO', value: 'INFO' },
  { label: 'DEBUG', value: 'DEBUG' }
]
