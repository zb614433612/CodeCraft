package com.example.agentdeepseek.service;

import com.example.agentdeepseek.scheduler.RunningProcessManager;
import com.example.agentdeepseek.util.CommandUtils;
import com.example.agentdeepseek.util.ProjectRootContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 项目编译/运行服务
 * 支持 Maven、npm、Gradle 项目的编译和运行
 */
@Slf4j
@Service
public class ProjectBuildService {

    private final RunningProcessManager processManager;

    public ProjectBuildService(RunningProcessManager processManager) {
        this.processManager = processManager;
    }

    /**
     * 编译项目
     *
     * @param projectRoot 项目根目录
     * @return 编译结果
     */
    public BuildResult build(String projectRoot) {
        String projectDir = projectRoot != null && !projectRoot.isEmpty() ? projectRoot : ProjectRootContext.get();

        String buildCommand = detectBuildCommand(projectDir);
        if (buildCommand == null) {
            return new BuildResult(false, "无法识别的项目类型，未找到 pom.xml / package.json / build.gradle", -1, 0);
        }

        log.info("开始编译项目: dir={}, command={}", projectDir, buildCommand);

        List<String> outputLines = new ArrayList<>();
        long startTime = System.currentTimeMillis();
        try {
            // Windows 上用 cmd /c 包装以继承 PATH
            List<String> cmdList;
            String fullCommand = buildCommand;
            if (CommandUtils.isWindows()) {
                cmdList = Arrays.asList("cmd", "/c", fullCommand);
            } else {
                cmdList = Arrays.asList("sh", "-c", fullCommand);
            }
            ProcessBuilder pb = new ProcessBuilder(cmdList);
            pb.directory(new java.io.File(projectDir));
            Process process = pb.start();

            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    outputLines.add(line);
                }
            }

            int exitCode = process.waitFor();
            long duration = System.currentTimeMillis() - startTime;
            String output = String.join("\n", outputLines);

            log.info("编译完成: exitCode={}, duration={}ms, lines={}", exitCode, duration, outputLines.size());

            return new BuildResult(exitCode == 0, output, exitCode, duration);

        } catch (Exception e) {
            long duration = System.currentTimeMillis() - startTime;
            log.error("编译失败: {}", e.getMessage(), e);
            return new BuildResult(false, "编译异常: " + e.getMessage(), -1, duration);
        }
    }

    /**
     * 运行项目（后台进程）
     *
     * @param projectRoot 项目根目录
     * @return 运行结果
     */
    public RunResult run(String projectRoot) {
        String projectDir = projectRoot != null && !projectRoot.isEmpty() ? projectRoot : ProjectRootContext.get();

        if (processManager.isRunning()) {
            return new RunResult(false, "已有服务在运行中 (PID: " + processManager.getPid() + ")", null);
        }

        String runCommand = detectRunCommand(projectDir);
        if (runCommand == null) {
            return new RunResult(false, "无法识别的项目类型，未找到 pom.xml / package.json / build.gradle", null);
        }

        log.info("启动项目: dir={}, command={}", projectDir, runCommand);

        processManager.clearOutput();
        processManager.start(runCommand, projectDir);

        if (processManager.isRunning()) {
            return new RunResult(true, "服务已启动 (PID: " + processManager.getPid() + ")", processManager.getPid());
        } else {
            List<String> output = processManager.getOutput(10);
            String lastLines = String.join("\n", output);
            return new RunResult(false, "服务启动失败:\n" + lastLines, null);
        }
    }

    /**
     * 停止运行中的项目
     */
    public StopResult stop() {
        if (!processManager.isRunning()) {
            return new StopResult(false, "当前没有运行中的服务");
        }
        long pid = processManager.getPid();
        processManager.stop();
        return new StopResult(true, "服务已停止 (PID: " + pid + ")");
    }

    /**
     * 获取运行状态
     */
    public StatusResult getStatus() {
        boolean running = processManager.isRunning();
        return new StatusResult(
                running,
                running ? processManager.getPid() : null,
                running ? processManager.getElapsed() : 0
        );
    }

    /**
     * 获取控制台输出
     *
     * @param tail 获取最近的行数，null 则返回全部
     */
    public OutputResult getOutput(Integer tail) {
        List<String> lines = processManager.getOutput(tail != null ? tail : 0);
        return new OutputResult(true, lines, processManager.isRunning());
    }

    /**
     * 检测项目类型并返回编译命令
     */
    private String detectBuildCommand(String projectDir) {
        java.io.File dir = new java.io.File(projectDir);
        if (new java.io.File(dir, "pom.xml").exists()) {
            return "mvn compile -DskipTests -Dmaven.test.skip=true";
        }
        if (new java.io.File(dir, "build.gradle").exists()) {
            return "gradle build -x test";
        }
        if (new java.io.File(dir, "package.json").exists()) {
            return "npm run build";
        }
        return null;
    }

    /**
     * 检测项目类型并返回运行命令
     */
    private String detectRunCommand(String projectDir) {
        java.io.File dir = new java.io.File(projectDir);
        if (new java.io.File(dir, "pom.xml").exists()) {
            return "mvn spring-boot:run";
        }
        if (new java.io.File(dir, "build.gradle").exists()) {
            return "gradle bootRun";
        }
        if (new java.io.File(dir, "package.json").exists()) {
            return "npm run dev";
        }
        return null;
    }

    // ===== 结果封装类 =====

    public record BuildResult(boolean success, String output, int exitCode, long duration) {}

    public record RunResult(boolean success, String message, Long pid) {}

    public record StopResult(boolean success, String message) {}

    public record StatusResult(boolean running, Long pid, long elapsed) {}

    public record OutputResult(boolean success, List<String> lines, boolean running) {}
}
