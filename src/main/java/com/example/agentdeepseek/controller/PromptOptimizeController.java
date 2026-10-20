package com.example.agentdeepseek.controller;

import com.example.agentdeepseek.service.PromptOptimizeService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 提示词优化控制器
 * 提供用户消息优化接口
 */
@Slf4j
@RestController
@RequestMapping("/api/prompt")
@Tag(name = "提示词优化", description = "对用户消息进行LLM优化，使其更清晰明确")
public class PromptOptimizeController {

    private final PromptOptimizeService promptOptimizeService;

    public PromptOptimizeController(PromptOptimizeService promptOptimizeService) {
        this.promptOptimizeService = promptOptimizeService;
    }

    /**
     * 优化用户提示词
     *
     * <p>返回**裸结构**（没有 {@code code/data} 包装），与旧前端保持兼容：
     * {@code { success, optimized, error?, elapsedMs?, model? }}。
     * 前端据此区分「已优化」「原文已够好」「接口/模型失败」，而不是一律提示「没有得到更好的表述」。</p>
     *
     * @param body 包含 message 字段的请求体
     * @return 优化结果
     */
    @Operation(summary = "优化提示词", description = "使用LLM对用户消息进行优化，使表达更清晰明确")
    @PostMapping("/optimize")
    public Map<String, Object> optimize(@RequestBody(required = false) Map<String, String> body) {
        String message = body == null ? null : body.get("message");
        if (message == null || message.trim().isEmpty()) {
            Map<String, Object> empty = new LinkedHashMap<>();
            empty.put("success", false);
            empty.put("optimized", message == null ? "" : message);
            empty.put("error", "消息不能为空");
            empty.put("elapsedMs", 0L);
            return empty;
        }

        log.info("收到提示词优化请求: 消息长度={}", message.length());
        PromptOptimizeService.OptimizeResult result = promptOptimizeService.optimize(message);

        // LinkedHashMap 而非 Map.of：error/model 在成功/失败时可能为 null，Map.of 不允许 null 值
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("success", result.success());
        payload.put("optimized", result.optimized() == null ? message : result.optimized());
        if (result.error() != null) {
            payload.put("error", result.error());
        }
        payload.put("elapsedMs", result.elapsedMs());
        if (result.model() != null) {
            payload.put("model", result.model());
        }
        return payload;
    }
}
