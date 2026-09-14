package com.example.agentdeepseek.model.dto;

import lombok.Data;

import java.util.List;

/**
 * 智能体互调请求（Phase 19）
 * <p>
 * Agent A 调用 Agent B 的任务委托请求：
 * create_session（为 B 建新会话执行） / continue_session（按会话 ID 继续 B 任务）。
 * caller* 字段由调用方（AgentInvokeTool / 服务内部）填充，仅存于 JVM 内，无 HTTP 注入面。
 * </p>
 */
@Data
public class AgentInvokeRequest {

    /** 模式：create_session（新建会话）/ continue_session（继续既有会话） */
    private String mode;

    /** 目标智能体 B：agent_config_id（数字字符串）或名称 */
    private String targetAgent;

    /** continue_session 时必填：B 的目标会话 ID（与 callId 二选一） */
    private Long sessionId;

    /** continue_session 时可替代 sessionId：按上次 invoke 返回的 callId 定位 B 会话 */
    private String callId;

    /** 任务指令（B 收到的 user 消息内容） */
    private String instructions;

    /** 覆盖 B 的默认工作目录（可选，默认 B.workDir） */
    private String workDir;

    /** 是否同步等待 B 完成（默认 true；false 立即返回 callId，由 poll 查询） */
    private Boolean await;

    /** await=true 时最大等待秒数（默认 300，最大 600） */
    private Integer timeoutSec;

    /** poll 结果粒度：summary（默认）/ full / final */
    private String scope;

    // ==================== 调用方上下文（服务内部填充，无 HTTP 注入面） ====================

    /** 调用方智能体 A 的 agent_config_id（null=非内部调用，如定时任务直派） */
    private Long callerAgentConfigId;

    /** 调用方 A 的会话 ID（溯源/审计） */
    private Long callerConversationId;

    /** 调用方 A 的名称（溯源注记展示用） */
    private String callerAgentName;

    /** 调用方所属 userId（create_session 会话归属；可空=系统级） */
    private Long callerUserId;

    /** 已有信任链（途经 agentConfigId；A 首次发起为空，B→C 时由 B 的执行上下文传入） */
    private List<Long> trustChain;
}
