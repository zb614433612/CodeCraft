package com.example.agentdeepseek.tool;

import com.example.agentdeepseek.service.SnapshotService;
import com.example.agentdeepseek.tool.permission.ToolExecutionPipeline;
import com.example.agentdeepseek.tool.permission.ToolPermissionRegistry;
import com.example.agentdeepseek.util.OperationDetailGenerator;
import com.example.agentdeepseek.util.ProjectRootContext;
import com.example.agentdeepseek.util.ToolContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
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
    private final SnapshotService snapshotService;
    private final ToolExecutionPipeline executionPipeline;
    private final ToolPermissionRegistry permissionRegistry;
    private final OperationDetailGenerator detailGenerator;

    public ToolExecutor(ToolRegistry toolRegistry, ObjectMapper objectMapper,
                        SnapshotService snapshotService,
                        ToolExecutionPipeline executionPipeline,
                        ToolPermissionRegistry permissionRegistry,
                        OperationDetailGenerator detailGenerator) {
        this.toolRegistry = toolRegistry;
        this.objectMapper = objectMapper;
        this.snapshotService = snapshotService;
        this.executionPipeline = executionPipeline;
        this.permissionRegistry = permissionRegistry;
        this.detailGenerator = detailGenerator;
    }

    // ============================================================
    // 工具调用结果
    // ============================================================

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

        public String getToolCallId() { return toolCallId; }
        public String getToolName() { return toolName; }
        public String getContent() { return content; }
        public boolean isRestricted() { return restricted; }
        public String getOperationSummary() { return operationSummary; }
    }

    // ============================================================
    // 主入口
    // ============================================================

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

    // ============================================================
    // 单个工具执行
    // ============================================================

    /**
     * 执行单个工具调用
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

        JsonNode arguments;
        try {
            String argsStr = functionNode.path("arguments").asText();
            arguments = objectMapper.readTree(argsStr);
        } catch (Exception e) {
            log.error("解析工具参数失败: tool={}, arguments={}", toolName,
                    functionNode.path("arguments"), e);
            return new ToolCallResult(toolCallId, toolName,
                "工具参数解析失败: " + e.getMessage());
        }

        // 查找并执行工具
        Tool tool = toolRegistry.getTool(toolName);
        if (tool == null) {
            log.warn("工具未找到: {}", toolName);
            return new ToolCallResult(toolCallId, toolName,
                "错误：未知工具 \"" + toolName + "\"，请检查工具名称是否正确");
        }

        try {
            String displayArgs = detailGenerator.generate(toolName, arguments);
            boolean isRestricted = permissionRegistry.requiresDataApproval(toolName);

            String filePathStr = resolveFilePath(toolName, arguments);

            // 文件修改类工具：创建代码快照
            if (isFileModifyingTool(toolName) && filePathStr != null) {
                String turnId = ToolContext.getTurnId();
                Long sessionId = ToolContext.getConversationId();
                log.info("快照创建: tool={}, filePath={}, turnId={}, sessionId={}",
                        toolName, filePathStr, turnId, sessionId);
                SnapshotService.SnapshotSummary summary = snapshotService.createSnapshot(turnId, sessionId, filePathStr);
                log.info("快照结果: {}", summary != null ? "已创建 snapshotId=" + summary.getSnapshotId() : "返回null");
            }

            // 通过三层防护管道执行工具（替代 tool.execute() 直接调用）
            String executionMode = ToolContext.getMode();
            Long conversationId = ToolContext.getConversationId();
            Long userId = ToolContext.getUserId();
            String result = executionPipeline.execute(tool, toolName, arguments,
                    executionMode, conversationId, userId);

            // 工具执行后：计算文件改动统计
            if (isFileModifyingTool(toolName) && filePathStr != null) {
                String turnId = ToolContext.getTurnId();
                snapshotService.computeDiffStats(turnId, filePathStr);
            }

            return new ToolCallResult(toolCallId, toolName, result,
                    isRestricted, displayArgs);
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

    private boolean isFileModifyingTool(String toolName) {
        return "write_file".equals(toolName)
                || "edit_file".equals(toolName)
                || "delete_file".equals(toolName);
    }

    /**
     * 从工具参数中提取文件路径，返回标准化绝对路径
     */
    private String resolveFilePath(String toolName, JsonNode arguments) {
        String filePathStr = arguments.path("file_path").asText("");
        if (filePathStr == null || filePathStr.isEmpty()) {
            filePathStr = arguments.path("path").asText("");
        }
        if (filePathStr == null || filePathStr.isEmpty()) {
            return null;
        }
        Path filePath = Path.of(filePathStr);
        if (!filePath.isAbsolute()) {
            String root = ProjectRootContext.get();
            if (root != null) {
                filePath = Path.of(root, filePathStr).normalize();
            }
        } else {
            filePath = filePath.normalize();
        }
        return filePath.toString();
    }

    // ============================================================
    // 消息构建
    // ============================================================

    /**
     * 将工具调用结果转换为DeepSeek API期望的消息格式
     */
    public List<ObjectNode> buildToolMessages(List<ToolCallResult> toolCallResults) {
        List<ObjectNode> messages = new ArrayList<>();
        for (ToolCallResult result : toolCallResults) {
            ObjectNode message = objectMapper.createObjectNode();
            message.put("role", "tool");
            message.put("content", result.getContent());
            message.put("tool_call_id", result.getToolCallId());
            message.put("tool_name", result.getToolName());
            messages.add(message);
        }
        return messages;
    }

    /**
     * 从DeepSeek响应中提取tool_calls节点
     */
    public JsonNode extractToolCalls(JsonNode messageNode) {
        return messageNode.path("tool_calls");
    }

    // ============================================================
    // 工具定义构建
    // ============================================================

    /**
     * 构建工具定义列表
     */
    public ArrayNode buildToolDefinitions(List<String> toolNames) {
        ArrayNode toolsArray = objectMapper.createArrayNode();
        if (toolNames == null || toolNames.isEmpty()) {
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
