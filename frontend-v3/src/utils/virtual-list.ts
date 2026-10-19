/**
 * 消息列表窗口化（F08）
 * ----------------------------------------------------------------------------
 * 聊天页的 `messages` 是「一直往上翻就一直往里加」的数组：后端按轮次分页，
 * 用户翻 20 页后本地就堆了几百条消息、每条又是一个完整的 markdown + 工具卡片子树，
 * 首屏渲染与滚动都会明显发涩（旧项目会话里单会话 1 万+ 条消息）。
 *
 * 为什么不用 `vue-virtual-scroller`（方案 B）：
 *   消息高度完全不定（思考折叠、工具展开、代码块、图片、diff 都会改变高度），
 *   而且与本项目已有的「流式自动贴底 / ResizeObserver 校正 / 触顶加载更早」三套逻辑
 *   强耦合，引入外部库要重新对齐这些行为，维护成本高于自研。
 *
 * 本模块采用**尾部窗口 + 顶部占位**（而不是逐项高度测量 + 绝对定位）：
 *   - 只渲染最近 `size` 条消息，更早的用一个占位块代替（`hiddenCount` 条）；
 *   - 用户滚到顶 / 点占位块 → `expand()` 每次多放 `step` 条，`size` 只增不减；
 *   - 消息新增（流式）时窗口自动跟随尾部，用户在原地的浏览位置由调用方做 scrollTop 补偿。
 *
 * 相比「测量 + 绝对定位」的好处：
 *   1) 不依赖行高缓存，折叠/展开/图片加载都不会让占位高度算错（不会出现跳动）；
 *   2) 未渲染部分是一个固定高度的块，滚动条比例始终「大致正确」；
 *   3) 流式期间无需退出窗口化 —— 新消息永远落在窗口尾部。
 * 代价：极长会话滚到最上面时不是「无限回放」，需要多点几次展开（每次都有明确按钮）。
 */

import { computed, ref, type Ref } from 'vue'

export interface TailWindowOptions {
  /** 初始渲染条数（首屏只放这么多） */
  initial?: number
  /** 每次展开追加的条数 */
  step?: number
}

export interface TailWindow<T> {
  /** 当前应该渲染的消息（数组尾部一段） */
  visible: Ref<T[]>
  /** 被窗口挡住的更早消息条数 */
  hiddenCount: Ref<number>
  /** 当前窗口容量 */
  size: Ref<number>
  /** 展开一段（默认 step 条） */
  expand: (step?: number) => void
  /** 一次性展开全部（调试/导出场景） */
  expandAll: () => void
  /** 重置为初始容量（切换会话时调用） */
  reset: () => void
}

/**
 * 创建「尾部窗口」。
 *
 * @param items 全量消息（通常是 `computed(() => props.messages ?? [])`）
 * @param options 初始容量与步长
 */
export function useTailWindow<T>(items: Ref<T[]>, options: TailWindowOptions = {}): TailWindow<T> {
  const initial = Math.max(1, options.initial ?? 60)
  const stepSize = Math.max(1, options.step ?? 40)

  const size = ref(initial)

  const visible = computed<T[]>(() => {
    const list = items.value ?? []
    if (list.length <= size.value) return list
    return list.slice(list.length - size.value)
  })

  const hiddenCount = computed(() => Math.max(0, (items.value?.length ?? 0) - visible.value.length))

  function expand(step = stepSize) {
    size.value += Math.max(1, step)
  }

  function expandAll() {
    size.value = Math.max(size.value, items.value?.length ?? 0)
  }

  function reset() {
    size.value = initial
  }

  return { visible, hiddenCount, size, expand, expandAll, reset }
}
