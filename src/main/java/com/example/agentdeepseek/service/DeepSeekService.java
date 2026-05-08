package com.example.agentdeepseek.service;

import com.example.agentdeepseek.model.dto.ChatRequest;
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

}