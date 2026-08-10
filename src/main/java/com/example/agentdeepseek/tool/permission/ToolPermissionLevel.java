package com.example.agentdeepseek.tool.permission;

/**
 * MCP 外部服务器权限档位（对应 mcp_server.permission_level 字段）
 * 与内置工具的 @ToolPermission 注解元数据互相映射：
 * - SAFE      → 只读（等价默认元数据，无授权要求）
 * - DATA      → 可写数据（manual 模式下需要前置授权）
 * - HIGH_RISK → 高危操作（所有模式下都需要前置授权）
 */
public enum ToolPermissionLevel {

    SAFE("只读，无授权要求"),
    DATA("可写数据，手动模式需授权"),
    HIGH_RISK("高危操作，始终需授权");

    private final String description;

    ToolPermissionLevel(String description) {
        this.description = description;
    }

    public String getDescription() {
        return description;
    }

    /**
     * 解析字符串档位（大小写不敏感），未知值回退为 SAFE
     */
    public static ToolPermissionLevel from(String level) {
        if (level == null || level.isBlank()) {
            return SAFE;
        }
        try {
            return valueOf(level.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return SAFE;
        }
    }

    /**
     * 转换为工具权限元数据（供 ToolPermissionRegistry 动态注册）
     */
    public ToolPermissionMetadata toMetadata() {
        return switch (this) {
            case SAFE -> new ToolPermissionMetadata(
                    OperationCategory.READ, false, false, false, "MCP 外部工具（SAFE 只读）");
            case DATA -> new ToolPermissionMetadata(
                    OperationCategory.WRITE, true, false, false, "MCP 外部工具（DATA 可写数据，需授权）");
            case HIGH_RISK -> new ToolPermissionMetadata(
                    OperationCategory.EXECUTE, true, true, true, "MCP 外部工具（HIGH_RISK 高危，需授权）");
        };
    }
}
