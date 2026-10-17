package com.example.agentdeepseek.controller;

import com.example.agentdeepseek.common.response.ApiResponse;
import com.example.agentdeepseek.mapper.ConversationMapper;
import com.example.agentdeepseek.model.entity.Conversation;
import com.example.agentdeepseek.model.entity.ConversationMessage;
import com.example.agentdeepseek.model.vo.ConversationMessagePageVO;
import com.example.agentdeepseek.service.ConversationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 会话控制器
 */
@Slf4j
@RestController
@RequestMapping("/api/conversation")
@Tag(name = "会话管理", description = "会话列表查询等接口")
public class ConversationController {

    private final ConversationService conversationService;
    private final ConversationMapper conversationMapper;

    @Autowired
    public ConversationController(ConversationService conversationService, ConversationMapper conversationMapper) {
        this.conversationService = conversationService;
        this.conversationMapper = conversationMapper;
    }

    /**
     * 查询当前用户的会话列表（不分页）
     * 通过Authorization头中的token获取用户信息，根据用户ID查询会话列表
     */
    @Operation(summary = "查询会话列表", description = "查询当前用户的会话列表（不分页），支持按 agentType 筛选，需要Authorization头")
    @GetMapping("/list")
    public ApiResponse<List<Conversation>> getConversationList(
            @Parameter(description = "用户ID，由TokenAuthenticationFilter自动注入", hidden = true)
            @RequestAttribute("userId") Long userId,
            @Parameter(description = "会话类型筛选，为空则查询所有")
            @RequestParam(value = "agentType", required = false) String agentType,
            @Parameter(description = "Agent配置ID筛选（切换Agent时用，优先于agentType）")
            @RequestParam(value = "agentConfigId", required = false) Long agentConfigId) {
        log.info("查询会话列表请求: userId={}, agentType={}, agentConfigId={}", userId, agentType, agentConfigId);
        List<Conversation> conversations;
        if (agentConfigId != null) {
            conversations = conversationService.getConversationsByAgentConfigId(userId, agentConfigId);
        } else {
            conversations = conversationService.getConversationsByUserId(userId, agentType);
        }
        return ApiResponse.success(conversations);
    }

    /**
     * 根据会话ID查询消息列表（不分页）
     * 需要验证会话属于当前用户
     */
    @Operation(summary = "查询会话消息列表", description = "根据会话ID查询消息列表（不分页），需要Authorization头")
    @GetMapping("/{conversationId}/messages")
    public ApiResponse<List<ConversationMessage>> getConversationMessages(
            @Parameter(description = "会话ID", required = true)
            @PathVariable Long conversationId,
            @Parameter(description = "用户ID，由TokenAuthenticationFilter自动注入", hidden = true)
            @RequestAttribute("userId") Long userId) {
        log.info("查询会话消息请求: conversationId={}, userId={}", conversationId, userId);
        // 验证会话是否存在且属于当前用户
        Conversation conversation = conversationMapper.selectById(conversationId).orElse(null);
        if (conversation == null || conversation.getUserId() == null || !conversation.getUserId().equals(userId)) {
            return ApiResponse.error(404, "会话不存在或无权访问");
        }
        List<ConversationMessage> messages = conversationService.getMessagesByConversationId(conversationId);
        return ApiResponse.success(messages);
    }

    /**
     * 分页查询会话消息（按「轮次」游标，从最新往前翻）——聊天记录懒加载入口。
     *
     * <p>改造背景：超长会话全量查询会导致 DB 扫描、网络传输与前端分组渲染全部变重，表现为「打开会话很慢」。
     * 现改为默认只取最近 5 轮，用户向上滑动到顶部时再用返回的 nextBeforeId 继续加载更早的 5 轮。</p>
     */
    @Operation(summary = "分页查询会话消息", description = "按轮次游标分页（默认最近 5 轮），用于聊天记录懒加载："
            + "向上滚动到顶部时用返回的 nextBeforeId 继续加载更早历史，需要Authorization头")
    @GetMapping("/{conversationId}/messages/page")
    public ApiResponse<ConversationMessagePageVO> getConversationMessagesPage(
            @Parameter(description = "会话ID", required = true)
            @PathVariable Long conversationId,
            @Parameter(description = "分页游标：上一页返回的 nextBeforeId（首页不传）")
            @RequestParam(value = "beforeId", required = false) Long beforeId,
            @Parameter(description = "本次加载的轮数，默认 5（一轮=一次用户提问+其后全部回复）")
            @RequestParam(value = "rounds", required = false, defaultValue = "5") Integer rounds,
            @Parameter(description = "用户ID，由TokenAuthenticationFilter自动注入", hidden = true)
            @RequestAttribute("userId") Long userId) {
        log.info("分页查询会话消息请求: conversationId={}, beforeId={}, rounds={}, userId={}",
                conversationId, beforeId, rounds, userId);
        // 验证会话是否存在且属于当前用户（与全量接口同一权限口径）
        Conversation conversation = conversationMapper.selectById(conversationId).orElse(null);
        if (conversation == null || conversation.getUserId() == null || !conversation.getUserId().equals(userId)) {
            return ApiResponse.error(404, "会话不存在或无权访问");
        }
        ConversationMessagePageVO page = conversationService.getMessagesPage(conversationId, beforeId, rounds == null ? 5 : rounds);
        return ApiResponse.success(page);
    }

    /**
     * 更新会话名称
     */
    @Operation(summary = "更新会话名称", description = "更新指定会话的name字段（会话标题）")
    @PutMapping("/{conversationId}")
    public ApiResponse<Void> updateConversation(
            @Parameter(description = "会话ID", required = true)
            @PathVariable Long conversationId,
            @Parameter(description = "新的会话名称", required = true)
            @RequestParam("name") String name,
            @RequestAttribute("userId") Long userId) {
        log.info("更新会话名称请求: conversationId={}, name={}, userId={}", conversationId, name, userId);
        // 验证会话是否存在且属于当前用户
        Conversation conversation = conversationMapper.selectById(conversationId)
                .orElse(null);
        if (conversation == null || conversation.getUserId() == null || !conversation.getUserId().equals(userId)) {
            return ApiResponse.error(404, "会话不存在或无权访问");
        }

        conversation.setName(name);
        conversation.setUpdatedAt(java.time.LocalDateTime.now());
        conversationMapper.update(conversation);
        log.info("会话名称更新成功，conversationId: {}", conversationId);
        return ApiResponse.success(null, "更新成功");
    }

    /**
     * 删除会话及其关联消息
     * 需要验证会话属于当前用户
     */
    @Operation(summary = "删除会话", description = "删除指定会话及其所有消息，需要Authorization头")
    @DeleteMapping("/{conversationId}")
    public ApiResponse<Void> deleteConversation(
            @Parameter(description = "会话ID", required = true)
            @PathVariable Long conversationId,
            @Parameter(description = "用户ID，由TokenAuthenticationFilter自动注入", hidden = true)
            @RequestAttribute("userId") Long userId) {
        log.info("删除会话请求: conversationId={}, userId={}", conversationId, userId);
        // 验证会话是否存在且属于当前用户
        Conversation conversation = conversationMapper.selectById(conversationId)
                .orElse(null);
        if (conversation == null || conversation.getUserId() == null || !conversation.getUserId().equals(userId)) {
            return ApiResponse.error(404, "会话不存在或无权访问");
        }
        boolean success = conversationService.deleteConversation(conversationId);
        if (success) {
            return ApiResponse.success(null);
        } else {
            return ApiResponse.error(500, "删除会话失败");
        }
    }
}