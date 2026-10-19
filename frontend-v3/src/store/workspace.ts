import { defineStore } from 'pinia'
import { computed, ref } from 'vue'

/**
 * 工作区（P4 · F02 多标签 + F03 分屏）
 * ----------------------------------------------------------------------------
 * 把聊天页右侧「单抽屉」升级为可切换、可并排的工作台：
 *
 *   ┌─────────────────────────┬──────────────┬──────────────┐
 *   │                         │ tabs (main)  │ tabs (side)  │  ← split-h
 *   │        聊天主列          ├──────────────┴──────────────┤
 *   │                         │       当前激活标签内容        │
 *   └─────────────────────────┴─────────────────────────────┘
 *
 * 设计要点：
 *   1) 标签是「全局单例」而非按会话隔离 —— 工作台服务于「边聊边看代码」这一个场景，
 *      切会话时把正在看的文件关掉反而碍事；标签里的路径本身已带绝对路径，不依赖会话；
 *   2) 状态持久化（persist）：刷新 / 重启后标签与分屏比例原样恢复，
 *      否则每次刷新都要重新点开一堆文件；
 *   3) 副栏（side）为空时自动回到单栏：分屏是个手段不是目的，
 *      留一个空荡荡的右半屏只会浪费聊天区宽度。
 */

/** 标签类型：对应右侧能打开的面板 */
export type TabKind = 'tree' | 'file' | 'diff' | 'git' | 'term' | 'web' | 'image'

/** 两栏标识：main 为左（split-h）/ 上（split-v），side 为右 / 下 */
export type PaneId = 'main' | 'side'

/** 布局：单栏 / 左右分屏 / 上下分屏 */
export type WorkspaceLayout = 'single' | 'split-h' | 'split-v'

/** 标签内容参数（必须可 JSON 序列化，随 store 一起持久化） */
export interface WorkspacePayload {
  /** file / image / diff：文件绝对路径 */
  path?: string
  /** diff：是否对比暂存区（git diff --cached） */
  staged?: boolean
  /** web：网址 */
  url?: string
  /** web：站点标识（跨域拿不到 document.title，用域名兜底） */
  host?: string
}

export interface WorkspaceTab {
  id: string
  kind: TabKind
  title: string
  payload: WorkspacePayload
  /** 未保存标记：由 file 标签的内容组件上报，标签栏据此亮圆点 */
  dirty?: boolean
  /** 所属栏 */
  pane: PaneId
}

/** 标签数量上限：persist 里堆到几百个会拖慢启动，且实际也用不到 */
export const MAX_TABS = 16

/** 工作区宽度上下限（px） */
export const MIN_WORKSPACE_W = 320
export const MAX_WORKSPACE_W = 1200

/** 分屏比例上下限：低于 0.2 或高于 0.8 时两栏都没法用 */
export const MIN_RATIO = 0.2
export const MAX_RATIO = 0.8

/** 各类型标签的默认标题 */
const KIND_TITLE: Record<TabKind, string> = {
  tree: '文件',
  file: '文件',
  diff: '差异',
  git: 'Git',
  term: '终端',
  web: '网页',
  image: '图片'
}

/** 各类型标签在标签栏显示的图标（symbol id，见 AppIcons.vue / preview.html） */
export const KIND_ICON: Record<TabKind, string> = {
  tree: 'i-folder',
  file: 'i-file',
  diff: 'i-branch',
  git: 'i-git',
  term: 'i-term',
  web: 'i-link',
  image: 'i-image'
}

/** 取自路径最后一段（同时兼容 Windows `\` 与 POSIX `/`） */
export function baseName(path: string): string {
  const seg = path.split(/[\\/]/).filter(Boolean)
  return seg.length ? seg[seg.length - 1] : path
}

/** 从 URL 取域名；非法 URL 返回空串（不抛错，调用方无需 try/catch） */
export function hostOf(url?: string): string {
  if (!url) return ''
  try {
    return new URL(url).host
  } catch {
    return ''
  }
}

/**
 * 标签去重键：同一种「资源」只允许开一个标签。
 * 文件按路径、网页按 URL、其余面板（文件树 / Git / 终端）天然单例。
 */
function tabKeyOf(kind: TabKind, payload: WorkspacePayload): string {
  if (kind === 'file' || kind === 'image' || kind === 'diff') {
    return `${kind}|${payload.path || ''}|${payload.staged ? 'staged' : 'work'}`
  }
  if (kind === 'web') return `web|${payload.url || ''}`
  return kind
}

/** 默认标题：文件取文件名，网页取域名，其余用类型名 */
function defaultTitle(kind: TabKind, payload: WorkspacePayload): string {
  if (kind === 'file' || kind === 'image') {
    return payload.path ? baseName(payload.path) : KIND_TITLE[kind]
  }
  if (kind === 'diff') {
    return payload.path ? `差异 · ${baseName(payload.path)}` : KIND_TITLE.diff
  }
  if (kind === 'web') return payload.host || hostOf(payload.url) || KIND_TITLE.web
  return KIND_TITLE[kind]
}

function clamp(value: number, min: number, max: number): number {
  return Math.min(max, Math.max(min, value))
}

/** 自增序号：同一毫秒内连开多个标签也不会撞 id */
let seq = 0
function nextId(kind: TabKind): string {
  seq += 1
  return `${kind}-${Date.now().toString(36)}-${seq}`
}

export const useWorkspaceStore = defineStore(
  'workspace',
  () => {
    /** 工作区是否展开（关闭后聊天区恢复满宽） */
    const open = ref(false)
    /** 全部标签（两栏共用一份数组，靠 tab.pane 区分归属） */
    const tabs = ref<WorkspaceTab[]>([])
    const activeMain = ref('')
    const activeSide = ref('')
    const layout = ref<WorkspaceLayout>('single')
    /** 分屏比例：main 栏占的比例（0.2~0.8） */
    const ratio = ref(0.55)
    /** 工作区整体宽度（px），由分隔条拖拽调整 */
    const width = ref(600)

    const mainTabs = computed(() => tabs.value.filter(t => t.pane === 'main'))
    const sideTabs = computed(() => tabs.value.filter(t => t.pane === 'side'))
    const tabCount = computed(() => tabs.value.length)
    /** 是否处于分屏（副栏可见） */
    const split = computed(() => layout.value !== 'single')

    function tabsOf(pane: PaneId): WorkspaceTab[] {
      return pane === 'main' ? mainTabs.value : sideTabs.value
    }

    function activeOf(pane: PaneId): string {
      return pane === 'main' ? activeMain.value : activeSide.value
    }

    function setActiveOf(pane: PaneId, id: string) {
      if (pane === 'main') activeMain.value = id
      else activeSide.value = id
    }

    /** 当前激活标签（找不到返回 null） */
    function activeTabOf(pane: PaneId): WorkspaceTab | null {
      const id = activeOf(pane)
      return tabs.value.find(t => t.id === id) || null
    }

    /**
     * 打开标签（同资源已存在则直接激活，不重复开）。
     * @returns 新标签 id；达到上限返回 null（由调用方提示用户先关几个）
     */
    function openTab(
      kind: TabKind,
      payload: WorkspacePayload = {},
      opts: { pane?: PaneId; title?: string } = {}
    ): string | null {
      const key = tabKeyOf(kind, payload)
      const exist = tabs.value.find(t => tabKeyOf(t.kind, t.payload) === key)
      if (exist) {
        // 已存在就直接激活原标签所在的栏，不做跨栏搬运（否则点一下文件标签就跳栏，很晕）
        setActiveOf(exist.pane, exist.id)
        open.value = true
        return exist.id
      }
      if (tabs.value.length >= MAX_TABS) return null

      // 单栏模式下副栏不可见，即便调用方指定了 side 也只能落在 main
      const pane: PaneId = opts.pane && split.value ? opts.pane : 'main'
      const tab: WorkspaceTab = {
        id: nextId(kind),
        kind,
        title: opts.title || defaultTitle(kind, payload),
        payload,
        pane
      }
      tabs.value = [...tabs.value, tab]
      setActiveOf(pane, tab.id)
      open.value = true
      return tab.id
    }

    function activate(id: string) {
      const tab = tabs.value.find(t => t.id === id)
      if (!tab) return
      setActiveOf(tab.pane, id)
      open.value = true
    }

    function closeTab(id: string) {
      const tab = tabs.value.find(t => t.id === id)
      if (!tab) return
      const pane = tab.pane
      tabs.value = tabs.value.filter(t => t.id !== id)

      if (activeOf(pane) === id) {
        // 关掉当前标签后，优先落在同栏最后一个标签上
        const rest = tabsOf(pane)
        setActiveOf(pane, rest.length ? rest[rest.length - 1].id : '')
      }

      // 副栏空了就回单栏：留一个空荡的右半屏只会压缩聊天区
      if (pane === 'side' && !sideTabs.value.length && layout.value !== 'single') {
        layout.value = 'single'
      }
      if (!tabs.value.length) open.value = false
    }

    /** 关闭某一栏的全部标签 */
    function closePane(pane: PaneId) {
      const ids = tabsOf(pane).map(t => t.id)
      ids.forEach(id => closeTab(id))
    }

    /** 把标签移动到另一栏（拖标签到另一栏时调用） */
    function moveTab(id: string, pane: PaneId) {
      const tab = tabs.value.find(t => t.id === id)
      if (!tab || tab.pane === pane || !split.value) return
      tab.pane = pane
      setActiveOf(pane, id)
    }

    /** 同栏内拖拽排序（toIndex 为该栏内的目标下标） */
    function reorderTab(id: string, toIndex: number) {
      const tab = tabs.value.find(t => t.id === id)
      if (!tab) return
      const same = tabsOf(tab.pane).filter(t => t.id !== id)
      const at = clamp(toIndex, 0, same.length)
      same.splice(at, 0, tab)
      const others = tabs.value.filter(t => t.pane !== tab.pane)
      tabs.value = [...others, ...same]
    }

    function setLayout(next: WorkspaceLayout) {
      if (layout.value === next) return
      if (next !== 'single') {
        // 进入分屏时把 main 栏「非当前」的最后一个标签挪到副栏，
        // 否则右半屏是空的，用户还得再点一次「+」
        if (!sideTabs.value.length) {
          const cur = activeOf('main')
          const movable = [...mainTabs.value].reverse().find(t => t.id !== cur)
          if (movable) {
            movable.pane = 'side'
            setActiveOf('side', movable.id)
          }
        }
      } else {
        // 回到单栏：副栏标签并回主栏，不丢标签
        sideTabs.value.forEach(t => {
          t.pane = 'main'
        })
        activeSide.value = ''
      }
      layout.value = next
    }

    function setRatio(value: number) {
      ratio.value = clamp(Number.isFinite(value) ? value : 0.55, MIN_RATIO, MAX_RATIO)
    }

    function setWidth(value: number) {
      width.value = clamp(Number.isFinite(value) ? value : 600, MIN_WORKSPACE_W, MAX_WORKSPACE_W)
    }

    function setOpen(value: boolean) {
      open.value = value
    }

    function toggleOpen() {
      open.value = !open.value
    }

    /** 文件标签上报未保存状态（标签栏据此显示圆点） */
    function markDirty(id: string, dirty: boolean) {
      const tab = tabs.value.find(t => t.id === id)
      if (tab) tab.dirty = dirty
    }

    function renameTab(id: string, title: string) {
      const tab = tabs.value.find(t => t.id === id)
      if (tab && title) tab.title = title
    }

    /** 更新标签参数（网页跳转后写回新地址，刷新仍能恢复） */
    function updatePayload(id: string, patch: WorkspacePayload) {
      const tab = tabs.value.find(t => t.id === id)
      if (!tab) return
      tab.payload = { ...tab.payload, ...patch }
    }

    /** 清空工作区（关闭全部标签并回到单栏） */
    function reset() {
      tabs.value = []
      activeMain.value = ''
      activeSide.value = ''
      layout.value = 'single'
      open.value = false
    }

    return {
      open,
      tabs,
      activeMain,
      activeSide,
      layout,
      ratio,
      width,
      // 派生
      mainTabs,
      sideTabs,
      tabCount,
      split,
      // 查询
      tabsOf,
      activeOf,
      activeTabOf,
      // 动作
      openTab,
      activate,
      closeTab,
      closePane,
      moveTab,
      reorderTab,
      setLayout,
      setRatio,
      setWidth,
      setOpen,
      toggleOpen,
      markDirty,
      renameTab,
      updatePayload,
      reset
    }
  },
  {
    /**
     * 键名加 `v3.` 前缀。
     *
     * <p>workspace 是 v3 独有 store，经典版没有同名键，理论上不会冲突；
     * 但从「新版独占的本地状态一律带 `v3.` 前缀」这条约定看，留着裸键名迟早
     * 会和后续版本对撞（出问题时很难定位是谁覆盖了谁）。统一加上更省心。</p>
     */
    persist: {
      key: 'v3.workspace'
    }
  }
)
