package com.example.agentdeepseek.service;

import com.example.agentdeepseek.model.dto.ChatRequest;
import com.example.agentdeepseek.model.entity.AgentTask;
import reactor.core.publisher.Flux;

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
     * 获取会话的活跃任务状态
     */
    AgentTask getActiveTask(Long conversationId);

    /**
     * 订阅活跃任务的事件流（用于页面刷新后重连）
     */
    Flux<String> subscribeToTask(Long conversationId);

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