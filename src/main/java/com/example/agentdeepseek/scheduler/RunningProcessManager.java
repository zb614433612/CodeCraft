package com.example.agentdeepseek.scheduler;

import com.example.agentdeepseek.util.CommandUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
@Slf4j
@Component
public class RunningProcessManager {

    /** 最大缓存输出行数 */
    private static final int MAX_OUTPUT_LINES = 5000;

    private Process runningProcess;
    private long pid;
    private long startTime;
    private final List<String> outputBuffer = new ArrayList<>();
    private String projectRoot;
    private volatile boolean running;

    /**
     * 启动一个后台进程
     *
     * @param command    要执行的命令
     * @param workingDir 工作目录
     */
    public synchronized void start(String command, String workingDir) {
        stop();

        try {
            // Windows 上用 cmd /c 包装以继承 PATH 环境变量
            // Linux/Mac 上用 sh -c 包装
            List<String> cmdList;
            if (CommandUtils.isWindows()) {
                cmdList = Arrays.asList("cmd", "/c", command);
            } else {
                cmdList = Arrays.asList("sh", "-c", command);
            }
            ProcessBuilder pb = new ProcessBuilder(cmdList);
            pb.redirectErrorStream(true);
            // 设置工作目录为用户选择的工作目录，否则进程默认在 JVM 启动目录下运行
            pb.directory(new java.io.File(workingDir));

            runningProcess = pb.start();
            pid = runningProcess.pid();
            startTime = System.currentTimeMillis();
            this.projectRoot = workingDir;
            running = true;

            log.info("进程已启动: command={}, pid={}, dir={}", command, pid, workingDir);

            // 启动读取线程，持续读取输出
            Thread readerThread = new Thread(() -> {
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(runningProcess.getInputStream(), CommandUtils.getProcessOutputCharset()))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        synchronized (outputBuffer) {
                            outputBuffer.add(line);
                            if (outputBuffer.size() > MAX_OUTPUT_LINES) {
                                outputBuffer.remove(0);
                            }
                        }
                    }
                } catch (Exception e) {
                    log.warn("读取进程输出异常: {}", e.getMessage());
                } finally {
                    running = false;
                    log.info("进程已退出: pid={}", pid);
                }
            }, "process-reader-" + pid);
            readerThread.setDaemon(true);
            readerThread.start();

        } catch (Exception e) {
            log.error("启动进程失败: command={}", command, e);
            running = false;
            synchronized (outputBuffer) {
                outputBuffer.add("[ERROR] 启动失败: " + e.getMessage());
            }
        }
    }

    /**
     * 停止运行中的进程（含整棵进程树，确保子进程也被清理）
     * <p>
     * 注意顺序：必须先 killProcessTree（taskkill /T）再 destroyForcibly。
     * 如果先 destroyForcibly 杀掉 cmd.exe 外壳，mvn/java 子进程变成孤儿进程，
     * 此时 taskkill /T 找不到进程树，端口依然被占用。
     */
    public synchronized void stop() {
        if (runningProcess != null && runningProcess.isAlive()) {
            long oldPid = pid;
            // 第一步：先用系统工具杀死整棵进程树（cmd.exe + mvn + java）
            killProcessTree(oldPid);
            // 第二步：再强制终止 Java Process 对象（兜底）
            runningProcess.destroyForcibly();
            try {
                runningProcess.waitFor(3, java.util.concurrent.TimeUnit.SECONDS);
            } catch (InterruptedException ignored) {
            }
            log.info("进程已强制终止: pid={}", oldPid);
        }
        runningProcess = null;
        running = false;
    }

    /**
     * 杀死指定 PID 的整棵进程树
     * <p>
     * 为什么需要：Java 的 Process.destroyForcibly() 只杀死直接子进程（cmd.exe/shell 外壳），
     * 不会递归杀死其子进程。例如 cmd /c mvn spring-boot:run 启动后：
     *   cmd.exe (PID X) ← destroyForcibly 只杀了这个
     *     └── mvn.cmd → java.exe (PID Y)
     *         └── java.exe (PID Z) ← 实际 Spring Boot 应用，持有端口
     * 导致端口仍被占用。本方法通过系统工具递归清理整棵进程树。
     */
    private void killProcessTree(long pid) {
        if (pid <= 0) return;
        try {
            if (isWindows()) {
                // Windows: taskkill /F /T 递归杀死整个进程树
                Process killProc = Runtime.getRuntime().exec(
                        new String[]{"cmd.exe", "/c", "taskkill /F /T /PID " + pid});
                killProc.waitFor(5, java.util.concurrent.TimeUnit.SECONDS);
                log.info("进程树清理完成: taskkill /F /T /PID {}", pid);
            } else {
                // Linux/Mac: 先尝试 kill -9 -PGID（负 PID = 进程组），再逐个杀子进程
                // 方法1: 用 kill 命令杀进程组（如果进程有独立的 PGID）
                Process killProc = Runtime.getRuntime().exec(
                        new String[]{"/bin/sh", "-c", "kill -9 -" + pid + " 2>/dev/null"});
                killProc.waitFor(2, java.util.concurrent.TimeUnit.SECONDS);
                // 方法2: 遍历 /proc 找所有父 PID 为 target 的子进程逐一杀死（兜底）
                Process findChildren = Runtime.getRuntime().exec(
                        new String[]{"/bin/sh", "-c",
                                "ps -eo pid,ppid | awk '$2==" + pid + " {print $1}' | xargs -r kill -9 2>/dev/null"});
                findChildren.waitFor(2, java.util.concurrent.TimeUnit.SECONDS);
                // 最后确保目标进程本身也被杀死
                Runtime.getRuntime().exec(new String[]{"kill", "-9", String.valueOf(pid)});
                log.info("进程树清理完成: pid={}", pid);
            }
        } catch (Exception e) {
            log.warn("进程树清理异常（不影响主进程已停止）: {}", e.getMessage());
        }
    }

    private boolean isWindows() {
        return System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win");
    }

    /**
     * 进程是否正在运行
     */
    public boolean isRunning() {
        if (runningProcess != null) {
            return runningProcess.isAlive();
        }
        return false;
    }

    /**
     * 获取进程 PID
     */
    public long getPid() {
        return pid;
    }

    /**
     * 获取已运行时长（毫秒）
     */
    public long getElapsed() {
        if (isRunning()) {
            return System.currentTimeMillis() - startTime;
        }
        return 0;
    }

    /**
     * 获取最近 N 行输出
     *
     * @param tail 行数，<=0 返回全部
     */
    public List<String> getOutput(int tail) {
        synchronized (outputBuffer) {
            if (tail <= 0 || tail >= outputBuffer.size()) {
                return new ArrayList<>(outputBuffer);
            }
            return new ArrayList<>(outputBuffer.subList(outputBuffer.size() - tail, outputBuffer.size()));
        }
    }

    /**
     * 获取当前项目根目录
     */
    public String getProjectRoot() {
        return projectRoot;
    }

    /**
     * 清空输出缓存
     */
    public synchronized void clearOutput() {
        synchronized (outputBuffer) {
            outputBuffer.clear();
        }
    }
}
