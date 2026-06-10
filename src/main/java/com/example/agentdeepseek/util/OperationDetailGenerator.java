package com.example.agentdeepseek.util;

import com.example.agentdeepseek.tool.permission.ToolPermissionRegistry;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * 操作详情生成器
 * 从受限工具的参数中生成格式化 markdown 文本，注入到 thinking 流中展示给用户。
 * 权限判断委托给 ToolPermissionRegistry（统一来源）。
 */
@Slf4j
@Component
public class OperationDetailGenerator {

    private final ToolPermissionRegistry permissionRegistry;

    public OperationDetailGenerator(ToolPermissionRegistry permissionRegistry) {
        this.permissionRegistry = permissionRegistry;
    }

    /**
     * 判断工具是否需要审查（委托给权限注册中心）
     */
    public boolean isRestricted(String toolName) {
        return permissionRegistry.requiresDataApproval(toolName);
    }

    /**
     * 从工具调用参数生成操作详情 markdown 文本
     *
     * @param toolName  工具名称
     * @param arguments 工具参数 JSON
     * @return 格式化的 markdown 文本，如果无需生成则返回 null
     */
    public String generate(String toolName, JsonNode arguments) {
        if (arguments == null) return null;
        return switch (toolName) {
            case "command" -> generateCommand(arguments);
            case "file_writer" -> generateFileWriter(arguments);
            case "execute_sql" -> generateExecuteSql(arguments);
            case "git_submit" -> generateGitSubmit(arguments);
            default -> null;
        };
    }

    /**
     * command 网关：根据 action 参数分发到 exec/start/logs/stop/list 处理器
     */
    private static String generateCommand(JsonNode args) {
        String action = args.path("action").asText("");
        return switch (action) {
            case "exec" -> generateRunCommand(args, "💻 执行命令");
            case "start" -> generateRunCommand(args, "🚀 启动服务");
            case "stop", "logs", "list" -> generateServiceControl(args);
            default -> null;
        };
    }

    /**
     * file_writer 网关：根据参数特征分发到 write / edit / delete 处理器
     */
    private static String generateFileWriter(JsonNode args) {
        // edit: 有 old_text 参数
        if (args.has("old_text") && !args.path("old_text").asText("").isEmpty()) {
            return generateEditFile(args);
        }
        // delete: 只有 path 参数，没有 file_path 也没有 content
        if (!args.has("file_path") && !args.has("content") && args.has("path")) {
            return generateDeleteFile(args);
        }
        // write: 默认（有 file_path + content）
        return generateWriteFile(args);
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
                    oldContent = FileEncodingDetector.readString(path);
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
        String oldText = args.path("old_text").asText("");
        String newText = args.path("new_text").asText("");
        if (filePath.isEmpty()) return null;

        StringBuilder sb = new StringBuilder();
        sb.append("> **📄 编辑文件:** `").append(filePath).append("`\n\n");

        sb.append("```diff\n");
        sb.append(generateDiff(oldText, newText));
        sb.append("\n```\n\n");

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

    private static String generateServiceControl(JsonNode args) {
        String action = args.path("action").asText("");
        if (action.isEmpty()) return null;

        StringBuilder sb = new StringBuilder();
        switch (action) {
            case "list" -> sb.append("> **📋 列出后台服务**\n\n");
            case "logs" -> {
                int serviceId = args.path("service_id").asInt(0);
                int tail = args.path("tail").asInt(0);
                sb.append("> **📜 查看服务日志** #").append(serviceId);
                if (tail > 0) sb.append(" (最后 ").append(tail).append(" 行)");
                sb.append("\n\n");
            }
            case "stop" -> {
                int serviceId = args.path("service_id").asInt(0);
                boolean force = args.path("force").asBoolean(true);
                sb.append("> **⛔ 停止服务** #").append(serviceId);
                sb.append(force ? " (强制)" : " (优雅)");
                sb.append("\n\n");
            }
            default -> {
                return null;
            }
        }
        return sb.toString();
    }

    private static String generateGitSubmit(JsonNode args) {
        String action = args.path("action").asText("");
        return switch (action) {
            case "add" -> generateGitAdd(args);
            case "commit" -> generateGitCommit(args);
            case "push" -> generateGitPush(args);
            default -> null;
        };
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
                        && (oi >= oldLines.length || !oldLines[oi].equals(newLines[ni]))) {
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
