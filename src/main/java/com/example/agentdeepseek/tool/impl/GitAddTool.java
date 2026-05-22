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
import com.example.agentdeepseek.tool.permission.OperationCategory;
import com.example.agentdeepseek.tool.permission.ToolPermission;

/**
 * Git Add 工具
 * 暂存文件变更
 */
@Slf4j
@Component
@ToolPermission(category = OperationCategory.GIT, affectsData = true, description = "暂存文件变更")
public class GitAddTool implements Tool {

    private final GitCommandExecutor gitExecutor;
    private final ObjectMapper objectMapper;

    public GitAddTool(GitCommandExecutor gitExecutor, ObjectMapper objectMapper) {
        this.gitExecutor = gitExecutor;
        this.objectMapper = objectMapper;
    }

    @Override
    public String getName() {
        return "git_add";
    }

    @Override
    public String getDescription() {
        return "【适用场景】将工作区文件变更放入暂存区，为 git_commit 做准备\n"
                + "【与 git_commit 区别】git_add 只暂存不提交，git_commit 才真正创建版本快照；提交前必须先执行 git_add\n"
                + "【使用方式】path=\"src/App.java\" 暂存单文件；path=\"src/A.java,src/B.java\" 逗号分隔暂存多个；path=\"all\" 或 \".\" 暂存工作区全部变更\n"
                + "【注意】手动模式下需要用户授权";
    }

    @Override
    public JsonNode getParameters() {
        ObjectNode parameters = objectMapper.createObjectNode();
        parameters.put("type", "object");

        ObjectNode properties = objectMapper.createObjectNode();

        ObjectNode path = objectMapper.createObjectNode();
        path.put("type", "string");
        path.put("description", "【必填】要暂存的文件相对路径。单文件: \"src/main/App.java\"；多文件: \"src/A.java,src/B.java\"；暂存全部变更: \"all\" 或 \".\"");
        properties.set("path", path);

        parameters.set("properties", properties);
        parameters.putArray("required").add("path");
        return parameters;
    }

    @Override
    public String execute(JsonNode arguments) {

        String path = arguments.path("path").asText();
        if (path.isEmpty()) {
            return "【参数错误】缺少必填参数 path。请传入要暂存的文件路径，示例: path=\"src/main/App.java\"；多个文件逗号分隔；暂存全部传 path=\"all\"";
        }

        String projectRoot = ProjectRootContext.get();

        if ("all".equals(path) || ".".equals(path)) {
            GitCommandExecutor.GitResult result = gitExecutor.execute(projectRoot, "add", "-A");
            if (!result.success()) {
                return "【Git 错误】git add -A 失败，请确认当前目录是有效的 Git 仓库且非 bare 仓库。Git 返回信息：" + result.output();
            }
            return "已暂存全部变更";
        }

        // 暂存一个或多个文件（一次性 add，避免逐个启动子进程）
        String[] files = path.split(",");
        List<String> fileList = new ArrayList<>();
        for (String file : files) {
            String trimmed = file.trim();
            if (!trimmed.isEmpty()) {
                fileList.add(trimmed);
            }
        }

        if (fileList.isEmpty()) {
            return "【参数错误】path 中未包含任何有效的文件路径（逗号分隔后全部为空）。请传入至少一个文件路径，如 path=\"src/App.java\"";
        }

        // 将所有文件一次性传给 git add
        String[] addArgs = new String[fileList.size() + 1];
        addArgs[0] = "add";
        for (int i = 0; i < fileList.size(); i++) {
            addArgs[i + 1] = fileList.get(i);
        }
        GitCommandExecutor.GitResult result = gitExecutor.execute(projectRoot, addArgs);
        if (!result.success()) {
            return "【Git 错误】git add 指定文件失败。请逐项检查：①文件路径是否正确（相对项目根目录）；②文件是否真实存在；③是否被 .gitignore 忽略。Git 返回信息：" + result.output();
        }

        return "已暂存 " + fileList.size() + " 个文件：" + path;
    }
}
