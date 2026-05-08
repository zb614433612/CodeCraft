package com.example.agentdeepseek.tool.impl;

import com.example.agentdeepseek.model.entity.Skill;
import com.example.agentdeepseek.service.SkillService;
import com.example.agentdeepseek.tool.Tool;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 报告技能执行结果工具
 * AI 在使用技能后调用，用于技能的进化与淘汰
 */
@Slf4j
@Component
public class ReportSkillResultTool implements Tool {

    private final ObjectMapper objectMapper;
    private final SkillService skillService;

    public ReportSkillResultTool(ObjectMapper objectMapper, SkillService skillService) {
        this.objectMapper = objectMapper;
        this.skillService = skillService;
    }

    @Override
    public String getName() {
        return "report_skill_result";
    }

    @Override
    public String getDescription() {
        return "报告技能执行结果。在完成技能相关操作后调用此工具反馈执行结果，"
                + "系统会根据反馈调整技能的置信度。也用于记录用户对技能的正负面反馈（+1/-1）。"
                + "成功则 confidence 提升，失败则 confidence 降低。"
                + "当 confidence 低于 0.1 时技能将不再自动生效。";
    }

    @Override
    public JsonNode getParameters() {
        ObjectNode parameters = objectMapper.createObjectNode();
        parameters.put("type", "object");

        ObjectNode properties = objectMapper.createObjectNode();

        ObjectNode skillId = objectMapper.createObjectNode();
        skillId.put("type", "integer");
        skillId.put("description", "技能 ID");
        properties.set("skill_id", skillId);

        ObjectNode success = objectMapper.createObjectNode();
        success.put("type", "boolean");
        success.put("description", "执行是否成功。true=成功(confidence +1)，false=失败(confidence -1)");
        properties.set("success", success);

        parameters.set("properties", properties);
        ArrayNode required = objectMapper.createArrayNode();
        required.add("skill_id");
        required.add("success");
        parameters.set("required", required);

        return parameters;
    }

    @Override
    public String execute(JsonNode arguments) {
        if (!arguments.has("skill_id") || arguments.path("skill_id").isNull()) {
            return "错误：缺少必要参数 skill_id";
        }
        if (!arguments.has("success") || arguments.path("success").isNull()) {
            return "错误：缺少必要参数 success";
        }

        long skillId = arguments.path("skill_id").asLong();
        boolean success = arguments.path("success").asBoolean();

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
}
