package com.example.agentdeepseek.controller;

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
@Tag(name = "技能管理", description = "Skill 技能的查看和删除")
public class SkillController {

    private final SkillService skillService;

    public SkillController(SkillService skillService) {
        this.skillService = skillService;
    }

    @GetMapping
    @Operation(summary = "获取用户技能列表")
    public List<Skill> listSkills(@RequestParam Long userId) {
        return skillService.listSkills(userId);
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "删除技能")
    public Map<String, Object> deleteSkill(@PathVariable Long id, @RequestParam Long userId) {
        Map<String, Object> result = new HashMap<>();
        try {
            skillService.deleteSkill(id, userId);
            result.put("success", true);
            result.put("message", "技能已删除");
        } catch (Exception e) {
            result.put("success", false);
            result.put("message", e.getMessage());
        }
        return result;
    }
}
