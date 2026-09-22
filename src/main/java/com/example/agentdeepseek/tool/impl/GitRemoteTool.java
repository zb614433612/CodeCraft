package com.example.agentdeepseek.tool.impl;

import com.example.agentdeepseek.tool.Tool;
import com.example.agentdeepseek.tool.permission.OperationCategory;
import com.example.agentdeepseek.tool.permission.ToolPermission;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Git 远程仓库管理工具 — 管理 remote 配置
 * 支持查看 / 添加 / 修改 URL / 删除远程仓库
 */
@Slf4j
@Component
@ToolPermission(category = OperationCategory.GIT, affectsData = true, description = "管理Git远程仓库配置")
public class GitRemoteTool implements Tool {

    private final GitCommandExecutor gitExecutor;
    private final ObjectMapper objectMapper;

    public GitRemoteTool(GitCommandExecutor gitExecutor, ObjectMapper objectMapper) {
        this.gitExecutor = gitExecutor;
        this.objectMapper = objectMapper;
    }

    @Override
    public String getName() {
        return "git_remote";
    }

    @Override
    public String getDescription() {
        return "【适用场景】管理 Git 远程仓库配置：查看远程列表、添加/修改/删除远程仓库地址。\n"
                + "【action 说明】list=列出所有远程仓库（含 URL）；add=添加远程仓库（name+url；已存在时报错，请改用 set_url）；set_url=修改远程仓库 URL；remove=删除远程仓库。\n"
                + "【使用方式】projectRoot 必填。示例：list 查看 remote；set_url name=\"origin\" url=\"https://...\" 更换仓库地址；add name=\"upstream\" url=\"https://...\" 添加第二个远程。";
    }

    @Override
    public JsonNode getParameters() {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("type", "object");

        ObjectNode properties = root.putObject("properties");

        ObjectNode projectRoot = properties.putObject("projectRoot");
        projectRoot.put("type", "string");
        projectRoot.put("description", "【必填】Git 项目根目录路径。示例：'/home/user/project' 或 'E:/work/my-app'。");

        ObjectNode action = properties.putObject("action");
        action.put("type", "string");
        action.put("description", "【可选，默认 list】操作类型：list=列出远程仓库；add=添加；set_url=修改 URL；remove=删除。");
        action.putArray("enum").add("list").add("add").add("set_url").add("remove");

        ObjectNode name = properties.putObject("name");
        name.put("type", "string");
        name.put("description", "【add/set_url/remove 时必填】远程仓库名称，通常为 'origin'。");

        ObjectNode url = properties.putObject("url");
        url.put("type", "string");
        url.put("description", "【add/set_url 时必填】远程仓库地址。示例：'https://github.com/user/repo.git'。");

        ArrayNode required = root.putArray("required");
        required.add("projectRoot");

        return root;
    }

    @Override
    public String execute(JsonNode arguments) {
        String projectRoot = arguments.has("projectRoot") ? arguments.get("projectRoot").asText() : ".";
        String action = arguments.path("action").asText("list");

        return switch (action) {
            case "add" -> doAdd(projectRoot, arguments);
            case "set_url" -> doSetUrl(projectRoot, arguments);
            case "remove" -> doRemove(projectRoot, arguments);
            default -> doList(projectRoot);
        };
    }

    // ========== remote list ==========

    private String doList(String projectRoot) {
        GitCommandExecutor.GitResult result = gitExecutor.execute(projectRoot, "remote", "-v");
        if (!result.success()) {
            return "git remote 失败：" + result.output();
        }
        String output = result.output();
        if (output.isBlank()) {
            return "未配置远程仓库。可用 action=add 添加，如 name=\"origin\"。";
        }
        return "远程仓库列表：\n" + output;
    }

    // ========== remote add ==========

    private String doAdd(String projectRoot, JsonNode args) {
        String name = args.path("name").asText("");
        String url = args.path("url").asText("");
        if (name.isEmpty() || url.isEmpty()) {
            return "【参数错误】action=add 需要 name 与 url 参数。示例：name=\"origin\", url=\"https://github.com/user/repo.git\"。";
        }

        // 先检查是否已存在
        GitCommandExecutor.GitResult check = gitExecutor.execute(projectRoot, "remote", "get-url", name);
        if (check.success()) {
            return "❌ 远程仓库 '" + name + "' 已存在（当前 URL：" + check.output().trim() + "）。如需修改地址请用 action=set_url。";
        }

        GitCommandExecutor.GitResult result = gitExecutor.execute(projectRoot, "remote", "add", name, url);
        if (!result.success()) {
            return "【Git 错误】添加远程仓库失败。Git 返回信息：" + result.output();
        }
        log.info("Git 远程仓库添加成功: {} -> {}", name, url);
        return "远程仓库 '" + name + "' 已添加（" + url + "）。";
    }

    // ========== remote set-url ==========

    private String doSetUrl(String projectRoot, JsonNode args) {
        String name = args.path("name").asText("");
        String url = args.path("url").asText("");
        if (name.isEmpty() || url.isEmpty()) {
            return "【参数错误】action=set_url 需要 name 与 url 参数。示例：name=\"origin\", url=\"https://github.com/user/repo.git\"。";
        }

        GitCommandExecutor.GitResult result = gitExecutor.execute(projectRoot, "remote", "set-url", name, url);
        if (!result.success()) {
            return "【Git 错误】修改远程仓库 URL 失败（远程名不存在？先用 action=list 查看）。Git 返回信息：" + result.output();
        }
        log.info("Git 远程仓库 URL 修改成功: {} -> {}", name, url);
        return "远程仓库 '" + name + "' 的 URL 已更新为：" + url;
    }

    // ========== remote remove ==========

    private String doRemove(String projectRoot, JsonNode args) {
        String name = args.path("name").asText("");
        if (name.isEmpty()) {
            return "【参数错误】action=remove 需要 name 参数。请传入要删除的远程仓库名称，如 name=\"origin\"。";
        }

        GitCommandExecutor.GitResult result = gitExecutor.execute(projectRoot, "remote", "remove", name);
        if (!result.success()) {
            return "【Git 错误】删除远程仓库失败（远程名不存在？先用 action=list 查看）。Git 返回信息：" + result.output();
        }
        log.info("Git 远程仓库删除成功: {}", name);
        return "远程仓库 '" + name + "' 已删除（仅移除本地配置，不影响远程服务器）。";
    }
}
