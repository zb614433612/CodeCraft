package com.example.agentdeepseek.model.entity;

import java.time.LocalDateTime;

/**
 * P2P Agent 会话映射实体
 * 记录 peerId + agentConfigId → conversationId 的映射关系
 * 实现同一台机器对同一 Agent 的上下文继承
 */
public class P2pAgentConversation {

    private Long id;

    /** 调用方机器特征码 */
    private String peerId;

    /** 使用的 Agent 配置 ID */
    private Long agentConfigId;

    /** 对应的本机会话 ID */
    private Long conversationId;

    /** 创建时间 */
    private LocalDateTime createdAt;

    /** 更新时间（每次调用时更新） */
    private LocalDateTime updatedAt;

    public P2pAgentConversation() {}

    public P2pAgentConversation(String peerId, Long agentConfigId, Long conversationId, LocalDateTime createdAt) {
        this.peerId = peerId;
        this.agentConfigId = agentConfigId;
        this.conversationId = conversationId;
        this.createdAt = createdAt;
        this.updatedAt = createdAt;
    }

    // ===== Getters & Setters =====

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getPeerId() { return peerId; }
    public void setPeerId(String peerId) { this.peerId = peerId; }
    public Long getAgentConfigId() { return agentConfigId; }
    public void setAgentConfigId(Long agentConfigId) { this.agentConfigId = agentConfigId; }
    public Long getConversationId() { return conversationId; }
    public void setConversationId(Long conversationId) { this.conversationId = conversationId; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
