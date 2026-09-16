package com.example.agentdeepseek.service.files;

import com.example.agentdeepseek.model.entity.ProviderConfig;
import com.example.agentdeepseek.service.files.impl.AnthropicFilesApiClient;
import com.example.agentdeepseek.service.files.impl.DeepSeekFilesApiClient;
import com.example.agentdeepseek.service.llm.LLMClientManager;
import com.example.agentdeepseek.service.llm.LLMWebClientManager;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Files API 客户端路由管理器（仿 LLMClientManager 模式）
 * <p>
 * 按 Provider 的 requestTemplate 分发到对应的 {@link FilesApiClient} 实现，
 * 并复用 {@link LLMWebClientManager} 的 WebClient（认证头 / 连接池 / 并发限流 / 429·5xx 重试全部现成）。
 * </p>
 *
 * <h3>路由规则</h3>
 * <ul>
 *   <li>deepseek → {@link DeepSeekFilesApiClient}（OpenAI 兼容风格 /files）</li>
 *   <li>anthropic → {@link AnthropicFilesApiClient}（Anthropic 兼容风格 /v1/files + x-api-key + anthropic-beta）</li>
 *   <li>其余 template（openai / ollama 等暂未适配）→ 返回 null，上层自行降级（不报 500）</li>
 * </ul>
 *
 * <h3>多厂商扩展指引</h3>
 * <ol>
 *   <li>新增厂商：实现 FilesApiClient（或继承 AbstractFilesApiClient）→ 在本类 switch 中注册 template；</li>
 *   <li>字段差异处理：一律在客户端内归一到 FileObject
 *       （例：Anthropic 的 size_bytes → bytes、RFC3339 时间字符串 → epoch 秒）；</li>
 *   <li>认证差异：无需处理——已在 {@code LLMWebClientManager} 按 template 覆盖认证头。</li>
 * </ol>
 *
 * <h3>缓存策略（P1）</h3>
 * 实例缓存 key 含 baseUrl（{@code code::baseUrl}），Provider 配置更新导致 baseUrl 变化时自动重建；
 * API Key 轮换由 WebClient 层每次请求动态解析，无需重建实例。
 */
@Slf4j
@Component
public class FilesApiClientManager {

    private final LLMClientManager llmClientManager;
    private final LLMWebClientManager webClientManager;
    private final ObjectMapper objectMapper;

    /** 实例缓存：key = providerCode::baseUrl（baseUrl 变更即重建） */
    private final Map<String, FilesApiClient> clientCache = new ConcurrentHashMap<>();

    public FilesApiClientManager(LLMClientManager llmClientManager,
                                 LLMWebClientManager webClientManager,
                                 ObjectMapper objectMapper) {
        this.llmClientManager = llmClientManager;
        this.webClientManager = webClientManager;
        this.objectMapper = objectMapper;
    }

    /**
     * 按 Provider Code 解析 FilesApiClient
     *
     * @param providerCode Provider 编码（如 "deepseek"）
     * @return 对应客户端；Provider 不存在 / 未启用 / template 未适配时返回 null（调用方按"不支持"处理）
     */
    public FilesApiClient resolveClientByCode(String providerCode) {
        if (providerCode == null || providerCode.isBlank()) {
            return null;
        }
        ProviderConfig config = llmClientManager.getProviderConfig(providerCode);
        if (config == null) {
            log.debug("Provider [{}] 不存在或未启用，无法解析 FilesApiClient", providerCode);
            return null;
        }
        String cacheKey = config.getCode() + "::" + config.getBaseUrl();
        return clientCache.computeIfAbsent(cacheKey, key -> createClient(config));
    }

    /**
     * 能力查询：指定 Provider 是否支持 Files API（供前端能力判断 / 注入降级使用）
     */
    public boolean supportsFilesApi(String providerCode) {
        try {
            return resolveClientByCode(providerCode) != null;
        } catch (Exception e) {
            log.debug("Files API 能力查询异常 providerCode={}: {}", providerCode, e.getMessage());
            return false;
        }
    }

    /**
     * 清除指定 Provider 的实例缓存（配置变更后可选调用）
     */
    public void evict(String providerCode) {
        if (providerCode == null) {
            return;
        }
        clientCache.keySet().removeIf(key -> key.startsWith(providerCode + "::"));
    }

    /**
     * 清除全部实例缓存
     */
    public void evictAll() {
        clientCache.clear();
    }

    // ==================== 内部方法 ====================

    /**
     * 按 requestTemplate 创建客户端；未适配模板返回 null（不缓存失败态，下次 resolve 会重新判断）
     */
    private FilesApiClient createClient(ProviderConfig config) {
        String template = config.getRequestTemplate();
        if (template == null || template.isBlank()) {
            template = config.getCode();
        }
        return switch (template.toLowerCase()) {
            case "deepseek" -> {
                var wc = webClientManager.getOrCreate(config);
                yield new DeepSeekFilesApiClient(wc, objectMapper, config.getCode());
            }
            case "anthropic" -> {
                // P3：Anthropic 兼容风格（/v1/files + x-api-key + anthropic-beta 头；baseUrl 需含 /anthropic 前缀）
                var wc = webClientManager.getOrCreate(config);
                yield new AnthropicFilesApiClient(wc, objectMapper, config.getCode());
            }
            default -> {
                // 未适配厂商：返回 null，上层降级处理（不抛异常、不报 500）
                log.debug("Provider [{}] template={} 尚未适配 Files API", config.getCode(), template);
                yield null;
            }
        };
    }
}
