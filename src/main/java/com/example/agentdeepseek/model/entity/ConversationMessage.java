package com.example.agentdeepseek.model.entity;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 会话消息实体类
 * 存储会话中的每条消息
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ConversationMessage {
    /**
     * 消息ID，自增主键
     */
    private Long id;

    /**
     * 会话ID，外键关联Conversation表
     */
    private Long conversationId;

    /**
     * 消息角色
     */
    private MessageRole role;

    /**
     * 消息内容
     */
    private String content;

    /**
     * 思考过程（仅ASSISTANT角色有效）
     */
    private String reasoning;

    /**
     * 工具调用数据块（JSON格式）
     */
    private String toolCalls;

    /**
     * turnId（前端生成，用于匹配回滚快照）
     */
    private String turnId;


    /**
     * 创建时间
     */
    private LocalDateTime createdAt;

    /**
     * 构造函数，用于创建新消息
     * @param conversationId 会话ID
     * @param role 消息角色
     * @param content 消息内容
     */
    public ConversationMessage(Long conversationId, MessageRole role, String content) {
        this.conversationId = conversationId;
        this.role = role;
        this.content = content;
        this.createdAt = LocalDateTime.now();
    }

    /**
     * 构造函数，用于创建ASSISTANT消息（包含思考过程和内容）
     * @param conversationId 会话ID
     * @param role 消息角色
     * @param content 消息内容
     * @param reasoning 思考过程
     */
    public ConversationMessage(Long conversationId, MessageRole role, String content, String reasoning) {
        this.conversationId = conversationId;
        this.role = role;
        this.content = content;
        this.reasoning = reasoning;
        this.createdAt = LocalDateTime.now();
    }

    /**
     * 构造函数，用于创建ASSISTANT消息（包含思考过程、内容和工具调用数据）
     * @param conversationId 会话ID
     * @param role 消息角色
     * @param content 消息内容
     * @param reasoning 思考过程
     * @param toolCalls 工具调用数据块（JSON格式）
     */
    public ConversationMessage(Long conversationId, MessageRole role, String content, String reasoning, String toolCalls) {
        this.conversationId = conversationId;
        this.role = role;
        this.content = content;
        this.reasoning = reasoning;
        this.toolCalls = toolCalls;
        this.createdAt = LocalDateTime.now();
    }
}