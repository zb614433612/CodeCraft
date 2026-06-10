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
 * Git 分支管理工具
 * 支持查看、创建、切换、删除分支
 */
@Slf4j
@Component
@ToolPermission(category = OperationCategory.GIT, affectsData = true, description = "管理Git分支")
public class GitBranchTool implements Tool {

    private final GitCommandExecutor gitExecutor;
    private final ObjectMapper objectMapper;

    public GitBranchTool(GitCommandExecutor gitExecutor, ObjectMapper objectMapper) {
        this.gitExecutor = gitExecutor;
        this.objectMapper = objectMapper;
    }

    @Override
    public String getName() {
        return "git_branch";
    }

    @Override
    public String getDescription() {
        return "【适用场景】管理本地 Git 分支：查看所有分支、创建功能/修复分支、在不同分支间切换、清理已合并的旧分支\n"
                + "【与 git_submit 区别】git_branch 管理本地分支，git_submit(action=push) 是将本地提交推送到远程仓库\n"
                + "【使用方式】action=list 列所有分支（默认）；action=create name=\"feature/xxx\" 创建新分支；action=switch name=\"develop\" 切换分支；action=delete name=\"old-branch\" 删除（force=true 强制删除未合并的分支）";
    }

    @Override
    public JsonNode getParameters() {
        ObjectNode parameters = objectMapper.createObjectNode();
        parameters.put("type", "object");

        ObjectNode properties = objectMapper.createObjectNode();

        ObjectNode action = objectMapper.createObjectNode();
        action.put("type", "string");
        action.put("description", "【可选，默认 list】操作类型: list=列出所有分支、create=创建新分支（需传 name）、switch=切换到指定分支（需传 name）、delete=删除分支（需传 name）");
        action.putArray("enum").add("list").add("create").add("switch").add("delete");
        properties.set("action", action);

        ObjectNode name = objectMapper.createObjectNode();
        name.put("type", "string");
        name.put("description", "【create/switch/delete 时必填】分支名称，如 \"feature/login\"、\"fix/bug-123\"、\"develop\"");
        properties.set("name", name);

        ObjectNode all = objectMapper.createObjectNode();
        all.put("type", "boolean");
        all.put("description", "【list 时可选】设为 true 同时显示远程分支（-a），默认 false 仅显示本地分支");
        properties.set("all", all);

        ObjectNode force = objectMapper.createObjectNode();
        force.put("type", "boolean");
        force.put("description", "【delete 时可选】设为 true 强制删除未合并的分支（-D）。警告：被删除分支上未合并的提交将永久丢失");
        properties.set("force", force);

        parameters.set("properties", properties);
        return parameters;
    }

    @Override
    public String execute(JsonNode arguments) {
        String projectRoot = ProjectRootContext.get();
        String action = arguments.path("action").asText("list");
        String name = arguments.path("name").asText("");
        boolean all = arguments.path("all").asBoolean(false);
        boolean force = arguments.path("force").asBoolean(false);

        return switch (action) {
            case "create" -> createBranch(projectRoot, name);
            case "switch" -> switchBranch(projectRoot, name);
            case "delete" -> deleteBranch(projectRoot, name, force);
            default -> listBranches(projectRoot, all);
        };
    }

    private String listBranches(String projectRoot, boolean all) {
        String[] args = all
                ? new String[]{"branch", "-a"}
                : new String[]{"branch"};
        GitCommandExecutor.GitResult result = gitExecutor.execute(projectRoot, args);
        if (!result.success()) {
            return "【Git 错误】git branch 执行失败，请确认当前目录在有效 Git 仓库内。Git 返回信息：" + result.output();
        }

        // 获取当前分支
        GitCommandExecutor.GitResult current = gitExecutor.execute(projectRoot,
                "rev-parse", "--abbrev-ref", "HEAD");
        String currentBranch = current.success() ? current.output().trim() : "未知";

        return "当前分支：" + currentBranch + "\n\n" + result.output();
    }

    private String createBranch(String projectRoot, String name) {
        if (name.isEmpty()) {
            return "【参数错误】当前 action 需要 name 参数。请传入分支名称，如 name=\"feature/new-feature\"";
        }

        GitCommandExecutor.GitResult result = gitExecutor.execute(projectRoot, "branch", name);
        if (!result.success()) {
            return "【Git 错误】创建分支失败。常见原因：①同名分支已存在；②名称含非法字符（只允许字母/数字/点/下划线/短横/斜杠）。Git 返回信息：" + result.output();
        }
        log.info("Git 分支创建成功: {}", name);
        return "分支 '" + name + "' 创建成功\n" + result.output();
    }

    private String switchBranch(String projectRoot, String name) {
        if (name.isEmpty()) {
            return "【参数错误】当前 action 需要 name 参数。请传入分支名称，如 name=\"feature/new-feature\"";
        }

        GitCommandExecutor.GitResult result = gitExecutor.execute(projectRoot, "switch", name);
        if (!result.success()) {
            return "【Git 错误】切换分支失败。常见原因：①目标分支不存在（先用 action=list 确认分支名）；②工作区有未提交变更冲突（先用 git_query action=status 查看，必要时 stash）。Git 返回信息：" + result.output();
        }
        log.info("Git 分支切换成功: {}", name);
        return "已切换到分支 '" + name + "'\n" + result.output();
    }

    private String deleteBranch(String projectRoot, String name, boolean force) {
        if (name.isEmpty()) {
            return "【参数错误】当前 action 需要 name 参数。请传入分支名称，如 name=\"feature/new-feature\"";
        }

        String[] args = force
                ? new String[]{"branch", "-D", name}
                : new String[]{"branch", "-d", name};
        GitCommandExecutor.GitResult result = gitExecutor.execute(projectRoot, args);
        if (!result.success()) {
            return "【Git 错误】删除分支失败。常见原因：①分支未完全合并（设 force=true 可强制删除）；②不能删除当前所在分支（先 switch 到其他分支）。Git 返回信息：" + result.output();
        }
        log.info("Git 分支删除成功: {}", name);
        return "分支 '" + name + "' 已删除\n" + result.output();
    }
}
