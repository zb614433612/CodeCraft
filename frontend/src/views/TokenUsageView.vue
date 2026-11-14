<!--
  成本看板（Token Usage）
  ----------------------------------------------------------------------------
  数据来源：后端 llm_token_usage 表 —— 每轮 API 请求落一条真实 usage
    · 命中/未命中分开记（deepseek-v4-pro 两者单价差约 120 倍，混算必然误判）
    · 思考链单独记（按输出计价 + 下轮回传再计一次 = 双重计费，最贵的 token）
  ⚠️ 页面金额均为按单价表估算的「成本结构参考值」，不是账单，以官方平台为准。
-->
<template>
  <div class="usage-page">
    <header class="page-head">
      <div class="head-text">
        <h2>成本看板</h2>
        <p class="hint">数据来自每轮真实 usage；金额为估算值，最终以官方账单为准</p>
      </div>
      <div class="range-switch">
        <button
          v-for="r in RANGES"
          :key="r.value"
          :class="['range-btn', { active: range === r.value }]"
          @click="changeRange(r.value)"
        >
          {{ r.label }}
        </button>
      </div>
    </header>

    <div v-if="loading" class="state">加载中…</div>
    <div v-else-if="error" class="state error">
      {{ error }}
      <button class="retry" @click="load">重试</button>
    </div>

    <template v-else>
      <section class="cards">
        <div class="card">
          <span class="label">估算费用</span>
          <strong class="value">{{ summaryCostText }}</strong>
          <span class="sub">{{ summaryCostHint }}</span>
        </div>
        <div class="card">
          <span class="label">总 Token</span>
          <strong class="value">{{ fmt(summary.totalTokens) }}</strong>
          <span class="sub">输入 {{ fmt(summary.promptTokens) }} · 输出 {{ fmt(summary.completionTokens) }}</span>
        </div>
        <div class="card">
          <span class="label">缓存命中率</span>
          <strong class="value" :class="hitClass">{{ hitRateText }}</strong>
          <span class="sub">命中 {{ fmt(summary.cacheHitTokens) }} · 未命中 {{ fmt(summary.cacheMissTokens) }}</span>
        </div>
        <div class="card">
          <span class="label">思考链 Token</span>
          <strong class="value">{{ fmt(summary.reasoningTokens) }}</strong>
          <span class="sub">按输出计价，且下轮回传会再计一次</span>
        </div>
      </section>

      <section v-if="summary.breakdownAvailable !== false" class="block">
        <h3>费用结构</h3>
        <div class="bar">
          <div class="seg hit" :style="{ width: pct(summary.cacheHitCost) + '%' }" />
          <div class="seg miss" :style="{ width: pct(summary.cacheMissCost) + '%' }" />
          <div class="seg out" :style="{ width: pct(summary.outputCost) + '%' }" />
        </div>
        <div class="legend">
          <span><i class="dot hit" />命中输入 ¥{{ money(summary.cacheHitCost) }}</span>
          <span><i class="dot miss" />未命中输入 ¥{{ money(summary.cacheMissCost) }}</span>
          <span><i class="dot out" />输出 ¥{{ money(summary.outputCost) }}</span>
        </div>
        <p class="tip">
          提示：未命中与输出通常是成本主体。命中部分虽然 token 数量大，但单价极低——想降成本请优先压「每轮新增内容」与「输出/思考链」。
        </p>
      </section>

      <section class="block">
        <h3>费用最高的会话</h3>
        <table v-if="conversations.length" class="tbl">
          <thead>
            <tr>
              <th>会话</th>
              <th class="num">请求</th>
              <th class="num">总 Token</th>
              <th class="num">命中率</th>
              <th class="num">估算费用</th>
            </tr>
          </thead>
          <tbody>
            <tr
              v-for="g in conversations"
              :key="g.groupKey"
              :class="{ clickable: true, active: activeConversation === g.groupKey }"
              @click="toggleDetail(g.groupKey)"
            >
              <td class="name">{{ g.groupName || ('会话#' + g.groupKey) }}</td>
              <td class="num">{{ g.requestCount }}</td>
              <td class="num">{{ fmt(g.totalTokens) }}</td>
              <td class="num">{{ rateText(g.cacheHitRate) }}</td>
              <td class="num strong">{{ costText(g.costEstimate, g.pricedRequestCount) }}</td>
            </tr>
          </tbody>
        </table>
        <p v-else class="state small">该时间范围内暂无记录</p>

        <div v-if="activeConversation" class="detail">
          <h4>
            轮次明细（会话 #{{ activeConversation }}）
            <button class="link" @click="closeDetail">收起</button>
          </h4>
          <div v-if="detailLoading" class="state small">加载中…</div>
          <table v-else-if="detailList.length" class="tbl small">
            <thead>
              <tr>
                <th class="num">轮次</th>
                <th>模型</th>
                <th class="num">输入</th>
                <th class="num">命中率</th>
                <th class="num">输出</th>
                <th class="num">思考</th>
                <th class="num">费用</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="d in detailList" :key="d.id">
                <td class="num">{{ d.iteration }}</td>
                <td>{{ d.model || '-' }}</td>
                <td class="num">{{ fmt(d.promptTokens) }}</td>
                <td class="num">{{ rateText(rateOf(d.cacheHitTokens, d.cacheMissTokens)) }}</td>
                <td class="num">{{ fmt(d.completionTokens) }}</td>
                <td class="num">{{ fmt(d.reasoningTokens) }}</td>
                <td class="num strong">{{ costText(d.costEstimate) }}</td>
              </tr>
            </tbody>
          </table>
          <p v-else class="state small">该会话暂无明细</p>
        </div>
      </section>

      <section class="block">
        <h3>按模型</h3>
        <table v-if="models.length" class="tbl">
          <thead>
            <tr>
              <th>模型</th>
              <th class="num">请求</th>
              <th class="num">输入</th>
              <th class="num">输出</th>
              <th class="num">命中率</th>
              <th class="num">估算费用</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="m in models" :key="m.groupKey">
              <td class="name">{{ m.groupKey || '未知模型' }}</td>
              <td class="num">{{ m.requestCount }}</td>
              <td class="num">{{ fmt(m.promptTokens) }}</td>
              <td class="num">{{ fmt(m.completionTokens) }}</td>
              <td class="num">{{ rateText(m.cacheHitRate) }}</td>
              <td class="num strong">{{ costText(m.costEstimate, m.pricedRequestCount) }}</td>
            </tr>
          </tbody>
        </table>
        <p v-else class="state small">该时间范围内暂无记录</p>
      </section>
    </template>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import {
  getUsageByConversation,
  getUsageByModel,
  getUsageDetail,
  getUsageSummary,
  type TokenUsageDetail,
  type TokenUsageGroup,
  type TokenUsageSummary,
  type UsageRange
} from '@/api/token-usage'

const RANGES: Array<{ value: UsageRange; label: string }> = [
  { value: 'today', label: '今天' },
  { value: '7d', label: '近 7 天' },
  { value: '30d', label: '近 30 天' },
  { value: 'all', label: '全部' }
]

const range = ref<UsageRange>('today')
const loading = ref(false)
const error = ref('')

const summary = ref<TokenUsageSummary>(emptySummary())
const conversations = ref<TokenUsageGroup[]>([])
const models = ref<TokenUsageGroup[]>([])

const activeConversation = ref('')
const detailList = ref<TokenUsageDetail[]>([])
const detailLoading = ref(false)

const hitRateText = computed(() => rateText(summary.value.cacheHitRate))
const hitClass = computed(() => {
  const r = summary.value.cacheHitRate
  if (r < 0) return ''
  if (r >= 0.9) return 'good'
  if (r >= 0.6) return 'warn'
  return 'bad'
})

onMounted(load)

async function load() {
  loading.value = true
  error.value = ''
  try {
    const [s, c, m] = await Promise.all([
      getUsageSummary(range.value),
      getUsageByConversation(range.value, 10),
      getUsageByModel(range.value)
    ])
    summary.value = s || emptySummary()
    conversations.value = c || []
    models.value = m || []
  } catch (e: any) {
    error.value = e?.message || '加载失败，请确认后端服务已启动'
  } finally {
    loading.value = false
  }
}

function changeRange(next: UsageRange) {
  if (range.value === next) return
  range.value = next
  closeDetail()
  load()
}

async function toggleDetail(groupKey: string) {
  if (activeConversation.value === groupKey) {
    closeDetail()
    return
  }
  const id = Number(groupKey)
  if (!Number.isFinite(id)) return
  activeConversation.value = groupKey
  detailLoading.value = true
  try {
    detailList.value = (await getUsageDetail(id)) || []
  } catch {
    detailList.value = []
  } finally {
    detailLoading.value = false
  }
}

function closeDetail() {
  activeConversation.value = ''
  detailList.value = []
}

function emptySummary(): TokenUsageSummary {
  return {
    requestCount: 0,
    promptTokens: 0,
    completionTokens: 0,
    totalTokens: 0,
    cacheHitTokens: 0,
    cacheMissTokens: 0,
    reasoningTokens: 0,
    costEstimate: 0,
    pricedRequestCount: 0,
    cacheHitRate: -1,
    cacheHitCost: 0,
    cacheMissCost: 0,
    outputCost: 0,
    breakdownAvailable: false
  }
}

/** 命中率文本（-1 = 无数据） */
function rateText(rate: number): string {
  if (rate === undefined || rate === null || rate < 0) return '—'
  return (rate * 100).toFixed(1) + '%'
}

/** 单条记录的命中率 */
function rateOf(hit: number, miss: number): number {
  const denom = (hit || 0) + (miss || 0)
  if (denom <= 0) return -1
  return (hit || 0) / denom
}

/** 费用金额（保留 4 位，极小值显示 <0.0001） */
function money(value: number | null | undefined): string {
  const v = Number(value) || 0
  if (v > 0 && v < 0.0001) return '<0.0001'
  return v.toFixed(4)
}

/**
 * 金额展示（带「不可用」语义）。
 *
 * <p>后端只为配过单价的模型计算费用，没配时 {@code cost_estimate} 为 NULL。
 * 若渲染成 ¥0 会被读成「这个模型免费」，因此这里明确显示「—」。</p>
 *
 * @param value       估算费用
 * @param pricedCount 有单价、可估算成本的请求条数（undefined 表示明细行，只看 value 是否为空）
 */
function costText(value: number | null | undefined, pricedCount?: number): string {
  if (pricedCount !== undefined && pricedCount <= 0) return '—'
  if (value === null || value === undefined) return '—'
  return '¥' + money(value)
}

/** 汇总金额：整批请求都没配单价时显示「—」 */
const summaryCostText = computed(() =>
  costText(summary.value.costEstimate, summary.value.pricedRequestCount)
)

/** 汇总金额下方说明：把「为什么是 —」讲清楚，避免用户以为统计坏了 */
const summaryCostHint = computed(() => {
  const s = summary.value
  if (s.pricedRequestCount !== undefined && s.pricedRequestCount <= 0) {
    return `${s.requestCount} 次请求 · 该模型未配置单价`
  }
  if (s.pricedRequestCount !== undefined && s.pricedRequestCount < s.requestCount) {
    return `${s.requestCount} 次请求 · 其中 ${s.requestCount - s.pricedRequestCount} 次未配置单价`
  }
  return `${s.requestCount} 次请求`
})

/** token 数量可读化 */
function fmt(value: number): string {
  const v = Number(value) || 0
  if (v >= 10_000_000) return (v / 1_000_000).toFixed(1) + 'M'
  if (v >= 10_000) return (v / 1000).toFixed(1) + 'K'
  return String(v)
}

/** 费用结构占比 */
function pct(part: number): number {
  const total = summary.value.cacheHitCost + summary.value.cacheMissCost + summary.value.outputCost
  if (!total || total <= 0) return 0
  return Math.max(0, Math.min(100, ((Number(part) || 0) / total) * 100))
}
</script>

<style scoped>
/* 颜色全部走 Layout.vue 在 .layout-container 上定义的 CSS 变量：
   --bg-card / --bg-root / --bg-hover / --text-1..4 / --border / --accent / --green
   这样两件事自动完成，不需要本页单独写两套色：
     1) 暗色主题：Layout 在 [data-theme="dark"] 下覆盖同名变量，卡片/表格跟着变深；
     2) 背景图 + 透明度：Layout 在 html.has-app-bg 下把 --bg-card 改成半透明，
        卡片自然透出壁纸（旧的 --bg-1 / --bg-2 / --primary 在本项目根本不存在，
        只会命中fallback 的写死色值，导致暗色下仍是白底、有壁纸时仍是不透明白块）。 */
.usage-page {
  padding: 20px 24px;
  color: var(--text-1, #1a1a2e);
  font-size: 13px;
  overflow-y: auto;
  height: 100%;
  box-sizing: border-box;
}

.page-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
  margin-bottom: 18px;
}

.page-head h2 {
  margin: 0 0 4px;
  font-size: 17px;
  font-weight: 600;
}

.hint {
  margin: 0;
  color: var(--text-3, #9696aa);
  font-size: 12px;
}

.range-switch {
  display: flex;
  gap: 6px;
  flex-wrap: wrap;
  flex-shrink: 0;
}

.range-btn {
  padding: 5px 12px;
  border: 1px solid var(--border, #e8e5f0);
  background: var(--bg-card, #fff);
  color: var(--text-2, #5c5c78);
  border-radius: 6px;
  cursor: pointer;
  font-size: 12px;
  font-family: inherit;
  transition: all 0.15s;
}

.range-btn:hover {
  border-color: var(--accent, #8b5cf6);
  color: var(--accent, #8b5cf6);
}

.range-btn.active {
  background: var(--accent, #8b5cf6);
  border-color: var(--accent, #8b5cf6);
  color: #fff;
}

.state {
  padding: 32px;
  text-align: center;
  color: var(--text-3, #9696aa);
}

.state.small {
  padding: 16px;
  font-size: 12px;
}

.state.error {
  color: var(--red, #ef4444);
}

.retry {
  margin-left: 8px;
  padding: 3px 10px;
  border: 1px solid currentColor;
  background: transparent;
  color: inherit;
  border-radius: 5px;
  cursor: pointer;
  font-size: 12px;
  font-family: inherit;
}

.cards {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(190px, 1fr));
  gap: 12px;
  margin-bottom: 20px;
}

.card {
  display: flex;
  flex-direction: column;
  gap: 4px;
  padding: 14px 16px;
  border: 1px solid var(--border, #e8e5f0);
  border-radius: 10px;
  background: var(--bg-card, #fff);
}

.card .label {
  color: var(--text-3, #9696aa);
  font-size: 12px;
}

.card .value {
  color: var(--text-1, #1a1a2e);
  font-size: 21px;
  font-weight: 600;
  line-height: 1.2;
  font-variant-numeric: tabular-nums;
}

.card .value.good {
  color: var(--green, #10b981);
}

.card .value.warn {
  color: var(--orange, #faad14);
}

.card .value.bad {
  color: var(--red, #ef4444);
}

.card .sub {
  color: var(--text-3, #9696aa);
  font-size: 11px;
}

.block {
  margin-bottom: 22px;
}

.block h3 {
  margin: 0 0 10px;
  color: var(--text-1, #1a1a2e);
  font-size: 14px;
  font-weight: 600;
}

.bar {
  display: flex;
  height: 12px;
  border-radius: 6px;
  overflow: hidden;
  background: var(--bg-hover, rgba(139, 92, 246, 0.08));
}

.seg {
  height: 100%;
  transition: width 0.3s;
}

.seg.hit,
.dot.hit {
  background: var(--green, #10b981);
}

.seg.miss,
.dot.miss {
  background: var(--red, #ef4444);
}

.seg.out,
.dot.out {
  background: var(--accent, #8b5cf6);
}

.legend {
  display: flex;
  gap: 18px;
  flex-wrap: wrap;
  margin-top: 8px;
  color: var(--text-2, #5c5c78);
  font-size: 12px;
}

.legend .dot {
  display: inline-block;
  width: 8px;
  height: 8px;
  border-radius: 2px;
  margin-right: 5px;
  vertical-align: middle;
}

.tip {
  margin: 10px 0 0;
  padding: 8px 12px;
  border-left: 3px solid var(--accent, #8b5cf6);
  background: var(--bg-hover, rgba(139, 92, 246, 0.08));
  color: var(--text-2, #5c5c78);
  font-size: 12px;
  line-height: 1.6;
  border-radius: 0 6px 6px 0;
}

.tbl {
  width: 100%;
  border-collapse: collapse;
  color: var(--text-1, #1a1a2e);
  font-size: 12px;
}

.tbl th,
.tbl td {
  padding: 8px 10px;
  text-align: left;
  border-bottom: 1px solid var(--border, #e8e5f0);
}

.tbl th {
  color: var(--text-3, #9696aa);
  font-weight: 500;
  white-space: nowrap;
}

.tbl .num {
  text-align: right;
  font-variant-numeric: tabular-nums;
}

.tbl .name {
  max-width: 320px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.tbl .strong {
  font-weight: 600;
}

.tbl tbody tr:hover {
  background: var(--bg-hover, rgba(139, 92, 246, 0.08));
}

.tbl tbody tr.clickable {
  cursor: pointer;
}

.tbl tbody tr.active {
  background: var(--bg-hover, rgba(139, 92, 246, 0.08));
}

.tbl.small th,
.tbl.small td {
  padding: 6px 8px;
  font-size: 11.5px;
}

.detail {
  margin-top: 16px;
  padding: 14px 16px;
  border: 1px solid var(--border, #e8e5f0);
  border-radius: 10px;
  background: var(--bg-card, #fff);
}

.detail h4 {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin: 0 0 10px;
  color: var(--text-1, #1a1a2e);
  font-size: 13px;
  font-weight: 600;
}

.link {
  border: none;
  background: transparent;
  color: var(--accent, #8b5cf6);
  cursor: pointer;
  font-size: 12px;
  font-family: inherit;
}

/* ---------- 背景图模式：卡片/明细毛玻璃 ----------
   Layout 在 html.has-app-bg 下已把 --bg-card 改成 0.72 半透明（见 style.css），
   这里再补 backdrop-filter 与更薄的 0.5 底，与「数据管理 / 个人信息」等页面观感一致，
   避免大卡片把壁纸整片挡住。scoped 编译后带 [data-v]，优先级高于普通 .card 规则。 */
html.has-app-bg .usage-page .card,
html.has-app-bg .usage-page .detail {
  background: rgba(255, 255, 255, 0.5);
  backdrop-filter: blur(14px);
  -webkit-backdrop-filter: blur(14px);
}

html.has-app-bg[data-theme="dark"] .usage-page .card,
html.has-app-bg[data-theme="dark"] .usage-page .detail {
  background: rgba(26, 29, 34, 0.5);
}
</style>
