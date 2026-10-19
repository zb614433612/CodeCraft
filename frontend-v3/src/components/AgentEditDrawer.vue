<!--
  Agent 新建 / 编辑抽屉（v3）
  ----------------------------------------------------------------------------
  右侧滑出（480px），复用 `.cc-drawer` 结构 + `--drawer-w` 覆盖宽度。
  分组顺序按使用频率：基本信息 → 模型来源 → 角色设定 → 角色性格 → 工具集 → 工作目录 → 运行参数。

  v3 相对 v2 的两处补齐：
    1) 模型选择联动「模型配置」页：新增 Provider 下拉，模型下拉的选项来自该 Provider
       的 `modelList`（后端 ProviderConfig.modelList），不再写死 4 个假模型；
    2) 新增「角色性格」折叠区：对应后端 `AgentConfig.characterProfile`（JSON 字符串），
       运行时由 `CharacterPromptUtil.buildCharacterSection()` 追加到系统提示词末尾。

  数据接缝：
    v-model:open      -> 抽屉开关
    agent             -> 传入已存在的 Agent 表示编辑，null 表示新建
    providers         -> Provider 选项（来自 `GET /api/llm-providers`，含各自 modelList）
    models            -> 兜底模型列表（Provider 接口未返回任何模型时使用）
    toolOptions       -> 工具集选项（来自工具注册表）
    emit('save')      -> 表单数据回传，由页面调用 创建/更新 接口

  工作目录：抽屉内自带 `DirectoryPicker`（居中弹窗，走 /api/project/drives + /children），
  选中后直接回填 `form.workDir`，不再向父组件抛 pick-dir 事件。

  注意：
    · 工具集是「全部工具 / 精细授权」双态，但两态在视觉与操作上连续：全部态下所有
      工具都显示为已选中，点掉任意一个即自动转成「全部 - 1」的显式列表，不需要先手动
      关掉「全部工具」（旧版用 pointer-events 锁死工具区，属于多余步骤，已废弃）；
      后端语义不变：tool_names 留空 = 全部工具（跟随系统新增），非空 = 只授权这些；
    · 清空角色性格时提交 `"{}"`（后端视为无性格），不能用 undefined —— update 里是
      `if (updated.getCharacterProfile() != null)`，传 undefined 等于「不修改」。
-->
<template>
  <aside class="cc-drawer" :class="{ 'is-open': open }" style="--drawer-w: 480px">
    <DrawerResizer storage-key="agent-edit" />
    <div class="cc-drawer__head">
      <div style="font-size: var(--fs-md); font-weight: 700">{{ isNew ? '新建 Agent' : '编辑 Agent' }}</div>
      <button class="cc-icon-btn" style="margin-left: auto" title="关闭" @click="close">
        <svg class="cc-i cc-i--sm" style="transform: rotate(45deg)"><use href="#i-plus" /></svg>
      </button>
    </div>

    <div class="cc-drawer__body">
      <div class="cc-form">
        <!-- ---------------- 头像 ---------------- -->
        <div class="cc-avatar-field">
          <AvatarUploader
            v-model="form.avatar"
            :name="form.name"
            :size="64"
            :disabled="avatarUploading"
            @uploading="avatarUploading = $event"
          />
          <div class="cc-avatar-field__meta">
            <div class="cc-avatar-field__title">头像</div>
            <div class="cc-avatar-field__desc">
              点击上传图片，或直接填 emoji（如 🤖）。<br />
              卡片、侧栏切换器与聊天页 AI 气泡共用这一个值。
            </div>
          </div>
        </div>

        <!-- ---------------- 基本信息 ---------------- -->
        <div class="cc-field">
          <label class="cc-field__label">名称<em>*</em></label>
          <input v-model="form.name" class="cc-input" placeholder="如：圆圆 / 代码审查员" maxlength="50" />
        </div>

        <div class="cc-field">
          <label class="cc-field__label">描述</label>
          <input v-model="form.description" class="cc-input" placeholder="一句话说明这个 Agent 擅长什么" maxlength="200" />
        </div>

        <!-- ---------------- 模型来源：Provider → 模型 联动 ---------------- -->
        <div class="cc-field">
          <label class="cc-field__label">Provider</label>
          <select v-model="form.providerCode" class="cc-select" @change="onProviderChange">
            <option value="">跟随默认 Provider</option>
            <option v-if="orphanProviderCode" :value="orphanProviderCode">
              {{ orphanProviderCode }}（配置已缺失）
            </option>
            <option v-for="p in providers" :key="p.code" :value="p.code">
              {{ p.name }} · {{ p.code }}{{ p.isDefault ? '（默认）' : '' }}
            </option>
          </select>
          <div class="cc-field__hint">{{ providerHint }}</div>
        </div>

        <div class="cc-field-row">
          <div class="cc-field">
            <label class="cc-field__label">模型</label>
            <select v-model="form.model" class="cc-select">
              <option v-if="!modelOptions.length" value="">未配置模型</option>
              <option v-for="m in modelOptions" :key="m" :value="m">{{ m }}</option>
            </select>
            <div class="cc-field__hint">{{ modelHint }}</div>
          </div>
          <div class="cc-field">
            <label class="cc-field__label">执行模式</label>
            <select v-model="form.executionMode" class="cc-select">
              <option value="auto">自动执行</option>
              <option value="manual">手动确认</option>
            </select>
          </div>
        </div>

        <!-- ---------------- 角色设定 ---------------- -->
        <div class="cc-field">
          <label class="cc-field__label">系统提示词</label>
          <textarea v-model="form.systemPrompt" class="cc-textarea" rows="6" placeholder="描述这个 Agent 的角色、边界与工作方式…" />
          <!-- v-pre：这里是「字面量文案」，不能被 Vue 当插值编译（否则内层 }} 会提前闭合插值） -->
          <div class="cc-field__hint" v-pre>支持变量注入：{{tools}} 工具清单 · {{cwd}} 工作目录 · {{skills}} 技能目录</div>
        </div>

        <!-- ---------------- 角色性格（characterProfile） ---------------- -->
        <div class="cc-char" :class="{ 'is-open': charOpen }">
          <button type="button" class="cc-char__toggle" @click="charOpen = !charOpen">
            <svg class="cc-i cc-i--sm cc-char__chev"><use href="#i-chev" /></svg>
            <span>🎭 角色性格</span>
            <span class="cc-char__badge" :class="{ 'is-on': charFilled }">{{ charFilled ? '已配置' : '可选' }}</span>
          </button>

          <div v-if="charOpen" class="cc-char__body">
            <p class="cc-char__intro">
              填写后随系统提示词一起注入（仅首次会话），用于稳定角色的自称、语气与说话风格。
            </p>

            <div class="cc-char__grid">
              <div class="cc-field">
                <label class="cc-field__label">姓名</label>
                <input v-model="char.name" class="cc-input" placeholder="如：小柔" maxlength="50" />
              </div>
              <div class="cc-field">
                <label class="cc-field__label">物种</label>
                <select v-model="char.species" class="cc-select">
                  <option value="">未设置</option>
                  <option v-for="opt in CHARACTER_SPECIES_OPTIONS" :key="opt.value" :value="opt.value">{{ opt.label }}</option>
                </select>
              </div>
              <div class="cc-field">
                <label class="cc-field__label">性别</label>
                <select v-model="char.gender" class="cc-select">
                  <option value="">未设置</option>
                  <option v-for="opt in CHARACTER_GENDER_OPTIONS" :key="opt.value" :value="opt.value">{{ opt.label }}</option>
                </select>
              </div>
              <div class="cc-field">
                <label class="cc-field__label">年龄</label>
                <input v-model="char.age" class="cc-input" type="number" min="1" max="9999" placeholder="如：18" />
              </div>
              <div class="cc-field cc-char__wide">
                <label class="cc-field__label">性格</label>
                <textarea v-model="char.personality" class="cc-textarea" rows="2" maxlength="500" placeholder="如：温柔体贴，善解人意" />
              </div>
              <div class="cc-field">
                <label class="cc-field__label">称呼</label>
                <input v-model="char.greeting" class="cc-input" maxlength="50" placeholder="用户对你的称呼，如：主人" />
              </div>
              <div class="cc-field">
                <label class="cc-field__label">喜好</label>
                <input v-model="char.likes" class="cc-input" maxlength="200" placeholder="如：看书、听音乐" />
              </div>
              <div class="cc-field cc-char__wide">
                <label class="cc-field__label">背景</label>
                <textarea v-model="char.background" class="cc-textarea" rows="3" maxlength="1000" placeholder="如：来自未来世界的 AI 助手…" />
              </div>
              <div class="cc-field cc-char__wide">
                <label class="cc-field__label">说话风格</label>
                <textarea v-model="char.style" class="cc-textarea" rows="2" maxlength="500" placeholder="如：说话温柔，喜欢用表情符号" />
              </div>
            </div>

            <div class="cc-char__foot">
              <span class="cc-char__hint">存为 JSON（characterProfile），留空即不注入</span>
              <button class="cc-btn cc-btn--ghost" :disabled="!charFilled" @click="resetCharacter">清空</button>
            </div>
          </div>
        </div>

        <!-- ---------------- 工具集（数据源：GET /api/tools/registry） ---------------- -->
        <div class="cc-field">
          <label class="cc-field__label">
            工具集
            <span class="cc-toolsel__count">
              已选 {{ form.allTools ? `全部 ${totalTools} 个` : `${selectedTotal} / ${totalTools}` }}
            </span>
          </label>

          <div class="cc-toolsel">
            <div class="cc-toolsel__bar">
              <span
                class="cc-chip cc-toolsel__all"
                :class="{ 'is-on': form.allTools }"
                :title="
                  form.allTools
                    ? '当前为全部工具；点任意工具即可改为精细授权'
                    : '一键选中全部工具（保存时 tool_names 留空，自动跟随系统新增能力）'
                "
                @click="toggleAllTools"
              >全部工具</span>
              <label class="cc-toolsel__search">
                <svg class="cc-i cc-i--sm"><use href="#i-search" /></svg>
                <input v-model="toolKeyword" placeholder="搜索工具名称或说明" />
              </label>
              <button
                v-if="toolKeyword.trim()"
                class="cc-toolsel__reset"
                type="button"
                @click="toolKeyword = ''"
              >清空搜索</button>
            </div>

            <div class="cc-toolsel__groups">
              <div v-for="group in filteredGroups" :key="group.code" class="cc-toolsel__group">
                <div class="cc-toolsel__group-head">
                  <span class="cc-toolsel__group-name">{{ group.label }}</span>
                  <span class="cc-toolsel__group-n">{{ selectedInGroup(group) }}/{{ group.options.length }}</span>
                  <button class="cc-toolsel__group-all" type="button" @click="toggleGroup(group)">
                    {{ allSelectedInGroup(group) ? '取消本组' : '全选本组' }}
                  </button>
                </div>
                <div class="cc-chips">
                  <span
                    v-for="opt in group.options"
                    :key="opt.key"
                    class="cc-chip"
                    :class="{ 'is-on': isToolOn(opt.key), 'is-risk': opt.risk === 'high' }"
                    :title="`${opt.key}${opt.risk === 'high' ? '（高危工具）' : ''} · ${opt.description}`"
                    @click="toggleTool(opt.key)"
                  >{{ opt.label }}</span>
                </div>
              </div>

              <div v-if="!filteredGroups.length" class="cc-toolsel__empty">
                没有匹配「{{ toolKeyword.trim() }}」的工具
              </div>
            </div>
          </div>

          <div class="cc-field__hint">
            <template v-if="form.allTools">
              「全部工具」：保存时 tool_names 留空，自动跟随系统新增能力。点任意工具即可改为精细授权。
            </template>
            <template v-else-if="fullSelection">
              已选满全部 {{ totalTools }} 个工具，保存时同样按「全部工具」提交（留空）。
            </template>
            <template v-else>仅授权勾选的 {{ selectedTotal }} 个工具；未勾选的工具会被后端拒绝执行。</template>
            <span v-if="unknownTools.length" style="color: var(--text-4)">
              · 保留 {{ unknownTools.length }} 个未在注册表中的历史工具
            </span>
            <span v-if="usingFallbackTools" style="color: var(--warn)">
              · 未能加载工具注册表，当前展示内置快照
            </span>
          </div>
        </div>

        <!-- ---------------- 工作目录 ---------------- -->
        <div class="cc-field">
          <label class="cc-field__label">工作目录</label>
          <div style="display: flex; gap: var(--sp-2)">
            <input v-model="form.workDir" class="cc-input" placeholder="选择该 Agent 的工作根目录" />
            <button class="cc-btn" @click="dirPickerOpen = true">
              <svg class="cc-i cc-i--sm"><use href="#i-folder" /></svg>选择
            </button>
          </div>
          <div class="cc-field__hint">该 Agent 的文件操作与命令执行都以此目录为根</div>
        </div>

        <!-- ---------------- 运行参数 ---------------- -->
        <div class="cc-field-row">
          <div class="cc-field">
            <label class="cc-field__label">思考模式</label>
            <select v-model="form.thinkingMode" class="cc-select">
              <option value="non-thinking">关闭思考</option>
              <option value="thinking">思考</option>
              <option value="thinking_max">深度思考</option>
            </select>
          </div>
          <div class="cc-field">
            <label class="cc-field__label">温度</label>
            <input v-model="form.temperature" class="cc-input" placeholder="0.3" />
          </div>
        </div>
      </div>
    </div>

    <div class="cc-drawer__foot">
      <button class="cc-btn" @click="close">取消</button>
      <button class="cc-btn cc-btn--primary" :disabled="!canSave" @click="onSave">保存</button>
    </div>
  </aside>

  <!-- 目录选择器：由「工作目录」右侧按钮打开，确认后回填 form.workDir（不经过父组件） -->
  <DirectoryPicker v-model:open="dirPickerOpen" :initial-path="form.workDir" @pick="onPickDir" />
</template>

<script setup lang="ts">
import DrawerResizer from './DrawerResizer.vue'
import AvatarUploader from './AvatarUploader.vue'
import { computed, reactive, ref, watch } from 'vue'
import type { AgentItem } from './AgentCard.vue'
import DirectoryPicker from './DirectoryPicker.vue'
import {
  CHARACTER_GENDER_OPTIONS,
  CHARACTER_SPECIES_OPTIONS,
  characterFormToProfile,
  createCharacterFormState,
  hasCharacterProfile,
  parseCharacterProfile,
  serializeCharacterProfile,
  type CharacterFormState
} from '../utils/character-profile'
import { FALLBACK_TOOL_GROUPS, flattenToolOptions, type ToolGroup } from '../utils/tool-catalog'

/**
 * Provider 选项（由页面从 `GET /api/llm-providers` 映射而来）
 *
 * <p>只保留联动需要的字段：`models` 即后端 `ProviderConfig.modelList` 解析结果；
 * `isDefault` 用于「跟随默认 Provider」时的回退判定。</p>
 */
export interface AgentProviderOption {
  id?: number
  code: string
  name: string
  models: string[]
  defaultModel?: string
  isDefault?: boolean
}

const props = withDefaults(
  defineProps<{
    open: boolean
    /** 传入 null 表示新建 */
    agent?: AgentItem | null
    /** Provider 选项（模型下拉的数据源） */
    providers?: AgentProviderOption[]
    /**
     * 兜底模型列表。
     * Provider 接口未返回任何模型（例如刚部署、还没配置 Provider）时使用，
     * 避免模型下拉完全空白导致无法保存。
     */
    models?: string[]
    /** 可选工具列表（来自工具注册表接口） */
    toolGroups?: ToolGroup[]
  }>(),
  {
    agent: null,
    providers: () => [],
    models: () => ['deepseek-v4-pro', 'deepseek-v4-flash', 'gpt-5', 'claude-sonnet-4'],
    toolGroups: () => []
  }
)

const emit = defineEmits<{
  (e: 'update:open', v: boolean): void
  (e: 'save', payload: Record<string, unknown>): void
}>()

const isNew = computed(() => !props.agent)

/** 头像上传进行中：先禁用保存，避免把旧值提交上去 */
const avatarUploading = ref(false)

const form = reactive({
  name: '',
  description: '',
  avatar: '',
  model: '',
  providerCode: '',
  executionMode: 'auto',
  systemPrompt: '',
  allTools: true,
  tools: [] as string[],
  workDir: '',
  thinkingMode: 'non-thinking',
  temperature: '0.3'
})

/* ============================ 模型联动 ============================ */
/** Provider 是否已加载（加载完成前不提示「配置缺失」） */
const providersReady = computed(() => props.providers.length > 0)

/**
 * 当前生效的 Provider
 *
 * <p>三级回退：表单选中的 code → `isDefault` 的 Provider → 列表第一个。
 * 与后端运行时选型（`LLMClientManager` 无 providerCode 时取默认 Provider）保持一致。</p>
 */
const activeProvider = computed<AgentProviderOption | null>(() => {
  const list = props.providers
  if (!list.length) return null
  const code = form.providerCode.trim()
  if (code) {
    const hit = list.find(p => p.code === code)
    if (hit) return hit
  }
  return list.find(p => p.isDefault) || list[0]
})

/** 绑定了已不存在（被删/停用）的 Provider 时保留原值，避免静默改写历史配置 */
const orphanProviderCode = computed(() => {
  const code = form.providerCode.trim()
  if (!code || !providersReady.value) return ''
  return props.providers.some(p => p.code === code) ? '' : code
})

/**
 * 模型下拉选项
 *
 * <p>优先级：当前 Provider 的 modelList → 全部 Provider 的模型合集 → 组件兜底列表。
 * 另外始终把当前已选模型插到首位 —— 历史数据里的模型可能已从 Provider 配置中移除，
 * 若不保留，打开编辑抽屉会直接把用户的模型换成别的。</p>
 */
const modelOptions = computed<string[]>(() => {
  const own = activeProvider.value?.models || []
  let base = own
  if (!base.length) {
    const merged: string[] = []
    for (const p of props.providers) {
      for (const m of p.models) if (!merged.includes(m)) merged.push(m)
    }
    base = merged.length ? merged : props.models
  }
  const current = form.model.trim()
  if (current && !base.includes(current)) return [current, ...base]
  return base
})

/** Provider 提示：说清模型从哪来、缺配置时怎么办 */
const providerHint = computed(() => {
  if (!providersReady.value) {
    return '未加载到模型配置，模型下拉使用内置默认列表；请先到「模型配置」页添加 Provider'
  }
  const p = activeProvider.value
  if (!p) return '未加载到模型配置'
  if (!form.providerCode.trim()) {
    return `未指定时跟随默认 Provider：${p.name}（${p.code}）`
  }
  return `${p.name} · 默认模型 ${p.defaultModel || '未指定'} · 共 ${p.models.length} 个模型`
})

/** 模型提示：区分「来自 Provider 配置」与两种回退 */
const modelHint = computed(() => {
  const own = activeProvider.value?.models || []
  if (own.length) return `选项来自「${activeProvider.value?.name}」已配置的模型列表`
  if (providersReady.value) {
    return form.providerCode.trim()
      ? '该 Provider 未配置模型列表，已回退为全部 Provider 的模型合集'
      : '默认 Provider 未配置模型列表，已回退为全部 Provider 的模型合集'
  }
  return '当前为内置默认模型列表'
})

/** 建议模型：Provider 的默认模型优先 */
function suggestModel(): string {
  const p = activeProvider.value
  if (p && p.models.length) {
    if (p.defaultModel && p.models.includes(p.defaultModel)) return p.defaultModel
    return p.models[0]
  }
  return props.models[0] || ''
}

/**
 * 用户手动切换 Provider
 *
 * <p>只在「当前模型不在新 Provider 的模型列表里」时自动改模型，避免下拉出现
 * 「选了 A 却没有 A 的模型」的错配；历史遗留模型不会被这里的逻辑覆盖
 * （回填时不触发 change，且 modelOptions 会把它保留在首位）。</p>
 */
function onProviderChange() {
  const p = activeProvider.value
  if (!p || !p.models.length) return
  const current = form.model.trim()
  if (current && p.models.includes(current)) return
  form.model = p.defaultModel && p.models.includes(p.defaultModel) ? p.defaultModel : p.models[0]
}

/* ============================ 角色性格 ============================ */
const charOpen = ref(false)
const char = reactive<CharacterFormState>(createCharacterFormState())

/** 是否有任一字段填了内容（决定折叠头徽标与「清空」按钮可用性） */
const charFilled = computed(() =>
  Object.values(char).some(v => String(v ?? '').trim().length > 0)
)

function resetCharacter() {
  Object.assign(char, createCharacterFormState())
}

/* ============================ 工具集选择 ============================ */
/**
 * 实际使用的工具分组
 *
 * <p>注册表未到位（首屏 / 后端不可用）时回退内置快照 `FALLBACK_TOOL_GROUPS`。
 * 兜底表里是**真实工具名**，所以即便在网络异常时保存，也不会把假 key 写进
 * `tool_names`（假 key 会让该 Agent 一个工具都调不出来）。</p>
 */
const activeToolGroups = computed<ToolGroup[]>(() =>
  props.toolGroups.length ? props.toolGroups : FALLBACK_TOOL_GROUPS
)
const usingFallbackTools = computed(() => props.toolGroups.length === 0)
const allToolOptions = computed(() => flattenToolOptions(activeToolGroups.value))
const totalTools = computed(() => allToolOptions.value.length)
/** 全部工具的 key 列表：「全部工具」↔ 精细授权互转时的基准集合 */
const allToolKeys = computed(() => allToolOptions.value.map((opt) => opt.key))
/** 已选数量：全部态即全部工具数，否则为显式勾选数 */
const selectedTotal = computed(() => (form.allTools ? totalTools.value : form.tools.length))

/** 搜索：命中短名 / 工具名 / 完整描述任一即可 */
const toolKeyword = ref('')
const filteredGroups = computed<ToolGroup[]>(() => {
  const kw = toolKeyword.value.trim().toLowerCase()
  if (!kw) return activeToolGroups.value
  return activeToolGroups.value
    .map((group) => ({
      ...group,
      options: group.options.filter(
        (opt) =>
          opt.label.toLowerCase().includes(kw) ||
          opt.key.toLowerCase().includes(kw) ||
          (opt.description || '').toLowerCase().includes(kw)
      )
    }))
    .filter((group) => group.options.length > 0)
})

/** 历史数据里可能存着已下线的工具名：不显示但必须原样提交，否则等于静默删授权 */
const unknownTools = computed(() => {
  const known = new Set(allToolOptions.value.map((opt) => opt.key))
  return form.tools.filter((key) => !known.has(key))
})

/**
 * 精细态下是否已选满全部已知工具（且没有历史遗留工具）。
 *
 * <p>此时保存会归一成「全部工具」（tool_names 留空），语义与直接点「全部工具」等价 ——
 * 手动勾满也应该能享受「跟随系统新增能力」，否则以后端新增工具时这个 Agent 会漏掉。</p>
 */
const fullSelection = computed(
  () =>
    !form.allTools &&
    allToolKeys.value.length > 0 &&
    allToolKeys.value.every((key) => form.tools.includes(key)) &&
    unknownTools.value.length === 0
)

/* ---------------- 「全部工具」↔ 精细授权的互转（三档操作的基础） ---------------- */
/**
 * 某个工具当前是否生效。
 * 全部态下所有工具都生效 —— 视觉上就是「全选了」，点掉哪一个就变成「全部 - 1」。
 */
function isToolOn(key: string): boolean {
  return form.allTools || form.tools.includes(key)
}

/**
 * 「全部工具」开关。
 * 关掉时把当前全部工具写进 `form.tools`，这样视觉与数据保持连续
 * （用户看到的是「仍然全选，只是从快捷态转成显式列表」），不会突然变成「一个都没选」。
 */
function toggleAllTools() {
  if (form.allTools) {
    form.tools = [...allToolKeys.value]
    form.allTools = false
  } else {
    form.allTools = true
    form.tools = []
  }
}

/**
 * 增减操作的基准集合。
 * 全部态下以「全部工具」为基准，所以点单个工具等价于「取消这一个」，
 * 不需要用户先手动退出全部态 —— 这是旧版最别扭的地方。
 */
function baseToolKeys(): string[] {
  return form.allTools ? [...allToolKeys.value] : [...form.tools]
}

function selectedInGroup(group: ToolGroup): number {
  if (form.allTools) return group.options.length
  return group.options.filter((opt) => form.tools.includes(opt.key)).length
}

function allSelectedInGroup(group: ToolGroup): boolean {
  if (form.allTools) return group.options.length > 0
  return group.options.length > 0 && group.options.every((opt) => form.tools.includes(opt.key))
}

/** 本组全选 / 取消（全部态下等价于「取消本组」，因为此刻本组本来就是全选） */
function toggleGroup(group: ToolGroup) {
  const keys = group.options.map((opt) => opt.key)
  const base = baseToolKeys()
  if (allSelectedInGroup(group)) {
    form.tools = base.filter((key) => !keys.includes(key))
  } else {
    form.tools = Array.from(new Set([...base, ...keys]))
  }
  form.allTools = false
}

/** 单个工具的开 / 关（全部态下第一次点击会自动转成「全部 - 1」） */
function toggleTool(key: string) {
  const base = baseToolKeys()
  const idx = base.indexOf(key)
  if (idx >= 0) base.splice(idx, 1)
  else base.push(key)
  form.tools = base
  form.allTools = false
}

/* ============================ 工作目录选择 ============================ */
/** 目录选择器开关（抽屉内自包含，不经过父组件） */
const dirPickerOpen = ref(false)

/** 选择器确认：回填工作目录；弹窗自己会 emit update:open=false 关闭 */
function onPickDir(path: string) {
  form.workDir = path
}

/* ============================ 回填 ============================ */
/** 打开抽屉时用传入的 Agent 回填表单 */
watch(
  () => [props.open, props.agent] as const,
  () => {
    if (!props.open) return
    // 上一次可能停在目录选择器里，重新打开抽屉时一并收掉
    dirPickerOpen.value = false
    const a = props.agent as (AgentItem & Record<string, unknown>) | null
    form.name = a?.name ?? ''
    form.description = a?.description ?? ''
    form.avatar = a?.avatar ?? ''
    avatarUploading.value = false
    form.providerCode = (a?.providerCode as string) ?? ''
    form.executionMode = a?.executionMode ?? 'auto'
    form.workDir = a?.workDir ?? ''
    form.systemPrompt = (a?.systemPrompt as string) ?? ''
    form.thinkingMode = (a?.thinkingMode as string) ?? 'non-thinking'
    form.temperature = String(a?.temperature ?? '0.3')
    const tools = (a?.tools as string[]) ?? []
    form.allTools = tools.length === 0
    form.tools = tools

    // 角色性格：'{}' 或无值都视为「未配置」，此时不自动展开，保持抽屉精简
    const profileRaw = (a?.characterProfile as string | undefined) ?? undefined
    Object.assign(char, createCharacterFormState(parseCharacterProfile(profileRaw)))
    charOpen.value = hasCharacterProfile(profileRaw)

    // 模型：编辑沿用历史值；新建按当前 Provider 推荐一个
    form.model = (a?.model as string) ?? ''
    if (!form.model) form.model = suggestModel()
  },
  { immediate: true }
)

/**
 * Provider 列表异步到位后补一次模型
 *
 * <p>页面进入智能体页时 Agent 列表与 Provider 列表是并行请求的，抽屉可能在
 * Provider 还没返回时就被打开；此时 `suggestModel()` 只能拿到兜底列表。
 * Provider 到位后若模型仍为空，用真实模型补上。</p>
 */
watch(
  () => props.providers,
  () => {
    if (!props.open) return
    if (!form.model) form.model = suggestModel()
  }
)

const canSave = computed(() => {
  if (!form.name.trim()) return false
  if (avatarUploading.value) return false
  return true
})

function close() {
  emit('update:open', false)
}

function onSave() {
  if (!canSave.value) return
  emit('save', {
    ...form,
    avatar: form.avatar.trim(),
    // 全部态 / 手选满全部 → 提交空数组（后端 tool_names 为 NULL，语义=全部工具），
    // 这样后端新增工具时该 Agent 自动获得，不用回来重新勾一遍
    tools: form.allTools || fullSelection.value ? [] : form.tools,
    temperature: Number(form.temperature) || 0.3,
    // 空性格提交 '{}'（后端视为无性格），传 undefined 会被当成「不修改」
    characterProfile: serializeCharacterProfile(characterFormToProfile(char))
  })
}
</script>
