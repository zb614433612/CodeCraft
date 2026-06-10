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
import java.util.Set;

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

        // ================================================================
        // 🔧 智能补齐：检测缺失的 action 参数并自动修复
        // ================================================================
        ActionFixResult actionFix = fixMissingAction(toolName, arguments);
        if (actionFix != null) {
            if (actionFix.isAutoFixed()) {
                // 智能补齐成功，使用修复后的参数
                arguments = actionFix.getFixedArguments();
                log.info("🔧 智能补齐 action={} for tool={}, 推断依据: {}",
                        actionFix.getInferredAction(), toolName, actionFix.getReason());
            } else {
                // 无法自动补齐，返回精确的修复指导
                return new ToolCallResult(toolCallId, toolName, actionFix.getGuidanceMessage());
            }
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
        return "file_writer".equals(toolName);
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

    // ============================================================
    // 🔧 智能补齐：缺失 action 参数的自动修复
    // ============================================================

    /**
     * 需要 action 参数的工具集合
     */
    private static final Set<String> ACTION_REQUIRED_TOOLS = Set.of(
            "file_explorer", "file_writer", "command",
            "git_query", "git_submit", "git_branch",
            "agent", "skill", "task_manager",
            "chat_attachment", "schedule_task"
    );

    /**
     * 智能补齐缺失 action 参数的结果
     */
    private static class ActionFixResult {
        private final boolean autoFixed;
        private final String inferredAction;
        private final String reason;
        private final JsonNode fixedArguments;
        private final String guidanceMessage;

        private ActionFixResult(boolean autoFixed, String inferredAction, String reason,
                                JsonNode fixedArguments, String guidanceMessage) {
            this.autoFixed = autoFixed;
            this.inferredAction = inferredAction;
            this.reason = reason;
            this.fixedArguments = fixedArguments;
            this.guidanceMessage = guidanceMessage;
        }

        boolean isAutoFixed() { return autoFixed; }
        String getInferredAction() { return inferredAction; }
        String getReason() { return reason; }
        JsonNode getFixedArguments() { return fixedArguments; }
        String getGuidanceMessage() { return guidanceMessage; }
    }

    /**
     * 检测并修复缺失的 action 参数。
     * 返回 null 表示不需要修复（已有 action 或该工具不需要 action）。
     * 返回 ActionFixResult(true) 表示已自动补齐。
     * 返回 ActionFixResult(false) 表示无法自动补齐，附带修复指导。
     */
    private ActionFixResult fixMissingAction(String toolName, JsonNode arguments) {
        // 不需要 action 的工具直接跳过
        if (!ACTION_REQUIRED_TOOLS.contains(toolName)) {
            return null;
        }

        // 已有 action 参数，无需修复
        if (arguments.has("action") && !arguments.path("action").asText("").isEmpty()) {
            return null;
        }

        // 尝试推断 action
        String inferred = inferAction(toolName, arguments);

        if (inferred != null) {
            // 可以安全推断，自动补齐
            ObjectNode fixedArgs = arguments.deepCopy();
            fixedArgs.put("action", inferred);
            String reason = buildInferReason(toolName, arguments, inferred);
            return new ActionFixResult(true, inferred, reason, fixedArgs, null);
        } else {
            // 无法推断，返回精确的修复指导
            String guidance = buildActionGuidance(toolName, arguments);
            return new ActionFixResult(false, null, null, null, guidance);
        }
    }

    /**
     * 根据已有参数推断 action 值
     */
    private String inferAction(String toolName, JsonNode args) {
        return switch (toolName) {
            case "file_explorer" -> inferFileExplorerAction(args);
            case "file_writer" -> inferFileWriterAction(args);
            case "command" -> inferCommandAction(args);
            case "git_query" -> inferGitQueryAction(args);
            case "git_submit" -> inferGitSubmitAction(args);
            case "git_branch" -> inferGitBranchAction(args);
            case "agent" -> inferAgentAction(args);
            case "skill" -> inferSkillAction(args);
            case "task_manager" -> inferTaskManagerAction(args);
            case "chat_attachment" -> inferChatAttachmentAction(args);
            case "schedule_task" -> inferScheduleTaskAction(args);
            default -> null;
        };
    }

    private String inferFileExplorerAction(JsonNode args) {
        boolean hasFilePath = isNonEmpty(args, "file_path");
        boolean hasPattern = isNonEmpty(args, "pattern");
        boolean hasDepth = args.has("depth") || args.has("show_file_count");
        boolean hasInclude = isNonEmpty(args, "include");
        boolean hasContextLines = args.has("context_lines");
        boolean hasMaxResults = args.has("max_results");

        if (hasFilePath && !hasPattern) return "read";
        if (hasPattern && hasInclude) return "grep";
        if (hasPattern && hasContextLines) return "grep";
        if (hasPattern && hasMaxResults) return "grep";
        if (hasPattern) return "glob";  // 仅有 pattern 默认 glob
        if (hasDepth) return "tree";
        return null; // 无法确定
    }

    private String inferFileWriterAction(JsonNode args) {
        boolean hasContent = isNonEmpty(args, "content");
        boolean hasOldText = isNonEmpty(args, "old_text");
        boolean hasForce = args.has("force");
        boolean hasPath = isNonEmpty(args, "path");

        if (hasContent) return "write";
        if (hasOldText) return "edit";
        if (hasForce && hasPath) return "write"; // force + path 暗示 write
        // delete 比较危险，不自动推断
        return null;
    }

    private String inferCommandAction(JsonNode args) {
        boolean hasCommand = isNonEmpty(args, "command");
        boolean hasServiceId = args.has("service_id");
        boolean hasWaitFor = isNonEmpty(args, "wait_for");
        boolean hasTail = args.has("tail");

        if (hasServiceId && hasTail) return "logs";
        if (hasServiceId) return "logs"; // 默认 logs
        if (hasCommand && hasWaitFor) return "start";
        if (hasCommand) return "exec";
        return null;
    }

    private String inferGitQueryAction(JsonNode args) {
        boolean hasFile = isNonEmpty(args, "file");
        boolean hasMaxCount = args.has("max_count");
        boolean hasGraph = args.has("graph");
        boolean hasStaged = args.has("staged");

        if (hasFile && hasStaged) return "diff";
        if (hasFile) return "diff";
        if (hasMaxCount || hasGraph) return "log";
        // 无特殊参数默认 status（查看状态）
        return "status";
    }

    private String inferGitSubmitAction(JsonNode args) {
        boolean hasMessage = isNonEmpty(args, "message");
        boolean hasPath = isNonEmpty(args, "path");
        boolean hasBranch = isNonEmpty(args, "branch");
        boolean hasSetUpstream = args.has("set_upstream");

        if (hasMessage) return "commit";
        if (hasBranch || hasSetUpstream) return "push";
        if (hasPath) return "add";
        return null;
    }

    private String inferGitBranchAction(JsonNode args) {
        boolean hasName = isNonEmpty(args, "name");
        boolean hasAll = args.has("all");
        boolean hasForce = args.has("force");

        if (hasAll) return "list";
        if (hasName && hasForce) return "delete";
        if (hasName) return "switch"; // 最常用的非 list 操作
        // 无参数默认 list
        return "list";
    }

    private String inferAgentAction(JsonNode args) {
        boolean hasInstructions = isNonEmpty(args, "instructions");
        boolean hasTools = args.has("tools");
        boolean hasScope = isNonEmpty(args, "scope");
        boolean hasAgentId = isNonEmpty(args, "agent_id");
        boolean hasTimeout = args.has("timeout");

        if (hasInstructions || hasTools) return "fork";
        if ((hasAgentId && hasTimeout) || (hasAgentId && hasScope)) return "collect";
        if (hasScope) return "inspect";
        if (hasAgentId) return "collect"; // 最常用
        return null;
    }

    private String inferSkillAction(JsonNode args) {
        boolean hasName = isNonEmpty(args, "name");
        boolean hasSkillId = args.has("skill_id");
        boolean hasSuccess = args.has("success");
        boolean hasTriggerWords = args.has("trigger_words");

        if (hasSuccess) return "report";
        if (hasName && hasTriggerWords) return "create";
        if (hasName) return "create";
        if (hasSkillId && !hasSuccess) return "update";
        // 无参数默认 list
        if (!hasName && !hasSkillId) return "list";
        return null;
    }

    private String inferTaskManagerAction(JsonNode args) {
        boolean hasTasks = args.has("tasks");
        boolean hasTaskIds = args.has("task_ids");
        boolean hasTaskId = isNonEmpty(args, "task_id");

        if (hasTasks) return "create";
        if (hasTaskIds) return "batch_complete";
        if (hasTaskId) return "complete";
        // 无参数默认 list（查看全部任务）
        return "list";
    }

    private String inferChatAttachmentAction(JsonNode args) {
        boolean hasAttachmentId = isNonEmpty(args, "attachment_id");
        boolean hasFilePath = isNonEmpty(args, "file_path");

        if (hasAttachmentId) return "read_by_attachment";
        if (hasFilePath) return "read_by_path";
        return null;
    }

    private String inferScheduleTaskAction(JsonNode args) {
        boolean hasName = isNonEmpty(args, "name");
        boolean hasId = args.has("id");
        boolean hasEnabled = args.has("enabled");
        boolean hasNaturalTime = isNonEmpty(args, "natural_time");
        boolean hasCronExpr = isNonEmpty(args, "cron_expression");
        boolean hasExecuteTime = isNonEmpty(args, "execute_time");

        if (hasEnabled && hasId) return "toggle";
        if (hasName && (hasNaturalTime || hasCronExpr || hasExecuteTime)) return "create";
        if (hasName) return "create";
        if (hasId) return "update";
        // 无参数默认 list
        return "list";
    }

    private boolean isNonEmpty(JsonNode node, String key) {
        if (!node.has(key) || node.get(key).isNull()) return false;
        return !node.path(key).asText("").isEmpty();
    }

    private String buildInferReason(String toolName, JsonNode args, String inferredAction) {
        // 提取关键参数名用于日志
        List<String> keys = new ArrayList<>();
        args.fieldNames().forEachRemaining(keys::add);
        List<String> keyParams = keys.stream()
                .filter(k -> !k.equals("action"))
                .limit(3)
                .toList();
        return toolName + " 缺少 action，根据参数 " + String.join(", ", keyParams)
                + " 自动推断为 " + inferredAction;
    }

    /**
     * 构建无法自动补齐时的精确修复指导
     */
    private String buildActionGuidance(String toolName, JsonNode args) {
        String actionOptions = getActionOptions(toolName);
        String argsJson = args.toString();
        if (argsJson.length() > 300) argsJson = argsJson.substring(0, 300) + "...";

        return "🔧【自动修复提示】工具 '" + toolName + "' 缺少必填参数 'action'。\n"
                + "你的参数: " + argsJson + "\n"
                + "可选的 action 值: " + actionOptions + "\n\n"
                + "请根据你的意图选择一个 action 并重新调用。正确示例：\n"
                + buildCorrectExample(toolName) + "\n\n"
                + "💡 提示：系统已尝试自动推断但信息不足，请明确指定 action。\n"
                + "如果你想执行最常见的操作，建议 action=\"" + getDefaultAction(toolName) + "\"。";
    }

    private String getActionOptions(String toolName) {
        return switch (toolName) {
            case "file_explorer" -> "read / glob / grep / tree";
            case "file_writer" -> "write / edit / delete";
            case "command" -> "exec / start / list / logs / stop";
            case "git_query" -> "status / diff / log";
            case "git_submit" -> "add / commit / push";
            case "git_branch" -> "list / create / switch / delete";
            case "agent" -> "fork / collect / inspect";
            case "skill" -> "create / update / delete / list / report";
            case "task_manager" -> "create / complete / batch_complete / batch_reopen / list";
            case "chat_attachment" -> "read_by_path / read_by_attachment";
            case "schedule_task" -> "create / list / update / delete / toggle";
            default -> "（未知）";
        };
    }

    private String getDefaultAction(String toolName) {
        return switch (toolName) {
            case "file_explorer" -> "read";
            case "file_writer" -> "write";
            case "command" -> "exec";
            case "git_query" -> "status";
            case "git_submit" -> "add";
            case "git_branch" -> "list";
            case "agent" -> "fork";
            case "skill" -> "list";
            case "task_manager" -> "list";
            case "chat_attachment" -> "read_by_path";
            case "schedule_task" -> "list";
            default -> "";
        };
    }

    private String buildCorrectExample(String toolName) {
        return switch (toolName) {
            case "file_explorer" -> "{ \"action\": \"read\", \"file_path\": \"src/main/App.java\" }";
            case "file_writer" -> "{ \"action\": \"write\", \"file_path\": \"src/main/NewFile.java\", \"content\": \"...\" }";
            case "command" -> "{ \"action\": \"exec\", \"command\": \"mvn clean compile\" }";
            case "git_query" -> "{ \"action\": \"status\" }";
            case "git_submit" -> "{ \"action\": \"add\", \"path\": \"src/main/App.java\" }";
            case "git_branch" -> "{ \"action\": \"list\" }";
            case "agent" -> "{ \"action\": \"fork\", \"agent_id\": \"sub-1\", \"name\": \"子Agent\", \"instructions\": \"...\", \"tools\": [\"file_explorer\"] }";
            case "skill" -> "{ \"action\": \"list\" }";
            case "task_manager" -> "{ \"action\": \"list\" }";
            case "chat_attachment" -> "{ \"action\": \"read_by_path\", \"file_path\": \"E:\\\\docs\\\\report.pdf\" }";
            case "schedule_task" -> "{ \"action\": \"list\" }";
            default -> "{ \"action\": \"...\" }";
        };
    }
}
