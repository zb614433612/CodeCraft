package com.example.agentdeepseek.model.entity;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Agent配置实体类
 * 存储自定义Agent的完整配置信息
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class AgentConfig {
    private Long id;
    private String name;
    private String description;
    private String avatar;
    private String systemPrompt;
    private String toolNames;
    private String modelName;
    private String thinkingMode;
    private String executionMode;
    private String workDir;
    private Integer sortOrder;
    private Integer enabled;
    private Integer isDefault;
    private Integer isBuiltin;
    private Long userId;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
