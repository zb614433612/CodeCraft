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
 * Git Push 工具
 * 将本地提交推送到远程仓库
 */
@Slf4j
@Component
public class GitPushTool implements Tool {

    private final GitCommandExecutor gitExecutor;
    private final ObjectMapper objectMapper;
    private final ExecutionTokenManager executionTokenManager;

    public GitPushTool(GitCommandExecutor gitExecutor, ObjectMapper objectMapper, ExecutionTokenManager executionTokenManager) {
        this.gitExecutor = gitExecutor;
        this.objectMapper = objectMapper;
        this.executionTokenManager = executionTokenManager;
    }

    @Override
    public String getName() {
        return "git_push";
    }

    @Override
    public String getDescription() {
        return "将本地提交推送到远程仓库。需要指定 remote（默认 origin）和 branch（默认当前分支）。"
                + "手动模式下需要 ask_execution 授权";
    }

    @Override
    public JsonNode getParameters() {
        ObjectNode parameters = objectMapper.createObjectNode();
        parameters.put("type", "object");

        ObjectNode properties = objectMapper.createObjectNode();

        ObjectNode remote = objectMapper.createObjectNode();
        remote.put("type", "string");
        remote.put("description", "远程仓库名称（可选，默认 origin）");
        properties.set("remote", remote);

        ObjectNode branch = objectMapper.createObjectNode();
        branch.put("type", "string");
        branch.put("description", "要推送的分支（可选，默认当前分支）");
        properties.set("branch", branch);

        ObjectNode setUpstream = objectMapper.createObjectNode();
        setUpstream.put("type", "boolean");
        setUpstream.put("description", "是否设置上游关联（-u），首次推送时需要（默认 false）");
        properties.set("set_upstream", setUpstream);

        ObjectNode force = objectMapper.createObjectNode();
        force.put("type", "boolean");
        force.put("description", "是否强制推送（--force），会覆盖远程历史，请谨慎使用（默认 false）");
        properties.set("force", force);

        parameters.set("properties", properties);
        return parameters;
    }

    @Override
    public String execute(JsonNode arguments) {
        String projectRoot = ProjectRootContext.get();
        String remote = arguments.path("remote").asText("origin");
        String branch = arguments.path("branch").asText("");
        boolean setUpstream = arguments.path("set_upstream").asBoolean(false);
        boolean force = arguments.path("force").asBoolean(false);

        // 如果未指定分支，获取当前分支
        if (branch.isEmpty()) {
            GitCommandExecutor.GitResult currentBranch = gitExecutor.execute(projectRoot,
                    "rev-parse", "--abbrev-ref", "HEAD");
            if (!currentBranch.success()) {
                return "错误：无法获取当前分支";
            }
            branch = currentBranch.output().trim();
        }

        // 手动模式检查权限
        if ("manual".equals(ToolContext.getMode())) {
            Long sessionId = ToolContext.getConversationId();
            if (sessionId == null || !executionTokenManager.tryConsume(sessionId)) {
                return "需要先通过 ask_execution 工具获得您的执行许可";
            }
        }

        // 构建 git push 命令
        java.util.List<String> args = new java.util.ArrayList<>();
        args.add("push");
        args.add(remote);
        args.add(branch);
        if (setUpstream) {
            args.add("-u");
        }
        if (force) {
            args.add("--force");
        }

        GitCommandExecutor.GitResult result = gitExecutor.executeWithStoredAuth(
                projectRoot, args.toArray(new String[0]));

        if (!result.success()) {
            return "错误：推送失败 - " + result.output();
        }

        log.info("Git 推送成功: {} -> {}/{}", branch, remote, branch);
        return "推送成功\n" + result.output();
    }
}
