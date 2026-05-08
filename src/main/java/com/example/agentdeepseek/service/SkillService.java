package com.example.agentdeepseek.service;

import com.example.agentdeepseek.model.entity.Skill;

import java.util.List;

/**
 * 技能服务接口
 */
public interface SkillService {

    Skill createSkill(String name, String description, String toolNames, String instructions,
                      Long userId, String agentType);

    void deleteSkill(Long skillId, Long userId);

    List<Skill> listSkills(Long userId);

    List<Skill> listActiveSkills(Long userId, String agentType);

    Skill getSkillById(Long skillId);

    Skill reportResult(Long skillId, boolean success);

    /**
     * 检查两个技能的相似度
     * @return 相似度 0.0-1.0
     */
    double calculateSimilarity(Skill a, Skill b);
}
