package com.example.agentdeepseek.service.llm;

import com.example.agentdeepseek.model.entity.ProviderConfig;
import com.example.agentdeepseek.util.ToolContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * DeepSeek 官方「网页搜索 / 网页抓取」工具 API 客户端。
 *
 * <p><b>启用条件（自动检测，无需用户配置）</b>：当前工具执行上下文中使用的 LLM Provider
 * 必须是 <b>DeepSeek 官方 API</b>——即 Provider 的 baseUrl 主机名为 {@code api.deepseek.com}
 * 且已配置 API Key。使用第三方中转/自建兼容服务（baseUrl 不是官方域名）时不会启用，
 * 仍走原有的 Bing/百度抓取与 Jina/本地抓取链路，避免向非官方服务发送请求。</p>
 *
 * <p><b>官方接口</b>（与 Chat Completions 共用同一个 API Key，Bearer 认证）：</p>
 * <ul>
 *   <li>{@code POST /v1/tools/search} —— 参数 query / count(1~50) / need_content 等，
 *       返回结构化结果（title、url、snippet、summary、content、site_name、date、authority、rerank_score）；</li>
 *   <li>{@code POST /v1/tools/fetch} —— 参数 url，返回 title + Markdown 正文。</li>
 * </ul>
 *
 * <p>本类只负责「调用 + 解析」，不做回退决策：调用失败时抛出异常，由
 * {@code SearchTool} / {@code WebFetchTool} 捕获后回退到原有实现，保证工具始终可用。</p>
 */
@Slf4j
@Component
public class DeepSeekToolsApiClient {

    /** 官方域名：仅该域名下的 Provider 启用官方工具 API */
    private static final String OFFICIAL_HOST = "api.deepseek.com";
    /** Provider 未配置 Key 时的占位符（与 LLMWebClientManager 保持一致） */
    private static final String MUST_CONFIGURE_PLACEHOLDER = "__MUST_CONFIGURE_API_KEY__";

    private static final Duration SEARCH_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration FETCH_TIMEOUT = Duration.ofSeconds(45);

    private final LLMClientManager llmClientManager;
    private final LLMWebClientManager webClientManager;
    private final ObjectMapper objectMapper;

    public DeepSeekToolsApiClient(LLMClientManager llmClientManager,
                                  LLMWebClientManager webClientManager,
                                  ObjectMapper objectMapper) {
        this.llmClientManager = llmClientManager;
        this.webClientManager = webClientManager;
        this.objectMapper = objectMapper;
    }

    // ==================== 可用性判断 ====================

    /**
     * 当前会话使用的 Provider 是否为 DeepSeek 官方 API（决定搜索/抓取工具是否走官方接口）。
     *
     * @return true 表示可调用官方 tools API
     */
    public boolean isOfficialProvider() {
        return resolveOfficialConfig() != null;
    }

    /**
     * 解析当前工具上下文对应的官方 Provider 配置。
     *
     * <p>判定依据（与 providerCode 名称无关，避免用户自定义 code 时失效）：</p>
     * <ol>
     *   <li>ToolContext 中存在当前 Provider code（由 DeepSeekServiceImpl 在工具执行前注入）；</li>
     *   <li>该 Provider 的 baseUrl 主机名严格等于 api.deepseek.com；</li>
     *   <li>已配置有效 API Key（非空且非占位符）。</li>
     * </ol>
     *
     * @return 官方 Provider 配置；不满足条件返回 null
     */
    private ProviderConfig resolveOfficialConfig() {
        String code = ToolContext.getProviderCode();
        if (code == null || code.isBlank()) {
            return null;
        }
        ProviderConfig config = llmClientManager.getProviderConfig(code);
        if (config == null) {
            return null;
        }
        String baseUrl = config.getBaseUrl();
        if (baseUrl == null || baseUrl.isBlank()) {
            baseUrl = "https://" + OFFICIAL_HOST;
        }
        String host;
        try {
            host = URI.create(baseUrl.trim()).getHost();
        } catch (Exception e) {
            log.debug("Provider [{}] baseUrl 无法解析，跳过官方工具 API: {}", code, baseUrl);
            return null;
        }
        if (host == null || !OFFICIAL_HOST.equalsIgnoreCase(host)) {
            return null;
        }
        String apiKey = config.getApiKey();
        if (apiKey == null || apiKey.isBlank() || MUST_CONFIGURE_PLACEHOLDER.equals(apiKey)) {
            log.debug("Provider [{}] 为 DeepSeek 官方域名但未配置 API Key，跳过官方工具 API", code);
            return null;
        }
        return config;
    }

    // ==================== 官方接口调用 ====================

    /**
     * 调用官方网页搜索接口。
     *
     * @param query 搜索词（1~128 字符）
     * @param count 期望条数（1~50）
     * @return 结果列表（可能为空）；未启用官方 Provider 时返回 null
     * @throws RuntimeException 调用失败（由调用方回退原有搜索）
     */
    public List<SearchHit> search(String query, int count) {
        ProviderConfig config = resolveOfficialConfig();
        if (config == null) {
            return null;
        }
        ObjectNode body = objectMapper.createObjectNode();
        body.put("query", query);
        body.put("count", Math.max(1, Math.min(count, 50)));
        // 让官方接口直接返回正文：模型拿到更完整的信息，减少二次抓取
        body.put("need_content", true);

        String json = post(config, "/tools/search", body, SEARCH_TIMEOUT);
        List<SearchHit> hits = parseSearchHits(json);
        log.info("DeepSeek 官方搜索完成: query=\"{}\", 结果数={}", query, hits.size());
        return hits;
    }

    /**
     * 调用官方网页抓取接口（返回 Markdown 正文）。
     *
     * @param url 目标网页（http/https）
     * @return 抓取结果；未启用官方 Provider 时返回 null
     * @throws RuntimeException 调用失败（由调用方回退原有抓取）
     */
    public FetchResult fetch(String url) {
        ProviderConfig config = resolveOfficialConfig();
        if (config == null) {
            return null;
        }
        ObjectNode body = objectMapper.createObjectNode();
        body.put("url", url);

        String json = post(config, "/tools/fetch", body, FETCH_TIMEOUT);
        try {
            JsonNode root = objectMapper.readTree(json);
            String title = root.path("title").asText("");
            String content = root.path("content").asText("");
            log.info("DeepSeek 官方抓取完成: url={}, 标题={}, 正文={} 字符",
                    url, title.isBlank() ? "(无)" : title, content.length());
            return new FetchResult(title, content);
        } catch (Exception e) {
            throw new RuntimeException("解析官方抓取响应失败: " + e.getMessage(), e);
        }
    }

    // ==================== 内部实现 ====================

    /**
     * 发送 POST 请求。
     *
     * <p>复用 {@link LLMWebClientManager} 为该 Provider 建立的 WebClient：已内置 baseUrl、
     * Bearer 认证头、连接池与 429/5xx 重试，与 Chat 请求走同一套配置。</p>
     */
    private String post(ProviderConfig config, String suffix, ObjectNode body, Duration timeout) {
        String path = resolveApiPath(config, suffix);
        try {
            WebClient client = webClientManager.getOrCreate(config);
            String response = client.post()
                    .uri(path)
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(body.toString())
                    .retrieve()
                    .bodyToMono(String.class)
                    .block(timeout);
            if (response == null || response.isBlank()) {
                throw new RuntimeException("官方接口返回空响应");
            }
            return response;
        } catch (Exception e) {
            throw new RuntimeException("调用 DeepSeek 官方工具接口失败(" + path + "): " + e.getMessage(), e);
        }
    }

    /**
     * 拼接官方接口路径，兼容 baseUrl 已带 /v1 的配置：
     * https://api.deepseek.com → /v1/tools/search；
     * https://api.deepseek.com/v1 → /tools/search。
     *
     * <p>包级可见：便于单元测试覆盖（避免 baseUrl 写法差异导致 404）。</p>
     */
    String resolveApiPath(ProviderConfig config, String suffix) {
        String base = config.getBaseUrl();
        if (base == null) {
            base = "";
        }
        base = base.trim().replaceAll("/+$", "");
        return base.endsWith("/v1") ? suffix : "/v1" + suffix;
    }

    /**
     * 解析官方搜索响应。
     *
     * <p>包级可见：便于单元测试覆盖（防止字段名与官方文档不一致导致解析为空）。</p>
     */
    List<SearchHit> parseSearchHits(String json) {
        try {
            JsonNode root = objectMapper.readTree(json);
            JsonNode results = root.path("results");
            List<SearchHit> hits = new ArrayList<>();
            if (results.isArray()) {
                for (JsonNode node : results) {
                    String url = node.path("url").asText("");
                    if (url.isBlank()) {
                        continue;
                    }
                    hits.add(new SearchHit(
                            node.path("title").asText(""),
                            url,
                            node.path("snippet").asText(""),
                            node.path("summary").asText(""),
                            node.path("content").asText(""),
                            node.path("site_name").asText(""),
                            node.path("date").asText(""),
                            node.path("rerank_score").asDouble(0),
                            node.path("authority").asInt(-1)
                    ));
                }
            }
            return hits;
        } catch (Exception e) {
            throw new RuntimeException("解析官方搜索响应失败: " + e.getMessage(), e);
        }
    }

    // ==================== 数据结构 ====================

    /** 官方搜索结果单条 */
    public record SearchHit(String title,
                            String url,
                            String snippet,
                            String summary,
                            String content,
                            String siteName,
                            String publishedAt,
                            double rerankScore,
                            int authority) {

        /**
         * 供模型阅读的摘要：优先长摘要 summary（600~800 字），退化为 snippet（100~200 字），
         * 再退化为正文前 300 字。
         */
        public String bestDescription() {
            if (summary != null && !summary.isBlank()) {
                return summary;
            }
            if (snippet != null && !snippet.isBlank()) {
                return snippet;
            }
            if (content != null && !content.isBlank()) {
                return content.length() > 300 ? content.substring(0, 300) + "…" : content;
            }
            return "";
        }
    }

    /** 官方抓取结果 */
    public record FetchResult(String title, String content) {
    }
}
