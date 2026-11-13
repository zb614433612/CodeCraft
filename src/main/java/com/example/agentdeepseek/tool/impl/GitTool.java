package com.example.agentdeepseek.tool.impl;

import com.example.agentdeepseek.tool.Tool;
import com.example.agentdeepseek.tool.permission.OperationCategory;
import com.example.agentdeepseek.tool.permission.ToolPermission;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Git 统一工具 —— 合并原 git_query / git_submit / git_branch / git_stash / git_tag / git_remote 六个工具
 * <p>
 * <b>为什么合并</b>：六个工具各自重复声明 projectRoot / action 与描述包装，schema 体积大且
 * 模型需要在六个相似名字之间做选择（选错就多一轮，而多一轮的成本远高于省下的 schema）。
 * 合并后只有一个 {@code git}，选择空间从 6 降到 1，描述里用 action 分组表把边界写死。
 * </p>
 * <p>
 * <b>实现策略</b>：纯委托（零逻辑复制）——按 action 路由到原工具实例执行，
 * 参数原样透传，因此行为与合并前<b>完全一致</b>，不存在"合并引入行为差异"的风险。
 * 旧工具仍注册在 ToolRegistry 中（可被历史消息引用），只是不再下发给模型。
 * </p>
 */
@Slf4j
@Component
@ToolPermission(category = OperationCategory.GIT, affectsData = true,
        description = "Git 统一操作：查询/提交/分支/储藏/标签/远程")
public class GitTool implements Tool {

    private final GitQueryTool queryTool;
    private final GitSubmitTool submitTool;
    private final GitBranchTool branchTool;
    private final GitStashTool stashTool;
    private final GitTagTool tagTool;
    private final GitRemoteTool remoteTool;
    private final ObjectMapper mapper = new ObjectMapper();

    /**
     * 合并后的 action → (目标子工具, 子工具内的 action)
     * <p>用 LinkedHashMap 保证描述与校验输出顺序稳定（也避免任何随机顺序影响缓存前缀）。</p>
     */
    private static final Map<String, String[]> ACTION_ROUTING = new LinkedHashMap<>();

    static {
        // ===== 查询（只读）→ git_query =====
        ACTION_ROUTING.put("status", new String[]{"query", "status"});
        ACTION_ROUTING.put("diff", new String[]{"query", "diff"});
        ACTION_ROUTING.put("log", new String[]{"query", "log"});
        ACTION_ROUTING.put("show", new String[]{"query", "show"});

        // ===== 提交与同步（写）→ git_submit =====
        ACTION_ROUTING.put("add", new String[]{"submit", "add"});
        ACTION_ROUTING.put("commit", new String[]{"submit", "commit"});
        ACTION_ROUTING.put("push", new String[]{"submit", "push"});
        ACTION_ROUTING.put("pull", new String[]{"submit", "pull"});
        ACTION_ROUTING.put("fetch", new String[]{"submit", "fetch"});
        ACTION_ROUTING.put("unstage", new String[]{"submit", "unstage"});
        ACTION_ROUTING.put("restore", new String[]{"submit", "restore"});
        ACTION_ROUTING.put("reset", new String[]{"submit", "reset"});
        ACTION_ROUTING.put("revert", new String[]{"submit", "revert"});

        // ===== 分支 → git_branch =====
        ACTION_ROUTING.put("branch_list", new String[]{"branch", "list"});
        ACTION_ROUTING.put("branch_create", new String[]{"branch", "create"});
        ACTION_ROUTING.put("branch_switch", new String[]{"branch", "switch"});
        ACTION_ROUTING.put("branch_merge", new String[]{"branch", "merge"});
        ACTION_ROUTING.put("branch_delete", new String[]{"branch", "delete"});

        // ===== 储藏 → git_stash =====
        ACTION_ROUTING.put("stash_save", new String[]{"stash", "save"});
        ACTION_ROUTING.put("stash_list", new String[]{"stash", "list"});
        ACTION_ROUTING.put("stash_pop", new String[]{"stash", "pop"});
        ACTION_ROUTING.put("stash_drop", new String[]{"stash", "drop"});

        // ===== 标签 → git_tag =====
        ACTION_ROUTING.put("tag_list", new String[]{"tag", "list"});
        ACTION_ROUTING.put("tag_create", new String[]{"tag", "create"});
        ACTION_ROUTING.put("tag_delete", new String[]{"tag", "delete"});
        ACTION_ROUTING.put("tag_push", new String[]{"tag", "push"});

        // ===== 远程 → git_remote =====
        ACTION_ROUTING.put("remote_list", new String[]{"remote", "list"});
        ACTION_ROUTING.put("remote_add", new String[]{"remote", "add"});
        ACTION_ROUTING.put("remote_set_url", new String[]{"remote", "set_url"});
        ACTION_ROUTING.put("remote_remove", new String[]{"remote", "remove"});
    }

    public GitTool(GitQueryTool queryTool,
                   GitSubmitTool submitTool,
                   GitBranchTool branchTool,
                   GitStashTool stashTool,
                   GitTagTool tagTool,
                   GitRemoteTool remoteTool) {
        this.queryTool = queryTool;
        this.submitTool = submitTool;
        this.branchTool = branchTool;
        this.stashTool = stashTool;
        this.tagTool = tagTool;
        this.remoteTool = remoteTool;
    }

    @Override
    public String getName() {
        return "git";
    }

    @Override
    public String getDescription() {
        return "【适用场景】一切 Git 操作：查询状态/差异/历史、提交与同步、分支、储藏、标签、远程仓库管理。\n"
                + "【何时使用】需要读取或修改 Git 仓库时（status/diff/log/commit/push/branch/merge/tag 等）。\n"
                + "【何时不要用】不要用 command 执行 git 命令——command 不做 Git 权限分类、输出无结构化处理，且违反项目规则。\n"
                + "【与 command 的区别】command 只用于运行非 git 的程序（构建、测试、脚本、依赖安装）。\n"
                + "【与 git 相关的所有动作都走本工具，不要拆成多个工具尝试】\n"
                + "【action 分组】\n"
                + "  · 查询（只读）：status=工作区/暂存区变更列表 | diff=代码差异（staged=true 看暂存区） | log=提交历史（max_count/graph） | show=某次提交详情（ref/stat/file）\n"
                + "  · 提交与同步：add=暂存（path） | commit=提交（message） | push=推送（branch/force/set_upstream） | pull=拉取并合并 | fetch=仅拉取（prune） | unstage=取消暂存（path） | restore=撤销工作区改动⚠丢弃 | reset=回退提交（target/mode，hard 需 confirm=true） | revert=反向提交（commit/no_commit）\n"
                + "  · 分支：branch_list | branch_create（name，switch=true 创建并切换） | branch_switch（name） | branch_merge（name，no_ff/message） | branch_delete（name，force=true 强制）\n"
                + "  · 储藏：stash_save（message） | stash_list | stash_pop（index） | stash_drop（index，⚠不恢复内容）\n"
                + "  · 标签：tag_list | tag_create（name，message=附注标签） | tag_delete（name） | tag_push（name；不传则推送全部）\n"
                + "  · 远程：remote_list | remote_add（name+url） | remote_set_url（name+url） | remote_remove（name）\n"
                + "【注意事项】projectRoot 必填（项目根目录绝对路径优先）；reset mode=hard 与 restore 属破坏性操作，执行前确认；diff 不传 file 查看全部未暂存变更。";
    }

    @Override
    public JsonNode getParameters() {
        ObjectNode root = mapper.createObjectNode();
        root.put("type", "object");

        ObjectNode properties = root.putObject("properties");

        ObjectNode projectRoot = properties.putObject("projectRoot");
        projectRoot.put("type", "string");
        projectRoot.put("description", "【必填】Git 项目根目录路径。示例：'/home/user/project' 或 'E:/work/my-app'。");

        ObjectNode action = properties.putObject("action");
        action.put("type", "string");
        action.put("description", "【必填】Git 操作类型，取值见枚举。查询类：status/diff/log/show；提交类：add/commit/push/pull/fetch/unstage/restore/reset/revert；分支：branch_list/branch_create/branch_switch/branch_merge/branch_delete；储藏：stash_save/stash_list/stash_pop/stash_drop；标签：tag_list/tag_create/tag_delete/tag_push；远程：remote_list/remote_add/remote_set_url/remote_remove。");
        ArrayNode actionEnum = action.putArray("enum");
        for (String key : ACTION_ROUTING.keySet()) {
            actionEnum.add(key);
        }

        ObjectNode path = properties.putObject("path");
        path.put("type", "string");
        path.put("description", "【可选，add/unstage/restore 时必填】文件路径。单文件：'src/main/App.java'；多文件逗号分隔：'src/A.java,src/B.java'；全部：'all' 或 '.'。");

        ObjectNode message = properties.putObject("message");
        message.put("type", "string");
        message.put("description", "【可选，commit/stash_save/tag_create/branch_merge 时使用】commit/stash 的说明文字，或附注标签/合并提交信息。建议 commit 用 conventional commits 格式：'feat: xxx'、'fix: xxx'。");

        ObjectNode file = properties.putObject("file");
        file.put("type", "string");
        file.put("description", "【可选，diff/log/show 时有效】指定文件相对路径。不传则查看全部。");

        ObjectNode staged = properties.putObject("staged");
        staged.put("type", "boolean");
        staged.put("description", "【可选，仅 diff 时有效】true=查看已暂存（git add 后）的变更；默认 false 看工作区未暂存变更。");

        ObjectNode maxCount = properties.putObject("max_count");
        maxCount.put("type", "integer");
        maxCount.put("description", "【可选，仅 log 时有效】显示最近 N 条提交，默认 20。");

        ObjectNode graph = properties.putObject("graph");
        graph.put("type", "boolean");
        graph.put("description", "【可选，仅 log 时有效】true=以文本图形展示分支合并关系。");

        ObjectNode ref = properties.putObject("ref");
        ref.put("type", "string");
        ref.put("description", "【可选，仅 show 时有效】提交引用，默认 HEAD。可为哈希、HEAD~2、分支名、标签名。");

        ObjectNode stat = properties.putObject("stat");
        stat.put("type", "boolean");
        stat.put("description", "【可选，仅 show 时有效】true=只显示文件统计（--stat），不显示完整 diff。");

        ObjectNode remote = properties.putObject("remote");
        remote.put("type", "string");
        remote.put("description", "【可选，push/pull/fetch/tag_push/remote_* 时有效】远程仓库名称，默认 origin。");

        ObjectNode branch = properties.putObject("branch");
        branch.put("type", "string");
        branch.put("description", "【可选，push/pull 时有效】分支名。push 不传则推送当前分支；pull 不传则拉取当前分支上游。");

        ObjectNode setName = properties.putObject("name");
        setName.put("type", "string");
        setName.put("description", "【可选，branch_*/tag_*/remote_* 时必填】分支名、标签名或远程名。示例：'feature/login'、'v1.0.0'、'origin'。");

        ObjectNode url = properties.putObject("url");
        url.put("type", "string");
        url.put("description", "【可选，仅 remote_add/remote_set_url 时必填】远程仓库地址。示例：'https://github.com/user/repo.git'。");

        ObjectNode switchBranch = properties.putObject("switch");
        switchBranch.put("type", "boolean");
        switchBranch.put("description", "【可选，仅 branch_create 时有效】true=创建后立即切换到新分支。");

        ObjectNode noFf = properties.putObject("no_ff");
        noFf.put("type", "boolean");
        noFf.put("description", "【可选，仅 branch_merge 时有效】true=强制生成合并提交（--no-ff），保留分支历史。");

        ObjectNode force = properties.putObject("force");
        force.put("type", "boolean");
        force.put("description", "【可选】push 时=强制推送（⚠覆盖远程历史）；branch_delete 时=强制删除未合并分支（⚠提交永久丢失）。仅确认需要时使用。");

        ObjectNode setUpstream = properties.putObject("set_upstream");
        setUpstream.put("type", "boolean");
        setUpstream.put("description", "【可选，仅 push 时有效】true=首次推送新分支时建立上游跟踪（等价 git push -u）。");

        ObjectNode prune = properties.putObject("prune");
        prune.put("type", "boolean");
        prune.put("description", "【可选，仅 fetch 时有效】true=清理远程已删除分支的本地追踪引用。");

        ObjectNode mode = properties.putObject("mode");
        mode.put("type", "string");
        mode.put("description", "【可选，仅 reset 时有效】回退模式：soft（改动留暂存区）/ mixed（默认，改动留工作区）/ hard（⚠丢弃提交与未提交改动，需 confirm=true）。");
        ArrayNode modeEnum = mode.putArray("enum");
        modeEnum.add("soft");
        modeEnum.add("mixed");
        modeEnum.add("hard");

        ObjectNode target = properties.putObject("target");
        target.put("type", "string");
        target.put("description", "【可选，reset 时必填】回退目标。示例：'HEAD~1' 撤销最近一次提交。");

        ObjectNode confirm = properties.putObject("confirm");
        confirm.put("type", "boolean");
        confirm.put("description", "【可选，reset mode=hard 时必填】二次确认标志，必须显式传 true 才会执行。");

        ObjectNode revertCommit = properties.putObject("commit");
        revertCommit.put("type", "string");
        revertCommit.put("description", "【可选，仅 revert 时有效】要撤销的提交引用，默认 HEAD。可为提交哈希、HEAD~2。");

        ObjectNode noCommit = properties.putObject("no_commit");
        noCommit.put("type", "boolean");
        noCommit.put("description", "【可选，仅 revert 时有效】true=只把改动落到暂存区/工作区，不自动创建提交。");

        ObjectNode index = properties.putObject("index");
        index.put("type", "integer");
        index.put("description", "【可选，仅 stash_pop/stash_drop 时有效】储藏记录序号（0=最新）。不传则操作最新一条。");

        ArrayNode required = root.putArray("required");
        required.add("projectRoot");
        required.add("action");

        return root;
    }

    @Override
    public String execute(JsonNode arguments) {
        String projectRoot = arguments.has("projectRoot") ? arguments.get("projectRoot").asText() : ".";
        String action = arguments.has("action") ? arguments.get("action").asText("").trim() : "";

        if (action.isEmpty()) {
            return buildUnknownActionMessage("（未提供 action）");
        }

        String[] routing = ACTION_ROUTING.get(action);
        if (routing == null) {
            return buildUnknownActionMessage(action);
        }

        String toolKey = routing[0];
        String subAction = routing[1];

        log.info("git action={} → {}.{} projectRoot={}", action, toolKey, subAction, projectRoot);

        // 把合并后的 action 还原成子工具认识的形式，其余参数原样透传（子工具只取自己关心的字段）
        JsonNode childArgs = rewriteAction(arguments, subAction);

        try {
            return switch (toolKey) {
                case "query" -> queryTool.execute(childArgs);
                case "submit" -> submitTool.execute(childArgs);
                case "branch" -> branchTool.execute(childArgs);
                case "stash" -> stashTool.execute(childArgs);
                case "tag" -> tagTool.execute(childArgs);
                case "remote" -> remoteTool.execute(childArgs);
                default -> buildUnknownActionMessage(action);
            };
        } catch (Exception e) {
            log.error("git 工具执行异常: action={}, err={}", action, e.getMessage(), e);
            return "git " + action + " 执行失败：" + e.getMessage()
                    + "\n【建议】先调用 git(action=status) 确认仓库状态，或检查 projectRoot 是否为有效的 Git 仓库根目录。";
        }
    }

    /**
     * 把合并工具的参数对象复制一份，并把 action 改写为子工具认识的值
     */
    private JsonNode rewriteAction(JsonNode arguments, String subAction) {
        ObjectNode copy = mapper.createObjectNode();
        arguments.fields().forEachRemaining(entry -> copy.set(entry.getKey(), entry.getValue()));
        copy.put("action", subAction);
        return copy;
    }

    /**
     * 未知 action 的可操作错误提示（列出可用取值，避免模型盲目重试）
     */
    private String buildUnknownActionMessage(String action) {
        StringBuilder sb = new StringBuilder();
        sb.append("错误：未知的 git action '").append(action).append("'。\n");
        sb.append("可用 action（按类别）：\n");
        sb.append("  · 查询：status / diff / log / show\n");
        sb.append("  · 提交与同步：add / commit / push / pull / fetch / unstage / restore / reset / revert\n");
        sb.append("  · 分支：branch_list / branch_create / branch_switch / branch_merge / branch_delete\n");
        sb.append("  · 储藏：stash_save / stash_list / stash_pop / stash_drop\n");
        sb.append("  · 标签：tag_list / tag_create / tag_delete / tag_push\n");
        sb.append("  · 远程：remote_list / remote_add / remote_set_url / remote_remove\n");
        sb.append("【下一步】改用上述某个 action 重新调用；不确定仓库状态时先调 action=status。");
        return sb.toString();
    }
}
