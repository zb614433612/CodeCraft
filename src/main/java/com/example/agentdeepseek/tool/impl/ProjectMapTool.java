package com.example.agentdeepseek.tool.impl;

import com.example.agentdeepseek.projectcontext.ProjectContextProvider;
import com.example.agentdeepseek.projectcontext.ProjectMapService;
import com.example.agentdeepseek.tool.Tool;
import com.example.agentdeepseek.tool.permission.OperationCategory;
import com.example.agentdeepseek.tool.permission.ToolPermission;
import com.example.agentdeepseek.util.ProjectRootContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

/**
 * 项目知识库工具（.codecraft/）。
 *
 * <p><b>设计立场</b>：这是一个「按需使用」的工具，不是一个「每次对话都跑一遍」的流程。
 * 工具描述里明确写了两张清单——什么情况该建、什么情况不该建——把判断权交给模型，
 * 而不是由代码强制。理由很实在：写死的流程会让「改一行字」也去扫描整个仓库，
 * 那是纯浪费。</p>
 *
 * <p>权限定为 SAFE：全部读写都限制在项目根下的 {@code .codecraft/} 目录内，
 * 不触碰源码，不需要用户授权。</p>
 */
@Slf4j
@Component
@ToolPermission(category = OperationCategory.READ, isPathSensitive = true,
        description = "项目知识库（扫描/查询/标注/规则）")
public class ProjectMapTool implements Tool {

    private final ObjectMapper objectMapper;
    private final ProjectMapService mapService;
    private final ProjectContextProvider contextProvider;

    public ProjectMapTool(ObjectMapper objectMapper, ProjectMapService mapService,
                          ProjectContextProvider contextProvider) {
        this.objectMapper = objectMapper;
        this.mapService = mapService;
        this.contextProvider = contextProvider;
    }

    @Override
    public String getName() {
        return "project_map";
    }

    @Override
    public String getDescription() {
        return "【适用场景】目录知识库（.codecraft/）：摸清「什么东西在哪」——代码、文档、配置、脚本、数据表都算；"
                + "也用来沉淀规则与联动约束。\n"
                + "★ 工作目录可能一个代码文件都没有：全是需求文档 / 会议纪要 / 运维手册也很常见"
                + "（例如桌面上的「V2.0需求文档」）。这种情况会自动切成**文档模式**，"
                + "产出「每个文件的标题 + 章节」清单，足够判断该读哪一篇、该改哪一篇。\n"
                + "★ 不限于写代码：运维排查（部署脚本 / 配置项 / 服务编排）、读需求（接口 / 数据表 / 业务规则）、"
                + "补测试（用例在哪、覆盖哪些模块）、写文档（已有文档怎么组织）都能用。\n"
                + "【什么时候该建】满足任一条才考虑 bootstrap：\n"
                + "  - 任务涉及多个目录，需要先搞清楚「东西在哪」（不限代码，文档/脚本/配置同样适用）\n"
                + "  - 目录结构不熟，预计要反复 file_explorer(grep/glob) 才能找到目标\n"
                + "  - 同一任务会持续很多轮，后面还要回头定位别的东西\n"
                + "  - 具体场景：接手运维要弄清部署流程与配置项；读需求要找到对应接口与数据表；"
                + "补测试要先知道现有用例位置与覆盖面；写文档要先看已有文档怎么组织\n"
                + "【什么时候不该建】这些情况直接读文件，不要建库：\n"
                + "  - 用户只让改某个目录下的一个文件，或已明确给出文件路径\n"
                + "  - 只是读某个文件看看内容\n"
                + "  - 可扫描文件少于 8 个的小目录（工具会自动拒绝，除非 force=true）\n"
                + "  - 一次性小改（改一行字、改个配置值）\n"
                + "【action 说明】\n"
                + "  status    — 检查知识库是否存在、是否过期（返回 stale 条目列表）\n"
                + "  bootstrap — 建立或增量更新（扫描文件 → 生成 INDEX.md，代码模式另生成 map/）；"
                + "只重写有变化的模块，无变化时不写任何文件；保留已有 annotate\n"
                + "  read      — 不传 module 读索引；传 module（模块名或文件名）看详情\n"
                + "  annotate  — 给模块/文档写职责与联动约束（支持多行；重建时保留）\n"
                + "  rule      — 项目规则与联动约束：op=add/update/remove/list\n"
                + "【会索引什么】\n"
                + "  - 代码：Java 类/方法、Vue 组件的 props/emits、TS 导出\n"
                + "  - 文档：Markdown 的一二级标题（一眼看出每篇讲什么；文档模式下每篇一行）\n"
                + "  - 配置：YAML/JSON/properties 的顶层键（服务名、配置块）\n"
                + "  - 数据：SQL 的建表/建视图语句\n"
                + "  - 脚本：Shell/bat/ps1 的函数\n"
                + "【注意事项】\n"
                + "  1) 本工具不会自动运行，也不会在后台建目录——只有显式调用 bootstrap 才落盘\n"
                + "  2) 地图是缓存不是真相：与实际情况冲突时以文件为准，发现偏差用 annotate 修正\n"
                + "  3) 建库后每轮会自动注入摘要（代码模式 ≤1100 字符，文档模式 ≤1500 字符），不需要反复 read 全文\n"
                + "  4) rule 的 key 前缀：开发 build./style./arch./avoid./flow./domain./link./risk.；"
                + "运维 ops./deploy./test.；需求与文档 req./doc./know.；技能 skill.\n"
                + "  5) 「改 A 必须同步改 B」这类联动约束优先写进 annotate 或 key=link.*，不必硬造证据\n"
                + "  6) 想让某条约定在加载技能时自动带上：用 key=skill.all.xxx（所有技能）或 "
                + "skill.<技能名或ID>.xxx（仅该技能），会随 skill action=load 一起返回\n"
                + "  7) rule op=update 只改正文时不必重填 evidence：原证据与命中数会自动沿用\n"
                + "  8) 单个文件超过 512KB、或属构建产物/依赖/缓存目录的内容不会被索引";
    }

    @Override
    public JsonNode getParameters() {
        ObjectNode parameters = objectMapper.createObjectNode();
        parameters.put("type", "object");

        ObjectNode properties = objectMapper.createObjectNode();

        ObjectNode action = objectMapper.createObjectNode();
        action.put("type", "string");
        action.put("description", "【必填】操作类型。status=检查状态；bootstrap=建立/增量更新（唯一的写库动作）；"
                + "read=读索引或模块详情；annotate=给模块写职责；rule=项目规则读写。");
        ArrayNode actionEnum = objectMapper.createArrayNode();
        actionEnum.add("status").add("bootstrap").add("read").add("annotate").add("rule");
        action.set("enum", actionEnum);
        properties.set("action", action);

        ObjectNode module = objectMapper.createObjectNode();
        module.put("type", "string");
        module.put("description", "【annotate 必填；read 可选】模块名或文件名。可用 INDEX 里的显示名（如 service、components），"
                + "也可用完整目录路径（如 src/main/java/com/example/agentdeepseek/service）。"
                + "read 不传 module 会返回完整索引（代码模式是模块表、文档模式是文件清单），传了才读单个模块/文档的详情。");
        properties.set("module", module);

        ObjectNode note = objectMapper.createObjectNode();
        note.put("type", "string");
        note.put("description", "【annotate 时必填】模块职责与联动约束，≤600 字符，支持多行（用 \\n 分隔）。"
                + "首行会进 INDEX 与每轮注入摘要，所以要写成一句能独立看懂的话；"
                + "后续行可以写「改这里要同步改 X」「与 Y 模块强耦合」这类联动关系——这类语义没有单一证据行，正常写即可。");
        properties.set("note", note);

        ObjectNode op = objectMapper.createObjectNode();
        op.put("type", "string");
        op.put("description", "【rule 用，默认 list】add=新增（key 已存在会被拒，需用 update）；"
                + "update=覆盖同 key 规则的正文（未填 evidence 时沿用原证据，命中数不清零）；"
                + "remove=删除；list=列出全部规则（不传 op 时的默认行为）。");
        ArrayNode opEnum = objectMapper.createArrayNode();
        opEnum.add("add").add("update").add("remove").add("list");
        op.set("enum", opEnum);
        properties.set("op", op);

        ObjectNode key = objectMapper.createObjectNode();
        key.put("type", "string");
        key.put("description", "【rule 的 add/update/remove 必填】规则标识，格式 前缀.名称。"
                + "前缀白名单：build.（构建/打包）、style.（代码风格）、arch.（架构约束）、avoid.（禁区）、"
                + "flow.（流程约定）、domain.（领域事实）、link.（联动约束：改 A 要同步改 B）、"
                + "know.（领域知识与业务事实）、risk.（已知坑与风险点）、test.（测试）、deploy.（部署）、"
                + "skill.（加载技能时随使用说明一起返回：skill.all.xxx 全技能通用，"
                + "skill.<技能名或ID>.xxx 仅该技能）。"
                + "例：build.cmd、link.settings-density、avoid.raw-md5、skill.all.before-edit。");
        properties.set("key", key);

        ObjectNode text = objectMapper.createObjectNode();
        text.put("type", "string");
        text.put("description", "【rule 的 add/update 必填】规则正文，≤400 字符（约 200 汉字）。"
                + "一条只讲一件事：讲不清就拆成多个 key。联动约束写清「改哪里 → 同步改哪里 → 否则会怎样」。");
        properties.set("text", text);

        ObjectNode evidence = objectMapper.createObjectNode();
        evidence.put("type", "string");
        evidence.put("description", "【可选，强烈建议】可核对的证据来源：文件:行号 / 命令输出片段 / 用户原话。"
                + "注意：联动约束、领域知识这类从代码结构推断出的内容通常没有单一证据行，"
                + "此时**留空即可，不要编造行号**——假证据比没有证据更有害。");
        properties.set("evidence", evidence);

        ObjectNode force = objectMapper.createObjectNode();
        force.put("type", "boolean");
        force.put("description", "【bootstrap 可选，默认 false】false=增量（默认）：只重写有文件变化/新增/详情缺失的模块，"
                + "无变化时一个文件都不写，保留已 annotate 的职责，并清理已消失模块的详情文件；"
                + "true=完全重建：重写全部模块并丢弃已 annotate 的职责描述（项目文件数低于阈值时也会被强制放行）。");
        properties.set("force", force);

        parameters.set("properties", properties);
        ArrayNode required = objectMapper.createArrayNode();
        required.add("action");
        parameters.set("required", required);
        return parameters;
    }

    @Override
    public String execute(JsonNode arguments) {
        String action = text(arguments, "action");
        if (action == null || action.isBlank()) {
            return "action 必填：status / bootstrap / read / annotate / rule";
        }
        Path root;
        try {
            root = Paths.get(ProjectRootContext.get()).toAbsolutePath().normalize();
        } catch (RuntimeException e) {
            return "无法解析项目根目录：" + e.getMessage();
        }
        if (!Files.isDirectory(root)) {
            return "项目根目录不存在：" + root;
        }
        String normalized = action.trim().toLowerCase();
        try {
            return switch (normalized) {
                case "status" -> renderStatus(root);
                case "bootstrap" -> runBootstrap(root, bool(arguments, "force"));
                case "read" -> mapService.read(root, text(arguments, "module"));
                case "annotate" -> {
                    ProjectMapService.WriteResult result =
                            mapService.annotate(root, text(arguments, "module"), text(arguments, "note"));
                    yield (result.ok() ? "✅ " : "⚠️ ") + result.message();
                }
                case "rule" -> mapService.rule(root, text(arguments, "op"), text(arguments, "key"),
                        text(arguments, "text"), text(arguments, "evidence"));
                default -> "不支持的 action：" + action + "（可用 status / bootstrap / read / annotate / rule）";
            };
        } catch (RuntimeException e) {
            log.error("project_map 执行失败: action={}, root={}", normalized, root, e);
            return "执行失败：" + e.getClass().getSimpleName() + ": " + e.getMessage();
        }
    }

    private String renderStatus(Path root) {
        ProjectMapService.StatusResult status = mapService.status(root);
        if (!status.exists()) {
            return "本项目尚未建立知识库（.codecraft/ 不存在）。\n"
                    + "判断是否需要建：\n"
                    + "- 若任务要跨模块定位代码、或预计反复搜索 → 调用 action=bootstrap\n"
                    + "- 若只是改某个已知文件、或只是读一下内容 → 不需要建库，直接用 file_explorer / file_writer";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("知识库存在\n");
        sb.append("- HEAD：").append(status.gitHead() == null ? "（非 Git 仓库）" : shortHead(status.gitHead())).append('\n');
        sb.append("- 生成时间：").append(status.generatedAt()).append('\n');
        sb.append("- 规模：").append(status.fileCount()).append(" 个文件 / ")
                .append(status.moduleCount()).append(" 个模块\n");
        if (status.addedFiles() > 0) {
            sb.append("- 文件数增量：+").append(status.addedFiles()).append('\n');
        }
        List<String> stale = status.staleModules();
        if (stale.isEmpty()) {
            sb.append("- 状态：✅ 无过期模块\n");
        } else {
            sb.append("- 状态：⚠️ 有 ").append(stale.size()).append(" 个模块可能过期\n");
            sb.append("- 过期模块：").append(String.join("、", stale.subList(0, Math.min(12, stale.size()))));
            if (stale.size() > 12) {
                sb.append(" 等");
            }
            sb.append("\n- 建议：先用 action=read module=<模块> 确认内容，确实偏差较大再 action=bootstrap 增量刷新")
                    .append("（地图是缓存不是真相，小偏差以代码为准即可）\n");
        }
        return sb.toString();
    }

    private String runBootstrap(Path root, boolean force) {
        long started = System.currentTimeMillis();
        ProjectMapService.WriteResult result = mapService.bootstrap(root, force);
        long elapsed = System.currentTimeMillis() - started;
        log.info("project_map bootstrap: root={}, force={}, ok={}, 耗时 {}ms",
                root, force, result.ok(), elapsed);
        String message = (result.ok() ? "✅ " : "⚠️ ") + result.message();
        if (result.ok() && !contextProvider.hasKnowledgeBase(root.toString())) {
            // 理论上不会发生，留作自检：写入成功但读不到索引，说明路径不一致
            return message + "\n⚠️ 自检提示：写入后未读到 INDEX.md，请检查工作目录是否为项目根。";
        }
        return message + "\n（耗时 " + elapsed + "ms）";
    }

    private String shortHead(String head) {
        return head.length() > 8 ? head.substring(0, 8) : head;
    }

    private String text(JsonNode arguments, String field) {
        if (arguments == null) {
            return null;
        }
        JsonNode node = arguments.get(field);
        if (node == null || node.isNull()) {
            return null;
        }
        String value = node.asText();
        return value == null || value.isBlank() ? null : value.trim();
    }

    private boolean bool(JsonNode arguments, String field) {
        if (arguments == null) {
            return false;
        }
        JsonNode node = arguments.get(field);
        return node != null && !node.isNull() && node.asBoolean(false);
    }
}
