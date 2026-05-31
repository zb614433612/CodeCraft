package com.example.agentdeepseek.controller;

import com.example.agentdeepseek.service.PromptOptimizeService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

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
     * @param body 包含 message 字段的请求体
     * @return 优化结果
     */
    @Operation(summary = "优化提示词", description = "使用LLM对用户消息进行优化，使表达更清晰明确")
    @PostMapping("/optimize")
    public Map<String, Object> optimize(@RequestBody Map<String, String> body) {
        String message = body.get("message");
        if (message == null || message.trim().isEmpty()) {
            return Map.of("success", false, "error", "消息不能为空");
        }

        log.info("收到提示词优化请求: 消息长度={}", message.length());
        String optimized = promptOptimizeService.optimize(message);
        return Map.of("success", true, "optimized", optimized);
    }
}
