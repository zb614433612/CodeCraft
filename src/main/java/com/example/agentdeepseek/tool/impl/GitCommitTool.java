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

/**
 * Git Commit 工具
 * 创建提交。自动模式下直接提交，手动模式下需要 ask_execution 授权
 */
@Slf4j
@Component
public class GitCommitTool implements Tool {

    private final GitCommandExecutor gitExecutor;
    private final ObjectMapper objectMapper;

    public GitCommitTool(GitCommandExecutor gitExecutor, ObjectMapper objectMapper) {
        this.gitExecutor = gitExecutor;
        this.objectMapper = objectMapper;
    }

    @Override
    public String getName() {
        return "git_commit";
    }

    @Override
    public String getDescription() {
        return "创建 Git 提交。需要提供提交信息（message）。"
                + "手动模式下需要 ask_execution 授权";
    }

    @Override
    public JsonNode getParameters() {
        ObjectNode parameters = objectMapper.createObjectNode();
        parameters.put("type", "object");

        ObjectNode properties = objectMapper.createObjectNode();

        ObjectNode message = objectMapper.createObjectNode();
        message.put("type", "string");
        message.put("description", "提交信息（必填），如 \"feat: 添加用户登录功能\"");
        properties.set("message", message);

        ObjectNode files = objectMapper.createObjectNode();
        files.put("type", "string");
        files.put("description", "可选，要提交的文件列表描述，不影响实际提交内容");
        properties.set("files", files);

        parameters.set("properties", properties);
        parameters.putArray("required").add("message");
        return parameters;
    }

    @Override
    public String execute(JsonNode arguments) {
        String message = arguments.path("message").asText();
        if (message.isEmpty()) {
            return "错误：缺少必要参数 message";
        }

        String projectRoot = ProjectRootContext.get();

        // manual 模式下请求用户授权
        if ("manual".equals(ToolContext.getMode())) {
            String response = PermissionContext.requestPermission(getName(), arguments, ToolContext.getConversationId());
            if (response != null) return response;
        }

        return executeCommit(projectRoot, message);
    }

    private String executeCommit(String projectRoot, String message) {
        // 检查是否有暂存的变更
        GitCommandExecutor.GitResult diffResult = gitExecutor.execute(projectRoot,
                "diff", "--cached", "--quiet");
        if (diffResult.success()) {
            return "错误：暂存区没有变更，请先使用 git_add 工具暂存文件";
        }

        GitCommandExecutor.GitResult result = gitExecutor.executeWithStoredAuth(
                projectRoot, "commit", "-m", message);

        if (!result.success()) {
            return "错误：提交失败 - " + result.output();
        }

        log.info("Git 提交成功: {}", message);
        return "提交成功\n" + result.output();
    }

}
