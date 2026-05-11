package com.example.agentdeepseek.controller;

import com.example.agentdeepseek.model.dto.ChatRequest;
import com.example.agentdeepseek.model.dto.PendingQuestion;
import com.example.agentdeepseek.model.entity.AgentTask;
import com.example.agentdeepseek.service.AttachmentReaderService;
import com.example.agentdeepseek.service.DeepSeekService;
import com.example.agentdeepseek.service.PendingQuestionStore;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import jakarta.servlet.http.HttpServletRequest;
import reactor.core.publisher.Flux;

import java.util.HashMap;
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
    private final AttachmentReaderService attachmentReaderService;

    public DeepSeekController(DeepSeekService deepSeekService,
                              PendingQuestionStore pendingQuestionStore,
                              AttachmentReaderService attachmentReaderService) {
        this.deepSeekService = deepSeekService;
        this.pendingQuestionStore = pendingQuestionStore;
        this.attachmentReaderService = attachmentReaderService;
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

    // ===== 后台任务管理 =====

    /**
     * 检查会话是否有正在运行的后台任务
     */
    @Operation(summary = "检查活跃任务", description = "查询指定会话是否有正在执行的后台任务，包含待审批问题信息（用于页面刷新后重连展示审批对话框）")
    @GetMapping("/task/{conversationId}")
    public Map<String, Object> getActiveTask(@PathVariable Long conversationId) {
        AgentTask task = deepSeekService.getActiveTask(conversationId);
        if (task != null) {
            Map<String, Object> result = new HashMap<>();
            result.put("active", true);
            result.put("taskId", task.getId());
            result.put("status", task.getStatus());
            result.put("iteration", task.getIteration());
            result.put("eventCount", task.getEventCount());
            if (task.getPendingQuestionUuid() != null) {
                result.put("pendingQuestionUuid", task.getPendingQuestionUuid());
                result.put("pendingQuestionText", task.getPendingQuestionText());
            }
            return result;
        }
        return Map.of("active", false);
    }

    /**
     * 订阅后台任务的事件流（用于页面刷新后重连）
     * 返回 SSE 流，与 /chat/stream 格式一致
     */
    @Operation(summary = "订阅任务事件流", description = "重连到正在执行的后台任务，接收实时事件")
    @GetMapping(value = "/task/{conversationId}/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> subscribeTask(@PathVariable Long conversationId) {
        return deepSeekService.subscribeToTask(conversationId)
                .map(chunk -> ServerSentEvent.builder(chunk).build())
                .doOnCancel(() -> log.info("任务事件流客户端断开: conversationId={}", conversationId));
    }

    /**
     * 取消正在运行的后台任务
     */
    @Operation(summary = "取消后台任务")
    @PostMapping("/task/{conversationId}/cancel")
    public Map<String, Object> cancelTask(@PathVariable Long conversationId) {
        deepSeekService.cancelTask(conversationId);
        return Map.of("success", true, "message", "任务已取消");
    }


    // ===== 附件上传 =====

    /**
     * 上传附件文件
     * 接收文件并读取文本内容，返回给前端用于拼接到用户消息中
     */
    @Operation(summary = "上传附件", description = "上传文件并读取文本内容，支持文本/代码/图片文件")
    @PostMapping("/upload")
    public Map<String, Object> uploadAttachment(@RequestParam("file") MultipartFile file) {
        log.info("收到附件上传: fileName={}, size={}", file.getOriginalFilename(), file.getSize());

        AttachmentReaderService.AttachmentResult result = attachmentReaderService.read(file);

        Map<String, Object> response = new HashMap<>();
        response.put("success", result.isSuccess());
        response.put("fileName", result.getFileName());
        response.put("extension", result.getExtension());
        response.put("size", result.getSize());
        response.put("content", result.getContent());
        response.put("image", result.isImage());
        response.put("language", result.getLanguage());
        if (result.getError() != null) {
            response.put("error", result.getError());
        }

        return response;
    }
}
