package com.example.agentdeepseek.service.lesson;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * P1 弯路通道（C3）·【方案取舍】块解析器
 * <p>
 * LLM 在最终回复中附的结构化块（约定格式，见 code_agent_prompt.txt）：
 * <pre>
 * 【方案取舍】
 * 目标：xxx
 * 尝试：方案A（原因：yyy 不可行）
 * 最终：方案B
 * </pre>
 * 解析成功 → 直接入库 DETOUR（source=llm）并短路 C3 提炼；
 * 解析失败不报错（C3 信号通道兜底，双保险）。
 */
@Slf4j
@Component
public class DetourBlockParser {

    /** 块起始标记 */
    private static final String BLOCK_MARK = "【方案取舍】";

    /** 尝试行：方案描述（原因：xxx），原因可空 */
    private static final Pattern ATTEMPT_PATTERN = Pattern.compile("^(.*?)(?:（原因[:：]\\s*(.+?)）)?$");

    /** 解析结果 */
    @Data
    public static class DetourBlock {
        private String goal;
        private String abandonedApproach;
        private String abandonReason;
        private String adoptedApproach;
        /** 可选：可复用建议 */
        private String solution;
    }

    /**
     * 从最终回复中解析【方案取舍】块。
     *
     * @param finalAnswer LLM 最终回复（可为 null/无块）
     * @return 解析成功且核心字段（goal/尝试/最终）非空返回块；否则 null
     */
    public DetourBlock parse(String finalAnswer) {
        if (finalAnswer == null || finalAnswer.isBlank()) {
            return null;
        }
        int markIdx = finalAnswer.indexOf(BLOCK_MARK);
        if (markIdx < 0) {
            return null;
        }
        String blockText = finalAnswer.substring(markIdx + BLOCK_MARK.length());
        // 截到下一个【（避免吞掉后续其他块/文本）
        int nextMark = blockText.indexOf('【');
        if (nextMark >= 0) {
            blockText = blockText.substring(0, nextMark);
        }
        if (blockText.isBlank()) {
            return null;
        }

        DetourBlock block = new DetourBlock();
        block.setGoal(extractField(blockText, "目标"));
        block.setAdoptedApproach(extractField(blockText, "最终"));
        block.setSolution(extractField(blockText, "建议"));
        String attempt = extractField(blockText, "尝试");

        // 尝试行解析：方案（原因：xxx）
        if (attempt != null) {
            Matcher m = ATTEMPT_PATTERN.matcher(attempt.trim());
            if (m.matches()) {
                block.setAbandonedApproach(m.group(1));
                block.setAbandonReason(m.group(2));
            } else {
                block.setAbandonedApproach(attempt.trim());
            }
        }

        // 核心字段校验（缺任一 → 无效块，C3 信号通道兜底）
        if (isBlank(block.getGoal()) || isBlank(block.getAbandonedApproach())
                || isBlank(block.getAdoptedApproach())) {
            log.debug("方案取舍块字段不完整，丢弃: goal={}, attempt={}, final={}",
                    block.getGoal(), attempt, block.getAdoptedApproach());
            return null;
        }
        return block;
    }

    /** 按行前缀提取字段值（「目标：xxx」→ xxx；支持中英文冒号） */
    private String extractField(String text, String fieldName) {
        Pattern p = Pattern.compile("(?m)^\\s*" + fieldName + "\\s*[:：]\\s*(.+)$");
        Matcher m = p.matcher(text);
        if (m.find()) {
            return m.group(1).trim();
        }
        return null;
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
