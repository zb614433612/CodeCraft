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
        return "任务管理器：管理任务的生命周期。支持五种操作：\n"
                + "① create — 创建任务列表（批量），将分析拆解后的任务登记到系统\n"
                + "② complete — 标记指定任务为已完成\n"
                + "③ batch_complete — 批量标记多个任务为已完成（推荐，减少调用次数）\n"
                + "④ batch_reopen — 批量将已完成的任务重新标记为未完成（如执行失败需要重试）\n"
                + "⑤ list — 查看当前所有任务及其状态（汇总输出前必须调用此操作确认全部完成）\n"
                + "注意：task_id 格式为 T1、T2、T3...\n"
                + "任务支持 priority 字段（HIGH/MEDIUM/LOW）和 depends_on 依赖字段。";
    }

    @Override
    public JsonNode getParameters() {
        ObjectNode parameters = objectMapper.createObjectNode();
        parameters.put("type", "object");

        ObjectNode properties = objectMapper.createObjectNode();

        // action
        ObjectNode action = objectMapper.createObjectNode();
        action.put("type", "string");
        action.put("description", "操作类型：create（创建任务列表）、complete（完成单个任务）、batch_complete（批量完成）、batch_reopen（批量重开）、list（查看所有任务）");
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
        tasks.put("description", "任务列表（create 操作必填）：[{ \"id\": \"T1\", \"description\": \"任务描述\", \"priority\": \"HIGH\", \"depends_on\": [] }]");
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
        ObjectNode priorityField = objectMapper.createObjectNode();
        priorityField.put("type", "string");
        priorityField.put("description", "优先级：HIGH/MEDIUM/LOW，默认 MEDIUM");
        ArrayNode priorityEnum = objectMapper.createArrayNode();
        priorityEnum.add("HIGH");
        priorityEnum.add("MEDIUM");
        priorityEnum.add("LOW");
        priorityField.set("enum", priorityEnum);
        taskItemProps.set("priority", priorityField);
        ObjectNode dependsOnField = objectMapper.createObjectNode();
        dependsOnField.put("type", "array");
        dependsOnField.put("description", "依赖的任务ID列表（可选），如 [\"T1\"] 表示依赖T1完成后才能开始");
        ObjectNode dependsItems = objectMapper.createObjectNode();
        dependsItems.put("type", "string");
        dependsOnField.set("items", dependsItems);
        taskItemProps.set("depends_on", dependsOnField);

        ObjectNode groupField = objectMapper.createObjectNode();
        groupField.put("type", "string");
        groupField.put("description", "分组名（可选），同一组的任务可以并行执行。不同组的任务按组顺序串行。例如：group=\"backend\" 和 group=\"frontend\" 可并行执行");
        taskItemProps.set("group", groupField);

        taskItem.set("properties", taskItemProps);
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
        taskId.put("description", "任务 ID（complete 操作必填），如 T1、T2");
        properties.set("task_id", taskId);

        // task_ids (for batch_complete/batch_reopen)
        ObjectNode taskIds = objectMapper.createObjectNode();
        taskIds.put("type", "array");
        taskIds.put("description", "任务 ID 数组（batch_complete/batch_reopen 操作必填），如 [\"T1\", \"T2\"]");
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
            return buildError("无法获取会话 ID");
        }

        // 清理过期会话
        cleanExpiredSessions();

        String action = arguments.path("action").asText();
        if (action.isEmpty()) {
            return buildError("缺少必要参数 action");
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
                return buildError("不支持的操作类型 - " + action + "，支持：create / complete / batch_complete / batch_reopen / list");
        }
    }

    // ==================== handleCreate ====================

    private String handleCreate(Long sessionId, JsonNode arguments, ReadWriteLock lock) {
        JsonNode tasksNode = arguments.path("tasks");
        if (tasksNode.isMissingNode() || !tasksNode.isArray() || tasksNode.isEmpty()) {
            return buildError("create 操作需要提供 tasks 数组");
        }

        // 检查冲突
        lock.readLock().lock();
        try {
            List<TaskItem> existing = taskMap.get(sessionId);
            if (existing != null && !existing.isEmpty()) {
                long pending = existing.stream().filter(t -> t.status == TaskStatus.PENDING).count();
                if (pending > 0) {
                    return buildError("当前会话已有 " + pending + " 个未完成任务，请先完成或使用 list 查看。如需重新规划，请先完成所有任务后再 create。");
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
                return buildError("任务项的 id 和 description 不能为空");
            }
            String priorityStr = item.path("priority").asText();
            TaskPriority priority = TaskPriority.MEDIUM;
            if (!priorityStr.isEmpty()) {
                try {
                    priority = TaskPriority.valueOf(priorityStr.toUpperCase());
                } catch (IllegalArgumentException e) {
                    return buildError("无效的优先级: " + priorityStr + "，支持 HIGH/MEDIUM/LOW");
                }
            }
            List<String> dependsOn = new ArrayList<>();
            JsonNode dependsNode = item.path("depends_on");
            if (dependsNode.isArray()) {
                for (JsonNode dep : dependsNode) {
                    dependsOn.add(dep.asText());
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

        log.info("ä¼è¯ {} åå»ºäº {} ä¸ªä»»å¡", sessionId, tasks.size());

        // æç»åç»è¾åºï¼åç»å¯å¹¶è¡
        Map<String, List<TaskItem>> grouped = tasks.stream()
                .collect(java.util.stream.Collectors.groupingBy(
                    t -> t.group.isEmpty() ? "_default" : t.group,
                    java.util.stream.Collectors.toList()
                ));

        StringBuilder sb = new StringBuilder();
        sb.append("â ä»»å¡æ¸åå·²åå»ºï¼å± ").append(tasks.size()).append(" é¡¹ï¼ï¼\n\n");

        // åè¾åºæåç»ç
        for (var entry : grouped.entrySet()) {
            String groupName = entry.getKey();
            List<TaskItem> groupTasks = entry.getValue();
            if ("_default".equals(groupName)) {
                for (TaskItem task : groupTasks) {
                    appendTaskItem(sb, task);
                }
            } else {
                boolean hasDeps = groupTasks.stream().anyMatch(t -> !t.dependsOn.isEmpty());
                sb.append("  ââ ç»: ").append(groupName)
                  .append("ï¼").append(groupTasks.size()).append(" é¡¹")
                  .append(hasDeps ? "ï¼æé¡ºåºæ§è¡" : "ï¼å¯å¹¶è¡æ§è¡")
                  .append("ï¼ââ\n");
                for (TaskItem task : groupTasks) {
                    appendTaskItem(sb, task);
                }
            }
        }

        sb.append("\nè¯·æé¡ºåºæ§è¡åé¡¹ä»»å¡ï¼æ¯å®æä¸é¡¹åè°ç¨ complete æä½æ è®°ã");
        if (tasks.stream().anyMatch(t -> !t.dependsOn.isEmpty())) {
            sb.append("\næ³¨æï¼é¨åä»»å¡æä¾èµå³ç³»ï¼è¯·ç¡®ä¿ä¾èµä»»å¡å®æååå¼å§ã");
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
            return buildError((isReopen ? "reopen" : "complete") + " 操作需要提供 task_id");
        }

        lock.writeLock().lock();
        try {
            List<TaskItem> tasks = getTasksOrNull(sessionId);
            if (tasks == null) {
                return buildError("当前会话没有任务记录，请先使用 create 创建任务");
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
            return buildError("未找到任务 " + taskId + "，请检查任务 ID 是否正确");
        } finally {
            lock.writeLock().unlock();
        }
    }

    // ==================== handleBatchComplete ====================

    private String handleBatchComplete(Long sessionId, JsonNode arguments, ReadWriteLock lock) {
        JsonNode idsNode = arguments.path("task_ids");
        if (idsNode.isMissingNode() || !idsNode.isArray() || idsNode.isEmpty()) {
            return buildError("batch_complete 操作需要提供 task_ids 数组");
        }

        List<String> ids = new ArrayList<>();
        for (JsonNode idNode : idsNode) {
            ids.add(idNode.asText());
        }

        lock.writeLock().lock();
        try {
            List<TaskItem> tasks = getTasksOrNull(sessionId);
            if (tasks == null) {
                return buildError("当前会话没有任务记录，请先使用 create 创建任务");
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
            return buildError("batch_reopen 操作需要提供 task_ids 数组");
        }

        List<String> ids = new ArrayList<>();
        for (JsonNode idNode : idsNode) {
            ids.add(idNode.asText());
        }

        lock.writeLock().lock();
        try {
            List<TaskItem> tasks = getTasksOrNull(sessionId);
            if (tasks == null) {
                return buildError("当前会话没有任务记录，请先使用 create 创建任务");
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
            lastAccessTime.put(sessionId, System.currentTimeMillis());

            long completed = tasks.stream().filter(t -> t.status == TaskStatus.COMPLETED).count();
            long total = tasks.size();
            boolean allDone = completed == total;

            StringBuilder sb = new StringBuilder();
            if (allDone) {
                sb.append("â å¨é¨ä»»å¡å·²å®æï¼").append(total).append("/").append(total).append("ï¼\n\n");
            } else {
                sb.append("â³ ä»»å¡è¿åº¦ï¼").append(completed).append("/").append(total).append(" å·²å®æ\n\n");
            }

            // æç»åç»è¾åº
            Map<String, List<TaskItem>> grouped = tasks.stream()
                    .collect(java.util.stream.Collectors.groupingBy(
                        t -> t.group.isEmpty() ? "_default" : t.group,
                        java.util.stream.Collectors.toList()
                    ));
            for (var entry : grouped.entrySet()) {
                String groupName = entry.getKey();
                List<TaskItem> groupTasks = entry.getValue();
                if (!"_default".equals(groupName)) {
                    sb.append("  ââ ç»: ").append(groupName).append(" ââ\n");
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
        return "错误：" + message;
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
     * æ·»å ä»»å¡é¡¹å° StringBuilderï¼å«ç¶æãä¼åçº§ãä¾èµä¿¡æ¯ï¼
     */
    private void appendTaskItem(StringBuilder sb, TaskItem task) {
        String statusIcon = task.status == TaskStatus.COMPLETED ? "â" : "â³";
        sb.append("  ").append(statusIcon).append(" ").append(task.id).append(" - ").append(task.description);
        if (task.priority == TaskPriority.HIGH) {
            sb.append(" [HIGH]");
        }
        if (!task.dependsOn.isEmpty()) {
            sb.append(" â ä¾èµ: ").append(String.join(", ", task.dependsOn));
        }
        sb.append("\n");
    }
}
