package com.example.agentdeepseek.service.llm;

import com.example.agentdeepseek.model.entity.ProviderConfig;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.List;

/**
 * {@link DeepSeekToolsApiClient} 纯逻辑测试（main 方法，不依赖 JUnit / Spring / 外部网络）。
 *
 * <p>覆盖两块最容易出错、且线上表现为「功能静默失效」的逻辑：</p>
 * <ol>
 *   <li>接口路径拼接：baseUrl 带不带 /v1、带不带尾斜杠，都必须得到正确的官方路径；</li>
 *   <li>搜索响应解析：字段名必须与官方文档一致（results/title/url/snippet/summary/content/
 *       site_name/date/rerank_score/authority），否则解析为空、工具静默回退到站点抓取。</li>
 * </ol>
 *
 * <h3>运行方式</h3>
 * <pre>
 * mvn -q -DskipTests -DskipFrontend=true test-compile
 * mvn -q org.codehaus.mojo:exec-maven-plugin:3.1.0:java \
 *     -Dexec.mainClass=com.example.agentdeepseek.service.llm.DeepSeekToolsApiClientTest \
 *     -Dexec.classpathScope=test -DskipFrontend=true
 * </pre>
 */
public class DeepSeekToolsApiClientTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static int passed = 0;
    private static int failed = 0;

    public static void main(String[] args) throws Exception {
        // ==================== 路径拼接 ====================
        resolveApiPathWithoutVersionSegment();
        resolveApiPathWithVersionSegment();

        // ==================== 响应解析 ====================
        parseSearchHitsFullResponse();
        parseSearchHitsEdgeCases();

        // ==================== 摘要优先级 ====================
        bestDescriptionFallbackChain();

        System.out.println("\n========== 结果: " + passed + " passed, " + failed + " failed ==========");
        if (failed > 0) {
            System.exit(1);
        }
    }

    // ==================== 路径拼接 ====================

    private static void resolveApiPathWithoutVersionSegment() {
        DeepSeekToolsApiClient client = newClient();
        check("baseUrl 无 /v1 → /v1/tools/search",
                client.resolveApiPath(config("https://api.deepseek.com"), "/tools/search"),
                "/v1/tools/search");
        check("baseUrl 无 /v1 → /v1/tools/fetch",
                client.resolveApiPath(config("https://api.deepseek.com"), "/tools/fetch"),
                "/v1/tools/fetch");
        check("baseUrl 带尾斜杠 → /v1/tools/search",
                client.resolveApiPath(config("https://api.deepseek.com/"), "/tools/search"),
                "/v1/tools/search");
    }

    private static void resolveApiPathWithVersionSegment() {
        DeepSeekToolsApiClient client = newClient();
        check("baseUrl 已带 /v1 → 不重复拼接",
                client.resolveApiPath(config("https://api.deepseek.com/v1"), "/tools/search"),
                "/tools/search");
        check("baseUrl 已带 /v1/ → 不重复拼接",
                client.resolveApiPath(config("https://api.deepseek.com/v1/"), "/tools/search"),
                "/tools/search");
        check("baseUrl 多个尾斜杠 → 归一化",
                client.resolveApiPath(config("https://api.deepseek.com///"), "/tools/search"),
                "/v1/tools/search");
    }

    // ==================== 响应解析 ====================

    private static void parseSearchHitsFullResponse() {
        String json = """
                {
                  "results": [
                    {
                      "rerank_score": 2.4,
                      "title": "DeepSeek V4 model released",
                      "url": "https://example.com/deepseek-v4",
                      "snippet": "DeepSeek V4 is now available.",
                      "summary": "DeepSeek has released the V4 model with improved tool calling.",
                      "content": "# DeepSeek V4\\n\\nFull article content.",
                      "site_name": "Example News",
                      "site_icon": "https://example.com/favicon.ico",
                      "date": "2026-06-01T00:00:00+00:00",
                      "authority": 3
                    }
                  ]
                }
                """;
        List<DeepSeekToolsApiClient.SearchHit> hits = newClient().parseSearchHits(json);
        check("结果条数", hits.size(), 1);

        DeepSeekToolsApiClient.SearchHit hit = hits.get(0);
        check("标题", hit.title(), "DeepSeek V4 model released");
        check("链接", hit.url(), "https://example.com/deepseek-v4");
        check("短摘要", hit.snippet(), "DeepSeek V4 is now available.");
        check("长摘要", hit.summary(), "DeepSeek has released the V4 model with improved tool calling.");
        check("站点名", hit.siteName(), "Example News");
        check("发布时间", hit.publishedAt(), "2026-06-01T00:00:00+00:00");
        check("排序分", hit.rerankScore(), 2.4);
        check("权威性", hit.authority(), 3);
    }

    private static void parseSearchHitsEdgeCases() {
        DeepSeekToolsApiClient client = newClient();
        check("空 results", client.parseSearchHits("{\"results\":[]}").size(), 0);
        check("无 results 字段", client.parseSearchHits("{}").size(), 0);
        check("缺 url 的条目被跳过", client.parseSearchHits("{\"results\":[{\"title\":\"no url\"}]}").size(), 0);

        List<DeepSeekToolsApiClient.SearchHit> partial =
                client.parseSearchHits("{\"results\":[{\"url\":\"https://a.com\"}]}");
        check("仅 url 的条目保留", partial.size(), 1);
        check("缺失标题为空串", partial.get(0).title(), "");
        check("缺失权威性为 -1", partial.get(0).authority(), -1);
    }

    // ==================== 摘要优先级 ====================

    private static void bestDescriptionFallbackChain() {
        DeepSeekToolsApiClient.SearchHit withSummary = new DeepSeekToolsApiClient.SearchHit(
                "t", "https://a.com", "snippet", "summary", "content", "site", "", 1.0, 1);
        check("摘要优先 summary", withSummary.bestDescription(), "summary");

        DeepSeekToolsApiClient.SearchHit withoutSummary = new DeepSeekToolsApiClient.SearchHit(
                "t", "https://a.com", "snippet", "", "content", "site", "", 1.0, 1);
        check("无 summary 退 snippet", withoutSummary.bestDescription(), "snippet");

        String longContent = "x".repeat(400);
        DeepSeekToolsApiClient.SearchHit onlyContent = new DeepSeekToolsApiClient.SearchHit(
                "t", "https://a.com", "", "", longContent, "site", "", 1.0, 1);
        String description = onlyContent.bestDescription();
        check("仅正文时截断到 300 字", description.length(), 301);
        check("截断以省略号结尾", description.endsWith("…"), true);

        DeepSeekToolsApiClient.SearchHit empty = new DeepSeekToolsApiClient.SearchHit(
                "", "https://a.com", "", "", "", "", "", 0, -1);
        check("全空返回空串", empty.bestDescription(), "");
    }

    // ==================== 工具方法 ====================

    /** 仅测纯方法，依赖传 null 即可（不会触发网络/数据库） */
    private static DeepSeekToolsApiClient newClient() {
        return new DeepSeekToolsApiClient(null, null, MAPPER);
    }

    private static ProviderConfig config(String baseUrl) {
        ProviderConfig config = new ProviderConfig();
        config.setCode("deepseek");
        config.setBaseUrl(baseUrl);
        config.setApiKey("sk-test");
        return config;
    }

    private static void check(String name, Object actual, Object expected) {
        boolean ok;
        if (actual instanceof Double a && expected instanceof Double e) {
            ok = Math.abs(a - e) < 1e-6;
        } else {
            ok = actual == null ? expected == null : actual.equals(expected);
        }
        if (ok) {
            passed++;
            System.out.println("  OK   " + name);
        } else {
            failed++;
            System.out.println("  FAIL " + name + " | actual=" + actual + " expected=" + expected);
        }
    }
}
