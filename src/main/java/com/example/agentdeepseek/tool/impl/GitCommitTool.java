package com.example.agentdeepseek.tool.impl;

import com.example.agentdeepseek.tool.Tool;
import com.example.agentdeepseek.util.ProjectRootContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import com.example.agentdeepseek.tool.permission.OperationCategory;
import com.example.agentdeepseek.tool.permission.ToolPermission;

/**
 * Git Commit 工具
 * 创建提交
 */
@Slf4j
@Component
@ToolPermission(category = OperationCategory.GIT, affectsData = true, description = "创建Git提交")
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
        return "【适用场景】将暂存区内容创建为一个版本快照（commit），是 git_add 之后的第二步\n"
                + "【与 git_add 区别】git_add 只暂存文件变更是前置步骤，git_commit 才真正创建提交记录；请确保已用 git_add 暂存所需文件后再调用本工具\n"
                + "【使用方式】先调用 git_add 暂存文件，再调用本工具传 message=\"feat: 功能描述\" 创建提交。message 建议用 conventional commits 格式\n"
                + "【注意】手动模式下需要用户授权";
    }

    @Override
    public JsonNode getParameters() {
        ObjectNode parameters = objectMapper.createObjectNode();
        parameters.put("type", "object");

        ObjectNode properties = objectMapper.createObjectNode();

        ObjectNode message = objectMapper.createObjectNode();
        message.put("type", "string");
        message.put("description", "【必填】提交信息，建议按 conventional commits 格式: \"feat: 新增XX功能\"、\"fix: 修复XX问题\"、\"refactor: 重构XX模块\"、\"docs: 更新XX文档\"");
        properties.set("message", message);

        parameters.set("properties", properties);
        parameters.putArray("required").add("message");
        return parameters;
    }

    @Override
    public String execute(JsonNode arguments) {
        String message = arguments.path("message").asText();
        if (message.isEmpty()) {
            return "【参数错误】缺少必填参数 message。请提供提交信息，格式: message=\"feat: 简短描述所做改动\"";
        }

        String projectRoot = ProjectRootContext.get();

        return executeCommit(projectRoot, message);
    }

    private String executeCommit(String projectRoot, String message) {
        // 检查是否有暂存的变更
        GitCommandExecutor.GitResult diffResult = gitExecutor.execute(projectRoot,
                "diff", "--cached", "--quiet");
        if (diffResult.success()) {
            return "【操作顺序错误】暂存区为空，没有可提交的内容。请先调用 git_add 工具暂存要提交的文件，再用 git_commit 创建提交。标准流程: git_add → git_commit";
        }

        GitCommandExecutor.GitResult result = gitExecutor.executeWithStoredAuth(
                projectRoot, "commit", "-m", message);

        if (!result.success()) {
            return "【Git 错误】git commit 失败。请检查：① git 用户配置（user.name/user.email 是否设置）；② 暂存区内容是否有效（非空）；③ pre-commit hook 是否拦截。Git 返回信息：" + result.output();
        }

        log.info("Git 提交成功: {}", message);
        return "提交成功\n" + result.output();
    }

}
