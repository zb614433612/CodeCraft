package com.example.agentdeepseek.tool.impl;

import com.example.agentdeepseek.tool.PermissionContext;
import com.example.agentdeepseek.tool.Tool;
import com.example.agentdeepseek.util.ToolContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 终止后台进程工具
 * 手动模式下需要 ask_execution 授权
 */
@Slf4j
@Component
public class KillProcessTool implements Tool {

    private final ObjectMapper objectMapper;

    public KillProcessTool(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public String getName() {
        return "kill_process";
    }

    @Override
    public String getDescription() {
        return "终止后台运行中的进程。手动模式下需要 ask_execution 授权。通过 run_background_command 启动的进程使用分配的 ID 管理";
    }

    @Override
    public JsonNode getParameters() {
        ObjectNode parameters = objectMapper.createObjectNode();
        parameters.put("type", "object");

        ObjectNode properties = objectMapper.createObjectNode();

        ObjectNode pid = objectMapper.createObjectNode();
        pid.put("type", "integer");
        pid.put("description", "进程 ID，通过 run_background_command 启动时返回的 ID");
        properties.set("pid", pid);

        parameters.set("properties", properties);
        parameters.putArray("required").add("pid");
        return parameters;
    }

    @Override
    public String execute(JsonNode arguments) {
        if (!arguments.has("pid")) {
            return "错误：缺少必要参数 pid";
        }
        int pid = arguments.get("pid").asInt();

        // manual 模式下请求用户授权
        if ("manual".equals(ToolContext.getMode())) {
            String response = PermissionContext.requestPermission(getName(), arguments, ToolContext.getConversationId());
            if (response != null) return response;
        }

        boolean success = RunBackgroundCommandTool.stopProcess(pid);
        if (success) {
            log.info("已终止进程 #{}", pid);
            return "进程 #" + pid + " 已终止\n当前进程列表：\n" + RunBackgroundCommandTool.getProcessList();
        } else {
            return "错误：找不到进程 #" + pid + "，使用 list_processes 查看当前进程列表";
        }
    }
}
