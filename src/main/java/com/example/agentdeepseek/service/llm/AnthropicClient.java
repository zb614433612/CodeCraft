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
 * Anthropic Claude API 的 LLMClient 实现
 *
 * <h3>与 OpenAI/DeepSeek 的关键差异</h3>
 * <table>
 *   <tr><th>特性</th><th>OpenAI/DeepSeek</th><th>Anthropic (Claude)</th></tr>
 *   <tr><td>端点</td><td>/v1/chat/completions</td><td>/v1/messages</td></tr>
 *   <tr><td>API 版本头</td><td>无</td><td>x-api-key + anthropic-version</td></tr>
 *   <tr><td>system 消息</td><td>放在 messages[0].role=system</td><td>顶层 system 字段（字符串或数组）</td></tr>
 *   <tr><td>thinking</td><td>thinking.type=enabled/disabled</td><td>thinking={type, budget_tokens}</td></tr>
 *   <tr><td>max_tokens</td><td>可选</td><td>必填（Claude 硬性要求）</td></tr>
 *   <tr><td>工具格式</td><td>{type, function: {name, description, parameters}}</td><td>{name, description, input_schema}</td></tr>
 *   <tr><td>SSE 事件</td><td>data: {"choices":[{delta:{}}]}</td><td>event: content_block_delta / data: {delta:{text:"..."}}</td></tr>
 *   <tr><td>响应 content</td><td>choices[0].message.content (string)</td><td>content[{type:"text", text:"..."}] (数组)</td></tr>
 * </table>
 */
@Slf4j
public class AnthropicClient extends AbstractLLMClient {

    private static final String CHAT_ENDPOINT = "/v1/messages";
    private static final String API_VERSION = "2023-06-01";
    private static final int DEFAULT_MAX_TOKENS = 4096;
    private static final int THINKING_BUDGET_TOKENS = 4000;

    public AnthropicClient(WebClient webClient, ObjectMapper objectMapper, String providerCode) {
        super(webClient, objectMapper, providerCode);
    }

    @Override
    public String getChatEndpoint() {
        return CHAT_ENDPOINT;
    }

    // ==================== 请求体构建 ====================

    @Override
    protected Map<String, Object> buildThinkingParams(String thinkingMode) {
        // Anthropic 将 thinking 放在顶层，而非消息参数
        // 这里返回空，由 customizeRequest 统一处理
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
        // ===== 1. Claude 要求 max_tokens 必填 =====
        request.put("max_tokens", DEFAULT_MAX_TOKENS);

        // ===== 2. 提取 system 消息到顶层 =====
        List<Map<String, Object>> conversationMessages = new ArrayList<>();
        List<String> systemContents = new ArrayList<>();

        for (Map<String, Object> msg : messages) {
            String role = (String) msg.get("role");
            if ("system".equals(role)) {
                Object content = msg.get("content");
                if (content instanceof String s) {
                    systemContents.add(s);
                } else if (content instanceof List<?> list) {
                    for (Object item : list) {
                        if (item instanceof Map<?, ?> itemMap) {
                            Object text = itemMap.get("text");
                            if (text instanceof String s) systemContents.add(s);
                        }
                    }
                }
            } else {
                conversationMessages.add(msg);
            }
        }

        if (!systemContents.isEmpty()) {
            request.put("system", systemContents.size() == 1
                    ? systemContents.get(0) : systemContents);
        }
        // 替换 messages（不含 system）
        request.put("messages", conversationMessages);

        // ===== 3. thinking 参数 =====
        String effectiveMode = (thinkingMode == null || thinkingMode.isEmpty())
                ? "non-thinking" : thinkingMode;

        if (!"non-thinking".equals(effectiveMode)) {
            Map<String, Object> thinking = new HashMap<>();
            thinking.put("type", "enabled");
            thinking.put("budget_tokens", THINKING_BUDGET_TOKENS);
            request.put("thinking", thinking);
        } else {
            Map<String, Object> thinking = new HashMap<>();
            thinking.put("type", "disabled");
            request.put("thinking", thinking);
        }

        // ===== 4. 转换工具格式：OpenAI → Anthropic =====
        if (tools != null && !tools.isEmpty()) {
            List<Map<String, Object>> anthropicTools = new ArrayList<>();
            for (Map<String, Object> openaiTool : tools) {
                // OpenAI: { type: "function", function: { name, description, parameters } }
                Object fnObj = openaiTool.get("function");
                if (fnObj instanceof Map) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> fnMap = (Map<String, Object>) fnObj;
                    Map<String, Object> anthropicTool = new HashMap<>();
                    anthropicTool.put("name", fnMap.get("name"));
                    anthropicTool.put("description", fnMap.getOrDefault("description", ""));
                    anthropicTool.put("input_schema", fnMap.getOrDefault("parameters",
                            Map.of("type", "object", "properties", Map.of())));
                    anthropicTools.add(anthropicTool);
                }
            }
            request.put("tools", anthropicTools);
            request.remove("tool_choice"); // Anthropic 不支持 tool_choice: auto
        }
    }

    // ==================== 响应解析（Anthropic 特有格式） ====================

    @Override
    public String extractContentFromStreamChunk(String sseChunk) {
        if (sseChunk == null || sseChunk.isEmpty()) {
            return null;
        }
        try {
            JsonNode node = objectMapper.readTree(sseChunk);
            // Anthropic SSE: {"type":"content_block_delta","delta":{"type":"text_delta","text":"..."}}
            String type = node.path("type").asText("");
            if ("content_block_delta".equals(type)) {
                JsonNode delta = node.path("delta");
                if ("text_delta".equals(delta.path("type").asText(""))) {
                    String text = delta.path("text").asText("");
                    return text.isEmpty() ? null : text;
                }
            }
            // content_block_start 中的 text 也可能有内容
            if ("content_block_start".equals(type)) {
                JsonNode block = node.path("content_block");
                if ("text".equals(block.path("type").asText(""))) {
                    String text = block.path("text").asText("");
                    return text.isEmpty() ? null : text;
                }
            }
            // ping 事件忽略
            if ("ping".equals(type)) return null;
        } catch (Exception e) {
            log.trace("Anthropic SSE 块解析失败: {}", e.getMessage());
        }
        return null;
    }

    @Override
    public String extractReasoningFromStreamChunk(String sseChunk) {
        if (sseChunk == null || sseChunk.isEmpty()) return null;
        try {
            JsonNode node = objectMapper.readTree(sseChunk);
            String type = node.path("type").asText("");
            if ("content_block_delta".equals(type)) {
                JsonNode delta = node.path("delta");
                // Anthropic 的 thinking_delta
                if ("thinking_delta".equals(delta.path("type").asText(""))) {
                    return delta.path("thinking").asText(null);
                }
            }
        } catch (Exception e) {
            log.trace("Anthropic reasoning 解析失败: {}", e.getMessage());
        }
        return null;
    }

    @Override
    public String extractContentFromBlockingResponse(String responseBody) {
        if (responseBody == null || responseBody.isEmpty()) return null;
        try {
            JsonNode root = objectMapper.readTree(responseBody);
            // Anthropic: { "content": [ { "type": "text", "text": "..." }, ... ] }
            JsonNode content = root.path("content");
            if (content.isArray()) {
                StringBuilder sb = new StringBuilder();
                for (JsonNode block : content) {
                    if ("text".equals(block.path("type").asText(""))) {
                        String text = block.path("text").asText("");
                        if (!text.isEmpty()) sb.append(text);
                    }
                }
                String result = sb.toString();
                return result.isEmpty() ? null : result.trim();
            }
        } catch (Exception e) {
            log.warn("Anthropic 阻塞响应解析失败: {}", e.getMessage());
        }
        return null;
    }

    // ==================== 流式请求（添加 Anthropic 特有 Headers） ====================

    @Override
    public Flux<String> streamChat(Map<String, Object> requestBody) {
        return webClient.post()
                .uri(CHAT_ENDPOINT)
                .contentType(MediaType.APPLICATION_JSON)
                .header("anthropic-version", API_VERSION)
                .bodyValue(requestBody)
                .accept(MediaType.TEXT_EVENT_STREAM)
                .retrieve()
                .bodyToFlux(String.class)
                .timeout(DEFAULT_STREAM_TIMEOUT)
                .doOnError(org.springframework.web.reactive.function.client.WebClientResponseException.class, e -> {
                    String body = e.getResponseBodyAsString();
                    log.error("Anthropic 流式调用失败 status={}: {}",
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
                    .header("anthropic-version", API_VERSION)
                    .bodyValue(requestBody)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block(effectiveTimeout);
        } catch (org.springframework.web.reactive.function.client.WebClientResponseException e) {
            String body = e.getResponseBodyAsString();
            log.error("Anthropic 阻塞调用失败 status={}: {}",
                    e.getStatusCode().value(),
                    body != null && body.length() > 500 ? body.substring(0, 500) + "..." : body);
            throw new RuntimeException("Anthropic API调用失败: " + e.getStatusCode() + " - "
                    + (body != null && body.length() > 200 ? body.substring(0, 200) : body), e);
        } catch (Exception e) {
            log.error("Anthropic 阻塞调用异常: {}", e.getMessage());
            throw new RuntimeException("Anthropic API调用失败: " + e.getMessage(), e);
        }
    }
}
