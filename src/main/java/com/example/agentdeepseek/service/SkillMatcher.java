package com.example.agentdeepseek.service;

import com.example.agentdeepseek.model.entity.Skill;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 技能自动匹配引擎
 * BM25 + Trigger Word 双路匹配，Top-3 截断注入
 */
@Slf4j
@Component
public class SkillMatcher {

    private final SkillIndexer skillIndexer;

    private static final double BM25_WEIGHT = 0.4;
    private static final double TRIGGER_WEIGHT = 0.6;
    private static final double TRIGGER_HIT_SCORE = 3.0;
    private static final double K1 = 1.5;
    private static final double B = 0.75;
    private static final int TOP_K = 3;
    private static final double FALLBACK_THRESHOLD = 1.0;

    public SkillMatcher(SkillIndexer skillIndexer) {
        this.skillIndexer = skillIndexer;
    }

    /**
     * 匹配技能：根据用户消息从活跃技能中筛选 Top-3
     * @param userMessage 用户原始消息
     * @param activeSkills 当前用户+助手的活跃技能列表（confidence >= 0.1）
     * @return 按置信度降序排列的 Top-3 匹配技能
     */
    public List<Skill> match(String userMessage, List<Skill> activeSkills) {
        if (activeSkills == null || activeSkills.isEmpty()) return List.of();
        if (userMessage == null || userMessage.isBlank()) {
            // 无用户消息时按置信度取 Top-3
            return activeSkills.stream()
                    .sorted(Comparator.comparingDouble(Skill::getConfidence).reversed())
                    .limit(3)
                    .collect(Collectors.toList());
        }

        // 加载触发词索引
        skillIndexer.loadSkills(activeSkills);

        // 计算 BM25 分数
        Map<Long, Double> bm25Scores = computeBM25Scores(userMessage, activeSkills);

        // 计算 Trigger Word 命中
        Map<Long, Integer> triggerHits = skillIndexer.matchTriggerWords(userMessage);

        // 合并得分
        Map<Long, Double> finalScores = new LinkedHashMap<>();
        for (Skill skill : activeSkills) {
            double bm25 = bm25Scores.getOrDefault(skill.getId(), 0.0);
            int hits = triggerHits.getOrDefault(skill.getId(), 0);
            double score = bm25 * BM25_WEIGHT + hits * TRIGGER_HIT_SCORE * TRIGGER_WEIGHT;
            finalScores.put(skill.getId(), score);
        }

        // Top-5 截断
        List<Skill> topSkills = finalScores.entrySet().stream()
                .sorted(Map.Entry.<Long, Double>comparingByValue().reversed())
                .limit(TOP_K)
                .map(e -> findSkill(activeSkills, e.getKey()))
                .filter(Objects::nonNull)
                .collect(Collectors.toList());

        // 保底：若最高分 < 1.0，按置信度再取 Top-3 保底
        double maxScore = finalScores.values().stream().max(Double::compareTo).orElse(0.0);
        if (maxScore < FALLBACK_THRESHOLD) {
            List<Skill> fallback = activeSkills.stream()
                    .sorted(Comparator.comparingDouble(Skill::getConfidence).reversed())
                    .limit(3)
                    .collect(Collectors.toList());
            // 合并去重
            Set<Long> existingIds = topSkills.stream().map(Skill::getId).collect(Collectors.toSet());
            for (Skill s : fallback) {
                if (!existingIds.contains(s.getId())) {
                    topSkills.add(s);
                }
            }
        }

        // 按置信度降序排序
        topSkills.sort(Comparator.comparingDouble(Skill::getConfidence).reversed());

        String shortQuery = userMessage.length() > 50 ? userMessage.substring(0, 50) + "..." : userMessage;
        if (topSkills.isEmpty()) {
            log.info("技能匹配: query=\"{}\" → 无匹配技能 (活跃技能数={})", shortQuery, activeSkills.size());
        } else {
            log.info("技能匹配: query=\"{}\" → 命中 {} 个: {}",
                    shortQuery, topSkills.size(),
                    topSkills.stream().map(s -> s.getName() + "(score=" + String.format("%.2f", finalScores.getOrDefault(s.getId(), 0.0))
                            + ", conf=" + String.format("%.0f%%", s.getConfidence() * 100) + ")")
                            .collect(Collectors.joining(", ")));
        }

        return topSkills;
    }

    // ============================================================
    // BM25 评分
    // ============================================================

    private Map<Long, Double> computeBM25Scores(String query, List<Skill> skills) {
        Map<Long, Double> scores = new LinkedHashMap<>();
        List<String> queryTokens = tokenize(query);
        if (queryTokens.isEmpty()) return scores;

        int N = skills.size();

        // 计算平均文档长度
        double avgDocLen = skills.stream()
                .mapToInt(s -> tokenize(s.getName() + " " + s.getDescription()).size())
                .average().orElse(1.0);

        // 计算 IDF（每个 query term）
        Map<String, Integer> docFreq = new HashMap<>();
        for (Skill skill : skills) {
            Set<String> uniqueTokens = new HashSet<>(tokenize(skill.getName() + " " + skill.getDescription()));
            for (String token : uniqueTokens) {
                docFreq.merge(token, 1, Integer::sum);
            }
        }

        for (Skill skill : skills) {
            List<String> docTokens = tokenize(skill.getName() + " " + skill.getDescription());
            double score = 0;
            for (String qToken : queryTokens) {
                int nQi = docFreq.getOrDefault(qToken, 0);
                double idf = Math.log((N - nQi + 0.5) / (nQi + 0.5) + 1.0);
                int fQiD = Collections.frequency(docTokens, qToken);
                if (fQiD == 0) continue;
                double numerator = fQiD * (K1 + 1);
                double denominator = fQiD + K1 * (1 - B + B * docTokens.size() / avgDocLen);
                score += idf * numerator / denominator;
            }
            scores.put(skill.getId(), score);
        }
        return scores;
    }

    // ============================================================
    // 中文字符 bigram 分词
    // ============================================================

    private List<String> tokenize(String text) {
        if (text == null || text.isEmpty()) return List.of();
        List<String> tokens = new ArrayList<>();
        String lower = text.toLowerCase();
        for (int i = 0; i < lower.length() - 1; i++) {
            tokens.add(lower.substring(i, i + 2));
        }
        return tokens;
    }

    private Skill findSkill(List<Skill> skills, Long id) {
        return skills.stream().filter(s -> s.getId().equals(id)).findFirst().orElse(null);
    }
}
