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

        // 变更文件列表
        GitCommandExecutor.GitResult statusResult = gitExecutor.execute(projectRoot,
                "status", "--porcelain");
        List<Map<String, String>> changes = new ArrayList<>();
        if (statusResult.success() && !statusResult.output().isBlank()) {
            for (String line : statusResult.output().split("\n")) {
                if (line.length() < 3) continue;
                Map<String, String> change = new LinkedHashMap<>();
                String statusStr = line.substring(0, 2).trim();
                String file = line.substring(3);
                change.put("file", file);
                if (line.startsWith("??")) {
                    change.put("status", "untracked");
                    change.put("label", "未跟踪");
                } else if (!statusStr.isEmpty()) {
                    change.put("status", "staged");
                    change.put("label", "已暂存");
                    change.put("stagedStatus", line.substring(0, 1).trim());
                    change.put("workingStatus", line.substring(1, 2).trim());
                } else {
                    change.put("status", "modified");
                    change.put("label", "已修改");
                    change.put("workingStatus", line.substring(1, 2).trim());
                }
                changes.add(change);
            }
        }
        result.put("changes", changes);

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
        if (!file.isEmpty()) {
            args.add("--");
            args.add(file);
        }

        GitCommandExecutor.GitResult diffResult = gitExecutor.execute(
                projectRoot, args.toArray(new String[0]));

        result.put("success", diffResult.success());
        result.put("diff", diffResult.success() ? diffResult.output() : diffResult.output());
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
}
