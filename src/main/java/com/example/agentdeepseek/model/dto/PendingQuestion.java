package com.example.agentdeepseek.model.dto;

import java.util.concurrent.CompletableFuture;

/**
 * 待处理问题
 * 当 LLM 调用 ask_user 工具时，创建此对象等待用户回答
 */
public class PendingQuestion {

    private final String uuid;
    private final String question;
    private final CompletableFuture<String> future;
    private final long createdAt;

    public PendingQuestion(String uuid, String question) {
        this.uuid = uuid;
        this.question = question;
        this.future = new CompletableFuture<>();
        this.createdAt = System.currentTimeMillis();
    }

    public String getUuid() {
        return uuid;
    }

    public String getQuestion() {
        return question;
    }

    public CompletableFuture<String> getFuture() {
        return future;
    }

    public long getCreatedAt() {
        return createdAt;
    }
}
