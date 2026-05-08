package com.example.agentdeepseek.tool.impl;

import com.example.agentdeepseek.tool.Tool;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 天气工具
 * 获取指定城市的天气信息（返回固定假数据）
 */
@Slf4j
@Component
public class WeatherTool implements Tool {

    private final ObjectMapper objectMapper;

    public WeatherTool(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public String getName() {
        return "get_weather";
    }

    @Override
    public String getDescription() {
        return "获取指定城市的天气信息【演示数据】。注意：此工具返回的是预设模拟数据（固定24°C晴天），并非真实天气数据，仅用于功能演示";
    }

    @Override
    public JsonNode getParameters() {
        // 定义参数模式（JSON Schema）
        ObjectNode parameters = objectMapper.createObjectNode();
        parameters.put("type", "object");
        parameters.put("description", "获取天气信息的参数");

        ObjectNode properties = objectMapper.createObjectNode();
        ObjectNode cityProperty = objectMapper.createObjectNode();
        cityProperty.put("type", "string");
        cityProperty.put("description", "城市名称，例如：北京、上海、广州");
        properties.set("city", cityProperty);

        parameters.set("properties", properties);
        parameters.putArray("required").add("city");
        return parameters;
    }

    @Override
    public String execute(JsonNode arguments) {
        // 记录参数用于调试
        log.info("WeatherTool 参数原始值: {}", arguments);
        log.info("WeatherTool 参数节点类型: {}", arguments.getNodeType());
        log.info("WeatherTool 参数是否为文本节点: {}", arguments.isTextual());
        log.info("WeatherTool 参数是否为对象节点: {}", arguments.isObject());
        log.info("WeatherTool 参数是否为数组节点: {}", arguments.isArray());
        log.info("WeatherTool 参数是否为null: {}", arguments.isNull());
        log.info("WeatherTool 参数是否为missing: {}", arguments.isMissingNode());
        log.info("WeatherTool 参数asText值: {}", arguments.asText());
        log.info("WeatherTool 参数toString值: {}", arguments.toString());

        // 如果arguments是文本节点，打印文本内容
        if (arguments.isTextual()) {
            log.info("WeatherTool 参数文本内容: {}", arguments.asText());
        }

        // 如果arguments是对象节点，打印所有字段
        if (arguments.isObject()) {
            log.info("WeatherTool 参数对象字段:");
            var fieldNames = arguments.fieldNames();
            while (fieldNames.hasNext()) {
                String fieldName = fieldNames.next();
                JsonNode fieldValue = arguments.path(fieldName);
                log.info("  field: {} = {} (type: {})", fieldName, fieldValue, fieldValue.getNodeType());
            }
        }

        JsonNode actualArguments = arguments;

        // 如果arguments是文本节点（JSON字符串），尝试解析它
        if (arguments.isTextual()) {
            try {
                log.info("参数是文本节点，尝试解析JSON字符串: {}", arguments.asText());
                actualArguments = objectMapper.readTree(arguments.asText());
                log.info("解析后的参数: {}", actualArguments);
            } catch (Exception e) {
                log.error("解析JSON参数失败: {}", arguments.asText(), e);
            }
        }

        // 检查参数是否为空或缺失
        if (actualArguments.isMissingNode() || actualArguments.isNull() ||
            (actualArguments.isTextual() && actualArguments.asText().isEmpty())) {
            log.error("WeatherTool 接收到空参数或缺失参数！无法获取城市信息");
            return "错误：天气工具调用失败，缺少城市参数。请提供城市名称，例如：北京、上海、广州。";
        }

        // 解析参数
        String city = actualArguments.path("city").asText("未知城市");
        log.info("解析到的城市: {}", city);

        // 如果city为空或未知城市，尝试从actualArguments中查找其他可能字段
        if (city.isEmpty() || "未知城市".equals(city)) {
            // 尝试其他可能的字段名
            String[] possibleFields = {"location", "city_name", "name", "cityName", "City", "cityName", "cityName", "city_name", "city", "城市", "location_name"};
            for (String field : possibleFields) {
                if (actualArguments.has(field)) {
                    city = actualArguments.path(field).asText("未知城市");
                    if (!city.isEmpty() && !"未知城市".equals(city)) {
                        log.info("从{}字段找到城市: {}", field, city);
                        break;
                    }
                }
            }

            // 如果仍然没有找到，且actualArguments是对象节点，尝试查找任何字符串类型的字段值
            if ((city.isEmpty() || "未知城市".equals(city)) && actualArguments.isObject()) {
                var fieldNames = actualArguments.fieldNames();
                while (fieldNames.hasNext()) {
                    String fieldName = fieldNames.next();
                    JsonNode fieldValue = actualArguments.path(fieldName);
                    if (fieldValue.isTextual()) {
                        String value = fieldValue.asText();
                        if (!value.isEmpty() && value.length() < 50) { // 假设城市名长度小于50字符
                            city = value;
                            log.info("从字段{}的文本值找到城市: {}", fieldName, city);
                            break;
                        }
                    }
                }
            }
        }

        // 如果仍然没有找到城市，并且actualArguments是文本节点，直接使用文本内容作为城市名
        if ((city.isEmpty() || "未知城市".equals(city)) && actualArguments.isTextual()) {
            city = actualArguments.asText();
            log.info("直接使用文本参数作为城市: {}", city);
        }

        // 返回固定假数据：24摄氏度，晴天
        return String.format("城市：%s\n天气：晴天\n温度：24°C\n湿度：65%%\n风速：3级", city);
    }
}