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
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.stream.Collectors;

import com.example.agentdeepseek.tool.permission.OperationCategory;
import com.example.agentdeepseek.tool.permission.ToolPermission;

/**
 * 任务管理器工具
 * AI 在任务拆解后调用此工具登记任务，每条任务完成后标记完成，
 * 出现问题可重新标记为未完成，汇总输出前必须检查全部任务状态。
 * 数据按会话隔离，纯内存存储。
 * 
 * 支持操作：create / batch_complete / batch_reopen / complete / reopen / list
 * 返回格式：可读文本 + 末尾结构化 JSON（---TASK_JSON--- 分隔），前端优先解析 JSON
 */
@Slf4j
@Component
@ToolPermission(category = OperationCategory.ADMIN, affectsData = true, description = "管理后台任务")
public class TaskManagerTool implements Tool {

    private final ObjectMapper objectMapper;

    /** 会话ID -> 任务列表 */
    private final ConcurrentHashMap<Long, List<TaskItem>> taskMap = new ConcurrentHashMap<>();

    /** 读写锁：保护单个会话内的任务列表操作（每个会话一把锁） */
    private final ConcurrentHashMap<Long, ReadWriteLock> sessionLocks = new ConcurrentHashMap<>();

    /** 最后访问时间，用于过期清理 */
    private final ConcurrentHashMap<Long, Long> lastAccessTime = new ConcurrentHashMap<>();

    /** 会话超时时间（毫秒）：30分钟 */
    private static final long SESSION_TIMEOUT_MS = 30 * 60 * 1000;

    public TaskManagerTool(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public String getName() {
        return "task_manager";
    }

    @Override
    public String getDescription() {
        return "管理任务的生命周期，将拆解后的子任务登记、追踪、标记完成。\n"
                + "【适用场景】任何需要多步骤完成的复杂任务，如「实现用户注册功能」需拆解为建表、写API、写前端等子任务\n"
                + "【使用方式】① 先用 action=create 批量登记任务（task_id 格式 T1/T2/T3...），② 每完成一项调用 action=batch_complete 标记（推荐批量，减少调用次数），③ 执行失败需重试时用 action=batch_reopen 重新打开，④ 汇总输出前必须调用 action=list 确认全部完成\n"
                + "【区别】batch_complete 比 complete 更高效，一次可标记多个任务；batch_reopen 用于将已完成的任务回退到待处理\n"
                + "【字段说明】priority（HIGH/MEDIUM/LOW）控制任务优先级，depends_on 指定前置依赖任务，group 指定任务所属分组（同组可并行）";
    }

    @Override
    public JsonNode getParameters() {
        ObjectNode parameters = objectMapper.createObjectNode();
        parameters.put("type", "object");

        ObjectNode properties = objectMapper.createObjectNode();

        // action
        ObjectNode action = objectMapper.createObjectNode();
        action.put("type", "string");
        action.put("description", "【必填】操作类型。用法：create=首次登记任务列表，batch_complete=完成任务后批量标记（推荐），batch_reopen=执行失败需重试时回退任务，list=汇总前确认进度");
        ArrayNode enumValues = objectMapper.createArrayNode();
        enumValues.add("create");
        enumValues.add("complete");
        enumValues.add("batch_complete");
        enumValues.add("batch_reopen");
        enumValues.add("list");
        action.set("enum", enumValues);
        properties.set("action", action);

        // tasks (for create)
        ObjectNode tasks = objectMapper.createObjectNode();
        tasks.put("type", "array");
        tasks.put("description", "【create 时必填】任务数组。示例：[{ \"id\": \"T1\", \"description\": \"创建 users 表\", \"priority\": \"HIGH\", \"depends_on\": [], \"group\": \"backend\" }]");
        ObjectNode taskItem = objectMapper.createObjectNode();
        taskItem.put("type", "object");
        ObjectNode taskItemProps = objectMapper.createObjectNode();
        ObjectNode idField = objectMapper.createObjectNode();
        idField.put("type", "string");
        idField.put("description", "【必填】任务编号，格式 T1/T2/T3...（数字递增，如 T1、T2、T3）");
        taskItemProps.set("id", idField);
        ObjectNode descField = objectMapper.createObjectNode();
        descField.put("type", "string");
        descField.put("description", "【必填】一句话描述任务内容。示例：\"创建 users 数据库表并添加索引\"");
        taskItemProps.set("description", descField);
        ObjectNode priorityField = objectMapper.createObjectNode();
        priorityField.put("type", "string");
        priorityField.put("description", "【可选】优先级，默认 MEDIUM。HIGH=优先处理，MEDIUM=常规，LOW=最后处理。示例：\"HIGH\"");
        ArrayNode priorityEnum = objectMapper.createArrayNode();
        priorityEnum.add("HIGH");
        priorityEnum.add("MEDIUM");
        priorityEnum.add("LOW");
        priorityField.set("enum", priorityEnum);
        taskItemProps.set("priority", priorityField);
        ObjectNode dependsOnField = objectMapper.createObjectNode();
        dependsOnField.put("type", "array");
        dependsOnField.put("description", "【可选】前置依赖任务ID列表。示例：[\"T1\"] 表示 T1 完成后才能开始本任务，[\"T1\", \"T2\"] 表示同时依赖 T1 和 T2");
        ObjectNode dependsItems = objectMapper.createObjectNode();
        dependsItems.put("type", "string");
        dependsOnField.set("items", dependsItems);
        taskItemProps.set("depends_on", dependsOnField);

        ObjectNode groupField = objectMapper.createObjectNode();
        groupField.put("type", "string");
        groupField.put("description", "【可选】任务所属分组名。同组任务可并行执行，不同组按组顺序串行。示例：group=\"backend\"（后端任务组），group=\"frontend\"（前端任务组，与 backend 组可并行）");
        taskItemProps.set("group", groupField);

        taskItem.set("properties", taskItemProps);
        ArrayNode requiredFields = objectMapper.createArrayNode();
        requiredFields.add("id");
        requiredFields.add("description");
        taskItem.set("required", requiredFields);
        tasks.set("items", taskItem);
        properties.set("tasks", tasks);

        // task_id (for complete)
        ObjectNode taskId = objectMapper.createObjectNode();
        taskId.put("type", "string");
        taskId.put("description", "【complete 时必填】单个任务编号。示例：\"T3\"（标记 T3 为完成）");
        properties.set("task_id", taskId);

        // task_ids (for batch_complete/batch_reopen)
        ObjectNode taskIds = objectMapper.createObjectNode();
        taskIds.put("type", "array");
        taskIds.put("description", "【batch_complete / batch_reopen 时必填】要操作的任务ID数组。示例：[\"T1\", \"T2\", \"T3\"]（一次标记多个任务完成或重开）");
        ObjectNode taskIdItem = objectMapper.createObjectNode();
        taskIdItem.put("type", "string");
        taskIds.set("items", taskIdItem);
        properties.set("task_ids", taskIds);

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
            return buildError("【会话无效】无法获取会话 ID，请在对话中调用此工具，不要在独立上下文中使用");
        }

        // 清理过期会话
        cleanExpiredSessions();

        String action = arguments.path("action").asText();
        if (action.isEmpty()) {
            return buildError("【缺少参数】action 字段缺失或为空。请设置 action 为 create/batch_complete/batch_reopen/list 之一");
        }

        ReadWriteLock lock = sessionLocks.computeIfAbsent(sessionId, k -> new ReentrantReadWriteLock());

        switch (action) {
            case "create":
                return handleCreate(sessionId, arguments, lock);
            case "complete":
                return handleComplete(sessionId, arguments, lock, false);
            case "batch_complete":
                return handleBatchComplete(sessionId, arguments, lock);
            case "batch_reopen":
                return handleBatchReopen(sessionId, arguments, lock);
            case "reopen":
                // 向后兼容：单个 reopen 走 batch_reopen 逻辑
                return handleComplete(sessionId, arguments, lock, true);
            case "list":
                return handleList(sessionId, lock);
            default:
                return buildError("【不支持的操作】action 值 '" + action + "' 无效。支持的操作：create / batch_complete / batch_reopen / list。完整流程：先 create 登记任务，执行后用 batch_complete 标记完成，最后 list 确认");
        }
    }

    // ==================== handleCreate ====================

    private String handleCreate(Long sessionId, JsonNode arguments, ReadWriteLock lock) {
        JsonNode tasksNode = arguments.path("tasks");
        if (tasksNode.isMissingNode() || !tasksNode.isArray() || tasksNode.isEmpty()) {
            return buildError("【缺少参数】create 操作需要 tasks 数组。示例：{\"tasks\": [{\"id\": \"T1\", \"description\": \"创建 users 表\"}]}");
        }

        // 检查冲突
        lock.readLock().lock();
        try {
            List<TaskItem> existing = taskMap.get(sessionId);
            if (existing != null && !existing.isEmpty()) {
                long pending = existing.stream().filter(t -> t.status == TaskStatus.PENDING).count();
                if (pending > 0) {
                    return buildError("【任务冲突】当前会话已有 " + pending + " 个未完成任务。请先调用 action=list 查看进度，完成未完成任务后再 create 新任务清单。如确定要重置，先 batch_complete 剩余任务再重新 create");
                }
            }
        } finally {
            lock.readLock().unlock();
        }

        List<TaskItem> tasks = new ArrayList<>();
        for (JsonNode item : tasksNode) {
            String id = item.path("id").asText();
            String description = item.path("description").asText();
            if (id.isEmpty() || description.isEmpty()) {
                return buildError("【缺少参数】任务项的 id 和 description 不能为空。示例：{\"id\": \"T1\", \"description\": \"创建数据库连接配置\"}");
            }
            String priorityStr = item.path("priority").asText();
            TaskPriority priority = TaskPriority.MEDIUM;
            if (!priorityStr.isEmpty()) {
                try {
                    priority = TaskPriority.valueOf(priorityStr.toUpperCase());
                } catch (IllegalArgumentException e) {
                    return buildError("【无效参数】优先级值 '" + priorityStr + "' 不合法。合法值：HIGH / MEDIUM / LOW（不区分大小写），如 \"HIGH\"");
                }
            }
            List<String> dependsOn = new ArrayList<>();
            JsonNode dependsNode = item.path("depends_on");
            if (dependsNode.isArray()) {
                for (JsonNode dep : dependsNode) {
                    dependsOn.add(dep.asText());
                }
            }
            String group = item.path("group").asText("");
            tasks.add(new TaskItem(id, description, priority, dependsOn, group));
        }

        lock.writeLock().lock();
        try {
            taskMap.put(sessionId, tasks);
            lastAccessTime.put(sessionId, System.currentTimeMillis());
        } finally {
            lock.writeLock().unlock();
        }

        log.info("会话 {} 创建了 {} 个任务", sessionId, tasks.size());

        // 按分组输出，同组可并行
        Map<String, List<TaskItem>> grouped = tasks.stream()
                .collect(java.util.stream.Collectors.groupingBy(
                    t -> t.group.isEmpty() ? "_default" : t.group,
                    java.util.stream.Collectors.toList()
                ));

        StringBuilder sb = new StringBuilder();
        sb.append("✅ 任务清单已创建（共 ").append(tasks.size()).append(" 项）：\n\n");

        // 先输出有分组的
        for (var entry : grouped.entrySet()) {
            String groupName = entry.getKey();
            List<TaskItem> groupTasks = entry.getValue();
            if ("_default".equals(groupName)) {
                for (TaskItem task : groupTasks) {
                    appendTaskItem(sb, task);
                }
            } else {
                boolean hasDeps = groupTasks.stream().anyMatch(t -> !t.dependsOn.isEmpty());
                sb.append("  ── 组: ").append(groupName)
                  .append("（").append(groupTasks.size()).append(" 项")
                  .append(hasDeps ? "，按顺序执行" : "，可并行执行")
                  .append("）──\n");
                for (TaskItem task : groupTasks) {
                    appendTaskItem(sb, task);
                }
            }
        }

        sb.append("\n请按顺序执行各项任务，每完成一项后调用 complete 操作标记。");
        if (tasks.stream().anyMatch(t -> !t.dependsOn.isEmpty())) {
            sb.append("\n注意：部分任务有依赖关系，请确保依赖任务完成后再开始。");
        }
        // 附加结构化 JSON
        sb.append("\n\n---TASK_JSON---\n");
        sb.append(toTaskListJson(tasks));
        return sb.toString();
    }

    // ==================== handleComplete (单个) ====================

    private String handleComplete(Long sessionId, JsonNode arguments, ReadWriteLock lock, boolean isReopen) {
        String taskId = arguments.path("task_id").asText();
        if (taskId.isEmpty()) {
            return buildError("【缺少参数】" + (isReopen ? "batch_reopen" : "complete") + " 操作需要 task_id。示例：{\"action\": \"complete\", \"task_id\": \"T1\"}");
        }

        lock.writeLock().lock();
        try {
            List<TaskItem> tasks = getTasksOrNull(sessionId);
            if (tasks == null) {
                return buildError("【无任务记录】当前会话没有已创建的任务。请先调用 action=create 登记任务列表，然后再标记单个任务完成");
            }

            for (TaskItem task : tasks) {
                if (task.id.equals(taskId)) {
                    TaskStatus targetStatus = isReopen ? TaskStatus.PENDING : TaskStatus.COMPLETED;
                    if (task.status == targetStatus) {
                        return buildResult(taskId + (isReopen ? " 已经是待完成状态" : " 已经标记为完成"));
                    }
                    task.status = targetStatus;
                    lastAccessTime.put(sessionId, System.currentTimeMillis());
                    log.info("会话 {} 任务 {} {}", sessionId, taskId, isReopen ? "重新打开" : "标记完成");

                    String icon = isReopen ? "🔄" : "✅";
                    String actionText = isReopen ? "已重新打开" : "已完成";
                    return buildResult(icon + " 任务 " + taskId + " " + actionText + "！\n" + getProgressSummary(tasks), tasks);
                }
            }
            return buildError("【任务不存在】未找到任务 " + taskId + "。请用 action=list 查看当前任务列表，确认正确的 task_id 后重试");
        } finally {
            lock.writeLock().unlock();
        }
    }

    // ==================== handleBatchComplete ====================

    private String handleBatchComplete(Long sessionId, JsonNode arguments, ReadWriteLock lock) {
        JsonNode idsNode = arguments.path("task_ids");
        if (idsNode.isMissingNode() || !idsNode.isArray() || idsNode.isEmpty()) {
            return buildError("【缺少参数】batch_complete 操作需要 task_ids 数组。示例：{\"action\": \"batch_complete\", \"task_ids\": [\"T1\", \"T2\"]}");
        }

        List<String> ids = new ArrayList<>();
        for (JsonNode idNode : idsNode) {
            ids.add(idNode.asText());
        }

        lock.writeLock().lock();
        try {
            List<TaskItem> tasks = getTasksOrNull(sessionId);
            if (tasks == null) {
                return buildError("【无任务记录】当前会话没有已创建的任务。请先调用 action=create 登记任务列表，然后再批量标记完成");
            }

            List<String> completed = new ArrayList<>();
            List<String> notFound = new ArrayList<>();
            List<String> alreadyDone = new ArrayList<>();

            for (String id : ids) {
                boolean found = false;
                for (TaskItem task : tasks) {
                    if (task.id.equals(id)) {
                        found = true;
                        if (task.status == TaskStatus.COMPLETED) {
                            alreadyDone.add(id);
                        } else {
                            task.status = TaskStatus.COMPLETED;
                            completed.add(id);
                        }
                        break;
                    }
                }
                if (!found) {
                    notFound.add(id);
                }
            }

            lastAccessTime.put(sessionId, System.currentTimeMillis());
            log.info("会话 {} 批量完成任务: {}", sessionId, completed);

            StringBuilder sb = new StringBuilder();
            if (!completed.isEmpty()) {
                sb.append("✅ 批量完成任务：").append(String.join(", ", completed)).append("\n");
            }
            if (!alreadyDone.isEmpty()) {
                sb.append("⚠️ 已是完成状态：").append(String.join(", ", alreadyDone)).append("\n");
            }
            if (!notFound.isEmpty()) {
                sb.append("❌ 未找到任务：").append(String.join(", ", notFound)).append("\n");
            }
            sb.append(getProgressSummary(tasks));
            return buildResult(sb.toString(), tasks);
        } finally {
            lock.writeLock().unlock();
        }
    }

    // ==================== handleBatchReopen ====================

    private String handleBatchReopen(Long sessionId, JsonNode arguments, ReadWriteLock lock) {
        JsonNode idsNode = arguments.path("task_ids");
        if (idsNode.isMissingNode() || !idsNode.isArray() || idsNode.isEmpty()) {
            return buildError("【缺少参数】batch_reopen 操作需要 task_ids 数组。示例：{\"action\": \"batch_reopen\", \"task_ids\": [\"T1\", \"T2\"]}");
        }

        List<String> ids = new ArrayList<>();
        for (JsonNode idNode : idsNode) {
            ids.add(idNode.asText());
        }

        lock.writeLock().lock();
        try {
            List<TaskItem> tasks = getTasksOrNull(sessionId);
            if (tasks == null) {
                return buildError("【无任务记录】当前会话没有已创建的任务。请先调用 action=create 登记任务列表，然后再批量重新打开");
            }

            List<String> reopened = new ArrayList<>();
            List<String> notFound = new ArrayList<>();
            List<String> alreadyPending = new ArrayList<>();

            for (String id : ids) {
                boolean found = false;
                for (TaskItem task : tasks) {
                    if (task.id.equals(id)) {
                        found = true;
                        if (task.status == TaskStatus.PENDING) {
                            alreadyPending.add(id);
                        } else {
                            task.status = TaskStatus.PENDING;
                            reopened.add(id);
                        }
                        break;
                    }
                }
                if (!found) {
                    notFound.add(id);
                }
            }

            lastAccessTime.put(sessionId, System.currentTimeMillis());
            log.info("会话 {} 批量重开任务: {}", sessionId, reopened);

            StringBuilder sb = new StringBuilder();
            if (!reopened.isEmpty()) {
                sb.append("🔄 批量重开任务：").append(String.join(", ", reopened)).append("\n");
            }
            if (!alreadyPending.isEmpty()) {
                sb.append("⚠️ 已是待完成状态：").append(String.join(", ", alreadyPending)).append("\n");
            }
            if (!notFound.isEmpty()) {
                sb.append("❌ 未找到任务：").append(String.join(", ", notFound)).append("\n");
            }
            sb.append(getProgressSummary(tasks));
            return buildResult(sb.toString(), tasks);
        } finally {
            lock.writeLock().unlock();
        }
    }

    // ==================== handleList ====================

    private String handleList(Long sessionId, ReadWriteLock lock) {
        lock.readLock().lock();
        try {
            List<TaskItem> tasks = getTasksOrNull(sessionId);
            if (tasks == null) {
                return buildResult("当前会话没有任务记录");
            }

            lastAccessTime.put(sessionId, System.currentTimeMillis());

            long completed = tasks.stream().filter(t -> t.status == TaskStatus.COMPLETED).count();
            long total = tasks.size();
            boolean allDone = completed == total;

            StringBuilder sb = new StringBuilder();
            if (allDone) {
                sb.append("✅ 全部任务已完成（").append(total).append("/").append(total).append("）\n\n");
            } else {
                sb.append("⏳ 任务进度：").append(completed).append("/").append(total).append(" 已完成\n\n");
            }

            // 按分组输出
            Map<String, List<TaskItem>> grouped = tasks.stream()
                    .collect(java.util.stream.Collectors.groupingBy(
                        t -> t.group.isEmpty() ? "_default" : t.group,
                        java.util.stream.Collectors.toList()
                    ));
            for (var entry : grouped.entrySet()) {
                String groupName = entry.getKey();
                List<TaskItem> groupTasks = entry.getValue();
                if (!"_default".equals(groupName)) {
                    sb.append("  ── 组: ").append(groupName).append(" ──\n");
                }
                for (TaskItem task : groupTasks) {
                    appendTaskItem(sb, task);
                }
            }
            if (!allDone) {
                sb.append("\n⚠️ 还有 ").append(total - completed).append(" 个任务未完成，请继续处理。");
            }

            return buildResult(sb.toString(), tasks);
        } finally {
            lock.readLock().unlock();
        }
    }

    // ==================== 辅助方法 ====================

    private List<TaskItem> getTasksOrNull(Long sessionId) {
        List<TaskItem> tasks = taskMap.get(sessionId);
        if (tasks == null || tasks.isEmpty()) return null;
        return tasks;
    }

    /**
     * 按优先级排序（HIGH → MEDIUM → LOW），同优先级按 ID 排序
     */
    private List<TaskItem> sortByPriority(List<TaskItem> tasks) {
        return tasks.stream()
                .sorted(Comparator.comparingInt((TaskItem t) -> t.priority.ordinal())
                        .thenComparing(t -> t.id))
                .collect(Collectors.toList());
    }

    private String getProgressSummary(List<TaskItem> tasks) {
        long completed = tasks.stream().filter(t -> t.status == TaskStatus.COMPLETED).count();
        long total = tasks.size();
        return "当前进度：" + completed + "/" + total + " 已完成";
    }

    /**
     * 构建任务列表的结构化 JSON
     */
    private String toTaskListJson(List<TaskItem> tasks) {
        try {
            ArrayNode arr = objectMapper.createArrayNode();
            for (TaskItem t : tasks) {
                ObjectNode node = objectMapper.createObjectNode();
                node.put("id", t.id);
                node.put("description", t.description);
                node.put("status", t.status.name().toLowerCase());
                node.put("priority", t.priority.name());
                if (!t.group.isEmpty()) {
                    node.put("group", t.group);
                }
                if (!t.dependsOn.isEmpty()) {
                    ArrayNode deps = objectMapper.createArrayNode();
                    for (String dep : t.dependsOn) deps.add(dep);
                    node.set("depends_on", deps);
                }
                arr.add(node);
            }
            return objectMapper.writeValueAsString(arr);
        } catch (Exception e) {
            return "[]";
        }
    }

    /**
     * 构建返回结果（文本 + 结构化 JSON）
     */
    private String buildResult(String text) {
        return text;
    }

    private String buildResult(String text, List<TaskItem> tasks) {
        if (tasks == null) return text;
        return text + "\n\n---TASK_JSON---\n" + toTaskListJson(tasks);
    }

    private String buildError(String message) {
        return message;
    }

    /**
     * 清理过期会话数据
     */
    private void cleanExpiredSessions() {
        long now = System.currentTimeMillis();
        List<Long> expired = new ArrayList<>();
        for (var entry : lastAccessTime.entrySet()) {
            if (now - entry.getValue() > SESSION_TIMEOUT_MS) {
                expired.add(entry.getKey());
            }
        }
        for (Long id : expired) {
            taskMap.remove(id);
            sessionLocks.remove(id);
            lastAccessTime.remove(id);
            log.info("清理过期会话任务数据: {}", id);
        }
    }

    // ==================== 内部类 ====================

    private enum TaskStatus {
        PENDING, COMPLETED
    }

    private enum TaskPriority {
        HIGH, MEDIUM, LOW
    }

    private static class TaskItem {
        final String id;
        final String description;
        final TaskPriority priority;
        final List<String> dependsOn;
        final String group;
        TaskStatus status;

        TaskItem(String id, String description, TaskPriority priority, List<String> dependsOn, String group) {
            this.id = id;
            this.description = description;
            this.priority = priority != null ? priority : TaskPriority.MEDIUM;
            this.dependsOn = dependsOn != null ? dependsOn : new ArrayList<>();
            this.group = group != null ? group : "";
            this.status = TaskStatus.PENDING;
        }
    }

    /**
     * 添加任务项到 StringBuilder（含状态、优先级、依赖信息）
     */
    private void appendTaskItem(StringBuilder sb, TaskItem task) {
        String statusIcon = task.status == TaskStatus.COMPLETED ? "✅" : "⏳";
        sb.append("  ").append(statusIcon).append(" ").append(task.id).append(" - ").append(task.description);
        if (task.priority == TaskPriority.HIGH) {
            sb.append(" [HIGH]");
        }
        if (!task.dependsOn.isEmpty()) {
            sb.append(" ← 依赖: ").append(String.join(", ", task.dependsOn));
        }
        sb.append("\n");
    }
}
