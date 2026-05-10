package com.example.agentdeepseek.tool.impl;

import com.example.agentdeepseek.tool.Tool;
import com.example.agentdeepseek.util.ProjectRootContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Git 状态查询工具
 * 显示当前分支和文件变更列表（修改/新增/删除/未跟踪）
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
        // 格式：XY filename，X=暂存区状态，Y=工作区状态，??=未跟踪
        // 前1-2行是分支信息 (# branch... / # ahead/behind...)
        String[] lines = raw.split("\n");

        // 单次遍历：解析所有行的状态并分类存储
        List<String> stagedFiles = new ArrayList<>();
        List<String> unstagedFiles = new ArrayList<>();
        List<String> untrackedFiles = new ArrayList<>();
        int stagedCount = 0;
        int unstagedCount = 0;

        for (String line : lines) {
            if (line.startsWith("#") || line.length() < 3) continue;

            if (line.startsWith("??")) {
                untrackedFiles.add(line.substring(3));
                continue;
            }

            char indexStatus = line.charAt(0);   // 暂存区状态
            char workStatus = line.charAt(1);    // 工作区状态
            String fileName = line.substring(3);

            if (indexStatus != ' ') {
                stagedFiles.add("  " + indexStatus + "  " + fileName);
                stagedCount++;
            }
            if (workStatus != ' ') {
                unstagedFiles.add("  " + workStatus + "  " + fileName);
                unstagedCount++;
            }
        }

        // 输出统计
        sb.append("变更统计：").append(stagedCount + unstagedCount).append(" 处变更")
                .append("（").append(stagedCount).append(" 个已暂存，").append(unstagedCount).append(" 个未暂存）")
                .append("，").append(untrackedFiles.size()).append(" 个未跟踪文件")
                .append("\n\n");

        // 输出已暂存
        sb.append("已暂存（Staged）：\n");
        if (!stagedFiles.isEmpty()) {
            for (String file : stagedFiles) {
                sb.append(file).append("\n");
            }
        } else {
            sb.append("  （无）\n");
        }

        // 输出工作区变更
        sb.append("\n工作区变更（Unstaged）：\n");
        if (!unstagedFiles.isEmpty()) {
            for (String file : unstagedFiles) {
                sb.append(file).append("\n");
            }
        } else {
            sb.append("  （无）\n");
        }

        // 输出未跟踪文件
        sb.append("\n未跟踪文件（Untracked）：\n");
        if (!untrackedFiles.isEmpty()) {
            for (String file : untrackedFiles) {
                sb.append("  ?  ").append(file).append("\n");
            }
        } else {
            sb.append("  （无）\n");
        }

        return sb.toString();
    }
}
