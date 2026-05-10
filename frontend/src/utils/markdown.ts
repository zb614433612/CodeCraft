import MarkdownIt from 'markdown-it'
import markdownItHighlightjs from 'markdown-it-highlightjs'
import markdownItKatex from 'markdown-it-katex'
// import markdownItMermaid from 'markdown-it-mermaid'

// 初始化Markdown解析器
const md = new MarkdownIt({
  html: false, // 禁止HTML标签，防止XSS
  linkify: true, // 自动将URL转换为链接
  typographer: true, // 启用一些语言中性的替换和引号美化
  breaks: true, // 将换行符转换为<br>
})

// 添加代码高亮支持
md.use(markdownItHighlightjs, {
  // 可选配置，使用默认的highlight.js自动检测语言
  auto: false,
  code: true,
})

// 添加KaTeX数学公式支持
md.use(markdownItKatex)

// 添加Mermaid图表支持 (暂时禁用)
// md.use(markdownItMermaid, {
//   // Mermaid配置，可在此处设置主题等
//   theme: 'default',
//   startOnLoad: false, // 我们将在客户端手动初始化
// })

// 自定义容器插件，用于增强块级元素样式
import markdownItContainer from 'markdown-it-container'

md.use(markdownItContainer, 'warning', {
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

md.use(markdownItContainer, 'info', {
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
md.renderer.rules.table_open = function (_tokens, _idx, _options, _env, _self) {
  return '<table class="markdown-table">\n'
}

// 渲染Markdown为HTML
export function renderMarkdown(content: string): string {
  if (!content) return ''
  return md.render(content)
}

// 自定义 fence 渲染：cmd / terminal → 终端风格，filelist → 文件清单风格
const defaultFence = md.renderer.rules.fence!
md.renderer.rules.fence = (tokens, idx, options, env, self) => {
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
