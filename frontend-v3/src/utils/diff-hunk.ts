/*
 * Diff 行模型：unified diff 解析 / 前端 LCS 计算 / hunk & 单行 patch 重写
 * ----------------------------------------------------------------------------
 * 两条数据通道共用同一套 {@link DiffHunk} 结构：
 *   1) Git 变更：`GET /api/git/diff` 返回的 unified diff 原文，直接
 *      {@link parseUnifiedDiff} 解析 —— 行号由 git 算好，绝对准确；
 *   2) 快照轮次：后端只给「该轮备份内容」与「当前内容」，用 {@link computeHunks}
 *      在前端算 diff（无 git 仓库也能看差异）。
 *
 * 撤销（F10）为什么要在前端重写 patch：
 *   后端 `POST /api/git/restore-hunks` 会拼上 `--- a/f` / `+++ b/f` 头后执行
 *   `git apply --reverse --unidiff-zero`。撤销整块时把 hunk 原文原样传回去即可；
 *   但「撤销单行」必须把同一个 hunk 里的**其它**变更行消掉，否则会把整块一起撤了。
 *   消法：
 *     - 非目标的 `+` 行 → 改成上下文行（该行在工作区确实存在，apply 时「不动」）
 *     - 非目标的 `-` 行 → 从 patch 中剔除（工作区里本来就没有它，留着会校验失败）
 *   重写后 new 侧行号保持不变，而 `--reverse` 正是用 new 侧定位，所以能安全命中。
 */
/** diff 中的一行：`ctx` 未变 / `add` 新增 / `del` 删除 */
export interface DiffLine {
  type: 'ctx' | 'add' | 'del'
  text: string
  /** 旧文件行号（add 行为 null） */
  oldLine: number | null
  /** 新文件行号（del 行为 null） */
  newLine: number | null
}

/** 一个连续变更块 */
export interface DiffHunk {
  /** 原始 `@@ ... @@` 头（前端计算结果为按实际行号重建的头） */
  header: string
  oldStart: number
  oldCount: number
  newStart: number
  newCount: number
  lines: DiffLine[]
  added: number
  removed: number
}

/** 单个文件的 diff 结果 */
export interface DiffFile {
  oldPath: string
  newPath: string
  hunks: DiffHunk[]
  added: number
  removed: number
  /** 二进制文件（不解析 hunk，只提示不可查看） */
  binary: boolean
}

/** LCS DP 表上限（单元格数）。超过则降级为逐行对齐，避免 2000×2000 以上的内存尖峰 */
const MAX_DP_CELLS = 4_000_000

/** 默认上下文行数（与 git 一致） */
const DEFAULT_CONTEXT = 3

function normalize(text: string): string {
  return (text ?? '').replace(/\r\n/g, '\n').replace(/\r/g, '\n')
}

/** 生成 `@@` 头里的行区间文本；count 为 0 时按 git 习惯退一行 */
function formatRange(start: number, count: number): string {
  if (count <= 0) return `${Math.max(0, start - 1)},0`
  if (count === 1) return String(start)
  return `${start},${count}`
}

function buildHeader(oldStart: number, oldCount: number, newStart: number, newCount: number): string {
  return `@@ -${formatRange(oldStart, oldCount)} +${formatRange(newStart, newCount)} @@`
}

/* ============================ 1. unified diff 解析 ============================ */

/**
 * 解析 `git diff` 的 unified 输出。
 * <p>只关心「一个或多个文件 + 每文件多个 hunk」这一层；`index` / `mode` 之类的
 * 元信息直接跳过，二进制文件置 `binary=true` 后不再解析 hunk。</p>
 */
export function parseUnifiedDiff(raw: string): DiffFile[] {
  const source = normalize(raw)
  if (!source.trim()) return []
  const rows = source.split('\n')
  const files: DiffFile[] = []
  let file: DiffFile | null = null
  let hunk: DiffHunk | null = null
  let oldCursor = 0
  let newCursor = 0

  const flushHunk = () => {
    if (!hunk) return
    if (file) {
      file.hunks.push(hunk)
      file.added += hunk.added
      file.removed += hunk.removed
    }
    hunk = null
  }
  const flushFile = () => {
    flushHunk()
    if (file) files.push(file)
    file = null
  }

  for (const row of rows) {
    if (row.startsWith('diff --git ')) {
      flushFile()
      const matched = row.match(/^diff --git a\/(.+?) b\/(.+)$/)
      file = {
        oldPath: matched?.[1] ?? '',
        newPath: matched?.[2] ?? '',
        hunks: [],
        added: 0,
        removed: 0,
        binary: false
      }
      continue
    }
    if (!file) {
      /*
       * 兜底：有些调用方只拿到「文件头 + hunk」的裸 diff（没有 `diff --git` 行，
       * 例如后端做裁剪、或 `git diff --no-prefix` 的变体）。
       * 这里必须在 `--- ` 分支之前创建，否则 `continue` 会把整段静默丢掉。
       */
      if (!row.startsWith('--- ')) continue
      file = { oldPath: '', newPath: '', hunks: [], added: 0, removed: 0, binary: false }
    }

    if (row.startsWith('--- ')) {
      const path = row.slice(4).trim()
      file.oldPath = path === '/dev/null' ? '' : path.replace(/^a\//, '')
      continue
    }
    if (row.startsWith('+++ ')) {
      const path = row.slice(4).trim()
      file.newPath = path === '/dev/null' ? '' : path.replace(/^b\//, '')
      continue
    }
    if (row.startsWith('Binary files ') || row.startsWith('GIT binary patch')) {
      file.binary = true
      continue
    }
    if (row.startsWith('@@')) {
      flushHunk()
      const matched = row.match(/^@@ -(\d+)(?:,(\d+))? \+(\d+)(?:,(\d+))? @@/)
      if (!matched) continue
      const oldStart = Number(matched[1])
      const oldCount = matched[2] === undefined ? 1 : Number(matched[2])
      const newStart = Number(matched[3])
      const newCount = matched[4] === undefined ? 1 : Number(matched[4])
      hunk = { header: row, oldStart, oldCount, newStart, newCount, lines: [], added: 0, removed: 0 }
      oldCursor = oldStart
      newCursor = newStart
      continue
    }
    if (!hunk) continue
    // `\ No newline at end of file` —— 没有实际行内容
    if (row.startsWith('\\')) continue
    if (!row) continue

    const marker = row[0]
    const text = row.slice(1)
    if (marker === '+') {
      hunk.lines.push({ type: 'add', text, oldLine: null, newLine: newCursor++ })
      hunk.added++
    } else if (marker === '-') {
      hunk.lines.push({ type: 'del', text, oldLine: oldCursor++, newLine: null })
      hunk.removed++
    } else if (marker === ' ') {
      hunk.lines.push({ type: 'ctx', text, oldLine: oldCursor++, newLine: newCursor++ })
    }
    // 其它前缀（如 `index ...`）已在上面被忽略
  }
  flushFile()
  return files
}

/* ============================ 2. 前端 LCS ============================ */

type OpType = 'ctx' | 'add' | 'del'
interface Op {
  type: OpType
  text: string
}

/**
 * 计算 old → new 的行级编辑脚本。
 * <p>先裁掉公共前后缀（绝大多数改动只集中在文件中间一小段），再对剩余部分做 LCS；
 * 剩余矩阵过大时降级为「逐行对齐」，保证不卡死（大文件下 diff 精度略降但不报错）。</p>
 */
function diffOps(oldLines: string[], newLines: string[]): Op[] {
  const n = oldLines.length
  const m = newLines.length
  let prefix = 0
  while (prefix < n && prefix < m && oldLines[prefix] === newLines[prefix]) prefix++
  let suffix = 0
  while (suffix < n - prefix && suffix < m - prefix && oldLines[n - 1 - suffix] === newLines[m - 1 - suffix]) {
    suffix++
  }

  const ops: Op[] = []
  for (let i = 0; i < prefix; i++) ops.push({ type: 'ctx', text: oldLines[i] })

  const a = oldLines.slice(prefix, n - suffix)
  const b = newLines.slice(prefix, m - suffix)

  if (a.length * b.length > MAX_DP_CELLS) {
    const max = Math.max(a.length, b.length)
    for (let i = 0; i < max; i++) {
      const left = i < a.length ? a[i] : null
      const right = i < b.length ? b[i] : null
      if (left !== null && right !== null) {
        if (left === right) ops.push({ type: 'ctx', text: left })
        else {
          ops.push({ type: 'del', text: left })
          ops.push({ type: 'add', text: right })
        }
      } else if (left !== null) ops.push({ type: 'del', text: left })
      else if (right !== null) ops.push({ type: 'add', text: right })
    }
  } else if (a.length && b.length) {
    const width = b.length + 1
    const dp = new Uint32Array((a.length + 1) * width)
    for (let i = 1; i <= a.length; i++) {
      for (let j = 1; j <= b.length; j++) {
        dp[i * width + j] =
          a[i - 1] === b[j - 1]
            ? dp[(i - 1) * width + (j - 1)] + 1
            : Math.max(dp[(i - 1) * width + j], dp[i * width + (j - 1)])
      }
    }
    let i = a.length
    let j = b.length
    const reversed: Op[] = []
    while (i > 0 || j > 0) {
      if (i > 0 && j > 0 && a[i - 1] === b[j - 1]) {
        reversed.push({ type: 'ctx', text: a[i - 1] })
        i--
        j--
      } else if (j > 0 && (i === 0 || dp[i * width + (j - 1)] >= dp[(i - 1) * width + j])) {
        reversed.push({ type: 'add', text: b[j - 1] })
        j--
      } else {
        reversed.push({ type: 'del', text: a[i - 1] })
        i--
      }
    }
    for (let k = reversed.length - 1; k >= 0; k--) ops.push(reversed[k])
  } else {
    for (const line of a) ops.push({ type: 'del', text: line })
    for (const line of b) ops.push({ type: 'add', text: line })
  }

  for (let i = n - suffix; i < n; i++) ops.push({ type: 'ctx', text: oldLines[i] })
  return ops
}

/**
 * 前端计算两个版本之间的 hunks（无 git 仓库时的兜底：快照视图）。
 * @param context 上下文行数，默认 3
 */
export function computeHunks(oldText: string, newText: string, context = DEFAULT_CONTEXT): DiffHunk[] {
  const oldLines = normalize(oldText).split('\n')
  const newLines = normalize(newText).split('\n')
  const ops = diffOps(oldLines, newLines)

  // 为每个 op 预先分配 old / new 行号
  const oldNos: Array<number | null> = []
  const newNos: Array<number | null> = []
  let oc = 1
  let nc = 1
  for (const op of ops) {
    if (op.type === 'ctx') {
      oldNos.push(oc++)
      newNos.push(nc++)
    } else if (op.type === 'del') {
      oldNos.push(oc++)
      newNos.push(null)
    } else {
      oldNos.push(null)
      newNos.push(nc++)
    }
  }

  const changed: number[] = []
  ops.forEach((op, index) => {
    if (op.type !== 'ctx') changed.push(index)
  })
  if (!changed.length) return []

  // 按上下文把变更点合并成区间（相邻/重叠的合成一个 hunk）
  const ranges: Array<[number, number]> = []
  let start = Math.max(0, changed[0] - context)
  let end = Math.min(ops.length - 1, changed[0] + context)
  for (let k = 1; k < changed.length; k++) {
    const index = changed[k]
    if (index - context <= end + 1) {
      end = Math.min(ops.length - 1, index + context)
    } else {
      ranges.push([start, end])
      start = Math.max(0, index - context)
      end = Math.min(ops.length - 1, index + context)
    }
  }
  ranges.push([start, end])

  return ranges.map(([from, to]) => {
    const lines: DiffLine[] = []
    for (let i = from; i <= to; i++) {
      lines.push({ type: ops[i].type, text: ops[i].text, oldLine: oldNos[i], newLine: newNos[i] })
    }
    const firstOld = lines.find(line => line.type !== 'add')?.oldLine ?? 1
    const firstNew = lines.find(line => line.type !== 'del')?.newLine ?? 1
    const oldCount = lines.filter(line => line.type !== 'add').length
    const newCount = lines.filter(line => line.type !== 'del').length
    return {
      header: buildHeader(firstOld, oldCount, firstNew, newCount),
      oldStart: firstOld,
      oldCount,
      newStart: firstNew,
      newCount,
      lines,
      added: lines.filter(line => line.type === 'add').length,
      removed: lines.filter(line => line.type === 'del').length
    }
  })
}

/* ============================ 3. patch 输出 ============================ */

/** 把 hunk 还原成 unified patch 片段（带 `@@` 头，不含 `---/+++` 文件头） */
export function hunkPatch(hunk: DiffHunk): string {
  const body = hunk.lines
    .map(line => `${line.type === 'add' ? '+' : line.type === 'del' ? '-' : ' '}${line.text}`)
    .join('\n')
  return `${hunk.header}\n${body}\n`
}

/** 多个 hunk 拼成一段 patch（一次撤销多块时用） */
export function hunksPatch(hunks: DiffHunk[]): string {
  return hunks.map(hunkPatch).join('')
}

/**
 * 只保留 hunk 中某一行变更的 patch（「撤销此行」）。
 * <p>非目标 `+` 行改写成上下文行、非目标 `-` 行直接剔除 —— 详见文件头说明。</p>
 * @returns 目标行不是变更行时返回 null
 */
export function patchForLine(hunk: DiffHunk, lineIndex: number): string | null {
  const target = hunk.lines[lineIndex]
  if (!target || target.type === 'ctx') return null

  const kept: DiffLine[] = []
  hunk.lines.forEach((line, index) => {
    if (line.type === 'ctx') {
      kept.push(line)
    } else if (line.type === 'add') {
      kept.push(index === lineIndex ? line : { type: 'ctx', text: line.text, oldLine: null, newLine: line.newLine })
    } else if (index === lineIndex) {
      kept.push(line)
    }
  })
  if (!kept.length) return null

  const oldCount = kept.filter(line => line.type !== 'add').length
  const newCount = kept.filter(line => line.type !== 'del').length
  const firstOld = kept.find(line => line.type !== 'add')?.oldLine ?? hunk.oldStart
  const firstNew = kept.find(line => line.type !== 'del')?.newLine ?? hunk.newStart
  const header = buildHeader(firstOld, oldCount, firstNew, newCount)
  const body = kept
    .map(line => `${line.type === 'add' ? '+' : line.type === 'del' ? '-' : ' '}${line.text}`)
    .join('\n')
  return `${header}\n${body}\n`
}

/* ============================ 4. 统计与工具 ============================ */

export interface DiffStats {
  added: number
  removed: number
  hunks: number
}

/** 汇总若干 hunk 的增删行数 */
export function summarize(hunks: DiffHunk[]): DiffStats {
  let added = 0
  let removed = 0
  for (const hunk of hunks) {
    added += hunk.added
    removed += hunk.removed
  }
  return { added, removed, hunks: hunks.length }
}

/**
 * 从 hunk 推断变更所在的「新文件行号区间」（用于双栏视图定位与折叠提示）。
 */
export function newRangeOf(hunk: DiffHunk): [number, number] {
  const start = hunk.newStart
  return [start, start + Math.max(hunk.newCount, 1) - 1]
}

/**
 * 双栏视图的数据源：把一个 hunk 内的行拆成左右两列。
 * <p>左右行数一定相等（不足的用占位补齐），这样两栏才能靠固定行高对齐。</p>
 */
export interface SplitRow {
  type: OpType
  left: DiffLine | null
  right: DiffLine | null
}

export function splitHunk(hunk: DiffHunk): SplitRow[] {
  const rows: SplitRow[] = []
  let pendingDel: DiffLine[] = []
  let pendingAdd: DiffLine[] = []

  const flush = () => {
    const max = Math.max(pendingDel.length, pendingAdd.length)
    for (let i = 0; i < max; i++) {
      const left = pendingDel[i] ?? null
      const right = pendingAdd[i] ?? null
      rows.push({ type: left && right ? 'del' : left ? 'del' : 'add', left, right })
    }
    pendingDel = []
    pendingAdd = []
  }

  for (const line of hunk.lines) {
    if (line.type === 'del') {
      pendingDel.push(line)
    } else if (line.type === 'add') {
      pendingAdd.push(line)
    } else {
      flush()
      rows.push({ type: 'ctx', left: line, right: line })
    }
  }
  flush()
  return rows
}
