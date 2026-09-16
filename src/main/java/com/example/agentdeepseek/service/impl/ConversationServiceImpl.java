package com.example.agentdeepseek.service.impl;

import com.example.agentdeepseek.mapper.ConversationMapper;
import com.example.agentdeepseek.mapper.ConversationMessageMapper;
import com.example.agentdeepseek.mapper.FileReferenceMapper;
import com.example.agentdeepseek.mapper.SubAgentLogMapper;
import com.example.agentdeepseek.model.entity.Conversation;
import com.example.agentdeepseek.model.entity.ConversationMessage;
import com.example.agentdeepseek.service.ConversationService;
import com.example.agentdeepseek.service.files.FileAssetService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Collections;
import java.util.List;

/**
 * 会话服务实现
 */
@Slf4j
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

    /** M2：文件引用关联（会话删除时级联清理） */
    @Autowired
    private FileReferenceMapper fileReferenceMapper;

    /** M2：文件资产服务（删除会话后"引用归零"级联清理） */
    @Autowired
    private FileAssetService fileAssetService;

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
        // M2：先收集该会话引用的文件资产（引用归零判定用；收集失败不阻塞会话删除）
        List<Long> referencedAssetIds = collectReferencedAssetIds(conversationId);

        // 先删除子Agent执行记录（虽数据库有外键级联，但显式删除保持逻辑清晰）
        subAgentLogMapper.deleteByConversationId(conversationId);
        // 再删除关联消息
        conversationMessageMapper.deleteByConversationId(conversationId);
        // 最后删除会话
        int affectedRows = conversationMapper.delete(conversationId);
        // M2：删除该会话的全部文件引用
        fileReferenceMapper.deleteByConversationId(conversationId);

        // M2：注册事务提交后回调——"引用归零"的文件做远端+本地级联清理
        // （远端 IO 不进事务，F3 决策 4；事务回滚时 afterCommit 不触发，天然安全）
        if (!referencedAssetIds.isEmpty()) {
            scheduleAssetCleanup(referencedAssetIds);
        }
        return affectedRows > 0;
    }

    /**
     * 收集会话引用的文件资产ID（失败返回空列表：不阻塞会话删除主链路）
     */
    private List<Long> collectReferencedAssetIds(Long conversationId) {
        try {
            List<Long> ids = fileReferenceMapper.selectAssetIdsByConversationId(conversationId);
            return ids == null ? Collections.emptyList() : ids;
        } catch (Exception e) {
            log.warn("收集会话文件引用失败（跳过级联清理）: conversationId={} - {}", conversationId, e.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * 事务提交后执行文件级联清理（best-effort：单文件失败不影响其他文件）
     */
    private void scheduleAssetCleanup(List<Long> assetIds) {
        Runnable cleanupTask = () -> {
            for (Long assetId : assetIds) {
                try {
                    fileAssetService.cleanupIfUnreferenced(assetId);
                } catch (Exception e) {
                    log.warn("文件级联清理失败: assetId={} - {}", assetId, e.getMessage());
                }
            }
        };
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    cleanupTask.run();
                }
            });
        } else {
            // 兜底（方法带 @Transactional，理论上不会走到）：同步执行保证清理不丢
            cleanupTask.run();
        }
    }
}