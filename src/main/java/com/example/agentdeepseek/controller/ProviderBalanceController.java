package com.example.agentdeepseek.controller;

import com.example.agentdeepseek.common.enums.ResponseEnum;
import com.example.agentdeepseek.common.response.ApiResponse;
import com.example.agentdeepseek.model.vo.ProviderBalanceVO;
import com.example.agentdeepseek.service.llm.ProviderBalanceService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * LLM Provider 余额查询 REST 控制器
 * <p>
 * 供聊天页面展示余额使用（登录用户即可访问，无需管理员权限——
 * 与 {@link LLMProviderController} 的管理员 CRUD 接口不同）。
 * 目前仅 DeepSeek 平台支持余额查询。
 * </p>
 */
@Slf4j
@RestController
@RequestMapping("/api/llm-providers")
@Tag(name = "LLM Provider 余额", description = "查询 Provider 账户余额（目前仅 DeepSeek 支持）")
public class ProviderBalanceController {

    private final ProviderBalanceService providerBalanceService;

    public ProviderBalanceController(ProviderBalanceService providerBalanceService) {
        this.providerBalanceService = providerBalanceService;
    }

    @GetMapping("/{code}/balance")
    @Operation(summary = "查询 Provider 余额", description = "查询指定 Provider 的账户余额（目前仅 DeepSeek 支持）；每次进入聊天页面及每次对话结束后由前端调用")
    public ApiResponse<ProviderBalanceVO> getBalance(@PathVariable String code) {
        try {
            ProviderBalanceVO balance = providerBalanceService.queryBalance(code);
            return ApiResponse.success(balance);
        } catch (IllegalArgumentException e) {
            return ApiResponse.error(ResponseEnum.BAD_REQUEST, e.getMessage());
        } catch (Exception e) {
            log.warn("余额查询失败: providerCode={}, error={}", code, e.getMessage());
            return ApiResponse.error(ResponseEnum.ERROR, e.getMessage());
        }
    }
}
