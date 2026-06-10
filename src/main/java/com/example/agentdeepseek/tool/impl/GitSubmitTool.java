package com.example.agentdeepseek.tool.impl;

import com.example.agentdeepseek.tool.Tool;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.File;

/**
 * Git 提交工具 — 合并 git_add / git_commit / git_push
 * 通过 action 参数区分操作，覆盖完整的提交流程
 */
@Slf4j
@Component
public class GitSubmitTool implements Tool {

    private final GitCommandExecutor gitExecutor;
    private final ObjectMapper mapper = new ObjectMapper();

    public GitSubmitTool(GitCommandExecutor gitExecutor) {
        this.gitExecutor = gitExecutor;
    }

    @Override
    public String getName() {
        return "git_submit";
    }

    @Override
    public String getDescription() {
        return "【适用场景】执行 Git 提交相关操作：暂存文件、创建提交、推送到远程仓库。是提交流程的三步曲。\n"
                + "【action 说明】add=暂存文件到暂存区（git add）；commit=创建版本提交（git commit）；push=推送到远程仓库（git push）。\n"
                + "【使用方式】典型流程：先 add 暂存文件，再 commit 创建提交（传 message），最后 push 推送远程。\n"
                + "【注意事项】commit 前必须先 add；push 前必须先 commit；force 强制推送为危险操作，需确认后使用。";
    }

    @Override
    public JsonNode getParameters() {
        ObjectNode root = mapper.createObjectNode();
        root.put("type", "object");

        ObjectNode properties = root.putObject("properties");

        // projectRoot
        ObjectNode projectRoot = properties.putObject("projectRoot");
        projectRoot.put("type", "string");
        projectRoot.put("description", "【必填】Git 项目根目录路径。示例：'/home/user/project' 或 'E:/work/my-app'。");

        // action
        ObjectNode action = properties.putObject("action");
        action.put("type", "string");
        action.put("description", "【必填】提交操作类型。add=暂存文件到暂存区；commit=创建版本快照；push=推送到远程仓库。");
        ArrayNode actionEnum = action.putArray("enum");
        actionEnum.add("add");
        actionEnum.add("commit");
        actionEnum.add("push");

        // path（仅 add 有效）
        ObjectNode path = properties.putObject("path");
        path.put("type", "string");
        path.put("description", "【add 时必填】要暂存的文件路径。单文件：'src/main/App.java'；多文件逗号分隔：'src/A.java,src/B.java'；暂存全部：'all' 或 '.'。");

        // message（仅 commit 有效）
        ObjectNode message = properties.putObject("message");
        message.put("type", "string");
        message.put("description", "【commit 时必填】提交信息，建议按 conventional commits 格式：'feat: 新增XX功能'、'fix: 修复XX问题'、'refactor: 重构XX模块'、'docs: 更新XX文档'。");

        // remote（仅 push 有效）
        ObjectNode remote = properties.putObject("remote");
        remote.put("type", "string");
        remote.put("description", "【可选，仅 push 时有效】远程仓库名称，默认 origin。示例：'origin'。");

        // branch（仅 push 有效）
        ObjectNode branch = properties.putObject("branch");
        branch.put("type", "string");
        branch.put("description", "【可选，仅 push 时有效】要推送的分支名。不传则推送当前所在分支。示例：'main'。");

        // set_upstream（仅 push 有效）
        ObjectNode setUpstream = properties.putObject("set_upstream");
        setUpstream.put("type", "boolean");
        setUpstream.put("description", "【可选，仅 push 时有效】首次推送新分支时设为 true（等价 git push -u），之后推送同一分支无需再设。");

        // force（仅 push 有效）
        ObjectNode force = properties.putObject("force");
        force.put("type", "boolean");
        force.put("description", "【可选，仅 push 时有效】设为 true 强制推送覆盖远程历史（--force）。警告：此操作不可逆，会覆盖远程其他人的提交，仅在你确认需要时使用。");

        // required
        ArrayNode required = root.putArray("required");
        required.add("projectRoot");
        required.add("action");

        return root;
    }

    @Override
    public String execute(JsonNode arguments) {
        String projectRoot = arguments.has("projectRoot") ? arguments.get("projectRoot").asText() : ".";
        String action = arguments.has("action") ? arguments.get("action").asText() : "";

        log.info("git_submit action={}, projectRoot={}", action, projectRoot);

        if (action.isEmpty()) {
            return "【参数缺失】'action' 参数缺失或为空。git_submit 的 action 必须设为 'add' / 'commit' / 'push' 之一。\n"
                    + "正确示例：{ \"action\": \"add\", \"path\": \"src/App.java\" }\n"
                    + "错误示例：{ \"path\": \"src/App.java\" } ← 缺少 action！";
        }
        return switch (action) {
            case "add" -> doAdd(projectRoot, arguments);
            case "commit" -> doCommit(projectRoot, arguments);
            case "push" -> doPush(projectRoot, arguments);
            default -> "❌ 错误：未知的 action '" + action + "'，仅支持 add / commit / push 三种取值，请改为其中之一。";
        };
    }

    // ========== git add ==========

    private String doAdd(String projectRoot, JsonNode args) {
        String path = args.has("path") ? args.get("path").asText() : null;

        if (path == null || path.isBlank()) {
            return "错误：action=add 时必须提供 path 参数。示例：path='src/main/App.java'（单文件）、path='src/A.java,src/B.java'（多文件逗号分隔）、path='all'（暂存所有变更）。";
        }

        // 处理 "all" 或 "." → 暂存全部
        if ("all".equals(path) || ".".equals(path)) {
            GitCommandExecutor.GitResult result = gitExecutor.execute(projectRoot, "add", "-A");
            if (!result.success()) {
                return "git add -A 失败：" + result.output();
            }
            return "✅ 已暂存所有文件变更（git add -A）。";
        }

        // 多文件逗号分隔（注意：文件名含逗号的极端情况会被错误拆分，建议用户用多次 add 替代）
        String[] files = path.split(",");
        StringBuilder sb = new StringBuilder();
        boolean allOk = true;

        for (String file : files) {
            String trimmed = file.trim();
            if (trimmed.isEmpty()) continue;

            // 检查文件是否存在
            File f = new File(projectRoot, trimmed);
            if (!f.exists()) {
                sb.append("⚠️ 文件不存在，跳过：").append(trimmed).append("\n");
                allOk = false;
                continue;
            }

            GitCommandExecutor.GitResult result = gitExecutor.execute(projectRoot, "add", trimmed);
            if (result.success()) {
                sb.append("✅ git add ").append(trimmed).append("\n");
            } else {
                sb.append("❌ git add ").append(trimmed).append(" 失败：").append(result.output()).append("\n");
                allOk = false;
            }
        }

        if (sb.isEmpty()) {
            return "没有需要暂存的文件。";
        }
        sb.insert(0, allOk ? "所有文件暂存成功：\n" : "部分文件暂存（详见下方）：\n");
        return sb.toString();
    }

    // ========== git commit ==========

    private String doCommit(String projectRoot, JsonNode args) {
        String message = args.has("message") ? args.get("message").asText() : null;

        if (message == null || message.isBlank()) {
            return "错误：action=commit 时必须提供 message 参数。示例：message='feat: 新增用户登录功能'。"
                    + "\n建议使用 conventional commits 格式："
                    + "\n  feat:    新功能"
                    + "\n  fix:     修复 Bug"
                    + "\n  refactor: 重构"
                    + "\n  docs:    文档更新"
                    + "\n  chore:   构建/工具变更";
        }

        GitCommandExecutor.GitResult result = gitExecutor.execute(projectRoot, "commit", "-m", message);
        if (!result.success()) {
            String output = result.output();
            String lowerOutput = output.toLowerCase();
            // 检测空暂存区场景（兼容中英文 git 输出）：
            //   git 将 "nothing to commit" 输出到 stderr，
            //   GitCommandExecutor 在 exitCode≠0 且 stdout 为空时用 stderr 作为 output
            if (lowerOutput.contains("nothing to commit")
                    || lowerOutput.contains("nothing added to commit")
                    || lowerOutput.contains("no changes added to commit")
                    || output.contains("没有要提交")
                    || output.contains("无文件要提交")
                    || output.contains("暂存区")
                    || output.contains("工作区干净")) {
                return "❌ 提交失败：暂存区为空，没有文件需要提交。请先使用 git_submit action=add 暂存文件。";
            }
            return "git commit 失败：" + output;
        }
        return "✅ " + result.output();
    }

    // ========== git push ==========

    private String doPush(String projectRoot, JsonNode args) {
        String remote = args.has("remote") ? args.get("remote").asText() : "origin";
        String branch = args.has("branch") ? args.get("branch").asText() : null;
        boolean setUpstream = args.has("set_upstream") && args.get("set_upstream").asBoolean();
        boolean force = args.has("force") && args.get("force").asBoolean();

        java.util.List<String> cmdArgs = new java.util.ArrayList<>();
        cmdArgs.add("push");

        // 参数顺序对齐 git push 习惯: push [-u] [--force] <remote> [<branch>]
        if (setUpstream) {
            cmdArgs.add("-u");
        }
        if (force) {
            cmdArgs.add("--force");
        }

        cmdArgs.add(remote);

        if (branch != null && !branch.isBlank()) {
            cmdArgs.add(branch);
        }

        GitCommandExecutor.GitResult result = gitExecutor.executeWithStoredAuth(
                projectRoot,
                cmdArgs.toArray(new String[0])
        );

        if (!result.success()) {
            String output = result.output();
            // 常见错误处理
            if (output.contains("rejected") || output.contains("non-fast-forward")) {
                return "❌ 推送被拒绝：远程仓库有更新的提交，请先 git pull 拉取最新代码再推送。"
                        + (force ? "" : "\n💡 如需强制覆盖远程历史可使用 force=true（危险操作！）")
                        + "\n详细错误：" + output;
            }
            if (output.contains("no upstream branch")) {
                return "❌ 推送失败：当前分支没有上游分支。请使用 set_upstream=true 建立关联。\n详细错误：" + output;
            }
            return "git push 失败：" + output;
        }

        String output = result.output();
        return "✅ 推送成功！" + (output.isBlank() ? "" : "\n" + output);
    }
}
