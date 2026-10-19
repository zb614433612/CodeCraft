> 🌐 English Version：[🇬🇧 LLM_PROVIDER_SYSTEM_EN](./LLM_PROVIDER_SYSTEM_EN.md)
# 多 LLM Provider 支持系统

> 版本：v3.0.0 | 更新：2026-10-19 | 受众：开发者 / AI 协作伙伴
> 本文档描述 CodeCraft 的多 LLM Provider 支持系统，包括架构设计、数据模型、核心组件和使用方式。

---

## 一、系统概述

CodeCraft 支持多种 LLM 平台（DeepSeek / OpenAI / Anthropic / Ollama / MiMo / MiniMax / 火山引擎方舟等），用户可以在运行时动态切换不同的 LLM Provider，每个 Agent 也可以绑定特定的 Provider。

### 1.1 核心特性

- **多 Provider 支持**：支持 DeepSeek、OpenAI、Anthropic、Ollama、MiMo、MiniMax、火山引擎（方舟）等主流 LLM 平台
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
| MiniMax | `MiniMaxClient` | `minimax` | MiniMax API（OpenAI 兼容，thinking adaptive + reasoning_split） |
| 火山引擎（方舟） | `VolcengineArkClient` | `volcengine`（别名 `ark` / `doubao`） | 方舟（豆包及方舟上的 DeepSeek/GLM 等），OpenAI 兼容；thinking.type = enabled/disabled/auto |
| 自定义 | `OpenAIClient` | `custom` | 最小 OpenAI 兼容模式 |

### 4.4 DeepSeek 官方工具 API 联动（v2.0.3）

`DeepSeekToolsApiClient` 让 `web_search` / `web_fetch` 在「使用 DeepSeek 官方 API」时自动改走官方端点，无需用户额外配置：

| 项 | 说明 |
|----|------|
| 启用条件 | Provider `baseUrl` 主机为 `api.deepseek.com` 且 apiKey 非空（占位符 `__MUST_CONFIGURE_API_KEY__` 视为未配置） |
| 端点 | `POST /v1/tools/search`（query / count / need_content 等）、`POST /v1/tools/fetch`（url） |
| 认证 | 与 Chat Completions 共用 `Authorization: Bearer <apiKey>`，由 `LLMWebClientManager` 统一注入 |
| 路径解析 | `resolveApiPath()` 兼容 baseUrl 带 / 不带 `/v1` |
| 失败策略 | 抛异常由工具捕获后回退原链路（Bing/百度、Jina/本地），不影响工具可用性 |
| 判定依据 | `baseUrl` 域名（不是 providerCode）——第三方中转 / 自建兼容服务不会误启用 |
| 子 Agent | `AgentForkManager` 通过 `ToolContext.setProviderCode(...)` 传递，保证子 Agent 内判定一致 |

> 为什么不用 providerCode：用户可自定义 code（如 `deepseek-proxy`），而域名是「是否官方」的客观事实。

### 4.5 火山引擎（方舟）增强适配

火山方舟提供聊天之外的多项平台能力，本系统按「能力按需启用、缺少凭证自动跳过、失败自动回退」的方式适配：

| 能力 | 实现类 | 需要的配置 | 说明 |
|------|--------|-----------|------|
| 对话 / 工具调用 | `VolcengineArkClient` | 模板 `volcengine` + `apiKey`（Ark API Key） | 端点自动补齐 `/api/v3/chat/completions`（baseUrl 带不带 `/api/v3` 都可）；`thinking.type` 映射 `enabled/disabled/auto`；`reasoning_effort` 仅对 `deepseek-v4` 系列下发，避免其他模型 400 |
| 图片识别（视觉理解） | `FileVisionService` + `image_url` 内容块 | 模型具备视觉能力 | 方舟只接受 `detail = low/high/xhigh`，适配器自动把 OpenAI 风格的 `auto` 归一为 `high`；`doubao-seed` 系列支持图像输入，方舟上的 `deepseek-*` 文本模型不支持（前置拦截并给出明确提示） |
| 余额查询 | `VolcengineBillingClient` + `ProviderBalanceService` | `extraConfig` 中 `accessKey` / `secretKey` | 费用中心 OpenAPI `QueryBalanceAcct`（V4 签名，POST `billing.volcengineapi.com`）；返回可用余额/现金余额/信控额度/冻结/欠费；未配置 AK/SK 时仅该能力不可用 |
| 联网搜索 | `VolcengineSearchApiClient` | `extraConfig` 中 `searchApiKey`，或环境变量 `VOLCENGINE_SEARCH_API_KEY` / `WEB_SEARCH_API_KEY` | 豆包搜索 Custom 版（`POST https://open.feedcoopapi.com/search_api/web_search`），返回结构化结果（Title/Url/Summary/Content/AuthInfoLevel 等）；`web_search` 工具优先使用，失败或无结果自动回退 Bing/百度 |
| 网页抓取 | `VolcengineSearchApiClient#fetchByUrl` | 同上 | 以 URL 的域名 + 路径关键词检索并命中同一 URL 的正文（best-effort），未命中回退 Jina Reader / 本地抓取 |

配置示例（Provider 管理页也可直接填表）：

```sql
UPDATE llm_provider SET
  request_template = 'volcengine',
  base_url         = 'https://ark.cn-beijing.volces.com/api/v3',
  api_key          = '<ARK_API_KEY>',
  default_model    = 'doubao-seed-2-1-pro-260628',
  extra_config     = '{"accessKey":"AK...","secretKey":"SK...","searchApiKey":"..."}'
WHERE code = 'volcengine';
```

> `extra_config` 是为平台增强凭证新增的可选列（启动时自动 `ALTER TABLE` 补齐，旧库无需手工迁移）。
> 列表接口对 `accessKey` / `secretKey` / `searchApiKey` 做掩码返回，提交含掩码时视为「未修改」。
> 联网搜索的 Key 与方舟 Ark Key **不通用**（需在联网搜索控制台单独创建）；未配置时该能力静默跳过，不影响对话。
> 所有增强能力的能力判定都基于「请求模板 + 实际域名 + 凭证有效性」，与用户自定义的 providerCode 名称无关。

### 4.6 MiniMax / 小米 MiMo 增强能力

> 结论先说：**MiniMax 三件套（搜索 / 图片识别 / 额度）都能适配；MiMo 只能适配图片识别与（Cookie 方式的）余额；两家都没有公开的「网页抓取」接口。**

| 平台 | 能力 | 支持情况 | 实现 / 配置 |
|------|------|----------|-------------|
| MiniMax | 联网搜索 | ✅ 支持 | `MiniMaxCodingPlanClient` → `POST {host}/v1/coding_plan/search`（body `{"q":"..."}`），返回 `organic[title/link/snippet/date]`；`web_search` 优先使用，失败或无结果回退 Bing/百度 |
| MiniMax | 图片识别 | ✅ 支持（两条路径） | ① 模型侧：`MiniMax-M3` / VL 系列走 chat 多模态 `image_url` 块；② 独立接口：`POST {host}/v1/coding_plan/vlm`（prompt + image_url，支持公网 URL 或 base64，JPEG/PNG/WebP） |
| MiniMax | 余额 / 额度 | ⚠️ 仅「套餐额度」 | `GET {host}/v1/token_plan/remains`（Coding Plan / Token Plan Key），展示剩余百分比、剩余次数、重置时间（`balanceType=quota`）；官方未开放「现金余额」OpenAPI，故前端以「额度 xx%」展示 |
| MiniMax | 网页抓取 | ❌ 无官方抓取接口 | 保持 Jina Reader / 本地抓取链路 |
| MiMo（小米） | 图片识别 | ✅ 支持 | `mimo-v2.5` / `mimo-v2.5-pro` 原生多模态（图片/音频/视频），走 chat `image_url` 块；适配器会剔除 MiMo 协议未声明的 `detail` 字段；早期纯文本模型前置拦截 |
| MiMo（小米） | 余额 | ⚠️ 需控制台 Cookie | MiMo 未开放余额 OpenAPI（推理 Key `sk-` 无法查询）：`GET https://platform.xiaomimimo.com/api/v1/balance` + 扩展配置 `{"mimoCookie":"..."}`，Cookie 约 24 小时有效，过期需重新粘贴并给出明确提示 |
| MiMo（小米） | 联网搜索 / 抓取 | ❌ 无官方接口 | 保持 Bing/百度 + Jina/本地链路 |

配置示例（Provider 管理页「扩展配置」）：

```json
// MiniMax（搜索 + 额度；apiHost 可省略，默认跟随 baseUrl 域名，国内 api.minimaxi.com / 国际 api.minimax.io）
{"codingPlanKey":"sk-cp-...","apiHost":"https://api.minimaxi.com"}

// 小米 MiMo（余额，浏览器 F12 复制 balance 请求的 Cookie）
{"mimoCookie":"api-platform_serviceToken=...; userId=..."}
```

> - MiniMax 的搜索 / 额度接口配套 **Coding Plan / Token Plan Key**，普通模型 Key 可能被拒（返回 `base_resp.status_code` 非 0，如 `2062 no active token plan`）；失败时搜索自动回退、额度仅不展示，不影响对话。
> - MiniMax `current_interval_usage_count` 语义为「**剩余**」而非已用（社区实测），解析时优先用 `current_interval_remaining_percent`，计数做兜底。
> - MiMo 的 Cookie 只有约 24 小时有效期，属于平台限制；失效时前端会提示重新粘贴，而不是静默失败。

### 4.7 通义千问（阿里云百炼 DashScope / QwenCloud）

| 能力 | 支持情况 | 实现 / 配置 |
|------|----------|-------------|
| 对话 / 工具调用 | ✅ 支持 | `QwenClient`，OpenAI 兼容端点 `{baseUrl}/compatible-mode/v1/chat/completions`（端点自适应：裸域名自动补 `/compatible-mode/v1`，已写全则原样使用） |
| 思考模式 | ✅ 支持 | Qwen 扩展参数：`enable_thinking`（`non-thinking→false`、`thinking / thinking_max→true`）+ `thinking_budget`（thinking_max 默认 8192，可用 `extraConfig.thinkingBudget` 覆盖；留空则不下发，交给模型默认策略） |
| 图片识别 | ✅ 支持 | Qwen-VL / Qwen3-VL / Omni 系列走 chat 多模态 `image_url` 块；`qwen-plus` / `qwen-max` 等纯文本模型前置拦截并给出提示 |
| 对话级联网搜索 | ✅ 可选 | `extraConfig` 配 `{"enableSearch": true}` → 请求体下发 `enable_search: true` + `search_options`（OpenAI 的 `web_search_options` DashScope 不支持） |
| 余额查询 | ✅ 支持 | 阿里云费用中心 `QueryAccountBalance`（BssOpenApi 2017-12-14，RPC 风格 HMAC-SHA1 签名），需 `extraConfig` 的 `accessKey` / `secretKey`（账号级 AK/SK，与百炼 API Key 不同） |
| 结构化网页搜索 | ❌ 无 | Qwen 的 `enable_search` 是"对话级"能力（模型自行决定检索并直接作答），不是"给定 query 返回结果列表"的结构化搜索接口 → `web_search` 工具仍走 Bing/百度 |

配置示例（Provider 管理页「扩展配置」）：

```json
{
  "enableSearch": true,          // 可选：对话级联网
  "thinkingBudget": 4096,        // 可选：思考预算上限
  "accessKey": "LTAI...",        // 余额查询：阿里云 AK
  "secretKey": "..."             // 余额查询：阿里云 SK
}
```

> 百炼 API Key 按地域绑定：用北京地域的 Key 调新加坡 endpoint 会返回 401 `invalid_api_key`；
> Base URL 建议使用工作空间专属域名 `https://{WorkspaceId}.cn-beijing.maas.aliyuncs.com/compatible-mode/v1`（旧域名 `https://dashscope.aliyuncs.com` 仍可用）。

### 4.8 OpenAI / Anthropic「公共契约」适配器

OpenAI 与 Anthropic 两个模板定位为**协议公共契约**：只要服务符合对应协议规范，就能用同一模板接入
（官方、Azure、OpenRouter 与各类中转站、vLLM / LM Studio / One-API 自建网关、GLM / DeepSeek / MiniMax 的 `/anthropic` 兼容层等）。

| 维度 | OpenAI（`openai` / `custom`） | Anthropic（`anthropic`） |
|------|------------------------------|--------------------------|
| 端点解析 | 裸域名 → `/v1/chat/completions`；已含 `/v1` → 不重复拼接；已写全 `/chat/completions` → 原样使用（绝对 URL，避免 baseUrl 路径被覆盖） | 裸域名 → `/v1/messages`；`/anthropic` 兼容层 → `/anthropic/v1/messages`；已写全 `/messages` → 原样使用 |
| 参数分级 | **仅官方**（`api.openai.com`）下发 `max_tokens`（8192）与 `reasoning_effort`；第三方兼容服务不下发（各家上限与支持度不一，交给服务端默认，避免 400） | `thinking` **仅在开启时下发**（很多兼容层不认识该字段会 400）；`max_tokens` 默认 8192，可用 `extraConfig.maxTokens` 覆盖，思考模式自动放宽到 `budget + 8192` 以满足 `max_tokens > budget_tokens` |
| 模型名归一 | `openai/gpt-4o`、`azure/o3-mini` 这类前缀写法按**最后一段**判定，避免把中转站命名误判成推理模型 | — |
| 图片 | 直接透传 `image_url` 块 | **自动协议转换**：`image_url` → `image/source`（`data:` URL → `base64` + `media_type`；http(s) → `url`） |
| 工具 | `tools` 原样透传（OpenAI 函数调用格式） | `tools` → `{name, description, input_schema}`，并移除部分兼容层不支持的 `tool_choice` |
| 推理模型 | `o1 / o3 / o4 / gpt-5` 系列自动改用 `max_completion_tokens` 并移除 `temperature` | — |

> 这两个适配器的能力判定与参数下发都以「协议 + 实际 baseUrl」为准，不绑定具体厂商，
> 因此可以放心把任意兼容服务挂到这两个模板上；厂商专有能力（图片理解接口、余额、结构化搜索等）交给各自的专用模板。

### 4.9 GPT（OpenAI 官方专精）vs OpenAI（公共契约）

两个模板都跑 OpenAI Chat Completions 协议，但**职责严格分离**，别配错：

| 维度 | `gpt`（官方专精，`GptClient`） | `openai`（公共契约，`OpenAIClient`） |
|------|-------------------------------|--------------------------------------|
| 适用对象 | OpenAI 官方 GPT / o 系列（`api.openai.com` 或官方代理） | Azure / OpenRouter / 各类中转站 / vLLM / LM Studio / One-API 等**任意兼容服务** |
| 端点 | 默认 `https://api.openai.com/v1/chat/completions` | 任意 baseUrl（含中转站自定义路径，已写全则原样使用） |
| 输出上限 | **统一下发** `max_completion_tokens`（普通 8192 / 推理 16384；外部注入的 `max_tokens` 自动转换） | **不下发**（各家上限未知，交给服务端默认，避免 400） |
| `reasoning_effort` | **按模型精细下发**：GPT-5 支持 `none`/`minimal`；o 系列不支持 `none`（故 non-thinking 时不下发）；`thinking→medium`、`thinking_max→high`，也支持直接传 `low`/`high`/`xhigh` | 不下发（第三方支持度不一） |
| `temperature` | 推理模型 或 开启推理（非 `none`）时自动移除 | 仅推理模型移除 |
| `stream_options` | 流式自动带 `{"include_usage": true}` | 不添加 |
| 模型名归一 | `openai/gpt-5`、`azure/gpt-5` 按最后一段判定 | 同左 |
| 典型误用 | 把**第三方中转站**配到 `gpt` → 可能因 `max_completion_tokens`/`reasoning_effort`/`stream_options` 报 400 | 把**官方 GPT** 配到 `openai` → 失去推理档位控制，且不会统计流式用量 |

> 官方侧有两个容易踩的坑：① `max_tokens` 在新接口中已废弃，GPT-5 / o 系列会直接 400，必须用 `max_completion_tokens`；
> ② 推理模型与「开启了 reasoning_effort（非 none）」的请求都**不接受 `temperature`**。

### 4.10 适配器选型总表（维护必读）

| requestTemplate | 适配器类 | 适用对象 | 不要用于 |
|-----------------|----------|----------|----------|
| `deepseek` | `DeepSeekClient` | DeepSeek 官方 API（`thinking.type` / `reasoning_effort` 原生语义） | 方舟上的 DeepSeek（用 `volcengine`）、中转站（用 `openai`） |
| `volcengine` / `ark` / `doubao` | `VolcengineArkClient` | 火山方舟（豆包及方舟托管模型），`thinking.type=auto/enabled/disabled` | DeepSeek 官方、第三方兼容中转站 |
| `qwen` / `dashscope` / `tongyi` | `QwenClient` | 阿里云百炼千问（含 Qwen-VL），`enable_thinking` / `enable_search` | 官方 GPT/o 系列（用 `gpt`）、其他兼容服务（用 `openai`） |
| `gpt` / `gpt-official` / `chatgpt` | `GptClient` | OpenAI 官方 GPT / o 系列：`max_completion_tokens` + `reasoning_effort` 全档位 + `stream_options` | 任何第三方兼容服务 |
| `openai` | `OpenAIClient` | **公共契约**：任意 OpenAI 兼容服务 | 官方 GPT 需要推理档位控制时（用 `gpt`） |
| `anthropic` | `AnthropicClient` | **公共契约**：Anthropic 官方 / Claude 中转 / GLM、DeepSeek、MiniMax 的 `/anthropic` 兼容层 | OpenAI 协议服务 |
| `ollama` | `OllamaClient` | 本机 / 内网 Ollama（`/api/chat`），离线与数据不出网 | 云端 OpenAI 兼容 API（vLLM / LM Studio 的 `/v1` 端点用 `openai`） |
| `mimo` / `xiaomi-mimo` | `MiMoClient` | 小米 MiMo 开放平台 v2.5 系列（多模态） | 其他兼容服务 |
| `minimax` / `minimax-cn` / `minimax-intl` | `MiniMaxClient` | MiniMax OpenAI 兼容端点（M3 / M2.x） | MiniMax 的 `/anthropic` 端点（用 `anthropic`） |
| `custom` | `OpenAIClient`（最小兼容模式） | 未知网关兜底 | 能匹配到具体模板时优先用具体模板（参数更准确） |

> 每个适配器类的 Javadoc 顶部都有「适用范围（选型指引）」块（适用 / 不适用 / 专属能力），
> 新增或调整 Provider 时先看类注释与本节表格；该总表与 `LLMClientManager` 类注释保持同步。

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

### 7.2 ProviderBalanceController

| 方法 | 路径 | 说明 | 权限 |
|------|------|------|------|
| GET | `/api/llm-providers/{code}/balance` | 查询 Provider 账户余额（仅 DeepSeek 支持；聊天页面在进入页面 / 每次对话结束后调用，用于展示余额） | 登录用户 |

**响应示例（DeepSeek）**

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

### 7.3 请求/响应示例

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
