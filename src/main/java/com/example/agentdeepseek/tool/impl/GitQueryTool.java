package com.example.agentdeepseek.tool.impl;

import com.example.agentdeepseek.tool.Tool;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Git 查询工具 — 合并 git_status / git_diff / git_log
 * 通过 action 参数区分操作，均为只读，不修改仓库状态
 */
@Slf4j
@Component
public class GitQueryTool implements Tool {

    private final GitCommandExecutor gitExecutor;
    private final ObjectMapper mapper = new ObjectMapper();

    public GitQueryTool(GitCommandExecutor gitExecutor) {
        this.gitExecutor = gitExecutor;
    }

    @Override
    public String getName() {
        return "git_query";
    }

    @Override
    public String getDescription() {
        return "【适用场景】查看 Git 仓库的状态、差异和提交历史，所有操作均为只读。\n"
                + "【action 说明】status=查看工作区/暂存区文件变更列表；diff=查看文件具体代码差异；log=查看提交历史。\n"
                + "【使用方式】指定 projectRoot（项目根目录）和 action，根据需要传入可选参数。\n"
                + "【注意事项】所有操作均为只读，不会修改仓库状态；diff 不传 file 则查看全部未暂存变更；log 默认显示最近 20 条。";
    }

    @Override
    public JsonNode getParameters() {
        ObjectNode root = mapper.createObjectNode();
        root.put("type", "object");

        ObjectNode properties = root.putObject("properties");

        // projectRoot
        ObjectNode projectRoot = properties.putObject("projectRoot");
        projectRoot.put("type", "string");
        projectRoot.put("description", "【必填】Git 项目根目录路径。示例：'/home/user/project' 或 'E:/work/my-app'。");

        // action
        ObjectNode action = properties.putObject("action");
        action.put("type", "string");
        action.put("description", "【必填】查询操作类型。status=查看工作区/暂存区文件变更状态；diff=查看文件代码差异详情；log=查看提交历史记录。");
        ArrayNode actionEnum = action.putArray("enum");
        actionEnum.add("status");
        actionEnum.add("diff");
        actionEnum.add("log");

        // file（diff 和 log 共用）
        ObjectNode file = properties.putObject("file");
        file.put("type", "string");
        file.put("description", "【可选，diff/log 时有效】指定要查看的文件相对路径。diff 时示例：'src/main/App.java'；log 时示例：'src/main/App.java' 仅查看该文件的提交历史。不传则查看全部。");

        // staged（仅 diff 有效）
        ObjectNode staged = properties.putObject("staged");
        staged.put("type", "boolean");
        staged.put("description", "【可选，仅 diff 时有效】设为 true 查看已暂存（git add 后）的变更；默认 false 查看工作区未暂存的变更。");

        // max_count（仅 log 有效）
        ObjectNode maxCount = properties.putObject("max_count");
        maxCount.put("type", "integer");
        maxCount.put("description", "【可选，仅 log 时有效】显示最近 N 条提交，默认 20。示例：10 显示最近 10 条。");

        // graph（仅 log 有效）
        ObjectNode graph = properties.putObject("graph");
        graph.put("type", "boolean");
        graph.put("description", "【可选，仅 log 时有效】设为 true 以文本图形展示分支合并关系。查看多分支协作历史时建议开启。");

        // required
        ArrayNode required = root.putArray("required");
        required.add("projectRoot");
        required.add("action");

        return root;
    }

    @Override
    public String execute(JsonNode arguments) {
        String projectRoot = arguments.has("projectRoot") ? arguments.get("projectRoot").asText() : ".";
        String action = arguments.has("action") ? arguments.get("action").asText() : "status";

        log.info("git_query action={}, projectRoot={}", action, projectRoot);

        return switch (action) {
            case "status" -> doStatus(projectRoot);
            case "diff" -> doDiff(projectRoot, arguments);
            case "log" -> doLog(projectRoot, arguments);
            default -> "错误：未知的 action '" + action + "'，支持 status / diff / log";
        };
    }

    // ========== git status ==========

    private String doStatus(String projectRoot) {
        GitCommandExecutor.GitResult result = gitExecutor.execute(projectRoot, "status", "--porcelain");
        if (!result.success()) {
            return "git status 失败：" + result.output();
        }
        String output = result.output();
        if (output.isBlank()) {
            return "工作区干净，没有文件变更。";
        }
        // 按状态码归类
        StringBuilder sb = new StringBuilder();
        sb.append("文件变更状态（--porcelain 格式）：\n\n");

        List<String> staged = new ArrayList<>();   // 暂存区
        List<String> unstaged = new ArrayList<>(); // 工作区
        List<String> untracked = new ArrayList<>(); // 未跟踪

        for (String line : output.split("\n")) {
            if (line.length() >= 3) {
                String code = line.substring(0, 2);
                // --porcelain 格式: XY filename，至少需要 4 字符（2状态码+空格+文件名首字符）
                // 安全处理：长度不足 4 时文件名按空字符串处理
                String filename = line.length() >= 4 ? line.substring(3).trim() : "";
                if (code.contains("?")) {
                    untracked.add(filename);
                } else if (code.trim().isEmpty() || code.equals("  ")) {
                    continue;
                } else {
                    // 暂存区状态码在第一位，工作区在第二位
                    // 一个文件可能同时在 staged 和 unstaged 中出现（如 MM = 暂存区和工作区都修改了同一文件），
                    // 这是正确行为：说明该文件有两层未提交的变更
                    char indexStatus = code.charAt(0);
                    char worktreeStatus = code.charAt(1);
                    if (indexStatus != ' ' && indexStatus != '?') {
                        staged.add("[" + code + "] " + filename);
                    }
                    if (worktreeStatus != ' ' && worktreeStatus != '?') {
                        unstaged.add("[" + code + "] " + filename);
                    }
                }
            }
        }

        if (!staged.isEmpty()) {
            sb.append("── 已暂存（git add 后）──\n");
            staged.forEach(f -> sb.append("  ").append(f).append("\n"));
            sb.append("\n");
        }
        if (!unstaged.isEmpty()) {
            sb.append("── 工作区变更（未暂存）──\n");
            unstaged.forEach(f -> sb.append("  ").append(f).append("\n"));
            sb.append("\n");
        }
        if (!untracked.isEmpty()) {
            sb.append("── 未跟踪文件（Untracked）──\n");
            untracked.forEach(f -> sb.append("  ? ").append(f).append("\n"));
            sb.append("\n");
        }

        sb.append("统计：已暂存 ").append(staged.size())
                .append(" | 工作区变更 ").append(unstaged.size())
                .append(" | 未跟踪 ").append(untracked.size());
        return sb.toString();
    }

    // ========== git diff ==========

    private String doDiff(String projectRoot, JsonNode args) {
        String file = args.has("file") ? args.get("file").asText() : null;
        boolean staged = args.has("staged") && args.get("staged").asBoolean();

        List<String> cmdArgs = new ArrayList<>();
        cmdArgs.add("diff");
        if (staged) {
            cmdArgs.add("--cached");
        }
        if (file != null && !file.isBlank()) {
            cmdArgs.add("--");
            cmdArgs.add(file);
        }

        GitCommandExecutor.GitResult result = gitExecutor.execute(projectRoot, cmdArgs.toArray(new String[0]));
        if (!result.success()) {
            return "git diff 失败：" + result.output();
        }
        String output = result.output();
        if (output.isBlank()) {
            return staged ? "暂存区没有变更。" : "工作区没有未暂存的变更。";
        }
        return output;
    }

    // ========== git log ==========

    private String doLog(String projectRoot, JsonNode args) {
        int maxCount = 20;
        if (args.has("max_count")) {
            try {
                maxCount = args.get("max_count").asInt();
                if (maxCount <= 0) maxCount = 20;
                if (maxCount > 500) maxCount = 500;  // 防止过大输出
            } catch (Exception e) {
                log.warn("git_query log: max_count 解析失败，使用默认值 20。原始值: {}", args.get("max_count"));
                maxCount = 20;
            }
        }
        boolean graph = args.has("graph") && args.get("graph").asBoolean();
        String file = args.has("file") ? args.get("file").asText() : null;

        List<String> cmdArgs = new ArrayList<>();
        cmdArgs.add("log");
        cmdArgs.add("--oneline");
        cmdArgs.add("-" + maxCount);
        if (graph) {
            cmdArgs.add("--graph");
            cmdArgs.add("--decorate");
        }
        if (file != null && !file.isBlank()) {
            cmdArgs.add("--");
            cmdArgs.add(file);
        }

        GitCommandExecutor.GitResult result = gitExecutor.execute(projectRoot, cmdArgs.toArray(new String[0]));
        if (!result.success()) {
            return "git log 失败：" + result.output();
        }
        String output = result.output();
        if (output.isBlank()) {
            return "暂无提交记录。";
        }
        return output;
    }
}
