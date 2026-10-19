<!--
  工具结果卡片（F13）
  ----------------------------------------------------------------------------
  v3 早期把所有工具输出塞进一个 `<pre>`（ChatMessage 的 .cc-tool-card__body），
  命令输出、搜索结果、文件改动长得一模一样，用户得自己在一坨等宽文本里找重点。

  本组件接收「工具名 + 原始输出」，交给 utils/tool-render 归一成结构化模型后
  按 kind 渲染成不同卡片：
    file-write  文件改动：路径 + 大小 + `+N/-M` 徽章 + diff 着色
    file-read   文件读取：路径 + 编码/行数 + 带行号正文
    file-tree   目录树：根目录 + 树形正文
    search      搜索：模式 + 命中数 + 文件/行号列表
    command     命令：命令行 + 退出码色标 + 终端块
    web         网页：搜索命中列表（标题/域名/摘要）或抓取正文
    db          数据库：目标库 + 影响行数 / 查询结果
    git         Git：语义动作（暂存/提交/推送…）+ 输出
    agent       子 Agent：动作 + 输出
    task        任务清单：进度列表
    error       失败：错误标题 + 建议
    raw         兜底：等宽原文

  设计取舍：
   - **不在模板里对联合类型做 narrowing**（vue-tsc 对 `v-if` 后的 computed 解包
     narrowing 支持不稳定），改为在 script 里把模型摊平成 {@link ToolView}，
     模板只读扁平字段，类型安全且不会有 `Property does not exist` 报错。
   - **原文永远可查**：非 raw 卡片底部有「查看原始输出」折叠，解析有偏差时用户
     仍能看到后端真实返回（这也是排查工具问题的一手材料）。
-->
<template>
  <div class="cc-tr" :class="[`is-${view.kind}`, { 'is-failed': view.failed }]">
    <!-- 头部：类型 + 目标 + 徽章 -->
    <div class="cc-tr__head">
      <span class="cc-tr__kind">
        <svg class="cc-i cc-i--sm"><use :href="view.icon" /></svg>
        {{ view.kindLabel }}
      </span>
      <span v-if="view.target" class="cc-tr__target" :title="view.target">{{ view.target }}</span>
      <span v-if="view.badge" class="cc-tr__badge" :class="`is-${view.badgeTone}`">{{ view.badge }}</span>
    </div>

    <!-- 文件改动 -->
    <template v-if="view.kind === 'file-write'">
      <div v-if="view.metaLines.length" class="cc-tr__meta">
        <span v-for="(line, i) in view.metaLines" :key="i">{{ line }}</span>
      </div>
      <pre v-if="view.diff.length" class="cc-tr__diff"><span
        v-for="(line, i) in view.diff"
        :key="i"
        class="cc-tr__diffline"
        :class="diffClass(line)"
      >{{ line }}
</span></pre>
    </template>

    <!-- 文件读取 / 目录树 / 命令 / 数据库 / Git / 子 Agent：等宽正文 -->
    <template v-else-if="view.preText">
      <div v-if="view.kind === 'file-read' && view.metaLines.length" class="cc-tr__meta">
        <span v-for="(line, i) in view.metaLines" :key="i">{{ line }}</span>
      </div>
      <pre class="cc-tr__pre" :class="`is-${view.preStyle}`">{{ view.preText }}</pre>
    </template>

    <!-- 搜索命中 -->
    <template v-else-if="view.kind === 'search'">
      <div v-if="view.searchSummary" class="cc-tr__meta">
        <span>{{ view.searchSummary }}</span>
      </div>
      <ul v-if="view.hits.length" class="cc-tr__hits">
        <li v-for="(hit, i) in view.hits" :key="i" class="cc-tr__hit">
          <span v-if="hit.file" class="cc-tr__hitfile" :title="hit.file">{{ hit.file }}</span>
          <span v-if="hit.line != null" class="cc-tr__hitline">:{{ hit.line }}</span>
          <span v-if="hit.text" class="cc-tr__hittext">{{ hit.text }}</span>
        </li>
      </ul>
      <div v-else class="cc-tr__empty">没有命中结果</div>
    </template>

    <!-- 网页 -->
    <template v-else-if="view.kind === 'web'">
      <ul v-if="view.webResults.length" class="cc-tr__web">
        <li v-for="r in view.webResults" :key="r.index" class="cc-tr__webitem">
          <a class="cc-tr__webtitle" :href="r.url" target="_blank" rel="noreferrer noopener">{{ r.title }}</a>
          <span v-if="r.domain" class="cc-tr__webdomain">{{ r.domain }}</span>
          <div v-if="r.snippet" class="cc-tr__websnippet">{{ r.snippet }}</div>
        </li>
      </ul>
      <pre v-else-if="view.preText" class="cc-tr__pre is-code">{{ view.preText }}</pre>
      <div v-else class="cc-tr__empty">没有解析到网页内容</div>
    </template>

    <!-- 任务清单 -->
    <template v-else-if="view.kind === 'task'">
      <div v-if="view.taskSummary" class="cc-tr__meta"><span>{{ view.taskSummary }}</span></div>
      <ul v-if="view.tasks.length" class="cc-tr__tasks">
        <li
          v-for="t in view.tasks"
          :key="t.id"
          class="cc-tr__task"
          :class="{ 'is-done': t.status === 'completed' || t.status === 'done' }"
        >
          <span class="cc-tr__taskbox">{{ t.status === 'completed' || t.status === 'done' ? '✓' : '' }}</span>
          <span class="cc-tr__taskid">{{ t.id }}</span>
          <span class="cc-tr__tasktitle">{{ t.title }}</span>
          <span v-if="t.priority" class="cc-tr__taskprio" :class="`is-${(t.priority || '').toLowerCase()}`">{{ t.priority }}</span>
        </li>
      </ul>
    </template>

    <!-- 失败 -->
    <template v-else-if="view.kind === 'error'">
      <div class="cc-tr__err">
        <div class="cc-tr__errtitle">{{ view.errorTitle }}</div>
        <pre v-if="view.errorMessage" class="cc-tr__errmsg">{{ view.errorMessage }}</pre>
      </div>
    </template>

    <!-- 兜底原文（raw 直接铺开，其它类型收进折叠区） -->
    <template v-else>
      <pre class="cc-tr__pre is-code">{{ view.raw }}</pre>
    </template>

    <!-- 原始输出（非 raw 才有，避免重复） -->
    <details v-if="view.kind !== 'raw' && view.raw" class="cc-tr__raw">
      <summary>查看原始输出</summary>
      <pre class="cc-tr__pre is-code">{{ view.raw }}</pre>
    </details>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import {
  renderToolModel,
  TOOL_KIND_LABEL,
  type SearchHit,
  type TaskRow,
  type ToolKind,
  type WebResult
} from '../utils/tool-render'

/** 模板消费的扁平视图（避免对联合类型做 narrowing） */
interface ToolView {
  kind: ToolKind
  kindLabel: string
  icon: string
  failed: boolean
  /** 头部主文案：路径 / 命令 / 关键词 / URL */
  target?: string
  badge?: string
  badgeTone: 'ok' | 'warn' | 'danger' | 'accent' | 'muted'
  /** 元信息行（编码 / 行数 / 大小 / 命中统计…） */
  metaLines: string[]
  /** 正文（read body / tree outline / stdout / db body / git body / agent body） */
  preText: string
  /** 正文样式：code（代码/文本）/ term（终端）/ tree（树形） */
  preStyle: 'code' | 'term' | 'tree'
  /** file-write 的 diff 行 */
  diff: string[]
  hits: SearchHit[]
  searchSummary?: string
  webResults: WebResult[]
  tasks: TaskRow[]
  taskSummary?: string
  errorTitle?: string
  errorMessage?: string
  raw: string
}

const props = withDefaults(
  defineProps<{
    /** 工具名（`file_writer` / `command` / `mcp_xxx` …） */
    name: string
    /** 工具输出原文（chat-mapper 已截断到 4000 字符） */
    detail?: string
  }>(),
  { detail: '' }
)

const KIND_ICON: Record<ToolKind, string> = {
  'file-write': '#i-file',
  'file-read': '#i-file',
  'file-tree': '#i-folder',
  search: '#i-search',
  command: '#i-term',
  web: '#i-link',
  db: '#i-db',
  git: '#i-branch',
  agent: '#i-agent',
  task: '#i-task',
  error: '#i-alert',
  raw: '#i-term'
}

/** 路径压缩（卡片头部空间有限，完整路径放 title） */
function shortPath(path: string): string {
  const clean = path.trim().replace(/\\/g, '/')
  const parts = clean.split('/').filter(Boolean)
  if (parts.length <= 3) return clean
  return `…/${parts.slice(-2).join('/')}`
}

const view = computed<ToolView>(() => {
  const m = renderToolModel(props.name, props.detail)
  const base: ToolView = {
    kind: m.kind,
    kindLabel: TOOL_KIND_LABEL[m.kind],
    icon: KIND_ICON[m.kind],
    failed: m.failed,
    badgeTone: 'muted',
    metaLines: [],
    preText: '',
    preStyle: 'code',
    diff: [],
    hits: [],
    webResults: [],
    tasks: [],
    raw: m.raw
  }

  switch (m.kind) {
    case 'file-write': {
      const actionText = m.action === 'edit' ? '编辑' : m.action === 'write' ? '写入' : m.action === 'delete' ? '删除' : '改动'
      base.target = m.path ? shortPath(m.path) : actionText
      if (m.sizeText) base.metaLines.push(`大小 ${m.sizeText}`)
      base.metaLines.push(...m.meta)
      if (m.linesAdded != null || m.linesDeleted != null) {
        base.badge = `+${m.linesAdded ?? 0} -${m.linesDeleted ?? 0}`
        base.badgeTone = (m.linesDeleted ?? 0) > 0 ? 'warn' : 'ok'
      }
      base.diff = m.diff
      base.preText = ''
      return base
    }
    case 'file-read': {
      base.target = m.path ? shortPath(m.path) : '文件读取'
      if (m.encoding) base.metaLines.push(`编码 ${m.encoding}`)
      if (m.typeText) base.metaLines.push(m.typeText)
      if (m.lines != null) base.metaLines.push(`${m.lines} 行`)
      if (m.rangeText) base.metaLines.push(`显示 ${m.rangeText}`)
      base.preText = m.body
      base.preStyle = 'code'
      return base
    }
    case 'file-tree': {
      base.target = m.root ? shortPath(m.root) : '目录树'
      base.preText = m.outline
      base.preStyle = 'tree'
      return base
    }
    case 'search': {
      base.target = m.pattern || (m.mode === 'glob' ? '文件搜索' : '内容搜索')
      base.searchSummary = m.total != null ? `匹配结果：共 ${m.total} ${m.mode === 'glob' ? '个文件' : '处'}` : undefined
      base.badge = m.total != null ? String(m.total) : undefined
      base.badgeTone = m.total === 0 ? 'warn' : 'accent'
      if (m.scope) base.metaLines.push(`目录 ${shortPath(m.scope)}`)
      base.hits = m.hits
      return base
    }
    case 'command': {
      base.target = m.cmd ? m.cmd : m.mode === 'service' ? '后台服务' : '命令'
      base.badge = m.exitCode == null ? undefined : `退出码 ${m.exitCode}`
      base.badgeTone = m.exitCode == null ? 'muted' : m.exitCode === 0 ? 'ok' : 'danger'
      if (m.durationText) base.metaLines.push(`耗时 ${m.durationText}`)
      base.preText = m.stdout
      base.preStyle = 'term'
      return base
    }
    case 'web': {
      if (m.mode === 'fetch') {
        base.target = m.url || '抓取网页'
        base.preText = m.body
        base.preStyle = 'code'
        base.metaLines.push(`${m.body.length} 字符`)
      } else {
        base.target = '联网搜索'
        base.badge = m.results.length ? `${m.results.length} 条` : undefined
        base.badgeTone = m.results.length ? 'accent' : 'warn'
        base.webResults = m.results
      }
      return base
    }
    case 'db': {
      base.target = m.dbLabel || m.summary || 'SQL'
      base.metaLines = m.summary && m.dbLabel ? [m.summary] : []
      base.preText = m.body === m.raw ? '' : m.body
      base.preStyle = 'code'
      return base
    }
    case 'git': {
      base.target = m.action
      base.preText = m.body
      base.preStyle = 'code'
      base.metaLines = m.summary ? [m.summary] : []
      base.badgeTone = 'accent'
      return base
    }
    case 'agent': {
      base.target = m.action
      base.preText = m.body
      base.preStyle = 'code'
      base.metaLines = m.summary ? [m.summary] : []
      base.badgeTone = 'accent'
      return base
    }
    case 'task': {
      base.target = m.summary || '任务清单'
      base.tasks = m.tasks
      base.badge = m.tasks.length ? `${m.tasks.filter(t => t.status === 'completed' || t.status === 'done').length}/${m.tasks.length}` : undefined
      base.badgeTone = 'accent'
      return base
    }
    case 'error': {
      base.target = m.title
      base.errorTitle = m.title
      base.errorMessage = m.message
      base.badge = '失败'
      base.badgeTone = 'danger'
      return base
    }
    default:
      return base
  }
})

/** diff 行着色：`-|` 删除 / `+|` 新增 / 其余上下文 */
function diffClass(line: string): string {
  if (/^-\|/.test(line) || /^-/.test(line)) return 'is-del'
  if (/^\+\|/.test(line) || /^\+/.test(line)) return 'is-add'
  return 'is-ctx'
}
</script>
