package com.example.agentdeepseek.tool.impl;

import com.example.agentdeepseek.service.SkillService;
import com.example.agentdeepseek.tool.Tool;
import com.example.agentdeepseek.util.ToolContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 删除技能工具
 */
@Slf4j
@Component
public class DeleteSkillTool implements Tool {

    private final ObjectMapper objectMapper;
    private final SkillService skillService;

    public DeleteSkillTool(ObjectMapper objectMapper, SkillService skillService) {
        this.objectMapper = objectMapper;
        this.skillService = skillService;
    }

    @Override
    public String getName() {
        return "delete_skill";
    }

    @Override
    public String getDescription() {
        return "删除一个已有的技能。需要提供技能 ID。删除不可恢复。";
    }

    @Override
    public JsonNode getParameters() {
        ObjectNode parameters = objectMapper.createObjectNode();
        parameters.put("type", "object");

        ObjectNode properties = objectMapper.createObjectNode();

        ObjectNode skillId = objectMapper.createObjectNode();
        skillId.put("type", "integer");
        skillId.put("description", "要删除的技能 ID");
        properties.set("skill_id", skillId);

        parameters.set("properties", properties);
        ArrayNode required = objectMapper.createArrayNode();
        required.add("skill_id");
        parameters.set("required", required);

        return parameters;
    }

    @Override
    public String execute(JsonNode arguments) {
        if (!arguments.has("skill_id") || arguments.path("skill_id").isNull()) {
            return "错误：缺少必要参数 skill_id";
        }
        long skillId = arguments.path("skill_id").asLong();
        Long userId = ToolContext.getUserId();
        if (userId == null) {
            return "错误：无法获取用户上下文";
        }

        try {
            skillService.deleteSkill(skillId, userId);
            return "技能 (ID:" + skillId + ") 已删除";
        } catch (IllegalArgumentException e) {
            return "错误：" + e.getMessage();
        } catch (SecurityException e) {
            return "错误：" + e.getMessage();
        }
    }
}
