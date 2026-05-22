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

import com.example.agentdeepseek.tool.permission.OperationCategory;
import com.example.agentdeepseek.tool.permission.ToolPermission;

/**
 * 报告技能执行结果工具
 * AI 在使用技能后调用，用于技能的进化与淘汰
 */
@Slf4j
@Component
@ToolPermission(category = OperationCategory.SKILL, affectsData = true, description = "上报技能执行结果")
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
        return "汇报技能执行结果。每次技能使用后必须调用，系统据此调整技能的置信度，置信度低于 0.1 的技能自动停用。"
                + "【适用场景】使用某个技能完成操作后，无论成功或失败，必须立即调用本工具反馈执行结果。"
                + "系统使用贝叶斯平滑公式自动计算置信度：confidence = (successCount + 1) / (usageCount + 3)。"
                + "初始置信度 50%（0 次使用），每次成功约 +10%~20%，每次失败约 -10%~20%。"
                + "置信度高于 0.8 的技能优先注入，低于 0.1 的技能自动淘汰不再参与匹配。"
                + "【调用时机】技能执行完毕后立即调用，不要延迟或遗漏。"
                + "【建议】如果用户对技能结果直接表示满意或不满，也应调用本工具反馈。"
                + "所有技能通过 manage_skill 工具统一管理（创建/编辑/删除/查看）。";
    }

    @Override
    public JsonNode getParameters() {
        ObjectNode parameters = objectMapper.createObjectNode();
        parameters.put("type", "object");

        ObjectNode properties = objectMapper.createObjectNode();

        ObjectNode skillId = objectMapper.createObjectNode();
        skillId.put("type", "number");
        skillId.put("description", "【必填】要反馈结果的技能 ID（数字）。从 manage_skill action=create 返回结果中获取，"
                + "或先调用 manage_skill action=list 查看技能列表获取 ID。");
        properties.set("skill_id", skillId);

        ObjectNode success = objectMapper.createObjectNode();
        success.put("type", "boolean");
        success.put("description", "【必填】技能执行是否成功（布尔值 true/false）。"
                + "true=目标达成/用户满意（置信度上升），false=未达预期/出错（置信度下降）。"
                + "系统使用贝叶斯平滑公式：confidence = (successCount + 1) / (usageCount + 3)。");
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
            return "【参数缺失】必填参数 skill_id 未提供或为 null。【修正】请传入 manage_skill action=create 返回的 ID，或先调用 manage_skill action=list 查询";
        }
        if (!arguments.has("success") || arguments.path("success").isNull()) {
            return "【参数缺失】必填参数 success 未提供或为 null。【修正】请传入布尔值 true（成功）或 false（失败）";
        }

        long skillId = arguments.path("skill_id").asLong();
        boolean success = arguments.path("success").asBoolean();

        // 校验所有权
        Long userId = ToolContext.getUserId();
        if (userId == null) {
            return "【会话上下文丢失】无法获取当前用户信息。【建议】请重试本次调用，若持续失败请联系管理员检查会话状态";
        }
        try {
            Skill existingSkill = skillService.getSkillById(skillId);
            if (!existingSkill.getUserId().equals(userId)) {
                return "【权限拒绝】技能 ID=" + skillId + " 不属于当前用户，无法报告执行结果。【建议】先用 manage_skill action=list 查询自己可操作的技能列表，确认目标技能 ID 正确";
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
}
