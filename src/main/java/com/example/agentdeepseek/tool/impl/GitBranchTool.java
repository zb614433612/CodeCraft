package com.example.agentdeepseek.tool.impl;

import com.example.agentdeepseek.tool.Tool;
import com.example.agentdeepseek.util.ProjectRootContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Git 分支管理工具
 * 支持查看、创建、切换、删除分支
 */
@Slf4j
@Component
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
        return "管理 Git 分支。支持 list（列出分支）、create（创建分支）、switch（切换分支）、delete（删除分支）四种操作";
    }

    @Override
    public JsonNode getParameters() {
        ObjectNode parameters = objectMapper.createObjectNode();
        parameters.put("type", "object");

        ObjectNode properties = objectMapper.createObjectNode();

        ObjectNode action = objectMapper.createObjectNode();
        action.put("type", "string");
        action.put("description", "操作类型：list（列出分支，默认）、create（创建分支）、switch（切换分支）、delete（删除分支）");
        action.putArray("enum").add("list").add("create").add("switch").add("delete");
        properties.set("action", action);

        ObjectNode name = objectMapper.createObjectNode();
        name.put("type", "string");
        name.put("description", "分支名称（create/switch/delete 时必填）");
        properties.set("name", name);

        ObjectNode all = objectMapper.createObjectNode();
        all.put("type", "boolean");
        all.put("description", "list 时是否显示远程分支（默认 false）；delete 时是否强制删除（-D，默认 false）");
        properties.set("all", all);

        parameters.set("properties", properties);
        return parameters;
    }

    @Override
    public String execute(JsonNode arguments) {
        String projectRoot = ProjectRootContext.get();
        String action = arguments.path("action").asText("list");
        String name = arguments.path("name").asText("");
        boolean all = arguments.path("all").asBoolean(false);

        return switch (action) {
            case "create" -> createBranch(projectRoot, name);
            case "switch" -> switchBranch(projectRoot, name);
            case "delete" -> deleteBranch(projectRoot, name, all);
            default -> listBranches(projectRoot, all);
        };
    }

    private String listBranches(String projectRoot, boolean all) {
        String[] args = all
                ? new String[]{"branch", "-a"}
                : new String[]{"branch"};
        GitCommandExecutor.GitResult result = gitExecutor.execute(projectRoot, args);
        if (!result.success()) {
            return "错误：获取分支列表失败 - " + result.output();
        }

        // 获取当前分支
        GitCommandExecutor.GitResult current = gitExecutor.execute(projectRoot,
                "rev-parse", "--abbrev-ref", "HEAD");
        String currentBranch = current.success() ? current.output().trim() : "未知";

        return "当前分支：" + currentBranch + "\n\n" + result.output();
    }

    private String createBranch(String projectRoot, String name) {
        if (name.isEmpty()) {
            return "错误：缺少必要参数 name（分支名称）";
        }

        GitCommandExecutor.GitResult result = gitExecutor.execute(projectRoot, "branch", name);
        if (!result.success()) {
            return "错误：创建分支失败 - " + result.output();
        }
        log.info("Git 分支创建成功: {}", name);
        return "分支 '" + name + "' 创建成功\n" + result.output();
    }

    private String switchBranch(String projectRoot, String name) {
        if (name.isEmpty()) {
            return "错误：缺少必要参数 name（分支名称）";
        }

        GitCommandExecutor.GitResult result = gitExecutor.execute(projectRoot, "checkout", name);
        if (!result.success()) {
            return "错误：切换分支失败 - " + result.output();
        }
        log.info("Git 分支切换成功: {}", name);
        return "已切换到分支 '" + name + "'\n" + result.output();
    }

    private String deleteBranch(String projectRoot, String name, boolean force) {
        if (name.isEmpty()) {
            return "错误：缺少必要参数 name（分支名称）";
        }

        String[] args = force
                ? new String[]{"branch", "-D", name}
                : new String[]{"branch", "-d", name};
        GitCommandExecutor.GitResult result = gitExecutor.execute(projectRoot, args);
        if (!result.success()) {
            return "错误：删除分支失败 - " + result.output();
        }
        log.info("Git 分支删除成功: {}", name);
        return "分支 '" + name + "' 已删除\n" + result.output();
    }
}
