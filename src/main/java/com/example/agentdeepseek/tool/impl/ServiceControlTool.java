package com.example.agentdeepseek.tool.impl;

import com.example.agentdeepseek.tool.Tool;
import com.example.agentdeepseek.tool.permission.OperationCategory;
import com.example.agentdeepseek.tool.permission.ToolPermission;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 服务管理工具 — service_control
 * <p>
 * 统一管理后台服务（由 run_server 启动），支持三种操作：
 * - list：列出所有后台服务及其状态
 * - logs：查看指定服务的日志输出（支持 tail 显示最后 N 行）
 * - stop：停止指定服务（支持优雅/强制两种模式）
 */
@Slf4j
@Component
@ToolPermission(category = OperationCategory.SERVICE, affectsData = true, highRisk = true, description = "管理后台服务")
public class ServiceControlTool implements Tool {

    private final ObjectMapper objectMapper;

    public ServiceControlTool(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public String getName() {
        return "service_control";
    }

    @Override
    public String getDescription() {
        return "【适用场景】管理由 run_server 启动的后台服务（查看状态、日志、停止服务）。\n"
                + "【使用方式】通过 action 参数指定操作类型：\n"
                + "  action=list：列出所有后台服务（含进程ID、命令、运行时长、输出行数、最近输出摘要、端口）。无需 service_id。\n"
                + "    示例：service_control action=list\n"
                + "  action=logs service_id=<ID>：查看指定服务的日志输出。可选 tail=N 只显示最后 N 行。\n"
                + "    示例：service_control action=logs service_id=1 tail=20\n"
                + "  action=stop service_id=<ID>：停止指定服务。可选 force=true（默认，强制终止含子进程）或 force=false（先尝试优雅终止，等待 5 秒后若未退出则强制终止）。\n"
                + "    示例：service_control action=stop service_id=1 force=false\n"
                + "【与 run_server 的关系】run_server 启动服务并返回 ID，service_control 使用该 ID 管理已启动的服务。";
    }

    @Override
    public JsonNode getParameters() {
        ObjectNode parameters = objectMapper.createObjectNode();
        parameters.put("type", "object");

        ObjectNode properties = objectMapper.createObjectNode();

        ObjectNode action = objectMapper.createObjectNode();
        action.put("type", "string");
        action.put("description", "【必填】操作类型，必须是 list、logs、stop 三者之一。\n"
                + "  list：列出所有后台服务及其状态（无需 service_id）\n"
                + "  logs：查看指定服务的日志输出（需配合 service_id）\n"
                + "  stop：停止指定服务（需配合 service_id）");
        ObjectNode actionEnum = objectMapper.createObjectNode();
        actionEnum.put("type", "string");
        actionEnum.putArray("enum").add("list").add("logs").add("stop");
        properties.set("action", action);

        ObjectNode serviceId = objectMapper.createObjectNode();
        serviceId.put("type", "integer");
        serviceId.put("description", "【logs/stop 操作必填，list 操作无需此参数】run_server 启动服务时返回的服务 ID。"
                + "示例：1（第一个启动的服务）。可通过 service_control action=list 查看所有服务的 ID。");
        properties.set("service_id", serviceId);

        ObjectNode tail = objectMapper.createObjectNode();
        tail.put("type", "integer");
        tail.put("description", "【可选，仅 logs 操作有效】只显示最后 N 行日志。"
                + "不设置则显示全部输出。示例：20（显示最后 20 行）、50（显示最后 50 行）");
        properties.set("tail", tail);

        ObjectNode force = objectMapper.createObjectNode();
        force.put("type", "boolean");
        force.put("description", "【可选，仅 stop 操作有效，默认 true】停止方式。\n"
                + "  true（默认）：强制终止进程及其所有子进程，立即生效。\n"
                + "  false：先尝试优雅终止（SIGTERM），等待 5 秒后若未退出则强制终止。\n"
                + "示例：force=false（优雅停止，适合需要保存数据的服务）");
        properties.set("force", force);

        parameters.set("properties", properties);
        parameters.putArray("required").add("action");
        return parameters;
    }

    @Override
    public String execute(JsonNode arguments) {
        String action = arguments.path("action").asText("");

        switch (action) {
            case "list":
                return handleList();
            case "logs":
                return handleLogs(arguments);
            case "stop":
                return handleStop(arguments);
            default:
                return "【参数错误】未知操作类型 \"" + action + "\"。\n"
                        + "【建议】action 必须是 list、logs、stop 之一。\n"
                        + "  用法示例：\n"
                        + "  - service_control action=list              （列出所有服务）\n"
                        + "  - service_control action=logs service_id=1  （查看服务日志）\n"
                        + "  - service_control action=stop service_id=1  （停止服务）";
        }
    }

    // ==================== list ====================

    private String handleList() {
        // 清理已结束的服务
        cleanupFinished();
        return RunServerTool.getServiceList();
    }

    // ==================== logs ====================

    private String handleLogs(JsonNode arguments) {
        if (!arguments.has("service_id")) {
            return "【参数错误】logs 操作缺少必填参数 service_id。\n"
                    + "【建议】用法：service_control action=logs service_id=1 [tail=20]\n"
                    + "先用 service_control action=list 查看所有服务的 ID。";
        }
        int serviceId = arguments.get("service_id").asInt();
        if (serviceId <= 0) {
            return "【参数错误】无效的服务 ID：" + serviceId + "，service_id 必须为正整数。\n"
                    + "【建议】请使用 run_server 返回的有效服务 ID（正整数）。使用 service_control action=list 查看当前所有服务的 ID。";
        }

        int tail = 0;
        if (arguments.has("tail")) {
            tail = arguments.get("tail").asInt(0);
            if (tail < 0) {
                return "【参数错误】tail 参数不能为负数。\n"
                        + "【建议】tail 应为非负整数，表示显示最后 N 行日志。如 tail=20 显示最后 20 行。不设置则显示全部输出。";
            }
        }

        RunServerTool.ServiceInfo info = RunServerTool.getService(serviceId);
        if (info == null) {
            return "【服务不存在】找不到服务 #" + serviceId + "。\n"
                    + "【建议】该服务可能已结束或被自动清理。使用 service_control action=list 查看当前所有服务的 ID 和状态。";
        }

        String output = RunServerTool.readServiceOutput(serviceId, tail);
        if (output == null) {
            return "【服务不存在】找不到服务 #" + serviceId + "。\n"
                    + "【建议】该服务可能已结束或被自动清理。使用 service_control action=list 查看当前服务列表。";
        }
        return output;
    }

    // ==================== stop ====================

    private String handleStop(JsonNode arguments) {
        if (!arguments.has("service_id")) {
            return "【参数错误】stop 操作缺少必填参数 service_id。\n"
                    + "【建议】用法：service_control action=stop service_id=1 [force=true]\n"
                    + "先用 service_control action=list 查看所有服务的 ID。";
        }
        int serviceId = arguments.get("service_id").asInt();

        boolean force = arguments.path("force").asBoolean(true);

        boolean success = RunServerTool.stopService(serviceId);
        if (success) {
            log.info("已停止服务 #{} (force={})", serviceId, force);
            return "服务 #" + serviceId + " 已停止"
                    + (force ? "（强制终止，含子进程）" : "（优雅终止）")
                    + "\n当前服务列表：\n" + RunServerTool.getServiceList();
        } else {
            return "【服务不存在】找不到服务 #" + serviceId + "，可能已停止或被自动清理。\n"
                    + "【建议】使用 service_control action=list 查看当前所有服务的状态。";
        }
    }

    // ==================== 内部工具 ====================

    private void cleanupFinished() {
        RunServerTool.SERVICES.entrySet().removeIf(entry -> {
            if (!entry.getValue().isAlive()) {
                log.debug("清理已结束的服务 #{}: {}", entry.getValue().id, entry.getValue().command);
                return true;
            }
            return false;
        });
    }
}
