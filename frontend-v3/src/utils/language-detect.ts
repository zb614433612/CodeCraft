/*
 * 文件 → highlight.js 语言名 / 显示名 / 大文件阈值
 * ----------------------------------------------------------------------------
 * 为什么单独一个模块：
 *   1) `ProjectFileTree`（编辑器）与后续的 diff / 多标签都会用同一套扩展名映射，
 *      写两份必然漂移；
 *   2) 大文件阈值（2MB / 2 万行）是「降级为只读」的唯一判定口径，集中在一处方便调参。
 *
 * 语言注册复用 `utils/markdown.ts` —— 它已经按需注册了 28 种常用语言，
 * 且与本模块 `import hljs from 'highlight.js/lib/core'` 拿到的是**同一个单例**
 * （ES 模块缓存），所以这里只需要 import 触发副作用，不必重复 registerLanguage。
 */
import hljs from 'highlight.js/lib/core'
import './markdown'

/** 扩展名 → hljs 语言名（键一律小写；与 markdown.ts 注册的 28 种对齐） */
const EXT_LANGUAGE: Record<string, string> = {
  ts: 'typescript',
  tsx: 'typescript',
  mts: 'typescript',
  cts: 'typescript',
  js: 'javascript',
  jsx: 'javascript',
  mjs: 'javascript',
  cjs: 'javascript',
  vue: 'xml',
  java: 'java',
  kt: 'kotlin',
  kts: 'kotlin',
  groovy: 'groovy',
  gradle: 'groovy',
  py: 'python',
  rb: 'ruby',
  php: 'php',
  go: 'go',
  rs: 'rust',
  swift: 'swift',
  c: 'c',
  h: 'c',
  cpp: 'cpp',
  cc: 'cpp',
  cxx: 'cpp',
  hpp: 'cpp',
  hh: 'cpp',
  cs: 'csharp',
  css: 'css',
  scss: 'scss',
  less: 'scss',
  html: 'xml',
  htm: 'xml',
  xml: 'xml',
  svg: 'xml',
  json: 'json',
  jsonc: 'json',
  yml: 'yaml',
  yaml: 'yaml',
  toml: 'ini',
  ini: 'ini',
  properties: 'ini',
  conf: 'ini',
  md: 'markdown',
  markdown: 'markdown',
  sql: 'sql',
  sh: 'bash',
  bash: 'bash',
  zsh: 'bash',
  ps1: 'powershell',
  bat: 'powershell',
  cmd: 'powershell',
  dockerfile: 'dockerfile',
  diff: 'diff',
  patch: 'diff',
  txt: 'plaintext',
  log: 'plaintext'
}

/** 按文件名（而非扩展名）判定的特例 */
const NAME_LANGUAGE: Record<string, string> = {
  dockerfile: 'dockerfile',
  makefile: 'bash',
  '.gitignore': 'ini',
  '.gitattributes': 'ini',
  '.editorconfig': 'ini',
  '.env': 'ini',
  '.npmrc': 'ini'
}

/** 语言名 → 展示名（头部徽章用） */
const LANGUAGE_LABEL: Record<string, string> = {
  typescript: 'TypeScript',
  javascript: 'JavaScript',
  java: 'Java',
  kotlin: 'Kotlin',
  groovy: 'Groovy',
  python: 'Python',
  ruby: 'Ruby',
  php: 'PHP',
  go: 'Go',
  rust: 'Rust',
  swift: 'Swift',
  c: 'C',
  cpp: 'C++',
  csharp: 'C#',
  css: 'CSS',
  scss: 'SCSS',
  xml: 'HTML/XML',
  json: 'JSON',
  yaml: 'YAML',
  ini: 'INI',
  markdown: 'Markdown',
  sql: 'SQL',
  bash: 'Shell',
  powershell: 'PowerShell',
  dockerfile: 'Dockerfile',
  diff: 'Diff',
  plaintext: 'Text'
}

/** 大文件阈值：超过任一项即降级为「只读查看」 */
export const FILE_LIMITS = {
  /** 2 MB —— 与旧版一致：逐行高亮在更大体量下会明显掉帧 */
  maxBytes: 2 * 1024 * 1024,
  /** 2 万行 —— 行号栏与高亮层会各自创建同等数量的节点 */
  maxLines: 20000
} as const

/** 取文件名的最后一段（同时兼容 `/` 与 `\`） */
export function baseName(path: string): string {
  if (!path) return ''
  const normalized = path.replace(/\\/g, '/')
  const idx = normalized.lastIndexOf('/')
  return idx >= 0 ? normalized.slice(idx + 1) : normalized
}

/** 取扩展名（小写、不含点；无扩展名返回空串） */
export function extName(path: string): string {
  const name = baseName(path)
  const idx = name.lastIndexOf('.')
  if (idx <= 0 || idx === name.length - 1) return ''
  return name.slice(idx + 1).toLowerCase()
}

/**
 * 推断文件应使用的 hljs 语言名。
 * <p>未知扩展名一律回落 `plaintext` —— 调用方无需再判空，
 * 代价只是不高亮，不会报错。</p>
 */
export function detectLanguage(path: string): string {
  const name = baseName(path).toLowerCase()
  const byName = NAME_LANGUAGE[name]
  if (byName) return byName
  // `.gitignore` 这类以点开头的文件名，`extName` 会返回空串，故先查 NAME_LANGUAGE
  const ext = extName(path)
  if (ext && EXT_LANGUAGE[ext]) return EXT_LANGUAGE[ext]
  return 'plaintext'
}

/** 语言展示名（未知语言原样大写返回） */
export function languageLabel(language: string): string {
  if (!language) return 'Text'
  return LANGUAGE_LABEL[language] || language.toUpperCase()
}

/** 该语言是否已被 highlight.js 注册（未注册时调用方应回落到纯文本） */
export function isLanguageReady(language: string): boolean {
  if (!language || language === 'plaintext') return false
  return !!hljs.getLanguage(language)
}

/** 统计行数（空内容算 1 行，与编辑器的行号栏保持一致） */
export function countLines(text: string): number {
  if (!text) return 1
  const normalized = text.replace(/\r\n/g, '\n').replace(/\r/g, '\n')
  return normalized.split('\n').length
}

/** 字节数（按 UTF-8 计，与文件实际落盘体积一致） */
export function byteLength(text: string): number {
  if (!text) return 0
  // Blob 在浏览器与 Node 18+ 均可用，比手写 UTF-8 长度更准确（含 emoji 代理对）
  return new Blob([text]).size
}

/** 人性化体积显示 */
export function formatBytes(bytes: number): string {
  if (!bytes || bytes < 0) return '0 B'
  if (bytes < 1024) return `${bytes} B`
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`
  return `${(bytes / (1024 * 1024)).toFixed(2)} MB`
}

export interface FileSizeVerdict {
  /** 是否超出阈值（true 时编辑器降级为只读） */
  tooLarge: boolean
  /** 面积/体积的可读描述，如 `3.20 MB / 32000 行` */
  detail: string
  /** 给用户看的降级原因（tooLarge 为 false 时为空串） */
  reason: string
}

/**
 * 判断文件是否超出「可编辑」阈值。
 * <p>阈值口径集中在 {@link FILE_LIMITS}；两个维度任一超限都降级，
 * 因为高亮层与行号栏的开销分别受体积和行数支配。</p>
 */
export function checkFileSize(content: string): FileSizeVerdict {
  const bytes = byteLength(content)
  const lines = countLines(content)
  const detail = `${formatBytes(bytes)} / ${lines} 行`
  const tooManyBytes = bytes > FILE_LIMITS.maxBytes
  const tooManyLines = lines > FILE_LIMITS.maxLines
  if (!tooManyBytes && !tooManyLines) {
    return { tooLarge: false, detail, reason: '' }
  }
  const which = tooManyBytes
    ? `体积超过 ${formatBytes(FILE_LIMITS.maxBytes)}`
    : `行数超过 ${FILE_LIMITS.maxLines} 行`
  return { tooLarge: true, detail, reason: `${which}，仅支持只读查看` }
}
