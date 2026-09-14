package com.example.agentdeepseek.tool.impl;

import com.example.agentdeepseek.mapper.AgentConfigMapper;
import com.example.agentdeepseek.model.dto.AgentInvokeRequest;
import com.example.agentdeepseek.model.dto.AgentInvokeResult;
import com.example.agentdeepseek.model.entity.AgentConfig;
import com.example.agentdeepseek.service.agentinvoke.AgentInvokeService;
import com.example.agentdeepseek.tool.Tool;
import com.example.agentdeepseek.tool.permission.OperationCategory;
import com.example.agentdeepseek.tool.permission.ToolPermission;
import com.example.agentdeepseek.util.ToolContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

/**
 * 智能体互相调用工具（Phase 19 P2）
 * <p>
 * Agent A（当前正在执行工具的智能体）委托 Agent B（agent_config 实例）执行任务：
 * create_session（为 B 新建会话）/ continue_session（按会话 ID 继续 B 任务）。
 * B 执行期间免用户授权——本工具 manual 模式弹窗即用户显式「委托凭证」
 * （affectsData=true → manual 弹窗；highRisk=false → auto 模式随 A 语境放行）。
 * </p>
 * <p>
 * 与 AgentForkManager（agent 工具 / 子 Agent）完全无关：本工具调用的是
 * {@link AgentInvokeService}，B 以独立智能体身份（自己的角色/模型/工作目录/会话）执行。
 * </p>
 */
@Slf4j
@Component
@ToolPermission(category = OperationCategory.ADMIN, affectsData = true,
        description = "智能体互相调用（委托其他智能体执行任务；manual 模式批准即委托授权凭证）")
public class AgentInvokeTool implements Tool {

    private final ObjectMapper objectMapper;
    private final AgentInvokeService agentInvokeService;
    private final AgentConfigMapper agentConfigMapper;

    public AgentInvokeTool(ObjectMapper objectMapper,
                           @Lazy AgentInvokeService agentInvokeService,
                           AgentConfigMapper agentConfigMapper) {
        this.objectMapper = objectMapper;
        this.agentInvokeService = agentInvokeService;
        this.agentConfigMapper = agentConfigMapper;
    }

    @Override
    public String getName() { return "agent_invoke"; }

    @Override
    public String getDescription() {
        return "【适用场景】智能体互相调用（Phase 19）：当前智能体 A 委托另一个智能体 B（agent_config 实例）执行任务。\n"
                + "B 以自身身份（角色/模型/工作目录/工具集）在独立会话中执行，执行期间免用户授权（委托即授权）。\n"
                + "【action 说明】\n"
                + "  invoke — 发起调用。mode=create_session（为 B 新建会话）| continue_session（按 session_id 继续 B 既有会话）\n"
                + "  poll   — 查询调用结果（异步模式/超时追查/任意时刻状态），scope=summary|full|final\n"
                + "  cancel — 取消 B 侧任务\n"
                + "【典型工作流】invoke（await=true 同步等待完成）→ 基于结果继续；"
                + "或 invoke await=false → 稍后 poll 查询\n"
                + "【注意事项】\n"
                + "  1. invoke/poll 返回的【最终答复】【工具轨迹摘要】是 B 的真实产出，禁止编造 B 的结果\n"
                + "  2. 返回的【B 会话 ID】用于后续继续让 B 干活：agent_invoke action=invoke mode=continue_session session_id=<B会话ID>\n"
                + "  3. 仅当任务需要其他智能体的专业能力/独立工作目录时才委托；能自己干的小活不要委托\n"
                + "  4. manual 模式下本工具需用户批准一次（= 委托授权）；B 执行期间不再弹窗\n"
                + "  5. 禁止调用自己；信任链最深 3 层（A→B→C），环调用会被拒绝";
    }

    @Override
    public JsonNode getParameters() {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("type", "object");
        ObjectNode props = root.putObject("properties");

        // === action ===
        ObjectNode action = props.putObject("action");
        action.put("type", "string");
        action.put("description", "【必填】invoke=发起调用；poll=查询结果；cancel=取消 B 侧任务");
        ArrayNode actionEnum = action.putArray("enum");
        actionEnum.add("invoke").add("poll").add("cancel");

        // === invoke 参数 ===
        ObjectNode targetAgent = props.putObject("target_agent");
        targetAgent.put("type", "string");
        targetAgent.put("description", "【invoke 必填】目标智能体 B：agent_config_id（数字）或名称");

        ObjectNode mode = props.putObject("mode");
        mode.put("type", "string");
        mode.put("description", "【invoke 必填】create_session=为 B 新建会话；continue_session=按 session_id 继续 B 既有会话");
        ArrayNode modeEnum = mode.putArray("enum");
        modeEnum.add("create_session").add("continue_session");

        ObjectNode instructions = props.putObject("instructions");
        instructions.put("type", "string");
        instructions.put("description", "【invoke 必填】任务指令（B 收到的用户消息）。要求 B 给出清晰总结：做了什么/改了哪些文件/结果");

        ObjectNode sessionId = props.putObject("session_id");
        sessionId.put("type", "integer");
        sessionId.put("description", "【continue_session 必填（与 call_id 二选一）】B 的目标会话 ID（上一次 invoke 返回的【B 会话 ID】）");

        ObjectNode callId = props.putObject("call_id");
        callId.put("type", "string");
        callId.put("description", "【continue_session 可选 / poll、cancel 必填或选】invoke 返回的调用 ID，如 call_xxx");

        ObjectNode workDir = props.putObject("work_dir");
        workDir.put("type", "string");
        workDir.put("description", "【invoke 可选】覆盖 B 的默认工作目录（默认 B.workDir）");

        ObjectNode await = props.putObject("await");
        await.put("type", "boolean");
        await.put("description", "【invoke 可选，默认 true】true=阻塞等待 B 完成并返回结果；false=立即返回 callId，稍后用 poll 查询");

        ObjectNode timeout = props.putObject("timeout");
        timeout.put("type", "integer");
        timeout.put("description", "【invoke 可选】await=true 时最大等待秒数（默认 300，最大 600）");

        ObjectNode scope = props.putObject("scope");
        scope.put("type", "string");
        scope.put("description", "【poll 可选，默认 summary】summary=最终答复+工具轨迹摘要；full=完整轨迹；final=仅最终答复（省 token）");
        ArrayNode scopeEnum = scope.putArray("enum");
        scopeEnum.add("summary").add("full").add("final");

        ArrayNode required = root.putArray("required");
        required.add("action");
        return root;
    }

    // ==================== 执行入口 ====================

    @Override
    public String execute(JsonNode arguments) {
        String action = arguments.path("action").asText("");
        if (action.isEmpty()) {
            return "【参数缺失】'action' 参数缺失或为空。agent_invoke 的 action 必须为 invoke / poll / cancel 之一。\n"
                    + "示例：{ \"action\": \"invoke\", \"target_agent\": \"2\", \"mode\": \"create_session\", \"instructions\": \"...\" }";
        }
        return switch (action) {
            case "invoke" -> doInvoke(arguments);
            case "poll" -> doPoll(arguments);
            case "cancel" -> doCancel(arguments);
            default -> "❌ 错误：未知的 action '" + action + "'，仅支持 invoke / poll / cancel 三种取值。";
        };
    }

    // ================================================================
    //                      action=invoke
    // ================================================================

    private String doInvoke(JsonNode args) {
        // 校验调用方身份：必须由「智能体」在其会话的工具循环中发起（A 身份 = 委托凭证的前提）
        Long callerAgentConfigId = ToolContext.getAgentConfigId();
        Long callerConversationId = ToolContext.getConversationId();
        if (callerAgentConfigId == null || callerConversationId == null) {
            return "❌ 无法发起智能体互调：当前上下文缺少智能体身份（agentConfigId=" + callerAgentConfigId
                    + ", conversationId=" + callerConversationId + "）。\n"
                    + "【说明】agent_invoke 只能由「智能体 A」在其会话执行中调用；请切换到智能体会话（如 AI 助手/自定义智能体）后重试。";
        }

        String targetAgent = args.path("target_agent").asText();
        String mode = args.path("mode").asText();
        String instructions = args.path("instructions").asText();
        if (targetAgent.isEmpty()) {
            return "【参数缺失】action=invoke 需要 target_agent（B 的 agent_config_id 或名称）。";
        }
        if (mode.isEmpty()) {
            return "【参数缺失】action=invoke 需要 mode（create_session / continue_session）。";
        }
        if (instructions.isEmpty()) {
            return "【参数缺失】action=invoke 需要 instructions（任务指令）。";
        }

        AgentInvokeRequest req = new AgentInvokeRequest();
        req.setTargetAgent(targetAgent);
        req.setMode(mode);
        req.setInstructions(instructions);
        if (args.hasNonNull("session_id")) {
            req.setSessionId(args.get("session_id").asLong());
        }
        if (args.hasNonNull("call_id") && !args.path("call_id").asText().isEmpty()) {
            req.setCallId(args.path("call_id").asText());
        }
        if (args.hasNonNull("work_dir") && !args.path("work_dir").asText().isEmpty()) {
            req.setWorkDir(args.path("work_dir").asText());
        }
        req.setAwait(args.hasNonNull("await") ? args.get("await").asBoolean() : null);
        if (args.hasNonNull("timeout")) {
            req.setTimeoutSec(args.get("timeout").asInt());
        }
        if (args.hasNonNull("scope") && !args.path("scope").asText().isEmpty()) {
            req.setScope(args.path("scope").asText());
        }
        // ===== 调用方上下文（服务内部信任依据，非 LLM 可注入）=====
        req.setCallerAgentConfigId(callerAgentConfigId);
        req.setCallerConversationId(callerConversationId);
        req.setCallerAgentName(resolveCallerName(callerAgentConfigId));
        req.setCallerUserId(ToolContext.getUserId());
        // 信任链传递：A 若本身处于某委托链中（B→C 场景），链信息来自 apiRequest._trustChain
        req.setTrustChain(ToolContext.getTrustChain());

        try {
            AgentInvokeResult result = agentInvokeService.invoke(req);
            if (result == null) {
                return "❌ 智能体互调服务返回空结果（内部异常）。";
            }
            if (result.isSuccess()) {
                log.info("[AgentInvokeTool] 委托成功: caller={}, target={}, sessionId={}, callId={}, elapsed={}s",
                        callerAgentConfigId, targetAgent, result.getSessionId(), result.getCallId(), result.getElapsedSec());
            } else {
                log.info("[AgentInvokeTool] 委托未成功: code={}, caller={}, target={}, sessionId={}",
                        result.getCode(), callerAgentConfigId, targetAgent, result.getSessionId());
            }
            return result.getMessage();
        } catch (Exception e) {
            log.error("[AgentInvokeTool] 委托调用异常: target={}, err={}", targetAgent, e.getMessage(), e);
            return "❌ 智能体互调执行异常，请稍后重试（详情见服务端日志）。";
        }
    }

    // ================================================================
    //                      action=poll / cancel
    // ================================================================

    private String doPoll(JsonNode args) {
        // 与 invoke 一致：必须有会话上下文（归属校验依据，防跨会话窥探）
        Long callerConversationId = ToolContext.getConversationId();
        if (callerConversationId == null) {
            return "❌ 无法执行 poll：当前缺少会话上下文（conversationId 为空）。\n【说明】agent_invoke 只能在智能体会话执行中调用。";
        }
        String callId = args.path("call_id").asText("");
        Long sessionId = args.hasNonNull("session_id") ? args.get("session_id").asLong() : null;
        if (callId.isEmpty() && sessionId == null) {
            return "【参数缺失】action=poll 需要 call_id 或 session_id（二选一）。";
        }
        String scope = args.path("scope").asText("summary");
        try {
            AgentInvokeResult result = agentInvokeService.poll(callId.isEmpty() ? null : callId, sessionId, scope, callerConversationId);
            return result != null ? result.getMessage() : "❌ 查询结果为空（内部异常）。";
        } catch (Exception e) {
            log.error("[AgentInvokeTool] poll 异常: callId={}, err={}", callId, e.getMessage(), e);
            return "❌ poll 执行异常，请稍后重试（详情见服务端日志）。";
        }
    }

    private String doCancel(JsonNode args) {
        Long callerConversationId = ToolContext.getConversationId();
        if (callerConversationId == null) {
            return "❌ 无法执行 cancel：当前缺少会话上下文（conversationId 为空）。\n【说明】agent_invoke 只能在智能体会话执行中调用。";
        }
        String callId = args.path("call_id").asText("");
        Long sessionId = args.hasNonNull("session_id") ? args.get("session_id").asLong() : null;
        if (callId.isEmpty() && sessionId == null) {
            return "【参数缺失】action=cancel 需要 call_id 或 session_id（二选一）。";
        }
        try {
            AgentInvokeResult result = agentInvokeService.cancel(callId.isEmpty() ? null : callId, sessionId, callerConversationId);
            return result != null ? result.getMessage() : "❌ 取消结果为空（内部异常）。";
        } catch (Exception e) {
            log.error("[AgentInvokeTool] cancel 异常: callId={}, err={}", callId, e.getMessage(), e);
            return "❌ cancel 执行异常，请稍后重试（详情见服务端日志）。";
        }
    }

    // ==================== 辅助 ====================

    /** 解析调用方 A 的名称（溯源注记展示；查不到时降级占位） */
    private String resolveCallerName(Long callerAgentConfigId) {
        try {
            if (callerAgentConfigId != null) {
                AgentConfig cfg = agentConfigMapper.selectById(callerAgentConfigId).orElse(null);
                if (cfg != null && cfg.getName() != null && !cfg.getName().isBlank()) {
                    return cfg.getName();
                }
            }
        } catch (Exception e) {
            log.debug("[AgentInvokeTool] 查询调用方名称失败: {}", e.getMessage());
        }
        return callerAgentConfigId != null ? "Agent#" + callerAgentConfigId : null;
    }
}
