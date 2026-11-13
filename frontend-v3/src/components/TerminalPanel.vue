<!--
  聊天页右侧抽屉 · 终端（真终端形态：xterm.js）
  ----------------------------------------------------------------------------
  数据接缝：
    POST /api/project/exec { command, workingDirectory, timeout }
      → { success, output, exitCode, timedOut }

  为什么用 xterm.js：
    旧版是「输入框 + 输出块」的日志式面板，视觉与操作都不像终端。这里改成真正的终端屏：
    提示符跟随当前目录、输入直接回显在同一块屏里、输出按 ANSI 转义着色、可框选复制、
    滚轮回看历史输出。后端 `/project/exec` 是「执行一次 → 返回完整输出」（无 PTY / 无 stdin），
    因此这是一个**行编辑器 + 执行器**：每敲一次回车执行一条命令，不做交互式程序（vi/top 跑不了）。

  支持的行编辑能力：
    · Enter 执行、Backspace 退格、Ctrl+C 中断当前输入、Ctrl+L 清屏
    · ↑ / ↓ 翻命令历史（落 localStorage，切页/重开抽屉不丢）
    · cd 由前端本地处理（不发给后端）：支持 `cd`（回 Agent 工作目录）、`cd ..`、绝对路径、
      相对路径；其余命令都带着当前 cwd 一起交给后端执行
    · clear / cls 等价于清屏

  主题：跟随「外观设置」的 theme（light / dark / auto），切换时实时换色。
-->
<template>
  <!-- 类名 cc-xterm：避免与第 14 节静态预览用的 .cc-term（深色示意块）撞名 -->
  <div class="cc-xterm">
    <div ref="hostRef" class="cc-xterm__screen" />
  </div>
</template>

<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { Terminal } from '@xterm/xterm'
import { FitAddon } from '@xterm/addon-fit'
import '@xterm/xterm/css/xterm.css'
import { execCommand } from '../api/project'
import { useSettingsStore } from '../store/settings'

const props = withDefaults(defineProps<{ root?: string }>(), { root: '' })

const settingsStore = useSettingsStore()

/* ---------------- 主题 ---------------- */

const DARK_THEME = {
  background: '#14131B',
  foreground: '#D7D4E6',
  cursor: '#A99BFF',
  selectionBackground: '#3B3556',
  black: '#14131B',
  red: '#FCA5A5',
  green: '#6EE7B7',
  yellow: '#FCD34D',
  blue: '#93C5FD',
  magenta: '#C4B5FD',
  cyan: '#67E8F9',
  white: '#D7D4E6',
  brightBlack: '#6B6880',
  brightRed: '#FCA5A5',
  brightGreen: '#6EE7B7',
  brightYellow: '#FDE68A',
  brightBlue: '#BFDBFE',
  brightMagenta: '#DDD6FE',
  brightCyan: '#A5F3FC',
  brightWhite: '#FFFFFF'
}

const LIGHT_THEME = {
  background: '#FAFAFB',
  foreground: '#2A2833',
  cursor: '#6D5BD0',
  selectionBackground: '#DCD5F5',
  black: '#2A2833',
  red: '#C0392B',
  green: '#1E8449',
  yellow: '#B7791F',
  blue: '#1F5FBF',
  magenta: '#7D3C98',
  cyan: '#0E7490',
  white: '#FAFAFB',
  brightBlack: '#8A8798',
  brightRed: '#E74C3C',
  brightGreen: '#27AE60',
  brightYellow: '#D4AC0D',
  brightBlue: '#3B82F6',
  brightMagenta: '#9B59B6',
  brightCyan: '#22B8CF',
  brightWhite: '#111111'
}

const prefersDark = ref(window.matchMedia('(prefers-color-scheme: dark)').matches)
const mq = window.matchMedia('(prefers-color-scheme: dark)')
const onSchemeChange = (event: MediaQueryListEvent) => {
  prefersDark.value = event.matches
}

/** 解析最终主题：auto 交给系统偏好 */
const resolvedTheme = computed<'light' | 'dark'>(() => {
  const mode = settingsStore.getTheme()
  if (mode === 'auto') return prefersDark.value ? 'dark' : 'light'
  return mode
})

/* ---------------- 终端实例 ---------------- */

const hostRef = ref<HTMLElement | null>(null)
let term: Terminal | null = null
let fitAddon: FitAddon | null = null
let resizeObserver: ResizeObserver | null = null
/** 命令执行中：屏蔽键盘输入，避免输出与输入交错 */
const executing = ref(false)
/** 当前工作目录（cd 只改这里，命令执行时带给后端） */
const cwd = ref(props.root)

/* ---------------- 真终端（PTY）----------------
 * Electron 桌面端：走主进程 node-pty 起真实 shell —— 支持 vim/top/ssh、Ctrl+C、Tab 补全、
 *   长命令实时出字（真终端形态）。
 * 浏览器环境：没有 preload，降级回既有的「输入行 + /project/exec」模式（功能有限但可用）。
 */
interface ElectronPtyApi {
  isAvailable: () => Promise<{ available: boolean; error?: string; defaultShell?: string }>
  create: (options: { cwd?: string; cols?: number; rows?: number; shell?: string }) => Promise<{
    sessionId: string
    pid: number
    shell: string
    cwd: string
  }>
  write: (sessionId: string, data: string) => Promise<boolean>
  resize: (sessionId: string, cols: number, rows: number) => Promise<boolean>
  kill: (sessionId: string) => Promise<boolean>
  onData: (callback: (payload: { sessionId: string; data: string }) => void) => () => void
  onExit: (callback: (payload: { sessionId: string; exitCode: number; signal?: number }) => void) => () => void
}

const ptyApi: ElectronPtyApi | undefined = (window as any).electronAPI?.pty

/** 当前是否处于真 PTY 模式（true 时所有按键原样转发给 shell，不再走本地输入行逻辑） */
const ptyMode = ref(false)
/** 当前 PTY 会话 ID（空串表示没有活跃会话） */
let ptySessionId = ''
let offPtyData: (() => void) | null = null
let offPtyExit: (() => void) | null = null
/** shell 自己 exit 后置 true：下一次按键用于重连（避免注册第二个 onData 监听） */
let ptyExited = false

let buffer = ''
let history: string[] = []
let historyIndex = -1
let savedInput = ''

const HISTORY_KEY = 'codecraft.terminal.history'
const HISTORY_MAX = 300

function loadHistory() {
  try {
    const raw = localStorage.getItem(HISTORY_KEY)
    if (!raw) return []
    const parsed = JSON.parse(raw)
    return Array.isArray(parsed) ? parsed.filter((x): x is string => typeof x === 'string').slice(-HISTORY_MAX) : []
  } catch {
    return []
  }
}

function saveHistory() {
  try {
    localStorage.setItem(HISTORY_KEY, JSON.stringify(history.slice(-HISTORY_MAX)))
  } catch {
    /* 隐私模式写入失败可忽略 */
  }
}

function pushHistory(command: string) {
  if (!command) return
  if (history[history.length - 1] !== command) history.push(command)
  if (history.length > HISTORY_MAX) history.shift()
  saveHistory()
  historyIndex = -1
  savedInput = ''
}

/** 提示符：把反斜杠统一成正斜杠，视觉上更像 Unix 终端 */
function promptText(): string {
  const dir = (cwd.value || props.root || '.').replace(/\\/g, '/')
  return `${dir} $ `
}

function writePrompt(withNewline = true) {
  if (!term) return
  if (withNewline) term.write('\r\n')
  term.write(`\x1b[38;5;141m${promptText()}\x1b[0m`)
  buffer = ''
}

/** 重绘当前输入行（先回到行首、清掉整行，再写提示符 + 缓冲） */
function redraw() {
  if (!term) return
  term.write(`\r\x1b[0K\x1b[38;5;141m${promptText()}\x1b[0m${buffer}`)
}

/** 后端输出转成 xterm 可渲染的行尾（\n → \r\n） */
function toTerminalText(text: string): string {
  return text.replace(/\r\n/g, '\n').replace(/\r/g, '\n').replace(/\n/g, '\r\n')
}

/* ---------------- cd ---------------- */

function joinPath(base: string, target: string): string {
  const normalizedBase = base.replace(/\\/g, '/').replace(/\/+$/, '')
  const normalizedTarget = target.replace(/\\/g, '/').replace(/^\/+/, '').replace(/\/+$/, '')
  return `${normalizedBase}/${normalizedTarget}`
}

function changeDir(target: string) {
  const current = (cwd.value || props.root || '').replace(/\\/g, '/')
  const arg = target.trim()

  if (!arg || arg === '~') {
    cwd.value = props.root
    return
  }
  if (arg === '.') return
  if (arg === '..') {
    const trimmed = current.replace(/\/+$/, '')
    // 保留 Windows 盘符根（C:/）与 Unix 根（/）
    const parent = trimmed.replace(/\/[^/]+$/, '')
    cwd.value = parent || trimmed
    return
  }
  // 绝对路径：C:/xxx、C:\xxx、/xxx、\\server\share
  if (/^[a-zA-Z]:/.test(arg) || arg.startsWith('/') || arg.startsWith('\\\\')) {
    cwd.value = arg.replace(/\\/g, '/').replace(/\/+$/, '')
    return
  }
  cwd.value = joinPath(current, arg)
}

/* ---------------- 命令执行 ---------------- */

async function runCommand(command: string) {
  if (!term) return
  const lower = command.toLowerCase()
  if (lower === 'clear' || lower === 'cls') {
    term.clear()
    writePrompt(false)
    return
  }
  if (lower === 'cd' || lower.startsWith('cd ')) {
    changeDir(command.slice(2))
    writePrompt()
    return
  }

  const workDir = cwd.value || props.root
  if (!workDir) {
    term.write('\r\n\x1b[33m[未设置工作目录] 先在智能体里配置工作目录，或在终端执行 cd 切换\x1b[0m')
    writePrompt()
    return
  }

  executing.value = true
  try {
    // 不传 timeout：后端默认 30s；timeout 单位是毫秒，误传小数会被立刻判超时
    const res = await execCommand(command, workDir)
    const output = (res.output || '').replace(/\s+$/, '')
    if (output) term.write('\r\n' + toTerminalText(output))
    if (res.timedOut) {
      term.write('\r\n\x1b[33m[命令超时（默认 30 秒）已被终止]\x1b[0m')
    } else if (!res.success && !output) {
      term.write(`\r\n\x1b[33m[命令执行失败，退出码 ${res.exitCode}]\x1b[0m`)
    } else if (!res.success) {
      term.write(`\r\n\x1b[33m[退出码 ${res.exitCode}]\x1b[0m`)
    }
  } catch (err) {
    const message = err instanceof Error ? err.message : '命令执行失败'
    term.write(`\r\n\x1b[31m[错误] ${message}\x1b[0m`)
  } finally {
    executing.value = false
    writePrompt()
  }
}

function submit() {
  if (!term) return
  const command = buffer.trim()
  buffer = ''
  term.write('\r\n')
  if (!command) {
    writePrompt(false)
    return
  }
  pushHistory(command)
  void runCommand(command)
}

function historyPrev() {
  if (!history.length) return
  if (historyIndex === -1) {
    savedInput = buffer
    historyIndex = history.length - 1
  } else if (historyIndex > 0) {
    historyIndex -= 1
  }
  buffer = history[historyIndex] ?? ''
  redraw()
}

function historyNext() {
  if (historyIndex === -1) return
  if (historyIndex < history.length - 1) {
    historyIndex += 1
    buffer = history[historyIndex] ?? ''
  } else {
    historyIndex = -1
    buffer = savedInput
    savedInput = ''
  }
  redraw()
}

/* ---------------- 初始化 / 销毁 ---------------- */

function applyTheme() {
  if (!term) return
  term.options.theme = resolvedTheme.value === 'dark' ? DARK_THEME : LIGHT_THEME
}

function fit() {
  if (!fitAddon || !hostRef.value) return
  // 抽屉还在滑入动画里时容器可能没有尺寸，fit 会抛错，忽略即可（ResizeObserver 会再触发）
  try {
    fitAddon.fit()
  } catch {
    return
  }
  // 真 PTY：尺寸必须同步给 shell，否则长命令换行错位、vim/top 界面错乱
  if (ptyMode.value && ptySessionId && term) {
    void ptyApi?.resize(ptySessionId, term.cols, term.rows)
  }
}

/**
 * 尝试启动真 PTY 会话。
 * @returns true = 已进入真终端模式（支持 vim/top/ssh、Ctrl+C、Tab 补全、实时输出）；
 *          false = 降级到 /project/exec 简易模式（浏览器环境或 node-pty 不可用）
 */
async function tryStartPty(): Promise<boolean> {
  if (!ptyApi?.isAvailable || !term) return false
  try {
    const info = await ptyApi.isAvailable()
    if (!info?.available) {
      term.writeln(`\x1b[33m[真终端不可用：${info?.error || '未知原因'}，已降级为简易模式（不支持交互式程序）]\x1b[0m`)
      return false
    }
    const session = await ptyApi.create({
      cwd: props.root || undefined,
      cols: term.cols,
      rows: term.rows
    })
    ptySessionId = session.sessionId
    ptyMode.value = true
    ptyExited = false

    // 输出：直接写进 xterm —— shell 已经处理好提示符 / 着色 / 换行，前端不再自行拼接
    offPtyData = ptyApi.onData((payload) => {
      if (!payload || payload.sessionId !== ptySessionId) return
      term?.write(payload.data)
    })

    // 退出：只标记状态，等下一次按键重连（避免在这里再注册一个 onData 造成监听器叠加）
    offPtyExit = ptyApi.onExit((payload) => {
      if (!payload || payload.sessionId !== ptySessionId) return
      ptySessionId = ''
      ptyMode.value = false
      ptyExited = true
      offPtyData?.(); offPtyData = null
      offPtyExit?.(); offPtyExit = null
      term?.write(`\r\n\x1b[33m[会话已结束 exitCode=${payload.exitCode}；按任意键重新连接]\x1b[0m\r\n`)
    })

    // 真终端不打印自绘 banner：shell 启动后会输出它自己的提示符与欢迎信息
    return true
  } catch (e: any) {
    term?.writeln(`\x1b[33m[真终端启动失败：${e?.message || '未知错误'}，已降级为简易模式]\x1b[0m`)
    return false
  }
}

function initTerminal() {
  if (!hostRef.value || term) return
  history = loadHistory()

  term = new Terminal({
    cursorBlink: true,
    fontSize: 12,
    fontFamily: "'JetBrains Mono','SF Mono','Consolas','Fira Code',monospace",
    lineHeight: 1.4,
    scrollback: 4000,
    convertEol: false,
    theme: resolvedTheme.value === 'dark' ? DARK_THEME : LIGHT_THEME
  })
  fitAddon = new FitAddon()
  term.loadAddon(fitAddon)
  term.open(hostRef.value)
  // 创建时的主题可能与当前解析结果存在时序差（store 恢复 / 系统偏好首次求值），
  // 挂载后强制对齐一次，避免「外壳浅色、终端深色」的割裂感
  applyTheme()
  fit()

  resizeObserver = new ResizeObserver(() => fit())
  resizeObserver.observe(hostRef.value)

  term.onData(data => {
    // 会话刚结束：任意键重连真 PTY（靠标记位，不重复注册监听器）
    if (ptyExited) {
      ptyExited = false
      void tryStartPty()
      return
    }
    // 真终端模式：所有按键（含 Ctrl+C / Tab / 方向键 / 输入法合成）原样转发给 shell，
    // 由 shell 自己处理行编辑、补全、信号与历史 —— 前端不再自绘提示符。
    if (ptyMode.value) {
      if (ptySessionId) void ptyApi?.write(ptySessionId, data)
      return
    }

    if (executing.value) return

    if (data === '\r') {
      submit()
      return
    }
    if (data === '\x7f') {
      if (buffer.length) {
        buffer = buffer.slice(0, -1)
        redraw()
      }
      return
    }
    if (data === '\x03') {
      // Ctrl+C：丢弃当前输入行
      term!.write('^C')
      writePrompt()
      return
    }
    if (data === '\x0c') {
      // Ctrl+L：清屏并重画提示符
      term!.clear()
      writePrompt(false)
      return
    }
    if (data === '\x1b[A') {
      historyPrev()
      return
    }
    if (data === '\x1b[B') {
      historyNext()
      return
    }
    if (data === '\x1b[C' || data === '\x1b[D') return

    // 普通字符 / 输入法合成结果 / 粘贴：过滤控制字符与换行
    const text = data.replace(/[\x00-\x1f\x7f]/g, '')
    if (!text) return
    buffer += text
    redraw()
  })

  // banner 保留：让用户一眼看出当前是「真终端」还是「简易模式」
  term.writeln('\x1b[38;5;141mCodeCraft Terminal\x1b[0m')
  if (props.root) {
    term.writeln(`\x1b[2m工作目录：${props.root}\x1b[0m`)
  } else {
    term.writeln('\x1b[33m当前智能体没有设置工作目录，可用 cd 切换后再执行命令\x1b[0m')
  }
  term.focus()

  // 异步探测真终端：成功 → 进入 PTY 模式（提示符由 shell 输出，前端不再自绘）；
  // 失败 / 浏览器环境 → 打印简易模式说明并画本地提示符
  void tryStartPty().then(ok => {
    if (ok) return
    term?.writeln('\x1b[2m简易模式：输入命令后回车执行（↑↓ 翻历史，Ctrl+L 清屏，cd 可切目录）\x1b[0m')
    writePrompt(false)
  })
}

function disposeTerminal() {
  // 先收 PTY：只关面板不 kill 会留下孤儿 shell 进程（面板关了 powershell 还在后台跑）
  offPtyData?.(); offPtyData = null
  offPtyExit?.(); offPtyExit = null
  if (ptySessionId) {
    void ptyApi?.kill(ptySessionId)
    ptySessionId = ''
  }
  ptyMode.value = false
  ptyExited = false

  resizeObserver?.disconnect()
  resizeObserver = null
  term?.dispose()
  term = null
  fitAddon = null
}

onMounted(async () => {
  mq.addEventListener('change', onSchemeChange)
  await nextTick()
  initTerminal()
})

onBeforeUnmount(() => {
  mq.removeEventListener('change', onSchemeChange)
  disposeTerminal()
})

/** 主题切换实时生效（外观设置里的 light / dark / auto） */
watch(resolvedTheme, () => applyTheme())

/**
 * 切换 Agent（工作目录变化）时：重置 cwd、清屏、重新写欢迎信息。
 * 用户已经在终端里 cd 到别处时，只有 Agent 真的换了工作目录才会被重置。
 */
watch(
  () => props.root,
  value => {
    cwd.value = value
    if (!term) return

    // 真终端：shell 启动时就把 cwd 固定了，切目录必须 kill 旧会话再重开，
    // 否则用户会看到「提示符还停在旧目录」这种自相矛盾的状态。
    if (ptyMode.value || ptySessionId) {
      offPtyData?.(); offPtyData = null
      offPtyExit?.(); offPtyExit = null
      if (ptySessionId) {
        void ptyApi?.kill(ptySessionId)
        ptySessionId = ''
      }
      ptyMode.value = false
      ptyExited = false
      term.clear()
      term.writeln('\x1b[38;5;141mCodeCraft Terminal\x1b[0m — 工作目录已切换')
      term.writeln(`\x1b[2m${value || '（未设置）'}\x1b[0m`)
      void tryStartPty().then(ok => {
        if (ok) return
        term?.writeln('\x1b[2m简易模式：输入命令后回车执行\x1b[0m')
        writePrompt(false)
      })
      return
    }

    term.clear()
    term.writeln('\x1b[38;5;141mCodeCraft Terminal\x1b[0m — 工作目录已切换')
    term.writeln(`\x1b[2m${value || '（未设置）'}\x1b[0m`)
    writePrompt(false)
  }
)
</script>
