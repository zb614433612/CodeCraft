package com.example.agentdeepseek.tool.impl;

import com.example.agentdeepseek.model.entity.Lesson;
import com.example.agentdeepseek.service.lesson.FailureNormalizer;
import com.example.agentdeepseek.service.lesson.LessonService;
import com.example.agentdeepseek.tool.Tool;
import com.example.agentdeepseek.tool.permission.OperationCategory;
import com.example.agentdeepseek.tool.permission.ToolPermission;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 踩坑经验管理工具 — record / search / feedback 三合一
 *
 * 与 skill 的区别：skill 是「常驻匹配的工作流模板」，lesson 是「按需检索的失败经验」。
 * LLM 遇到错误时主动 action=search 查询，应用解法后 action=feedback 反馈结果，
 * 经验库通过验证闭环自动转正/隐藏，平时零上下文开销。
 */
@Slf4j
@Component
@ToolPermission(category = OperationCategory.SKILL, affectsData = true, description = "踩坑经验管理（记录/检索/反馈，成长体系）")
public class LessonTool implements Tool {

    private final ObjectMapper objectMapper;
    private final LessonService lessonService;
    private final FailureNormalizer normalizer;

    public LessonTool(ObjectMapper objectMapper, LessonService lessonService, FailureNormalizer normalizer) {
        this.objectMapper = objectMapper;
        this.lessonService = lessonService;
        this.normalizer = normalizer;
    }

    @Override
    public String getName() { return "lesson"; }

    @Override
    public String getDescription() {
        return "【适用场景】踩坑经验（成长体系）一站式工具：记录失败教训、检索历史经验、补全草稿、反馈验证结果。\n"
                + "【核心价值】经验库按项目隔离、按需检索，不占用上下文；LLM 遇到错误先 search 查经验，"
                + "解决后 complete 补全草稿、feedback 反馈，经验自动验证转正，形成成长闭环。\n"
                + "【action 说明】\n"
                + "  search   — 检索踩坑经验（遇到错误时优先调用！）\n"
                + "  record   — 主动记录一条踩坑经验（LLM 或人工沉淀）\n"
                + "  complete — 补全一条草稿经验的内容（自动捕获的草稿只有现象，成功解决后用此操作提炼「模板+变量」）\n"
                + "  feedback — 反馈经验应用结果（有效/无效），系统据此转正或隐藏经验\n"
                + "  list     — 列出当前项目的经验库（巡检用，按有效/命中排序）\n"
                + "【推荐工作流】\n"
                + "  1) 工具执行失败 → lesson action=search 查经验（按项目+工具+错误码精准过滤）\n"
                + "  2) 命中 → 按「解法+适用条件+关键参数」判断是否适用，应用后 feedback 反馈\n"
                + "  3) 未命中 → 自行解决后，用 action=record 记录；若之前自动捕获过草稿（search 可见），用 action=complete 补全\n"
                + "  4) 新项目/新任务开始 → action=list 巡检经验库，规避已知坑";
    }

    @Override
    public JsonNode getParameters() {
        ObjectNode parameters = objectMapper.createObjectNode();
        parameters.put("type", "object");

        ObjectNode properties = objectMapper.createObjectNode();

        // === action（必填） ===
        ObjectNode action = objectMapper.createObjectNode();
        action.put("type", "string");
        action.put("description", "【必填】操作类型。search=检索经验（遇到错误时优先调用）；record=记录经验；complete=补全草稿；feedback=反馈验证结果；list=巡检经验库。");
        ArrayNode enumValues = objectMapper.createArrayNode();
        enumValues.add("search").add("record").add("complete").add("feedback").add("list");
        action.set("enum", enumValues);
        properties.set("action", action);

        // === 通用 ===
        ObjectNode projectKey = objectMapper.createObjectNode();
        projectKey.put("type", "string");
        projectKey.put("description", "【可选】项目标识（隔离维度）。不传则默认当前项目根目录名，通常无需传。");
        properties.set("project_key", projectKey);

        ObjectNode toolName = objectMapper.createObjectNode();
        toolName.put("type", "string");
        toolName.put("description", "【search/record 时推荐】工具名，如 command / file_writer / mcp_server_manager / execute_sql。");
        properties.set("tool_name", toolName);

        // === search 专用 ===
        ObjectNode errorCode = objectMapper.createObjectNode();
        errorCode.put("type", "string");
        errorCode.put("description", "【search 可选】错误码/异常类名，如 NoClassDefFoundError / ECONNREFUSED / 401 / BUILD FAILURE。");
        properties.set("error_code", errorCode);

        ObjectNode keyword = objectMapper.createObjectNode();
        keyword.put("type", "string");
        keyword.put("description", "【search 可选】关键词（现象/解法/标签模糊匹配），如 编译 依赖 端口。search 至少提供 keyword 或 error_code 之一。");
        properties.set("keyword", keyword);

        // === search 专用（P1）===
        ObjectNode type = objectMapper.createObjectNode();
        type.put("type", "string");
        type.put("description", "【search 可选】经验类型过滤：FAILURE=失败经验 / DETOUR=弯路经验（新任务规划方案前查弯路经验用）/ 不传=全部。");
        properties.set("type", type);

        ObjectNode params = objectMapper.createObjectNode();
        params.put("type", "array");
        ObjectNode paramItems = objectMapper.createObjectNode();
        paramItems.put("type", "object");
        params.set("items", paramItems);
        params.put("description", "【search/record 可选】关键参数数组 [{\"name\":\"缺失依赖\",\"value\":\"starter-web\"}]。"
                + "search 时用于精确过滤：同名参数值不一致的经验会被淘汰（差一个参数就是两条不同的坑）。");
        properties.set("params", params);

        // === record 专用 ===
        ObjectNode global = objectMapper.createObjectNode();
        global.put("type", "boolean");
        global.put("description", "【record 可选，默认 false】true=记录为「全局共享经验」（与项目无关的通用坑，如 mvn 缺依赖/端口被占），"
                + "所有项目检索时兜底可见；false=记录到当前项目（项目特有坑）。");
        properties.set("global", global);

        ObjectNode errorCategory = objectMapper.createObjectNode();
        errorCategory.put("type", "string");
        errorCategory.put("description", "【record 可选】错误类别：COMPILE/DEPENDENCY/NETWORK/AUTH/MCP_HANDSHAKE/SQL/PARAM/ENV/OTHER，不传自动识别。");
        properties.set("error_category", errorCategory);

        ObjectNode symptom = objectMapper.createObjectNode();
        symptom.put("type", "string");
        symptom.put("description", "【record 必填】失败现象（报错信息/现象描述）。");
        properties.set("symptom", symptom);

        ObjectNode rootCause = objectMapper.createObjectNode();
        rootCause.put("type", "string");
        rootCause.put("description", "【record 可选】根因分析。");
        properties.set("root_cause", rootCause);

        ObjectNode solution = objectMapper.createObjectNode();
        solution.put("type", "string");
        solution.put("description", "【record 必填】解法。强烈建议提炼成「模板+变量」：用 {变量名} 占位，"
                + "如「在 pom.xml 添加 {缺失依赖} 后重新编译」，并把变量填进 params。");
        properties.set("solution", solution);

        ObjectNode applicableCond = objectMapper.createObjectNode();
        applicableCond.put("type", "string");
        applicableCond.put("description", "【record 可选】适用条件（何时才适用，何时不适用），如「仅当 pom.xml 中不存在 {缺失依赖} 时」。");
        properties.set("applicable_cond", applicableCond);

        ObjectNode keywords = objectMapper.createObjectNode();
        keywords.put("type", "string");
        keywords.put("description", "【record 可选】标签/关键词，空格分隔，用于模糊检索兜底。");
        properties.set("keywords", keywords);

        // === feedback 专用 ===
        ObjectNode lessonId = objectMapper.createObjectNode();
        lessonId.put("type", "number");
        lessonId.put("description", "【feedback 必填】经验 ID（search 返回结果中的 ID）。");
        properties.set("lesson_id", lessonId);

        ObjectNode effective = objectMapper.createObjectNode();
        effective.put("type", "boolean");
        effective.put("description", "【feedback 必填】经验应用结果：true=按解法操作后成功了；false=按解法操作后仍失败。");
        properties.set("effective", effective);

        // === complete / list 通用 ===
        ObjectNode limit = objectMapper.createObjectNode();
        limit.put("type", "number");
        limit.put("description", "【list 可选】返回条数上限，默认 10，最大 20。");
        properties.set("limit", limit);

        parameters.set("properties", properties);
        ArrayNode required = objectMapper.createArrayNode();
        required.add("action");
        parameters.set("required", required);

        return parameters;
    }

    @Override
    public String execute(JsonNode arguments) {
        String action = arguments.path("action").asText();
        if (action.isEmpty()) {
            return "【参数缺失】必填参数 action 未提供。可选值：search、record、complete、feedback、list。";
        }
        return switch (action) {
            case "search" -> handleSearch(arguments);
            case "record" -> handleRecord(arguments);
            case "complete" -> handleComplete(arguments);
            case "feedback" -> handleFeedback(arguments);
            case "list" -> handleList(arguments);
            default -> "【无效操作】action=\"" + action + "\" 不是有效操作。可选值：search、record、complete、feedback、list。";
        };
    }

    // ============================================================
    // search：三级确定性漏斗检索
    // ============================================================

    private String handleSearch(JsonNode args) {
        String keyword = args.path("keyword").asText("");
        String errorCode = args.path("error_code").asText("");
        String toolName = args.path("tool_name").asText("");
        if (keyword.isBlank() && errorCode.isBlank() && toolName.isBlank()) {
            return "【参数不足】search 至少需要提供 keyword（关键词）或 error_code（错误码）之一，"
                    + "建议同时传 tool_name（工具名）提高精度。示例：\n"
                    + "  lesson action=search tool_name=command error_code=NoClassDefFoundError\n"
                    + "  lesson action=search keyword=\"编译 依赖\"\n"
                    + "  lesson action=search type=detour keyword=\"登录 改造\"（P1：新任务规划方案前查弯路经验）";
        }

        String projectKey = resolveProjectKey(args);
        String paramsJson = args.has("params") ? args.path("params").toString() : null;
        String type = args.path("type").asText("");
        return lessonService.searchLessons(projectKey, toolName, errorCode, keyword, paramsJson, type);
    }

    // ============================================================
    // record：主动记录经验（提炼「模板+变量」）
    // ============================================================

    private String handleRecord(JsonNode args) {
        String symptom = args.path("symptom").asText();
        String solution = args.path("solution").asText();
        if (symptom.isBlank() || solution.isBlank()) {
            return "【参数缺失】record 需要必填参数：symptom（失败现象）、solution（解法）。"
                    + "建议把解法提炼成「模板+变量」：如「在 pom.xml 添加 {缺失依赖} 后重新编译」，"
                    + "并将变量填入 params：[{\"name\":\"缺失依赖\",\"value\":\"starter-web\"}]。";
        }

        String projectKey = resolveProjectKey(args);
        // F6：global=true → 记录到全局共享池（通用坑），所有项目检索兜底可见
        if (args.path("global").asBoolean(false)) {
            projectKey = Lesson.GLOBAL_PROJECT_KEY;
        }
        String toolName = args.path("tool_name").asText("");
        String errorCategory = args.path("error_category").asText("");
        String errorCode = args.path("error_code").asText("");
        String rootCause = args.path("root_cause").asText("");
        String applicableCond = args.path("applicable_cond").asText("");
        String keywords = args.path("keywords").asText("");
        String paramsJson = args.has("params") ? args.path("params").toString() : null;

        LessonService.RecordResult recordResult = lessonService.recordLessonWithResult(
                projectKey, toolName, errorCategory, errorCode,
                symptom, rootCause, solution, paramsJson, applicableCond, keywords,
                Lesson.SOURCE_LLM, null, null);
        Lesson lesson = recordResult.lesson;

        StringBuilder sb = new StringBuilder();
        if (recordResult.created) {
            sb.append("✅ 新经验已入库\n");
        } else if (recordResult.merged) {
            sb.append("🔀 同坑已存在但无解法，已用本次记录补全旧草稿（解法合并）\n");
        } else {
            sb.append("⚠️ 该经验已存在且已有解法（同坑去重命中，仅命中计数 +1，未新增/未覆盖）\n");
        }
        sb.append("踩坑经验 (ID:").append(lesson.getId()).append(")\n");
        sb.append("  项目: ").append(lesson.getProjectKey()).append("\n");
        sb.append("  工具: ").append(lesson.getToolName()).append("\n");
        sb.append("  类别: [").append(lesson.getErrorCategory()).append("] ")
                .append(lesson.getErrorCode()).append("\n");
        if (paramsJson != null && !paramsJson.isBlank() && !"[]".equals(paramsJson)) {
            sb.append("  关键参数: ").append(paramsJson).append("\n");
        }
        sb.append("  状态: ").append(lesson.getStatus() == Lesson.STATUS_ACTIVE ? "有效"
                : lesson.getStatus() == Lesson.STATUS_HIDDEN ? "隐藏" : "草稿")
                .append("（被有效复用 2 次后自动转正，后续检索优先展示）\n");
        sb.append("  验证: 后续遇到相同错误时，检索命中后请 feedback 反馈结果");
        return sb.toString();
    }

    // ============================================================
    // feedback：反馈验证结果，驱动转正/隐藏闭环
    // ============================================================

    private String handleFeedback(JsonNode args) {
        if (!args.has("lesson_id") || args.path("lesson_id").isNull()) {
            return "【参数缺失】feedback 需要 lesson_id 参数（search 返回结果中的经验 ID）。";
        }
        if (!args.has("effective") || args.path("effective").isNull()) {
            return "【参数缺失】feedback 需要 effective 参数（布尔值：true=按解法操作成功，false=仍失败）。";
        }
        long lessonId = args.path("lesson_id").asLong();
        boolean effective = args.path("effective").asBoolean();
        return lessonService.feedbackLesson(lessonId, effective);
    }

    // ============================================================
    // complete：补全草稿经验（LLM 模板提炼）
    // ============================================================

    private String handleComplete(JsonNode args) {
        if (!args.has("lesson_id") || args.path("lesson_id").isNull()) {
            return "【参数缺失】complete 需要 lesson_id 参数（search 返回结果中的经验 ID）。";
        }
        long lessonId = args.path("lesson_id").asLong();
        String rootCause = args.path("root_cause").asText("");
        String solution = args.path("solution").asText("");
        String applicableCond = args.path("applicable_cond").asText("");
        String keywords = args.path("keywords").asText("");
        String paramsJson = args.has("params") ? args.path("params").toString() : null;

        return lessonService.completeLesson(lessonId,
                rootCause.isBlank() ? null : rootCause,
                solution.isBlank() ? null : solution,
                paramsJson,
                applicableCond.isBlank() ? null : applicableCond,
                keywords.isBlank() ? null : keywords);
    }

    // ============================================================
    // list：列出项目经验库（巡检）
    // ============================================================

    private String handleList(JsonNode args) {
        String projectKey = resolveProjectKey(args);
        int limit = args.path("limit").asInt(10);
        return lessonService.listLessons(projectKey, limit);
    }

    // ============================================================
    // 工具方法
    // ============================================================

    private String resolveProjectKey(JsonNode args) {
        String projectKey = args.path("project_key").asText("");
        return projectKey.isBlank() ? normalizer.extractProjectKey() : projectKey;
    }
}
