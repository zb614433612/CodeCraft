package com.example.agentdeepseek.tool.permission;

import com.example.agentdeepseek.tool.PermissionContext;
import com.example.agentdeepseek.util.ProjectRootContext;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * 路径穿透安全检测器（层面二防护）
 * <p>
 * 从工具参数中提取 file_path / path 字段，判断是否超出项目根目录。
 * 仅在 manual 模式下由管道调用；超出时发起授权弹窗。
 */
@Slf4j
@Component
public class PathSecurityChecker {

    private static final List<String> PATH_PARAM_NAMES = List.of("file_path", "path");

    /**
     * 仅检测是否存在跨目录路径，不发起授权请求。
     * 供 DeepSeekServiceImpl 在执行前判断是否需要审批弹窗。
     */
    public boolean hasCrossPathViolation(String toolName, JsonNode arguments) {
        List<String> extractedPaths = extractPaths(arguments);
        if (extractedPaths.isEmpty()) return false;

        String projectRoot = ProjectRootContext.get();
        if (projectRoot == null) return false;

        Path rootPath = Paths.get(projectRoot).normalize();

        for (String rawPath : extractedPaths) {
            Path resolved = resolvePath(rawPath, rootPath);
            if (resolved != null && !resolved.startsWith(rootPath)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 检查路径是否超出项目根目录，超出则发起授权请求。
     *
     * @return null 表示通过或已授权；非 null 表示拒绝/等待
     */
    public String checkAndRequest(String toolName, JsonNode arguments, Long conversationId) {
        List<String> violations = detectViolations(arguments);
        if (violations.isEmpty()) return null;

        return requestCrossPathApproval(toolName, violations, conversationId);
    }

    /** 检测所有越界路径，返回违规列表 */
    private List<String> detectViolations(JsonNode arguments) {
        List<String> extractedPaths = extractPaths(arguments);
        if (extractedPaths.isEmpty()) return List.of();

        String projectRoot = ProjectRootContext.get();
        if (projectRoot == null) return List.of();

        Path rootPath = Paths.get(projectRoot).normalize();

        List<String> violations = new ArrayList<>();
        for (String rawPath : extractedPaths) {
            Path resolved = resolvePath(rawPath, rootPath);
            if (resolved != null && !resolved.startsWith(rootPath)) {
                violations.add(rawPath + " → " + resolved);
            }
        }
        return violations;
    }

    private List<String> extractPaths(JsonNode arguments) {
        List<String> paths = new ArrayList<>();
        for (String paramName : PATH_PARAM_NAMES) {
            JsonNode val = arguments.path(paramName);
            if (!val.isMissingNode() && !val.isNull()) {
                String text = val.asText();
                if (!text.isEmpty()) {
                    paths.add(text);
                }
            }
        }
        return paths;
    }

    private Path resolvePath(String pathStr, Path rootPath) {
        Path p = Paths.get(pathStr);
        return p.isAbsolute() ? p.normalize() : rootPath.resolve(pathStr).normalize();
    }

    private String requestCrossPathApproval(String toolName, List<String> violations, Long conversationId) {
        String denial = PermissionContext.requestCrossPathPermission(toolName, violations, conversationId);
        if (denial != null) {
            log.warn("路径穿透授权被拒绝: tool={}, violations={}", toolName, violations);
        }
        return denial;
    }
}
