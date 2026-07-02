package com.example.agentdeepseek.service.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.*;

/**
 * Ollama 本地模型的 LLMClient 实现
 *
 * <h3>与 OpenAI/DeepSeek 的关键差异</h3>
 * <table>
 *   <tr><th>特性</th><th>OpenAI/DeepSeek</th><th>Ollama</th></tr>
 *   <tr><td>端点</td><td>/v1/chat/completions</td><td>/api/chat</td></tr>
 *   <tr><td>认证</td><td>Authorization: Bearer xxx</td><td>无需认证（本地服务）</td></tr>
 *   <tr><td>temperature</td><td>顶层字段</td><td>放在 options 子对象中</td></tr>
 *   <tr><td>thinking</td><td>支持</td><td>不支持（忽略）</td></tr>
 *   <tr><td>tools</td><td>支持 function calling</td><td>部分模型支持（当前忽略）</td></tr>
 *   <tr><td>SSE 格式</td><td>data: {"choices":[...]}</td><td>每行一个完整 JSON，含 message.content</td></tr>
 *   <tr><td>响应 content</td><td>choices[0].message.content</td><td>message.content (string)</td></tr>
 * </table>
 *
 * <h3>使用场景</h3>
 * <ul>
 *   <li>本地开发环境无需联网</li>
 *   <li>运行 llama3、qwen、mistral、gemma 等开源模型</li>
 *   <li>代码审查/分析等无需高级推理的任务</li>
 * </ul>
 */
@Slf4j
public class OllamaClient extends AbstractLLMClient {

    private static final String CHAT_ENDPOINT = "/api/chat";

    public OllamaClient(WebClient webClient, ObjectMapper objectMapper, String providerCode) {
        super(webClient, objectMapper, providerCode);
    }

    @Override
    public String getChatEndpoint() {
        return CHAT_ENDPOINT;
    }

    // ==================== 请求体构建 ====================

    @Override
    protected Map<String, Object> buildThinkingParams(String thinkingMode) {
        // Ollama 不支持 thinking 参数
        return Collections.emptyMap();
    }

    @Override
    protected void customizeRequest(Map<String, Object> request,
                                     List<Map<String, Object>> messages,
                                     String model,
                                     Double temperature,
                                     String thinkingMode,
                                     boolean stream,
                                     List<Map<String, Object>> tools) {
        // ===== 1. Ollama 的 temperature 放在 options 子对象中 =====
        if (request.containsKey("temperature")) {
            Object temp = request.remove("temperature");
            Map<String, Object> options = new HashMap<>();
            if (temp instanceof Number num) {
                options.put("temperature", num.doubleValue());
            }
            request.put("options", options);
        }

        // ===== 2. Ollama 不支持 function calling tools（忽略） =====
        request.remove("tools");
        request.remove("tool_choice");

        // ===== 3. 移除 Anthropic/OpenAI 特有字段 =====
        request.remove("reasoning_effort");
        request.remove("max_tokens");
        request.remove("max_completion_tokens");
    }

    // ==================== 响应解析（Ollama 特有格式） ====================

    @Override
    public String extractContentFromStreamChunk(String sseChunk) {
        if (sseChunk == null || sseChunk.isEmpty()) {
            return null;
        }
        try {
            JsonNode node = objectMapper.readTree(sseChunk);
            // Ollama 流式: {"model":"...","message":{"role":"assistant","content":"..."},"done":false}
            JsonNode message = node.path("message");
            String content = message.path("content").asText("");
            return content.isEmpty() ? null : content;
        } catch (Exception e) {
            log.trace("Ollama SSE 块解析失败: {}", e.getMessage());
        }
        return null;
    }

    @Override
    public String extractReasoningFromStreamChunk(String sseChunk) {
        // Ollama 没有独立的 reasoning_content 字段
        return null;
    }

    @Override
    public String extractContentFromBlockingResponse(String responseBody) {
        if (responseBody == null || responseBody.isEmpty()) return null;
        try {
            JsonNode root = objectMapper.readTree(responseBody);
            // Ollama 阻塞: {"model":"...","message":{"role":"assistant","content":"..."},"done":true}
            JsonNode message = root.path("message");
            String content = message.path("content").asText("");
            return content.isEmpty() ? null : content.trim();
        } catch (Exception e) {
            log.warn("Ollama 阻塞响应解析失败: {}", e.getMessage());
        }
        return null;
    }

    // ==================== 流式请求（Ollama 无认证头，格式相同） ====================

    @Override
    public Flux<String> streamChat(Map<String, Object> requestBody) {
        return webClient.post()
                .uri(CHAT_ENDPOINT)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(requestBody)
                .accept(MediaType.TEXT_EVENT_STREAM)
                .retrieve()
                .bodyToFlux(String.class)
                .timeout(DEFAULT_STREAM_TIMEOUT)
                .doOnError(org.springframework.web.reactive.function.client.WebClientResponseException.class, e -> {
                    String body = e.getResponseBodyAsString();
                    log.error("Ollama 流式调用失败 status={}: {}",
                            e.getStatusCode().value(),
                            body != null && body.length() > 500 ? body.substring(0, 500) + "..." : body);
                });
    }

    @Override
    public String blockingChat(Map<String, Object> requestBody, Duration timeout) {
        try {
            Duration effectiveTimeout = timeout != null ? timeout : DEFAULT_BLOCKING_TIMEOUT;
            return webClient.post()
                    .uri(CHAT_ENDPOINT)
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(requestBody)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block(effectiveTimeout);
        } catch (org.springframework.web.reactive.function.client.WebClientResponseException e) {
            String body = e.getResponseBodyAsString();
            log.error("Ollama 阻塞调用失败 status={}: {}",
                    e.getStatusCode().value(),
                    body != null && body.length() > 500 ? body.substring(0, 500) + "..." : body);
            throw new RuntimeException("Ollama API调用失败: " + e.getStatusCode() + " - "
                    + (body != null && body.length() > 200 ? body.substring(0, 200) : body), e);
        } catch (Exception e) {
            log.error("Ollama 阻塞调用异常: {}", e.getMessage());
            throw new RuntimeException("Ollama API调用失败: " + e.getMessage(), e);
        }
    }
}
