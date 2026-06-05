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

    @Operation(summary = "还原/撤销文件改动", description = "还原指定文件的未暂存修改 (git checkout -- file)")
    @PostMapping("/restore")
    public Map<String, Object> restoreFile(@RequestBody Map<String, String> body) {
        Map<String, Object> result = new LinkedHashMap<>();
        String projectRoot = body.get("projectRoot");
        String file = body.get("file");

        if (projectRoot == null || file == null || file.isEmpty()) {
            result.put("success", false);
            result.put("error", "缺少必要参数 projectRoot 或 file");
            return result;
        }

        GitCommandExecutor.GitResult restoreResult = gitExecutor.execute(projectRoot,
                "checkout", "--", file);

        result.put("success", restoreResult.success());
        if (!restoreResult.success()) {
            result.put("error", restoreResult.output());
        } else {
            result.put("output", "已还原: " + file);
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
