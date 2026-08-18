> 🌐 中文版：[🇨🇳 LLM_PROVIDER_SYSTEM](./LLM_PROVIDER_SYSTEM.md)
# Multi LLM Provider Support System

> Version: v1.1.7 | Updated: 2026-08-13 | Audience: Developers / AI Collaborators
> This document describes CodeCraft's multi LLM Provider support system, including architecture design, data model, core components, and usage patterns.

---

## 1. System Overview

CodeCraft supports multiple LLM platforms (DeepSeek / OpenAI / Anthropic / Ollama / MiMo / MiniMax, etc.). Users can dynamically switch between different LLM Providers at runtime, and each Agent can be bound to a specific Provider.

### 1.1 Core Features

- **Multi-Provider Support**: Supports mainstream LLM platforms including DeepSeek, OpenAI, Anthropic, Ollama, MiMo, MiniMax
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
| Custom | `OpenAIClient` | `custom` | Minimal OpenAI-compatible mode |

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

### 7.2 Request/Response Example

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
