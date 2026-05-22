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

/**
 * Git Push 工具
 * 将本地提交推送到远程仓库
 */
@Slf4j
@Component
@ToolPermission(category = OperationCategory.GIT, affectsData = true, highRisk = true, description = "推送到远程仓库")
public class GitPushTool implements Tool {

    private final GitCommandExecutor gitExecutor;
    private final ObjectMapper objectMapper;

    public GitPushTool(GitCommandExecutor gitExecutor, ObjectMapper objectMapper) {
        this.gitExecutor = gitExecutor;
        this.objectMapper = objectMapper;
    }

    @Override
    public String getName() {
        return "git_push";
    }

    @Override
    public String getDescription() {
        return "【适用场景】将本地提交推送到远程仓库，使团队其他人可拉取你的代码\n"
                + "【与 git_commit 区别】git_commit 提交到本地仓库（仅自己可见），git_push 才上传到远程（团队共享）\n"
                + "【使用方式】本地 commit 后调用本工具推送。首次推送新分支用 set_upstream=true 建立上游关联；常规推送不传参数即可。force=true 仅用于需要覆盖远程历史时（危险操作）\n"
                + "【注意】手动模式下需要用户授权";
    }

    @Override
    public JsonNode getParameters() {
        ObjectNode parameters = objectMapper.createObjectNode();
        parameters.put("type", "object");

        ObjectNode properties = objectMapper.createObjectNode();

        ObjectNode remote = objectMapper.createObjectNode();
        remote.put("type", "string");
        remote.put("description", "【可选，默认 origin】远程仓库名称。标准配置下默认为 origin，通常无需修改");
        properties.set("remote", remote);

        ObjectNode branch = objectMapper.createObjectNode();
        branch.put("type", "string");
        branch.put("description", "【可选，默认当前分支】要推送的分支名，如 \"main\"。不传则自动获取当前所在分支");
        properties.set("branch", branch);

        ObjectNode setUpstream = objectMapper.createObjectNode();
        setUpstream.put("type", "boolean");
        setUpstream.put("description", "【可选，默认 false】首次推送新创建的分支时设为 true（等价 git push -u），之后推送同一分支无需再设此参数");
        properties.set("set_upstream", setUpstream);

        ObjectNode force = objectMapper.createObjectNode();
        force.put("type", "boolean");
        force.put("description", "【可选，默认 false】设为 true 强制推送覆盖远程历史（--force）。警告：此操作不可逆，会覆盖远程其他人的提交，仅在你确认需要时使用");
        properties.set("force", force);

        parameters.set("properties", properties);
        return parameters;
    }

    @Override
    public String execute(JsonNode arguments) {
        String projectRoot = ProjectRootContext.get();
        String remote = arguments.path("remote").asText("origin");
        String branch = arguments.path("branch").asText("");
        boolean setUpstream = arguments.path("set_upstream").asBoolean(false);
        boolean force = arguments.path("force").asBoolean(false);

        // 如果未指定分支，获取当前分支
        if (branch.isEmpty()) {
            GitCommandExecutor.GitResult currentBranch = gitExecutor.execute(projectRoot,
                    "rev-parse", "--abbrev-ref", "HEAD");
            if (!currentBranch.success()) {
                return "【Git 错误】无法获取当前分支名。请确认：①当前目录是有效 Git 仓库；②不处于 detached HEAD 状态（可通过 git_branch 查看）";
            }
            branch = currentBranch.output().trim();
        }

        // 强制推送的安全警告
        if (force) {
            log.warn("执行强制推送（--force），目标: {}/{}，将覆盖远程历史", remote, branch);
        }

        // 构建 git push 命令
        java.util.List<String> args = new java.util.ArrayList<>();
        args.add("push");
        args.add(remote);
        args.add(branch);
        if (setUpstream) {
            args.add("-u");
        }
        if (force) {
            args.add("--force");
        }

        GitCommandExecutor.GitResult result = gitExecutor.executeWithStoredAuth(
                projectRoot, args.toArray(new String[0]));

        if (!result.success()) {
            return "【Git 错误】推送失败。常见原因及修正：①远程有新提交 → 先执行 git pull 合并后再推送；②无推送权限 → 检查远程仓库权限；③分支无上游关联 → 设 set_upstream=true；④推送被 pre-receive hook 拒绝 → 查看远程仓库规则。Git 返回信息：" + result.output();
        }

        String flags = (force ? " --force" : "") + (setUpstream ? " -u" : "");
        log.info("Git 推送成功: {} -> {}/{}{}", branch, remote, branch, flags);
        return "推送成功" + (force ? "（强制推送，远程历史已被覆盖）" : "") + "\n" + result.output();
    }
}
