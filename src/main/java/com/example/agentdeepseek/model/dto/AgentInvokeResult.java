package com.example.agentdeepseek.model.dto;

import lombok.Data;

/**
 * 智能体互调结果（Phase 19）
 * <p>
 * message 为给调用方（Agent A / LLM）的完整结构化文本（含结果打包）；
 * 结构化字段供上层（AgentInvokeTool）与验证程序使用。
 * </p>
 */
@Data
public class AgentInvokeResult {

    /** code 枚举：OK / BUSY / CYCLE / DEPTH_LIMIT / AGENT_NOT_FOUND / AGENT_DISABLED /
     *  SELF_INVOKE / SESSION_NOT_FOUND / SESSION_MISMATCH / TIMEOUT / FAILED / ERROR */
    private String code;

    /** 给 A 的完整文本（状态 + 结果打包 / 失败原因），LLM 直接可读 */
    private String message;

    /** 本次调用 ID（后续 poll/cancel/continue 可用） */
    private String callId;

    /** B 的会话 ID（create_session=新建 / continue_session=原会话；A 用于继续会话） */
    private Long sessionId;

    /** B 的任务 ID（agent_task 行，终态后才有值） */
    private Long taskId;

    /** 目标智能体 B 的名称 */
    private String targetAgentName;

    /** 本次调用耗时（秒） */
    private long elapsedSec;

    public boolean isSuccess() {
        return "OK".equals(code);
    }
}
