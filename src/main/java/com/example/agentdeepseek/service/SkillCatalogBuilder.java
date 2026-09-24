package com.example.agentdeepseek.service;

import com.example.agentdeepseek.model.entity.Skill;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 技能目录构建器 —— L1「渐进式披露」层。
 *
 * <p>把当前全部活跃技能压缩成一份「目录」（每条仅：名称 / ID / 置信度 / 触发词 / 一句话描述），
 * 动态注入到 system prompt，让 LLM 始终拥有全量技能的「全局视野」。</p>
 *
 * <p>技能完整的使用说明（instructions）不在目录中，由 LLM 通过
 * {@code skill action=load} 按需加载（L2 层），从而在「不漏技能」与「不爆 token」之间取得平衡。</p>
 *
 * <p>预算控制：目录列表部分受 {@link #MAX_CATALOG_CHARS} 约束，超出后按置信度降序截断，
 * 并提示 LLM 可用 {@code skill action=list} 查看其余技能。</p>
 */
@Slf4j
@Component
public class SkillCatalogBuilder {

    /** 技能目录「列表部分」的字符预算（约等于 token 数，中文 1 字符≈1 token）；超出按置信度降序截断 */
    private static final int MAX_CATALOG_CHARS = 3500;

    /** 单条技能的描述最大字符数（超出截断，防止单条过长挤占预算） */
    private static final int MAX_DESC_CHARS = 100;

    /** 单条技能最多展示的触发词数量 */
    private static final int MAX_TRIGGER_WORDS = 6;

    /** 技能目录段标题 —— 用于「防重复注入」检测 */
    public static final String CATALOG_HEADER = "## 技能目录";

    private final ObjectMapper objectMapper;

    public SkillCatalogBuilder(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * 构建完整的技能注入段（使用规则 + 技能目录）。无技能时返回空字符串。
     *
     * @param skills 当前活跃技能列表（已过滤 confidence &lt; 0.1 的淘汰技能）
     * @return 可追加到 system prompt 末尾的文本；无技能时返回 ""
     */
    public String buildCatalogSection(List<Skill> skills) {
        if (skills == null || skills.isEmpty()) {
            return "";
        }

        // 按置信度降序：预算不足时优先保留高置信度技能
        List<Skill> sorted = new ArrayList<>(skills);
        sorted.sort(Comparator.comparingDouble(
                (Skill s) -> s.getConfidence() == null ? 0.0 : s.getConfidence()).reversed());

        // 预算控制：逐条累加，超出预算即停止（至少保留 1 条，避免全空）
        StringBuilder list = new StringBuilder();
        int shown = 0;
        for (Skill s : sorted) {
            String line = formatLine(s);
            if (shown > 0 && list.length() + line.length() > MAX_CATALOG_CHARS) {
                break;
            }
            list.append(line);
            shown++;
        }

        StringBuilder sb = new StringBuilder();
        sb.append("\n\n---\n");
        sb.append("## 技能使用规则（必须遵守）\n\n");
        sb.append("以下规则优先级仅次于系统注入的「语言强制指令」，高于其余所有指令：\n\n");
        sb.append("1. 下方【技能目录】列出当前可用的**全部技能简介**（只有名称与描述）。"
                + "当用户需求与某个技能的描述匹配时，先调用 `skill` 工具 `action=load`（传 `skill_id`）"
                + "加载该技能的完整「使用说明」，再严格按说明执行。切勿凭名称猜测技能步骤。\n");
        sb.append("2. 技能使用完毕后（无论成功或失败），必须调用 `skill` 工具 `action=report` 反馈：\n");
        sb.append("   - `skill_id`：技能目录中标注的 ID（数字）\n");
        sb.append("   - `success`：true（操作成功/用户满意）或 false（未达预期/出错）\n");
        sb.append("3. 即使用户直接对结果表示满意或不满，也必须调用 `skill action=report`，遗漏会导致技能置信度无法更新。\n");
        sb.append("4. 置信度由系统自动计算（贝叶斯平滑公式），低于 10% 的技能自动淘汰、不再出现在目录中。\n");
        sb.append("5. 持续反馈能让好技能进化、低效技能自然淘汰，形成正向循环。\n");
        sb.append("\n---\n");
        sb.append(CATALOG_HEADER);
        if (shown < sorted.size()) {
            sb.append("（共 ").append(sorted.size()).append(" 个，此处按置信度列出前 ").append(shown).append(" 个）\n\n");
        } else {
            sb.append("（共 ").append(sorted.size()).append(" 个）\n\n");
        }
        sb.append(list);
        if (shown < sorted.size()) {
            sb.append("\n（其余 ").append(sorted.size() - shown)
                    .append(" 个技能因长度预算未列出，可调用 `skill action=list` 查看全部）\n");
        }
        return sb.toString();
    }

    /** 格式化单条技能目录行（一行一条，尽量紧凑） */
    private String formatLine(Skill s) {
        StringBuilder sb = new StringBuilder();
        sb.append("- 【").append(nullToEmpty(s.getName())).append("】(ID:").append(s.getId());
        if (s.getConfidence() != null) {
            sb.append(" | 置信度 ").append(String.format("%.0f%%", s.getConfidence() * 100));
        }
        if (s.getUsageCount() != null) {
            sb.append(" | 用 ").append(s.getUsageCount()).append(" 次");
        }
        sb.append(")");
        String tw = formatTriggerWords(s.getTriggerWords());
        if (!tw.isEmpty()) {
            sb.append(" 触发: ").append(tw);
        }
        String desc = shorten(s.getDescription(), MAX_DESC_CHARS);
        if (!desc.isEmpty()) {
            sb.append(" | ").append(desc);
        }
        sb.append("\n");
        return sb.toString();
    }

    /** 解析并格式化触发词（JSON 数组字符串 → "a/b/c"，最多 MAX_TRIGGER_WORDS 个） */
    private String formatTriggerWords(String triggerWordsJson) {
        if (triggerWordsJson == null || triggerWordsJson.isBlank() || "[]".equals(triggerWordsJson.trim())) {
            return "";
        }
        try {
            List<String> words = objectMapper.readValue(triggerWordsJson, List.class);
            if (words == null || words.isEmpty()) {
                return "";
            }
            List<String> limited = words.size() > MAX_TRIGGER_WORDS
                    ? words.subList(0, MAX_TRIGGER_WORDS) : words;
            return String.join("/", limited);
        } catch (Exception e) {
            log.debug("解析技能触发词失败，忽略: {}", triggerWordsJson);
            return "";
        }
    }

    /** 截断过长文本（保留前 max 个字符 + 省略号） */
    private String shorten(String text, int max) {
        if (text == null) return "";
        String trimmed = text.replaceAll("\\s+", " ").trim();
        if (trimmed.length() <= max) return trimmed;
        return trimmed.substring(0, max) + "…";
    }

    private String nullToEmpty(String s) {
        return s == null ? "" : s;
    }
}
