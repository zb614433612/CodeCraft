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
 * 列出技能工具
 */
@Slf4j
@Component
public class ListSkillsTool implements Tool {

    private final ObjectMapper objectMapper;
    private final SkillService skillService;

    public ListSkillsTool(ObjectMapper objectMapper, SkillService skillService) {
        this.objectMapper = objectMapper;
        this.skillService = skillService;
    }

    @Override
    public String getName() {
        return "list_skills";
    }

    @Override
    public String getDescription() {
        return "列出当前用户的所有技能，包含名称、描述、置信度、使用次数等信息。可用于查看已有的技能。";
    }

    @Override
    public JsonNode getParameters() {
        ObjectNode parameters = objectMapper.createObjectNode();
        parameters.put("type", "object");
        parameters.set("properties", objectMapper.createObjectNode());
        return parameters;
    }

    @Override
    public String execute(JsonNode arguments) {
        Long userId = ToolContext.getUserId();
        if (userId == null) {
            return "错误：无法获取用户上下文";
        }

        List<Skill> skills = skillService.listSkills(userId);
        if (skills.isEmpty()) {
            return "当前没有已创建的技能。你可以根据用户的重复性工作模式创建技能来提高效率。";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("当前共有 ").append(skills.size()).append(" 个技能：\n\n");
        for (int i = 0; i < skills.size(); i++) {
            Skill s = skills.get(i);
            sb.append(i + 1).append(". ").append(s.getName()).append(" (ID:").append(s.getId()).append(")\n");
            sb.append("   描述：").append(s.getDescription()).append("\n");
            sb.append("   助手类型：").append(s.getAgentType()).append("\n");
            sb.append("   置信度：").append(String.format("%.0f%%", s.getConfidence() * 100)).append("\n");
            sb.append("   使用次数：").append(s.getUsageCount()).append("（成功 ").append(s.getSuccessCount())
                    .append(" / 失败 ").append(s.getFailCount()).append("）\n");
            sb.append("   创建时间：").append(s.getCreatedAt()).append("\n");
            if (i < skills.size() - 1) sb.append("\n");
        }
        return sb.toString();
    }
}
