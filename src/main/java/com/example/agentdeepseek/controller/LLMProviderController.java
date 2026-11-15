package com.example.agentdeepseek.controller;

import com.example.agentdeepseek.common.enums.ResponseEnum;
import com.example.agentdeepseek.common.response.ApiResponse;
import com.example.agentdeepseek.mapper.ProviderConfigMapper;
import com.example.agentdeepseek.model.entity.ProviderConfig;
import com.example.agentdeepseek.model.vo.ProviderCapabilityVO;
import com.example.agentdeepseek.service.llm.LLMClientManager;
import com.example.agentdeepseek.service.llm.ProviderCapabilityService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

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
    private final ProviderCapabilityService capabilityService;

    public LLMProviderController(ProviderConfigMapper providerConfigMapper,
                                 LLMClientManager llmClientManager,
                                 ProviderCapabilityService capabilityService) {
        this.providerConfigMapper = providerConfigMapper;
        this.llmClientManager = llmClientManager;
        this.capabilityService = capabilityService;
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
            // 扩展配置中可能含 AccessKey/SecretKey、联网搜索 Key，同样掩码返回
            config.setExtraConfig(maskExtraConfig(config.getExtraConfig()));
        }
        log.info("Provider 列表查询: count={}", list.size());
        return ApiResponse.success(list);
    }

    // ==================== 能力档案（T9：前端唯一真相源） ====================

    /*
      ⚠️ 这两个接口刻意【不做 checkAdmin】：理由有二 ——
      ① 返回内容不含任何密钥值。missingKeys 只给"字段名"（如 secretKey），
         不会泄露具体值；结论本身是静态能力描述，不是敏感配置。
      ② 聊天页需要它来判断"当前 Provider 能否发图"，而聊天页不是管理页，
         普通角色也会进入。若加 admin 校验，非管理员会看到"能力未知"，
         反而退化成前端猜（那正是本方案要消灭的第四份副本）。

      路由说明：/templates、/capabilities 是字面量路径，与 /{id} 并存时
      Spring 的 PathPatternParser 会优先匹配字面量，不会误把 "templates" 当 id 转换。
    */

    /**
     * 全部内置模板的能力档案（<b>模板级，不含凭证判定</b>）。
     *
     * <p>用途：① 前端「新建 Provider」的模板下拉（替代 ProviderEditDrawer 里硬编码的
     * TEMPLATE_OPTIONS）；② 刚选模板、还没填凭证时的能力预览。</p>
     *
     * <p>此状态下 search / balance 显示为「平台支持，配好凭证即可用」（warn），
     * 而不是 off —— 让用户知道"配完就会亮"。</p>
     */
    @GetMapping("/templates")
    @Operation(summary = "获取全部内置模板的能力档案（新建 Provider 时预览）")
    public ApiResponse<List<ProviderCapabilityVO>> templates() {
        return ApiResponse.success(capabilityService.templates());
    }

    /**
     * 所有已启用 Provider 的能力（key = provider code）。
     *
     * <p>前端一次拉取即可填满本地缓存，避免逐个 Provider发 N 次请求。</p>
     */
    @GetMapping("/capabilities")
    @Operation(summary = "批量获取所有 Provider 的能力（前端一次预热）")
    public ApiResponse<Map<String, ProviderCapabilityVO>> allCapabilities() {
        List<ProviderConfig> list = providerConfigMapper.selectAllEnabled();
        return ApiResponse.success(capabilityService.forAllProviders(list));
    }

    /**
     * 单个 Provider 的能力（<b>结合其真实凭证</b>）。
     *
     * <p>注意：这里读的是 DB <b>原始值</b>（含真实密钥），因此能精确判断
     * 「凭证是否成对齐全」并返回 missingKeys。返回值本身不含任何密钥，可安全下发。</p>
     */
    @GetMapping("/{code}/capabilities")
    @Operation(summary = "获取单个 Provider 的能力（含凭证就绪判定）")
    public ApiResponse<ProviderCapabilityVO> capabilities(@PathVariable String code) {
        ProviderConfig config = providerConfigMapper.selectByCode(code);
        if (config == null) {
            return ApiResponse.error(ResponseEnum.NOT_FOUND, "Provider [" + code + "] 不存在");
        }
        return ApiResponse.success(capabilityService.forProvider(config));
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
        // 扩展配置：空串=清空；含掩码=未修改（保留原值）；其余=覆盖
        if (config.getExtraConfig() != null) {
            String extra = config.getExtraConfig().trim();
            if (extra.isEmpty()) {
                existing.setExtraConfig(null);
            } else if (!extra.contains(MASK)) {
                existing.setExtraConfig(extra);
            }
        }
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

    /** 脱敏占位符（前端收到该值表示"已配置但不可见"，提交时视为未修改） */
    private static final String MASK = "****";

    /**
     * 脱敏扩展配置：把 JSON 中的密钥类字段值替换为掩码，保留结构供前端展示"已配置"状态。
     * <p>覆盖火山引擎 AccessKey/SecretKey、联网搜索 API Key 等平台增强凭证。</p>
     */
    private static String maskExtraConfig(String extraConfig) {
        if (extraConfig == null || extraConfig.isBlank()) {
            return extraConfig;
        }
        return extraConfig.replaceAll(
                "(\"(?:accessKey|secretKey|searchApiKey|apiKey|token|sessionToken)\"\\s*:\\s*\")[^\"]*(\")",
                "$1" + MASK + "$2");
    }

    private void checkAdmin(HttpServletRequest request) {
        String role = (String) request.getAttribute("userRole");
        if (!"admin".equals(role)) {
            throw new RuntimeException("权限不足，需要管理员权限");
        }
    }
}
