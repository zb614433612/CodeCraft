package com.example.agentdeepseek.tool;

import com.example.agentdeepseek.util.OperationDetailGenerator;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * 工具执行器
 * 负责解析DeepSeek的工具调用请求，执行相应的工具并返回结果
 */
@Slf4j
@Component
public class ToolExecutor {

    private final ToolRegistry toolRegistry;
    private final ObjectMapper objectMapper;

    public ToolExecutor(ToolRegistry toolRegistry, ObjectMapper objectMapper) {
        this.toolRegistry = toolRegistry;
        this.objectMapper = objectMapper;
    }

    /**
     * 工具调用结果
     */
    public static class ToolCallResult {
        private final String toolCallId;
        private final String toolName;
        private final String content;
        private final boolean restricted;
        private final String operationSummary;

        public ToolCallResult(String toolCallId, String toolName, String content) {
            this(toolCallId, toolName, content, false, null);
        }

        public ToolCallResult(String toolCallId, String toolName, String content,
                              boolean restricted, String operationSummary) {
            this.toolCallId = toolCallId;
            this.toolName = toolName;
            this.content = content;
            this.restricted = restricted;
            this.operationSummary = operationSummary;
        }

        public String getToolCallId() {
            return toolCallId;
        }

        public String getToolName() {
            return toolName;
        }

        public String getContent() {
            return content;
        }

        public boolean isRestricted() {
            return restricted;
        }

        public String getOperationSummary() {
            return operationSummary;
        }
    }

    /**
     * 解析并执行工具调用
     * @param toolCallsJson DeepSeek返回的tool_calls JSON节点
     * @return 工具调用结果列表
     */
    public List<ToolCallResult> executeToolCalls(JsonNode toolCallsJson) {
        List<ToolCallResult> results = new ArrayList<>();

        if (toolCallsJson == null || !toolCallsJson.isArray()) {
            log.warn("tool_calls is not an array or null: {}", toolCallsJson);
            return results;
        }

        for (JsonNode toolCallNode : toolCallsJson) {
            try {
                ToolCallResult result = executeSingleToolCall(toolCallNode);
                if (result != null) {
                    results.add(result);
                }
            } catch (Exception e) {
                log.error("执行工具调用失败: {}", toolCallNode, e);
                // 生成错误结果，以便模型知道工具调用失败
                String toolCallId = toolCallNode.path("id").asText("unknown");
                String toolName = toolCallNode.path("function").path("name").asText("unknown");
                String briefMsg = e.getMessage();
                if (briefMsg != null && briefMsg.length() > 120) {
                    briefMsg = briefMsg.substring(0, 120) + "...";
                }
                results.add(new ToolCallResult(toolCallId, toolName,
                    "工具调用失败: " + briefMsg));
            }
        }

        return results;
    }

    /**
     * 执行单个工具调用
     * @param toolCallNode 单个工具调用节点
     * @return 工具调用结果
     */
    private ToolCallResult executeSingleToolCall(JsonNode toolCallNode) {
        String toolCallId = toolCallNode.path("id").asText();
        if (toolCallId.isEmpty()) {
            log.error("工具调用缺少id字段: {}", toolCallNode);
            return null;
        }

        JsonNode functionNode = toolCallNode.path("function");
        String toolName = functionNode.path("name").asText();
        if (toolName.isEmpty()) {
            log.error("工具调用缺少function.name字段: {}", toolCallNode);
            return null;
        }

        Tool tool = toolRegistry.getTool(toolName);
        if (tool == null) {
            log.warn("找不到工具: {}", toolName);
            return new ToolCallResult(toolCallId, toolName,
                "错误：找不到工具 '" + toolName + "'");
        }

        JsonNode arguments = functionNode.path("arguments");
        try {
            log.debug("执行工具调用: id={}, tool={}, arguments={}",
                toolCallId, toolName, arguments);
            log.debug("工具调用参数详情: {}", arguments.toString());
            log.debug("工具调用参数节点类型: {}", arguments.getNodeType());
            log.debug("工具调用参数是否为文本节点: {}", arguments.isTextual());
            log.debug("工具调用参数是否为对象节点: {}", arguments.isObject());
            log.debug("工具调用参数是否为数组节点: {}", arguments.isArray());
            log.debug("工具调用参数是否为null: {}", arguments.isNull());
            log.debug("工具调用参数是否为missing: {}", arguments.isMissingNode());

            // 检查参数是否为空字符串
            if (arguments.isTextual() && arguments.asText().isEmpty()) {
                log.warn("工具调用参数为空字符串！这可能表示DeepSeek还未完全发送参数，或者参数格式有问题");
                log.debug("完整的function节点: {}", functionNode.toString());
                log.debug("完整的toolCall节点: {}", toolCallNode.toString());
            }

            // 如果arguments是文本节点（JSON字符串），解析为对象
            JsonNode parsedArguments = arguments;
            if (arguments.isTextual()) {
                try {
                    log.debug("参数是文本节点，解析JSON字符串: {}", arguments.asText());
                    parsedArguments = objectMapper.readTree(arguments.asText());
                    log.debug("解析后的参数: {}", parsedArguments);
                } catch (Exception e) {
                    log.error("解析JSON参数失败: {}", arguments.asText(), e);
                }
            }

            String result = tool.execute(parsedArguments);

            // 生成本地操作摘要（用于展示在 thinking 流中）
            boolean isRestricted = OperationDetailGenerator.isRestricted(toolName);
            String operationSummary = null;
            if (isRestricted && parsedArguments != null) {
                try {
                    operationSummary = OperationDetailGenerator.generate(toolName, parsedArguments);
                } catch (Exception e) {
                    log.debug("生成操作摘要失败: {}", e.getMessage());
                }
            }

            return new ToolCallResult(toolCallId, toolName, result, isRestricted, operationSummary);
        } catch (Exception e) {
            log.error("工具执行异常: tool={}, arguments={}", toolName, arguments, e);
            String briefMsg = e.getMessage();
            if (briefMsg != null && briefMsg.length() > 120) {
                briefMsg = briefMsg.substring(0, 120) + "...";
            }
            return new ToolCallResult(toolCallId, toolName,
                "工具执行异常: " + briefMsg);
        }
    }

    /**
     * 将工具调用结果转换为DeepSeek API期望的消息格式
     * @param toolCallResults 工具调用结果列表
     * @return 消息列表，每个消息包含role=tool和工具结果
     */
    public List<ObjectNode> buildToolMessages(List<ToolCallResult> toolCallResults) {
        List<ObjectNode> messages = new ArrayList<>();
        for (ToolCallResult result : toolCallResults) {
            ObjectNode message = objectMapper.createObjectNode();
            message.put("role", "tool");
            message.put("content", result.getContent());
            message.put("tool_call_id", result.getToolCallId());

            messages.add(message);
        }
        return messages;
    }

    /**
     * 从DeepSeek响应中提取tool_calls节点
     * @param messageNode DeepSeek响应的message节点
     * @return tool_calls节点，如果没有则返回null
     */
    public JsonNode extractToolCalls(JsonNode messageNode) {
        return messageNode.path("tool_calls");
    }

    /**
     * 构建工具定义列表，用于DeepSeek API请求
     * @return 工具定义列表（JSON数组）
     */
    public ArrayNode buildToolDefinitions() {
        return buildToolDefinitions(null);
    }

    /**
     * 构建指定工具的定义列表，用于DeepSeek API请求
     * @param toolNames 工具名称列表，如果为null或空列表则返回空数组（不注册任何工具）
     * @return 工具定义列表（JSON数组）
     */
    public ArrayNode buildToolDefinitions(List<String> toolNames) {
        ArrayNode toolsArray = objectMapper.createArrayNode();
        if (toolNames == null || toolNames.isEmpty()) {
            // null或空列表表示不注册任何工具
            return toolsArray;
        }

        Collection<Tool> tools = new ArrayList<>();
        for (String toolName : toolNames) {
            Tool tool = toolRegistry.getTool(toolName);
            if (tool != null) {
                tools.add(tool);
            } else {
                log.warn("工具未找到: {}", toolName);
            }
        }

        for (Tool tool : tools) {
            ObjectNode toolNode = objectMapper.createObjectNode();
            toolNode.put("type", "function");

            ObjectNode functionNode = objectMapper.createObjectNode();
            functionNode.put("name", tool.getName());
            functionNode.put("description", tool.getDescription());
            functionNode.set("parameters", tool.getParameters());

            toolNode.set("function", functionNode);
            toolsArray.add(toolNode);
        }
        return toolsArray;
    }
}