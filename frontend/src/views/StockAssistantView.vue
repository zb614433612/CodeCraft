<template>
  <div class="stock-assistant-view">
    <!-- 左侧股票列表 -->
    <aside class="stock-sidebar" :class="{ collapsed }">
      <div class="sidebar-header">
        <div class="sidebar-tabs" :class="{ collapsed }">
          <button
            :class="['tab-btn', { active: !watchlistActive }]"
            @click="switchToSearch"
          ><span class="tab-full">股票列表</span><span class="tab-short">股</span></button>
          <button
            :class="['tab-btn', { active: watchlistActive }]"
            @click="switchToWatchlist"
          ><span class="tab-full">自选股</span><span class="tab-short">选</span></button>
        </div>
        <div class="sidebar-collapse-btn" @click="toggleCollapsed">
          <MenuFoldOutlined v-if="!collapsed" />
          <MenuUnfoldOutlined v-else />
        </div>
      </div>
      <template v-if="!watchlistActive">
        <!-- 搜索与筛选 -->
        <div class="filter-section">
          <a-input-search
            v-model:value="keyword"
            placeholder="搜索股票代码/名称"
            enter-button
            @search="handleSearch"
            :loading="isLoading"
          />
          <div class="filter-row">
            <a-select
              v-model:value="market"
              placeholder="市场"
              allow-clear
              class="filter-select"
              @change="handleSearch"
            >
              <a-select-option value="SH">沪市</a-select-option>
              <a-select-option value="SZ">深市</a-select-option>
            </a-select>
            <a-select
              v-model:value="statusFilter"
              placeholder="状态"
              allow-clear
              class="filter-select"
              @change="handleSearch"
            >
              <a-select-option :value="1">正常</a-select-option>
              <a-select-option :value="0">退市/停牌</a-select-option>
            </a-select>
          </div>
        </div>
        <!-- 股票列表 -->
        <div class="stock-list" ref="stockListRef">
          <div v-if="isLoading" class="loading-state">
            <a-spin />
            <span>加载中...</span>
          </div>
          <div v-else-if="stockList.length === 0" class="empty-state">
            <p>请输入关键词搜索股票</p>
          </div>
          <div
            v-for="item in stockList"
            :key="item.id"
            :class="['stock-item', { active: selectedStock?.id === item.id }]"
            @click="selectStock(item)"
          >
            <div class="stock-left">
              <div class="stock-name">{{ item.name }}</div>
              <div class="stock-code">{{ item.tsCode || '-' }}</div>
            </div>
            <div class="stock-right" v-if="realtimeMap[item.tsCode]">
              <div class="rt-price" :class="realtimeMap[item.tsCode].change_ >= 0 ? 'up' : 'down'">
                {{ realtimeMap[item.tsCode].price.toFixed(2) }}
              </div>
              <div class="rt-change" :class="realtimeMap[item.tsCode].change_ >= 0 ? 'up' : 'down'">
                {{ realtimeMap[item.tsCode].change_ >= 0 ? '+' : '' }}{{ realtimeMap[item.tsCode].pctChange.toFixed(2) }}%
              </div>
            </div>
          </div>
        </div>
        <!-- 分页 -->
        <div class="pagination-bar" v-if="totalPages > 1">
          <a-pagination
            v-model:current="currentPage"
            :total="totalRecords"
            :pageSize="pageSize"
            size="small"
            :showSizeChanger="false"
            show-less-items
            showQuickJumper
            @change="handlePageChange"
          />
        </div>
      </template>
      <!-- 自选股 -->
      <template v-else>
        <div class="watchlist-toolbar">
          <span class="wl-section-title">我的分组</span>
          <a-button size="small" type="primary" @click="showCreateGroup">+ 新建分组</a-button>
        </div>
        <div class="watchlist-scroll">
          <div v-if="watchlistLoading" class="loading-state">
            <a-spin />
            <span>加载中...</span>
          </div>
          <div v-else-if="watchlistGroups.length === 0" class="empty-state">
            <p>还没有自选股分组</p>
            <a-button size="small" type="primary" ghost @click="showCreateGroup">创建第一个分组</a-button>
          </div>
          <div v-else v-for="group in watchlistGroups" :key="group.id" class="wl-group">
            <div class="wl-group-header" @click="toggleWatchlistGroup(group.id)">
              <FolderOutlined class="wl-folder-icon" />
              <span class="wl-group-name">{{ group.name }}</span>
              <span class="wl-group-count">{{ group.stocks.length }}</span>
              <span class="wl-group-arrow">{{ expandedGroupIds.has(group.id) ? '▼' : '▶' }}</span>
              <div class="wl-group-actions" @click.stop>
                <EditOutlined class="wl-action-icon" @click="showEditGroup(group)" />
                <DeleteOutlined class="wl-action-icon wl-action-del" @click="confirmDeleteGroup(group)" />
              </div>
            </div>
            <div v-if="expandedGroupIds.has(group.id)" class="wl-group-stocks">
              <div v-if="group.stocks.length === 0" class="wl-empty-stock">暂无股票</div>
              <div
                v-for="stock in group.stocks"
                :key="stock.id"
                :class="['wl-stock-item', { active: selectedStock?.tsCode === stock.tsCode }]"
                @click="selectWatchlistStock(stock)"
              >
                <div class="wl-stock-info">
                  <span class="stock-name">{{ stock.stockName }}</span>
                  <span class="stock-code">{{ stock.tsCode }}</span>
                </div>
                <div class="wl-stock-price" v-if="watchlistRtMap[stock.tsCode]">
                  <span :class="watchlistRtMap[stock.tsCode].change_ >= 0 ? 'up' : 'down'">
                    {{ watchlistRtMap[stock.tsCode].price.toFixed(2) }}
                  </span>
                  <span class="wl-stock-pct" :class="watchlistRtMap[stock.tsCode].change_ >= 0 ? 'up' : 'down'">
                    {{ watchlistRtMap[stock.tsCode].change_ >= 0 ? '+' : '' }}{{ watchlistRtMap[stock.tsCode].pctChange.toFixed(2) }}%
                  </span>
                </div>
                <span class="wl-stock-remove" @click.stop="removeStockFromWatchlist(group, stock)" title="移除自选">×</span>
              </div>
            </div>
          </div>
        </div>
      </template>
    </aside>

    <!-- 右侧主区域 -->
    <main class="stock-main">
      <div v-if="!selectedStock" class="placeholder">
        <h2>股票助手</h2>
        <p>从左侧选择一支股票查看 K 线图</p>
      </div>
      <div v-else-if="isKlineLoading" class="placeholder">
        <a-spin />
        <p style="margin-top: 16px">加载 K 线数据中...</p>
      </div>
      <div v-else-if="klineError" class="placeholder">
        <div v-if="isMinKlineMode" style="margin-bottom: 12px;">
          <a-button size="small" @click="backToDailyKline">← 返回日K</a-button>
        </div>
        <h2 class="error-title">加载失败</h2>
        <p>{{ klineError }}</p>
      </div>
      <div v-else class="kline-container">
        <div class="kline-header">
          <div class="kline-header-left">
            <template v-if="isMinKlineMode">
              <a-button size="small" @click="backToDailyKline">← 返回日K</a-button>
              <span class="kline-title">{{ selectedStock.name }} {{ minKlineDate }} 分时图</span>
              <StarOutlined v-if="selectedStock && !isInAnyWatchlist" class="wl-star-btn" @click="showWatchlistPicker" />
              <StarFilled v-else-if="selectedStock" class="wl-star-btn wl-starred" @click="showWatchlistPicker" />
            </template>
            <template v-else>
              <span class="kline-title">{{ selectedStock.name }}（{{ selectedStock.tsCode }}）</span>
              <StarOutlined v-if="selectedStock && !isInAnyWatchlist" class="wl-star-btn" @click="showWatchlistPicker" />
              <StarFilled v-else-if="selectedStock" class="wl-star-btn wl-starred" @click="showWatchlistPicker" />
              <a-select
                v-model:value="klineAdjust"
                size="small"
                style="width: 96px"
                @change="handleAdjustChange"
              >
                <a-select-option value="forward">前复权</a-select-option>
                <a-select-option value="raw">不复权</a-select-option>
              </a-select>
            </template>
          </div>
        </div>
        <div class="kline-body">
          <div class="kline-chart-wrap">
            <div ref="klineChartRef" class="kline-chart"></div>
          </div>
          <div class="kline-sidebar" v-if="selectedRealtime">
            <div class="sr-grid">
              <div class="sr-header" @click="srGridCollapsed = !srGridCollapsed">
                <span class="sr-header-time">{{ currentTime }}</span>
                <span class="sr-header-arrow">{{ srGridCollapsed ? '▶' : '▼' }}</span>
              </div>
              <div v-show="srGridCollapsed" class="sr-compact">
                <div class="sc-main-row">
                  <span class="sc-price" :class="selectedRealtime.change_ >= 0 ? 'up' : 'down'">{{ selectedRealtime.price.toFixed(2) }}</span>
                  <span class="sc-change" :class="selectedRealtime.change_ >= 0 ? 'up' : 'down'">{{ selectedRealtime.change_ >= 0 ? '+' : '' }}{{ selectedRealtime.change_.toFixed(2) }}</span>
                  <span class="sc-pct" :class="selectedRealtime.change_ >= 0 ? 'up' : 'down'">{{ selectedRealtime.change_ >= 0 ? '+' : '' }}{{ selectedRealtime.pctChange.toFixed(2) }}%</span>
                </div>
                <div class="sc-sub-row">
                  <span class="sc-label">今开 <em>{{ selectedRealtime.open.toFixed(2) }}</em></span>
                  <span class="sc-label">最高 <em class="up">{{ selectedRealtime.high.toFixed(2) }}</em></span>
                  <span class="sc-label">最低 <em class="down">{{ selectedRealtime.low.toFixed(2) }}</em></span>
                  <span class="sc-label">昨收 <em>{{ selectedRealtime.preClose.toFixed(2) }}</em></span>
                </div>
              </div>
              <div v-show="!srGridCollapsed" class="sr-body">
              <div class="sr-item">
                <span class="sr-label">现价</span>
                <span class="sr-value price" :class="selectedRealtime.change_ >= 0 ? 'up' : 'down'">{{ selectedRealtime.price.toFixed(2) }}</span>
              </div>
              <div class="sr-item">
                <span class="sr-label">涨跌</span>
                <span class="sr-value" :class="selectedRealtime.change_ >= 0 ? 'up' : 'down'">{{ selectedRealtime.change_ >= 0 ? '+' : '' }}{{ selectedRealtime.change_.toFixed(2) }}</span>
              </div>
              <div class="sr-item">
                <span class="sr-label">涨跌幅</span>
                <span class="sr-value" :class="selectedRealtime.change_ >= 0 ? 'up' : 'down'">{{ selectedRealtime.change_ >= 0 ? '+' : '' }}{{ selectedRealtime.pctChange.toFixed(2) }}%</span>
              </div>
              <div class="sr-item">
                <span class="sr-label">今开</span>
                <span class="sr-value">{{ selectedRealtime.open.toFixed(2) }}</span>
              </div>
              <div class="sr-item">
                <span class="sr-label">最高</span>
                <span class="sr-value">{{ selectedRealtime.high.toFixed(2) }}</span>
              </div>
              <div class="sr-item">
                <span class="sr-label">最低</span>
                <span class="sr-value">{{ selectedRealtime.low.toFixed(2) }}</span>
              </div>
              <div class="sr-item">
                <span class="sr-label">昨收</span>
                <span class="sr-value">{{ selectedRealtime.preClose.toFixed(2) }}</span>
              </div>
              <div class="sr-divider"></div>
              <div class="sr-item">
                <span class="sr-label">成交量</span>
                <span class="sr-value">{{ formatVol(selectedRealtime.volume) }}</span>
              </div>
              <div class="sr-item">
                <span class="sr-label">成交额</span>
                <span class="sr-value">{{ formatAmount(selectedRealtime.amount) }}</span>
              </div>
              <div class="sr-item">
                <span class="sr-label">换手率</span>
                <span class="sr-value">{{ selectedRealtime.turnoverRate.toFixed(2) }}%</span>
              </div>
              <div class="sr-item" v-if="selectedRealtime.volumeRatio">
                <span class="sr-label">量比</span>
                <span class="sr-value">{{ selectedRealtime.volumeRatio.toFixed(2) }}</span>
              </div>
              <div class="sr-item">
                <span class="sr-label">振幅</span>
                <span class="sr-value">{{ selectedRealtime.amplitude.toFixed(2) }}%</span>
              </div>
              <div class="sr-item">
                <span class="sr-label">均价</span>
                <span class="sr-value">{{ selectedRealtime.avgPrice.toFixed(2) }}</span>
              </div>
              <div class="sr-divider"></div>
              <div class="sr-item">
                <span class="sr-label">涨停</span>
                <span class="sr-value up">{{ selectedRealtime.limitUp.toFixed(2) }}</span>
              </div>
              <div class="sr-item">
                <span class="sr-label">跌停</span>
                <span class="sr-value down">{{ selectedRealtime.limitDown.toFixed(2) }}</span>
              </div>
              <div class="sr-item" v-if="selectedRealtime.pe">
                <span class="sr-label">市盈率(静)</span>
                <span class="sr-value">{{ selectedRealtime.pe.toFixed(2) }}</span>
              </div>
              <div class="sr-item" v-if="selectedRealtime.peTtm">
                <span class="sr-label">市盈率(动)</span>
                <span class="sr-value">{{ selectedRealtime.peTtm.toFixed(2) }}</span>
              </div>
              <div class="sr-item" v-if="selectedRealtime.pb">
                <span class="sr-label">市净率</span>
                <span class="sr-value">{{ selectedRealtime.pb.toFixed(2) }}</span>
              </div>
              <div class="sr-item" v-if="selectedRealtime.eps">
                <span class="sr-label">每股收益</span>
                <span class="sr-value">{{ selectedRealtime.eps.toFixed(3) }}</span>
              </div>
              <div class="sr-item" v-if="selectedRealtime.bvps">
                <span class="sr-label">每股净资产</span>
                <span class="sr-value">{{ selectedRealtime.bvps.toFixed(2) }}</span>
              </div>
              <div class="sr-divider"></div>
              <div class="sr-item" v-if="selectedRealtime.totalMv">
                <span class="sr-label">总市值</span>
                <span class="sr-value">{{ formatMv(selectedRealtime.totalMv) }}</span>
              </div>
              <div class="sr-item" v-if="selectedRealtime.circMv">
                <span class="sr-label">流通市值</span>
                <span class="sr-value">{{ formatMv(selectedRealtime.circMv) }}</span>
              </div>
              </div>
            </div>
            <!-- 买卖五档 -->
            <div class="bid-ask-panel" v-if="bidAskData">
              <div class="ba-section ask">
                <div class="ba-row" v-for="(_, i) in 5" :key="'a' + i">
                  <span class="ba-label ask">卖{{ 5 - i }}</span>
                  <span class="ba-price ask">{{ bidAskData.askPrices[4 - i]?.toFixed(2) ?? '--' }}</span>
                  <span class="ba-vol">{{ formatBaVol(bidAskData.askVolumes[4 - i]) }}</span>
                </div>
              </div>
              <div class="ba-spread" :class="selectedRealtime.change_ >= 0 ? 'up' : 'down'">
                <span class="spread-price">{{ selectedRealtime.price.toFixed(2) }}</span>
                <span class="spread-change">{{ selectedRealtime.change_ >= 0 ? '+' : '' }}{{ selectedRealtime.pctChange.toFixed(2) }}%</span>
              </div>
              <div class="ba-section bid">
                <div class="ba-row" v-for="(_, i) in 5" :key="'b' + i">
                  <span class="ba-label bid">买{{ i + 1 }}</span>
                  <span class="ba-price bid">{{ bidAskData.bidPrices[i]?.toFixed(2) ?? '--' }}</span>
                  <span class="ba-vol">{{ formatBaVol(bidAskData.bidVolumes[i]) }}</span>
                </div>
              </div>
            </div>
            <!-- 分时成交 -->
            <div class="kline-tick">
              <div class="kt-header">
              <span>分时成交</span>
              <button
                class="kt-toggle"
                :class="{ paused: !tickPollingEnabled }"
                @click="toggleTickPolling"
                :title="tickPollingEnabled ? '点击暂停轮询' : '点击开启轮询'"
              >{{ tickPollingEnabled ? '暂停' : '开启' }}</button>
            </div>
              <div class="kt-list" ref="tickListRef" @scroll="onTickScroll">
                <div class="kt-row" v-for="(tick, i) in tickData" :key="i">
                  <span class="kt-time">{{ tick.time }}</span>
                  <span class="kt-price" :class="tick.type === '买' ? 'up' : tick.type === '卖' ? 'down' : 'neutral'">{{ tick.price.toFixed(2) }}</span>
                  <span class="kt-vol">{{ formatTickVol(tick.volume) }}</span>
                  <span class="kt-type" :class="tick.type === '买' ? 'up' : tick.type === '卖' ? 'down' : 'neutral'">{{ tick.type }}</span>
                </div>
              </div>
              <button v-if="!tickAutoScroll" class="kt-scroll-bottom" @click="scrollTickToBottom">直达底部</button>
            </div>
          </div>
        </div>
      </div>

      <!-- 自选股分组选择 -->
      <a-modal
        title="加入自选"
        v-model:open="watchlistPickerVisible"
        @ok="confirmWatchlistPicker"
        :okButtonProps="{ disabled: watchlistPickerSubmitDisabled }"
      >
        <div v-if="watchlistGroups.length === 0" class="wl-picker-empty">
          <p>还没有分组，请先在左侧创建分组</p>
        </div>
        <div v-else class="wl-picker-list">
          <div v-for="group in watchlistGroups" :key="group.id" class="wl-picker-item">
            <a-checkbox
              :checked="watchlistPickerSelected.has(group.id)"
              @change="togglePickerGroup(group.id)"
            >{{ group.name }}（{{ group.stocks.length }} 只）</a-checkbox>
          </div>
        </div>
      </a-modal>

      <!-- 新建/编辑分组 -->
      <a-modal
        :title="groupModalEditId ? '编辑分组' : '新建分组'"
        v-model:open="groupModalVisible"
        @ok="confirmGroupModal"
        :okButtonProps="{ disabled: !groupModalName.trim() }"
      >
        <a-input
          v-model:value="groupModalName"
          :placeholder="groupModalEditId ? '输入分组新名称' : '输入分组名称'"
          @pressEnter="confirmGroupModal"
        />
      </a-modal>
    </main>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted, onUnmounted, nextTick, watch } from 'vue'
import { getStockInfo, getKlineDaily, getKlineMin, getRealtime, getTick, type StockRecord, type KlineDailyRecord, type KlineMinRecord, type RealtimeRecord, type TickRecord } from '@/api/stock'
import { getWatchlistGroups, createWatchlistGroup, updateWatchlistGroup, deleteWatchlistGroup, addWatchlistStocks, removeWatchlistStocks, type WatchlistGroup, type WatchlistStock } from '@/api/watchlist'
import { StarOutlined, StarFilled, EditOutlined, DeleteOutlined, FolderOutlined, MenuFoldOutlined, MenuUnfoldOutlined } from '@ant-design/icons-vue'
import { Modal, message } from 'ant-design-vue'
import * as echarts from 'echarts'

const keyword = ref('')
const collapsed = ref(false)

const toggleCollapsed = () => {
  collapsed.value = !collapsed.value
}
const market = ref<string | undefined>(undefined)
const statusFilter = ref<number | undefined>(undefined)
const currentPage = ref(1)
const pageSize = ref(20)
const totalRecords = ref(0)
const totalPages = ref(0)
const isLoading = ref(false)

const stockList = ref<StockRecord[]>([])
const selectedStock = ref<StockRecord | null>(null)
const stockListRef = ref<HTMLElement | null>(null)

// 自选股
const watchlistActive = ref(false)
const watchlistGroups = ref<WatchlistGroup[]>([])
const watchlistLoading = ref(false)
const expandedGroupIds = ref(new Set<number>())
const watchlistRtMap = ref<Record<string, RealtimeRecord>>({})
let watchlistRtTimer: ReturnType<typeof setInterval> | null = null

// 自选股弹窗
const watchlistPickerVisible = ref(false)
const watchlistPickerSelected = ref(new Set<number>())
const watchlistPickerSubmitDisabled = computed(() => watchlistPickerSelected.value.size === 0)

// 分组弹窗
const groupModalVisible = ref(false)
const groupModalName = ref('')
const groupModalEditId = ref<number | null>(null)

// 当前股票是否在自选分组中
const isInAnyWatchlist = computed(() => {
  if (!selectedStock.value) return false
  return watchlistGroups.value.some(g => g.stocks.some(s => s.tsCode === selectedStock.value!.tsCode))
})

// K线相关
const klineChartRef = ref<HTMLElement | null>(null)
const isKlineLoading = ref(false)
const klineError = ref('')
const klineData = ref<KlineDailyRecord[]>([])
const todayCandle = ref<KlineDailyRecord | null>(null)
const klineAdjust = ref<string>('forward')
let chartInstance: echarts.ECharts | null = null

// 实时行情
const realtimeMap = ref<Record<string, RealtimeRecord>>({})
let pollingTimer: ReturnType<typeof setInterval> | null = null
const isTradingDay = ref(true)
const currentTime = ref('')
let clockTimer: ReturnType<typeof setInterval> | null = null

const srGridCollapsed = ref(true)

// 合并在线K线 → 最终K线数据
const effectiveKlineData = computed(() => {
  if (!todayCandle.value) return klineData.value
  return [...klineData.value, todayCandle.value]
})

const buildTodayCandle = (rt: RealtimeRecord): KlineDailyRecord => ({
  id: 0,
  tsCode: rt.tsCode,
  tradeDate: formatDate(new Date()),
  open: rt.open,
  high: rt.high,
  low: rt.low,
  close: rt.price,
  preClose: rt.preClose,
  change: rt.change_,
  pctChange: rt.pctChange,
  vol: rt.volume,
  amount: rt.amount,
  turnover: rt.turnoverRate,
  createdAt: ''
})

const initTodayCandle = () => {
  const rt = selectedRealtime.value
  if (!rt || !isTradingDay.value || !isTradingTime() || klineData.value.length === 0) {
    todayCandle.value = null
    return
  }
  const lastDate = klineData.value[klineData.value.length - 1].tradeDate
  const todayStr = formatDate(new Date())
  if (lastDate >= todayStr) {
    todayCandle.value = null
    return
  }
  todayCandle.value = buildTodayCandle(rt)
}

const formatDate = (d: Date) =>
  `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`

const checkHoliday = async () => {
  try {
    const year = new Date().getFullYear()
    const res = await fetch(`https://cdn.jsdelivr.net/npm/chinese-days/dist/years/${year}.json`)
    const data = await res.json() as { holidays: string[]; workdays: string[] }
    const today = formatDate(new Date())

    if (data.holidays?.includes(today)) {
      // 法定节假日 → 不交易
      isTradingDay.value = false
    } else {
      // 非节假日 → 按周六周日判断（补班对股票市场无意义）
      const day = new Date().getDay()
      isTradingDay.value = day >= 1 && day <= 5
    }
    console.log('交易日判断:', today, isTradingDay.value)
  } catch (e) {
    console.warn('节假日数据获取失败，使用周判断:', e)
    const day = new Date().getDay()
    isTradingDay.value = day >= 1 && day <= 5
  }
}

const isTradingTime = () => {
  if (!isTradingDay.value) return false
  const t = new Date().getHours() * 100 + new Date().getMinutes()
  return (t >= 930 && t <= 1130) || (t >= 1300 && t <= 1500)
}

const queryRealtime = async () => {
  const codes = stockList.value.map(s => s.tsCode).filter(Boolean)
  if (codes.length === 0) return
  try {
    const response = await getRealtime(codes)
    if (response.code === 200 && response.data) {
      const map: Record<string, RealtimeRecord> = {}
      response.data.forEach(r => { map[r.tsCode] = r })
      realtimeMap.value = map
    }
  } catch {
    // 静默失败，下次轮询重试
  }
}

const startPolling = async () => {
  stopPolling()
  await checkHoliday()
  // 立即查一次
  queryRealtime()
  if (isTradingTime()) {
    pollingTimer = setInterval(queryRealtime, 3000)
  }
}

const stopPolling = () => {
  if (pollingTimer) {
    clearInterval(pollingTimer)
    pollingTimer = null
  }
}

const startClock = () => {
  const update = () => {
    const now = new Date()
    currentTime.value =
      `${String(now.getHours()).padStart(2, '0')}:${String(now.getMinutes()).padStart(2, '0')}:${String(now.getSeconds()).padStart(2, '0')}`
  }
  update()
  clockTimer = setInterval(update, 1000)
}

const stopClock = () => {
  if (clockTimer) {
    clearInterval(clockTimer)
    clockTimer = null
  }
}

const handleVisibility = async () => {
  if (document.visibilityState === 'visible') {
    await startPolling()
    startTickPolling()
    if (isMinKlineMode.value && isTradingTime() && minKlineDate.value === formatDate(new Date())) {
      startMinKlinePolling()
    }
  } else {
    stopPolling()
    stopTickPolling()
    stopMinKlinePolling()
  }
}

// 格式化函数
const formatVol = (v: number) => {
  if (v >= 1e8) return (v / 1e8).toFixed(2) + '亿'
  if (v >= 1e4) return (v / 1e4).toFixed(0) + '万'
  return v.toFixed(0)
}
const formatAmount = (v: number) => {
  if (v >= 1e8) return (v / 1e8).toFixed(2) + '亿'
  if (v >= 1e4) return (v / 1e4).toFixed(0) + '万'
  return v.toFixed(0)
}
const formatMv = (v: number) => {
  if (v >= 1e8) return (v / 1e8).toFixed(2) + '亿'
  if (v >= 1e4) return (v / 1e4).toFixed(0) + '万'
  return v.toFixed(0)
}
const formatBaVol = (v: number) => {
  if (v >= 1e4) return (v / 1e4).toFixed(0) + '万'
  return v.toFixed(0)
}

// 分时成交
const tickData = ref<TickRecord[]>([])
const tickListRef = ref<HTMLElement | null>(null)
const tickAutoScroll = ref(true)
const tickPollingEnabled = ref(true)
let tickTimer: ReturnType<typeof setInterval> | null = null

const queryTick = async () => {
  if (!selectedStock.value?.tsCode) return
  try {
    const res = await getTick(selectedStock.value.tsCode)
    if (res.code === 200 && res.data) {
      tickData.value = res.data
    }
  } catch { /* 静默失败 */ }
}

const startTickPolling = () => {
  stopTickPolling()
  queryTick()
  if (tickPollingEnabled.value && isTradingTime()) {
    tickTimer = setInterval(queryTick, 3000)
  }
}

const stopTickPolling = () => {
  if (tickTimer) {
    clearInterval(tickTimer)
    tickTimer = null
  }
}

const toggleTickPolling = () => {
  tickPollingEnabled.value = !tickPollingEnabled.value
  if (tickPollingEnabled.value) {
    startTickPolling()
  } else {
    stopTickPolling()
  }
}

// 分钟K线
const isMinKlineMode = ref(false)
const minKlineDate = ref('')
const minKlineData = ref<KlineMinRecord[]>([])
const minKlinePreClose = ref(0)
let minKlineTimer: ReturnType<typeof setInterval> | null = null

const formatTickVol = (v: number) => {
  if (v >= 1e4) return (v / 1e4).toFixed(1) + '万'
  return v.toFixed(0)
}

// ---- 分钟K线 ----
const fetchMinKline = async (tsCode: string, date: string, preClose: number) => {
  minKlineDate.value = date
  minKlinePreClose.value = preClose
  isMinKlineMode.value = true
  isKlineLoading.value = true
  klineError.value = ''
  try {
    const res = await getKlineMin(tsCode, date)
    if (res.code === 200 && res.data) {
      minKlineData.value = res.data
      isKlineLoading.value = false
      await nextTick()
      // isKlineLoading 切换导致 kline-chart DOM 重建，需重新初始化图表
      if (klineChartRef.value) {
        chartInstance?.dispose()
        chartInstance = echarts.init(klineChartRef.value)
        bindChartClick()
        renderMinKlineChart(res.data)
      }
    } else {
      klineError.value = res.message || '获取分钟K线数据失败'
      isKlineLoading.value = false
    }
  } catch (error: any) {
    klineError.value = error.message || '网络错误'
    isKlineLoading.value = false
  }
  if (isTradingTime() && date === formatDate(new Date())) {
    startMinKlinePolling()
  }
}

const renderMinKlineChart = (data: KlineMinRecord[]) => {
  if (!chartInstance || data.length === 0) return

  const preClose = minKlinePreClose.value
  if (!preClose) return

  // 在前面插入 9:30 开盘价起点，使曲线从开盘位置开始
  const has930 = data[0].minute.slice(0, 5) === '09:30'
  let points = has930 ? data : [
    { ...data[0], minute: '09:30', close: data[0].open, high: data[0].open, low: data[0].open, vol: 0, amount: 0 },
    ...data
  ]

  // 插入 13:00 点位，采用上午收盘价
  const has1300 = points.some(p => p.minute.slice(0, 5) === '13:00')
  if (!has1300) {
    const morningClose = [...points].reverse().find(p => p.minute < '12:00')
    if (morningClose) {
      points.push({ ...morningClose, minute: '13:00', vol: 0, amount: 0 })
    }
  }

  // ---- 固定 X 轴：生成完整交易时段 09:30-11:30 / 13:00-15:00 ----
  const fullSlots: string[] = []
  for (let m = 30; m <= 59; m++) fullSlots.push(`09:${String(m).padStart(2, '0')}`)
  for (let m = 0; m <= 59; m++) fullSlots.push(`10:${String(m).padStart(2, '0')}`)
  for (let m = 0; m <= 30; m++) fullSlots.push(`11:${String(m).padStart(2, '0')}`)
  for (let m = 0; m <= 59; m++) fullSlots.push(`13:${String(m).padStart(2, '0')}`)
  for (let m = 0; m <= 59; m++) fullSlots.push(`14:${String(m).padStart(2, '0')}`)
  fullSlots.push('15:00')

  // 数据映射
  const dataMap = new Map(points.map(d => [d.minute.slice(0, 5), d]))

  // 完整序列（无数据为 null）
  const times = fullSlots
  const closes = fullSlots.map(t => dataMap.has(t) ? dataMap.get(t)!.close : null)
  const volumes = fullSlots.map(t => dataMap.has(t) ? dataMap.get(t)!.vol : null)

  // 计算分时均线：累计成交额 / 累计成交量
  let cumAmount = 0
  let cumVol = 0
  const avgBySlot: Record<string, number> = {}
  for (const d of points) {
    const key = d.minute.slice(0, 5)
    cumAmount += d.amount
    cumVol += d.vol * 100  // vol 单位为手，转成股
    if (cumVol > 0) {
      avgBySlot[key] = cumAmount / cumVol
    }
  }
  const avgPrices = fullSlots.map(t => avgBySlot[t] ?? null)

  // 按完整时段索引查找实际数据
  const dataAt = (idx: number): KlineMinRecord | null =>
    dataMap.get(fullSlots[idx]) ?? null

  // ---- Y 轴范围：根据数据动态调整，对称于昨收 ----
  const validCloses = closes.filter((c): c is number => c !== null)
  validCloses.push(preClose)
  const dataMin = Math.min(...validCloses)
  const dataMax = Math.max(...validCloses)
  let halfRange = Math.max(Math.abs(dataMax - preClose), Math.abs(preClose - dataMin)) || 1
  // 不超过涨跌停范围
  const rt = selectedRealtime.value
  if (rt?.limitUp && rt?.limitDown && rt.limitUp > rt.limitDown) {
    halfRange = Math.min(halfRange, rt.limitUp - preClose)
  }
  const padding = halfRange * 0.08
  const yMin = preClose - halfRange - padding
  const yMax = preClose + halfRange + padding

  // 整体涨跌方向（取最后一个有效收盘）
  const nonNullCloses = closes.filter(c => c !== null) as number[]
  const lastClose = nonNullCloses.length > 0 ? nonNullCloses[nonNullCloses.length - 1] : preClose
  const overallUp = lastClose >= preClose
  const lineColor = overallUp ? '#ef5350' : '#26a69a'
  const areaTopColor = overallUp ? 'rgba(239,83,80,0.18)' : 'rgba(38,166,154,0.18)'
  const areaBottomColor = overallUp ? 'rgba(239,83,80,0.02)' : 'rgba(38,166,154,0.02)'

  // 轴标签仅在关键时间显示
  const keyTimeLabels = new Set(['09:30', '10:30', '13:00', '14:00', '15:00'])

  const fmtVol = (v: number) => {
    if (v >= 1e8) return (v / 1e8).toFixed(2) + '亿'
    if (v >= 1e4) return (v / 1e4).toFixed(2) + '万'
    return v.toFixed(0)
  }

  const option: echarts.EChartsOption = {
    backgroundColor: '#fff',
    grid: [
      { left: '8%', right: '8%', top: '6%', height: '64%' },
      { left: '8%', right: '8%', top: '78%', height: '14%' }
    ],
    xAxis: [
      {
        type: 'category',
        data: times,
        axisLine: { onZero: false },
        axisLabel: {
          fontSize: 10,
          color: '#8c8c8c',
          interval: 0,
          formatter: (value: string) => keyTimeLabels.has(value) ? value : ''
        },
        splitLine: { show: false },
        gridIndex: 0,
        boundaryGap: false
      },
      {
        type: 'category',
        data: times,
        axisLabel: { show: false },
        axisTick: { show: false },
        axisLine: { show: false },
        splitLine: { show: false },
        gridIndex: 1,
        boundaryGap: false
      }
    ],
    yAxis: [
      {
        // 左轴：价格
        gridIndex: 0,
        scale: false,
        min: yMin,
        max: yMax,
        splitLine: { lineStyle: { color: '#f0f0f0', type: 'dashed' } },
        axisLabel: {
          fontSize: 10,
          color: '#8c8c8c',
          formatter: (value: number) => value.toFixed(2)
        }
      },
      {
        gridIndex: 0,
        scale: false,
        min: yMin,
        max: yMax,
        position: 'right',
        splitLine: { show: false },
        axisTick: { show: false },
        axisLine: { show: false },
        axisLabel: {
          fontSize: 10,
          color: '#8c8c8c',
          formatter: (value: number) => {
            const pct = ((value - preClose) / preClose * 100)
            return `${pct >= 0 ? '+' : ''}${pct.toFixed(2)}%`
          }
        }
      },
      {
        // 成交量Y轴
        gridIndex: 1,
        scale: true,
        splitLine: { show: false },
        axisLabel: { show: false },
        axisTick: { show: false }
      }
    ],
    series: [
      {
        // 分时价格线
        type: 'line',
        name: '价格',
        data: closes,
        xAxisIndex: 0,
        yAxisIndex: 0,
        smooth: true,
        symbol: 'none',
        lineStyle: { width: 1.5, color: lineColor },
        areaStyle: {
          color: new echarts.graphic.LinearGradient(0, 0, 0, 1, [
            { offset: 0, color: areaTopColor },
            { offset: 1, color: areaBottomColor }
          ])
        },
        markLine: {
          silent: true,
          symbol: 'none',
          animation: false,
          lineStyle: { color: '#8c8c8c', type: 'dashed', width: 0.8 },
          label: {
            fontSize: 10,
            color: '#8c8c8c',
            formatter: `昨收 ${preClose.toFixed(2)}`,
            position: 'insideStartTop'
          },
          data: [{ yAxis: preClose }]
        }
      },
      {
        // 分时均线
        type: 'line',
        name: '均价',
        data: avgPrices,
        xAxisIndex: 0,
        yAxisIndex: 0,
        smooth: true,
        symbol: 'none',
        lineStyle: { width: 1, color: '#e6a23c' },
        connectNulls: true
      },
      {
        // 分时成交量
        type: 'bar',
        name: '成交量',
        data: volumes,
        xAxisIndex: 1,
        yAxisIndex: 2,
        itemStyle: {
          color: (params: any) => {
            const i = params.dataIndex
            const cur: number | null = closes[i]
            if (cur === null) return 'transparent'
            if (i === 0 || closes[i - 1] === null) return cur >= preClose ? '#ef5350' : '#26a69a'
            const prev = closes[i - 1] as number
            return cur >= prev ? '#ef5350' : '#26a69a'
          }
        }
      }
    ],
    tooltip: {
      trigger: 'axis',
      axisPointer: { type: 'cross' },
      formatter: (params: any) => {
        if (!params || params.length === 0) return ''
        const idx = params[0].dataIndex
        const d = dataAt(idx)
        if (!d) return ''
        const change = d.close - preClose
        const pctChange = (change / preClose * 100)
        const isUp = d.close >= preClose
        const color = isUp ? '#ef5350' : '#26a69a'
        const avg = avgPrices[idx]
        return `
          <div style="font-size:12px;line-height:2;min-width:160px">
            <div style="font-weight:600;font-size:13px;border-bottom:1px solid #e8e8e8;padding-bottom:4px;margin-bottom:4px">
              ${d.tradeDate} ${d.minute}
            </div>
            <div style="display:flex;justify-content:space-between;gap:20px">
              <span style="color:#8c8c8c">价格</span>
              <span style="font-weight:600;color:${color}">${d.close.toFixed(2)}</span>
            </div>
            <div style="display:flex;justify-content:space-between;gap:20px">
              <span style="color:#8c8c8c">涨跌</span>
              <span style="font-weight:500;color:${color}">${change >= 0 ? '+' : ''}${change.toFixed(2)}</span>
            </div>
            <div style="display:flex;justify-content:space-between;gap:20px">
              <span style="color:#8c8c8c">涨幅</span>
              <span style="font-weight:500;color:${color}">${pctChange >= 0 ? '+' : ''}${pctChange.toFixed(2)}%</span>
            </div>
            <div style="display:flex;justify-content:space-between;gap:20px">
              <span style="color:#8c8c8c">均价</span>
              <span style="font-weight:500;color:#e6a23c">${avg !== null ? avg.toFixed(2) : '-'}</span>
            </div>
            <div style="display:flex;justify-content:space-between;gap:20px;border-top:1px solid #f0f0f0;padding-top:2px;margin-top:2px">
              <span style="color:#8c8c8c">成交量</span>
              <span style="font-weight:500">${fmtVol(d.vol)}</span>
            </div>
          </div>
        `
      }
    },
    dataZoom: [
      { type: 'inside', xAxisIndex: [0, 1] },
      { type: 'slider', xAxisIndex: [0, 1], height: 16, bottom: 8 }
    ]
  }

  chartInstance.setOption(option, true)
}

const startMinKlinePolling = () => {
  stopMinKlinePolling()
  minKlineTimer = setInterval(async () => {
    if (!selectedStock.value?.tsCode || !minKlineDate.value) return
    try {
      const res = await getKlineMin(selectedStock.value.tsCode, minKlineDate.value)
      if (res.code === 200 && res.data) {
        minKlineData.value = res.data
        if (chartInstance) {
          renderMinKlineChart(res.data)
        }
      }
    } catch { /* 静默失败 */ }
  }, 60000)
}

const stopMinKlinePolling = () => {
  if (minKlineTimer) {
    clearInterval(minKlineTimer)
    minKlineTimer = null
  }
}

const backToDailyKline = () => {
  isMinKlineMode.value = false
  minKlineData.value = []
  minKlineDate.value = ''
  stopMinKlinePolling()
  if (chartInstance && effectiveKlineData.value.length > 0) {
    renderChart(effectiveKlineData.value)
  }
}

// 选中股票的实时行情（用于右侧展示）
const selectedRealtime = computed(() => {
  if (!selectedStock.value) return null
  return realtimeMap.value[selectedStock.value.tsCode] || watchlistRtMap.value[selectedStock.value.tsCode] || null
})

// 解析买卖五档数据
const parseArray = (v: string | undefined): number[] => {
  if (!v) return []
  try { return JSON.parse(v) } catch { return [] }
}

const bidAskData = computed(() => {
  const rt = selectedRealtime.value
  if (!rt) return null
  const ap = parseArray(rt.askPrices)
  const av = parseArray(rt.askVolumes)
  const bp = parseArray(rt.bidPrices)
  const bv = parseArray(rt.bidVolumes)
  if (ap.length < 5 || bp.length < 5) return null
  return { askPrices: ap, askVolumes: av, bidPrices: bp, bidVolumes: bv }
})

// 计算每页条数以填满左侧区域
const calcPageSize = () => {
  const listEl = stockListRef.value
  if (!listEl) return 13
  // 测量第一条 item 的实际高度（含 margin-bottom）
  const firstItem = listEl.querySelector('.stock-item') as HTMLElement | null
  if (!firstItem) return 13
  const itemHeight = firstItem.offsetHeight + 4 // + margin-bottom
  const availableHeight = listEl.clientHeight - 12 // - padding-bottom
  return Math.max(5, Math.floor(availableHeight / itemHeight))
}

const fetchStockList = async (page: number = 1) => {
  isLoading.value = true
  try {
    const response = await getStockInfo({
      keyword: keyword.value || undefined,
      market: market.value,
      status: statusFilter.value,
      pageSize: pageSize.value,
      page
    })
    if (response.code === 200 && response.data) {
      stockList.value = response.data.records
      totalRecords.value = response.data.total
      totalPages.value = response.data.totalPages
      // 调试：查看实际API返回的字段
      if (response.data.records.length > 0) {
        console.log('股票记录字段:', Object.keys(response.data.records[0]))
        console.log('第一条记录:', response.data.records[0])
      }
    }
  } catch (error: any) {
    console.error('获取股票列表失败:', error)
  } finally {
    isLoading.value = false
  }
}

const doSearch = async (page: number = 1) => {
  await checkHoliday()
  await fetchStockList(page)
  // 渲染后测量实际 item 高度，校准 pageSize
  await nextTick()
  const corrected = calcPageSize()
  if (corrected !== pageSize.value) {
    pageSize.value = corrected
    await fetchStockList(page)
  }
  // 默认选中第一条股票
  await nextTick()
  if (stockList.value.length > 0) {
    selectStock(stockList.value[0])
  }
  // 刷新后重启实时行情轮询
  await startPolling()
}

const handleSearch = () => {
  currentPage.value = 1
  selectedStock.value = null
  doSearch(1)
}

const handlePageChange = (page: number) => {
  currentPage.value = page
  doSearch(page)
}

const selectStock = (item: StockRecord) => {
  selectedStock.value = item
}

// ---- K线图 ----
let chartResizeObserver: ResizeObserver | null = null

const bindChartClick = () => {
  if (!chartInstance) return
  chartInstance.on('click', 'series.candlestick', (params: any) => {
    if (!isMinKlineMode.value && params.dataIndex !== undefined && selectedStock.value?.tsCode) {
      const data = effectiveKlineData.value
      const candle = data[params.dataIndex]
      if (candle) {
        fetchMinKline(selectedStock.value.tsCode, candle.tradeDate, candle.preClose)
      }
    }
  })
}

const waitAndInitChart = () => {
  if (!klineChartRef.value) return
  // 销毁旧实例，让 ResizeObserver 重新创建
  chartInstance?.dispose()
  chartInstance = null
  if (chartResizeObserver) chartResizeObserver.disconnect()
  chartResizeObserver = new ResizeObserver(() => {
    if (klineChartRef.value && klineChartRef.value.clientWidth > 0 && !chartInstance) {
      chartInstance = echarts.init(klineChartRef.value)
      bindChartClick()
      if (effectiveKlineData.value.length > 0) {
        renderChart(effectiveKlineData.value)
      }
    }
    chartInstance?.resize()
  })
  chartResizeObserver.observe(klineChartRef.value)
}

const renderChart = (data: KlineDailyRecord[]) => {
  if (!chartInstance || data.length === 0) return

  const dates = data.map(d => d.tradeDate.slice(5)) // MM-DD
  const ohlc = data.map(d => [d.open, d.close, d.low, d.high])
  const volumes = data.map(d => d.vol)
  const closes = data.map(d => d.close)

  // 默认显示最近 60 个交易日
  const defaultZoom = 60
  const zoomStart = Math.max(0, ((data.length - defaultZoom) / data.length) * 100)

  // 计算均线
  const calcSMA = (period: number) => {
    const result: (number | null)[] = []
    let sum = 0
    for (let i = 0; i < closes.length; i++) {
      sum += closes[i]
      if (i >= period) sum -= closes[i - period]
      result.push(i >= period - 1 ? +(sum / period).toFixed(2) : null)
    }
    return result
  }

  const maPeriods = [5, 10, 20, 60, 120, 250]
  const maColors = ['#f59e0b', '#3b82f6', '#8b5cf6', '#10b981', '#f97316', '#ec4899']
  const maData = maPeriods.map(p => calcSMA(p))

  const formatVol = (v: number) => {
    if (v >= 1e8) return (v / 1e8).toFixed(2) + '亿'
    if (v >= 1e4) return (v / 1e4).toFixed(2) + '万'
    return v.toFixed(0)
  }

  const option: echarts.EChartsOption = {
    backgroundColor: '#fff',
    legend: {
      data: maPeriods.map((p, i) => ({
        name: `MA${p}`,
        icon: 'line',
        textStyle: { fontSize: 11, color: maColors[i] }
      })),
      top: 4,
      left: 'center',
      itemWidth: 16,
      itemHeight: 10
    },
    grid: [
      { left: '8%', right: '8%', top: '15%', height: '55%' },
      { left: '8%', right: '8%', top: '78%', height: '14%' }
    ],
    xAxis: [
      {
        type: 'category',
        data: dates,
        axisLine: { onZero: false },
        axisLabel: { fontSize: 10, color: '#8c8c8c' },
        splitLine: { show: false },
        gridIndex: 0,
        boundaryGap: true
      },
      {
        type: 'category',
        data: dates,
        axisLabel: { show: false },
        axisTick: { show: false },
        axisLine: { show: false },
        splitLine: { show: false },
        gridIndex: 1,
        boundaryGap: true
      }
    ],
    yAxis: [
      {
        scale: true,
        gridIndex: 0,
        splitLine: { lineStyle: { color: '#f0f0f0', type: 'dashed' } },
        axisLabel: { fontSize: 10, color: '#8c8c8c' }
      },
      {
        scale: true,
        gridIndex: 1,
        splitLine: { show: false },
        axisLabel: { show: false },
        axisTick: { show: false }
      }
    ],
    series: [
      {
        type: 'candlestick',
        name: 'K线',
        data: ohlc,
        xAxisIndex: 0,
        yAxisIndex: 0,
        itemStyle: {
          color: '#ef5350',
          color0: '#26a69a',
          borderColor: '#ef5350',
          borderColor0: '#26a69a'
        },
        markLine: {
          silent: true,
          symbol: 'none',
          animation: false,
          lineStyle: { color: '#f5222d', type: 'dashed', width: 0.8, opacity: 0.35 },
          label: { show: false },
          data: data.map((d, i) => d.upPoint ? { xAxis: i } : null).filter((x): x is { xAxis: number } => x !== null)
        }
      },
      ...maPeriods.map((p, i) => ({
        type: 'line' as const,
        name: `MA${p}`,
        data: maData[i],
        xAxisIndex: 0,
        yAxisIndex: 0,
        smooth: false,
        symbol: 'none',
        lineStyle: { width: 0.6, color: maColors[i] },
        connectNulls: true
      })),
      {
        type: 'bar',
        name: '成交量',
        data: volumes.map((v, i) => {
          const d = data[i]
          return {
            value: v,
            itemStyle: { color: d.close >= d.open ? '#ef5350' : '#26a69a' }
          }
        }),
        xAxisIndex: 1,
        yAxisIndex: 1
      },
      {
        // 起爆点标记
        type: 'scatter',
        name: '起爆点',
        data: data.map(d => d.upPoint ? d.high + (d.high - d.low) * 0.4 : null),
        xAxisIndex: 0,
        yAxisIndex: 0,
        symbol: 'pin',
        symbolSize: 34,
        symbolOffset: [0, -10],
        itemStyle: {
          color: '#f5222d',
          borderColor: '#ffccc7',
          borderWidth: 2,
          shadowBlur: 10,
          shadowColor: 'rgba(245,34,45,0.6)'
        },
        label: {
          show: true,
          formatter: '点火',
          fontSize: 9,
          color: '#fff',
          fontWeight: 'bold',
          position: 'inside'
        },
        z: 10
      }
    ],
    tooltip: {
      trigger: 'axis',
      axisPointer: { type: 'cross' },
      formatter: (params: any) => {
        if (!params || params.length === 0) return ''
        const idx = params[0].dataIndex
        const d = data[idx]
        if (!d) return ''
        const pct = d.pctChange !== null && d.pctChange !== undefined ? d.pctChange : null
        const isUp = d.close >= d.open
        const color = isUp ? '#ef5350' : '#26a69a'
        const arrow = isUp ? '▲' : '▼'
        // 提取均线值
        const maLines = maPeriods.map((p, i) => {
          const v = maData[i][idx]
          return v !== null ? `<div style="display:flex;justify-content:space-between;gap:20px">
            <span style="color:#8c8c8c">MA${p}</span>
            <span style="font-weight:500;color:${maColors[i]}">${v.toFixed(2)}</span>
          </div>` : ''
        }).join('')
        return `
          <div style="font-size:12px;line-height:2;min-width:180px">
            <div style="font-weight:600;font-size:13px;border-bottom:1px solid #e8e8e8;padding-bottom:4px;margin-bottom:4px">
              ${d.tradeDate}
            </div>
            <div style="display:flex;justify-content:space-between;gap:20px">
              <span style="color:#8c8c8c">开盘</span>
              <span style="font-weight:500">${d.open.toFixed(2)}</span>
            </div>
            <div style="display:flex;justify-content:space-between;gap:20px">
              <span style="color:#8c8c8c">收盘</span>
              <span style="font-weight:600;color:${color}">${d.close.toFixed(2)}</span>
            </div>
            <div style="display:flex;justify-content:space-between;gap:20px">
              <span style="color:#8c8c8c">最高</span>
              <span style="font-weight:500">${d.high.toFixed(2)}</span>
            </div>
            <div style="display:flex;justify-content:space-between;gap:20px">
              <span style="color:#8c8c8c">最低</span>
              <span style="font-weight:500">${d.low.toFixed(2)}</span>
            </div>
            <div style="display:flex;justify-content:space-between;gap:20px;border-top:1px solid #f0f0f0;padding-top:2px;margin-top:2px">
              <span style="color:#8c8c8c">涨跌幅</span>
              <span style="font-weight:600;color:${pct !== null ? (pct >= 0 ? '#ef5350' : '#26a69a') : '#8c8c8c'}">
                ${pct !== null ? arrow + ' ' + pct.toFixed(2) + '%' : '-'}
              </span>
            </div>
            ${d.upPoint ? '<div style="display:flex;justify-content:space-between;gap:20px"><span style="color:#8c8c8c">起爆点</span><span style="font-weight:700;color:#f5222d">🔥 是</span></div>' : ''}
            <div style="display:flex;justify-content:space-between;gap:20px">
              <span style="color:#8c8c8c">成交量</span>
              <span style="font-weight:500">${formatVol(d.vol)}</span>
            </div>
            <div style="border-top:1px solid #f0f0f0;padding-top:2px;margin-top:2px">
              ${maLines}
            </div>
          </div>
        `
      }
    },
    dataZoom: [
      { type: 'inside', xAxisIndex: [0, 1], start: zoomStart, end: 100 },
      { type: 'slider', xAxisIndex: [0, 1], start: zoomStart, end: 100, height: 16, bottom: 8 }
    ]
  }

  chartInstance.setOption(option, true)
}

const fetchKline = async (tsCode: string, adjust?: string) => {
  isKlineLoading.value = true
  klineError.value = ''
  try {
    const response = await getKlineDaily(tsCode, adjust || klineAdjust.value)
    if (response.code === 200 && response.data) {
      klineData.value = response.data
      // 先标记加载完成，让 kline-container 渲染出来
      isKlineLoading.value = false
      await nextTick()
      initTodayCandle()
      waitAndInitChart()
      return
    } else {
      klineError.value = response.message || '获取 K 线数据失败'
    }
  } catch (error: any) {
    klineError.value = error.message || '网络错误'
  }
  isKlineLoading.value = false
}

// 复权方式切换
const handleAdjustChange = () => {
  if (selectedStock.value?.tsCode) {
    if (isMinKlineMode.value) {
      backToDailyKline()
    }
    fetchKline(selectedStock.value.tsCode, klineAdjust.value)
  }
}

// 实时行情更新 → 刷新今日在线K线 / 分钟K线当前分钟
watch(selectedRealtime, (rt) => {
  if (rt) {
    if (isTradingTime()) {
      if (todayCandle.value) {
        todayCandle.value = buildTodayCandle(rt)
      } else if (isTradingDay.value && klineData.value.length > 0) {
        const lastDate = klineData.value[klineData.value.length - 1].tradeDate
        if (lastDate < formatDate(new Date())) {
          todayCandle.value = buildTodayCandle(rt)
        }
      }
    }
    // 分钟K线实时更新最后一根K线（仅当日分时图）
    if (isMinKlineMode.value && minKlineData.value.length > 0 && isTradingTime() && minKlineDate.value === formatDate(new Date())) {
      const data = [...minKlineData.value]
      const last = data[data.length - 1]
      if (last) {
        last.close = rt.price
        last.high = Math.max(last.high, rt.price)
        last.low = Math.min(last.low, rt.price)
        minKlineData.value = data
        if (chartInstance) {
          renderMinKlineChart(data)
        }
      }
    }
  }
})

// 分时成交更新 → 自动滚动到底部
watch(tickData, () => {
  if (!tickAutoScroll.value) return
  nextTick(() => {
    const el = tickListRef.value
    if (!el) return
    if (tickData.value.length === 0) {
      el.scrollTop = 0
      return
    }
    requestAnimationFrame(() => {
      el.scrollTop = el.scrollHeight
    })
  })
})

const onTickScroll = () => {
  const el = tickListRef.value
  if (!el) return
  const atBottom = el.scrollHeight - el.scrollTop - el.clientHeight < 10
  tickAutoScroll.value = atBottom
}

const scrollTickToBottom = () => {
  tickAutoScroll.value = true
  nextTick(() => {
    const el = tickListRef.value
    if (!el) return
    el.scrollTop = el.scrollHeight
  })
}

// 选中股票变化时加载 K 线
watch(selectedStock, (stock) => {
  if (stock?.tsCode) {
    todayCandle.value = null
    tickData.value = []
    tickAutoScroll.value = true
    stopMinKlinePolling()
    isMinKlineMode.value = false
    fetchKline(stock.tsCode, klineAdjust.value)
    startTickPolling()
  }
})

// 最终K线变化 → 更新图表数据（不重置 dataZoom，分钟模式下不干扰）
watch(effectiveKlineData, (data) => {
  if (isMinKlineMode.value || !chartInstance || data.length === 0) return
  const dates = data.map(d => d.tradeDate.slice(5))
  const ohlc = data.map(d => [d.open, d.close, d.low, d.high])
  const volumes = data.map(d => d.vol)
  const closes = data.map(d => d.close)

  const calcSMA = (period: number) => {
    const result: (number | null)[] = []
    let sum = 0
    for (let i = 0; i < closes.length; i++) {
      sum += closes[i]
      if (i >= period) sum -= closes[i - period]
      result.push(i >= period - 1 ? +(sum / period).toFixed(2) : null)
    }
    return result
  }
  const maPeriods = [5, 10, 20, 60, 120, 250]
  const maData = maPeriods.map(p => calcSMA(p))

  chartInstance.setOption({
    xAxis: [{ data: dates }, { data: dates }],
    series: [
      { type: 'candlestick', data: ohlc },
      ...maPeriods.map((p, i) => ({ type: 'line', name: `MA${p}`, data: maData[i] })),
      {
        type: 'bar', name: '成交量',
        data: volumes.map((v, i) => {
          const d = data[i]
          return {
            value: v,
            itemStyle: { color: d.close >= d.open ? '#ef5350' : '#26a69a' }
          }
        })
      },
      { type: 'scatter', name: '起爆点', data: data.map(d => d.upPoint ? d.high + (d.high - d.low) * 0.4 : null) }
    ]
  })
})

// ---- 自选股 ----

const switchToSearch = () => {
  watchlistActive.value = false
  stopWatchlistRtPolling()
  // 自动选中股票列表第一支
  if (stockList.value.length > 0) {
    selectStock(stockList.value[0])
  }
}

const switchToWatchlist = async () => {
  watchlistActive.value = true
  await fetchWatchlistGroups()
  startWatchlistRtPolling()
  // 自动选中第一个自选股分组的第一支股票
  const firstGroup = watchlistGroups.value.find(g => g.stocks.length > 0)
  if (firstGroup) {
    selectWatchlistStock(firstGroup.stocks[0])
  }
}

const fetchWatchlistGroups = async () => {
  watchlistLoading.value = true
  try {
    const res = await getWatchlistGroups()
    if (res.code === 200 && res.data) {
      watchlistGroups.value = res.data
      // 默认展开所有分组
      res.data.forEach(g => expandedGroupIds.value.add(g.id))
    }
  } catch (e: any) {
    message.error('获取自选股失败: ' + (e.message || '未知错误'))
  } finally {
    watchlistLoading.value = false
  }
}

const toggleWatchlistGroup = (id: number) => {
  const s = new Set(expandedGroupIds.value)
  if (s.has(id)) s.delete(id)
  else s.add(id)
  expandedGroupIds.value = s
}

const selectWatchlistStock = (stock: WatchlistStock) => {
  selectedStock.value = {
    id: stock.id,
    tsCode: stock.tsCode,
    symbol: stock.symbol,
    market: stock.market,
    name: stock.stockName,
    listDate: '',
    totalShare: 0,
    status: 1,
    createdAt: '',
    updatedAt: ''
  }
  fetchKline(stock.tsCode)
}

// 新建分组
const showCreateGroup = () => {
  groupModalEditId.value = null
  groupModalName.value = ''
  groupModalVisible.value = true
}

// 编辑分组
const showEditGroup = (group: WatchlistGroup) => {
  groupModalEditId.value = group.id
  groupModalName.value = group.name
  groupModalVisible.value = true
}

const confirmGroupModal = async () => {
  const name = groupModalName.value.trim()
  if (!name) return
  try {
    if (groupModalEditId.value) {
      const res = await updateWatchlistGroup(groupModalEditId.value, name)
      if (res.code === 200) {
        message.success('分组已重命名')
      }
    } else {
      const res = await createWatchlistGroup(name)
      if (res.code === 200) {
        message.success('分组已创建')
      }
    }
    groupModalVisible.value = false
    await fetchWatchlistGroups()
  } catch (e: any) {
    message.error('操作失败: ' + (e.message || '未知错误'))
  }
}

// 删除分组
const confirmDeleteGroup = (group: WatchlistGroup) => {
  Modal.confirm({
    title: '删除分组',
    content: `确定要删除分组"${group.name}"吗？分组内的自选股将一并删除。`,
    okText: '删除',
    okType: 'danger',
    cancelText: '取消',
    onOk: async () => {
      try {
        const res = await deleteWatchlistGroup(group.id)
        if (res.code === 200) {
          message.success('分组已删除')
          await fetchWatchlistGroups()
        }
      } catch (e: any) {
        message.error('删除失败: ' + (e.message || '未知错误'))
      }
    }
  })
}

// 移除单只自选股
const removeStockFromWatchlist = (group: WatchlistGroup, stock: WatchlistStock) => {
  Modal.confirm({
    title: '移除自选',
    content: `确定将"${stock.stockName}"从分组"${group.name}"中移除吗？`,
    okText: '移除',
    okType: 'danger',
    cancelText: '取消',
    onOk: async () => {
      try {
        const res = await removeWatchlistStocks(group.id, [stock.tsCode])
        if (res.code === 200) {
          message.success('已移除')
          await fetchWatchlistGroups()
        }
      } catch (e: any) {
        message.error('移除失败: ' + (e.message || '未知错误'))
      }
    }
  })
}

// 自选股弹窗
const showWatchlistPicker = () => {
  watchlistPickerSelected.value = new Set(
    watchlistGroups.value
      .filter(g => g.stocks.some(s => s.tsCode === selectedStock.value?.tsCode))
      .map(g => g.id)
  )
  watchlistPickerVisible.value = true
}

const togglePickerGroup = (id: number) => {
  const s = new Set(watchlistPickerSelected.value)
  if (s.has(id)) s.delete(id)
  else s.add(id)
  watchlistPickerSelected.value = s
}

const confirmWatchlistPicker = async () => {
  const tsCode = selectedStock.value?.tsCode
  if (!tsCode) return

  try {
    // 对于每个分组，检查是否需要添加或删除
    for (const group of watchlistGroups.value) {
      const currentlyIn = group.stocks.some(s => s.tsCode === tsCode)
      const wantIn = watchlistPickerSelected.value.has(group.id)

      if (wantIn && !currentlyIn) {
        await addWatchlistStocks(group.id, [tsCode])
      } else if (!wantIn && currentlyIn) {
        await removeWatchlistStocks(group.id, [tsCode])
      }
    }

    message.success('自选股已更新')
    watchlistPickerVisible.value = false
    await fetchWatchlistGroups()
  } catch (e: any) {
    message.error('操作失败: ' + (e.message || '未知错误'))
  }
}

// 自选股实时行情轮询
const queryWatchlistRt = async () => {
  const codes = watchlistGroups.value.flatMap(g => g.stocks.map(s => s.tsCode)).filter(Boolean)
  if (codes.length === 0) return
  try {
    const res = await getRealtime(codes)
    if (res.code === 200 && res.data) {
      const map: Record<string, RealtimeRecord> = {}
      res.data.forEach(r => { map[r.tsCode] = r })
      watchlistRtMap.value = map
    }
  } catch { /* 静默 */ }
}

const startWatchlistRtPolling = () => {
  stopWatchlistRtPolling()
  queryWatchlistRt()
  if (isTradingTime()) {
    watchlistRtTimer = setInterval(queryWatchlistRt, 3000)
  }
}

const stopWatchlistRtPolling = () => {
  if (watchlistRtTimer) {
    clearInterval(watchlistRtTimer)
    watchlistRtTimer = null
  }
}

// 窗口自适应
const handleResize = () => {
  chartInstance?.resize()
}

let resizeObserver: ResizeObserver | null = null

onMounted(() => {
  startClock()
  nextTick(() => {
    doSearch(1)
  })
  // 监听容器大小变化
  if (stockListRef.value) {
    resizeObserver = new ResizeObserver(() => {
      const corrected = calcPageSize()
      if (corrected !== pageSize.value) {
        pageSize.value = corrected
        fetchStockList(currentPage.value)
      }
    })
    resizeObserver.observe(stockListRef.value)
  }
  window.addEventListener('resize', handleResize)
  // 切回页面时恢复轮询
  document.addEventListener('visibilitychange', handleVisibility)
})

onUnmounted(() => {
  stopPolling()
  stopTickPolling()
  stopMinKlinePolling()
  stopWatchlistRtPolling()
  stopClock()
  resizeObserver?.disconnect()
  chartResizeObserver?.disconnect()
  chartInstance?.dispose()
  window.removeEventListener('resize', handleResize)
  document.removeEventListener('visibilitychange', handleVisibility)
})
</script>

<style scoped>
.stock-assistant-view {
  display: flex;
  height: 100%;
  background-color: #f7f9fc;
}

/* 左侧边栏 */
.stock-sidebar {
  width: 320px;
  background: white;
  border-right: 1px solid #eef2f7;
  display: flex;
  flex-direction: column;
  height: 100%;
  transition: width 0.25s ease;
  overflow: hidden;
  flex-shrink: 0;
}

.stock-sidebar.collapsed {
  width: 80px;
}

.sidebar-header {
  padding: 12px 8px 4px;
  flex-shrink: 0;
  display: flex;
  align-items: center;
  gap: 4px;
}

.sidebar-header h3 {
  margin: 0;
  font-size: 16px;
  color: #1a202c;
}

/* 侧边栏选项卡 */
.sidebar-tabs {
  display: flex;
  background: #f0f2f5;
  border-radius: 6px;
  padding: 2px;
  flex: 1;
  min-width: 0;
}

.tab-btn {
  flex: 1;
  border: none;
  background: transparent;
  padding: 5px 0;
  font-size: 13px;
  color: #666;
  cursor: pointer;
  border-radius: 4px;
  transition: all 0.2s;
  font-family: inherit;
}

.tab-btn.active {
  background: white;
  color: #1a202c;
  font-weight: 600;
  box-shadow: 0 1px 2px rgba(0,0,0,0.08);
}

.tab-btn:hover:not(.active) {
  color: #333;
}

.tab-full { display: inline; }
.tab-short { display: none; }

.sidebar-tabs.collapsed .tab-full { display: none; }
.sidebar-tabs.collapsed .tab-short { display: inline; }
.sidebar-tabs.collapsed .tab-btn {
  font-size: 13px;
  font-weight: 600;
}

.sidebar-collapse-btn {
  cursor: pointer;
  font-size: 18px;
  color: #8c8c8c;
  display: flex;
  align-items: center;
  justify-content: center;
  transition: color 0.2s;
  flex-shrink: 0;
}

.sidebar-collapse-btn:hover {
  color: #1890ff;
}

/* 折叠状态 */
.collapsed .sidebar-tabs {
  flex: 1;
}

.collapsed .filter-section {
  display: none;
}

.collapsed .stock-item .stock-right {
  display: none;
}

.collapsed .stock-item .stock-name {
  font-size: 11px;
  text-align: center;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.collapsed .stock-item .stock-code {
  display: none;
}

.collapsed .stock-item {
  padding: 6px 4px;
  justify-content: center;
}

.collapsed .stock-left {
  text-align: center;
}

.collapsed .stock-list {
  padding: 0 4px 12px;
}

.collapsed .pagination-bar {
  display: none;
}

.collapsed .watchlist-toolbar {
  display: none;
}

.collapsed .watchlist-scroll {
  padding: 4px 2px;
}

.collapsed .wl-group-name,
.collapsed .wl-group-count,
.collapsed .wl-group-arrow,
.collapsed .wl-group-actions,
.collapsed .wl-stock-info .stock-code,
.collapsed .wl-stock-price,
.collapsed .wl-stock-remove {
  display: none;
}

.collapsed .wl-group-header {
  justify-content: center;
  padding: 8px 4px;
}

.collapsed .wl-stock-item {
  justify-content: center;
  padding: 4px 2px;
}

.collapsed .wl-stock-info .stock-name {
  font-size: 11px;
}

.collapsed .wl-empty-stock {
  font-size: 10px;
  padding: 4px;
}

/* 自选股工具栏 */
.watchlist-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 8px 16px;
  border-bottom: 1px solid #eef2f7;
  flex-shrink: 0;
}

.wl-section-title {
  font-size: 13px;
  font-weight: 600;
  color: #1a202c;
}

/* 自选股滚动区 */
.watchlist-scroll {
  flex: 1;
  overflow-y: auto;
  padding: 4px 8px;
}

/* 分组 */
.wl-group {
  margin-bottom: 2px;
}

.wl-group-header {
  display: flex;
  align-items: center;
  gap: 4px;
  padding: 8px 8px 8px 4px;
  cursor: pointer;
  border-radius: 4px;
  transition: background 0.15s;
  user-select: none;
}

.wl-group-header:hover {
  background: #f5f7fa;
}

.wl-folder-icon {
  font-size: 14px;
  color: #faad14;
  flex-shrink: 0;
}

.wl-group-name {
  font-size: 13px;
  font-weight: 600;
  color: #1a202c;
  flex: 1;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.wl-group-count {
  font-size: 11px;
  color: #999;
  background: #f0f2f5;
  padding: 0 6px;
  border-radius: 8px;
  line-height: 18px;
  flex-shrink: 0;
}

.wl-group-arrow {
  font-size: 10px;
  color: #bbb;
  flex-shrink: 0;
  width: 14px;
  text-align: center;
}

.wl-group-actions {
  display: flex;
  gap: 2px;
  opacity: 0;
  transition: opacity 0.15s;
  flex-shrink: 0;
}

.wl-group-header:hover .wl-group-actions {
  opacity: 1;
}

.wl-action-icon {
  font-size: 13px;
  color: #999;
  padding: 2px;
  cursor: pointer;
  border-radius: 3px;
  transition: all 0.15s;
}

.wl-action-icon:hover {
  color: #1890ff;
  background: #e6f7ff;
}

.wl-action-del:hover {
  color: #ff4d4f;
  background: #fff2f0;
}

/* 分组内股票列表 */
.wl-group-stocks {
  margin-left: 8px;
}

.wl-empty-stock {
  padding: 8px 12px;
  font-size: 12px;
  color: #bbb;
  text-align: center;
}

.wl-stock-item {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 6px 8px 6px 12px;
  cursor: pointer;
  border-radius: 4px;
  transition: background 0.15s;
  border-left: 2px solid transparent;
}

.wl-stock-item:hover {
  background: #f5f7fa;
}

.wl-stock-item.active {
  background: #eef4ff;
  border-left-color: #adc6ff;
}

.wl-stock-info {
  display: flex;
  flex-direction: column;
  min-width: 0;
}

.wl-stock-info .stock-name {
  font-size: 13px;
  font-weight: 600;
  color: #1a202c;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.wl-stock-info .stock-code {
  font-size: 11px;
  color: #8c8c8c;
  font-family: 'SF Mono', 'Monaco', 'Menlo', monospace;
}

.wl-stock-price {
  text-align: right;
  flex-shrink: 0;
  line-height: 1.4;
}

.wl-stock-price .up {
  color: #ef5350;
}

.wl-stock-price .down {
  color: #26a69a;
}

.wl-stock-pct {
  font-size: 11px;
  display: block;
}

.wl-stock-remove {
  display: none;
  flex-shrink: 0;
  width: 18px;
  height: 18px;
  line-height: 18px;
  text-align: center;
  font-size: 14px;
  color: #bfbfbf;
  cursor: pointer;
  border-radius: 50%;
  margin-left: 6px;
  user-select: none;
  transition: color 0.15s, background 0.15s;
}

.wl-stock-item:hover .wl-stock-remove {
  display: block;
}

.wl-stock-remove:hover {
  color: #ef5350;
  background: #fff1f0;
}

/* 星标按钮 */
.wl-star-btn {
  font-size: 18px;
  color: #d9d9d9;
  cursor: pointer;
  transition: all 0.2s;
  flex-shrink: 0;
  line-height: 1;
}

.wl-star-btn:hover {
  color: #faad14;
  transform: scale(1.15);
}

.wl-star-btn.wl-starred {
  color: #faad14;
}

/* 自选股选择弹窗 */
.wl-picker-empty {
  padding: 16px 0;
  text-align: center;
  color: #999;
}

.wl-picker-list {
  max-height: 300px;
  overflow-y: auto;
}

.wl-picker-item {
  padding: 8px 0;
}

.wl-picker-item .ant-checkbox-wrapper {
  font-size: 14px;
}

/* 搜索与筛选区域 */
.filter-section {
  padding: 0 12px 12px;
  display: flex;
  flex-direction: column;
  gap: 8px;
  border-bottom: 1px solid #eef2f7;
  margin-bottom: 8px;
}

.filter-row {
  display: flex;
  gap: 8px;
}

.filter-select {
  flex: 1;
  min-width: 0;
}

.stock-list {
  flex: 1;
  overflow-y: hidden;
  padding: 0 12px 12px;
}

.loading-state {
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 40px 20px;
  color: #718096;
  gap: 8px;
}

.empty-state {
  text-align: center;
  padding: 40px 20px;
  color: #8c8c8c;
}

.stock-item {
  display: flex;
  align-items: center;
  padding: 8px 12px;
  border-radius: 6px;
  cursor: pointer;
  margin-bottom: 4px;
  transition: all 0.15s;
  border: 1px solid transparent;
  gap: 8px;
}

.stock-left {
  min-width: 0;
  flex: 1;
}

.stock-right {
  text-align: right;
  flex-shrink: 0;
}

.rt-price {
  font-size: 14px;
  font-weight: 700;
  font-family: 'SF Mono', 'Monaco', 'Menlo', monospace;
  line-height: 1.35;
}

.rt-change {
  font-size: 11px;
  font-weight: 600;
  font-family: 'SF Mono', 'Monaco', 'Menlo', monospace;
  line-height: 1.3;
}

.up { color: #ef5350; }
.down { color: #26a69a; }

.stock-item:hover {
  background-color: #f5f7fa;
  border-color: #d9e1ec;
}

.stock-item.active {
  background-color: #eef4ff;
  border-color: #adc6ff;
}

.stock-name {
  font-weight: 600;
  color: #1a202c;
  font-size: 13px;
  line-height: 1.4;
}

.stock-code {
  color: #8c8c8c;
  font-size: 11px;
  font-family: 'SF Mono', 'Monaco', 'Menlo', monospace;
  line-height: 1.3;
  margin-top: 1px;
}

/* 分页 */
.pagination-bar {
  padding: 12px 8px;
  border-top: 1px solid #eef2f7;
  display: flex;
  justify-content: center;
  overflow-x: auto;
  overflow-y: hidden;
}

.pagination-bar :deep(.ant-pagination) {
  white-space: nowrap;
  display: flex;
  align-items: center;
  flex-wrap: nowrap;
  flex-shrink: 0;
}

.pagination-bar :deep(.ant-pagination-options-quick-jumper) {
  font-size: 12px;
  white-space: nowrap;
}

.pagination-bar :deep(.ant-pagination-options-quick-jumper input) {
  width: 36px;
  height: 20px;
  font-size: 12px;
  margin: 0 2px;
  padding: 0 4px;
}

/* 右侧主区域 */
.stock-main {
  flex: 1;
  display: flex;
  flex-direction: column;
  overflow: hidden;
  background: #f7f9fc;
}

.placeholder {
  text-align: center;
  padding: 40px;
  background: white;
  border-radius: 12px;
  box-shadow: 0 4px 12px rgba(0, 0, 0, 0.1);
  margin: auto;
}

.placeholder h2 {
  color: #1890ff;
  margin-bottom: 16px;
}

.placeholder .error-title {
  color: #dc2626;
}

.placeholder p {
  color: #8c8c8c;
  font-size: 16px;
}

/* K线容器 — 填满剩余空间 */
.kline-container {
  flex: 1;
  display: flex;
  flex-direction: column;
  min-height: 0;
  padding: 8px 12px 4px;
}

.kline-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding-bottom: 8px;
  flex-shrink: 0;
}

.kline-header-left {
  display: flex;
  align-items: center;
  gap: 12px;
}

.kline-title {
  font-size: 18px;
  font-weight: 600;
  color: #1a202c;
}

/* 主体：图表 + 右侧信息栏 */
.kline-body {
  flex: 1;
  display: flex;
  flex-direction: row;
  min-height: 0;
  gap: 8px;
  overflow: hidden;
}

.kline-chart-wrap {
  flex: 1;
  min-width: 0;
  min-height: 0;
  display: flex;
  overflow: hidden;
}

.kline-chart {
  flex: 1;
  min-height: 0;
  min-width: 0;
  background: white;
  border-radius: 8px;
  box-shadow: 0 1px 4px rgba(0, 0, 0, 0.06);
}

/* 右侧信息栏 */
.kline-sidebar {
  width: 230px;
  flex-shrink: 0;
  overflow-y: auto;
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.kline-sidebar::-webkit-scrollbar {
  width: 3px;
}

.kline-sidebar::-webkit-scrollbar-thumb {
  background: #d9d9d9;
  border-radius: 2px;
}

/* 实时行情网格 */
.sr-grid {
  background: white;
  border-radius: 6px;
  box-shadow: 0 1px 3px rgba(0, 0, 0, 0.05);
  flex-shrink: 0;
}

.sr-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 6px 10px;
  cursor: pointer;
  user-select: none;
  border-bottom: 1px solid #f0f0f0;
}

.sr-header:hover {
  background: #fafafa;
}

/* 折叠时紧凑展示 */
.sr-compact {
  padding: 4px 10px 6px;
}

.sc-main-row {
  display: flex;
  align-items: baseline;
  gap: 8px;
  margin-bottom: 2px;
}

.sc-price {
  font-size: 18px;
  font-weight: 700;
  font-family: 'SF Mono', 'Monaco', 'Menlo', monospace;
}

.sc-change {
  font-size: 13px;
  font-weight: 600;
  font-family: 'SF Mono', 'Monaco', 'Menlo', monospace;
}

.sc-pct {
  font-size: 13px;
  font-weight: 600;
  font-family: 'SF Mono', 'Monaco', 'Menlo', monospace;
}

.sc-sub-row {
  display: flex;
  flex-wrap: wrap;
  gap: 3px 10px;
}

.sc-label {
  font-size: 11px;
  color: #8c8c8c;
  white-space: nowrap;
}

.sc-label em {
  font-style: normal;
  font-weight: 600;
  color: #262626;
  font-family: 'SF Mono', 'Monaco', 'Menlo', monospace;
}

.sr-header-time {
  font-size: 14px;
  font-weight: 600;
  color: #262626;
  font-family: 'SF Mono', 'Monaco', 'Menlo', monospace;
}

.sr-header-arrow {
  font-size: 10px;
  color: #8c8c8c;
  transition: transform 0.2s;
}

.sr-body {
  padding: 3px 10px;
}

.sr-item {
  display: flex;
  align-items: center;
  justify-content: space-between;
  font-size: 12px;
  line-height: 1.9;
  padding: 0;
}

.sr-label {
  color: #8c8c8c;
  white-space: nowrap;
}

.sr-value {
  font-weight: 600;
  color: #262626;
  font-family: 'SF Mono', 'Monaco', 'Menlo', monospace;
  text-align: right;
}

.sr-value.price {
  font-size: 16px;
  font-weight: 700;
}

.sr-divider {
  height: 1px;
  background: #f0f0f0;
  margin: 1px 0;
}

/* 买卖五档 */
.bid-ask-panel {
  background: white;
  border-radius: 6px;
  box-shadow: 0 1px 3px rgba(0, 0, 0, 0.05);
  padding: 2px 10px 4px;
  flex-shrink: 0;
}

.ba-section {
  display: flex;
  flex-direction: column;
}

.ba-row {
  display: flex;
  align-items: center;
  gap: 0;
  font-size: 12px;
  line-height: 1.8;
  font-family: 'SF Mono', 'Monaco', 'Menlo', monospace;
}

.ba-label {
  width: 28px;
  text-align: center;
  font-weight: 500;
  font-size: 11px;
  flex-shrink: 0;
}

.ba-label.ask { color: #ef5350; }
.ba-label.bid { color: #26a69a; }

.ba-price {
  width: 68px;
  text-align: right;
  font-weight: 600;
  padding: 0 4px;
}

.ba-price.ask { color: #ef5350; }
.ba-price.bid { color: #26a69a; }

.ba-vol {
  flex: 1;
  text-align: right;
  color: #595959;
  padding: 0 2px;
}

.ba-spread {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 12px;
  padding: 2px 0;
  border-top: 1px solid #f0f0f0;
  border-bottom: 1px solid #f0f0f0;
  margin: 1px 0;
  font-family: 'SF Mono', 'Monaco', 'Menlo', monospace;
}

.ba-spread .spread-price {
  font-size: 15px;
  font-weight: 700;
}

.ba-spread .spread-change {
  font-size: 12px;
  font-weight: 600;
}

/* 分时成交（放在 sidebar 最下方） */
.kline-tick {
  background: white;
  border-radius: 6px;
  box-shadow: 0 1px 3px rgba(0, 0, 0, 0.05);
  flex: 1;
  display: flex;
  flex-direction: column;
  min-height: 0;
  overflow: hidden;
  position: relative;
}

.kt-header {
  font-size: 12px;
  font-weight: 600;
  color: #262626;
  padding: 5px 10px;
  border-bottom: 1px solid #f0f0f0;
  flex-shrink: 0;
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.kt-toggle {
  font-size: 11px;
  padding: 1px 8px;
  border: 1px solid #d9d9d9;
  border-radius: 4px;
  background: #fff;
  color: #1677ff;
  cursor: pointer;
  line-height: 1.6;
  transition: all 0.15s;
  font-family: inherit;
}
.kt-toggle:hover {
  border-color: #1677ff;
  background: #f0f7ff;
}
.kt-toggle.paused {
  color: #52c41a;
  border-color: #52c41a;
}
.kt-toggle.paused:hover {
  background: #f6ffed;
}

.kt-list {
  flex: 1;
  overflow-y: auto;
  padding: 2px 0;
}

.kt-list::-webkit-scrollbar {
  width: 3px;
}

.kt-list::-webkit-scrollbar-thumb {
  background: #d9d9d9;
  border-radius: 2px;
}

.kt-scroll-bottom {
  position: absolute;
  bottom: 6px;
  left: 50%;
  transform: translateX(-50%);
  padding: 2px 12px;
  font-size: 11px;
  color: #1677ff;
  background: #fff;
  border: 1px solid #d9d9d9;
  border-radius: 10px;
  cursor: pointer;
  box-shadow: 0 1px 4px rgba(0, 0, 0, 0.1);
  z-index: 1;
  white-space: nowrap;
  transition: all 0.15s;
}
.kt-scroll-bottom:hover {
  color: #4096ff;
  border-color: #4096ff;
}

.kt-row {
  display: flex;
  align-items: center;
  font-size: 11px;
  font-family: 'SF Mono', 'Monaco', 'Menlo', monospace;
  padding: 1px 10px;
  line-height: 1.8;
}

.kt-time {
  width: 60px;
  color: #8c8c8c;
  flex-shrink: 0;
}

.kt-price {
  width: 56px;
  text-align: right;
  font-weight: 600;
  padding: 0 2px;
  flex-shrink: 0;
}

.kt-vol {
  flex: 1;
  text-align: right;
  color: #595959;
  padding: 0 2px;
}

.kt-type {
  width: 56px;
  text-align: center;
  font-size: 10px;
  flex-shrink: 0;
}
.kt-type.neutral {
  color: #8c8c8c;
}
</style>
