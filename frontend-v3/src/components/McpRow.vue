<!--
  MCP 服务器行（v2）
  ----------------------------------------------------------------------------
  信息层次：类型图标 + 名称 + 类型 + 状态点 + 权限档  →  地址/命令（等宽截断）
            →  前缀 / 工具数 / 自动注册  →  失败原因（仅 FAILED）
  状态语义（与后端 McpServerVO.status 一致）：
    CONNECTED 已连接（绿点）· DISABLED 未连接（灰点）· FAILED 连接失败（红点 + 错误行）
  操作：
    连接/断开（主操作，常驻）· 刷新工具列表 / 编辑 / 删除（hover 出现）· 启用开关
  数据接缝：
    server.*       -> GET /mcp/servers
    emit('connect'|'disconnect'|'refresh') -> POST /mcp/servers/{id}/{动作}
    emit('toggle-enabled', enabled)         -> PUT /mcp/servers/{id}（全量配置，仅改 enabled）
    emit('delete')                          -> DELETE /mcp/servers/{id}
-->
<template>
  <div class="cc-mcp-row" :class="{ 'is-failed': server.status === 'FAILED' }">
    <!-- 类型图标：http 蓝 / stdio 紫 -->
    <div class="cc-mcp-row__icon" :class="{ 'is-stdio': server.type === 'stdio' }">
      <svg class="cc-i"><use :href="server.type === 'stdio' ? '#i-term' : '#i-link'" /></svg>
    </div>

    <div class="cc-mcp-row__main">
      <!-- 名称 + 类型 + 状态 + 权限 -->
      <div class="cc-mcp-row__title">
        <span class="cc-mcp-name">{{ server.name }}</span>
        <span class="cc-tag">{{ server.type === 'http' ? 'HTTP' : 'STDIO' }}</span>
        <span class="cc-status" :class="statusClass">
          <span class="cc-status__dot" />{{ statusText }}
        </span>
        <span class="cc-tag" :class="permissionTagClass">{{ permissionLabel }}</span>
      </div>

      <!-- 地址 / 命令 -->
      <div class="cc-mcp-row__target">
        <span :title="target">{{ target }}</span>
      </div>

      <!-- 前缀 / 工具数 / 自动注册 -->
      <div class="cc-mcp-row__meta">
        <span>前缀 <code>{{ server.toolPrefix || defaultPrefix }}</code></span>
        <span v-if="server.status === 'CONNECTED'">
          工具 <b>{{ server.registeredToolCount ?? 0 }}</b> / {{ server.toolCount ?? 0 }}
        </span>
        <span v-else>工具 -</span>
        <span>{{ server.autoRegister === 1 ? '自动注册' : '手动注册' }}</span>
      </div>

      <!-- 失败原因 -->
      <div v-if="server.status === 'FAILED' && server.errorMessage" class="cc-mcp-row__error" :title="server.errorMessage">
        <svg class="cc-i cc-i--sm"><use href="#i-bug" /></svg>
        <span>{{ server.errorMessage }}</span>
      </div>
    </div>

    <!-- 右侧：启用开关 + 主操作 + hover 操作 -->
    <div class="cc-mcp-row__side">
      <div
        class="cc-switch"
        :class="{ 'is-on': server.enabled === 1 }"
        :title="server.enabled === 1 ? '已启用' : '已停用'"
        @click="$emit('toggle-enabled', server.enabled === 1 ? 0 : 1)"
      />

      <button
        v-if="server.status === 'CONNECTED'"
        class="cc-text-btn"
        @click="$emit('disconnect', server)"
      >断开</button>
      <button
        v-else
        class="cc-text-btn"
        @click="$emit('connect', server)"
      >{{ server.status === 'FAILED' ? '重试' : '连接' }}</button>

      <div class="cc-mcp-row__ops">
        <button
          v-if="server.status === 'CONNECTED'"
          class="cc-icon-btn cc-icon-btn--sm"
          title="刷新工具列表"
          @click="$emit('refresh', server)"
        >
          <svg class="cc-i cc-i--sm"><use href="#i-refresh" /></svg>
        </button>
        <button class="cc-icon-btn cc-icon-btn--sm" title="编辑" @click="$emit('edit', server)">
          <svg class="cc-i cc-i--sm"><use href="#i-brush" /></svg>
        </button>
        <button class="cc-icon-btn cc-icon-btn--sm" title="删除" @click="$emit('delete', server)">
          <svg class="cc-i cc-i--sm"><use href="#i-trash" /></svg>
        </button>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'

export interface McpServerItem {
  id: number | string
  name: string
  type: 'http' | 'stdio'
  url?: string
  command?: string
  toolPrefix?: string
  /** SAFE / DATA / HIGH_RISK */
  permissionLevel?: string
  enabled?: 0 | 1
  autoRegister?: 0 | 1
  /** CONNECTED / DISABLED / FAILED */
  status?: string
  toolCount?: number
  registeredToolCount?: number
  errorMessage?: string
}

const props = defineProps<{ server: McpServerItem }>()

defineEmits<{
  (e: 'connect', server: McpServerItem): void
  (e: 'disconnect', server: McpServerItem): void
  (e: 'refresh', server: McpServerItem): void
  (e: 'edit', server: McpServerItem): void
  (e: 'delete', server: McpServerItem): void
  (e: 'toggle-enabled', value: 0 | 1): void
}>()

/** 连接目标：http 用 url，stdio 用启动命令 */
const target = computed(() =>
  props.server.type === 'http' ? (props.server.url || '未配置 URL') : (props.server.command || '未配置命令')
)

/** 前缀缺省值：服务器名小写 + 下划线（与后端默认规则一致） */
const defaultPrefix = computed(() => `${(props.server.name || 'mcp').toLowerCase().replace(/\s+/g, '_')}_`)

const statusText = computed(() => {
  switch (props.server.status) {
    case 'CONNECTED': return '已连接'
    case 'FAILED': return '连接失败'
    default: return '未连接'
  }
})

const statusClass = computed(() => {
  switch (props.server.status) {
    case 'CONNECTED': return 'is-connected'
    case 'FAILED': return 'is-failed'
    default: return ''
  }
})

const permissionLabel = computed(() => {
  switch ((props.server.permissionLevel || 'SAFE').toUpperCase()) {
    case 'DATA': return '可写数据'
    case 'HIGH_RISK': return '高危'
    default: return '只读'
  }
})

const permissionTagClass = computed(() => {
  switch ((props.server.permissionLevel || 'SAFE').toUpperCase()) {
    case 'DATA': return 'cc-tag--warn'
    case 'HIGH_RISK': return 'cc-tag--danger'
    default: return 'cc-tag--ok'
  }
})
</script>
