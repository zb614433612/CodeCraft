<!--
  技能 新建 / 编辑抽屉（v2）
  ----------------------------------------------------------------------------
  右侧滑出（520px）。字段顺序按「先定触发时机，再定执行步骤」编排：
    基本信息（名称/描述）→ 作用域（全局 or 指定 Agent）→ 触发词 → 指令 → 工具集
  触发词交互：输入框按逗号分隔，实时解析成 chips 预览（与后端存储格式一致）。
  数据接缝：
    v-model:open  -> 抽屉开关
    skill         -> null 表示新建，传入对象表示编辑
    agents        -> GET /agent-config/list（用于「作用域」下拉）
    toolGroups    -> GET /tool-registry（按分类展示，风险项单独标注）
    emit('save')  -> 回传表单数据，由页面调用 创建/更新 接口
-->
<template>
  <aside class="cc-drawer" :class="{ 'is-open': open }" style="--drawer-w: 520px">
    <DrawerResizer storage-key="skill-edit" />
    <div class="cc-drawer__head">
      <div style="font-size: var(--fs-md); font-weight: 700">{{ isNew ? '新建技能' : '编辑技能' }}</div>
      <button class="cc-icon-btn" style="margin-left: auto" title="关闭" @click="close">
        <svg class="cc-i cc-i--sm" style="transform: rotate(45deg)"><use href="#i-plus" /></svg>
      </button>
    </div>

    <div class="cc-drawer__body">
      <div class="cc-form">
        <!-- 基本信息 -->
        <div class="cc-field">
          <label class="cc-field__label">名称<em>*</em></label>
          <input v-model="form.name" class="cc-input" placeholder="给技能起个简洁的名字" maxlength="50" />
        </div>

        <div class="cc-field">
          <label class="cc-field__label">描述<em>*</em></label>
          <input v-model="form.description" class="cc-input" placeholder="简要描述这个技能的功能和用途" maxlength="200" />
        </div>

        <!-- 作用域 -->
        <div class="cc-field">
          <label class="cc-field__label">作用域</label>
          <select v-model="form.agentConfigId" class="cc-select">
            <option :value="null">全局（所有 Agent 可用）</option>
            <option v-for="a in agents" :key="a.id" :value="a.id">{{ a.name }}</option>
          </select>
          <div class="cc-field__hint">指定 Agent 后，技能只在该 Agent 的会话中被匹配</div>
        </div>

        <!-- 触发词 -->
        <div class="cc-field">
          <label class="cc-field__label">触发词</label>
          <input
            v-model="triggerInput"
            class="cc-input"
            placeholder="多个触发词用逗号分隔，如：样式规范, 配色, 间距"
            maxlength="500"
          />
          <div v-if="triggerList.length" class="cc-chips" style="margin-top: 2px">
            <span v-for="w in triggerList" :key="w" class="cc-chip is-on">{{ w }}</span>
          </div>
          <div class="cc-field__hint">用户消息包含这些关键词时，系统自动匹配并激活该技能</div>
        </div>

        <!-- 指令 -->
        <div class="cc-field">
          <label class="cc-field__label">指令<em>*</em></label>
          <textarea
            v-model="form.instructions"
            class="cc-textarea"
            rows="8"
            placeholder="详细描述执行步骤，越具体成功率越高…"
          />
          <div class="cc-field__hint">支持 Markdown；指令会作为技能正文注入模型上下文</div>
        </div>

        <!-- 工具集 -->
        <div class="cc-field">
          <label class="cc-field__label">工具集<em>*</em></label>
          <div v-for="group in toolGroups" :key="group.label" style="margin-bottom: 10px">
            <div style="font-size: var(--fs-xs); color: var(--text-3); margin-bottom: 5px">{{ group.label }}</div>
            <div class="cc-chips">
              <span
                v-for="tool in group.tools"
                :key="tool.name"
                class="cc-chip"
                :class="{ 'is-on': form.tools.includes(tool.name) }"
                :title="tool.description"
                @click="toggleTool(tool.name)"
              >
                {{ tool.name }}<span v-if="tool.risk" style="color: var(--warn)">⚠</span>
              </span>
            </div>
          </div>
          <div class="cc-field__hint">
            已选 {{ form.tools.length }} 个工具；高风险工具仍受手动模式审批约束
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
import { computed, reactive, ref, watch } from 'vue'
import type { SkillItem } from './SkillRow.vue'

interface ToolGroup {
  label: string
  tools: Array<{ name: string; description?: string; risk?: boolean }>
}

const props = withDefaults(
  defineProps<{
    open: boolean
    skill?: (SkillItem & { instructions?: string; tools?: string[] }) | null
    agents?: Array<{ id: number; name: string }>
    toolGroups?: ToolGroup[]
  }>(),
  {
    skill: null,
    agents: () => [],
    toolGroups: () => [
      {
        label: '文件与命令',
        tools: [
          { name: 'file_explorer', description: '读取与搜索文件' },
          { name: 'file_writer', description: '写入 / 编辑 / 删除文件' },
          { name: 'command', description: '执行 shell 命令', risk: true }
        ]
      },
      {
        label: '协作与检索',
        tools: [
          { name: 'agent', description: '派发子 Agent 并行执行' },
          { name: 'web_search', description: '联网搜索' },
          { name: 'web_fetch', description: '抓取网页正文' }
        ]
      },
      {
        label: '高风险（需确认）',
        tools: [
          { name: 'execute_sql', description: '执行 SQL', risk: true },
          { name: 'git_submit', description: '提交 / 推送代码', risk: true }
        ]
      }
    ]
  }
)

const emit = defineEmits<{
  (e: 'update:open', v: boolean): void
  (e: 'save', payload: Record<string, unknown>): void
}>()

const isNew = computed(() => !props.skill)

const form = reactive({
  name: '',
  description: '',
  agentConfigId: null as number | null,
  instructions: '',
  tools: [] as string[]
})

/** 触发词输入（逗号分隔），实时解析为数组 */
const triggerInput = ref('')

const triggerList = computed<string[]>(() =>
  triggerInput.value
    .split(/[,，]/)
    .map(s => s.trim())
    .filter(Boolean)
)

/** 打开抽屉时回填 */
watch(
  () => [props.open, props.skill] as const,
  () => {
    if (!props.open) return
    const s = props.skill as (SkillItem & { instructions?: string; tools?: string[] }) | null
    form.name = s?.name ?? ''
    form.description = s?.description ?? ''
    form.agentConfigId = s?.agentConfigId ?? null
    form.instructions = s?.instructions ?? ''
    form.tools = s?.tools ?? []

    // 回填触发词：兼容 JSON 字符串与数组
    const raw = s?.triggerWords
    if (!raw) {
      triggerInput.value = ''
    } else if (Array.isArray(raw)) {
      triggerInput.value = raw.join(', ')
    } else {
      try {
        const parsed = JSON.parse(raw)
        triggerInput.value = Array.isArray(parsed) ? parsed.join(', ') : String(raw)
      } catch {
        triggerInput.value = String(raw)
      }
    }
  },
  { immediate: true }
)

const canSave = computed(
  () => form.name.trim().length > 0 && form.description.trim().length > 0 && form.instructions.trim().length > 0
)

function toggleTool(name: string) {
  const idx = form.tools.indexOf(name)
  if (idx >= 0) form.tools.splice(idx, 1)
  else form.tools.push(name)
}

function close() {
  emit('update:open', false)
}

function onSave() {
  if (!canSave.value) return
  emit('save', {
    name: form.name.trim(),
    description: form.description.trim(),
    agentConfigId: form.agentConfigId,
    instructions: form.instructions,
    tools: [...form.tools],
    triggerWords: triggerList.value,   // 后端按 JSON 数组存储
    confidence: props.skill?.confidence ?? 0.5
  })
  // 立即关闭：新建不幂等，抽屉一直开着再点一次「保存」会重复创建同名技能；
  // 失败时由 App.vue 弹 error toast，用户重新打开改一处即可。
  close()
}
</script>
