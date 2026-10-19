<!--
  定时任务 新建 / 编辑抽屉（v2）
  ----------------------------------------------------------------------------
  右侧滑出（480px）。字段顺序：任务名称 → 执行 Agent → 执行方式 → 任务指令 → 次数上限 → 启用
  执行方式二选一（与后端字段一一对应）：
    周期  -> cronExpression（六位表达式）
    一次性 -> executeTime（到点执行一次）
  Cron 提供常用预设，点一下填入输入框，减少手写表达式出错。
  数据接缝：
    v-model:open   -> 抽屉开关
    task           -> null 表示新建，传入对象表示编辑
    agents         -> GET /agent-config/list（执行 Agent 下拉）
    emit('save')   -> POST /schedule-task（新建）/ PUT /schedule-task/{id}（编辑）
  注意：maxExecuteCount = 0 表示不限制；达到上限后后端会自动停用任务。
-->
<template>
  <aside class="cc-drawer" :class="{ 'is-open': open }" style="--drawer-w: 480px">
    <DrawerResizer storage-key="schedule-task-edit" />
    <div class="cc-drawer__head">
      <div style="font-size: var(--fs-md); font-weight: 700">{{ isNew ? '新建定时任务' : '编辑定时任务' }}</div>
      <button class="cc-icon-btn" style="margin-left: auto" title="关闭" @click="close">
        <svg class="cc-i cc-i--sm" style="transform: rotate(45deg)"><use href="#i-plus" /></svg>
      </button>
    </div>

    <div class="cc-drawer__body">
      <div class="cc-form">
        <!-- 任务名称 -->
        <div class="cc-field">
          <label class="cc-field__label">任务名称<em>*</em></label>
          <input v-model="form.name" class="cc-input" placeholder="如：每日代码审查" maxlength="50" />
        </div>

        <!-- 执行 Agent -->
        <div class="cc-field">
          <label class="cc-field__label">执行 Agent</label>
          <select v-model="form.agentConfigId" class="cc-select">
            <option :value="null">默认 Agent（圆圆）</option>
            <option v-for="a in agents" :key="a.id" :value="a.id">{{ a.name }}</option>
          </select>
          <div class="cc-field__hint">任务触发时以该 Agent 的身份与会话配置执行</div>
        </div>

        <!-- 执行方式 -->
        <div class="cc-field">
          <label class="cc-field__label">执行方式<em>*</em></label>
          <div class="cc-seg" style="width: fit-content">
            <button :class="{ 'is-active': form.scheduleType === 'cron' }" @click="form.scheduleType = 'cron'">
              周期（Cron）
            </button>
            <button :class="{ 'is-active': form.scheduleType === 'once' }" @click="form.scheduleType = 'once'">
              一次性
            </button>
          </div>
        </div>

        <!-- 周期：Cron 表达式 + 预设 -->
        <div v-if="form.scheduleType === 'cron'" class="cc-field">
          <label class="cc-field__label">Cron 表达式<em>*</em></label>
          <input v-model="form.cronExpression" class="cc-input" placeholder="0 0 9 * * ?（每天 9 点）" />
          <div class="cc-cron-presets">
            <span
              v-for="preset in cronPresets"
              :key="preset.value"
              class="cc-chip"
              :class="{ 'is-on': form.cronExpression === preset.value }"
              @click="form.cronExpression = preset.value"
            >{{ preset.label }}<code>{{ preset.value }}</code></span>
          </div>
          <div class="cc-field__hint">六位表达式：秒 分 时 日 月 周（支持 ? 占位）</div>
        </div>

        <!-- 一次性：执行时间 -->
        <div v-else class="cc-field">
          <label class="cc-field__label">执行时间<em>*</em></label>
          <input v-model="form.executeTime" class="cc-input" type="datetime-local" />
          <div class="cc-field__hint">到点后执行一次，执行结束任务自动停用</div>
        </div>

        <!-- 任务指令 -->
        <div class="cc-field">
          <label class="cc-field__label">任务指令<em>*</em></label>
          <textarea
            v-model="form.instruction"
            class="cc-textarea"
            rows="6"
            placeholder="告诉 Agent 做什么，如：审查今天的所有提交，列出问题清单…"
          />
          <div class="cc-field__hint">触发时作为用户消息发给 Agent，可引用项目内的文件与命令</div>
        </div>

        <!-- 最大执行次数 -->
        <div class="cc-field">
          <label class="cc-field__label">最大执行次数</label>
          <input v-model.number="form.maxExecuteCount" class="cc-input" type="number" min="0" max="10000" style="width: 160px" />
          <div class="cc-field__hint">达到上限后任务自动停用；设为 0 表示长期运行</div>
        </div>

        <!-- 启用 -->
        <div class="cc-field">
          <div class="cc-switch-row">
            <div
              class="cc-switch"
              :class="{ 'is-on': form.enabled === 1 }"
              @click="form.enabled = form.enabled === 1 ? 0 : 1"
            />
            <div class="cc-switch-row__text">
              <div class="cc-switch-row__title">启用</div>
              <div class="cc-switch-row__desc">关闭后不参与调度，可随时在列表中重新开启</div>
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
import type { ScheduleTaskItem } from './ScheduleTaskRow.vue'

const props = withDefaults(
  defineProps<{
    open: boolean
    task?: (ScheduleTaskItem & { enabled?: 0 | 1 }) | null
    agents?: Array<{ id: number; name: string }>
  }>(),
  {
    task: null,
    agents: () => []
  }
)

const emit = defineEmits<{
  (e: 'update:open', v: boolean): void
  (e: 'save', payload: Record<string, unknown>): void
}>()

const isNew = computed(() => !props.task)

/** 常用 Cron 预设 */
const cronPresets = [
  { label: '每天 9 点', value: '0 0 9 * * ?' },
  { label: '每小时', value: '0 0 * * * ?' },
  { label: '每周一 10 点', value: '0 0 10 ? * MON' },
  { label: '每月 1 号', value: '0 0 0 1 * ?' }
]

const form = reactive({
  name: '',
  agentConfigId: null as number | null,
  scheduleType: 'cron' as 'cron' | 'once',
  cronExpression: '0 0 9 * * ?',
  executeTime: '',
  instruction: '',
  maxExecuteCount: 100,
  enabled: 1 as 0 | 1
})

/** 打开时回填 */
watch(
  () => [props.open, props.task] as const,
  () => {
    if (!props.open) return
    const t = props.task as (ScheduleTaskItem & { enabled?: 0 | 1 }) | null
    form.name = t?.name ?? ''
    form.agentConfigId = t?.agentConfigId ?? null
    form.scheduleType = t?.cronExpression ? 'cron' : t?.executeTime ? 'once' : 'cron'
    form.cronExpression = t?.cronExpression || '0 0 9 * * ?'
    // datetime-local 需要 yyyy-MM-ddTHH:mm 格式
    form.executeTime = (t?.executeTime || '').replace(' ', 'T').slice(0, 16)
    form.instruction = t?.instruction ?? ''
    form.maxExecuteCount = Number(t?.maxExecuteCount ?? 100)
    form.enabled = (t?.enabled ?? (t?.status === 'ENABLED' ? 1 : 1)) as 0 | 1
  },
  { immediate: true }
)

const canSave = computed(() => {
  if (!form.name.trim()) return false
  if (!form.instruction.trim()) return false
  return form.scheduleType === 'cron' ? form.cronExpression.trim().length > 0 : form.executeTime.length > 0
})

function close() {
  emit('update:open', false)
}

function onSave() {
  if (!canSave.value) return
  emit('save', {
    name: form.name.trim(),
    agentConfigId: form.agentConfigId,
    instruction: form.instruction.trim(),
    // 只提交当前模式对应的字段，另一个必须是 null（不能是空串）：
    // 后端 executeTime 是 LocalDateTime + @JsonFormat，空串会反序列化失败；
    // 而 PUT /schedule-task/update 是全量覆盖，null 才会把该列写回 NULL。
    cronExpression: form.scheduleType === 'cron' ? form.cronExpression.trim() : null,
    executeTime: form.scheduleType === 'once' ? form.executeTime.replace('T', ' ') + ':00' : null,
    maxExecuteCount: Number(form.maxExecuteCount) || 0,
    status: form.enabled === 1 ? 'ENABLED' : 'DISABLED'
  })
  // 新建不幂等，保存后立即关闭抽屉，避免重复提交
  close()
}
</script>
