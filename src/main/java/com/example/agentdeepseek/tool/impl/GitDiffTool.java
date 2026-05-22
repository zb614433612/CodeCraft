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
 * Git Diff 工具
 * 查看文件或工作区的变更详情
 */
@Slf4j
@Component
@ToolPermission(category = OperationCategory.READ, description = "查看Git差异")
public class GitDiffTool implements Tool {

    /** Diff 输出最大字符数，超过则截断 */
    private static final int MAX_OUTPUT_LENGTH = 10000;

    private final GitCommandExecutor gitExecutor;
    private final ObjectMapper objectMapper;

    public GitDiffTool(GitCommandExecutor gitExecutor, ObjectMapper objectMapper) {
        this.gitExecutor = gitExecutor;
        this.objectMapper = objectMapper;
    }

    @Override
    public String getName() {
        return "git_diff";
    }

    @Override
    public String getDescription() {
        return "【适用场景】查看文件或全部工作区/暂存区的具体内容变更（diff），即每行代码的增删详情\n"
                + "【与 git_status 区别】git_status 只列文件名，git_diff 显示文件内每一行的改动（+绿色/-红色行）\n"
                + "【使用方式】不传 file 查看全部未暂存文件的变更；传 file=\"src/Main.java\" 只看指定文件；传 staged=true 查看已 git_add 暂存但未提交的变更\n"
                + "【建议】若 diff 输出过长被截断（>" + MAX_OUTPUT_LENGTH + "字符），应指定具体 file 缩小范围查看";
    }

    @Override
    public JsonNode getParameters() {
        ObjectNode parameters = objectMapper.createObjectNode();
        parameters.put("type", "object");

        ObjectNode properties = objectMapper.createObjectNode();

        ObjectNode file = objectMapper.createObjectNode();
        file.put("type", "string");
        file.put("description", "【可选】要查看 diff 的文件相对路径，如 \"src/main/java/App.java\"。不传则查看全部未暂存文件的变更。多个文件需分别调用");
        properties.set("file", file);

        ObjectNode staged = objectMapper.createObjectNode();
        staged.put("type", "boolean");
        staged.put("description", "【可选】设为 true 查看已暂存（已 git_add）的变更（--cached）；默认 false 查看工作区未暂存的变更。提交前用 staged=true 确认将要提交的内容");
        properties.set("staged", staged);

        parameters.set("properties", properties);
        return parameters;
    }

    @Override
    public String execute(JsonNode arguments) {
        String projectRoot = ProjectRootContext.get();

        String filePath = arguments.path("file").asText();
        boolean staged = arguments.path("staged").asBoolean(false);

        List<String> args = new ArrayList<>();
        args.add("diff");
        if (staged) {
            args.add("--cached");
        }
        if (!filePath.isEmpty()) {
            args.add("--");
            args.add(filePath);
        }

        GitCommandExecutor.GitResult result = gitExecutor.execute(
                projectRoot, args.toArray(new String[0]));

        if (!result.success()) {
            return "【Git 错误】git diff 执行失败。请检查：①文件路径是否正确（区分大小写）；②是否在 Git 仓库根目录下；③文件是否被 .gitignore 忽略。Git 返回信息：" + result.output();
        }

        String output = result.output();
        if (output.isBlank()) {
            return "【无变更】" + (filePath.isEmpty() ? "工作区无任何未暂存的变更。如有已暂存变更，请用 staged=true 查看" : "文件 " + filePath + " 当前无变更，可能原因：①文件未被修改；②修改已被回退；③文件路径写错");
        }

        // 限制输出大小
        if (output.length() > MAX_OUTPUT_LENGTH) {
            output = output.substring(0, MAX_OUTPUT_LENGTH) + "\n\n【截断提示】diff 输出超过 " + MAX_OUTPUT_LENGTH + " 字符已截断。建议传 file 参数指定具体文件路径缩小范围";
        }

        return output;
    }
}
