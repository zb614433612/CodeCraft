/**
 * Markdown 渲染（L4 聊天页正文）
 * ----------------------------------------------------------------------------
 * 目标：把 LLM 输出的 markdown 原文渲染成 HTML，供 ChatMessage 用 v-html 输出。
 *
 * 两个实例（与旧项目 v2 的改造思路一致）：
 *   - `md`     完整版：代码高亮（highlight.js）+ 容器块 + 自定义 fence（filelist / cmd / diff）
 *   - `mdLight`流式版：跳过 highlight.js（最贵的一步），代码块以纯文本转义输出。
 *     流式期间每 ~80ms 重渲染一次，用轻量版避免长代码块把主线程打满；
 *     消息收尾后父组件再用完整版渲染一次，补齐高亮。
 *
 * 安全：`html: false` —— 所有 HTML 标签一律转义，聊天内容不会被当作标签执行。
 */
import MarkdownIt from 'markdown-it'
import container from 'markdown-it-container'
import hljs from 'highlight.js/lib/core'

// —— 按需注册语言（全量包 ~900KB，这里只挑开发场景常用的，产物小很多）——
import bash from 'highlight.js/lib/languages/bash'
import c from 'highlight.js/lib/languages/c'
import cpp from 'highlight.js/lib/languages/cpp'
import csharp from 'highlight.js/lib/languages/csharp'
import css from 'highlight.js/lib/languages/css'
import diff from 'highlight.js/lib/languages/diff'
import dockerfile from 'highlight.js/lib/languages/dockerfile'
import go from 'highlight.js/lib/languages/go'
import groovy from 'highlight.js/lib/languages/groovy'
import ini from 'highlight.js/lib/languages/ini'
import java from 'highlight.js/lib/languages/java'
import javascript from 'highlight.js/lib/languages/javascript'
import json from 'highlight.js/lib/languages/json'
import kotlin from 'highlight.js/lib/languages/kotlin'
import markdown from 'highlight.js/lib/languages/markdown'
import php from 'highlight.js/lib/languages/php'
import plaintext from 'highlight.js/lib/languages/plaintext'
import powershell from 'highlight.js/lib/languages/powershell'
import python from 'highlight.js/lib/languages/python'
import ruby from 'highlight.js/lib/languages/ruby'
import rust from 'highlight.js/lib/languages/rust'
import scss from 'highlight.js/lib/languages/scss'
import shell from 'highlight.js/lib/languages/shell'
import sql from 'highlight.js/lib/languages/sql'
import swift from 'highlight.js/lib/languages/swift'
import typescript from 'highlight.js/lib/languages/typescript'
import xml from 'highlight.js/lib/languages/xml'
import yaml from 'highlight.js/lib/languages/yaml'

const LANGUAGES: Array<[string, Parameters<typeof hljs.registerLanguage>[1]]> = [
  ['bash', bash],
  ['c', c],
  ['cpp', cpp],
  ['csharp', csharp],
  ['css', css],
  ['diff', diff],
  ['dockerfile', dockerfile],
  ['go', go],
  ['groovy', groovy],
  ['ini', ini],
  ['java', java],
  ['javascript', javascript],
  ['json', json],
  ['kotlin', kotlin],
  ['markdown', markdown],
  ['php', php],
  ['plaintext', plaintext],
  ['powershell', powershell],
  ['python', python],
  ['ruby', ruby],
  ['rust', rust],
  ['scss', scss],
  ['shell', shell],
  ['sql', sql],
  ['swift', swift],
  ['typescript', typescript],
  ['xml', xml],
  ['yaml', yaml]
]
for (const [name, definition] of LANGUAGES) hljs.registerLanguage(name, definition)

/** markdown 基础选项：禁止原始 HTML（防 XSS）、自动链接、保留换行 */
const BASE_OPTIONS = {
  html: false,
  linkify: true,
  typographer: false,
  breaks: true
} as const

function escapeHtml(text: string): string {
  return text.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
}

function escapeAttr(text: string): string {
  return escapeHtml(text).replace(/"/g, '&quot;')
}

/* ============================ 自定义块：filelist / cmd ============================ */

/**
 * 文件修改清单块（```filelist）。
 *
 * 约定格式（每行一个文件）：
 *   + path/to/New.java      新增
 *   - path/to/Old.java      删除
 *   ~ path/to/Mod.java   说明  修改
 */
function renderFileList(content: string): string {
  const lines = content
    .trim()
    .split('\n')
    .map(line => line.trimEnd())
    .filter(line => line.trim())

  const rows = lines
    .map(line => {
      const match = line.match(/^([+\-~])\s+(.+?)(?:\s*\|\s*(.+))?$/)
      if (!match) return { type: '', path: line.trim(), summary: '' }
      return { type: match[1], path: match[2], summary: match[3] || '' }
    })
    .map(row => {
      const cls = row.type === '+' ? 'is-add' : row.type === '-' ? 'is-del' : row.type === '~' ? 'is-mod' : ''
      const badge = row.type === '+' ? '新增' : row.type === '-' ? '删除' : row.type === '~' ? '修改' : '文件'
      return (
        `<div class="cc-filelist__row ${cls}">` +
        `<span class="cc-filelist__badge">${badge}</span>` +
        `<span class="cc-filelist__path">${escapeHtml(row.path)}</span>` +
        (row.summary ? `<span class="cc-filelist__sum">${escapeHtml(row.summary)}</span>` : '') +
        `</div>`
      )
    })
    .join('')

  return (
    `<div class="cc-filelist">` +
    `<div class="cc-filelist__head"><span class="cc-filelist__title">文件修改清单</span>` +
    `<span class="cc-filelist__count">${lines.length} 个文件</span></div>` +
    `<div class="cc-filelist__body">${rows}</div>` +
    `</div>`
  )
}

/**
 * 命令执行块（```cmd / ```terminal）。
 * 首行视为命令，其余为输出；输出里命中 error/fail 时状态章显示失败。
 */
function renderCmdBlock(content: string): string {
  const lines = content.replace(/\r\n/g, '\n').split('\n')
  const command = (lines[0] || '').trim()
  const output = lines.slice(1).join('\n').trim()
  const failed = /(?:^|\s)(?:error|failed|fatal|not found|exception)(?:\s|:|$)/i.test(content)
  return (
    `<div class="cc-cmd">` +
    `<div class="cc-cmd__head"><span class="cc-cmd__title">命令执行</span>` +
    `<span class="cc-cmd__status ${failed ? 'is-fail' : 'is-ok'}">${failed ? '✗ 失败' : '✓ 完成'}</span></div>` +
    `<div class="cc-cmd__body">` +
    `<div class="cc-cmd__line"><span class="cc-cmd__prompt">$</span><span class="cc-cmd__command">${escapeHtml(command)}</span></div>` +
    (output ? `<pre class="cc-cmd__out">${escapeHtml(output)}</pre>` : '') +
    `</div></div>`
  )
}

/* ============================ 代码高亮 ============================ */

/**
 * 高亮一段代码，返回 `<code>` 内部的 HTML（不含 code 标签本身）。
 * 未知语言 / 高亮异常时回退为转义后的纯文本，保证内容不丢。
 */
function highlightToHtml(code: string, lang: string): string {
  const language = (lang || '').trim().split(/\s+/)[0].toLowerCase()
  if (language && hljs.getLanguage(language)) {
    try {
      return hljs.highlight(code, { language, ignoreIllegals: true }).value
    } catch {
      /* 回退到纯文本 */
    }
  }
  return escapeHtml(code)
}

/**
 * 代码块渲染（覆盖 markdown-it 默认 fence）。
 *
 * 返回结构：`<div class="cc-code" data-lang="java"><pre><code class="hljs language-java">…</code></pre></div>`
 * 语言名通过 `::before` 展示，diff 的行级底色由 CSS 的 `.hljs-addition/.hljs-deletion` 提供。
 */
function applyFence(md: MarkdownIt, highlight: boolean): void {
  md.renderer.rules.fence = (tokens, idx) => {
    const token = tokens[idx]
    const info = (token.info || '').trim()
    const lang = info.split(/\s+/)[0].toLowerCase()
    const content = token.content

    if (lang === 'filelist') return renderFileList(content)
    if (lang === 'cmd' || lang === 'terminal' || lang === 'shell-session') return renderCmdBlock(content)

    const inner = highlight ? highlightToHtml(content, lang) : escapeHtml(content)
    const langLabel = lang ? `<span class="cc-code__lang">${escapeHtml(lang)}</span>` : ''
    const codeClass = `hljs${lang ? ` language-${escapeAttr(lang)}` : ''}`
    return (
      `<div class="cc-code">${langLabel}` +
      `<pre class="cc-code__pre"><code class="${codeClass}">${inner}</code></pre>` +
      `</div>`
    )
  }
}

/* ============================ 本地文件 / 图片路径 → 可点击链接 ============================ */
/*
 * LLM 回答里经常直接甩路径（`C:\proj\src\App.vue` / `src/utils/markdown.ts`），
 * 纯文本看着像代码，用户还得手动翻文件树。
 * 这里把路径转成带 `data-cc-path` / `data-cc-kind` 的链接，
 * 点击由 ChatView 的点击委托接管（打开右侧文件抽屉预览）。
 */

/** 图片扩展名（用于区分「图片芯片」与「文件链接」） */
const IMAGE_EXT_RE = /\.(png|jpe?g|gif|webp|bmp|svg|ico)$/i

/** 是否为图片路径 */
function isImagePath(p: string): boolean {
  return IMAGE_EXT_RE.test(p.split(/[?#]/)[0])
}

/**
 * 是否为可识别的本地文件路径（含路径分隔符 + 扩展名，且非 URL）。
 *
 * @param allowBareName 是否接受「纯文件名」（如 LLM 常写的 `[CodeCraftMcpServer.java](CodeCraftMcpServer.java)`）。
 *   这类链接不含 `/`、`\`，旧逻辑会当成普通外链，点击后浏览器按相对 URL 导航 → 404 / 白屏。
 *   开启后必须命中扩展名白名单，避免把 `example.com` 这种真域名误判成本地文件。
 */
function isLocalFilePath(p: string, allowBareName = false): boolean {
  if (!p || p.length > 600) return false
  if (p.includes('://')) return false
  const path = p.split(/[?#]/)[0]
  if (/[\\/]/.test(path) && /\.[A-Za-z0-9]{1,10}$/.test(path)) return true
  if (!allowBareName) return false
  const bare = /^([^\s/\\]+)\.([A-Za-z0-9]{1,10})$/.exec(path)
  return !!bare && FILE_EXT_STRICT_RE.test(bare[2])
}

/** 已知可预览扩展名（纯文本里识别路径用，必须含路径分隔符，避免误伤普通单词） */
const PATH_EXT =
  'java|kt|kts|groovy|xml|yml|yaml|properties|json|json5|js|mjs|cjs|ts|tsx|jsx|vue|css|less|scss|sass|html|htm|md|markdown|txt|log|sql|py|rb|go|rs|c|cc|cpp|cxx|h|hpp|cs|php|swift|sh|bash|zsh|bat|cmd|ps1|gradle|toml|ini|conf|cfg|env|png|jpe?g|gif|webp|bmp|svg|ico|pdf|docx?|xlsx?|pptx?|csv|zip|tar|gz|jar|war|mp4|mp3|wav|mov'

/**
 * 纯文本路径**候选片段**：连续的非空白字符（路径本身不可能含空白）。
 *
 * <h3>为什么不再用「一条大正则全局扫描」</h3>
 * 旧实现把
 * `(?:盘符|./|/)?[^...]*[\\/][^...]*\.(ext)`（`[^...]` 排除空白但长度不限）
 * 直接套 `g` 标志在整段文本上 exec。遇到**一整段没有斜杠的长文本**时，
 * 每个起点都会一路扫到串尾才发现没有 `[\\/]` —— 典型的 O(n²) 回溯。
 *
 * 实测（Node 20，纯中文无换行）：
 *   2 千字 51ms / 8 千字 774ms / 1.6 万字 2721ms / 3.2 万字 10697ms
 *
 * 而「思考过程」恰好就是这种形状（模型往往吐一整段无换行的推理），
 * 展开思考 = 同步跑一次 markdown → 主线程被锁死几十秒，
 * 表现就是「点展开直接卡死、整页无响应」（不是渐变卡顿）。
 *
 * 现在改成：先按空白切词（O(n)），只对含 `\` / `/` 的短片段跑锚定正则。
 */
const PATH_CANDIDATE_RE = /[^\s<>"'`|*?]+/g

/**
 * 单片段路径正则：`^` 锚定（每条候选只尝试一次，不跨片段回溯），
 * 不加 `$` —— 尾部标点（如句号「。」）不属于路径，语义与旧版一致。
 */
const PATH_WORD_RE = new RegExp(
  '^(?:[A-Za-z]:[\\\\/]|\\.{1,2}[\\\\/]|/)?[^\\s<>"\'`|*?\\n]*[\\\\/][^\\s<>"\'`|*?\\n]*\\.(?:' +
    PATH_EXT +
    ')(?![\\w])',
  'i'
)

/** 文件扩展名白名单精确校验（识别 linkify 误判的「纯文件名」，如 README.md） */
const FILE_EXT_STRICT_RE = new RegExp('^(?:' + PATH_EXT + ')$', 'i')

/**
 * linkify 会把「纯文件名」误识别为域名并转成链接（.md / .py 恰好是有效 TLD）。
 * 这里识别这种伪链接并还给本地文件处理；真域名 / 带路径 URL 返回 null 保持原样。
 */
function matchLinkifiedFileName(url: string): string | null {
  const m = /^https?:\/\/([^\s/:?#]+)$/i.exec(url)
  if (!m) return null
  const host = m[1]
  if (host.toLowerCase().startsWith('www.')) return null
  if (!/^[^\s/:?#]+\.[A-Za-z0-9]{1,10}$/.test(host)) return null
  const ext = host.split('.').pop() || ''
  return FILE_EXT_STRICT_RE.test(ext) ? host : null
}

/** 拆分一段纯文本：把其中的本地路径片段切出来（其余为普通文本） */
function splitTextByFilePath(text: string): Array<{ text: string; path?: string; kind?: 'file' | 'image' }> {
  const segs: Array<{ text: string; path?: string; kind?: 'file' | 'image' }> = []
  let last = 0
  PATH_CANDIDATE_RE.lastIndex = 0
  let m: RegExpExecArray | null
  while ((m = PATH_CANDIDATE_RE.exec(text)) !== null) {
    const word = m[0]
    /*
      两道 O(len) 预检，缺一不可：
      1) 没有路径分隔符的片段不可能是路径 —— 旧实现正是在这种片段上退化成 O(n²)；
      2) 单片段正则只对短候选跑（isLocalFilePath 内部还有 600 字符上限）。
    */
    if (!/[\\/]/.test(word)) continue
    const hit = PATH_WORD_RE.exec(word)
    if (!hit) continue
    const p = hit[0]
    if (!isLocalFilePath(p)) continue
    const start = m.index + hit.index
    if (start > last) segs.push({ text: text.slice(last, start) })
    segs.push({ text: p, path: p, kind: isImagePath(p) ? 'image' : 'file' })
    last = start + p.length
  }
  if (last < text.length) segs.push({ text: text.slice(last) })
  return segs.length ? segs : [{ text }]
}

/** 构造指向本地文件/图片的 link_open token（携带 data-cc-path 供点击委托识别） */
function makePathLinkOpen(TokenCtor: any, path: string, kind: 'file' | 'image') {
  const open = new TokenCtor('link_open', 'a', 1)
  open.attrs = [
    ['href', path],
    ['data-cc-path', path],
    ['data-cc-kind', kind],
    ['class', kind === 'image' ? 'cc-media-chip' : 'cc-file-link'],
    ['title', kind === 'image' ? '点击预览图片' : '点击预览文件']
  ]
  return open
}

/**
 * 为 Markdown 实例挂载「本地路径链接化」规则：
 *  1) 图片语法 ![alt](localPath) → 可点击的图片芯片；
 *  2) 链接语法 [text](localPath) → 带 data 属性的可点击链接；
 *  3) 行内代码 / 纯文本中的本地路径 → 自动转成可点击链接。
 */
function applyLocalPathLinks(instance: MarkdownIt): void {
  // 1) 图片：本地路径渲染为可点击芯片（远程图片保持默认 <img>）
  instance.renderer.rules.image = function (tokens, idx, options, _env, self) {
    const token = tokens[idx]
    const src = token.attrGet('src') || ''
    if (isLocalFilePath(src, true)) {
      const alt = token.attrGet('alt') || ''
      const name = alt || src.split(/[\\/]/).pop() || src
      const kind: 'file' | 'image' = isImagePath(src) ? 'image' : 'file'
      const icon = kind === 'image' ? '🖼️' : '📄'
      return (
        `<a class="cc-media-chip" data-cc-path="${escapeAttr(src)}" data-cc-kind="${kind}" ` +
        `title="点击预览：${escapeAttr(src)}"><span class="cc-chip-icon">${icon}</span>` +
        `<span class="cc-chip-name">${escapeHtml(name)}</span></a>`
      )
    }
    return self.renderToken(tokens, idx, options)
  }

  // 2) 链接：本地路径链接补 data 属性；其余走默认渲染
  const defaultLinkOpen = instance.renderer.rules.link_open
  instance.renderer.rules.link_open = function (tokens, idx, options, env, self) {
    const token = tokens[idx]
    const ccPath = token.attrGet('data-cc-path')
    const href = token.attrGet('href') || ''
    if (ccPath) {
      const kind = token.attrGet('data-cc-kind') || (isImagePath(ccPath) ? 'image' : 'file')
      const cls = token.attrGet('class') || (kind === 'image' ? 'cc-media-chip' : 'cc-file-link')
      return `<a class="${escapeAttr(cls)}" data-cc-path="${escapeAttr(ccPath)}" data-cc-kind="${escapeAttr(kind)}" title="点击预览：${escapeAttr(ccPath)}">`
    }
    if (isLocalFilePath(href, true)) {
      const kind: 'file' | 'image' = isImagePath(href) ? 'image' : 'file'
      const cls = kind === 'image' ? 'cc-media-chip' : 'cc-file-link'
      return `<a class="${cls}" data-cc-path="${escapeAttr(href)}" data-cc-kind="${kind}" title="点击预览：${escapeAttr(href)}">`
    }
    // linkify 误把「纯文件名」（README.md / main.py）识别为域名 → 还原为本地文件链接
    if (token.info === 'auto') {
      const bare = matchLinkifiedFileName(href)
      if (bare) {
        const kind: 'file' | 'image' = isImagePath(bare) ? 'image' : 'file'
        const cls = kind === 'image' ? 'cc-media-chip' : 'cc-file-link'
        return `<a class="${cls}" data-cc-path="${escapeAttr(bare)}" data-cc-kind="${kind}" title="点击预览：${escapeAttr(bare)}">`
      }
    }
    return defaultLinkOpen
      ? defaultLinkOpen(tokens, idx, options, env, self)
      : self.renderToken(tokens, idx, options)
  }

  // 3) 纯文本 / 行内代码中的路径 → 拆分为链接 token（在 linkify 之后执行，避免误伤 URL）
  instance.core.ruler.push('cc_path_link', (state: any) => {
    const TokenCtor = state.Token
    for (const blockToken of state.tokens) {
      if (blockToken.type !== 'inline' || !blockToken.children) continue
      const out: any[] = []
      let inLink = 0
      for (const token of blockToken.children) {
        if (token.type === 'link_open') {
          inLink++
          out.push(token)
          continue
        }
        if (token.type === 'link_close') {
          inLink--
          out.push(token)
          continue
        }
        if (inLink > 0) {
          out.push(token)
          continue
        }

        if (token.type === 'text') {
          const segs = splitTextByFilePath(token.content)
          if (segs.length === 1 && !segs[0].path) {
            out.push(token)
            continue
          }
          for (const seg of segs) {
            const textToken = new TokenCtor('text', '', 0)
            textToken.content = seg.text
            textToken.level = token.level
            if (seg.path) {
              out.push(makePathLinkOpen(TokenCtor, seg.path, seg.kind!))
              out.push(textToken)
              out.push(new TokenCtor('link_close', 'a', -1))
            } else {
              out.push(textToken)
            }
          }
        } else if (token.type === 'code_inline') {
          const content = (token.content || '').trim()
          if (isLocalFilePath(content)) {
            const kind: 'file' | 'image' = isImagePath(content) ? 'image' : 'file'
            out.push(makePathLinkOpen(TokenCtor, content, kind))
            const textToken = new TokenCtor('text', '', 0)
            textToken.content = content
            textToken.level = token.level
            out.push(textToken)
            out.push(new TokenCtor('link_close', 'a', -1))
          } else {
            out.push(token)
          }
        } else {
          out.push(token)
        }
      }
      blockToken.children = out
    }
  })
}

/* ============================ 共享插件 ============================ */

const CONTAINERS: Array<[string, string]> = [
  ['warning', 'is-warning'],
  ['info', 'is-info'],
  ['tip', 'is-tip'],
  ['danger', 'is-danger']
]

function applySharedPlugins(md: MarkdownIt): void {
  // 提示容器 ::: warning / info / tip / danger
  for (const [name, cls] of CONTAINERS) {
    md.use(container, name, {
      validate: (params: string) => params.trim().split(/\s+/)[0] === name,
      render: (tokens: any[], idx: number) => {
        if (tokens[idx].nesting === 1) {
          return `<div class="cc-md-note ${cls}">`
        }
        return '</div>\n'
      }
    })
  }

  // 表格加类，便于统一滚动与描边
  md.renderer.rules.table_open = () => '<table class="cc-md-table">\n'

  // 外链统一新窗口打开，并阻断 opener 引用
  const defaultLinkOpen = md.renderer.rules.link_open
  md.renderer.rules.link_open = (tokens, idx, options, env, self) => {
    const href = tokens[idx].attrGet('href') || ''
    if (/^https?:\/\//i.test(href)) {
      tokens[idx].attrSet('target', '_blank')
      tokens[idx].attrSet('rel', 'noopener noreferrer')
    }
    return defaultLinkOpen
      ? defaultLinkOpen(tokens, idx, options, env, self)
      : self.renderToken(tokens, idx, options)
  }

  // 本地文件 / 图片路径链接化（必须在外链规则之后挂载：
  // 它会把当前 link_open 保存为 defaultLinkOpen，本地路径走自己的分支，其余交回外链规则）
  applyLocalPathLinks(md)
}

/* ============================ 实例 ============================ */

const md = new MarkdownIt(BASE_OPTIONS)
applySharedPlugins(md)
applyFence(md, true)

const mdLight = new MarkdownIt(BASE_OPTIONS)
applySharedPlugins(mdLight)
applyFence(mdLight, false)

/**
 * 渲染 markdown 为 HTML（完整版，含代码高亮）。
 * 用于：历史消息首次渲染、流式消息收尾补齐、重新生成等一次性场景。
 */
export function renderMarkdown(content: string): string {
  if (!content) return ''
  try {
    return md.render(content)
  } catch {
    // 渲染异常绝不能吞掉内容：回退为转义后的纯文本
    return `<p>${escapeHtml(content)}</p>`
  }
}

/**
 * 渲染 markdown 为 HTML（轻量版：跳过代码高亮）。
 * 仅用于流式增量刷新的中间态，最终仍会由 {@link renderMarkdown} 渲染一次。
 */
export function renderMarkdownLight(content: string): string {
  if (!content) return ''
  try {
    return mdLight.render(content)
  } catch {
    return `<p>${escapeHtml(content)}</p>`
  }
}

/** 暴露实例与能力判断，便于调试 / 后续按需扩展（测试脚本也会用到） */
export const markdownUtils = {
  hasLanguage: (lang: string) => !!hljs.getLanguage(lang),
  registeredLanguages: () => hljs.listLanguages()
}
