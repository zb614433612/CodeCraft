package com.example.agentdeepseek.service.impl;

import com.example.agentdeepseek.config.DeepSeekConfig;
import com.example.agentdeepseek.mapper.ConversationMapper;
import com.example.agentdeepseek.mapper.ConversationMessageMapper;
import com.example.agentdeepseek.model.entity.Conversation;
import com.example.agentdeepseek.model.entity.ConversationMessage;
import com.example.agentdeepseek.model.entity.MessageRole;
import com.example.agentdeepseek.util.TokenEstimator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.*;

/**
 * 上下文构建器
 * <p>
 * 从 DeepSeekServiceImpl 拆分出来，负责：
 * - 从数据库构建历史消息列表
 * - Token 预算管理与滑动窗口裁剪
 * - SSE 流式响应的解析与清洗
 * - 语言指令注入
 * - 技能工具名格式化
 * </p>
 */
@Slf4j
@Component
public class ContextBuilder {

    private static final int SESSION_NAME_TRUNCATE_LENGTH = 6;

    private final ConversationMapper conversationMapper;
    private final ConversationMessageMapper conversationMessageMapper;
    private final CompactionService compactionService;
    private final DeepSeekConfig deepSeekConfig;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public ContextBuilder(ConversationMapper conversationMapper,
                          ConversationMessageMapper conversationMessageMapper,
                          CompactionService compactionService,
                          DeepSeekConfig deepSeekConfig) {
        this.conversationMapper = conversationMapper;
        this.conversationMessageMapper = conversationMessageMapper;
        this.compactionService = compactionService;
        this.deepSeekConfig = deepSeekConfig;
    }

    // ==================== Agent 类型解析 ====================

    /**
     * 根据提示词文件名映射会话类型
     */
    public String resolveAgentType(String promptFileName) {
        if (promptFileName == null) return "ai_assistant";
        return switch (promptFileName) {
            case "code_agent_prompt.txt" -> "code_assistant";
            default -> "code_assistant";
        };
    }

    // ==================== 会话管理 ====================

    /**
     * 获取或创建会话
     * @param sessionId 可选会话ID，如果为null则创建新会话
     * @param userMessage 用户消息，用于生成会话名称
     * @param userId 用户ID，可以为null
     * @param agentType 会话类型
     * @param agentConfigId Agent配置ID
     * @param workDir 工作目录
     * @return 会话对象
     */
    public Conversation getOrCreateConversation(Long sessionId, String userMessage, Long userId,
                                                 String agentType, Long agentConfigId, String workDir) {
        if (sessionId != null) {
            Optional<Conversation> conversationOpt = conversationMapper.selectById(sessionId);
            if (conversationOpt.isPresent()) {
                Conversation conversation = conversationOpt.get();
                conversation.setUpdatedAt(LocalDateTime.now());
                conversationMapper.update(conversation);
                return conversation;
            }
            log.warn("指定的会话ID {} 不存在，将创建新会话", sessionId);
        }
        // 创建新会话
        String sessionName = userMessage.length() > SESSION_NAME_TRUNCATE_LENGTH
                ? userMessage.substring(0, SESSION_NAME_TRUNCATE_LENGTH)
                : userMessage;
        Conversation conversation = new Conversation(sessionName, userId, agentType, agentConfigId);
        if (workDir != null) {
            conversation.setWorkDir(workDir);
        }
        conversationMapper.insert(conversation);
        log.debug("创建新会话: ID={}, Name={}, UserId={}, AgentType={}, AgentConfigId={}, WorkDir={}",
                conversation.getId(), conversation.getName(), userId, agentType, agentConfigId, workDir);
        return conversation;
    }

    // ==================== 历史消息构建 ====================

    /**
     * 根据会话ID构建历史消息列表，用于API请求
     * 将数据库中的消息格式转换为DeepSeek API所需的消息格式
     * 集成三级阈值压缩策略：应用已有压缩记录 → 智能压缩 → 滑动窗口丢弃
     * @param conversationId 会话ID
     * @return 消息列表，每个元素包含role、content和可选的tool_calls
     */
    public List<Map<String, Object>> buildMessagesFromHistory(Long conversationId) {
        // 1. 加载原始消息
        List<ConversationMessage> dbMessages = conversationMessageMapper.selectByConversationId(conversationId);

        // 2. 应用已有的压缩记录（将已被压缩的消息替换为摘要）
        compactionService.applyCompactionRecords(conversationId, dbMessages);

        // 3. 将消息转换为 API 格式
        List<Map<String, Object>> result = new ArrayList<>();
        Map<Integer, List<String>> assistantToolCallIdsMap = new HashMap<>();
        Set<String> consumedToolCallIds = new HashSet<>();
        Queue<String> pendingToolCallIdQueue = new LinkedList<>();

        for (ConversationMessage msg : dbMessages) {
            Map<String, Object> messageMap = new HashMap<>();
            MessageRole role = msg.getRole();

            if (role == MessageRole.USER || role == MessageRole.SYSTEM) {
                messageMap.put("role", role.getValue());
                String content = msg.getContent() != null ? msg.getContent() : "";
                messageMap.put("content", content);
                result.add(messageMap);
            } else if (role == MessageRole.ASSISTANT) {
                messageMap.put("role", "assistant");

                if (!pendingToolCallIdQueue.isEmpty()) {
                    log.warn("发现未消耗的tool call IDs，可能数据不一致: {}", pendingToolCallIdQueue);
                    pendingToolCallIdQueue.clear();
                }

                if (msg.getToolCalls() != null && !msg.getToolCalls().isEmpty()) {
                    try {
                        JsonNode toolCallsNode = objectMapper.readTree(msg.getToolCalls());
                        messageMap.put("tool_calls", toolCallsNode);

                        if (toolCallsNode.isArray()) {
                            List<String> idList = new ArrayList<>();
                            for (JsonNode toolCall : toolCallsNode) {
                                JsonNode idNode = toolCall.path("id");
                                if (!idNode.isMissingNode() && !idNode.isNull()) {
                                    String id = idNode.asText();
                                    pendingToolCallIdQueue.add(id);
                                    idList.add(id);
                                }
                            }
                            assistantToolCallIdsMap.put(result.size(), idList);
                        }
                    } catch (Exception e) {
                        log.warn("解析tool_calls JSON失败: {}", e.getMessage());
                    }
                }

                String content = msg.getContent();
                if (content != null && !content.isEmpty()) {
                    messageMap.put("content", content);
                }

                String reasoning = msg.getReasoning();
                if (reasoning != null && !reasoning.isEmpty()) {
                    messageMap.put("reasoning_content", reasoning);
                }

                result.add(messageMap);
            } else if (role == MessageRole.TOOL) {
                if (!pendingToolCallIdQueue.isEmpty()) {
                    String toolCallId = pendingToolCallIdQueue.poll();
                    consumedToolCallIds.add(toolCallId);
                    messageMap.put("role", "tool");
                    messageMap.put("tool_call_id", toolCallId);

                    String content = msg.getReasoning() != null ? msg.getReasoning() :
                            (msg.getContent() != null ? msg.getContent() : "");
                    messageMap.put("content", content);

                    result.add(messageMap);
                } else {
                    log.warn("TOOL消息没有对应的tool call ID，跳过该消息: {}", msg.getId());
                }
            }
        }

        // 精确修复：只移除缺少对应 TOOL 消息的 assistant 消息的 tool_calls
        boolean hasOrphanToolCalls = false;
        for (Map.Entry<Integer, List<String>> entry : assistantToolCallIdsMap.entrySet()) {
            int msgIndex = entry.getKey();
            List<String> expectedIds = entry.getValue();
            boolean allConsumed = true;
            for (String id : expectedIds) {
                if (!consumedToolCallIds.contains(id)) {
                    allConsumed = false;
                    break;
                }
            }
            if (!allConsumed) {
                Map<String, Object> msg = result.get(msgIndex);
                if (msg.containsKey("tool_calls")) {
                    msg.remove("tool_calls");
                    log.info("精确修复：从assistant消息[{}]中移除了孤立的tool_calls（缺少对应的TOOL消息）", msgIndex);
                    hasOrphanToolCalls = true;
                }
            }
        }
        if (hasOrphanToolCalls) {
            log.warn("上下文孤立修复完成：移除了 {} 条缺少TOOL消息的assistant消息的tool_calls",
                     assistantToolCallIdsMap.size() -
                     (int) assistantToolCallIdsMap.values().stream()
                         .filter(ids -> ids.stream().allMatch(consumedToolCallIds::contains))
                         .count());
        }

        // 4. 三级阈值上下文管理：先尝试压缩，兜底丢弃
        int maxTokens = deepSeekConfig.getMaxContextTokens();
        int estimatedTokens = TokenEstimator.estimateMessages(result);

        if (estimatedTokens > maxTokens * deepSeekConfig.getCompaction().getCompactThreshold()) {
            log.info("历史上下文超限（压缩阈值），尝试智能压缩：估算 {} tokens, 上限 {}",
                    estimatedTokens, maxTokens);
            int afterCompactTokens = compactionService.compact(conversationId, result);
            if (afterCompactTokens > 0) {
                estimatedTokens = afterCompactTokens;
                log.info("智能压缩完成，压缩后 {} tokens", estimatedTokens);
            }
        }

        if (estimatedTokens > maxTokens * deepSeekConfig.getCompaction().getDropThreshold()) {
            log.warn("智能压缩后仍超限，执行滑动窗口丢弃：估算 {} tokens, 上限 {}",
                    estimatedTokens, maxTokens);
            trimToTokenBudget(result, maxTokens);
        }

        return result;
    }

    /**
     * 根据会话ID构建历史消息列表（支持上下文模式）
     * @param conversationId 会话ID
     * @param contextMode 上下文模式：full（全量）/ compact（精简）
     * @return 消息列表
     */
    public List<Map<String, Object>> buildMessagesFromHistory(Long conversationId, String contextMode) {
        List<Map<String, Object>> result = buildMessagesFromHistory(conversationId);
        if ("compact".equals(contextMode)) {
            int beforeTokens = TokenEstimator.estimateMessages(result);
            compactHistoryMessages(result);
            int afterTokens = TokenEstimator.estimateMessages(result);
            log.info("compact 模式：精简完成, 消息数={}, Token: {} → {} (节省 {}%)",
                    result.size(), beforeTokens, afterTokens,
                    beforeTokens > 0 ? (beforeTokens - afterTokens) * 100 / beforeTokens : 0);

            // 精简后仍超限则执行滑动窗口丢弃
            int maxTokens = deepSeekConfig.getMaxContextTokens();
            if (afterTokens > maxTokens * deepSeekConfig.getCompaction().getDropThreshold()) {
                log.warn("compact 精简后仍超限，执行滑动窗口丢弃：{} tokens > {} 上限",
                        afterTokens, maxTokens);
                trimToTokenBudget(result, maxTokens);
            }
        }
        return result;
    }

    /**
     * 精简非本轮对话的消息（compact 模式核心逻辑）
     * <p>
     * 规则：
     * - 找到最后一条 user 消息作为"本轮对话"的起点
     * - 此前的 assistant 消息移除 reasoning_content
     * - 此前的 tool 消息 content 替换为"[工具 xxx: 成功/失败]"摘要
     * - tool_call_id 完整保留（API 格式要求）
     * </p>
     */
    private void compactHistoryMessages(List<Map<String, Object>> messages) {
        if (messages == null || messages.isEmpty()) return;

        // 1. 从后往前找到最后一条 user 消息的索引（"本轮对话"起点）
        int lastUserIdx = -1;
        for (int i = messages.size() - 1; i >= 0; i--) {
            if ("user".equals(messages.get(i).get("role"))) {
                lastUserIdx = i;
                break;
            }
        }
        if (lastUserIdx <= 0) {
            log.debug("compact: 没有历史消息需要精简（lastUserIdx={}）", lastUserIdx);
            return;
        }

        // 2. 构建 tool_call_id → tool_name 的映射
        Map<String, String> tcIdToName = new HashMap<>();
        for (Map<String, Object> msg : messages) {
            Object tcObj = msg.get("tool_calls");
            if (tcObj instanceof JsonNode tcNode && tcNode.isArray()) {
                for (JsonNode tc : tcNode) {
                    String id = tc.path("id").asText();
                    String name = tc.path("function").path("name").asText();
                    // 过滤 NullNode 返回的 "null" 字符串
                    if (!id.isEmpty() && !"null".equals(id) && !name.isEmpty() && !"null".equals(name)) {
                        tcIdToName.put(id, name);
                    }
                }
            }
        }

        // 3. 遍历 lastUserIdx 之前的消息并精简
        int compactedToolCount = 0;
        int removedReasoningCount = 0;
        for (int i = 0; i < lastUserIdx; i++) {
            Map<String, Object> msg = messages.get(i);
            String role = (String) msg.get("role");

            if ("assistant".equals(role)) {
                // 移除 reasoning_content（历史思考过程参考价值低、token 消耗大）
                if (msg.containsKey("reasoning_content")) {
                    msg.remove("reasoning_content");
                    removedReasoningCount++;
                }
            } else if ("tool".equals(role)) {
                // 精简 tool content
                String content = (String) msg.get("content");
                String toolCallId = (String) msg.get("tool_call_id");
                String toolName = toolCallId != null ? tcIdToName.getOrDefault(toolCallId, "unknown") : "unknown";

                if (content != null) {
                    // 错误检测：检查特定的错误前缀模式，避免误判包含 "error" 的正常内容
                    // 只检查明确的错误标识，不检查通用的 "error"/"ERROR" 关键词
                    String contentPrefix = content.length() > 200 ? content.substring(0, 200) : content;
                    boolean isError = contentPrefix.startsWith("ERROR:") ||
                            contentPrefix.startsWith("ERROR：") ||
                            contentPrefix.startsWith("Error:") ||
                            contentPrefix.startsWith("Error：") ||
                            contentPrefix.startsWith("Exception:") ||
                            contentPrefix.startsWith("Exception：") ||
                            contentPrefix.contains("【缺少参数】") ||
                            contentPrefix.contains("【未找到】") ||
                            contentPrefix.contains("【查询失败】") ||
                            contentPrefix.contains("【无匹配") ||
                            contentPrefix.contains("【无数据】") ||
                            contentPrefix.contains("Permission denied") ||
                            contentPrefix.contains("Access denied") ||
                            contentPrefix.contains("命令执行失败") ||
                            contentPrefix.contains("操作失败：");

                    if (isError) {
                        // 提取错误信息前 150 字符
                        String shortContent = content.length() > 150
                                ? content.substring(0, 150) + "..."
                                : content;
                        msg.put("content", "[工具 " + toolName + " 调用失败: " + shortContent + "]");
                    } else {
                        msg.put("content", "[工具 " + toolName + " 调用成功 - 详细结果已精简，使用 query_tool_history 查询]");
                    }
                    compactedToolCount++;
                }
            }
        }

        log.info("compact 精简完成: 精简了 {} 条 tool 消息, 移除了 {} 条 reasoning_content",
                compactedToolCount, removedReasoningCount);
    }

    /**
     * 构建 compact 模式的提示词指令
     * 注入到系统提示词中，告知 LLM 精简机制和查询方式
     * @param conversationId 当前会话ID，用于替换提示词中的占位符
     */
    public String buildCompactModeInstruction(Long conversationId) {
        String cid = conversationId != null ? conversationId.toString() : "当前会话ID";
        return "\n\n【上下文精简模式 - 必须遵守】\n"
                + "当前处于上下文精简模式。为节省 Token，非本轮对话（即最后一条用户消息之前的历史）中的内容已做以下处理：\n"
                + "1. 工具调用结果（tool 消息）已被替换为 \"[工具 xxx 调用成功/失败]\" 的摘要\n"
                + "2. 思考过程（reasoning_content）已被移除\n\n"
                + "如需查看某次工具调用的完整原始结果，请使用 query_tool_history 工具查询：\n"
                + "  - query_tool_history(tool_name=\"工具名\")\n"
                + "    返回最近几次该工具的调用详情（conversation_id 会自动从上下文检测，无需手动传入）\n"
                + "  - query_tool_history(limit=10)\n"
                + "    返回最近10条工具调用详情\n"
                + "  - query_tool_history(message_id=<消息ID>)\n"
                + "    返回指定消息的完整内容\n\n"
                + "本会话的真实ID为 " + cid + "，但工具会自动检测，调用时无需传入此参数。\n"
                + "本指令优先级高于其他指令，确保你不会因为缺少历史详情而做出错误判断。";
    }

    // ==================== Token 管理 ====================

    /**
     * Token 感知的上下文滑动窗口裁剪（兜底策略）
     * 从最早的历史开始，按完整轮次（user + assistant + tool）裁剪，保留保护带
     * @param messages 消息列表（会直接修改）
     * @param maxTokens token 上限
     */
    public void trimToTokenBudget(List<Map<String, Object>> messages, int maxTokens) {
        if (messages == null || messages.isEmpty()) return;

        int estimatedTokens = TokenEstimator.estimateMessages(messages);
        if (estimatedTokens <= maxTokens) return;

        int beforeSize = messages.size();
        log.warn("上下文滑动窗口丢弃：估算 {} tokens (上限 {}), 开始裁剪（共 {} 条消息）",
                estimatedTokens, maxTokens, beforeSize);

        int protectRounds = deepSeekConfig.getCompaction().getProtectRounds();
        int protectStart = findProtectStart(messages, protectRounds);

        int idx = 1;
        int lastIdx = Math.min(protectStart, messages.size() - 1);
        while (idx < lastIdx) {
            int endIdx = idx + 1;
            while (endIdx < lastIdx) {
                String role = (String) messages.get(endIdx).get("role");
                if ("user".equals(role) || "system".equals(role)) break;
                endIdx++;
            }

            for (int i = endIdx - 1; i >= idx; i--) {
                messages.remove(i);
            }
            lastIdx = Math.min(protectStart, messages.size() - 1);

            estimatedTokens = TokenEstimator.estimateMessages(messages);
            if (estimatedTokens <= maxTokens) break;
        }

        int trimmed = beforeSize - messages.size();
        log.info("上下文丢弃完成：移除了 {} 条消息，剩余 {} 条，估算 {} tokens",
                trimmed, messages.size(), estimatedTokens);
    }

    /** 单条工具结果保留给 LLM 的最大字符数（超出部分首尾截断） */
    private static final int MAX_TOOL_CONTENT_CHARS = 30000;
    /** 工具结果截断时保留的头部字符数 */
    private static final int TOOL_HEAD_KEEP_CHARS = 12000;
    /** 工具结果截断时保留的尾部字符数 */
    private static final int TOOL_TAIL_KEEP_CHARS = 3000;

    /**
     * 工具循环专用上下文裁剪（兜底策略，防工具循环内消息无限膨胀导致 API 400 超限）
     * <p>
     * 与 {@link #trimToTokenBudget} 的区别：
     * - trimToTokenBudget 基于 user 轮次保护带，工具循环内通常只有 1 条 user 消息，
     *   findProtectStart 会返回 1 导致裁剪循环不执行，保护带逻辑失效；
     * - 本方法专门处理工具循环场景：
     *   1. 将最早的历史 tool 消息 content 替换为"[工具 xxx 调用成功/失败]"摘要
     *      （tool_call_id 必须保留，API 格式要求，只替换 content）；
     *   2. 摘要化后仍超限时，对剩余超大 tool 消息做首尾截断（保留头尾，中间省略）。
     * </p>
     *
     * @param messages 消息列表（会直接修改）
     * @param maxTokens token 上限
     */
    public void trimToolLoopToBudget(List<Map<String, Object>> messages, int maxTokens) {
        if (messages == null || messages.isEmpty()) return;

        int estimated = TokenEstimator.estimateMessages(messages);
        if (estimated <= maxTokens) return;
        log.warn("工具循环上下文裁剪：估算 {} tokens (上限 {}), 共 {} 条消息", estimated, maxTokens, messages.size());

        // 1. 构建 tool_call_id → tool_name 映射（用于摘要文案）
        Map<String, String> tcIdToName = new HashMap<>();
        for (Map<String, Object> msg : messages) {
            Object tcObj = msg.get("tool_calls");
            if (tcObj instanceof JsonNode tcNode && tcNode.isArray()) {
                for (JsonNode tc : tcNode) {
                    String id = tc.path("id").asText();
                    String name = tc.path("function").path("name").asText();
                    if (!id.isEmpty() && !"null".equals(id) && !name.isEmpty() && !"null".equals(name)) {
                        tcIdToName.put(id, name);
                    }
                }
            }
        }

        // 2. 确定摘要区：保留最近 1 轮完整工具调用（最后一条 assistant(tool_calls) 及其后消息），
        //    倒数第二条 assistant(tool_calls) 之前的 tool 消息全部摘要化
        int summaryEnd = 0; // [0, summaryEnd) 区间内的 tool 消息摘要化
        int assistantWithToolCallsSeen = 0;
        for (int i = messages.size() - 1; i >= 0; i--) {
            Map<String, Object> msg = messages.get(i);
            if ("assistant".equals(msg.get("role")) && msg.containsKey("tool_calls")) {
                assistantWithToolCallsSeen++;
                if (assistantWithToolCallsSeen >= 2) {
                    summaryEnd = i;
                    break;
                }
            }
        }

        // 3. 摘要化 [0, summaryEnd) 内的 tool 消息（保留 tool_call_id，替换 content）
        int compacted = 0;
        for (int i = 0; i < summaryEnd; i++) {
            Map<String, Object> msg = messages.get(i);
            if (!"tool".equals(msg.get("role"))) continue;
            String content = (String) msg.get("content");
            if (content == null || content.isEmpty()) continue;
            String toolCallId = (String) msg.get("tool_call_id");
            String toolName = toolCallId != null ? tcIdToName.getOrDefault(toolCallId, "unknown") : "unknown";

            String prefix = content.length() > 200 ? content.substring(0, 200) : content;
            boolean isError = prefix.startsWith("ERROR:") || prefix.startsWith("ERROR：")
                    || prefix.startsWith("Error:") || prefix.startsWith("Error：")
                    || prefix.startsWith("Exception:") || prefix.startsWith("Exception：")
                    || prefix.contains("【缺少参数】") || prefix.contains("【未找到】")
                    || prefix.contains("【查询失败】") || prefix.contains("【无匹配")
                    || prefix.contains("【无数据】") || prefix.contains("Permission denied")
                    || prefix.contains("Access denied") || prefix.contains("命令执行失败")
                    || prefix.contains("操作失败：");

            if (isError) {
                String shortContent = content.length() > 150 ? content.substring(0, 150) + "..." : content;
                msg.put("content", "[工具 " + toolName + " 调用失败(上下文裁剪): " + shortContent + "]");
            } else {
                msg.put("content", "[工具 " + toolName + " 调用成功 - 上下文超限已裁剪，完整结果请用 query_tool_history 查询]");
            }
            compacted++;
        }

        // 4. 仍超限：对剩余超大 tool 消息按从新到旧顺序做首尾截断（保留头尾，省略中间）
        estimated = TokenEstimator.estimateMessages(messages);
        int truncated = 0;
        if (estimated > maxTokens) {
            for (int i = messages.size() - 1; i >= 0 && estimated > maxTokens; i--) {
                Map<String, Object> msg = messages.get(i);
                if (!"tool".equals(msg.get("role"))) continue;
                String content = (String) msg.get("content");
                if (content == null || content.length() <= MAX_TOOL_CONTENT_CHARS) continue;
                String toolCallId = (String) msg.get("tool_call_id");
                String toolName = toolCallId != null ? tcIdToName.getOrDefault(toolCallId, "unknown") : "unknown";
                msg.put("content", truncatePreservingHeadTail(content, toolName));
                truncated++;
                estimated = TokenEstimator.estimateMessages(messages);
            }
        }

        log.info("工具循环裁剪完成：摘要化 {} 条, 截断 {} 条, 剩余估算 {} tokens", compacted, truncated, estimated);
    }

    /**
     * 工具结果首尾截断：保留头部（文件头/摘要）和尾部（错误信息/结尾），中间省略
     */
    private String truncatePreservingHeadTail(String content, String toolName) {
        if (content == null || content.length() <= MAX_TOOL_CONTENT_CHARS) return content;
        int omitted = content.length() - TOOL_HEAD_KEEP_CHARS - TOOL_TAIL_KEEP_CHARS;
        return content.substring(0, TOOL_HEAD_KEEP_CHARS)
                + "\n\n...[工具 " + toolName + " 结果过长，已截断：原始 " + content.length()
                + " 字符，中间省略 " + omitted + " 字符]...\n\n"
                + content.substring(content.length() - TOOL_TAIL_KEEP_CHARS);
    }

    /**
     * 查找保护带的起始位置
     * 从后往前找，找到第 protectRounds 个 user 消息的索引
     */
    private int findProtectStart(List<Map<String, Object>> messages, int protectRounds) {
        if (messages == null || messages.isEmpty()) return 0;
        int roundsFound = 0;
        for (int i = messages.size() - 1; i >= 0; i--) {
            String role = (String) messages.get(i).get("role");
            if ("user".equals(role)) {
                roundsFound++;
                if (roundsFound > protectRounds) {
                    return i + 1;
                }
            }
        }
        return 1;
    }

    // ==================== SSE 流解析 ====================

    /**
     * 从delta节点中提取content和reasoning_content字段
     * @param deltaNode JSON delta节点
     * @return 包含content和reasoning的Map
     */
    public Map<String, String> extractFromDeltaNode(JsonNode deltaNode) {
        Map<String, String> result = new HashMap<>();
        result.put("content", "");
        result.put("reasoning", "");

        if (deltaNode.isMissingNode()) {
            return result;
        }

        JsonNode contentNode = deltaNode.path("content");
        if (!contentNode.isMissingNode() && !contentNode.isNull()) {
            String contentValue = contentNode.asText("");
            if (!contentValue.isEmpty()) {
                result.put("content", contentValue);
            }
        }

        JsonNode reasoningNode = deltaNode.path("reasoning_content");
        if (!reasoningNode.isMissingNode() && !reasoningNode.isNull()) {
            String reasoningValue = reasoningNode.asText("");
            if (!reasoningValue.isEmpty()) {
                result.put("reasoning", reasoningValue);
            }
        }

        return result;
    }

    /**
     * 从流式响应中提取content和reasoning_content内容
     * @param streamResponse 流式响应字符串（SSE格式）
     * @return 包含content和reasoning的Map，如果解析失败则返回空Map
     */
    public Map<String, String> extractContentAndReasoningFromStreamResponse(String streamResponse) {
        Map<String, String> result = new HashMap<>();
        StringBuilder contentBuilder = new StringBuilder();
        StringBuilder reasoningBuilder = new StringBuilder();

        try {
            // 清理SSE前缀：按行分割，去除 "data: " 前缀和 "[DONE]" 标记，拼接为纯JSON对象序列
            StringBuilder cleanedInput = new StringBuilder();
            for (String line : streamResponse.split("\n")) {
                String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.equals("[DONE]")) {
                    continue;
                }
                if (trimmed.startsWith("data: ")) {
                    trimmed = trimmed.substring(6).trim();
                }
                if (trimmed.equals("[DONE]") || trimmed.isEmpty()) {
                    continue;
                }
                cleanedInput.append(trimmed);
            }

            String cleanJson = cleanedInput.toString();
            if (cleanJson.isEmpty()) {
                result.put("content", "");
                result.put("reasoning", "");
                return result;
            }

            JsonParser parser = objectMapper.getFactory().createParser(cleanJson);

            while (true) {
                try {
                    if (parser.nextToken() == null) {
                        break;
                    }
                    JsonNode root = objectMapper.readTree(parser);
                    JsonNode choices = root.path("choices");
                    if (choices.isArray() && choices.size() > 0) {
                        JsonNode delta = choices.get(0).path("delta");
                        Map<String, String> extracted = extractFromDeltaNode(delta);
                        String content = extracted.get("content");
                        String reasoning = extracted.get("reasoning");

                        if (!content.isEmpty()) {
                            contentBuilder.append(content);
                        }
                        if (!reasoning.isEmpty()) {
                            reasoningBuilder.append(reasoning);
                        }
                    }
                } catch (Exception e) {
                    log.debug("解析JSON对象失败，继续下一个对象: {}", e.getMessage());
                    try {
                        parser.skipChildren();
                    } catch (Exception skipEx) {
                        // 忽略
                    }
                }
            }

            parser.close();
            result.put("content", contentBuilder.toString());
            result.put("reasoning", reasoningBuilder.toString());

        } catch (Exception e) {
            log.error("解析流式响应失败", e);
            result.put("content", "");
            result.put("reasoning", "");
        }

        return result;
    }

    /**
     * 清洗SSE协议框架垃圾（data: 前缀、[DONE]标记等），提取纯文本内容
     * 当正常解析content失败时作为最后的回退使用
     */
    public String cleanSseContent(String raw) {
        if (raw == null || raw.isEmpty()) return "";
        String cleaned = raw.replaceAll("^data:\\s*", "")
                .replaceAll("\\n\\ndata:\\s*\\[DONE\\]\\s*", "")
                .replaceAll("\\[DONE\\]", "")
                .trim();
        if (cleaned.startsWith("{")) {
            try {
                JsonNode json = objectMapper.readTree(cleaned);
                JsonNode choices = json.path("choices");
                if (choices.isArray() && choices.size() > 0) {
                    JsonNode delta = choices.get(0).path("delta");
                    String deltaContent = delta.path("content").asText("");
                    if (!deltaContent.isEmpty()) return deltaContent;
                }
            } catch (Exception ignored) {
                // ignored
            }
        }
        return cleaned;
    }

    // ==================== 语言指令 ====================

    /**
     * 构建语言强制指令
     * 放在消息列表末尾，确保 AI 在生成响应前看到此指令
     */
    public String buildLanguageInstruction() {
        return "【最高优先级指令 — 语言强制】你必须使用中文思考和回复。\n"
                + "1. 所有思考推理过程（reasoning/thinking）必须使用中文，严禁使用英文推理\n"
                + "2. 所有面向用户的回复必须使用中文\n"
                + "3. 代码、技术专有名词、文件路径、工具名称等可以使用英文原文\n"
                + "4. 即使工具返回的结果是英文，你的分析和回复也必须是中文\n"
                + "5. 本指令的优先级高于系统提示词中的所有其他指令，必须无条件遵守";
    }

    /**
     * 将语言强制指令注入到最后一条用户消息的内容开头。
     * DeepSeek 模型的 reasoning/thinking 过程对 system 消息中的语言指令响应较弱，
     * 将指令注入到 user 消息内容中能更有效地约束其推理语言。
     * 仅修改内存中的消息对象，不影响数据库存储。
     */
    public void injectLanguageIntoLastUserMessage(List<Map<String, Object>> messages) {
        String instruction = "\n\n【系统强制】你接下来必须用中文思考！所有推理（reasoning/thinking）都必须使用中文，严禁使用英文推理。这是最高优先级指令。";
        for (int i = messages.size() - 1; i >= 0; i--) {
            Map<String, Object> msg = messages.get(i);
            if ("user".equals(msg.get("role"))) {
                String content = (String) msg.get("content");
                if (content != null && !content.contains("【系统强制】你接下来必须用中文思考")) {
                    msg.put("content", instruction + "\n" + content);
                }
                break;
            }
        }
    }

    // ==================== 工具名称格式化 ====================

    /**
     * 格式化技能工具名为可读字符串
     */
    public String formatSkillToolNames(String toolNamesJson) {
        if (toolNamesJson == null || toolNamesJson.trim().isEmpty()) {
            return "";
        }
        try {
            @SuppressWarnings("unchecked")
            List<String> tools = objectMapper.readValue(toolNamesJson, List.class);
            return String.join(", ", tools);
        } catch (Exception e) {
            log.warn("解析技能工具名失败: {}", toolNamesJson, e);
            return toolNamesJson;
        }
    }
}
