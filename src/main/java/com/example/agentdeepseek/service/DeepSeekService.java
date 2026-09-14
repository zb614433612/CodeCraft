package com.example.agentdeepseek.service;

import com.example.agentdeepseek.model.dto.ChatRequest;
import com.example.agentdeepseek.model.entity.AgentTask;
import reactor.core.publisher.Flux;

import java.util.List;

/**
 * DeepSeek API服务接口
 * 定义与DeepSeek API通信的方法
 */
public interface DeepSeekService {

    /**
     * 流式调用DeepSeek API
     *
     * @param request 聊天请求，包含消息和可选会话ID
     * @return 原始响应流（字符串）
     */
    Flux<String> streamChat(ChatRequest request);

    /**
     * 内部委托执行（Phase 19 智能体互调专用）：以指定 Agent 配置执行/续跑任务。
     * 信任链校验由 AgentInvokeService 完成；callerAgentConfigId 非 null 即视为内部委托，
     * 目标会话工具循环按「委托即授权」跳过审批弹窗（B 工具集内全部放行）。
     * 本方法只允许服务层内部调用——HTTP Controller 仅暴露 streamChat，外部无法伪造信任。
     *
     * @param request             聊天请求（message/sessionId/agentConfigId/executionMode 等）
     * @param callerAgentConfigId 调用方智能体（A）配置 ID
     * @param callerConversationId 调用方（A）会话 ID（溯源/审计，可空）
     * @param trustChain          信任链（途经 agentConfigId 列表）
     * @return 原始响应流（字符串）
     */
    Flux<String> streamChatInternal(ChatRequest request, Long callerAgentConfigId,
                                    Long callerConversationId, List<Long> trustChain);

    /**
     * 获取会话的活跃任务状态
     */
    AgentTask getActiveTask(Long conversationId);

    /**
     * 订阅活跃任务的事件流（用于页面刷新后重连）
     * @param cursor 已消费的事件序号游标，后端跳过 seq &lt;= cursor 的历史事件，只推增量
     */
    Flux<String> subscribeToTask(Long conversationId, long cursor);

    /**
     * 获取会话最新事件序号（重连游标：前端携带此值，后端跳过 seq &lt;= cursor 的历史事件）
     */
    long getLatestEventSeq(Long conversationId);

    /**
     * 清除会话的待审批问题记录（answer 接口授权成功后同步调用，防止重连重复弹窗）
     */
    void clearPendingQuestion(Long conversationId);

    /**
     * 取消正在运行的后台任务
     */
    void cancelTask(Long conversationId);

    /**
     * 异步处理会话（用于定时任务等后台场景）
     * 创建 ChatRequest 并调用 streamChat 处理，AI 回复自动保存到数据库
     * @param conversationId 会话ID
     * @param message 用户消息（任务指令）
     * @param agentType agent类型（用于选择提示词文件）
     */
     void processConversationAsync(Long conversationId, String message, String agentType, Long agentConfigId);
}