<!--
  子Agent面板（F05）

  ------------------------------------------------------------------------------------
  落点：聊天页运行状态栏（.cc-runbar）右侧的「子Agent」按钮 → 向上弹出浮层。
  数据源（两条都是真实存在的通道，见 utils/agent-event.ts 的说明）：
    1) 子Agent（fork）：GET /api/sub-agents 读 sub_agent_log 表
    2) 委托调用：SSE delegate_progress 心跳（A 委托 B 智能体执行任务期间）
  组件自身不发请求，纯展示 + 事件上抛（与 ChangeReview / ChatTaskPanel 的分工一致）。
  ------------------------------------------------------------------------------------
-->
<template>
  <div class="cc-subagentpop">
    <div class="cc-subagentpop__head">
      <span class="cc-subagentpop__title">
        <span class="cc-subagentpop__emoji">🤖</span>
        子Agent
      </span>
      <span v-if="stats.total" class="cc-subagentpop__count">
        <span v-if="stats.running" class="is-running">{{ stats.running }} 运行中</span>
        <span v-if="stats.done" class="is-ok">{{ stats.done }} 已完成</span>
        <span v-if="stats.failed" class="is-danger">{{ stats.failed }} 失败</span>
        <span class="is-total">共 {{ stats.total }}</span>
      </span>
      <button class="cc-icon-btn cc-icon-btn--sm" title="关闭" @click="$emit('close')">
        <svg class="cc-i cc-i--sm" style="transform: rotate(45deg)"><use href="#i-plus" /></svg>
      </button>
    </div>

    <!-- 空态：说明子Agent从哪来，避免用户以为是坏了 -->
    <div v-if="!runs.length && !delegates.length" class="cc-subagentpop__empty">
      还没有子Agent。主Agent在任务可以并行拆分时，会用 <code>agent</code> 工具的
      <code>fork</code> 动作派出子Agent独立执行；派出的每个子Agent都会出现在这里。
    </div>

    <div v-else class="cc-subagentpop__body">
      <!-- ============ 子Agent（fork） ============ -->
      <div
        v-for="run in runs"
        :key="run.key"
        class="cc-sa"
        :class="{
          'is-open': expanded.includes(run.key),
          'is-running': run.status === 'running',
          'is-danger': run.status === 'failed' || run.status === 'timeout'
        }"
      >
        <button type="button" class="cc-sa__head" :title="headTitle(run)" @click="toggle(run.key)">
          <span class="cc-sa__avatar" :class="`is-tone-${toneOf(run.agentId)}`">{{ avatarText(run) }}</span>
          <span class="cc-sa__name">{{ run.name }}</span>
          <span v-if="run.agentId && run.agentId !== run.name" class="cc-sa__id">{{ run.agentId }}</span>
          <span class="cc-sa__status" :class="`is-${SUB_AGENT_STATUS_META[run.status].tone}`">
            <span v-if="run.status === 'running'" class="cc-sa__pulse" />
            {{ SUB_AGENT_STATUS_META[run.status].label }}
          </span>
          <span class="cc-sa__meta">
            <span v-if="run.elapsedMs" class="cc-sa__elapsed">{{ formatDuration(run.elapsedMs) }}</span>
            <span v-if="run.maxIterations" class="cc-sa__iters" :title="`已用 ${run.iterationsUsed} / 最多 ${run.maxIterations} 轮迭代`">
              {{ run.iterationsUsed }}/{{ run.maxIterations }}
            </span>
            <span v-if="fileStats(run).total" class="cc-sa__files-badge" :title="fileTitle(run)">
              {{ fileStats(run).added ? '+' + fileStats(run).added : '' }}{{ fileStats(run).deleted ? ' -' + fileStats(run).deleted : '' }}
            </span>
          </span>
          <svg class="cc-i cc-i--sm cc-sa__chev" :class="{ 'is-open': expanded.includes(run.key) }"><use href="#i-chev" /></svg>
        </button>

        <div v-if="expanded.includes(run.key)" class="cc-sa__body">
          <!-- 失败/超时：错误原因置顶（这是用户最先要看的信息） -->
          <div v-if="run.errorMessage" class="cc-sa__err">
            <b>{{ run.status === 'timeout' ? '超时' : '失败原因' }}</b>
            <div class="cc-sa__err-text">{{ run.errorMessage }}</div>
          </div>

          <div v-if="run.task" class="cc-sa__section">
            <div class="cc-sa__label">任务描述</div>
            <div class="cc-sa__task">{{ run.task }}</div>
          </div>

          <div v-if="run.summary" class="cc-sa__section">
            <div class="cc-sa__label">执行摘要</div>
            <div class="cc-sa__task">{{ run.summary }}</div>
          </div>

          <div v-if="run.tools.length" class="cc-sa__section">
            <div class="cc-sa__label">可用工具 · {{ run.tools.length }}</div>
            <div class="cc-sa__chips">
              <span v-for="tool in run.tools" :key="tool" class="cc-sa__chip">{{ tool }}</span>
            </div>
          </div>

          <div v-if="run.skills.length" class="cc-sa__section">
            <div class="cc-sa__label">可用技能 · {{ run.skills.length }}</div>
            <div class="cc-sa__chips">
              <span v-for="skill in run.skills" :key="skill" class="cc-sa__chip is-skill">{{ skill }}</span>
            </div>
          </div>

          <div v-if="fileStats(run).total" class="cc-sa__section">
            <div class="cc-sa__label">文件变更 · {{ fileStats(run).total }}</div>
            <div class="cc-sa__files">
              <div v-for="f in run.files.created" :key="'c-' + f" class="cc-sa__file is-added">
                <span class="cc-sa__file-tag">新增</span>{{ f }}
              </div>
              <div v-for="f in run.files.modified" :key="'m-' + f" class="cc-sa__file is-modified">
                <span class="cc-sa__file-tag">修改</span>{{ f }}
              </div>
              <div v-for="f in run.files.deleted" :key="'d-' + f" class="cc-sa__file is-deleted">
                <span class="cc-sa__file-tag">删除</span>{{ f }}
              </div>
            </div>
          </div>

          <div class="cc-sa__foot">
            <span v-if="run.contextMode">上下文：{{ describeContextMode(run.contextMode) }}</span>
            <span v-if="run.compileResult && run.compileResult !== 'not_run'">
              编译：{{ run.compileResult === 'passed' ? '通过' : '失败' }}
            </span>
            <span v-if="run.startedAt">开始：{{ run.startedAt }}</span>
            <span v-if="run.endedAt">结束：{{ run.endedAt }}</span>
          </div>
        </div>
      </div>

      <!-- ============ 委托调用（delegate_progress 心跳） ============ -->
      <template v-if="delegates.length">
        <div class="cc-subagentpop__sep">
          <span>委托调用</span>
          <span class="cc-subagentpop__sep-hint">A 委托另一个智能体执行任务</span>
        </div>
        <div v-for="call in delegates" :key="call.callId" class="cc-sa is-delegate">
          <div class="cc-sa__head is-static">
            <span class="cc-sa__avatar is-tone-delegate">↗</span>
            <span class="cc-sa__name">{{ call.targetAgent || '另一个智能体' }}</span>
            <span class="cc-sa__status" :class="stale(call) ? 'is-warn' : 'is-running'">
              <span v-if="!stale(call)" class="cc-sa__pulse" />
              {{ stale(call) ? '已结束' : '委托中' }}
            </span>
            <span class="cc-sa__meta">
              <span class="cc-sa__elapsed">已等待 {{ call.elapsedSec }}s</span>
            </span>
            <button
              v-if="call.targetSessionId"
              type="button"
              class="cc-sa__jump"
              :title="`前往该会话查看执行明细（会话 ${call.targetSessionId}）`"
              @click="$emit('open-session', call.targetSessionId!)"
            >
              前往会话
            </button>
          </div>
        </div>
      </template>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import {
  SUB_AGENT_STATUS_META,
  describeContextMode,
  formatDuration,
  isDelegateStale,
  subAgentFileStats,
  subAgentStats,
  type DelegateCall,
  type SubAgentRun
} from '../utils/agent-event'

const props = withDefaults(
  defineProps<{
    runs?: SubAgentRun[]
    delegates?: DelegateCall[]
  }>(),
  {
    runs: () => [],
    delegates: () => []
  }
)

const emit = defineEmits<{
  (e: 'close'): void
  /** 点击委托项的「前往会话」：切到目标智能体的会话看明细 */
  (e: 'open-session', sessionId: number): void
}>()

defineExpose({ reset })

const expanded = ref<string[]>([])

const stats = computed(() => subAgentStats(props.runs))

/** 运行中的默认展开，结束后保持用户选择（新出现的运行项自动展开，便于观察状态流转） */
watch(
  () => props.runs.map(r => r.key + ':' + r.status).join('|'),
  () => {
    const running = props.runs.filter(r => r.status === 'running').map(r => r.key)
    const keep = expanded.value.filter(key => props.runs.some(r => r.key === key && r.status !== 'running'))
    const next = Array.from(new Set([...keep, ...running]))
    if (next.join('|') !== expanded.value.join('|')) expanded.value = next
  },
  { immediate: true }
)

function toggle(key: string) {
  expanded.value = expanded.value.includes(key)
    ? expanded.value.filter(item => item !== key)
    : [...expanded.value, key]
}

function reset() {
  expanded.value = []
}

function fileStats(run: SubAgentRun) {
  return subAgentFileStats(run)
}

function fileTitle(run: SubAgentRun): string {
  const s = fileStats(run)
  return `新增 ${s.added} · 修改 ${s.modified} · 删除 ${s.deleted}`
}

/** 头像取名称首字（中文取第一个字符，英文取首字母大写） */
function avatarText(run: SubAgentRun): string {
  const text = (run.name || run.agentId || '?').trim()
  return text ? text.slice(0, 1).toUpperCase() : '?'
}

/** 头像配色：按 agentId 散列到 6 个色相，同一子Agent颜色稳定 */
function toneOf(seed: string): number {
  let hash = 0
  for (let i = 0; i < seed.length; i += 1) hash = (hash * 31 + seed.charCodeAt(i)) % 997
  return hash % 6
}

function headTitle(run: SubAgentRun): string {
  const parts = [run.name]
  if (run.agentId) parts.push(`(${run.agentId})`)
  parts.push(SUB_AGENT_STATUS_META[run.status].label)
  if (run.elapsedMs) parts.push(formatDuration(run.elapsedMs))
  return parts.join(' · ')
}

function stale(call: DelegateCall): boolean {
  return isDelegateStale(call)
}
</script>
