package com.example.agentdeepseek.config;

import com.example.agentdeepseek.common.enums.ContextMode;
import io.netty.channel.ChannelOption;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.ExchangeStrategies;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.HttpProtocol;
import reactor.netty.http.client.HttpClient;
import reactor.netty.resources.ConnectionProvider;

import java.time.Duration;
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

    /** 内部持有 HttpClient 引用，供 WebClient bean 使用 */
    private HttpClient httpClient;

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
     * 思考模式：non-thinking / thinking_low / thinking / thinking_max
     * <p>
     * 与 DeepSeek 官方 {@code reasoning_effort} 四档（none / low / high / max）的映射关系：
     * <ul>
     *   <li>{@code non-thinking} → {@code thinking.type=disabled}（不下发 reasoning_effort，等同 none）</li>
     *   <li>{@code thinking_low}  → {@code thinking.type=enabled} + {@code reasoning_effort=low}（浅思考）</li>
     *   <li>{@code thinking}      → {@code thinking.type=enabled} + {@code reasoning_effort=high}（默认档）</li>
     *   <li>{@code thinking_max}  → {@code thinking.type=enabled} + {@code reasoning_effort=max}（最深思）</li>
     * </ul>
     * </p>
     * <p><b>降档提示</b>：{@code high → low} 只适合简单任务（读文件/改文案/跑命令）。
     * 疑难 bug 根因分析、跨模块重构、多方案权衡类任务降档会明显掉质量，且一旦因降档多跑一轮，
     * 单轮成本（约 0.03~0.04 元）远高于省下的 reasoning 费用，反而更贵。</p>
     */
    private String thinkingMode = "thinking";

    /**
     * 上下文模式（主对话历史注入策略）：full / balanced / compact
     * <p>
     * <b>三档差异</b>：
     * <ul>
     *   <li>{@code full}     —— 历史 reasoning 也回传、工具结果原样保留（最准、最贵）</li>
     *   <li>{@code balanced} —— <b>默认</b>：剔除历史 reasoning、工具结果原样保留。
     *       reasoning 双重计费约 9 元/百万且对后续决策价值低；工具结果是决策证据必须留</li>
     *   <li>{@code compact}  —— 额外摘要化历史工具结果（救急：上下文逼近模型上限时用，
     *       会改写历史导致一次前缀缓存失效）</li>
     * </ul>
     * </p>
     * <p>取值优先级：请求参数 {@code ChatRequest.contextMode} &gt;
     * sys_config 表 {@code context_mode} 键 &gt; 本配置 &gt; {@link ContextMode#DEFAULT}。</p>
     */
    private String contextMode;

    /**
     * 【已废弃】是否回传历史 reasoning —— 由 {@link #contextMode} 统一取代
     * <p>
     * 保留仅为平滑升级：仅当 {@code context-mode} 未显式配置、且本字段被显式设为 {@code false} 时，
     * 才等价于请求 {@code full} 模式。新配置请直接使用 {@code deepseek.context-mode}。
     * </p>
     *
     * @deprecated 请改用 {@link #contextMode} / {@link #resolveContextMode()}
     */
    @Deprecated
    private Boolean stripHistoryReasoning;

    /**
     * 解析生效的上下文模式（含旧开关兼容）
     * <p>
     * 兼容规则：只有当 {@code context-mode} 未显式配置（为空 / 无法识别）且旧的
     * {@code strip-history-reasoning} 被显式设为 {@code false} 时，才回到 {@code full}；
     * 其余情况一律以 {@code context-mode} 为准。
     * </p>
     */
    public ContextMode resolveContextMode() {
        ContextMode parsed = ContextMode.tryParse(contextMode);
        if (parsed != null) {
            return parsed;
        }
        if (Boolean.FALSE.equals(stripHistoryReasoning)) {
            return ContextMode.FULL;
        }
        return ContextMode.DEFAULT;
    }

    /**
     * 便捷判定：当前模式下是否「不携带真实的历史 {@code reasoning_content}」。
     * <p>等价于 {@code resolveContextMode().shouldStripHistoryReasoning()}；
     * 各调用点统一走此方法，避免各处自行字符串比较导致三档语义漂移。</p>
     * <p>
     * ⚠️ 语义提醒：为 {@code true} 表示只把字段值置为空串，<b>字段本身仍必须下发</b>
     * —— DeepSeek thinking + tools 场景下字段缺失会直接 400。
     * 需要写历史消息时请改用 {@code util.ReasoningPassback#putHistory}，
     * 不要在本方法为 true 时省略字段。
     * </p>
     */
    public boolean isStripHistoryReasoning() {
        return resolveContextMode().shouldStripHistoryReasoning();
    }

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
                .maxIdleTime(Duration.ofSeconds(20))       // 空闲20s后客户端主动关闭（服务端 keepalive 实测可能短于预期，缩小死连接窗口）
                .evictInBackground(Duration.ofSeconds(10))  // 每10s后台清理过期连接
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
}