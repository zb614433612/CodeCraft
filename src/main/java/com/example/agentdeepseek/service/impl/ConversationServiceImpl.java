package com.example.agentdeepseek.service.impl;

import com.example.agentdeepseek.mapper.ConversationMapper;
import com.example.agentdeepseek.mapper.ConversationMessageMapper;
import com.example.agentdeepseek.mapper.FileReferenceMapper;
import com.example.agentdeepseek.mapper.SubAgentLogMapper;
import com.example.agentdeepseek.model.entity.Conversation;
import com.example.agentdeepseek.model.entity.ConversationMessage;
import com.example.agentdeepseek.model.vo.ConversationMessagePageVO;
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

    /** 分页默认轮数（一轮 = 一次用户提问 + 其后全部回复） */
    private static final int DEFAULT_PAGE_ROUNDS = 5;
    /** 分页轮数上限，防止前端传入异常值导致一次拉取过多 */
    private static final int MAX_PAGE_ROUNDS = 50;

    @Override
    public ConversationMessagePageVO getMessagesPage(Long conversationId, Long beforeId, int rounds) {
        int safeRounds = rounds <= 0 ? DEFAULT_PAGE_ROUNDS : Math.min(rounds, MAX_PAGE_ROUNDS);

        // 1) 找本页起点：从尾部往前数第 safeRounds 个「正常用户消息」。
        //    startId=null 表示用户消息不足 safeRounds 轮（已到最早），退化为查 beforeId 之前的全部消息。
        Long startId = conversationMessageMapper.selectNthUserMessageIdFromTail(
                conversationId, beforeId, safeRounds - 1);

        // 2) 取该起点到游标之间的全部消息（含 assistant/tool，保证一轮完整）
        List<ConversationMessage> messages = conversationMessageMapper.selectRange(conversationId, startId, beforeId);
        if (messages == null) {
            messages = Collections.emptyList();
        }

        // 3) 是否还有更早的消息（决定前端是否还能继续上滑加载）
        boolean hasMore = startId != null && conversationMessageMapper.countBefore(conversationId, startId) > 0;

        // 4) 下一页游标 = 本页最早一条消息 id；空页返回 null，避免前端无意义地重复请求
        Long nextBeforeId = messages.isEmpty() ? null : messages.get(0).getId();

        log.debug("分页查询会话消息: conversationId={}, beforeId={}, rounds={}, startId={}, 返回={}, hasMore={}",
                conversationId, beforeId, safeRounds, startId, messages.size(), hasMore);

        return new ConversationMessagePageVO(messages, hasMore, nextBeforeId, safeRounds);
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

    @Override
    @Transactional
    public int deleteMessagesFromTurn(Long conversationId, String turnId) {
        if (conversationId == null || turnId == null || turnId.isEmpty()) {
            return 0;
        }
        // 起点 = 该轮 user 提问的消息 id；找不到说明这轮还没落库（或已被删），无事可做
        Long fromId = conversationMessageMapper.selectFirstIdByTurnId(conversationId, turnId);
        if (fromId == null) {
            log.info("从轮次删除消息：未找到 turnId 对应消息，conversationId={}, turnId={}", conversationId, turnId);
            return 0;
        }
        int deleted = conversationMessageMapper.deleteFromId(conversationId, fromId);
        log.info("从轮次删除消息完成：conversationId={}, turnId={}, fromId={}, 删除={}条",
                conversationId, turnId, fromId, deleted);
        return deleted;
    }

    /**
     * 收集会话引用的文件资产ID（失败返回空列表：不阻塞会话删除主链路）
     */
    private List<Long> collectReferencedAssetIds(Long conversationId) {        try {
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