/**
 * 右侧抽屉拖拽调宽（F16 · P0 地基）
 * ----------------------------------------------------------------------------
 * 为什么要抽成 composable：
 *   1) 聊天页工具抽屉、各管理页编辑抽屉（Agent / Provider / 数据库执行 / 文件详情 …）
 *      都需要「拖左边界改宽度 + 记住宽度 + 双击复位」，逐个实现必然三套不一致的实现；
 *   2) 宽度统一落到既有 CSS 变量 `--drawer-w`，组件只改变量、不动 DOM 结构，
 *      `.cc-drawer` 既有样式（position:fixed / 右侧滑入 / 遮罩）完全不用重写；
 *   3) 后续 P3/P4 的代码工作区（diff 面板、终端、多标签）都要宽抽屉，这里是公共底座。
 *
 * 用法（模板 2 处 + 脚本 1 行）：
 *   const { drawerStyle, resizing, onPointerDown, onPointerMove, onPointerUp, resetWidth }
 *     = useDrawerResize('agent-edit', { defaultWidth: 480 })
 *
 *   <aside class="cc-drawer" :style="drawerStyle">
 *     <div
 *       class="cc-drawer__resizer"
 *       :class="{ 'is-dragging': resizing }"
 *       title="拖动调整宽度，双击复位"
 *       @pointerdown="onPointerDown"
 *       @pointermove="onPointerMove"
 *       @pointerup="onPointerUp"
 *       @pointercancel="onPointerUp"
 *       @dblclick="resetWidth"
 *     />
 *     ...
 *
 * 边界语义（都是踩过坑才这么定的）：
 *   - 只改宽度、不动 `position: fixed` 的 right 锚点，所以左边界严格跟手；
 *   - 上限同时受 `max` 与视口约束（最多占 72% 视口、主区至少留 320px），
 *     避免把对话区挤成一条缝；
 *   - 窄屏（≤900px，与 app.css 媒体查询同口径）直接拒绝拖拽，
 *     宽度交给 CSS 的 `min(var(--drawer-w), 100vw)` 兜底，不会溢出屏幕；
 *   - 用户设定值与实际宽度分离：窗口临时变窄时实际宽度自动收敛，
 *     窗口恢复后按原设定回弹，不会把配置写坏。
 */

import { computed, onBeforeUnmount, ref, type CSSProperties } from 'vue'

export interface DrawerResizeOptions {
  /** 默认宽度（px）：双击分隔条复位到这个值，也是从未拖过时的初始宽度 */
  defaultWidth?: number
  /** 最小宽度（px），默认 320 */
  min?: number
  /** 最大宽度（px），默认 1200（仍受视口约束） */
  max?: number
}

/** localStorage 键前缀，沿用项目 `codecraft.` 约定 */
const STORAGE_PREFIX = 'codecraft.drawerWidth.'
/** 抽屉最宽占视口比例：再宽就把内容区挤没了 */
const MAX_VIEWPORT_RATIO = 0.72
/** 主区（对话/列表）至少保留的宽度 */
const MIN_MAIN_WIDTH = 320
/** 窄屏断点：必须与 app.css 里 `@media (max-width: 900px)` 保持一致 */
const NARROW_BREAKPOINT = 900
/** 拖拽期间挂到 body 上的类：统一鼠标形状 + 禁止误选文本 */
const RESIZING_CLASS = 'cc-resizing'

export function useDrawerResize(key: string, options: DrawerResizeOptions = {}) {
  /**
   * 默认宽度用 ref：`DrawerResizer.vue` 会在挂载后从父元素现有的 `--drawer-w`
   * （各抽屉原本写死的 480 / 520 / 560 / 720）反读真实默认值再校准，
   * 这样接入方不必在样式与脚本两处重复写同一个数字。
   */
  const defaultWidth = ref(Math.round(options.defaultWidth ?? 400))
  const minWidth = Math.round(options.min ?? 320)
  const maxWidth = Math.round(options.max ?? 1200)
  const storageKey = STORAGE_PREFIX + key

  /** 当前视口允许的最大宽度 */
  function upperBound(): number {
    if (typeof window === 'undefined') return maxWidth
    const viewport = window.innerWidth || 0
    if (!viewport) return maxWidth
    const byRatio = Math.floor(viewport * MAX_VIEWPORT_RATIO)
    const byMain = Math.floor(viewport - MIN_MAIN_WIDTH)
    return Math.max(minWidth, Math.min(maxWidth, byRatio, byMain))
  }

  function clampWidth(value: number): number {
    const upper = upperBound()
    if (!Number.isFinite(value)) return Math.min(defaultWidth.value, upper)
    return Math.min(upper, Math.max(minWidth, Math.round(value)))
  }

  function readStored(): number | null {
    if (typeof window === 'undefined') return null
    try {
      const raw = window.localStorage.getItem(storageKey)
      if (raw == null) return null
      const parsed = Number(raw)
      return Number.isFinite(parsed) ? parsed : null
    } catch {
      // 隐私模式 / 禁用存储：退回默认宽度，不影响拖拽本身
      return null
    }
  }

  /** 用户设定值（未受视口约束）；实际宽度由 computed 收敛 */
  const desiredWidth = ref<number>(readStored() ?? defaultWidth.value)

  /**
   * 视口变化的「心跳」。
   *
   * <p>`upperBound()` 读的是 `window.innerWidth`，它不是响应式数据 ——
   * 如果 `width` 这个 computed 只依赖 `desiredWidth`，窗口变窄时它不会重算，
   * 抽屉就会一直停在旧宽度上（缩到 800px 窗口仍然 600px 宽，直接压掉主区）；
   * 窗口恢复后也不会回弹。所以这里用一个自增 ref 建立显式依赖。</p>
   */
  const viewportTick = ref(0)

  const width = computed(() => {
    // 仅用于建立依赖：视口尺寸每变一次，这里就重新 clamp 一次
    void viewportTick.value
    return clampWidth(desiredWidth.value)
  })
  const resizing = ref(false)

  const drawerStyle = computed<CSSProperties>(() => ({ '--drawer-w': `${width.value}px` }))

  let startX = 0
  let startWidth = 0
  let activePointerId: number | null = null

  function persist() {
    try {
      window.localStorage.setItem(storageKey, String(desiredWidth.value))
    } catch {
      // 存不下就只在本次会话生效，不阻断交互
    }
  }

  function endResize() {
    if (!resizing.value) return
    resizing.value = false
    activePointerId = null
    document.body.classList.remove(RESIZING_CLASS)
    persist()
  }

  function onPointerDown(event: PointerEvent) {
    // 窄屏禁用：CSS 会把宽度收敛到视口，拖拽没有意义
    if (typeof window !== 'undefined' && window.innerWidth <= NARROW_BREAKPOINT) return
    if (event.button !== 0) return
    event.preventDefault()
    startX = event.clientX
    startWidth = width.value
    activePointerId = event.pointerId
    resizing.value = true
    document.body.classList.add(RESIZING_CLASS)
    // 捕获指针：拖出抽屉/窗口边界后仍能持续收到 move。
    // 合成事件（自动化测试 / 部分触屏合成）下 pointerId 可能不存在，会抛 NotFoundError，
    // 捕获失败不影响拖拽本身（监听器就挂在分隔条上），所以这里必须吞掉。
    const target = event.currentTarget as HTMLElement | null
    try {
      target?.setPointerCapture?.(event.pointerId)
    } catch {
      // 忽略：拿不到捕获就退化成普通事件流
    }
  }

  function onPointerMove(event: PointerEvent) {
    if (!resizing.value) return
    if (activePointerId != null && event.pointerId !== activePointerId) return
    // 抽屉贴右边：鼠标往左移（clientX 变小）→ 宽度变大
    desiredWidth.value = startWidth + (startX - event.clientX)
  }

  function onPointerUp(event: PointerEvent) {
    if (!resizing.value) return
    const target = event.currentTarget as HTMLElement | null
    if (activePointerId != null) {
      try {
        target?.releasePointerCapture?.(activePointerId)
      } catch {
        // 同上：合成事件下可能没有真正的捕获，忽略即可
      }
    }
    endResize()
  }

  /** 双击分隔条：复位到默认宽度并清掉持久化记录 */
  function resetWidth() {
    desiredWidth.value = defaultWidth.value
    try {
      window.localStorage.removeItem(storageKey)
    } catch {
      // 忽略
    }
  }

  /**
   * 校准默认宽度（由 `DrawerResizer` 挂载后调用）。
   *
   * <p>为什么不在构造时就传：各抽屉的默认宽度本来就写在模板的 `style="--drawer-w: 480px"` 上，
   * 再让调用方写一遍数字属于两处重复、必然漂移。改为从父元素反读后校准：
   * 用户没拖过 → 跟随真实默认值；拖过 → 保持用户设定，不被覆盖。</p>
   */
  function setDefaultWidth(next: number) {
    const value = Math.round(next)
    if (!Number.isFinite(value) || value <= 0) return
    const touched = readStored() != null
    defaultWidth.value = value
    if (!touched) desiredWidth.value = value
  }

  /**
   * 视口尺寸变化：bump 心跳让 `width` 重新收敛（窗口变窄自动收、恢复自动回弹）。
   * 同时兜底清理「拖拽中组件被卸载」残留的 body 类 ——
   * 表现为全局鼠标一直是 col-resize 且无法选中文本。
   */
  if (typeof window !== 'undefined') {
    const onViewportResize = () => {
      viewportTick.value++
    }
    window.addEventListener('resize', onViewportResize)
    onBeforeUnmount(() => {
      window.removeEventListener('resize', onViewportResize)
      document.body.classList.remove(RESIZING_CLASS)
    })
  }

  return {
    /** 实际生效宽度（已收敛到视口约束） */
    width,
    /** 是否正在拖拽：驱动分隔条的 is-dragging 视觉反馈 */
    resizing,
    /** 直接给到 `<aside class="cc-drawer" :style="drawerStyle">` */
    drawerStyle,
    onPointerDown,
    onPointerMove,
    onPointerUp,
    resetWidth,
    setDefaultWidth
  }
}
