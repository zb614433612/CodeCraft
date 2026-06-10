package com.example.agentdeepseek.tool.impl;

import com.example.agentdeepseek.model.entity.ScheduleTask;
import com.example.agentdeepseek.service.ScheduleTaskService;
import com.example.agentdeepseek.tool.Tool;
import com.example.agentdeepseek.tool.permission.OperationCategory;
import com.example.agentdeepseek.tool.permission.ToolPermission;
import com.example.agentdeepseek.util.CronHelper;
import com.example.agentdeepseek.util.ToolContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Optional;

/**
 * 定时任务管理工具
 * LLM 通过此工具创建/查看/修改/删除/启停定时任务，
 * 与系统中已有的 schedule_task 表及 ScheduleTaskScheduler 联动。
 *
 * 支持操作：create / list / update / delete / toggle
 * 返回格式：可读文本
 */
@Slf4j
@Component
@ToolPermission(category = OperationCategory.ADMIN, affectsData = true, description = "创建和管理定时任务")
public class ScheduleTaskTool implements Tool {

    private final ObjectMapper objectMapper;
    private final ScheduleTaskService scheduleTaskService;

    public ScheduleTaskTool(ObjectMapper objectMapper, ScheduleTaskService scheduleTaskService) {
        this.objectMapper = objectMapper;
        this.scheduleTaskService = scheduleTaskService;
    }

    @Override
    public String getName() {
        return "schedule_task";
    }

    @Override
    public String getDescription() {
        return "管理定时任务，让用户通过自然语言指挥LLM创建/查看/修改/删除定时任务。\n"
                + "【适用场景】用户说「帮我每天9点审查代码」「创建一个定时任务每周一跑测试」「查看我的定时任务」等\n"
                + "【使用方式】通过 action 参数选择操作：create=创建、list=查看列表、update=修改、delete=删除、toggle=启用/禁用\n"
                + "【natural_time 字段】支持自然语言时间描述，如「每天早上9点」「每周一上午10点」「每小时」「每30分钟」「每月1号下午3点」\n"
                + "  如果 natural_time 无法识别，请改用 cron_expression 参数指定标准 Cron 表达式\n"
                + "【安全提醒】delete 操作前应向用户确认；创建任务默认上限100次";
    }

    @Override
    public JsonNode getParameters() {
        ObjectNode parameters = objectMapper.createObjectNode();
        parameters.put("type", "object");

        ObjectNode properties = objectMapper.createObjectNode();

        // ── action ──
        ObjectNode action = objectMapper.createObjectNode();
        action.put("type", "string");
        action.put("description", "【必填】操作类型。create=创建定时任务；list=查看任务列表；update=修改任务；delete=删除任务；toggle=启用/禁用任务");
        ArrayNode actionEnum = objectMapper.createArrayNode();
        actionEnum.add("create");
        actionEnum.add("list");
        actionEnum.add("update");
        actionEnum.add("delete");
        actionEnum.add("toggle");
        action.set("enum", actionEnum);
        properties.set("action", action);

        // ── name ──
        ObjectNode name = objectMapper.createObjectNode();
        name.put("type", "string");
        name.put("description", "【create 时必填，update 时可选】任务名称，LLM 根据用户指令自动生成简洁名称。示例：\"每日代码审查\"、\"每周测试报告\"");
        properties.set("name", name);

        // ── instruction ──
        ObjectNode instruction = objectMapper.createObjectNode();
        instruction.put("type", "string");
        instruction.put("description", "【create 时必填，update 时可选】任务指令内容，即定时任务触发时发给 LLM 执行的指令。示例：\"审查最近提交的代码，检查代码质量和安全问题\"");
        properties.set("instruction", instruction);

        // ── cron_expression ──
        ObjectNode cronExpression = objectMapper.createObjectNode();
        cronExpression.put("type", "string");
        cronExpression.put("description", "【create/update 时三选一】标准 Cron 表达式（6位）。示例：\"0 0 9 * * ?\" 表示每天9:00。与 execute_time、natural_time 三选一。选 cron_expression 为重复执行任务");
        properties.set("cron_expression", cronExpression);

        // ── execute_time ──
        ObjectNode executeTime = objectMapper.createObjectNode();
        executeTime.put("type", "string");
        executeTime.put("description", "【create/update 时三选一】一次性执行时间，ISO 格式。示例：\"2025-05-01T18:00:00\"。与 cron_expression、natural_time 三选一。选 execute_time 为一次性任务");
        properties.set("execute_time", executeTime);

        // ── natural_time ──
        ObjectNode naturalTime = objectMapper.createObjectNode();
        naturalTime.put("type", "string");
        naturalTime.put("description", "【create/update 时推荐优先使用】自然语言时间描述，后端自动转为 Cron 表达式。示例：\"每天早上9点\"、\"每周一上午10点\"、\"每小时\"、\"每30分钟\"、\"每月1号下午3点\"。与 cron_expression、execute_time 三选一");
        properties.set("natural_time", naturalTime);

        // ── agent_config_id ──
        ObjectNode agentConfigId = objectMapper.createObjectNode();
        agentConfigId.put("type", "number");
        agentConfigId.put("description", "【create/update 可选】指定执行任务时使用的 Agent 配置 ID。不传则默认使用当前对话的 Agent 配置。传 -999 可将已设置的任务重置为默认 Agent");
        properties.set("agent_config_id", agentConfigId);

        // ── max_execute_count ──
        ObjectNode maxExecuteCount = objectMapper.createObjectNode();
        maxExecuteCount.put("type", "number");
        maxExecuteCount.put("description", "【create/update 可选】最大执行次数，默认 100（安全上限），设为 0 表示不限，最大 10000。到达次数后自动停止");
        properties.set("max_execute_count", maxExecuteCount);

        // ── id ──
        ObjectNode id = objectMapper.createObjectNode();
        id.put("type", "number");
        id.put("description", "【update/delete/toggle 时必填】任务 ID。从 list 返回结果中获取");
        properties.set("id", id);

        // ── enabled (toggle) ──
        ObjectNode enabled = objectMapper.createObjectNode();
        enabled.put("type", "boolean");
        enabled.put("description", "【toggle 时必填】true=启用任务，false=禁用任务");
        properties.set("enabled", enabled);

        // ── status (list filter) ──
        ObjectNode status = objectMapper.createObjectNode();
        status.put("type", "string");
        status.put("description", "【list 可选】按状态过滤。ENABLED=已启用，DISABLED=已禁用。不传则返回全部");
        properties.set("status", status);

        parameters.set("properties", properties);

        ArrayNode required = objectMapper.createArrayNode();
        required.add("action");
        parameters.set("required", required);

        return parameters;
    }

    @Override
    public String execute(JsonNode arguments) {
        String action = arguments.path("action").asText();
        if (action.isEmpty()) {
            return buildError("【缺少参数】action 字段缺失。请设为 create / list / update / delete / toggle 之一");
        }

        return switch (action) {
            case "create" -> handleCreate(arguments);
            case "list" -> handleList(arguments);
            case "update" -> handleUpdate(arguments);
            case "delete" -> handleDelete(arguments);
            case "toggle" -> handleToggle(arguments);
            default -> buildError("【不支持的操作】action='" + action + "' 无效。支持：create / list / update / delete / toggle");
        };
    }

    // ======================== handleCreate ========================

    private String handleCreate(JsonNode args) {
        String name = args.path("name").asText();
        String instruction = args.path("instruction").asText();

        if (name.isEmpty()) {
            return buildError("【缺少参数】create 需要 name。示例：{\"name\": \"每日代码审查\"}");
        }
        if (instruction.isEmpty()) {
            return buildError("【缺少参数】create 需要 instruction。示例：{\"instruction\": \"审查最近提交的代码\"}");
        }

        // 解析时间参数：natural_time > cron_expression > execute_time
        TimeParseResult timeResult = parseTimeParams(args, false);
        if (timeResult.error != null) {
            return buildError(timeResult.error);
        }

        String cronExpression = timeResult.cronExpression != null ? timeResult.cronExpression : "";
        LocalDateTime executeTime = timeResult.executeTime;

        // 三选一校验
        if (cronExpression.isEmpty() && executeTime == null) {
            return buildError("【缺少时间参数】create 需要指定执行时间，请三选一：\n"
                    + "1) natural_time：自然语言描述，如 \"每天早上9点\"（推荐）\n"
                    + "2) cron_expression：Cron 表达式，如 \"0 0 9 * * ?\"\n"
                    + "3) execute_time：一次性时间，如 \"2025-05-01T18:00:00\"");
        }

        // 校验 Cron（parseTimeParams 已校验 explicit cron，此处防御性校验 natural_time 解析结果）
        if (!cronExpression.isEmpty() && !CronHelper.validate(cronExpression)) {
            return buildError("【Cron 格式错误】cron_expression='" + cronExpression
                    + "' 无效。标准 Cron 为 6 位空格分隔，如 \"0 0 9 * * ?\"");
        }

        // 构建实体
        ScheduleTask task = new ScheduleTask();
        task.setName(name);
        task.setInstruction(instruction);
        task.setCronExpression(cronExpression.isEmpty() ? null : cronExpression);
        task.setExecuteTime(executeTime);
        task.setStatus("ENABLED");

        // max_execute_count：不传默认100次上限，传0表示不限；硬封顶10000
        long maxCount = args.path("max_execute_count").asLong(100);
        task.setMaxExecuteCount(maxCount == 0 ? 0 : (int) Math.min(maxCount, 10000));

        // 用户 ID
        Long userId = ToolContext.getUserId();
        task.setUserId(userId);

        // 策略 B：agent_config_id 默认取当前对话的 Agent 配置
        long agentConfigIdParam = args.path("agent_config_id").asLong(-1);
        if (agentConfigIdParam == -999) {
            task.setAgentConfigId(null);
        } else if (agentConfigIdParam >= 0) {
            task.setAgentConfigId(agentConfigIdParam);
        } else {
            Long currentAgentConfigId = ToolContext.getAgentConfigId();
            task.setAgentConfigId(currentAgentConfigId);
        }

        // Agent 类型
        String agentType = ToolContext.getAgentType();
        task.setAgentType(agentType);

        try {
            ScheduleTask created = scheduleTaskService.createTask(task);
            log.info("工具创建定时任务: id={}, name={}, cron={}, userId={}",
                    created.getId(), created.getName(), created.getCronExpression(), userId);

            StringBuilder sb = new StringBuilder();
            sb.append("✅ 定时任务创建成功！\n\n");
            sb.append("  📋 ID：").append(created.getId()).append("\n");
            sb.append("  📛 名称：").append(created.getName()).append("\n");
            sb.append("  📝 指令：").append(created.getInstruction()).append("\n");
            if (created.getCronExpression() != null) {
                sb.append("  ⏱ 执行计划：").append(CronHelper.describe(created.getCronExpression()))
                        .append("（").append(created.getCronExpression()).append("）\n");
            } else if (created.getExecuteTime() != null) {
                sb.append("  ⏱ 执行时间：").append(created.getExecuteTime().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")))
                        .append("（一次性）\n");
            }
            sb.append("  🟢 状态：已启用\n");
            if (created.getMaxExecuteCount() != null && created.getMaxExecuteCount() > 0) {
                sb.append("  🔢 最大执行次数：").append(created.getMaxExecuteCount()).append("\n");
            } else {
                sb.append("  🔢 最大执行次数：不限\n");
            }
            return sb.toString();
        } catch (DataAccessException e) {
            log.error("创建定时任务失败（数据库异常）", e);
            return buildError("【创建失败】数据库操作异常：" + e.getMessage());
        } catch (RuntimeException e) {
            log.error("创建定时任务失败", e);
            return buildError("【创建失败】" + e.getMessage());
        }
    }

    // ======================== handleList ========================

    private String handleList(JsonNode args) {
        try {
            List<ScheduleTask> allTasks = scheduleTaskService.getAllTasks();
            String statusFilter = args.path("status").asText();

            // 全量统计（始终显示总体概览）
            int totalAll = allTasks.size();
            long enabledAll = allTasks.stream().filter(t -> "ENABLED".equals(t.getStatus())).count();
            long disabledAll = totalAll - enabledAll;

            List<ScheduleTask> tasks;
            if (!statusFilter.isEmpty()) {
                tasks = allTasks.stream()
                        .filter(t -> statusFilter.equalsIgnoreCase(t.getStatus()))
                        .toList();
            } else {
                tasks = allTasks;
            }

            StringBuilder sb = new StringBuilder();
            sb.append("📊 定时任务列表：共 ").append(totalAll).append(" 个（启用 ").append(enabledAll)
                    .append(" / 禁用 ").append(disabledAll).append("）");

            if (!statusFilter.isEmpty()) {
                sb.append("  【已过滤：").append(statusFilter).append("，显示 ").append(tasks.size()).append(" 个】");
            }
            sb.append("\n\n");

            if (tasks.isEmpty()) {
                sb.append("  暂无任务。试试对我说「帮我创建一个定时任务」吧！\n");
                return sb.toString();
            }

            for (ScheduleTask t : tasks) {
                String statusIcon = "ENABLED".equals(t.getStatus()) ? "🟢" : "🔴";
                sb.append("  ").append(statusIcon).append(" [").append(t.getId()).append("] ").append(t.getName()).append("\n");
                if (t.getCronExpression() != null) {
                    sb.append("      ⏱ ").append(CronHelper.describe(t.getCronExpression()))
                            .append("（").append(t.getCronExpression()).append("）\n");
                } else if (t.getExecuteTime() != null) {
                    sb.append("      ⏱ 一次性：").append(t.getExecuteTime()
                            .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))).append("\n");
                }
                sb.append("      📝 ").append(truncate(t.getInstruction(), 60)).append("\n");
                if (t.getExecuteCount() != null && t.getExecuteCount() > 0) {
                    sb.append("      🔢 已执行 ").append(t.getExecuteCount()).append(" 次");
                    if (t.getMaxExecuteCount() != null && t.getMaxExecuteCount() > 0) {
                        sb.append(" / 上限 ").append(t.getMaxExecuteCount()).append(" 次");
                    }
                    sb.append("\n");
                }
            }
            return sb.toString();
        } catch (DataAccessException e) {
            log.error("查看定时任务列表失败（数据库异常）", e);
            return buildError("【查询失败】数据库操作异常：" + e.getMessage());
        } catch (RuntimeException e) {
            log.error("查看定时任务列表失败", e);
            return buildError("【查询失败】" + e.getMessage());
        }
    }

    // ======================== handleUpdate ========================

    private String handleUpdate(JsonNode args) {
        long id = args.path("id").asLong(-1);
        if (id < 0) {
            return buildError("【缺少参数】update 需要 id。示例：{\"action\": \"update\", \"id\": 1, \"name\": \"新名称\"}");
        }

        // 先查出已有任务
        ScheduleTask task = findTask(id);
        if (task == null) {
            return buildError("【任务不存在】未找到 ID=" + id + " 的任务。请用 action=list 查看任务列表确认正确 ID");
        }

        String name = args.path("name").asText();
        String instruction = args.path("instruction").asText();

        if (!name.isEmpty()) task.setName(name);
        if (!instruction.isEmpty()) task.setInstruction(instruction);

        // 处理时间更新：严格按 natural_time > cron_expression > execute_time 优先级
        TimeParseResult timeResult = parseTimeParams(args, true);
        if (timeResult.error != null) {
            return buildError(timeResult.error);
        }
        if (timeResult.cronExpression != null) {
            task.setCronExpression(timeResult.cronExpression);
            task.setExecuteTime(null);
        } else if (timeResult.executeTime != null) {
            task.setExecuteTime(timeResult.executeTime);
            task.setCronExpression(null);
        }

        // max_execute_count：上限裁剪，与 create 保持一致
        long maxCount = args.path("max_execute_count").asLong(-1);
        if (maxCount >= 0) {
            task.setMaxExecuteCount(maxCount == 0 ? 0 : (int) Math.min(maxCount, 10000));
        }

        // agent_config_id：-999 重置为默认（null），>=0 覆盖，不传保持原值
        long agentConfigIdParam = args.path("agent_config_id").asLong(-1);
        if (agentConfigIdParam == -999) {
            task.setAgentConfigId(null);
        } else if (agentConfigIdParam >= 0) {
            task.setAgentConfigId(agentConfigIdParam);
        }

        try {
            scheduleTaskService.updateTask(task);
            log.info("工具更新定时任务: id={}, name={}", id, task.getName());

            StringBuilder sb = new StringBuilder();
            sb.append("✅ 定时任务 [").append(id).append("] 已更新！\n\n");
            sb.append("  📛 名称：").append(task.getName()).append("\n");
            sb.append("  📝 指令：").append(truncate(task.getInstruction(), 80)).append("\n");
            if (task.getCronExpression() != null) {
                sb.append("  ⏱ 执行计划：").append(CronHelper.describe(task.getCronExpression())).append("\n");
            } else if (task.getExecuteTime() != null) {
                sb.append("  ⏱ 执行时间：").append(task.getExecuteTime()
                        .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))).append("（一次性）\n");
            }
            return sb.toString();
        } catch (DataAccessException e) {
            log.error("更新定时任务失败（数据库异常，可能存在并发修改）: id={}", id, e);
            return buildError("【更新失败】数据库操作异常（可能任务已被其他操作修改或删除）：" + e.getMessage());
        } catch (RuntimeException e) {
            log.error("更新定时任务失败", e);
            return buildError("【更新失败】" + e.getMessage());
        }
    }

    // ======================== handleDelete ========================

    private String handleDelete(JsonNode args) {
        long id = args.path("id").asLong(-1);
        if (id < 0) {
            return buildError("【缺少参数】delete 需要 id。示例：{\"action\": \"delete\", \"id\": 1}");
        }

        // 查一下任务名用于确认反馈
        ScheduleTask task = findTask(id);
        if (task == null) {
            return buildError("【任务不存在】未找到 ID=" + id + " 的任务");
        }

        String taskName = task.getName();
        try {
            scheduleTaskService.deleteTask(id);
            log.info("工具删除定时任务: id={}, name={}", id, taskName);
            return "✅ 已删除定时任务「" + taskName + "」（ID=" + id + "）";
        } catch (DataAccessException e) {
            log.error("删除定时任务失败（数据库异常，可能存在并发操作）: id={}", id, e);
            return buildError("【删除失败】数据库操作异常（可能任务已被其他操作删除）：" + e.getMessage());
        } catch (RuntimeException e) {
            log.error("删除定时任务失败", e);
            return buildError("【删除失败】" + e.getMessage());
        }
    }

    // ======================== handleToggle ========================

    private String handleToggle(JsonNode args) {
        long id = args.path("id").asLong(-1);
        if (id < 0) {
            return buildError("【缺少参数】toggle 需要 id。示例：{\"action\": \"toggle\", \"id\": 1, \"enabled\": false}");
        }
        if (!args.has("enabled")) {
            return buildError("【缺少参数】toggle 需要 enabled 字段。示例：{\"action\": \"toggle\", \"id\": 1, \"enabled\": true}");
        }

        boolean enabled = args.path("enabled").asBoolean();
        ScheduleTask task = findTask(id);
        if (task == null) {
            return buildError("【任务不存在】未找到 ID=" + id + " 的任务");
        }

        try {
            if (enabled) {
                scheduleTaskService.enableTask(id);
            } else {
                scheduleTaskService.disableTask(id);
            }
            String statusText = enabled ? "🟢 已启用" : "🔴 已禁用";
            log.info("工具切换定时任务状态: id={}, enabled={}", id, enabled);
            return statusText + " 定时任务「" + task.getName() + "」（ID=" + id + "）";
        } catch (DataAccessException e) {
            log.error("切换定时任务状态失败（数据库异常，可能存在并发操作）: id={}", id, e);
            return buildError("【操作失败】数据库操作异常（可能任务已被其他操作修改或删除）：" + e.getMessage());
        } catch (RuntimeException e) {
            log.error("切换定时任务状态失败", e);
            return buildError("【操作失败】" + e.getMessage());
        }
    }

    // ======================== 时间解析 ========================

    /**
     * 时间参数解析结果
     */
    private static class TimeParseResult {
        String cronExpression;      // 最终确定的 cron 表达式（null 表示未设置）
        LocalDateTime executeTime;  // 最终确定的一次性执行时间（null 表示未设置）
        String error;               // 错误信息（null 表示无错误）
    }

    /**
     * 统一解析时间参数（natural_time / cron_expression / execute_time 三选一）。
     *
     * @param args     请求参数
     * @param isUpdate true=update 模式（natural_time 失败即报错，cron 优先于 execute_time）；
     *                 false=create 模式（natural_time 失败回退到其他方式）
     * @return 解析结果，包含 cronExpression / executeTime / error
     */
    private TimeParseResult parseTimeParams(JsonNode args, boolean isUpdate) {
        String naturalTime = args.path("natural_time").asText();
        String cronExpr = args.path("cron_expression").asText();
        String executeTimeStr = args.path("execute_time").asText();

        TimeParseResult result = new TimeParseResult();

        // ── 1. natural_time（优先级最高）──
        if (!naturalTime.isEmpty()) {
            Optional<String> parsed = CronHelper.parseNaturalLanguage(naturalTime);
            if (parsed.isPresent()) {
                result.cronExpression = parsed.get();
                return result; // natural_time 解析成功，直接返回，跳过 execute_time
            }
            // natural_time 解析失败
            if (isUpdate) {
                result.error = "【无法识别时间描述】natural_time='" + naturalTime
                        + "' 无法解析。请改用 cron_expression 或调整描述。\n"
                        + "支持格式：每天早上X点 / 每周N X点 / 每月N号 X点 / 每小时 / 每N分钟 / 每N小时";
                return result;
            }
            // create 模式：natural_time 失败不回传错误，继续尝试其他方式
        }

        // ── 2. cron_expression ──
        if (!cronExpr.isEmpty()) {
            if (!CronHelper.validate(cronExpr)) {
                result.error = "【Cron 格式错误】cron_expression='" + cronExpr
                        + "' 无效。标准 Cron 为 6 位空格分隔，如 \"0 0 9 * * ?\"";
                return result;
            }
            result.cronExpression = cronExpr;
            return result; // cron_expression 成功即返回，不与 execute_time 并存
        }

        // ── 3. execute_time（双重格式：ISO + yyyy-MM-dd HH:mm:ss）──
        if (!executeTimeStr.isEmpty()) {
            try {
                result.executeTime = LocalDateTime.parse(executeTimeStr, DateTimeFormatter.ISO_LOCAL_DATE_TIME);
            } catch (DateTimeParseException e) {
                try {
                    result.executeTime = LocalDateTime.parse(executeTimeStr,
                            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
                } catch (DateTimeParseException e2) {
                    result.error = "【时间格式错误】execute_time='" + executeTimeStr
                            + "' 无法解析。请使用 ISO 格式：2025-05-01T18:00:00 或 yyyy-MM-dd HH:mm:ss";
                    return result;
                }
            }
        }

        return result;
    }

    // ======================== 辅助方法 ========================

    /**
     * 按 ID 精确查询任务（通过 Service → Mapper.selectById）。
     * <p>
     * <b>并发风险说明：</b>此方法与后续的 update/delete/toggle 操作之间存在 TOCTOU 竞态窗口，
     * 查询和修改之间无锁保护。高并发场景下，任务可能在查询后被其他线程修改或删除。
     * 当前通过捕获 {@link DataAccessException} 提供友好错误提示，不做架构级锁改造。
     * </p>
     */
    private ScheduleTask findTask(long id) {
        return scheduleTaskService.getTaskById(id);
    }

    private String truncate(String s, int maxLen) {
        if (s == null) return "";
        return s.length() <= maxLen ? s : s.substring(0, maxLen) + "...";
    }

    private String buildError(String message) {
        return message;
    }
}
