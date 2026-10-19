/**
 * 内嵌网页的地址规范化（P4 · F04）
 * ----------------------------------------------------------------------------
 * 只接受 http / https：
 *   - `javascript:` 会直接执行脚本；
 *   - `file://` 能读本机磁盘（配合 Electron 更是任意文件读取）；
 *   - `data:` / `blob:` 同样可以承载脚本。
 * 这些协议在内嵌框里一律拒绝，不做「尽力兼容」。

 * 关于「协议头」的判定（踩过的坑）：
 *   最初用 `/^[a-z][a-z0-9+.-]*:\/\//` 判断「是否已带协议」，结果 `javascript:alert(1)`
 *   没有 `//`，被当成裸域名补成 `https://javascript:alert(1)` 再解析失败 ——
 *   虽然仍然拒绝了，但错误文案变成「格式不正确」，没告诉用户真正原因。
 *   现在改为「识别 scheme + 区分 host:port」：
 *     javascript:alert(1)  → scheme=javascript → 明确拒绝并说明原因
 *     example.com:8080     → scheme 后是纯数字端口 → 视为 host:port，补 https
 */

const DANGEROUS_SCHEMES = new Set([
  'javascript',
  'data',
  'file',
  'filesystem',
  'blob',
  'about',
  'chrome',
  'vbscript',
  'view-source',
  'jar',
  'mailto',
  'tel',
  'ftp',
  'ws',
  'wss'
])

/** `scheme:` 前缀（不要求 `//`，因为 javascript: / data: / mailto: 都没有） */
const SCHEME_RE = /^([a-z][a-z0-9+.-]*):/i
/** scheme 之后是「纯数字端口」 → 说明这是 host:port，不是协议 */
const PORT_RE = /^\d+(?:[/?#]|$)/

function parse(raw: string): string | null {
  try {
    const parsed = new URL(raw)
    if (parsed.protocol !== 'http:' && parsed.protocol !== 'https:') return null
    return parsed.toString()
  } catch {
    return null
  }
}

type SchemeKind = 'web' | 'host-port' | 'blocked' | 'none'

/** 判断输入里的 scheme 属于哪一类（决定补协议还是直接拒绝） */
function classify(input: string): { kind: SchemeKind; scheme: string } {
  const text = (input || '').trim()
  const hit = SCHEME_RE.exec(text)
  if (!hit) return { kind: 'none', scheme: '' }
  const scheme = hit[1].toLowerCase()
  if (scheme === 'http' || scheme === 'https') return { kind: 'web', scheme }
  const rest = text.slice(hit[0].length)
  // example.com:8080、localhost:3000 —— 端口写法，不是协议
  if (PORT_RE.test(rest)) return { kind: 'host-port', scheme }
  if (DANGEROUS_SCHEMES.has(scheme)) return { kind: 'blocked', scheme }
  // 未知 scheme 一律拒绝（内嵌框里没有它的合法用途）
  return { kind: 'blocked', scheme }
}

/**
 * 规范化用户输入的网址。
 * @returns 合法则返回规范化后的 URL；非法协议 / 无法解析返回 null
 */
export function normalizeWebUrl(input: string): string | null {
  const text = (input || '').trim()
  if (!text) return null
  const { kind } = classify(text)
  if (kind === 'blocked') return null
  if (kind === 'web') return parse(text)
  // 无协议 或 host:port → 补 https 再解析
  return parse(`https://${text}`)
}

/**
 * 给出「为什么打不开」的人话说明（校验失败时提示用户，而不是静默不响应）。
 */
export function describeWebUrlError(input: string): string {
  const text = (input || '').trim()
  if (!text) return '请输入网址'
  const { kind, scheme } = classify(text)
  if (kind === 'blocked') {
    return `只支持 http / https，${scheme}: 不允许在内嵌页打开`
  }
  if (!normalizeWebUrl(text)) return '网址格式不正确'
  return ''
}
