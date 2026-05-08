package com.example.agentdeepseek.tool.impl;

import com.example.agentdeepseek.tool.ExecutionTokenManager;
import com.example.agentdeepseek.tool.Tool;
import com.example.agentdeepseek.util.ProjectRootContext;
import com.example.agentdeepseek.util.ToolContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Git Add 工具
 * 暂存文件变更
 */
@Slf4j
@Component
public class GitAddTool implements Tool {

    private final GitCommandExecutor gitExecutor;
    private final ObjectMapper objectMapper;
    private final ExecutionTokenManager executionTokenManager;

    public GitAddTool(GitCommandExecutor gitExecutor, ObjectMapper objectMapper, ExecutionTokenManager executionTokenManager) {
        this.gitExecutor = gitExecutor;
        this.objectMapper = objectMapper;
        this.executionTokenManager = executionTokenManager;
    }

    @Override
    public String getName() {
        return "git_add";
    }

    @Override
    public String getDescription() {
        return "暂存文件变更到 Git 暂存区。支持暂存单个文件、多个文件（逗号分隔）或全部变更（path=all）。手动模式下需要 ask_execution 授权";
    }

    @Override
    public JsonNode getParameters() {
        ObjectNode parameters = objectMapper.createObjectNode();
        parameters.put("type", "object");

        ObjectNode properties = objectMapper.createObjectNode();

        ObjectNode path = objectMapper.createObjectNode();
        path.put("type", "string");
        path.put("description", "要暂存的文件路径。多个文件用逗号分隔。传 'all' 或 '.' 表示暂存全部变更");
        properties.set("path", path);

        parameters.set("properties", properties);
        parameters.putArray("required").add("path");
        return parameters;
    }

    @Override
    public String execute(JsonNode arguments) {
        // manual 模式下检查执行权限
        if ("manual".equals(ToolContext.getMode())) {
            Long sessionId = ToolContext.getConversationId();
            if (sessionId == null || !executionTokenManager.tryConsume(sessionId)) {
                return "需要先通过 ask_execution 工具获得您的执行许可";
            }
        }

        String path = arguments.path("path").asText();
        if (path.isEmpty()) {
            return "错误：缺少必要参数 path";
        }

        String projectRoot = ProjectRootContext.get();

        if ("all".equals(path) || ".".equals(path)) {
            GitCommandExecutor.GitResult result = gitExecutor.execute(projectRoot, "add", "-A");
            if (!result.success()) {
                return "错误：暂存全部变更失败 - " + result.output();
            }
            return "已暂存全部变更";
        }

        // 暂存一个或多个文件
        String[] files = path.split(",");
        for (String file : files) {
            file = file.trim();
            if (file.isEmpty()) continue;

            GitCommandExecutor.GitResult result = gitExecutor.execute(projectRoot, "add", file);
            if (!result.success()) {
                return "错误：暂存文件失败 '" + file + "' - " + result.output();
            }
        }

        return "已暂存 " + files.length + " 个文件：" + path;
    }
}
