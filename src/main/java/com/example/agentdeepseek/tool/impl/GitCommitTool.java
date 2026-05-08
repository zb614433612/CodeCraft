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

import java.util.UUID;

/**
 * Git Commit 工具
 * 创建提交。commit_mode=auto 时直接提交，manual 时返回 __PENDING_COMMIT__ 标记等待用户确认
 */
@Slf4j
@Component
public class GitCommitTool implements Tool {

    private final GitCommandExecutor gitExecutor;
    private final ObjectMapper objectMapper;
    private final ExecutionTokenManager executionTokenManager;

    public GitCommitTool(GitCommandExecutor gitExecutor, ObjectMapper objectMapper, ExecutionTokenManager executionTokenManager) {
        this.gitExecutor = gitExecutor;
        this.objectMapper = objectMapper;
        this.executionTokenManager = executionTokenManager;
    }

    @Override
    public String getName() {
        return "git_commit";
    }

    @Override
    public String getDescription() {
        return "创建 Git 提交。需要提供提交信息（message）。"
                + "commit_mode=auto 时直接提交（手动模式下需要 ask_execution 授权），"
                + "commit_mode=manual 时返回待提交信息由用户在前端确认后执行";
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
        files.put("description", "可选，要提交的文件列表描述，仅用于 manual 模式下展示给用户确认，不影响实际提交内容");
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
        String commitMode = ToolContext.getGitCommitMode();

        // none 模式：直接返回无需提交
        if ("none".equals(commitMode)) {
            return "当前 Git 提交模式为「无」，已禁用提交功能，无需执行提交操作";
        }

        // manual 提交模式：返回待确认标记
        if ("manual".equals(commitMode)) {
            String filesInfo = arguments.path("files").asText("");

            // 获取当前暂存的文件列表
            String stagedFiles = getStagedFiles(projectRoot);
            String user = getUserName(projectRoot);
            String email = getUserEmail(projectRoot);

            String uuid = UUID.randomUUID().toString();
            ObjectNode commitInfo = objectMapper.createObjectNode();
            commitInfo.put("message", message);
            commitInfo.put("files", filesInfo);
            commitInfo.put("staged_files", stagedFiles);
            commitInfo.put("user_name", user);
            commitInfo.put("user_email", email);

            String jsonStr;
            try {
                jsonStr = objectMapper.writeValueAsString(commitInfo);
            } catch (Exception e) {
                jsonStr = "{\"message\":\"" + message + "\"}";
            }

            log.info("pending_commit: uuid={}, message={}", uuid, message);
            return "__PENDING_COMMIT__:" + uuid + ":" + jsonStr;
        }

        // auto 提交模式：检查权限后直接提交
        if ("manual".equals(ToolContext.getMode())) {
            Long sessionId = ToolContext.getConversationId();
            if (sessionId == null || !executionTokenManager.tryConsume(sessionId)) {
                return "需要先通过 ask_execution 工具获得您的执行许可";
            }
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

    private String getStagedFiles(String projectRoot) {
        GitCommandExecutor.GitResult result = gitExecutor.execute(projectRoot,
                "diff", "--cached", "--name-only");
        if (!result.success() || result.output().isBlank()) {
            return "（暂存区无文件）";
        }
        return result.output();
    }

    private String getUserName(String projectRoot) {
        GitCommandExecutor.GitResult result = gitExecutor.execute(projectRoot, "config", "user.name");
        return result.success() ? result.output().trim() : "";
    }

    private String getUserEmail(String projectRoot) {
        GitCommandExecutor.GitResult result = gitExecutor.execute(projectRoot, "config", "user.email");
        return result.success() ? result.output().trim() : "";
    }
}
