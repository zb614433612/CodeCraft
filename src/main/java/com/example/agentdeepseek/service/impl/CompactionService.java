package com.example.agentdeepseek.service.impl;

import com.example.agentdeepseek.config.DeepSeekConfig;
import com.example.agentdeepseek.mapper.CompactionMapper;
import com.example.agentdeepseek.mapper.ConversationMessageMapper;
import com.example.agentdeepseek.model.entity.CompactionRecord;
import com.example.agentdeepseek.model.entity.ConversationMessage;
import com.example.agentdeepseek.model.entity.MessageRole;
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
import org.springframework.web.reactive.function.client.WebClient;

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
    private final WebClient webClient;
    private final CompactionMapper compactionMapper;
    private final ConversationMessageMapper conversationMessageMapper;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** 预压缩执行器（单线程，避免并发冲突） */
    private final java.util.concurrent.ExecutorService precompressExecutor =
            java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "compaction-precompress-");
                t.setDaemon(true);
                return t;
            });

    /** 会话是否正在预压缩中（防止重复触发） */
    private final Set<Long> precompressingConversations = ConcurrentHashMap.newKeySet();

    @Autowired
    public CompactionService(DeepSeekConfig deepSeekConfig,
                             CompactionMapper compactionMapper,
                             ConversationMessageMapper conversationMessageMapper,
                             WebClient deepSeekWebClient) {
        this.deepSeekConfig = deepSeekConfig;
        this.webClient = deepSeekWebClient;
        this.compactionMapper = compactionMapper;
        this.conversationMessageMapper = conversationMessageMapper;
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

        // 找到对应的消息 ID 范围（现在 allMessages 的消息数量与 messages 一致）
        Long startMsgId = allMessages.get(Math.min(compressStart, allMessages.size() - 1)).getId();
        Long endMsgId = allMessages.get(Math.min(compressEnd - 1, allMessages.size() - 1)).getId();

        // 3. 收集被压缩的消息内容，构建压缩 prompt
        // 使用索引范围提取（比 ID 范围更可靠，因为摘要消息可能没有真实 ID）
        List<ConversationMessage> toCompress;
        if (compressStart >= 0 && compressEnd <= allMessages.size() && compressStart < compressEnd) {
            toCompress = new ArrayList<>(allMessages.subList(compressStart, compressEnd));
        } else {
            toCompress = allMessages.stream()
                    .filter(m -> {
                        Long id = m.getId();
                        if (id == null) return false;
                        return id >= startMsgId && id <= endMsgId;
                    })
                    .collect(Collectors.toList());
        }

        if (toCompress.isEmpty()) {
            return -1;
        }

        // 4. 调用 LLM 压缩
        String summary;
        try {
            summary = callCompactionLlm(toCompress);
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

        // 6. 保存压缩记录到 DB
        CompactionRecord record = new CompactionRecord(conversationId, summary, startMsgId, endMsgId, Math.max(savings, 0));
        compactionMapper.insert(record);
        log.info("压缩完成：conversationId={}, 压缩 {} 条消息, 节省 {} tokens, recordId={}",
                conversationId, toCompress.size(), Math.max(savings, 0), record.getId());

        // 7. 将之前已有的、被本压缩覆盖的旧压缩记录标记为废弃
        List<CompactionRecord> activeRecords = compactionMapper.selectActiveByConversationId(conversationId);
        for (CompactionRecord old : activeRecords) {
            if (!old.getId().equals(record.getId()) && old.getEndMessageId() <= endMsgId) {
                compactionMapper.markSuperseded(old.getId());
                log.debug("标记旧压缩记录为废弃: id={}", old.getId());
            }
        }

        // 8. 在内存消息列表中，用压缩摘要替换原始消息
        replaceWithCompaction(messages, compressStart, compressEnd, summary);

        return TokenEstimator.estimateMessages(messages);
    }

    /**
     * 异步预压缩：在请求返回后，后台提前压缩最早的历史
     * 这样下次请求时 buildMessagesFromHistory 可以直接使用压缩结果，无需等待 LLM
     *
     * @param conversationId 会话 ID
     */
    public void asyncPrecompress(Long conversationId) {
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

                // 粗略估算 token（用 DB 消息长度估算）
                int estimatedTokens = estimateDbMessages(messages);
                int maxTokens = deepSeekConfig.getMaxContextTokens();
                double ratio = (double) estimatedTokens / maxTokens;

                // 异步预压缩阈值比同步更低：超过 60% 就开始预压缩
                if (ratio >= 0.60) {
                    log.info("异步预压缩触发：conversationId={}, 估算 tokens={}, 比例={}",
                            conversationId, estimatedTokens, ratio);

                    // 构建 API 格式消息列表来调用 compact（内部会重新查 DB）
                    List<Map<String, Object>> apiMessages = buildApiMessages(messages);
                    compact(conversationId, apiMessages);
                }
            } catch (Exception e) {
                log.warn("异步预压缩失败: conversationId={}, error={}", conversationId, e.getMessage());
            } finally {
                precompressingConversations.remove(conversationId);
            }
        }, precompressExecutor);
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

            // 创建压缩摘要消息
            ConversationMessage compactMsg = new ConversationMessage();
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
     * 调用 LLM 执行压缩
     */
    private String callCompactionLlm(List<ConversationMessage> messages) {
        String historyText = formatMessagesForCompaction(messages);
        String prompt = buildCompactionPrompt(historyText);

        String model = deepSeekConfig.getCompaction().getModel();
        if (model == null || model.trim().isEmpty()) {
            model = deepSeekConfig.getDefaultModel();
        }

        // 构建请求体
        ObjectNode requestBody = objectMapper.createObjectNode();
        requestBody.put("model", model);
        requestBody.put("stream", false);
        requestBody.put("temperature", 0.3); // 低温度确保一致性

        ArrayNode messagesNode = requestBody.putArray("messages");
        ObjectNode userMsg = messagesNode.addObject();
        userMsg.put("role", "user");
        userMsg.put("content", prompt);

        try {
            String responseBody = webClient.post()
                    .bodyValue(requestBody)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block(java.time.Duration.ofSeconds(60));

            if (responseBody == null || responseBody.isEmpty()) {
                log.warn("LLM 压缩返回空响应");
                return null;
            }

            JsonNode root = objectMapper.readTree(responseBody);
            JsonNode choices = root.path("choices");
            if (choices.isArray() && choices.size() > 0) {
                JsonNode message = choices.get(0).path("message");
                String content = message.path("content").asText("");
                if (!content.isEmpty()) {
                    return content.trim();
                }
            }

            log.warn("LLM 压缩返回格式异常: {}", responseBody.length() > 200 ? responseBody.substring(0, 200) : responseBody);
            return null;
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
     * 将 ConversationMessage 列表转为 API 格式的 Map 列表（用于 token 估算）
     */
    private List<Map<String, Object>> buildApiMessages(List<ConversationMessage> messages) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (ConversationMessage msg : messages) {
            Map<String, Object> map = new HashMap<>();
            map.put("role", msg.getRole().getValue());
            if (msg.getContent() != null) map.put("content", msg.getContent());
            if (msg.getReasoning() != null) map.put("reasoning_content", msg.getReasoning());
            if (msg.getToolCalls() != null) map.put("tool_calls", msg.getToolCalls());
            result.add(map);
        }
        return result;
    }

    /**
     * 粗略估算 DB 消息的 token 数（用于异步预压缩判断）
     */
    private int estimateDbMessages(List<ConversationMessage> messages) {
        int total = 0;
        for (ConversationMessage msg : messages) {
            total += TokenEstimator.estimate(msg.getContent());
            total += TokenEstimator.estimate(msg.getReasoning());
            total += 10; // 消息开销
        }
        return total;
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
