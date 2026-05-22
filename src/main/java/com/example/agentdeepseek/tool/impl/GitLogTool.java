package com.example.agentdeepseek.tool.impl;

import com.example.agentdeepseek.tool.Tool;
import com.example.agentdeepseek.util.ProjectRootContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import com.example.agentdeepseek.tool.permission.OperationCategory;
import com.example.agentdeepseek.tool.permission.ToolPermission;

import java.util.ArrayList;
import java.util.List;

/**
 * Git Log 工具
 * 查看提交历史
 */
@Slf4j
@Component
@ToolPermission(category = OperationCategory.READ, description = "查看Git日志")
public class GitLogTool implements Tool {

    private static final int DEFAULT_MAX_COUNT = 20;

    private final GitCommandExecutor gitExecutor;
    private final ObjectMapper objectMapper;

    public GitLogTool(GitCommandExecutor gitExecutor, ObjectMapper objectMapper) {
        this.gitExecutor = gitExecutor;
        this.objectMapper = objectMapper;
    }

    @Override
    public String getName() {
        return "git_log";
    }

    @Override
    public String getDescription() {
        return "【适用场景】查看项目提交历史，了解最近谁做了什么、查找特定提交或定位问题引入时间点\n"
                + "【与 git_status/git_diff 区别】git_log 看的是已提交的历史记录，git_status/diff 看的是当前未提交的变更\n"
                + "【使用方式】直接调用看最近默认条数；传 max_count=10 调整数量；传 file=\"src/App.java\" 只看某文件的提交历史；传 graph=true 显示分支合并拓扑";
    }

    @Override
    public JsonNode getParameters() {
        ObjectNode parameters = objectMapper.createObjectNode();
        parameters.put("type", "object");

        ObjectNode properties = objectMapper.createObjectNode();

        ObjectNode maxCount = objectMapper.createObjectNode();
        maxCount.put("type", "integer");
        maxCount.put("description", "【可选】显示最近 N 条提交，如 10。默认 " + DEFAULT_MAX_COUNT + " 条。要查更多历史可增大此值");
        properties.set("max_count", maxCount);

        ObjectNode file = objectMapper.createObjectNode();
        file.put("type", "string");
        file.put("description", "【可选】文件相对路径，如 \"src/main/App.java\"。只显示包含该文件变更的提交记录。用于追踪某个文件的修改历史");
        properties.set("file", file);

        ObjectNode graph = objectMapper.createObjectNode();
        graph.put("type", "boolean");
        graph.put("description", "【可选】设为 true 以文本图形展示分支合并关系（--graph）。查看多分支协作历史或合并记录时建议开启");
        properties.set("graph", graph);

        parameters.set("properties", properties);
        return parameters;
    }

    @Override
    public String execute(JsonNode arguments) {
        String projectRoot = ProjectRootContext.get();

        int maxCount = arguments.path("max_count").asInt(DEFAULT_MAX_COUNT);
        String filePath = arguments.path("file").asText();
        boolean showGraph = arguments.path("graph").asBoolean(false);

        List<String> args = new ArrayList<>();
        args.add("log");
        if (showGraph) {
            // --graph 与 --oneline 兼容，共同使用保持简约
            args.add("--oneline");
            args.add("--graph");
        } else {
            args.add("--oneline");
        }
        args.add("-" + maxCount);

        if (!filePath.isEmpty()) {
            args.add("--");
            args.add(filePath);
        }

        GitCommandExecutor.GitResult result = gitExecutor.execute(
                projectRoot, args.toArray(new String[0]));

        if (!result.success()) {
            return "【Git 错误】git log 执行失败。请检查：①当前路径是否为有效 Git 仓库；②指定的 file 文件是否存在；③max_count 是否为正整数。Git 返回信息：" + result.output();
        }

        String output = result.output();
        if (output.isBlank()) {
            return "【无历史】该仓库暂无提交记录。请先用 git_commit 创建首个提交";
        }

        return "最近 " + maxCount + " 条提交记录：\n" + output;
    }
}
