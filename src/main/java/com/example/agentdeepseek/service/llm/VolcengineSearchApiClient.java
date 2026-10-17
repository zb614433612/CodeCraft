package com.example.agentdeepseek.service.llm;

import com.example.agentdeepseek.model.entity.ProviderConfig;
import com.example.agentdeepseek.util.ToolContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 火山引擎「豆包搜索 Custom 版」（联网搜索）API 客户端。
 *
 * <h3>接口</h3>
 * <pre>
 * POST https://open.feedcoopapi.com/search_api/web_search
 * Authorization: Bearer &lt;联网搜索 API Key&gt;
 * {"Query":"...","SearchType":"web","Count":10,"Filter":{"NeedContent":true,"NeedUrl":true}}
 * </pre>
 * 官方文档：<a href="https://www.volcengine.com/docs/85508/1650263">豆包搜索 Custom 版</a>
 *
 * <h3>启用条件（自动检测，无需额外开关）</h3>
 * <ol>
 *   <li>当前工具上下文的 LLM Provider 是火山引擎（请求模板 volcengine/ark/doubao，或 baseUrl 域名为 *.volces.com）；</li>
 *   <li>该 Provider 的「扩展配置」中配置了 {@code searchApiKey}（联网搜索控制台的 Key，与方舟 Ark Key 不通用），
 *       或环境变量 {@code VOLCENGINE_SEARCH_API_KEY} 已设置且非占位符。</li>
 * </ol>
 *
 * <p><b>职责边界</b>：本类只负责「调用 + 解析」，不做回退决策——调用失败抛出异常，
 * 由 {@code SearchTool} / {@code WebFetchTool} 捕获后回退原有 Bing/百度/Jina/本地抓取链路，保证工具始终可用。</p>
 */
@Slf4j
@Component
public class VolcengineSearchApiClient {

    /** 联网搜索 API 端点（API Key 接入方式） */
    private static final String ENDPOINT = "https://open.feedcoopapi.com/search_api/web_search";

    /** 环境变量兜底（与火山文档的 WEB_SEARCH_API_KEY 习惯保持一致） */
    private static final String ENV_KEY_PRIMARY = "VOLCENGINE_SEARCH_API_KEY";
    private static final String ENV_KEY_FALLBACK = "WEB_SEARCH_API_KEY";

    /** 火山引擎请求模板标识（含历史别名） */
    private static final Set<String> TEMPLATES_VOLCENGINE = Set.of("volcengine", "ark", "doubao");

    /** 掩码占位符（与 LLMProviderController 脱敏保持一致） */
    private static final String MASK = "****";

    /** 联网搜索最大返回条数（官方限制 50） */
    private static final int MAX_COUNT = 50;

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(8);
    private static final Duration SEARCH_TIMEOUT = Duration.ofSeconds(25);

    private final LLMClientManager llmClientManager;
    private final ObjectMapper objectMapper;

    public VolcengineSearchApiClient(LLMClientManager llmClientManager, ObjectMapper objectMapper) {
        this.llmClientManager = llmClientManager;
        this.objectMapper = objectMapper;
    }

    // ==================== 可用性判断 ====================

    /**
     * 当前会话是否可使用火山联网搜索（火山 Provider + 已配置搜索 Key）。
     */
    public boolean isAvailable() {
        return resolveSearchApiKey() != null;
    }

    /**
     * 解析联网搜索 API Key。
     * <p>判定依据是「Provider 模板/域名 + 凭证有效性」，与 providerCode 名称无关，避免用户自定义 code 时失效。</p>
     *
     * @return 有效 Key；不满足条件返回 null
     */
    private String resolveSearchApiKey() {
        String code = ToolContext.getProviderCode();
        if (code == null || code.isBlank()) {
            return null;
        }
        ProviderConfig config = llmClientManager.getProviderConfig(code);
        if (config == null || !isVolcengineProvider(config)) {
            return null;
        }
        String key = readExtraValue(config.getExtraConfig(), "searchApiKey");
        if (isUsableKey(key)) {
            return key.trim();
        }
        String env = System.getenv(ENV_KEY_PRIMARY);
        if (!isUsableKey(env)) {
            env = System.getenv(ENV_KEY_FALLBACK);
        }
        return isUsableKey(env) ? env.trim() : null;
    }

    private boolean isVolcengineProvider(ProviderConfig config) {
        String template = config.getRequestTemplate();
        if (template == null || template.isBlank()) {
            template = config.getCode();
        }
        if (template != null && TEMPLATES_VOLCENGINE.contains(template.toLowerCase(Locale.ROOT))) {
            return true;
        }
        String host = hostOf(config.getBaseUrl());
        return host != null && host.toLowerCase(Locale.ROOT).endsWith("volces.com");
    }

    private static boolean isUsableKey(String key) {
        return key != null && !key.isBlank() && !MASK.equals(key.trim()) && !key.contains(MASK);
    }

    // ==================== 联网搜索 ====================

    /**
     * 调用联网搜索接口。
     *
     * @param query 搜索词（官方限制 1~100 字符，超长自动截断）
     * @param count 期望条数（1~50）
     * @return 结果列表（可能为空）；未启用时返回 null
     * @throws RuntimeException 调用失败（由调用方回退原有搜索）
     */
    public List<WebHit> search(String query, int count) {
        String apiKey = resolveSearchApiKey();
        if (apiKey == null) {
            return null;
        }
        return search(query, count, apiKey);
    }

    private List<WebHit> search(String query, int count, String apiKey) {
        String effectiveQuery = query == null ? "" : query.trim();
        if (effectiveQuery.length() > 100) {
            effectiveQuery = effectiveQuery.substring(0, 100);
        }
        int effectiveCount = Math.max(1, Math.min(count, MAX_COUNT));

        ObjectNode body = objectMapper.createObjectNode();
        body.put("Query", effectiveQuery);
        body.put("SearchType", "web");
        body.put("Count", effectiveCount);
        ObjectNode filter = objectMapper.createObjectNode();
        // 让官方接口直接返回正文：模型拿到更完整的信息，减少二次抓取
        filter.put("NeedContent", true);
        filter.put("NeedUrl", true);
        body.set("Filter", filter);

        String json = post(body.toString(), apiKey);
        List<WebHit> hits = parseHits(json);
        log.info("火山联网搜索完成: query=\"{}\", 结果数={}", effectiveQuery, hits.size());
        return hits;
    }

    /**
     * 抓取指定 URL 的正文（best-effort）。
     *
     * <p>火山暂无独立的网页抓取 OpenAPI，这里复用联网搜索的正文能力：
     * 以 URL 的域名 + 路径关键词作为查询词检索，命中同一 URL 时直接返回其 Content/Summary。
     * 未命中返回 null，由调用方回退 Jina / 本地抓取链路。</p>
     *
     * @param url 目标网页（http/https）
     * @return 抓取结果；未启用或未命中返回 null
     * @throws RuntimeException 调用失败（由调用方回退原有抓取）
     */
    public FetchResult fetchByUrl(String url) {
        String apiKey = resolveSearchApiKey();
        if (apiKey == null || url == null || url.isBlank()) {
            return null;
        }
        String query = buildQueryFromUrl(url);
        if (query.isBlank()) {
            return null;
        }
        List<WebHit> hits = search(query, 10, apiKey);
        for (WebHit hit : hits) {
            if (!sameUrl(hit.url(), url)) {
                continue;
            }
            String content = hit.content();
            if (content == null || content.isBlank()) {
                content = hit.summary();
            }
            if (content != null && !content.isBlank()) {
                log.info("火山联网搜索命中目标页面: url={}, 正文={} 字符", url, content.length());
                return new FetchResult(hit.title(), content);
            }
        }
        log.info("火山联网搜索未命中目标页面，交回原有抓取链路: url={}", url);
        return null;
    }

    // ==================== 内部实现 ====================

    private String post(String body, String apiKey) {
        HttpRequest request = HttpRequest.newBuilder(URI.create(ENDPOINT))
                .timeout(SEARCH_TIMEOUT)
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + apiKey)
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();
        try {
            HttpClient client = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build();
            HttpResponse<String> response = client.send(request,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            String responseBody = response.body();
            if (response.statusCode() / 100 != 2) {
                throw new RuntimeException("HTTP " + response.statusCode() + " - " + describeError(responseBody));
            }
            JsonNode error = objectMapper.readTree(responseBody).path("ResponseMetadata").path("Error");
            if (!error.isMissingNode() && !error.isNull() && error.hasNonNull("Code")) {
                throw new RuntimeException(error.path("Code").asText() + " - "
                        + error.path("Message").asText(""));
            }
            return responseBody;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("联网搜索被中断", e);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("调用火山联网搜索失败: " + e.getMessage(), e);
        }
    }

    /**
     * 解析搜索响应（Result.WebResults）。
     * <p>包级可见：便于单元测试覆盖字段名与官方文档不一致导致解析为空的问题。</p>
     */
    List<WebHit> parseHits(String json) {
        List<WebHit> hits = new ArrayList<>();
        try {
            JsonNode root = objectMapper.readTree(json).path("Result");
            JsonNode results = root.path("WebResults");
            if (results.isArray()) {
                for (JsonNode node : results) {
                    String url = node.path("Url").asText("");
                    if (url.isBlank()) {
                        continue;
                    }
                    hits.add(new WebHit(
                            node.path("Title").asText(""),
                            url,
                            node.path("Snippet").asText(""),
                            node.path("Summary").asText(""),
                            node.path("Content").asText(""),
                            node.path("SiteName").asText(""),
                            node.path("PublishTime").asText(""),
                            node.path("RankScore").asDouble(0),
                            node.path("AuthInfoLevel").asInt(-1)
                    ));
                }
            }
            return hits;
        } catch (Exception e) {
            throw new RuntimeException("解析火山联网搜索响应失败: " + e.getMessage(), e);
        }
    }

    /** 从 URL 提取检索关键词：域名 + 路径末段（去扩展名），控制在 100 字符内 */
    static String buildQueryFromUrl(String url) {
        try {
            URI uri = URI.create(url.trim());
            String host = uri.getHost() == null ? "" : uri.getHost();
            if (host.isBlank()) {
                // 无主机名（非法/相对地址）：不构造关键词，交回原有抓取链路
                return "";
            }
            String path = uri.getPath() == null ? "" : uri.getPath();
            String last = path;
            int slash = path.lastIndexOf('/');
            if (slash >= 0) {
                last = path.substring(slash + 1);
            }
            last = URLDecoder.decode(last, StandardCharsets.UTF_8);
            int dot = last.lastIndexOf('.');
            if (dot > 0) {
                last = last.substring(0, dot);
            }
            last = last.replaceAll("[-_]+", " ").trim();
            String query = (host + " " + last).trim();
            return query.length() > 100 ? query.substring(0, 100) : query;
        } catch (Exception e) {
            return "";
        }
    }

    private static boolean sameUrl(String a, String b) {
        if (a == null || b == null) {
            return false;
        }
        return normalizeUrl(a).equals(normalizeUrl(b));
    }

    private static String normalizeUrl(String url) {
        String normalized = url.trim().replaceAll("/+$", "");
        int hash = normalized.indexOf('#');
        return hash >= 0 ? normalized.substring(0, hash) : normalized;
    }

    private static String hostOf(String baseUrl) {
        if (baseUrl == null || baseUrl.isBlank()) {
            return null;
        }
        try {
            return URI.create(baseUrl.trim()).getHost();
        } catch (Exception e) {
            return null;
        }
    }

    /** 读取扩展配置中的字符串字段（非法 JSON 视为未配置） */
    private String readExtraValue(String extraConfig, String field) {
        if (extraConfig == null || extraConfig.isBlank()) {
            return null;
        }
        try {
            JsonNode node = objectMapper.readTree(extraConfig);
            JsonNode value = node.get(field);
            return value == null || value.isNull() ? null : value.asText();
        } catch (Exception e) {
            log.warn("扩展配置不是合法 JSON，已忽略: {}", e.getMessage());
            return null;
        }
    }

    private String describeError(String responseBody) {
        if (responseBody == null || responseBody.isBlank()) {
            return "(空响应)";
        }
        try {
            JsonNode error = objectMapper.readTree(responseBody).path("ResponseMetadata").path("Error");
            if (error.hasNonNull("Code")) {
                return error.path("Code").asText() + " - " + error.path("Message").asText("");
            }
        } catch (Exception ignored) {
            // 非 JSON 错误体，走截断兜底
        }
        return responseBody.length() > 200 ? responseBody.substring(0, 200) + "..." : responseBody;
    }

    // ==================== 数据结构 ====================

    /** 联网搜索结果单条 */
    public record WebHit(String title,
                         String url,
                         String snippet,
                         String summary,
                         String content,
                         String siteName,
                         String publishedAt,
                         double rankScore,
                         int authLevel) {

        /**
         * 供模型阅读的摘要：优先长摘要 summary（500~1000 字），退化为 snippet（约 200 字），
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

    /** 抓取结果 */
    public record FetchResult(String title, String content) {
    }
}
