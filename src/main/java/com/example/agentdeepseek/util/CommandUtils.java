package com.example.agentdeepseek.util;

import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 命令行工具公用方法
 * 主要解决 Windows 上 ProcessBuilder 不识别 .cmd/.bat 后缀的问题
 */
@Slf4j
public class CommandUtils {

    /**
     * Windows 兼容：解析可执行文件的完整路径
     * ProcessBuilder 在 Windows 上不会自动查找 PATHEXT（.cmd、.bat 等），
     * 导致直接使用 "mvn"、"npm"、"node" 等命令时找不到文件。
     * 此方法在 PATH 中搜索带扩展名的可执行文件并返回完整路径。
     *
     * @param cmdAndArgs 命令及参数列表，第一个元素为可执行文件名
     * @return 解析后的命令列表（第一个元素替换为完整路径），非 Windows 或无变化时返回原列表
     */
    public static List<String> resolveWindowsExecutable(List<String> cmdAndArgs) {
        if (cmdAndArgs == null || cmdAndArgs.isEmpty()) return cmdAndArgs;
        if (!isWindows()) return cmdAndArgs;

        String exec = cmdAndArgs.get(0);
        // 如果已经有扩展名或是绝对路径/相对路径，不处理
        if (exec.contains(".") || exec.contains(File.separator)) return cmdAndArgs;

        // 读取 PATHEXT 环境变量（.COM;.EXE;.BAT;.CMD;...）
        String pathext = System.getenv("PATHEXT");
        if (pathext == null || pathext.isEmpty()) return cmdAndArgs;

        // 拆分 PATH 路径
        String pathEnv = System.getenv("PATH");
        if (pathEnv == null || pathEnv.isEmpty()) return cmdAndArgs;

        List<String> extensions = Arrays.stream(pathext.split(";"))
                .map(String::toLowerCase)
                .collect(Collectors.toList());

        String[] pathDirs = pathEnv.split(";");

        // 在 PATH 中搜索可执行文件
        for (String dir : pathDirs) {
            if (dir.isEmpty()) continue;
            Path dirPath = Paths.get(dir);
            if (!Files.isDirectory(dirPath)) continue;
            for (String ext : extensions) {
                Path fullPath = dirPath.resolve(exec + ext);
                if (Files.exists(fullPath)) {
                    List<String> result = new ArrayList<>(cmdAndArgs);
                    result.set(0, fullPath.toString());
                    log.debug("Windows 可执行文件解析: {} -> {}", exec, fullPath);
                    return result;
                }
            }
        }

        // 没找到，返回原列表，让 ProcessBuilder 报错（会给出清晰的错误信息）
        log.warn("Windows 可执行文件未找到: {} (已搜索 PATH)", exec);
        return cmdAndArgs;
    }

    /**
     * 判断当前是否为 Windows 系统
     */
    public static boolean isWindows() {
        return System.getProperty("os.name").toLowerCase().contains("win");
    }
}
