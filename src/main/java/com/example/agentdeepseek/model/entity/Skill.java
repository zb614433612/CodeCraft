package com.example.agentdeepseek.model.entity;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 技能实体类
 * AI 自动管理的可复用工作流单元
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Skill {
    private Long id;
    private String name;
    private String description;
    private String toolNames;
    private String instructions;
    private String triggerWords;
    private Double confidence;
    private Integer usageCount;
    private Integer successCount;
    private Integer failCount;
    private Long userId;
    private String agentType;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public Skill(String name, String description, String toolNames, String instructions,
                 Long userId, String agentType) {
        this.name = name;
        this.description = description;
        this.toolNames = toolNames;
        this.instructions = instructions;
        this.confidence = 0.5;
        this.usageCount = 0;
        this.successCount = 0;
        this.failCount = 0;
        this.userId = userId;
        this.agentType = agentType;
    }

    public Skill(String name, String description, String toolNames, String instructions,
                 String triggerWords, Long userId, String agentType) {
        this(name, description, toolNames, instructions, userId, agentType);
        this.triggerWords = triggerWords;
    }
}
