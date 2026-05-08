package com.example.agentdeepseek.controller;

import com.example.agentdeepseek.common.response.ApiResponse;
import com.example.agentdeepseek.mapper.ConversationMessageMapper;
import com.example.agentdeepseek.mapper.UserProfileMapper;
import com.example.agentdeepseek.model.entity.ConversationMessage;
import com.example.agentdeepseek.model.entity.UserProfile;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 用户聊天记录控制器
 */
@Slf4j
@RestController
@RequestMapping("/api/user-profile")
@Tag(name = "用户聊天记录", description = "用户聊天记录查询接口")
public class UserProfileController {

    private final UserProfileMapper userProfileMapper;
    private final ConversationMessageMapper conversationMessageMapper;

    public UserProfileController(UserProfileMapper userProfileMapper, ConversationMessageMapper conversationMessageMapper) {
        this.userProfileMapper = userProfileMapper;
        this.conversationMessageMapper = conversationMessageMapper;
    }

    /**
     * 获取用户聊天列表
     * 返回当前用户的聊天记录列表
     */
    @Operation(summary = "获取用户聊天列表", description = "查询当前用户的聊天记录列表")
    @GetMapping("/list")
    public ApiResponse<List<UserProfile>> getConversationList(HttpServletRequest httpServletRequest) {
        Long userId = (Long) httpServletRequest.getAttribute("userId");
        log.info("查询用户聊天列表，userId: {}", userId);
        List<UserProfile> list = userProfileMapper.selectByUserId(userId);
        return ApiResponse.success(list);
    }

    /**
     * 新增用户聊天记录
     */
    @Operation(summary = "新增用户聊天记录", description = "创建新的用户聊天记录")
    @PostMapping("/create")
    public ApiResponse<Void> createUserProfile(
            @Parameter(description = "用户名", required = true)
            @RequestParam("username") String username,
            HttpServletRequest httpServletRequest) {
        Long userId = (Long) httpServletRequest.getAttribute("userId");
        log.info("新增用户聊天记录，username: {}, userId: {}", username, userId);

        UserProfile userProfile = new UserProfile();
        userProfile.setUsername(username);
        userProfile.setUserId(userId);
        userProfile.setCreatedAt(LocalDateTime.now());
        userProfileMapper.insert(userProfile);

        log.info("用户聊天记录创建成功，id: {}", userProfile.getId());
        return ApiResponse.success(null, "创建成功");
    }

    /**
     * 删除聊天记录
     * 同步删除关联的会话消息
     */
    @Operation(summary = "删除聊天记录", description = "根据ID删除聊天记录，同步删除关联的会话消息")
    @DeleteMapping("/{id}")
    public ApiResponse<Void> deleteUserProfile(
            @Parameter(description = "聊天记录ID", required = true)
            @PathVariable Long id,
            HttpServletRequest httpServletRequest) {
        Long userId = (Long) httpServletRequest.getAttribute("userId");
        log.info("删除聊天记录，id: {}, userId: {}", id, userId);

        UserProfile existing = userProfileMapper.selectById(id);
        if (existing == null) {
            return ApiResponse.error(404, "聊天记录不存在");
        }
        if (!userId.equals(existing.getUserId())) {
            return ApiResponse.error(403, "无权删除该聊天记录");
        }

        // 同步删除关联的会话消息
        int deletedMessages = conversationMessageMapper.deleteByConversationId(id);
        log.info("同步删除会话消息 {} 条", deletedMessages);

        userProfileMapper.deleteById(id);
        log.info("聊天记录删除成功，id: {}", id);
        return ApiResponse.success(null, "删除成功");
    }

    /**
     * 修改会话消息内容
     */
    @Operation(summary = "修改会话消息", description = "根据消息ID修改会话消息的content字段")
    @PutMapping("/message/{messageId}")
    public ApiResponse<Void> updateMessageContent(
            @Parameter(description = "消息ID", required = true)
            @PathVariable Long messageId,
            @Parameter(description = "消息内容", required = true)
            @RequestParam("content") String content,
            HttpServletRequest httpServletRequest) {
        Long userId = (Long) httpServletRequest.getAttribute("userId");
        log.info("修改会话消息，messageId: {}, userId: {}", messageId, userId);

        ConversationMessage message = conversationMessageMapper.selectById(messageId);
        if (message == null) {
            return ApiResponse.error(404, "消息不存在");
        }

        conversationMessageMapper.updateContent(messageId, content);
        log.info("会话消息修改成功，messageId: {}", messageId);
        return ApiResponse.success(null, "修改成功");
    }
}
