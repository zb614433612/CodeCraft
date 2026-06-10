package com.example.agentdeepseek.tool.impl.document;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.HashMap;
import java.util.Map;

/**
 * 文档解析结果 DTO
 * 统一封装各种文档解析器的输出
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ParsedDocument {

    /** 文档标题（从元数据提取，无则用文件名） */
    private String title;

    /** 纯文本内容 */
    private String textContent;

    /** 页数（PDF专属，其他=1） */
    @Builder.Default
    private int pageCount = 1;

    /** 使用的解析器类型：text/pdf/word/excel */
    private String parserType;

    /** 元数据：作者、创建时间等 */
    @Builder.Default
    private Map<String, String> metadata = new HashMap<>();

    /** 解析耗时（毫秒） */
    private long parseTimeMs;

    /** 是否成功 */
    @Builder.Default
    private boolean success = true;

    /** 错误信息 */
    private String error;
}
