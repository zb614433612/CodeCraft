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

    /**
     * 将命令字符串解析为可执行文件 + 参数列表
     * 支持单引号、双引号包裹的参数，以及反斜杠转义
     *
     * @param command 原始命令字符串
     * @return 解析后的 token 列表
     */
    public static List<String> tokenize(String command) {
        List<String> tokens = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inSingleQuote = false;
        boolean inDoubleQuote = false;

        for (int i = 0; i < command.length(); i++) {
            char c = command.charAt(i);

            // 处理转义字符（不在单引号内时有效）
            if (c == '\\' && !inSingleQuote) {
                // 转义模式：下一个字符作为字面量加入
                if (i + 1 < command.length()) {
                    i++;
                    current.append(command.charAt(i));
                } else {
                    // 末尾反斜杠，按字面保留
                    current.append('\\');
                }
                continue;
            }

            if (c == '\'' && !inDoubleQuote) {
                inSingleQuote = !inSingleQuote;
            } else if (c == '"' && !inSingleQuote) {
                inDoubleQuote = !inDoubleQuote;
            } else if (Character.isWhitespace(c) && !inSingleQuote && !inDoubleQuote) {
                if (current.length() > 0) {
                    tokens.add(current.toString());
                    current.setLength(0);
                }
            } else {
                current.append(c);
            }
        }
        if (current.length() > 0) {
            tokens.add(current.toString());
        }
        return tokens;
    }
}
