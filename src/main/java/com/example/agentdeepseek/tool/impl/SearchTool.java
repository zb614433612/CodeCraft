package com.example.agentdeepseek.tool.impl;

import com.example.agentdeepseek.config.NetworkToolConfig;
import com.example.agentdeepseek.config.ProxyResolver;
import com.example.agentdeepseek.service.llm.DeepSeekToolsApiClient;
import com.example.agentdeepseek.service.llm.MiniMaxCodingPlanClient;
import com.example.agentdeepseek.service.llm.VolcengineSearchApiClient;
import com.example.agentdeepseek.tool.Tool;
import com.example.agentdeepseek.tool.permission.OperationCategory;
import com.example.agentdeepseek.tool.permission.ToolPermission;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.springframework.stereotype.Component;

import java.net.Proxy;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 互联网搜索工具（多源聚合 + 结果后处理版）
 *
 * <p>检索策略：按「搜索引擎优先级」顺序抓取，聚合去重后按质量重排，取前 N 条返回。</p>
 *
 * <p>相比早期版本的优化：</p>
 * <ol>
 *   <li>源升级：优先使用全球版 Bing（www.bing.com），规避中国特供版的合规/可访问性过滤；</li>
 *   <li>语言自适应：中文 query 偏好中国版 Bing（mkt=zh-CN），英文/技术 query 偏好全球版（mkt=en-US）；</li>
 *   <li>算符透传：空格编码为 %20（而非 +），保证 site:、"精确短语" 等算符不被破坏；</li>
 *   <li>多源聚合：主源不足时自动补充其他源；</li>
 *   <li>失败透明化：区分「连接超时 / 反爬验证 / 解析 0 条 / HTTP 异常」并回报给上层；</li>
 *   <li>结果后处理：URL 规范化去重、按域名限制刷屏、权威域提权 / 低质域降权；</li>
 *   <li>结构增强：每条结果附带真实域名（Bing 跳转链已解包）；</li>
 *   <li>同 query 短时缓存，降低重复请求与被反爬概率。</li>
 * </ol>
 */
@Slf4j
@Component
@ToolPermission(category = OperationCategory.NETWORK, description = "网络搜索")
public class SearchTool implements Tool {

    private static final int MAX_RESULT_TITLE_LENGTH = 150;
    private static final int MAX_RESULT_CONTENT_LENGTH = 300;

    /** 全球版 Bing（默认主源：无中国特供过滤） */
    private static final String BING_GLOBAL_URL = "https://www.bing.com/search";
    /** 中国版 Bing（中文 query 召回更佳，但带合规过滤） */
    private static final String BING_CN_URL = "https://cn.bing.com/search";
    /** 百度（国内备用） */
    private static final String BAIDU_URL = "https://www.baidu.com/s";

    /** 单域名最多保留的结果数（抑制单一站点刷屏） */
    private static final int MAX_RESULTS_PER_DOMAIN = 2;

    /** 权威域名特征（命中即提权） */
    private static final List<String> AUTHORITY_HINTS = List.of(
            "github.com", "gitlab.com", "stackoverflow.com", "stackexchange.com",
            "developer.mozilla.org", "docs.", "developer.", "devdocs.io",
            "wikipedia.org", "readthedocs.io", "arxiv.org", "acm.org", "ieee.org",
            "apache.org", "spring.io", "oracle.com", "microsoft.com", "python.org",
            "rust-lang.org", "golang.org", "nodejs.org", "npmjs.com", "maven.apache.org",
            "openai.com", "anthropic.com", "modelcontextprotocol.io"
    );

    private final ObjectMapper objectMapper;
    private final NetworkToolConfig config;
    private final ProxyResolver proxyResolver;
    private final String userAgent;
    /** DeepSeek 官方网页搜索 API（当前 Provider 为 DeepSeek 官网时优先使用） */
    private final DeepSeekToolsApiClient deepSeekToolsApi;

    /** 火山引擎联网搜索 API（当前 Provider 为火山方舟且配置了联网搜索 Key 时优先使用） */
    private final VolcengineSearchApiClient volcengineSearchApi;

    /** MiniMax Coding Plan 搜索 API（当前 Provider 为 MiniMax 且有可用 Key 时优先使用） */
    private final MiniMaxCodingPlanClient miniMaxSearchApi;

    /** 搜索结果短时缓存（key = 规范化 query + "|" + 期望条数） */
    private final Map<String, CacheEntry> searchCache = new ConcurrentHashMap<>();

    public SearchTool(ObjectMapper objectMapper, NetworkToolConfig config, ProxyResolver proxyResolver,
                      DeepSeekToolsApiClient deepSeekToolsApi,
                      VolcengineSearchApiClient volcengineSearchApi,
                      MiniMaxCodingPlanClient miniMaxSearchApi) {
        this.objectMapper = objectMapper;
        this.config = config;
        this.proxyResolver = proxyResolver;
        this.userAgent = config.getFetchUserAgent();
        this.deepSeekToolsApi = deepSeekToolsApi;
        this.volcengineSearchApi = volcengineSearchApi;
        this.miniMaxSearchApi = miniMaxSearchApi;
    }

    @Override
    public String getName() {
        return "web_search";
    }

    @Override
    public String getDescription() {
        return "【适用场景】获取最新新闻、实时数据、百科知识、技术问题解决方案等需要搜索引擎聚合的信息。"
                + "【与 web_fetch 的区别】web_search 返回多个结果的标题+摘要+链接（概览），web_fetch 读取某一个 URL 的完整页面内容（深入）。"
                + "【使用建议】"
                + "1) 先用本工具搜索找到目标页面，再用 web_fetch 深入了解其中某个结果。"
                + "2) 调研**协议规范 / 官方文档 / API 参考**类资料时，**优先直接用 web_fetch 打开官方站点**（如 docs.*、github.com 仓库页），"
                + "搜索通道对这类内容的召回不稳定（可能受搜索引擎合规过滤影响）。"
                + "3) 关键词越简短精确越好：**核心术语（英文技术名词 + 版本号）** 效果最佳，冗长自然语言问句效果较差。"
                + "4) 支持基本算符：site:domain.com、\"精确短语\"。"
                + "5) 中文内容自动优先中国版 Bing，英文/技术内容自动优先全球版 Bing，无需手动指定。"
                + "6) 网络自适应：检测到本机代理则走代理，否则直连国内源兜底，无需手动配置。"
                + "7) 当前会话使用 DeepSeek 官方 API 时自动改用 DeepSeek 官方网页搜索接口；使用火山方舟并配置了联网搜索 Key 时改用火山联网搜索；"
                + "使用 MiniMax 并配置了 Coding Plan Key 时改用 MiniMax 官方搜索（均为结构化结果），失败会自动回退站点抓取，无需手动指定。"
                + "搜索源为 DeepSeek 官方搜索 / 火山联网搜索 / MiniMax 搜索 / Bing 全球版 / Bing 中国版 / 百度（自动切换聚合），无需额外配置。";
    }

    @Override
    public JsonNode getParameters() {
        ObjectNode parameters = objectMapper.createObjectNode();
        parameters.put("type", "object");
        parameters.put("description", "搜索互联网信息");

        ObjectNode properties = objectMapper.createObjectNode();
        ObjectNode queryProperty = objectMapper.createObjectNode();
        queryProperty.put("type", "string");
        queryProperty.put("description", "【必填】搜索关键词。用简短精确的术语、多个词用空格分隔（如：Java 21 虚拟线程 最佳实践）；"
                + "支持基本算符（site:domain.com、\"精确短语\"）；避免整句自然语言问句（召回质量较差）。");
        properties.set("query", queryProperty);

        ObjectNode count = objectMapper.createObjectNode();
        count.put("type", "integer");
        count.put("description", "【可选】返回结果数量，默认 " + config.getSearchMaxResults() + "，最大 " + config.getSearchMaxResults() + "。仅在需要更多/更少结果时传，一般情况下用默认值即可");
        properties.set("count", count);

        parameters.set("properties", properties);
        parameters.putArray("required").add("query");
        return parameters;
    }

    @Override
    public String execute(JsonNode arguments) {
        String query = arguments.path("query").asText("").trim();
        if (query.isEmpty()) {
            return "【参数错误】query 参数不能为空。请提供搜索关键词，例如：{\"query\": \"Spring Boot 3 配置指南\"}";
        }

        int maxResults = arguments.path("count").asInt(config.getSearchMaxResults());
        maxResults = Math.max(1, Math.min(maxResults, config.getSearchMaxResults()));

        // 缓存命中
        String cacheKey = query + "|" + maxResults;
        String cached = getFromCache(cacheKey);
        if (cached != null) {
            log.info("网络搜索命中缓存: {}", query);
            return cached;
        }

        // ★ 火山引擎联网搜索优先：当前会话 Provider 为火山方舟且配置了联网搜索 Key 时，调用豆包搜索 Custom API
        //   （结构化结果 + 正文/摘要，质量高于站点抓取）；失败或无结果时自动回退原有 Bing/百度链路
        if (volcengineSearchApi != null && volcengineSearchApi.isAvailable()) {
            try {
                List<VolcengineSearchApiClient.WebHit> hits = volcengineSearchApi.search(query, maxResults);
                if (hits != null && !hits.isEmpty()) {
                    List<SearchResult> official = new ArrayList<>(hits.size());
                    for (VolcengineSearchApiClient.WebHit hit : hits) {
                        official.add(new SearchResult(hit.title(), hit.url(), hit.bestDescription()));
                    }
                    List<SearchResult> rankedOfficial = dedupRankAndLimit(official, maxResults);
                    if (!rankedOfficial.isEmpty()) {
                        String text = formatResults(rankedOfficial, List.of("火山引擎联网搜索 API"));
                        putCache(cacheKey, text);
                        return text;
                    }
                }
                log.info("火山引擎联网搜索无结果，回退站点抓取: {}", query);
            } catch (Exception e) {
                log.warn("火山引擎联网搜索失败，回退站点抓取: query={}, error={}", query, e.getMessage());
            }
        }

        // ★ MiniMax Coding Plan 搜索优先：当前 Provider 为 MiniMax 且有可用 Key 时，调用官方 /v1/coding_plan/search
        //   （结构化结果 organic[title/link/snippet/date]）；失败或无结果时自动回退原有 Bing/百度链路
        if (miniMaxSearchApi != null && miniMaxSearchApi.isAvailable()) {
            try {
                List<MiniMaxCodingPlanClient.WebHit> hits = miniMaxSearchApi.search(query, maxResults);
                if (hits != null && !hits.isEmpty()) {
                    List<SearchResult> official = new ArrayList<>(hits.size());
                    for (MiniMaxCodingPlanClient.WebHit hit : hits) {
                        official.add(new SearchResult(hit.title(), hit.url(), hit.bestDescription()));
                    }
                    List<SearchResult> rankedOfficial = dedupRankAndLimit(official, maxResults);
                    if (!rankedOfficial.isEmpty()) {
                        String text = formatResults(rankedOfficial, List.of("MiniMax 联网搜索 API"));
                        putCache(cacheKey, text);
                        return text;
                    }
                }
                log.info("MiniMax 联网搜索无结果，回退站点抓取: {}", query);
            } catch (Exception e) {
                log.warn("MiniMax 联网搜索失败，回退站点抓取: query={}, error={}", query, e.getMessage());
            }
        }

        // ★ DeepSeek 官方搜索优先：当前会话使用的 Provider 是 DeepSeek 官网 API 时，直接调用官方 /v1/tools/search
        //   （结构化结果 + 权威性/时效性打分，质量高于站点抓取）；失败或无结果时自动回退原有 Bing/百度链路
        if (deepSeekToolsApi != null && deepSeekToolsApi.isOfficialProvider()) {
            try {
                List<DeepSeekToolsApiClient.SearchHit> hits = deepSeekToolsApi.search(query, maxResults);
                if (hits != null && !hits.isEmpty()) {
                    List<SearchResult> official = new ArrayList<>(hits.size());
                    for (DeepSeekToolsApiClient.SearchHit hit : hits) {
                        official.add(new SearchResult(hit.title(), hit.url(), hit.bestDescription()));
                    }
                    // 复用现有去重/重排/单域限量逻辑，保持输出格式与站点抓取完全一致
                    List<SearchResult> rankedOfficial = dedupRankAndLimit(official, maxResults);
                    if (!rankedOfficial.isEmpty()) {
                        String text = formatResults(rankedOfficial, List.of("DeepSeek 官方网页搜索 API"));
                        putCache(cacheKey, text);
                        return text;
                    }
                }
                log.info("DeepSeek 官方搜索无结果，回退站点抓取: {}", query);
            } catch (Exception e) {
                log.warn("DeepSeek 官方搜索失败，回退站点抓取: query={}, error={}", query, e.getMessage());
            }
        }

        List<String> engineOrder = resolveEngineOrder(query);
        log.info("网络搜索: query=\"{}\", 网络模式={}, 引擎顺序={}", query, proxyResolver.describe(), engineOrder);

        List<SearchResult> collected = new ArrayList<>();
        List<String> outcomes = new ArrayList<>();

        // 多源检索：按优先级依次抓取，够用即停（兼顾质量与延迟）
        for (String engine : engineOrder) {
            if (collected.size() >= maxResults) {
                break;
            }
            EngineOutcome outcome = searchByEngine(engine, query);
            outcomes.add(outcome.summary);
            if (outcome.results != null && !outcome.results.isEmpty()) {
                collected.addAll(outcome.results);
            }
        }

        // 全部源均无结果 → 透明化回报各源状态
        if (collected.isEmpty()) {
            return buildNoResultMessage(query, outcomes);
        }

        // 去重 + 质量重排 + 单域限量
        List<SearchResult> ranked = dedupRankAndLimit(collected, maxResults);
        if (ranked.isEmpty()) {
            return buildNoResultMessage(query, outcomes);
        }
        String text = formatResults(ranked, outcomes);
        putCache(cacheKey, text);
        return text;
    }

    // ============================================================
    // 检索
    // ============================================================

    /**
     * 根据「当前网络模式 + query 语言」决定引擎优先级。
     *
     * <p>有代理时按配置顺序（全球版 Bing 优先，可拿更完整的英文结果）；
     * 检测不到代理、走国内直连兜底时只使用国内可达源，避免被墙源白白拖慢一次超时。</p>
     */
    private List<String> resolveEngineOrder(String query) {
        boolean proxyActive = proxyResolver.isProxyActive();
        List<String> configured = proxyActive ? config.getSearchEngines() : config.getSearchEnginesNoProxy();
        if (configured == null || configured.isEmpty()) {
            configured = proxyActive
                    ? List.of("bing-global", "bing-cn", "baidu")
                    : List.of("bing-cn", "baidu");
        }
        String preferred = containsChinese(query) ? "bing-cn" : "bing-global";
        List<String> ordered = new ArrayList<>();
        if (configured.contains(preferred)) {
            ordered.add(preferred);
        }
        for (String engine : configured) {
            if (!ordered.contains(engine)) {
                ordered.add(engine);
            }
        }
        return ordered;
    }

    private EngineOutcome searchByEngine(String engine, String query) {
        return switch (engine) {
            case "bing-global" -> fetchBing(query, BING_GLOBAL_URL, true, "Bing全球");
            case "bing-cn" -> fetchBing(query, BING_CN_URL, false, "Bing中国");
            case "baidu" -> fetchBaidu(query);
            default -> new EngineOutcome("未知搜索源[" + engine + "]", null);
        };
    }

    /** 抓取 Bing 结果页（global=true 使用全球版参数，否中国内版） */
    private EngineOutcome fetchBing(String query, String baseUrl, boolean global, String label) {
        try {
            // 语言自适应：中文 query 用 zh-CN，其余用 en-US 并强制英文结果（ensearch）
            boolean chineseQuery = containsChinese(query);
            String url = baseUrl + "?q=" + encodeQuery(query)
                    + (global
                        ? (chineseQuery ? "&mkt=zh-CN" : "&mkt=en-US&ensearch=1")
                        : "&mkt=zh-CN");

            Document doc = Jsoup.connect(url)
                    .userAgent(userAgent)
                    .header("Accept-Language", chineseQuery ? "zh-CN,zh;q=0.9,en;q=0.8" : "en-US,en;q=0.9,zh-CN;q=0.8")
                    .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                    .timeout(config.getFetchConnectTimeout())
                    .proxy(getProxy())
                    .get();

            if (isAntiBotPage(doc)) {
                return new EngineOutcome(label + "：触发反爬/验证页", null);
            }

            Elements items = doc.select("li.b_algo");
            if (items.isEmpty()) items = doc.select("#b_results .b_algo");
            if (items.isEmpty()) items = doc.select(".b_results > li.b_algo");
            if (items.isEmpty()) items = doc.select("li[data-bm]");
            if (items.isEmpty()) {
                return new EngineOutcome(label + "：页面已获取但解析 0 条（选择器可能失效）", null);
            }
            List<SearchResult> results = parseBingItems(items);
            return new EngineOutcome(label + "：解析 " + results.size() + " 条", results);

        } catch (SocketTimeoutException e) {
            return new EngineOutcome(label + "：连接超时", null);
        } catch (Exception e) {
            log.warn("{} 搜索失败: {}", label, e.getMessage());
            return new EngineOutcome(label + "：请求失败（" + e.getClass().getSimpleName() + "）", null);
        }
    }

    /** 抓取百度结果页（国内备用） */
    private EngineOutcome fetchBaidu(String query) {
        try {
            String url = BAIDU_URL + "?wd=" + encodeQuery(query);
            Document doc = Jsoup.connect(url)
                    .userAgent(userAgent)
                    .header("Accept-Language", "zh-CN,zh;q=0.9")
                    .timeout(config.getFetchConnectTimeout())
                    .proxy(getProxy())
                    .get();

            if (isAntiBotPage(doc)) {
                return new EngineOutcome("百度：触发反爬/验证页", null);
            }

            Elements items = doc.select("div.result, div.c-container");
            if (items.isEmpty()) items = doc.select("#content_left > div");
            if (items.isEmpty()) {
                return new EngineOutcome("百度：页面已获取但解析 0 条（选择器可能失效）", null);
            }

            List<SearchResult> results = new ArrayList<>();
            for (Element item : items) {
                Element titleEl = item.selectFirst("h3 a, .t a, a.c-title");
                if (titleEl == null) continue;
                String title = truncate(titleEl.text(), MAX_RESULT_TITLE_LENGTH);
                String rurl = normalizeUrl(titleEl.attr("href"));
                Element snippetEl = item.selectFirst(".c-abstract, .c-span-last, .c-color-text, .content-right_8Zs40");
                String desc = snippetEl != null ? truncate(snippetEl.text(), MAX_RESULT_CONTENT_LENGTH) : "";
                if (title.isEmpty() && rurl.isEmpty()) continue;
                results.add(new SearchResult(title, rurl, desc));
            }
            return new EngineOutcome("百度：解析 " + results.size() + " 条", results);

        } catch (SocketTimeoutException e) {
            return new EngineOutcome("百度：连接超时", null);
        } catch (Exception e) {
            log.warn("百度搜索失败: {}", e.getMessage());
            return new EngineOutcome("百度：请求失败（" + e.getClass().getSimpleName() + "）", null);
        }
    }

    private List<SearchResult> parseBingItems(Elements items) {
        List<SearchResult> results = new ArrayList<>();
        for (Element item : items) {
            Element titleEl = item.selectFirst("h2 a, h3 a, .t a, .c-title a");
            if (titleEl == null) continue;
            String title = truncate(titleEl.text(), MAX_RESULT_TITLE_LENGTH);
            String url = normalizeUrl(titleEl.attr("href"));
            Element snippetEl = item.selectFirst(".b_caption p, .b_lineclamp2, .b_paractl, .c-abstract, .c-span-last");
            String desc = snippetEl != null ? truncate(snippetEl.text(), MAX_RESULT_CONTENT_LENGTH) : "";
            if (title.isEmpty() && url.isEmpty()) continue;
            results.add(new SearchResult(title, url, desc));
        }
        return results;
    }

    // ============================================================
    // 结果后处理：去重 + 质量重排 + 单域限量
    // ============================================================

    private List<SearchResult> dedupRankAndLimit(List<SearchResult> raw, int maxResults) {
        for (SearchResult r : raw) {
            r.score = scoreOf(r);
        }

        // 去重：按规范化 URL（同 key 保留分数更高者）
        Map<String, SearchResult> deduped = new LinkedHashMap<>();
        for (SearchResult r : raw) {
            String key = canonicalKey(r);
            SearchResult exist = deduped.get(key);
            if (exist == null || r.score > exist.score) {
                deduped.put(key, r);
            }
        }

        // 排序：分数降序
        List<SearchResult> list = new ArrayList<>(deduped.values());
        list.sort((a, b) -> Integer.compare(b.score, a.score));

        // 单域限量：同域名最多 MAX_RESULTS_PER_DOMAIN 条，超出暂存待补
        List<SearchResult> chosen = new ArrayList<>();
        List<SearchResult> overflow = new ArrayList<>();
        Map<String, Integer> domainCount = new LinkedHashMap<>();
        for (SearchResult r : list) {
            String domain = r.domain.isEmpty() ? "(unknown)" : r.domain;
            int used = domainCount.getOrDefault(domain, 0);
            if (used < MAX_RESULTS_PER_DOMAIN) {
                domainCount.put(domain, used + 1);
                chosen.add(r);
                if (chosen.size() >= maxResults) break;
            } else {
                overflow.add(r);
            }
        }
        // 数量不足时用同域超额结果补齐
        for (SearchResult r : overflow) {
            if (chosen.size() >= maxResults) break;
            chosen.add(r);
        }
        return chosen;
    }

    /** 结果打分：权威域提权，低质域降权 */
    private int scoreOf(SearchResult r) {
        int score = 0;
        String domain = r.domain == null ? "" : r.domain.toLowerCase();
        for (String hint : AUTHORITY_HINTS) {
            if (domain.contains(hint)) {
                score += 3;
                break;
            }
        }
        List<String> lowQuality = config.getSearchLowQualityDomains();
        if (lowQuality != null) {
            for (String low : lowQuality) {
                if (low != null && !low.isBlank() && domain.contains(low.toLowerCase().trim())) {
                    score -= 5;
                    break;
                }
            }
        }
        return score;
    }

    /** 去重键：去掉协议/www/查询参数/末尾斜杠 */
    private String canonicalKey(SearchResult r) {
        String u = r.url == null ? "" : r.url;
        int q = u.indexOf('?');
        if (q >= 0) u = u.substring(0, q);
        int hash = u.indexOf('#');
        if (hash >= 0) u = u.substring(0, hash);
        u = u.replaceFirst("^https?://", "").replaceFirst("^www\\.", "");
        if (u.endsWith("/")) u = u.substring(0, u.length() - 1);
        if (!u.isEmpty()) return u.toLowerCase();
        return "title:" + (r.title == null ? "" : r.title.toLowerCase());
    }

    // ============================================================
    // 格式化与工具方法
    // ============================================================

    private String formatResults(List<SearchResult> results, List<String> outcomes) {
        StringBuilder sb = new StringBuilder();
        sb.append("--- 搜索结果 ---\n\n");
        int i = 1;
        for (SearchResult r : results) {
            sb.append(i++).append(". ").append(r.title).append("\n");
            if (!r.description.isEmpty()) {
                sb.append("   ").append(r.description).append("\n");
            }
            if (!r.url.isEmpty()) {
                sb.append("   链接: ").append(r.url).append("\n");
            }
            if (!r.domain.isEmpty()) {
                sb.append("   来源: ").append(r.domain).append("\n");
            }
            sb.append("\n");
        }
        if (outcomes != null && !outcomes.isEmpty()) {
            sb.append("（检索源状态：").append(String.join("；", outcomes)).append("）\n");
        }
        return sb.toString();
    }

    /** 无结果时的透明化提示：附各源状态 + 可操作建议 */
    private String buildNoResultMessage(String query, List<String> outcomes) {
        StringBuilder sb = new StringBuilder();
        sb.append("【无结果】未获取到与「").append(query).append("」相关的搜索结果。\n");
        sb.append("当前网络模式：").append(proxyResolver.describe()).append("\n");
        if (outcomes != null && !outcomes.isEmpty()) {
            sb.append("检索源状态：").append(String.join("；", outcomes)).append("\n");
        }
        sb.append("建议：1) 精简关键词，用核心术语（去掉整句描述）2) 尝试精确的英文关键词 3) "
                + "调研协议/规范/官方文档时，直接用 web_fetch 打开官网，不依赖搜索 4) "
                + "若提示超时/反爬，可先用 check_network 排查网络连通性");
        return sb.toString();
    }

    /** 算符安全编码：空格编码为 %20（避免 + 被解析为字面加号破坏 site:/引号 等算符） */
    private String encodeQuery(String query) {
        return URLEncoder.encode(query, StandardCharsets.UTF_8).replace("+", "%20");
    }

    /** 规范化 URL：解包 Bing 跳转链，暴露真实地址 */
    private String normalizeUrl(String url) {
        if (url == null || url.isBlank()) {
            return "";
        }
        String unwrapped = unwrapBingRedirect(url);
        return unwrapped.trim();
    }

    /** 解包 Bing 的 https://www.bing.com/ck/a?...&u=a1<base64url> 跳转链 */
    private String unwrapBingRedirect(String url) {
        int idx = url.indexOf("&u=a1");
        if (idx < 0) {
            return url;
        }
        try {
            String b64 = url.substring(idx + 5);
            int amp = b64.indexOf('&');
            if (amp >= 0) {
                b64 = b64.substring(0, amp);
            }
            // base64url 补齐 padding
            int pad = b64.length() % 4;
            if (pad == 2) b64 += "==";
            else if (pad == 3) b64 += "=";
            else if (pad == 1) return url;
            byte[] decoded = Base64.getUrlDecoder().decode(b64);
            String real = new String(decoded, StandardCharsets.UTF_8);
            return real.startsWith("http") ? real : url;
        } catch (Exception e) {
            return url;
        }
    }

    /** 提取域名（去掉 www.） */
    private static String extractDomain(String url) {
        if (url == null || url.isBlank()) {
            return "";
        }
        try {
            String host = URI.create(url).getHost();
            if (host == null) {
                return "";
            }
            return host.startsWith("www.") ? host.substring(4) : host;
        } catch (Exception e) {
            return "";
        }
    }

    /** 检测是否命中反爬/验证页 */
    private boolean isAntiBotPage(Document doc) {
        if (doc == null) {
            return false;
        }
        String title = doc.title() == null ? "" : doc.title().toLowerCase();
        String text = doc.text();
        if (text.length() > 3000) {
            text = text.substring(0, 3000);
        }
        String lower = text.toLowerCase();
        return title.contains("captcha") || title.contains("验证")
                || lower.contains("请输入验证码") || lower.contains("安全验证")
                || lower.contains("unusual traffic") || lower.contains("verify you are human")
                || lower.contains("are you a robot") || lower.contains("人机验证");
    }

    /** 是否含中日韩汉字（用于中文/英文检索策略分流） */
    private boolean containsChinese(String s) {
        if (s == null) {
            return false;
        }
        for (int i = 0; i < s.length(); i++) {
            if (Character.UnicodeScript.of(s.charAt(i)) == Character.UnicodeScript.HAN) {
                return true;
            }
        }
        return false;
    }

    private String getFromCache(String key) {
        if (config.getSearchCacheTtlSeconds() <= 0) {
            return null;
        }
        CacheEntry entry = searchCache.get(key);
        if (entry == null) {
            return null;
        }
        if (System.currentTimeMillis() > entry.expireAt) {
            searchCache.remove(key);
            return null;
        }
        return entry.text;
    }

    private void putCache(String key, String text) {
        if (config.getSearchCacheTtlSeconds() <= 0) {
            return;
        }
        if (searchCache.size() >= config.getSearchCacheMaxEntries()) {
            long now = System.currentTimeMillis();
            searchCache.entrySet().removeIf(e -> e.getValue().expireAt < now);
            if (searchCache.size() >= config.getSearchCacheMaxEntries()) {
                searchCache.clear();
            }
        }
        long ttl = config.getSearchCacheTtlSeconds() * 1000L;
        searchCache.put(key, new CacheEntry(text, System.currentTimeMillis() + ttl));
    }

    /** 当前应使用的代理（自动探测本地代理；探测不到则直连，即国内网络兜底） */
    private Proxy getProxy() {
        return proxyResolver.resolve();
    }

    private String truncate(String text, int maxLength) {
        if (text == null || text.isEmpty()) return "";
        if (text.length() <= maxLength) return text;
        return text.substring(0, maxLength) + "…";
    }

    // ============================================================
    // 内部数据结构
    // ============================================================

    /** 单条搜索结果 */
    private static class SearchResult {
        final String title;
        final String url;
        final String description;
        final String domain;
        int score;

        SearchResult(String title, String url, String description) {
            this.title = title == null ? "" : title;
            this.url = url == null ? "" : url;
            this.description = description == null ? "" : description;
            this.domain = extractDomain(this.url);
        }
    }

    /** 单个搜索引擎的抓取结果 */
    private static class EngineOutcome {
        final String summary;
        final List<SearchResult> results;

        EngineOutcome(String summary, List<SearchResult> results) {
            this.summary = summary;
            this.results = results;
        }
    }

    /** 缓存条目 */
    private static class CacheEntry {
        final String text;
        final long expireAt;

        CacheEntry(String text, long expireAt) {
            this.text = text;
            this.expireAt = expireAt;
        }
    }
}
