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

    @Autowired
    private org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;

    @jakarta.annotation.PostConstruct
    public void init() {
        // 兼容旧数据库：确保 conversation 表包含 Agent 系统新增的列
        try {
            jdbcTemplate.execute("ALTER TABLE conversation ADD COLUMN agent_config_id BIGINT AFTER agent_type");
        } catch (Exception e) { /* 列已存在 */ }
        try {
            jdbcTemplate.execute("ALTER TABLE conversation ADD COLUMN work_dir VARCHAR(500) AFTER agent_config_id");
        } catch (Exception e) { /* 列已存在 */ }
    }

    @Override
    public List<Conversation> getConversationsByUserId(Long userId, String agentType) {
        if (agentType != null && !agentType.trim().isEmpty()) {
            return conversationMapper.selectByUserIdAndAgentType(userId, agentType);
        }
        return conversationMapper.selectByUserId(userId);
    }

    @Override
    public List<Conversation> getConversationsByAgentConfigId(Long userId, Long agentConfigId) {
        return conversationMapper.selectByUserIdAndAgentConfigId(userId, agentConfigId);
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