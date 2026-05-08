package com.example.agentdeepseek.model.entity;

/**
 * 消息角色枚举
 */
public enum MessageRole {
    SYSTEM("system"),
    USER("user"),
    ASSISTANT("assistant"),
    TOOL("tool");

    private final String value;

    MessageRole(String value) {
        this.value = value;
    }

    public String getValue() {
        return value;
    }

    /**
     * 从字符串转换为枚举
     * @param value 角色字符串
     * @return 对应的枚举值，不匹配时返回null
     */
    public static MessageRole fromValue(String value) {
        for (MessageRole role : MessageRole.values()) {
            if (role.value.equalsIgnoreCase(value)) {
                return role;
            }
        }
        return null;
    }
}