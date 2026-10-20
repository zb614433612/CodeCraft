> 🌐 中文版：[🇨🇳 LLM_PROVIDER_SYSTEM](./LLM_PROVIDER_SYSTEM.md)
# Multi LLM Provider Support System

> Version: v3.0.3 | Updated: 2026-10-20 | Audience: Developers / AI Collaborators
> This document describes CodeCraft's multi LLM Provider support system, including architecture design, data model, core components, and usage patterns.

---

## 1. System Overview

CodeCraft supports multiple LLM platforms (DeepSeek / OpenAI / Anthropic / Ollama / MiMo / MiniMax / Volcengine Ark, etc.). Users can dynamically switch between different LLM Providers at runtime, and each Agent can be bound to a specific Provider.

### 1.1 Core Features

- **Multi-Provider Support**: Supports mainstream LLM platforms including DeepSeek, OpenAI, Anthropic, Ollama, MiMo, MiniMax, Volcengine Ark
- **Dynamic Switching**: Frontend can switch Providers at runtime without restarting the service
- **Agent Binding**: Each Agent can be bound to a specific Provider and model
- **Unified Interface**: All Providers are called through a unified `LLMClient` interface, shielding platform differences
- **Hot Refresh**: Client cache is automatically refreshed after Provider configuration changes

---

## 2. Architecture Design

### 2.1 System Topology

```
┌─────────────────────────────────────────────────────────────────────────┐
│                        Vue 3 Frontend (frontend/)                       │
│                                                                         │
│  ┌─────────────────┐    ┌─────────────────┐    ┌─────────────────┐     │
│  │ CodeAssistantView│    │ AgentSelector   │    │ProviderConfigView│    │
│  │ (Chat Main UI)  │    │ (Agent Selector)│    │(Provider Mgmt)  │     │
│  └────────┬────────┘    └────────┬────────┘    └────────┬────────┘     │
│           │                      │                      │              │
│           │ providerCode         │ providerId           │ CRUD         │
│           │ (runtime switch)     │ (Agent binding)      │              │
│           └──────────────────────┼──────────────────────┘              │
│                                  │                                     │
│                                  ▼                                     │
│  ┌─────────────────────────────────────────────────────────────────┐   │
│  │                    REST API + SSE                                │   │
│  └─────────────────────────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────────────────────┘
                                  │
                                  ▼
┌─────────────────────────────────────────────────────────────────────────┐
│                    Spring Boot 3.4 Backend                              │
│                                                                         │
│  ┌─────────────────────────────────────────────────────────────────┐   │
│  │              LLMProviderController (REST API)                    │   │
│  │              GET/POST/PUT/DELETE /api/llm-providers              │   │
│  └─────────────────────────────────────────────────────────────────┘   │
│                                  │                                     │
│                                  ▼                                     │
│  ┌─────────────────────────────────────────────────────────────────┐   │
│  │              LLMClientManager (Core Manager)                     │   │
│  │                                                                 │   │
│  │  ┌─────────────────┐  ┌─────────────────┐  ┌─────────────────┐ │   │
│  │  │ Provider Routing│  │ Client Cache    │  │ Hot Refresh     │ │   │
│  │  │ resolveClient() │  │ ConcurrentHashMap│ │ refreshClients()│ │   │
│  │  └─────────────────┘  └─────────────────┘  └─────────────────┘ │   │
│  └─────────────────────────────────────────────────────────────────┘   │
│                                  │                                     │
│                                  ▼                                     │
│  ┌─────────────────────────────────────────────────────────────────┐   │
│  │              LLMClient Interface (Unified Abstraction)           │   │
│  │                                                                 │   │
│  │  ┌─────────────┐ ┌─────────────┐ ┌─────────────┐ ┌─────────────┐│   │
│  │  │DeepSeekClient│ │ OpenAIClient│ │AnthropicClient│ │ OllamaClient││  │
│  │  └─────────────┘ └─────────────┘ └─────────────┘ └─────────────┘│   │
│  │  ┌─────────────┐ ┌─────────────┐                               │   │
│  │  │  MiMoClient │ │ AbstractLLM │                               │   │
│  │  └─────────────┘ │   Client    │                               │   │
│  │                  └─────────────┘                               │   │
│  └─────────────────────────────────────────────────────────────────┘   │
│                                  │                                     │
│                                  ▼                                     │
│  ┌─────────────────────────────────────────────────────────────────┐   │
│  │              LLMWebClientManager (WebClient Management)          │   │
│  │                                                                 │   │
│  │  - Independent WebClient instance per Provider                   │   │
│  │  - Dynamic API Key injection                                    │   │
│  │  - Connection pool management                                   │   │
│  └─────────────────────────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────────────────────┘
                                  │
                                  ▼
┌─────────────────────────────────────────────────────────────────────────┐
│                        LLM Platform APIs                                │
│                                                                         │
│  ┌─────────────┐ ┌─────────────┐ ┌─────────────┐ ┌─────────────┐      │
│  │ DeepSeek API│ │ OpenAI API  │ │Anthropic API│ │ Ollama API  │      │
│  └─────────────┘ └─────────────┘ └─────────────┘ └─────────────┘      │
└─────────────────────────────────────────────────────────────────────────┘
```

### 2.2 Data Flow

```
User switches Provider on frontend
    ↓
CodeAssistantView.onProviderSwitch(providerCode)
    ↓
AgentSelector.saveRuntime() → Persist to agent_config table
    ↓
User sends message
    ↓
ChatRequest.providerCode = agentRuntime.providerCode
    ↓
DeepSeekServiceImpl.prepareConversationContext()
    ↓
LLMClientManager.resolveClientByCode(providerCode)
    ↓
Returns corresponding LLMClient instance
    ↓
LLMClient.buildRequestBody() → Build Provider-specific request body
    ↓
LLMClient.streamChat() / blockingChat() → Call LLM API
    ↓
LLMClient.extractContentFromStreamChunk() → Parse Provider-specific response
```

---

## 3. Data Model

### 3.1 llm_provider Table

```sql
CREATE TABLE IF NOT EXISTS llm_provider (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  code VARCHAR(30) NOT NULL UNIQUE COMMENT 'Provider code: deepseek/openai/anthropic/ollama/custom',
  name VARCHAR(100) NOT NULL COMMENT 'Display name, e.g. DeepSeek / OpenAI / Claude',
  base_url VARCHAR(300) NOT NULL COMMENT 'API Base URL, e.g. https://api.deepseek.com',
  api_key VARCHAR(200) COMMENT 'API Key (can be encrypted, falls back to sys_config when empty)',
  default_model VARCHAR(100) COMMENT 'Default model name for this Provider',
  model_list TEXT COMMENT 'Available models JSON array, e.g. ["deepseek-v4-pro","deepseek-v4-flash"]',
  request_template VARCHAR(30) DEFAULT 'deepseek' COMMENT 'Request template type: deepseek / openai / anthropic / ollama / custom',
  is_default TINYINT DEFAULT 0 COMMENT 'Whether it is the default Provider',
  enabled TINYINT DEFAULT 1 COMMENT 'Whether enabled',
  sort_order INT DEFAULT 0 COMMENT 'Sort order',
  created_at DATETIME NOT NULL,
  updated_at DATETIME NOT NULL,
  INDEX idx_provider_code (code),
  INDEX idx_provider_enabled (enabled)
) DEFAULT CHARSET=utf8mb4 COMMENT='LLM Provider configuration table';
```

### 3.2 agent_config Table New Columns

```sql
ALTER TABLE agent_config ADD COLUMN provider_id BIGINT DEFAULT NULL COMMENT 'LLM Provider ID';
ALTER TABLE agent_config ADD COLUMN provider_code VARCHAR(30) DEFAULT NULL COMMENT 'LLM Provider Code';
ALTER TABLE agent_config ADD COLUMN character_profile TEXT DEFAULT NULL COMMENT 'Character profile';
```

### 3.3 ProviderConfig Entity

```java
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ProviderConfig {
    private Long id;
    /** Provider code: deepseek / openai / anthropic / ollama / custom */
    private String code;
    /** Display name */
    private String name;
    /** API Base URL */
    private String baseUrl;
    /** API Key (can be encrypted) */
    private String apiKey;
    /** Default model name */
    private String defaultModel;
    /** Available models (JSON array string) */
    private String modelList;
    /** Request template type: deepseek / openai / anthropic / ollama / custom */
    private String requestTemplate;
    /** Whether it is the default Provider */
    private Integer isDefault;
    /** Whether enabled */
    private Integer enabled;
    /** Sort order */
    private Integer sortOrder;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
```

### 3.4 AgentConfig Entity New Fields

```java
/** Bound LLM Provider ID, references llm_provider.id (null or 0 means use default Provider) */
private Long providerId;
/** Frontend dynamically selected LLM Provider Code (e.g. deepseek/openai), persisted at runtime */
private String providerCode;
/** Character profile JSON string (e.g. {"name":"Alice","species":"Human",...}) */
private String characterProfile;
```

---

## 4. Core Components

### 4.1 LLMClient Interface

All LLM Providers must implement this interface to shield API differences across platforms.

```java
public interface LLMClient {
    /** Get Provider code (e.g. "deepseek", "openai") */
    String getProviderCode();

    /** Get current WebClient instance */
    WebClient getWebClient();

    /** Build API request body */
    Map<String, Object> buildRequestBody(
            List<Map<String, Object>> messages,
            String model,
            Double temperature,
            String thinkingMode,
            boolean stream,
            List<Map<String, Object>> tools);

    /** Streaming chat (SSE) */
    Flux<String> streamChat(Map<String, Object> requestBody);

    /** Blocking chat (non-streaming) */
    String blockingChat(Map<String, Object> requestBody, Duration timeout);

    /** Extract content delta from SSE stream chunk */
    String extractContentFromStreamChunk(String sseChunk);

    /** Extract reasoning delta from SSE stream chunk */
    String extractReasoningFromStreamChunk(String sseChunk);

    /** Get API endpoint path (e.g. "/v1/chat/completions") */
    default String getChatEndpoint() {
        return "/v1/chat/completions";
    }

    /** Extract final content from blocking response */
    String extractContentFromBlockingResponse(String responseBody);
}
```

### 4.2 LLMClientManager

Core manager responsible for Provider registration, routing, and caching.

```java
@Slf4j
@Component
public class LLMClientManager {
    /** Provider Code → LLMClient cache */
    private final Map<String, LLMClient> clientMap = new ConcurrentHashMap<>();

    /** Provider Code → ProviderConfig cache */
    private final Map<String, ProviderConfig> configMap = new ConcurrentHashMap<>();

    /** Resolve LLMClient by Provider Code */
    public LLMClient resolveClientByCode(String providerCode) { ... }

    /** Resolve LLMClient by Provider ID */
    public LLMClient resolveClientByProviderId(Long providerId) { ... }

    /** Get first available LLMClient (sorted by sortOrder) */
    public LLMClient getFirstClient() { ... }

    /** Hot refresh all Providers */
    public synchronized void refreshClients() { ... }
}
```

### 4.3 Provider Implementations

| Provider | Implementation | requestTemplate | Description |
|----------|----------------|-----------------|-------------|
| DeepSeek | `DeepSeekClient` | `deepseek` | OpenAI-compatible format, supports thinking parameter |
| OpenAI | `OpenAIClient` | `openai` | Standard OpenAI API format |
| Anthropic | `AnthropicClient` | `anthropic` | Claude API format, supports thinking parameter |
| Ollama | `OllamaClient` | `ollama` | Local Ollama service |
| MiMo | `MiMoClient` | `mimo` | Xiaomi MiMo model |
| MiniMax | `MiniMaxClient` | `minimax` | MiniMax API (OpenAI-compatible, thinking adaptive + reasoning_split) |
| Volcengine Ark | `VolcengineArkClient` | `volcengine` (aliases `ark` / `doubao`) | Ark (Doubao and third-party models hosted on Ark), OpenAI-compatible; thinking.type = enabled/disabled/auto |
| Custom | `OpenAIClient` | `custom` | Minimal OpenAI-compatible mode |

### 4.5 Volcengine Ark Enhanced Capabilities

Beyond chat, Volcengine Ark exposes several platform APIs. They are integrated with the same principle: **enable per capability, silently skip when credentials are missing, always fall back on failure**.

| Capability | Implementation | Required config | Notes |
|------------|----------------|-----------------|-------|
| Chat / tool calling | `VolcengineArkClient` | template `volcengine` + `apiKey` (Ark API Key) | Endpoint resolution appends `/api/v3/chat/completions` (works with or without `/api/v3` in baseUrl); `thinking.type` maps to `enabled/disabled/auto`; `reasoning_effort` is only sent for `deepseek-v4` models to avoid 400s |
| Image understanding | `FileVisionService` + `image_url` blocks | A vision-capable model | Ark only accepts `detail = low/high/xhigh`; the adapter normalizes OpenAI-style `auto` to `high`. `doubao-seed` models accept images, while `deepseek-*` text models on Ark do not (blocked up-front with a clear message) |
| Balance query | `VolcengineBillingClient` + `ProviderBalanceService` | `accessKey` / `secretKey` in `extraConfig` | Billing Center OpenAPI `QueryBalanceAcct` (V4 signature, POST to `billing.volcengineapi.com`); returns available/cash balance, credit limit, frozen and arrears amounts; only this capability is disabled when AK/SK is absent |
| Web search | `VolcengineSearchApiClient` | `searchApiKey` in `extraConfig`, or env `VOLCENGINE_SEARCH_API_KEY` / `WEB_SEARCH_API_KEY` | Doubao Search Custom edition (`POST https://open.feedcoopapi.com/search_api/web_search`) returning structured results (Title/Url/Summary/Content/AuthInfoLevel); `web_search` prefers it and falls back to Bing/Baidu on failure |
| Web fetch | `VolcengineSearchApiClient#fetchByUrl` | Same as above | Best-effort fetch: queries by URL host + path keywords and returns the matching URL's body; falls back to Jina Reader / local fetch when there is no hit |

Configuration example (the Provider management page supports the same fields):

```sql
UPDATE llm_provider SET
  request_template = 'volcengine',
  base_url         = 'https://ark.cn-beijing.volces.com/api/v3',
  api_key          = '<ARK_API_KEY>',
  default_model    = 'doubao-seed-2-1-pro-260628',
  extra_config     = '{"accessKey":"AK...","secretKey":"SK...","searchApiKey":"..."}'
WHERE code = 'volcengine';
```

> `extra_config` is an optional column added for platform-specific credentials (auto-created via `ALTER TABLE` at startup — no manual migration needed).
> The list API masks `accessKey` / `secretKey` / `searchApiKey`; submitting a masked value means "unchanged".
> The web-search key is **not** the Ark API key (create it in the Search console); when absent, the capability is skipped silently and chat is unaffected.
> All capability checks rely on "request template + actual host + credential validity" rather than the user-defined providerCode.

### 4.6 MiniMax / Xiaomi MiMo Enhanced Capabilities

> Short version: **MiniMax supports all three (search / image understanding / quota); MiMo supports image understanding and (cookie-based) balance only; neither exposes a public web-fetch API.**

| Platform | Capability | Support | Implementation / config |
|----------|------------|---------|-------------------------|
| MiniMax | Web search | ✅ Yes | `MiniMaxCodingPlanClient` → `POST {host}/v1/coding_plan/search` (body `{"q":"..."}`), returns `organic[title/link/snippet/date]`; `web_search` prefers it and falls back to Bing/Baidu |
| MiniMax | Image understanding | ✅ Yes (two paths) | ① Model side: `MiniMax-M3` / VL models use chat multimodal `image_url` blocks; ② Dedicated API: `POST {host}/v1/coding_plan/vlm` (prompt + image_url, URL or base64; JPEG/PNG/WebP) |
| MiniMax | Balance / quota | ⚠️ Quota only | `GET {host}/v1/token_plan/remains` (Coding Plan / Token Plan key) returning remaining percent, counts and reset time (`balanceType=quota`). MiniMax has no public cash-balance OpenAPI, so the UI shows "quota xx%" |
| MiniMax | Web fetch | ❌ No official API | Keeps the Jina Reader / local fetch pipeline |
| Xiaomi MiMo | Image understanding | ✅ Yes | `mimo-v2.5` / `mimo-v2.5-pro` are natively multimodal (image/audio/video); uses chat `image_url` blocks and strips the undocumented `detail` field. Early text-only models are blocked up-front |
| Xiaomi MiMo | Balance | ⚠️ Console cookie required | MiMo has no balance OpenAPI (an `sk-` inference key cannot read it): `GET https://platform.xiaomimimo.com/api/v1/balance` with `{"mimoCookie":"..."}` in `extraConfig`. The cookie lasts about 24 hours and the UI tells you to re-paste it |
| Xiaomi MiMo | Web search / fetch | ❌ No official API | Keeps Bing/Baidu + Jina/local pipeline |

Configuration examples (Provider page → "Extended config"):

```json
// MiniMax (search + quota; apiHost is optional and defaults to the baseUrl host:
// api.minimaxi.com for China, api.minimax.io for global)
{"codingPlanKey":"sk-cp-...","apiHost":"https://api.minimaxi.com"}

// Xiaomi MiMo (balance; copy the Cookie header of the balance request from DevTools)
{"mimoCookie":"api-platform_serviceToken=...; userId=..."}
```

> - MiniMax search/quota endpoints expect a **Coding Plan / Token Plan key**; ordinary model keys may be rejected (`base_resp.status_code` != 0, e.g. `2062 no active token plan`). On failure the search falls back and the quota chip is simply hidden — chat is unaffected.
> - `current_interval_usage_count` actually means **remaining** (not consumed); the parser prefers `current_interval_remaining_percent` and uses counts as a fallback.
> - The MiMo cookie only lives ~24 hours (platform limitation); when it expires the UI asks you to re-paste instead of failing silently.

### 4.7 Qwen (Alibaba Cloud Model Studio / DashScope)

| Capability | Support | Implementation / config |
|------------|---------|-------------------------|
| Chat / tool calling | ✅ Yes | `QwenClient` on the OpenAI-compatible endpoint `{baseUrl}/compatible-mode/v1/chat/completions` (smart endpoint resolution: bare host gets `/compatible-mode/v1` appended, full URLs are used as-is) |
| Thinking mode | ✅ Yes | Qwen extension params: `enable_thinking` (`non-thinking→false`, `thinking / thinking_max→true`) + `thinking_budget` (defaults to 8192 for `thinking_max`; override with `extraConfig.thinkingBudget`; omitted when unset so the model default applies) |
| Image understanding | ✅ Yes | Qwen-VL / Qwen3-VL / Omni models use chat multimodal `image_url` blocks; text-only models (`qwen-plus`, `qwen-max`) are blocked up-front with a clear message |
| Chat-level web search | ✅ Optional | `{"enableSearch": true}` in `extraConfig` sends `enable_search: true` + `search_options` (DashScope does not support OpenAI's `web_search_options`) |
| Balance query | ✅ Yes | Alibaba Cloud Billing `QueryAccountBalance` (BssOpenApi 2017-12-14, RPC-style HMAC-SHA1 signature); requires account-level `accessKey` / `secretKey` in `extraConfig` |
| Structured web search | ❌ No | `enable_search` is a chat-level capability (the model decides to search and answers directly), not a "query → result list" API; the `web_search` tool keeps using Bing/Baidu |

Configuration example (Provider page → "Extended config"):

```json
{
  "enableSearch": true,          // optional: chat-level web search
  "thinkingBudget": 4096,        // optional: thinking budget cap
  "accessKey": "LTAI...",        // balance query: Alibaba Cloud AK
  "secretKey": "..."             // balance query: Alibaba Cloud SK
}
```

> DashScope API keys are region-bound: a Beijing key against the Singapore endpoint returns 401 `invalid_api_key`.
> Prefer the workspace-specific base URL `https://{WorkspaceId}.cn-beijing.maas.aliyuncs.com/compatible-mode/v1` (the legacy `https://dashscope.aliyuncs.com` still works).

### 4.8 OpenAI / Anthropic as Protocol Contracts

The OpenAI and Anthropic templates are positioned as **protocol contracts**: any service that follows the spec can be plugged in
(official APIs, Azure, OpenRouter and proxies, self-hosted gateways such as vLLM / LM Studio / One-API, and the `/anthropic`
compatibility layers from GLM / DeepSeek / MiniMax).

| Dimension | OpenAI (`openai` / `custom`) | Anthropic (`anthropic`) |
|-----------|------------------------------|-------------------------|
| Endpoint resolution | Bare host → `/v1/chat/completions`; existing `/v1` is not duplicated; a full `/chat/completions` URL is used as-is (absolute URL, so baseUrl paths are never dropped) | Bare host → `/v1/messages`; `/anthropic` compatibility prefix → `/anthropic/v1/messages`; a full `/messages` URL is used as-is |
| Parameter tiering | **Only official** (`api.openai.com`) receives `max_tokens` (8192) and `reasoning_effort`; third-party compatible services get neither (limits and support vary — let the server decide, avoiding 400s) | `thinking` is sent **only when enabled** (many compatibility layers reject the field); `max_tokens` defaults to 8192, overridable via `extraConfig.maxTokens`, and is widened to `budget + 8192` in thinking mode to satisfy `max_tokens > budget_tokens` |
| Model key normalization | Prefixed names like `openai/gpt-4o` or `azure/o3-mini` are matched on the **last segment**, so proxy naming never misclassifies a reasoning model | — |
| Images | `image_url` blocks are passed through | **Automatic protocol conversion**: `image_url` → `image/source` (`data:` URLs become `base64` + `media_type`; http(s) becomes `url`) |
| Tools | `tools` passed through (OpenAI function-calling format) | `tools` converted to `{name, description, input_schema}`; `tool_choice` removed for compatibility |
| Reasoning models | `o1 / o3 / o4 / gpt-5` families switch to `max_completion_tokens` and drop `temperature` | — |

> Both adapters decide parameters by "protocol + actual baseUrl" instead of vendor names, so any compatible service can be attached safely.
> Vendor-specific capabilities (dedicated vision APIs, balance, structured search, etc.) belong to their dedicated templates.

### 4.9 GPT (official-specialized) vs OpenAI (protocol contract)

Both templates speak OpenAI Chat Completions, but their responsibilities are **strictly separated** — do not mix them up:

| Dimension | `gpt` (official, `GptClient`) | `openai` (contract, `OpenAIClient`) |
|-----------|-------------------------------|--------------------------------------|
| Target | OpenAI official GPT / o-series (`api.openai.com` or an official proxy) | Azure / OpenRouter / proxies / vLLM / LM Studio / One-API — **any compatible service** |
| Endpoint | Defaults to `https://api.openai.com/v1/chat/completions` | Any baseUrl (custom proxy paths are used as-is when complete) |
| Output cap | **Always sends** `max_completion_tokens` (8192 normal / 16384 reasoning; injected `max_tokens` is converted) | **Never sends** it (limits vary — let the server decide, avoiding 400s) |
| `reasoning_effort` | **Model-aware**: GPT-5 supports `none`/`minimal`; o-series rejects `none` (so nothing is sent for non-thinking); `thinking→medium`, `thinking_max→high`; explicit `low`/`high`/`xhigh` pass through | Never sent (third-party support varies) |
| `temperature` | Removed for reasoning models or when reasoning is active (non-`none`) | Removed for reasoning models only |
| `stream_options` | Streaming requests automatically include `{"include_usage": true}` | Not added |
| Model key | `openai/gpt-5`, `azure/gpt-5` matched on the last segment | Same |
| Typical misuse | Pointing a **third-party proxy** at `gpt` → may 400 due to `max_completion_tokens` / `reasoning_effort` / `stream_options` | Pointing **official GPT** at `openai` → loses reasoning-level control and stream usage stats |

> Two official gotchas: ① `max_tokens` is deprecated for the new endpoints and GPT-5 / o-series reject it with 400 — use `max_completion_tokens`;
> ② reasoning models (and requests with `reasoning_effort` other than `none`) do **not** accept `temperature`.

### 4.10 Adapter Selection Matrix (maintenance reference)

| requestTemplate | Adapter | Use for | Do not use for |
|-----------------|---------|---------|----------------|
| `deepseek` | `DeepSeekClient` | DeepSeek official API (native `thinking.type` / `reasoning_effort`) | DeepSeek on Ark (use `volcengine`), proxies (use `openai`) |
| `volcengine` / `ark` / `doubao` | `VolcengineArkClient` | Volcengine Ark (Doubao + hosted models), `thinking.type=auto/enabled/disabled` | DeepSeek official, third-party proxies |
| `qwen` / `dashscope` / `tongyi` | `QwenClient` | Alibaba Model Studio Qwen (incl. Qwen-VL), `enable_thinking` / `enable_search` | Official GPT/o-series (use `gpt`), other compatible services (use `openai`) |
| `gpt` / `gpt-official` / `chatgpt` | `GptClient` | OpenAI official GPT / o-series: `max_completion_tokens` + full `reasoning_effort` range + `stream_options` | Any third-party compatible service |
| `openai` | `OpenAIClient` | **Protocol contract**: any OpenAI-compatible service | Official GPT when reasoning-level control is needed (use `gpt`) |
| `anthropic` | `AnthropicClient` | **Protocol contract**: Anthropic official / Claude proxies / GLM, DeepSeek, MiniMax `/anthropic` layers | OpenAI-protocol services |
| `ollama` | `OllamaClient` | Local / intranet Ollama (`/api/chat`), offline scenarios | Cloud OpenAI-compatible APIs (vLLM / LM Studio `/v1` → use `openai`) |
| `mimo` / `xiaomi-mimo` | `MiMoClient` | Xiaomi MiMo platform v2.5 series (multimodal) | Other compatible services |
| `minimax` / `minimax-cn` / `minimax-intl` | `MiniMaxClient` | MiniMax OpenAI-compatible endpoints (M3 / M2.x) | MiniMax `/anthropic` endpoint (use `anthropic`) |
| `custom` | `OpenAIClient` (minimal mode) | Unknown gateways (fallback) | Prefer a specific template when one matches (more accurate parameters) |

> Every adapter class carries an "适用范围（选型指引）" block in its Javadoc (applicable / not applicable / exclusive capabilities);
> this matrix is kept in sync with the `LLMClientManager` class comment.

### 4.4 DeepSeek Official Tool API Integration (v2.0.3)

`DeepSeekToolsApiClient` makes `web_search` / `web_fetch` automatically use the official endpoints when the DeepSeek official API is in use — no extra user configuration required:

| Item | Description |
|------|-------------|
| Enabled when | Provider `baseUrl` host is `api.deepseek.com` and apiKey is non-empty (the placeholder `__MUST_CONFIGURE_API_KEY__` counts as unconfigured) |
| Endpoints | `POST /v1/tools/search` (query / count / need_content, etc.), `POST /v1/tools/fetch` (url) |
| Auth | Shares `Authorization: Bearer <apiKey>` with Chat Completions, injected by `LLMWebClientManager` |
| Path resolution | `resolveApiPath()` tolerates baseUrl with or without `/v1` |
| Failure policy | Exceptions are caught by the tools, which fall back to the legacy pipeline (Bing/Baidu, Jina/local); tool availability is never affected |
| Detection basis | The `baseUrl` host (not providerCode) — third-party proxies / self-hosted compatible services are never enabled |
| Sub-agents | `AgentForkManager` forwards it via `ToolContext.setProviderCode(...)` so child agents detect consistently |

> Why not providerCode: users may define arbitrary codes (e.g. `deepseek-proxy`), while the host is the objective fact of "official or not".

---

## 5. Provider Routing Logic

### 5.1 Routing Priority

```
Frontend dynamic providerCode > Agent config providerId > First available Provider
```

### 5.2 Routing Flowchart

```
DeepSeekServiceImpl.prepareConversationContext()
    ↓
    ├─ 1. Check ChatRequest.providerCode (frontend dynamic switch)
    │      ↓
    │      LLMClientManager.resolveClientByCode(providerCode)
    │      ↓
    │      Found → Return LLMClient
    │      ↓
    │      Not found → Continue to next step
    │
    ├─ 2. Check AgentConfig.providerId (Agent binding)
    │      ↓
    │      LLMClientManager.resolveClientByProviderId(providerId)
    │      ↓
    │      Found → Return LLMClient
    │      ↓
    │      Not found → Continue to next step
    │
    └─ 3. Get first available Provider (sorted by sortOrder)
           ↓
           LLMClientManager.getFirstClient()
           ↓
           Found → Return LLMClient
           ↓
           Not found → Throw exception "No available LLM Provider"
```

---

## 6. Frontend Integration

### 6.1 Provider Switching UI

In `CodeAssistantView.vue`, users can switch Providers via the "More Settings" panel:

```vue
<!-- Low frequency: More Settings popover -->
<div v-if="showMoreSettings" class="more-settings-popover">
  <div class="more-settings-item">
    <span class="mode-emoji">📡</span>
    <span class="mode-label">Provider</span>
    <a-select
      :value="agentRuntime.providerCode || providerOptions[0]?.code"
      @change="(v: string) => { onProviderSwitch(v) }"
      size="small"
      class="model-select"
    >
      <a-select-option
        v-for="prov in providerOptions"
        :key="prov.code"
        :value="prov.code"
      >{{ prov.name }}</a-select-option>
    </a-select>
  </div>
</div>
```

### 6.2 Dynamic Model List

Dynamically refresh the model list based on the currently selected Provider:

```typescript
/** Refresh model list based on current Provider */
const refreshModelList = () => {
  const providers = providerOptions.value
  if (providers.length === 0) {
    availableModels.value = []
    return
  }

  const runtime = agentSelectorRef.value?.runtime
  // Find the current Provider
  const prov = (runtime?.providerCode
    ? providers.find((p: ProviderConfig) => p.code === runtime.providerCode)
    : null) || providers[0]

  if (prov?.parsedModelList && prov.parsedModelList.length > 0) {
    availableModels.value = prov.parsedModelList
    // If current model is not in new Provider list, auto-switch to default model
    const currentModel = runtime?.model
    if (runtime && (!currentModel || !prov.parsedModelList.includes(currentModel))) {
      runtime.model = prov.defaultModel || prov.parsedModelList[0]
      agentSelectorRef.value?.saveRuntime()
    }
  } else {
    availableModels.value = providers.flatMap((p: ProviderConfig) => p.parsedModelList || [])
  }
}
```

### 6.3 No Provider Guide

When no Provider is configured, a guide interface is displayed:

```vue
<div v-if="providerOptions.length === 0" class="no-provider-guide">
  <div class="guide-icon">🔌</div>
  <h2 class="guide-title">No LLM Provider Configured</h2>
  <p class="guide-desc">The AI assistant needs an LLM Provider to connect to a large language model</p>
  <a-button type="primary" size="large" class="guide-btn" @click="router.push('/config')">
    Go to Settings to Create Provider
  </a-button>
</div>
```

---

## 7. REST API

### 7.1 LLMProviderController

| Method | Path | Description | Permission |
|--------|------|-------------|------------|
| GET | `/api/llm-providers` | Get all enabled Providers | admin |
| GET | `/api/llm-providers/{id}` | Get single Provider details | admin |
| POST | `/api/llm-providers` | Create Provider | admin |
| PUT | `/api/llm-providers/{id}` | Update Provider | admin |
| DELETE | `/api/llm-providers/{id}` | Delete Provider | admin |

### 7.2 ProviderBalanceController

| Method | Path | Description | Permission |
|--------|------|-------------|------------|
| GET | `/api/llm-providers/{code}/balance` | Query Provider account balance (DeepSeek only; called by the chat page on page entry / after each conversation to display the balance) | Logged-in user |

**Response Example (DeepSeek)**

```json
{
  "code": 200,
  "message": "操作成功",
  "data": {
    "providerCode": "deepseek",
    "isAvailable": true,
    "balanceInfos": [
      {
        "currency": "CNY",
        "totalBalance": "110.00",
        "grantedBalance": "10.00",
        "toppedUpBalance": "100.00"
      }
    ],
    "updatedAt": 1678886400000
  }
}
```

### 7.3 Request/Response Example

**Create Provider**

```json
POST /api/llm-providers
{
  "code": "openai",
  "name": "OpenAI",
  "baseUrl": "https://api.openai.com",
  "apiKey": "sk-xxx",
  "defaultModel": "gpt-4o",
  "modelList": "[\"gpt-4o\",\"gpt-4o-mini\",\"gpt-3.5-turbo\"]",
  "requestTemplate": "openai"
}
```

**Response**

```json
{
  "code": 200,
  "message": "Provider created successfully",
  "data": {
    "id": 2,
    "code": "openai",
    "name": "OpenAI",
    "baseUrl": "https://api.openai.com",
    "apiKey": "****",
    "defaultModel": "gpt-4o",
    "modelList": "[\"gpt-4o\",\"gpt-4o-mini\",\"gpt-3.5-turbo\"]",
    "requestTemplate": "openai",
    "isDefault": 0,
    "enabled": 1,
    "sortOrder": 0,
    "createdAt": "2026-07-01T10:00:00",
    "updatedAt": "2026-07-01T10:00:00"
  }
}
```

---

## 8. Configuration Guide

### 8.1 Adding a New Provider

1. Visit `/config` page (or `/provider-config` page)
2. Click "Add Provider" button
3. Fill in configuration:
   - **Code**: Unique identifier, e.g. `openai`, `anthropic`
   - **Name**: Display name, e.g. `OpenAI`, `Anthropic`
   - **Base URL**: API endpoint, e.g. `https://api.openai.com`
   - **API Key**: API key (optional, falls back to system config when empty)
   - **Default Model**: Default model name to use
   - **Model List**: JSON array format available model list
   - **Request Template**: Select corresponding API format
4. Click "Create Provider"

### 8.2 Binding Provider to Agent

1. Visit `/agent-config` page
2. Edit target Agent
3. Select the Provider to bind in the "LLM Provider" dropdown
4. Save configuration

### 8.3 Runtime Provider Switching

1. In the chat interface bottom, click the "More" button
2. Select the Provider to use in the "Provider" dropdown
3. System will automatically refresh the model list
4. Selected Provider will be persisted to current Agent config

---

## 9. Extension Guide

### 9.1 Adding a New Provider Implementation

1. Create a new client class in the `service/llm/` directory, extending `AbstractLLMClient` or directly implementing `LLMClient` interface
2. Implement all abstract methods:
   - `buildRequestBody()` - Build Provider-specific request body
   - `streamChat()` - Streaming call
   - `blockingChat()` - Blocking call
   - `extractContentFromStreamChunk()` - Parse streaming response
   - `extractReasoningFromStreamChunk()` - Parse reasoning process
   - `extractContentFromBlockingResponse()` - Parse blocking response
3. Add new case in `LLMClientManager.createClient()` method:

```java
case "new_provider" -> {
    var wc = webClientManager.getOrCreate(config);
    yield new NewProviderClient(wc, objectMapper, config.getCode());
}
```

4. Add new style mapping in frontend `ProviderConfigView.vue`'s `templateLabel()` and `templateStyle()` methods

### 9.2 Custom Request Template

If a Provider's API format doesn't match any existing template, use the `custom` template type which uses a minimal OpenAI-compatible mode (doesn't send thinking and other platform-specific parameters).

---

## 10. Known Issues & Notes

| Issue | Description | Recommendation |
|-------|-------------|---------------|
| API Key Security | Currently stored as plaintext in database | Should support encrypted storage |
| Provider not found | Frontend switching to a deleted Provider falls back to default | Frontend should show notification |
| Model list sync | Some Providers' model lists may change | Should support dynamic model list fetching from API |
| Concurrent requests | Race conditions possible when multiple users switch Providers simultaneously | ConcurrentHashMap cache already mitigates this |

---

> 📌 **Doc Maintenance Convention**: When adding new LLM Provider implementations or modifying Provider routing logic, please sync this document.
