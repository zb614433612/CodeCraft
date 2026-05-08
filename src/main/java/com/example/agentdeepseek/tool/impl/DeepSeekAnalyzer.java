package com.example.agentdeepseek.tool.impl;

import com.example.agentdeepseek.config.DeepSeekConfig;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.ExchangeStrategies;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.*;

/**
 * DeepSeek 非流式分析器
 * 供子 agent 工具调用 DeepSeek API 进行 LLM 分析（非流式，大缓冲区）
 */
@Slf4j
@Component
public class DeepSeekAnalyzer {

    private final WebClient webClient;
    private final DeepSeekConfig deepSeekConfig;
    private final ObjectMapper objectMapper;

    public DeepSeekAnalyzer(WebClient.Builder webClientBuilder, DeepSeekConfig deepSeekConfig, ObjectMapper objectMapper) {
        this.deepSeekConfig = deepSeekConfig;
        this.objectMapper = objectMapper;

        // 大缓冲区（10MB），适配非流式分析场景
        ExchangeStrategies strategies = ExchangeStrategies.builder()
                .codecs(configurer -> configurer.defaultCodecs().maxInMemorySize(10 * 1024 * 1024))
                .build();

        this.webClient = webClientBuilder
                .baseUrl(deepSeekConfig.getBaseUrl())
                .defaultHeader("Authorization", "Bearer " + deepSeekConfig.getApiKey())
                .defaultHeader("Content-Type", "application/json")
                .exchangeStrategies(strategies)
                .build();
    }

    /**
     * 调用 DeepSeek 非流式 API 进行分析
     *
     * @param systemPrompt  系统提示词
     * @param userMessage   用户消息（含上下文）
     * @param timeoutSeconds 超时秒数
     * @return 分析结果文本，失败时返回错误描述
     */
    public String analyze(String systemPrompt, String userMessage, int timeoutSeconds) {
        Map<String, Object> systemMsg = new HashMap<>();
        systemMsg.put("role", "system");
        systemMsg.put("content", systemPrompt);

        Map<String, Object> userMsg = new HashMap<>();
        userMsg.put("role", "user");
        userMsg.put("content", userMessage);

        return analyzeInternal(List.of(systemMsg, userMsg), timeoutSeconds);
    }

    /**
     * 调用 DeepSeek 非流式 API 进行分析（完整消息列表）
     */
    public String analyzeWithMessages(List<Map<String, Object>> messages, int timeoutSeconds) {
        return analyzeInternal(messages, timeoutSeconds);
    }

    /**
     * 执行非流式 API 调用
     */
    private String analyzeInternal(List<Map<String, Object>> messages, int timeoutSeconds) {
        Map<String, Object> request = new HashMap<>();
        request.put("model", deepSeekConfig.getDefaultModel());
        request.put("messages", messages);
        request.put("stream", false);

        String thinkingMode = deepSeekConfig.getThinkingMode();
        if (thinkingMode != null && !thinkingMode.isEmpty()) {
            request.put("thinking_mode", thinkingMode);
        }

        try {
            String response = webClient.post()
                    .uri("/v1/chat/completions")
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(request)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block(Duration.ofSeconds(timeoutSeconds));

            if (response == null || response.isEmpty()) {
                return "错误：API 返回空响应";
            }

            JsonNode responseNode = objectMapper.readTree(response);
            JsonNode choices = responseNode.path("choices");
            if (choices.isArray() && choices.size() > 0) {
                JsonNode message = choices.get(0).path("message");
                String content = message.path("content").asText("");
                if (!content.isEmpty()) {
                    return content;
                }
                // 降级使用 reasoning_content
                String reasoning = message.path("reasoning_content").asText("");
                if (!reasoning.isEmpty()) {
                    return "[思考过程]\n" + reasoning;
                }
                return "错误：API 返回的 content 为空";
            }
            return "错误：API 返回的 choices 为空";
        } catch (Exception e) {
            log.error("DeepSeek 分析调用失败", e);
            return "错误：分析调用失败 - " + e.getClass().getSimpleName();
        }
    }
}
