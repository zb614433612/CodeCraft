package com.example.agentdeepseek.model.entity;

import java.time.LocalDateTime;

/**
 * P2P Agent 授权记录实体
 * 记录本机与对端之间 Agent 的授权关系
 */
public class P2pAgentAuthorization {

    private Long id;

    /** 对方机器特征码 */
    private String peerId;

    /** 被授权的 Agent 配置 ID */
    private Long agentConfigId;

    /** Agent 名称（冗余存储，P2P两端Agent配置独立，接收方不查本地agent_config表） */
    private String agentName;

    /** Agent 描述（冗余） */
    private String agentDescription;

    /** Agent 头像（冗余） */
    private String agentAvatar;

    /** 授权的用户ID（谁授权的 / 授权给谁的） */
    private Long userId;

    /** 方向：sent=我授权给对方, received=对方授权给我 */
    private String direction;

    /** 状态：active=有效, cancelled=已取消 */
    private String status;

    /** 创建时间 */
    private LocalDateTime createdAt;

    /** 取消时间 */
    private LocalDateTime cancelledAt;

    public P2pAgentAuthorization() {}

    public P2pAgentAuthorization(String peerId, Long agentConfigId, String agentName,
                                  String agentDescription, String agentAvatar, Long userId,
                                  String direction, String status, LocalDateTime createdAt) {
        this.peerId = peerId;
        this.agentConfigId = agentConfigId;
        this.agentName = agentName;
        this.agentDescription = agentDescription;
        this.agentAvatar = agentAvatar;
        this.userId = userId;
        this.direction = direction;
        this.status = status;
        this.createdAt = createdAt;
    }

    // ===== Getters & Setters =====

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getPeerId() { return peerId; }
    public void setPeerId(String peerId) { this.peerId = peerId; }
    public Long getAgentConfigId() { return agentConfigId; }
    public void setAgentConfigId(Long agentConfigId) { this.agentConfigId = agentConfigId; }
    public String getAgentName() { return agentName; }
    public void setAgentName(String agentName) { this.agentName = agentName; }
    public String getAgentDescription() { return agentDescription; }
    public void setAgentDescription(String agentDescription) { this.agentDescription = agentDescription; }
    public String getAgentAvatar() { return agentAvatar; }
    public void setAgentAvatar(String agentAvatar) { this.agentAvatar = agentAvatar; }
    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public String getDirection() { return direction; }
    public void setDirection(String direction) { this.direction = direction; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getCancelledAt() { return cancelledAt; }
    public void setCancelledAt(LocalDateTime cancelledAt) { this.cancelledAt = cancelledAt; }
}
