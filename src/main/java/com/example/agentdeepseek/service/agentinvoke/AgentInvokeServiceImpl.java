package com.example.agentdeepseek.service.agentinvoke;

import com.example.agentdeepseek.mapper.AgentConfigMapper;
import com.example.agentdeepseek.mapper.ConversationMapper;
import com.example.agentdeepseek.model.dto.AgentInvokeRequest;
import com.example.agentdeepseek.model.dto.AgentInvokeResult;
import com.example.agentdeepseek.model.dto.ChatRequest;
import com.example.agentdeepseek.model.entity.AgentConfig;
import com.example.agentdeepseek.model.entity.Conversation;
import com.example.agentdeepseek.service.DeepSeekService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 智能体互相调用服务实现（Phase 19 P1）
 * <p>
 * 链路：resolveAgent（B 校验）→ 信任链校验（环/深度）→ 会话准备（create/continue）→
 * 基线消息 id 记录 → 注册 callId → 构造 ChatRequest 调 streamChat(Internal)（后台订阅执行）→
 * await=true 轮询 agent_task 至终态 → 结果打包（状态/最终答复/工具轨迹摘要）。
 * </p>
 * <p>
 * 溯源三件套：① 内存注册表（callId → 调用映射，TTL 清理）② 消息溯源注记
 * （B 会话首条 user 消息文本带「由智能体 A 委托」）③ slf4j 审计日志。
 * 模块独立于 AgentForkManager，不 fork、不依赖子 Agent 代码。
 * </p>
 */
@Slf4j
@Service
public class AgentInvokeServiceImpl implements AgentInvokeService {

    /** 同步等待默认/上限秒数 */
    private static final int DEFAULT_AWAIT_SEC = 300;
    private static final int MAX_AWAIT_SEC = 600;
    /** 结果打包截断参数 */
    private static final int TRACE_PER_LINE_CHARS = 300;
    private static final int TRACE_MAX_LINES = 20;
    private static final int TRACE_FULL_MAX_CHARS = 20000;
    /** 注册表 TTL 与容量保护 */
    private static final long RECORD_TTL_MS = 30 * 60 * 1000L;
    private static final int RECORD_MAX_SIZE = 5000;

    private final AgentConfigMapper agentConfigMapper;
    private final ConversationMapper conversationMapper;
    private final DeepSeekService deepSeekService;
    private final JdbcTemplate jdbcTemplate;

    /** 调用注册表：callId → InvokeRecord（内存溯源，重启丢失可接受——会话 ID 本身持久化） */
    private final ConcurrentHashMap<String, InvokeRecord> records = new ConcurrentHashMap<>();

    public AgentInvokeServiceImpl(AgentConfigMapper agentConfigMapper,
                                  ConversationMapper conversationMapper,
                                  DeepSeekService deepSeekService,
                                  JdbcTemplate jdbcTemplate) {
        this.agentConfigMapper = agentConfigMapper;
        this.conversationMapper = conversationMapper;
        this.deepSeekService = deepSeekService;
        this.jdbcTemplate = jdbcTemplate;
    }

    // ==================== invoke ====================

    @Override
    public AgentInvokeResult invoke(AgentInvokeRequest req) {
        long start = System.currentTimeMillis();
        // 基础校验
        if (req == null || req.getTargetAgent() == null || req.getTargetAgent().isBlank()) {
            return fail("ERROR", null, null, "【参数错误】缺少目标智能体 target_agent（agent_config_id 或名称）", start);
        }
        if (req.getInstructions() == null || req.getInstructions().isBlank()) {
            return fail("ERROR", null, null, "【参数错误】缺少任务指令 instructions", start);
        }
        String mode = req.getMode() != null ? req.getMode() : "create_session";
        if (!"create_session".equals(mode) && !"continue_session".equals(mode)) {
            return fail("ERROR", null, null, "【参数错误】mode 仅支持 create_session / continue_session", start);
        }
        String scope = req.getScope() != null ? req.getScope() : "summary";

        // 1. resolveAgent：目标 B（id 数字 or 名称；名称仅查 enabled 配置，沿用 selectByUser 语义）
        AgentConfig target = resolveAgent(req.getTargetAgent());
        if (target == null) {
            return fail("AGENT_NOT_FOUND", null, null,
                    "❌ 智能体不存在或未启用: " + req.getTargetAgent() + "\n【建议】用名称时确认拼写；用 ID 时确认 agent_config_id", start);
        }
        if (target.getEnabled() == null || target.getEnabled() != 1) {
            return fail("AGENT_DISABLED", null, null,
                    "❌ 智能体「" + target.getName() + "」已停用（enabled=0），无法被调用", start);
        }
        Long callerAgent = req.getCallerAgentConfigId();

        // 2. 信任链校验（环检测 + 深度限制）
        List<Long> chain = new ArrayList<>();
        if (req.getTrustChain() != null) {
            chain.addAll(req.getTrustChain());
        }
        if (callerAgent != null) {
            if (callerAgent.equals(target.getId())) {
                return fail("SELF_INVOKE", null, null,
                        "❌ 智能体不能调用自己（" + target.getName() + "），请选择其他智能体或让上层智能体委托", start);
            }
            if (!chain.contains(callerAgent)) {
                chain.add(callerAgent);
            }
            if (chain.contains(target.getId())) {
                return fail("CYCLE", null, null,
                        "❌ 检测到调用环：信任链 " + chain + " 中已包含目标智能体「" + target.getName()
                                + "」，拒绝调用（防止 A→B→A 死循环）", start);
            }
            if (chain.size() >= MAX_TRUST_DEPTH) {
                return fail("DEPTH_LIMIT", null, null,
                        "❌ 信任链深度已达上限（" + MAX_TRUST_DEPTH + " 层，当前 " + chain + "），拒绝更深层调用", start);
            }
        }
        // 3. 会话准备（create_session 建会话 / continue_session 校验+复用）
        Long sessionId;
        String workDir = (req.getWorkDir() != null && !req.getWorkDir().isBlank())
                ? req.getWorkDir() : target.getWorkDir();
        if ("create_session".equals(mode)) {
            sessionId = createSessionFor(req, target, workDir);
            log.info("[AgentInvoke] 已为智能体 B 创建新会话: caller={}, target={}, sessionId={}, chain={}",
                    callerAgent, target.getId(), sessionId, chain);
        } else {
            Long sid;
            try {
                sid = resolveContinueSession(req, target, callerAgent);
            } catch (BusySessionException e) {
                return fail("BUSY", null, null,
                        "❌ 智能体 B「" + target.getName() + "」的目标会话 #" + e.getSessionId()
                                + " 正忙（有任务在执行），无法继续会话。\n【建议】等待当前任务完成；或改用 create_session 新建会话", start);
            }
            if (sid == null) {
                return fail("SESSION_NOT_FOUND", null, null,
                        "❌ 无法继续会话：会话不存在或不属于智能体「" + target.getName()
                                + "」。\n【建议】确认 session_id 正确；或改用 create_session 新建会话", start);
            }
            sessionId = sid;
        }

        // 4. 记录基线（结果打包消息边界 + 任务行边界：只认本次调用之后的新消息/新任务行）
        Long baselineMsgId = maxMessageId(sessionId);
        Long baselineTaskId = maxTaskId(sessionId);

        // 5. 注册调用记录（callId）
        String callId = "call_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        InvokeRecord record = new InvokeRecord(callId, callerAgent, req.getCallerConversationId(),
                target.getId(), target.getName(), sessionId, mode, new ArrayList<>(chain));
        record.baselineMsgId = baselineMsgId;
        record.baselineTaskId = baselineTaskId;
        registerRecord(record);

        // 6. 构造 ChatRequest 并派发（后台订阅驱动执行，与 processConversationAsync 同模式；
        //    事件流丢弃——结果从 agent_task + conversation_message 读取）
        String message = buildInstructionMessage(req, target, chain);
        ChatRequest chat = new ChatRequest();
        chat.setMessage(message);
        chat.setSessionId(sessionId);
        chat.setAgentConfigId(target.getId());
        chat.setExecutionMode(target.getExecutionMode() != null ? target.getExecutionMode() : "auto");
        if (workDir != null && !workDir.isBlank()) {
            chat.setProjectRoot(workDir);
        }
        chat.setProviderCode(target.getProviderCode());
        chat.setUserId(req.getCallerUserId());
        chat.setTurnId(UUID.randomUUID().toString());
        dispatch(chat, record);

        // 7. await
        boolean await = req.getAwait() == null || req.getAwait();
        if (!await) {
            record.status = "INVOKED";
            AgentInvokeResult r = ok(record, target, null, null, start, buildImmediateMessage(record, target, start));
            log.info("[AgentInvoke] 已异步派发: callId={}, sessionId={}, target={}", callId, sessionId, target.getName());
            return r;
        }
        return awaitCompletion(record, target, baselineMsgId, scope,
                req.getTimeoutSec() != null ? req.getTimeoutSec() : DEFAULT_AWAIT_SEC, start);
    }

    // ==================== poll / cancel ====================

    /** 归属校验：记录存在且调用方为智能体会话时，仅允许操作本会话发起的委托（防跨会话窥探/干扰） */
    private boolean notOwnedBy(InvokeRecord record, Long callerConversationId) {
        return callerConversationId != null && record != null
                && record.callerConversationId != null
                && !callerConversationId.equals(record.callerConversationId);
    }

    @Override
    public AgentInvokeResult poll(String callId, Long sessionId, String scope, Long callerConversationId) {
        long start = System.currentTimeMillis();
        String effScope = scope != null ? scope : "summary";
        InvokeRecord record = resolveRecord(callId, sessionId);
        if (record == null) {
            return fail("ERROR", callId, sessionId, "❌ 找不到调用记录（callId/sessionId 均无法定位）", start);
        }
        if (notOwnedBy(record, callerConversationId)) {
            log.warn("[AgentInvoke] poll 归属校验失败: callId={}, sessionId={}, callerConversationId={}, record.callerConversationId={}",
                    callId, sessionId, callerConversationId, record.callerConversationId);
            return fail("NOT_ALLOWED", callId, sessionId,
                    "❌ 无权查询该委托任务（不属于当前会话发起），仅可 poll 本会话通过 agent_invoke 发起的调用", start);
        }
        AgentConfig target = agentConfigMapper.selectById(record.targetAgentConfigId).orElse(null);
        Long baseline = record.baselineMsgId != null ? record.baselineMsgId : 0L;
        Long baselineTaskId = record.baselineTaskId != null ? record.baselineTaskId : 0L;
        // 查询当前任务终态（只认本次调用之后创建的任务行，防读到 continue_session 历史旧行）
        Map<String, Object> row = queryTaskRowAfter(record.sessionId, baselineTaskId);
        if (row == null) {
            record.status = "PENDING";
            return ok(record, target, null, "PENDING", start,
                    "任务尚未开始（本次调用的任务记录尚未创建），请稍后重试");
        }
        String status = str(row.get("status"));
        if ("running".equals(status)) {
            record.status = "RUNNING";
            return ok(record, target, null, "RUNNING", start,
                    "⏳ 智能体 B「" + record.targetAgentName + "」任务仍在执行中（callId=" + record.callId + "），请稍后再 poll");
        }
        record.status = status.toUpperCase();
        return packResult(record, target, baseline, num(row.get("id")), str(row.get("error_message")), start, effScope);
    }

    @Override
    public AgentInvokeResult cancel(String callId, Long sessionId, Long callerConversationId) {
        long start = System.currentTimeMillis();
        InvokeRecord record = resolveRecord(callId, sessionId);
        if (record == null) {
            return fail("ERROR", callId, sessionId, "❌ 找不到调用记录（callId/sessionId 均无法定位）", start);
        }
        if (notOwnedBy(record, callerConversationId)) {
            log.warn("[AgentInvoke] cancel 归属校验失败: callId={}, sessionId={}, callerConversationId={}, record.callerConversationId={}",
                    callId, sessionId, callerConversationId, record.callerConversationId);
            return fail("NOT_ALLOWED", callId, sessionId,
                    "❌ 无权取消该委托任务（不属于当前会话发起），仅可 cancel 本会话通过 agent_invoke 发起的调用", start);
        }
        // 走现有会话级取消链路（置位取消标志 + 注销上下文，检查点自然终止）
        try {
            deepSeekService.cancelTask(record.sessionId);
        } catch (Exception e) {
            log.warn("[AgentInvoke] 取消任务异常: callId={}, sessionId={}, error={}", callId, record.sessionId, e.getMessage());
            return fail("ERROR", record.callId, record.sessionId, "❌ 取消失败，请查看服务端日志后重试", start);
        }
        record.status = "CANCELLED";
        AgentInvokeResult r = ok(record, null, null, null, start,
                "✅ 已请求取消智能体 B「" + record.targetAgentName + "」的任务（callId=" + record.callId
                        + ", sessionId=" + record.sessionId + "），取消检查点将终止工具循环");
        log.info("[AgentInvoke] 已取消委托任务: callId={}, sessionId={}, target={}", callId, record.sessionId, record.targetAgentName);
        return r;
    }

    @Override
    public int cancelByCallerConversation(Long callerConversationId) {
        if (callerConversationId == null) {
            return 0;
        }
        // 收集调用方会话名下的未终态委托（非终态：INVOKED/RUNNING/TIMEOUT/PENDING）
        List<String> toCancel = new ArrayList<>();
        for (InvokeRecord rec : records.values()) {
            if (callerConversationId.equals(rec.callerConversationId)
                    && !"COMPLETED".equals(rec.status) && !"FAILED".equals(rec.status)
                    && !"CANCELLED".equals(rec.status)) {
                toCancel.add(rec.callId);
            }
        }
        if (toCancel.isEmpty()) {
            return 0;
        }
        int count = 0;
        for (String callId : toCancel) {
            InvokeRecord rec = records.get(callId);
            if (rec == null) {
                continue;
            }
            try {
                // 走现有会话级取消链路（B 会话：置位取消标志 + 注销上下文，检查点自然终止）
                deepSeekService.cancelTask(rec.sessionId);
                rec.status = "CANCELLED";
                count++;
                log.info("[AgentInvoke] 级联取消委托任务（调用方会话已取消）: callId={}, callerConversationId={}, "
                                + "targetSessionId={}, target={}",
                        rec.callId, callerConversationId, rec.sessionId, rec.targetAgentName);
            } catch (Exception e) {
                log.warn("[AgentInvoke] 级联取消委托任务异常: callId={}, sessionId={}, err={}",
                        rec.callId, rec.sessionId, e.getMessage());
            }
        }
        log.info("[AgentInvoke] 级联取消完成: callerConversationId={}, cancelled={}", callerConversationId, count);
        return count;
    }

    // ==================== 内部：resolve / 校验 ====================

    /** 解析目标智能体：数字视为 id；否则按名称匹配（仅 enabled 配置，与 selectByUser 语义一致） */
    private AgentConfig resolveAgent(String targetAgent) {
        try {
            Long id = Long.parseLong(targetAgent.trim());
            return agentConfigMapper.selectById(id).orElse(null);
        } catch (NumberFormatException ignored) {
            // 名称匹配：user_id IS NULL（系统级）或任意用户？名称匹配范围过宽有歧义，
            // 限定为系统级（user_id IS NULL）+ 名称精确（忽略大小写）
            List<AgentConfig> candidates = agentConfigMapper.selectAllSystem();
            for (AgentConfig cfg : candidates) {
                if (targetAgent.trim().equalsIgnoreCase(cfg.getName())) {
                    return cfg;
                }
            }
            return null;
        }
    }

    /** create_session：新建归属调用者的会话（名字带 [A 委托] 前缀，透明可审计） */
    private Long createSessionFor(AgentInvokeRequest req, AgentConfig target, String workDir) {
        Conversation conv = new Conversation();
        conv.setName(buildSessionName(req));
        conv.setUserId(req.getCallerUserId());
        conv.setAgentType("agent_" + target.getId());
        conv.setAgentConfigId(target.getId());
        if (workDir != null && !workDir.isBlank()) {
            conv.setWorkDir(workDir);
        }
        LocalDateTime now = LocalDateTime.now();
        conv.setCreatedAt(now);
        conv.setUpdatedAt(now);
        conversationMapper.insert(conv);
        return conv.getId();
    }

    /**
     * continue_session：校验会话归属 B + 空闲。
     * 失败返回 null（调用方给出统一提示）；BUSY 时直接抛业务结果语义——为区分原因，内部返回码通过 record 状态旁路。
     */
    private Long resolveContinueSession(AgentInvokeRequest req, AgentConfig target, Long callerAgent) {
        Long sid = req.getSessionId();
        if (sid == null && req.getCallId() != null) {
            InvokeRecord rec = records.get(req.getCallId());
            if (rec != null) {
                sid = rec.sessionId;
            }
        }
        if (sid == null) {
            return null;
        }
        Conversation conv = conversationMapper.selectById(sid).orElse(null);
        if (conv == null) {
            log.warn("[AgentInvoke] continue_session 会话不存在: sessionId={}", sid);
            return null;
        }
        // 归属校验：会话必须是目标智能体 B 的
        if (conv.getAgentConfigId() == null || !conv.getAgentConfigId().equals(target.getId())) {
            log.warn("[AgentInvoke] continue_session 归属不匹配: sessionId={}, conv.agentConfigId={}, target={}",
                    sid, conv.getAgentConfigId(), target.getId());
            return null;
        }
        // busy 保护：目标会话有活跃任务则拒绝（不粗暴打断 B 正在干的事）
        if (deepSeekService.getActiveTask(sid) != null) {
            log.warn("[AgentInvoke] continue_session 目标会话正忙: sessionId={}, target={}", sid, target.getId());
            throw new BusySessionException(sid);
        }
        return sid;
    }

    // ==================== 内部：派发 / 等待 / 打包 ====================

    private void dispatch(ChatRequest chat, InvokeRecord record) {
        // 订阅驱动后台执行（事件丢弃；无订阅者时 sink replay buffer 有界，主动消费防堆积）
        if (record.callerAgentConfigId != null) {
            deepSeekService.streamChatInternal(chat, record.callerAgentConfigId,
                            record.callerConversationId, record.trustChain)
                    .subscribe(data -> {}, err -> log.warn("[AgentInvoke] 委托任务流异常: callId={}, err={}",
                            record.callId, err.getMessage()), () -> log.debug("[AgentInvoke] 委托任务流完成: callId={}", record.callId));
        } else {
            // 非智能体场景（系统级直派）：走普通 streamChat（B 按自身 executionMode 正常授权）
            deepSeekService.streamChat(chat)
                    .subscribe(data -> {}, err -> log.warn("[AgentInvoke] 直派任务流异常: sessionId={}, err={}",
                            chat.getSessionId(), err.getMessage()), () -> {});
        }
    }

    /** await：轮询 agent_task 至终态；只认「本次调用之后创建」的任务行（baselineTaskId 之后），
     *  防 continue_session 场景读到上一轮任务的终态旧行而误返回旧结果 */
    private AgentInvokeResult awaitCompletion(InvokeRecord record, AgentConfig target, Long baselineMsgId,
                                              String scope, int timeoutSec, long start) {
        int timeout = Math.max(1, Math.min(timeoutSec, MAX_AWAIT_SEC));
        long deadline = System.currentTimeMillis() + timeout * 1000L;
        long intervalMs = 500;
        int emptyRetry = 20; // 任务行尚未 INSERT 时的重试次数
        long baselineTaskId = record.baselineTaskId != null ? record.baselineTaskId : 0L;
        while (System.currentTimeMillis() < deadline) {
            Map<String, Object> row = queryTaskRowAfter(record.sessionId, baselineTaskId);
            if (row == null) {
                if (emptyRetry-- > 0) {
                    sleepQuietly(300);
                    continue;
                }
                return fail("ERROR", record.callId, record.sessionId,
                        "❌ 等待超时：任务记录未创建（B 会话启动失败？或 B 无工具纯文本任务无 agent_task 记录），可查看 B 会话或重试", start);
            }
            String status = str(row.get("status"));
            if ("running".equals(status)) {
                sleepQuietly(intervalMs);
                intervalMs = Math.min(intervalMs + 300, 2000);
                continue;
            }
            record.status = status.toUpperCase();
            AgentInvokeResult packed = packResult(record, target, baselineMsgId, num(row.get("id")),
                    str(row.get("error_message")), start, scope);
            // 审计：委托任务终态（completed/failed/cancelled）
            log.info("[AgentInvoke] 委托任务终态: callId={}, callerConversationId={}, targetSessionId={}, "
                            + "target={}, status={}, elapsedSec={}, code={}",
                    record.callId, record.callerConversationId, record.sessionId,
                    record.targetAgentName, record.status, packed.getElapsedSec(), packed.getCode());
            return packed;
        }
        // 超时：任务不销毁，后台继续跑；A 可 poll/cancel
        record.status = "TIMEOUT";
        AgentInvokeResult r = ok(record, target, null, null, start,
                "⏰ 等待超时（" + timeout + " 秒），B 的任务仍在后台执行。\n"
                        + "【继续查】agent_invoke action=poll call_id=" + record.callId + "\n"
                        + "【取消】agent_invoke action=cancel call_id=" + record.callId);
        r.setCode("TIMEOUT");
        return r;
    }

    /** 结果打包：状态 + 最终答复 + 工具轨迹摘要（注入消息 id 之后的范围） */
    private AgentInvokeResult packResult(InvokeRecord record, AgentConfig target, Long baselineMsgId,
                                         Long taskId, String errorMessage, long start, String scope) {
        boolean finalOnly = "final".equals(scope);
        boolean full = "full".equals(scope);
        String finalReply = queryFinalReply(record.sessionId, baselineMsgId);
        List<String> trace = full ? queryToolTraceFull(record.sessionId, baselineMsgId)
                : queryToolTraceSummary(record.sessionId, baselineMsgId);

        StringBuilder sb = new StringBuilder();
        String status = record.status != null ? record.status : "COMPLETED";
        if ("COMPLETED".equals(status)) {
            sb.append("✅ 智能体 B「").append(record.targetAgentName).append("」任务已完成\n");
        } else if ("FAILED".equals(status)) {
            sb.append("❌ 智能体 B「").append(record.targetAgentName).append("」任务失败\n");
        } else {
            sb.append("ℹ️ 智能体 B「").append(record.targetAgentName).append("」任务状态：").append(status).append("\n");
        }
        sb.append("【状态】").append(status)
                .append("（耗时 ").append((System.currentTimeMillis() - start) / 1000).append(" 秒）\n");
        sb.append("【模式】").append("create_session".equals(record.mode) ? "create_session（新建会话）" : "continue_session（续跑会话）").append("\n");
        sb.append("【B 会话 ID】").append(record.sessionId).append("\n");
        sb.append("【调用 ID】").append(record.callId).append("\n");
        if (taskId != null) {
            sb.append("【任务 ID】").append(taskId).append("\n");
        }
        if ("FAILED".equals(status) && errorMessage != null && !errorMessage.isBlank()) {
            sb.append("【失败原因】").append(errorMessage.length() > 500 ? errorMessage.substring(0, 500) : errorMessage).append("\n");
        }
        if (!finalOnly) {
            sb.append("──────────────────────────────\n");
        }
        if (finalReply != null && !finalReply.isBlank()) {
            sb.append("【最终答复】\n").append(finalReply).append("\n");
        } else if (!finalOnly) {
            sb.append("【最终答复】（无文本回复）\n");
        }
        if (!finalOnly && !trace.isEmpty()) {
            sb.append("【工具轨迹摘要】共 ").append(trace.size()).append(" 条\n");
            for (int i = 0; i < trace.size(); i++) {
                sb.append("  ").append(i + 1).append(". ").append(trace.get(i)).append("\n");
            }
            if (!full) {
                sb.append("（如需完整轨迹：agent_invoke action=poll call_id=").append(record.callId).append(" scope=full）\n");
            }
        }
        sb.append("【后续】继续让 B 干活：agent_invoke action=invoke mode=continue_session session_id=")
                .append(record.sessionId).append(" instructions=\"...\"");
        AgentInvokeResult r = ok(record, target, taskId, null, start, sb.toString());
        if (!"COMPLETED".equals(status)) {
            r.setCode(status.equals("FAILED") ? "FAILED" : "CANCELLED");
        }
        return r;
    }

    // ==================== 内部：消息/任务查询 ====================

    /** 查询指定会话在 baselineTaskId 之后创建的最新任务行（防读到历史旧行）；无新行返回 null */
    private Map<String, Object> queryTaskRowAfter(Long conversationId, Long baselineTaskId) {
        try {
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                    "SELECT id, status, error_message FROM agent_task WHERE conversation_id = ? AND id > ? "
                            + "ORDER BY id DESC LIMIT 1",
                    conversationId, baselineTaskId != null ? baselineTaskId : 0L);
            return rows.isEmpty() ? null : rows.get(0);
        } catch (Exception e) {
            log.warn("[AgentInvoke] 查询任务状态失败: conversationId={}, err={}", conversationId, e.getMessage());
            return null;
        }
    }

    /** 会话当前最大任务 id（await/poll 基线：只认本次调用之后的新行） */
    private Long maxTaskId(Long conversationId) {
        try {
            Long max = jdbcTemplate.queryForObject(
                    "SELECT COALESCE(MAX(id), 0) FROM agent_task WHERE conversation_id = ?",
                    Long.class, conversationId);
            return max != null ? max : 0L;
        } catch (Exception e) {
            return 0L;
        }
    }

    private Long maxMessageId(Long conversationId) {
        try {
            Long max = jdbcTemplate.queryForObject(
                    "SELECT COALESCE(MAX(id), 0) FROM conversation_message WHERE conversation_id = ?",
                    Long.class, conversationId);
            return max != null ? max : 0L;
        } catch (Exception e) {
            return 0L;
        }
    }

    /** 最终答复：注入点之后最后一条 assistant 消息（content 优先，reasoning 兜底——工具循环最终答复可能仅有 reasoning 落库）。
     *  ⚠️ role 列经 EnumTypeHandler 存储为枚举名（大写的 'ASSISTANT'），H2 比较大小写敏感，必须用大写匹配 */
    private String queryFinalReply(Long conversationId, Long baselineMsgId) {
        try {
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                    "SELECT content, reasoning FROM conversation_message WHERE conversation_id = ? AND id > ? "
                            + "AND role = 'ASSISTANT' AND (content IS NOT NULL AND content <> '' "
                            + "OR reasoning IS NOT NULL AND reasoning <> '') ORDER BY id DESC LIMIT 1",
                    conversationId, baselineMsgId);
            if (rows.isEmpty()) {
                return null;
            }
            Map<String, Object> row = rows.get(0);
            String content = (String) row.get("content");
            String reasoning = (String) row.get("reasoning");
            if (content != null && !content.isBlank()) {
                return content;
            }
            if (reasoning != null && !reasoning.isBlank()) {
                return reasoning;
            }
            return null;
        } catch (Exception e) {
            log.warn("[AgentInvoke] 查询最终答复失败: conversationId={}, err={}", conversationId, e.getMessage());
            return null;
        }
    }

    /** 工具轨迹摘要：每条截断 300 字符、最多 20 条（role 列存枚举名 'TOOL'，大写匹配） */
    private List<String> queryToolTraceSummary(Long conversationId, Long baselineMsgId) {
        List<String> result = new ArrayList<>();
        try {
            List<String> rows = jdbcTemplate.queryForList(
                    "SELECT reasoning FROM conversation_message WHERE conversation_id = ? AND id > ? "
                            + "AND role = 'TOOL' AND reasoning IS NOT NULL AND reasoning <> '' ORDER BY id ASC",
                    String.class, conversationId, baselineMsgId);
            for (String line : rows) {
                if (result.size() >= TRACE_MAX_LINES) {
                    result.add("…共 " + rows.size() + " 条工具调用，其余省略（scope=full 查看完整轨迹）");
                    break;
                }
                result.add(truncate(line, TRACE_PER_LINE_CHARS));
            }
        } catch (Exception e) {
            log.warn("[AgentInvoke] 查询工具轨迹失败: conversationId={}, err={}", conversationId, e.getMessage());
        }
        return result;
    }

    /** 工具轨迹全文（总长上限保护；role 列存枚举名 'TOOL'，大写匹配） */
    private List<String> queryToolTraceFull(Long conversationId, Long baselineMsgId) {
        List<String> result = new ArrayList<>();
        int total = 0;
        try {
            List<String> rows = jdbcTemplate.queryForList(
                    "SELECT reasoning FROM conversation_message WHERE conversation_id = ? AND id > ? "
                            + "AND role = 'TOOL' AND reasoning IS NOT NULL AND reasoning <> '' ORDER BY id ASC",
                    String.class, conversationId, baselineMsgId);
            for (String line : rows) {
                if (total >= TRACE_FULL_MAX_CHARS) {
                    result.add("…轨迹总长超限（" + TRACE_FULL_MAX_CHARS + " 字符），已截断");
                    break;
                }
                String t = line;
                if (t.length() > 3000) {
                    t = t.substring(0, 3000) + "…";
                }
                result.add(t);
                total += t.length();
            }
        } catch (Exception e) {
            log.warn("[AgentInvoke] 查询工具轨迹失败: conversationId={}, err={}", conversationId, e.getMessage());
        }
        return result;
    }

    // ==================== 内部：注册表 / 工具方法 ====================

    private void registerRecord(InvokeRecord record) {
        // 容量超限：只淘汰最旧「终态」记录（绝不清活跃委托——级联取消依赖注册表，
        // 误清会令运行中委托失去 poll/cancel/级联能力而成为孤儿 B 任务）
        if (records.size() >= RECORD_MAX_SIZE) {
            long now = System.currentTimeMillis();
            boolean removed = records.entrySet().removeIf(e -> {
                String s = e.getValue().status;
                return (s == null || "COMPLETED".equals(s) || "FAILED".equals(s)
                        || "CANCELLED".equals(s) || "TIMEOUT".equals(s))
                        && now - e.getValue().createdAt > RECORD_TTL_MS;
            });
            if (!removed) {
                // 全是活跃记录：删除最旧一条（注册表非正确性来源，会话 ID 持久化兜底）
                records.entrySet().stream()
                        .min(java.util.Comparator.comparingLong(e -> e.getValue().createdAt))
                        .ifPresent(e -> records.remove(e.getKey(), e.getValue()));
                log.warn("[AgentInvoke] 注册表超容量且无终态可淘汰（{}），已移除最旧记录", RECORD_MAX_SIZE);
            }
        }
        // 惰性清理过期终态记录（活跃记录不受 TTL 影响——30 分钟未跑完的任务仍需可取消）
        if (records.size() > 500) {
            long now = System.currentTimeMillis();
            records.entrySet().removeIf(e -> {
                String s = e.getValue().status;
                boolean terminal = s == null || "COMPLETED".equals(s) || "FAILED".equals(s)
                        || "CANCELLED".equals(s) || "TIMEOUT".equals(s);
                return terminal && now - e.getValue().createdAt > RECORD_TTL_MS;
            });
        }
        records.put(record.callId, record);
    }

    private InvokeRecord resolveRecord(String callId, Long sessionId) {
        if (callId != null) {
            InvokeRecord rec = records.get(callId);
            if (rec != null) {
                return rec;
            }
        }
        if (sessionId != null) {
            for (InvokeRecord rec : records.values()) {
                if (sessionId.equals(rec.sessionId)) {
                    return rec;
                }
            }
        }
        return null;
    }

    private String buildSessionName(AgentInvokeRequest req) {
        String raw = req.getInstructions().replaceAll("\\s+", " ").trim();
        String prefix = req.getCallerAgentName() != null && !req.getCallerAgentName().isBlank()
                ? "[" + req.getCallerAgentName() + " 委托] " : "[A 委托] ";
        return prefix + (raw.length() > 20 ? raw.substring(0, 20) : raw);
    }

    /** 溯源注记：B 的 user 消息自带「受托」语境，历史持久化后可审计 */
    private String buildInstructionMessage(AgentInvokeRequest req, AgentConfig target, List<Long> chain) {
        String note;
        if (req.getCallerAgentName() != null && !req.getCallerAgentName().isBlank()) {
            note = "（由智能体「" + req.getCallerAgentName() + "」委托执行" + (chain.isEmpty() ? "" : "，信任链 " + chain) + "。"
                    + "请以专业身份完成任务，并在最终答复中说明：做了什么、改了哪些文件、结果与注意事项。）";
        } else {
            note = "（系统委托任务。请在最终答复中说明：做了什么、改了哪些文件、结果与注意事项。）";
        }
        return note + "\n\n" + req.getInstructions().trim();
    }

    private String buildImmediateMessage(InvokeRecord record, AgentConfig target, long start) {
        return "✅ 已向智能体 B「" + record.targetAgentName + "」异步派发任务\n"
                + "【B 会话 ID】" + record.sessionId + "\n"
                + "【调用 ID】" + record.callId + "\n"
                + "【查结果】agent_invoke action=poll call_id=" + record.callId + "\n"
                + "【取消】agent_invoke action=cancel call_id=" + record.callId;
    }

    private AgentInvokeResult ok(InvokeRecord record, AgentConfig target, Long taskId,
                                 String code, long start, String message) {
        AgentInvokeResult r = new AgentInvokeResult();
        r.setCode(code != null ? code : "OK");
        r.setMessage(message);
        r.setCallId(record != null ? record.callId : null);
        r.setSessionId(record != null ? record.sessionId : null);
        r.setTaskId(taskId);
        r.setTargetAgentName(target != null ? target.getName() : (record != null ? record.targetAgentName : null));
        r.setElapsedSec((System.currentTimeMillis() - start) / 1000);
        return r;
    }

    private AgentInvokeResult fail(String code, String callId, Long sessionId, String message, long start) {
        AgentInvokeResult r = new AgentInvokeResult();
        r.setCode(code);
        r.setMessage(message);
        r.setCallId(callId);
        r.setSessionId(sessionId);
        r.setElapsedSec((System.currentTimeMillis() - start) / 1000);
        return r;
    }

    private void sleepQuietly(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private String truncate(String s, int max) {
        if (s == null) return "";
        // 压缩空白便于阅读
        String oneLine = s.replaceAll("\\s+", " ").trim();
        return oneLine.length() > max ? oneLine.substring(0, max) + "…" : oneLine;
    }

    private String str(Object o) {
        return o != null ? o.toString() : null;
    }

    private Long num(Object o) {
        return o != null ? ((Number) o).longValue() : null;
    }

    /** BUSY 会话异常（内部旁路 continue_session 的 busy 语义） */
    private static class BusySessionException extends RuntimeException {
        private final Long sessionId;

        BusySessionException(Long sessionId) {
            super("busy:" + sessionId);
            this.sessionId = sessionId;
        }

        Long getSessionId() {
            return sessionId;
        }
    }

    /** 调用记录（内存溯源） */
    static class InvokeRecord {
        final String callId;
        final Long callerAgentConfigId;
        final Long callerConversationId;
        final Long targetAgentConfigId;
        final String targetAgentName;
        final Long sessionId;
        final String mode;
        final List<Long> trustChain;
        final long createdAt;
        Long baselineMsgId;
        /** dispatch 前会话最大任务 id：await/poll 只认本次调用之后创建的任务行（防 continue_session 读旧行） */
        Long baselineTaskId;
        volatile String status = "INVOKED";

        InvokeRecord(String callId, Long callerAgentConfigId, Long callerConversationId,
                     Long targetAgentConfigId, String targetAgentName, Long sessionId,
                     String mode, List<Long> trustChain) {
            this.callId = callId;
            this.callerAgentConfigId = callerAgentConfigId;
            this.callerConversationId = callerConversationId;
            this.targetAgentConfigId = targetAgentConfigId;
            this.targetAgentName = targetAgentName;
            this.sessionId = sessionId;
            this.mode = mode;
            this.trustChain = trustChain;
            this.createdAt = System.currentTimeMillis();
        }
    }
}
