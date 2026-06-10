package com.example.agentdeepseek.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 文件编辑工具（FileWriterTool action=edit）的后处理配置
 * 涵盖编辑后自动格式化和自动诊断
 */
@Configuration
@ConfigurationProperties(prefix = "edit-file")
@Data
public class EditFileConfig {

    /** 编辑后格式化配置 */
    private FormatterConfig formatter = new FormatterConfig();

    /** 编辑后诊断配置 */
    private DiagnosticConfig diagnostic = new DiagnosticConfig();

    @Data
    public static class FormatterConfig {
        /** 是否启用编辑后自动格式化 */
        private boolean enabled = true;
        /** 格式化超时（毫秒） */
        private int timeout = 30_000;
    }

    @Data
    public static class DiagnosticConfig {
        /** 是否启用编辑后自动诊断 */
        private boolean enabled = true;
        /** 诊断级别：fast（单文件快速检查）/ full（项目级编译检查） */
        private String level = "fast";
        /** 诊断超时（毫秒） */
        private int timeout = 15_000;
    }
}
