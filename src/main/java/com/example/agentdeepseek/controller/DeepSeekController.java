package com.example.agentdeepseek.controller;

import com.example.agentdeepseek.model.dto.ChatRequest;
import com.example.agentdeepseek.model.dto.PendingQuestion;
import com.example.agentdeepseek.model.entity.AgentTask;
import com.example.agentdeepseek.service.AttachmentReaderService;
import com.example.agentdeepseek.service.AttachmentStore;
import com.example.agentdeepseek.service.DeepSeekService;
import com.example.agentdeepseek.service.PendingQuestionStore;
import com.example.agentdeepseek.service.SupplementStore;
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
    private final AttachmentStore attachmentStore;
    private final SupplementStore supplementStore;

    public DeepSeekController(DeepSeekService deepSeekService,
                               PendingQuestionStore pendingQuestionStore,
                               AttachmentReaderService attachmentReaderService,
                               AttachmentStore attachmentStore,
                               SupplementStore supplementStore) {
        this.deepSeekService = deepSeekService;
        this.pendingQuestionStore = pendingQuestionStore;
        this.attachmentReaderService = attachmentReaderService;
        this.attachmentStore = attachmentStore;
        this.supplementStore = supplementStore;
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
     * action 取值：approve（同意）/ approve_all（本轮对话全部同意）/ reject（拒绝）/ custom（其他，需输入消息）
     */
    @Operation(summary = "回答ask_user问题", description = "用户回答LLM提出的问题，回答会传递给正在等待的流")
    @PostMapping("/answer")
    public Map<String, Object> answerQuestion(@RequestBody Map<String, String> body) {
        String uuid = body.get("uuid");
        String answer = body.get("answer");
        String action = body.get("action");

        if (uuid == null || uuid.isEmpty() || answer == null) {
            return Map.of("success", false, "error", "缺少必要参数 uuid 或 answer");
        }

        PendingQuestion pq = pendingQuestionStore.get(uuid);
        if (pq == null) {
            log.warn("问题不存在或已超时: uuid={}", uuid);
            return Map.of("success", false, "error", "问题不存在或已超时，请重新发送消息");
        }

        // 默认 action 为 approve（兼容旧版本前端）
        if (action == null || action.isEmpty()) {
            action = "approve";
        }

        // 将 action 和 answer 组合后传递给等待的流
        // 格式: "__ACTION__:actionType:userMessage"
        String combined = "__ACTION__:" + action + ":" + (answer != null ? answer : "");

        // 完成 CompletableFuture，唤醒等待的流
        boolean completed = pq.getFuture().complete(combined);
        if (!completed) {
            log.warn("问题已被回答: uuid={}", uuid);
            return Map.of("success", false, "error", "该问题已被回答过");
        }

        pendingQuestionStore.remove(uuid);
        log.info("用户回答了问题: uuid={}, action={}, answer={}", uuid, action, answer);
        return Map.of("success", true);
    }

    /**
     * 补充需求（中途注入）
     * 用户在 AI 执行过程中主动发送补充需求，不走 cancel+rebuild 流程，
     * 而是将消息注入到正在执行的 ToolLoop 中。
     */
    @Operation(summary = "补充需求", description = "在AI执行过程中补充需求，消息会注入到当前ToolLoop的下一轮迭代中")
    @PostMapping("/supplement")
    public Map<String, Object> supplement(@RequestBody Map<String, String> body) {
        String conversationIdStr = body.get("conversationId");
        String message = body.get("message");

        if (conversationIdStr == null || conversationIdStr.isEmpty() || message == null || message.trim().isEmpty()) {
            return Map.of("success", false, "error", "缺少必要参数 conversationId 或 message");
        }

        Long conversationId;
        try {
            conversationId = Long.parseLong(conversationIdStr);
        } catch (NumberFormatException e) {
            return Map.of("success", false, "error", "conversationId 格式不正确");
        }

        // 检查是否有正在运行的任务（软检查，仅记日志，不阻塞补充消息入队）
        // 因为 agent_task 表查询可能存在边界条件（INSERT 延迟/失败但 toolLoop 仍在运行等），
        // 补充消息放入 SupplementStore 队列后，运行中的 toolLoop 会自动消费，不在运行的会被下次清理。
        AgentTask task = deepSeekService.getActiveTask(conversationId);
        if (task == null) {
            log.warn("getActiveTask 返回 null，但补充消息仍会入队: conversationId={}", conversationId);
        }

        // 将补充消息放入队列，ToolLoop 下一轮迭代会自动取出
        supplementStore.offer(conversationId, message.trim());
        log.info("用户补充需求: conversationId={}, message={}", conversationId, message);
        return Map.of("success", true, "message", "补充需求已发送，AI 将在下一轮处理中收到");
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
     * 接收文件并暂存到临时目录，返回 attachmentId 和元数据。
     * LLM 通过 chat_attachment 工具按需读取内容（支持PDF/Word/Excel）。
     */
    @Operation(summary = "上传附件", description = "上传文件并暂存，返回附件ID。LLM可通过 chat_attachment 工具读取内容（支持PDF/Word/Excel）")
    @PostMapping("/upload")
    public Map<String, Object> uploadAttachment(@RequestParam("file") MultipartFile file) {
        log.info("收到附件上传: fileName={}, size={}", file.getOriginalFilename(), file.getSize());

        try {
            String attachmentId = attachmentStore.store(file);
            AttachmentStore.AttachmentMeta meta = attachmentStore.getMeta(attachmentId);

            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("attachmentId", attachmentId);
            response.put("fileName", meta.getFileName());
            response.put("extension", meta.getExtension());
            response.put("size", meta.getSize());
            response.put("type", meta.getType());
            return response;

        } catch (Exception e) {
            log.error("附件上传失败: {}", file.getOriginalFilename(), e);
            Map<String, Object> response = new HashMap<>();
            response.put("success", false);
            response.put("fileName", file.getOriginalFilename());
            response.put("error", "上传失败: " + e.getMessage());
            return response;
        }
    }
}
