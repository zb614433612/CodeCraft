> 🌐 English Version：[🇬🇧 LLM_PROVIDER_SYSTEM_EN](./LLM_PROVIDER_SYSTEM_EN.md)
# 多 LLM Provider 支持系统

> 版本：v1.1.3 | 更新：2026-07-02 | 受众：开发者 / AI 协作伙伴
> 本文档描述 CodeCraft 的多 LLM Provider 支持系统，包括架构设计、数据模型、核心组件和使用方式。

---

## 一、系统概述

CodeCraft 支持多种 LLM 平台（DeepSeek / OpenAI / Anthropic / Ollama / MiMo 等），用户可以在运行时动态切换不同的 LLM Provider，每个 Agent 也可以绑定特定的 Provider。

### 1.1 核心特性

- **多 Provider 支持**：支持 DeepSeek、OpenAI、Anthropic、Ollama、MiMo 等主流 LLM 平台
- **动态切换**：前端运行时可随时切换 Provider，无需重启服务
- **Agent 绑定**：每个 Agent 可以绑定特定的 Provider 和模型
- **统一接口**：所有 Provider 通过统一的 `LLMClient` 接口调用，屏蔽平台差异
- **热刷新**：Provider 配置变更后自动刷新客户端缓存

---

## 二、架构设计

### 2.1 系统拓扑图

```
┌─────────────────────────────────────────────────────────────────────────┐
│                        Vue 3 前端 (frontend/)                          │
│                                                                         │
│  ┌─────────────────┐    ┌─────────────────┐    ┌─────────────────┐     │
│  │ CodeAssistantView│    │ AgentSelector   │    │ProviderConfigView│    │
│  │ (聊天主界面)     │    │ (Agent 选择器)  │    │ (Provider 管理) │     │
│  └────────┬────────┘    └────────┬────────┘    └────────┬────────┘     │
│           │                      │                      │              │
│           │ providerCode         │ providerId           │ CRUD         │
│           │ (运行时切换)         │ (Agent 绑定)         │              │
│           └──────────────────────┼──────────────────────┘              │
│                                  │                                     │
│                                  ▼                                     │
│  ┌─────────────────────────────────────────────────────────────────┐   │
│  │                    REST API + SSE                               │   │
│  └─────────────────────────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────────────────────┘
                                  │
                                  ▼
┌─────────────────────────────────────────────────────────────────────────┐
│                    Spring Boot 3.4 后端                                │
│                                                                         │
│  ┌─────────────────────────────────────────────────────────────────┐   │
│  │              LLMProviderController (REST API)                   │   │
│  │              GET/POST/PUT/DELETE /api/llm-providers              │   │
│  └─────────────────────────────────────────────────────────────────┘   │
│                                  │                                     │
│                                  ▼                                     │
│  ┌─────────────────────────────────────────────────────────────────┐   │
│  │              LLMClientManager (核心管理器)                      │   │
│  │                                                                 │   │
│  │  ┌─────────────────┐  ┌─────────────────┐  ┌─────────────────┐ │   │
│  │  │ Provider 路由   │  │ Client 缓存     │  │ 热刷新          │ │   │
│  │  │ resolveClient() │  │ ConcurrentHashMap│  │ refreshClients()│ │   │
│  │  └─────────────────┘  └─────────────────┘  └─────────────────┘ │   │
│  └─────────────────────────────────────────────────────────────────┘   │
│                                  │                                     │
│                                  ▼                                     │
│  ┌─────────────────────────────────────────────────────────────────┐   │
│  │              LLMClient 接口 (统一抽象层)                        │   │
│  │                                                                 │   │
│  │  ┌─────────────┐ ┌─────────────┐ ┌─────────────┐ ┌─────────────┐│   │
│  │  │DeepSeekClient│ │ OpenAIClient│ │AnthropicClient│ │ OllamaClient││   │
│  │  └─────────────┘ └─────────────┘ └─────────────┘ └─────────────┘│   │
│  │  ┌─────────────┐ ┌─────────────┐                               │   │
│  │  │  MiMoClient │ │ AbstractLLM │                               │   │
│  │  └─────────────┘ │   Client    │                               │   │
│  │                  └─────────────┘                               │   │
│  └─────────────────────────────────────────────────────────────────┘   │
│                                  │                                     │
│                                  ▼                                     │
│  ┌─────────────────────────────────────────────────────────────────┐   │
│  │              LLMWebClientManager (WebClient 管理)               │   │
│  │                                                                 │   │
│  │  - 每个 Provider 独立的 WebClient 实例                          │   │
│  │  - 动态 API Key 注入                                            │   │
│  │  - 连接池管理                                                   │   │
│  └─────────────────────────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────────────────────┘
                                  │
                                  ▼
┌─────────────────────────────────────────────────────────────────────────┐
│                        LLM 平台 API                                    │
│                                                                         │
│  ┌─────────────┐ ┌─────────────┐ ┌─────────────┐ ┌─────────────┐      │
│  │ DeepSeek API│ │ OpenAI API  │ │Anthropic API│ │ Ollama API  │      │
│  └─────────────┘ └─────────────┘ └─────────────┘ └─────────────┘      │
└─────────────────────────────────────────────────────────────────────────┘
```

### 2.2 数据流

```
用户在前端切换 Provider
    ↓
CodeAssistantView.onProviderSwitch(providerCode)
    ↓
AgentSelector.saveRuntime() → 持久化到 agent_config 表
    ↓
用户发送消息
    ↓
ChatRequest.providerCode = agentRuntime.providerCode
    ↓
DeepSeekServiceImpl.prepareConversationContext()
    ↓
LLMClientManager.resolveClientByCode(providerCode)
    ↓
返回对应的 LLMClient 实例
    ↓
LLMClient.buildRequestBody() → 构建 Provider 特定格式的请求体
    ↓
LLMClient.streamChat() / blockingChat() → 调用 LLM API
    ↓
LLMClient.extractContentFromStreamChunk() → 解析 Provider 特定格式的响应
```

---

## 三、数据模型

### 3.1 llm_provider 表

```sql
CREATE TABLE IF NOT EXISTS llm_provider (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  code VARCHAR(30) NOT NULL UNIQUE COMMENT 'Provider编码：deepseek/openai/anthropic/ollama/custom',
  name VARCHAR(100) NOT NULL COMMENT '显示名称，如 DeepSeek / OpenAI / Claude',
  base_url VARCHAR(300) NOT NULL COMMENT 'API Base URL，如 https://api.deepseek.com',
  api_key VARCHAR(200) COMMENT 'API Key（可加密存储，为空时从 sys_config 兜底读取）',
  default_model VARCHAR(100) COMMENT '该 Provider 的默认模型名',
  model_list TEXT COMMENT '可用模型列表 JSON 数组，如 ["deepseek-v4-pro","deepseek-v4-flash"]',
  request_template VARCHAR(30) DEFAULT 'deepseek' COMMENT '请求模板类型：deepseek / openai / anthropic / ollama / custom',
  is_default TINYINT DEFAULT 0 COMMENT '是否为默认 Provider',
  enabled TINYINT DEFAULT 1 COMMENT '是否启用',
  sort_order INT DEFAULT 0 COMMENT '排序号',
  created_at DATETIME NOT NULL,
  updated_at DATETIME NOT NULL,
  INDEX idx_provider_code (code),
  INDEX idx_provider_enabled (enabled)
) DEFAULT CHARSET=utf8mb4 COMMENT='LLM Provider 配置表';
```

### 3.2 agent_config 表新增字段

```sql
ALTER TABLE agent_config ADD COLUMN provider_id BIGINT DEFAULT NULL COMMENT 'LLM Provider ID';
ALTER TABLE agent_config ADD COLUMN provider_code VARCHAR(30) DEFAULT NULL COMMENT 'LLM Provider Code';
ALTER TABLE agent_config ADD COLUMN character_profile TEXT DEFAULT NULL COMMENT '角色设定';
```

### 3.3 ProviderConfig 实体类

```java
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ProviderConfig {
    private Long id;
    /** Provider 编码：deepseek / openai / anthropic / ollama / custom */
    private String code;
    /** 显示名称 */
    private String name;
    /** API Base URL */
    private String baseUrl;
    /** API Key（可加密存储） */
    private String apiKey;
    /** 默认模型名称 */
    private String defaultModel;
    /** 可用模型列表（JSON数组字符串） */
    private String modelList;
    /** 请求模板类型：deepseek / openai / anthropic / ollama / custom */
    private String requestTemplate;
    /** 是否为默认 Provider */
    private Integer isDefault;
    /** 是否启用 */
    private Integer enabled;
    /** 排序号 */
    private Integer sortOrder;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
```

### 3.4 AgentConfig 实体类新增字段

```java
/** 绑定的 LLM Provider ID，关联 llm_provider.id（null 或 0 表示使用默认 Provider） */
private Long providerId;
/** 前端动态选择的 LLM Provider Code（如 deepseek/openai），运行时持久化 */
private String providerCode;
/** 角色性格配置 JSON 字符串（如 {"name":"圆圆","species":"人",...}） */
private String characterProfile;
```

---

## 四、核心组件

### 4.1 LLMClient 接口

所有 LLM Provider 都必须实现此接口，屏蔽不同平台的 API 差异。

```java
public interface LLMClient {
    /** 获取 Provider 编码（如 "deepseek"、"openai"） */
    String getProviderCode();

    /** 获取当前使用的 WebClient 实例 */
    WebClient getWebClient();

    /** 构建 API 请求体 */
    Map<String, Object> buildRequestBody(
            List<Map<String, Object>> messages,
            String model,
            Double temperature,
            String thinkingMode,
            boolean stream,
            List<Map<String, Object>> tools);

    /** 流式聊天（SSE） */
    Flux<String> streamChat(Map<String, Object> requestBody);

    /** 阻塞式聊天（非流式） */
    String blockingChat(Map<String, Object> requestBody, Duration timeout);

    /** 从 SSE 流式块中提取内容增量 */
    String extractContentFromStreamChunk(String sseChunk);

    /** 从 SSE 流式块中提取思考过程增量 */
    String extractReasoningFromStreamChunk(String sseChunk);

    /** 获取 API 端点路径（如 "/v1/chat/completions"） */
    default String getChatEndpoint() {
        return "/v1/chat/completions";
    }

    /** 从阻塞式响应中提取最终内容 */
    String extractContentFromBlockingResponse(String responseBody);
}
```

### 4.2 LLMClientManager

核心管理器，负责 Provider 注册、路由和缓存。

```java
@Slf4j
@Component
public class LLMClientManager {
    /** Provider Code → LLMClient 缓存 */
    private final Map<String, LLMClient> clientMap = new ConcurrentHashMap<>();

    /** Provider Code → ProviderConfig 缓存 */
    private final Map<String, ProviderConfig> configMap = new ConcurrentHashMap<>();

    /** 根据 Provider Code 获取 LLMClient */
    public LLMClient resolveClientByCode(String providerCode) { ... }

    /** 根据 Provider ID 获取 LLMClient */
    public LLMClient resolveClientByProviderId(Long providerId) { ... }

    /** 获取第一个可用的 LLMClient（按 sortOrder 排序） */
    public LLMClient getFirstClient() { ... }

    /** 热刷新所有 Provider */
    public synchronized void refreshClients() { ... }
}
```

### 4.3 Provider 实现类

| Provider | 实现类 | requestTemplate | 说明 |
|----------|--------|-----------------|------|
| DeepSeek | `DeepSeekClient` | `deepseek` | 兼容 OpenAI 格式，支持 thinking 参数 |
| OpenAI | `OpenAIClient` | `openai` | 标准 OpenAI API 格式 |
| Anthropic | `AnthropicClient` | `anthropic` | Claude API 格式，支持 thinking 参数 |
| Ollama | `OllamaClient` | `ollama` | 本地 Ollama 服务 |
| MiMo | `MiMoClient` | `mimo` | 小米 MiMo 模型 |
| 自定义 | `OpenAIClient` | `custom` | 最小 OpenAI 兼容模式 |

---

## 五、Provider 路由逻辑

### 5.1 路由优先级

```
前端动态 providerCode > Agent 配置 providerId > 第一个可用 Provider
```

### 5.2 路由流程图

```
DeepSeekServiceImpl.prepareConversationContext()
    ↓
    ├─ 1. 检查 ChatRequest.providerCode（前端动态切换）
    │      ↓
    │      LLMClientManager.resolveClientByCode(providerCode)
    │      ↓
    │      找到 → 返回 LLMClient
    │      ↓
    │      未找到 → 继续下一步
    │
    ├─ 2. 检查 AgentConfig.providerId（Agent 绑定）
    │      ↓
    │      LLMClientManager.resolveClientByProviderId(providerId)
    │      ↓
    │      找到 → 返回 LLMClient
    │      ↓
    │      未找到 → 继续下一步
    │
    └─ 3. 获取第一个可用 Provider（按 sortOrder 排序）
           ↓
           LLMClientManager.getFirstClient()
           ↓
           找到 → 返回 LLMClient
           ↓
           未找到 → 抛出异常 "没有可用的 LLM Provider"
```

---

## 六、前端集成

### 6.1 Provider 切换 UI

在 `CodeAssistantView.vue` 中，用户可以通过"更多设置"面板切换 Provider：

```vue
<!-- 低频：更多设置弹出面板 -->
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

### 6.2 动态模型列表

根据当前选择的 Provider 动态刷新模型列表：

```typescript
/** 根据当前 Provider 刷新模型列表 */
const refreshModelList = () => {
  const providers = providerOptions.value
  if (providers.length === 0) {
    availableModels.value = []
    return
  }

  const runtime = agentSelectorRef.value?.runtime
  // 找到当前使用的 Provider
  const prov = (runtime?.providerCode
    ? providers.find((p: ProviderConfig) => p.code === runtime.providerCode)
    : null) || providers[0]

  if (prov?.parsedModelList && prov.parsedModelList.length > 0) {
    availableModels.value = prov.parsedModelList
    // 如果当前模型不在新 Provider 列表中，自动切到默认模型
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

### 6.3 无 Provider 引导界面

当没有配置任何 Provider 时，显示引导界面：

```vue
<div v-if="providerOptions.length === 0" class="no-provider-guide">
  <div class="guide-icon">🔌</div>
  <h2 class="guide-title">尚未配置 LLM Provider</h2>
  <p class="guide-desc">AI 助手需要通过 LLM Provider 连接大模型才能工作</p>
  <a-button type="primary" size="large" class="guide-btn" @click="router.push('/config')">
    前往设置 创建 Provider
  </a-button>
</div>
```

---

## 七、REST API

### 7.1 LLMProviderController

| 方法 | 路径 | 说明 | 权限 |
|------|------|------|------|
| GET | `/api/llm-providers` | 获取所有启用的 Provider 列表 | admin |
| GET | `/api/llm-providers/{id}` | 获取单个 Provider 详情 | admin |
| POST | `/api/llm-providers` | 创建 Provider | admin |
| PUT | `/api/llm-providers/{id}` | 更新 Provider | admin |
| DELETE | `/api/llm-providers/{id}` | 删除 Provider | admin |

### 7.2 请求/响应示例

**创建 Provider**

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

**响应**

```json
{
  "code": 200,
  "message": "Provider 创建成功",
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

## 八、配置指南

### 8.1 添加新的 Provider

1. 访问 `/config` 页面（或 `/provider-config` 页面）
2. 点击"新增 Provider"按钮
3. 填写配置信息：
   - **编码**：唯一标识，如 `openai`、`anthropic`
   - **名称**：显示名称，如 `OpenAI`、`Anthropic`
   - **Base URL**：API 端点，如 `https://api.openai.com`
   - **API Key**：API 密钥（可选，为空时从系统配置读取）
   - **默认模型**：默认使用的模型名
   - **模型列表**：JSON 数组格式的可用模型列表
   - **请求模板**：选择对应的 API 格式
4. 点击"创建 Provider"

### 8.2 为 Agent 绑定 Provider

1. 访问 `/agent-config` 页面
2. 编辑目标 Agent
3. 在"LLM Provider"下拉框中选择要绑定的 Provider
4. 保存配置

### 8.3 运行时切换 Provider

1. 在聊天界面底部，点击"更多"按钮
2. 在"Provider"下拉框中选择要使用的 Provider
3. 系统会自动刷新模型列表
4. 选择的 Provider 会持久化到当前 Agent 配置

---

## 九、扩展指南

### 9.1 添加新的 Provider 实现

1. 在 `service/llm/` 目录下创建新的客户端类，继承 `AbstractLLMClient` 或直接实现 `LLMClient` 接口
2. 实现所有抽象方法：
   - `buildRequestBody()` - 构建 Provider 特定格式的请求体
   - `streamChat()` - 流式调用
   - `blockingChat()` - 阻塞调用
   - `extractContentFromStreamChunk()` - 解析流式响应
   - `extractReasoningFromStreamChunk()` - 解析思考过程
   - `extractContentFromBlockingResponse()` - 解析阻塞响应
3. 在 `LLMClientManager.createClient()` 方法中添加新的 case：

```java
case "new_provider" -> {
    var wc = webClientManager.getOrCreate(config);
    yield new NewProviderClient(wc, objectMapper, config.getCode());
}
```

4. 在前端 `ProviderConfigView.vue` 的 `templateLabel()` 和 `templateStyle()` 方法中添加新的样式映射

### 9.2 自定义请求模板

如果某个 Provider 的 API 格式与现有模板都不匹配，可以使用 `custom` 模板类型，它会使用最小的 OpenAI 兼容模式（不发送 thinking 等特有参数）。

---

## 十、已知问题与注意事项

| 问题 | 说明 | 建议 |
|------|------|------|
| API Key 安全 | 当前 API Key 明文存储在数据库中 | 后续应支持加密存储 |
| Provider 不存在 | 前端切换到已删除的 Provider 时会回退到默认 Provider | 前端应显示提示信息 |
| 模型列表同步 | 某些 Provider 的模型列表可能变化 | 后续应支持从 API 动态获取模型列表 |
| 并发请求 | 多个用户同时切换 Provider 时可能出现竞态条件 | 已使用 ConcurrentHashMap 缓存 |

---

> 📌 **文档维护约定**: 新增 LLM Provider 实现或修改 Provider 路由逻辑后，请同步更新本文档。
