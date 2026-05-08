package com.example.agentdeepseek.tool.impl;

import com.example.agentdeepseek.tool.Tool;
import com.example.agentdeepseek.util.ToolContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 任务管理器工具
 * AI 在任务拆解后调用此工具登记任务，每条任务完成后标记完成，
 * 出现问题可重新标记为未完成，汇总输出前必须检查全部任务状态。
 * 数据按会话隔离，纯内存存储。
 */
@Slf4j
@Component
public class TaskManagerTool implements Tool {

    private final ObjectMapper objectMapper;

    /** 会话ID -> 任务列表 */
    private final ConcurrentHashMap<Long, List<TaskItem>> taskMap = new ConcurrentHashMap<>();

    public TaskManagerTool(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public String getName() {
        return "task_manager";
    }

    @Override
    public String getDescription() {
        return "任务管理器：管理任务的生命周期。支持四种操作：\n"
                + "① create — 创建任务列表（批量），将分析拆解后的任务登记到系统\n"
                + "② complete — 标记指定任务为已完成\n"
                + "③ reopen — 将已完成的任务重新标记为未完成（如执行失败需要重试）\n"
                + "④ list — 查看当前所有任务及其状态（汇总输出前必须调用此操作确认全部完成）\n"
                + "注意：task_id 格式为 T1、T2、T3...";
    }

    @Override
    public JsonNode getParameters() {
        ObjectNode parameters = objectMapper.createObjectNode();
        parameters.put("type", "object");

        ObjectNode properties = objectMapper.createObjectNode();

        // action
        ObjectNode action = objectMapper.createObjectNode();
        action.put("type", "string");
        action.put("description", "操作类型：create（创建任务列表）、complete（完成任务）、reopen（重新打开任务）、list（查看所有任务）");
        ArrayNode enumValues = objectMapper.createArrayNode();
        enumValues.add("create");
        enumValues.add("complete");
        enumValues.add("reopen");
        enumValues.add("list");
        action.set("enum", enumValues);
        properties.set("action", action);

        // tasks (for create)
        ObjectNode tasks = objectMapper.createObjectNode();
        tasks.put("type", "array");
        tasks.put("description", "任务列表（create 操作必填）：[{ \"id\": \"T1\", \"description\": \"任务描述\" }]");
        ObjectNode taskItem = objectMapper.createObjectNode();
        taskItem.put("type", "object");
        ObjectNode taskItemProps = objectMapper.createObjectNode();
        ObjectNode idField = objectMapper.createObjectNode();
        idField.put("type", "string");
        idField.put("description", "任务 ID，如 T1、T2");
        taskItemProps.set("id", idField);
        ObjectNode descField = objectMapper.createObjectNode();
        descField.put("type", "string");
        descField.put("description", "任务描述");
        taskItemProps.set("description", descField);
        taskItem.set("properties", taskItemProps);
        ArrayNode requiredFields = objectMapper.createArrayNode();
        requiredFields.add("id");
        requiredFields.add("description");
        taskItem.set("required", requiredFields);
        tasks.set("items", taskItem);
        properties.set("tasks", tasks);

        // task_id (for complete/reopen)
        ObjectNode taskId = objectMapper.createObjectNode();
        taskId.put("type", "string");
        taskId.put("description", "任务 ID（complete/reopen 操作必填），如 T1、T2");
        properties.set("task_id", taskId);

        parameters.set("properties", properties);
        ArrayNode required = objectMapper.createArrayNode();
        required.add("action");
        parameters.set("required", required);
        return parameters;
    }

    @Override
    public String execute(JsonNode arguments) {
        Long sessionId = ToolContext.getConversationId();
        if (sessionId == null) {
            return "错误：无法获取会话 ID";
        }

        String action = arguments.path("action").asText();
        if (action.isEmpty()) {
            return "错误：缺少必要参数 action";
        }

        switch (action) {
            case "create":
                return handleCreate(sessionId, arguments);
            case "complete":
                return handleComplete(sessionId, arguments);
            case "reopen":
                return handleReopen(sessionId, arguments);
            case "list":
                return handleList(sessionId);
            default:
                return "错误：不支持的 action 类型 - " + action;
        }
    }

    private String handleCreate(Long sessionId, JsonNode arguments) {
        JsonNode tasksNode = arguments.path("tasks");
        if (tasksNode.isMissingNode() || !tasksNode.isArray() || tasksNode.isEmpty()) {
            return "错误：create 操作需要提供 tasks 数组";
        }

        List<TaskItem> tasks = new ArrayList<>();
        for (JsonNode item : tasksNode) {
            String id = item.path("id").asText();
            String description = item.path("description").asText();
            if (id.isEmpty() || description.isEmpty()) {
                return "错误：任务项的 id 和 description 不能为空";
            }
            tasks.add(new TaskItem(id, description));
        }

        taskMap.put(sessionId, tasks);
        log.info("会话 {} 创建了 {} 个任务", sessionId, tasks.size());

        StringBuilder sb = new StringBuilder();
        sb.append("✅ 任务清单已创建（共 ").append(tasks.size()).append(" 项）：\n");
        for (TaskItem task : tasks) {
            sb.append("  ").append(task.id).append(" - ").append(task.description).append(" [⏳ 待完成]\n");
        }
        sb.append("\n请按顺序执行各项任务，每完成一项后调用 complete 操作标记。");
        return sb.toString();
    }

    private String handleComplete(Long sessionId, JsonNode arguments) {
        String taskId = arguments.path("task_id").asText();
        if (taskId.isEmpty()) {
            return "错误：complete 操作需要提供 task_id";
        }

        List<TaskItem> tasks = taskMap.get(sessionId);
        if (tasks == null || tasks.isEmpty()) {
            return "错误：当前会话没有任务记录，请先使用 create 创建任务";
        }

        for (TaskItem task : tasks) {
            if (task.id.equals(taskId)) {
                if (task.status == TaskStatus.COMPLETED) {
                    return "提示：任务 " + taskId + " 已经是完成状态";
                }
                task.status = TaskStatus.COMPLETED;
                log.info("会话 {} 任务 {} 标记完成", sessionId, taskId);
                return "✅ 任务 " + taskId + " 已完成！\n" + getProgressSummary(tasks);
            }
        }

        return "错误：未找到任务 " + taskId + "，请检查任务 ID 是否正确";
    }

    private String handleReopen(Long sessionId, JsonNode arguments) {
        String taskId = arguments.path("task_id").asText();
        if (taskId.isEmpty()) {
            return "错误：reopen 操作需要提供 task_id";
        }

        List<TaskItem> tasks = taskMap.get(sessionId);
        if (tasks == null || tasks.isEmpty()) {
            return "错误：当前会话没有任务记录，请先使用 create 创建任务";
        }

        for (TaskItem task : tasks) {
            if (task.id.equals(taskId)) {
                if (task.status == TaskStatus.PENDING) {
                    return "提示：任务 " + taskId + " 已经是待完成状态";
                }
                task.status = TaskStatus.PENDING;
                log.info("会话 {} 任务 {} 重新打开", sessionId, taskId);
                return "🔄 任务 " + taskId + " 已重新打开，请继续处理！\n" + getProgressSummary(tasks);
            }
        }

        return "错误：未找到任务 " + taskId + "，请检查任务 ID 是否正确";
    }

    private String handleList(Long sessionId) {
        List<TaskItem> tasks = taskMap.get(sessionId);
        if (tasks == null || tasks.isEmpty()) {
            return "当前会话没有任务记录";
        }

        long completed = tasks.stream().filter(t -> t.status == TaskStatus.COMPLETED).count();
        long total = tasks.size();
        boolean allDone = completed == total;

        StringBuilder sb = new StringBuilder();
        if (allDone) {
            sb.append("✅ 全部任务已完成（").append(total).append("/").append(total).append("）\n\n");
        } else {
            sb.append("⏳ 任务进度：").append(completed).append("/").append(total).append(" 已完成\n\n");
        }

        for (TaskItem task : tasks) {
            String statusIcon = task.status == TaskStatus.COMPLETED ? "✅" : "⏳";
            sb.append("  ").append(statusIcon).append(" ").append(task.id).append(" - ").append(task.description).append("\n");
        }

        if (!allDone) {
            sb.append("\n⚠️ 还有 ").append(total - completed).append(" 个任务未完成，请继续处理。");
        }

        return sb.toString();
    }

    private String getProgressSummary(List<TaskItem> tasks) {
        long completed = tasks.stream().filter(t -> t.status == TaskStatus.COMPLETED).count();
        long total = tasks.size();
        return "当前进度： " + completed + "/" + total + " 已完成";
    }

    private enum TaskStatus {
        PENDING, COMPLETED
    }

    private static class TaskItem {
        final String id;
        final String description;
        TaskStatus status;

        TaskItem(String id, String description) {
            this.id = id;
            this.description = description;
            this.status = TaskStatus.PENDING;
        }
    }
}
