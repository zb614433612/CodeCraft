package com.example.agentdeepseek.model.entity;

import java.time.LocalDateTime;

/**
 * P2P 聊天消息实体
 */
public class P2pChatMessage {

    private Long id;
    private String peerId;
    private String senderName;
    private String content;
    private String direction;  // sent / received
    private String messageType;  // chat / auth_grant / auth_cancel / agent_invoke / agent_response
    private Long agentConfigId;
    private String agentName;
    private LocalDateTime createdAt;

    public P2pChatMessage() {}

    public P2pChatMessage(String peerId, String senderName, String content, String direction, LocalDateTime createdAt) {
        this(peerId, senderName, content, direction, "chat", null, null, createdAt);
    }

    public P2pChatMessage(String peerId, String senderName, String content, String direction,
                          String messageType, Long agentConfigId, String agentName, LocalDateTime createdAt) {
        this.peerId = peerId;
        this.senderName = senderName;
        this.content = content;
        this.direction = direction;
        this.messageType = messageType != null ? messageType : "chat";
        this.agentConfigId = agentConfigId;
        this.agentName = agentName;
        this.createdAt = createdAt;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getPeerId() { return peerId; }
    public void setPeerId(String peerId) { this.peerId = peerId; }
    public String getSenderName() { return senderName; }
    public void setSenderName(String senderName) { this.senderName = senderName; }
    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }
    public String getDirection() { return direction; }
    public void setDirection(String direction) { this.direction = direction; }
    public String getMessageType() { return messageType; }
    public void setMessageType(String messageType) { this.messageType = messageType; }
    public Long getAgentConfigId() { return agentConfigId; }
    public void setAgentConfigId(Long agentConfigId) { this.agentConfigId = agentConfigId; }
    public String getAgentName() { return agentName; }
    public void setAgentName(String agentName) { this.agentName = agentName; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
