﻿﻿﻿﻿import MarkdownIt from 'markdown-it'
import markdownItHighlightjs from 'markdown-it-highlightjs'
import texmath from 'markdown-it-texmath'
import katex from 'katex'
import markdownItContainer from 'markdown-it-container'

// 基础解析选项（完整实例与降级实例共用，保证解析行为一致）
const BASE_OPTIONS = {
  html: false, // 禁止HTML标签，防止XSS
  linkify: true, // 自动将URL转换为链接
  typographer: true, // 启用一些语言中性的替换和引号美化
  breaks: true, // 将换行符转换为<br>
}

// 初始化Markdown解析器（完整实例：代码高亮 + KaTeX 公式）
const md = new MarkdownIt({ ...BASE_OPTIONS })

// 添加代码高亮支持
md.use(markdownItHighlightjs, {
  // 可选配置，使用默认的highlight.js自动检测语言
  auto: false,
  code: true,
})

// KaTeX 数学公式支持
// 使用 markdown-it-texmath（维护中、无已知漏洞），替代已废弃且存在 XSS 漏洞（GHSA-5ff8-jcf9-fw62）的 markdown-it-katex
md.use(texmath, {
  engine: katex,
  delimiters: 'dollars',
  katexOptions: { throwOnError: false },
})

// 添加Mermaid图表支持 (暂时禁用)
// md.use(markdownItMermaid, {
//   // Mermaid配置，可在此处设置主题等
//   theme: 'default',
//   startOnLoad: false, // 我们将在客户端手动初始化
// })

// ===== 共享插件与规则（完整实例 / 降级实例行为必须一致）=====

/** 挂载共享插件与规则（容器、表格增强） */
function applySharedPlugins(instance: MarkdownIt): void {
  // 自定义容器插件，用于增强块级元素样式
  instance.use(markdownItContainer, 'warning', {
    validate: function (params: string) {
      return params.trim().match(/^warning$/)
    },
    render: function (tokens: any[], idx: number) {
      if (tokens[idx].nesting === 1) {
        //  opening tag
        return '<div class="warning custom-block">\n'
      } else {
        // closing tag
        return '</div>\n'
      }
    }
  })

  instance.use(markdownItContainer, 'info', {
    validate: function (params: string) {
      return params.trim().match(/^info$/)
    },
    render: function (tokens: any[], idx: number) {
      if (tokens[idx].nesting === 1) {
        return '<div class="info custom-block">\n'
      } else {
        return '</div>\n'
      }
    }
  })

  // 表格增强：添加CSS类
  instance.renderer.rules.table_open = function (_tokens, _idx, _options, _env, _self) {
    return '<table class="markdown-table">\n'
  }

  // 本地文件/图片路径 → 可点击链接（点击后在应用内标签页打开）
  applyLocalPathLinks(instance)
}

// ===== 本地文件/图片路径 → 可点击链接 =====

/** 图片扩展名（用于区分「图片标签」与「文件标签」） */
const IMAGE_EXT_RE = /\.(png|jpe?g|gif|webp|bmp|svg|ico)$/i

/** 是否为图片路径 */
function isImagePath(p: string): boolean {
  return IMAGE_EXT_RE.test(p.split(/[?#]/)[0])
}

/** 是否为可识别的本地文件路径（含路径分隔符 + 扩展名，且非 URL） */
function isLocalFilePath(p: string): boolean {
  if (!p || p.length > 600) return false
  if (p.includes('://')) return false
  return /[\\/]/.test(p) && /\.[A-Za-z0-9]{1,10}$/.test(p)
}

/** 已知可预览的文件扩展名（纯文本中识别路径用，保守起见必须含路径分隔符） */
const PATH_EXT = 'java|kt|kts|groovy|xml|yml|yaml|properties|json|json5|js|mjs|cjs|ts|tsx|jsx|vue|css|less|scss|sass|html|htm|md|markdown|txt|log|sql|py|rb|go|rs|c|cc|cpp|cxx|h|hpp|cs|php|swift|sh|bash|zsh|bat|cmd|ps1|gradle|toml|ini|conf|cfg|env|png|jpe?g|gif|webp|bmp|svg|ico|pdf|docx?|xlsx?|pptx?|csv|zip|tar|gz|jar|war|mp4|mp3|wav|mov'

/** 纯文本路径正则：须含至少一个路径分隔符 + 已知扩展名（避免误伤 Node.js 之类的普通词） */
const PATH_RE = new RegExp(
  '(?:[A-Za-z]:[\\\\/]|\\.{1,2}[\\\\/]|/)?[^\\s<>"\'`|*?\\n]*[\\\\/][^\\s<>"\'`|*?\\n]*\\.(?:' + PATH_EXT + ')(?![\\w])',
  'gi'
)

/** 文件扩展名白名单精确校验（用于识别 linkify 误判的「纯文件名」） */
const FILE_EXT_STRICT_RE = new RegExp('^(?:' + PATH_EXT + ')$', 'i')

/**
 * linkify 会把「纯文件名」文本（如 README.md / main.py）误识别为域名并自动转成
 * http(s)://链接（.md / .py 等扩展名恰好是有效 TLD）。此函数识别这种伪链接：
 * 形如 https://无斜杠单段文本、文本带扩展名且扩展名在文件白名单内（排除 www. 开头的真域名）
 * → 返回该文件名（交由点击委托按本地文件打开）；其余（真域名 / 带路径 URL）返回 null 保持原链接。
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

/** 属性值转义 */
function escapeAttr(text: string): string {
  return text.replace(/&/g, '&amp;').replace(/"/g, '&quot;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
}

/** 拆分一段纯文本：把其中的本地路径片段切出来（其余为普通文本） */
function splitTextByFilePath(text: string): { text: string; path?: string; kind?: 'file' | 'image' }[] {
  const segs: { text: string; path?: string; kind?: 'file' | 'image' }[] = []
  let last = 0
  PATH_RE.lastIndex = 0
  let m: RegExpExecArray | null
  while ((m = PATH_RE.exec(text)) !== null) {
    const p = m[0]
    if (!isLocalFilePath(p)) continue
    if (m.index > last) segs.push({ text: text.slice(last, m.index) })
    segs.push({ text: p, path: p, kind: isImagePath(p) ? 'image' : 'file' })
    last = m.index + p.length
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
    ['title', kind === 'image' ? '点击在标签页查看图片' : '点击在标签页打开文件']
  ]
  return open
}

/**
 * 为 Markdown 实例挂载「本地路径链接化」规则：
 * 1) 图片语法 ![alt](localPath) → 可点击的图片芯片；
 * 2) 链接语法 [text](localPath) → 带 data 属性的可点击链接；
 * 3) 行内代码 / 纯文本中的本地路径 → 自动转成可点击链接。
 * 注：这些链接不带 http 前缀，点击导航由聊天区的点击委托（handleMessageLinkClick）接管。
 */
function applyLocalPathLinks(instance: MarkdownIt): void {
  // 1) 图片：本地路径渲染为可点击芯片（远程图片保持默认 <img>）
  instance.renderer.rules.image = function (tokens, idx, options, _env, self) {
    const token = tokens[idx]
    const src = token.attrGet('src') || ''
    if (isLocalFilePath(src)) {
      const alt = token.attrGet('alt') || ''
      const name = alt || src.split(/[\\/]/).pop() || src
      const kind: 'file' | 'image' = isImagePath(src) ? 'image' : 'file'
      const icon = kind === 'image' ? '🖼️' : '📄'
      return `<a class="cc-media-chip" data-cc-path="${escapeAttr(src)}" data-cc-kind="${kind}" title="点击在标签页查看：${escapeAttr(src)}"><span class="cc-chip-icon">${icon}</span><span class="cc-chip-name">${escapeHtml(name)}</span></a>`
    }
    return self.renderToken(tokens, idx, options)
  }

  // 2) 链接：本地路径链接补上 data 属性；其余走默认渲染
  const defaultLinkOpen = instance.renderer.rules.link_open
  instance.renderer.rules.link_open = function (tokens, idx, options, env, self) {
    const token = tokens[idx]
    const ccPath = token.attrGet('data-cc-path')
    const href = token.attrGet('href') || ''
    if (ccPath) {
      const kind = token.attrGet('data-cc-kind') || (isImagePath(ccPath) ? 'image' : 'file')
      const cls = token.attrGet('class') || (kind === 'image' ? 'cc-media-chip' : 'cc-file-link')
      return `<a class="${escapeAttr(cls)}" data-cc-path="${escapeAttr(ccPath)}" data-cc-kind="${escapeAttr(kind)}" title="点击在标签页打开：${escapeAttr(ccPath)}">`
    }
    if (isLocalFilePath(href)) {
      const kind: 'file' | 'image' = isImagePath(href) ? 'image' : 'file'
      const cls = kind === 'image' ? 'cc-media-chip' : 'cc-file-link'
      return `<a class="${cls}" data-cc-path="${escapeAttr(href)}" data-cc-kind="${kind}" title="点击在标签页打开：${escapeAttr(href)}">`
    }
    // linkify 误把「纯文件名」（如 README.md / main.py）识别为域名链接 → 还原为可点击的本地文件链接。
    // 仅处理 linkify 自动生成的链接（token.info === 'auto'）：显式 Markdown 链接与真域名（example.com）不受影响。
    if (token.info === 'auto') {
      const bare = matchLinkifiedFileName(href)
      if (bare) {
        const kind: 'file' | 'image' = isImagePath(bare) ? 'image' : 'file'
        const cls = kind === 'image' ? 'cc-media-chip' : 'cc-file-link'
        return `<a class="${cls}" data-cc-path="${escapeAttr(bare)}" data-cc-kind="${kind}" title="点击在标签页打开：${escapeAttr(bare)}">`
      }
    }
    return defaultLinkOpen ? defaultLinkOpen(tokens, idx, options, env, self) : self.renderToken(tokens, idx, options)
  }

  // 3) 纯文本 / 行内代码中的路径 → 拆分为链接 token（在 linkify 之后执行，避免误伤 URL）
  instance.core.ruler.push('cc_path_link', (state: any) => {
    const TokenCtor = state.Token
    for (const blockToken of state.tokens) {
      if (blockToken.type !== 'inline' || !blockToken.children) continue
      const out: any[] = []
      let inLink = 0
      for (const token of blockToken.children) {
        if (token.type === 'link_open') { inLink++; out.push(token); continue }
        if (token.type === 'link_close') { inLink--; out.push(token); continue }
        if (inLink > 0) { out.push(token); continue }

        if (token.type === 'text') {
          const segs = splitTextByFilePath(token.content)
          if (segs.length === 1 && !segs[0].path) { out.push(token); continue }
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

/** 给 <code> 添加 hljs 类名（与 markdown-it-highlightjs 的类名处理保持一致，避免降级/完整渲染切换时样式跳变） */
function wrapHljsClass(renderer: (...args: any[]) => string): (...args: any[]) => string {
  return function wrappedRenderer(...args: any[]): string {
    return renderer(...args)
      .replace(/<code class="/g, '<code class="hljs ')
      .replace(/<code>/g, '<code class="hljs">')
  }
}

/** 自定义 fence 渲染工厂：cmd / terminal → 终端风格，filelist → 文件清单风格；其余语言走传入的默认 fence */
function makeFence(defaultFence: (...args: any[]) => string) {
  return (tokens: any[], idx: number, options: any, env: any, self: any): string => {
    const token = tokens[idx]
    const lang = token.info.trim().split(/\s+/)[0]

    if (lang === 'cmd' || lang === 'terminal') {
      const cmd = token.content.split('\n')[0]
      const output = token.content.split('\n').slice(1).join('\n').trim()
      const isError = /error|fail|fatal/i.test(token.content)
      return `
      <div class="code-block-cmd">
        <div class="cbc-header">
          <span class="cbc-icon">⧩</span>
          <span class="cbc-title">命令执行</span>
          <span class="cbc-status ${isError ? 'fail' : 'success'}">${isError ? '✗ 失败' : '✓ 成功'}</span>
        </div>
        <div class="cbc-body">
          <div class="cbc-command"><span class="cbc-prompt">$</span> ${escapeHtml(cmd)}</div>
          <div class="cbc-output">${escapeHtml(output)}</div>
        </div>
      </div>`
    }

    if (lang === 'filelist') {
      const lines = token.content.trim().split('\n').filter(l => l.trim())
      let html = '<div class="code-block-filelist"><div class="cbf-header"><span class="cbf-icon">📄</span><span class="cbf-title">文件修改清单</span><span class="cbf-count">' + lines.length + ' 个文件</span></div><div class="cbf-body">'
      for (const line of lines) {
        const match = line.match(/^([+\-~])\s+(.+?)(?:\s*\|\s*(.+))?$/)
        if (match) {
          const type = match[1]
          const path = match[2]
          const summary = match[3] || ''
          let badge = '', badgeClass = ''
          if (type === '+') { badge = '新增'; badgeClass = 'add' }
          else if (type === '-') { badge = '删除'; badgeClass = 'del' }
          else if (type === '~') { badge = '修改'; badgeClass = 'mod' }
          html += `<div class="cbf-item ${badgeClass}"><span class="cbf-badge">${badge}</span><span class="cbf-path">${escapeHtml(path)}</span>${summary ? '<span class="cbf-summary">— ' + escapeHtml(summary) + '</span>' : ''}</div>`
        } else {
          html += `<div class="cbf-item"><span class="cbf-path">${escapeHtml(line)}</span></div>`
        }
      }
      html += '</div></div>'
      return html
    }

    return defaultFence(tokens, idx, options, env, self)
  }
}

// —— 完整实例：共享插件 + 自定义 fence（兜底走 hljs 高亮 fence）——
applySharedPlugins(md)
const hljsFence = md.renderer.rules.fence!
md.renderer.rules.fence = makeFence(hljsFence)

// ===== 降级实例（M4 流式尾段渲染，2026-09-15 性能改造）=====
// 用途：流式尾段每 tick 重渲染时跳过 hljs 高亮与 KaTeX（最贵的两步），只做基础 markdown；
// 段"封段"后由完整实例渲染一次（随段缓存复用），消息结束后整块完整渲染。
// 与完整实例的差异仅两点：
//   1) 不设 options.highlight —— 代码内容以纯文本转义输出（保留 hljs 类名，样式基线一致）；
//   2) 不挂 texmath —— 公式在流式期间短暂显示 $...$ 原文，封段后补齐。
// 其余（容器、表格、cmd/terminal/filelist 自定义块）与完整实例完全一致，避免封段补齐时结构跳变。
const mdLight = new MarkdownIt({ ...BASE_OPTIONS })
applySharedPlugins(mdLight)
const plainFence = mdLight.renderer.rules.fence!
mdLight.renderer.rules.fence = makeFence(wrapHljsClass(plainFence))
const plainCodeBlock = mdLight.renderer.rules.code_block
if (plainCodeBlock) {
  mdLight.renderer.rules.code_block = wrapHljsClass(plainCodeBlock)
}

// 渲染Markdown为HTML
export function renderMarkdown(content: string): string {
  if (!content) return ''
  return md.render(content)
}

// 渲染Markdown为HTML（降级版：跳过代码高亮与公式渲染，仅限流式尾段使用，见上方 mdLight 说明）
export function renderMarkdownLight(content: string): string {
  if (!content) return ''
  return mdLight.render(content)
}

function escapeHtml(text: string): string {
  return text.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
}

// 初始化Mermaid（需要在客户端调用） - 暂时禁用
// export function initMermaid() {
//   if (typeof window !== 'undefined' && (window as any).mermaid) {
//     // 新版本Mermaid使用run()方法
//     const mermaid = (window as any).mermaid
//     if (typeof mermaid.run === 'function') {
//       mermaid.run()
//     } else if (typeof mermaid.init === 'function') {
//       mermaid.init(undefined, '.mermaid')
//     }
//   }
// }

// 重新渲染所有Mermaid图表（当内容动态加载时调用） - 暂时禁用
// export function refreshMermaid() {
//   if (typeof window !== 'undefined' && (window as any).mermaid) {
//     const mermaid = (window as any).mermaid
//     if (typeof mermaid.run === 'function') {
//       mermaid.run()
//     } else if (typeof mermaid.init === 'function') {
//       mermaid.init(undefined, '.mermaid')
//     }
//   }
// }
