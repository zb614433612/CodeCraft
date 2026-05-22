package com.example.agentdeepseek.model.entity;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 上下文压缩记录实体类
 * 记录每次智能压缩的摘要信息，用于替换被压缩的原始消息。
 * 当 buildMessagesFromHistory 加载消息时，如果发现某段消息已
 * 被压缩记录覆盖，则用压缩摘要替换原始消息序列。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class CompactionRecord {

    /**
     * 主键 ID
     */
    private Long id;

    /**
     * 所属会话 ID
     */
    private Long conversationId;

    /**
     * 压缩后的结构化摘要内容（Markdown 格式）
     */
    private String summary;

    /**
     * 被压缩的起始消息 ID（conversation_message.id），包含该消息
     */
    private Long startMessageId;

    /**
     * 被压缩的结束消息 ID（conversation_message.id），包含该消息
     */
    private Long endMessageId;

    /**
     * 压缩节省的 token 数量
     */
    private int tokenSavings;

    /**
     * 是否已被后续压缩覆盖（逻辑删除）
     */
    private boolean superseded;

    /**
     * 创建时间
     */
    private LocalDateTime createdAt;

    /**
     * 构造函数：创建新压缩记录
     */
    public CompactionRecord(Long conversationId, String summary, Long startMessageId,
                            Long endMessageId, int tokenSavings) {
        this.conversationId = conversationId;
        this.summary = summary;
        this.startMessageId = startMessageId;
        this.endMessageId = endMessageId;
        this.tokenSavings = tokenSavings;
        this.superseded = false;
        this.createdAt = LocalDateTime.now();
    }
}
