package com.example.agentdeepseek.controller;

import com.example.agentdeepseek.common.enums.ResponseEnum;
import com.example.agentdeepseek.common.response.ApiResponse;
import com.example.agentdeepseek.mapper.ProviderConfigMapper;
import com.example.agentdeepseek.model.entity.ProviderConfig;
import com.example.agentdeepseek.service.llm.LLMClientManager;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;

/**
 * LLM Provider 配置管理 REST 控制器
 */
@Slf4j
@RestController
@RequestMapping("/api/llm-providers")
@Tag(name = "LLM Provider 管理", description = "LLM 平台的 CRUD 和默认设置")
public class LLMProviderController {

    private final ProviderConfigMapper providerConfigMapper;
    private final LLMClientManager llmClientManager;

    public LLMProviderController(ProviderConfigMapper providerConfigMapper,
                                 LLMClientManager llmClientManager) {
        this.providerConfigMapper = providerConfigMapper;
        this.llmClientManager = llmClientManager;
    }

    @GetMapping
    @Operation(summary = "获取所有启用的 Provider 列表")
    public ApiResponse<List<ProviderConfig>> list(HttpServletRequest request) {
        checkAdmin(request);
        List<ProviderConfig> list = providerConfigMapper.selectAllEnabled();
        // 列表接口脱敏：已配置的 Key 返回掩码，前端据此显示"已配置"指示器
        for (ProviderConfig config : list) {
            String key = config.getApiKey();
            if (key != null && !key.isEmpty()) {
                config.setApiKey("****");
            }
        }
        log.info("Provider 列表查询: count={}", list.size());
        return ApiResponse.success(list);
    }

    @GetMapping("/{id}")
    @Operation(summary = "获取单个 Provider 详情")
    public ApiResponse<ProviderConfig> getById(@PathVariable Long id, HttpServletRequest request) {
        checkAdmin(request);
        ProviderConfig config = providerConfigMapper.selectById(id);
        if (config == null) {
            return ApiResponse.error(ResponseEnum.NOT_FOUND, "Provider 不存在");
        }
        return ApiResponse.success(config);
    }

    @PostMapping
    @Operation(summary = "创建 Provider")
    public ApiResponse<ProviderConfig> create(@RequestBody ProviderConfig config, HttpServletRequest request) {
        checkAdmin(request);
        // 默认值（安全兜底，无论前端传什么，创建时强制 isDefault=0）
        if (config.getRequestTemplate() == null || config.getRequestTemplate().isEmpty()) {
            config.setRequestTemplate(config.getCode());
        }
        config.setIsDefault(0);  // ★ 强制：新建的 Provider 绝不能是默认
        if (config.getEnabled() == null) {
            config.setEnabled(1);
        }
        if (config.getSortOrder() == null) {
            config.setSortOrder(0);
        }
        // code 唯一性检查
        ProviderConfig existing = providerConfigMapper.selectByCode(config.getCode());
        if (existing != null) {
            return ApiResponse.error(ResponseEnum.BAD_REQUEST, "Provider 编码 '" + config.getCode() + "' 已存在");
        }

        config.setCreatedAt(LocalDateTime.now());
        config.setUpdatedAt(LocalDateTime.now());
        providerConfigMapper.insert(config);
        llmClientManager.refreshClients();
        log.info("Provider 创建成功: code={}, name={}, id={}", config.getCode(), config.getName(), config.getId());
        return ApiResponse.success(config, "Provider 创建成功");
    }

    @PutMapping("/{id}")
    @Operation(summary = "更新 Provider")
    public ApiResponse<Void> update(@PathVariable Long id, @RequestBody ProviderConfig config, HttpServletRequest request) {
        checkAdmin(request);
        ProviderConfig existing = providerConfigMapper.selectById(id);
        if (existing == null) {
            return ApiResponse.error(ResponseEnum.NOT_FOUND, "Provider 不存在");
        }

        // 只更新允许修改的字段
        if (config.getName() != null) existing.setName(config.getName());
        if (config.getBaseUrl() != null) existing.setBaseUrl(config.getBaseUrl());
        if (config.getApiKey() != null && !config.getApiKey().isEmpty() && !"****".equals(config.getApiKey())) existing.setApiKey(config.getApiKey());
        if (config.getDefaultModel() != null) existing.setDefaultModel(config.getDefaultModel());
        if (config.getModelList() != null) existing.setModelList(config.getModelList());
        if (config.getRequestTemplate() != null) existing.setRequestTemplate(config.getRequestTemplate());
        if (config.getEnabled() != null) existing.setEnabled(config.getEnabled());
        if (config.getSortOrder() != null) existing.setSortOrder(config.getSortOrder());
        existing.setUpdatedAt(LocalDateTime.now());

        providerConfigMapper.update(existing);
        llmClientManager.refreshClients();
        log.info("Provider 更新成功: id={}, code={}", id, existing.getCode());
        return ApiResponse.success(null, "Provider 已更新");
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "删除 Provider")
    public ApiResponse<Void> delete(@PathVariable Long id, HttpServletRequest request) {
        checkAdmin(request);
        ProviderConfig existing = providerConfigMapper.selectById(id);
        if (existing == null) {
            return ApiResponse.error(ResponseEnum.NOT_FOUND, "Provider 不存在");
        }
        providerConfigMapper.delete(id);
        llmClientManager.refreshClients();
        log.info("Provider 删除成功: id={}, code={}", id, existing.getCode());
        return ApiResponse.success(null, "Provider 已删除");
    }

    private void checkAdmin(HttpServletRequest request) {
        String role = (String) request.getAttribute("userRole");
        if (!"admin".equals(role)) {
            throw new RuntimeException("权限不足，需要管理员权限");
        }
    }
}
