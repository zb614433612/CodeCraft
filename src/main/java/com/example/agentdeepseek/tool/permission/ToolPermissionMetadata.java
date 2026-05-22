package com.example.agentdeepseek.tool.permission;

/**
 * 工具权限元数据模型（从 @ToolPermission 注解解析后的值对象）
 */
public class ToolPermissionMetadata {

    private final OperationCategory category;
    private final boolean affectsData;
    private final boolean isPathSensitive;
    private final boolean highRisk;
    private final String description;

    public static final ToolPermissionMetadata DEFAULT = new ToolPermissionMetadata(
            OperationCategory.READ, false, false, false, "");

    public ToolPermissionMetadata(OperationCategory category, boolean affectsData,
                                   boolean isPathSensitive, boolean highRisk, String description) {
        this.category = category;
        this.affectsData = affectsData;
        this.isPathSensitive = isPathSensitive;
        this.highRisk = highRisk;
        this.description = description;
    }

    public static ToolPermissionMetadata from(ToolPermission ann) {
        return new ToolPermissionMetadata(
                ann.category(), ann.affectsData(),
                ann.isPathSensitive(), ann.highRisk(),
                ann.description());
    }

    public OperationCategory getCategory() { return category; }
    public boolean affectsData() { return affectsData; }
    public boolean isPathSensitive() { return isPathSensitive; }
    public boolean highRisk() { return highRisk; }
    public String getDescription() { return description; }

    /**
     * 判断在指定执行模式下是否需要前置授权
     */
    public boolean requiresApproval(String executionMode) {
        if ("manual".equals(executionMode)) {
            return affectsData;
        }
        return highRisk;
    }
}
