package com.example.agentdeepseek.controller;

import com.example.agentdeepseek.common.response.ApiResponse;
import com.example.agentdeepseek.model.entity.AgentConfig;
import com.example.agentdeepseek.service.AgentConfigService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Agent配置管理 REST 控制器
 */
@Slf4j
@RestController
@RequestMapping("/api/agent-configs")
@Tag(name = "Agent配置管理", description = "自定义Agent的CRUD和默认设置")
public class AgentConfigController {

    private final AgentConfigService agentConfigService;

    public AgentConfigController(AgentConfigService agentConfigService) {
        this.agentConfigService = agentConfigService;
    }

    @GetMapping("/list")
    @Operation(summary = "获取Agent配置列表")
    public ApiResponse<List<AgentConfig>> list(@RequestParam(required = false) Long userId) {
        List<AgentConfig> list = agentConfigService.listByUser(userId);
        log.info("Agent列表查询: userId={}, count={}", userId, list.size());
        return ApiResponse.success(list);
    }

    @PostMapping("/create")
    @Operation(summary = "创建Agent配置")
    public ApiResponse<AgentConfig> create(@RequestBody AgentConfig agentConfig) {
        AgentConfig created = agentConfigService.create(agentConfig);
        return ApiResponse.success(created, "Agent创建成功");
    }

    @PutMapping("/{id}")
    @Operation(summary = "更新Agent配置")
    public ApiResponse<AgentConfig> update(@PathVariable Long id, @RequestBody AgentConfig agentConfig) {
        AgentConfig updated = agentConfigService.update(id, agentConfig);
        return ApiResponse.success(updated, "Agent更新成功");
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "删除Agent配置")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        agentConfigService.delete(id);
        return ApiResponse.success(null, "Agent已删除");
    }

    @PutMapping("/{id}/default")
    @Operation(summary = "设置为默认Agent")
    public ApiResponse<AgentConfig> setDefault(@PathVariable Long id, @RequestParam(required = false) Long userId) {
        AgentConfig result = agentConfigService.setDefault(id, userId);
        return ApiResponse.success(result, "已设为默认Agent");
    }

    @PutMapping("/{id}/runtime")
    @Operation(summary = "更新Agent运行时配置（模型、思考、执行、工作目录）")
    public ApiResponse<AgentConfig> updateRuntime(
            @PathVariable Long id,
            @RequestBody AgentConfig runtimeConfig) {
        AgentConfig updated = agentConfigService.updateRuntime(id, runtimeConfig);
        return ApiResponse.success(updated, "运行时配置已更新");
    }
}
