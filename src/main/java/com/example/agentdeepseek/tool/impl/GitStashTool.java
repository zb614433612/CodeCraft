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
 * Git 储藏工具 — 管理 stash 储藏栈
 * 支持保存 / 列出 / 弹出 / 删除储藏记录
 */
@Slf4j
@Component
@ToolPermission(category = OperationCategory.GIT, affectsData = true, description = "管理Git储藏(stash)")
public class GitStashTool implements Tool {

    private final GitCommandExecutor gitExecutor;
    private final ObjectMapper objectMapper;

    public GitStashTool(GitCommandExecutor gitExecutor, ObjectMapper objectMapper) {
        this.gitExecutor = gitExecutor;
        this.objectMapper = objectMapper;
    }

    @Override
    public String getName() {
        return "git_stash";
    }

    @Override
    public String getDescription() {
        return "【适用场景】管理 Git 储藏（stash）：临时保存当前工作区改动（如切换分支前保存未提交的工作）、恢复储藏、查看与删除储藏记录。\n"
                + "【action 说明】save=储藏当前工作区与暂存区改动（可选 message 备注）；list=列出所有储藏记录；pop=恢复并删除指定储藏（默认最新，冲突时保留记录）；drop=删除指定储藏（默认最新，⚠不恢复内容）。\n"
                + "【使用方式】projectRoot 必填。分支切换失败提示\"有未提交变更\"时，可先 action=save 储藏，切换后再 action=pop 恢复。";
    }

    @Override
    public JsonNode getParameters() {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("type", "object");

        ObjectNode properties = objectMapper.createObjectNode();

        ObjectNode projectRoot = properties.putObject("projectRoot");
        projectRoot.put("type", "string");
        projectRoot.put("description", "【必填】Git 项目根目录路径。示例：'/home/user/project' 或 'E:/work/my-app'。");

        ObjectNode action = properties.putObject("action");
        action.put("type", "string");
        action.put("description", "【可选，默认 list】操作类型：save=储藏当前改动；list=列出储藏记录；pop=恢复储藏（默认最新）；drop=删除储藏（默认最新）。");
        action.putArray("enum").add("save").add("list").add("pop").add("drop");

        ObjectNode message = properties.putObject("message");
        message.put("type", "string");
        message.put("description", "【save 时可选】储藏备注信息（-m），如 'WIP: 登录页开发中'。");

        ObjectNode index = properties.putObject("index");
        index.put("type", "integer");
        index.put("description", "【pop/drop 时可选】储藏序号（stash@{n} 中的 n），默认 0（最新）。先用 action=list 查看序号。");

        ArrayNode required = root.putArray("required");
        required.add("projectRoot");

        return root;
    }

    @Override
    public String execute(JsonNode arguments) {
        String projectRoot = arguments.has("projectRoot") ? arguments.get("projectRoot").asText() : ".";
        String action = arguments.path("action").asText("list");

        return switch (action) {
            case "save" -> doSave(projectRoot, arguments);
            case "pop" -> doPop(projectRoot, arguments);
            case "drop" -> doDrop(projectRoot, arguments);
            default -> doList(projectRoot);
        };
    }

    // ========== stash save ==========

    private String doSave(String projectRoot, JsonNode args) {
        String message = args.has("message") ? args.get("message").asText() : null;

        List<String> cmdArgs = new ArrayList<>();
        cmdArgs.add("stash");
        cmdArgs.add("push");
        if (message != null && !message.isBlank()) {
            cmdArgs.add("-m");
            cmdArgs.add(message);
        }

        GitCommandExecutor.GitResult result = gitExecutor.execute(projectRoot, cmdArgs.toArray(new String[0]));
        if (!result.success()) {
            return "git stash 失败：" + result.output();
        }
        String output = result.output();
        if (output.contains("No local changes") || output.contains("没有要保存")) {
            return "没有需要储藏的改动（工作区与暂存区均无变更）。";
        }
        return "✅ 已储藏当前改动。" + (output.isBlank() ? "" : "\n" + output);
    }

    // ========== stash list ==========

    private String doList(String projectRoot) {
        GitCommandExecutor.GitResult result = gitExecutor.execute(projectRoot, "stash", "list");
        if (!result.success()) {
            return "git stash list 失败：" + result.output();
        }
        String output = result.output();
        if (output.isBlank()) {
            return "没有储藏记录。";
        }
        return "储藏记录（stash@{n}，序号越小越新）：\n" + output;
    }

    // ========== stash pop ==========

    private String doPop(String projectRoot, JsonNode args) {
        String stashRef = buildStashRef(args);
        GitCommandExecutor.GitResult result = stashRef == null
                ? gitExecutor.execute(projectRoot, "stash", "pop")
                : gitExecutor.execute(projectRoot, "stash", "pop", stashRef);
        if (!result.success()) {
            String output = result.output();
            if (output.contains("CONFLICT") || output.contains("冲突")) {
                return "❌ 弹出储藏产生冲突：改动已恢复到工作区但存在冲突，需手动解决（冲突时储藏记录不会被删除）。"
                        + "\n建议：手动解决冲突标记后 git_submit action=add + action=commit。\n详细错误：" + output;
            }
            if (output.contains("No stash entries") || output.contains("没有储藏")) {
                return "弹出失败：没有对应的储藏记录（先 action=list 查看序号）。";
            }
            return "git stash pop 失败：" + output;
        }
        return "✅ 已恢复储藏内容。" + (result.output().isBlank() ? "" : "\n" + result.output());
    }

    // ========== stash drop ==========

    private String doDrop(String projectRoot, JsonNode args) {
        String stashRef = buildStashRef(args);
        GitCommandExecutor.GitResult result = stashRef == null
                ? gitExecutor.execute(projectRoot, "stash", "drop")
                : gitExecutor.execute(projectRoot, "stash", "drop", stashRef);
        if (!result.success()) {
            return "git stash drop 失败：" + result.output();
        }
        return "✅ 已删除储藏记录。" + (result.output().isBlank() ? "" : "\n" + result.output());
    }

    /** 将 index 参数转换为 stash@{n}；未提供时返回 null（使用默认最新） */
    private String buildStashRef(JsonNode args) {
        if (!args.has("index")) return null;
        int idx = args.get("index").asInt(0);
        return "stash@{" + Math.max(idx, 0) + "}";
    }
}
