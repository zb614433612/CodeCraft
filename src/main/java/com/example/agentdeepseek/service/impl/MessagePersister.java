package com.example.agentdeepseek.service.impl;

import com.example.agentdeepseek.mapper.ConversationMessageMapper;
import com.example.agentdeepseek.model.entity.ConversationMessage;
import com.example.agentdeepseek.model.entity.MessageRole;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 消息持久化组件
 * <p>
 * 从 DeepSeekServiceImpl 拆分出来，负责会话消息的数据库写入。
 * 包含 5 个 save 方法：用户消息、助手消息、工具消息、助手推理、通用保存。
 * </p>
 */
@Slf4j
@Component
public class MessagePersister {

    private final ConversationMessageMapper conversationMessageMapper;

    public MessagePersister(ConversationMessageMapper conversationMessageMapper) {
        this.conversationMessageMapper = conversationMessageMapper;
    }

    /**
     * 保存用户消息
     * @param conversationId 会话ID
     * @param content 消息内容
     * @param turnId 前端生成的 turnId（用于匹配回滚快照）
     */
    public void saveUserMessage(Long conversationId, String content, String turnId) {
        ConversationMessage msg = new ConversationMessage(conversationId, MessageRole.USER, content, null, null);
        msg.setTurnId(turnId);
        conversationMessageMapper.insert(msg);
    }

    /**
     * 保存助手消息（包含思考过程和内容）
     * @param conversationId 会话ID
     * @param content 消息内容（将作为content字段存储）
     * @param reasoning 思考过程
     */
    public void saveAssistantMessage(Long conversationId, String content, String reasoning) {
        saveConversationMessage(conversationId, MessageRole.ASSISTANT, content, reasoning, null);
    }

    /**
     * 保存工具调用消息
     * @param conversationId 会话ID
     * @param content 工具调用结果内容（将存储到reasoning字段）
     */
    public void saveToolMessage(Long conversationId, String content) {
        saveConversationMessage(conversationId, MessageRole.TOOL, null, content, null);
    }

    /**
     * 保存助手思考过程消息（只保存reasoning，content为null）
     * @param conversationId 会话ID
     * @param reasoning 思考过程
     * @param toolCalls 工具调用数据块（JSON格式），可选
     */
    public void saveAssistantReasoning(Long conversationId, String reasoning, String toolCalls) {
        saveConversationMessage(conversationId, MessageRole.ASSISTANT, null, reasoning, toolCalls);
    }

    /**
     * 保存会话消息到数据库（通用方法）
     *
     * @param conversationId 会话ID
     * @param role 消息角色
     * @param content 消息内容（存储到content字段），可以为null
     * @param reasoning 思考过程（存储到reasoning字段），可以为null
     * @param toolCalls 工具调用数据块（JSON格式，存储到tool_calls字段），可以为null
     */
    public void saveConversationMessage(Long conversationId, MessageRole role, String content, String reasoning, String toolCalls) {
        ConversationMessage message = new ConversationMessage(conversationId, role, content, reasoning, toolCalls);
        conversationMessageMapper.insert(message);
        log.debug("保存{}消息: conversationId={}, contentLength={}, reasoningLength={}, toolCallsLength={}",
                role, conversationId,
                content != null ? content.length() : 0,
                reasoning != null ? reasoning.length() : 0,
                toolCalls != null ? toolCalls.length() : 0);
    }
}
