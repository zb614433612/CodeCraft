package com.example.agentdeepseek.service.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * LLMClient 抽象基类
 * <p>
 * 提供流式/非流式调用的通用实现，子类只需关注：
 * </p>
 * <ol>
 *   <li>{@link #buildRequestBody} — 构建 Provider 特有的请求体</li>
 *   <li>{@link #getChatEndpoint()} — API 端点路径</li>
 *   <li>{@link #extractContentFromStreamChunk} / {@link #extractReasoningFromStreamChunk} — SSE 解析</li>
 *   <li>{@link #extractContentFromBlockingResponse} — 阻塞响应解析</li>
 * </ol>
 */
@Slf4j
public abstract class AbstractLLMClient implements LLMClient {

    protected final WebClient webClient;
    protected final ObjectMapper objectMapper;
    protected final String providerCode;

    /** SSE data 行前缀 */
    protected static final String DATA_PREFIX = "data: ";

    /** 默认流式请求超时 */
    protected static final Duration DEFAULT_STREAM_TIMEOUT = Duration.ofSeconds(300);

    /** 默认阻塞请求超时 */
    protected static final Duration DEFAULT_BLOCKING_TIMEOUT = Duration.ofSeconds(120);

    protected AbstractLLMClient(WebClient webClient, ObjectMapper objectMapper, String providerCode) {
        this.webClient = webClient;
        this.objectMapper = objectMapper;
        this.providerCode = providerCode;
    }

    // ==================== 抽象方法：子类实现 ====================

    /**
     * 获取 Provider 编码
     */
    @Override
    public String getProviderCode() {
        return this.providerCode;
    }

    /**
     * 构建 Provider 特有的扩展参数
     * <p>
     * 如 DeepSeek 的 thinking / reasoning_effort，
     * OpenAI 的 reasoning / max_tokens，Anthropic 的 thinking 等
     * </p>
     *
     * @param thinkingMode 思考模式
     * @return 扩展参数 Map（会被合并到请求体中）
     */
    protected abstract Map<String, Object> buildThinkingParams(String thinkingMode);

    // ==================== 通用实现 ====================

    @Override
    public WebClient getWebClient() {
        return webClient;
    }

    @Override
    public Map<String, Object> buildRequestBody(
            List<Map<String, Object>> messages,
            String model,
            Double temperature,
            String thinkingMode,
            boolean stream,
            List<Map<String, Object>> tools) {

        Map<String, Object> request = new HashMap<>();
        request.put("model", model);
        request.put("messages", messages);
        request.put("stream", stream);

        if (temperature != null) {
            request.put("temperature", temperature);
        }

        // 合并 Provider 特有参数
        Map<String, Object> thinkingParams = buildThinkingParams(thinkingMode);
        if (thinkingParams != null && !thinkingParams.isEmpty()) {
            request.putAll(thinkingParams);
        }

        // 工具定义
        if (tools != null && !tools.isEmpty()) {
            request.put("tools", tools);
            request.put("tool_choice", "auto");
        }

        // 子类可进一步定制
        customizeRequest(request, messages, model, temperature, thinkingMode, stream, tools);

        return request;
    }

    /**
     * 子类覆盖此方法做进一步定制（如添加 max_tokens、top_p 等）
     * 默认空实现
     */
    protected void customizeRequest(Map<String, Object> request,
                                     List<Map<String, Object>> messages,
                                     String model,
                                     Double temperature,
                                     String thinkingMode,
                                     boolean stream,
                                     List<Map<String, Object>> tools) {
        // 默认不做额外定制
    }

    @Override
    public Flux<String> streamChat(Map<String, Object> requestBody) {
        return webClient.post()
                .uri(getChatEndpoint())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(requestBody)
                .accept(MediaType.TEXT_EVENT_STREAM)
                .retrieve()
                .bodyToFlux(String.class)
                .timeout(DEFAULT_STREAM_TIMEOUT)
                .doOnError(WebClientResponseException.class, e -> {
                    String body = e.getResponseBodyAsString();
                    log.error("LLM 流式调用失败 [{}] status={}: {}",
                            getProviderCode(), e.getStatusCode().value(),
                            body != null && body.length() > 500 ? body.substring(0, 500) + "..." : body);
                });
    }

    @Override
    public String blockingChat(Map<String, Object> requestBody, Duration timeout) {
        try {
            Duration effectiveTimeout = timeout != null ? timeout : DEFAULT_BLOCKING_TIMEOUT;
            return webClient.post()
                    .uri(getChatEndpoint())
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(requestBody)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block(effectiveTimeout);
        } catch (WebClientResponseException e) {
            String body = e.getResponseBodyAsString();
            log.error("LLM 阻塞调用失败 [{}] status={}: {}",
                    getProviderCode(), e.getStatusCode().value(),
                    body != null && body.length() > 500 ? body.substring(0, 500) + "..." : body);
            throw new RuntimeException("LLM API调用失败 [" + getProviderCode() + "]: "
                    + e.getStatusCode() + " - "
                    + (body != null && body.length() > 200 ? body.substring(0, 200) : body), e);
        } catch (Exception e) {
            log.error("LLM 阻塞调用异常 [{}]: {}", getProviderCode(), e.getMessage());
            throw new RuntimeException("LLM API调用失败 [" + getProviderCode() + "]: " + e.getMessage(), e);
        }
    }

    // ==================== 默认响应解析（子类可覆盖） ====================

    @Override
    public String extractContentFromStreamChunk(String sseChunk) {
        if (sseChunk == null || sseChunk.isEmpty() || "[DONE]".equals(sseChunk.trim())) {
            return null;
        }
        try {
            JsonNode node = objectMapper.readTree(sseChunk);
            JsonNode choices = node.path("choices");
            if (choices.isArray() && choices.size() > 0) {
                JsonNode delta = choices.get(0).path("delta");
                JsonNode content = delta.path("content");
                return content.isNull() ? null : content.asText();
            }
        } catch (Exception e) {
            log.trace("SSE 块解析失败: {}", sseChunk.length() > 100 ? sseChunk.substring(0, 100) : sseChunk);
        }
        return null;
    }

    @Override
    public String extractReasoningFromStreamChunk(String sseChunk) {
        if (sseChunk == null || sseChunk.isEmpty() || "[DONE]".equals(sseChunk.trim())) {
            return null;
        }
        try {
            JsonNode node = objectMapper.readTree(sseChunk);
            JsonNode choices = node.path("choices");
            if (choices.isArray() && choices.size() > 0) {
                JsonNode delta = choices.get(0).path("delta");
                // DeepSeek 的 reasoning_content
                JsonNode reasoning = delta.path("reasoning_content");
                return reasoning.isNull() ? null : reasoning.asText();
            }
        } catch (Exception e) {
            log.trace("SSE reasoning 解析失败: {}", e.getMessage());
        }
        return null;
    }

    @Override
    public String extractContentFromBlockingResponse(String responseBody) {
        if (responseBody == null || responseBody.isEmpty()) {
            return null;
        }
        try {
            JsonNode root = objectMapper.readTree(responseBody);
            JsonNode choices = root.path("choices");
            if (choices.isArray() && choices.size() > 0) {
                JsonNode message = choices.get(0).path("message");
                String content = message.path("content").asText("");
                return content.isEmpty() ? null : content.trim();
            }
        } catch (Exception e) {
            log.warn("阻塞响应解析失败: {}", e.getMessage());
        }
        return null;
    }
}
