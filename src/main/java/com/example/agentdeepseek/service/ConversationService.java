package com.example.agentdeepseek.service;

import com.example.agentdeepseek.model.entity.Conversation;
import com.example.agentdeepseek.model.entity.ConversationMessage;

import java.util.List;

/**
 * 会话服务接口
 */
public interface ConversationService {

    /**
     * 根据用户ID查询会话列表
     * @param userId 用户ID
     * @param agentType 会话类型（可选，为空则查询所有）
     * @return 会话列表
     */
    List<Conversation> getConversationsByUserId(Long userId, String agentType);

    /**
     * 根据用户ID和Agent配置ID查询会话列表（切换 Agent 时用）
     */
    List<Conversation> getConversationsByAgentConfigId(Long userId, Long agentConfigId);

    /**
     * 根据会话ID查询消息列表（不分页）
     * @param conversationId 会话ID
     * @return 消息列表
     */
    List<ConversationMessage> getMessagesByConversationId(Long conversationId);

    /**
     * 删除会话及其关联消息
     * @param conversationId 会话ID
     * @return 删除是否成功
     */
    boolean deleteConversation(Long conversationId);
}