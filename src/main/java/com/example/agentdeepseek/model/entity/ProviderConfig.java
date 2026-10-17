package com.example.agentdeepseek.model.entity;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * LLM Provider 配置实体类
 * 存储多种 LLM 平台的连接配置信息（DeepSeek / OpenAI / Anthropic / Ollama 等）
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ProviderConfig {
    private Long id;
    /** Provider 编码：deepseek / openai / anthropic / ollama / custom */
    private String code;
    /** 显示名称 */
    private String name;
    /** API Base URL */
    private String baseUrl;
    /** API Key（可加密存储） */
    private String apiKey;
    /** 默认模型名称 */
    private String defaultModel;
    /** 可用模型列表（JSON数组字符串） */
    private String modelList;
    /**
     * 扩展配置（JSON 字符串）
     * <p>
     * 用于存放平台增强能力所需的额外凭证/开关，例如火山引擎：
     * {@code {"accessKey":"AK...","secretKey":"SK...","searchApiKey":"联网搜索Key","region":"cn-beijing"}}。
     * 为空表示未配置，相关增强能力自动跳过（不影响对话主链路）。
     * </p>
     */
    private String extraConfig;
    /** 请求模板类型：deepseek / openai / anthropic / ollama / custom / volcengine */
    private String requestTemplate;
    /** 是否为默认 Provider */
    private Integer isDefault;
    /** 是否启用 */
    private Integer enabled;
    /** 排序号 */
    private Integer sortOrder;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
