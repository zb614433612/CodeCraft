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

import java.util.ArrayList;
import java.util.List;

/**
 * Git 标签工具 — 管理 tag 标签
 * 支持查看 / 创建（轻量或附注）/ 删除 / 推送标签
 */
@Slf4j
@Component
@ToolPermission(category = OperationCategory.GIT, affectsData = true, description = "管理Git标签(tag)")
public class GitTagTool implements Tool {

    private final GitCommandExecutor gitExecutor;
    private final ObjectMapper objectMapper;

    public GitTagTool(GitCommandExecutor gitExecutor, ObjectMapper objectMapper) {
        this.gitExecutor = gitExecutor;
        this.objectMapper = objectMapper;
    }

    @Override
    public String getName() {
        return "git_tag";
    }

    @Override
    public String getDescription() {
        return "【适用场景】管理 Git 标签（tag）：查看标签、创建版本发布标签（轻量/附注）、推送标签到远程、删除标签。\n"
                + "【action 说明】list=列出所有标签；create=创建标签（name 必填；传 message 则创建附注标签 -a，否则轻量标签）；delete=删除本地标签；push=推送标签到远程（name 指定单个；不传则推送全部 --tags）。\n"
                + "【使用方式】projectRoot 必填。版本发布典型流程：create name=\"v1.0.0\" message=\"Release 1.0.0\" → push name=\"v1.0.0\"。";
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
        action.put("description", "【可选，默认 list】操作类型：list=列出所有标签；create=创建标签；delete=删除本地标签；push=推送标签到远程。");
        action.putArray("enum").add("list").add("create").add("delete").add("push");

        ObjectNode name = properties.putObject("name");
        name.put("type", "string");
        name.put("description", "【create/delete 时必填；push 时可选】标签名称。示例：'v1.0.0'、'release-2026-09'。push 不传则推送全部标签。");

        ObjectNode message = properties.putObject("message");
        message.put("type", "string");
        message.put("description", "【create 时可选】附注标签信息（-a -m）。传了创建附注标签（推荐用于正式版本），不传则为轻量标签。");

        ObjectNode remote = properties.putObject("remote");
        remote.put("type", "string");
        remote.put("description", "【可选，仅 push 时有效】远程仓库名称，默认 origin。");

        ArrayNode required = root.putArray("required");
        required.add("projectRoot");

        return root;
    }

    @Override
    public String execute(JsonNode arguments) {
        String projectRoot = arguments.has("projectRoot") ? arguments.get("projectRoot").asText() : ".";
        String action = arguments.path("action").asText("list");

        return switch (action) {
            case "create" -> doCreate(projectRoot, arguments);
            case "delete" -> doDelete(projectRoot, arguments);
            case "push" -> doPush(projectRoot, arguments);
            default -> doList(projectRoot);
        };
    }

    // ========== tag list ==========

    private String doList(String projectRoot) {
        GitCommandExecutor.GitResult result = gitExecutor.execute(projectRoot, "tag", "-l");
        if (!result.success()) {
            return "git tag 失败：" + result.output();
        }
        String output = result.output();
        if (output.isBlank()) {
            return "暂无标签。";
        }
        return "标签列表：\n" + output;
    }

    // ========== tag create ==========

    private String doCreate(String projectRoot, JsonNode args) {
        String name = args.path("name").asText("");
        if (name.isEmpty()) {
            return "【参数错误】action=create 需要 name 参数。请传入标签名称，如 name=\"v1.0.0\"。";
        }
        String message = args.has("message") ? args.get("message").asText() : null;

        List<String> cmdArgs = new ArrayList<>();
        cmdArgs.add("tag");
        if (message != null && !message.isBlank()) {
            cmdArgs.add("-a");
            cmdArgs.add(name);
            cmdArgs.add("-m");
            cmdArgs.add(message);
        } else {
            cmdArgs.add(name);
        }

        GitCommandExecutor.GitResult result = gitExecutor.execute(projectRoot, cmdArgs.toArray(new String[0]));
        if (!result.success()) {
            return "【Git 错误】创建标签失败。常见原因：①同名标签已存在（先 action=list 查看，或 action=delete 删除后重建）。Git 返回信息：" + result.output();
        }
        log.info("Git 标签创建成功: {}", name);
        return "标签 '" + name + "' 创建成功（" + (message != null && !message.isBlank() ? "附注标签" : "轻量标签") + "）。如需推送远程请用 action=push。";
    }

    // ========== tag delete ==========

    private String doDelete(String projectRoot, JsonNode args) {
        String name = args.path("name").asText("");
        if (name.isEmpty()) {
            return "【参数错误】action=delete 需要 name 参数。请传入要删除的标签名称，如 name=\"v1.0.0\"。";
        }

        GitCommandExecutor.GitResult result = gitExecutor.execute(projectRoot, "tag", "-d", name);
        if (!result.success()) {
            return "【Git 错误】删除标签失败（标签不存在？先 action=list 查看）。Git 返回信息：" + result.output();
        }
        log.info("Git 标签删除成功: {}", name);
        return "标签 '" + name + "' 已删除（仅本地；如已推送远程，需手动删除远程标签）。";
    }

    // ========== tag push ==========

    private String doPush(String projectRoot, JsonNode args) {
        String name = args.path("name").asText("");
        String remote = args.has("remote") ? args.get("remote").asText() : "origin";

        List<String> cmdArgs = new ArrayList<>();
        cmdArgs.add("push");
        cmdArgs.add(remote);
        if (!name.isEmpty()) {
            cmdArgs.add(name);
        } else {
            cmdArgs.add("--tags");
        }

        GitCommandExecutor.GitResult result = gitExecutor.executeWithStoredAuth(projectRoot, cmdArgs.toArray(new String[0]));
        if (!result.success()) {
            String output = result.output();
            if (output.contains("already exists")) {
                return "❌ 推送标签失败：远程已存在同名标签（如需覆盖请先删除远程标签或换用新版本号）。\n详细错误：" + output;
            }
            return "git push tag 失败：" + output;
        }
        return "✅ 标签推送成功！" + (name.isEmpty() ? "（已推送全部标签）" : "（" + name + "）") + (result.output().isBlank() ? "" : "\n" + result.output());
    }
}
