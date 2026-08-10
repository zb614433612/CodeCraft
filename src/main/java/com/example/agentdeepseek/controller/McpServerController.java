package com.example.agentdeepseek.controller;

import com.example.agentdeepseek.common.response.ApiResponse;
import com.example.agentdeepseek.model.entity.McpServerConfig;
import com.example.agentdeepseek.service.McpServerService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * MCP 外部服务器管理控制器
 * 提供 mcp_server 配置的 CRUD 与连接操作（connect/disconnect/refresh），
 * 返回配置 + 实时连接状态（供前端 MCP 管理页面展示）。
 */
@Slf4j
@RestController
@RequestMapping("/api/mcp")
@Tag(name = "MCP 管理", description = "MCP 外部服务器配置管理与连接状态查询")
public class McpServerController {

    private final McpServerService mcpServerService;

    public McpServerController(McpServerService mcpServerService) {
        this.mcpServerService = mcpServerService;
    }

    @GetMapping("/servers")
    @Operation(summary = "查询全部 MCP 服务器配置（含连接状态）")
    public ApiResponse<List<McpServerService.McpServerVO>> list() {
        return ApiResponse.success(mcpServerService.listAll());
    }

    @GetMapping("/servers/{id}")
    @Operation(summary = "查询单个 MCP 服务器配置（含连接状态）")
    public ApiResponse<McpServerService.McpServerVO> get(@PathVariable Long id) {
        McpServerService.McpServerVO vo = mcpServerService.getById(id);
        if (vo == null) {
            return ApiResponse.error(404, "MCP 服务器配置不存在: " + id);
        }
        return ApiResponse.success(vo);
    }

    @PostMapping("/servers")
    @Operation(summary = "新增 MCP 服务器配置（enabled+autoRegister 时自动连接）")
    public ApiResponse<McpServerService.McpServerVO> create(@RequestBody McpServerConfig config) {
        try {
            return ApiResponse.success(mcpServerService.create(config), "创建成功");
        } catch (IllegalArgumentException e) {
            return ApiResponse.error(400, e.getMessage());
        }
    }

    @PutMapping("/servers/{id}")
    @Operation(summary = "更新 MCP 服务器配置（启用状态变化时自动重连/断开）")
    public ApiResponse<McpServerService.McpServerVO> update(@PathVariable Long id,
                                                            @RequestBody McpServerConfig config) {
        try {
            return ApiResponse.success(mcpServerService.update(id, config), "更新成功");
        } catch (IllegalArgumentException e) {
            return ApiResponse.error(400, e.getMessage());
        }
    }

    @DeleteMapping("/servers/{id}")
    @Operation(summary = "删除 MCP 服务器配置（先断开连接）")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        mcpServerService.delete(id);
        return ApiResponse.success(null, "删除成功");
    }

    @PostMapping("/servers/{id}/connect")
    @Operation(summary = "连接指定 MCP 服务器并注册其工具")
    public ApiResponse<McpServerService.McpServerVO> connect(@PathVariable Long id) {
        try {
            return ApiResponse.success(mcpServerService.connect(id), "连接完成");
        } catch (IllegalArgumentException e) {
            return ApiResponse.error(404, e.getMessage());
        }
    }

    @PostMapping("/servers/{id}/disconnect")
    @Operation(summary = "断开指定 MCP 服务器并注销其工具")
    public ApiResponse<McpServerService.McpServerVO> disconnect(@PathVariable Long id) {
        try {
            return ApiResponse.success(mcpServerService.disconnect(id), "已断开");
        } catch (IllegalArgumentException e) {
            return ApiResponse.error(404, e.getMessage());
        }
    }

    @PostMapping("/servers/{id}/refresh")
    @Operation(summary = "重新拉取指定 MCP 服务器的工具列表")
    public ApiResponse<McpServerService.McpServerVO> refresh(@PathVariable Long id) {
        try {
            return ApiResponse.success(mcpServerService.refresh(id), "刷新完成");
        } catch (IllegalArgumentException e) {
            return ApiResponse.error(404, e.getMessage());
        }
    }
}
