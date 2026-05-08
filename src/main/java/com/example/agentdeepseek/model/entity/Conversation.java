package com.example.agentdeepseek.model.entity;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 会话实体类
 * 存储会话基本信息
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Conversation {
    /**
     * 会话ID，自增主键
     */
    private Long id;

    /**
     * 会话名称，取用户问题前6个字符（不足则取全部）
     */
    private String name;

    /**
     * 用户ID，关联用户表
     */
    private Long userId;

    /**
     * 创建时间
     */
    private LocalDateTime createdAt;

    /**
     * 更新时间
     */
    private LocalDateTime updatedAt;

    /**
     * 会话类型：ai_assistant / chat_assistant / code_assistant
     */
    private String agentType;

    /**
     * 构造函数，用于创建新会话（无用户）
     * @param name 会话名称
     */
    public Conversation(String name) {
        this(name, null);
    }

    /**
     * 构造函数，用于创建新会话（带用户）
     * @param name 会话名称
     * @param userId 用户ID
     */
    public Conversation(String name, Long userId) {
        this.name = name;
        this.userId = userId;
        this.createdAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
    }

    /**
     * 构造函数，用于创建新会话（带用户和agent类型）
     * @param name 会话名称
     * @param userId 用户ID
     * @param agentType 会话类型
     */
    public Conversation(String name, Long userId, String agentType) {
        this.name = name;
        this.userId = userId;
        this.agentType = agentType;
        this.createdAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
    }
}