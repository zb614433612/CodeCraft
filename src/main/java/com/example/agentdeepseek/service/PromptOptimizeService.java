package com.example.agentdeepseek.service;

import com.example.agentdeepseek.util.PromptUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.*;

/**
 * 提示词优化服务
 * 使用 DeepSeek Flash 模型对用户消息进行非流式优化，使其更清晰明确
 */
@Slf4j
@Service
public class PromptOptimizeService {

    private final WebClient webClient;
    private final ConfigService configService;
    private final ObjectMapper objectMapper;

    private static final String OPTIMIZE_PROMPT_FILE = "prompt_optimize.txt";
    private static final String FLASH_MODEL = "deepseek-v4-flash";
    private static final int MAX_TOKENS = 500;
    private static final int TIMEOUT_SECONDS = 15;
    private static final double TEMPERATURE = 0.0;

    public PromptOptimizeService(WebClient.Builder webClientBuilder,
                                 ConfigService configService,
                                 ObjectMapper objectMapper) {
        this.configService = configService;
        this.objectMapper = objectMapper;

        // 构建独立的 WebClient（不依赖静态 API Key，每次请求时动态获取）
        this.webClient = webClientBuilder
                .baseUrl("https://api.deepseek.com")
                .defaultHeader("Content-Type", "application/json")
                .build();
    }

    /**
     * 优化用户消息
     *
     * @param originalMessage 原始用户消息
     * @return 优化后的消息；失败时返回原始消息
     */
    public String optimize(String originalMessage) {
        if (originalMessage == null || originalMessage.trim().isEmpty()) {
            return originalMessage;
        }

        // 1. 加载优化提示词模板
        String optimizePrompt = PromptUtil.getPrompt(OPTIMIZE_PROMPT_FILE);
        if (optimizePrompt.isEmpty()) {
            log.warn("优化提示词模板加载失败，返回原始消息");
            return originalMessage;
        }

        // 2. 获取动态 API Key
        String apiKey = configService.getValue("deepseek_api_key");
        if (apiKey == null || apiKey.isEmpty()) {
            log.warn("未配置 API Key，跳过优化");
            return originalMessage;
        }

        // 3. 构建消息列表
        List<Map<String, Object>> messages = new ArrayList<>();
        Map<String, Object> systemMsg = new HashMap<>();
        systemMsg.put("role", "system");
        systemMsg.put("content", optimizePrompt);
        messages.add(systemMsg);

        Map<String, Object> userMsg = new HashMap<>();
        userMsg.put("role", "user");
        userMsg.put("content", originalMessage);
        messages.add(userMsg);

        // 4. 构建请求体
        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("model", FLASH_MODEL);
        requestBody.put("messages", messages);
        requestBody.put("stream", false);
        requestBody.put("max_tokens", MAX_TOKENS);
        requestBody.put("temperature", TEMPERATURE);

        // 关闭思考模式（优化任务不需要推理过程）
        Map<String, Object> thinking = new HashMap<>();
        thinking.put("type", "disabled");
        requestBody.put("thinking", thinking);

        // 5. 调用 API
        try {
            log.info("提示词优化请求: 消息长度={}", originalMessage.length());
            String response = webClient.post()
                    .uri("/v1/chat/completions")
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("Authorization", "Bearer " + apiKey)
                    .bodyValue(requestBody)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block(Duration.ofSeconds(TIMEOUT_SECONDS));

            if (response == null || response.isEmpty()) {
                log.warn("优化 API 返回空响应");
                return originalMessage;
            }

            // 6. 解析响应
            JsonNode responseNode = objectMapper.readTree(response);
            JsonNode choices = responseNode.path("choices");
            if (choices.isArray() && choices.size() > 0) {
                JsonNode message = choices.get(0).path("message");
                String optimized = message.path("content").asText("");
                if (!optimized.isEmpty()) {
                    log.info("提示词优化完成: 原始长度={}, 优化后长度={}",
                            originalMessage.length(), optimized.length());
                    return optimized.trim();
                }
            }

            log.warn("优化 API 返回的 content 为空");
            return originalMessage;
        } catch (Exception e) {
            log.warn("提示词优化调用失败: {}", e.getMessage());
            return originalMessage;
        }
    }
}
