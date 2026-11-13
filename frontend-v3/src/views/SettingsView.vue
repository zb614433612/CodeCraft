<!--
  设置（聚合导航）页（v2）
  ----------------------------------------------------------------------------
  定位：一级导航「设置」落地页——把「更多」面板里的全部管理页收敛成可点击卡片。
        纯导航，不虚构接口。
  数据来源：menu.config.ts 的 MORE_GROUPS（唯一权威），本组件只补一条描述文案。
  别名处理：
    /agents/config -> /agents、/skills/manage -> /skills
    （这两项与一级导航的智能体 / 技能页指向同一实现，路由层也做了 redirect）
  emit('navigate', key) -> 父级切换页面（真实项目中等价于 router.push(key)）
-->
<template>
  <div class="cc-page-scroll cc-set">
    <div class="cc-page-inner">
      <!-- 页面头 -->
      <div class="cc-page-head">
        <div class="cc-page-head__icon">
          <svg class="cc-i"><use href="#i-cog" /></svg>
        </div>
        <div>
          <h1 class="cc-page-head__title">设置</h1>
          <p class="cc-page-head__sub">
            全部管理页面入口；标记「管理员」的页面需要 admin 角色，未列出的页面当前账号无权访问
          </p>
        </div>
      </div>

      <!-- 统计 -->
      <div class="cc-stats-inline" style="margin-bottom: var(--sp-4)">
        <span>配置页 <b>{{ pageCount }}</b></span><span class="cc-dot" />
        <span>需管理员 <b>{{ adminCount }}</b></span><span class="cc-dot" />
        <span>本机偏好 <b>外观设置</b></span>
      </div>

      <!-- 本机偏好：只影响当前设备的对话行为（与「管理页面入口」区分开） -->
      <section class="cc-set-prefs">
        <div class="cc-set-prefs__head">
          <div class="cc-set-prefs__title">本机偏好</div>
          <div class="cc-set-prefs__hint">立即生效；同时镜像一份到服务端作为兜底默认值</div>
        </div>
        <div class="cc-set-prefs__row">
          <div class="cc-set-prefs__meta">
            <div class="cc-set-prefs__label">上下文模式</div>
            <div class="cc-set-prefs__desc">{{ currentContextDesc }}</div>
          </div>
          <div class="cc-set-prefs__modes">
            <button
              v-for="mode in CONTEXT_MODE_OPTIONS"
              :key="mode.value"
              :class="['cc-set-mode', { 'cc-set-mode--active': contextMode === mode.value }]"
              :title="mode.tip"
              @click="onContextModeChange(mode.value)"
            >
              <span class="cc-set-mode__label">{{ mode.label }}</span>
              <span class="cc-set-mode__desc">{{ mode.desc }}</span>
            </button>
          </div>
        </div>
      </section>

      <!-- 分组导航 -->
      <div class="cc-set-grid">
        <section v-for="group in groups" :key="group.title" class="cc-set-group">
          <div class="cc-set-group__title">{{ group.title }}</div>
          <button
            v-for="item in group.items"
            :key="item.key"
            class="cc-set-item"
            @click="go(item.key)"
          >
            <span class="cc-set-item__icon">
              <svg class="cc-i cc-i--sm"><use :href="'#' + item.icon" /></svg>
            </span>
            <span class="cc-set-item__main">
              <span class="cc-set-item__title">
                {{ item.label }}
                <span v-if="item.adminOnly" class="cc-tag cc-tag--warn">管理员</span>
              </span>
              <span class="cc-set-item__desc">{{ describe(item.key) }}</span>
            </span>
            <svg class="cc-i cc-i--sm cc-set-item__arrow"><use href="#i-chev" /></svg>
          </button>
        </section>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted } from 'vue'
import { MORE_GROUPS, type MenuGroup } from '../menu.config'
import { useSettingsStore } from '../store/settings'
import { setConfig } from '../api/config'
import { toast } from '../utils/toast'

const props = withDefaults(
  defineProps<{
    /** 是否管理员：非管理员不显示 adminOnly 入口（与路由守卫同源） */
    isAdmin?: boolean
    /**
     * 进入页面时的数据加载（由 App.vue 的 PAGE_BINDINGS 注入）
     * 页面随路由真实挂载，所以在这里调一次即可；重复进页由父级的「已加载 / 加载中 / 已失败」跳过
     */
    load?: () => void
  }>(),
  { isAdmin: false }
)

const emit = defineEmits<{
  (e: 'navigate', key: string): void
}>()

/** 与一级导航重复的入口做别名，避免出现两个「同一个页面」 */
const ROUTE_ALIAS: Record<string, string> = {
  '/agents/config': '/agents',
  '/skills/manage': '/skills'
}

/** 每个入口的一句话说明（menu.config 只负责导航，不承载文案） */
const DESCRIPTIONS: Record<string, string> = {
  '/agents/config': '人设、模型、工具集与工作目录',
  '/skills/manage': '技能指令、触发词与置信度',
  '/lessons': '工具失败自动捕获的经验库与归并',
  '/mcp': '外部 MCP 服务器连接与工具注册',
  '/tasks/schedule': 'Cron / 一次性任务与执行记录',
  '/files': '上传文件、云端对象与本地副本',
  '/databases': '外部业务库连接与只读 SQL 执行',
  '/logs': '实时 tail 与按日期 / 级别搜索',
  '/data': 'JSON 备份导出 / 导入与一键重置',
  '/p2p': '局域网设备、二维码连接与 Agent 授权',
  '/users': '账号、角色与启用状态',
  '/menu-permission': '菜单项维护与角色菜单分配',
  '/providers': 'LLM 凭证、模型列表与余额查询',
  '/appearance': '主题、背景图与窗口透明度',
  '/about': '版本号、检查更新与升级设置'
}

/** 可见分组：非管理员过滤掉 adminOnly 入口，空分组一并剔除 */
const groups = computed<MenuGroup[]>(() =>
  MORE_GROUPS.map<MenuGroup>(group => ({
    title: group.title,
    items: group.items.filter(item => !item.adminOnly || props.isAdmin)
  })).filter(group => group.items.length > 0)
)

/** 当前账号实际可进的配置页数量（与列表一致，避免统计与列表对不上） */
const pageCount = computed(() => groups.value.reduce((n, g) => n + g.items.length, 0))

/** 系统内需要管理员的页面总数（信息提示，不受当前角色影响） */
const adminCount = computed(() =>
  MORE_GROUPS.reduce((n, g) => n + g.items.filter(item => item.adminOnly).length, 0)
)

// ==================== 本机偏好：上下文模式（三档） ====================

type CtxMode = 'full' | 'balanced' | 'compact'

/**
 * 三档选项（与后端 ContextMode 枚举同源）。
 * 值必须保持 full / balanced / compact 原文，后端按枚举精确判定。
 */
const CONTEXT_MODE_OPTIONS: Array<{ value: CtxMode; label: string; desc: string; tip: string }> = [
  {
    value: 'full',
    label: '全量',
    desc: '历史完整注入',
    tip: '历史消息完整注入（含历史思考过程）—— 最准，成本最高'
  },
  {
    value: 'balanced',
    label: '均衡',
    desc: '去掉历史思考',
    tip: '剔除历史思考过程，工具结果完整保留 —— 推荐默认：省钱且不丢准确性'
  },
  {
    value: 'compact',
    label: '精简',
    desc: '工具仅留摘要',
    tip: '在均衡基础上再摘要化历史工具结果 —— 最省，会改写历史，建议仅上下文逼近上限时使用'
  }
]

const settings = useSettingsStore()
const contextMode = computed<CtxMode>(() => settings.contextMode as CtxMode)
const currentContextDesc = computed(
  () => CONTEXT_MODE_OPTIONS.find(o => o.value === contextMode.value)?.tip || ''
)

/**
 * 切换上下文模式：先落本地（persist 到 localStorage，立即生效），
 * 再镜像到 {@code sys_config.context_mode}（后端在请求未带该参数时用它兜底）。
 * 同步失败不影响当前会话（请求参数里已显式带上），只提示一次。
 */
function onContextModeChange(next: CtxMode) {
  if (contextMode.value === next) return
  settings.contextMode = next
  void setConfig('context_mode', next).catch(() => {
    toast.warning('已保存到本机，但同步服务端失败（不影响当前对话）')
  })
}

function describe(key: string): string {
  return DESCRIPTIONS[key] || ''
}

function go(key: string) {
  emit('navigate', ROUTE_ALIAS[key] || key)
}

/**
 * 「进入页面才拉数据」的触发点（原 App.vue 的 PAGE_LOADERS 已下沉至此）
 * 父级通过 props.load 注入具体加载函数，组件只负责在最合适的时机调用它。
 */
onMounted(() => props.load?.())
</script>

<style scoped>
/* 本机偏好卡片：与下方「管理页面入口」网格在视觉上区分开（一个改行为、一个是跳转） */
.cc-set-prefs {
  margin-bottom: var(--sp-4, 16px);
  padding: var(--sp-4, 16px) var(--sp-5, 20px);
  border: 1px solid var(--border, #d0d7de);
  border-radius: var(--r-lg, 12px);
  background: var(--bg-1, #fff);
}

.cc-set-prefs__head {
  display: flex;
  align-items: baseline;
  gap: var(--sp-3, 12px);
  margin-bottom: var(--sp-3, 12px);
  flex-wrap: wrap;
}

.cc-set-prefs__title {
  font-size: 14px;
  font-weight: 600;
  color: var(--text-1, #1f2328);
}

.cc-set-prefs__hint {
  font-size: 12px;
  color: var(--text-3, #8b949e);
}

.cc-set-prefs__row {
  display: flex;
  align-items: center;
  gap: var(--sp-4, 16px);
  flex-wrap: wrap;
}

.cc-set-prefs__meta {
  flex: 1 1 220px;
  min-width: 200px;
}

.cc-set-prefs__label {
  margin-bottom: 2px;
  font-size: 13px;
  font-weight: 500;
  color: var(--text-1, #1f2328);
}

.cc-set-prefs__desc {
  font-size: 12px;
  line-height: 1.5;
  color: var(--text-3, #8b949e);
}

.cc-set-prefs__modes {
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
}

.cc-set-mode {
  display: flex;
  flex-direction: column;
  gap: 2px;
  min-width: 108px;
  padding: 8px 12px;
  border: 1px solid var(--border, #d0d7de);
  border-radius: var(--r-md, 8px);
  background: var(--bg-1, #fff);
  text-align: left;
  cursor: pointer;
  transition: border-color 0.15s, background 0.15s;
}

.cc-set-mode:hover {
  border-color: var(--accent, #0969da);
}

.cc-set-mode--active {
  border-color: var(--accent, #0969da);
  background: var(--accent-soft, rgba(9, 105, 218, 0.08));
}

.cc-set-mode__label {
  font-size: 13px;
  font-weight: 600;
  color: var(--text-1, #1f2328);
}

.cc-set-mode--active .cc-set-mode__label {
  color: var(--accent, #0969da);
}

.cc-set-mode__desc {
  font-size: 11px;
  color: var(--text-3, #8b949e);
}
</style>
