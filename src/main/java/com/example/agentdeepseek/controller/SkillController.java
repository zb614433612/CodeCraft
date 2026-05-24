package com.example.agentdeepseek.controller;

import com.example.agentdeepseek.common.response.ApiResponse;
import com.example.agentdeepseek.model.entity.Skill;
import com.example.agentdeepseek.service.SkillService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
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
                skill.getTriggerWords(), skill.getUserId(), skill.getAgentType());
            if (skill.getAgentConfigId() != null) {
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
