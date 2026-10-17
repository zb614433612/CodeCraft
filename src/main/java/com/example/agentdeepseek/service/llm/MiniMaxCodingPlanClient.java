package com.example.agentdeepseek.service.llm;

import com.example.agentdeepseek.model.entity.ProviderConfig;
import com.example.agentdeepseek.util.ToolContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.net.URI;
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
 * MiniMax「Coding Plan / Token Plan」平台能力客户端：联网搜索、图片理解、套餐额度。
 *
 * <h3>官方接口（均与 Coding Plan / Token Plan Key 配套，Bearer 认证）</h3>
 * <ul>
 *   <li><b>联网搜索</b>：{@code POST {host}/v1/coding_plan/search}，body {@code {"q":"..."}}，
 *       响应 {@code {organic:[{title,link,snippet,date}], related_searches:[...], base_resp:{...}}}
 *       （官方 MCP：MiniMax-Coding-Plan-MCP）</li>
 *   <li><b>图片理解</b>：{@code POST {host}/v1/coding_plan/vlm}，body {@code {"prompt":"...","image_url":"<url|data:base64>"}}，
 *       响应 {@code {content:"..."}}；支持 JPEG / PNG / WebP</li>
 *   <li><b>套餐额度</b>：{@code GET {host}/v1/token_plan/remains}，响应 {@code {model_remains:[...], base_resp:{...}}}，
 *       每项含 5 小时窗口与周窗口的剩余计数/百分比与重置时间</li>
 * </ul>
 *
 * <h3>区域（host）选择</h3>
 * <p>优先跟随 Provider 的 baseUrl 域名：{@code *.minimaxi.com → 国内}，{@code *.minimax.io → 国际}；
 * 也可用扩展配置 {@code apiHost} 显式覆盖；无法判断时默认国内。</p>
 *
 * <h3>启用条件（自动检测）</h3>
 * <ol>
 *   <li>当前工具上下文的 LLM Provider 是 MiniMax（模板 minimax*，或 baseUrl 域名命中 minimax 官方域名）；</li>
 *   <li>存在可用 Key：{@code extraConfig.codingPlanKey} &gt; Provider apiKey &gt;
 *       环境变量 {@code MINIMAX_CODE_PLAN_KEY} / {@code MINIMAX_CODING_API_KEY}。</li>
 * </ol>
 *
 * <p><b>职责边界</b>：只负责「调用 + 解析」，失败抛出异常，由调用方回退原有链路（搜索回退 Bing/百度；
 * 额度查询失败仅不展示，不影响对话）。</p>
 */
@Slf4j
@Component
public class MiniMaxCodingPlanClient {

    /** 火山/方舟同款掩码占位符（列表接口脱敏返回值） */
    private static final String MASK = "****";

    /** MiniMax 模板标识（含区域别名） */
    private static final Set<String> TEMPLATES_MINIMAX = Set.of("minimax", "minimax-cn", "minimax-intl", "minimax-global");

    /** 国内 / 国际官方域名后缀 */
    private static final String CN_HOST_SUFFIX = "minimaxi.com";
    private static final String GLOBAL_HOST_SUFFIX = "minimax.io";

    /** 默认 host（无法从 baseUrl 判断时使用国内站） */
    private static final String DEFAULT_CN_HOST = "https://api.minimaxi.com";
    private static final String DEFAULT_GLOBAL_HOST = "https://api.minimax.io";

    /** 接口路径 */
    private static final String SEARCH_PATH = "/v1/coding_plan/search";
    private static final String VLM_PATH = "/v1/coding_plan/vlm";
    private static final String QUOTA_PATH = "/v1/token_plan/remains";

    /** 官方 MCP 使用的来源标记（便于服务端识别调用方） */
    private static final String API_SOURCE_HEADER = "MM-API-Source";
    private static final String API_SOURCE_VALUE = "Minimax-MCP";

    /** 环境变量兜底 */
    private static final String ENV_KEY_PRIMARY = "MINIMAX_CODE_PLAN_KEY";
    private static final String ENV_KEY_FALLBACK = "MINIMAX_CODING_API_KEY";

    /** 搜索接口支持的返回条数上限（官方：1~10） */
    private static final int MAX_SEARCH_COUNT = 10;

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(8);
    private static final Duration SEARCH_TIMEOUT = Duration.ofSeconds(25);
    private static final Duration VLM_TIMEOUT = Duration.ofSeconds(60);
    private static final Duration QUOTA_TIMEOUT = Duration.ofSeconds(15);

    private final LLMClientManager llmClientManager;
    private final ObjectMapper objectMapper;

    public MiniMaxCodingPlanClient(LLMClientManager llmClientManager, ObjectMapper objectMapper) {
        this.llmClientManager = llmClientManager;
        this.objectMapper = objectMapper;
    }

    // ==================== 可用性判断 ====================

    /** 当前会话是否可使用 MiniMax 平台能力（MiniMax Provider + 有可用 Key）。 */
    public boolean isAvailable() {
        return resolveContext() != null;
    }

    /**
     * 解析调用上下文（Provider 配置 + Key + host）。
     *
     * @return 上下文；不满足条件返回 null
     */
    private MiniMaxContext resolveContext() {
        String code = ToolContext.getProviderCode();
        if (code == null || code.isBlank()) {
            return null;
        }
        ProviderConfig config = llmClientManager.getProviderConfig(code);
        if (config == null || !isMiniMaxProvider(config)) {
            return null;
        }
        JsonNode extra = parseExtra(config.getExtraConfig());
        String key = firstUsableKey(
                text(extra, "codingPlanKey", "coding_plan_key", "searchApiKey"),
                config.getApiKey(),
                System.getenv(ENV_KEY_PRIMARY),
                System.getenv(ENV_KEY_FALLBACK));
        if (key == null) {
            return null;
        }
        String host = resolveHost(config, extra);
        return new MiniMaxContext(config.getCode(), key, host);
    }

    private boolean isMiniMaxProvider(ProviderConfig config) {
        String template = config.getRequestTemplate();
        if (template == null || template.isBlank()) {
            template = config.getCode();
        }
        if (template != null && TEMPLATES_MINIMAX.contains(template.toLowerCase(Locale.ROOT))) {
            return true;
        }
        String host = hostOf(config.getBaseUrl());
        return host != null && (host.endsWith(CN_HOST_SUFFIX) || host.endsWith(GLOBAL_HOST_SUFFIX));
    }

    /** 解析区域 host：扩展配置 apiHost &gt; baseUrl 域名 &gt; 默认国内 */
    String resolveHost(ProviderConfig config, JsonNode extra) {
        String override = text(extra, "apiHost", "api_host", "host");
        if (override != null && !override.isBlank()) {
            return trimTrailingSlash(override);
        }
        String baseUrl = config == null ? null : config.getBaseUrl();
        String host = hostOf(baseUrl);
        if (host != null) {
            if (host.endsWith(GLOBAL_HOST_SUFFIX)) {
                return baseUrlScheme(baseUrl) + "//" + host;
            }
            if (host.endsWith(CN_HOST_SUFFIX)) {
                return baseUrlScheme(baseUrl) + "//" + host;
            }
        }
        return DEFAULT_CN_HOST;
    }

    // ==================== 联网搜索 ====================

    /**
     * 调用 MiniMax Coding Plan 搜索接口。
     *
     * @param query 搜索词（官方建议 3~5 个关键词）
     * @param count 期望条数（1~10，超出裁剪）
     * @return 搜索结果；未启用时返回 null
     * @throws RuntimeException 调用失败（由调用方回退原有搜索）
     */
    public List<WebHit> search(String query, int count) {
        MiniMaxContext ctx = resolveContext();
        if (ctx == null) {
            return null;
        }
        String effectiveQuery = query == null ? "" : query.trim();
        if (effectiveQuery.isEmpty()) {
            return List.of();
        }
        int effectiveCount = Math.max(1, Math.min(count, MAX_SEARCH_COUNT));

        ObjectNode body = objectMapper.createObjectNode();
        body.put("q", effectiveQuery);

        String json = post(ctx, SEARCH_PATH, body.toString(), SEARCH_TIMEOUT);
        List<WebHit> hits = parseSearchHits(json);
        if (hits.size() > effectiveCount) {
            hits = new ArrayList<>(hits.subList(0, effectiveCount));
        }
        log.info("MiniMax 联网搜索完成: query=\"{}\", 结果数={}", effectiveQuery, hits.size());
        return hits;
    }

    /**
     * 解析搜索响应。
     * <p>官方结构：{@code {organic:[{title,link,snippet,date}], related_searches, base_resp}}；
     * 同时兼容 {@code data.organic} / {@code results} 等包装形态。</p>
     *
     * <p>包级可见：便于单元测试覆盖字段名漂移。</p>
     */
    List<WebHit> parseSearchHits(String json) {
        List<WebHit> hits = new ArrayList<>();
        try {
            JsonNode root = objectMapper.readTree(json);
            JsonNode organic = root.path("organic");
            if (!organic.isArray()) {
                organic = root.path("data").path("organic");
            }
            if (!organic.isArray()) {
                organic = root.path("results");
            }
            if (!organic.isArray()) {
                return hits;
            }
            for (JsonNode node : organic) {
                String url = node.path("link").asText(node.path("url").asText(""));
                String title = node.path("title").asText("");
                String snippet = node.path("snippet").asText(node.path("description").asText(""));
                String date = node.path("date").asText("");
                if (url.isBlank() && title.isBlank()) {
                    continue;
                }
                hits.add(new WebHit(title, url, snippet, date));
            }
            return hits;
        } catch (Exception e) {
            throw new RuntimeException("解析 MiniMax 搜索响应失败: " + e.getMessage(), e);
        }
    }

    // ==================== 图片理解（VLM） ====================

    /**
     * 调用 MiniMax 图片理解接口（JPEG / PNG / WebP）。
     *
     * @param prompt     分析指令
     * @param imageUrl   图片公网 URL 或 data:image/...;base64,... 内联数据
     * @return 模型对图片的文字结论；未启用时返回 null
     * @throws RuntimeException 调用失败
     */
    public String understandImage(String prompt, String imageUrl) {
        MiniMaxContext ctx = resolveContext();
        if (ctx == null) {
            return null;
        }
        if (prompt == null || prompt.isBlank() || imageUrl == null || imageUrl.isBlank()) {
            throw new IllegalArgumentException("图片理解需要 prompt 与 image_url");
        }
        ObjectNode body = objectMapper.createObjectNode();
        body.put("prompt", prompt);
        body.put("image_url", imageUrl);

        String json = post(ctx, VLM_PATH, body.toString(), VLM_TIMEOUT);
        return parseVlmContent(json);
    }

    /** 解析 VLM 响应（官方：{@code {content:"..."}}，兼容 base_resp 错误） */
    String parseVlmContent(String json) {
        try {
            JsonNode root = objectMapper.readTree(json);
            checkBaseResp(root, "图片理解");
            String content = root.path("content").asText("");
            if (content.isBlank()) {
                throw new RuntimeException("MiniMax 图片理解返回空内容");
            }
            return content;
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("解析 MiniMax 图片理解响应失败: " + e.getMessage(), e);
        }
    }

    // ==================== 套餐额度 ====================

    /**
     * 查询 MiniMax Token Plan 套餐额度（5 小时窗口 + 周窗口）。
     *
     * @return 额度快照；未启用时返回 null
     * @throws RuntimeException 调用失败（由调用方转成"不支持/不可用"提示）
     */
    public QuotaSnapshot queryQuota() {
        MiniMaxContext ctx = resolveContext();
        if (ctx == null) {
            return null;
        }
        String json = get(ctx, QUOTA_PATH, QUOTA_TIMEOUT);
        QuotaSnapshot snapshot = parseQuota(json);
        log.info("MiniMax 额度查询完成: provider={}, 剩余={}%, 重置={}",
                ctx.providerCode(), snapshot.remainingPercent(), snapshot.resetAt());
        return snapshot;
    }

    /**
     * 解析额度响应。
     * <p>
     * 注意字段语义（社区实测）：{@code current_interval_usage_count} 实际表示<b>剩余</b>次数而非已用；
     * 百分比字段 {@code current_interval_remaining_percent} 为剩余百分比。优先用百分比，计数做兜底。
     * </p>
     *
     * <p>包级可见：便于单元测试覆盖。</p>
     */
    QuotaSnapshot parseQuota(String json) {
        try {
            JsonNode root = objectMapper.readTree(json);
            checkBaseResp(root, "额度查询");
            JsonNode rows = root.path("model_remains");
            if (!rows.isArray() || rows.isEmpty()) {
                throw new RuntimeException("MiniMax 额度响应缺少 model_remains");
            }
            // 优先 chat 通用桶（general），否则取第一项
            JsonNode row = rows.get(0);
            for (JsonNode node : rows) {
                String name = node.path("model_name").asText(node.path("model").asText(""));
                if ("general".equalsIgnoreCase(name)) {
                    row = node;
                    break;
                }
            }

            long total = row.path("current_interval_total_count").asLong(0);
            long remaining = row.path("current_interval_usage_count").asLong(-1);
            if (remaining < 0) {
                remaining = row.path("current_interval_remaining_count").asLong(-1);
            }
            if (remaining < 0) {
                remaining = row.path("current_interval_remains_count").asLong(-1);
            }
            double remainingPercent = row.path("current_interval_remaining_percent").asDouble(-1);
            if (remainingPercent < 0 && total > 0 && remaining >= 0) {
                remainingPercent = remaining * 100.0 / total;
            }
            if (remainingPercent < 0) {
                remainingPercent = 0;
            }
            long resetAt = row.path("end_time").asLong(0);
            if (resetAt <= 0) {
                resetAt = row.path("remains_time").asLong(0);
            }
            String planName = row.path("current_subscribe_title").asText(
                    row.path("plan_name").asText(row.path("plan").asText("")));

            return new QuotaSnapshot(
                    planName == null ? "" : planName,
                    Math.max(0, Math.min(100, remainingPercent)),
                    remaining,
                    total,
                    resetAt);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("解析 MiniMax 额度响应失败: " + e.getMessage(), e);
        }
    }

    // ==================== HTTP ====================

    private String post(MiniMaxContext ctx, String path, String body, Duration timeout) {
        HttpRequest request = HttpRequest.newBuilder(URI.create(ctx.host() + path))
                .timeout(timeout)
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + ctx.key())
                .header(API_SOURCE_HEADER, API_SOURCE_VALUE)
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();
        return send(request, path);
    }

    private String get(MiniMaxContext ctx, String path, Duration timeout) {
        HttpRequest request = HttpRequest.newBuilder(URI.create(ctx.host() + path))
                .timeout(timeout)
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + ctx.key())
                .header(API_SOURCE_HEADER, API_SOURCE_VALUE)
                .GET()
                .build();
        return send(request, path);
    }

    private String send(HttpRequest request, String path) {
        try {
            HttpClient client = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build();
            HttpResponse<String> response = client.send(request,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            String responseBody = response.body();
            if (response.statusCode() / 100 != 2) {
                throw new RuntimeException("HTTP " + response.statusCode() + " - " + describeError(responseBody));
            }
            return responseBody;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("MiniMax 请求被中断(" + path + ")", e);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("调用 MiniMax 接口失败(" + path + "): " + e.getMessage(), e);
        }
    }

    /** 校验 MiniMax 统一响应信封 base_resp.status_code（0 表示成功） */
    private void checkBaseResp(JsonNode root, String action) {
        JsonNode baseResp = root.path("base_resp");
        if (baseResp.isMissingNode() || baseResp.isNull()) {
            return;
        }
        int statusCode = baseResp.path("status_code").asInt(0);
        if (statusCode != 0) {
            throw new RuntimeException("MiniMax " + action + "失败: " + statusCode
                    + " - " + baseResp.path("status_msg").asText(""));
        }
    }

    private String describeError(String responseBody) {
        if (responseBody == null || responseBody.isBlank()) {
            return "(空响应)";
        }
        try {
            JsonNode root = objectMapper.readTree(responseBody);
            JsonNode baseResp = root.path("base_resp");
            if (baseResp.hasNonNull("status_code")) {
                return baseResp.path("status_code").asText() + " - " + baseResp.path("status_msg").asText("");
            }
        } catch (Exception ignored) {
            // 非 JSON 错误体，走截断兜底
        }
        return responseBody.length() > 200 ? responseBody.substring(0, 200) + "..." : responseBody;
    }

    // ==================== 工具方法 ====================

    private JsonNode parseExtra(String extraConfig) {
        if (extraConfig == null || extraConfig.isBlank()) {
            return objectMapper.createObjectNode();
        }
        try {
            JsonNode node = objectMapper.readTree(extraConfig);
            return node.isObject() ? node : objectMapper.createObjectNode();
        } catch (Exception e) {
            log.warn("MiniMax 扩展配置不是合法 JSON，已忽略: {}", e.getMessage());
            return objectMapper.createObjectNode();
        }
    }

    private static String text(JsonNode node, String... names) {
        if (node == null || node.isMissingNode()) {
            return null;
        }
        for (String name : names) {
            JsonNode value = node.get(name);
            if (value != null && !value.isNull()) {
                String text = value.asText();
                if (text != null && !text.isBlank()) {
                    return text;
                }
            }
        }
        return null;
    }

    /** 依次取第一个「可用」Key（非空、非掩码） */
    private static String firstUsableKey(String... candidates) {
        for (String candidate : candidates) {
            if (candidate == null || candidate.isBlank()) {
                continue;
            }
            String trimmed = candidate.trim();
            if (MASK.equals(trimmed) || trimmed.contains(MASK)) {
                continue;
            }
            return trimmed;
        }
        return null;
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

    private static String baseUrlScheme(String baseUrl) {
        if (baseUrl != null && baseUrl.trim().toLowerCase(Locale.ROOT).startsWith("http://")) {
            return "http:";
        }
        return "https:";
    }

    private static String trimTrailingSlash(String url) {
        return url.trim().replaceAll("/+$", "");
    }

    // ==================== 数据结构 ====================

    /** 调用上下文 */
    private record MiniMaxContext(String providerCode, String key, String host) {
    }

    /** 搜索结果单条 */
    public record WebHit(String title, String url, String snippet, String publishedAt) {
        /** 供模型阅读的描述（snippet 为空时退化为标题） */
        public String bestDescription() {
            if (snippet != null && !snippet.isBlank()) {
                return snippet;
            }
            return title == null ? "" : title;
        }
    }

    /** 套餐额度快照 */
    public record QuotaSnapshot(String planName,
                                double remainingPercent,
                                long remainingCount,
                                long totalCount,
                                long resetAt) {
    }
}
