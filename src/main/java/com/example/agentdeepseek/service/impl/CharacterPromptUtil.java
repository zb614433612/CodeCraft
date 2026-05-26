package com.example.agentdeepseek.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

/**
 * 角色性格配置构建工具
 * <p>
 * 从 DeepSeekServiceImpl 拆分出来，负责将角色 JSON 配置转换为提示词片段。
 * 纯工具类，所有方法均为 static，无状态无外部依赖。
 * </p>
 */
@Slf4j
public final class CharacterPromptUtil {

    private CharacterPromptUtil() {
        // 工具类，禁止实例化
    }

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * 从角色性格配置 JSON 构建提示词片段（简洁格式，无特殊符号）
     *
     * @param characterJson 角色性格配置 JSON 字符串
     * @return 提示词片段字符串，如果无有效字段则返回 null
     */
    public static String buildCharacterSection(String characterJson) {
        try {
            JsonNode root = MAPPER.readTree(characterJson);
            StringBuilder sb = new StringBuilder("\n\n请扮演以下角色设定：\n");
            appendCharField(sb, root, "name", "姓名");
            appendCharField(sb, root, "species", "物种");
            appendCharField(sb, root, "gender", "性别");
            appendCharField(sb, root, "age", "年龄");
            appendCharField(sb, root, "personality", "性格");
            appendCharField(sb, root, "greeting", "对你的称呼");
            appendCharField(sb, root, "background", "背景");
            appendCharField(sb, root, "likes", "喜好");
            appendCharField(sb, root, "style", "说话风格");
            if (sb.length() <= "\n\n请扮演以下角色设定：\n".length()) {
                return null;
            }
            return sb.toString();
        } catch (Exception e) {
            log.warn("解析角色性格配置失败", e);
            return null;
        }
    }

    private static void appendCharField(StringBuilder sb, JsonNode node, String field, String label) {
        JsonNode value = node.get(field);
        if (value != null && !value.asText().trim().isEmpty()) {
            sb.append("  ").append(label).append(": ").append(value.asText().trim()).append("\n");
        }
    }
}
