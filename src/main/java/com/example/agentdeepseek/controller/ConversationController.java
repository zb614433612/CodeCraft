package com.example.agentdeepseek.controller;

import com.example.agentdeepseek.common.response.ApiResponse;
import com.example.agentdeepseek.mapper.ConversationMapper;
import com.example.agentdeepseek.mapper.UserProfileMapper;
import com.example.agentdeepseek.model.entity.Conversation;
import com.example.agentdeepseek.model.entity.ConversationMessage;
import com.example.agentdeepseek.model.entity.UserProfile;
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
    private final UserProfileMapper userProfileMapper;

    @Autowired
    public ConversationController(ConversationService conversationService, ConversationMapper conversationMapper,
                                  UserProfileMapper userProfileMapper) {
        this.conversationService = conversationService;
        this.conversationMapper = conversationMapper;
        this.userProfileMapper = userProfileMapper;
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
            @Parameter(description = "会话类型筛选：ai_assistant / chat_assistant / code_assistant，为空则查询所有")
            @RequestParam(value = "agentType", required = false) String agentType) {
        log.info("查询会话列表请求: userId={}, agentType={}", userId, agentType);
        List<Conversation> conversations = conversationService.getConversationsByUserId(userId, agentType);
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
        // 验证会话是否存在且属于当前用户（同时检查conversation表和user_profile表）
        boolean valid = false;
        Conversation conversation = conversationMapper.selectById(conversationId).orElse(null);
        if (conversation != null && conversation.getUserId() != null && conversation.getUserId().equals(userId)) {
            valid = true;
        } else {
            UserProfile userProfile = userProfileMapper.selectById(conversationId);
            if (userProfile != null && userId.equals(userProfile.getUserId())) {
                valid = true;
            }
        }
        if (!valid) {
            return ApiResponse.error(404, "会话不存在或无权访问");
        }
        List<ConversationMessage> messages = conversationService.getMessagesByConversationId(conversationId);
        return ApiResponse.success(messages);
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