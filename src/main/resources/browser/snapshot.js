/**
 * CodeCraft 页面快照采集脚本（在目标页面内通过 CDP Runtime.evaluate 执行）
 *
 * ── 为什么返回字符串而不是对象 ───────────────────────────────────────
 * Runtime.evaluate 的 returnByValue 对循环引用、DOM 节点、超大对象都可能出问题。
 * 统一 JSON.stringify 成字符串再回传，Java 侧 parse 一次即可，最稳。
 *
 * ── 为什么是「紧凑文本 + ref」而不是 HTML ─────────────────────────────
 * 把整页 HTML 丢给模型动辄几万 token，而且模型还得自己推断哪个元素可点。
 * 这里只输出「可见 + 可交互」的元素（通常 20~60 个），每个一行带稳定 ref：
 *
 *   e12  button  「提交订单」
 *   e13  input   [textbox] 提示=手机号
 *
 * 模型点 e12 即可，不需要猜坐标、不需要写选择器。
 * ref 存在 window.__ccRefs，每次 snapshot 全量重建 —— 旧 ref 一律失效，
 * 这是刻意的：页面结构变了还让旧 ref 生效，点错位置的代价比「重新 snapshot」高得多。
 *
 * ── 遍历策略（P1 新增）─────────────────────────────────────────────
 * 单纯的 document.querySelectorAll 会漏掉两类现代页面里大量存在的元素：
 *   1. Shadow DOM（Web Components）内部 —— querySelector 不穿透 shadow 边界
 *   2. iframe 内部 —— 元素属于另一个 document
 * 所以这里改成递归遍历：document → open shadowRoot → 同源 iframe.contentDocument。
 * 只有 open 模式的 shadowRoot 可访问（closed 模式浏览器不暴露，无解）；
 * 跨域 iframe 受同源策略限制，只能统计数量并在回执里提示改用截图。
 *
 * ── 安全检查 ──────────────────────────────────────────────────────
 * input[type=password] 的值永不输出（只显示「密码已隐藏」），
 * 否则密码会进入工具回执 → 对话上下文 → 数据库，一路留痕。
 */
(function (opts) {
  'use strict'

  var MAX_ELEMENTS = (opts && opts.maxElements) || 60
  var LABEL_LIMIT = (opts && opts.labelLimit) || 60
  var TEXT_LIMIT = (opts && opts.textLimit) || 1200

  /** 候选元素总数上限：超大页面（几万个节点）下防止 querySelectorAll('*') 把页面卡住 */
  var MAX_SCAN_NODES = 4000
  /** shadow 嵌套深度上限：Web Components 套娃极少超过 3 层 */
  var MAX_SHADOW_DEPTH = 4
  /** iframe 嵌套深度上限 */
  var MAX_FRAME_DEPTH = 3

  /** 压成单行（用于元素文案） */
  function clipLine (s, n) {
    s = String(s == null ? '' : s).replace(/\s+/g, ' ').trim()
    return s.length > n ? s.slice(0, n) + '…' : s
  }

  /** 保留换行（用于页面正文） */
  function clipBlock (s, n) {
    s = String(s == null ? '' : s).replace(/\r/g, '')
    s = s.split('\n')
      .map(function (line) { return line.replace(/[ \t]+/g, ' ').trim() })
      .filter(function (line) { return line.length > 0 })
      .join('\n')
    return s.length > n ? s.slice(0, n) + '\n…(正文已截断)' : s
  }

  var SELECTOR = [
    'a[href]',
    'button',
    'input',
    'select',
    'textarea',
    'summary',
    '[role="button"]',
    '[role="link"]',
    '[role="checkbox"]',
    '[role="radio"]',
    '[role="tab"]',
    '[role="menuitem"]',
    '[role="switch"]',
    '[role="option"]',
    '[role="combobox"]',
    '[role="textbox"]',
    '[onclick]',
    '[contenteditable="true"]',
    '[tabindex]:not([tabindex="-1"])'
  ].join(',')

  function viewOf (el) {
    try {
      return (el.ownerDocument && el.ownerDocument.defaultView) || window
    } catch (e) {
      return window
    }
  }

  function isVisible (el) {
    if (!el || !el.isConnected) return false
    var rects
    try {
      rects = el.getClientRects()
    } catch (e) {
      return false
    }
    if (!rects || rects.length === 0) return false
    var r = rects[0]
    if (r.width <= 0 && r.height <= 0) return false
    var view = viewOf(el)
    var style = view && view.getComputedStyle ? view.getComputedStyle(el) : null
    if (!style) return false
    if (style.visibility === 'hidden' || style.display === 'none') return false
    // 注意不能用 === '0'：opacity 可能是 undefined 或空串
    var opacity = parseFloat(style.opacity)
    if (!isNaN(opacity) && opacity === 0) return false
    return true
  }

  function roleOf (el) {
    var role = el.getAttribute && el.getAttribute('role')
    if (role) return role
    var tag = el.tagName.toLowerCase()
    if (tag === 'a') return 'link'
    if (tag === 'button') return 'button'
    if (tag === 'select') return 'combobox'
    if (tag === 'textarea') return 'textbox'
    if (tag === 'summary') return 'button'
    if (tag === 'input') {
      var t = (el.type || 'text').toLowerCase()
      if (t === 'submit' || t === 'button' || t === 'reset' || t === 'image') return 'button'
      if (t === 'checkbox') return 'checkbox'
      if (t === 'radio') return 'radio'
      if (t === 'file') return 'file'
      if (t === 'search') return 'searchbox'
      if (t === 'range') return 'slider'
      return 'textbox'
    }
    return tag
  }

  function labelOf (el) {
    var tag = el.tagName.toLowerCase()
    var parts = []

    var aria = el.getAttribute && el.getAttribute('aria-label')
    if (aria && aria.trim()) parts.push(aria.trim())

    if (tag === 'input' || tag === 'textarea') {
      var type = (el.type || '').toLowerCase()
      if (type === 'password') {
        // 关键：密码值绝不落入回执 / 上下文 / 数据库
        parts.push('[密码已隐藏]')
      } else if (el.value) {
        parts.push('值=' + el.value)
      }
      if (el.placeholder) parts.push('提示=' + el.placeholder)
    } else if (tag === 'select') {
      var options = el.options || []
      var selected = options[el.selectedIndex]
      if (selected) parts.push('选中=' + (selected.text || selected.value))
      if (options.length) parts.push('共 ' + options.length + ' 项')
    } else {
      var text = el.innerText || el.textContent || ''
      if (text.trim()) parts.push(text)
    }

    if (tag === 'a' && el.getAttribute) {
      var href = el.getAttribute('href')
      if (href) parts.push('→' + href)
    }

    if (!parts.length && el.getAttribute) {
      var title = el.getAttribute('title')
      if (title) parts.push(title)
      var alt = el.getAttribute('alt')
      if (alt) parts.push(alt)
    }

    return clipLine(parts.join(' | '), LABEL_LIMIT)
  }

  var vp = {
    width: window.innerWidth || 0,
    height: window.innerHeight || 0
  }

  // ── 递归采集（document → open shadowRoot → 同源 iframe）───────────
  var candidates = []
  var shadowRoots = 0
  var sameOriginFrames = 0
  var crossOriginFrames = 0

  function collectFrom (root, ctx, depth) {
    if (!root) return
    if (candidates.length >= MAX_SCAN_NODES) return

    var nodes = []
    try {
      nodes = Array.prototype.slice.call(root.querySelectorAll(SELECTOR))
    } catch (e) {
      nodes = []
    }
    for (var i = 0; i < nodes.length && candidates.length < MAX_SCAN_NODES; i++) {
      candidates.push({ el: nodes[i], ctx: ctx })
    }

    var all = []
    try {
      all = Array.prototype.slice.call(root.querySelectorAll('*'))
    } catch (e) {
      all = []
    }

    // 1) open shadow root（closed 模式浏览器不暴露，无法穿透）
    if (depth < MAX_SHADOW_DEPTH) {
      for (var j = 0; j < all.length && candidates.length < MAX_SCAN_NODES; j++) {
        var host = all[j]
        var sr = null
        try { sr = host.shadowRoot } catch (e) { sr = null }
        if (sr) {
          shadowRoots++
          // shadow 内部坐标与顶层一致（不产生坐标系偏移），ctx 原样传下去
          collectFrom(sr, ctx, depth + 1)
        }
      }
    }

    // 2) 同源 iframe（跨域 contentDocument 为 null，只能计数）
    if (depth < MAX_FRAME_DEPTH) {
      var frames = []
      try {
        frames = Array.prototype.slice.call(root.querySelectorAll('iframe, frame'))
      } catch (e) {
        frames = []
      }
      for (var k = 0; k < frames.length; k++) {
        if (candidates.length >= MAX_SCAN_NODES) break
        var fr = frames[k]
        var doc = null
        try { doc = fr.contentDocument } catch (e) { doc = null }
        if (!doc) {
          crossOriginFrames++
          // 跨域 iframe 读不到内部 DOM（同源策略，无解），但它是「点进去」的唯一入口：
          // 把它本身作为候选输出（带 bbox），模型能用相对坐标 click（rx/ry）点到里面。
          // 这比「跨域就整块跳过、让模型去截图猜坐标」实用得多。
          var crossRect = null
          try { crossRect = fr.getBoundingClientRect() } catch (e) { crossRect = null }
          if (crossRect && crossRect.width > 4 && crossRect.height > 4) {
            candidates.push({ el: fr, ctx: ctx, crossOrigin: true })
          }
          continue
        }
        sameOriginFrames++
        var rect = null
        try { rect = fr.getBoundingClientRect() } catch (e) { rect = null }
        if (!rect) continue
        collectFrom(doc, {
          frame: ctx.frame ? ctx.frame + '>iframe' : 'iframe',
          host: ctx.host || fr,
          offsetX: ctx.offsetX + rect.left + (fr.clientLeft || 0),
          offsetY: ctx.offsetY + rect.top + (fr.clientTop || 0)
        }, depth + 1)
      }
    }
  }

  try {
    collectFrom(document, { frame: '', host: null, offsetX: 0, offsetY: 0 }, 0)
  } catch (e) {
    /* 采集失败时保持空清单，由 Java 侧提示 */
  }

  var iframeTotal = 0
  try {
    iframeTotal = document.querySelectorAll('iframe, frame').length
  } catch (e) {
    iframeTotal = 0
  }

  // ── 可见性过滤（iframe 内元素还要求宿主可见）──────────────────────
  var visible = []
  for (var v = 0; v < candidates.length; v++) {
    var item = candidates[v]
    if (!isVisible(item.el)) continue
    if (item.ctx.host && !isVisible(item.ctx.host)) continue
    visible.push(item)
  }

  // ── 排序：视口内的优先，其次按从上到下、从左到右 ─────────────────
  var scored = visible.map(function (item, index) {
    var r
    try {
      r = item.el.getBoundingClientRect()
    } catch (e) {
      r = { left: 0, top: 0, width: 0, height: 0 }
    }
    var left = r.left + item.ctx.offsetX
    var top = r.top + item.ctx.offsetY
    var inView = (left + r.width) > 0 && left < vp.width && (top + r.height) > 0 && top < vp.height
    return {
      item: item,
      inView: inView ? 0 : 1,
      top: top,
      left: left,
      index: index
    }
  })
  scored.sort(function (a, b) {
    if (a.inView !== b.inView) return a.inView - b.inView
    if (Math.abs(a.top - b.top) > 4) return a.top - b.top
    if (a.left !== b.left) return a.left - b.left
    return a.index - b.index
  })

  var picked = scored.slice(0, MAX_ELEMENTS)

  // ── 重建 ref 表（旧 ref 全部作废，这是刻意的）────────────────────
  var refs = Object.create(null)
  var elements = []
  for (var p = 0; p < picked.length; p++) {
    var record = picked[p]
    var el = record.item.el
    var ctx = record.item.ctx
    var ref = 'e' + (p + 1)
    refs[ref] = el

    // 供 click / fill / press 使用：iframe 内元素要先滚动宿主，再按动态偏移算坐标
    if (ctx.frame || record.item.crossOrigin) {
      try {
        if (ctx.frame) el.__ccFrame = ctx.frame
        if (ctx.host) el.__ccHost = ctx.host
        // 跨域 iframe 本身作为一个可点区域：坐标表达式据此提示「只能按比例点」
        if (record.item.crossOrigin) el.__ccCrossOrigin = true
      } catch (e) { /* 个别宿主对象不允许扩展属性，忽略 */ }
    }

    var rect = el.getBoundingClientRect()
    var out = {
      ref: ref,
      role: roleOf(el),
      label: labelOf(el),
      inViewport: record.inView === 0,
      x: Math.round(rect.left + ctx.offsetX),
      y: Math.round(rect.top + ctx.offsetY),
      w: Math.round(rect.width),
      h: Math.round(rect.height)
    }
    if (ctx.frame) out.frame = ctx.frame
    if (record.item.crossOrigin) {
      out.crossOrigin = true
      // 跨域 iframe 的 label 兜底成 src：模型据此知道这块区域是哪个站点
      if (!out.label) {
        var srcAttr = ''
        try { srcAttr = el.getAttribute('src') || el.src || '' } catch (e) { srcAttr = '' }
        out.label = srcAttr
          ? ('跨域 iframe：' + clipLine(srcAttr, 120))
          : '跨域 iframe（内部 DOM 不可读，可用 rx/ry 相对坐标点击）'
      }
    }
    if (el.disabled) out.disabled = true
    var tag = el.tagName.toLowerCase()
    if (tag === 'input') {
      var type = (el.type || 'text').toLowerCase()
      if (type !== 'password') out.type = type
      if (type === 'checkbox' || type === 'radio') out.checked = !!el.checked
    }
    if (tag === 'select') {
      // 把选项清单一起带上：模型据此知道有哪些可选值，不必再猜或反复试错
      try {
        var list = el.options || []
        if (list.length) {
          var listed = []
          for (var oi = 0; oi < list.length && listed.length < 20; oi++) {
            var o = list[oi]
            listed.push(clipLine((o.text || '') + '→' + (o.value == null ? '' : o.value), 40))
          }
          out.options = listed
          out.multiple = !!el.multiple
        }
      } catch (e) { /* 忽略 */ }
    }
    elements.push(out)
  }
  window.__ccRefs = refs

  // ── 弹窗提示：有模态框挡着时模型点不到下面的元素，必须知道 ────────
  var dialogs = []
  try {
    var dialogNodes = document.querySelectorAll('[role="dialog"], [role="alertdialog"], dialog[open]')
    for (var d = 0; d < dialogNodes.length && dialogs.length < 5; d++) {
      if (isVisible(dialogNodes[d])) {
        dialogs.push(clipLine(dialogNodes[d].innerText || dialogNodes[d].textContent, 100))
      }
    }
  } catch (e) {
    /* 忽略 */
  }

  var bodyText = ''
  try {
    bodyText = document.body ? document.body.innerText || '' : ''
  } catch (e) {
    bodyText = ''
  }

  // 顶层文档的滚动信息（iframe 内滚动不计入，模型只关心主页面）
  var docHeight = 0
  try {
    docHeight = document.documentElement
      ? Math.max(document.documentElement.scrollHeight,
        document.body ? document.body.scrollHeight : 0)
      : 0
  } catch (e) {
    docHeight = 0
  }

  return JSON.stringify({
    ok: true,
    url: location.href,
    title: document.title || '',
    readyState: document.readyState || '',
    viewport: {
      width: vp.width,
      height: vp.height,
      scrollX: Math.round(window.scrollX || 0),
      scrollY: Math.round(window.scrollY || 0),
      docHeight: docHeight
    },
    text: clipBlock(bodyText, TEXT_LIMIT),
    totalInteractive: candidates.length,
    visibleInteractive: visible.length,
    returned: elements.length,
    truncated: visible.length > elements.length,
    dialogs: dialogs,
    iframes: iframeTotal,
    sameOriginFrames: sameOriginFrames,
    crossOriginFrames: crossOriginFrames,
    shadowRoots: shadowRoots,
    elements: elements
  })
})
