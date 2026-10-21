package com.example.agentdeepseek.service.lesson;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.HashMap;
import java.util.Map;

/**
 * params_json 解析工具（{@code [{"name":"x","value":"y"}]} → Map）。
 *
 * <p>抽成独立工具类的原因：检索排序器（{@link LessonRanker}）也要按「参数是否一致」打分，
 * 但排序器是独立组件、不该反向依赖 {@code LessonService} 的实例字段（objectMapper）。
 * 两处共用同一份解析实现，避免「过滤用一套、打分用另一套」的口径漂移。</p>
 */
final class LessonParams {

    private LessonParams() {
    }

    /**
     * 解析 params_json；非法 JSON / 空值一律按「无参数」返回空 Map（不抛异常，与旧行为一致）。
     */
    static Map<String, String> parse(String paramsJson, ObjectMapper objectMapper) {
        Map<String, String> result = new HashMap<>();
        if (paramsJson == null || paramsJson.isBlank() || "[]".equals(paramsJson) || objectMapper == null) {
            return result;
        }
        try {
            JsonNode array = objectMapper.readTree(paramsJson);
            if (array != null && array.isArray()) {
                for (JsonNode node : array) {
                    String name = node.path("name").asText("");
                    String value = node.path("value").asText("");
                    if (!name.isEmpty()) {
                        result.put(name, value);
                    }
                }
            }
        } catch (Exception e) {
            // 解析失败按「无参数」处理——检索链路的姿态是「参数维度缺失就不过滤」，不能因脏数据报错
        }
        return result;
    }
}
