package com.example.agentdeepseek.tool.impl;

import com.example.agentdeepseek.tool.Tool;
import com.example.agentdeepseek.util.ProjectRootContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Git 状态查询工具
 * 显示当前分支和变更文件列表
 */
@Slf4j
@Component
public class GitStatusTool implements Tool {

    private final GitCommandExecutor gitExecutor;
    private final ObjectMapper objectMapper;

    public GitStatusTool(GitCommandExecutor gitExecutor, ObjectMapper objectMapper) {
        this.gitExecutor = gitExecutor;
        this.objectMapper = objectMapper;
    }

    @Override
    public String getName() {
        return "git_status";
    }

    @Override
    public String getDescription() {
        return "查看 Git 仓库状态，显示当前分支和文件变更列表（修改/新增/删除/未跟踪）";
    }

    @Override
    public JsonNode getParameters() {
        ObjectNode parameters = objectMapper.createObjectNode();
        parameters.put("type", "object");
        parameters.put("description", "无需参数");
        parameters.set("properties", objectMapper.createObjectNode());
        return parameters;
    }

    @Override
    public String execute(JsonNode arguments) {
        String projectRoot = ProjectRootContext.get();

        // 获取当前分支
        GitCommandExecutor.GitResult branchResult = gitExecutor.execute(projectRoot, "rev-parse", "--abbrev-ref", "HEAD");
        String branch = branchResult.success() ? branchResult.output().trim() : "未知";

        // 获取状态
        GitCommandExecutor.GitResult statusResult = gitExecutor.execute(projectRoot,
                "status", "--porcelain", "--branch");
        if (!statusResult.success()) {
            return "错误：获取 git 状态失败 - " + statusResult.output();
        }

        String raw = statusResult.output();
        StringBuilder sb = new StringBuilder();
        sb.append("当前分支：").append(branch).append("\n\n");

        if (raw.isBlank()) {
            sb.append("工作区干净，无未提交的变更");
            return sb.toString();
        }

        // 解析 --porcelain --branch 输出
        // 前两行是分支信息 (# branch... / # ahead/behind...)
        String[] lines = raw.split("\n");
        int changedCount = 0;
        int untrackedCount = 0;
        int stagedCount = 0;

        for (String line : lines) {
            if (line.startsWith("#")) continue;
            if (line.startsWith("??")) {
                untrackedCount++;
            } else {
                changedCount++;
                if (!line.substring(0, 2).trim().isEmpty()) {
                    stagedCount++;
                }
            }
        }

        sb.append("变更统计：").append(changedCount).append(" 个已跟踪文件变更")
                .append("，").append(untrackedCount).append(" 个未跟踪文件");
        if (stagedCount > 0) {
            sb.append("（").append(stagedCount).append(" 个已暂存）");
        }
        sb.append("\n\n");

        // 按区域分组显示
        sb.append("已暂存（Staged）：\n");
        boolean hasStaged = false;
        for (String line : lines) {
            if (line.startsWith("#")) continue;
            if (line.length() < 3) continue;
            String status = line.substring(0, 2);
            if (!status.trim().isEmpty() && !line.startsWith("??")) {
                String file = line.substring(3);
                sb.append("  ").append(status.trim()).append("  ").append(file).append("\n");
                hasStaged = true;
            }
        }
        if (!hasStaged) sb.append("  （无）\n");

        sb.append("\n工作区变更（Unstaged）：\n");
        boolean hasUnstaged = false;
        for (String line : lines) {
            if (line.startsWith("#")) continue;
            if (line.length() < 3) continue;
            String staged = line.substring(0, 1);
            String working = line.substring(1, 2);
            if (!working.trim().isEmpty() && !line.startsWith("??")) {
                String file = line.substring(3);
                sb.append("  ").append(working.trim()).append("  ").append(file).append("\n");
                hasUnstaged = true;
            }
        }
        if (!hasUnstaged) sb.append("  （无）\n");

        sb.append("\n未跟踪文件（Untracked）：\n");
        boolean hasUntracked = false;
        for (String line : lines) {
            if (line.startsWith("??")) {
                sb.append("  ?  ").append(line.substring(3)).append("\n");
                hasUntracked = true;
            }
        }
        if (!hasUntracked) sb.append("  （无）\n");

        return sb.toString();
    }
}
