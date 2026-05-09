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
     */
    private String thinkingMode = "thinking";

    /**
     * 提示词文件与工具组映射
     * key: prompt 文件名, value: 工具名称列表
     */
    private Map<String, List<String>> toolGroups = new HashMap<>();

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
                .defaultHeader("Authorization", "Bearer " + apiKey)
                .defaultHeader("Content-Type", "application/json")
                .exchangeStrategies(exchangeStrategies)
                .build();
    }
}