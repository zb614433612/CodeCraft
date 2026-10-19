<!--
  模型配置行（v3）
  ----------------------------------------------------------------------------
  信息层次：平台标识块 + 名称 + 编码 + 请求模板 + 默认标记 + 启用状态
           → Base URL · 默认模型（等宽弱底一行）
           → 能力徽章（对话 / 图片识别 / 联网搜索 / 余额查询）
           → 元信息（模型数 / 凭证状态 / 缺凭证或停用提示）
  能力语义：后端 ProviderConfig 无 capability 字段，全部由 utils/provider-capability.ts 推导：
    图片识别 —— 模型级三态：on=默认模型确定支持多模态；warn=平台支持但模型未知/非多模态；off=平台不支持。
               各平台协议差异由后端适配器抹平（火山归一 detail、Anthropic 转 image/source、
               Qwen/MiniMax/MiMo 剔除 detail、Ollama 转 messages[].images[]、OpenAI 公共契约原样透传）
    联网搜索 —— 三态：on=平台官方搜索接口就绪（火山 searchApiKey / 千问 enableSearch /
               MiniMax codingPlanKey / DeepSeek 走官方域名 api.deepseek.com 免凭证）；
               warn=本来有官方接口但当前配置用不了（DeepSeek 被第三方中转挡住），只剩通用抓取；
               off=该平台无官方搜索接口，同样只能靠通用抓取
    余额查询 —— deepseek 免凭证；火山与千问需账号 AK+SK；MiniMax / MiMo 需各自 Key
  说明：is_default 后端无单独接口（创建强制 0、更新不处理），因此只读展示，不提供切换按钮。
-->
<template>
  <div class="cc-prov-row" :class="{ 'is-off': !isEnabled }">
    <div class="cc-prov-row__icon" :class="iconTone">{{ initial }}</div>

    <div class="cc-prov-row__main">
      <div class="cc-prov-row__title">
        <span class="cc-prov-name">{{ provider.name }}</span>
        <span class="cc-prov-code">{{ provider.code }}</span>
        <span class="cc-tag">{{ label }}</span>
        <span v-if="provider.isDefault === 1" class="cc-tag cc-tag--ok">默认</span>
        <span class="cc-status" :class="isEnabled ? 'is-connected' : ''">
          <span class="cc-status__dot" />{{ isEnabled ? '已启用' : '已停用' }}
        </span>
      </div>

      <div class="cc-prov-url">
        <span>{{ provider.baseUrl }}</span>
        <span class="cc-prov-url__sep">·</span>
        <span class="cc-prov-url__model">默认 {{ provider.defaultModel || '未指定' }}</span>
      </div>

      <div class="cc-prov-caps">
        <span class="cc-prov-cap is-on">对话</span>
        <span class="cc-prov-cap" :class="visionClass" :title="visionTitle">图片识别</span>
        <span class="cc-prov-cap" :class="searchClass" :title="searchTitle">{{ searchCapLabel }}</span>
        <span class="cc-prov-cap" :class="balanceClass">{{ balanceLabel }}</span>
      </div>

      <div class="cc-prov-row__meta">
        <span class="cc-prov-meta-item">
          <svg class="cc-i cc-i--sm"><use href="#i-cog" /></svg>{{ modelCount }} 个模型
        </span>
        <span class="cc-prov-meta-item">
          <svg class="cc-i cc-i--sm"><use href="#i-lock" /></svg>{{ credentialState }}
        </span>
        <span v-if="caps.balance === 'warn'" class="cc-prov-meta-item" style="color: var(--warn)">
          扩展配置缺 accessKey / secretKey，余额与部分增强能力不可用
        </span>
        <span v-else-if="!isEnabled" class="cc-prov-meta-item">
          停用后不参与模型选择与默认 Provider 回退
        </span>
      </div>
    </div>

    <div class="cc-prov-row__side">
      <span
        class="cc-switch"
        :class="{ 'is-on': isEnabled, 'is-readonly': toggleDisabled }"
        :title="
          toggleDisabled
            ? '只读：停用后该项会从列表消失且无法恢复（后端列表接口只返回启用项）'
            : '启用 / 停用'
        "
        @click="onToggle"
      />
      <div class="cc-prov-row__ops">
        <button class="cc-icon-btn cc-icon-btn--sm" title="查询余额" @click="$emit('balance', provider)">
          <svg class="cc-i cc-i--sm"><use href="#i-db" /></svg>
        </button>
        <button class="cc-icon-btn cc-icon-btn--sm" title="编辑" @click="$emit('edit', provider)">
          <svg class="cc-i cc-i--sm"><use href="#i-brush" /></svg>
        </button>
        <button class="cc-icon-btn cc-icon-btn--sm" title="删除" @click="$emit('delete', provider)">
          <svg class="cc-i cc-i--sm"><use href="#i-trash" /></svg>
        </button>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import {
  deriveCapabilities,
  providerIconTone,
  providerInitial,
  searchHint,
  searchLabel,
  templateLabel,
  visionHint
} from '../utils/provider-capability'

/** Provider 配置（对应后端 ProviderConfig；apiKey 与 extraConfig 中的密钥为 **** 掩码） */
export interface ProviderItem {
  id?: number
  /** 唯一编码：deepseek / volcengine / qwen / anthropic / ollama / custom…（创建后不可改） */
  code: string
  name: string
  baseUrl: string
  /** 已配置时为 **** 掩码，空表示未配置 */
  apiKey?: string
  defaultModel?: string
  /** JSON 数组字符串 */
  modelList?: string
  /**
   * 解析后的模型数组。
   * 由 `api/llm-provider.ts` 的 `listProviders()` 在响应映射阶段填入；
   * 智能体页的「模型下拉」直接消费它，避免在多个组件里重复 JSON.parse。
   */
  parsedModelList?: string[]
  /** 平台增强凭证 JSON 字符串（密钥字段掩码） */
  extraConfig?: string
  requestTemplate?: string
  /** 1=默认 Provider（后端创建时强制 0，无单独设置接口，只读） */
  isDefault?: number
  /** 1=启用，0=停用 */
  enabled?: number
  sortOrder?: number
  createdAt?: string
  updatedAt?: string
}

const props = withDefaults(
  defineProps<{
    provider: ProviderItem
    /**
     * 开关是否只读。
     * 后端 `GET /api/llm-providers` 走 `selectAllEnabled()`，只返回 enabled=1 的记录；
     * 一旦停用，该项立即从列表消失，且 code 已被占用无法重建 —— 属于不可撤销操作。
     * 因此在后端提供「查全部」能力之前，页面把开关设为只读。
     */
    toggleDisabled?: boolean
  }>(),
  {
    toggleDisabled: false
  }
)

const emit = defineEmits<{
  (e: 'toggle', provider: ProviderItem): void
  (e: 'balance', provider: ProviderItem): void
  (e: 'edit', provider: ProviderItem): void
  (e: 'delete', provider: ProviderItem): void
}>()

/** 只读时不派发事件，避免误触导致 Provider 从列表消失 */
function onToggle() {
  if (props.toggleDisabled) return
  emit('toggle', props.provider)
}

const isEnabled = computed(() => props.provider.enabled !== 0)

const caps = computed(() =>
  deriveCapabilities(
    props.provider.requestTemplate,
    props.provider.code,
    props.provider.extraConfig,
    props.provider.defaultModel,
    // DeepSeek 官方搜索接口按 Base URL 判域名，必须把 baseUrl 传进去
    props.provider.baseUrl
  )
)

/** 图片识别徽章：on 实心 / warn 描边（模型未知或非多模态）/ off 置灰 */
const visionClass = computed(() =>
  caps.value.vision === 'on' ? 'is-on' : caps.value.vision === 'warn' ? 'is-warn' : ''
)
const visionTitle = computed(() => visionHint(caps.value.vision, props.provider.defaultModel))

/**
 * 联网搜索徽章：on 实心 / warn 描边（官方接口被中转挡住，只剩通用抓取）/ off 置灰。
 * 文案在 warn 时补「·通用抓取」，避免被读成「完全不能联网」。
 */
const searchClass = computed(() =>
  caps.value.search === 'on' ? 'is-on' : caps.value.search === 'warn' ? 'is-warn' : ''
)
const searchCapLabel = computed(() => searchLabel(caps.value.search))
const searchTitle = computed(() =>
  searchHint(caps.value.search, {
    template: props.provider.requestTemplate,
    code: props.provider.code,
    baseUrl: props.provider.baseUrl
  })
)

const initial = computed(() => providerInitial(props.provider.code))
const iconTone = computed(() => providerIconTone(props.provider.requestTemplate))
const label = computed(() => templateLabel(props.provider.requestTemplate || props.provider.code))

/** 模型列表长度（列表接口已解析出 parsedModelList，缺失时才回落到手工解析） */
const modelCount = computed(() => {
  if (props.provider.parsedModelList) return props.provider.parsedModelList.length
  const raw = props.provider.modelList
  if (!raw) return 0
  try {
    const parsed = JSON.parse(raw)
    return Array.isArray(parsed) ? parsed.length : 0
  } catch {
    return 0
  }
})

/** 凭证状态：列表接口返回掩码即视为已配置；本地 Ollama 免密 */
const credentialState = computed(() => {
  if (props.provider.apiKey) return 'API Key 已配置'
  const tpl = String(props.provider.requestTemplate || props.provider.code || '').toLowerCase()
  return tpl === 'ollama' ? '本地免密' : '未配置 API Key'
})

const balanceClass = computed(() => (caps.value.balance === 'on'
  ? 'is-on'
  : caps.value.balance === 'warn'
    ? 'is-warn'
    : ''))

const balanceLabel = computed(() => (caps.value.balance === 'warn' ? '余额查询缺凭证' : '余额查询'))
</script>
