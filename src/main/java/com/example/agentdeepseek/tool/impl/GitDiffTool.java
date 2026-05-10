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
 * Git Diff 工具
 * 查看文件或工作区的变更详情
 */
@Slf4j
@Component
public class GitDiffTool implements Tool {

    /** Diff 输出最大字符数，超过则截断 */
    private static final int MAX_OUTPUT_LENGTH = 10000;

    private final GitCommandExecutor gitExecutor;
    private final ObjectMapper objectMapper;

    public GitDiffTool(GitCommandExecutor gitExecutor, ObjectMapper objectMapper) {
        this.gitExecutor = gitExecutor;
        this.objectMapper = objectMapper;
    }

    @Override
    public String getName() {
        return "git_diff";
    }

    @Override
    public String getDescription() {
        return "查看 Git 文件变更详情（diff）。支持指定文件或查看全部变更，可选查看已暂存的变更（staged）";
    }

    @Override
    public JsonNode getParameters() {
        ObjectNode parameters = objectMapper.createObjectNode();
        parameters.put("type", "object");

        ObjectNode properties = objectMapper.createObjectNode();

        ObjectNode file = objectMapper.createObjectNode();
        file.put("type", "string");
        file.put("description", "可选，要查看的文件路径。不指定则查看所有文件的变更");
        properties.set("file", file);

        ObjectNode staged = objectMapper.createObjectNode();
        staged.put("type", "boolean");
        staged.put("description", "可选，是否查看已暂存（staged）的变更，默认为 false 查看工作区变更");
        properties.set("staged", staged);

        parameters.set("properties", properties);
        return parameters;
    }

    @Override
    public String execute(JsonNode arguments) {
        String projectRoot = ProjectRootContext.get();

        String filePath = arguments.path("file").asText();
        boolean staged = arguments.path("staged").asBoolean(false);

        List<String> args = new ArrayList<>();
        args.add("diff");
        if (staged) {
            args.add("--cached");
        }
        if (!filePath.isEmpty()) {
            args.add("--");
            args.add(filePath);
        }

        GitCommandExecutor.GitResult result = gitExecutor.execute(
                projectRoot, args.toArray(new String[0]));

        if (!result.success()) {
            return "错误：获取 diff 失败 - " + result.output();
        }

        String output = result.output();
        if (output.isBlank()) {
            return "无变更内容" + (filePath.isEmpty() ? "" : "（文件 " + filePath + "）");
        }

        // 限制输出大小
        if (output.length() > MAX_OUTPUT_LENGTH) {
            output = output.substring(0, MAX_OUTPUT_LENGTH) + "\n\n...（diff 过长已截断，建议指定具体文件查看）";
        }

        return output;
    }
}
