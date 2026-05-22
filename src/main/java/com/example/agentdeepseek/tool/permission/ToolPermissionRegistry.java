package com.example.agentdeepseek.tool.permission;

import com.example.agentdeepseek.tool.Tool;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 权限注册中心 — 从 @ToolPermission 注解自动构建工具权限元数据索引。
 * 直接注入 List&lt;Tool&gt; 在 afterPropertiesSet() 阶段构建元数据，
 * 而非依赖 ToolRegistry（ToolRegistry 在 ApplicationRunner 阶段才填充）。
 */
@Slf4j
@Component
public class ToolPermissionRegistry implements InitializingBean {

    private final List<Tool> tools;
    private final Map<String, ToolPermissionMetadata> metadataMap = new ConcurrentHashMap<>();

    public ToolPermissionRegistry(List<Tool> tools) {
        this.tools = tools;
    }

    @Override
    public void afterPropertiesSet() {
        for (Tool tool : tools) {
            ToolPermission ann = tool.getClass().getAnnotation(ToolPermission.class);
            if (ann != null) {
                metadataMap.put(tool.getName(), ToolPermissionMetadata.from(ann));
            } else {
                metadataMap.put(tool.getName(), ToolPermissionMetadata.DEFAULT);
            }
        }
        log.info("权限注册中心初始化完成: 已注册 {} 个工具的权限元数据", metadataMap.size());
    }

    public ToolPermissionMetadata getMetadata(String toolName) {
        return metadataMap.getOrDefault(toolName, ToolPermissionMetadata.DEFAULT);
    }

    /** 层面一：是否需要数据影响授权 */
    public boolean requiresDataApproval(String toolName) {
        return getMetadata(toolName).affectsData();
    }

    /** 层面二：是否涉及文件路径 */
    public boolean isPathSensitive(String toolName) {
        return getMetadata(toolName).isPathSensitive();
    }

    /** 层面三：是否为高危操作 */
    public boolean isHighRisk(String toolName) {
        return getMetadata(toolName).highRisk();
    }

    /** 获取所有需要授权的工具名列表 */
    public Set<String> getToolsRequiringApproval() {
        return metadataMap.entrySet().stream()
                .filter(e -> e.getValue().affectsData())
                .map(Map.Entry::getKey)
                .collect(Collectors.toSet());
    }

    /** 获取所有路径敏感工具名列表 */
    public Set<String> getPathSensitiveTools() {
        return metadataMap.entrySet().stream()
                .filter(e -> e.getValue().isPathSensitive())
                .map(Map.Entry::getKey)
                .collect(Collectors.toSet());
    }

    /** 获取所有高危工具名列表 */
    public Set<String> getHighRiskTools() {
        return metadataMap.entrySet().stream()
                .filter(e -> e.getValue().highRisk())
                .map(Map.Entry::getKey)
                .collect(Collectors.toSet());
    }

    /** 判断指定模式下工具是否需要前置授权 */
    public boolean requiresApproval(String toolName, String executionMode) {
        return getMetadata(toolName).requiresApproval(executionMode);
    }
}
