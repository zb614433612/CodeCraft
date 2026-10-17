package com.example.agentdeepseek.service.llm;

import com.example.agentdeepseek.mapper.ProviderConfigMapper;
import com.example.agentdeepseek.model.entity.ProviderConfig;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * LLM 客户端管理器
 *
 * <h3>模板选型总览（新增 Provider 时先看这张表）</h3>
 * <table border="1">
 *   <tr><th>requestTemplate</th><th>适配器</th><th>适用对象</th><th>不要用于</th></tr>
 *   <tr>
 *     <td>{@code deepseek}</td><td>{@link DeepSeekClient}</td>
 *     <td>DeepSeek 官方 API（thinking.type / reasoning_effort 原生语义）</td>
 *     <td>方舟上的 DeepSeek（用 {@code volcengine}）、中转站（用 {@code openai}）</td>
 *   </tr>
 *   <tr>
 *     <td>{@code volcengine} / {@code ark} / {@code doubao}</td><td>{@link VolcengineArkClient}</td>
 *     <td>火山方舟（豆包及方舟托管模型），thinking.type=auto/enabled/disabled</td>
 *     <td>DeepSeek 官方 API、任意第三方兼容中转站</td>
 *   </tr>
 *   <tr>
 *     <td>{@code qwen} / {@code dashscope} / {@code tongyi}</td><td>{@link QwenClient}</td>
 *     <td>阿里云百炼千问（含 Qwen-VL），enable_thinking / enable_search 扩展参数</td>
 *     <td>官方 GPT/o 系列（用 {@code gpt}）、其他兼容服务（用 {@code openai}）</td>
 *   </tr>
 *   <tr>
 *     <td>{@code gpt} / {@code gpt-official} / {@code chatgpt}</td><td>{@link GptClient}</td>
 *     <td>OpenAI 官方 GPT / o 系列：max_completion_tokens + reasoning_effort 全档位 + stream_options</td>
 *     <td>任何第三方兼容服务（它们常不支持这些官方参数，请用 {@code openai}）</td>
 *   </tr>
 *   <tr>
 *     <td>{@code openai}</td><td>{@link OpenAIClient}</td>
 *     <td><b>公共契约</b>：Azure / OpenRouter / 中转站 / vLLM / LM Studio / One-API 等任意 OpenAI 兼容服务</td>
 *     <td>OpenAI 官方需要 reasoning_effort 档位时（用 {@code gpt}）</td>
 *   </tr>
 *   <tr>
 *     <td>{@code anthropic}</td><td>{@link AnthropicClient}</td>
 *     <td><b>公共契约</b>：Anthropic 官方 / Claude 中转 / GLM、DeepSeek、MiniMax 的 {@code /anthropic} 兼容层</td>
 *     <td>OpenAI Chat Completions 协议的服务</td>
 *   </tr>
 *   <tr>
 *     <td>{@code ollama}</td><td>{@link OllamaClient}</td>
 *     <td>本机 / 内网 Ollama（{@code /api/chat}），离线与数据不出网场景</td>
 *     <td>云端 OpenAI 兼容 API（vLLM / LM Studio 的 /v1 端点用 {@code openai}）</td>
 *   </tr>
 *   <tr>
 *     <td>{@code mimo} / {@code xiaomi-mimo}</td><td>{@link MiMoClient}</td>
 *     <td>小米 MiMo 开放平台 v2.5 系列（多模态）</td>
 *     <td>其他兼容服务</td>
 *   </tr>
 *   <tr>
 *     <td>{@code minimax} / {@code minimax-cn} / {@code minimax-intl}</td><td>{@link MiniMaxClient}</td>
 *     <td>MiniMax OpenAI 兼容端点（M3 / M2.x）</td>
 *     <td>MiniMax 的 {@code /anthropic} 端点（用 {@code anthropic}）</td>
 *   </tr>
 *   <tr>
 *     <td>{@code custom}</td><td>{@link OpenAIClient}（最小兼容模式）</td>
 *     <td>未知网关兜底</td>
 *     <td>能匹配到具体模板时优先使用具体模板（参数更准确）</td>
 *   </tr>
 * </table>
 *
 * <h3>核心职责</h3>
 * <ol>
 *   <li><b>Provider 注册</b>：启动时扫描 llm_provider 表，为每个启用的 Provider 创建 LLMClient 实例</li>
 *   <li><b>Provider 路由</b>：根据 agentConfigId → providerId → Provider → LLMClient 完成路由</li>
 *   <li><b>API Key 解析</b>：Provider 自身 Key → sys_config 兜底 → 配置文件 fallback</li>
 *   <li><b>默认回退</b>：Agent 未绑定 Provider 时使用默认 Provider</li>
 * </ol>
 *
 * <h3>使用示例</h3>
 * <pre>{@code
 * // 通过 AgentConfig 获取对应的 LLM 客户端
 * LLMClient client = llmClientManager.resolveClient(agentConfigId);
 *
 * // 构建请求
 * Map<String, Object> body = client.buildRequestBody(messages, model, temp, thinking, stream, tools);
 *
 * // 流式调用
 * Flux<String> stream = client.streamChat(body);
 *
 * // 阻塞调用
 * String resp = client.blockingChat(body, Duration.ofSeconds(60));
 * }</pre>
 */
@Slf4j
@Component
public class LLMClientManager {

    private final ProviderConfigMapper providerConfigMapper;
    private final LLMWebClientManager webClientManager;
    private final ObjectMapper objectMapper;
    private final JdbcTemplate jdbcTemplate;

    /** Provider Code → LLMClient 缓存 */
    private final Map<String, LLMClient> clientMap = new ConcurrentHashMap<>();

    /** Provider Code → ProviderConfig 缓存 */
    private final Map<String, ProviderConfig> configMap = new ConcurrentHashMap<>();

    /** 第一个 Provider Code（按 sortOrder 排序，用于默认选择） */
    private volatile String firstProviderCode = null;

    /** 第一个 Provider ID */
    private volatile Long firstProviderId = null;

    @Autowired
    public LLMClientManager(ProviderConfigMapper providerConfigMapper,
                            LLMWebClientManager webClientManager,
                            ObjectMapper objectMapper,
                            JdbcTemplate jdbcTemplate) {
        this.providerConfigMapper = providerConfigMapper;
        this.webClientManager = webClientManager;
        this.objectMapper = objectMapper;
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 启动时初始化：建表 + 初始数据 + 加载所有 Provider
     */
    @PostConstruct
    public void init() {
        try {
            initTable();
            refreshClients();
            log.info("LLMClientManager 初始化完成，共 {} 个 Provider，首个: [{}]",
                    clientMap.size(), firstProviderCode != null ? firstProviderCode : "无");
        } catch (Exception e) {
            log.warn("LLMClientManager 初始化失败: {}", e.getMessage());
        }
    }

    /**
     * 创建 llm_provider 表并插入初始数据（兼容 H2 和 MySQL）
     * <p>
     * 仅做 fallback 兜底：正常情况下由 AgentConfigServiceImpl.initLLMProviderTable() 建表。
     * 如果该方法已执行过，此处的 CREATE TABLE IF NOT EXISTS 无副作用。
     * </p>
     */
    private void initTable() {
        try {
            jdbcTemplate.execute("CREATE TABLE IF NOT EXISTS llm_provider (" +
                    "id BIGINT AUTO_INCREMENT PRIMARY KEY, " +
                    "code VARCHAR(30) NOT NULL UNIQUE, " +
                    "name VARCHAR(100) NOT NULL, " +
                    "base_url VARCHAR(300) NOT NULL, " +
                    "api_key VARCHAR(200), " +
                    "default_model VARCHAR(100), " +
                    "model_list TEXT, " +
                    "extra_config TEXT, " +
                    "request_template VARCHAR(30) DEFAULT 'deepseek', " +
                    "is_default TINYINT DEFAULT 0, " +
                    "enabled TINYINT DEFAULT 1, " +
                    "sort_order INT DEFAULT 0, " +
                    "created_at DATETIME NOT NULL, " +
                    "updated_at DATETIME NOT NULL" +
                    ")");
        } catch (Exception e) {
            log.warn("LLMClientManager: initTable fallback 失败: {}", e.getMessage());
        }
        // 兼容旧表：extended 配置列（火山引擎 AK/SK、联网搜索 API Key 等），确保 refreshClients 前列已存在
        try {
            jdbcTemplate.execute("ALTER TABLE llm_provider ADD COLUMN extra_config TEXT");
        } catch (Exception e) {
            log.debug("LLMClientManager: extra_config 列已存在或添加失败: {}", e.getMessage());
        }
    }

    /**
     * 从数据库重新加载所有 Provider 并创建/更新 Client（热刷新）
     */
    public synchronized void refreshClients() {
        // ★ 清除所有旧 WebClient 缓存，确保 baseUrl/apiKey/认证头变更后重建
        webClientManager.evictAll();

        List<ProviderConfig> providers;
        try {
            providers = providerConfigMapper.selectAllEnabled();
        } catch (Exception e) {
            log.warn("无法查询 Provider 列表: {}", e.getMessage());
            return;
        }

        if (providers.isEmpty()) {
            log.warn("没有启用的 Provider");
            this.clientMap.clear();
            this.configMap.clear();
            this.firstProviderCode = null;
            this.firstProviderId = null;
            return;
        }

        Map<String, LLMClient> newClients = new ConcurrentHashMap<>();
        Map<String, ProviderConfig> newConfigs = new ConcurrentHashMap<>();

        boolean isFirst = true;
        for (ProviderConfig config : providers) {
            // 检查 baseUrl 必填
            if (config.getBaseUrl() == null || config.getBaseUrl().isEmpty()) {
                log.warn("Provider [{}] baseUrl 为空，跳过", config.getCode());
                continue;
            }
            // 默认 requestTemplate
            if (config.getRequestTemplate() == null || config.getRequestTemplate().isEmpty()) {
                config.setRequestTemplate(config.getCode());
            }

            newConfigs.put(config.getCode(), config);
            // 按 sortOrder 排序后的第一个作为"首选 Provider"
            if (isFirst) {
                firstProviderCode = config.getCode();
                firstProviderId = config.getId();
                isFirst = false;
            }

            // 创建或复用 WebClient
            LLMClient client = createClient(config);
            newClients.put(config.getCode(), client);
            log.info("注册 LLM Provider: [{}] baseUrl={}, defaultModel={}, template={}",
                    config.getCode(), config.getBaseUrl(), config.getDefaultModel(), config.getRequestTemplate());
        }

        this.clientMap.clear();
        this.clientMap.putAll(newClients);
        this.configMap.clear();
        this.configMap.putAll(newConfigs);

        // 附加 API Key resolver
        webClientManager.setApiKeyResolver(this::resolveApiKey);
    }

    // ==================== Provider 路由 ====================

    /**
     * 根据 Agent 的 providerId 解析 LLMClient
     * <p>
     * 解析链：agentConfig.providerId → ProviderConfig → LLMClient
     * 如果 Agent 未绑定 Provider 或 Provider 不存在，回退到默认 Provider
     * </p>
     *
     * @param providerId Provider ID（可从 AgentConfig 的 providerId 字段获取）
     * @return 对应的 LLMClient
     */
    public LLMClient resolveClientByProviderId(Long providerId) {
        if (providerId != null) {
            try {
                ProviderConfig config = providerConfigMapper.selectById(providerId);
                if (config != null && config.getEnabled() != null && config.getEnabled() == 1) {
                    return getOrCreateClient(config);
                }
            } catch (Exception e) {
                log.warn("根据 providerId={} 查询 Provider 失败: {}", providerId, e.getMessage());
            }
        }
        // 没有可用的 Provider，返回 null
        return null;
    }

    /**
     * 根据 Provider Code 获取 LLMClient
     */
    public LLMClient resolveClientByCode(String providerCode) {
        if (providerCode != null && !providerCode.isEmpty()) {
            LLMClient client = clientMap.get(providerCode);
            if (client != null) {
                return client;
            }
            // 尝试从 DB 按 code 查询
            try {
                ProviderConfig config = providerConfigMapper.selectByCode(providerCode);
                if (config != null) {
                    return getOrCreateClient(config);
                }
            } catch (Exception e) {
                log.warn("根据 code={} 查询 Provider 失败: {}", providerCode, e.getMessage());
            }
        }
        return getFirstClient();
    }

    /**
     * 获取第一个可用的 LLMClient（按 sortOrder 排序）
     */
    public LLMClient getFirstClient() {
        if (firstProviderCode != null) {
            LLMClient client = clientMap.get(firstProviderCode);
            if (client != null) {
                return client;
            }
        }
        // 如果 clientMap 不为空，返回第一个注册的
        if (!clientMap.isEmpty()) {
            return clientMap.values().iterator().next();
        }
        // 最后的 fallback：创建 DeepSeek 兜底客户端
        log.warn("没有可用的 Provider，使用内置 DeepSeek 兜底客户端");
        return clientMap.computeIfAbsent("deepseek", code -> {
            ProviderConfig config;
            try {
                config = providerConfigMapper.selectByCode("deepseek");
            } catch (Exception e) {
                config = null;
            }
            if (config == null) {
                config = createFallbackDeepSeekConfig();
            }
            return createClient(config);
        });
    }

    /** @deprecated 使用 {@link #getFirstClient()} 代替 */
    public LLMClient getDefaultClient() {
        return getFirstClient();
    }

    /** 获取第一个 Provider ID */
    public Long getFirstProviderId() {
        return firstProviderId;
    }

    /** @deprecated 使用 {@link #getFirstProviderId()} 代替 */
    public Long getDefaultProviderId() {
        return firstProviderId;
    }

    /** 获取第一个 Provider Code */
    public String getFirstProviderCode() {
        return firstProviderCode;
    }

    /** @deprecated 使用 {@link #getFirstProviderCode()} 代替 */
    public String getDefaultProviderCode() {
        return firstProviderCode;
    }

    // ==================== Provider 配置访问 ====================

    /**
     * 获取 Provider 完整配置
     */
    public ProviderConfig getProviderConfig(String providerCode) {
        return configMap.get(providerCode);
    }

    /**
     * 获取 Provider 的可用模型列表
     */
    public String getDefaultModel(String providerCode) {
        ProviderConfig config = configMap.get(providerCode);
        return config != null ? config.getDefaultModel() : "deepseek-v4-pro";
    }

    /**
     * 获取 Provider 的 Base URL
     */
    public String getBaseUrl(String providerCode) {
        ProviderConfig config = configMap.get(providerCode);
        return config != null ? config.getBaseUrl() : "https://api.deepseek.com";
    }

    /**
     * 列出所有已注册的 Provider Code
     */
    public java.util.Set<String> getRegisteredProviders() {
        return clientMap.keySet();
    }

    // ==================== 内部方法 ====================

    /**
     * 按需获取或创建 Client
     */
    private LLMClient getOrCreateClient(ProviderConfig config) {
        return clientMap.computeIfAbsent(config.getCode(), code -> {
            configMap.put(code, config);
            return createClient(config);
        });
    }

    /**
     * 创建 Client 实例
     */
    private LLMClient createClient(ProviderConfig config) {
        String template = config.getRequestTemplate();
        if (template == null) template = config.getCode();

        return switch (template.toLowerCase()) {
            case "deepseek" -> {
                var wc = webClientManager.getOrCreate(config);
                yield new DeepSeekClient(wc, objectMapper, config.getCode());
            }
            case "gpt", "gpt-official", "chatgpt" -> {
                // OpenAI 官方 GPT / o 系列专用：max_completion_tokens + reasoning_effort + stream_options
                // 与 openai（公共契约）区分：这里下发官方专有参数，只适合官方或官方代理
                var wc = webClientManager.getOrCreate(config);
                yield new GptClient(wc, objectMapper, config.getCode(), config.getBaseUrl());
            }
            case "openai" -> {
                // 公共契约：任意 OpenAI 兼容服务（Azure / 中转站 / 自建网关），不下发厂商专有参数
                var wc = webClientManager.getOrCreate(config);
                yield new OpenAIClient(wc, objectMapper, config.getCode(), config.getBaseUrl());
            }
            case "anthropic" -> {
                // 公共契约：任意 Anthropic Messages 兼容服务（官方 / Claude 兼容层 / 中转站）
                var wc = webClientManager.getOrCreate(config);
                yield new AnthropicClient(wc, objectMapper, config.getCode(), config.getBaseUrl(),
                        readExtraInt(config, "maxTokens"));
            }
            case "ollama" -> {
                var wc = webClientManager.getOrCreate(config);
                yield new OllamaClient(wc, objectMapper, config.getCode());
            }
            case "custom" -> {
                // 自定义 = 最小 OpenAI 兼容模式（不发送 thinking 等特有参数）
                log.info("自定义 Provider [{}]，使用 OpenAI 兼容基础模式", config.getCode());
                var wc = webClientManager.getOrCreate(config);
                yield new OpenAIClient(wc, objectMapper, config.getCode(), config.getBaseUrl());
            }
            case "mimo" -> {
                var wc = webClientManager.getOrCreate(config);
                yield new MiMoClient(wc, objectMapper, config.getCode());
            }
            case "minimax" -> {
                var wc = webClientManager.getOrCreate(config);
                yield new MiniMaxClient(wc, objectMapper, config.getCode());
            }
            case "qwen", "dashscope", "tongyi" -> {
                // 通义千问（阿里云百炼 DashScope / QwenCloud），OpenAI 兼容 + Qwen 扩展参数
                var wc = webClientManager.getOrCreate(config);
                yield new QwenClient(wc, objectMapper, config.getCode(), config.getBaseUrl(),
                        readExtraBoolean(config, "enableSearch"),
                        readExtraInt(config, "thinkingBudget"));
            }
            case "volcengine", "ark", "doubao" -> {
                // 火山引擎方舟（豆包 / 方舟上的 DeepSeek、GLM、Kimi 等模型），OpenAI 兼容协议
                var wc = webClientManager.getOrCreate(config);
                yield new VolcengineArkClient(wc, objectMapper, config.getCode(), config.getBaseUrl());
            }
            default -> {
                log.warn("未知的 request_template: {}，回退到 DeepSeekClient", template);
                var wc = webClientManager.getOrCreate(config);
                yield new DeepSeekClient(wc, objectMapper, config.getCode());
            }
        };
    }

    // ==================== 扩展配置读取（extraConfig） ====================

    /** 读取扩展配置中的布尔开关（缺省 false） */
    private boolean readExtraBoolean(ProviderConfig config, String field) {
        String value = readExtraValue(config, field);
        return value != null && ("true".equalsIgnoreCase(value.trim()) || "1".equals(value.trim()));
    }

    /** 读取扩展配置中的整数字段（缺省 null） */
    private Integer readExtraInt(ProviderConfig config, String field) {
        String value = readExtraValue(config, field);
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (Exception e) {
            log.warn("Provider [{}] 扩展配置字段 {} 不是合法整数: {}", config.getCode(), field, value);
            return null;
        }
    }

    /** 读取扩展配置中的字符串字段（非法 JSON 视为未配置） */
    private String readExtraValue(ProviderConfig config, String field) {
        String extraConfig = config.getExtraConfig();
        if (extraConfig == null || extraConfig.isBlank()) {
            return null;
        }
        try {
            JsonNode node = objectMapper.readTree(extraConfig);
            JsonNode value = node.get(field);
            return value == null || value.isNull() ? null : value.asText();
        } catch (Exception e) {
            log.warn("Provider [{}] 扩展配置不是合法 JSON，已忽略: {}", config.getCode(), e.getMessage());
            return null;
        }
    }

    /**
     * 动态 API Key 解析器
     * <p>
     * Provider 自身配置的 apiKey 由 LLMWebClientManager 读取，
     * 此方法仅作为动态解析的扩展点（可被覆盖以支持其他 Key 来源）。
     * </p>
     */
    private String resolveApiKey(String providerCode) {
        return null;
    }

    /**
     * 创建 Fallback 的 DeepSeek ProviderConfig（表不存在或完全没有配置时使用）
     */
    private ProviderConfig createFallbackDeepSeekConfig() {
        ProviderConfig config = new ProviderConfig();
        config.setId(1L);
        config.setCode("deepseek");
        config.setName("DeepSeek");
        config.setBaseUrl("https://api.deepseek.com");
        config.setDefaultModel("deepseek-v4-pro");
        config.setModelList("[\"deepseek-v4-pro\",\"deepseek-v4-flash\"]");
        config.setRequestTemplate("deepseek");
        config.setIsDefault(0);
        config.setEnabled(1);
        config.setSortOrder(1);
        return config;
    }
}
