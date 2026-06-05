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
        // ★ 不要 redirectErrorStream — stderr（autocrlf warning 等）必须单独丢弃
        pb.redirectErrorStream(false);
        // 禁止 git 分页器，防止进程挂起
        pb.environment().put("GIT_PAGER", "cat");
        pb.environment().put("PAGER", "cat");

        // ★ 注入 HOME 环境变量，让 git 子进程能找到 ~/.gitconfig 全局配置
        //   ProcessBuilder 默认不继承父进程的环境变量，导致 core.autocrlf=true
        //   配置缺失。git diff 会因 CRLF/LF 差异把所有文件都标记为"已修改"(786个)
        String userHome = System.getProperty("user.home");
        if (userHome != null) {
            pb.environment().put("HOME", userHome);
            // Windows 上 git 也用 USERPROFILE 定位 .gitconfig
            pb.environment().put("USERPROFILE", userHome);
        }

        // 如果有 Token，通过 GIT_ASKPASS 注入（跨平台脚本）
        if (token != null && !token.isEmpty()) {
            pb.environment().put("GIT_ASKPASS", createAskPassScript(token));
            pb.environment().put("GIT_TOKEN", token);
        }

        try {
            Process process = pb.start();

            // ★ 异步读取 stdout（正常输出）和 stderr（warning/error，丢弃）
            StringBuilder outputBuilder = new StringBuilder();
            Thread stdoutReader = new Thread(() -> {
                try {
                    String line;
                    try (BufferedReader br = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                        while ((line = br.readLine()) != null) {
                            if (outputBuilder.length() > 0) outputBuilder.append("\n");
                            outputBuilder.append(line);
                        }
                    }
                } catch (IOException e) {
                    log.warn("读取 git stdout 时异常", e);
                }
            });
            stdoutReader.setDaemon(true);
            stdoutReader.start();

            // 单独消费 stderr，防止管道堵塞
            StringBuilder stderrBuilder = new StringBuilder();
            Thread stderrReader = new Thread(() -> {
                try {
                    String line;
                    try (BufferedReader br = new BufferedReader(new InputStreamReader(process.getErrorStream()))) {
                        while ((line = br.readLine()) != null) {
                            if (stderrBuilder.length() > 0) stderrBuilder.append("\n");
                            stderrBuilder.append(line);
                        }
                    }
                } catch (IOException e) {
                    // ignore
                }
            });
            stderrReader.setDaemon(true);
            stderrReader.start();

            boolean finished = process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS);

            // 等待读取线程消费完剩余数据
            stdoutReader.join(5000);
            stderrReader.join(3000);

            if (!finished) {
                process.destroyForcibly();
                return new GitResult(false, "git 命令超时（" + TIMEOUT_SECONDS + " 秒）: git " + String.join(" ", args));
            }

            String output = outputBuilder.toString();
            String stderr = stderrBuilder.toString();
            int exitCode = process.exitValue();

            if (exitCode != 0) {
                String errMsg = output.isBlank() ? stderr : (output + (stderr.isBlank() ? "" : "\n" + stderr));
                return new GitResult(false, errMsg.isBlank() ? "命令执行失败（exit=" + exitCode + "）" : errMsg);
            }

            // 即使 exitCode=0，stderr 有内容也不混入 output
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
     * 自动检测操作系统：Windows 生成 .bat，Unix 生成 .sh
     */
    private String createAskPassScript(String token) {
        try {
            boolean isWindows = System.getProperty("os.name").toLowerCase().contains("win");
            String suffix = isWindows ? ".bat" : ".sh";
            File tempScript = File.createTempFile("git-askpass-", suffix);
            tempScript.deleteOnExit();

            try (Writer w = new OutputStreamWriter(new FileOutputStream(tempScript), "UTF-8")) {
                if (isWindows) {
                    w.write("@echo off\r\n");
                    w.write("echo %GIT_TOKEN%\r\n");
                } else {
                    w.write("#!/bin/sh\n");
                    w.write("echo \"$GIT_TOKEN\"\n");
                }
            }

            if (!isWindows && !tempScript.setExecutable(true)) {
                log.warn("无法设置 askpass 脚本可执行权限");
            }

            return tempScript.getAbsolutePath();
        } catch (IOException e) {
            log.error("创建 GIT_ASKPASS 脚本失败", e);
            return null;
        }
    }

    /**
     * Git 命令执行结果
     */
    public record GitResult(boolean success, String output) {}

    /**
     * 执行 git 命令并将字符串通过 stdin 传入（如 git apply --reverse）
     */
    public GitResult executeWithStdin(String projectRoot, String stdinInput, String... args) {
        Path workDir = Paths.get(projectRoot).normalize();
        if (!workDir.toFile().isDirectory()) {
            return new GitResult(false, "项目目录不存在: " + workDir);
        }

        String[] cmdArray = new String[args.length + 1];
        cmdArray[0] = "git";
        System.arraycopy(args, 0, cmdArray, 1, args.length);

        ProcessBuilder pb = new ProcessBuilder(cmdArray);
        pb.directory(workDir.toFile());
        pb.redirectErrorStream(false);
        pb.environment().put("GIT_PAGER", "cat");
        pb.environment().put("PAGER", "cat");

        // ★ 注入 HOME/USERPROFILE，让 git 子进程能找到全局 .gitconfig
        String userHome = System.getProperty("user.home");
        if (userHome != null) {
            pb.environment().put("HOME", userHome);
            pb.environment().put("USERPROFILE", userHome);
        }

        try {
            Process process = pb.start();

            // 写入 stdin
            try (OutputStream os = process.getOutputStream();
                 Writer writer = new OutputStreamWriter(os, "UTF-8")) {
                writer.write(stdinInput);
                writer.flush();
            }

            // 读取 stdout（正常输出）
            StringBuilder outputBuilder = new StringBuilder();
            Thread stdoutReader = new Thread(() -> {
                try {
                    String line;
                    try (BufferedReader br = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                        while ((line = br.readLine()) != null) {
                            if (outputBuilder.length() > 0) outputBuilder.append("\n");
                            outputBuilder.append(line);
                        }
                    }
                } catch (IOException e) {
                    log.warn("读取 git stdout 时异常", e);
                }
            });
            stdoutReader.setDaemon(true);
            stdoutReader.start();

            // 单独消费 stderr，防止管道堵塞
            StringBuilder stderrBuilder = new StringBuilder();
            Thread stderrReader = new Thread(() -> {
                try {
                    String line;
                    try (BufferedReader br = new BufferedReader(new InputStreamReader(process.getErrorStream()))) {
                        while ((line = br.readLine()) != null) {
                            if (stderrBuilder.length() > 0) stderrBuilder.append("\n");
                            stderrBuilder.append(line);
                        }
                    }
                } catch (IOException e) {
                    // ignore
                }
            });
            stderrReader.setDaemon(true);
            stderrReader.start();

            boolean finished = process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            stdoutReader.join(5000);
            stderrReader.join(3000);

            if (!finished) {
                process.destroyForcibly();
                return new GitResult(false, "git 命令超时（" + TIMEOUT_SECONDS + " 秒）");
            }

            String output = outputBuilder.toString();
            String stderr = stderrBuilder.toString();
            int exitCode = process.exitValue();

            if (exitCode != 0) {
                String errMsg = output.isBlank() ? stderr : (output + (stderr.isBlank() ? "" : "\n" + stderr));
                return new GitResult(false, errMsg.isBlank() ? "命令执行失败（exit=" + exitCode + "）" : errMsg);
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
}
