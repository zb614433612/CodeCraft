package com.example.agentdeepseek.service.impl;

import com.example.agentdeepseek.config.DeepSeekConfig;
import com.example.agentdeepseek.mapper.CompactionMapper;
import com.example.agentdeepseek.mapper.ConversationMessageMapper;
import com.example.agentdeepseek.model.entity.CompactionRecord;
import com.example.agentdeepseek.model.entity.ConversationMessage;
import com.example.agentdeepseek.model.entity.MessageRole;
import com.example.agentdeepseek.service.llm.LLMClientManager;
import com.example.agentdeepseek.service.llm.LLMClient;
import com.example.agentdeepseek.util.PromptUtil;
import com.example.agentdeepseek.util.TokenEstimator;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 上下文智能压缩服务
 *
 * 核心职责：
 * 1. 将最早的历史对话用 LLM 压缩为结构化摘要
 * 2. 保存压缩记录，在后续 buildMessagesFromHistory 中用摘要替换原始消息
 * 3. 支持异步预压缩，降低请求延迟
 * 4. 提供三级阈值判断（黄色预警/橙色压缩/红色丢弃）
 */
@Slf4j
@Service
public class CompactionService {

    private final DeepSeekConfig deepSeekConfig;
    private final CompactionMapper compactionMapper;
    private final ConversationMessageMapper conversationMessageMapper;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final LLMClientManager llmClientManager;

    /** 预压缩执行器（多线程，支持并发压缩不同会话） */
    private final java.util.concurrent.ExecutorService precompressExecutor =
            java.util.concurrent.Executors.newFixedThreadPool(3, r -> {
                Thread t = new Thread(r, "compaction-precompress-");
                t.setDaemon(true);
                return t;
            });

    /** 会话是否正在预压缩中（防止重复触发） */
    private final Set<Long> precompressingConversations = ConcurrentHashMap.newKeySet();

    /**
     * 异步预压缩触发阈值（token 占 max-context-tokens 的比例）。
     * <p>
     * 取 0.60，低于同步压缩阈值（0.85），目的是「提前、小步」压缩，避免等上下文涨到
     * 85% 才一次性全量重算——压缩后首次请求必然全量未命中，压缩时上下文越小越省钱。
     * </p>
     */
    private static final double ASYNC_PRECOMPRESS_THRESHOLD = 0.60;

    /**
     * 两次压缩之间的最小间隔（毫秒）。
     * <p>
     * 每次压缩都会改变上下文前缀、导致一次 O(历史长度) 的全量未命中（命中 0.02 vs
     * 未命中 1.0 元/百万）。压缩本身只在「后续还能跑很多轮」时才划算，因此设最小间隔，
     * 保证压缩后至少有几轮能吃到缓存，而不是压缩完立刻再压一次。
     * </p>
     */
    private static final long MIN_COMPACT_INTERVAL_MS = 120_000L;

    @Autowired
    public CompactionService(DeepSeekConfig deepSeekConfig,
                             CompactionMapper compactionMapper,
                             ConversationMessageMapper conversationMessageMapper,
                             LLMClientManager llmClientManager) {
        this.deepSeekConfig = deepSeekConfig;
        this.compactionMapper = compactionMapper;
        this.conversationMessageMapper = conversationMessageMapper;
        this.llmClientManager = llmClientManager;
    }

    /**
     * 初始化：自动创建压缩记录表（如果不存在）
     */
    @PostConstruct
    public void init() {
        try {
            compactionMapper.createTable();
            log.info("压缩记录表初始化完成");
        } catch (Exception e) {
            log.warn("压缩记录表初始化失败: {}", e.getMessage());
        }
    }

    // ==================== 对外接口 ====================

    /**
     * 判断当前消息列表是否需要压缩
     *
     * @param messages API 格式的消息列表
     * @return 压缩决策：NONE / WARN（仅标记）/ COMPACT（执行压缩）/ DROP（丢弃兜底）
     */
    public CompactionDecision decide(List<Map<String, Object>> messages) {
        if (!deepSeekConfig.getCompaction().isEnabled()) {
            return CompactionDecision.NONE;
        }
        int estimatedTokens = TokenEstimator.estimateMessages(messages);
        int maxTokens = deepSeekConfig.getMaxContextTokens();
        DeepSeekConfig.CompactionConfig cfg = deepSeekConfig.getCompaction();
        double ratio = (double) estimatedTokens / maxTokens;

        if (ratio >= cfg.getDropThreshold()) {
            log.warn("上下文压缩决策：DROP（红色丢弃）, tokens={}, 上限={}, 比例={}", estimatedTokens, maxTokens, ratio);
            return CompactionDecision.DROP;
        }
        if (ratio >= cfg.getCompactThreshold()) {
            log.info("上下文压缩决策：COMPACT（橙色压缩）, tokens={}, 上限={}, 比例={}", estimatedTokens, maxTokens, ratio);
            return CompactionDecision.COMPACT;
        }
        if (ratio >= cfg.getWarningThreshold()) {
            log.debug("上下文压缩决策：WARN（黄色预警）, tokens={}, 上限={}, 比例={}", estimatedTokens, maxTokens, ratio);
            return CompactionDecision.WARN;
        }
        return CompactionDecision.NONE;
    }

    /**
     * 执行智能压缩
     *
     * @param conversationId 会话 ID
     * @param messages       API 格式的消息列表（会被修改：用压缩摘要替换最早历史）
     * @return 压缩后的 token 数，如果不需要压缩返回 -1
     */
    public int compact(Long conversationId, List<Map<String, Object>> messages) {
        return compact(conversationId, messages, null);
    }

    /**
     * 执行智能压缩
     *
     * @param conversationId 会话 ID
     * @param messages       API 格式的消息列表（会被修改：用压缩摘要替换最早历史）
     * @param providerCode   LLM Provider Code（可选，为空则用默认）
     * @return 压缩后的 token 数，如果不需要压缩返回 -1
     */
    public int compact(Long conversationId, List<Map<String, Object>> messages, String providerCode) {
        DeepSeekConfig.CompactionConfig cfg = deepSeekConfig.getCompaction();
        if (!cfg.isEnabled() || messages == null || messages.size() < 4) {
            return -1;
        }

        // 1. 确定可压缩范围（保留保护带）
        int protectEnd = findProtectZoneEnd(messages, cfg.getProtectRounds());
        if (protectEnd <= 2) {
            // 保护带覆盖了全部历史，无法压缩
            log.debug("压缩跳过：保护带覆盖全部历史，protectEnd={}", protectEnd);
            return -1;
        }

        // 索引 0 通常是 system 消息，从索引 1 开始压缩
        int compressStart = 1;
        int compressEnd = protectEnd; // 不包含

        if (compressEnd - compressStart < 2) {
            log.debug("压缩跳过：可压缩区过小，compressStart={}, compressEnd={}", compressStart, compressEnd);
            return -1;
        }

        // 2. 从 DB 获取原始 ConversationMessage 对象
        List<ConversationMessage> allMessages = conversationMessageMapper.selectByConversationId(conversationId);
        if (allMessages == null || allMessages.isEmpty()) {
            return -1;
        }

        // 按创建时间排序（与 messages 列表顺序一致）
        allMessages.sort(Comparator.comparing(ConversationMessage::getCreatedAt));

        // ★ 关键修复：对 allMessages 也应用压缩记录，保持与 messages 参数索引一致
        // 因为 buildMessagesFromHistory 中已经先调用了 applyCompactionRecords 再组装 messages，
        // 所以这里也需要对 DB 消息执行同样的操作才能对齐索引
        applyCompactionRecords(conversationId, allMessages);

        // ★ 找到对应的消息 ID 范围（跳过摘要消息）：
        // applyCompactionRecords 注入的摘要消息 id 为负数（-recordId），并非真实 DB 消息。
        // 若直接用索引位置的 id 落库，后续轮次 applyCompactionRecords 用该 id 在 DB 消息中
        // 匹配会失败（负数 id 在 DB 中不存在），压缩记录被跳过、压缩链断裂。
        // 因此 startMsgId/endMsgId 必须取区间内第一条/最后一条真实消息（id > 0）的 id。
        Long startMsgId = null;
        for (int i = compressStart; i < allMessages.size(); i++) {
            Long id = allMessages.get(i).getId();
            if (id != null && id > 0) {
                startMsgId = id;
                break;
            }
        }
        Long endMsgId = null;
        for (int i = Math.min(compressEnd - 1, allMessages.size() - 1); i >= 0; i--) {
            Long id = allMessages.get(i).getId();
            if (id != null && id > 0) {
                endMsgId = id;
                break;
            }
        }
        if (startMsgId == null || endMsgId == null || startMsgId > endMsgId) {
            log.info("压缩跳过：可压缩范围内没有真实消息（历史已全部是摘要），startMsgId={}, endMsgId={}", startMsgId, endMsgId);
            return -1;
        }

        // 3. 收集被压缩的消息内容，构建压缩 prompt
        // ★ 只用区间内的真实消息（id > 0），排除旧摘要（负 id）：
        // compressStart 固定为 1，若之前压缩过，旧摘要就位于 index 1 附近，
        // 用 subList 会把它包进来导致：旧摘要被重复压缩进新摘要（内容冗余、LLM 浪费），
        // 且 record 落库区间 [startMsgId, endMsgId] 与 toCompress 实际内容不一致。
        final Long fStartMsgId = startMsgId;
        final Long fEndMsgId = endMsgId;
        List<ConversationMessage> toCompress = allMessages.stream()
                .filter(m -> {
                    Long id = m.getId();
                    return id != null && id >= fStartMsgId && id <= fEndMsgId;
                })
                .collect(Collectors.toList());

        if (toCompress.isEmpty()) {
            return -1;
        }

        // 4. 调用 LLM 压缩
        String summary;
        try {
            summary = callCompactionLlm(toCompress, providerCode);
        } catch (Exception e) {
            log.error("LLM 压缩调用失败，跳过压缩: {}", e.getMessage());
            return -1;
        }

        if (summary == null || summary.trim().isEmpty()) {
            log.warn("压缩结果为空，跳过");
            return -1;
        }

        // 5. 计算节省的 token 数
        int beforeTokens = TokenEstimator.estimateMessages(messages);
        String originalText = formatMessagesForCompaction(toCompress);
        int originalTokens = TokenEstimator.estimate(originalText);
        int summaryTokens = TokenEstimator.estimate(summary);
        int savings = originalTokens - summaryTokens;

        // ★ 收益保护：压缩后不比原来小，就根本不值得压缩。
        // 每次压缩都会让 applyCompactionRecords 注入的摘要改变索引 1 附近的内容，
        // 进而导致下一次请求的整段前缀失效（50 倍价差）。若压缩本身没有正收益，
        // 就是纯亏：既损失了历史细节，又白白丢掉一次全量缓存。
        if (savings <= 0) {
            log.info("压缩跳过：无收益（原始 {} tokens → 摘要 {} tokens），保持上下文不变以复用前缀缓存",
                    originalTokens, summaryTokens);
            return -1;
        }

        // 6. 保存压缩记录到 DB
        CompactionRecord record = new CompactionRecord(conversationId, summary, startMsgId, endMsgId, savings);
        compactionMapper.insert(record);
        log.info("压缩完成：conversationId={}, 压缩 {} 条消息, 节省 {} tokens, recordId={}",
                conversationId, toCompress.size(), savings, record.getId());

        // 7. 将之前已有的、被本压缩完全覆盖的旧压缩记录标记为废弃
        // 废弃条件：旧记录的消息区间 [start, end] 完全落在本次压缩区间 [startMsgId, endMsgId] 内
        // 之前用 old.getEndMessageId() <= endMsgId 判断，会导致相邻区间的旧记录被误废弃
        // （例如第一次压缩 [1,10]、第二次压缩 [11,20] 时，第一次记录 end=10 <= 20 被错误标记废弃，
        //   其摘要失效后原始消息重新进入上下文，压缩链条断裂）
        List<CompactionRecord> activeRecords = compactionMapper.selectActiveByConversationId(conversationId);
        for (CompactionRecord old : activeRecords) {
            Long oldStart = old.getStartMessageId();
            Long oldEnd = old.getEndMessageId();
            if (!old.getId().equals(record.getId())
                    && oldStart != null && oldEnd != null
                    && oldStart >= startMsgId && oldEnd <= endMsgId) {
                compactionMapper.markSuperseded(old.getId());
                log.debug("标记旧压缩记录为废弃: id={}, 区间[{},{}] 被本次 [{},{}] 完全覆盖",
                        old.getId(), oldStart, oldEnd, startMsgId, endMsgId);
            }
        }

        // 8. 在内存消息列表中，用压缩摘要替换原始消息
        replaceWithCompaction(messages, compressStart, compressEnd, summary);

        // 压缩前后真实对比：这是判断「压缩到底亏不亏」的唯一可靠观测点。
        // 注意 afterTokens 必然低于 beforeTokens（savings > 0 已在前置校验），否则说明口径有偏差。
        int afterTokens = TokenEstimator.estimateMessages(messages);
        log.info("压缩前后对比：conversationId={}, {} tokens → {} tokens（消息 {} 条 → {} 条）",
                conversationId, beforeTokens, afterTokens, compressEnd - compressStart, messages.size());
        return afterTokens;
    }

    /**
     * 异步预压缩：在请求返回后，后台提前压缩最早的历史
     * 这样下次请求时 buildMessagesFromHistory 可以直接使用压缩结果，无需等待 LLM
     *
     * @param conversationId 会话 ID
     */
    public void asyncPrecompress(Long conversationId) {
        asyncPrecompress(conversationId, null);
    }

    public void asyncPrecompress(Long conversationId, String providerCode) {
        DeepSeekConfig.CompactionConfig cfg = deepSeekConfig.getCompaction();
        if (!cfg.isEnabled() || !cfg.isAsyncPrecompress()) {
            return;
        }
        if (!precompressingConversations.add(conversationId)) {
            // 该会话已在压缩中
            return;
        }

        CompletableFuture.runAsync(() -> {
            try {
                // 获取当前消息列表检查是否需要压缩
                List<ConversationMessage> messages = conversationMessageMapper.selectByConversationId(conversationId);
                if (messages == null || messages.size() < 6) {
                    return; // 消息太少，无需压缩
                }

                // ★★★ P0 修复（缓存全量失效的根因）★★★
                // 原实现调用 estimateDbMessages(messages)：无差别累加 DB 里「全部原始消息」的
                // content + reasoning，完全忽略已经落库的压缩记录。
                // 后果：历史一旦被压缩过，DB 原文仍然存在，估算值只增不减 ——
                //   实测 430 万字符 ≈ 215 万 token，而 max-context-tokens = 100 万，
                //   ratio 永久 ≈ 2.15 > 0.60，于是「每一次请求返回后」都触发一次预压缩。
                // 每次压缩都会产出新的摘要文本并被 applyCompactionRecords 注入到索引 1 附近，
                // 导致下一次请求的整段前缀全部改变 → 前缀缓存 100% 失效（命中单价 0.02 vs
                // 未命中 1.0 元/百万，50 倍价差），实测 15 次压缩中 9 次造成全量失效。
                //
                // 修正：先按「真实请求的组装顺序」处理 —— 排序 + 应用已有压缩记录 —— 再估算。
                // 这样估算口径与 ContextBuilder.buildMessagesFromHistory 下发给模型的上下文一致，
                // 压缩记录覆盖掉的历史不会再被重复计入。
                messages.sort(Comparator.comparing(ConversationMessage::getCreatedAt,
                        Comparator.nullsLast(Comparator.naturalOrder())));
                applyCompactionRecords(conversationId, messages);

                // 与 ContextBuilder 一致的 API 口径估算（含 TOOL 消息的工具结果、tool_calls 开销）
                List<Map<String, Object>> apiMessages =
                        toApiMessages(messages, deepSeekConfig.isStripHistoryReasoning());
                int estimatedTokens = TokenEstimator.estimateMessages(apiMessages);
                int maxTokens = deepSeekConfig.getMaxContextTokens();
                double ratio = (double) estimatedTokens / maxTokens;

                // 异步预压缩阈值比同步更低：超过 60% 就开始预压缩
                if (ratio >= ASYNC_PRECOMPRESS_THRESHOLD) {
                    // ★ 防抖动：刚压缩过就跳过。
                    // 即便估算口径已修正，边界情况下（摘要 + 保护带本身就接近阈值）仍可能在
                    // 相邻两轮连续压缩。每压缩一次前缀就变一次、缓存就全量失效一次，
                    // 而压缩本身收益有限，因此设一个最小间隔，让「压缩 → 复用缓存」至少跑几轮。
                    if (isRecentlyCompacted(conversationId)) {
                        log.debug("异步预压缩跳过：距上次压缩不足 {} 秒，conversationId={}",
                                MIN_COMPACT_INTERVAL_MS / 1000, conversationId);
                        return;
                    }

                    log.info("异步预压缩触发：conversationId={}, 估算 tokens={}（已应用压缩记录）, 比例={}",
                            conversationId, estimatedTokens, ratio);

                    // 传入「已应用压缩记录」的消息列表：compact() 内部会重新查 DB 并再次应用压缩记录，
                    // 两者逐条对齐后 compressStart/compressEnd 索引才有意义
                    // （原实现传的是未压缩的全量列表，索引与 compact 内部不一致，会压错区间）。
                    compact(conversationId, apiMessages, providerCode);
                }
            } catch (Exception e) {
                log.warn("异步预压缩失败: conversationId={}, error={}", conversationId, e.getMessage());
            } finally {
                precompressingConversations.remove(conversationId);
            }
        }, precompressExecutor);
    }

    /**
     * 判断该会话是否刚刚压缩过（防抖动）。
     * <p>
     * 取当前生效记录中最新的 createdAt 与当前时间比较；无记录则视为「未压缩过」。
     * </p>
     */
    private boolean isRecentlyCompacted(Long conversationId) {
        try {
            List<CompactionRecord> records = compactionMapper.selectActiveByConversationId(conversationId);
            if (records == null || records.isEmpty()) {
                return false;
            }
            LocalDateTime latest = null;
            for (CompactionRecord record : records) {
                LocalDateTime createdAt = record.getCreatedAt();
                if (createdAt != null && (latest == null || createdAt.isAfter(latest))) {
                    latest = createdAt;
                }
            }
            if (latest == null) {
                return false;
            }
            long elapsedMs = java.time.Duration.between(latest, LocalDateTime.now()).toMillis();
            return elapsedMs >= 0 && elapsedMs < MIN_COMPACT_INTERVAL_MS;
        } catch (Exception e) {
            log.debug("检查最近压缩时间失败（忽略该保护）: {}", e.getMessage());
            return false;
        }
    }

    /**
     * 在构建历史消息时，将压缩记录注入到消息列表中
     * 在 buildMessagesFromHistory 中调用，用于将 DB 中的压缩摘要替换原始消息
     *
     * @param conversationId 会话 ID
     * @param dbMessages      从 DB 读出的原始消息列表（会修改）
     */
    public void applyCompactionRecords(Long conversationId, List<ConversationMessage> dbMessages) {
        if (dbMessages == null || dbMessages.isEmpty()) return;

        List<CompactionRecord> records = compactionMapper.selectActiveByConversationId(conversationId);
        if (records.isEmpty()) return;

        // 按创建时间正序处理
        records.sort(Comparator.comparing(CompactionRecord::getCreatedAt));

        // 从后往前处理，避免索引错乱
        List<CompactionRecord> reversed = new ArrayList<>(records);
        Collections.reverse(reversed);

        for (CompactionRecord record : reversed) {
            // 找到 startMessageId 和 endMessageId 在 dbMessages 中的索引
            int startIdx = -1;
            int endIdx = -1;
            for (int i = 0; i < dbMessages.size(); i++) {
                if (dbMessages.get(i).getId().equals(record.getStartMessageId())) {
                    startIdx = i;
                }
                if (dbMessages.get(i).getId().equals(record.getEndMessageId())) {
                    endIdx = i;
                    break; // endIdx 一定在 startIdx 之后
                }
            }

            if (startIdx < 0 || endIdx < 0 || endIdx < startIdx) {
                log.warn("压缩记录索引无效，跳过: recordId={}, startIdx={}, endIdx={}", record.getId(), startIdx, endIdx);
                continue;
            }

            // 额外检查：确保索引在有效范围内
            if (startIdx >= dbMessages.size() || endIdx >= dbMessages.size()) {
                log.warn("压缩记录索引越界，跳过: recordId={}, startIdx={}, endIdx={}, dbMessages.size={}",
                        record.getId(), startIdx, endIdx, dbMessages.size());
                continue;
            }

            // 创建压缩摘要消息
            ConversationMessage compactMsg = new ConversationMessage();
            // ★ 使用负数合成 ID（数据库真实 ID 均为正数，不会冲突）：
            // 摘要消息被后续压缩范围覆盖时，compact() 会以该 ID 作为 startMsgId/endMsgId 落库，
            // 保证 applyCompactionRecords 的索引匹配仍能成立，压缩链不会断裂。
            compactMsg.setId(-record.getId());
            compactMsg.setConversationId(conversationId);
            compactMsg.setRole(MessageRole.SYSTEM);
            compactMsg.setContent(record.getSummary());
            compactMsg.setToolCalls("{\"type\":\"compaction\",\"recordId\":" + record.getId()
                    + ",\"replacedCount\":" + (endIdx - startIdx + 1) + "}");
            compactMsg.setCreatedAt(dbMessages.get(startIdx).getCreatedAt());

            // 替换 [startIdx, endIdx] 为 compactMsg
            List<ConversationMessage> remaining = new ArrayList<>();
            remaining.addAll(dbMessages.subList(0, startIdx));
            remaining.add(compactMsg);
            remaining.addAll(dbMessages.subList(endIdx + 1, dbMessages.size()));

            dbMessages.clear();
            dbMessages.addAll(remaining);

            log.debug("应用压缩记录: recordId={}, 替换了 {} 条消息为 {} tokens 的摘要",
                    record.getId(), (endIdx - startIdx + 1),
                    TokenEstimator.estimate(record.getSummary()));
        }
    }

    // ==================== 内部方法 ====================

    /**
     * 调用 LLM 执行压缩（使用默认 Provider 的 LLMClient）
     */
    private String callCompactionLlm(List<ConversationMessage> messages, String providerCode) {
        String historyText = formatMessagesForCompaction(messages);
        String prompt = buildCompactionPrompt(historyText);

        LLMClient client = (providerCode != null && !providerCode.isEmpty())
                ? llmClientManager.resolveClientByCode(providerCode)
                : llmClientManager.getDefaultClient();
        if (client == null) {
            log.error("无法获取 LLMClient，providerCode={}, 跳过压缩", providerCode);
            return null;
        }
        String model = deepSeekConfig.getCompaction().getModel();
        if (model == null || model.trim().isEmpty()) {
            model = llmClientManager.getDefaultModel(client.getProviderCode());
        }

        // 构建消息列表
        List<Map<String, Object>> msgs = new ArrayList<>();
        Map<String, Object> userMsg = new HashMap<>();
        userMsg.put("role", "user");
        userMsg.put("content", prompt);
        msgs.add(userMsg);

        // 使用 LLMClient 构建请求体 + 阻塞调用
        Map<String, Object> requestBody = client.buildRequestBody(msgs, model, 0.3, "non-thinking", false, null);

        try {
            String responseBody = client.blockingChat(requestBody, java.time.Duration.ofSeconds(60));
            return client.extractContentFromBlockingResponse(responseBody);
        } catch (Exception e) {
            log.error("调用 LLM 压缩失败: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 构建压缩 prompt
     */
    private String buildCompactionPrompt(String historyText) {
        return "你是一个对话上下文压缩专家。请将以下 AI 助手的对话历史压缩为结构化摘要。\n\n"
                + "要求：\n"
                + "1. 保留所有关键决策、技术方案、代码变更、文件路径\n"
                + "2. 保持任务进度和待办事项的完整性\n"
                + "3. 用简洁的要点形式输出，不要遗漏重要信息\n"
                + "4. 必须使用中文输出\n"
                + "5. 仅输出结构化摘要本身，不要额外解释\n\n"
                + "请按以下结构输出：\n\n"
                + "## 目标\n"
                + "- [一句话概括当前任务]\n\n"
                + "## 已完成\n"
                + "- [已完成的子任务或关键步骤]\n\n"
                + "## 进行中\n"
                + "- [当前正在进行的工作]\n\n"
                + "## 关键决策\n"
                + "- [重要的技术决策及原因]\n\n"
                + "## 待办事项\n"
                + "- [下一步需要做的事]\n\n"
                + "## 重要上下文\n"
                + "- [文件路径、变量名、架构信息等需要记住的技术细节]\n\n"
                + "待压缩的对话历史：\n"
                + "---\n"
                + historyText
                + "\n---";
    }

    /**
     * 将消息列表格式化为纯文本，供压缩 LLM 使用
     */
    private String formatMessagesForCompaction(List<ConversationMessage> messages) {
        StringBuilder sb = new StringBuilder();
        for (ConversationMessage msg : messages) {
            String role = msg.getRole().getValue(); // user / assistant / tool / system
            sb.append("【").append(role.toUpperCase()).append("】\n");
            if (msg.getContent() != null && !msg.getContent().isEmpty()) {
                sb.append(msg.getContent()).append("\n");
            }
            if (msg.getReasoning() != null && !msg.getReasoning().isEmpty()) {
                sb.append("【思考过程】\n").append(msg.getReasoning()).append("\n");
            }
            sb.append("\n");
        }
        return sb.toString();
    }

    /**
     * 将 ConversationMessage 列表转为「与真实请求逐条对齐」的 API 格式消息列表。
     * <p>
     * <b>为什么必须逐条对齐（一一映射，不跳过、不净化）</b>：{@link #compact} 内部会重新
     * {@code selectByConversationId} 并 {@link #applyCompactionRecords}，然后用
     * {@code compressStart / compressEnd} 索引在这份列表上定位压缩区间。调用方传入的列表
     * 必须与之逐条对应，否则会压错区间（甚至压到保护带）。
     * </p>
     * <p>
     * <b>口径必须与 ContextBuilder 一致</b>：
     * <ul>
     *   <li>TOOL 消息的 API {@code content} 取自 {@code reasoning} 字段（工具结果存在该字段），
     *       且<b>不受 stripHistoryReasoning 影响</b>——它存的是工具结果，不是思考链。
     *       原实现把它当 {@code reasoning_content} 直接丢弃，导致工具结果（本系统上下文的
     *       绝对大头）完全不计入估算，估算值严重偏低。</li>
     *   <li>ASSISTANT 消息的 {@code tool_calls} 也有 token 开销，需计入。</li>
     * </ul>
     * </p>
     *
     * @param messages               已经过排序 + {@link #applyCompactionRecords} 处理的 DB 消息
     * @param stripHistoryReasoning  是否剔除历史思考链（balanced / compact 模式为 true）
     * @return API 格式消息列表，与入参逐条对应
     */
    static List<Map<String, Object>> toApiMessages(List<ConversationMessage> messages,
                                                   boolean stripHistoryReasoning) {
        List<Map<String, Object>> result = new ArrayList<>();
        if (messages == null) {
            return result;
        }
        for (ConversationMessage msg : messages) {
            Map<String, Object> map = new HashMap<>();
            MessageRole role = msg.getRole();

            // 角色缺失时保底占位，绝不能让条数变少（否则索引错位）
            if (role == null) {
                map.put("role", "system");
                result.add(map);
                continue;
            }

            map.put("role", role.getValue());

            if (role == MessageRole.TOOL) {
                // 工具结果存在 reasoning 字段（content 通常为 NULL）
                String content = msg.getReasoning() != null ? msg.getReasoning()
                        : (msg.getContent() != null ? msg.getContent() : "");
                map.put("content", content);
            } else {
                if (msg.getContent() != null) {
                    map.put("content", msg.getContent());
                }
                if (role == MessageRole.ASSISTANT) {
                    if (msg.getToolCalls() != null && !msg.getToolCalls().isEmpty()) {
                        map.put("tool_calls", msg.getToolCalls());
                    }
                    if (msg.getReasoning() != null && !stripHistoryReasoning) {
                        map.put("reasoning_content", msg.getReasoning());
                    }
                }
            }
            result.add(map);
        }
        return result;
    }

    /**
     * 在 API 格式的消息列表中，用压缩摘要替换指定范围的消息
     */
    private void replaceWithCompaction(List<Map<String, Object>> messages,
                                       int startInclusive, int endExclusive, String summary) {
        // 移除旧消息（从后往前）
        for (int i = endExclusive - 1; i >= startInclusive; i--) {
            messages.remove(i);
        }

        // 插入压缩摘要作为 system 消息
        Map<String, Object> compactMsg = new HashMap<>();
        compactMsg.put("role", "system");
        compactMsg.put("content", "【上下文摘要 - 以下为之前对话的压缩记录】\n" + summary);
        messages.add(startInclusive, compactMsg);

        log.info("替换压缩摘要：位置 {}~{}, 摘要长度={}", startInclusive, endExclusive - 1, summary.length());
    }

    /**
     * 查找保护带的结束位置（保护带内的消息不压缩不丢弃）
     * 保护带指最近的 protectRounds 轮完整对话
     */
    private int findProtectZoneEnd(List<Map<String, Object>> messages, int protectRounds) {
        if (messages == null || messages.isEmpty()) return 0;

        int roundsFound = 0;
        // 从最后一条消息往前找
        for (int i = messages.size() - 1; i >= 0; i--) {
            Map<String, Object> msg = messages.get(i);
            String role = (String) msg.get("role");
            if ("user".equals(role)) {
                roundsFound++;
                if (roundsFound > protectRounds) {
                    // 找到保护带起始位置
                    int zoneEnd = i + 1;
                    // 往前找到这一轮 user 消息的前一条消息的索引
                    // zoneEnd 就是保护带中最早的消息索引
                    return zoneEnd;
                }
            }
        }
        // 保护带覆盖全部消息
        return 0;
    }

    // ==================== 内部枚举 ====================

    public enum CompactionDecision {
        /** 无需压缩 */
        NONE,
        /** 黄色预警：标记但暂不压缩 */
        WARN,
        /** 橙色压缩：执行 LLM 压缩 */
        COMPACT,
        /** 红色丢弃：压缩后仍超限，执行滑动窗口丢弃 */
        DROP
    }
}
