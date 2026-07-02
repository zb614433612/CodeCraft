package com.example.agentdeepseek.tool.impl;

import com.example.agentdeepseek.config.DeepSeekConfig;
import com.example.agentdeepseek.service.llm.LLMClientManager;
import com.example.agentdeepseek.service.llm.LLMClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
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

    private final DeepSeekConfig deepSeekConfig;
    private final ObjectMapper objectMapper;
    private final LLMClientManager llmClientManager;

    public DeepSeekAnalyzer(DeepSeekConfig deepSeekConfig,
                            ObjectMapper objectMapper,
                            LLMClientManager llmClientManager) {
        this.deepSeekConfig = deepSeekConfig;
        this.objectMapper = objectMapper;
        this.llmClientManager = llmClientManager;
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

    /** 指定 providerCode 的分析 */
    public String analyze(String systemPrompt, String userMessage, int timeoutSeconds, String providerCode) {
        Map<String, Object> systemMsg = new HashMap<>();
        systemMsg.put("role", "system");
        systemMsg.put("content", systemPrompt);
        Map<String, Object> userMsg = new HashMap<>();
        userMsg.put("role", "user");
        userMsg.put("content", userMessage);
        return analyzeInternal(List.of(systemMsg, userMsg), timeoutSeconds, true, providerCode);
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

    /** 指定 providerCode 的分析（不含 thinking） */
    public String analyzeWithoutThinking(String systemPrompt, String userMessage, int timeoutSeconds, String providerCode) {
        Map<String, Object> systemMsg = new HashMap<>();
        systemMsg.put("role", "system");
        systemMsg.put("content", systemPrompt);
        Map<String, Object> userMsg = new HashMap<>();
        userMsg.put("role", "user");
        userMsg.put("content", userMessage);
        return analyzeInternal(List.of(systemMsg, userMsg), timeoutSeconds, false, providerCode);
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
        return analyzeInternal(messages, timeoutSeconds, enableThinking, null);
    }

    /**
     * 执行非流式 API 调用（带重试+可选 ProviderCode）
     */
    private String analyzeInternal(List<Map<String, Object>> messages, int timeoutSeconds,
                                    boolean enableThinking, String providerCode) {
        // ===== 解析 LLM Client：providerCode > 默认 Provider =====
        LLMClient client = (providerCode != null && !providerCode.isEmpty())
                ? llmClientManager.resolveClientByCode(providerCode)
                : llmClientManager.getDefaultClient();
        String model = llmClientManager.getDefaultModel(client.getProviderCode());

        // ===== 思考模式：false=non-thinking（评委快速响应），true=跟随 Agent 配置 =====
        String thinkingMode = enableThinking
                ? deepSeekConfig.getThinkingMode()
                : "non-thinking";

        // ===== 使用 LLMClient 构建标准请求体（Provider 适配层自动处理 thinking 格式差异）=====
        Map<String, Object> request = client.buildRequestBody(
                messages, model, 0.3, thinkingMode, false, null);

        int maxRetries = 2;
        Exception lastException = null;

        for (int attempt = 0; attempt <= maxRetries; attempt++) {
            if (attempt > 0) {
                long waitMs = attempt * 2000L;
                log.info("LLM 分析调用重试 (第 {}/{} 次)，Provider=[{}]，等待 {}ms",
                        attempt, maxRetries, client.getProviderCode(), waitMs);
                try {
                    Thread.sleep(waitMs);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }

            try {
                // 使用当前 Provider 的 blockingChat（自动处理认证头/超时/错误）
                String response = client.blockingChat(request, Duration.ofSeconds(timeoutSeconds));

                if (response == null || response.isEmpty()) {
                    return "错误：API 返回空响应";
                }

                // 使用 Provider 适配层提取内容（兼容 Anthropic/Ollama 等非 OpenAI 格式）
                String content = client.extractContentFromBlockingResponse(response);
                if (content != null && !content.isEmpty()) {
                    return content;
                }

                // 降级：content 为空时，尝试手动解析 reasoning_content
                JsonNode responseNode = objectMapper.readTree(response);
                JsonNode choices = responseNode.path("choices");
                if (choices.isArray() && choices.size() > 0) {
                    JsonNode message = choices.get(0).path("message");
                    String reasoning = message.path("reasoning_content").asText("");
                    if (!reasoning.isEmpty()) {
                        String extractedJson = extractJsonFromText(reasoning);
                        if (extractedJson != null) {
                            log.info("从 reasoning_content 中成功提取 JSON");
                            return extractedJson;
                        }
                        return "[思考过程]\n" + reasoning;
                    }
                }
                return "错误：API 返回的 content 为空";

            } catch (RuntimeException e) {
                // blockingChat() 将 WebClientResponseException 包装为 RuntimeException
                Throwable cause = e.getCause() != null ? e.getCause() : e;
                if (cause instanceof WebClientResponseException wcre) {
                    lastException = wcre;
                    int statusCode = wcre.getStatusCode().value();
                    String errorBody = wcre.getResponseBodyAsString();
                    String errorMsg = String.format("HTTP %d: %s", statusCode,
                            errorBody != null && !errorBody.isEmpty() ? errorBody : wcre.getMessage());
                    log.error("LLM 分析调用失败 [{}]: {}", client.getProviderCode(), errorMsg);

                    // 4xx 客户端错误（非 429 限流）不重试，直接返回
                    if (statusCode >= 400 && statusCode < 500 && statusCode != 429) {
                        return "错误：API 请求被拒绝 - " + errorMsg;
                    }
                    // 429 限流 或 5xx 服务端错误，继续重试
                } else {
                    lastException = e;
                    log.error("LLM 分析调用失败 [{}]: {}", client.getProviderCode(), e.getMessage(), e);
                    // 非 HTTP 异常，继续重试
                }

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
