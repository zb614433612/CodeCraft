package com.example.agentdeepseek.model.entity;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 子Agent执行记录实体类
 * 记录子Agent的完整执行上下文和结果，支持事后查询和级联删除
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class SubAgentLog {

    /**
     * 主键
     */
    private Long id;

    /**
     * 子Agent唯一标识，如 sub-dal-1（由主Agent指定）
     */
    private String agentId;

    /**
     * 父会话ID，关联 conversation.id
     */
    private Long parentConversationId;

    /**
     * 创建该子Agent的那条消息的 turnId，用于消息级联删除
     */
    private String parentTurnId;

    /**
     * 子Agent名称
     */
    private String name;

    /**
     * 状态：running / completed / failed / timeout
     */
    private String status;

    /**
     * 子Agent的完整任务描述（由主Agent传入）
     */
    private String instructions;

    /**
     * 上下文继承模式：inherit_summary / inherit_full / none
     */
    private String contextMode;

    /**
     * 子Agent可用的工具列表，JSON数组格式
     */
    private String tools;

    /**
     * 子Agent可用的技能列表，JSON数组（名称或ID）
     */
    private String skills;

    /**
     * 结构化执行摘要（返回给主Agent的文本）
     */
    private String summary;

    /**
     * 子Agent的完整消息历史（JSON格式）
     */
    private String fullMessages;

    /**
     * 文件变更记录，JSON格式：{"created":[],"modified":[],"deleted":[]}
     */
    private String fileChanges;

    /**
     * 编译结果：passed / failed / not_run
     */
    private String compileResult;

    /**
     * 实际使用的迭代次数
     */
    private Integer iterationsUsed;

    /**
     * 最大允许迭代次数
     */
    private Integer maxIterations;

    /**
     * 错误信息（状态为failed/timeout时记录）
     */
    private String errorMessage;

    /**
     * 创建时间
     */
    private LocalDateTime createdAt;

    /**
     * 完成时间
     */
    private LocalDateTime completedAt;

    /**
     * 更新时间
     */
    private LocalDateTime updatedAt;

    /**
     * 构造函数：创建新的子Agent记录时使用
     * 自动设置状态为 running，初始化时间和默认值
     */
    public SubAgentLog(String agentId, Long parentConversationId, String parentTurnId,
                       String name, String instructions, String contextMode,
                       String tools, String skills, Integer maxIterations) {
        this.agentId = agentId;
        this.parentConversationId = parentConversationId;
        this.parentTurnId = parentTurnId;
        this.name = name;
        this.status = "running";
        this.instructions = instructions;
        this.contextMode = contextMode;
        this.tools = tools;
        this.skills = skills;
        this.iterationsUsed = 0;
        this.maxIterations = maxIterations != null ? maxIterations : 30;
        this.compileResult = "not_run";
        this.createdAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
    }
}
