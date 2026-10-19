<!--
  Provider 编辑抽屉（v2）
  ----------------------------------------------------------------------------
  用途：新增 / 编辑 LLM Provider，并就地查看账户余额与套餐额度。
  与后端对应：
    GET    /api/llm-providers        列表（管理员；apiKey 与 extraConfig 密钥返回 **** 掩码）
    POST   /api/llm-providers        创建（后端强制 isDefault=0，code 唯一）
    PUT    /api/llm-providers/{id}   更新（apiKey 为空或为 **** 时不修改；extraConfig 空串=清空、含 ****=保持）
    DELETE /api/llm-providers/{id}   删除
    GET    /api/llm-providers/{code}/balance  余额 / 套餐额度（登录用户即可，非管理员接口）
  数据接缝：
    v-model:open   -> 抽屉开关
    provider       -> 编辑对象（null 表示新增）
    balance        -> 余额查询结果（父组件持有）
    emit('save')          -> POST / PUT
    emit('query-balance') -> GET /{code}/balance
  校验：modelList 必须为 JSON 数组、extraConfig 必须为合法 JSON，否则不允许保存（与旧页面一致）。
-->
<template>
  <aside class="cc-drawer" :class="{ 'is-open': open }" style="--drawer-w: 560px">
    <DrawerResizer storage-key="provider-edit" />
    <div class="cc-drawer__head">
      <div style="font-size: var(--fs-md); font-weight: 700">{{ isNew ? '新增 Provider' : '编辑 Provider' }}</div>
      <span class="cc-tag">{{ isNew ? 'new' : form.code }}</span>
      <button class="cc-icon-btn" style="margin-left: auto" title="关闭" @click="close">
        <svg class="cc-i cc-i--sm" style="transform: rotate(45deg)"><use href="#i-plus" /></svg>
      </button>
    </div>

    <div class="cc-drawer__body">
      <div class="cc-form">
        <div class="cc-field-row">
          <div class="cc-field">
            <label class="cc-field__label">编码<em>*</em></label>
            <input
              ref="codeInput"
              v-model="form.code"
              class="cc-input"
              :disabled="!isNew"
              placeholder="如 volcengine"
            />
            <div class="cc-field__hint">唯一标识，创建后不可修改</div>
          </div>
          <div class="cc-field">
            <label class="cc-field__label">名称<em>*</em></label>
            <input ref="nameInput" v-model="form.name" class="cc-input" placeholder="如 火山方舟" />
          </div>
        </div>

        <div class="cc-field">
          <label class="cc-field__label">Base URL<em>*</em></label>
          <input
            ref="baseUrlInput"
            v-model="form.baseUrl"
            class="cc-input"
            placeholder="https://ark.cn-beijing.volces.com/api/v3"
          />
          <div class="cc-field__hint">
            OpenAI 兼容端点根路径；Ollama 本地填 http://localhost:11434/v1
          </div>
        </div>

        <div class="cc-field">
          <label class="cc-field__label">API Key</label>
          <input
            v-model="form.apiKey"
            class="cc-input"
            type="password"
            :placeholder="isNew ? '填写平台 API Key（本地 Ollama 可留空）' : '留空表示不修改（**** 表示已配置）'"
          />
          <div class="cc-field__hint">
            列表接口以 **** 掩码返回；编辑时留空或保持 **** 均视为不修改
          </div>
        </div>

        <div class="cc-field-row">
          <div class="cc-field">
            <label class="cc-field__label">默认模型</label>
            <input v-model="form.defaultModel" class="cc-input" placeholder="如 doubao-seed-1-6-250615" />
          </div>
          <div class="cc-field">
            <label class="cc-field__label">排序号</label>
            <input v-model.number="form.sortOrder" class="cc-input" placeholder="0" />
          </div>
        </div>

        <div class="cc-field">
          <label class="cc-field__label">模型列表</label>
          <textarea
            v-model="form.modelList"
            class="cc-textarea"
            placeholder='["doubao-seed-1-6-250615","doubao-seed-1-6-flash-250715"]'
          />
          <div class="cc-chips">
            <span v-if="modelError" class="cc-prov-cap is-warn">{{ modelError }}</span>
            <template v-else-if="parsedModels.length">
              <span v-for="m in parsedModels" :key="m" class="cc-chip">{{ m }}</span>
            </template>
            <span v-else class="cc-prov-cap">暂无模型</span>
          </div>
          <div class="cc-field__hint">JSON 数组字符串，模型选择器据此渲染；非法 JSON 不允许保存</div>
        </div>

        <div class="cc-field">
          <label class="cc-field__label">请求模板</label>
          <select v-model="form.requestTemplate" class="cc-select">
            <option v-for="opt in TEMPLATE_OPTIONS" :key="opt.value" :value="opt.value">{{ opt.label }}</option>
          </select>
          <div class="cc-field__hint">
            决定请求体构造与平台专属能力（思考模式 / 图片 / 联网搜索 / 余额查询）
          </div>
        </div>

        <div class="cc-field">
          <label class="cc-field__label">扩展配置（JSON）</label>
          <div class="cc-prov-extra">
            <div class="cc-prov-extra__presets">
              <button
                v-for="preset in EXTRA_PRESETS"
                :key="preset.key"
                class="cc-chip"
                @click="applyPreset(preset.key)"
              >
                {{ preset.label }}
              </button>
            </div>
            <textarea
              v-model="form.extraConfig"
              class="cc-textarea"
              rows="4"
              placeholder='{"accessKey":"...","secretKey":"...","searchApiKey":"...","region":"cn-beijing"}'
            />
          </div>
          <div class="cc-field__hint">
            密钥类字段以 **** 掩码返回；留空提交表示清空，含 **** 表示保持原值
          </div>
          <div class="cc-field__hint">
            火山方舟专属可配项：imageDetail（low / high / xhigh，缺省 high，方舟不接受 auto）、
            reasoningEffortModels（档位白名单，缺省仅 deepseek-v4 系列）、
            reasoningEffortEnabled（true = 对所有模型下发档位）、
            reasoningEffortHigh / reasoningEffortMax（档位文案，缺省 high / max）
          </div>
          <div v-if="extraError" class="cc-field__hint" style="color: var(--danger)">{{ extraError }}</div>
        </div>

        <div class="cc-field">
          <label class="cc-field__label">能力预览</label>
          <div class="cc-prov-caps">
            <span class="cc-prov-cap is-on">对话</span>
            <span
              class="cc-prov-cap"
              :class="previewCaps.vision === 'on' ? 'is-on' : previewCaps.vision === 'warn' ? 'is-warn' : ''"
              :title="previewVisionHint"
            >
              {{ previewCaps.vision === 'warn' ? '图片识别看模型' : '图片识别' }}
            </span>
            <span class="cc-prov-cap" :class="previewSearchClass" :title="previewSearchTitle">
              {{ previewSearchLabel }}
            </span>
            <span
              class="cc-prov-cap"
              :class="previewCaps.balance === 'on' ? 'is-on' : previewCaps.balance === 'warn' ? 'is-warn' : ''"
            >
              {{ previewCaps.balance === 'warn' ? '余额查询缺凭证' : '余额查询' }}
            </span>
          </div>
          <div class="cc-field__hint">
            按「请求模板 + 默认模型 + 扩展配置 + Base URL」推导，与列表徽章同口径；图片识别以默认模型的多模态能力为准，
            联网搜索看是否具备平台官方搜索接口（DeepSeek 要求 Base URL 是 api.deepseek.com，走中转则只剩通用抓取）
          </div>
        </div>

        <div class="cc-switch-row">
          <div class="cc-switch-row__text">
            <div class="cc-switch-row__title">启用该 Provider</div>
            <div class="cc-switch-row__desc">
              只读：后端列表接口只返回已启用项，停用后该项会从列表消失且无法在本页恢复
            </div>
          </div>
          <span
            class="cc-switch is-readonly"
            :class="{ 'is-on': form.enabled === 1 }"
            title="只读：停用后该项会从列表消失且无法恢复（后端列表接口只返回启用项）"
          />
        </div>

        <!-- 余额 / 套餐额度（仅编辑态） -->
        <div v-if="!isNew" class="cc-field">
          <label class="cc-field__label">账户余额 / 套餐额度</label>
          <div class="cc-prov-balance" :class="{ 'cc-prov-balance--error': !!balanceError }">
            <div class="cc-prov-balance__head">
              {{ balance?.balanceType === 'quota' ? '套餐额度' : '账户余额' }}
              <span>GET /api/llm-providers/{{ form.code }}/balance</span>
            </div>

            <div v-if="balanceLoading" class="cc-prov-balance__empty">查询中…</div>
            <div v-else-if="balanceError" class="cc-prov-balance__empty">{{ balanceError }}</div>
            <template v-else-if="balance && balance.balanceInfos.length">
              <div class="cc-prov-balance__grid">
                <div v-for="(info, index) in balance.balanceInfos" :key="index" class="cc-prov-balance__item">
                  <template v-if="balance.balanceType === 'quota'">
                    <span class="cc-prov-balance__label">{{ info.planName || '套餐额度' }}</span>
                    <span class="cc-prov-balance__value is-strong">{{ info.remainingPercent ?? 0 }}%</span>
                    <div class="cc-prov-quota">
                      <div
                        class="cc-prov-quota__fill"
                        :class="{ 'is-low': (info.remainingPercent ?? 0) < 20 }"
                        :style="{ width: clampPercent(info.remainingPercent) }"
                      />
                    </div>
                    <span class="cc-prov-balance__label">
                      剩余 {{ info.remainingCount ?? '-' }} / {{ info.totalCount ?? '-' }} 次
                    </span>
                  </template>
                  <template v-else>
                    <span class="cc-prov-balance__label">可用余额（{{ info.currency || 'CNY' }}）</span>
                    <span class="cc-prov-balance__value is-strong">{{ info.totalBalance ?? '-' }}</span>
                    <span class="cc-prov-balance__label">
                      赠金 {{ info.grantedBalance ?? '0' }} · 充值 {{ info.toppedUpBalance ?? '0' }}
                    </span>
                    <span v-if="info.cashBalance" class="cc-prov-balance__label">
                      现金余额 {{ info.cashBalance }} · 欠费 {{ info.arrearsBalance ?? '0' }}
                    </span>
                    <span v-if="info.accountId" class="cc-prov-balance__label">账号 {{ info.accountId }}</span>
                  </template>
                </div>
              </div>
            </template>
            <div v-else class="cc-prov-balance__empty">
              点下方「查询余额」拉取实时数据（DeepSeek / 火山引擎 / MiniMax / MiMo 支持）
            </div>
          </div>
        </div>
      </div>
    </div>

    <div class="cc-drawer__foot">
      <button v-if="!isNew" class="cc-btn" :disabled="balanceLoading" @click="$emit('query-balance', form.code)">
        <svg class="cc-i cc-i--sm"><use href="#i-db" /></svg>查询余额
      </button>
      <button class="cc-btn" @click="close">取消</button>
      <button class="cc-btn cc-btn--primary" @click="submit">保存</button>
    </div>
  </aside>
</template>

<script setup lang="ts">
import DrawerResizer from './DrawerResizer.vue'
import { computed, reactive, ref, watch } from 'vue'
import { deriveCapabilities, searchHint, searchLabel, visionHint } from '../utils/provider-capability'
import type { ProviderItem } from './ProviderRow.vue'

/** 单币种余额明细（与后端 ProviderBalanceVO.BalanceInfo 对应） */
export interface ProviderBalanceInfoModel {
  currency?: string
  totalBalance?: string
  grantedBalance?: string
  toppedUpBalance?: string
  /** 火山引擎扩展字段 */
  cashBalance?: string
  creditLimit?: string
  freezeAmount?: string
  arrearsBalance?: string
  accountId?: string
  /** quota 类型（MiniMax Token Plan）字段 */
  remainingPercent?: number
  remainingCount?: number
  totalCount?: number
  resetAt?: number
  planName?: string
}

/** 余额 / 套餐额度（对应后端 ProviderBalanceVO） */
export interface ProviderBalanceModel {
  providerCode: string
  balanceType?: 'balance' | 'quota'
  isAvailable: boolean
  balanceInfos: ProviderBalanceInfoModel[]
  updatedAt?: number
}

/** 保存载荷（emit('save') → POST / PUT /api/llm-providers） */
export interface ProviderSavePayload {
  id?: number
  code: string
  name: string
  baseUrl: string
  /** 留空 = 不修改（后端对空值与 **** 均跳过） */
  apiKey?: string
  defaultModel?: string
  modelList?: string
  extraConfig?: string
  requestTemplate?: string
  enabled: number
  sortOrder?: number
}

const props = withDefaults(
  defineProps<{
    open: boolean
    /** 编辑对象；null 表示新增 */
    provider?: ProviderItem | null
    balance?: ProviderBalanceModel | null
    balanceLoading?: boolean
    /** 查询失败信息（父组件透传） */
    balanceError?: string
  }>(),
  {
    provider: null,
    balance: null,
    balanceLoading: false,
    balanceError: ''
  }
)

const emit = defineEmits<{
  (e: 'update:open', value: boolean): void
  (e: 'save', payload: ProviderSavePayload): void
  (e: 'query-balance', code: string): void
}>()

const TEMPLATE_OPTIONS = [
  { value: 'deepseek', label: 'DeepSeek（兼容 OpenAI）' },
  { value: 'volcengine', label: '火山方舟（豆包｜图片 / 搜索 / 余额）' },
  { value: 'qwen', label: '通义千问（阿里云百炼 / OpenAI 兼容）' },
  { value: 'gpt', label: 'GPT（OpenAI 官方 / o 系列）' },
  { value: 'openai', label: 'OpenAI（任意兼容服务）' },
  { value: 'anthropic', label: 'Anthropic（Messages 兼容）' },
  { value: 'minimax', label: 'MiniMax' },
  { value: 'ollama', label: 'Ollama（本地）' },
  { value: 'custom', label: '自定义' }
]

/** 扩展配置快捷模板（火山方舟 / 千问 / MiniMax） */
const EXTRA_PRESETS: Array<{ key: string; label: string }> = [
  { key: 'volcengine', label: '火山引擎 AK/SK + 搜索 Key' },
  { key: 'qwen', label: '通义千问 余额 + 联网' },
  { key: 'minimax', label: 'MiniMax 套餐 Key' },
  { key: 'clear', label: '清空' }
]

const codeInput = ref<HTMLInputElement | null>(null)
const nameInput = ref<HTMLInputElement | null>(null)
const baseUrlInput = ref<HTMLInputElement | null>(null)

const form = reactive({
  code: '',
  name: '',
  baseUrl: '',
  apiKey: '',
  defaultModel: '',
  modelList: '[]',
  extraConfig: '{}',
  requestTemplate: 'openai',
  enabled: 1,
  sortOrder: 0
})

const isNew = computed(() => !props.provider)
const modelError = ref('')
const extraError = ref('')

watch(
  () => [props.open, props.provider] as const,
  () => {
    if (!props.open) return
    const p = props.provider
    form.code = p?.code || ''
    form.name = p?.name || ''
    form.baseUrl = p?.baseUrl || ''
    form.apiKey = ''
    form.defaultModel = p?.defaultModel || ''
    form.modelList = p?.modelList || '[]'
    form.extraConfig = p?.extraConfig || '{}'
    form.requestTemplate = p?.requestTemplate || p?.code || 'openai'
    form.enabled = p?.enabled === 0 ? 0 : 1
    form.sortOrder = p?.sortOrder ?? 0
    modelError.value = ''
    extraError.value = ''
  },
  { immediate: true }
)

/** 模型列表预览（非法 JSON 时置空，由 modelError 展示提示） */
const parsedModels = computed<string[]>(() => {
  try {
    const parsed = JSON.parse(form.modelList.trim() || '[]')
    return Array.isArray(parsed) ? parsed.map(String) : []
  } catch {
    return []
  }
})

/**
 * 能力预览：与列表徽章同口径。
 * baseUrl 也参与推导 —— DeepSeek 官方搜索接口要求主机是 api.deepseek.com，
 * 换成中转域名后「联网搜索」会从 on 掉到 warn。
 */
const previewCaps = computed(() =>
  deriveCapabilities(
    form.requestTemplate,
    form.code,
    form.extraConfig,
    form.defaultModel,
    form.baseUrl
  )
)

/** 图片识别徽章悬浮说明（表格里填了默认模型时给出模型级结论） */
const previewVisionHint = computed(() => visionHint(previewCaps.value.vision, form.defaultModel))

/** 联网搜索徽章：on 实心 / warn 描边（官方接口被中转挡住）/ off 置灰 */
const previewSearchClass = computed(() =>
  previewCaps.value.search === 'on' ? 'is-on' : previewCaps.value.search === 'warn' ? 'is-warn' : ''
)
const previewSearchLabel = computed(() => searchLabel(previewCaps.value.search))
const previewSearchTitle = computed(() =>
  searchHint(previewCaps.value.search, {
    template: form.requestTemplate,
    code: form.code,
    baseUrl: form.baseUrl
  })
)

function clampPercent(value?: number): string {
  const num = Number(value ?? 0)
  return `${Math.max(0, Math.min(100, num))}%`
}

/**
 * 切换启用状态。
 * 当前模板里的开关是只读的（后端 list 只返回启用项，停用即从列表消失且无法恢复），
 * 保留此函数，等后端补上「查全部」能力后给开关加回 @click 即可。
 */
function toggleEnabled() {
  form.enabled = form.enabled === 1 ? 0 : 1
}

function applyPreset(key: string) {
  if (key === 'volcengine') {
    form.extraConfig = JSON.stringify(
      {
        accessKey: '',
        secretKey: '',
        searchApiKey: '',
        region: 'cn-beijing',
        // 多模态图片解析粒度：low / high / xhigh（方舟不接受 auto，留空即 high）
        imageDetail: 'high',
        // reasoning_effort 档位白名单（模型前缀）；留空 = 仅 deepseek-v4 系列下发
        reasoningEffortModels: ['deepseek-v4']
      },
      null,
      2
    )
  } else if (key === 'qwen') {
    form.extraConfig = JSON.stringify({ accessKey: '', secretKey: '', enableSearch: true }, null, 2)
  } else if (key === 'minimax') {
    form.extraConfig = JSON.stringify({ codingPlanKey: '' }, null, 2)
  } else {
    form.extraConfig = '{}'
  }
  extraError.value = ''
}

function close() {
  emit('update:open', false)
}

function submit() {
  modelError.value = ''
  extraError.value = ''

  if (!form.code.trim()) {
    codeInput.value?.focus()
    return
  }
  if (!form.name.trim()) {
    nameInput.value?.focus()
    return
  }
  if (!form.baseUrl.trim()) {
    baseUrlInput.value?.focus()
    return
  }

  let models: string[] = []
  try {
    const parsed = JSON.parse(form.modelList.trim() || '[]')
    if (!Array.isArray(parsed)) throw new Error('not array')
    models = parsed.map(String)
  } catch {
    modelError.value = '模型列表必须是 JSON 数组，例如 ["model-a","model-b"]'
    return
  }

  const extra = form.extraConfig.trim() || '{}'
  try {
    JSON.parse(extra)
  } catch {
    extraError.value = '扩展配置必须是合法 JSON'
    return
  }

  emit('save', {
    id: props.provider?.id,
    code: form.code.trim(),
    name: form.name.trim(),
    baseUrl: form.baseUrl.trim(),
    // 空串表示「不修改」：后端对空值与 **** 都会跳过 apiKey 更新
    apiKey: form.apiKey.trim() || undefined,
    defaultModel: form.defaultModel.trim(),
    modelList: JSON.stringify(models),
    extraConfig: extra,
    requestTemplate: form.requestTemplate,
    enabled: form.enabled,
    sortOrder: Number(form.sortOrder) || 0
  })
}
</script>
