package com.example.agentdeepseek.controller;

import com.example.agentdeepseek.model.dto.ChatRequest;
import com.example.agentdeepseek.model.dto.PendingQuestion;
import com.example.agentdeepseek.service.DeepSeekService;
import com.example.agentdeepseek.service.PendingQuestionStore;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.*;
import jakarta.servlet.http.HttpServletRequest;
import reactor.core.publisher.Flux;

import java.util.Map;


/**
 * DeepSeek API流式响应控制器
 * 提供调用DeepSeek V4-Pro模型的流式接口
 */
@Slf4j
@RestController
@RequestMapping("/api/deepseek")
@Tag(name = "DeepSeek API", description = "DeepSeek V4-Pro模型流式响应接口")
public class DeepSeekController {

    private final DeepSeekService deepSeekService;
    private final PendingQuestionStore pendingQuestionStore;

    public DeepSeekController(DeepSeekService deepSeekService, PendingQuestionStore pendingQuestionStore) {
        this.deepSeekService = deepSeekService;
        this.pendingQuestionStore = pendingQuestionStore;
    }

    /**
     * 流式聊天接口
     * 接收用户消息并返回DeepSeek模型的流式响应
     *
     * @param request 聊天请求，包含消息内容
     * @param httpServletRequest HTTP请求对象，用于获取用户ID
     * @return Server-Sent Events流
     */
    @Operation(summary = "流式聊天", description = "调用DeepSeek V4-Pro模型进行流式聊天")
    @PostMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> streamChat(
            @Parameter(description = "聊天请求，包含消息内容")
            @RequestBody ChatRequest request,
            HttpServletRequest httpServletRequest) {

        log.info("收到流式聊天请求: {}", request.getMessage());

        // 从请求属性中获取用户ID
        Long userId = (Long) httpServletRequest.getAttribute("userId");
        request.setUserId(userId);
        log.debug("设置用户ID: {}", userId);

        // 调用服务层获取流式响应
        return deepSeekService.streamChat(request)
                .map(chunk -> ServerSentEvent.builder(chunk).build())
                .doOnError(error -> log.error("DeepSeek API调用失败", error))
                .doOnComplete(() -> log.info("流式响应完成"));
    }

    /**
     * 回答 ask_user 问题
     */
    @Operation(summary = "回答ask_user问题", description = "用户回答LLM提出的问题，回答会传递给正在等待的流")
    @PostMapping("/answer")
    public Map<String, Object> answerQuestion(@RequestBody Map<String, String> body) {
        String uuid = body.get("uuid");
        String answer = body.get("answer");

        if (uuid == null || uuid.isEmpty() || answer == null) {
            return Map.of("success", false, "error", "缺少必要参数 uuid 或 answer");
        }

        PendingQuestion pq = pendingQuestionStore.get(uuid);
        if (pq == null) {
            log.warn("问题不存在或已超时: uuid={}", uuid);
            return Map.of("success", false, "error", "问题不存在或已超时，请重新发送消息");
        }

        // 完成 CompletableFuture，唤醒等待的流
        boolean completed = pq.getFuture().complete(answer);
        if (!completed) {
            log.warn("问题已被回答: uuid={}", uuid);
            return Map.of("success", false, "error", "该问题已被回答过");
        }

        pendingQuestionStore.remove(uuid);
        log.info("用户回答了问题: uuid={}, answer={}", uuid, answer);
        return Map.of("success", true);
    }


}