<!--
  MCP 服务器 新增 / 编辑抽屉（v2）
  ----------------------------------------------------------------------------
  右侧滑出（520px）。字段随「传输类型」切换：
    http  -> 端点 URL（必填）+ 请求头 JSON（可选）
    stdio -> 启动命令（必填）
  权限档位用三段选择（只读 / 可写数据 / 高危），比下拉更直观且能表达差异。
  数据接缝：
    v-model:open  -> 抽屉开关
    server        -> null 表示新建，传入对象表示编辑
    emit('save')   -> 回传表单数据；由页面调用 创建/更新 接口
  注意：编辑时 headers 出于安全不回显（后端返回掩码或不返回），
        用户重新填写才会覆盖；留空表示保持原值。
-->
<template>
  <aside class="cc-drawer" :class="{ 'is-open': open }" style="--drawer-w: 520px">
    <DrawerResizer storage-key="mcp-edit" />
    <div class="cc-drawer__head">
      <div style="font-size: var(--fs-md); font-weight: 700">{{ isNew ? '新增 MCP 服务器' : '编辑 MCP 服务器' }}</div>
      <button class="cc-icon-btn" style="margin-left: auto" title="关闭" @click="close">
        <svg class="cc-i cc-i--sm" style="transform: rotate(45deg)"><use href="#i-plus" /></svg>
      </button>
    </div>

    <div class="cc-drawer__body">
      <div class="cc-form">
        <!-- 名称 -->
        <div class="cc-field">
          <label class="cc-field__label">名称<em>*</em></label>
          <input v-model="form.name" class="cc-input" placeholder="如 GitHub、文件系统、内部知识库" maxlength="50" />
        </div>

        <!-- 传输类型 -->
        <div class="cc-field">
          <label class="cc-field__label">传输类型<em>*</em></label>
          <div class="cc-seg" style="width: fit-content">
            <button :class="{ 'is-active': form.type === 'http' }" @click="form.type = 'http'">Streamable HTTP</button>
            <button :class="{ 'is-active': form.type === 'stdio' }" @click="form.type = 'stdio'">stdio（本地进程）</button>
          </div>
        </div>

        <!-- HTTP 字段 -->
        <template v-if="form.type === 'http'">
          <div class="cc-field">
            <label class="cc-field__label">端点 URL<em>*</em></label>
            <input v-model="form.url" class="cc-input" placeholder="http://localhost:3001/mcp" />
            <div class="cc-field__hint">支持 Streamable HTTP 协议的 MCP 端点</div>
          </div>

          <div class="cc-field">
            <label class="cc-field__label">请求头（JSON）</label>
            <textarea
              v-model="form.headers"
              class="cc-textarea"
              rows="2"
              placeholder='{"Authorization":"Bearer xxx"}（可选，仅 http 类型生效）'
            />
            <div v-if="headersConfigured" class="cc-field__hint">已配置请求头（出于安全不回显），重新填写将覆盖</div>
          </div>
        </template>

        <!-- stdio 字段 -->
        <template v-else>
          <div class="cc-field">
            <label class="cc-field__label">启动命令<em>*</em></label>
            <input
              v-model="form.command"
              class="cc-input"
              placeholder="npx -y @modelcontextprotocol/server-github"
            />
            <div class="cc-field__hint">本地进程方式启动，命令需在应用所在环境可执行</div>
          </div>
        </template>

        <!-- 工具名前缀 -->
        <div class="cc-field">
          <label class="cc-field__label">工具名前缀</label>
          <input v-model="form.toolPrefix" class="cc-input" placeholder="如 github_，留空则用服务器名小写 + _" maxlength="30" />
          <div class="cc-field__hint">注册后的工具名为「前缀 + 原始工具名」，避免多服务器冲突</div>
        </div>

        <!-- 权限档位 -->
        <div class="cc-field">
          <label class="cc-field__label">权限档位<em>*</em></label>
          <div class="cc-perm">
            <button
              v-for="opt in permissionOptions"
              :key="opt.value"
              class="cc-perm__item"
              :class="{ 'is-on': form.permissionLevel === opt.value }"
              @click="form.permissionLevel = opt.value"
            >
              <span class="cc-perm__title">{{ opt.label }}</span>
              <span class="cc-perm__desc">{{ opt.value }}</span>
            </button>
          </div>
          <div class="cc-field__hint">权限档位决定该服务器工具在手动/自动模式下的审批策略</div>
        </div>

        <!-- 启用 -->
        <div class="cc-field">
          <div class="cc-switch-row">
            <div class="cc-switch" :class="{ 'is-on': form.enabled === 1 }" @click="form.enabled = form.enabled === 1 ? 0 : 1" />
            <div class="cc-switch-row__text">
              <div class="cc-switch-row__title">启用</div>
              <div class="cc-switch-row__desc">关闭后该服务器不参与工具注册，可在列表中随时开启</div>
            </div>
          </div>
        </div>

        <!-- 自动注册 -->
        <div class="cc-field">
          <div class="cc-switch-row">
            <div class="cc-switch" :class="{ 'is-on': form.autoRegister === 1 }" @click="form.autoRegister = form.autoRegister === 1 ? 0 : 1" />
            <div class="cc-switch-row__text">
              <div class="cc-switch-row__title">自动注册</div>
              <div class="cc-switch-row__desc">应用启动时自动连接并注册其工具（关闭则需手动点「连接」）</div>
            </div>
          </div>
        </div>
      </div>
    </div>

    <div class="cc-drawer__foot">
      <button class="cc-btn" @click="close">取消</button>
      <button class="cc-btn cc-btn--primary" :disabled="!canSave" @click="onSave">保存</button>
    </div>
  </aside>
</template>

<script setup lang="ts">
import DrawerResizer from './DrawerResizer.vue'
import { computed, reactive, watch } from 'vue'
import type { McpServerItem } from './McpRow.vue'

const props = withDefaults(
  defineProps<{
    open: boolean
    server?: (McpServerItem & { headers?: string; headersConfigured?: boolean }) | null
  }>(),
  { server: null }
)

const emit = defineEmits<{
  (e: 'update:open', v: boolean): void
  (e: 'save', payload: Record<string, unknown>): void
}>()

const isNew = computed(() => !props.server)

const permissionOptions = [
  { value: 'SAFE', label: '只读' },
  { value: 'DATA', label: '可写数据' },
  { value: 'HIGH_RISK', label: '高危' }
]

const form = reactive({
  name: '',
  type: 'http' as 'http' | 'stdio',
  url: '',
  command: '',
  headers: '',
  toolPrefix: '',
  permissionLevel: 'SAFE',
  enabled: 1 as 0 | 1,
  autoRegister: 1 as 0 | 1
})

/** 编辑态下是否已配置过请求头（用于展示"重新填写将覆盖"提示） */
const headersConfigured = computed(() => Boolean(props.server?.headersConfigured))

/** 打开时回填 */
watch(
  () => [props.open, props.server] as const,
  () => {
    if (!props.open) return
    const s = props.server as (McpServerItem & { headers?: string }) | null
    form.name = s?.name ?? ''
    form.type = s?.type ?? 'http'
    form.url = s?.url ?? ''
    form.command = s?.command ?? ''
    form.headers = ''   // 安全考虑：不回显，留空表示保持原值
    form.toolPrefix = s?.toolPrefix ?? ''
    form.permissionLevel = (s?.permissionLevel ?? 'SAFE').toUpperCase()
    form.enabled = (s?.enabled ?? 1) as 0 | 1
    form.autoRegister = (s?.autoRegister ?? 1) as 0 | 1
  },
  { immediate: true }
)

const canSave = computed(() => {
  if (!form.name.trim()) return false
  return form.type === 'http' ? form.url.trim().length > 0 : form.command.trim().length > 0
})

function close() {
  emit('update:open', false)
}

function onSave() {
  if (!canSave.value) return
  emit('save', {
    name: form.name.trim(),
    type: form.type,
    url: form.type === 'http' ? form.url.trim() : undefined,
    command: form.type === 'stdio' ? form.command.trim() : undefined,
    // 请求头：留空表示保持原值（后端按 undefined 处理）
    headers: form.headers.trim() || undefined,
    toolPrefix: form.toolPrefix.trim() || undefined,
    permissionLevel: form.permissionLevel,
    enabled: form.enabled,
    autoRegister: form.autoRegister
  })
  // 立即关闭：新建不幂等，抽屉一直开着再点一次「保存」会重复创建同名服务器；
  // 失败时由 App.vue 弹 error toast（如名称重复、URL 非法），用户重新打开修改即可。
  close()
}
</script>
