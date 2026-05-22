package com.example.agentdeepseek.service;

import com.example.agentdeepseek.model.entity.Skill;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 触发词倒排索引
 * 维护 trigger_word → Set&lt;skillId&gt; 映射，提供快速匹配
 */
@Slf4j
@Component
public class SkillIndexer {

    private final ObjectMapper objectMapper;
    private final Map<String, Set<Long>> invertedIndex = new ConcurrentHashMap<>();

    public SkillIndexer(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * 批量加载技能到索引
     */
    public void loadSkills(List<Skill> skills) {
        invertedIndex.clear();
        for (Skill skill : skills) {
            List<String> words = parseTriggerWords(skill.getTriggerWords());
            for (String word : words) {
                invertedIndex.computeIfAbsent(word, k -> ConcurrentHashMap.newKeySet()).add(skill.getId());
            }
        }
        log.debug("技能索引已加载: {} 个技能, {} 个触发词", skills.size(), invertedIndex.size());
    }

    /**
     * 检查用户消息命中哪些 trigger_word，返回 skillId → 命中次数
     */
    public Map<Long, Integer> matchTriggerWords(String userMessage) {
        Map<Long, Integer> hitCount = new LinkedHashMap<>();
        if (userMessage == null || userMessage.isEmpty()) return hitCount;
        String msg = userMessage.toLowerCase();
        List<String> matchedWords = new ArrayList<>();

        for (Map.Entry<String, Set<Long>> entry : invertedIndex.entrySet()) {
            String triggerWord = entry.getKey();
            if (msg.contains(triggerWord.toLowerCase())) {
                matchedWords.add(triggerWord);
                for (Long skillId : entry.getValue()) {
                    hitCount.merge(skillId, 1, Integer::sum);
                }
            }
        }
        if (!matchedWords.isEmpty()) {
            log.info("触发词命中: words={}, skills={}", matchedWords, hitCount);
        }
        return hitCount;
    }

    /**
     * 解析 trigger_words JSON 数组
     */
    private List<String> parseTriggerWords(String triggerWordsJson) {
        if (triggerWordsJson == null || triggerWordsJson.isBlank()) return List.of();
        try {
            return objectMapper.readValue(triggerWordsJson, new TypeReference<List<String>>() {});
        } catch (Exception e) {
            log.debug("解析 trigger_words 失败: {}", triggerWordsJson, e);
            return List.of();
        }
    }
}
