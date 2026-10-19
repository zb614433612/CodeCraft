package com.example.agentdeepseek.controller;

import com.example.agentdeepseek.common.response.ApiResponse;
import com.example.agentdeepseek.model.entity.Skill;
import com.example.agentdeepseek.service.SkillService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 技能管理 REST 控制器
 * 供前端查看和删除技能
 */
@Slf4j
@RestController
@RequestMapping("/api/skills")
@Tag(name = "技能管理", description = "Skill 技能的查看、创建、编辑、删除")
public class SkillController {

    /** 默认助手类型：`skill.agent_type` 为 NOT NULL 列，前端不传时用它兜底 */
    private static final String DEFAULT_AGENT_TYPE = "code_assistant";

    private final SkillService skillService;
    private final org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;

    public SkillController(SkillService skillService, org.springframework.jdbc.core.JdbcTemplate jdbcTemplate) {
        this.skillService = skillService;
        this.jdbcTemplate = jdbcTemplate;
    }

    @GetMapping
    @Operation(summary = "获取技能列表")
    public ApiResponse<List<Skill>> listSkills(@RequestParam Long userId,
                                   @RequestParam(required = false) Long agentConfigId) {
        List<Skill> skills = skillService.listSkills(userId);
        if (agentConfigId != null) {
            skills = skills.stream()
                .filter(s -> s.getAgentConfigId() == null || s.getAgentConfigId().equals(agentConfigId))
                .collect(java.util.stream.Collectors.toList());
        }
        return ApiResponse.success(skills);
    }

    @PostMapping
    @Operation(summary = "创建技能")
    public ApiResponse<Skill> createSkill(@RequestBody Skill skill) {
        try {
            Skill created = skillService.createSkill(
                skill.getName(), skill.getDescription(),
                skill.getToolNames(), skill.getInstructions(),
                skill.getTriggerWords(), skill.getUserId(),
                // agent_type 是 NOT NULL 列（schema 注释值 code_assistant），
                // 前端管理页不关心该内部字段，缺省时兜底为默认助手类型，否则插入直接违反非空约束
                StringUtils.hasText(skill.getAgentType()) ? skill.getAgentType() : DEFAULT_AGENT_TYPE);
            // 作用域：仅 agentConfigId > 0 才绑定到具体 Agent；0 / null 表示全局（保持 NULL）
            if (skill.getAgentConfigId() != null && skill.getAgentConfigId() > 0) {
                created.setAgentConfigId(skill.getAgentConfigId());
                jdbcTemplate.update("UPDATE skill SET agent_config_id = ? WHERE id = ?",
                        skill.getAgentConfigId(), created.getId());
            }
            return ApiResponse.success(created, "技能创建成功");
        } catch (Exception e) {
            return ApiResponse.error(400, e.getMessage());
        }
    }

    @PutMapping("/{id}")
    @Operation(summary = "更新技能")
    public ApiResponse<Skill> updateSkill(@PathVariable Long id, @RequestBody Skill skill) {
        try {
            Skill updated = skillService.updateSkill(id, skill.getName(), skill.getDescription(),
                skill.getToolNames(), skill.getInstructions(), skill.getTriggerWords(), skill.getUserId());
            // 作用域（agent_config_id）不在 SkillMapper.update 的更新列内，这里沿用 createSkill 的补写方式。
            // 约定：agentConfigId > 0 → 绑定到该 Agent；= 0 → 改为全局（NULL）；传 null → 保持不变。
            Long agentConfigId = skill.getAgentConfigId();
            if (agentConfigId != null) {
                if (agentConfigId > 0) {
                    jdbcTemplate.update("UPDATE skill SET agent_config_id = ? WHERE id = ?", agentConfigId, id);
                    updated.setAgentConfigId(agentConfigId);
                } else {
                    jdbcTemplate.update("UPDATE skill SET agent_config_id = NULL WHERE id = ?", id);
                    updated.setAgentConfigId(null);
                }
            }
            return ApiResponse.success(updated, "技能更新成功");
        } catch (Exception e) {
            return ApiResponse.error(400, e.getMessage());
        }
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "删除技能")
    public ApiResponse<Void> deleteSkill(@PathVariable Long id, @RequestParam Long userId) {
        try {
            skillService.deleteSkill(id, userId);
            return ApiResponse.success(null, "技能已删除");
        } catch (Exception e) {
            return ApiResponse.error(400, e.getMessage());
        }
    }
}
