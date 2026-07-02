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
    private Double temperature;
    private String workDir;
    private Integer sortOrder;
    private Integer enabled;
    private Integer isDefault;
    private Integer isBuiltin;
    /** 绑定的 LLM Provider ID，关联 llm_provider.id（null 或 0 表示使用默认 Provider） */
    private Long providerId;
    /** 前端动态选择的 LLM Provider Code（如 deepseek/openai），运行时持久化 */
    private String providerCode;
    /** 角色性格配置 JSON 字符串（如 {"name":"圆圆","species":"人",...}） */
    private String characterProfile;
    private Long userId;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
