package com.example.agentdeepseek.model.entity;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 后台任务实体
 * 持久化工具调用循环的状态，支持页面刷新/跳转后重连
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class AgentTask {
    private Long id;
    private Long conversationId;
    private String status; // running / completed / failed / cancelled
    private Integer iteration;
    private Integer maxIterations;
    private String errorMessage;
    private Integer eventCount;
    private String pendingQuestionUuid; // 待审批问题的UUID，用于页面刷新后重连展示
    private String pendingQuestionText; // 待审批问题的文本内容
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public AgentTask(Long conversationId, Integer maxIterations) {
        this.conversationId = conversationId;
        this.status = "running";
        this.iteration = 0;
        this.maxIterations = maxIterations;
        this.eventCount = 0;
        this.createdAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
    }
}
