package com.example.agentdeepseek.tool.impl;

import com.example.agentdeepseek.model.entity.Skill;
import com.example.agentdeepseek.service.SkillService;
import com.example.agentdeepseek.tool.Tool;
import com.example.agentdeepseek.util.ToolContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 创建技能工具
 * AI 在检测到重复模式时自动创建可复用的技能
 */
@Slf4j
@Component
public class CreateSkillTool implements Tool {

    private static final double MERGE_THRESHOLD = 0.85;

    private final ObjectMapper objectMapper;
    private final SkillService skillService;

    public CreateSkillTool(ObjectMapper objectMapper, SkillService skillService) {
        this.objectMapper = objectMapper;
        this.skillService = skillService;
    }

    @Override
    public String getName() {
        return "create_skill";
    }

    @Override
    public String getDescription() {
        return "创建可复用的技能。当检测到用户有重复性的工作模式时，将一系列工具调用和步骤封装为技能。"
                + "技能创建后会在当前助手中自动生效。如果新技能与已有技能高度相似（>85%），会提示合并建议。"
                + "用户也可以通过对话要求你创建技能。";
    }

    @Override
    public JsonNode getParameters() {
        ObjectNode parameters = objectMapper.createObjectNode();
        parameters.put("type", "object");

        ObjectNode properties = objectMapper.createObjectNode();

        ObjectNode name = objectMapper.createObjectNode();
        name.put("type", "string");
        name.put("description", "技能名称，简洁明了，如「数据库问题排查」「代码提交检查」");
        properties.set("name", name);

        ObjectNode description = objectMapper.createObjectNode();
        description.put("type", "string");
        description.put("description", "技能描述，说明何时使用和能解决什么问题");
        properties.set("description", description);

        ObjectNode toolNames = objectMapper.createObjectNode();
        toolNames.put("type", "array");
        toolNames.put("description", "技能使用的工具名称列表，必须是当前助手中已存在的工具");
        ObjectNode items = objectMapper.createObjectNode();
        items.put("type", "string");
        toolNames.set("items", items);
        properties.set("tool_names", toolNames);

        ObjectNode instructions = objectMapper.createObjectNode();
        instructions.put("type", "string");
        instructions.put("description", "技能执行步骤和指令，描述什么时候使用以及具体执行流程");
        properties.set("instructions", instructions);

        parameters.set("properties", properties);
        ArrayNode required = objectMapper.createArrayNode();
        required.add("name");
        required.add("description");
        required.add("tool_names");
        required.add("instructions");
        parameters.set("required", required);

        return parameters;
    }

    @Override
    public String execute(JsonNode arguments) {
        String name = arguments.path("name").asText();
        String description = arguments.path("description").asText();
        String instructions = arguments.path("instructions").asText();

        JsonNode toolNamesNode = arguments.path("tool_names");
        if (name.isEmpty() || description.isEmpty() || instructions.isEmpty() || !toolNamesNode.isArray()) {
            return "错误：缺少必要参数 name、description、tool_names、instructions";
        }

        String toolNames;
        try {
            toolNames = objectMapper.writeValueAsString(toolNamesNode);
        } catch (Exception e) {
            return "错误：tool_names 格式无效";
        }

        Long userId = ToolContext.getUserId();
        String agentType = ToolContext.getAgentType();
        if (userId == null || agentType == null) {
            return "错误：无法获取用户上下文，请重试";
        }

        // 检查是否存在高度相似的同用户技能
        List<Skill> existingSkills = skillService.listSkills(userId);
        Skill similarSkill = null;
        double highestSimilarity = 0;
        for (Skill existing : existingSkills) {
            if (!agentType.equals(existing.getAgentType())) continue;
            double sim = skillService.calculateSimilarity(
                    new Skill(name, description, toolNames, instructions, userId, agentType),
                    existing);
            if (sim > highestSimilarity) {
                highestSimilarity = sim;
                similarSkill = existing;
            }
        }

        Skill skill = skillService.createSkill(name, description, toolNames, instructions, userId, agentType);

        StringBuilder result = new StringBuilder();
        result.append("技能「").append(name).append("」创建成功 (ID: ").append(skill.getId()).append(")\n");
        result.append("关联工具：").append(toolNamesNode).append("\n");
        result.append("指令：").append(instructions).append("\n");

        if (highestSimilarity > MERGE_THRESHOLD && similarSkill != null) {
            result.append("\n⚠️ 检测到与已有技能「").append(similarSkill.getName())
                    .append("」(ID:").append(similarSkill.getId()).append(") 的相似度达 ")
                    .append(String.format("%.1f%%", highestSimilarity * 100))
                    .append("，建议考虑合并两者或删除冗余技能。");
        }

        return result.toString();
    }
}
