package com.example.agentdeepseek.service;

import com.example.agentdeepseek.service.llm.LLMClientManager;
import com.example.agentdeepseek.service.llm.LLMClient;
import com.example.agentdeepseek.util.PromptUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.TimeoutException;

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
    /**
     * 输出上限。原值 500 在「默认模型是推理模型」时容易被思考 token 吃满导致 content 为空，
     * 这里放宽到 800；输入侧由前端限制 6000 字，避免「优化后比原文还短」。
     */
    private static final int MAX_TOKENS = 800;
    private static final int TIMEOUT_SECONDS = 15;
    private static final double TEMPERATURE = 0.0;

    public PromptOptimizeService(LLMClientManager llmClientManager) {
        this.llmClientManager = llmClientManager;
    }

    /**
     * 优化结果。
     *
     * <p>为什么要返回结构体而不是「失败就返回原文」：调用方（Controller → 前端）无法
     * 区分「模型认为原文已经够好」和「模板缺失 / 没配 Provider / 超时 / 空响应」，
     * 于是前端只能统一提示「没有得到更好的表述」，用户等十几秒看到一句无关的话。</p>
     *
     * @param success   是否拿到可用的优化结果
     * @param optimized 优化后的文本；失败时回落为原文，便于调用方无脑回填
     * @param error     失败原因（success=true 时为 null）
     * @param elapsedMs 本次耗时（毫秒）
     * @param model     实际使用的模型（便于排查是哪个模型拖慢 / 截断）
     */
    public record OptimizeResult(boolean success, String optimized, String error, long elapsedMs, String model) {

        static OptimizeResult ok(String optimized, long elapsedMs, String model) {
            return new OptimizeResult(true, optimized, null, elapsedMs, model);
        }

        static OptimizeResult fail(String original, String error, long elapsedMs, String model) {
            return new OptimizeResult(false, original, error, elapsedMs, model);
        }
    }

    /**
     * 优化用户消息（兼容旧入口）。
     *
     * @deprecated 改用 {@link #optimize(String, String)} 并显式传入当前会话的 providerCode：
     *             不传 Provider 现在会直接失败，而不是回退默认 Provider（那会扣到另一个账户的钱）。
     */
    @Deprecated
    public OptimizeResult optimize(String originalMessage) {
        return optimize(originalMessage, null);
    }

    /**
     * 优化用户消息（指定 Provider）。
     *
     * @param originalMessage 原始用户消息
     * @param providerCode    当前会话使用的 Provider 编码；为空时拒绝执行
     *                        （避免回退默认 Provider 扣错账户）
     * @return 结构化结果；失败时 {@code optimized} 回落为原文，并带上可读的 {@code error}
     */
    public OptimizeResult optimize(String originalMessage, String providerCode) {
        long startedAt = System.currentTimeMillis();
        if (originalMessage == null || originalMessage.trim().isEmpty()) {
            return OptimizeResult.fail(originalMessage, "消息不能为空", 0L, null);
        }

        String model = null;
        try {
            // 1. 加载优化提示词模板
            String optimizePrompt = PromptUtil.getPrompt(OPTIMIZE_PROMPT_FILE);
            if (optimizePrompt.isEmpty()) {
                log.warn("优化提示词模板加载失败，返回原始消息");
                return OptimizeResult.fail(originalMessage, "优化提示词模板缺失（" + OPTIMIZE_PROMPT_FILE + "）",
                        System.currentTimeMillis() - startedAt, null);
            }

            /*
              ★ 2026-11-17（费用泄露修复 · 提示词优化通道）：不再使用 getDefaultClient()。

              Provider 列表里 deepseek（id=1）与 huoshan（id=34）的 sortOrder 同为 0，
              getDefaultClient() = getFirstClient() 会取 id 更小的 deepseek 官网 ——
              用户在用火山方舟，点一下「优化」却扣了官网余额。
              现在要求调用方（PromptOptimizeController）显式传 providerCode；
              传不到就明确失败并提示，绝不替用户猜账户。
            */
            if (providerCode == null || providerCode.isEmpty()) {
                return OptimizeResult.fail(originalMessage,
                        "未指定 Provider：为避免误用默认 Provider（可能扣到另一个账户的余额），本次不执行优化",
                        System.currentTimeMillis() - startedAt, null);
            }
            LLMClient client = llmClientManager.resolveClientByCode(providerCode);
            if (client == null) {
                return OptimizeResult.fail(originalMessage, "无法解析 Provider [" + providerCode + "]",
                        System.currentTimeMillis() - startedAt, null);
            }
            model = llmClientManager.getDefaultModel(client.getProviderCode());
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
            log.info("提示词优化请求: 消息长度={}, Provider=[{}], model={}",
                    originalMessage.length(), client.getProviderCode(), model);
            String response = client.blockingChat(requestBody, java.time.Duration.ofSeconds(TIMEOUT_SECONDS));

            // 6. 解析响应
            String optimized = client.extractContentFromBlockingResponse(response);
            long elapsed = System.currentTimeMillis() - startedAt;
            if (optimized != null && !optimized.trim().isEmpty()) {
                log.info("提示词优化完成: 原始长度={}, 优化后长度={}, 耗时={}ms",
                        originalMessage.length(), optimized.trim().length(), elapsed);
                return OptimizeResult.ok(optimized.trim(), elapsed, model);
            }

            log.warn("优化 API 返回的 content 为空: model={}, 耗时={}ms", model, elapsed);
            return OptimizeResult.fail(originalMessage,
                    "模型返回空结果（可能不支持非思考模式或输出被 max_tokens 截断）", elapsed, model);
        } catch (Exception e) {
            long elapsed = System.currentTimeMillis() - startedAt;
            String reason = e instanceof TimeoutException
                    ? "模型响应超时（" + TIMEOUT_SECONDS + " 秒）"
                    : "调用模型失败：" + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
            log.warn("提示词优化调用失败: model={}, 耗时={}ms, 原因={}", model, elapsed, reason);
            return OptimizeResult.fail(originalMessage, reason, elapsed, model);
        }
    }
}
