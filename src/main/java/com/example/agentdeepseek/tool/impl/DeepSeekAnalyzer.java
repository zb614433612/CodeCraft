package com.example.agentdeepseek.tool.impl;

import com.example.agentdeepseek.config.DeepSeekConfig;
import com.example.agentdeepseek.service.ConfigService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.ExchangeStrategies;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

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
    private final ConfigService configService;
    private final ObjectMapper objectMapper;

    public DeepSeekAnalyzer(WebClient.Builder webClientBuilder, DeepSeekConfig deepSeekConfig,
                            ConfigService configService, ObjectMapper objectMapper) {
        this.deepSeekConfig = deepSeekConfig;
        this.configService = configService;
        this.objectMapper = objectMapper;

        // 大缓冲区（10MB），适配非流式分析场景
        ExchangeStrategies strategies = ExchangeStrategies.builder()
                .codecs(configurer -> configurer.defaultCodecs().maxInMemorySize(10 * 1024 * 1024))
                .build();

        // 【修复】不再 hardcode defaultHeader("Authorization")，改为每次请求时动态获取 API Key
        // - 用户可能在前端「配置」页面修改 API Key（存入数据库 sys_config 表）
        // - 若硬编码 defaultHeader，后续修改不会生效，导致 401 认证失败
        // - 参考 AgentForkManager.callDeepSeekApi() 的做法：每次请求前动态获取并设置 Bearer Auth
        this.webClient = webClientBuilder
                .baseUrl(deepSeekConfig.getBaseUrl())
                .defaultHeader("Content-Type", "application/json")
                .exchangeStrategies(strategies)
                .build();
    }

    /**
     * 调用 DeepSeek 非流式 API 进行分析
     *
     * @param systemPrompt   系统提示词
     * @param userMessage    用户消息（含上下文）
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

        return analyzeInternal(List.of(systemMsg, userMsg), timeoutSeconds, true);
    }

    /**
     * 调用 DeepSeek 非流式 API 进行分析（不含 thinking 模式）
     * 专供评委等需要快速、确定性响应的场景使用，避免 thinking 模式导致：
     * 1. 响应超时（thinking 模式下模型思考耗时更长）
     * 2. content 为空而 reasoning_content 非 JSON 导致解析失败
     *
     * @param systemPrompt   系统提示词
     * @param userMessage    用户消息（含上下文）
     * @param timeoutSeconds 超时秒数
     * @return 分析结果文本，失败时返回错误描述
     */
    public String analyzeWithoutThinking(String systemPrompt, String userMessage, int timeoutSeconds) {
        Map<String, Object> systemMsg = new HashMap<>();
        systemMsg.put("role", "system");
        systemMsg.put("content", systemPrompt);

        Map<String, Object> userMsg = new HashMap<>();
        userMsg.put("role", "user");
        userMsg.put("content", userMessage);

        return analyzeInternal(List.of(systemMsg, userMsg), timeoutSeconds, false);
    }

    /**
     * 调用 DeepSeek 非流式 API 进行分析（完整消息列表）
     */
    public String analyzeWithMessages(List<Map<String, Object>> messages, int timeoutSeconds) {
        return analyzeInternal(messages, timeoutSeconds, true);
    }

    // ==================== 核心调用逻辑 ====================

    /**
     * 执行非流式 API 调用（带重试）
     *
     * @param messages        消息列表
     * @param timeoutSeconds  超时秒数
     * @param enableThinking  是否启用 thinking 模式
     * @return 分析结果文本
     */
    private String analyzeInternal(List<Map<String, Object>> messages, int timeoutSeconds, boolean enableThinking) {
        Map<String, Object> request = new HashMap<>();
        request.put("model", deepSeekConfig.getDefaultModel());
        request.put("messages", messages);
        request.put("stream", false);

        if (enableThinking) {
            String thinkingMode = deepSeekConfig.getThinkingMode();
            if (thinkingMode != null && !thinkingMode.isEmpty()) {
                request.put("thinking_mode", thinkingMode);
            }
        } else {
            // 【修复】显式禁用 thinking，避免模型默认启用导致 content 为空
            // deepseek-v4-pro 等模型默认启用 thinking，不传 thinking_mode 时
            // API 返回 content="" + reasoning_content="思考文本"，导致评委提取 JSON 失败
            request.put("thinking_mode", "non-thinking");
        }

        int maxRetries = 2;
        Exception lastException = null;

        for (int attempt = 0; attempt <= maxRetries; attempt++) {
            if (attempt > 0) {
                long waitMs = attempt * 2000L;
                log.info("DeepSeek 分析调用重试 (第 {}/{} 次)，等待 {}ms", attempt, maxRetries, waitMs);
                try {
                    Thread.sleep(waitMs);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }

            try {
                // 动态获取 API Key（与 AgentForkManager.callDeepSeekApi() 逻辑一致）
                // 统一从数据库 sys_config 表读取，用户可在前端「配置」页面实时修改
                final String apiKey = configService.getValue("deepseek_api_key");
                if (apiKey == null || apiKey.isEmpty()) {
                    return "错误：DeepSeek API Key 未配置，请先在配置页面设置 API Key";
                }

                String response = webClient.post()
                        .uri("/v1/chat/completions")
                        .headers(headers -> headers.setBearerAuth(apiKey))
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

                    // 降级：content 为空时，尝试从 reasoning_content 中提取 JSON
                    // thinking 模式下模型可能把 JSON 放在思考过程中
                    String reasoning = message.path("reasoning_content").asText("");
                    if (!reasoning.isEmpty()) {
                        String extractedJson = extractJsonFromText(reasoning);
                        if (extractedJson != null) {
                            log.info("从 reasoning_content 中成功提取 JSON");
                            return extractedJson;
                        }
                        // 提取不到 JSON，返回原始推理内容（调用方需自行处理）
                        return "[思考过程]\n" + reasoning;
                    }
                    return "错误：API 返回的 content 为空";
                }
                return "错误：API 返回的 choices 为空";

            } catch (WebClientResponseException e) {
                lastException = e;
                int statusCode = e.getStatusCode().value();
                String errorBody = e.getResponseBodyAsString();
                String errorMsg = String.format("HTTP %d: %s", statusCode,
                        errorBody != null && !errorBody.isEmpty() ? errorBody : e.getMessage());
                log.error("DeepSeek 分析调用失败: {}", errorMsg);

                // 4xx 客户端错误（非 429 限流）不重试，直接返回
                if (statusCode >= 400 && statusCode < 500 && statusCode != 429) {
                    return "错误：API 请求被拒绝 - " + errorMsg;
                }
                // 429 限流 或 5xx 服务端错误，继续重试

            } catch (Exception e) {
                lastException = e;
                log.error("DeepSeek 分析调用失败: {}", e.getMessage(), e);
                // 网络超时等异常，继续重试
            }
        }

        // 所有重试均失败
        String finalError;
        if (lastException instanceof WebClientResponseException wcre) {
            finalError = String.format("HTTP %d: %s", wcre.getStatusCode().value(), wcre.getMessage());
        } else if (lastException != null && lastException.getMessage() != null) {
            finalError = lastException.getMessage();
        } else if (lastException != null) {
            finalError = lastException.getClass().getSimpleName();
        } else {
            finalError = "未知错误";
        }
        return "错误：分析调用失败（已重试 " + maxRetries + " 次） - " + finalError;
    }

    // ==================== 辅助方法 ====================

    /**
     * 从文本中尝试提取 JSON 对象
     * 支持 ```json ... ``` 代码块和直接内嵌的 {...} 对象
     *
     * @param text 原始文本
     * @return 提取到的 JSON 字符串，失败返回 null
     */
    private String extractJsonFromText(String text) {
        if (text == null || text.isEmpty()) {
            return null;
        }

        // 尝试从 ```json 代码块中提取
        int jsonBlockStart = text.indexOf("```json");
        if (jsonBlockStart >= 0) {
            int contentStart = text.indexOf('\n', jsonBlockStart + 7);
            if (contentStart < 0) contentStart = jsonBlockStart + 7;
            else contentStart = contentStart + 1;
            int contentEnd = text.indexOf("```", contentStart);
            if (contentEnd > contentStart) {
                return text.substring(contentStart, contentEnd).trim();
            }
        }

        // 尝试从 ``` 代码块中提取
        int codeBlockStart = text.indexOf("```");
        if (codeBlockStart >= 0) {
            int contentStart = text.indexOf('\n', codeBlockStart + 3);
            if (contentStart < 0) contentStart = codeBlockStart + 3;
            else contentStart = contentStart + 1;
            int contentEnd = text.indexOf("```", contentStart);
            if (contentEnd > contentStart) {
                String blockContent = text.substring(contentStart, contentEnd).trim();
                // 验证是否为有效 JSON
                if (isValidJson(blockContent)) {
                    return blockContent;
                }
            }
        }

        // 尝试从文本中定位 { ... } JSON 对象
        // 使用括号匹配算法，避免首尾 { 和 } 跨越多个 JSON 块导致提取失败
        // 例如推理文本中可能出现 "应该返回 {"key": "value"}，而不是 {"wrong": ...}" → 需精确匹配
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '{') {
                int end = findMatchingBrace(text, i);
                if (end > i) {
                    String candidate = text.substring(i, end + 1).trim();
                    if (isValidJson(candidate)) {
                        return candidate;
                    }
                }
            }
        }

        return null;
    }

    /**
     * 从文本中定位与 start 位置的 '{' 匹配的 '}' 位置
     * 使用栈计数器处理嵌套的 { } 对和字符串字面量
     *
     * @param text  文本
     * @param start '{' 的位置
     * @return 匹配的 '}' 位置，未找到返回 -1
     */
    private int findMatchingBrace(String text, int start) {
        int depth = 0;
        boolean inString = false;
        boolean escaped = false;

        for (int i = start; i < text.length(); i++) {
            char c = text.charAt(i);

            // 处理字符串转义
            if (inString) {
                if (escaped) {
                    escaped = false;
                    continue;
                }
                if (c == '\\') {
                    escaped = true;
                    continue;
                }
                if (c == '"') {
                    inString = false;
                }
                continue;
            }

            if (c == '"') {
                inString = true;
                continue;
            }

            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return i;
                }
            }
        }
        return -1;
    }

    /**
     * 检查字符串是否为有效 JSON 对象
     */
    private boolean isValidJson(String text) {
        try {
            objectMapper.readTree(text);
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
