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

import java.io.File;

/**
 * Git 提交工具 — 提交、远程同步与本地撤销
 * 通过 action 参数区分操作：add / commit / push / pull / fetch / unstage / restore / reset / revert
 */
@Slf4j
@Component
@ToolPermission(category = OperationCategory.GIT, affectsData = true, description = "Git提交、远程同步与撤销操作")
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
        return "【适用场景】执行 Git 提交与同步相关操作：暂存文件、创建提交、推送/拉取/获取远程更新、取消暂存、还原文件改动、回退与撤销提交。\n"
                + "【action 说明】add=暂存文件；commit=创建版本提交；push=推送远程；pull=拉取远程并合并；fetch=仅获取远程更新（不合并）；unstage=取消暂存（保留改动）；restore=撤销工作区未提交改动（⚠会丢弃改动）；reset=回退提交（soft/mixed/hard）；revert=创建反向提交撤销指定提交。\n"
                + "【使用方式】典型流程：先 add 暂存，再 commit 提交（传 message），最后 push 推送；推送被拒（远程有新提交）时先 pull 再 push。\n"
                + "【注意事项】commit 前必须先 add；reset --hard 必须显式传 confirm=true；restore 会永久丢弃未提交的工作区改动，使用前务必确认。";
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
        action.put("description", "【必填】操作类型。add=暂存文件；commit=创建版本快照；push=推送远程；pull=拉取远程并合并；fetch=获取远程更新；unstage=取消暂存；restore=还原工作区改动；reset=回退提交；revert=撤销指定提交。");
        ArrayNode actionEnum = action.putArray("enum");
        actionEnum.add("add");
        actionEnum.add("commit");
        actionEnum.add("push");
        actionEnum.add("pull");
        actionEnum.add("fetch");
        actionEnum.add("unstage");
        actionEnum.add("restore");
        actionEnum.add("reset");
        actionEnum.add("revert");

        // path（add / unstage / restore 共用）
        ObjectNode path = properties.putObject("path");
        path.put("type", "string");
        path.put("description", "【add/unstage/restore 时必填】文件路径。单文件：'src/main/App.java'；多文件逗号分隔：'src/A.java,src/B.java'；全部：'all' 或 '.'。");

        // message（仅 commit 有效）
        ObjectNode message = properties.putObject("message");
        message.put("type", "string");
        message.put("description", "【commit 时必填】提交信息，建议按 conventional commits 格式：'feat: 新增XX功能'、'fix: 修复XX问题'、'refactor: 重构XX模块'、'docs: 更新XX文档'。");

        // remote（push / pull / fetch 共用）
        ObjectNode remote = properties.putObject("remote");
        remote.put("type", "string");
        remote.put("description", "【可选，push/pull/fetch 时有效】远程仓库名称，默认 origin。示例：'origin'。");

        // branch（push / pull 共用）
        ObjectNode branch = properties.putObject("branch");
        branch.put("type", "string");
        branch.put("description", "【可选，push/pull 时有效】分支名。push 不传则推送当前分支；pull 不传则拉取当前分支的上游。示例：'main'。");

        // set_upstream（仅 push）
        ObjectNode setUpstream = properties.putObject("set_upstream");
        setUpstream.put("type", "boolean");
        setUpstream.put("description", "【可选，仅 push 时有效】首次推送新分支时设为 true（等价 git push -u），之后推送同一分支无需再设。");

        // force（仅 push）
        ObjectNode force = properties.putObject("force");
        force.put("type", "boolean");
        force.put("description", "【可选，仅 push 时有效】设为 true 强制推送覆盖远程历史（--force）。警告：此操作不可逆，会覆盖远程其他人的提交，仅在你确认需要时使用。");

        // prune（仅 fetch）
        ObjectNode prune = properties.putObject("prune");
        prune.put("type", "boolean");
        prune.put("description", "【可选，仅 fetch 时有效】设为 true 清理远程已删除分支的本地追踪引用（--prune）。");

        // mode（仅 reset）
        ObjectNode mode = properties.putObject("mode");
        mode.put("type", "string");
        mode.put("description", "【可选，仅 reset 时有效】回退模式，默认 mixed。soft=撤销提交、改动保留在暂存区；mixed=撤销提交与暂存、改动保留在工作区；hard=丢弃提交与全部未提交改动（⚠危险，需 confirm=true）。");
        ArrayNode modeEnum = mode.putArray("enum");
        modeEnum.add("soft");
        modeEnum.add("mixed");
        modeEnum.add("hard");

        // target（仅 reset）
        ObjectNode target = properties.putObject("target");
        target.put("type", "string");
        target.put("description", "【reset 时必填】回退目标。示例：'HEAD~1'（回退到上一次提交，撤销最近一次提交时常用）。");

        // confirm（仅 reset --hard）
        ObjectNode confirm = properties.putObject("confirm");
        confirm.put("type", "boolean");
        confirm.put("description", "【reset mode=hard 时必填】二次确认标志。reset --hard 会永久丢弃未提交改动，必须显式传 true 才会执行。");

        // commit（仅 revert）
        ObjectNode revertCommit = properties.putObject("commit");
        revertCommit.put("type", "string");
        revertCommit.put("description", "【可选，仅 revert 时有效】要撤销的提交引用，默认 HEAD。示例：'HEAD'、'a1b2c3d'。");

        // no_commit（仅 revert）
        ObjectNode noCommit = properties.putObject("no_commit");
        noCommit.put("type", "boolean");
        noCommit.put("description", "【可选，仅 revert 时有效】设为 true 只撤销改动到暂存区/工作区，不自动创建提交（--no-commit）。");

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
            return "【参数缺失】'action' 参数缺失或为空。git_submit 的 action 必须设为 'add' / 'commit' / 'push' / 'pull' / 'fetch' / 'unstage' / 'restore' / 'reset' / 'revert' 之一。\n"
                    + "正确示例：{ \"action\": \"add\", \"path\": \"src/App.java\" }\n"
                    + "错误示例：{ \"path\": \"src/App.java\" } ← 缺少 action！";
        }
        return switch (action) {
            case "add" -> doAdd(projectRoot, arguments);
            case "commit" -> doCommit(projectRoot, arguments);
            case "push" -> doPush(projectRoot, arguments);
            case "pull" -> doPull(projectRoot, arguments);
            case "fetch" -> doFetch(projectRoot, arguments);
            case "unstage" -> doUnstage(projectRoot, arguments);
            case "restore" -> doRestore(projectRoot, arguments);
            case "reset" -> doReset(projectRoot, arguments);
            case "revert" -> doRevert(projectRoot, arguments);
            default -> "❌ 错误：未知的 action '" + action + "'，仅支持 add / commit / push / pull / fetch / unstage / restore / reset / revert，请改为其中之一。";
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
                return "❌ 推送被拒绝：远程仓库有更新的提交，请先执行 git_submit action=pull（或 action=fetch + git_branch action=merge）拉取并合并最新代码后再推送。"
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

    // ========== git pull ==========

    private String doPull(String projectRoot, JsonNode args) {
        String remote = args.has("remote") ? args.get("remote").asText() : "origin";
        String branch = args.has("branch") ? args.get("branch").asText() : null;

        java.util.List<String> cmdArgs = new java.util.ArrayList<>();
        cmdArgs.add("pull");
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
            if (output.contains("CONFLICT") || output.contains("冲突")) {
                return "❌ 拉取产生冲突：需手动解决冲突后提交。"
                        + "\n建议：①git_query action=status 查看冲突文件；②手动编辑解决冲突标记（<<<<<<< / ======= / >>>>>>>）；"
                        + "③git_submit action=add 暂存、action=commit 提交完成合并。"
                        + "\n详细错误：" + output;
            }
            if (output.contains("no tracking information") || output.contains("no such remote")) {
                return "❌ 拉取失败：当前分支未关联远程分支，或远程仓库不存在。"
                        + "\n建议：确认 remote 参数（默认 origin）；首次推送时可用 push 的 set_upstream=true 建立分支关联。"
                        + "\n详细错误：" + output;
            }
            return "git pull 失败：" + output;
        }

        String output = result.output();
        if (output.contains("Already up to date") || output.contains("已经是最新")) {
            return "✅ 已是最新（Already up to date）。";
        }
        return "✅ 拉取成功！" + (output.isBlank() ? "" : "\n" + output);
    }

    // ========== git fetch ==========

    private String doFetch(String projectRoot, JsonNode args) {
        String remote = args.has("remote") ? args.get("remote").asText() : "origin";
        boolean prune = args.has("prune") && args.get("prune").asBoolean();

        java.util.List<String> cmdArgs = new java.util.ArrayList<>();
        cmdArgs.add("fetch");
        if (prune) {
            cmdArgs.add("--prune");
        }
        cmdArgs.add(remote);

        GitCommandExecutor.GitResult result = gitExecutor.executeWithStoredAuth(
                projectRoot,
                cmdArgs.toArray(new String[0])
        );

        if (!result.success()) {
            return "git fetch 失败：" + result.output();
        }
        String output = result.output();
        return "✅ 获取远程更新完成。" + (output.isBlank() ? "（远程无新内容）" : "\n" + output);
    }

    // ========== git unstage（取消暂存） ==========

    private String doUnstage(String projectRoot, JsonNode args) {
        String path = args.has("path") ? args.get("path").asText() : null;

        if (path == null || path.isBlank()) {
            return "错误：action=unstage 时必须提供 path 参数（要取消暂存的文件）。示例：path='src/main/App.java'（单文件）、path='all'（取消全部暂存）。改动会保留在工作区。";
        }

        if ("all".equals(path) || ".".equals(path)) {
            GitCommandExecutor.GitResult result = gitExecutor.execute(projectRoot, "reset", "HEAD", "--", ".");
            if (!result.success()) {
                return "git reset HEAD -- . 失败：" + result.output();
            }
            return "✅ 已取消全部暂存（改动保留在工作区）。";
        }

        String[] files = path.split(",");
        StringBuilder sb = new StringBuilder();
        boolean allOk = true;

        for (String file : files) {
            String trimmed = file.trim();
            if (trimmed.isEmpty()) continue;

            GitCommandExecutor.GitResult result = gitExecutor.execute(projectRoot, "reset", "HEAD", "--", trimmed);
            if (result.success()) {
                sb.append("✅ 已取消暂存: ").append(trimmed).append("\n");
            } else {
                sb.append("❌ 取消暂存失败: ").append(trimmed).append(" — ").append(result.output()).append("\n");
                allOk = false;
            }
        }

        if (sb.isEmpty()) {
            return "没有指定要取消暂存的文件。";
        }
        sb.insert(0, allOk ? "取消暂存完成：\n" : "部分取消暂存（详见下方）：\n");
        return sb.toString();
    }

    // ========== git restore（还原工作区改动） ==========

    private String doRestore(String projectRoot, JsonNode args) {
        String path = args.has("path") ? args.get("path").asText() : null;

        if (path == null || path.isBlank()) {
            return "错误：action=restore 时必须提供 path 参数（要还原的文件）。⚠️ 该操作会永久丢弃工作区未提交的改动。示例：path='src/main/App.java'（单文件）、path='all'（还原全部未暂存改动）。";
        }

        if ("all".equals(path) || ".".equals(path)) {
            GitCommandExecutor.GitResult result = gitExecutor.execute(projectRoot, "checkout", "--", ".");
            if (!result.success()) {
                return "git checkout -- . 失败：" + result.output();
            }
            return "✅ 已还原全部未暂存的工作区改动（⚠ 改动已丢弃）。";
        }

        String[] files = path.split(",");
        StringBuilder sb = new StringBuilder();
        boolean allOk = true;

        for (String file : files) {
            String trimmed = file.trim();
            if (trimmed.isEmpty()) continue;

            GitCommandExecutor.GitResult result = gitExecutor.execute(projectRoot, "checkout", "--", trimmed);
            if (result.success()) {
                sb.append("✅ 已还原: ").append(trimmed).append("\n");
            } else {
                sb.append("❌ 还原失败: ").append(trimmed).append(" — ").append(result.output()).append("\n");
                allOk = false;
            }
        }

        if (sb.isEmpty()) {
            return "没有指定要还原的文件。";
        }
        sb.insert(0, allOk ? "还原完成（⚠ 改动已丢弃）：\n" : "部分还原（详见下方）：\n");
        return sb.toString();
    }

    // ========== git reset（回退提交） ==========

    private String doReset(String projectRoot, JsonNode args) {
        String mode = args.has("mode") ? args.get("mode").asText() : "mixed";
        String target = args.has("target") ? args.get("target").asText() : null;
        boolean confirm = args.has("confirm") && args.get("confirm").asBoolean();

        if (target == null || target.isBlank()) {
            return "错误：action=reset 时必须提供 target 参数。示例：target='HEAD~1'（回退到上一次提交）。mode 可选 soft / mixed / hard，默认 mixed。";
        }
        java.util.Set<String> validModes = java.util.Set.of("soft", "mixed", "hard");
        if (!validModes.contains(mode)) {
            return "错误：mode 仅支持 soft / mixed / hard，当前值：'" + mode + "'。";
        }
        if ("hard".equals(mode) && !confirm) {
            return "⚠️ 高危操作拦截：reset --hard 会【永久丢弃】工作区与暂存区的全部未提交改动，且无法恢复。"
                    + "\n如确认要执行，请显式传入 confirm=true 参数。"
                    + "\n（若只想撤销提交但保留改动，请用 mode=soft，如 mode='soft', target='HEAD~1'）";
        }

        GitCommandExecutor.GitResult result = gitExecutor.execute(projectRoot, "reset", "--" + mode, target);
        if (!result.success()) {
            return "git reset 失败：" + result.output();
        }
        String tip = switch (mode) {
            case "soft" -> "已回退（--soft）：提交已撤销，改动保留在暂存区。";
            case "hard" -> "已回退（--hard）：工作区与暂存区均已重置（未提交改动已丢弃）。";
            default -> "已回退（--mixed）：提交已撤销，改动保留在工作区（未暂存）。";
        };
        String output = result.output();
        return "✅ " + tip + (output.isBlank() ? "" : "\n" + output);
    }

    // ========== git revert（撤销指定提交） ==========

    private String doRevert(String projectRoot, JsonNode args) {
        String commit = args.has("commit") ? args.get("commit").asText() : "HEAD";
        if (commit == null || commit.isBlank()) commit = "HEAD";
        boolean noCommit = args.has("no_commit") && args.get("no_commit").asBoolean();

        java.util.List<String> cmdArgs = new java.util.ArrayList<>();
        cmdArgs.add("revert");
        cmdArgs.add("--no-edit");
        if (noCommit) {
            cmdArgs.add("--no-commit");
        }
        cmdArgs.add(commit);

        GitCommandExecutor.GitResult result = gitExecutor.execute(projectRoot, cmdArgs.toArray(new String[0]));
        if (!result.success()) {
            String output = result.output();
            if (output.contains("CONFLICT") || output.contains("冲突")) {
                return "❌ 撤销提交产生冲突：需手动解决冲突。"
                        + "\n建议：①手动解决冲突文件；②git_submit action=add 暂存、action=commit 提交完成撤销操作。"
                        + "\n详细错误：" + output;
            }
            return "git revert 失败：" + output;
        }
        String output = result.output();
        if (noCommit) {
            return "✅ 已应用反向改动（--no-commit，未创建提交，改动在暂存区/工作区）。" + (output.isBlank() ? "" : "\n" + output);
        }
        return "✅ 已创建反向提交（撤销 " + commit + " 的改动）。" + (output.isBlank() ? "" : "\n" + output);
    }
}
