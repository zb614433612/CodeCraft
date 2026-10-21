package com.example.agentdeepseek.service.lesson;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * P0-2 查询侧同义扩展：把「用户/模型怎么说」与「经验库里怎么写」之间的词汇鸿沟补上。
 *
 * <p><b>解决的问题</b>：模糊检索是字面 LIKE 匹配，中文描述查不到英文经验——
 * 典型例子是「端口被占用」查不到 {@code Address already in use}，
 * 「缺少依赖」查不到 {@code Could not resolve dependencies}。
 * 这类漏召不是排序问题，是「词根本不重合」，必须从查询侧扩展。</p>
 *
 * <p><b>为什么是人工白名单而不是 LLM 生成</b>：别名表要的是确定性——
 * 同一个查询词每次扩展出同一批词，才能保证检索结果可复现、可 A/B 对比。
 * LLM 生成会引入随机性与额外延迟，且这类术语对数量有限（几十条即可覆盖主场景）。</p>
 *
 * <p><b>扩展口径</b>：值里的目标词与查询拆词走同一套分词（ASCII 整词小写 / 中文 2-gram），
 * 保证「扩展出来的词」与「库里被 LIKE 匹配的子串」同构。</p>
 */
@Slf4j
@Component
@ConfigurationProperties(prefix = "lesson.query-alias")
public class QueryAliasExpander {

    /** 总开关：false 时 expand 原样返回（检索行为与改造前逐字节一致） */
    private boolean enabled = true;

    /** 单次检索参与 SQL OR 的词数上限（原有拆词 + 扩展词），防条件膨胀 */
    private int maxWords = 16;

    /**
     * 别名表：key = 查询原文中出现即触发的说法（不区分大小写，中文直接子串匹配）；
     * value = 需要补充检索的目标词/词组。
     *
     * <p>顺序有意义：词数超限时先遍历到的别名优先保留，所以高频场景写在前面。</p>
     */
    private Map<String, List<String>> entries = new LinkedHashMap<>();

    /**
     * 在已拆词结果上做同义扩展。
     *
     * @param splitWords 拆词结果（已小写去重，可为空）
     * @param rawText    用户原始查询文本（用于子串触发判断，可为 null）
     * @return 扩展后的词表；保持原有词顺序，别名新词追加在后
     */
    public List<String> expand(List<String> splitWords, String rawText) {
        List<String> base = splitWords == null ? List.of() : splitWords;
        if (!enabled || entries == null || entries.isEmpty() || rawText == null || rawText.isBlank()) {
            return base;
        }
        String haystack = rawText.toLowerCase(Locale.ROOT);
        Set<String> words = new LinkedHashSet<>(base);
        int before = words.size();
        for (Map.Entry<String, List<String>> entry : entries.entrySet()) {
            if (words.size() >= maxWords) {
                break;
            }
            String trigger = entry.getKey() == null ? "" : entry.getKey().toLowerCase(Locale.ROOT).trim();
            if (trigger.isEmpty() || !haystack.contains(trigger)) {
                continue;
            }
            List<String> targets = entry.getValue();
            if (targets == null || targets.isEmpty()) {
                continue;
            }
            for (String target : targets) {
                for (String token : tokenize(target)) {
                    if (words.size() >= maxWords) {
                        break;
                    }
                    words.add(token);
                }
            }
        }
        if (words.size() > before) {
            log.debug("查询同义扩展: {} 词 → {} 词（原文 {}）", before, words.size(), abbreviate(rawText));
        }
        return new ArrayList<>(words);
    }

    /**
     * 目标词分词：复用检索侧拆词口径（ASCII 整词小写 / 中文 2-gram）。
     * 单字中文词（2-gram 滑窗装不下）直接保留原文，否则短别名会被静默丢弃。
     */
    private List<String> tokenize(String target) {
        if (target == null || target.isBlank()) {
            return List.of();
        }
        List<String> tokens = LessonService.splitFuzzyKeywords(target);
        if (tokens.isEmpty()) {
            return List.of(target.toLowerCase(Locale.ROOT).trim());
        }
        return tokens;
    }

    private String abbreviate(String text) {
        return text.length() <= 40 ? text : text.substring(0, 40) + "…";
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public int getMaxWords() {
        return maxWords;
    }

    public void setMaxWords(int maxWords) {
        this.maxWords = maxWords;
    }

    public Map<String, List<String>> getEntries() {
        return entries;
    }

    public void setEntries(Map<String, List<String>> entries) {
        this.entries = entries == null ? new LinkedHashMap<>() : new LinkedHashMap<>(entries);
    }
}
