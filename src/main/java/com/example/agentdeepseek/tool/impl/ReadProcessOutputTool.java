package com.example.agentdeepseek.tool.impl;

import com.example.agentdeepseek.tool.Tool;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 读取后台进程输出工具
 */
@Slf4j
@Component
public class ReadProcessOutputTool implements Tool {

    private final ObjectMapper objectMapper;

    public ReadProcessOutputTool(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public String getName() {
        return "read_process_output";
    }

    @Override
    public String getDescription() {
        return "读取后台进程的输出内容。支持指定读取行数。注意：进程输出保留最近 5000 行";
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

        ObjectNode tail = objectMapper.createObjectNode();
        tail.put("type", "integer");
        tail.put("description", "可选，只显示最后 N 行。不指定则显示全部输出");
        properties.set("tail", tail);

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
        int tail = arguments.has("tail") ? arguments.get("tail").asInt(0) : 0;

        String output = RunBackgroundCommandTool.readProcessOutput(pid, tail);
        if (output == null) {
            return "错误：找不到进程 #" + pid + "。使用 list_processes 查看当前进程列表";
        }
        return output;
    }
}
