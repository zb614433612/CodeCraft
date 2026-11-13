package com.example.agentdeepseek.controller;

import com.example.agentdeepseek.common.response.ApiResponse;
import com.example.agentdeepseek.model.entity.LlmTokenUsage;
import com.example.agentdeepseek.model.vo.TokenUsageGroupVO;
import com.example.agentdeepseek.model.vo.TokenUsageSummaryVO;
import com.example.agentdeepseek.model.vo.TokenUsageTurnVO;
import com.example.agentdeepseek.service.TokenUsageService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * LLM 成本统计接口
 * <p>
 * 数据来自每轮真实 usage（{@code llm_token_usage} 表），用于成本看板与缓存诊断。
 * 所有金额均为<b>估算值</b>，最终以官方平台账单为准。
 * </p>
 */
@Slf4j
@RestController
@RequestMapping("/api/token-usage")
@Tag(name = "成本统计", description = "LLM 调用用量与成本看板：汇总 / 按模型 / 按会话 / 轮次明细")
public class TokenUsageController {

    private final TokenUsageService tokenUsageService;

    public TokenUsageController(TokenUsageService tokenUsageService) {
        this.tokenUsageService = tokenUsageService;
    }

    @Operation(summary = "汇总统计", description = "返回指定时间范围（或指定会话）的用量与费用汇总，含缓存命中率与三段费用拆分")
    @GetMapping("/summary")
    public ApiResponse<TokenUsageSummaryVO> summary(
            @Parameter(description = "时间范围：today / 7d / 30d / all，默认 today")
            @RequestParam(required = false, defaultValue = "today") String range,
            @Parameter(description = "指定会话ID（传了则忽略 range，返回该会话全生命周期汇总）")
            @RequestParam(required = false) Long conversationId) {
        return ApiResponse.success(tokenUsageService.summary(range, conversationId));
    }

    @Operation(summary = "按模型统计", description = "按模型分组返回用量与费用，按费用倒序")
    @GetMapping("/by-model")
    public ApiResponse<List<TokenUsageGroupVO>> byModel(
            @Parameter(description = "时间范围：today / 7d / 30d / all，默认 7d")
            @RequestParam(required = false, defaultValue = "7d") String range) {
        return ApiResponse.success(tokenUsageService.groupByModel(range));
    }

    @Operation(summary = "按会话统计", description = "按会话分组返回费用最高的前 N 个会话，附带会话名")
    @GetMapping("/by-conversation")
    public ApiResponse<List<TokenUsageGroupVO>> byConversation(
            @Parameter(description = "时间范围：today / 7d / 30d / all，默认 7d")
            @RequestParam(required = false, defaultValue = "7d") String range,
            @Parameter(description = "返回条数上限，默认 10，最大 100")
            @RequestParam(required = false, defaultValue = "10") Integer limit) {
        return ApiResponse.success(tokenUsageService.groupByConversation(range, limit));
    }

    @Operation(summary = "轮次明细", description = "返回指定会话每一轮请求的用量明细（含轮次、命中率、费用），用于定位轮次膨胀")
    @GetMapping("/detail")
    public ApiResponse<List<LlmTokenUsage>> detail(
            @Parameter(description = "会话ID", required = true)
            @RequestParam Long conversationId) {
        return ApiResponse.success(tokenUsageService.detailByConversation(conversationId));
    }

    @Operation(summary = "按轮次聚合",
            description = "返回指定会话每一轮对话（一次用户提问，含其工具循环内多次请求）的成本/token/缓存命中率，"
                    + "供聊天页在每条 AI 回复下展示用量；存量数据无 turnId，只计入会话汇总")
    @GetMapping("/turns")
    public ApiResponse<List<TokenUsageTurnVO>> turns(
            @Parameter(description = "会话ID", required = true)
            @RequestParam Long conversationId) {
        return ApiResponse.success(tokenUsageService.turnUsage(conversationId));
    }
}
