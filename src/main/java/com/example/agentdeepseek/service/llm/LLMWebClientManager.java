package com.example.agentdeepseek.service.llm;

import com.example.agentdeepseek.model.entity.ProviderConfig;
import io.netty.channel.ChannelOption;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ExchangeStrategies;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.HttpProtocol;
import reactor.netty.http.client.HttpClient;
import reactor.netty.resources.ConnectionProvider;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * LLM WebClient 多实例管理器
 * <p>
 * 为每个 Provider 维护独立的 WebClient 实例（独立的连接池、baseUrl、认证），
 * 避免不同 Provider 的请求互相干扰。
 * </p>
 *
 * <p>
 * 核心特性：
 * <ul>
 *   <li>每个 Provider 独立的连接池（最大空闲 40s，最大存活 5min）</li>
 *   <li>支持动态 API Key 解析（通过 apiKeyResolver）</li>
 *   <li>256KB 内存缓冲区，容纳 tool_calls 大 JSON 块</li>
 *   <li>HTTP/2 优先 + HTTP/1.1 兜底</li>
 * </ul>
 * </p>
 */
@Slf4j
@Component
public class LLMWebClientManager {

    /** Provider Code → WebClient 缓存 */
    private final Map<String, WebClient> clientCache = new ConcurrentHashMap<>();

    /** 动态 API Key 解析器（由 LLMClientManager 注入） */
    private volatile Function<String, String> apiKeyResolver = code -> null;

    /**
     * 获取或创建指定 Provider 的 WebClient
     *
     * @param config Provider 配置
     * @return 该 Provider 专用的 WebClient 实例
     */
    public WebClient getOrCreate(ProviderConfig config) {
        return clientCache.computeIfAbsent(config.getCode(), code -> buildWebClient(config));
    }

    /**
     * 获取指定 Provider 的 WebClient（若不存在返回 null）
     */
    public WebClient getIfExists(String providerCode) {
        return clientCache.get(providerCode);
    }

    /**
     * 清除指定 Provider 的缓存（配置变更后调用）
     */
    public void evict(String providerCode) {
        WebClient old = clientCache.remove(providerCode);
        if (old != null) {
            log.info("已清除 Provider [{}] 的 WebClient 缓存", providerCode);
        }
    }

    /**
     * 清除所有缓存
     */
    public void evictAll() {
        clientCache.clear();
        log.info("已清除所有 WebClient 缓存");
    }

    /**
     * 设置动态 API Key 解析器
     * <p>
     * 当 Provider 自身没有配置 apiKey 时，通过此解析器动态获取
     * （例如从 sys_config 表读取，或在每次请求时通过 filter 注入）
     * </p>
     */
    public void setApiKeyResolver(Function<String, String> resolver) {
        this.apiKeyResolver = resolver;
    }

    // ==================== 内部方法 ====================

    private WebClient buildWebClient(ProviderConfig config) {
        log.info("为 Provider [{}] 创建 WebClient: baseUrl={}", config.getCode(), config.getBaseUrl());

        // 连接池配置（与 DeepSeekConfig 保持一致）
        ConnectionProvider connectionProvider = ConnectionProvider.builder("llm-pool-" + config.getCode())
                .maxIdleTime(Duration.ofSeconds(20))
                .evictInBackground(Duration.ofSeconds(10))
                .maxLifeTime(Duration.ofMinutes(5))
                .build();

        HttpClient httpClient = HttpClient.create(connectionProvider)
                .protocol(HttpProtocol.H2, HttpProtocol.HTTP11)
                .responseTimeout(Duration.ofSeconds(120))
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 10000)
                .option(ChannelOption.SO_KEEPALIVE, true);

        // 内存缓冲区
        ExchangeStrategies exchangeStrategies = ExchangeStrategies.builder()
                .codecs(codecs -> codecs.defaultCodecs().maxInMemorySize(256 * 1024))
                .build();

        WebClient.Builder builder = WebClient.builder()
                .baseUrl(config.getBaseUrl())
                .defaultHeader("Content-Type", "application/json")
                .exchangeStrategies(exchangeStrategies)
                .clientConnector(new ReactorClientHttpConnector(httpClient));

        // API Key 处理：优先使用 Provider 自身配置，否则通过 filter 动态获取
        // 不同 Provider 使用不同的认证头格式
        String authHeaderName = getAuthHeaderName(config);
        String authHeaderPrefix = getAuthHeaderPrefix(config);

        String apiKey = resolveApiKey(config);
        if (apiKey != null && !apiKey.isEmpty()) {
            if (authHeaderPrefix != null && !authHeaderPrefix.isEmpty()) {
                builder.defaultHeader(authHeaderName, authHeaderPrefix + apiKey);
            } else {
                builder.defaultHeader(authHeaderName, apiKey);
            }
        }
        // 即使有静态 Key，也添加 filter 作为动态 fallback
        builder.filter((request, next) -> {
            // 动态 API Key（每次请求前检查 resolveApiKey 是否有更新）
            String dynamicKey = resolveApiKey(config);
            if (dynamicKey != null && !dynamicKey.isEmpty()) {
                String dynamicHeaderName = getAuthHeaderName(config);
                String dynamicPrefix = getAuthHeaderPrefix(config);
                String dynamicValue = (dynamicPrefix != null && !dynamicPrefix.isEmpty())
                        ? dynamicPrefix + dynamicKey : dynamicKey;
                // 如果 request 上已有认证头且与动态 Key 不同，替换之
                var existingAuth = request.headers().get(dynamicHeaderName);
                if (existingAuth.isEmpty() || !existingAuth.get(0).equals(dynamicValue)) {
                    return next.exchange(
                            ClientRequest.from(request)
                                    .headers(h -> h.set(dynamicHeaderName, dynamicValue))
                                    .build()
                    );
                }
            }
            return next.exchange(request);
        });

        return builder.build();
    }

    /**
     * 解析 API Key：Provider 自身配置 > 动态解析器
     */
    private String resolveApiKey(ProviderConfig config) {
        // 1. Provider 自身配置的 Key
        if (config.getApiKey() != null && !config.getApiKey().isEmpty()
                && !"__MUST_CONFIGURE_API_KEY__".equals(config.getApiKey())) {
            return config.getApiKey();
        }
        // 2. 动态解析器（如从 sys_config 表读取）
        if (apiKeyResolver != null) {
            String dynamicKey = apiKeyResolver.apply(config.getCode());
            if (dynamicKey != null && !dynamicKey.isEmpty()
                    && !"__MUST_CONFIGURE_API_KEY__".equals(dynamicKey)) {
                return dynamicKey;
            }
        }
        return null;
    }

    /**
     * 按 Provider 模板获取认证头名称
     * <ul>
     *   <li>deepseek / openai / minimax → Authorization</li>
     *   <li>anthropic → x-api-key</li>
     *   <li>mimo → api-key（MiMo 自定义头）</li>
     *   <li>ollama / custom → 无（返回 Authorization 但无前缀时跳过）</li>
     * </ul>
     */
    private String getAuthHeaderName(ProviderConfig config) {
        String template = config.getRequestTemplate();
        if (template == null) template = config.getCode();
        return switch (template.toLowerCase()) {
            case "anthropic" -> "x-api-key";
            case "mimo" -> "api-key";
            default -> "Authorization"; // deepseek, openai, minimax, ollama, custom
        };
    }

    /**
     * 按 Provider 模板获取认证头值前缀
     * <ul>
     *   <li>deepseek / openai / minimax → "Bearer "</li>
     *   <li>anthropic → null（无前缀，直接放 apiKey）</li>
     *   <li>mimo / ollama → null（无认证或自定义头）</li>
     * </ul>
     */
    private String getAuthHeaderPrefix(ProviderConfig config) {
        String template = config.getRequestTemplate();
        if (template == null) template = config.getCode();
        return switch (template.toLowerCase()) {
            case "anthropic", "mimo", "ollama", "custom" -> null;
            default -> "Bearer "; // deepseek, openai, minimax
        };
    }
}
