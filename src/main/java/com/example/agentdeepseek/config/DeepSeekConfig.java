package com.example.agentdeepseek.config;

import io.netty.channel.ChannelOption;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.EventListener;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.reactive.function.client.ExchangeStrategies;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import reactor.netty.http.HttpProtocol;
import reactor.netty.http.client.HttpClient;
import reactor.netty.resources.ConnectionProvider;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * DeepSeek API配置类
 * 配置API端点、密钥和HTTP客户端
 */
@Slf4j
@Configuration
@ConfigurationProperties(prefix = "deepseek")
@Data
public class DeepSeekConfig {

    /** 内部持有 HttpClient 引用，供预热连接使用 */
    private HttpClient httpClient;

    /** 数据库操作，用于从 sys_config 表读取用户配置的 API Key */
    @Autowired
    private JdbcTemplate jdbcTemplate;

    /**
     * DeepSeek API基础URL
     */
    private String baseUrl = "https://api.deepseek.com";

    /**
     * DeepSeek API密钥
     */
    private String apiKey;

    /**
     * 默认模型名称
     */
    private String defaultModel = "deepseek-v4-pro";

    /**
     * 思考模式：non-thinking / thinking / thinking_max
     * non-thinking -> thinking.type=disabled
     * thinking    -> thinking.type=enabled + reasoning_effort=high
     * thinking_max -> thinking.type=enabled + reasoning_effort=max
     */
    private String thinkingMode = "thinking";

    /**
     * 上下文模式：full（全量注入）/ compact（精简历史工具调用和思考过程）
     * 默认 full，运行时可通过 sys_config 表 context_mode 键动态覆盖
     */
    private String contextMode = "full";

    /**
     * 提示词文件与工具组映射
     * key: prompt 文件名, value: 工具名称列表
     */
    private Map<String, List<String>> toolGroups = new HashMap<>();

    // ========== 上下文压缩配置 ==========

    /**
     * 上下文最大 token 数上限（DeepSeek 模型为 1M）
     */
    private int maxContextTokens = 1000000;

    /**
     * 上下文压缩配置
     */
    private CompactionConfig compaction = new CompactionConfig();

    @Data
    public static class CompactionConfig {
        /**
         * 是否启用智能压缩
         */
        private boolean enabled = true;

        /**
         * 黄色预警阈值（token 用量比例），达到此值标记下次需要压缩
         */
        private double warningThreshold = 0.7;

        /**
         * 橙色压缩阈值（token 用量比例），达到此值立即触发 LLM 压缩
         */
        private double compactThreshold = 0.85;

        /**
         * 红色丢弃阈值（token 用量比例），压缩后仍超此值则执行滑动窗口丢弃
         */
        private double dropThreshold = 0.95;

        /**
         * 保护带轮数：最近 N 轮对话不压缩/不丢弃
         */
        private int protectRounds = 2;

        /**
         * 每批压缩的最大轮数
         */
        private int batchSize = 10;

        /**
         * 压缩使用的模型名称（用小模型节省成本，为空则用 defaultModel）
         */
        private String model = "";

        /**
         * 是否启用异步预压缩（请求返回后后台压缩，下次请求直接使用）
         */
        private boolean asyncPrecompress = true;
    }

    /**
     * 创建WebClient Bean用于流式请求，配置连接池和超时防止死连接导致长时间阻塞。
     *
     * 核心策略：客户端空闲超时（40s）< 服务端 keepalive（~60s），
     * 确保客户端始终先关闭连接，永不拿到被服务端关闭的"死连接"。
     */
    @Bean
    public WebClient deepSeekWebClient(WebClient.Builder webClientBuilder) {
        // ===== 连接池配置：核心！预防死连接问题 =====
        ConnectionProvider connectionProvider = ConnectionProvider.builder("deepseek-pool")
                .maxIdleTime(Duration.ofSeconds(40))       // 空闲40s后客户端主动关闭（比服务端~60s短）
                .evictInBackground(Duration.ofSeconds(20))  // 每20s后台清理过期连接
                .maxLifeTime(Duration.ofMinutes(5))         // 连接最大存活5分钟，强制轮换
                .build();

        // ===== HttpClient 配置：超时 + TCP keepalive + HTTP/2 兜底 =====
        this.httpClient = HttpClient.create(connectionProvider)
                .protocol(HttpProtocol.H2, HttpProtocol.HTTP11)       // 优先HTTP/2（401不关连接）
                .responseTimeout(Duration.ofSeconds(120))              // 请求级响应超时
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 10000)   // TCP连接超时10s
                .option(ChannelOption.SO_KEEPALIVE, true);             // OS级TCP keepalive兜底

        // 配置内存缓冲区，256KB 可容纳 tool_calls 等大 JSON 块
        // 流式实时性由 HTTP chunked 和 SSE 协议决定，不受缓冲区大小影响
        ExchangeStrategies exchangeStrategies = ExchangeStrategies.builder()
                .codecs(configurer -> configurer.defaultCodecs().maxInMemorySize(256 * 1024))
                .build();

        return webClientBuilder
                .baseUrl(baseUrl)
                .defaultHeader("Content-Type", "application/json")
                .exchangeStrategies(exchangeStrategies)
                .clientConnector(new ReactorClientHttpConnector(this.httpClient))
                .build();
    }

    /**
     * 服务启动完成后，异步预热到 DeepSeek API 的 HTTPS 连接。
     * DNS + TCP + TLS 握手在后台完成，消除首次用户请求的 10-20 秒延迟。
     * 
     * 优先从数据库 sys_config 表读取用户配置的 API Key，
     * 数据库没有时回退到配置文件（application.yml / application-local.yml）。
     */
    @EventListener(ApplicationReadyEvent.class)
    public void warmupDeepSeekConnection() {
        // 优先从数据库读取 API Key（与 DeepSeekServiceImpl 中 initDynamicApiKey() 逻辑一致）
        String key = null;
        try {
            List<String> rows = jdbcTemplate.queryForList(
                    "SELECT config_value FROM sys_config WHERE config_key = 'deepseek_api_key'",
                    String.class);
            if (rows != null && !rows.isEmpty()) {
                key = rows.get(0);
            }
        } catch (Exception e) {
            log.debug("从数据库读取 API Key 失败（可能表尚未初始化），回退到配置文件: {}", e.getMessage());
        }

        // 统一从数据库获取，不再回退到配置文件（与 AgentForkManager 逻辑一致）
        if (key == null || key.isEmpty() || "__MUST_CONFIGURE_API_KEY__".equals(key)) {
            log.warn("API Key 未配置，跳过预热（首次请求将较慢）。请在前端「配置」页面设置 API Key。");
            return;
        }

        final String finalKey = key;
        log.info("开始预热 DeepSeek API 连接 (HTTP/2)...");
        Mono.fromRunnable(() -> {
            try {
                // HTTP/2 协议下，即使返回 401 连接也不会被关闭，后续用户请求直接复用
                this.httpClient
                        .headers(h -> h.set("Authorization", "Bearer " + finalKey))
                        .get()
                        .uri("https://api.deepseek.com/v1/models")
                        .responseSingle((response, bytes) -> {
                            log.info("DeepSeek API 连接预热完成, status={}", response.status());
                            return Mono.just("ok");
                        })
                        .block(Duration.ofSeconds(15));
            } catch (Exception e) {
                log.warn("DeepSeek API 连接预热失败: {}（首次用户请求将稍慢）", e.getMessage());
            }
        })
        .subscribeOn(Schedulers.boundedElastic())
        .subscribe();
    }
}