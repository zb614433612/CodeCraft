package com.example.agentdeepseek.tool.impl;

import com.example.agentdeepseek.model.entity.Skill;
import com.example.agentdeepseek.service.SkillService;
import com.example.agentdeepseek.tool.Tool;
import com.example.agentdeepseek.tool.ToolRegistry;
import com.example.agentdeepseek.tool.permission.OperationCategory;
import com.example.agentdeepseek.tool.permission.ToolPermission;
import com.example.agentdeepseek.util.ToolContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 技能管理工具（三合一）
 * 一个工具搞定技能的创建、编辑、删除、查看
 */
@Slf4j
@Component
@ToolPermission(category = OperationCategory.SKILL, affectsData = true, description = "管理技能")
public class ManageSkillTool implements Tool {

    private static final double MERGE_THRESHOLD = 0.85;

    private final ObjectMapper objectMapper;
    private final SkillService skillService;
    private final ToolRegistry toolRegistry;

    public ManageSkillTool(ObjectMapper objectMapper, SkillService skillService, ToolRegistry toolRegistry) {
        this.objectMapper = objectMapper;
        this.skillService = skillService;
        this.toolRegistry = toolRegistry;
    }

    @Override
    public String getName() {
        return "manage_skill";
    }

    @Override
    public String getDescription() {
        return "管理技能。支持创建/编辑/删除/查看，一个工具全搞定。"
                + "【适用场景】当用户多次执行相同流程时创建新技能；技能描述或步骤需要调整时编辑；技能过时或冗余时删除；需要确认当前已有哪些技能时查看列表。"
                + "【使用方式】通过 action 参数指定操作类型：create=创建技能，update=编辑已有技能，delete=删除技能，list=查看技能列表。"
                + "【注意】创建技能时推荐提供 trigger_words（触发词）数组，系统会根据触发词自动将技能匹配给用户问题。"
                + "每次技能使用后必须调用 report_skill_result 反馈执行结果，系统据此自动调整技能置信度，置信度低于 0.1 的技能自动淘汰。";
    }

    @Override
    public JsonNode getParameters() {
        ObjectNode parameters = objectMapper.createObjectNode();
        parameters.put("type", "object");

        ObjectNode properties = objectMapper.createObjectNode();

        // === action（必填） ===
        ObjectNode action = objectMapper.createObjectNode();
        action.put("type", "string");
        action.put("description", "【必填】操作类型：create=创建新技能, update=编辑已有技能, delete=删除技能, list=查看技能列表。"
                + "各操作所需参数不同，详见各参数字段说明中的【create/update/delete 时必填】标记。");
        ArrayNode enumValues = objectMapper.createArrayNode();
        enumValues.add("create").add("update").add("delete").add("list");
        action.set("enum", enumValues);
        properties.set("action", action);

        // === skill_id（update/delete 时必填） ===
        ObjectNode skillId = objectMapper.createObjectNode();
        skillId.put("type", "number");
        skillId.put("description", "【update/delete 时必填，create/list 时忽略】技能 ID（数字）。"
                + "从 create 返回结果中获取，或先 action=list 查看已有技能列表从中找到目标技能的 ID。");
        properties.set("skill_id", skillId);

        // === name ===
        ObjectNode name = objectMapper.createObjectNode();
        name.put("type", "string");
        name.put("description", "【create 时必填，update 时可选】技能名称，用简洁的中文短语概括。"
                + "例如：「天气查询技能」「Git代码提交」「数据库问题排查」「前端构建部署」。"
                + "名称应准确描述该技能解决的问题，方便用户和系统识别。");
        properties.set("name", name);

        // === description ===
        ObjectNode description = objectMapper.createObjectNode();
        description.put("type", "string");
        description.put("description", "【create 时必填，update 时可选】技能描述，用一两句话写清【何时触发】和【解决什么问题】。"
                + "例如：「当用户询问天气、温度、降水等气象信息时触发，自动搜索目标城市的实时天气和预报数据」。"
                + "良好的描述能提高 BM25 匹配精度，让系统更准确地将用户问题路由到正确的技能。");
        properties.set("description", description);

        // === tool_names ===
        ObjectNode toolNames = objectMapper.createObjectNode();
        toolNames.put("type", "array");
        ObjectNode items = objectMapper.createObjectNode();
        items.put("type", "string");
        toolNames.set("items", items);
        toolNames.put("description", "【create 时必填，update 时可选】该技能执行时需要用到的工具名称列表（字符串数组）。"
                + "例如 [\"web_search\", \"web_fetch\"]，或 [\"execute_sql\", \"read_file\"]。"
                + "必须是当前助手中已注册存在的工具。系统会自动将这些工具注入到本轮对话中供你使用。");
        properties.set("tool_names", toolNames);

        // === instructions ===
        ObjectNode instructions = objectMapper.createObjectNode();
        instructions.put("type", "string");
        instructions.put("description", "【create 时必填，update 时可选】详细的执行步骤与决策逻辑。"
                + "用「第 N 步：」或数字编号组织层次，写清：何时触发、调用哪个工具、传什么参数、如何判断成功/失败、失败后怎么处理。"
                + "例如：「第1步：调用 web_search 搜索[城市名+天气]，参数 searchTerm 使用用户提及的城市；"
                + "第2步：若搜索结果为空，用 web_fetch 抓取中央气象台页面；第3步：提取温度、降水概率、风力等关键信息返回给用户」。"
                + "越详细的指令，技能执行成功率越高，置信度积累越快。");
        properties.set("instructions", instructions);

        // === trigger_words ===
        ObjectNode triggerWords = objectMapper.createObjectNode();
        triggerWords.put("type", "array");
        ObjectNode twItems = objectMapper.createObjectNode();
        twItems.put("type", "string");
        triggerWords.set("items", twItems);
        triggerWords.put("description", "【强烈推荐提供】触发词/同义词列表（字符串数组）。"
                + "用户消息中包含这些词时，系统自动匹配并注入此技能，每个命中词为该技能 +3 分。"
                + "应覆盖用户可能说的各种说法（同义词、简称、英文、口语等），越多越全则匹配越精准。"
                + "示例——天气技能：[\"天气\",\"气温\",\"温度\",\"预报\",\"下雨\",\"台风\",\"降雨\",\"降雪\",\"湿度\",\"晴\",\"阴\",\"多云\"]。"
                + "示例——Git技能：[\"提交\",\"commit\",\"推送\",\"push\",\"拉取\",\"pull\",\"分支\",\"branch\",\"合并\",\"merge\",\"PR\"]。"
                + "示例——数据库技能：[\"数据库\",\"SQL\",\"慢查询\",\"连接池\",\"死锁\",\"索引\",\"分库分表\"]。"
                + "不提供触发词时，技能仅依赖名称和描述的 BM25 文本匹配，召回率会显著降低。");
        properties.set("trigger_words", triggerWords);

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
            return "【参数缺失】必填参数 action 未提供。可选值：create（创建）、update（编辑）、delete（删除）、list（查看）。"
                    + "例如 {\"action\":\"list\"} 查看所有技能，{\"action\":\"create\",\"name\":\"天气技能\",...} 创建新技能。";
        }

        Long userId = ToolContext.getUserId();
        String agentType = ToolContext.getAgentType();

        return switch (action) {
            case "create" -> handleCreate(arguments, userId, agentType);
            case "update" -> handleUpdate(arguments, userId);
            case "delete" -> handleDelete(arguments, userId);
            case "list" -> handleList(userId);
            default -> "【无效操作】action=\"" + action + "\" 不是有效操作。"
                    + "可选值：create（创建技能）、update（编辑技能）、delete（删除技能）、list（查看技能列表）。"
                    + "请检查拼写后重试。";
        };
    }

    // ============================================================
    // create
    // ============================================================

    private String handleCreate(JsonNode args, Long userId, String agentType) {
        String name = args.path("name").asText();
        String description = args.path("description").asText();
        String instructions = args.path("instructions").asText();
        JsonNode toolNamesNode = args.path("tool_names");
        JsonNode triggerWordsNode = args.path("trigger_words");

        if (name.isEmpty() || description.isEmpty() || instructions.isEmpty() || !toolNamesNode.isArray()) {
            return "【参数缺失】create 操作需要以下必填参数：name（技能名称）、description（技能描述）、tool_names（工具列表）、instructions（执行步骤）。"
                    + "缺失项：" + (name.isEmpty() ? " name" : "")
                    + (description.isEmpty() ? " description" : "")
                    + (!toolNamesNode.isArray() ? " tool_names" : "")
                    + (instructions.isEmpty() ? " instructions" : "")
                    + "。请补充后重新调用。";
        }

        if (userId == null || agentType == null) {
            return "【会话上下文丢失】无法获取当前用户或助手类型信息。请重试，若持续失败请联系管理员。";
        }

        // 校验工具名
        List<String> invalidTools = new ArrayList<>();
        for (JsonNode tn : toolNamesNode) {
            String toolName = tn.asText();
            if (!toolRegistry.containsTool(toolName)) {
                invalidTools.add(toolName);
            }
        }
        if (!invalidTools.isEmpty()) {
            return "【工具不存在】以下工具名在当前助手中未注册：" + String.join(", ", invalidTools)
                    + "。请先 action=list 查看可用工具，确认拼写无误后重新调用。";
        }

        String toolNames;
        String triggerWords = null;
        try {
            toolNames = objectMapper.writeValueAsString(toolNamesNode);
            if (triggerWordsNode.isArray()) {
                triggerWords = objectMapper.writeValueAsString(triggerWordsNode);
            }
        } catch (Exception e) {
            return "【格式错误】tool_names 或 trigger_words 不是合法的 JSON 数组格式。"
                    + "请传入标准 JSON 字符串数组，例如 [\"tool_a\", \"tool_b\"]。";
        }

        // 检查相似技能
        List<Skill> existingSkills = skillService.listSkills(userId);
        Skill similarSkill = null;
        double highestSimilarity = 0;
        Skill candidate = new Skill(name, description, toolNames, instructions, triggerWords, userId, agentType);
        for (Skill existing : existingSkills) {
            if (!agentType.equals(existing.getAgentType())) continue;
            double sim = skillService.calculateSimilarity(candidate, existing);
            if (sim > highestSimilarity) {
                highestSimilarity = sim;
                similarSkill = existing;
            }
        }

        Skill skill = skillService.createSkill(name, description, toolNames, instructions, triggerWords, userId, agentType);

        StringBuilder result = new StringBuilder();
        result.append("技能「").append(name).append("」创建成功 (ID: ").append(skill.getId()).append(")\n");
        result.append("初始置信度：50%。后续每次使用后请调用 report_skill_result 反馈结果，系统自动调整。\n");
        result.append("关联工具：").append(toolNamesNode).append("\n");
        if (triggerWords != null) {
            result.append("触发词：").append(triggerWordsNode).append("\n");
        }
        result.append("执行步骤：").append(instructions);

        if (highestSimilarity > MERGE_THRESHOLD && similarSkill != null) {
            result.append("\n\n⚠️ 检测到与已有技能「").append(similarSkill.getName())
                    .append("」(ID:").append(similarSkill.getId()).append(") 的相似度达 ")
                    .append(String.format("%.1f%%", highestSimilarity * 100))
                    .append("，建议考虑合并两者或删除冗余技能（action=delete）。");
        }

        return result.toString();
    }

    // ============================================================
    // update
    // ============================================================

    private String handleUpdate(JsonNode args, Long userId) {
        if (!args.has("skill_id") || args.path("skill_id").isNull()) {
            return "【参数缺失】update 操作需要 skill_id 参数（要编辑的技能 ID）。"
                    + "请先 action=list 查看技能列表获取目标技能 ID，再传入更新。";
        }
        long skillId = args.path("skill_id").asLong();

        if (userId == null) {
            return "【会话上下文丢失】无法获取当前用户信息。请重试，若持续失败请联系管理员。";
        }

        try {
            Skill existing = skillService.getSkillById(skillId);
            if (!existing.getUserId().equals(userId)) {
                return "【权限拒绝】技能 ID=" + skillId + " 不属于当前用户，无法编辑。"
                        + "请先 action=list 查看自己可操作的技能列表，确认目标技能 ID 正确。";
            }
        } catch (IllegalArgumentException e) {
            return "错误：" + e.getMessage();
        }

        String name = args.path("name").asText();
        String description = args.path("description").asText();
        String instructions = args.path("instructions").asText();
        JsonNode toolNamesNode = args.path("tool_names");
        JsonNode triggerWordsNode = args.path("trigger_words");

        // 校验工具名
        if (toolNamesNode.isArray()) {
            List<String> invalidTools = new ArrayList<>();
            for (JsonNode tn : toolNamesNode) {
                if (!toolRegistry.containsTool(tn.asText())) {
                    invalidTools.add(tn.asText());
                }
            }
            if (!invalidTools.isEmpty()) {
                return "【工具不存在】以下工具名未注册：" + String.join(", ", invalidTools)
                        + "。请确认工具名拼写后重新调用。";
            }
        }

        String toolNames = null;
        String triggerWords = null;
        try {
            if (toolNamesNode.isArray()) toolNames = objectMapper.writeValueAsString(toolNamesNode);
            if (triggerWordsNode.isArray()) triggerWords = objectMapper.writeValueAsString(triggerWordsNode);
        } catch (Exception e) {
            return "【格式错误】tool_names 或 trigger_words 不是合法的 JSON 数组。";
        }

        Skill updated = skillService.updateSkill(skillId,
                name.isEmpty() ? null : name,
                description.isEmpty() ? null : description,
                toolNames,
                instructions.isEmpty() ? null : instructions,
                triggerWords,
                userId);

        StringBuilder result = new StringBuilder();
        result.append("技能「").append(updated.getName()).append("」(ID:").append(skillId).append(") 更新成功。");
        if (name != null && !name.isEmpty()) result.append("\n名称 → ").append(name);
        if (description != null && !description.isEmpty()) result.append("\n描述 → ").append(description);
        if (toolNames != null) result.append("\n工具 → ").append(toolNamesNode);
        if (instructions != null && !instructions.isEmpty()) result.append("\n步骤 → ").append(instructions);
        if (triggerWords != null) result.append("\n触发词 → ").append(triggerWordsNode);
        return result.toString();
    }

    // ============================================================
    // delete
    // ============================================================

    private String handleDelete(JsonNode args, Long userId) {
        if (!args.has("skill_id") || args.path("skill_id").isNull()) {
            return "【参数缺失】delete 操作需要 skill_id 参数（要删除的技能 ID）。"
                    + "请先 action=list 查看技能列表，确认要删除目标的 ID 后重新调用。";
        }
        long skillId = args.path("skill_id").asLong();

        if (userId == null) {
            return "【会话上下文丢失】无法获取当前用户信息。请重试，若持续失败请联系管理员。";
        }

        try {
            skillService.deleteSkill(skillId, userId);
            return "技能 (ID:" + skillId + ") 已删除。若误删，可用 action=create 重新创建。";
        } catch (IllegalArgumentException | SecurityException e) {
            return "错误：" + e.getMessage();
        }
    }

    // ============================================================
    // list
    // ============================================================

    private String handleList(Long userId) {
        if (userId == null) {
            return "【会话上下文丢失】无法获取当前用户信息。请重试，若持续失败请联系管理员。";
        }

        List<Skill> skills = skillService.listSkills(userId);
        if (skills.isEmpty()) {
            return "当前用户尚无已创建的技能。如果你发现某个多步骤操作被重复执行了 3 次以上，"
                    + "可用 action=create 创建技能以提升后续效率。提供 trigger_words（触发词）能让技能自动匹配用户问题。";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("当前共有 ").append(skills.size()).append(" 个技能：\n\n");
        for (int i = 0; i < skills.size(); i++) {
            Skill s = skills.get(i);
            sb.append(i + 1).append(". ").append(s.getName()).append(" (ID:").append(s.getId()).append(")\n");
            sb.append("   描述：").append(s.getDescription()).append("\n");
            sb.append("   助手类型：").append(s.getAgentType()).append("\n");
            sb.append("   置信度：").append(String.format("%.0f%%", s.getConfidence() * 100));
            if (s.getConfidence() < 0.1) sb.append("（已停用）");
            sb.append("\n");
            sb.append("   使用：").append(s.getUsageCount()).append(" 次（成功 ").append(s.getSuccessCount())
                    .append(" / 失败 ").append(s.getFailCount()).append("）\n");
            if (s.getTriggerWords() != null && !s.getTriggerWords().isBlank() && !"[]".equals(s.getTriggerWords())) {
                sb.append("   触发词：").append(s.getTriggerWords()).append("\n");
            }
            sb.append("   创建时间：").append(s.getCreatedAt()).append("\n");
            if (i < skills.size() - 1) sb.append("\n");
        }
        return sb.toString();
    }
}
