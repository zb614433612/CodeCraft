package com.example.agentdeepseek.scheduler;

import com.example.agentdeepseek.util.CommandUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
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

            runningProcess = pb.start();
            pid = runningProcess.pid();
            startTime = System.currentTimeMillis();
            this.projectRoot = workingDir;
            running = true;

            log.info("进程已启动: command={}, pid={}, dir={}", command, pid, workingDir);

            // 启动读取线程，持续读取输出
            Thread readerThread = new Thread(() -> {
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(runningProcess.getInputStream(), StandardCharsets.UTF_8))) {
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
     * 停止运行中的进程
     */
    public synchronized void stop() {
        if (runningProcess != null && runningProcess.isAlive()) {
            long oldPid = pid;
            runningProcess.destroyForcibly();
            try {
                runningProcess.waitFor(5, java.util.concurrent.TimeUnit.SECONDS);
            } catch (InterruptedException ignored) {
            }
            log.info("进程已强制终止: pid={}", oldPid);
        }
        runningProcess = null;
        running = false;
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
