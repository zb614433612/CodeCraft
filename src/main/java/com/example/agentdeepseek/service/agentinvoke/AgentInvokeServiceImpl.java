package com.example.agentdeepseek.service.agentinvoke;

import com.example.agentdeepseek.mapper.AgentConfigMapper;
import com.example.agentdeepseek.mapper.ConversationMapper;
import com.example.agentdeepseek.model.dto.AgentInvokeRequest;
import com.example.agentdeepseek.model.dto.AgentInvokeResult;
import com.example.agentdeepseek.model.dto.ChatRequest;
import com.example.agentdeepseek.model.entity.AgentConfig;
import com.example.agentdeepseek.model.entity.Conversation;
import com.example.agentdeepseek.service.DeepSeekService;
import com.example.agentdeepseek.service.impl.AgentEventBus;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
    /** 批量等待（wait）默认秒数（上限见 AgentInvokeService.MAX_BATCH_WAIT_SEC） */
    private static final int DEFAULT_BATCH_WAIT_SEC = 120;
    /** 委托进度心跳间隔（毫秒；await 阻塞期间向 A 会话推送，前端委托卡片展示已等待时长） */
    private static final long HEARTBEAT_INTERVAL_MS = 5000;
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
    /** 委托进度心跳通道（2026-09-21：await 阻塞期间向 A 会话发 delegate_progress 事件，供前端卡片进度展示） */
    private final AgentEventBus agentEventBus;
    private final ObjectMapper objectMapper;

    /** 调用注册表：callId → InvokeRecord（内存溯源，重启丢失可接受——会话 ID 本身持久化） */
    private final ConcurrentHashMap<String, InvokeRecord> records = new ConcurrentHashMap<>();

    public AgentInvokeServiceImpl(AgentConfigMapper agentConfigMapper,
                                  ConversationMapper conversationMapper,
                                  DeepSeekService deepSeekService,
                                  JdbcTemplate jdbcTemplate,
                                  AgentEventBus agentEventBus,
                                  ObjectMapper objectMapper) {
        this.agentConfigMapper = agentConfigMapper;
        this.conversationMapper = conversationMapper;
        this.deepSeekService = deepSeekService;
        this.jdbcTemplate = jdbcTemplate;
        this.agentEventBus = agentEventBus;
        this.objectMapper = objectMapper;
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
        InvokeRecord record = new InvokeRecord(callId, callerAgent, req.getCallerConversationId(), req.getCallerTaskId(),
                target.getId(), target.getName(), sessionId, mode, new ArrayList<>(chain));
        record.baselineMsgId = baselineMsgId;
        record.baselineTaskId = baselineTaskId;
        registerRecord(record);

        // 6. 构造 ChatRequest 并派发（后台订阅驱动执行，与 processConversationAsync 同模式；
        //    事件流丢弃——结果从 agent_task + conversation_message 读取；注记携带工作目录，避免 B 找错目录产生迷茫）
        String message = buildInstructionMessage(req, target, chain, workDir);
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
    public AgentInvokeResult poll(String callId, Long sessionId, String scope, int waitSec, Long callerConversationId) {
        long start = System.currentTimeMillis();
        String effScope = scope != null ? scope : "summary";
        int wait = Math.max(0, Math.min(waitSec, MAX_POLL_WAIT_SEC));
        InvokeRecord record = resolveRecord(callId, sessionId, callerConversationId);
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
        if (wait <= 0) {
            return pollOnce(record, target, effScope, start);
        }
        // wait_sec>0：短暂等待——期间任务完成立即返回完整结果；到点返回最新进度快照（查 → 等 1s → 再查）
        long deadline = System.currentTimeMillis() + wait * 1000L;
        while (true) {
            AgentInvokeResult current = pollOnce(record, target, effScope, start);
            String code = current.getCode();
            if (!"RUNNING".equals(code) && !"PENDING".equals(code)) {
                return current; // 终态（COMPLETED/FAILED/CANCELLED）或异常语义：立即返回
            }
            long remain = deadline - System.currentTimeMillis();
            if (remain <= 0) {
                return current; // 到点：返回最新进度快照（而非空转）
            }
            sleepQuietly(Math.min(1000, remain));
        }
    }

    /** 单次查询（wait 循环与立即查询共用）：终态 → 结果打包；running → 进度快照；任务行未创建 → PENDING */
    private AgentInvokeResult pollOnce(InvokeRecord record, AgentConfig target, String effScope, long start) {
        Long baseline = record.baselineMsgId != null ? record.baselineMsgId : 0L;
        Long baselineTaskId = record.baselineTaskId != null ? record.baselineTaskId : 0L;
        // 查询当前任务行（只认本次调用之后创建的任务行，防读到 continue_session 历史旧行）
        Map<String, Object> row = queryTaskRowAfter(record.sessionId, baselineTaskId);
        if (row == null) {
            record.status = "PENDING";
            return ok(record, target, null, "PENDING", start,
                    "任务尚未开始（本次调用的任务记录尚未创建），请稍后重试"
                            + (record.degraded ? "\n（注意：内存调用记录缺失，按会话最新任务近似查询）" : ""));
        }
        String status = str(row.get("status"));
        if ("running".equals(status)) {
            record.status = "RUNNING";
            return ok(record, target, num(row.get("id")), "RUNNING", start,
                    buildRunningProgress(record, row, baseline));
        }
        record.status = status.toUpperCase();
        return packResult(record, target, baseline, num(row.get("id")), str(row.get("error_message")), start, effScope);
    }

    // ==================== 批量等待（wait） ====================

    @Override
    public AgentInvokeResult waitBatch(List<String> callIds, String mode, int timeoutSec, String scope, Long callerConversationId) {
        long start = System.currentTimeMillis();
        if (callIds == null || callIds.isEmpty()) {
            return fail("ERROR", null, null, "【参数错误】wait 需要至少一个 callId（invoke 返回的调用 ID）", start);
        }
        String effScope = scope != null ? scope : "summary";
        boolean anyMode = "any".equalsIgnoreCase(mode != null ? mode.trim() : "");
        int timeout = Math.max(1, Math.min(timeoutSec > 0 ? timeoutSec : DEFAULT_BATCH_WAIT_SEC, MAX_BATCH_WAIT_SEC));
        // 解析批次：逐条定位 + 归属校验；失败项记录原因，不阻断整批
        List<InvokeRecord> batch = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        for (String cid : callIds) {
            if (cid == null || cid.isBlank()) {
                continue;
            }
            InvokeRecord rec = resolveRecord(cid.trim(), null, callerConversationId);
            if (rec == null) {
                errors.add("• " + cid.trim() + "：找不到调用记录（callId 无效或记录已过期；可改用 session_id poll）");
                continue;
            }
            if (notOwnedBy(rec, callerConversationId)) {
                errors.add("• " + cid.trim() + "：无权查询（不属于当前会话发起）");
                continue;
            }
            batch.add(rec);
        }
        if (batch.isEmpty()) {
            return fail("ERROR", null, null,
                    "❌ 批次中没有可等待的委托：\n" + String.join("\n", errors), start);
        }
        // 轮询等待：all=全部终态 / any=任一终态；到点返回最新状态快照
        long deadline = System.currentTimeMillis() + timeout * 1000L;
        while (true) {
            int terminal = 0;
            for (InvokeRecord rec : batch) {
                if (refreshTerminal(rec)) {
                    terminal++;
                }
            }
            boolean satisfied = anyMode ? terminal >= 1 : terminal == batch.size();
            if (satisfied || System.currentTimeMillis() >= deadline) {
                break;
            }
            sleepQuietly(1000);
        }
        return buildBatchSummary(batch, errors, anyMode, timeout, effScope, start);
    }

    /** 刷新记录状态：查任务行并同步 rec.status；返回「是否已到终态」（任务行未创建=PENDING 不算终态） */
    private boolean refreshTerminal(InvokeRecord rec) {
        Long baselineTaskId = rec.baselineTaskId != null ? rec.baselineTaskId : 0L;
        Map<String, Object> row = queryTaskRowAfter(rec.sessionId, baselineTaskId);
        if (row == null) {
            rec.status = "PENDING";
            return false;
        }
        String status = str(row.get("status"));
        if (status == null) {
            return false;
        }
        rec.status = "running".equals(status) ? "RUNNING" : status.toUpperCase();
        return !"RUNNING".equals(rec.status) && !"PENDING".equals(rec.status);
    }

    /** 批量等待汇总：每个委托一节（终态=状态+最终答复截断；运行中=进度快照）；附解析失败项 */
    private AgentInvokeResult buildBatchSummary(List<InvokeRecord> batch, List<String> errors,
                                                boolean anyMode, int timeout, String effScope, long start) {
        StringBuilder sb = new StringBuilder();
        sb.append("⏱ 批量等待结果（").append(anyMode ? "任一完成即返回" : "全部完成")
                .append("，上限 ").append(timeout).append(" 秒）：\n");
        int done = 0;
        for (int i = 0; i < batch.size(); i++) {
            InvokeRecord rec = batch.get(i);
            Long baseline = rec.baselineMsgId != null ? rec.baselineMsgId : 0L;
            Long baselineTaskId = rec.baselineTaskId != null ? rec.baselineTaskId : 0L;
            Map<String, Object> row = queryTaskRowAfter(rec.sessionId, baselineTaskId);
            sb.append("──────────\n");
            sb.append(i + 1).append(". ");
            if (row == null) {
                sb.append("🕐 智能体 B「").append(rec.targetAgentName).append("」：任务尚未开始（callId=").append(rec.callId).append("）\n");
                continue;
            }
            String status = str(row.get("status"));
            Long taskId = num(row.get("id"));
            if ("running".equals(status)) {
                rec.status = "RUNNING";
                sb.append("⏳ 智能体 B「").append(rec.targetAgentName).append("」：仍在执行中 · 已运行 ")
                        .append(formatElapsed(elapsedSinceRow(row.get("created_at"), rec.createdAt)));
                sb.append("（callId=").append(rec.callId).append("）\n");
                String action = queryLatestToolAction(rec.sessionId, baseline);
                if (action != null && !action.isBlank()) {
                    sb.append("   最近动作：").append(truncate(action, 120)).append("\n");
                }
            } else {
                done++;
                String up = status.toUpperCase();
                rec.status = up;
                String icon = "COMPLETED".equals(up) ? "✅" : ("FAILED".equals(up) ? "❌" : "ℹ️");
                sb.append(icon).append(" 智能体 B「").append(rec.targetAgentName).append("」：").append(up);
                if (taskId != null) {
                    sb.append("（任务 ").append(taskId).append("）");
                }
                sb.append("（callId=").append(rec.callId).append("）\n");
                String errMsg = str(row.get("error_message"));
                if ("FAILED".equals(up) && errMsg != null && !errMsg.isBlank()) {
                    sb.append("   失败原因：").append(truncate(errMsg, 300)).append("\n");
                }
                String finalReply = queryFinalReply(rec.sessionId, baseline);
                if (finalReply != null && !finalReply.isBlank()) {
                    int cap = "full".equals(effScope) ? 2000 : ("final".equals(effScope) ? 300 : 500);
                    sb.append("   最终答复：").append(truncate(finalReply, cap)).append("\n");
                }
            }
        }
        sb.append("──────────\n");
        sb.append("【汇总】共 ").append(batch.size()).append(" 个委托，终态 ").append(done).append(" 个");
        if (done < batch.size()) {
            sb.append("（其余仍在执行，可再次 wait 或 poll）");
        }
        if (!errors.isEmpty()) {
            sb.append("\n【未纳入】\n").append(String.join("\n", errors));
        }
        sb.append("\n【详情】单条完整结果：agent_invoke action=poll call_id=<ID> scope=full");
        return ok(null, null, null, null, start, sb.toString());
    }

    @Override
    public AgentInvokeResult cancel(String callId, Long sessionId, Long callerConversationId) {
        long start = System.currentTimeMillis();
        InvokeRecord record = resolveRecord(callId, sessionId, callerConversationId);
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
                        + ", sessionId=" + record.sessionId + "），取消检查点将终止工具循环"
                        + (record.degraded ? "\n（注意：内存调用记录缺失，按会话直查降级定位）" : ""));
        log.info("[AgentInvoke] 已取消委托任务: callId={}, sessionId={}, target={}", callId, record.sessionId, record.targetAgentName);
        return r;
    }

    @Override
    public int cancelByCallerConversation(Long callerConversationId, Set<Long> cancelledTaskIds) {
        if (callerConversationId == null || cancelledTaskIds == null || cancelledTaskIds.isEmpty()) {
            return 0;
        }
        // 收集调用方会话名下的未终态委托（非终态：INVOKED/RUNNING/TIMEOUT/PENDING），且「委托发起自本轮被取消的任务」——
        // 任务级精确（2026-09-21）：① A 的普通新消息（无活跃任务→集合为空）不牵连任何委托；
        // ② A 的后续任务被打断时，不误杀其更早回合发起的独立后台委托（await=false / 超时后仍在跑的委托）
        List<String> toCancel = new ArrayList<>();
        for (InvokeRecord rec : records.values()) {
            if (!callerConversationId.equals(rec.callerConversationId)) {
                continue;
            }
            if ("COMPLETED".equals(rec.status) || "FAILED".equals(rec.status) || "CANCELLED".equals(rec.status)) {
                continue;
            }
            if (rec.callerTaskId == null || !cancelledTaskIds.contains(rec.callerTaskId)) {
                log.debug("[AgentInvoke] 级联取消跳过（委托非本轮被取消任务发起）: callId={}, callerTaskId={}, cancelledTaskIds={}",
                        rec.callId, rec.callerTaskId, cancelledTaskIds);
                continue;
            }
            toCancel.add(rec.callId);
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
                // 2026-09-21 加固：仅当 B 会话当前确有活跃任务时才需要取消（已完成/未启动的任务无需取消，
                // 避免无谓的 cancelTask 链路副作用——如注销 B 会话事件总线）；
                // 已知限制：会话粒度判断，若 B 会话当前运行的是其他来源的任务（用户手动/他方委托）无法区分
                if (deepSeekService.getActiveTask(rec.sessionId) == null) {
                    log.debug("[AgentInvoke] 级联取消跳过（B 会话无活跃任务）: callId={}, sessionId={}",
                            rec.callId, rec.sessionId);
                    continue;
                }
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
     *  防 continue_session 场景读到上一轮任务的终态旧行而误返回旧结果。
     *  阻塞期间每 ~5s 向 A 会话发进度心跳（前端委托卡片展示「已等待时长」并可跳转 B 会话）。 */
    private AgentInvokeResult awaitCompletion(InvokeRecord record, AgentConfig target, Long baselineMsgId,
                                              String scope, int timeoutSec, long start) {
        int timeout = Math.max(1, Math.min(timeoutSec, MAX_AWAIT_SEC));
        long deadline = System.currentTimeMillis() + timeout * 1000L;
        long intervalMs = 500;
        int emptyRetry = 20; // 任务行尚未 INSERT 时的重试次数
        long baselineTaskId = record.baselineTaskId != null ? record.baselineTaskId : 0L;
        // 委托进度心跳（2026-09-21）：进入等待立即发一次（前端尽早拿到 B 会话信息），之后每 ~5s 一次
        long lastHeartbeat = emitDelegateProgress(record, (System.currentTimeMillis() - start) / 1000);
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
                if (System.currentTimeMillis() - lastHeartbeat >= HEARTBEAT_INTERVAL_MS) {
                    lastHeartbeat = emitDelegateProgress(record, (System.currentTimeMillis() - start) / 1000);
                }
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
        // 超时：任务不销毁，后台继续跑；A 可 poll（可 wait_sec 短暂等待）/ cancel；超时 ≠ 失败，勿重复委托
        record.status = "TIMEOUT";
        AgentInvokeResult r = ok(record, target, null, null, start,
                "⏰ 等待超时（" + timeout + " 秒），B 的任务仍在后台执行（超时≠失败，请勿重复委托）。\n"
                        + "【继续查】agent_invoke action=poll call_id=" + record.callId + " wait_sec=60（等待最多 60 秒，完成立即返回）\n"
                        + "【取消】agent_invoke action=cancel call_id=" + record.callId);
        r.setCode("TIMEOUT");
        return r;
    }

    /**
     * 委托进度心跳（2026-09-21）：await 阻塞期间向 A 会话发 delegate_progress 事件——
     * A 会话前端据此把委托工具卡更新为「已委托「B」· 已等待 N 秒」并提供「前往 B 会话」入口。
     * 无事件通道（A 会话无 sink）时静默忽略。
     * ⚠️ payload 用 targetSessionId 字段名——前端 SseParser 会把 sessionId 认作「当前会话 ID」，不可占用。
     *
     * @return 本次发送时间戳（毫秒；供节流判断）
     */
    private long emitDelegateProgress(InvokeRecord record, long elapsedSec) {
        long now = System.currentTimeMillis();
        if (record.callerConversationId == null) {
            return now;
        }
        try {
            ObjectNode event = objectMapper.createObjectNode();
            event.put("event", "delegate_progress");
            event.put("callId", record.callId);
            event.put("targetSessionId", record.sessionId);
            event.put("targetAgent", record.targetAgentName);
            event.put("status", "RUNNING");
            event.put("elapsedSec", elapsedSec);
            agentEventBus.emit(record.callerConversationId, objectMapper.writeValueAsString(event));
        } catch (Exception e) {
            log.debug("[AgentInvoke] 发送委托进度心跳失败: callId={}, err={}", record.callId, e.getMessage());
        }
        return now;
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
        if (record.degraded) {
            sb.append("【注意】内存调用记录缺失，按会话最新任务/最近委托消息近似查询——多任务历史时请核对\n");
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

    /** 查询指定会话在 baselineTaskId 之后创建的最新任务行（防读到历史旧行）；无新行返回 null。
     *  created_at / event_count 供「运行中进度快照」使用 */
    private Map<String, Object> queryTaskRowAfter(Long conversationId, Long baselineTaskId) {
        try {
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                    "SELECT id, status, error_message, created_at, event_count FROM agent_task "
                            + "WHERE conversation_id = ? AND id > ? ORDER BY id DESC LIMIT 1",
                    conversationId, baselineTaskId != null ? baselineTaskId : 0L);
            return rows.isEmpty() ? null : rows.get(0);
        } catch (Exception e) {
            log.warn("[AgentInvoke] 查询任务状态失败: conversationId={}, err={}", conversationId, e.getMessage());
            return null;
        }
    }

    /** 运行中进度快照：已运行时长 + 事件数 + 最近动作 + 等待建议（让 A 的查询有信息量，而非「仍在执行中」空转） */
    private String buildRunningProgress(InvokeRecord record, Map<String, Object> row, Long baselineMsgId) {
        StringBuilder sb = new StringBuilder();
        sb.append("⏳ 智能体 B「").append(record.targetAgentName).append("」任务仍在执行中\n");
        sb.append("【调用 ID】").append(record.callId).append("\n");
        sb.append("【B 会话 ID】").append(record.sessionId).append("\n");
        Long taskId = num(row.get("id"));
        sb.append("【进度】已运行 ").append(formatElapsed(elapsedSinceRow(row.get("created_at"), record.createdAt)));
        if (taskId != null) {
            sb.append("（任务 ").append(taskId).append("）");
        }
        Object eventCount = row.get("event_count");
        if (eventCount != null) {
            sb.append(" · 事件数 ").append(eventCount);
        }
        sb.append("\n");
        String action = queryLatestToolAction(record.sessionId, baselineMsgId);
        if (action != null && !action.isBlank()) {
            sb.append("【最近动作】").append(truncate(action, 150)).append("\n");
        }
        sb.append("【建议】稍后再 poll；或 poll wait_sec=60 等待（任务完成会立即返回，无需空转轮询）");
        if (record.degraded) {
            sb.append("\n【注意】内存调用记录缺失，按会话最新任务/最近委托消息近似查询——多任务历史时请核对");
        }
        return sb.toString();
    }

    /** 距任务行创建时间的已运行秒数（created_at 不可解析时回退到内存记录创建时间） */
    private long elapsedSinceRow(Object createdAt, long fallbackStartMs) {
        try {
            if (createdAt instanceof java.sql.Timestamp ts) {
                return Math.max(0, (System.currentTimeMillis() - ts.getTime()) / 1000);
            }
            if (createdAt instanceof java.time.LocalDateTime ldt) {
                return Math.max(0, java.time.Duration.between(ldt, java.time.LocalDateTime.now()).getSeconds());
            }
        } catch (Exception ignored) {
        }
        return Math.max(0, (System.currentTimeMillis() - fallbackStartMs) / 1000);
    }

    /** 秒数友好格式化（90 → 「1 分 30 秒」） */
    private String formatElapsed(long sec) {
        if (sec < 60) return sec + " 秒";
        if (sec < 3600) return (sec / 60) + " 分 " + (sec % 60) + " 秒";
        return (sec / 3600) + " 小时 " + ((sec % 3600) / 60) + " 分";
    }

    /** 最近一条 TOOL 消息（截断由调用方处理；无则 null）——B 的「当前在做什么」 */
    private String queryLatestToolAction(Long conversationId, Long baselineMsgId) {
        try {
            List<String> rows = jdbcTemplate.queryForList(
                    "SELECT reasoning FROM conversation_message WHERE conversation_id = ? AND id > ? "
                            + "AND role = 'TOOL' AND reasoning IS NOT NULL AND reasoning <> '' ORDER BY id DESC LIMIT 1",
                    String.class, conversationId, baselineMsgId != null ? baselineMsgId : 0L);
            return rows.isEmpty() ? null : rows.get(0);
        } catch (Exception e) {
            log.warn("[AgentInvoke] 查询最近工具动作失败: conversationId={}, err={}", conversationId, e.getMessage());
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

    /**
     * 定位调用记录：内存注册表优先；未命中且给了 sessionId 时按 B 会话直查 DB 降级构建
     * （2026-09-21：兑现设计文档 §3.3「进程重启/注册表丢失后仍可按 session_id 操作」承诺）。
     *
     * @param callerConversationId 调用方（A）会话 ID（降级构建的归属校验依据）
     */
    private InvokeRecord resolveRecord(String callId, Long sessionId, Long callerConversationId) {
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
            // 内存未命中：降级直查（会话 ID 持久化，不依赖注册表）
            return buildDegradedRecord(sessionId, callerConversationId);
        }
        return null;
    }

    /**
     * 降级构建调用记录（内存未命中时）：按 B 会话直查 DB 恢复查询/取消能力。
     * <p>近似项：baselineMsgId=最后一条 USER 消息（委托指令边界）；baselineTaskId=0（取会话最新任务行）——
     * 结果中带 degraded 提示，多任务历史会话时建议核对。归属校验：B 会话与调用方会话 userId 一致
     * （均为空视为系统级放行；调用方不可解析时拒绝）。</p>
     */
    private InvokeRecord buildDegradedRecord(Long sessionId, Long callerConversationId) {
        try {
            Conversation conv = conversationMapper.selectById(sessionId).orElse(null);
            if (conv == null || conv.getAgentConfigId() == null) {
                return null; // 非「智能体会话」（非委托目标）或已删除
            }
            if (callerConversationId == null) {
                return null; // 无调用方上下文：无从校验归属
            }
            Conversation callerConv = conversationMapper.selectById(callerConversationId).orElse(null);
            if (callerConv == null) {
                return null;
            }
            if (conv.getUserId() != null && callerConv.getUserId() != null
                    && !conv.getUserId().equals(callerConv.getUserId())) {
                log.warn("[AgentInvoke] 降级查询归属校验失败: sessionId={}, conv.userId={}, caller.userId={}",
                        sessionId, conv.getUserId(), callerConv.getUserId());
                return null;
            }
            AgentConfig target = agentConfigMapper.selectById(conv.getAgentConfigId()).orElse(null);
            String targetName = (target != null && target.getName() != null && !target.getName().isBlank())
                    ? target.getName() : ("Agent#" + conv.getAgentConfigId());
            InvokeRecord record = new InvokeRecord("degraded-" + sessionId, null, callerConversationId, null,
                    conv.getAgentConfigId(), targetName, sessionId, "unknown", List.of());
            record.degraded = true;
            record.baselineMsgId = maxUserMessageId(sessionId); // 近似：最后一条 user 消息（委托指令）
            record.baselineTaskId = 0L;                         // 近似：会话最新任务行
            log.info("[AgentInvoke] 内存记录未命中，按会话降级查询: sessionId={}, callerConversationId={}, target={}",
                    sessionId, callerConversationId, targetName);
            return record;
        } catch (Exception e) {
            log.warn("[AgentInvoke] 降级构建调用记录失败: sessionId={}, err={}", sessionId, e.getMessage());
            return null;
        }
    }

    /** 会话最后一条 USER 消息 id（降级模式的近似委托边界；UPPER 兼容枚举名大小写） */
    private Long maxUserMessageId(Long conversationId) {
        try {
            Long max = jdbcTemplate.queryForObject(
                    "SELECT COALESCE(MAX(id), 0) FROM conversation_message WHERE conversation_id = ? AND UPPER(role) = 'USER'",
                    Long.class, conversationId);
            return max != null ? max : 0L;
        } catch (Exception e) {
            return 0L;
        }
    }

    private String buildSessionName(AgentInvokeRequest req) {
        String raw = req.getInstructions().replaceAll("\\s+", " ").trim();
        String prefix = req.getCallerAgentName() != null && !req.getCallerAgentName().isBlank()
                ? "[" + req.getCallerAgentName() + " 委托] " : "[A 委托] ";
        return prefix + (raw.length() > 20 ? raw.substring(0, 20) : raw);
    }

    /** 溯源注记：B 的 user 消息自带「受托」语境与工作目录信息（2026-09-18 增强），历史持久化后可审计 */
    private String buildInstructionMessage(AgentInvokeRequest req, AgentConfig target, List<Long> chain, String workDir) {
        // 工作目录告知（2026-09-18）：B 默认按自己的 workDir 找文件易迷失，注记显式告知本会话实际生效的工作目录
        String dirPart = (workDir != null && !workDir.isBlank()) ? "，工作目录：" + workDir : "";
        String note;
        if (req.getCallerAgentName() != null && !req.getCallerAgentName().isBlank()) {
            note = "（由智能体「" + req.getCallerAgentName() + "」委托执行" + (chain.isEmpty() ? "" : "，信任链 " + chain) + dirPart + "。"
                    + "请以专业身份完成任务，并在最终答复中说明：做了什么、改了哪些文件、结果与注意事项。）";
        } else {
            note = "（系统委托任务" + dirPart + "。请在最终答复中说明：做了什么、改了哪些文件、结果与注意事项。）";
        }
        return note + "\n\n" + req.getInstructions().trim();
    }

    private String buildImmediateMessage(InvokeRecord record, AgentConfig target, long start) {
        return "✅ 已向智能体 B「" + record.targetAgentName + "」异步派发任务（任务在后台执行，你可继续其他工作）\n"
                + "【B 会话 ID】" + record.sessionId + "\n"
                + "【调用 ID】" + record.callId + "\n"
                + "【查进度/结果】agent_invoke action=poll call_id=" + record.callId + "（可加 wait_sec=60 短暂等待，完成立即返回）\n"
                + "【批量等待】多个委托就绪后：agent_invoke action=wait call_ids=[...] wait_mode=all\n"
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
        /** 委托发起自哪个任务（agent_task.id；2026-09-21 任务级精确级联取消依据；null=未知则不级联） */
        final Long callerTaskId;
        final Long targetAgentConfigId;
        final String targetAgentName;
        final Long sessionId;
        final String mode;
        final List<Long> trustChain;
        final long createdAt;
        Long baselineMsgId;
        /** dispatch 前会话最大任务 id：await/poll 只认本次调用之后创建的任务行（防 continue_session 读旧行） */
        Long baselineTaskId;
        /** 降级记录标记（2026-09-21）：内存注册表缺失时按 session_id 直查 DB 构建，结果中附加提示 */
        boolean degraded = false;
        volatile String status = "INVOKED";

        InvokeRecord(String callId, Long callerAgentConfigId, Long callerConversationId, Long callerTaskId,
                     Long targetAgentConfigId, String targetAgentName, Long sessionId,
                     String mode, List<Long> trustChain) {
            this.callId = callId;
            this.callerAgentConfigId = callerAgentConfigId;
            this.callerConversationId = callerConversationId;
            this.callerTaskId = callerTaskId;
            this.targetAgentConfigId = targetAgentConfigId;
            this.targetAgentName = targetAgentName;
            this.sessionId = sessionId;
            this.mode = mode;
            this.trustChain = trustChain;
            this.createdAt = System.currentTimeMillis();
        }
    }
}
