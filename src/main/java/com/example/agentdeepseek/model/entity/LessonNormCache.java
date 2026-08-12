package com.example.agentdeepseek.model.entity;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 归一化缓存实体（P0 归一化管线）
 * key = md5(toolName|errorText前512)，不含参数（类别/错误码与参数解耦）
 * 命中缓存直接回写草稿，避免同一错误重复调 LLM 语义归一化。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class LessonNormCache {

    /** 缓存键：md5(toolName|errorText前512) */
    private String cacheKey;
    /** 语义归一化错误类别 */
    private String errorCategory;
    /** 语义归一化稳定短码（工具域_现象） */
    private String errorCode;
    /** 工具名 */
    private String toolName;
    /** 根因（LLM 提炼，可空） */
    private String rootCause;
    /** 解法（可空：归一化不强求解法，主要靠 C2 复盘） */
    private String solution;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
