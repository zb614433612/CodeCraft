package com.example.agentdeepseek.tool.permission;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 工具操作审计日志
 */
@Slf4j
@Component
public class ToolAuditLogger {

    public void log(String toolName, JsonNode arguments, Long userId, String executionMode) {
        String argsPreview = arguments != null ? summarizeArgs(arguments) : "{}";
        log.info("[AUDIT] tool={} mode={} userId={} args={}", toolName, executionMode, userId, argsPreview);
    }

    private String summarizeArgs(JsonNode args) {
        if (args == null || args.isEmpty()) return "{}";
        StringBuilder sb = new StringBuilder("{");
        var fields = args.fields();
        int count = 0;
        while (fields.hasNext() && count < 3) {
            var f = fields.next();
            String val = f.getValue().asText();
            if (val.length() > 80) val = val.substring(0, 80) + "...";
            if (count > 0) sb.append(", ");
            sb.append(f.getKey()).append("=").append(val);
            count++;
        }
        if (fields.hasNext()) sb.append(", ...");
        sb.append("}");
        return sb.toString();
    }
}
