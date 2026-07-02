package com.example.agentdeepseek.service;

import com.example.agentdeepseek.service.llm.LLMClientManager;
import com.example.agentdeepseek.service.llm.LLMClient;
import com.example.agentdeepseek.util.PromptUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * 提示词优化服务
 * 使用 DeepSeek Flash 模型对用户消息进行非流式优化，使其更清晰明确
 */
@Slf4j
@Service
public class PromptOptimizeService {

    private final LLMClientManager llmClientManager;

    private static final String OPTIMIZE_PROMPT_FILE = "prompt_optimize.txt";
    private static final String FLASH_MODEL = "deepseek-v4-flash";
    private static final int MAX_TOKENS = 500;
    private static final int TIMEOUT_SECONDS = 15;
    private static final double TEMPERATURE = 0.0;

    public PromptOptimizeService(LLMClientManager llmClientManager) {
        this.llmClientManager = llmClientManager;
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

        // 2. 使用默认 Provider 的 LLMClient（API Key 由 LLMWebClientManager 自动管理）
        LLMClient client = llmClientManager.getDefaultClient();
        String model = llmClientManager.getDefaultModel(client.getProviderCode());
        if (model == null || model.isEmpty()) {
            model = FLASH_MODEL;
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

        // 4. 使用 LLMClient 构建请求体（关闭思考模式 + max_tokens）
        Map<String, Object> requestBody = client.buildRequestBody(
                messages, model, TEMPERATURE, "non-thinking", false, null);
        requestBody.put("max_tokens", MAX_TOKENS);

        // 5. 调用 API
        try {
            log.info("提示词优化请求: 消息长度={}, Provider=[{}], model={}",
                    originalMessage.length(), client.getProviderCode(), model);
            String response = client.blockingChat(requestBody, java.time.Duration.ofSeconds(TIMEOUT_SECONDS));

            // 6. 解析响应
            String optimized = client.extractContentFromBlockingResponse(response);
            if (optimized != null && !optimized.isEmpty()) {
                log.info("提示词优化完成: 原始长度={}, 优化后长度={}",
                        originalMessage.length(), optimized.length());
                return optimized.trim();
            }

            log.warn("优化 API 返回的 content 为空");
            return originalMessage;
        } catch (Exception e) {
            log.warn("提示词优化调用失败: {}", e.getMessage());
            return originalMessage;
        }
    }
}
