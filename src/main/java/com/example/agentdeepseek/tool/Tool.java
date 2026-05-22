package com.example.agentdeepseek.tool;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * 工具接口
 * 定义 DeepSeek 工具调用的基本契约
 */
public interface Tool {

    /**
     * 获取工具名称
     */
    String getName();

    /**
     * 获取工具描述
     */
    String getDescription();

    /**
     * 获取工具参数模式（JSON Schema）
     */
    JsonNode getParameters();

    /**
     * 执行工具
     * @param arguments 工具参数（JSON格式）
     * @return 工具执行结果（字符串）
     */
    String execute(JsonNode arguments);
}
