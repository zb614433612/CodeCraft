package com.example.agentdeepseek.service.impl;

import com.example.agentdeepseek.common.enums.ContextMode;
import com.example.agentdeepseek.config.DeepSeekConfig;
import com.example.agentdeepseek.mapper.ConversationMapper;
import com.example.agentdeepseek.mapper.ConversationMessageMapper;
import com.example.agentdeepseek.mapper.FileReferenceMapper;
import com.example.agentdeepseek.model.entity.Conversation;
import com.example.agentdeepseek.model.entity.ConversationMessage;
import com.example.agentdeepseek.model.entity.MessageRole;
import com.example.agentdeepseek.service.files.FileVisionService;
import com.example.agentdeepseek.service.llm.LLMClient;
import com.example.agentdeepseek.service.llm.LLMClientManager;
import com.example.agentdeepseek.service.llm.ToolCallSequenceSanitizer;
import com.example.agentdeepseek.util.ReasoningPassback;
import com.example.agentdeepseek.util.TokenEstimator;
import com.example.agentdeepseek.util.ToolResultBudget;
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

    /**
     * 工具循环裁剪摘要的识别前缀（幂等标记）。
     *
     * <p>凡是 content 以此开头的 tool 消息，说明已被 {@link #trimToolLoopToBudget} 摘要过，
     * 后续轮次必须<b>原样跳过</b>：重复改写会让历史每轮都变一次，前缀缓存全量失效
     * （实测 TTFB 从 1.4s 退化到 20s），同时让模型看到的历史始终不稳定 ——
     * 这是「均衡模式失忆」的放大器之一。</p>
     *
     * <p><b>⚠️ 为什么必须用这么长的独特前缀（2026-11-16 修复）</b>：
     * 此前这里只写 {@code "[工具 "}，而 {@link #compactHistoryMessages}（compact 模式）
     * 生成的摘要文案同样是 {@code "[工具 xxx 调用成功/失败...]"} 开头 —— 两者<b>撞了前缀</b>。
     * 后果：compact 模式下被精简过的 tool 消息，在后续 {@code trimToolLoopToBudget} 里
     * 会被误判成「已摘要过」而永久跳过，即使它还带着几百字符的可压缩正文。
     * 于是工具循环裁剪的收敛能力被削弱：明明还有可压缩空间，却判定「无处可压」提前退出，
     * 最终仍然把超限请求发出去（API 400）。</p>
     *
     * <p>因此改用带方括号嵌套的独特串，与 {@code compactHistoryMessages} 的
     * {@code "[工具 "}、以及 {@code ToolResultBudget.MARK}（{@code "…[CC-TRUNC]"}）
     * 三者互不包含，任何一方都不会误吞另一方。</p>
     */
    private static final String TRIM_SUMMARY_MARK = "[CC-TRIM][工具 ";

    /**
     * 工具循环裁剪时<b>完整保留</b>的最近工具调用轮数。
     *
     * <p>⚠️ 这个值直接决定模型「还记得最近几步」。旧实现硬编码保留 2 轮，
     * 导致每个工具结果只完整存活 2 轮就被摘要成空壳 —— 模型永远只看得到「刚刚这两步」，
     * 再往前全忘，表现为反复重新表态、重复同一批操作（用户反馈的「均衡模式失忆」）。</p>
     *
     * <p>为什么取 8：单条工具结果已被 {@code ToolResultBudget} 在写入前截到
     * 10K~12K 字符（约 2.5K~3K tokens），8 轮 ≈ 2 万~2.4 万 tokens，相对 30 万+ 的
     * 上下文预算只占 8%，成本可接受；而 8 步的连续视野足以让模型建立「我在做什么、
     * 做到哪一步」的稳定认知，不必频繁回头重做。</p>
     *
     * <p>若保留 8 轮后仍超限，{@link #trimToolLoopToBudget} 的逐级扩展会继续向更早的轮次推进，
     * 因此调大此值不会导致裁剪失效，只会让「开始损失记忆」的位置更晚出现。</p>
     */
    private static final int PROTECT_RECENT_TOOL_ROUNDS = 8;

    /**
     * 工具结果「展示包装」的固定头部（与 {@code ToolLoopManager#formatToolResult} 严格一致）。
     *
     * <p><b>为什么需要它（跨轮前缀缓存修复）</b>：工具结果在落库前会被
     * {@code formatToolResult} 包装成「虚线框 + 工具名」的展示文本，并存进
     * {@code reasoning} 列（TOOL 消息的 {@code content} 恒为 null）；而<b>当轮发给模型的
     * 是未经包装的原始 content</b>（{@code ToolExecutor#buildToolMessages}）。重建历史时若
     * 直接取 reasoning，同一条 TOOL 消息就会在「内存态（原始）」与「重建态（包装）」之间
     * 来回翻转 —— 从该条起后面所有内容前缀全部失效，跨轮第一枪整段重新 prefill。</p>
     *
     * <p><b>实测证据</b>（2026-11-24，安装版 v3.3.8，conversation 550）：跨轮首枪
     * 585017 字符的请求，逐消息指纹报 {@code index=190 role: tool → tool 长度 210 → 336}，
     * 差值 {@code 126} 正是
     * {@code "\n\n" + 48*"-" + "工具调用:" + 48*"-" + "\n" + "> **file_explorer**\n" + "\n\n"}
     * 的长度（113 + 工具名长度）。剥离包装后恢复原始 content，前缀即可跨轮稳定。</p>
     */
    private static final String TOOL_RESULT_WRAPPER_PREFIX =
            "\n\n" + "-".repeat(48) + "工具调用:" + "-".repeat(48) + "\n";

    private final ConversationMapper conversationMapper;
    private final ConversationMessageMapper conversationMessageMapper;
    private final CompactionService compactionService;
    private final DeepSeekConfig deepSeekConfig;
    /** 工具结果体积预算（与 ToolExecutor 共用同一份规则，保证截断幂等、前缀稳定） */
    private final ToolResultBudget toolResultBudget;
    /** 桌面截图消息重建：按消息查 file_reference，再补回与内存一致的 file 块 */
    private final FileReferenceMapper fileReferenceMapper;
    private final FileVisionService fileVisionService;
    /** 当前 Provider 能力解析：latest_reminder 只对 DeepSeek 适配器生成 */
    private final LLMClientManager llmClientManager;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public ContextBuilder(ConversationMapper conversationMapper,
                          ConversationMessageMapper conversationMessageMapper,
                          CompactionService compactionService,
                          DeepSeekConfig deepSeekConfig,
                          ToolResultBudget toolResultBudget,
                          FileReferenceMapper fileReferenceMapper,
                          FileVisionService fileVisionService,
                          LLMClientManager llmClientManager) {
        this.conversationMapper = conversationMapper;
        this.conversationMessageMapper = conversationMessageMapper;
        this.compactionService = compactionService;
        this.deepSeekConfig = deepSeekConfig;
        this.toolResultBudget = toolResultBudget;
        this.fileReferenceMapper = fileReferenceMapper;
        this.fileVisionService = fileVisionService;
        this.llmClientManager = llmClientManager;
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
     * 判断会话是否已有真实系统提示词（注入块不算）。
     *
     * <p>阶段 C 起，弯路预警/技能目录/执行模式/目录知识库也以 SYSTEM 角色落库，
     * 因此不能用「是否存在任意 SYSTEM 消息」判断首次会话，否则真实系统提示词永远不会保存。</p>
     */
    public boolean hasRealSystemMessage(Long conversationId) {
        if (conversationId == null) {
            return false;
        }
        return conversationMessageMapper.countRealSystemMessages(conversationId) > 0;
    }

    /**
     * 根据会话ID构建历史消息列表，用于API请求
     * 将数据库中的消息格式转换为DeepSeek API所需的消息格式
     * 应用已有压缩记录后重建消息；<b>压缩决策不在这里做</b>（2026-11-23 起改由
     * {@code DeepSeekServiceImpl#enforceContextGuard} 在全部注入完成、tools 构建后统一判断，
     * 避免用「不含注入与 tools」的口径做压缩决策）
     * @param conversationId 会话ID
     * @return 消息列表，每个元素包含role、content和可选的tool_calls
     */
    public List<Map<String, Object>> buildMessagesFromHistory(Long conversationId) {
        return buildMessagesFromHistory(conversationId, deepSeekConfig.resolveContextMode());
    }

    /**
     * 按指定上下文模式构建历史消息
     * <p>
     * 模式决定两件事：① 是否剔除历史 reasoning（full 回传，balanced/compact 不回传）；
     * ② 是否摘要化历史工具结果（仅 compact）。详见 {@link ContextMode}。
     * </p>
     *
     * @param mode 上下文模式；{@code null} 时回退到全局配置
     */
    public List<Map<String, Object>> buildMessagesFromHistory(Long conversationId, ContextMode mode) {
        return buildMessagesFromHistory(conversationId, mode, null);
    }

    /**
     * 按指定上下文模式与 Provider 构建历史消息（支持 Provider 级上下文上限）。
     *
     * <p><b>为什么需要 providerCode（2026-11-16）</b>：上下文上限不再由全局单值决定——
     * 不同平台窗口差异大（DeepSeek 官方 1M / 火山 Coding Plan 1049K / 部分中转站 128K），
     * 用同一个值管所有 Provider 会「偏小时提前裁剪、偏大时直接 400」。
     * 实际取值见 {@link DeepSeekConfig#resolveMaxContextTokens(String)}。</p>
     *
     * @param mode         上下文模式；{@code null} 时回退到全局配置
     * @param providerCode Provider 编码；{@code null} 时回退全局上限
     */
    public List<Map<String, Object>> buildMessagesFromHistory(Long conversationId, ContextMode mode, String providerCode) {
        ContextMode effectiveMode = mode != null ? mode : deepSeekConfig.resolveContextMode();

        // latest_reminder 虽是普通消息字段入库，但只有 DeepSeek 适配器能消费；
        // 历史构建阶段就必须按当前 Provider 能力决定是否合成，不能等发送前再补救。
        boolean includeLatestReminder = supportsLatestReminder(providerCode);

        // 桌面截图注入消息落库只存纯文本；重建时需按 file_reference 补回 file 块，
        // 否则内存态（text+file）与重建态（纯文本）不一致，跨轮前缀会从该条起断裂。
        Long conversationUserId = null;
        if (conversationId != null && conversationMapper != null) {
            try {
                conversationUserId = conversationMapper.selectById(conversationId)
                        .map(Conversation::getUserId).orElse(null);
            } catch (Exception e) {
                log.warn("查询会话用户ID失败（桌面截图消息将回退纯文本）: conversationId={}, err={}",
                        conversationId, e.getMessage());
            }
        }

        /*
          ★ 2026-11-23 增量重建：只读「SYSTEM + 水位线之后」的消息

          旧实现每次请求都全量加载整个会话（实测长会话 31426 条 / 4412 万字符），
          再在内存里用 applyCompactionRecords 丢掉已被摘要覆盖的部分 —— 压缩只减小了
          发给模型的内容，并没有减少 DB 读取量，长会话每个请求都白读几十 MB。

          现在：先读压缩快照（一条 select），有水位线就按 id 增量查询（索引命中
          idx_conversation_id + 主键），再把摘要插回原位置；没有压缩记录才走全量查询。
          两条路径产出的消息序列完全一致：system + 摘要 + 水位线之后的对话。
         */
        // 1. 压缩快照（水位线 + 累进摘要）
        CompactionService.CompactState compactionState = compactionService.loadSnapshot(conversationId);

        // 2. 加载消息：有水位线走增量查询，否则全量（首次会话 / 从未压缩过）
        List<ConversationMessage> dbMessages;
        if (compactionState.watermark() > 0) {
            dbMessages = conversationMessageMapper.selectForContext(conversationId, compactionState.watermark());
            // 2.1 摘要插回「第一条非 SYSTEM 消息之前」（system 归位后会变成 system + 摘要 + 后续对话）
            compactionService.insertSummaryBefore(conversationId, dbMessages,
                    compactionState.summary(), compactionState.watermark());
        } else {
            dbMessages = conversationMessageMapper.selectByConversationId(conversationId);
        }

        // 3. 将消息转换为 API 格式
        List<Map<String, Object>> result = new ArrayList<>();
        // 仅用于按顺序给 role=tool 消息分配 tool_call_id（配对合法性最终由末尾的净化器统一保证）
        Queue<String> pendingToolCallIdQueue = new LinkedList<>();

        for (ConversationMessage msg : dbMessages) {
            Map<String, Object> messageMap = new HashMap<>();
            MessageRole role = msg.getRole();

            if (role == MessageRole.USER || role == MessageRole.SYSTEM) {
                /*
                 * DeepSeek latest_reminder：落库只存在 user 消息的 latest_reminder 字段里，
                 * 重建时在它所属的 user 消息之前合成一条独立 role 消息。
                 * 这样 DB 行序不受影响，历史每轮的 reminder 也保持稳定（利于前缀缓存）。
                 * 非 DeepSeek Provider 会在适配器出口统一过滤。
                 */
                if (role == MessageRole.USER
                        && includeLatestReminder
                        && msg.getLatestReminder() != null
                        && !msg.getLatestReminder().isBlank()) {
                    Map<String, Object> reminderMessage = new HashMap<>();
                    reminderMessage.put("role", LLMClient.LATEST_REMINDER_ROLE);
                    reminderMessage.put("content", msg.getLatestReminder());
                    result.add(reminderMessage);
                }
                messageMap.put("role", role.getValue());
                Object content = role == MessageRole.USER
                        ? buildHistoryUserContent(msg, conversationUserId, providerCode)
                        : (msg.getContent() != null ? msg.getContent() : "");
                messageMap.put("content", content);
                if (msg.getMessageType() != null) {
                    messageMap.put("_messageType", msg.getMessageType());
                }
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
                            for (JsonNode toolCall : toolCallsNode) {
                                JsonNode idNode = toolCall.path("id");
                                if (!idNode.isMissingNode() && !idNode.isNull()) {
                                    String id = idNode.asText();
                                    if (id != null && !id.isEmpty() && !"null".equals(id)) {
                                        pendingToolCallIdQueue.add(id);
                                    }
                                }
                            }
                        }
                    } catch (Exception e) {
                        log.warn("解析tool_calls JSON失败: {}", e.getMessage());
                    }
                }

                String content = msg.getContent();
                if (content != null && !content.isEmpty()) {
                    messageMap.put("content", content);
                }

                // 【DeepSeek thinking + tools 强校验】assistant 消息必须「始终携带」reasoning_content 字段：
                //   只要有一条 assistant 缺少该字段，DeepSeek 就返回 400
                //   （The reasoning_content in the thinking mode must be passed back to the API）。
                //   因此成本优化的着力点不是「删掉字段」，而是「把值置空」——
                //   full      → 回传真实思考内容
                //   balanced  → 空串占位（≈3~5 token，约为真实思考链的千分之一，且能通过校验）
                //   compact   → 空串占位（同上，额外摘要化工具结果）
                //   ⚠️ 数据库中 reasoning 仍完整保留（前端展示与溯源不受影响），此处只影响下发给模型的部分。
                //   ⚠️ 注意把开关做成"恒定"判断：中途切换会让前缀失效一次，属预期代价。
                ReasoningPassback.putHistory(messageMap, msg.getReasoning(), effectiveMode);

                result.add(messageMap);
            } else if (role == MessageRole.TOOL) {
                if (!pendingToolCallIdQueue.isEmpty()) {
                    String toolCallId = pendingToolCallIdQueue.poll();
                    messageMap.put("role", "tool");
                    messageMap.put("tool_call_id", toolCallId);

                    String content = msg.getReasoning() != null ? msg.getReasoning() :
                            (msg.getContent() != null ? msg.getContent() : "");
                    // ★ 前缀缓存关键：还原「当轮发给模型的原始 content」。
                    //   reasoning 列存的是 formatToolResult 包装后的展示文本（content 列恒为 null），
                    //   当轮发给模型的却是未包装的原始内容。不剥离 → 同一条 TOOL 消息两种形态，
                    //   跨轮前缀从第一条 TOOL 起全部失效（实测 index=190 / 长度差 126 即此因）。
                    content = stripToolResultWrapper(content);
                    messageMap.put("content", content);

                    result.add(messageMap);
                } else {
                    log.warn("TOOL消息没有对应的tool call ID，跳过该消息: {}", msg.getId());
                }
            }
        }

        // ===== 工具调用序列净化（关键兜底，取代历史上"只删 assistant.tool_calls"的局部修复）=====
        // 旧实现只移除「缺少 TOOL 消息的 assistant.tool_calls」，但已经落库的 tool 消息会因此变成
        // "无前置 tool_calls 的孤立 tool 消息"，接口依旧 400 —— 这正是上下文孤立问题反复出现的原因。
        // 现统一交给 ToolCallSequenceSanitizer：孤立 tool 消息直接丢弃、无结果的 tool_call 逐个剔除、
        // 空 assistant 消息整条丢弃，保证序列一定合法（宁可丢信息，也绝不让会话卡死）。
        ToolCallSequenceSanitizer.sanitize(result);

        // ===== 3.5 上下文模式：仅精简模式额外摘要化历史工具结果 =====
        // ⚠️ 这一步会改写历史 tool 消息的 content，导致前缀缓存失效一次（O(历史长度) 全额重算），
        //    因此只在 compact（救急模式）执行；balanced 只剔 reasoning（构建阶段就不加入），
        //    工具结果原样保留、前缀保持稳定，这是"省钱但不丢准确性"的最优切分点。
        if (effectiveMode.shouldCompactToolResults()) {
            int beforeCompact = TokenEstimator.estimateMessages(result, providerCode);
            compactHistoryMessages(result);
            int afterCompact = TokenEstimator.estimateMessages(result, providerCode);
            log.info("compact 模式：历史工具结果摘要化完成, 消息数={}, Token: {} → {} (节省 {}%)",
                    result.size(), beforeCompact, afterCompact,
                    beforeCompact > 0 ? (beforeCompact - afterCompact) * 100 / beforeCompact : 0);
            ToolCallSequenceSanitizer.sanitize(result);
        }

        // 4. 上下文预算（2026-11-23 重构：构建阶段不再自行压缩）
        //
        //    【为什么把压缩从这里移走】
        //    本方法执行时，技能目录 / 附件提示 / 知识库摘要 / 语言指令 / 图像块都还没注入，
        //    也看不到本次请求的 tools 定义（约 6 万 token 的固定地板）。用它做压缩决策会
        //    系统性低估真实请求体，造成「压缩器认为没超、真正请求却超限」的口径错位，
        //    进而出现「刚压完又被守卫判定超限、反复压缩改前缀」。
        //
        //    【现在由谁负责】
        //    DeepSeekServiceImpl#enforceContextGuard：在 prepareConversationContext 末尾、
        //    全部注入完成之后、apiRequest 构建之前执行，统计口径 =
        //    system + 最新摘要 + 水位线之后的对话 + 全部注入 + tools（与真正发出的请求体同源）。
        //    工具循环每一轮迭代另有同口径守卫（handleToolCallIteration）。
        //
        //    【这里保留什么】
        //    只保留 compaction 被显式关闭时的滑动窗口兜底（此时没有入库摘要可用）。
        int maxTokens = deepSeekConfig.resolveMaxContextTokens(providerCode);
        boolean compactionEnabled = deepSeekConfig.getCompaction().isEnabled();

        /*
          ⚠️ 只在「压缩被显式关闭」时才做全量分词估算（2026-11-23）。

          开启压缩时，请求前守卫会按「含 tools + 全部注入」的口径统一算一次；
          这里再算一遍纯属重复开销 —— 几十万 token 的 CL100K 分词是百毫秒级，
          每个请求都算两次没有意义。关闭压缩时没有入库摘要可用，只能在这里判断并裁剪。
         */
        if (!compactionEnabled) {
            int estimatedTokens = TokenEstimator.estimateMessages(result, providerCode);
            if (estimatedTokens > maxTokens * deepSeekConfig.getCompaction().getDropThreshold()) {
                log.warn("压缩功能已关闭，退回滑动窗口丢弃：估算 {} tokens, 上限 {}",
                        estimatedTokens, maxTokens);
                trimToTokenBudget(result, maxTokens, providerCode);
            }
        }

        // 压缩/裁剪都可能切断 assistant(tool_calls) 与 tool 消息的配对关系，返回前再净化一次（幂等）
        ToolCallSequenceSanitizer.sanitize(result);

        // ===== 5. reasoning_content 兜底补齐（400 不复发的最后一道防线）=====
        // DeepSeek thinking + tools 场景下，任何一条 assistant 缺少 reasoning_content 都会 400。
        // 上面的各条路径（模式裁剪 / 工具结果摘要化 / 智能压缩 / 滑动窗口丢弃）都可能改写消息列表，
        // 未来新增路径也可能忘记写该字段。缺字段必炸、补空串无害，因此统一扫一遍兜底。
        int padded = 0;
        for (Map<String, Object> message : result) {
            if (ReasoningPassback.needsPadding(message)) {
                ReasoningPassback.putHistory(message, null, effectiveMode);
                padded++;
            }
        }
        if (padded > 0) {
            log.debug("reasoning_content 兜底补齐 {} 条 assistant 消息（空串占位）", padded);
        }

        // ★ 6. system 消息归位（2026-11-17 修复「跨轮前缀结构不一致」）
        //   首次会话的落库顺序是「先存 user 消息、再存 system 消息」——
        //   因为 prepareConversationContext 是先 buildMessagesFromHistory（此时 user 已在库），
        //   再发现 hasSystemMessage=false 才补存 system。于是 DB 中 system 的 id 比首条 user 更大，
        //   按 id 升序重建时 system 落到第 2 位；而首轮内存里用的是 add(0) 把 system 放在第 1 位。
        //   两种结构开头就不同 → 跨轮前缀缓存无法命中。
        //   这里统一把全部 system 提到最前（保持相对顺序），使重建结果与首轮内存结构完全一致。
        hoistSystemMessages(result);

        return result;
    }

    /**
     * 把全部 system 消息提到列表最前面（保持它们彼此的相对顺序，其余消息相对顺序不变）。
     *
     * <p><b>为什么需要</b>：DB 中 system 的插入位置不可靠 —— 首次会话先把用户消息落库，
     * 之后才补存 system，导致 {@code id(system) > id(第一条 user)}。按 id 升序重建时
     * system 会落到第二位，与「首轮内存 add(0)」的结构不一致，前缀缓存跨轮必然失效。</p>
     *
     * <p><b>为什么是确定性操作</b>：同样的消息集合永远得到同样的排列，
     * 因此不会引入「每轮结构漂移」（这正是前缀缓存最怕的）。</p>
     *
     * @param messages 消息列表（原地修改）
     */
    private void hoistSystemMessages(List<Map<String, Object>> messages) {
        if (messages == null || messages.size() < 2) {
            return;
        }
        // 只把「真实系统提示词」提到最前：message_type 为空或 base_system，
        // 且内容不含旧的 [动态运行时注入] 标记。
        // 弯路预警/技能目录/执行模式/目录知识库等注入块保留在原有位置，
        // 由数据库 id 顺序决定，固定在本轮用户消息之前。
        int realSystemIndex = -1;
        for (int i = 0; i < messages.size(); i++) {
            Map<String, Object> msg = messages.get(i);
            if (!"system".equals(msg.get("role"))) {
                continue;
            }
            Object type = msg.get("_messageType");
            Object content = msg.get("content");
            boolean legacyDynamicBlock = content instanceof String s && s.contains("[动态运行时注入]");
            boolean realSystem = !legacyDynamicBlock
                    && (type == null || "base_system".equals(type));
            if (realSystem) {
                realSystemIndex = i;
                break;
            }
        }
        if (realSystemIndex <= 0) {
            return;
        }
        Map<String, Object> realSystem = messages.remove(realSystemIndex);
        messages.add(0, realSystem);
    }

    /**
     * 剥离工具结果的「展示包装」，还原当轮真正发给模型的原始 content。
     *
     * <p><b>包装的由来</b>：{@code ToolLoopManager#formatToolResult} 为了前端展示，在工具结果
     * 前后加了虚线框、「工具调用:」标题与「&gt; **工具名**」行，并把这整段存进 TOOL 消息的
     * {@code reasoning} 列；而 {@code ToolExecutor#buildToolMessages} 发给模型的
     * {@code content} 是<b>未包装</b>的原始内容。两者字节不同 —— 跨轮重建时若直接下发
     * reasoning，同一条消息就会出现两种形态，前缀从该条起全部失效。</p>
     *
     * <p><b>可逆性</b>：包装是「固定头 + 原始内容 + 固定两个换行」的纯拼接，因此按同样规则
     * 剥回去可以逐字节还原（空结果场景剥完为 {@code ""}）。不以包装头开头的消息
     * （旧数据 / 非本路径写入）原样返回，绝不误伤。</p>
     *
     * @param raw TOOL 消息 reasoning 列的内容
     * @return 当轮发给模型的原始工具结果
     */
    private String stripToolResultWrapper(String raw) {
        if (raw == null || raw.isEmpty()) {
            return raw == null ? "" : raw;
        }
        if (!raw.startsWith(TOOL_RESULT_WRAPPER_PREFIX)) {
            return raw;
        }
        int offset = TOOL_RESULT_WRAPPER_PREFIX.length();
        // 紧随固定头的是 "> **工具名**\n" 一整行，跳过它
        if (raw.startsWith("> **", offset)) {
            int lineEnd = raw.indexOf('\n', offset);
            if (lineEnd > 0) {
                offset = lineEnd + 1;
            }
        }
        String body = raw.substring(offset);
        // formatToolResult 无条件在末尾追加两个换行，这里等量剥回，保证与原始 content 逐字节一致
        if (body.endsWith("\n\n")) {
            body = body.substring(0, body.length() - 2);
        } else if (body.endsWith("\n")) {
            body = body.substring(0, body.length() - 1);
        }
        return body;
    }

    /**
     * 按字符串形式构建历史消息（对外兼容层）
     * <p>
     * 内部统一收敛到 {@link ContextMode}：字符串 → 枚举的容错解析放在这里，
     * 避免调用方各自写 {@code "compact".equals(...)} 导致三档语义漂移。
     * </p>
     *
     * @param contextMode 上下文模式字符串（full / balanced / compact，含历史别名）
     */
    public List<Map<String, Object>> buildMessagesFromHistory(Long conversationId, String contextMode) {
        return buildMessagesFromHistory(conversationId,
                ContextMode.from(contextMode, deepSeekConfig.resolveContextMode()));
    }

    /**
     * 当前 Provider 是否为支持 latest_reminder 的适配器（目前只有 DeepSeekClient）。
     * 解析失败按不支持处理——宁可少一条元信息，也不能让其他 Provider 收到非法 role。
     */
    private boolean supportsLatestReminder(String providerCode) {
        if (llmClientManager == null) {
            return false;
        }
        try {
            LLMClient client = llmClientManager.resolveClientByCode(providerCode);
            return client != null && client.supportsLatestReminder();
        } catch (Exception e) {
            log.warn("解析 Provider latest_reminder 能力失败，按不支持处理: providerCode={}, err={}",
                    providerCode, e.getMessage());
            return false;
        }
    }

    /**
     * 重建 user 消息 content。
     *
     * <p>桌面截图注入消息落库时只保存纯文本通知，内存发送时却是「text + file」多模态数组。
     * 不补回 file 块会让同一消息在内存态与重建态之间长度不同，跨轮前缀从该条起断裂
     * （实测 index=390，759→671，差 -88）。这里按 file_reference 重新构建同样的块。</p>
     */
    private Object buildHistoryUserContent(ConversationMessage msg, Long userId, String providerCode) {
        String text = msg.getContent() != null ? msg.getContent() : "";
        if (!MessagePersister.TYPE_DESKTOP_INJECT.equals(msg.getMessageType())
                || text.isEmpty() || msg.getId() == null || userId == null
                || fileReferenceMapper == null || fileVisionService == null) {
            return text;
        }
        try {
            List<Long> assetIds = fileReferenceMapper.selectAssetIdsByMessageId(msg.getId());
            if (assetIds == null || assetIds.isEmpty()) {
                return text;
            }
            FileVisionService.FileBlocksResult blocksResult =
                    fileVisionService.buildFileBlocks(assetIds, userId, providerCode);
            if (blocksResult == null || blocksResult.blocks().isEmpty()) {
                return text;
            }
            List<Object> parts = new ArrayList<>(1 + blocksResult.blocks().size());
            Map<String, Object> textBlock = new HashMap<>();
            textBlock.put("type", "text");
            textBlock.put("text", text);
            parts.add(textBlock);
            parts.addAll(blocksResult.blocks());
            return parts;
        } catch (Exception e) {
            log.warn("重建桌面截图多模态消息失败（回退纯文本）: messageId={}, err={}",
                    msg.getId(), e.getMessage());
            return text;
        }
    }

    /**
     * 精简非本轮对话的消息（compact 模式核心逻辑）
     * <p>
     * 规则：
     * - 找到最后一条 user 消息作为"本轮对话"的起点
     * - 此前的 assistant 消息把 reasoning_content <b>置为空串</b>（不删字段，字段缺失会 400）
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
        //    注意：SYSTEM 注入块（技能目录/执行模式/知识库/弯路预警）不参与精简：
        //    compact 模式只精简历史 reasoning 与 tool 结果；旧注入块随压缩水位线被覆盖，
        //    本轮注入块由 buildMessagesFromHistory 保留在本轮 user 之前。
        int compactedToolCount = 0;
        int removedReasoningCount = 0;
        for (int i = 0; i < lastUserIdx; i++) {
            Map<String, Object> msg = messages.get(i);
            String role = (String) msg.get("role");

            if ("assistant".equals(role)) {
                // 剥离真实思考内容（历史思考参考价值低、token 消耗大）
                // ⚠️ 只把值改为空串，绝不 remove 字段：字段缺失会让 DeepSeek 直接返回 400
                if (ReasoningPassback.stripToPlaceholder(msg)) {
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
     *
     * <p><b>2026-11-16 修复「移除了 0 条消息」</b>：原实现只按保护带计算可裁剪区，
     * 而从 {@code idx = 1} 起裁的循环在「user 轮次不足 protectRounds」时（工具循环里
     * 常见只有 1 条 user 消息）会因 {@code protectStart = 1} 导致循环条件
     * {@code idx < lastIdx} 恒不成立——即使估算已远超丢弃阈值，也一条都删不掉。
     * 日志证据：{@code 上下文丢弃完成：移除了 0 条消息，剩余 273 条，估算 429388 tokens}。</p>
     *
     * <p>修复方式：保护带裁完后若仍超限，按阈值降级——先缩保护带至最近 1 轮，
     * 仍超限则放开保护带（只保留 system prompt 与最后一条 user 提问）。
     * 这是「宁可有损、也不能把超限请求发给 API」的兜底取舍。</p>
     *
     * @param messages 消息列表（会直接修改）
     * @param maxTokens token 上限
     */
    public void trimToTokenBudget(List<Map<String, Object>> messages, int maxTokens) {
        trimToTokenBudget(messages, maxTokens, null);
    }

    /**
     * 滑动窗口丢弃（带 provider 校准口径）。
     *
     * @param messages     消息列表（会直接修改）
     * @param maxTokens    token 上限（真实 token 口径）
     * @param providerCode Provider 编码，用于把估算换算到与 maxTokens 同一口径
     */
    public void trimToTokenBudget(List<Map<String, Object>> messages, int maxTokens, String providerCode) {
        if (messages == null || messages.isEmpty()) return;

        int estimatedTokens = TokenEstimator.estimateMessages(messages, providerCode);
        if (estimatedTokens <= maxTokens) return;

        int beforeSize = messages.size();
        log.warn("上下文滑动窗口丢弃：估算 {} tokens (上限 {}), 开始裁剪（共 {} 条消息）",
                estimatedTokens, maxTokens, beforeSize);

        int protectRounds = deepSeekConfig.getCompaction().getProtectRounds();

        /*
         * 降级序列：先按配置的保护带裁（正常路径）；裁完仍超限则逐级缩小保护带。
         * 每一级都复用同一套裁剪循环，只是 protectRounds 递减。
         */
        int[] protectRoundsSteps = {protectRounds, 1, 0};
        for (int step = 0; step < protectRoundsSteps.length; step++) {
            int rounds = protectRoundsSteps[step];
            if (step > 0) {
                log.warn("保护带（{} 轮）内已无更多可裁内容，降级保护带至 {} 轮后重试裁剪", protectRoundsSteps[step - 1], rounds);
            }
            estimatedTokens = trimWithinProtectBand(messages, maxTokens, rounds, providerCode);
            if (estimatedTokens <= maxTokens) break;
        }

        int trimmed = beforeSize - messages.size();
        log.info("上下文丢弃完成：移除了 {} 条消息，剩余 {} 条，估算 {} tokens",
                trimmed, messages.size(), estimatedTokens);
    }

    /**
     * 在指定保护带轮次下执行一轮滑动窗口裁剪，返回裁剪后的估算 token 数。
     *
     * <p><b>⚠️ 不可突破的底线</b>：无论保护带降到多低，<b>最后一条 user 消息及其之后的
     * 全部消息（当前轮次）永远不裁</b>。理由：裁掉用户的提问，模型就没有可回答的对象，
     * API 会直接 400 或答非所问——这比「上下文超限」严重得多。
     * （自检用例 {@code ContextBuilderTrimTest} 覆盖了该约束，修复前实测会把 62 条裁到 2 条
     * 且删掉 user 提问。）</p>
     *
     * @param messages     消息列表（会直接修改）
     * @param maxTokens    token 上限
     * @param protectRounds 保护带轮数（最近的 N 轮 user 对话不裁）
     * @param providerCode Provider 编码（用于把估算换算到与 maxTokens 同一口径）
     * @return 裁剪后的估算 token 数
     */
    private int trimWithinProtectBand(List<Map<String, Object>> messages, int maxTokens,
                                      int protectRounds, String providerCode) {
        int idx = 1;
        int lastIdx = resolveTrimUpperBound(messages, protectRounds);

        /*
         * 可裁区为空（单 user 工具循环的常态）：idx 落在当前轮次起点上，
         * 此时「没有更早的轮次可丢」——正确策略是交给
         * trimToolLoopToBudget 做「旧 tool 消息摘要化 + 超大消息截断」，
         * 而不是在这里硬删当前提问（那会让模型失去回答对象，比超限严重得多）。
         */
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

            /* 删除后保护带与当前轮次起点都会左移，必须重算上界 */
            lastIdx = resolveTrimUpperBound(messages, protectRounds);

            int estimatedTokens = TokenEstimator.estimateMessages(messages, providerCode);
            if (estimatedTokens <= maxTokens) return estimatedTokens;
        }

        return TokenEstimator.estimateMessages(messages, providerCode);
    }

    /**
     * 计算滑动窗口可裁区的上界（下标区间 {@code [1, 返回值)} 允许删除）。
     *
     * <p>上界必须同时满足两个约束，取<b>较严</b>的那个（即 {@code min}）：</p>
     * <ol>
     *   <li><b>保护带约束</b>：最近的 {@code protectRounds} 轮不裁 → 上界不超过
     *       {@link #findProtectStart} 的返回值；</li>
     *   <li><b>当前轮次底线</b>：最后一条 user 提问及其之后的所有消息永不可裁 →
     *       上界不超过 {@link #findLastUserIndex} 的返回值。</li>
     * </ol>
     *
     * <p><b>为什么第 2 条不能省</b>：工具循环里通常只有 1 条 user 消息（index 1），
     * 而裁剪固定从 {@code idx = 1} 起跳——若只用保护带约束，{@code protectRounds}
     * 降到 0 时上界会落到 2，于是 {@code [1,2)} 正好把用户提问删掉。
     * 自检用例曾实测出「62 条裁到 2 条且 user 提问消失」，正是缺了这条底线。</p>
     *
     * @param messages      消息列表
     * @param protectRounds 保护带轮数
     * @return 可裁区上界（{@code idx} 大于等于它时不裁任何消息）
     */
    private int resolveTrimUpperBound(List<Map<String, Object>> messages, int protectRounds) {
        int protectStart = findProtectStart(messages, protectRounds);
        int currentRoundStart = findLastUserIndex(messages);
        return Math.min(protectStart, currentRoundStart);
    }

    /**
     * 查找最后一条 user 消息的下标（当前轮次起点）。
     *
     * @param messages 消息列表
     * @return 最后一条 user 的下标；不存在时返回 {@code messages.size()}（表示「全部受保护」）
     */
    private int findLastUserIndex(List<Map<String, Object>> messages) {
        if (messages == null || messages.isEmpty()) return 0;
        for (int i = messages.size() - 1; i >= 0; i--) {
            if ("user".equals(messages.get(i).get("role"))) {
                return i;
            }
        }
        return messages.size();
    }

    /**
     * 工具循环专用上下文裁剪（兜底策略，防工具循环内消息无限膨胀导致 API 400 超限）
     * <p>
     * 与 {@link #trimToTokenBudget} 的区别：
     * - trimToTokenBudget 基于 user 轮次保护带，工具循环内通常只有 1 条 user 消息，
     *   findProtectStart 会返回 1 导致裁剪循环不执行，保护带逻辑失效；
     * - 本方法专门处理工具循环场景：
     *   1. 将最早的历史 tool 消息 content 替换为"[工具 xxx 调用成功/失败]"摘要
     *      （tool_call_id 必须保留，API 格式要求，只替换 content）；
     *   2. 摘要化后仍超限时，对剩余超大 tool 消息做首尾截断。
     * </p>
     * <p>
     * ⚠️ 第 2 步的截断必须复用 {@link ToolResultBudget}（与
     * {@code ToolExecutor#buildToolMessages} 同一份规则）：
     * 已在写入前裁剪过的内容会因幂等标记被原样返回，
     * 只有「存量超预算数据」才会真正被改一次，改完即稳定——这是前缀缓存不失效的前提。
     * </p>
     *
     * @param messages 消息列表（会直接修改）
     * @param maxTokens token 上限
     * @deprecated 2026-11-17 起<b>不再使用</b>，请改用 {@code CompactionService#compact}（压缩并入库）。
     *
     *             <p><b>原因</b>：本方法只改写内存 messages，无法与 DB 保持一致 ——
     *             下一轮用户发言时 {@link #buildMessagesFromHistory} 从 DB 重建，
     *             tool 消息又变回完整原文，而上一轮内存里是「[CC-TRIM][工具…]」摘要。
     *             同一条消息两种形态 → 本次请求不再是上一次的真前缀
     *             → 从差异点起全部按未命中重新 prefill（实测 125K 上下文 ≈ 30 秒）。
     *             实测佐证：{@code llm_token_usage} 中 {@code iteration=0} 的
     *             {@code cache_hit_tokens} 从 45952(98.8%) 掉到全部 0，
     *             而同一轮内 {@code iteration>=1} 始终 95%+（因为同轮复用同一内存列表）。</p>
     *
     *             <p>压缩（compaction）与裁剪的本质区别：前者把摘要<b>写入
     *             {@code conversation_compaction}</b>，之后每次重建都得到
     *             「system + 摘要 + 其后消息」这一稳定前缀；后者只改内存，必然破坏前缀。</p>
     */
    @Deprecated
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

        /*
          ===== 2. 确定摘要区 =====

          ★ 2026-11-16 修复「均衡模式失忆」的真正根因（用户直接指出）：

          旧实现是 `assistantWithToolCallsSeen >= 2` —— 只把**最后 2 轮**完整工具调用留在可摘要区之外。
          而工具结果是按轮次滑动的：第 N 轮时 t(N-1)/tN 完整，到第 N+1 轮时 t(N-1) 就退化成
          「倒数第三条」，立刻被摘要掉。于是**每个工具结果只完整存活 2 轮**，模型永远只看得见
          「刚刚这两步」，再往前全是空壳。

          这不是「从旧到新逐步压缩」的直觉语义，而是「每走一步就丢一步记忆」：
          模型看不到「我已经读过这个文件、已经定位到根因、已经改完第 1 处」，
          于是每轮都重新表态「我不再验证了，直接动手」，然后重复同一批操作 —— 即用户说的「失忆」。

          修复：把保护窗口从 2 轮提升到 PROTECT_RECENT_TOOL_ROUNDS 轮（默认 8），
          让近期的连续执行链完整可见；更早的部分才收敛成「保留首部的摘要」。
          若保留 8 轮后仍超限，下面的逐级扩展会继续向前压，保证一定收敛。
         */
        int summaryEnd = 0; // [0, summaryEnd) 区间内的 tool 消息摘要化
        int assistantWithToolCallsSeen = 0;
        for (int i = messages.size() - 1; i >= 0; i--) {
            Map<String, Object> msg = messages.get(i);
            if ("assistant".equals(msg.get("role")) && msg.containsKey("tool_calls")) {
                assistantWithToolCallsSeen++;
                if (assistantWithToolCallsSeen > PROTECT_RECENT_TOOL_ROUNDS) {
                    summaryEnd = i;
                    break;
                }
            }
        }

        /*
          ===== 3. 摘要化 [0, summaryEnd) 内的 tool 消息（保留 tool_call_id，替换 content）=====

          ★ 2026-11-16 修复「均衡模式失忆」：两处缺陷叠加导致模型完全看不到自己做过什么

          缺陷一（信息归零）：旧实现把成功结果整体替换成
              "[工具 xxx 调用成功 - 上下文超限已裁剪，完整结果请用 query_tool_history 查询]"
          这是一句**不含任何内容**的空壳。工具循环里模型靠「工具结果」判断进度，
          结果全被抹成同样的空串后，它就不知道「读过哪个文件、发现了什么、改到哪一步」，
          表现为反复输出同一句开场白、把已经做对的事重新做一遍 —— 即用户说的「失忆」。
          修复：摘要必须保留结果首部（成功 400 / 失败 300 字符），让模型能认出「这是哪一步」。

          缺陷二（覆盖不足 + 每轮重复改写）：
          旧实现只处理 [0, summaryEnd)（保留最近 1 轮），而工具循环的 token 大头往往还在
          被保留的尾部；于是每一轮都判定「仍超限 → 再裁一次」，历史被反复改写 →
          前缀缓存持续失效（实测 TTFB 从 1.4s 退化到 20s）→ 行为更不稳定。
          修复：① 逐级扩大可摘要范围直到降到上限或无处可扩；② 已摘要的消息带标记幂等跳过，
          保证「第一次裁完就稳定」，后续轮次不再动历史。
         */
        int compacted = 0;
        int cursor = summaryEnd;
        while (true) {
            for (int i = 0; i < cursor; i++) {
                Map<String, Object> msg = messages.get(i);
                if (!"tool".equals(msg.get("role"))) continue;
                String content = (String) msg.get("content");
                if (content == null || content.isEmpty()) continue;
                // 幂等：已摘要过的消息不再改写（否则每轮都会重写历史 → 缓存全失效）
                if (content.startsWith(TRIM_SUMMARY_MARK)) continue;

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
                    // 失败信息对「避免重复踩坑」价值极高，保留更多首部
                    String shortContent = content.length() > 300 ? content.substring(0, 300) + "…" : content;
                    // 注意：TRIM_SUMMARY_MARK 已包含「[工具 」前缀，此处不要再拼「工具」
                    msg.put("content", TRIM_SUMMARY_MARK + toolName + " 已执行（失败）] " + shortContent);
                } else {
                    // 成功结果保留首部摘要：模型据此能认出「做过这一步」，不再重复劳动
                    String preview = content.length() > 400 ? content.substring(0, 400) + "…" : content;
                    msg.put("content", TRIM_SUMMARY_MARK + toolName
                            + " 已执行（结果已压缩，完整内容可用 query_tool_history 查询）] " + preview);
                }
                compacted++;
            }

            estimated = TokenEstimator.estimateMessages(messages);
            if (estimated <= maxTokens) break;

            // 覆盖仍不足 → 把下一条 assistant(tool_calls) 纳入可摘要区，逐步向近处扩展
            int nextCursor = findNextSummaryCursor(messages, cursor);
            if (nextCursor <= cursor) break;
            cursor = nextCursor;
        }

        // 4. 仍超限：对剩余超大 tool 消息按从新到旧顺序做首尾截断（统一走 ToolResultBudget，保证幂等）
        estimated = TokenEstimator.estimateMessages(messages);
        int truncated = 0;
        if (estimated > maxTokens) {
            for (int i = messages.size() - 1; i >= 0 && estimated > maxTokens; i--) {
                Map<String, Object> msg = messages.get(i);
                if (!"tool".equals(msg.get("role"))) continue;
                String content = (String) msg.get("content");
                if (content == null || content.isEmpty()) continue;
                String toolCallId = (String) msg.get("tool_call_id");
                String toolName = toolCallId != null ? tcIdToName.getOrDefault(toolCallId, "unknown") : "unknown";
                String shrunk = toolResultBudget.apply(toolName, content);
                // 未变化 = 未超预算或已带截断标记（幂等跳过），不重复计数
                if (shrunk == null || shrunk.equals(content)) continue;
                msg.put("content", shrunk);
                truncated++;
                estimated = TokenEstimator.estimateMessages(messages);
            }
        }

        log.info("工具循环裁剪完成：摘要化 {} 条, 截断 {} 条, 剩余估算 {} tokens", compacted, truncated, estimated);
    }

    /**
     * 计算「下一档可摘要区上界」，用于 {@link #trimToolLoopToBudget} 逐级扩大摘要范围。
     *
     * <p>背景：旧实现只摘要化 {@code [0, summaryEnd)}（保留最近 1 轮完整工具调用），
     * 但工具循环里 token 大头常常还压在尾部；一次摘要降不到上限，于是<b>每一轮</b>
     * 都判定「仍超限 → 再裁一次」，历史被反复改写、前缀缓存持续失效，模型看到的历史
     * 每轮都不一样 —— 与「摘要成空壳」叠加后就是用户感知的「均衡模式失忆」。</p>
     *
     * <p>本方法把可摘要上界向后推一个 {@code assistant(tool_calls)} 的位置，
     * 使调用方可以「摘要 → 重新估算 → 仍超限再扩大」逐步收敛，直到降到上限或无处可扩。</p>
     *
     * <p><b>不可突破的底线</b>：最后一条 {@code assistant(tool_calls)} 及其之后的消息
     * （即当前正在进行的这一轮）永远不摘要 —— 否则模型连「刚刚这一步的结果」都看不到，
     * 会立刻重复执行同一次工具调用。</p>
     *
     * @param messages 消息列表
     * @param cursor   当前可摘要区上界（不含）
     * @return 新的上界（严格大于 {@code cursor}）；无可扩展空间时返回 {@code -1}
     */
    private int findNextSummaryCursor(List<Map<String, Object>> messages, int cursor) {
        if (messages == null || messages.isEmpty()) return -1;

        // 找到最后一条 assistant(tool_calls)：它及其之后属于「当前轮」，一律保护
        int lastAssistantWithTc = -1;
        for (int i = messages.size() - 1; i >= 0; i--) {
            Map<String, Object> msg = messages.get(i);
            if ("assistant".equals(msg.get("role")) && msg.containsKey("tool_calls")) {
                lastAssistantWithTc = i;
                break;
            }
        }
        int limit = lastAssistantWithTc >= 0 ? lastAssistantWithTc : messages.size();

        for (int i = Math.max(cursor, 0); i < limit; i++) {
            Map<String, Object> msg = messages.get(i);
            if ("assistant".equals(msg.get("role")) && msg.containsKey("tool_calls")) {
                return i + 1;
            }
        }
        return -1;
    }

    /**
     * 查找保护带的起始位置
     * 从后往前找，找到第 protectRounds 个 user 消息的索引
     *
     * <p><b>返回值语义（2026-11-16 修正）</b>：返回的是「保护带第一条消息的下标」。
     * 调用方 {@link #trimToTokenBudget} 把 [0, protectStart) 视为可裁剪区。
     * 当 user 轮次数不足 {@code protectRounds} 时（工具循环里常见只有 1 条 user 消息），
     * 原实现返回 1 —— 于是可裁剪区变成 [0,1)，而裁剪循环从 {@code idx = 1} 起跳、
     * 条件 {@code idx < lastIdx}（lastIdx = 1）直接不成立，<b>一条都删不掉</b>：
     * 日志表现为「上下文丢弃完成：移除了 0 条消息」。</p>
     *
     * <p>修正为「返回 0」：用户明确要求丢弃时应允许裁掉全部历史轮次（保护带为空）。
     * 只有 user 消息本身受调用方 {@code idx} 起点保护（{@code idx} 从 1 开始，
     * 第 0 条通常是 system prompt，永不被裁）。</p>
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
        /*
         * 轮次不足保护带：说明「全部历史都在保护带内」。此处返回 1（保护带从下标 1 起，
         * 即除 system prompt 外全部保护），语义与原实现一致。
         *
         * ⚠️ 但要特别注意：调用方 trimToTokenBudget 的可裁剪区是 [idx=1, protectStart)，
         * 返回 1 会让该区间为空、裁剪循环整体不执行（日志表现为「移除了 0 条消息」）。
         * 因此「是否允许突破保护带」的决策必须放在调用方——它才知道当前是否已越过
         * 丢弃阈值、是否必须让保护带让位。详见 trimToTokenBudget 的降级分支。
         */
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
        /*
          ★ 2026-11-18 修复「输出过程中秒断，末尾蹦出一坨 JSON」。

          因果链（实测 conversationId=257 消息 25926，62,192 字符）：
            模型空回合（finish_reason=stop、delta.content 全空、只有 reasoning）
            → 流里全是 delta.content="" 的多帧 JSON，readTree 对「多帧拼接」必然失败
            → 旧实现直接 `return cleaned`，把整段原始 JSON 当正文返回
            → 用户在前端看到 `{"choices":[...` —— 主观感受就是「输出到一半突然断了」
            → 更糟的是：工具循环的空回合检测以「content 非空」为前提，于是被完全绕过，
               `_finishReason=empty` 的续写通道永远走不到，
              这条 6 万字符脏消息还会被后续每轮重新加载，污染前缀缓存与上下文。

          因此：清洗后仍以 `{` / `[` 开头 → 判定为「无可提取正文」，返回空串。
          调用方据此走空回合处理：工具循环追加续写；无工具路径用提示文案替代。
        */
        if (cleaned.startsWith("{") || cleaned.startsWith("[")) {
            return "";
        }
        return cleaned;
    }

    // ==================== 语言指令 ====================

    // 语言强制指令已于 2026-11-26 取消：模型成熟后会按用户语言习惯回复，不再注入。

    /*
      ⚠️ 2026-11-25 彻底移除：injectLanguageIntoUserMessages / injectLanguageIntoLastUserMessage

      这两个方法都往 **user 消息内容** 里拼一段 72 字符的语言强制指令，而 user 消息只改内存、
      不落库 —— 前缀缓存要求「本次请求必须是上次请求的真前缀」，任何内存态与重建态的差异
      都会让该位置之后的内容全部作废：

        ① injectLanguageIntoLastUserMessage（只注入最后一条 user）：
           上一轮发出的那条已被改写，下一轮它退化为中间历史 user、形态回退成 DB 原文 → 断裂；
        ② injectLanguageIntoUserMessages（改成全部 user 注入）仍不彻底：
           工具循环中**动态新增**的 user 消息（中途补充需求 supplement_inject / 桌面注入）
           错过了注入时机（该注入只在请求准备阶段跑一次），下一轮从 DB 重建时被补注入，
           于是同一 index 的长度凭空多出 72 字符。

      实测证据（会话 353，v3.4.8）：ContextFingerprint 报
        `首条不一致 index=59 role: user → user，长度 119 → 191（差 72）`
      其后约 1.4 万 token 全部按未命中重新 prefill，跨轮首枪命中率仅 83.9%。

      结论：语言强制改由「唯一一条 system 指令」承担（见
      DeepSeekServiceImpl#prepareConversationContext 与 handleStreamingPhase 的 buildLanguageInstruction），
      user 消息一律保持 DB 原文，不在内存里做任何改写。
    */

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
