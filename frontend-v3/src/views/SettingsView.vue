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
