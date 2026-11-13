<template>
  <div class="terminal-panel">
    <div ref="xtermContainer" class="xterm-container"></div>
    <!-- 补全候选弹出层 -->
    <div v-if="terminalShowSuggestions && terminalSuggestions.length > 0" class="terminal-suggestions-popup">
      <div
        v-for="(item, i) in terminalSuggestions"
        :key="item.path"
        :class="['suggestion-item', { active: i === terminalSuggestionIndex }]"
        @click="selectSuggestion(item)"
      >
        <span class="suggestion-icon">{{ item.directory ? '📁' : '📄' }}</span>
        <span class="suggestion-name">{{ item.name }}{{ item.directory ? '/' : '' }}</span>
        <span class="suggestion-type">{{ item.directory ? '目录' : '文件' }}</span>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, watch, onMounted, onUnmounted, nextTick } from 'vue'
import { Terminal } from '@xterm/xterm'
import { FitAddon } from '@xterm/addon-fit'
import '@xterm/xterm/css/xterm.css'
import { execCommand, getDirChildren, type DirectoryEntry } from '@/api/project'
import { useSettingsStore } from '@/store/settings'

const settingsStore = useSettingsStore()

// ===== 解析当前实际主题（light | dark），考虑 auto 模式 =====
const resolvedTheme = computed<'light' | 'dark'>(() => {
  const mode = settingsStore.getTheme()
  if (mode === 'auto') {
    return window.matchMedia('(prefers-color-scheme: dark)').matches ? 'dark' : 'light'
  }
  return mode as 'light' | 'dark'
})

// ===== 亮/暗主题定义 =====
const DARK_THEME = {
  background: '#1e1d2c', foreground: '#d4d4d4', cursor: '#d4d4d4',
  selectionBackground: '#3b3a52',
  black: '#1e1d2c', red: '#f48771', green: '#3fb950', yellow: '#dcdcaa',
  blue: '#569cd6', magenta: '#c586c0', cyan: '#4ec9b0', white: '#d4d4d4',
  brightBlack: '#525070', brightRed: '#f48771', brightGreen: '#3fb950',
  brightYellow: '#dcdcaa', brightBlue: '#569cd6', brightMagenta: '#c586c0',
  brightCyan: '#4ec9b0', brightWhite: '#ffffff',
}
const LIGHT_THEME = {
  background: '#fafafa', foreground: '#1a1a2e', cursor: '#1a1a2e',
  selectionBackground: '#c4b5fd',
  black: '#fafafa', red: '#d32f2f', green: '#2e7d32', yellow: '#f9a825',
  blue: '#1565c0', magenta: '#7b1fa2', cyan: '#00838f', white: '#1a1a2e',
  brightBlack: '#9e9e9e', brightRed: '#ef5350', brightGreen: '#43a047',
  brightYellow: '#fdd835', brightBlue: '#1e88e5', brightMagenta: '#ab47bc',
  brightCyan: '#00acc1', brightWhite: '#424242',
}

const props = defineProps<{ workDir: string }>()

// ===== xterm.js 终端核心 =====
const xtermContainer = ref<HTMLElement | null>(null)
let xterm: Terminal | null = null
let inputBuffer = ''
let systemThemeCleanup: (() => void) | null = null  // matchMedia 监听器清理

/* ==================== 真终端（PTY） ====================
 * Electron 桌面端：走主进程 node-pty 起真实 shell —— 支持 vim/top/ssh、Ctrl+C、Tab 补全、
 *   长命令实时出字。提示符/行编辑/补全/历史全部由 shell 负责，前端只做字节转发。
 * 浏览器环境：没有 preload，降级回既有的「输入行 + /project/exec」模式（含目录补全）。
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
/** 当前是否处于真 PTY 模式（true 时按键原样转发，不再走本地补全/输入行逻辑） */
const ptyMode = ref(false)
let ptySessionId = ''
let offPtyData: (() => void) | null = null
let offPtyExit: (() => void) | null = null
/** shell 自己 exit 后置 true：下一次按键用于重连 */
let ptyExited = false
const isTerminalExecuting = ref(false)
const terminalCwd = ref(props.workDir || '')

// 补全相关
const terminalGhostText = ref('')
const terminalSuggestions = ref<DirectoryEntry[]>([])
const terminalShowSuggestions = ref(false)
const terminalSuggestionIndex = ref(0)
let terminalCompletionStem = ''
let suggestionTimer: ReturnType<typeof setTimeout> | null = null
let lastTabTime = 0

// 命令历史
const HISTORY_KEY = 'codecraft_terminal_history'
const HISTORY_MAX = 500
let commandHistory: string[] = []
let historyIndex = -1
let savedInput = ''

const loadHistory = (): string[] => {
  try {
    const raw = localStorage.getItem(HISTORY_KEY)
    if (raw) {
      const arr = JSON.parse(raw)
      if (Array.isArray(arr)) return arr.filter((x): x is string => typeof x === 'string').slice(-HISTORY_MAX)
    }
  } catch { /* ignore */ }
  return []
}
const saveHistory = () => {
  try { localStorage.setItem(HISTORY_KEY, JSON.stringify(commandHistory.slice(-HISTORY_MAX))) } catch { /* ignore */ }
}
const addToHistory = (cmd: string) => {
  const trimmed = cmd.trim()
  if (!trimmed) return
  commandHistory.push(trimmed)
  if (commandHistory.length > HISTORY_MAX) commandHistory.shift()
  historyIndex = -1
  savedInput = ''
  saveHistory()
}

const formatTerminalPrompt = () => {
  const cwd = terminalCwd.value || props.workDir || '.'
  return cwd.replace(/\\/g, '/') + ' $'
}

const writePrompt = () => {
  if (!xterm) return
  const prompt = formatTerminalPrompt()
  xterm.write('\r\n' + prompt)
  inputBuffer = ''
  terminalGhostText.value = ''
  terminalShowSuggestions.value = false
}

const redrawInputLine = () => {
  if (!xterm) return
  const prompt = formatTerminalPrompt()
  const ghost = terminalGhostText.value
  xterm.write('\r\x1b[0K' + prompt + inputBuffer)
  if (ghost) {
    xterm.write('\x1b[2m' + ghost + '\x1b[0m\x1b[0K')
    xterm.write(`\x1b[${ghost.length}D`)
  } else {
    xterm.write('\x1b[0K')
  }
}

const applyXtermTheme = () => {
  if (!xterm) return
  xterm.options.theme = resolvedTheme.value === 'dark' ? DARK_THEME : LIGHT_THEME
}

/**
 * 尝试启动真 PTY 会话。
 * @returns true = 真终端模式（vim/top/ssh、Ctrl+C、Tab 补全、实时输出）；
 *          false = 降级到 /project/exec 简易模式（浏览器环境或 node-pty 不可用）
 */
const tryStartPty = async (): Promise<boolean> => {
  if (!ptyApi?.isAvailable || !xterm) return false
  try {
    const info = await ptyApi.isAvailable()
    if (!info?.available) {
      xterm.writeln(`\x1b[33m[真终端不可用：${info?.error || '未知原因'}，已降级为简易模式（不支持交互式程序）]\x1b[0m`)
      return false
    }
    const session = await ptyApi.create({
      cwd: props.workDir || undefined,
      cols: xterm.cols,
      rows: xterm.rows
    })
    ptySessionId = session.sessionId
    ptyMode.value = true
    ptyExited = false
    // 真终端由 shell 自己补全，本地补全弹层/ghost text 必须收起来，否则会与 shell 输出叠在一起
    terminalShowSuggestions.value = false
    terminalGhostText.value = ''

    offPtyData = ptyApi.onData((payload) => {
      if (!payload || payload.sessionId !== ptySessionId) return
      xterm?.write(payload.data)
    })

    offPtyExit = ptyApi.onExit((payload) => {
      if (!payload || payload.sessionId !== ptySessionId) return
      ptySessionId = ''
      ptyMode.value = false
      ptyExited = true
      offPtyData?.(); offPtyData = null
      offPtyExit?.(); offPtyExit = null
      xterm?.write(`\r\n\x1b[33m[会话已结束 exitCode=${payload.exitCode}；按任意键重新连接]\x1b[0m\r\n`)
    })
    return true
  } catch (e: any) {
    xterm?.writeln(`\x1b[33m[真终端启动失败：${e?.message || '未知错误'}，已降级为简易模式]\x1b[0m`)
    return false
  }
}

const initXterm = () => {
  if (!xtermContainer.value || xterm) return
  commandHistory = loadHistory()
  const fitAddon = new FitAddon()
  xterm = new Terminal({
    cursorBlink: true,
    fontSize: 12,
    fontFamily: "'SF Mono','Monaco','Fira Code',monospace",
    theme: DARK_THEME,
    allowProposedApi: true,
    scrollback: 2000,
  })
  xterm.loadAddon(fitAddon)
  xterm.open(xtermContainer.value)
  fitAddon.fit()
  applyXtermTheme()

  // 容器大小变化自动 fit；PTY 模式下还要把新尺寸同步给 shell（否则换行/全屏程序错位）
  const ro = new ResizeObserver(() => {
    try {
      fitAddon.fit()
    } catch {
      return
    }
    if (ptyMode.value && ptySessionId && xterm) {
      void ptyApi?.resize(ptySessionId, xterm.cols, xterm.rows)
    }
  })
  ro.observe(xtermContainer.value)

  // 监听主题变化 → 实时切换 xterm 主题（基于 Pinia + 系统媒体查询）
  watch(resolvedTheme, () => applyXtermTheme())
  const mq = window.matchMedia('(prefers-color-scheme: dark)')
  const mqHandler = () => { if (settingsStore.getTheme() === 'auto') applyXtermTheme() }
  mq.addEventListener('change', mqHandler)
  systemThemeCleanup = () => mq.removeEventListener('change', mqHandler)

  // onData 键盘处理
  xterm.onData((data) => {
    // 会话刚结束：任意键重连真 PTY（靠标记位，不重复注册监听器）
    if (ptyExited) {
      ptyExited = false
      void tryStartPty()
      return
    }
    // 真终端模式：所有按键（含 Ctrl+C / Tab / 方向键 / 输入法合成）原样转发给 shell，
    // 行编辑、补全、历史、信号全部由 shell 负责 —— 前端不再自绘提示符。
    if (ptyMode.value) {
      if (ptySessionId) void ptyApi?.write(ptySessionId, data)
      return
    }

    if (isTerminalExecuting.value) return
    if (data === '\r') {
      terminalGhostText.value = ''
      terminalShowSuggestions.value = false
      const cmd = inputBuffer.trim()
      inputBuffer = ''
      xterm!.write('\r\n')
      if (cmd) processCommand(cmd)
      else writePrompt()
      return
    }
    if (data === '\x7f') {
      if (inputBuffer.length > 0) {
        inputBuffer = inputBuffer.slice(0, -1)
        terminalGhostText.value = ''
        scheduleSuggestionUpdate()
        redrawInputLine()
      }
      return
    }
    if (data === '\x03') {
      terminalGhostText.value = ''
      terminalShowSuggestions.value = false
      inputBuffer = ''
      xterm!.write('^C\r\n')
      writePrompt()
      return
    }
    if (data === '\x1b[A') {
      if (commandHistory.length === 0) return
      if (historyIndex === -1) { savedInput = inputBuffer; historyIndex = commandHistory.length - 1 }
      else if (historyIndex > 0) historyIndex--
      inputBuffer = commandHistory[historyIndex]
      terminalGhostText.value = ''
      terminalShowSuggestions.value = false
      redrawInputLine()
      return
    }
    if (data === '\x1b[B') {
      if (historyIndex === -1) return
      if (historyIndex < commandHistory.length - 1) { historyIndex++; inputBuffer = commandHistory[historyIndex] }
      else { historyIndex = -1; inputBuffer = savedInput; savedInput = '' }
      terminalGhostText.value = ''
      terminalShowSuggestions.value = false
      redrawInputLine()
      return
    }
    if (data === '\x1b') {
      terminalShowSuggestions.value = false
      terminalGhostText.value = ''
      redrawInputLine()
      return
    }
    if (data === '\x1b[C') {
      if (terminalShowSuggestions.value && terminalSuggestions.value.length > 0) {
        terminalSuggestionIndex.value = (terminalSuggestionIndex.value + 1) % terminalSuggestions.value.length
        const entry = terminalSuggestions.value[terminalSuggestionIndex.value]
        inputBuffer = (inputBuffer.split(' ')[0] || '') + ' ' + terminalCompletionStem + entry.name + (entry.directory ? '/' : '')
        redrawInputLine()
      }
      return
    }
    if (data === '\x1b[D') {
      if (terminalShowSuggestions.value && terminalSuggestions.value.length > 0) {
        terminalSuggestionIndex.value = (terminalSuggestionIndex.value - 1 + terminalSuggestions.value.length) % terminalSuggestions.value.length
        const entry = terminalSuggestions.value[terminalSuggestionIndex.value]
        inputBuffer = (inputBuffer.split(' ')[0] || '') + ' ' + terminalCompletionStem + entry.name + (entry.directory ? '/' : '')
        redrawInputLine()
      }
      return
    }
    if (data === '\t') { handleTabComplete(); return }
    if (data.length === 1 && data.charCodeAt(0) >= 32) {
      inputBuffer += data
      terminalGhostText.value = ''
      terminalShowSuggestions.value = false
      redrawInputLine()
      scheduleSuggestionUpdate()
    }
  })

  xterm.writeln('CodeCraft Terminal')
  xterm.focus()

  // 异步探测真终端：成功 → PTY 模式（提示符/补全/历史全部由 shell 提供）；
  // 失败 / 浏览器环境 → 打印简易模式说明并画本地提示符
  void tryStartPty().then(ok => {
    if (ok) return
    xterm?.writeln('\x1b[2m简易模式：输入命令，按 Enter 执行（Tab 补全路径）\x1b[0m')
    writePrompt()
  })
}

const disposeXterm = () => {
  systemThemeCleanup?.()
  systemThemeCleanup = null
  // 先收 PTY：只关面板不 kill 会留下孤儿 shell 进程
  offPtyData?.(); offPtyData = null
  offPtyExit?.(); offPtyExit = null
  if (ptySessionId) {
    void ptyApi?.kill(ptySessionId)
    ptySessionId = ''
  }
  ptyMode.value = false
  ptyExited = false
  if (xterm) { xterm.dispose(); xterm = null }
}

const focusTerminal = () => { xterm?.focus() }

// ===== cd 命令 =====
const handleCdCommand = (target: string) => {
  const current = terminalCwd.value || props.workDir || ''
  if (!target || target === '~') {
    terminalCwd.value = props.workDir || current
  } else if (target === '.') {
    // no-op
  } else if (target === '..') {
    const clean = current.replace(/\\/g, '/').replace(/\/+$/, '')
    const parent = clean.replace(/\/[^/]*$/, '')
    terminalCwd.value = parent || clean || current
  } else if (target.match(/^[a-zA-Z]:/) || target.match(/^\/\//) || target.match(/^\\\\/)) {
    terminalCwd.value = target
  } else {
    const cleanTarget = target.replace(/[/\\]+$/, '')
    const separator = current.endsWith('\\') || current.endsWith('/') ? '' : '\\'
    terminalCwd.value = current + separator + cleanTarget
  }
}

// ===== 路径补全 =====
const resolveCompletionPath = (partial: string): { dir: string; prefix: string } | null => {
  const cwd = terminalCwd.value || props.workDir || '.'
  const normalized = partial.replace(/\\/g, '/')
  if (normalized.match(/^[a-zA-Z]:/) || normalized.startsWith('/')) {
    const lastSlash = normalized.lastIndexOf('/')
    if (lastSlash === -1) {
      if (normalized.match(/^[a-zA-Z]:$/)) return { dir: normalized + '/', prefix: '' }
      return { dir: normalized, prefix: normalized }
    }
    const dir = normalized.substring(0, lastSlash)
    const prefix = normalized.substring(lastSlash + 1)
    return { dir: dir || '/', prefix }
  }
  const normalizedCwd = cwd.replace(/\\/g, '/')
  const lastSlash = normalized.lastIndexOf('/')
  if (lastSlash === -1) return { dir: normalizedCwd, prefix: normalized }
  const relDir = normalized.substring(0, lastSlash)
  const prefix = normalized.substring(lastSlash + 1)
  return { dir: normalizedCwd + '/' + relDir, prefix }
}

const longestCommonPrefix = (strings: string[]): string => {
  if (strings.length === 0) return ''
  if (strings.length === 1) return strings[0]
  let prefix = strings[0]
  for (let i = 1; i < strings.length; i++) {
    while (!strings[i].startsWith(prefix)) {
      prefix = prefix.substring(0, prefix.length - 1)
      if (prefix === '') return ''
    }
  }
  return prefix
}

const parseInputForCompletion = (): { isFirstToken: boolean; pathArg: string } => {
  const input = inputBuffer
  const trimmed = input.trimStart()
  const spaceIdx = trimmed.indexOf(' ')
  if (spaceIdx === -1) return { isFirstToken: true, pathArg: '' }
  return { isFirstToken: false, pathArg: trimmed.substring(spaceIdx + 1) }
}

const fetchSuggestions = async (dir: string, prefix: string): Promise<DirectoryEntry[]> => {
  try {
    const res = await getDirChildren(dir, true)
    if (res.code === 200 && res.data) return res.data.filter(e => e.name.toLowerCase().startsWith(prefix.toLowerCase()))
  } catch { /* ignore */ }
  return []
}

const scheduleSuggestionUpdate = () => {
  if (suggestionTimer) clearTimeout(suggestionTimer)
  suggestionTimer = setTimeout(async () => {
    const input = inputBuffer
    if (!input || isTerminalExecuting.value) { terminalGhostText.value = ''; return }
    const { pathArg } = parseInputForCompletion()
    if (!pathArg && !input.includes(' ')) { terminalGhostText.value = ''; return }
    const resolved = resolveCompletionPath(pathArg)
    if (!resolved) { terminalGhostText.value = ''; return }
    const entries = await fetchSuggestions(resolved.dir, resolved.prefix)
    if (entries.length === 1) {
      const suffix = entries[0].name.substring(resolved.prefix.length)
      terminalGhostText.value = suffix + (entries[0].directory ? '/' : '')
      redrawInputLine()
    } else if (entries.length > 1) {
      const names = entries.map(e => e.name + (e.directory ? '/' : ''))
      const lcp = longestCommonPrefix(names)
      terminalGhostText.value = lcp.length > resolved.prefix.length ? lcp.substring(resolved.prefix.length) : ''
      redrawInputLine()
    } else { terminalGhostText.value = '' }
  }, 150)
}

const handleTabComplete = async () => {
  const input = inputBuffer
  if (!input) return
  const { pathArg } = parseInputForCompletion()
  if (!pathArg && !input.includes(' ')) return
  const resolved = resolveCompletionPath(pathArg)
  if (!resolved) return
  const now = Date.now()
  const isDoubleTab = (now - lastTabTime) < 500

  if (terminalShowSuggestions.value && terminalSuggestions.value.length > 0) {
    if (isDoubleTab && terminalSuggestions.value.length > 1) {
      terminalSuggestionIndex.value = (terminalSuggestionIndex.value + 1) % terminalSuggestions.value.length
      const entry = terminalSuggestions.value[terminalSuggestionIndex.value]
      const newArg = terminalCompletionStem + entry.name + (entry.directory ? '/' : '')
      const spaceIdx = input.indexOf(' ')
      inputBuffer = (spaceIdx === -1 ? '' : input.substring(0, spaceIdx + 1)) + newArg
      redrawInputLine()
      lastTabTime = now
      return
    }
  }

  const entries = await fetchSuggestions(resolved.dir, resolved.prefix)
  if (entries.length === 0) { terminalShowSuggestions.value = false; terminalGhostText.value = ''; lastTabTime = now; return }
  if (entries.length === 1) {
    terminalShowSuggestions.value = false; terminalGhostText.value = ''
    replacePathArg(pathArg, resolved.prefix, entries[0].name + (entries[0].directory ? '/' : ''))
    lastTabTime = now; return
  }
  const names = entries.map(e => e.name + (e.directory ? '/' : ''))
  const lcp = longestCommonPrefix(names)
  if (lcp.length > resolved.prefix.length) {
    terminalShowSuggestions.value = false; terminalGhostText.value = ''
    replacePathArg(pathArg, resolved.prefix, lcp)
    lastTabTime = now; return
  }
  terminalCompletionStem = pathArg.substring(0, pathArg.length - resolved.prefix.length)
  terminalSuggestions.value = entries
  terminalSuggestionIndex.value = 0
  terminalShowSuggestions.value = true
  terminalGhostText.value = ''
  lastTabTime = now
}

const replacePathArg = (pathArg: string, oldPrefix: string, newName: string) => {
  const input = inputBuffer
  const spaceIdx = input.indexOf(' ')
  if (spaceIdx === -1) return
  const before = input.substring(0, spaceIdx + 1)
  inputBuffer = before + pathArg.substring(0, pathArg.length - oldPrefix.length) + newName
  redrawInputLine()
}

const selectSuggestion = (entry: DirectoryEntry) => {
  const input = inputBuffer
  const spaceIdx = input.indexOf(' ')
  const before = spaceIdx === -1 ? '' : input.substring(0, spaceIdx + 1)
  inputBuffer = before + terminalCompletionStem + entry.name + (entry.directory ? '/' : '')
  terminalShowSuggestions.value = false
  terminalGhostText.value = ''
  redrawInputLine()
  focusTerminal()
}

// ===== 命令执行 =====
const processCommand = async (cmd: string) => {
  addToHistory(cmd)
  if (!terminalCwd.value && props.workDir) terminalCwd.value = props.workDir
  if (cmd === 'cd') { handleCdCommand(''); writePrompt(); return }
  if (cmd.startsWith('cd ')) { handleCdCommand(cmd.substring(3).trim()); writePrompt(); return }

  const projectRoot = props.workDir
  if (!projectRoot) { xterm?.writeln('\x1b[33m请先设置工作目录\x1b[0m'); writePrompt(); return }

  isTerminalExecuting.value = true
  try {
    const cwd = terminalCwd.value || projectRoot
    const result = await execCommand(cmd, cwd)
    if (result.output) xterm?.writeln(result.output)
    if (result.timedOut) xterm?.writeln('\x1b[33m[命令执行超时]\x1b[0m')
    else if (result.exitCode !== 0) xterm?.writeln(`\x1b[33m[进程退出码: ${result.exitCode}]\x1b[0m`)
  } catch (e: any) {
    xterm?.writeln(`\x1b[31m[错误] ${e.message}\x1b[0m`)
  } finally {
    isTerminalExecuting.value = false
    writePrompt()
  }
}

// ===== 生命周期 =====
watch(() => props.workDir, (val) => {
  if (val && !terminalCwd.value) terminalCwd.value = val
  // 真终端：shell 在 spawn 时就把 cwd 固定了，切目录必须 kill 旧会话再重开，
  // 否则提示符还停在旧目录，用户会以为 cd 没生效。（immediate 时 xterm 尚未创建，跳过）
  if (ptyApi && xterm && (ptyMode.value || ptySessionId)) {
    offPtyData?.(); offPtyData = null
    offPtyExit?.(); offPtyExit = null
    if (ptySessionId) {
      void ptyApi.kill(ptySessionId)
      ptySessionId = ''
    }
    ptyMode.value = false
    ptyExited = false
    xterm.clear()
    xterm.writeln('CodeCraft Terminal — 工作目录已切换')
    void tryStartPty().then(ok => {
      if (!ok) writePrompt()
    })
  }
}, { immediate: true })

onMounted(() => { nextTick(() => initXterm()) })
onUnmounted(() => { disposeXterm() })
</script>

<style scoped>
.terminal-panel {
  display: flex;
  flex-direction: column;
  height: 100%;
  position: relative;
}
.xterm-container {
  flex: 1;
  overflow: hidden;
}
/* 补全候选弹出层 */
.terminal-suggestions-popup {
  display: flex;
  flex-wrap: wrap;
  gap: 6px 18px;
  padding: 6px 14px;
  background: var(--bg-card, #1a1925);
  border-top: 1px solid var(--border, #2a2838);
  flex-shrink: 0;
}
.suggestion-item {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  padding: 2px 8px;
  border-radius: 4px;
  cursor: pointer;
  font-size: 12px;
  color: var(--text-3, #b4b2d0);
  transition: all 0.12s;
}
.suggestion-item:hover,
.suggestion-item.active {
  background: rgba(139, 92, 246, 0.18);
  color: var(--text-1, #e4e2f0);
}
.suggestion-icon { font-size: 13px; flex-shrink: 0; }
.suggestion-name { font-family: 'SF Mono', 'Monaco', 'Fira Code', monospace; }
.suggestion-type { font-size: 10px; color: var(--text-4, #6a6880); margin-left: 2px; }
</style>
