package com.example.agentdeepseek.service.llm;

import com.example.agentdeepseek.mapper.ProviderConfigMapper;
import com.example.agentdeepseek.model.entity.ProviderConfig;
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
            case "openai" -> {
                var wc = webClientManager.getOrCreate(config);
                yield new OpenAIClient(wc, objectMapper, config.getCode());
            }
            case "anthropic" -> {
                var wc = webClientManager.getOrCreate(config);
                yield new AnthropicClient(wc, objectMapper, config.getCode());
            }
            case "ollama" -> {
                var wc = webClientManager.getOrCreate(config);
                yield new OllamaClient(wc, objectMapper, config.getCode());
            }
            case "custom" -> {
                // 自定义 = 最小 OpenAI 兼容模式（不发送 thinking 等特有参数）
                log.info("自定义 Provider [{}]，使用 OpenAI 兼容基础模式", config.getCode());
                var wc = webClientManager.getOrCreate(config);
                yield new OpenAIClient(wc, objectMapper, config.getCode());
            }
            case "mimo" -> {
                var wc = webClientManager.getOrCreate(config);
                yield new MiMoClient(wc, objectMapper, config.getCode());
            }
            default -> {
                log.warn("未知的 request_template: {}，回退到 DeepSeekClient", template);
                var wc = webClientManager.getOrCreate(config);
                yield new DeepSeekClient(wc, objectMapper, config.getCode());
            }
        };
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
