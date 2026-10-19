<!--
  数据管理页（v2）
  ----------------------------------------------------------------------------
  布局：页面头（刷新统计）→ 内联统计 → 双列卡片（导出备份 / 导入备份）→ 通栏危险操作 → 重置确认抽屉
  数据接缝（全部在 /api/system 下，均需管理员权限）：
    stats                 -> GET  /api/system/data-stats（7 个类别的行数）
    emit('export', types) -> GET  /api/system/export-data?types=...（附件下载，文件名由 Content-Disposition 给出）
    emit('import', file)  -> POST /api/system/import-data（multipart，字段名 file）
    emit('reset')         -> POST /api/system/reset-data（清表 + 重建出厂数据 + 删文件类数据）
    emit('refresh')       -> 重新拉取 data-stats
    emit('clear-import')  -> 清空上一次导入结果
  说明：
    1) 统计口径：类别行数 = 类别下各表行数之和（后端 DataTransferService.stats()）；
    2) 表数量固定 10 张（llm_provider / agent_config / skill / conversation ×3 / lesson ×2 / mcp_server / db_connection）；
    3) 「最近导出」由前端记录（接口不返回），仅作本次会话内的提示。
-->
<template>
  <div class="cc-page-scroll">
    <div class="cc-page-inner">
      <!-- 页面头 -->
      <div class="cc-page-head">
        <div class="cc-page-head__icon">
          <svg class="cc-i"><use href="#i-disk" /></svg>
        </div>
        <div>
          <h1 class="cc-page-head__title">数据管理</h1>
          <p class="cc-page-head__sub">导出 / 导入 JSON 备份，或重置本机全部数据（接口：/api/system）</p>
        </div>
        <div class="cc-page-head__actions">
          <button class="cc-btn" @click="$emit('refresh')">
            <svg class="cc-i cc-i--sm"><use href="#i-refresh" /></svg>刷新统计
          </button>
        </div>
      </div>

      <!-- 统计 -->
      <div class="cc-stats-inline" style="margin-bottom: var(--sp-4)">
        <span>总记录 <b>{{ totalRecords }}</b> 条</span><span class="cc-dot" />
        <span>类别 <b>{{ DATA_CATEGORIES.length }}</b> 类</span><span class="cc-dot" />
        <span>数据表 <b>{{ tableCount }}</b> 张</span><span class="cc-dot" />
        <span>备份格式 <b>v{{ DATA_FORMAT_VERSION }}</b></span><span class="cc-dot" />
        <span>最近导出 <b>{{ lastExportAt || '本次会话未导出' }}</b></span>
      </div>

      <!-- 三块功能区 -->
      <div class="cc-data-grid">
        <!-- 导出备份 -->
        <DataExportCard :stats="stats" :exporting="exporting" @export="$emit('export', $event)" />

        <!-- 导入备份 -->
        <DataImportCard
          :importing="importing"
          :result="importResult"
          @import="$emit('import', $event)"
          @clear="$emit('clear-import')"
        />

        <!-- 危险操作（整行通栏） -->
        <section class="cc-data-card cc-data-card--wide cc-data-card--danger">
          <div class="cc-data-card__head">
            <div class="cc-data-card__icon">
              <svg class="cc-i"><use href="#i-shield" /></svg>
            </div>
            <div>
              <div class="cc-data-card__title">危险操作</div>
              <div class="cc-data-card__desc">仅管理员可执行；操作不可撤销，建议先导出备份</div>
            </div>
          </div>

          <div class="cc-data-danger-text">
            重置会清空全部业务数据并恢复出厂基础数据（默认管理员 + 内置「AI 助手」），
            同时删除<b>快照、运行日志、P2P 接收文件、附件暂存</b>等文件类数据。
          </div>

          <ul class="cc-data-danger-list">
            <li>会话与聊天消息、上下文压缩记录</li>
            <li>智能体配置、技能与触发词</li>
            <li>定时任务与执行记录</li>
            <li>LLM Provider 配置（含 API Key）</li>
            <li>踩坑经验与归并规则</li>
            <li>MCP 服务器、数据库连接</li>
            <li>用户账号（保留出厂管理员）</li>
            <li>快照 / 日志 / 接收文件 / 附件暂存</li>
          </ul>

          <div class="cc-data-card__foot">
            <button class="cc-btn cc-btn--danger" :disabled="resetting" @click="resetOpen = true">
              <svg class="cc-i cc-i--sm"><use href="#i-trash" /></svg>重置所有数据
            </button>
            <span class="cc-data-hint is-danger">清空后无法恢复，请确认已备份</span>
          </div>
        </section>
      </div>
    </div>

    <!-- 重置确认抽屉（输入「重置」二次确认） -->
    <DataResetDrawer v-model:open="resetOpen" :resetting="resetting" @confirm="$emit('reset')" />
  </div>
</template>

<script setup lang="ts">
import { computed, ref, watch, onMounted } from 'vue'
import DataExportCard from '../components/DataExportCard.vue'
import DataImportCard from '../components/DataImportCard.vue'
import DataResetDrawer from '../components/DataResetDrawer.vue'
import {
  DATA_CATEGORIES,
  DATA_FORMAT_VERSION,
  totalCount,
  type DataImportResult
} from '../utils/data-transfer'

const props = withDefaults(
  defineProps<{
    /** GET /api/system/data-stats：{ llmProvider: 5, agent: 2, skill: 12, ... } */
    stats?: Record<string, number>
    exporting?: boolean
    importing?: boolean
    importResult?: DataImportResult | null
    resetting?: boolean
    /** 最近一次导出时间（前端记录） */
    lastExportAt?: string
    /**
     * 进入页面时的数据加载（由 App.vue 的 PAGE_BINDINGS 注入）
     * 页面随路由真实挂载，所以在这里调一次即可；重复进页由父级的「已加载 / 加载中 / 已失败」跳过
     */
    load?: () => void
  }>(),
  {
    stats: () => ({}),
    exporting: false,
    importing: false,
    importResult: null,
    resetting: false,
    lastExportAt: ''
  }
)

defineEmits<{
  (e: 'refresh'): void
  (e: 'export', types: string[]): void
  (e: 'import', file: File): void
  (e: 'clear-import'): void
  (e: 'reset'): void
}>()

const resetOpen = ref(false)

const totalRecords = computed(() => totalCount(props.stats))

/** 后端覆盖 10 张表（chat 3 张、lesson 2 张，其余各 1 张） */
const tableCount = computed(() =>
  DATA_CATEGORIES.reduce((sum, cat) => sum + cat.tables.length, 0)
)

/** 重置完成后父组件把 resetting 置回 false，此时自动收起抽屉 */
watch(
  () => props.resetting,
  value => {
    if (!value) resetOpen.value = false
  }
)

/**
 * 「进入页面才拉数据」的触发点（原 App.vue 的 PAGE_LOADERS 已下沉至此）
 * 父级通过 props.load 注入具体加载函数，组件只负责在最合适的时机调用它。
 */
onMounted(() => props.load?.())
</script>
