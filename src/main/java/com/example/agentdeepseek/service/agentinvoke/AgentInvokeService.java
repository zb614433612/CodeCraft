package com.example.agentdeepseek.service.agentinvoke;

import com.example.agentdeepseek.model.dto.AgentInvokeRequest;
import com.example.agentdeepseek.model.dto.AgentInvokeResult;

/**
 * 智能体互相调用服务（Phase 19）
 * <p>
 * Agent A 调用 Agent B（agent_config 实例）：B 以自身身份（角色/模型/工作目录/工具集）
 * 在独立会话中执行任务（create_session 新建 / continue_session 续跑），
 * 执行期间按「委托即授权」免审批（信任链校验通过后由 DeepSeekServiceImpl 内部信任上下文放行）。
 * </p>
 * <p>
 * ⚠️ 本模块与 AgentForkManager（fork 子 Agent / agent 工具）完全无关；
 * 调用关系（callId → caller/target/session）由内存注册表维护，不落库、不依赖子 Agent 模块。
 * </p>
 */
public interface AgentInvokeService {

    /** 信任链最大深度：A→B→C 合法（链上已有 3 个时不再放行新的被调方） */
    int MAX_TRUST_DEPTH = 3;

    /**
     * 发起调用：resolveAgent + 信任链校验 + 会话准备 + 派发（后台执行）+ 可选同步等待
     *
     * @param request 互调请求（caller* 由服务内部填充）
     * @return 结构化结果（含 B 会话 ID / callId / 结果打包）
     */
    AgentInvokeResult invoke(AgentInvokeRequest request);

    /**
     * 查询调用结果（异步模式 / 超时后追查 / 任意时刻状态）
     * <p>归属校验：callerConversationId 非空时，仅允许查询「本会话发起」的委托记录（防跨会话窥探/干扰）</p>
     *
     * @param callId                invoke 返回的调用 ID（与 sessionId 二选一）
     * @param sessionId             B 会话 ID（callId 丢失/进程重启后可用）
     * @param scope                 summary（默认）| full | final
     * @param callerConversationId  调用方（A）会话 ID（归属校验；可为 null=宽松模式）
     */
    AgentInvokeResult poll(String callId, Long sessionId, String scope, Long callerConversationId);

    /**
     * 取消 B 侧任务（走现有会话级取消链路：取消检查点自然终止）
     * <p>归属校验同 poll：callerConversationId 非空时仅允许取消本会话发起的委托</p>
     *
     * @param callId                invoke 返回的调用 ID（与 sessionId 二选一）
     * @param sessionId             B 会话 ID
     * @param callerConversationId  调用方（A）会话 ID（归属校验；可为 null=宽松模式）
     */
    AgentInvokeResult cancel(String callId, Long sessionId, Long callerConversationId);

    /**
     * 级联取消指定调用方会话名下的所有未终态委托任务（P3：A 的任务被取消时调用，
     * 防止 A 已停止而 B 仍在后台继续执行——孤儿委托）
     *
     * @param callerConversationId 调用方（A）会话 ID
     * @return 被级联取消的委托任务数
     */
    int cancelByCallerConversation(Long callerConversationId);
}
