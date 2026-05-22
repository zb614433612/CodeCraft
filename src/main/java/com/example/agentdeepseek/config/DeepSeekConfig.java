package com.example.agentdeepseek.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.ExchangeStrategies;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * DeepSeek API配置类
 * 配置API端点、密钥和HTTP客户端
 */
@Configuration
@ConfigurationProperties(prefix = "deepseek")
@Data
public class DeepSeekConfig {

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
     * 创建WebClient Bean用于流式请求
     */
    @Bean
    public WebClient deepSeekWebClient(WebClient.Builder webClientBuilder) {
        // 配置内存缓冲区，256KB 可容纳 tool_calls 等大 JSON 块
        // 流式实时性由 HTTP chunked 和 SSE 协议决定，不受缓冲区大小影响
        ExchangeStrategies exchangeStrategies = ExchangeStrategies.builder()
                .codecs(configurer -> configurer.defaultCodecs().maxInMemorySize(256 * 1024))
                .build();

        return webClientBuilder
                .baseUrl(baseUrl)
                .defaultHeader("Content-Type", "application/json")
                .exchangeStrategies(exchangeStrategies)
                .build();
    }
}