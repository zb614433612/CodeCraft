package com.example.agentdeepseek.tool.impl;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.*;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.TimeUnit;

/**
 * Git 命令执行器
 * 统一执行 git 命令，支持 Token 认证注入
 */
@Slf4j
@Component
public class GitCommandExecutor {

    private static final int TIMEOUT_SECONDS = 60;

    private final GitAuthStore gitAuthStore;

    public GitCommandExecutor(GitAuthStore gitAuthStore) {
        this.gitAuthStore = gitAuthStore;
    }

    /**
     * 执行 git 命令（无认证）
     */
    public GitResult execute(String projectRoot, String... args) {
        return executeInternal(projectRoot, null, args);
    }

    /**
     * 执行 git 命令（带 Token 认证）
     */
    public GitResult executeWithAuth(String projectRoot, String token, String... args) {
        return executeInternal(projectRoot, token, args);
    }

    /**
     * 执行 git 命令，自动从存储读取 Token
     */
    public GitResult executeWithStoredAuth(String projectRoot, String... args) {
        String token = gitAuthStore.getToken(projectRoot);
        return executeInternal(projectRoot, token, args);
    }

    private GitResult executeInternal(String projectRoot, String token, String... args) {
        Path workDir = Paths.get(projectRoot).normalize();
        if (!workDir.toFile().isDirectory()) {
            return new GitResult(false, "项目目录不存在: " + workDir);
        }

        // 检查是否有 .git 目录（git init 命令不需要此检查）
        boolean isInit = args.length > 0 && "init".equals(args[0]);
        if (!isInit) {
            Path gitDir = workDir.resolve(".git");
            if (!gitDir.toFile().isDirectory()) {
                return new GitResult(false, "不是 git 仓库，请先执行 git init");
            }
        }

        String[] cmdArray = new String[args.length + 1];
        cmdArray[0] = "git";
        System.arraycopy(args, 0, cmdArray, 1, args.length);

        ProcessBuilder pb = new ProcessBuilder(cmdArray);
        pb.directory(workDir.toFile());
        pb.redirectErrorStream(true);
        // 禁止 git 分页器，防止进程挂起
        pb.environment().put("GIT_PAGER", "cat");
        pb.environment().put("PAGER", "cat");

        // 如果有 Token，通过 GIT_ASKPASS 注入
        if (token != null && !token.isEmpty()) {
            pb.environment().put("GIT_ASKPASS", createAskPassScript(token));
            pb.environment().put("GIT_TOKEN", token);
        }

        try {
            Process process = pb.start();
            boolean finished = process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS);

            if (!finished) {
                process.destroyForcibly();
                return new GitResult(false, "git 命令超时（" + TIMEOUT_SECONDS + " 秒）: git " + String.join(" ", args));
            }

            String output = readOutput(process.getInputStream());
            int exitCode = process.exitValue();

            if (exitCode != 0) {
                return new GitResult(false, output.isBlank() ? "命令执行失败（exit=" + exitCode + "）" : output);
            }

            return new GitResult(true, output);
        } catch (IOException e) {
            log.error("git 命令执行失败", e);
            return new GitResult(false, "git 命令执行失败: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new GitResult(false, "git 命令被中断");
        }
    }

    /**
     * 创建 GIT_ASKPASS 脚本用于 Token 认证
     */
    private String createAskPassScript(String token) {
        try {
            File tempScript = File.createTempFile("git-askpass-", ".sh");
            tempScript.deleteOnExit();

            try (Writer w = new OutputStreamWriter(new FileOutputStream(tempScript), "UTF-8")) {
                w.write("#!/bin/sh\n");
                w.write("echo \"$GIT_TOKEN\"\n");
            }

            if (!tempScript.setExecutable(true)) {
                log.warn("无法设置 askpass 脚本可执行权限");
            }

            return tempScript.getAbsolutePath();
        } catch (IOException e) {
            log.error("创建 GIT_ASKPASS 脚本失败", e);
            return null;
        }
    }

    private String readOutput(InputStream inputStream) throws IOException {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader br = new BufferedReader(new InputStreamReader(inputStream))) {
            String line;
            while ((line = br.readLine()) != null) {
                if (sb.length() > 0) sb.append("\n");
                sb.append(line);
            }
        }
        return sb.toString();
    }

    /**
     * Git 命令执行结果
     */
    public record GitResult(boolean success, String output) {}
}
