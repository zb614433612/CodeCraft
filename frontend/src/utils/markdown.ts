import MarkdownIt from 'markdown-it'
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
