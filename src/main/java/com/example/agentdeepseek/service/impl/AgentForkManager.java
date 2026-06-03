package com.example.agentdeepseek.service.impl;

import com.example.agentdeepseek.config.DeepSeekConfig;
import com.example.agentdeepseek.mapper.SubAgentLogMapper;
import com.example.agentdeepseek.model.SubAgentResult;
import com.example.agentdeepseek.service.ConfigService;
import com.example.agentdeepseek.model.dto.ForkAgentRequest;
import com.example.agentdeepseek.model.entity.Skill;
import com.example.agentdeepseek.model.entity.SubAgentLog;
import com.example.agentdeepseek.service.SkillMatcher;
import com.example.agentdeepseek.service.SkillService;
import com.example.agentdeepseek.model.dto.PendingQuestion;
import com.example.agentdeepseek.service.PendingQuestionStore;
import com.example.agentdeepseek.tool.PermissionContext;
import com.example.agentdeepseek.tool.Tool;
import com.example.agentdeepseek.tool.ToolExecutor;
import com.example.agentdeepseek.tool.ToolRegistry;
import com.example.agentdeepseek.tool.impl.DeepSeekAnalyzer;
import com.example.agentdeepseek.util.ProjectRootContext;
import com.example.agentdeepseek.util.PromptUtil;
import com.example.agentdeepseek.util.ToolContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

/**
 * 子Agent调度管理器
 * 负责子Agent的创建、执行、结果收集和详情查询。
 * 子Agent在后台使用独立的工具循环执行任务，完成后结构化摘要回传给主Agent。
 */
@Slf4j
@Component
public class AgentForkManager {

    private final WebClient deepSeekWebClient;
    private final DeepSeekConfig deepSeekConfig;
    private final ObjectMapper objectMapper;
    private final ToolExecutor toolExecutor;
    private final ToolRegistry toolRegistry;
    private final SubAgentLogMapper subAgentLogMapper;
    private final SkillService skillService;
    private final SkillMatcher skillMatcher;
    private final AgentEventBus agentEventBus;
    private final PendingQuestionStore pendingQuestionStore;
    private final ConfigService configService;
    private final DeepSeekAnalyzer deepSeekAnalyzer;
    private final ToolLoopManager toolLoopManager;

    /** 子Agent结果存储：agentId → CompletableFuture */
    private final ConcurrentHashMap<String, CompletableFuture<SubAgentResult>> agentFutures = new ConcurrentHashMap<>();

    /** 运行中的子Agent上下文：agentId → SubAgentContext */
    private final ConcurrentHashMap<String, SubAgentContext> runningAgents = new ConcurrentHashMap<>();

    /** 待收集的子Agent跟踪（用于自动收集）：conversationId → Set<agentId> */
    private final ConcurrentHashMap<Long, Set<String>> pendingAgentsByConversation = new ConcurrentHashMap<>();

    /** 子Agent最大并发数 */
    private static final int MAX_CONCURRENT_AGENTS = 5;

    /** 子Agent默认最大迭代次数 */
    private static final int DEFAULT_MAX_ITERATIONS = 30;

    /** 评委累计扩展上限 */
    private static final int MAX_JUDGE_GRANTED = 100;

    /** 子Agent运行线程池（独立于主Agent线程池，避免互相影响） */
    private final ExecutorService agentExecutor = new ThreadPoolExecutor(
            2,                              // corePoolSize
            MAX_CONCURRENT_AGENTS,          // maximumPoolSize
            30L,                            // keepAliveTime
            TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(20),
            r -> {
                Thread t = new Thread(r, "sub-agent-");
                t.setDaemon(true);
                return t;
            },
            new ThreadPoolExecutor.CallerRunsPolicy()
    );

    public AgentForkManager(
            WebClient deepSeekWebClient,
            DeepSeekConfig deepSeekConfig,
            ObjectMapper objectMapper,
            @Lazy ToolExecutor toolExecutor,
            ToolRegistry toolRegistry,
            SubAgentLogMapper subAgentLogMapper,
            SkillService skillService,
            SkillMatcher skillMatcher,
            AgentEventBus agentEventBus,
            PendingQuestionStore pendingQuestionStore,
            ConfigService configService,
            DeepSeekAnalyzer deepSeekAnalyzer,
            ToolLoopManager toolLoopManager) {
        this.deepSeekWebClient = deepSeekWebClient;
        this.deepSeekConfig = deepSeekConfig;
        this.objectMapper = objectMapper;
        this.toolExecutor = toolExecutor;
        this.toolRegistry = toolRegistry;
        this.subAgentLogMapper = subAgentLogMapper;
        this.skillService = skillService;
        this.skillMatcher = skillMatcher;
        this.agentEventBus = agentEventBus;
        this.pendingQuestionStore = pendingQuestionStore;
        this.configService = configService;
        this.deepSeekAnalyzer = deepSeekAnalyzer;
        this.toolLoopManager = toolLoopManager;
    }

    // ================================================================
    //  公开接口
    // ================================================================

    /**
     * 创建并启动一个子Agent
     *
     * @param request           创建请求（含agentId, name, instructions, tools, skills等）
     * @param parentConversationId 父会话ID
     * @param parentTurnId        创建该子Agent的消息turnId
     * @param parentContext       父Agent的上下文摘要（用于context_mode=inherit_summary）
     * @return agentId
     */
    public String forkAgent(ForkAgentRequest request, Long parentConversationId,
                            String parentTurnId, String parentContext,
                            Long userId, String mode) {
        // 检查并发限制
        if (runningAgents.size() >= MAX_CONCURRENT_AGENTS) {
            throw new IllegalStateException("子Agent并发数已达上限（" + MAX_CONCURRENT_AGENTS + "个），请先 collect 已完成的任务");
        }

        String agentId = request.getAgentId();
        if (agentId == null || agentId.isBlank()) {
            throw new IllegalArgumentException("agent_id 不能为空");
        }
        if (agentFutures.containsKey(agentId)) {
            throw new IllegalArgumentException("agent_id '" + agentId + "' 已存在，请使用唯一ID");
        }

        // 1. 构建子Agent的系统提示词
        String systemPrompt = buildSubAgentSystemPrompt(request, parentContext);

        // 2. 构建初始消息列表
        List<Map<String, Object>> messages = buildSubAgentMessages(systemPrompt, request.getInstructions());

        // 3. 创建结果Future和上下文
        CompletableFuture<SubAgentResult> future = new CompletableFuture<>();
        agentFutures.put(agentId, future);

        SubAgentContext context = new SubAgentContext();
        context.setAgentId(agentId);
        context.setName(request.getName());
        context.setStatus("running");
        context.setMessages(messages);
        context.setTools(request.getTools() != null ? request.getTools() : List.of());
        context.setMaxIterations(request.getMaxIterations() != null ? request.getMaxIterations() : DEFAULT_MAX_ITERATIONS);
        context.setUserId(userId);
        context.setMode(mode != null ? mode : "auto");
        context.setConversationId(parentConversationId);
        runningAgents.put(agentId, context);

        // 4. 注册到待收集列表（主Agent完成时自动收集）
        pendingAgentsByConversation.computeIfAbsent(parentConversationId, k -> ConcurrentHashMap.newKeySet()).add(agentId);

        // 5. 持久化初始记录
        SubAgentLog logRecord = new SubAgentLog(agentId, parentConversationId, parentTurnId,
                request.getName(), request.getInstructions(), request.getContextMode(),
                toJsonArray(request.getTools()), toJsonArray(request.getSkills()),
                context.getMaxIterations());
        try {
            subAgentLogMapper.insert(logRecord);
            context.setLogId(logRecord.getId());
        } catch (Exception e) {
            log.warn("子Agent记录持久化失败: {}", e.getMessage());
        }

        // 5. 在后台线程执行子Agent工具循环
        // 先获取父线程的项目工作目录，子线程需要继承
        String parentProjectRoot = ProjectRootContext.get();
        agentExecutor.submit(() -> {
            try {
                // 设置线程级上下文（子Agent需要这些信息来通过权限检查和发送事件）
                ToolContext.set(mode != null ? mode : "auto", parentConversationId,
                        ToolContext.getAgentType(), userId);
                ToolContext.setTurnId(parentTurnId);
                PermissionContext.set(pendingQuestionStore, objectMapper);
                // 继承父Agent的项目工作目录
                ProjectRootContext.set(parentProjectRoot);

                SubAgentResult result = executeSubAgentCycle(context, parentConversationId);
                future.complete(result);
                context.setStatus("completed");
                // 更新持久化记录
                updateLogResult(context, result);
            } catch (Exception e) {
                log.error("子Agent {} 执行异常", agentId, e);
                SubAgentResult failed = SubAgentResult.failed(agentId, e.getMessage());
                future.complete(failed);
                context.setStatus("failed");
                context.setError(e.getMessage());
                updateLogResult(context, failed);
            } finally {
                ToolContext.clear();
                PermissionContext.clear();
                ProjectRootContext.clear();
            }
        });

        log.info("子Agent已创建: agentId={}, name={}, tools={}, skills={}",
                agentId, request.getName(), request.getTools(), request.getSkills());
        return agentId;
    }

    /**
     * 收集子Agent执行结果（阻塞等待）
     *
     * @param agentId  子Agent ID
     * @param timeoutSec 超时秒数
     * @return 子Agent执行结果
     */
    public SubAgentResult collectAgent(String agentId, long timeoutSec) {
        CompletableFuture<SubAgentResult> future = agentFutures.get(agentId);
        if (future == null) {
            // 尝试从数据库查询
            Optional<SubAgentLog> logOpt = subAgentLogMapper.selectByAgentId(agentId);
            if (logOpt.isPresent()) {
                SubAgentResult result = logToResult(logOpt.get());
                removePending(agentId);
                return result;
            }
            return SubAgentResult.notFound(agentId);
        }
        try {
            SubAgentResult result = future.get(timeoutSec, TimeUnit.SECONDS);
            removePending(agentId);
            agentFutures.remove(agentId);
            runningAgents.remove(agentId);
            return result;
        } catch (TimeoutException e) {
            return SubAgentResult.timeout(agentId, timeoutSec);
        } catch (Exception e) {
            return SubAgentResult.failed(agentId, e.getMessage());
        }
    }

    /**
     * 非阻塞收集子Agent结果：如果已完成则直接返回，否则返回 null
     * 用于 collect_agent 工具，避免阻塞主Agent的工具循环
     */
    public SubAgentResult collectAgentIfReady(String agentId) {
        CompletableFuture<SubAgentResult> future = agentFutures.get(agentId);
        if (future == null) {
            Optional<SubAgentLog> logOpt = subAgentLogMapper.selectByAgentId(agentId);
            if (logOpt.isPresent()) {
                SubAgentResult result = logToResult(logOpt.get());
                removePending(agentId);
                return result;
            }
            return SubAgentResult.notFound(agentId);
        }
        if (!future.isDone()) {
            return null;
        }
        try {
            SubAgentResult result = future.get();
            removePending(agentId);
            agentFutures.remove(agentId);
            runningAgents.remove(agentId);
            return result;
        } catch (Exception e) {
            return SubAgentResult.failed(agentId, e.getMessage());
        }
    }

    /**
     * 收集指定会话所有待收集的子Agent结果（阻塞等待每个子Agent完成）
     * 主Agent完成时自动调用，确保所有子Agent结果被收集
     */
    public List<SubAgentResult> collectPendingAgents(Long conversationId, long perAgentTimeoutSec) {
        Set<String> pending = pendingAgentsByConversation.get(conversationId);
        if (pending == null || pending.isEmpty()) return List.of();

        List<String> agentIds = new ArrayList<>(pending);
        List<SubAgentResult> results = new ArrayList<>();
        for (String agentId : agentIds) {
            try {
                results.add(collectAgent(agentId, perAgentTimeoutSec));
            } catch (Exception e) {
                log.warn("收集子Agent结果异常: agentId={}", agentId, e);
                results.add(SubAgentResult.failed(agentId, e.getMessage()));
            }
        }
        return results;
    }

    /**
     * 获取指定会话待收集的子Agent数量
     */
    public int getPendingAgentCount(Long conversationId) {
        Set<String> pending = pendingAgentsByConversation.get(conversationId);
        return pending != null ? pending.size() : 0;
    }

    /**
     * 从待收集列表中移除指定子Agent
     */
    private void removePending(String agentId) {
        SubAgentContext ctx = runningAgents.get(agentId);
        if (ctx != null && ctx.getConversationId() != null) {
            Long convId = ctx.getConversationId();
            Set<String> pending = pendingAgentsByConversation.get(convId);
            if (pending != null) {
                pending.remove(agentId);
                if (pending.isEmpty()) {
                    pendingAgentsByConversation.remove(convId);
                }
            }
        }
    }

    /**
     * 查看子Agent执行详情
     *
     * @param agentId 子Agent ID
     * @param scope   查看范围：summary / diff / thinking / calls / full_log
     * @return 详情文本
     */
    public String inspectAgent(String agentId, String scope) {
        Optional<SubAgentLog> logOpt = subAgentLogMapper.selectByAgentId(agentId);
        if (logOpt.isEmpty()) {
            return "未找到子Agent记录：" + agentId;
        }
        SubAgentLog log = logOpt.get();

        switch (scope) {
            case "summary": {
                // 重建完整的SubAgentResult并格式化输出
                SubAgentResult result = logToResult(log);
                // 补充文件变更列表（logToResult没有解析fileChangesJSON，这里额外补充）
                if (log.getFileChanges() != null) {
                    try {
                        JsonNode changes = objectMapper.readTree(log.getFileChanges());
                        List<String> created = new ArrayList<>();
                        List<String> modified = new ArrayList<>();
                        List<String> deleted = new ArrayList<>();
                        for (JsonNode n : changes.path("created")) created.add(n.asText());
                        for (JsonNode n : changes.path("modified")) modified.add(n.asText());
                        for (JsonNode n : changes.path("deleted")) deleted.add(n.asText());
                        result.setCreatedFiles(created);
                        result.setModifiedFiles(modified);
                        result.setDeletedFiles(deleted);
                    } catch (Exception ignored) {}
                }
                return result.toResultString();
            }
            case "diff":
                return log.getFileChanges() != null ? log.getFileChanges() : "无文件变更记录";
            case "thinking":
                return extractThinkingFromMessages(log.getFullMessages());
            case "calls":
                return extractToolCallsFromMessages(log.getFullMessages());
            case "full_log":
                return formatFullLog(log);
            default:
                return "未知的scope: " + scope + "，支持：summary / diff / thinking / calls / full_log";
        }
    }

    /**
     * 获取正在运行中的子Agent数量
     */
    public int getRunningCount() {
        return (int) runningAgents.values().stream().filter(c -> "running".equals(c.getStatus())).count();
    }

    // ================================================================
    //  子Agent工具循环（核心逻辑）
    // ================================================================

    /**
     * 执行子Agent的工具调用循环（非流式）
     * 递归调用DeepSeek API，处理tool_calls，直到完成任务或达到最大迭代次数
     */
    private SubAgentResult executeSubAgentCycle(SubAgentContext context, Long parentConversationId) {
        List<Map<String, Object>> messages = new ArrayList<>(context.getMessages());
        List<String> toolNames = context.getTools();
        int maxIterations = context.getMaxIterations();
        int totalGranted = 0;

        // 工具定义
        ArrayNode toolsDef = buildToolsDefinition(toolNames);
        boolean hasTools = toolsDef != null && toolsDef.size() > 0;

        SubAgentResult result = SubAgentResult.success(context.getAgentId(), "");
        List<String> modifiedFiles = new ArrayList<>();
        List<String> createdFiles = new ArrayList<>();
        List<String> keyDecisions = new ArrayList<>();
        List<String> trialRecords = new ArrayList<>();
        List<String> unexpectedFindings = new ArrayList<>();
        StringBuilder fullMessagesJson = new StringBuilder("[");
        boolean firstMsg = true;

        for (int iteration = 0; iteration < maxIterations + totalGranted; iteration++) {

            // 检查会话级别批准：如果已获批，设置 PermissionContext 跳过后续所有权限检查
            Long sessionConvId = context.getConversationId();
            if (sessionConvId != null && PermissionContext.isSessionApproved(sessionConvId)) {
                PermissionContext.setApproved();
                log.info("子Agent {} 检测到会话 {} 已获批，跳过所有权限检查", context.getAgentId(), sessionConvId);
            }

            // 构建API请求
            Map<String, Object> apiRequest = buildApiRequest(messages, toolsDef, hasTools);

            // 调用DeepSeek API（非流式）
            String response = callDeepSeekApi(apiRequest);

            // 解析响应
            JsonNode responseNode;
            try {
                responseNode = objectMapper.readTree(response);
            } catch (Exception e) {
                log.warn("子Agent API响应解析失败: {}", e.getMessage());
                result.setCompileResult("failed");
                result.setErrorMessage("API响应解析失败: " + e.getMessage());
                break;
            }

            JsonNode choice = responseNode.path("choices").get(0);
            JsonNode messageNode = choice.path("message");

            // 提取助手消息内容
            String assistantContent = messageNode.path("content").asText("");
            String reasoningContent = messageNode.path("reasoning_content").asText("");

            // 构建assistant消息并加入历史（thinking模式下reasoning_content必须回传）
            Map<String, Object> assistantMsg = new HashMap<>();
            assistantMsg.put("role", "assistant");
            assistantMsg.put("content", assistantContent);
            if (!reasoningContent.isEmpty()) {
                assistantMsg.put("reasoning_content", reasoningContent);
            }
            messages.add(assistantMsg);

            // 检查是否有tool_calls
            JsonNode toolCalls = messageNode.path("tool_calls");

            // 如果有tool_calls，将tool_calls加入assistant消息（tool消息需要关联tool_call_id）
            if (!toolCalls.isMissingNode() && toolCalls.isArray() && toolCalls.size() > 0) {
                assistantMsg.put("tool_calls", toolCalls);
            }

            // 收集完整消息到JSON（包含完整字段，包括tool_calls、reasoning_content等）
            Map<String, Object> fullAssistantMsg = new HashMap<>();
            fullAssistantMsg.put("role", "assistant");
            fullAssistantMsg.put("content", assistantContent);
            if (!reasoningContent.isEmpty()) {
                fullAssistantMsg.put("reasoning_content", reasoningContent);
            }
            if (!toolCalls.isMissingNode() && toolCalls.isArray() && toolCalls.size() > 0) {
                fullAssistantMsg.put("tool_calls", toolCalls);
            }
            if (!firstMsg) fullMessagesJson.append(",");
            fullMessagesJson.append(toMessageJson(fullAssistantMsg));
            firstMsg = false;

            if (toolCalls.isMissingNode() || !toolCalls.isArray() || toolCalls.isEmpty()) {
                // 没有工具调用，任务完成
                // 优先用 assistantContent；如果为空（thinking模式下可能如此），fallback 到 reasoning_content
                String summaryText = assistantContent;
                if (summaryText == null || summaryText.isBlank()) {
                    summaryText = reasoningContent;
                }
                result.setSummary(summaryText);
                result.setIterationsUsed(iteration + 1);
                result.setModifiedFiles(modifiedFiles);
                result.setCreatedFiles(createdFiles);
                result.setKeyDecisions(keyDecisions);
                result.setTrialRecords(trialRecords);
                result.setUnexpectedFindings(unexpectedFindings);
                // 尝试编译验证
                String compileResult = tryCompile();
                result.setCompileResult(compileResult);
                break;
            }

            // 处理工具调用：子Agent直接通过 ToolRegistry 执行工具，跳过 ToolExecutor 的权限拦截
            // 但保留高危工具的手动授权检查，通过 ask_user 弹窗管理权限
            JsonNode originalToolCalls = toolCalls.deepCopy();
            Set<String> highRiskTools = Set.of("delete_file", "write_file", "edit_file", "execute_sql", "run_command");
            List<ToolExecutor.ToolCallResult> toolResults = new ArrayList<>();
            for (JsonNode tc : toolCalls) {
                String tcId = tc.path("id").asText();
                String tcName = tc.path("function").path("name").asText();
                String argsStr = tc.path("function").path("arguments").asText();
                JsonNode args;
                try {
                    args = objectMapper.readTree(argsStr);
                } catch (Exception e) {
                    args = objectMapper.createObjectNode();
                }
                Tool tool = toolRegistry.getTool(tcName);
                boolean restricted = highRiskTools.contains(tcName);

                // 对于高危工具，检查会话级别授权
                if (restricted) {
                    Long cId = context.getConversationId();
                    if (cId != null && PermissionContext.isSessionApproved(cId)) {
                        restricted = false; // 已获批，不设为受限
                    }
                }

                String content;
                String displayArgs = "";
                if (tool != null && !restricted) {
                    try {
                        displayArgs = tcName + "(" + (argsStr.length() > 100 ? argsStr.substring(0, 100) : argsStr) + ")";
                        content = tool.execute(args);
                        log.info("子Agent {} 执行工具 {} 成功", context.getAgentId(), tcName);
                    } catch (Exception e) {
                        content = "工具执行异常: " + e.getMessage();
                        log.warn("子Agent {} 执行工具 {} 失败: {}", context.getAgentId(), tcName, e.getMessage());
                    }
                } else if (tool != null) {
                    content = "高危操作未获批准，已取消。工具 " + tcName + " 需要用户授权。";
                    log.warn("子Agent {} 高危工具 {} 被拒绝", context.getAgentId(), tcName);
                    displayArgs = tcName + "(需要授权)";
                } else {
                    content = "错误：未知工具 \"" + tcName + "\"";
                }
                toolResults.add(new ToolExecutor.ToolCallResult(tcId, tcName, content, restricted, displayArgs));
            }

            // 收集工具结果中的文件变更信息
            for (ToolExecutor.ToolCallResult tr : toolResults) {
                String toolName = tr.getToolName();
                String content = tr.getContent();

                // 构建tool消息
                Map<String, Object> toolMsg = new HashMap<>();
                toolMsg.put("role", "tool");
                toolMsg.put("tool_call_id", tr.getToolCallId());
                toolMsg.put("content", content);
                messages.add(toolMsg);

                // 收集完整消息
                fullMessagesJson.append(",");
                fullMessagesJson.append(toMessageJson(toolMsg));

                // 从工具结果中提取文件变更信息（启发式解析）
                if (content != null) {
                    extractFileChanges(toolName, content, modifiedFiles, createdFiles);
                }
            }

            // 检查是否有受限工具需要用户授权
            boolean hasRestricted = false;
            StringBuilder restrictedSummary = new StringBuilder();
            for (ToolExecutor.ToolCallResult tr : toolResults) {
                if (tr.isRestricted()) {
                    hasRestricted = true;
                    restrictedSummary.append("• ").append(tr.getOperationSummary() != null
                            ? tr.getOperationSummary() : tr.getToolName()).append("\n");
                }
            }
            if (hasRestricted) {
                // 通过主Agent事件通道发送 ask_user 事件，等待用户批准
                Long convId = context.getConversationId();
                if (convId != null && agentEventBus.hasSink(convId)) {
                    String uuid = java.util.UUID.randomUUID().toString();
                    String question = restrictedSummary.toString()
                            + "子Agent「" + context.getName() + "」需要您的批准，回复「批准」继续或输入其他内容拒绝";
                    PendingQuestion pq = new PendingQuestion(uuid, question);
                    pendingQuestionStore.put(uuid, pq);
                    boolean sent = agentEventBus.emitAskUser(convId, uuid, question, "permission");
                    if (!sent) {
                        log.warn("子Agent {} 发送授权事件失败，跳过授权等待", context.getAgentId());
                        pendingQuestionStore.remove(uuid);
                    } else {
                        log.info("子Agent {} 等待用户授权: uuid={}, question={}",
                                context.getAgentId(), uuid, question);
                        try {
                            String answer = pq.getFuture().get(120, TimeUnit.SECONDS);
                            // answer 格式: "__ACTION__:actionType:userMessage"
                            // actionType 取值: approve / approve_all / reject / custom
                            boolean isApproved = false;
                            if (answer != null && answer.startsWith("__ACTION__:")) {
                                String[] parts = answer.split(":", 3);
                                if (parts.length >= 3) {
                                    String action = parts[1];
                                    isApproved = "approve".equals(action) || "approve_all".equals(action);
                                }
                            } else if (answer != null) {
                                // 兼容旧格式：纯文本回答
                                isApproved = true;
                            }
                            if (!isApproved) {
                                log.warn("子Agent {} 工具调用被用户拒绝", context.getAgentId());
                                // 用户拒绝，终止工具循环
                                result.setSummary("用户拒绝了子Agent的工具调用");
                                result.setIterationsUsed(iteration + 1);
                                result.setCompileResult("not_run");
                                break;
                            }
                            log.info("子Agent {} 工具调用已获用户批准", context.getAgentId());
                            // 用户批准，设置会话级自动批准
                            PermissionContext.setSessionApproved(convId);
                            // 重新执行被拒绝的工具：遍历原始 toolCalls，找到被拒绝的工具直接执行
                            for (JsonNode originalCall : originalToolCalls) {
                                String origToolName = originalCall.path("function").path("name").asText();
                                String origToolCallId = originalCall.path("id").asText();
                                // 检查这个工具在 toolResults 中是否被拒绝
                                boolean wasRestricted = false;
                                for (ToolExecutor.ToolCallResult tr : toolResults) {
                                    if (tr.getToolName().equals(origToolName) && tr.isRestricted()) {
                                        wasRestricted = true;
                                        break;
                                    }
                                }
                                if (wasRestricted) {
                                    // 直接执行工具（跳过权限检查）
                                    Tool tool = toolRegistry.getTool(origToolName);
                                    if (tool != null) {
                                        try {
                                            String argsStr = originalCall.path("function").path("arguments").asText();
                                            JsonNode args = objectMapper.readTree(argsStr);
                                            log.info("子Agent {} 重新执行被授权工具: {}", context.getAgentId(), origToolName);
                                            String newResult = tool.execute(args);
                                            // 替换原来被拒绝的 tool 消息（不追加，避免同 tool_call_id 重复）
                                            for (int i = messages.size() - 1; i >= 0; i--) {
                                                Map<String, Object> msg = messages.get(i);
                                                if ("tool".equals(msg.get("role"))
                                                        && origToolCallId.equals(msg.get("tool_call_id"))) {
                                                    msg.put("content", newResult);
                                                    break;
                                                }
                                            }
                                            // 更新文件变更信息
                                            if (newResult != null) {
                                                extractFileChanges(origToolName, newResult, modifiedFiles, createdFiles);
                                            }
                                        } catch (Exception e) {
                                            log.warn("子Agent {} 重新执行工具 {} 失败: {}", context.getAgentId(), origToolName, e.getMessage());
                                        }
                                    }
                                }
                            }
                        } catch (java.util.concurrent.TimeoutException e) {
                            log.warn("子Agent {} 等待用户授权超时(120s)，继续执行", context.getAgentId());
                            pendingQuestionStore.remove(uuid);
                        } catch (Exception e) {
                            log.warn("子Agent {} 等待用户授权异常: {}", context.getAgentId(), e.getMessage());
                            pendingQuestionStore.remove(uuid);
                        }
                    }
                } else {
                    log.warn("子Agent {} 无SSE事件通道，跳过授权（仅记录日志）", context.getAgentId());
                }
            }

            // 检测重复调用（防止死循环）
            if (hasRepeatedCalls(messages, 4)) {
                keyDecisions.add("检测到重复工具调用，终止循环");
                result.setIterationsUsed(iteration + 1);
                result.setCompileResult("not_run");
                break;
            }

            // 到达迭代上限，触发评委
            if (iteration >= maxIterations - 1 + totalGranted) {
                boolean shouldExtend = evaluateWithSubJudge(messages);
                if (shouldExtend && totalGranted < MAX_JUDGE_GRANTED) {
                    int additional = Math.min(10, MAX_JUDGE_GRANTED - totalGranted);
                    totalGranted += additional;
                    log.info("子Agent {} 评委允许扩展 {} 次迭代，累计扩展 {}", context.getAgentId(), additional, totalGranted);
                    keyDecisions.add("评委评估通过，扩展 " + additional + " 次迭代");
                } else {
                    keyDecisions.add("评委评估终止或已达扩展上限");
                    result.setIterationsUsed(iteration + 1);
                    result.setCompileResult("not_run");
                    break;
                }
            }
        }

        fullMessagesJson.append("]");
        result.setFullMessagesJson(fullMessagesJson.toString());

        // 兜底：如果summary为空（可能在重复调用/评委终止/用户拒绝等路径中未设置），
        // 从最后一条助手消息的内容中提取
        if (result.getSummary() == null || result.getSummary().isBlank()) {
            for (int i = messages.size() - 1; i >= 0; i--) {
                Map<String, Object> msg = messages.get(i);
                if ("assistant".equals(msg.get("role"))) {
                    String content = (String) msg.get("content");
                    if (content != null && !content.isBlank()) {
                        result.setSummary(content);
                        break;
                    }
                    String reasoning = (String) msg.get("reasoning_content");
                    if (reasoning != null && !reasoning.isBlank()) {
                        result.setSummary(reasoning);
                        break;
                    }
                }
            }
        }

        // 持久化完整消息
        context.setResult(result);
        return result;
    }

    // ================================================================
    //  辅助方法
    // ================================================================

    /**
     * 构建子Agent的system prompt
     */
    private String buildSubAgentSystemPrompt(ForkAgentRequest request, String parentContext) {
        StringBuilder sb = new StringBuilder();
        sb.append("你是主Agent创建的子Agent「").append(request.getName()).append("」。\n\n");
        sb.append("## 你的任务\n").append(request.getInstructions()).append("\n\n");

        // 注入工作目录信息（让子Agent知道项目根路径）
        String workDir = ProjectRootContext.get();
        sb.append("## 工作目录\n");
        sb.append("当前项目根目录：").append(workDir).append("\n");
        sb.append("所有文件操作（read_file/write_file/edit_file/glob_files/grep_search 等）和命令执行（run_command/run_server）都基于此目录。\n");
        sb.append("使用相对路径时以此目录为基准，例如 \"src/main/java/App.java\" 表示 \"").append(workDir).append("/src/main/java/App.java\"。\n\n");

        // 注入技能（如果指定了skills）
        if (request.getSkills() != null && !request.getSkills().isEmpty()) {
            sb.append("## 可用技能\n");
            sb.append("以下技能可用于指导你的工作流程，根据任务情况自动匹配使用：\n\n");
            try {
                List<Skill> skills = loadSkillsByNameOrId(request.getSkills());
                for (Skill skill : skills) {
                    sb.append("### ").append(skill.getName()).append("\n");
                    sb.append(skill.getDescription()).append("\n");
                    if (skill.getInstructions() != null && !skill.getInstructions().isBlank()) {
                        sb.append("使用说明：\n").append(skill.getInstructions()).append("\n");
                    }
                    sb.append("\n");
                }
            } catch (Exception e) {
                log.warn("加载技能失败: {}", e.getMessage());
            }
        }

        // 上下文摘要
        if (parentContext != null && !parentContext.isEmpty()) {
            sb.append("## 父Agent上下文摘要\n").append(parentContext).append("\n\n");
        }

        sb.append("## 可用工具\n");
        if (request.getTools() != null) {
            for (String toolName : request.getTools()) {
                Tool tool = toolRegistry.getTool(toolName);
                if (tool != null) {
                    sb.append("- **").append(tool.getName()).append("**：").append(tool.getDescription()).append("\n");
                }
            }
        }

        sb.append("\n## 执行要求\n");
        sb.append("1. 独立完成分配给你的子任务，完成后输出详细的结果报告\n");
        sb.append("2. 报告应包含：完成了什么、修改了哪些文件、关键决策及理由\n");
        sb.append("3. 如果发现现有代码的问题，请在报告中记录\n");
        sb.append("4. 如果尝试了多种方案，请记录试错过程\n");
        sb.append("5. 请使用中文思考和回复\n");
        sb.append("6. **【重要】最终输出的汇总报告必须写在 content 字段中**（而不是只写在 reasoning/思考过程中），\n");
        sb.append("   以确保主Agent能清晰看到你的执行结果。最终报告格式如下：\n");
        sb.append("   【已完成】简述完成了什么\n");
        sb.append("   【文件变更】列出创建/修改/删除的文件路径\n");
        sb.append("   【关键决策】记录重要方案选择的理由\n");
        sb.append("   【其他说明】试错记录、意外发现等\n");

        return sb.toString();
    }

    /**
     * 构建子Agent初始消息列表
     */
    private List<Map<String, Object>> buildSubAgentMessages(String systemPrompt, String instructions) {
        List<Map<String, Object>> messages = new ArrayList<>();

        Map<String, Object> systemMsg = new HashMap<>();
        systemMsg.put("role", "system");
        systemMsg.put("content", systemPrompt);
        messages.add(systemMsg);

        Map<String, Object> userMsg = new HashMap<>();
        userMsg.put("role", "user");
        userMsg.put("content", "请开始执行你的任务：\n" + instructions);
        messages.add(userMsg);

        return messages;
    }

    /**
     * 构建工具定义JSON
     */
    private ArrayNode buildToolsDefinition(List<String> toolNames) {
        return toolExecutor.buildToolDefinitions(toolNames);
    }

    /**
     * 构建API请求体
     */
    private Map<String, Object> buildApiRequest(List<Map<String, Object>> messages,
                                                ArrayNode toolsDef, boolean hasTools) {
        Map<String, Object> request = new HashMap<>();
        request.put("model", deepSeekConfig.getDefaultModel());
        request.put("messages", messages);
        request.put("stream", false);
        request.put("temperature", 1.0);

        // 思考模式跟随主Agent配置
        String thinkingMode = deepSeekConfig.getThinkingMode();
        Map<String, Object> thinking = new HashMap<>();
        if ("non-thinking".equals(thinkingMode)) {
            thinking.put("type", "disabled");
        } else {
            thinking.put("type", "enabled");
            request.put("reasoning_effort", "thinking_max".equals(thinkingMode) ? "max" : "high");
        }
        request.put("thinking", thinking);

        if (hasTools) {
            List<Map<String, Object>> toolsList = objectMapper.convertValue(
                    toolsDef,
                    objectMapper.getTypeFactory().constructCollectionType(List.class, Map.class)
            );
            request.put("tools", toolsList);
            request.put("tool_choice", "auto");
        }

        return request;
    }

    /**
     * 调用DeepSeek API（非流式）
     * 每次请求前从数据库动态获取 API Key（与主Agent保持一致）
     */
    private String callDeepSeekApi(Map<String, Object> apiRequest) {
        try {
            // 动态获取 API Key（与主Agent DeepSeekServiceImpl 中 initDynamicApiKey() 的 filter 逻辑一致）
            String apiKey = configService.getValue("deepseek_api_key");
            if (apiKey == null || apiKey.isEmpty()) {
                throw new RuntimeException("DeepSeek API Key 未配置，请先在配置页面设置 API Key");
            }
            return deepSeekWebClient.post()
                    .uri("/v1/chat/completions")
                    .headers(headers -> headers.setBearerAuth(apiKey))
                    .bodyValue(apiRequest)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block(Duration.ofSeconds(300));
        } catch (WebClientResponseException e) {
            String responseBody = e.getResponseBodyAsString();
            log.error("子Agent API调用失败 ({}): status={}, body={}", 
                    e.getStatusCode(), e.getStatusCode().value(), 
                    responseBody.length() > 2000 ? responseBody.substring(0, 2000) + "..." : responseBody);
            throw new RuntimeException("DeepSeek API调用失败: " + e.getStatusCode() + " - " 
                    + (responseBody.length() > 200 ? responseBody.substring(0, 200) : responseBody), e);
        } catch (Exception e) {
            log.error("子Agent API调用失败", e);
            throw new RuntimeException("DeepSeek API调用失败: " + e.getMessage(), e);
        }
    }

    /**
     * 通过名称或ID加载技能
     */
    private List<Skill> loadSkillsByNameOrId(List<String> skillRefs) {
        if (skillRefs == null || skillRefs.isEmpty()) return List.of();
        List<Skill> result = new ArrayList<>();
        for (String ref : skillRefs) {
            try {
                Long id = Long.parseLong(ref);
                Skill skill = skillService.getSkillById(id);
                if (skill != null) {
                    result.add(skill);
                }
            } catch (NumberFormatException e) {
                // 按名称匹配（简化实现：返回空，实际可扩展名称查询）
                log.debug("技能引用不是数字ID，按名称匹配暂不支持: {}", ref);
            }
        }
        return result;
    }

    /**
     * 从工具结果中提取文件变更信息
     * 支持：write_file（写入成功：路径）、edit_file（编辑成功：路径 / 编辑成功（宽松匹配）：路径）
     *       delete_file（删除成功：路径 / 已删除：路径 / 路径 已删除）
     */
    private void extractFileChanges(String toolName, String content,
                                    List<String> modifiedFiles, List<String> createdFiles) {
        if (content == null || content.isBlank()) return;
        // write_file：写入成功：/path/to/file
        if ("write_file".equals(toolName) && content.contains("写入成功")) {
            String path = extractPathAfterPrefix(content, "写入成功：");
            if (path != null) {
                createdFiles.add(normalizePath(path));
            }
            return;
        }
        // edit_file：编辑成功：/path/to/file 或 编辑成功（宽松匹配）：/path/to/file
        if ("edit_file".equals(toolName) && content.contains("编辑成功")) {
            String path = extractPathAfterPrefix(content, "编辑成功：");
            if (path == null) {
                path = extractPathAfterPrefix(content, "编辑成功（宽松匹配）：");
            }
            if (path != null) {
                modifiedFiles.add(normalizePath(path));
            }
            return;
        }
        // delete_file：删除成功：/path/to/file 或 已删除：/path/to/file
        if ("delete_file".equals(toolName)) {
            String path = extractPathAfterPrefix(content, "删除成功：");
            if (path == null) {
                path = extractPathAfterPrefix(content, "已删除：");
            }
            if (path != null) {
                // 注意：此处不回传 deletedFiles，因为 SubAgentResult 没有 deletedFiles 收集参数
                // 统一添加到 modifiedFiles 标记为删除
                modifiedFiles.add("[已删除] " + normalizePath(path));
            }
            return;
        }
    }

    /**
     * 从文本中提取指定前缀后的文件路径（取到行尾或第一个空白/换行符）
     */
    private String extractPathAfterPrefix(String text, String prefix) {
        int idx = text.indexOf(prefix);
        if (idx < 0) return null;
        int start = idx + prefix.length();
        if (start >= text.length()) return null;
        // 取到行尾
        int end = text.indexOf('\n', start);
        if (end < 0) end = text.indexOf('\r', start);
        if (end < 0) end = text.length();
        String path = text.substring(start, end).trim();
        return path.isEmpty() ? null : path;
    }

    /**
     * 规范化文件路径：如果路径在项目根目录下，转为相对路径
     */
    private String normalizePath(String absPath) {
        if (absPath == null) return null;
        String projectRoot = ProjectRootContext.get();
        if (projectRoot != null && absPath.startsWith(projectRoot)) {
            String relative = absPath.substring(projectRoot.length());
            // 去掉前导分隔符
            if (relative.startsWith("/") || relative.startsWith("\\")) {
                relative = relative.substring(1);
            }
            return relative;
        }
        return absPath;
    }

    /**
     * 检测最近工具调用是否重复
     */
    private boolean hasRepeatedCalls(List<Map<String, Object>> messages, int threshold) {
        int repeatCount = 0;
        String lastPattern = null;
        for (int i = messages.size() - 1; i >= 0; i--) {
            Map<String, Object> msg = messages.get(i);
            if (!"assistant".equals(msg.get("role")) || !msg.containsKey("tool_calls")) {
                continue;
            }
            Object toolCallsObj = msg.get("tool_calls");
            if (!(toolCallsObj instanceof JsonNode)) continue;
            JsonNode toolCalls = (JsonNode) toolCallsObj;
            String pattern = buildCallPattern(toolCalls);
            if (pattern == null || pattern.isEmpty()) continue;
            if (lastPattern == null) {
                lastPattern = pattern;
                repeatCount = 1;
            } else if (pattern.equals(lastPattern)) {
                repeatCount++;
                if (repeatCount >= threshold) return true;
            } else {
                return false;
            }
        }
        return false;
    }

    private String buildCallPattern(JsonNode toolCalls) {
        if (!toolCalls.isArray() || toolCalls.isEmpty()) return "";
        return toolCalls.get(0).path("function").path("name").asText("");
    }

    /**
     * 子Agent评委评估（复用主Agent评委机制）
     * <p>
     * 调用 DeepSeek API 分析子Agent的任务进展，判断是否应继续执行。
     * API 调用失败时自动降级到本地简化逻辑，确保子Agent不被误杀。
     * </p>
     */
    private boolean evaluateWithSubJudge(List<Map<String, Object>> messages) {
        // 1. 加载评委提示词
        String judgePrompt = PromptUtil.getPrompt("judge_prompt.txt");
        if (judgePrompt == null || judgePrompt.isEmpty()) {
            log.warn("子Agent评委提示词加载失败，使用降级逻辑");
            return evaluateWithSubJudgeFallback(messages);
        }

        // 2. 构建评委上下文（复用 ToolLoopManager）
        String judgeContext = toolLoopManager.buildJudgeContext(messages);

        // 3. 调用评委 API（禁用 thinking 以获得更快响应，超时 180s）
        String response = deepSeekAnalyzer.analyzeWithoutThinking(judgePrompt, judgeContext, 180);

        // 4. 检查是否返回错误
        if (response.startsWith("错误：")) {
            log.warn("子Agent评委 API 调用失败，使用降级逻辑: {}", response);
            return evaluateWithSubJudgeFallback(messages);
        }

        // 5. 解析评委响应（复用 ToolLoopManager）
        ToolLoopManager.JudgeResult result = toolLoopManager.parseJudgeResult(response);
        if (result == null) {
            log.warn("子Agent评委响应解析失败，使用降级逻辑");
            return evaluateWithSubJudgeFallback(messages);
        }

        // 6. 根据判断结果返回
        if ("extend".equals(result.judgment)) {
            log.info("子Agent评委评估：extend，理由: {}", result.reason);
            return true;
        } else {
            log.info("子Agent评委评估：reject，理由: {}", result.reason);
            return false;
        }
    }

    /**
     * 降级评估逻辑：当评委 API 不可用时使用
     * 检查最近是否有实质进展（工具调用是否成功）
     */
    private boolean evaluateWithSubJudgeFallback(List<Map<String, Object>> messages) {
        for (int i = messages.size() - 1; i >= Math.max(0, messages.size() - 6); i--) {
            Map<String, Object> msg = messages.get(i);
            if ("tool".equals(msg.get("role"))) {
                String content = (String) msg.get("content");
                if (content != null && !content.contains("错误") && !content.contains("失败")) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * 尝试编译验证（简化版）
     */
    private String tryCompile() {
        // 此处应由T8/T9/T10中的实际工具调用触发编译
        // 简化实现返回not_run
        return "not_run";
    }

    /**
     * 将JSON数组转换为字符串
     */
    private String toJsonArray(List<String> list) {
        if (list == null || list.isEmpty()) return "[]";
        try {
            ArrayNode arr = objectMapper.createArrayNode();
            for (String s : list) arr.add(s);
            return objectMapper.writeValueAsString(arr);
        } catch (Exception e) {
            return "[]";
        }
    }

    /**
     * 将消息转换为JSON字符串
     */
    private String toMessageJson(Map<String, Object> msg) {
        try {
            return objectMapper.writeValueAsString(msg);
        } catch (Exception e) {
            return "{}";
        }
    }

    /**
     * 更新持久化记录
     */
    private void updateLogResult(SubAgentContext context, SubAgentResult result) {
        if (context.getLogId() == null) return;
        try {
            SubAgentLog log = new SubAgentLog();
            log.setId(context.getLogId());
            log.setStatus(result.getStatus());
            log.setSummary(result.getSummary());
            log.setFullMessages(result.getFullMessagesJson());
            log.setIterationsUsed(result.getIterationsUsed());
            log.setCompileResult(result.getCompileResult());
            log.setErrorMessage(result.getErrorMessage());
            log.setCompletedAt(LocalDateTime.now());
            log.setUpdatedAt(LocalDateTime.now());

            // 文件变更
            Map<String, List<String>> changes = new HashMap<>();
            changes.put("created", result.getCreatedFiles());
            changes.put("modified", result.getModifiedFiles());
            changes.put("deleted", result.getDeletedFiles());
            try {
                log.setFileChanges(objectMapper.writeValueAsString(changes));
            } catch (Exception e) {
                log.setFileChanges("{}");
            }

            subAgentLogMapper.update(log);
        } catch (Exception e) {
            log.warn("更新子Agent持久化记录失败: {}", e.getMessage());
        }
    }

    /**
     * 将数据库记录转换为SubAgentResult
     */
    private SubAgentResult logToResult(SubAgentLog log) {
        SubAgentResult result = new SubAgentResult();
        result.setAgentId(log.getAgentId());
        result.setStatus(log.getStatus());
        result.setSummary(log.getSummary());
        result.setCompileResult(log.getCompileResult());
        result.setIterationsUsed(log.getIterationsUsed() != null ? log.getIterationsUsed() : 0);
        result.setErrorMessage(log.getErrorMessage());
        result.setFullMessagesJson(log.getFullMessages());
        return result;
    }

    /**
     * 从完整消息中提取thinking内容
     */
    private String extractThinkingFromMessages(String fullMessagesJson) {
        if (fullMessagesJson == null || fullMessagesJson.isEmpty()) return "无thinking记录";
        try {
            JsonNode msgs = objectMapper.readTree(fullMessagesJson);
            StringBuilder sb = new StringBuilder();
            for (JsonNode msg : msgs) {
                JsonNode reasoning = msg.path("reasoning_content");
                if (!reasoning.isMissingNode() && !reasoning.asText().isEmpty()) {
                    sb.append(reasoning.asText()).append("\n---\n");
                }
            }
            return sb.length() > 0 ? sb.toString() : "无thinking记录";
        } catch (Exception e) {
            return "解析thinking失败: " + e.getMessage();
        }
    }

    /**
     * 从完整消息中提取工具调用记录
     */
    private String extractToolCallsFromMessages(String fullMessagesJson) {
        if (fullMessagesJson == null || fullMessagesJson.isEmpty()) return "无工具调用记录";
        try {
            JsonNode msgs = objectMapper.readTree(fullMessagesJson);
            StringBuilder sb = new StringBuilder();
            for (JsonNode msg : msgs) {
                JsonNode toolCalls = msg.path("tool_calls");
                if (!toolCalls.isMissingNode() && toolCalls.isArray()) {
                    for (JsonNode tc : toolCalls) {
                        String name = tc.path("function").path("name").asText("");
                        String args = tc.path("function").path("arguments").asText("");
                        sb.append("🔧 ").append(name);
                        if (args.length() > 100) args = args.substring(0, 100) + "...";
                        sb.append("(").append(args).append(")\n");
                    }
                }
                JsonNode content = msg.path("content");
                if (!content.isMissingNode() && "tool".equals(msg.path("role").asText())) {
                    String c = content.asText();
                    if (c.length() > 200) c = c.substring(0, 200) + "...";
                    sb.append("  → ").append(c).append("\n");
                }
            }
            return sb.length() > 0 ? sb.toString() : "无工具调用记录";
        } catch (Exception e) {
            return "解析工具调用失败: " + e.getMessage();
        }
    }

    /**
     * 格式化为完整日志
     */
    private String formatFullLog(SubAgentLog log) {
        StringBuilder sb = new StringBuilder();
        sb.append("═══════════════════════════════════════════\n");
        sb.append("子Agent: ").append(log.getAgentId()).append("（").append(log.getName()).append("）\n");
        sb.append("状态: ").append(log.getStatus()).append("\n");
        sb.append("任务: ").append(log.getInstructions()).append("\n");
        sb.append("迭代: ").append(log.getIterationsUsed()).append(" / ").append(log.getMaxIterations()).append(" 次\n");
        sb.append("编译: ").append(log.getCompileResult()).append("\n");
        sb.append("创建: ").append(log.getCreatedAt()).append("\n");
        sb.append("完成: ").append(log.getCompletedAt()).append("\n");
        sb.append("───────────────────────────────────────────\n");

        // 摘要
        if (log.getSummary() != null && !log.getSummary().isEmpty()) {
            sb.append("【执行摘要】").append(log.getSummary()).append("\n\n");
        }

        // 文件变更（解析JSON展示）
        if (log.getFileChanges() != null) {
            try {
                JsonNode changes = objectMapper.readTree(log.getFileChanges());
                int total = changes.path("created").size() + changes.path("modified").size() + changes.path("deleted").size();
                if (total > 0) {
                    sb.append("【文件变更】共 ").append(total).append(" 个\n");
                    if (changes.path("created").size() > 0) {
                        sb.append("  新增：");
                        List<String> files = new ArrayList<>();
                        for (JsonNode n : changes.path("created")) files.add(n.asText());
                        sb.append(String.join(", ", files)).append("\n");
                    }
                    if (changes.path("modified").size() > 0) {
                        sb.append("  修改：");
                        List<String> files = new ArrayList<>();
                        for (JsonNode n : changes.path("modified")) files.add(n.asText());
                        sb.append(String.join(", ", files)).append("\n");
                    }
                    if (changes.path("deleted").size() > 0) {
                        sb.append("  删除：");
                        List<String> files = new ArrayList<>();
                        for (JsonNode n : changes.path("deleted")) files.add(n.asText());
                        sb.append(String.join(", ", files)).append("\n");
                    }
                }
            } catch (Exception e) {
                sb.append("【文件变更】").append(log.getFileChanges()).append("\n");
            }
        }

        // 错误信息
        if (log.getErrorMessage() != null && !log.getErrorMessage().isEmpty()) {
            sb.append("【错误信息】").append(log.getErrorMessage()).append("\n");
        }

        // 工具/技能
        if (log.getTools() != null) {
            sb.append("【可用工具】").append(log.getTools()).append("\n");
        }
        if (log.getSkills() != null) {
            sb.append("【可用技能】").append(log.getSkills()).append("\n");
        }
        if (log.getContextMode() != null) {
            sb.append("【上下文模式】").append(log.getContextMode()).append("\n");
        }

        sb.append("═══════════════════════════════════════════\n");
        return sb.toString();
    }
}

// ================================================================
//  子Agent上下文（内部类）
// ================================================================

@Data
class SubAgentContext {
    private Long logId;
    private String agentId;
    private String name;
    private String status;
    private List<Map<String, Object>> messages;
    private List<String> tools;
    private int maxIterations;
    private SubAgentResult result;
    private String error;
    private Long userId;
    private String mode;
    private Long conversationId;
}
