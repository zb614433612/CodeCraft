package com.example.agentdeepseek.service.impl;

import com.example.agentdeepseek.mapper.SkillMapper;
import com.example.agentdeepseek.model.entity.Skill;
import com.example.agentdeepseek.service.SkillService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * 技能服务实现
 */
@Slf4j
@Service
public class SkillServiceImpl implements SkillService {

    private final SkillMapper skillMapper;
    private final ObjectMapper objectMapper;

    public SkillServiceImpl(SkillMapper skillMapper, ObjectMapper objectMapper) {
        this.skillMapper = skillMapper;
        this.objectMapper = objectMapper;
    }

    @Override
    public Skill createSkill(String name, String description, String toolNames, String instructions,
                             String triggerWords, Long userId, String agentType) {
        Skill skill = new Skill(name, description, toolNames, instructions, triggerWords, userId, agentType);
        skill.setCreatedAt(LocalDateTime.now());
        skill.setUpdatedAt(LocalDateTime.now());
        skillMapper.insert(skill);
        log.info("用户 {} 创建技能: {} (ID={}), 关联工具: {}, 触发词: {}", userId, name, skill.getId(), toolNames, triggerWords);
        return skill;
    }

    @Override
    public Skill updateSkill(Long skillId, String name, String description, String toolNames,
                             String instructions, String triggerWords, Long userId) {
        Skill skill = skillMapper.selectById(skillId).orElse(null);
        if (skill == null) {
            throw new IllegalArgumentException("技能不存在: " + skillId);
        }
        if (!skill.getUserId().equals(userId)) {
            throw new SecurityException("无权修改其他用户的技能");
        }
        if (name != null) skill.setName(name);
        if (description != null) skill.setDescription(description);
        if (toolNames != null) skill.setToolNames(toolNames);
        if (instructions != null) skill.setInstructions(instructions);
        if (triggerWords != null) skill.setTriggerWords(triggerWords);
        skill.setUpdatedAt(LocalDateTime.now());
        skillMapper.update(skill);
        log.info("用户 {} 更新技能: {} (ID={})", userId, skill.getName(), skillId);
        return skill;
    }

    @Override
    public void deleteSkill(Long skillId, Long userId) {
        Skill skill = skillMapper.selectById(skillId).orElse(null);
        if (skill == null) {
            throw new IllegalArgumentException("技能不存在: " + skillId);
        }
        if (!skill.getUserId().equals(userId)) {
            throw new SecurityException("无权删除其他用户的技能");
        }
        skillMapper.delete(skillId);
        log.info("用户 {} 删除技能: {} (ID={})", userId, skill.getName(), skillId);
    }

    @Override
    public List<Skill> listSkills(Long userId) {
        return skillMapper.selectByUserId(userId);
    }

    @Override
    public List<Skill> listActiveSkills(Long userId, String agentType) {
        return skillMapper.selectActiveByUserAndAgent(userId, agentType);
    }

    @Override
    public Skill getSkillById(Long skillId) {
        return skillMapper.selectById(skillId)
                .orElseThrow(() -> new IllegalArgumentException("技能不存在: " + skillId));
    }

    @Override
    public Skill reportResult(Long skillId, boolean success) {
        int successInc = success ? 1 : 0;
        int failInc = success ? 0 : 1;
        skillMapper.atomicReportResult(skillId, successInc, failInc);
        Skill skill = getSkillById(skillId);
        log.debug("技能 {} 执行结果: success={}, 置信度={}", skillId, success, skill.getConfidence());
        return skill;
    }

    @Override
    public double calculateSimilarity(Skill a, Skill b) {
        String textA = (a.getName() + " " + a.getDescription()).toLowerCase();
        String textB = (b.getName() + " " + b.getDescription()).toLowerCase();
        double textSimilarity = jaccardSimilarity(textA, textB);
        double toolOverlap = calculateToolOverlap(a.getToolNames(), b.getToolNames());
        // 文本相似度占 70%，工具重叠占 30%
        return Math.min(1.0, textSimilarity * 0.7 + toolOverlap * 0.3);
    }

    /**
     * 字符级二元组 Jaccard 相似度
     */
    private double jaccardSimilarity(String s1, String s2) {
        Set<String> bigrams1 = getBigrams(s1);
        Set<String> bigrams2 = getBigrams(s2);
        if (bigrams1.isEmpty() && bigrams2.isEmpty()) return 1.0;
        Set<String> intersection = new HashSet<>(bigrams1);
        intersection.retainAll(bigrams2);
        Set<String> union = new HashSet<>(bigrams1);
        union.addAll(bigrams2);
        return (double) intersection.size() / union.size();
    }

    private double calculateToolOverlap(String toolNamesA, String toolNamesB) {
        try {
            Set<String> toolsA = objectMapper.readValue(toolNamesA, Set.class);
            Set<String> toolsB = objectMapper.readValue(toolNamesB, Set.class);
            if (toolsA.isEmpty() && toolsB.isEmpty()) return 0;
            Set<String> intersection = new HashSet<>(toolsA);
            intersection.retainAll(toolsB);
            Set<String> union = new HashSet<>(toolsA);
            union.addAll(toolsB);
            return (double) intersection.size() / union.size();
        } catch (Exception e) {
            return 0;
        }
    }

    private Set<String> getBigrams(String s) {
        Set<String> bigrams = new HashSet<>();
        if (s == null || s.isEmpty()) return bigrams;
        for (int i = 0; i < s.length() - 1; i++) {
            bigrams.add(s.substring(i, i + 2));
        }
        return bigrams;
    }
}
