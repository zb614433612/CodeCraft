package com.example.agentdeepseek.tool.impl;

import com.example.agentdeepseek.tool.Tool;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 列出后台进程工具
 */
@Slf4j
@Component
public class ListProcessesTool implements Tool {

    private final ObjectMapper objectMapper;

    public ListProcessesTool(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public String getName() {
        return "list_processes";
    }

    @Override
    public String getDescription() {
        return "列出所有后台进程及其状态，包括进程 ID、命令、运行时长、输出行数";
    }

    @Override
    public JsonNode getParameters() {
        ObjectNode parameters = objectMapper.createObjectNode();
        parameters.put("type", "object");
        parameters.put("description", "无需参数");
        parameters.put("properties", objectMapper.createObjectNode());
        return parameters;
    }

    @Override
    public String execute(JsonNode arguments) {
        return RunBackgroundCommandTool.getProcessList();
    }
}
