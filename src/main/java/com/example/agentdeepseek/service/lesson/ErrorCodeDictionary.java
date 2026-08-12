package com.example.agentdeepseek.service.lesson;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 错误码字典：按工具域分组的「报错文本特征 → 稳定短码 + 错误类别」映射。
 * <p>
 * P0 归一化管线 · 规则通道第一优先级：字典命中时错误类别与错误码联动带出
 * （不再依赖正则库单独猜类别，避免「类别能分、错误码 UNKNOWN」的落空）。
 * <p>
 * 配置来源：application.yml 的 {@code lesson.error-dictionary.entries}（顺序即优先级）。
 * 短码规范：「工具域_现象」（如 MAVEN_DEP_RESOLVE / CMD_NOT_FOUND），
 * 禁止包含路径/版本号/端口等易变信息（易变信息留给 params 维度）。
 */
@Slf4j
@Data
@Component
@ConfigurationProperties(prefix = "lesson.error-dictionary")
public class ErrorCodeDictionary {

    /** 字典条目列表（配置顺序 = 匹配优先级） */
    private List<DictEntry> entries = new ArrayList<>();

    /**
     * 字典条目：工具域 + 正则模式 → 稳定短码 + 错误类别
     */
    @Data
    public static class DictEntry {
        /** 工具域：command / file_writer / ...；空字符串 = 通用（不限定工具） */
        private String tool = "";
        /** 匹配模式：正则表达式（对报错文本全文匹配，忽略大小写） */
        private String pattern;
        /** 稳定短码：工具域_现象，如 CMD_NOT_FOUND / MAVEN_DEP_RESOLVE */
        private String code;
        /** 错误类别：COMPILE / DEPENDENCY / NETWORK / AUTH / MCP_HANDSHAKE / SQL / PARAM / ENV / OTHER */
        private String category;

        /** 编译后的正则（懒加载缓存） */
        private transient volatile Pattern compiledPattern;

        Pattern getCompiledPattern() {
            if (compiledPattern == null) {
                compiledPattern = Pattern.compile(pattern, Pattern.CASE_INSENSITIVE);
            }
            return compiledPattern;
        }
    }

    /**
     * 按工具域 + 报错文本匹配字典（规则通道第一优先级）。
     *
     * @param toolName  工具名（command / file_writer / ...），null/空白按不限定处理
     * @param errorText 报错文本原文
     * @return 首个命中条目；未命中返回 null
     */
    public DictEntry match(String toolName, String errorText) {
        if (errorText == null || errorText.isBlank() || entries.isEmpty()) {
            return null;
        }
        String tool = toolName == null ? "" : toolName.trim();
        for (DictEntry entry : entries) {
            if (entry == null || entry.code == null || entry.code.isBlank()
                    || entry.pattern == null || entry.pattern.isBlank()) {
                continue;
            }
            // 工具域过滤：条目指定了工具域且不匹配 → 跳过；空 = 通用
            if (entry.tool != null && !entry.tool.isBlank() && !entry.tool.equalsIgnoreCase(tool)) {
                continue;
            }
            try {
                if (entry.getCompiledPattern().matcher(errorText).find()) {
                    return entry;
                }
            } catch (Exception e) {
                log.warn("错误码字典条目正则编译失败，跳过: pattern={}, err={}", entry.pattern, e.getMessage());
            }
        }
        return null;
    }
}
