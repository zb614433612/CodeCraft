package com.example.agentdeepseek.service.impl;

import com.example.agentdeepseek.mapper.CompactionMapper;
import com.example.agentdeepseek.mapper.ConversationMapper;
import com.example.agentdeepseek.mapper.ConversationMessageMapper;
import com.example.agentdeepseek.mapper.FileReferenceMapper;
import com.example.agentdeepseek.mapper.LlmTokenUsageMapper;
import com.example.agentdeepseek.mapper.SubAgentLogMapper;
import com.example.agentdeepseek.model.entity.CompactionRecord;
import com.example.agentdeepseek.common.enums.ContextMode;
import com.example.agentdeepseek.config.DeepSeekConfig;
import com.example.agentdeepseek.model.entity.Conversation;
import com.example.agentdeepseek.model.entity.ConversationMessage;
import com.example.agentdeepseek.model.entity.LlmTokenUsage;
import com.example.agentdeepseek.model.entity.MessageRole;
import com.example.agentdeepseek.model.vo.ConversationMessagePageVO;
import com.example.agentdeepseek.service.ConversationService;
import com.example.agentdeepseek.service.files.FileAssetService;
import com.example.agentdeepseek.util.TokenEstimator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDateTime;
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

    /** 成本可观测：会话删除时级联清理该会话的用量记录（用户要求） */
    @Autowired
    private LlmTokenUsageMapper llmTokenUsageMapper;

    /** 上下文压缩记录（供分页接口回传「上下文重建起点」，前端上下文口径需要它） */
    @Autowired
    private CompactionMapper compactionMapper;

    /**
     * 上下文模式默认值解析（2026-11-25）。
     *
     * <p>前端分页接口会显式传 {@code contextMode}；未传时用
     * {@link DeepSeekConfig#resolveContextMode()} 解析，保证与真实请求的默认口径
     * （请求参数 &gt; sys_config &gt; 配置文件 &gt; DEFAULT）完全一致，
     * 避免「界面按 balanced 算、实际按 full 发」的错位。</p>
     */
    @Autowired
    private DeepSeekConfig deepSeekConfig;

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
        // 兼容旧调用方：不传模式时按后端默认解析（请求参数 > sys_config > 配置 > DEFAULT）
        return getMessagesPage(conversationId, beforeId, rounds, null);
    }

    @Override
    public ConversationMessagePageVO getMessagesPage(Long conversationId, Long beforeId, int rounds,
                                                     String contextMode) {
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

        ConversationMessagePageVO page = new ConversationMessagePageVO(messages, hasMore, nextBeforeId, safeRounds);
        fillCompactionBoundary(page, conversationId);
        /*
          上下文大小（2026-11-25）：按当前上下文模式现算 —— full 回传整段思考链、balanced 置空占位、
          compact 再把历史工具结果压成摘要，三者差异巨大，必须带 mode 才能反映真实水位。

          ⚠️ 只在「首页」（beforeId == null）计算：向上翻页只影响「显示多少条历史」，
          不改变「会发给模型的上下文总量」，但 computeContextTokens 需要全量读取该会话消息
          （实测长会话 1635 条 / 2.8MB 文本），每翻一页都算一次纯属浪费，会让滚动加载明显变慢。
          前端翻页时沿用上一次拿到的数字即可（时间/模式变化会由首页加载或 /context-stats 覆盖）。
        */
        if (beforeId == null) {
            fillContextTokens(page, conversationId, contextMode);
        }
        return page;
    }

    /**
     * 回填「上下文压缩边界」（2026-11-17 新增）。
     *
     * <p><b>为什么需要回传</b>：压缩后后端重建上下文只使用
     * 「system + 压缩摘要 + 摘要之后的消息」，被压缩的历史不再计入上下文
     * （但原文仍完整保留在 conversation_message 表中，用户翻页照样能查到历史聊天记录）。
     * 前端另有一套「输入区下方『上下文 xx』」的字符估算，若仍把被压缩历史算进去，
     * 显示值会与后端真实上下文相差数倍，属于误导性数据。</p>
     *
     * <p><b>口径</b>：{@code compactedUpToMessageId} 取所有<b>未被取代</b>压缩记录的
     * 最大 {@code end_message_id}；{@code compactedSummaryTokens} 为这些摘要的估算 token 累加，
     * 用来替代「被折叠掉的那段历史」在上下文中的占比。</p>
     *
     * <p>查询失败不影响分页（降级为「未压缩」语义，前端行为回到改动前）。</p>
     */
    private void fillCompactionBoundary(ConversationMessagePageVO page, Long conversationId) {
        try {
            List<CompactionRecord> records = compactionMapper.selectActiveByConversationId(conversationId);
            if (records == null || records.isEmpty()) {
                return;
            }
            Long maxEndId = null;
            int summaryTokens = 0;
            for (CompactionRecord record : records) {
                if (record.getEndMessageId() != null
                        && (maxEndId == null || record.getEndMessageId() > maxEndId)) {
                    maxEndId = record.getEndMessageId();
                }
                String summary = record.getSummary();
                if (summary != null && !summary.isEmpty()) {
                    // 摘要 token 用未校准口径即可：它只与前端同一套字符估算做比较，不参与后端阈值判断
                    summaryTokens += TokenEstimator.estimate(summary);
                }
            }
            if (maxEndId != null && maxEndId > 0) {
                page.setCompactedUpToMessageId(maxEndId);
                page.setCompactedSummaryTokens(summaryTokens);
            }
        } catch (Exception e) {
            log.warn("回填压缩边界失败（不影响分页）: conversationId={}, err={}", conversationId, e.getMessage());
        }
    }

    /**
     * tools 定义的 token 地板（2026-11-25）。
     *
     * <p>本项目 20~31 个工具的 schema 恒占约 14 万字符 ≈ 6 万真实 token，它压不掉也删不得
     * （DeepSeek 服务端的 {@code usage.prompt_tokens} 包含这块）。上下文展示若漏掉它，
     * 小会话会偏小一半以上（messages 只 4 万 token 时，真实 prompt 约 10 万）。</p>
     *
     * <p>取 6 万是保守值：不同 Agent 授权工具数不同会有 ±2 万浮动，
     * 但「三种上下文模式之间的相对差异」这一核心诉求不受影响。</p>
     */
    private static final int TOOLS_TOKENS_FLOOR = 60_000;

    /**
     * compact 模式下「历史工具结果」被替换成的摘要文本长度（约值）。
     * 见 {@code ContextBuilder#compactHistoryMessages}：成功约 40 字符、失败约 160 字符，统一按 60 估。
     */
    private static final int COMPACT_TOOL_PLACEHOLDER_CHARS = 60;

    /**
     * 回填「当前上下文大小」（2026-11-25，<b>按上下文模式区分</b>）。
     *
     * <p><b>为什么不能只回传「最近一次真实 prompt_tokens」</b>：那是<b>上一次请求所用模式</b>的快照。
     * 切换模式后（full ↔ balanced ↔ compact）真实请求体差异巨大 —— full 回传整段思考链、
     * balanced 置空占位、compact 再把历史工具结果压成摘要 —— 若继续显示旧值，
     * 用户会看到「切到精简模式数字纹丝不动」，比不显示更误导。</p>
     *
     * <p>因此这里改为<b>按当前模式现算</b>（{@link #computeContextTokens}），
     * 并把最近一次真实值放进 {@code lastPromptTokens} 供前端 tooltip 对照。</p>
     */
    private void fillContextTokens(ConversationMessagePageVO page, Long conversationId, String contextMode) {
        try {
            ContextMode mode = ContextMode.from(contextMode, deepSeekConfig.resolveContextMode());
            int tokens = computeContextTokens(conversationId, mode.value());
            if (tokens > 0) {
                page.setContextTokens(tokens);
                page.setContextTokensSource("mode");
                page.setContextMode(mode.value());
            }
            // 对照值：最近一次真实请求的 prompt_tokens（含 tools，即「上一次实际发了多少」）
            LlmTokenUsage latestUsage = llmTokenUsageMapper.selectLatestUsage(conversationId);
            if (latestUsage != null && latestUsage.getPromptTokens() > 0) {
                page.setLastPromptTokens(latestUsage.getPromptTokens());
            }
        } catch (Exception e) {
            log.warn("回填上下文 token 失败（不影响分页）: conversationId={}, err={}", conversationId, e.getMessage());
        }
    }

    /**
     * 只算上下文统计（不返回消息）——聊天页切换上下文模式时的轻量刷新入口。
     *
     * <p>复用 {@link #fillContextTokens}，直接返回一个 {@code messages=null} 的分页 VO，
     * 前端只读 {@code contextTokens / contextMode / lastPromptTokens} 三个字段。
     * 这样切换模式不必重新拉一遍消息列表（长会话翻页状态不会被重置）。</p>
     */
    @Override
    public ConversationMessagePageVO getContextStats(Long conversationId, String contextMode) {
        ConversationMessagePageVO vo = new ConversationMessagePageVO();
        fillContextTokens(vo, conversationId, contextMode);
        return vo;
    }

    /**
     * 按上下文模式计算「当前会发给模型的上下文 token 数」（2026-11-25 新增）。
     *
     * <p><b>口径与真实重建严格对齐</b>（见 {@code ContextBuilder#buildMessagesFromHistory} +
     * {@code compactHistoryMessages} + {@code ReasoningPassback}）：</p>
     * <ul>
     *   <li>范围：压缩摘要 + 水位线之后的全部消息（含 SYSTEM）—— 与真实重建同源，被折叠的历史不计入；</li>
     *   <li><b>full</b>：assistant 的 {@code reasoning_content} 回传真实思考链 → 计入；工具结果原文计入；</li>
     *   <li><b>balanced</b>：思考链只下发空串占位 → <b>不计入</b>；工具结果原文仍计入；</li>
     *   <li><b>compact</b>：在 balanced 基础上，<b>历史</b>（最后一条 user 之前）的工具结果
     *       被替换为约 60 字符的摘要 → 按摘要长度计入；只有本轮的按原文计入。</li>
     * </ul>
     *
     * <p>字符 → token 用 {@link TokenEstimator#CHARS_TO_TOKEN_RATIO}（实测与真实 prompt_tokens
     * 逐位吻合），再补上 {@link #TOOLS_TOKENS_FLOOR} 这块固定地板。</p>
     *
     * @param conversationId 会话 ID
     * @param contextMode    full / balanced / compact；null 或无法识别时按后端默认解析
     * @return 估算 token；无消息返回 0（表示不显示）
     */
    @Override
    public int computeContextTokens(Long conversationId, String contextMode) {
        if (conversationId == null) {
            return 0;
        }
        ContextMode mode = ContextMode.from(contextMode, deepSeekConfig.resolveContextMode());
        try {
            // 1) 压缩水位线 + 累进摘要（与真实重建同源：只算「摘要 + 水位线之后」）
            long watermark = 0L;
            String summary = null;
            List<CompactionRecord> records = compactionMapper.selectActiveByConversationId(conversationId);
            if (records != null) {
                for (CompactionRecord record : records) {
                    if (record.getEndMessageId() == null || record.getEndMessageId() <= 0) {
                        continue;
                    }
                    if (record.getEndMessageId() > watermark) {
                        watermark = record.getEndMessageId();
                        summary = record.getSummary();
                    }
                }
            }

            List<ConversationMessage> messages = watermark > 0
                    ? conversationMessageMapper.selectForContext(conversationId, watermark)
                    : conversationMessageMapper.selectByConversationId(conversationId);
            if (messages == null || messages.isEmpty()) {
                return 0;
            }

            // 2) compact：只有「最后一条 user 之前」的历史工具结果会被摘要化，本轮保持原文
            Long lastUserId = mode.shouldCompactToolResults()
                    ? conversationMessageMapper.selectNthUserMessageIdFromTail(conversationId, null, 0)
                    : null;

            // 3) 按模式累计字符
            long chars = summary == null ? 0L : summary.length();
            for (ConversationMessage msg : messages) {
                MessageRole role = msg.getRole();
                if (role == null) {
                    continue;
                }
                switch (role) {
                    case TOOL -> {
                        boolean compacted = mode.shouldCompactToolResults()
                                && lastUserId != null
                                && msg.getId() != null
                                && msg.getId() < lastUserId;
                        // 工具结果存放在 reasoning 列（content 列恒为 null，见 ContextBuilder 注释）
                        chars += compacted
                                ? COMPACT_TOOL_PLACEHOLDER_CHARS
                                : textLen(msg.getReasoning()) + textLen(msg.getContent());
                    }
                    case ASSISTANT -> {
                        chars += textLen(msg.getContent()) + textLen(msg.getToolCalls());
                        // full 回传真实思考链；balanced / compact 只下发空串占位（见 ReasoningPassback）
                        if (!mode.shouldStripHistoryReasoning()) {
                            chars += textLen(msg.getReasoning());
                        }
                    }
                    default -> chars += textLen(msg.getContent());
                }
            }

            if (chars <= 0) {
                return 0;
            }
            long tokens = Math.round(chars * TokenEstimator.CHARS_TO_TOKEN_RATIO) + TOOLS_TOKENS_FLOOR;
            return (int) Math.min(Integer.MAX_VALUE, tokens);
        } catch (Exception e) {
            log.warn("计算上下文 token 失败（降级为 0）: conversationId={}, mode={}, err={}",
                    conversationId, mode.value(), e.getMessage());
            return 0;
        }
    }

    /** 字符串长度（null 视为 0） */
    private static long textLen(String text) {
        return text == null ? 0L : text.length();
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
        /*
          成本记录级联清理（用户要求：删会话即删它的成本记录）。
          独立 try/catch：llm_token_usage 是观测旁路表，任何异常都不该让「删会话」这个主操作失败
          （例如老库缺表 / 缺列时）。失败只留 warn，用户可以到成本看板手工确认残留。
        */
        try {
            int removedUsage = llmTokenUsageMapper.deleteByConversationId(conversationId);
            if (removedUsage > 0) {
                log.info("会话删除联动清理成本记录: conversationId={}, 删除={}条", conversationId, removedUsage);
            }
        } catch (Exception e) {
            log.warn("会话成本记录清理失败（不影响会话删除）: conversationId={}, err={}", conversationId, e.getMessage());
        }

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