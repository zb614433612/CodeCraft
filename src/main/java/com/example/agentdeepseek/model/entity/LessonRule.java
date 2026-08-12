package com.example.agentdeepseek.model.entity;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 归一化规则实体（P0 归一化管线 · 规则自学习，简化版）
 * LLM 归一化成功时沉淀 (报错文本片段 → 类别+短码)；提取时规则表优先命中。
 * 同一 text_pattern 被 LLM 重复产出 ≥2 次 → status 转正（永久生效）。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class LessonRule {

    /** 状态：候选（LLM 沉淀，未验证） */
    public static final int STATUS_CANDIDATE = 0;
    /** 状态：转正（同一 pattern 产出 ≥2 次，永久生效） */
    public static final int STATUS_ACTIVE = 1;

    /** 来源：LLM 沉淀 */
    public static final String SOURCE_LLM = "llm";
    /** 来源：人工维护 */
    public static final String SOURCE_MANUAL = "manual";

    private Long id;
    /** 报错文本包含片段（转小写归一化，用于 contains 匹配） */
    private String textPattern;
    /** 错误类别 */
    private String errorCategory;
    /** 稳定短码（工具域_现象） */
    private String errorCode;
    /** 沉淀次数（同 pattern 再次被 LLM 产出时 +1） */
    private Integer hitCount;
    /** 提取命中次数（归一化时实际匹配到该规则时 +1，P2 使用度追踪——淘汰依据） */
    private Integer matchHitCount;
    /** 来源：llm / manual */
    private String source;
    /** 状态：0=候选 1=转正 */
    private Integer status;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
