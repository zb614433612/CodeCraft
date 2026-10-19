#!/usr/bin/env node
/**
 * API 契约检查：前端 `src/api/*.ts` 的调用 ⟷ 后端 Controller 的映射
 * ============================================================================
 * 背景（L1 收口时发现的真问题）：
 *   旧项目有一批 api 函数把后端的 GET 接口写成 POST 调用（例如
 *   `POST /api/agent-configs/list`，而后端是 `@GetMapping("/list")`），
 *   实测返回 405。这些函数大概率从未被 UI 调用（死代码），或者对应页面一直是坏的。
 *   L4 逐页接线时若照抄旧代码，会直接踩坑 —— 因此需要这个脚本随时核对。
 *
 * 用法：
 *   node scripts/check-api-contract.cjs [后端源码根目录]
 *   CC_BACKEND_SRC=/path/to/src/main/java npm run check:api
 *
 * 输出：
 *   [A] 前端调用了但后端没有对应 (方法, 路径)：必须修（会 404/405）
 *   [B] 后端有映射但前端 api 层未使用：仅提示（可能由 SSE / 其它层调用）
 *   退出码：A 非空时返回 1，可用于 CI
 *
 * 归一化规则：
 *   · `${...}` 与 `{var}` 都视为路径变量（另提供「整体剔除」变体，兼容 `${qs ? '?' + qs : ''}` 这类查询串拼接）
 *   · 前端路径若不以 /api 开头则自动补上（http-client 的 toFullUrl 行为）
 *   · 先切查询串再归一化变量，避免 `?page=${page}` 被误解析成路径的一部分
 *   · 后端 `@RequestMapping`（不写 RequestMethod）视为支持任意方法
 *
 * 判定成「一次调用」的规则（这两条都曾因实现过粗而误报/漏报，改动时请留意）：
 *   · method 只在**本次调用的实参括号内**查找（括号配对），不跨函数。
 *     旧实现固定截取 320 字符，会把下一个函数的 `method: 'POST'` 算进来，
 *     导致 13 条 GET 被误判成 POST（git/lessons/p2p/schedule-task 全部躺枪）。
 *   · 首个实参可以是变量，回查同文件内的字面量赋值（含三元素表达式与 `+=` 追加）。
 *     旧实现只认紧跟的字面量，`const url = '/logs/search' + qs; request(url)` 会被整段漏扫，
 *     使 A 类出现假绿。
 */
const fs = require('fs')
const path = require('path')

const FRONT_DIR = path.resolve(__dirname, '../src/api')
const BACK_ROOT = path.resolve(
  process.argv[2] || process.env.CC_BACKEND_SRC || '../src/main/java'
)

/** 模板变量占位符：避免 `${qs ? '?' + qs : ''}` 里的 ? 干扰查询串切分 */
const VAR = '\u0000'

/** 基础归一化：抽出模板变量 → 切查询串 → 补 /api → 去重复/尾部斜杠 */
function basePath(raw) {
  let s = String(raw || '').trim()
  s = s.replace(/\$\{[^}]*\}/g, VAR)
  s = s.split('?')[0]
  if (!s.startsWith('/')) s = '/' + s
  if (!s.startsWith('/api/') && s !== '/api') s = '/api' + s
  s = s.replace(/\/{2,}/g, '/')
  if (s.length > 4 && s.endsWith('/')) s = s.slice(0, -1)
  return s
}

/**
 * 前端 URL 的两条归一化变体：
 *  v1 把变量当路径变量（`/files/${id}` → `/api/files/{}`，连续变量合并为一个）
 *  v2 把变量整体剔除（`/files${qs}` → `/api/files`）
 * 任一变体命中后端即视为通过。
 */
function frontVariants(raw) {
  const p = basePath(raw)
  return [
    p.replace(/\u0000+/g, '{}').replace(/\{[^}]*\}/g, '{}'),
    p.replace(/\u0000+/g, '').replace(/\/{2,}/g, '/').replace(/(.)\/$/, '$1')
  ]
}

function backKey(raw) {
  return basePath(raw).replace(/\{[^}]*\}/g, '{}')
}

const k = (method, p) => method.toUpperCase() + ' ' + p

// ---------- 前端 ----------
const front = []
const CALL_RE =
  /\b(request|get|post|put|del|upload|authFetch)\s*(?:<[^(\n]*>)?\s*\(\s*(`[^`]*`|'[^']*'|"[^"]*"|[A-Za-z_$][\w$]*)/g
const SIMPLE = { get: 'GET', post: 'POST', put: 'PUT', del: 'DELETE', upload: 'POST' }

/**
 * 定位本次调用的 `(`：正则命中的字符串字面量是第一个实参，
 * 它前面跳过空白后的那个字符就是调用括号本身。
 */
function openParenIndex(text, m) {
  const literalStart = m.index + m[0].length - m[2].length
  let i = literalStart - 1
  while (i > m.index && /\s/.test(text[i])) i--
  return text[i] === '(' ? i : literalStart
}

/**
 * 取出「本次调用」的实参文本：从调用的 `(` 开始做括号配对，遇到匹配的 `)` 停止。
 *
 * <p>不能像旧实现那样固定截取 320 字符 —— 那样会把**下一个函数**里的
 * `method: 'POST'` 算进本次调用，把 `request('/x')`（默认 GET）误判成 POST。
 * 这曾造成 13 条假阳性（git/lessons/p2p/schedule-task 等全是 GET 却报 405）。</p>
 */
function callArgsText(text, openParenIdx) {
  let depth = 0
  let quote = ''
  for (let i = openParenIdx; i < text.length; i++) {
    const ch = text[i]
    if (quote) {
      if (ch === '\\') {
        i++
        continue
      }
      if (ch === quote) quote = ''
      continue
    }
    if (ch === '"' || ch === "'" || ch === '`') {
      quote = ch
      continue
    }
    if (ch === '(') depth++
    else if (ch === ')') {
      depth--
      if (depth === 0) return text.slice(openParenIdx, i + 1)
    }
  }
  return text.slice(openParenIdx)
}

/**
 * 收集文件内的「变量 → 字面量候选值」映射。
 *
 * <p>为了覆盖 `const url = '/logs/search' + qs; request(url)` 这类变量间接调用 ——
 * 旧实现只认「`request(` 后紧跟字符串字面量」，这类写法会被整段漏扫，
 * 使 A 类检查出现假绿（实际有调用、却没被核对）。</p>
 *
 * <p>右值允许是三元素表达式（把其中所有字面量都收作候选），以及后续的 `x += '...'` 追加。</p>
 */
function collectVars(text) {
  const vars = new Map()
  const add = (name, val) => {
    if (!vars.has(name)) vars.set(name, [])
    if (!vars.get(name).includes(val)) vars.get(name).push(val)
  }
  let m
  const DECL_RE = /\b(?:const|let|var)\s+([A-Za-z_$][\w$]*)\s*=\s*([^\n;]+)/g
  while ((m = DECL_RE.exec(text))) {
    const literals = m[2].match(/`[^`]*`|'[^']*'|"[^"]*"/g)
    if (literals) literals.forEach((l) => add(m[1], l.slice(1, -1)))
  }
  const APPEND_RE = /\b([A-Za-z_$][\w$]*)\s*\+=\s*(`[^`]*`|'[^']*'|"[^"]*")/g
  while ((m = APPEND_RE.exec(text))) add(m[1], m[2].slice(1, -1))
  return vars
}

/**
 * 解析调用的首个实参：字面量直接用，标识符回查 {@link collectVars} 的候选值。
 * 无法静态确定（如参数透传 `url: string`）时返回空数组，表示「不检查」。
 */
function resolveFirstArg(literalOrName, vars) {
  if (/^[`'"]/.test(literalOrName)) return [literalOrName.slice(1, -1)]
  return vars.get(literalOrName) || []
}

if (!fs.existsSync(FRONT_DIR)) {
  console.error('前端 api 目录不存在：' + FRONT_DIR)
  process.exit(2)
}

for (const file of fs.readdirSync(FRONT_DIR).filter((f) => f.endsWith('.ts'))) {
  const text = fs.readFileSync(path.join(FRONT_DIR, file), 'utf8')
  const vars = collectVars(text)
  let m
  CALL_RE.lastIndex = 0
  while ((m = CALL_RE.exec(text))) {
    const raws = resolveFirstArg(m[2], vars)
    if (!raws.length) continue
    const args = callArgsText(text, openParenIndex(text, m))
    const mm = /method\s*:\s*['"]([A-Za-z]+)['"]/.exec(args)
    const method = SIMPLE[m[1]] || (mm ? mm[1].toUpperCase() : 'GET')
    const loc = file + ':' + text.slice(0, m.index).split('\n').length
    for (const raw of raws) {
      if (!raw.startsWith('/')) continue
      front.push({
        method,
        raw,
        variants: frontVariants(raw).map((v) => k(method, v)),
        loc
      })
    }
  }
}

// ---------- 后端 ----------
function walk(dir, out = []) {
  for (const e of fs.readdirSync(dir, { withFileTypes: true })) {
    const p = path.join(dir, e.name)
    if (e.isDirectory()) walk(p, out)
    else if (e.name.endsWith('Controller.java')) out.push(p)
  }
  return out
}

if (!fs.existsSync(BACK_ROOT)) {
  console.error('后端源码目录不存在：' + BACK_ROOT)
  process.exit(2)
}

const back = new Map()
const ALL = ['GET', 'POST', 'PUT', 'DELETE', 'PATCH']
const addBack = (method, full, file) => {
  const key = k(method, backKey(full))
  if (!back.has(key)) back.set(key, path.basename(file))
}

for (const file of walk(BACK_ROOT)) {
  const text = fs.readFileSync(file, 'utf8')
  const cls = /@RequestMapping\s*\(\s*(?:value\s*=\s*|path\s*=\s*)?["']([^"']+)["']/.exec(text)
  const base = cls ? cls[1] : ''

  let m
  const MAPPING_RE =
    /@(Get|Post|Put|Delete|Patch)Mapping\s*(?:\(\s*(?:value\s*=\s*|path\s*=\s*)?(?:["']([^"']*)["'])?)?/g
  while ((m = MAPPING_RE.exec(text))) addBack(m[1], base + (m[2] || ''), file)

  const RM_RE = /@RequestMapping\s*\(([^)]*)\)/g
  while ((m = RM_RE.exec(text))) {
    const args = m[1]
    const pm = /(?:value\s*=\s*|path\s*=\s*)?["']([^"']*)["']/.exec(args)
    if (!pm) continue
    const sub = pm[1]
    if (sub === base) continue
    const mm = /RequestMethod\.(GET|POST|PUT|DELETE|PATCH)/.exec(args)
    if (mm) addBack(mm[1], base + sub, file)
    else ALL.forEach((x) => addBack(x, base + sub, file))
  }
}

// ---------- 比对 ----------
const missing = front.filter((f) => !f.variants.some((v) => back.has(v)))
const usedKeys = new Set(front.flatMap((f) => f.variants.filter((v) => back.has(v))))
const unused = [...back.keys()].filter((key) => !usedKeys.has(key))

console.log('=== 契约检查：前端调用 ' + front.length + ' 处 ｜ 后端映射 ' + back.size + ' 个 ===\n')

console.log('[A] 前端调用了但后端没有对应 (方法, 路径) —— 必须修，否则 404/405')
if (!missing.length) {
  console.log('  (无)')
} else {
  missing
    .slice()
    .sort((a, b) => a.raw.localeCompare(b.raw))
    .forEach((f) => {
      console.log('  ' + k(f.method, frontVariants(f.raw)[0]) + '   <- ' + f.loc)
      console.log('      RAW=' + f.raw)
    })
}

console.log('\n[B] 后端有映射但前端 api 层未使用 —— 仅提示（SSE / 下载 / 其它层可能直连）')
if (!unused.length) console.log('  (无)')
unused.sort().forEach((key) => console.log('  ' + key + '   <- ' + back.get(key)))

console.log('\nRESULT: A=' + missing.length + '  B=' + unused.length)
process.exit(missing.length ? 1 : 0)
