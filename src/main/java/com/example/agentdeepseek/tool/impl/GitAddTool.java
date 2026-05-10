package com.example.agentdeepseek.tool.impl;

import com.example.agentdeepseek.tool.PermissionContext;
import com.example.agentdeepseek.tool.Tool;
import com.example.agentdeepseek.util.ProjectRootContext;
import com.example.agentdeepseek.util.ToolContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Git Add 工具
 * 暂存文件变更
 */
@Slf4j
@Component
public class GitAddTool implements Tool {

    private final GitCommandExecutor gitExecutor;
    private final ObjectMapper objectMapper;

    public GitAddTool(GitCommandExecutor gitExecutor, ObjectMapper objectMapper) {
        this.gitExecutor = gitExecutor;
        this.objectMapper = objectMapper;
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
        // manual 模式下请求用户授权
        if ("manual".equals(ToolContext.getMode())) {
            String response = PermissionContext.requestPermission(getName(), arguments, ToolContext.getConversationId());
            if (response != null) return response;
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

        // 暂存一个或多个文件（一次性 add，避免逐个启动子进程）
        String[] files = path.split(",");
        List<String> fileList = new ArrayList<>();
        for (String file : files) {
            String trimmed = file.trim();
            if (!trimmed.isEmpty()) {
                fileList.add(trimmed);
            }
        }

        if (fileList.isEmpty()) {
            return "错误：未指定有效的文件路径";
        }

        // 将所有文件一次性传给 git add
        String[] addArgs = new String[fileList.size() + 1];
        addArgs[0] = "add";
        for (int i = 0; i < fileList.size(); i++) {
            addArgs[i + 1] = fileList.get(i);
        }
        GitCommandExecutor.GitResult result = gitExecutor.execute(projectRoot, addArgs);
        if (!result.success()) {
            return "错误：暂存文件失败 - " + result.output();
        }

        return "已暂存 " + fileList.size() + " 个文件：" + path;
    }
}
