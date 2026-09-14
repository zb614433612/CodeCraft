package com.example.agentdeepseek.service.llm;

import com.example.agentdeepseek.model.entity.ProviderConfig;
import io.netty.channel.ChannelOption;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ExchangeStrategies;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Mono;
import reactor.netty.http.HttpProtocol;
import reactor.netty.http.client.HttpClient;
import reactor.netty.resources.ConnectionProvider;
import reactor.util.retry.Retry;

import java.nio.charset.StandardCharsets;
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

    /** P4：LLM 并发限流器（全局并发信号量，DeepSeek 官方仅按并发限制） */
    private final ProviderRateLimiter providerRateLimiter;

    /** P4：429/5xx 重试次数（含首次请求，默认 3 次 = 1 次原始 + 2 次退避重试） */
    @Value("${llm.retry.max-attempts:3}")
    private int retryMaxAttempts;

    /** P4：重试基础延迟（毫秒，指数退避起点） */
    @Value("${llm.retry.base-delay-ms:1000}")
    private long retryBaseDelayMs;

    /** P4：重试最大延迟（毫秒，退避封顶） */
    @Value("${llm.retry.max-delay-ms:10000}")
    private long retryMaxDelayMs;

    public LLMWebClientManager(ProviderRateLimiter providerRateLimiter) {
        this.providerRateLimiter = providerRateLimiter;
    }

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

        // ===== P4：LLM 并发限流过滤器（全局并发信号量，等待策略自动排队） =====
        // 所有经本 WebClient 的请求（含工具循环直连路径、AbstractLLMClient、无工具路径）统一限流。
        // 响应头到达后即释放并发许可（流式 body 下载阶段不算在飞请求）。
        builder.filter((request, next) ->
                providerRateLimiter.acquireAsync(config.getCode())
                        .flatMap(lease -> next.exchange(request)
                                .doFinally(signalType -> lease.close())));

        // ===== P4：429/5xx 指数退避重试过滤器（流式安全） =====
        // 仅在「响应头阶段」判定错误（4xx/5xx 状态码）时重试——此时响应体尚未开始流转，
        // 重试不会导致流式数据重复；一旦 200 响应头到达、body 开始输出，后续断流不会触发重试。
        builder.filter((request, next) ->
                Mono.defer(() -> next.exchange(request))
                        .flatMap(response -> {
                            HttpStatusCode status = response.statusCode();
                            if (status.isError()) {
                                // 将错误状态转为异常以触发 retryWhen；读取并释放错误响应体（防连接泄漏）
                                return response.bodyToMono(String.class)
                                        .defaultIfEmpty("")
                                        .flatMap(body -> Mono.error(new WebClientResponseException(
                                                status.value(), status.toString(),
                                                response.headers().asHttpHeaders(),
                                                body.getBytes(StandardCharsets.UTF_8), null)));
                            }
                            return Mono.just(response);
                        })
                        .retryWhen(Retry.backoff(Math.max(0, retryMaxAttempts - 1),
                                        Duration.ofMillis(retryBaseDelayMs))
                                .maxBackoff(Duration.ofMillis(retryMaxDelayMs))
                                .filter(this::isRetryableStatus)
                                .onRetryExhaustedThrow((spec, signal) -> signal.failure())));

        return builder.build();
    }

    /**
     * P4：判定错误是否可重试——429（限流）与 5xx（服务端临时错误）可重试；
     * 4xx 业务错误（400/401/403/404）不可重试
     */
    private boolean isRetryableStatus(Throwable ex) {
        if (ex instanceof WebClientResponseException wce) {
            int code = wce.getStatusCode().value();
            boolean retryable = code == 429 || code >= 500;
            if (retryable) {
                log.warn("LLM 调用返回 {}，将指数退避重试: {}", code,
                        wce.getMessage() != null && wce.getMessage().length() > 200
                                ? wce.getMessage().substring(0, 200) : wce.getMessage());
            }
            return retryable;
        }
        return false;
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
