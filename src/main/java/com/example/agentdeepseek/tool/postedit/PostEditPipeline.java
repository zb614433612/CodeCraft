package com.example.agentdeepseek.tool.postedit;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.file.Path;

/**
 * 编辑后处理管道
 * <p>
 * 编排 Formatter → Diagnostic 顺序执行，结果合并返回。
 * 每个环节独立运行，单个环节失败不影响其他环节，不阻塞主流程。
 */
@Slf4j
@Component
public class PostEditPipeline {

    private final Formatter formatter;
    private final Diagnostic diagnostic;

    public PostEditPipeline(Formatter formatter, Diagnostic diagnostic) {
        this.formatter = formatter;
        this.diagnostic = diagnostic;
    }

    /**
     * 后处理结果
     */
    public record PostEditResult(String formatterMessage, String diagnosticMessage) {

        /** 是否包含任何附加信息 */
        public boolean hasInfo() {
            return !formatterMessage.isEmpty() || !diagnosticMessage.isEmpty();
        }

        /** 合并为完整的后处理报告 */
        public String toReport() {
            StringBuilder sb = new StringBuilder();
            if (!formatterMessage.isEmpty()) {
                sb.append(formatterMessage);
            }
            if (!diagnosticMessage.isEmpty()) {
                if (!sb.isEmpty()) sb.append("\n");
                sb.append(diagnosticMessage);
            }
            return sb.toString();
        }
    }

    /**
     * 执行后处理管道
     * <p>
     * 执行顺序：先格式化，再诊断（格式化后的代码做诊断更准确）
     *
     * @param filePath 已修改的文件路径
     * @return 后处理结果报告
     */
    public PostEditResult execute(Path filePath) {
        log.debug("执行编辑后处理: {}", filePath);

        // 1. 格式化
        Formatter.FormatResult formatResult = formatter.format(filePath);

        // 2. 诊断（在格式化后的文件上执行，结果更准确）
        Diagnostic.DiagnosticResult diagResult = diagnostic.diagnose(filePath);

        if (formatResult.executed() || diagResult.executed()) {
            log.info("编辑后处理完成: file={}, format={}, diagnostic_errors={}",
                    filePath, formatResult.success() ? "OK" : formatResult.message(),
                    diagResult.hasError() ? diagResult.errors().size() : 0);
        }

        return new PostEditResult(formatResult.toDisplay(), diagResult.toDisplay());
    }
}
