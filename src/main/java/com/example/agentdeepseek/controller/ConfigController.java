package com.example.agentdeepseek.controller;

import com.example.agentdeepseek.common.response.ApiResponse;
import com.example.agentdeepseek.service.ConfigService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/config")
@Tag(name = "系统配置", description = "动态配置项管理")
public class ConfigController {

    @Autowired
    private ConfigService configService;

    @Operation(summary = "获取配置项")
    @GetMapping("/{key}")
    public ApiResponse<Map<String, String>> getConfig(@PathVariable String key) {
        String value = configService.getValue(key);
        // 对 API Key 做脱敏处理
        if ("deepseek_api_key".equals(key) && value != null && !value.isEmpty()) {
            value = maskApiKey(value);
        }
        return ApiResponse.success(Map.of("key", key, "value", value != null ? value : ""));
    }

    @Operation(summary = "更新配置项")
    @PutMapping("/{key}")
    public ApiResponse<Void> setConfig(@PathVariable String key, @RequestBody Map<String, String> body) {
        String value = body.get("value");
        configService.setValue(key, value);
        log.info("配置已更新: key={}", key);
        return ApiResponse.success(null);
    }

    /**
     * 脱敏 API Key：只保留前4位和后4位
     */
    private String maskApiKey(String key) {
        if (key.length() <= 8) {
            return "****";
        }
        return key.substring(0, 4) + "****" + key.substring(key.length() - 4);
    }
}
