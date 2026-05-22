package com.example.agentdeepseek.service.impl;

import com.example.agentdeepseek.mapper.ConversationMapper;
import com.example.agentdeepseek.mapper.ConversationMessageMapper;
import com.example.agentdeepseek.mapper.SubAgentLogMapper;
import com.example.agentdeepseek.model.entity.Conversation;
import com.example.agentdeepseek.model.entity.ConversationMessage;
import com.example.agentdeepseek.service.ConversationService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 会话服务实现
 */
@Service
public class ConversationServiceImpl implements ConversationService {

    @Autowired
    private ConversationMapper conversationMapper;

    @Autowired
    private ConversationMessageMapper conversationMessageMapper;

    @Autowired
    private SubAgentLogMapper subAgentLogMapper;

    @Override
    public List<Conversation> getConversationsByUserId(Long userId, String agentType) {
        if (agentType != null && !agentType.trim().isEmpty()) {
            return conversationMapper.selectByUserIdAndAgentType(userId, agentType);
        }
        return conversationMapper.selectByUserId(userId);
    }

    @Override
    public List<ConversationMessage> getMessagesByConversationId(Long conversationId) {
        return conversationMessageMapper.selectByConversationId(conversationId);
    }

    @Override
    @Transactional
    public boolean deleteConversation(Long conversationId) {
        // 先删除子Agent执行记录（虽数据库有外键级联，但显式删除保持逻辑清晰）
        subAgentLogMapper.deleteByConversationId(conversationId);
        // 再删除关联消息
        conversationMessageMapper.deleteByConversationId(conversationId);
        // 最后删除会话
        int affectedRows = conversationMapper.delete(conversationId);
        return affectedRows > 0;
    }
}