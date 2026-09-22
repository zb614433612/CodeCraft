package com.example.agentdeepseek.controller;

import com.example.agentdeepseek.tool.impl.GitAuthStore;
import com.example.agentdeepseek.tool.impl.GitCommandExecutor;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/**
 * Git 操作控制器
 * 提供前端 Git 侧边栏调用的 REST API
 */
@Slf4j
@RestController
@RequestMapping("/api/git")
@Tag(name = "Git操作", description = "Git 仓库管理和提交操作")
public class GitController {

    private final GitCommandExecutor gitExecutor;
    private final GitAuthStore gitAuthStore;
    private final ObjectMapper objectMapper;

    public GitController(GitCommandExecutor gitExecutor, GitAuthStore gitAuthStore, ObjectMapper objectMapper) {
        this.gitExecutor = gitExecutor;
        this.gitAuthStore = gitAuthStore;
        this.objectMapper = objectMapper;
    }

    @Operation(summary = "获取仓库状态")
    @GetMapping("/status")
    public Map<String, Object> getStatus(@RequestParam String projectRoot) {
        Map<String, Object> result = new LinkedHashMap<>();

        // 检查是否是 git 仓库
        GitCommandExecutor.GitResult gitCheck = gitExecutor.execute(projectRoot, "rev-parse", "--is-inside-work-tree");
        if (!gitCheck.success()) {
            result.put("isRepo", false);
            result.put("error", "不是 git 仓库");
            return result;
        }

        result.put("isRepo", true);

        // 当前分支
        GitCommandExecutor.GitResult branchResult = gitExecutor.execute(projectRoot, "rev-parse", "--abbrev-ref", "HEAD");
        result.put("branch", branchResult.success() ? branchResult.output().trim() : "未知");

        // ===== 变更文件列表 =====
        // 两步：
        //   1. git diff --name-only HEAD → 已跟踪文件的真正内容差异
        //      （忽略纯换行符差异，HOME 已注入子进程使得 autocrlf 生效）
        //   2. git status --porcelain → 仅提取 ?? 行 → 未跟踪文件
        //      （自动遵守 .gitignore，无需手写排除规则）
        List<Map<String, String>> changes = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();

        // 1. 已跟踪文件的修改/新增/删除（git diff 内建 autocrlf 规范化）
        appendTrackedChanges(changes, seen, projectRoot);

        // 2. 未跟踪文件（仅取 git status --porcelain 的 ?? 行）
        appendUntrackedFiles(changes, seen, projectRoot);

        result.put("changes", changes);
        log.info("[Git] 仓库状态: {} 个变更文件", changes.size());
        if (!changes.isEmpty()) {
            for (Map<String, String> c : changes) {
                log.info("[Git]   {} [{}]", c.get("file"), c.get("label"));
            }
        }

        // 是否有 Token
        result.put("hasToken", gitAuthStore.hasToken(projectRoot));

        // 远程仓库信息
        GitCommandExecutor.GitResult remoteResult = gitExecutor.execute(projectRoot,
                "remote", "get-url", "origin");
        result.put("remoteUrl", remoteResult.success() ? remoteResult.output().trim() : "");

        return result;
    }

    @Operation(summary = "获取文件 Diff")
    @GetMapping("/diff")
    public Map<String, Object> getDiff(
            @RequestParam String projectRoot,
            @RequestParam(defaultValue = "") String file,
            @RequestParam(defaultValue = "false") boolean staged) {
        Map<String, Object> result = new LinkedHashMap<>();

        List<String> args = new ArrayList<>();
        args.add("diff");
        if (staged) {
            args.add("--cached");
        }
        // 始终以 HEAD 为基准做对比（git diff [--cached] HEAD -- file）
        // 这样 diff 展示的是工作区/暂存区 vs 最新提交，而非工作区 vs 暂存区
        args.add("HEAD");
        if (!file.isEmpty()) {
            args.add("--");
            args.add(file);
        }

        GitCommandExecutor.GitResult diffResult = gitExecutor.execute(
                projectRoot, args.toArray(new String[0]));

        // stderr 已在 GitCommandExecutor 中分离，output 不含 warning
        result.put("success", diffResult.success());
        result.put("diff", diffResult.output());
        return result;
    }

    @Operation(summary = "暂存文件")
    @PostMapping("/add")
    public Map<String, Object> addFiles(@RequestBody Map<String, Object> body) {
        Map<String, Object> result = new LinkedHashMap<>();
        String projectRoot = (String) body.get("projectRoot");

        @SuppressWarnings("unchecked")
        List<String> files = (List<String>) body.get("files");
        boolean all = Boolean.TRUE.equals(body.get("all"));

        GitCommandExecutor.GitResult gitResult;
        if (all) {
            gitResult = gitExecutor.execute(projectRoot, "add", "-A");
        } else if (files != null && !files.isEmpty()) {
            String[] fileArray = files.toArray(new String[0]);
            String[] args = new String[fileArray.length + 1];
            args[0] = "add";
            System.arraycopy(fileArray, 0, args, 1, fileArray.length);
            gitResult = gitExecutor.execute(projectRoot, args);
        } else {
            result.put("success", false);
            result.put("error", "请指定 files 或设置 all=true");
            return result;
        }

        result.put("success", gitResult.success());
        if (!gitResult.success()) {
            result.put("error", gitResult.output());
        }
        return result;
    }

    @Operation(summary = "执行提交")
    @PostMapping("/commit")
    public Map<String, Object> commit(@RequestBody Map<String, Object> body) {
        Map<String, Object> result = new LinkedHashMap<>();
        String projectRoot = (String) body.get("projectRoot");
        String message = (String) body.get("message");

        if (message == null || message.isEmpty()) {
            result.put("success", false);
            result.put("error", "缺少提交信息");
            return result;
        }

        // 检查是否有暂存的变更
        GitCommandExecutor.GitResult diffCheck = gitExecutor.execute(projectRoot,
                "diff", "--cached", "--quiet");
        if (diffCheck.success()) {
            result.put("success", false);
            result.put("error", "暂存区没有变更，请先暂存文件");
            return result;
        }

        GitCommandExecutor.GitResult commitResult = gitExecutor.executeWithStoredAuth(
                projectRoot, "commit", "-m", message);

        result.put("success", commitResult.success());
        result.put("output", commitResult.output());

        if (!commitResult.success()) {
            result.put("error", commitResult.output());
        }
        return result;
    }

    @Operation(summary = "获取提交历史")
    @GetMapping("/log")
    public Map<String, Object> getLog(
            @RequestParam String projectRoot,
            @RequestParam(defaultValue = "20") int maxCount) {
        Map<String, Object> result = new LinkedHashMap<>();

        GitCommandExecutor.GitResult logResult = gitExecutor.execute(projectRoot,
                "log", "--oneline", "-n", String.valueOf(maxCount));

        result.put("success", logResult.success());
        if (logResult.success()) {
            result.put("log", logResult.output());
        } else {
            result.put("error", logResult.output());
        }
        return result;
    }

    @Operation(summary = "保存 Git Token")
    @PostMapping("/auth")
    public Map<String, Object> saveAuth(@RequestBody Map<String, String> body) {
        Map<String, Object> result = new LinkedHashMap<>();
        String projectRoot = body.get("projectRoot");
        String token = body.get("token");

        if (projectRoot == null || token == null) {
            result.put("success", false);
            result.put("error", "缺少必要参数");
            return result;
        }

        try {
            gitAuthStore.saveToken(projectRoot, token);
            result.put("success", true);
        } catch (Exception e) {
            result.put("success", false);
            result.put("error", e.getMessage());
        }
        return result;
    }

    @Operation(summary = "检查 Git Token")
    @GetMapping("/auth")
    public Map<String, Object> checkAuth(@RequestParam String projectRoot) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("hasToken", gitAuthStore.hasToken(projectRoot));
        return result;
    }

    @Operation(summary = "清除 Git Token")
    @DeleteMapping("/auth")
    public Map<String, Object> clearAuth(@RequestParam String projectRoot) {
        gitAuthStore.clearToken(projectRoot);
        return Map.of("success", true);
    }

    @Operation(summary = "还原/撤销文件改动", description = "还原未暂存的修改（git checkout -- <file>）；all=true 时还原全部 (git checkout -- .)")
    @PostMapping("/restore")
    public Map<String, Object> restoreFile(@RequestBody Map<String, Object> body) {
        Map<String, Object> result = new LinkedHashMap<>();
        String projectRoot = body.get("projectRoot") != null ? String.valueOf(body.get("projectRoot")) : null;
        String file = body.get("file") != null ? String.valueOf(body.get("file")) : null;
        boolean all = Boolean.TRUE.equals(body.get("all")) || "true".equalsIgnoreCase(String.valueOf(body.get("all")));

        if (projectRoot == null || projectRoot.isEmpty()) {
            result.put("success", false);
            result.put("error", "缺少必要参数 projectRoot");
            return result;
        }
        if (!all && (file == null || file.isEmpty())) {
            result.put("success", false);
            result.put("error", "缺少必要参数 file（或设置 all=true 还原全部）");
            return result;
        }

        GitCommandExecutor.GitResult restoreResult = all
                ? gitExecutor.execute(projectRoot, "checkout", "--", ".")
                : gitExecutor.execute(projectRoot, "checkout", "--", file);

        result.put("success", restoreResult.success());
        if (!restoreResult.success()) {
            result.put("error", restoreResult.output());
        } else {
            result.put("output", all ? "已还原全部未暂存改动" : "已还原: " + file);
        }
        return result;
    }

    @Operation(summary = "获取HEAD版本文件", description = "获取指定文件在HEAD提交中的原始内容 (git show HEAD:file)")
    @GetMapping("/show")
    public Map<String, Object> showFile(
            @RequestParam String projectRoot,
            @RequestParam String file) {
        Map<String, Object> result = new LinkedHashMap<>();

        GitCommandExecutor.GitResult showResult = gitExecutor.execute(projectRoot,
                "show", "HEAD:" + file);

        result.put("success", showResult.success());
        if (showResult.success()) {
            result.put("content", showResult.output());
        } else {
            result.put("error", showResult.output());
        }
        return result;
    }

    @Operation(summary = "按块还原文件改动", description = "还原指定文件中选中的 diff hunks (git apply --reverse)")
    @PostMapping("/restore-hunks")
    public Map<String, Object> restoreHunks(@RequestBody Map<String, String> body) {
        Map<String, Object> result = new LinkedHashMap<>();
        String projectRoot = body.get("projectRoot");
        String file = body.get("file");
        String hunks = body.get("hunks");

        if (projectRoot == null || file == null || hunks == null || hunks.isEmpty()) {
            result.put("success", false);
            result.put("error", "缺少必要参数 projectRoot、file 或 hunks");
            return result;
        }

        // 格式化 hunk patch（确保以 --- / +++ 文件头开始）
        String fullPatch = "--- a/" + file + "\n+++ b/" + file + "\n" + hunks;

        GitCommandExecutor.GitResult applyResult = gitExecutor.executeWithStdin(
                projectRoot, fullPatch, "apply", "--reverse", "--unidiff-zero");

        result.put("success", applyResult.success());
        if (!applyResult.success()) {
            result.put("error", applyResult.output());
        } else {
            result.put("output", "已按块还原: " + file);
        }
        return result;
    }

    @Operation(summary = "初始化 Git 仓库")
    @PostMapping("/init")
    public Map<String, Object> initRepo(@RequestBody Map<String, Object> body) {
        Map<String, Object> result = new LinkedHashMap<>();
        String projectRoot = (String) body.get("projectRoot");
        String remoteUrl = (String) body.get("remoteUrl");
        String token = (String) body.get("token");

        if (projectRoot == null || projectRoot.isEmpty()) {
            result.put("success", false);
            result.put("error", "缺少必要参数 projectRoot");
            return result;
        }

        // 1. git init
        GitCommandExecutor.GitResult initResult = gitExecutor.execute(projectRoot, "init");
        if (!initResult.success()) {
            result.put("success", false);
            result.put("error", "git init 失败: " + initResult.output());
            return result;
        }
        log.info("Git 仓库初始化成功: {}", projectRoot);

        // 2. 设置 remote（可选）
        if (remoteUrl != null && !remoteUrl.isEmpty()) {
            // 先检查是否已存在 remote origin
            GitCommandExecutor.GitResult checkRemote = gitExecutor.execute(projectRoot, "remote", "get-url", "origin");
            if (checkRemote.success()) {
                // 已存在，更新 URL
                gitExecutor.execute(projectRoot, "remote", "set-url", "origin", remoteUrl);
            } else {
                gitExecutor.execute(projectRoot, "remote", "add", "origin", remoteUrl);
            }
            log.info("Git remote 已设置: {}", remoteUrl);
        }

        // 3. 保存 token（可选）
        if (token != null && !token.isEmpty()) {
            try {
                gitAuthStore.saveToken(projectRoot, token);
                log.info("Git Token 已保存");
            } catch (Exception e) {
                log.warn("Git Token 保存失败: {}", e.getMessage());
            }
        }

        result.put("success", true);
        return result;
    }

    // ==================== 远程同步（pull / fetch / push） ====================

    @Operation(summary = "拉取远程更新", description = "git pull [remote] [branch]，自动注入已保存 Token")
    @PostMapping("/pull")
    public Map<String, Object> pull(@RequestBody Map<String, Object> body) {
        String projectRoot = getStr(body, "projectRoot");
        if (projectRoot == null || projectRoot.isEmpty()) {
            return errorResult("缺少必要参数 projectRoot");
        }
        String remote = getStr(body, "remote");
        if (remote == null || remote.isBlank()) remote = "origin";
        String branch = getStr(body, "branch");

        List<String> args = new ArrayList<>();
        args.add("pull");
        args.add(remote);
        if (branch != null && !branch.isBlank()) {
            args.add(branch);
        }
        return execResult(gitExecutor.executeWithStoredAuth(projectRoot, args.toArray(new String[0])));
    }

    @Operation(summary = "获取远程更新", description = "git fetch [--prune] [remote]，不合并")
    @PostMapping("/fetch")
    public Map<String, Object> fetch(@RequestBody Map<String, Object> body) {
        String projectRoot = getStr(body, "projectRoot");
        if (projectRoot == null || projectRoot.isEmpty()) {
            return errorResult("缺少必要参数 projectRoot");
        }
        String remote = getStr(body, "remote");
        if (remote == null || remote.isBlank()) remote = "origin";
        boolean prune = getBool(body, "prune");

        List<String> args = new ArrayList<>();
        args.add("fetch");
        if (prune) {
            args.add("--prune");
        }
        args.add(remote);
        return execResult(gitExecutor.executeWithStoredAuth(projectRoot, args.toArray(new String[0])));
    }

    @Operation(summary = "推送到远程", description = "git push [-u] [--force] <remote> [branch]，自动注入已保存 Token")
    @PostMapping("/push")
    public Map<String, Object> push(@RequestBody Map<String, Object> body) {
        String projectRoot = getStr(body, "projectRoot");
        if (projectRoot == null || projectRoot.isEmpty()) {
            return errorResult("缺少必要参数 projectRoot");
        }
        String remote = getStr(body, "remote");
        if (remote == null || remote.isBlank()) remote = "origin";
        String branch = getStr(body, "branch");
        boolean setUpstream = getBool(body, "setUpstream");
        boolean force = getBool(body, "force");

        List<String> args = new ArrayList<>();
        args.add("push");
        if (setUpstream) {
            args.add("-u");
        }
        if (force) {
            args.add("--force");
        }
        args.add(remote);
        if (branch != null && !branch.isBlank()) {
            args.add(branch);
        }

        GitCommandExecutor.GitResult r = gitExecutor.executeWithStoredAuth(projectRoot, args.toArray(new String[0]));
        Map<String, Object> result = execResult(r);
        if (!r.success()) {
            String output = r.output();
            if (output.contains("rejected") || output.contains("non-fast-forward")) {
                result.put("error", "推送被拒绝：远程仓库有更新的提交，请先拉取（pull）再推送。\n" + output);
            }
        }
        return result;
    }

    // ==================== 本地撤销（unstage） ====================

    @Operation(summary = "取消暂存", description = "git reset HEAD -- <files>；all=true 取消全部暂存（保留工作区改动）")
    @PostMapping("/unstage")
    public Map<String, Object> unstage(@RequestBody Map<String, Object> body) {
        String projectRoot = getStr(body, "projectRoot");
        if (projectRoot == null || projectRoot.isEmpty()) {
            return errorResult("缺少必要参数 projectRoot");
        }

        @SuppressWarnings("unchecked")
        List<String> files = (List<String>) body.get("files");
        boolean all = getBool(body, "all");

        GitCommandExecutor.GitResult r;
        if (all) {
            r = gitExecutor.execute(projectRoot, "reset", "HEAD", "--", ".");
        } else if (files != null && !files.isEmpty()) {
            List<String> args = new ArrayList<>();
            args.add("reset");
            args.add("HEAD");
            args.add("--");
            args.addAll(files);
            r = gitExecutor.execute(projectRoot, args.toArray(new String[0]));
        } else {
            return errorResult("请指定 files 或设置 all=true");
        }
        return execResult(r);
    }

    // ==================== 储藏（stash） ====================

    @Operation(summary = "储藏当前改动", description = "git stash push [-m message]")
    @PostMapping("/stash")
    public Map<String, Object> stashSave(@RequestBody Map<String, Object> body) {
        String projectRoot = getStr(body, "projectRoot");
        if (projectRoot == null || projectRoot.isEmpty()) {
            return errorResult("缺少必要参数 projectRoot");
        }
        String message = getStr(body, "message");

        List<String> args = new ArrayList<>();
        args.add("stash");
        args.add("push");
        if (message != null && !message.isBlank()) {
            args.add("-m");
            args.add(message);
        }
        return execResult(gitExecutor.execute(projectRoot, args.toArray(new String[0])));
    }

    @Operation(summary = "列出储藏记录", description = "git stash list（返回原始列表文本）")
    @GetMapping("/stash")
    public Map<String, Object> stashList(@RequestParam String projectRoot) {
        return execResult(gitExecutor.execute(projectRoot, "stash", "list"));
    }

    @Operation(summary = "弹出储藏", description = "git stash pop [stash@{n}]")
    @PostMapping("/stash/pop")
    public Map<String, Object> stashPop(@RequestBody Map<String, Object> body) {
        String projectRoot = getStr(body, "projectRoot");
        if (projectRoot == null || projectRoot.isEmpty()) {
            return errorResult("缺少必要参数 projectRoot");
        }
        String stashRef = buildStashRef(body.get("index"));
        GitCommandExecutor.GitResult r = stashRef == null
                ? gitExecutor.execute(projectRoot, "stash", "pop")
                : gitExecutor.execute(projectRoot, "stash", "pop", stashRef);
        return execResult(r);
    }

    @Operation(summary = "删除储藏", description = "git stash drop [stash@{n}]")
    @PostMapping("/stash/drop")
    public Map<String, Object> stashDrop(@RequestBody Map<String, Object> body) {
        String projectRoot = getStr(body, "projectRoot");
        if (projectRoot == null || projectRoot.isEmpty()) {
            return errorResult("缺少必要参数 projectRoot");
        }
        String stashRef = buildStashRef(body.get("index"));
        GitCommandExecutor.GitResult r = stashRef == null
                ? gitExecutor.execute(projectRoot, "stash", "drop")
                : gitExecutor.execute(projectRoot, "stash", "drop", stashRef);
        return execResult(r);
    }

    // ==================== 分支（branches / switch / create / delete / merge） ====================

    @Operation(summary = "获取分支列表", description = "返回本地分支列表与当前分支名")
    @GetMapping("/branches")
    public Map<String, Object> getBranches(@RequestParam String projectRoot) {
        Map<String, Object> result = new LinkedHashMap<>();
        GitCommandExecutor.GitResult r = gitExecutor.execute(projectRoot, "branch");
        if (!r.success()) {
            result.put("success", false);
            result.put("error", r.output());
            return result;
        }
        List<String> branches = new ArrayList<>();
        for (String line : r.output().split("\n")) {
            String name = line.replace("*", "").trim();
            if (!name.isEmpty()) {
                branches.add(name);
            }
        }
        GitCommandExecutor.GitResult cur = gitExecutor.execute(projectRoot, "rev-parse", "--abbrev-ref", "HEAD");
        result.put("success", true);
        result.put("current", cur.success() ? cur.output().trim() : "");
        result.put("branches", branches);
        return result;
    }

    @Operation(summary = "切换分支", description = "git switch <name>")
    @PostMapping("/branch/switch")
    public Map<String, Object> branchSwitch(@RequestBody Map<String, Object> body) {
        String projectRoot = getStr(body, "projectRoot");
        String name = getStr(body, "name");
        if (projectRoot == null || projectRoot.isEmpty() || name == null || name.isEmpty()) {
            return errorResult("缺少必要参数 projectRoot 或 name");
        }
        return execResult(gitExecutor.execute(projectRoot, "switch", name));
    }

    @Operation(summary = "创建分支", description = "git switch -c <name>（switch=true）或 git branch <name>")
    @PostMapping("/branch/create")
    public Map<String, Object> branchCreate(@RequestBody Map<String, Object> body) {
        String projectRoot = getStr(body, "projectRoot");
        String name = getStr(body, "name");
        if (projectRoot == null || projectRoot.isEmpty() || name == null || name.isEmpty()) {
            return errorResult("缺少必要参数 projectRoot 或 name");
        }
        boolean switchTo = getBool(body, "switch");
        GitCommandExecutor.GitResult r = switchTo
                ? gitExecutor.execute(projectRoot, "switch", "-c", name)
                : gitExecutor.execute(projectRoot, "branch", name);
        return execResult(r);
    }

    @Operation(summary = "删除分支", description = "git branch -d/-D <name>")
    @PostMapping("/branch/delete")
    public Map<String, Object> branchDelete(@RequestBody Map<String, Object> body) {
        String projectRoot = getStr(body, "projectRoot");
        String name = getStr(body, "name");
        if (projectRoot == null || projectRoot.isEmpty() || name == null || name.isEmpty()) {
            return errorResult("缺少必要参数 projectRoot 或 name");
        }
        boolean force = getBool(body, "force");
        GitCommandExecutor.GitResult r = force
                ? gitExecutor.execute(projectRoot, "branch", "-D", name)
                : gitExecutor.execute(projectRoot, "branch", "-d", name);
        return execResult(r);
    }

    @Operation(summary = "合并分支", description = "git merge [--no-ff] [-m message] <branch>，合并到当前分支")
    @PostMapping("/merge")
    public Map<String, Object> merge(@RequestBody Map<String, Object> body) {
        String projectRoot = getStr(body, "projectRoot");
        String branch = getStr(body, "branch");
        if (projectRoot == null || projectRoot.isEmpty() || branch == null || branch.isEmpty()) {
            return errorResult("缺少必要参数 projectRoot 或 branch");
        }
        boolean noFf = getBool(body, "noFf");
        String message = getStr(body, "message");

        List<String> args = new ArrayList<>();
        args.add("merge");
        if (noFf) {
            args.add("--no-ff");
        }
        if (message != null && !message.isBlank()) {
            args.add("-m");
            args.add(message);
        }
        args.add(branch);

        GitCommandExecutor.GitResult r = gitExecutor.execute(projectRoot, args.toArray(new String[0]));
        Map<String, Object> result = execResult(r);
        if (!r.success()) {
            String output = r.output();
            if (output.contains("CONFLICT") || output.contains("冲突")) {
                result.put("error", "合并产生冲突，需手动解决冲突文件后暂存并提交。\n" + output);
            }
        }
        return result;
    }

    // ==================== 历史操作（reset / revert） ====================

    @Operation(summary = "回退提交", description = "git reset --soft/--mixed/--hard <target>；hard 必须 confirm=true")
    @PostMapping("/reset")
    public Map<String, Object> reset(@RequestBody Map<String, Object> body) {
        String projectRoot = getStr(body, "projectRoot");
        if (projectRoot == null || projectRoot.isEmpty()) {
            return errorResult("缺少必要参数 projectRoot");
        }
        String mode = getStr(body, "mode");
        if (mode == null || mode.isBlank()) mode = "mixed";
        String target = getStr(body, "target");
        boolean confirm = getBool(body, "confirm");

        if (target == null || target.isBlank()) {
            return errorResult("缺少必要参数 target（如 'HEAD~1'）");
        }
        if (!List.of("soft", "mixed", "hard").contains(mode)) {
            return errorResult("mode 仅支持 soft / mixed / hard，当前值：" + mode);
        }
        if ("hard".equals(mode) && !confirm) {
            return errorResult("reset --hard 会永久丢弃未提交改动，必须显式传 confirm=true 才执行");
        }

        GitCommandExecutor.GitResult r = gitExecutor.execute(projectRoot, "reset", "--" + mode, target);
        return execResult(r);
    }

    @Operation(summary = "撤销指定提交", description = "git revert --no-edit [--no-commit] <commit>")
    @PostMapping("/revert")
    public Map<String, Object> revert(@RequestBody Map<String, Object> body) {
        String projectRoot = getStr(body, "projectRoot");
        if (projectRoot == null || projectRoot.isEmpty()) {
            return errorResult("缺少必要参数 projectRoot");
        }
        String commit = getStr(body, "commit");
        if (commit == null || commit.isBlank()) commit = "HEAD";
        boolean noCommit = getBool(body, "noCommit");

        List<String> args = new ArrayList<>();
        args.add("revert");
        args.add("--no-edit");
        if (noCommit) {
            args.add("--no-commit");
        }
        args.add(commit);

        GitCommandExecutor.GitResult r = gitExecutor.execute(projectRoot, args.toArray(new String[0]));
        Map<String, Object> result = execResult(r);
        if (!r.success()) {
            String output = r.output();
            if (output.contains("CONFLICT") || output.contains("冲突")) {
                result.put("error", "撤销提交产生冲突，需手动解决冲突文件后暂存并提交。\n" + output);
            }
        }
        return result;
    }

    // ==================== 标签（tag） ====================

    @Operation(summary = "获取标签列表", description = "git tag -l，返回标签数组")
    @GetMapping("/tags")
    public Map<String, Object> getTags(@RequestParam String projectRoot) {
        Map<String, Object> result = new LinkedHashMap<>();
        GitCommandExecutor.GitResult r = gitExecutor.execute(projectRoot, "tag", "-l");
        if (!r.success()) {
            result.put("success", false);
            result.put("error", r.output());
            return result;
        }
        List<String> tags = new ArrayList<>();
        for (String line : r.output().split("\n")) {
            String name = line.trim();
            if (!name.isEmpty()) {
                tags.add(name);
            }
        }
        result.put("success", true);
        result.put("tags", tags);
        return result;
    }

    @Operation(summary = "创建标签", description = "git tag [-a -m message] <name>")
    @PostMapping("/tag")
    public Map<String, Object> tagCreate(@RequestBody Map<String, Object> body) {
        String projectRoot = getStr(body, "projectRoot");
        String name = getStr(body, "name");
        if (projectRoot == null || projectRoot.isEmpty() || name == null || name.isEmpty()) {
            return errorResult("缺少必要参数 projectRoot 或 name");
        }
        String message = getStr(body, "message");

        List<String> args = new ArrayList<>();
        args.add("tag");
        if (message != null && !message.isBlank()) {
            args.add("-a");
            args.add(name);
            args.add("-m");
            args.add(message);
        } else {
            args.add(name);
        }
        return execResult(gitExecutor.execute(projectRoot, args.toArray(new String[0])));
    }

    @Operation(summary = "删除本地标签", description = "git tag -d <name>")
    @DeleteMapping("/tag")
    public Map<String, Object> tagDelete(@RequestParam String projectRoot, @RequestParam String name) {
        return execResult(gitExecutor.execute(projectRoot, "tag", "-d", name));
    }

    @Operation(summary = "推送标签", description = "git push <remote> <name>；不传 name 则推送全部标签 --tags")
    @PostMapping("/tag/push")
    public Map<String, Object> tagPush(@RequestBody Map<String, Object> body) {
        String projectRoot = getStr(body, "projectRoot");
        if (projectRoot == null || projectRoot.isEmpty()) {
            return errorResult("缺少必要参数 projectRoot");
        }
        String name = getStr(body, "name");
        String remote = getStr(body, "remote");
        if (remote == null || remote.isBlank()) remote = "origin";

        List<String> args = new ArrayList<>();
        args.add("push");
        args.add(remote);
        if (name != null && !name.isBlank()) {
            args.add(name);
        } else {
            args.add("--tags");
        }
        return execResult(gitExecutor.executeWithStoredAuth(projectRoot, args.toArray(new String[0])));
    }

    // ==================== 远程仓库管理（remote） ====================

    @Operation(summary = "获取远程仓库列表", description = "git remote -v（返回原始文本）")
    @GetMapping("/remotes")
    public Map<String, Object> getRemotes(@RequestParam String projectRoot) {
        return execResult(gitExecutor.execute(projectRoot, "remote", "-v"));
    }

    @Operation(summary = "添加/修改远程仓库", description = "不存在则 git remote add，已存在则 git remote set-url")
    @PostMapping("/remote")
    public Map<String, Object> remoteSave(@RequestBody Map<String, Object> body) {
        String projectRoot = getStr(body, "projectRoot");
        String name = getStr(body, "name");
        String url = getStr(body, "url");
        if (projectRoot == null || projectRoot.isEmpty() || name == null || name.isEmpty() || url == null || url.isEmpty()) {
            return errorResult("缺少必要参数 projectRoot、name 或 url");
        }

        // 已存在则更新 URL，否则新增（与 /init 中的逻辑一致）
        GitCommandExecutor.GitResult check = gitExecutor.execute(projectRoot, "remote", "get-url", name);
        GitCommandExecutor.GitResult r = check.success()
                ? gitExecutor.execute(projectRoot, "remote", "set-url", name, url)
                : gitExecutor.execute(projectRoot, "remote", "add", name, url);
        return execResult(r);
    }

    @Operation(summary = "删除远程仓库", description = "git remote remove <name>（仅移除本地配置）")
    @DeleteMapping("/remote")
    public Map<String, Object> remoteRemove(@RequestParam String projectRoot, @RequestParam String name) {
        return execResult(gitExecutor.execute(projectRoot, "remote", "remove", name));
    }

    // ==================== 内部辅助 ====================

    /** 从请求体安全读取字符串参数 */
    private static String getStr(Map<String, Object> body, String key) {
        Object v = body.get(key);
        return v != null ? String.valueOf(v) : null;
    }

    /** 从请求体安全读取布尔参数（兼容 Boolean 与 "true" 字符串） */
    private static boolean getBool(Map<String, Object> body, String key) {
        Object v = body.get(key);
        return Boolean.TRUE.equals(v) || "true".equalsIgnoreCase(String.valueOf(v));
    }

    /** 将 index 参数转换为 stash@{n}；未提供/非法时返回 null（使用默认最新） */
    private static String buildStashRef(Object indexObj) {
        if (indexObj == null) return null;
        try {
            int idx = Integer.parseInt(String.valueOf(indexObj).trim());
            return "stash@{" + Math.max(idx, 0) + "}";
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 统一封装 git 执行结果为接口返回 */
    private Map<String, Object> execResult(GitCommandExecutor.GitResult r) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", r.success());
        result.put("output", r.output());
        if (!r.success()) {
            result.put("error", r.output());
        }
        return result;
    }

    private Map<String, Object> errorResult(String message) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", false);
        result.put("error", message);
        return result;
    }

    /** 使用 git diff --name-only HEAD 获取已跟踪文件的真正内容差异 */
    private void appendTrackedChanges(List<Map<String, String>> changes,
                                       java.util.Set<String> seen,
                                       String projectRoot) {
        GitCommandExecutor.GitResult r = gitExecutor.execute(projectRoot,
                "diff", "--name-only", "HEAD");

        if (!r.success() || r.output().isBlank()) return;

        for (String file : r.output().split("\n")) {
            file = file.trim();
            if (file.isBlank()) continue;
            if (isExcludedPath(file)) continue;  // 过滤构建产物
            if (!seen.add(file)) continue;
            Map<String, String> c = new LinkedHashMap<>();
            c.put("file", file);
            c.put("status", "modified");
            c.put("label", "已修改");
            changes.add(c);
        }
    }

    /** 从 git status --porcelain 中仅提取 ?? 行（未跟踪文件） */
    private void appendUntrackedFiles(List<Map<String, String>> changes,
                                       java.util.Set<String> seen,
                                       String projectRoot) {
        GitCommandExecutor.GitResult r = gitExecutor.execute(projectRoot,
                "ls-files", "--others", "--exclude-standard");

        if (!r.success() || r.output().isBlank()) return;

        // ls-files --others --exclude-standard 每行直接是文件名，无需解析 ?? 前缀
        for (String file : r.output().split("\n")) {
            file = file.trim();
            if (file.isBlank()) continue;
            if (isExcludedPath(file)) continue;  // 过滤构建产物/快照等
            if (!seen.add(file)) continue;
            Map<String, String> c = new LinkedHashMap<>();
            c.put("file", file);
            c.put("status", "untracked");
            c.put("label", "未跟踪");
            changes.add(c);
        }
    }

    /** 硬编码排除常见构建产物 / 自动生成目录 / 临时文件 / 纯目录 */
    private static boolean isExcludedPath(String path) {
        // 纯目录（以 / 或 \ 结尾）— 不显示
        if (path.endsWith("/") || path.endsWith("\\")) return true;
        // 目录前缀匹配
        String[] excludedDirs = {
            "target/", "node_modules/", "dist/", "build/",
            "snapshots/", "data/", "logs/", ".git/",
            "BOOT-INF/", "__pycache__/", ".idea/", ".vscode/",
            "electron/release/", "electron/node_modules/", "electron/jre/",
            "frontend/dist/", "frontend/node_modules/", "frontend/node/",
            "src/main/resources/static/",
            "ylcode/", "snapshots/", "script/",
            "temp_diff", "temp_untracked"
        };
        for (String dir : excludedDirs) {
            if (path.startsWith(dir)) return true;
        }
        // 文件/扩展名匹配
        return path.endsWith(".class") || path.endsWith(".jar")
            || path.endsWith(".pyc") || path.startsWith("~");
    }
}
