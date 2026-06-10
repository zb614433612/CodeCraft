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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 技能管理工具 — 合并 manage_skill + report_skill_result
 * 通过 action 参数区分操作，覆盖技能 CRUD 和结果反馈五大能力。
 */
@Slf4j
@Component
@ToolPermission(category = OperationCategory.SKILL, affectsData = true, description = "技能管理（创建/编辑/删除/查看/反馈）")
public class SkillTool implements Tool {

    private static final double MERGE_THRESHOLD = 0.85;

    private final ObjectMapper objectMapper;
    private final SkillService skillService;
    private final ToolRegistry toolRegistry;
    private final JdbcTemplate jdbcTemplate;

    public SkillTool(ObjectMapper objectMapper, SkillService skillService, ToolRegistry toolRegistry,
                     JdbcTemplate jdbcTemplate) {
        this.objectMapper = objectMapper;
        this.skillService = skillService;
        this.toolRegistry = toolRegistry;
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public String getName() { return "skill"; }

    @Override
    public String getDescription() {
        return "【适用场景】技能管理一站式工具，通过 action 参数选择操作模式。\n"
                + "【action 说明】\n"
                + "  create — 创建新技能\n"
                + "  update — 编辑已有技能\n"
                + "  delete — 删除技能\n"
                + "  list   — 查看技能列表\n"
                + "  report — 反馈技能执行结果（成功/失败），系统据此自动调整置信度\n"
                + "【典型工作流】\n"
                + "  1) list 查看已有技能\n"
                + "  2) create 创建新技能（提供 name/description/tool_names/instructions/trigger_words）\n"
                + "  3) 技能使用完毕后调用 report 反馈结果\n"
                + "【注意事项】\n"
                + "  1) create 时强烈建议提供 trigger_words（触发词）数组，系统据此自动匹配\n"
                + "  2) 每次技能使用后必须调用 action=report，漏报会导致置信度无法更新\n"
                + "  3) 置信度低于 0.1 的技能自动淘汰不再参与匹配";
    }

    @Override
    public JsonNode getParameters() {
        ObjectNode parameters = objectMapper.createObjectNode();
        parameters.put("type", "object");

        ObjectNode properties = objectMapper.createObjectNode();

        // === action（必填） ===
        ObjectNode action = objectMapper.createObjectNode();
        action.put("type", "string");
        action.put("description", "【必填】操作类型。create=创建技能；update=编辑技能；delete=删除技能；list=查看技能列表；report=反馈执行结果。"
                + "各操作所需参数不同，详见各参数字段说明。");
        ArrayNode enumValues = objectMapper.createArrayNode();
        enumValues.add("create").add("update").add("delete").add("list").add("report");
        action.set("enum", enumValues);
        properties.set("action", action);

        // === skill_id（update/delete/report 时必填） ===
        ObjectNode skillId = objectMapper.createObjectNode();
        skillId.put("type", "number");
        skillId.put("description", "【update/delete/report 时必填，create/list 时忽略】技能 ID（数字）。"
                + "从 create 返回结果中获取，或先 action=list 查看已有技能列表从中找到目标技能的 ID。");
        properties.set("skill_id", skillId);

        // === name ===
        ObjectNode name = objectMapper.createObjectNode();
        name.put("type", "string");
        name.put("description", "【create 时必填，update 时可选】技能名称，用简洁的中文短语概括。"
                + "例如：「天气查询技能」「Git代码提交」「数据库问题排查」。");
        properties.set("name", name);

        // === description ===
        ObjectNode description = objectMapper.createObjectNode();
        description.put("type", "string");
        description.put("description", "【create 时必填，update 时可选】技能描述，写清【何时触发】和【解决什么问题】。"
                + "例如：「当用户询问天气、温度等气象信息时触发，自动搜索目标城市的实时天气和预报数据」。");
        properties.set("description", description);

        // === tool_names ===
        ObjectNode toolNames = objectMapper.createObjectNode();
        toolNames.put("type", "array");
        ObjectNode items = objectMapper.createObjectNode();
        items.put("type", "string");
        toolNames.set("items", items);
        toolNames.put("description", "【create 时必填，update 时可选】该技能需要用到的工具名称列表。"
                + "例如 [\"web_search\", \"web_fetch\"]。必须是当前已注册的工具名。");
        properties.set("tool_names", toolNames);

        // === instructions ===
        ObjectNode instructions = objectMapper.createObjectNode();
        instructions.put("type", "string");
        instructions.put("description", "【create 时必填，update 时可选】详细执行步骤与决策逻辑。"
                + "用「第 N 步：」组织层次，写清：何时触发、调用哪个工具、传什么参数、如何判断成功/失败。"
                + "越详细的指令，技能执行成功率越高，置信度积累越快。");
        properties.set("instructions", instructions);

        // === trigger_words ===
        ObjectNode triggerWords = objectMapper.createObjectNode();
        triggerWords.put("type", "array");
        ObjectNode twItems = objectMapper.createObjectNode();
        twItems.put("type", "string");
        triggerWords.set("items", twItems);
        triggerWords.put("description", "【create 时强烈建议提供】触发词/同义词列表。"
                + "用户消息中包含这些词时，系统自动匹配并注入此技能。"
                + "示例：[\"天气\",\"气温\",\"温度\",\"预报\",\"下雨\"]。"
                + "不提供触发词时，技能仅依赖名称和描述匹配，召回率会显著降低。");
        properties.set("trigger_words", triggerWords);

        // === success（仅 report 时有效） ===
        ObjectNode success = objectMapper.createObjectNode();
        success.put("type", "boolean");
        success.put("description", "【report 时必填】技能执行是否成功（布尔值 true/false）。"
                + "true=目标达成/用户满意（置信度上升），false=未达预期/出错（置信度下降）。"
                + "系统使用贝叶斯平滑公式：confidence = (successCount + 1) / (usageCount + 3)。");
        properties.set("success", success);

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
            return "【参数缺失】必填参数 action 未提供。可选值：create、update、delete、list、report。";
        }

        Long userId = ToolContext.getUserId();
        Long agentConfigId = ToolContext.getAgentConfigId();

        return switch (action) {
            case "create" -> handleCreate(arguments, userId, agentConfigId);
            case "update" -> handleUpdate(arguments, userId);
            case "delete" -> handleDelete(arguments, userId);
            case "list"   -> handleList(userId, agentConfigId);
            case "report" -> handleReport(arguments, userId);
            default -> "【无效操作】action=\"" + action + "\" 不是有效操作。"
                    + "可选值：create、update、delete、list、report。";
        };
    }

    // ============================================================
    // create
    // ============================================================

    private String handleCreate(JsonNode args, Long userId, Long agentConfigId) {
        String name = args.path("name").asText();
        String description = args.path("description").asText();
        String instructions = args.path("instructions").asText();
        JsonNode toolNamesNode = args.path("tool_names");
        JsonNode triggerWordsNode = args.path("trigger_words");

        if (name.isEmpty() || description.isEmpty() || instructions.isEmpty() || !toolNamesNode.isArray()) {
            return "【参数缺失】create 操作需要以下必填参数：name、description、tool_names、instructions。"
                    + "缺失项：" + (name.isEmpty() ? " name" : "")
                    + (description.isEmpty() ? " description" : "")
                    + (!toolNamesNode.isArray() ? " tool_names" : "")
                    + (instructions.isEmpty() ? " instructions" : "")
                    + "。请补充后重新调用。";
        }

        if (userId == null) {
            return "【会话上下文丢失】无法获取当前用户信息。请重试，若持续失败请联系管理员。";
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
            return "【工具不存在】以下工具名未注册：" + String.join(", ", invalidTools)
                    + "。请先 action=list 查看可用工具，确认拼写无误后重新调用。";
        }

        String toolNamesStr;
        String triggerWordsStr = null;
        try {
            toolNamesStr = objectMapper.writeValueAsString(toolNamesNode);
            if (triggerWordsNode.isArray()) {
                triggerWordsStr = objectMapper.writeValueAsString(triggerWordsNode);
            }
        } catch (Exception e) {
            return "【格式错误】tool_names 或 trigger_words 不是合法的 JSON 数组。";
        }

        // 检查相似技能
        List<Skill> existingSkills = skillService.listSkills(userId);
        Skill similarSkill = null;
        double highestSimilarity = 0;
        Skill candidate = new Skill(name, description, toolNamesStr, instructions, triggerWordsStr, userId, "custom");
        candidate.setAgentConfigId(agentConfigId);
        for (Skill existing : existingSkills) {
            if (existing.getAgentConfigId() != null && !existing.getAgentConfigId().equals(agentConfigId)) continue;
            double sim = skillService.calculateSimilarity(candidate, existing);
            if (sim > highestSimilarity) {
                highestSimilarity = sim;
                similarSkill = existing;
            }
        }

        Skill skill = skillService.createSkill(name, description, toolNamesStr, instructions, triggerWordsStr, userId, "custom");
        if (agentConfigId != null) {
            skill.setAgentConfigId(agentConfigId);
            jdbcTemplate.update("UPDATE skill SET agent_config_id = ? WHERE id = ?", agentConfigId, skill.getId());
        }

        StringBuilder result = new StringBuilder();
        result.append("技能「").append(name).append("」创建成功 (ID: ").append(skill.getId()).append(")\n");
        result.append("初始置信度：50%。后续每次使用后请调用 skill action=report 反馈结果，系统自动调整。\n");
        result.append("关联工具：").append(toolNamesNode).append("\n");
        if (triggerWordsStr != null) {
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
            return "【参数缺失】update 操作需要 skill_id 参数（要编辑的技能 ID）。请先 action=list 查看技能列表获取目标技能 ID。";
        }
        long skillId = safeLong(args, "skill_id");

        if (userId == null) {
            return "【会话上下文丢失】无法获取当前用户信息。请重试，若持续失败请联系管理员。";
        }

        try {
            Skill existing = skillService.getSkillById(skillId);
            if (!existing.getUserId().equals(userId)) {
                return "【权限拒绝】技能 ID=" + skillId + " 不属于当前用户，无法编辑。请先 action=list 查看自己可操作的技能列表。";
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

        String toolNamesStr = null;
        String triggerWordsStr = null;
        try {
            if (toolNamesNode.isArray()) toolNamesStr = objectMapper.writeValueAsString(toolNamesNode);
            if (triggerWordsNode.isArray()) triggerWordsStr = objectMapper.writeValueAsString(triggerWordsNode);
        } catch (Exception e) {
            return "【格式错误】tool_names 或 trigger_words 不是合法的 JSON 数组。";
        }

        Skill updated = skillService.updateSkill(skillId,
                name.isEmpty() ? null : name,
                description.isEmpty() ? null : description,
                toolNamesStr,
                instructions.isEmpty() ? null : instructions,
                triggerWordsStr,
                userId);

        StringBuilder result = new StringBuilder();
        result.append("技能「").append(updated.getName()).append("」(ID:").append(skillId).append(") 更新成功。");
        if (name != null && !name.isEmpty()) result.append("\n名称 → ").append(name);
        if (description != null && !description.isEmpty()) result.append("\n描述 → ").append(description);
        if (toolNamesStr != null) result.append("\n工具 → ").append(toolNamesNode);
        if (instructions != null && !instructions.isEmpty()) result.append("\n步骤 → ").append(instructions);
        if (triggerWordsStr != null) result.append("\n触发词 → ").append(triggerWordsNode);
        return result.toString();
    }

    // ============================================================
    // delete
    // ============================================================

    private String handleDelete(JsonNode args, Long userId) {
        if (!args.has("skill_id") || args.path("skill_id").isNull()) {
            return "【参数缺失】delete 操作需要 skill_id 参数。请先 action=list 查看技能列表，确认要删除目标的 ID 后重新调用。";
        }
        long skillId = safeLong(args, "skill_id");

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

    private String handleList(Long userId, Long agentConfigId) {
        if (userId == null) {
            return "【会话上下文丢失】无法获取当前用户信息。请重试，若持续失败请联系管理员。";
        }

        List<Skill> skills = skillService.listSkills(userId);
        if (agentConfigId != null) {
            skills = skills.stream()
                    .filter(s -> s.getAgentConfigId() == null || s.getAgentConfigId().equals(agentConfigId))
                    .toList();
        }
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

    // ============================================================
    // report（原 report_skill_result）
    // ============================================================

    private String handleReport(JsonNode args, Long userId) {
        if (!args.has("skill_id") || args.path("skill_id").isNull()) {
            return "【参数缺失】action=report 需要 skill_id 参数。请传入 skill action=create 返回的 ID，或先 skill action=list 查询。";
        }
        if (!args.has("success") || args.path("success").isNull()) {
            return "【参数缺失】action=report 需要 success 参数。请传入布尔值 true（成功）或 false（失败）。";
        }

        long skillId = safeLong(args, "skill_id");
        boolean success = args.path("success").asBoolean();

        if (userId == null) {
            return "【会话上下文丢失】无法获取当前用户信息。请重试，若持续失败请联系管理员。";
        }
        try {
            Skill existingSkill = skillService.getSkillById(skillId);
            if (!existingSkill.getUserId().equals(userId)) {
                return "【权限拒绝】技能 ID=" + skillId + " 不属于当前用户，无法报告执行结果。"
                        + "请先用 skill action=list 查询自己可操作的技能列表，确认目标技能 ID 正确。";
            }
        } catch (IllegalArgumentException e) {
            return "错误：" + e.getMessage();
        }

        try {
            Skill skill = skillService.reportResult(skillId, success);
            String status = success ? "成功" : "失败";
            double confidence = skill.getConfidence();
            String warning = "";
            if (confidence < 0.1) {
                warning = "\n⚠️ 技能「" + skill.getName() + "」置信度已低于 0.1，已不再自动生效。";
            }
            return "技能「" + skill.getName() + "」(ID:" + skillId + ") 执行结果：" + status
                    + "\n当前置信度：" + String.format("%.0f%%", confidence * 100)
                    + "（共使用 " + skill.getUsageCount() + " 次，成功 " + skill.getSuccessCount()
                    + " / 失败 " + skill.getFailCount() + "）" + warning;
        } catch (IllegalArgumentException e) {
            return "错误：" + e.getMessage();
        }
    }

    // ==================== 工具方法 ====================

    private long safeLong(JsonNode args, String key) {
        try {
            return args.get(key).asLong();
        } catch (Exception e) {
            log.warn("skill {}: 解析失败。原始值: {}", key, args.get(key));
            return 0;
        }
    }
}
