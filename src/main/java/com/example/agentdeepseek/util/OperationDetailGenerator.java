package com.example.agentdeepseek.util;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * 操作详情生成器
 * 从受限工具的参数中生成格式化 markdown 文本，注入到 thinking 流中展示给用户
 */
@Slf4j
public class OperationDetailGenerator {

    /** 受限工具名称列表 */
    public static final List<String> RESTRICTED_TOOLS = List.of(
            "write_file", "edit_file", "delete_file",
            "run_command", "run_background_command",
            "execute_sql", "kill_process",
            "git_add", "git_commit", "git_push"
    );

    /**
     * 判断工具是否需要审查
     */
    public static boolean isRestricted(String toolName) {
        return RESTRICTED_TOOLS.contains(toolName);
    }

    /**
     * 从工具调用参数生成操作详情 markdown 文本
     *
     * @param toolName  工具名称
     * @param arguments 工具参数 JSON
     * @return 格式化的 markdown 文本，如果无需生成则返回 null
     */
    public static String generate(String toolName, JsonNode arguments) {
        if (arguments == null) return null;
        return switch (toolName) {
            case "write_file" -> generateWriteFile(arguments);
            case "edit_file" -> generateEditFile(arguments);
            case "delete_file" -> generateDeleteFile(arguments);
            case "run_command" -> generateRunCommand(arguments, "💻 执行命令");
            case "run_background_command" -> generateRunCommand(arguments, "💻 后台执行命令");
            case "execute_sql" -> generateExecuteSql(arguments);
            case "kill_process" -> generateKillProcess(arguments);
            case "git_add" -> generateGitAdd(arguments);
            case "git_commit" -> generateGitCommit(arguments);
            case "git_push" -> generateGitPush(arguments);
            default -> null;
        };
    }

    private static String generateWriteFile(JsonNode args) {
        String filePath = args.path("file_path").asText("");
        if (filePath.isEmpty()) return null;
        String content = args.path("content").asText("");
        boolean force = args.path("force").asBoolean(false);

        StringBuilder sb = new StringBuilder();
        boolean exists = false;
        String oldContent = null;

        // 尝试读取原文件内容生成 diff
        String resolvedPath = resolvePath(filePath);
        if (resolvedPath != null) {
            Path path = Paths.get(resolvedPath);
            if (Files.exists(path)) {
                exists = true;
                try {
                    oldContent = Files.readString(path, StandardCharsets.UTF_8);
                } catch (Exception e) {
                    log.debug("读取原文件失败: {}", e.getMessage());
                }
            }
        }

        if (exists && oldContent != null) {
            sb.append("> **📄 修改文件:** `").append(filePath).append("`");
            if (force) sb.append(" (强制覆盖)");
            sb.append("\n\n```diff\n");
            sb.append(generateDiff(oldContent, content));
            sb.append("\n```\n\n");
        } else if (exists) {
            sb.append("> **📄 覆盖文件:** `").append(filePath).append("`\n\n");
            sb.append("```\n").append(truncateContent(content)).append("\n```\n\n");
        } else {
            sb.append("> **📄 创建文件:** `").append(filePath).append("`\n\n");
            sb.append("```\n").append(truncateContent(content)).append("\n```\n\n");
        }

        return sb.toString();
    }

    private static String generateEditFile(JsonNode args) {
        String filePath = args.path("file_path").asText("");
        String pattern = args.path("pattern").asText("");
        String replacement = args.path("replacement").asText("");
        if (filePath.isEmpty()) return null;

        StringBuilder sb = new StringBuilder();
        sb.append("> **📄 编辑文件:** `").append(filePath).append("`\n\n");

        boolean multiline = args.path("multiline").asBoolean(false);
        if (multiline) {
            sb.append("> 多行模式 | ");
        }

        // 显示 pattern 和 replacement 的对比
        sb.append("**匹配模式:**\n\n```\n").append(truncateContent(pattern)).append("\n```\n\n");
        sb.append("**替换为:**\n\n```\n").append(truncateContent(replacement)).append("\n```\n\n");

        return sb.toString();
    }

    private static String generateDeleteFile(JsonNode args) {
        String path = args.path("path").asText("");
        if (path.isEmpty()) return null;
        return "> **🗑️ 删除文件:** `" + path + "`\n\n";
    }

    private static String generateRunCommand(JsonNode args, String title) {
        String command = args.path("command").asText("");
        String cwd = args.path("cwd").asText("");
        String executable = args.path("executable").asText("");

        StringBuilder sb = new StringBuilder();
        sb.append("> **").append(title).append("**\n\n```bash\n");

        if (!command.isEmpty()) {
            sb.append(command);
        } else if (!executable.isEmpty()) {
            sb.append(executable);
            JsonNode argsNode = args.path("args");
            if (argsNode.isArray()) {
                for (JsonNode arg : argsNode) {
                    sb.append(" ").append(arg.asText());
                }
            }
        } else {
            sb.append("(空命令)");
        }
        sb.append("\n```\n\n");

        if (!cwd.isEmpty()) {
            sb.append("> 工作目录: `").append(cwd).append("`\n\n");
        }

        return sb.toString();
    }

    private static String generateExecuteSql(JsonNode args) {
        String sql = args.path("sql").asText("");
        if (sql.isEmpty()) return null;

        // 检测 SQL 类型
        String trimmed = sql.trim().toUpperCase();
        String label = "查询";
        if (trimmed.startsWith("INSERT")) label = "插入";
        else if (trimmed.startsWith("UPDATE")) label = "更新";
        else if (trimmed.startsWith("DELETE")) label = "删除";
        else if (trimmed.startsWith("ALTER") || trimmed.startsWith("CREATE")
                || trimmed.startsWith("DROP") || trimmed.startsWith("TRUNCATE")) {
            label = "DDL 变更";
        }

        return "> **🗄️ SQL " + label + "**\n\n```sql\n" + sql + "\n```\n\n";
    }

    private static String generateKillProcess(JsonNode args) {
        int pid = args.path("pid").asInt(0);
        String name = args.path("name").asText("");
        if (pid == 0 && name.isEmpty()) return null;

        StringBuilder sb = new StringBuilder();
        sb.append("> **⛔ 终止进程**\n\n");
        if (pid > 0) sb.append("PID: ").append(pid);
        if (!name.isEmpty()) {
            if (pid > 0) sb.append(" | ");
            sb.append("名称: ").append(name);
        }
        sb.append("\n\n");
        return sb.toString();
    }

    private static String generateGitAdd(JsonNode args) {
        String path = args.path("path").asText("");
        if (path.isEmpty()) return null;

        boolean isAll = "all".equals(path) || ".".equals(path);
        return "> **📌 Git 暂存**" + (isAll ? " (全部变更)" : "") + "\n\n"
                + (isAll ? "" : "文件: `" + path + "`\n\n");
    }

    private static String generateGitCommit(JsonNode args) {
        String message = args.path("message").asText("");
        if (message.isEmpty()) return null;
        return "> **📦 Git 提交**\n\n提交信息: `" + message + "`\n\n";
    }

    private static String generateGitPush(JsonNode args) {
        String remote = args.path("remote").asText("origin");
        String branch = args.path("branch").asText("");
        if (branch.isEmpty()) return null;
        return "> **⬆️ Git 推送**\n\n远程: `" + remote + "` / 分支: `" + branch + "`\n\n";
    }

    /**
     * 生成简单的行级 diff（统一格式）
     */
    private static String generateDiff(String oldText, String newText) {
        String[] oldLines = oldText.split("\n", -1);
        String[] newLines = newText.split("\n", -1);
        List<String> diffLines = new ArrayList<>();

        int oi = 0, ni = 0;
        while (oi < oldLines.length || ni < newLines.length) {
            if (oi < oldLines.length && ni < newLines.length
                    && oldLines[oi].equals(newLines[ni])) {
                diffLines.add(" " + oldLines[oi]);
                oi++;
                ni++;
            } else {
                // 输出所有旧行（-）
                while (oi < oldLines.length
                        && (ni >= newLines.length || !oldLines[oi].equals(newLines[ni]))) {
                    diffLines.add("-" + oldLines[oi]);
                    oi++;
                }
                // 输出所有新行（+）
                while (ni < newLines.length
                        && (oi >= oldLines.length || !oldLines[ni].equals(newLines[oi]))) {
                    diffLines.add("+" + newLines[ni]);
                    ni++;
                }
            }
        }

        // 限制 diff 行数
        int maxLines = 100;
        if (diffLines.size() > maxLines) {
            int half = maxLines / 2;
            List<String> truncated = new ArrayList<>(diffLines.subList(0, half));
            truncated.add("... (" + (diffLines.size() - maxLines) + " 行被省略)");
            truncated.addAll(diffLines.subList(diffLines.size() - half, diffLines.size()));
            diffLines = truncated;
        }

        return String.join("\n", diffLines);
    }

    /**
     * 截断过长内容
     */
    private static String truncateContent(String content) {
        if (content == null) return "";
        if (content.length() > 2000) {
            return content.substring(0, 2000) + "\n... (共 " + content.length() + " 字符, 已截断)";
        }
        return content;
    }

    /**
     * 解析文件路径，支持相对路径
     */
    private static String resolvePath(String pathStr) {
        Path path = Paths.get(pathStr);
        if (path.isAbsolute()) {
            return path.normalize().toString();
        }
        String root = ProjectRootContext.get();
        if (root != null) {
            return Paths.get(root, pathStr).normalize().toString();
        }
        return null;
    }
}
