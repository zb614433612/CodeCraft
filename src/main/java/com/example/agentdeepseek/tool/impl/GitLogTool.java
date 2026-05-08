package com.example.agentdeepseek.tool.impl;

import com.example.agentdeepseek.tool.Tool;
import com.example.agentdeepseek.util.ProjectRootContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Git Log 工具
 * 查看提交历史
 */
@Slf4j
@Component
public class GitLogTool implements Tool {

    private static final int DEFAULT_MAX_COUNT = 20;

    private final GitCommandExecutor gitExecutor;
    private final ObjectMapper objectMapper;

    public GitLogTool(GitCommandExecutor gitExecutor, ObjectMapper objectMapper) {
        this.gitExecutor = gitExecutor;
        this.objectMapper = objectMapper;
    }

    @Override
    public String getName() {
        return "git_log";
    }

    @Override
    public String getDescription() {
        return "查看 Git 提交历史。支持指定显示条数和过滤文件，可选显示分支图";
    }

    @Override
    public JsonNode getParameters() {
        ObjectNode parameters = objectMapper.createObjectNode();
        parameters.put("type", "object");

        ObjectNode properties = objectMapper.createObjectNode();

        ObjectNode maxCount = objectMapper.createObjectNode();
        maxCount.put("type", "integer");
        maxCount.put("description", "可选，最多显示多少条，默认 " + DEFAULT_MAX_COUNT + " 条");
        properties.set("max_count", maxCount);

        ObjectNode file = objectMapper.createObjectNode();
        file.put("type", "string");
        file.put("description", "可选，只显示包含此文件变更的提交");
        properties.set("file", file);

        ObjectNode graph = objectMapper.createObjectNode();
        graph.put("type", "boolean");
        graph.put("description", "可选，是否显示分支图，默认为 false");
        properties.set("graph", graph);

        parameters.set("properties", properties);
        return parameters;
    }

    @Override
    public String execute(JsonNode arguments) {
        String projectRoot = ProjectRootContext.get();

        int maxCount = arguments.path("max_count").asInt(DEFAULT_MAX_COUNT);
        String filePath = arguments.path("file").asText();
        boolean showGraph = arguments.path("graph").asBoolean(false);

        List<String> args = new ArrayList<>();
        args.add("log");
        if (showGraph) {
            args.add("--graph");
            args.add("--pretty=format:%h %s (%an, %ar)");
        } else {
            args.add("--oneline");
        }
        args.add("-n");
        args.add(String.valueOf(maxCount));

        if (!filePath.isEmpty()) {
            args.add("--");
            args.add(filePath);
        }

        GitCommandExecutor.GitResult result = gitExecutor.execute(
                projectRoot, args.toArray(new String[0]));

        if (!result.success()) {
            return "错误：获取提交历史失败 - " + result.output();
        }

        String output = result.output();
        if (output.isBlank()) {
            return "暂无提交记录";
        }

        int count = output.split("\n").length;
        return "最近 " + count + " 条提交记录：\n" + output;
    }
}
