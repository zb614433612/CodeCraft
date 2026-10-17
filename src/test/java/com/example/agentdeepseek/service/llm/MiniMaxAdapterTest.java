package com.example.agentdeepseek.service.llm;

import com.example.agentdeepseek.model.entity.ProviderConfig;
import com.example.agentdeepseek.service.files.FileVisionService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.List;

/**
 * MiniMax / MiMo 平台能力适配纯逻辑测试（main 方法，不依赖 JUnit / Spring / 外部网络）。
 *
 * <p>覆盖四块易错逻辑（错了会表现为「功能静默失效」）：</p>
 * <ol>
 *   <li>搜索响应解析：官方结构 {@code organic[{title,link,snippet,date}]}，兼容 data 包装；</li>
 *   <li>图片理解解析：{@code {content}} 与 base_resp 错误码；</li>
 *   <li>额度解析：{@code model_remains[]} 的百分比/计数/重置时间（注意 usage_count 实为"剩余"）；</li>
 *   <li>区域 host 解析：minimaxi.com → 国内、minimax.io → 国际、扩展配置可覆盖；</li>
 *   <li>视觉能力判定：MiMo v2.5 支持、MiniMax M3 支持 / M2.x 不支持。</li>
 * </ol>
 *
 * <h3>运行方式</h3>
 * <pre>
 * mvn -q -DskipTests -DskipFrontend=true test-compile
 * mvn -q org.codehaus.mojo:exec-maven-plugin:3.1.0:java \
 *     -Dexec.mainClass=com.example.agentdeepseek.service.llm.MiniMaxAdapterTest \
 *     -Dexec.classpathScope=test -DskipFrontend=true
 * </pre>
 */
public class MiniMaxAdapterTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static int passed = 0;
    private static int failed = 0;

    public static void main(String[] args) throws Exception {
        MiniMaxCodingPlanClient client = new MiniMaxCodingPlanClient(null, MAPPER);

        // ==================== 搜索解析 ====================
        parseSearchHitsOfficial(client);
        parseSearchHitsDataWrapped(client);
        parseSearchHitsEmpty(client);

        // ==================== 图片理解解析 ====================
        parseVlmContentOk(client);
        parseVlmContentEmpty(client);
        parseVlmBaseRespError(client);

        // ==================== 额度解析 ====================
        parseQuotaWithPercent(client);
        parseQuotaComputePercent(client);
        parseQuotaBaseRespError(client);

        // ==================== 区域 host 解析 ====================
        resolveHostCases(client);

        // ==================== 视觉能力判定 ====================
        visionCapability();

        System.out.println("\n========== 结果: " + passed + " passed, " + failed + " failed ==========");
        if (failed > 0) {
            System.exit(1);
        }
    }

    // ==================== 搜索解析 ====================

    private static void parseSearchHitsOfficial(MiniMaxCodingPlanClient client) {
        String json = """
                {
                  "organic": [
                    {"title": "MiniMax 官网", "link": "https://www.minimax.io", "snippet": "官方站点", "date": "2026-01-01"},
                    {"title": "只有标题的结果"}
                  ],
                  "related_searches": [{"query": "minimax api"}],
                  "base_resp": {"status_code": 0, "status_msg": "success"}
                }
                """;
        List<MiniMaxCodingPlanClient.WebHit> hits = client.parseSearchHits(json);
        check("搜索解析：条数", "2", String.valueOf(hits.size()));
        check("搜索解析：title", "MiniMax 官网", hits.get(0).title());
        check("搜索解析：link", "https://www.minimax.io", hits.get(0).url());
        check("搜索解析：snippet", "官方站点", hits.get(0).bestDescription());
        check("搜索解析：date", "2026-01-01", hits.get(0).publishedAt());
        check("搜索解析：无 snippet 退化为标题", "只有标题的结果", hits.get(1).bestDescription());
    }

    private static void parseSearchHitsDataWrapped(MiniMaxCodingPlanClient client) {
        String json = """
                {"data": {"organic": [{"title": "包装形态", "link": "https://example.com/a"}]}}
                """;
        List<MiniMaxCodingPlanClient.WebHit> hits = client.parseSearchHits(json);
        check("搜索解析：兼容 data.organic 包装", "1", String.valueOf(hits.size()));
        check("搜索解析：包装形态字段", "https://example.com/a", hits.get(0).url());
    }

    private static void parseSearchHitsEmpty(MiniMaxCodingPlanClient client) {
        List<MiniMaxCodingPlanClient.WebHit> hits = client.parseSearchHits("{\"organic\":[]}");
        check("搜索解析：空 organic 返回空列表", "0", String.valueOf(hits.size()));
    }

    // ==================== 图片理解解析 ====================

    private static void parseVlmContentOk(MiniMaxCodingPlanClient client) {
        String json = "{\"content\":\"这是一只橘猫\",\"base_resp\":{\"status_code\":0,\"status_msg\":\"success\"}}";
        check("图片理解：解析 content", "这是一只橘猫", client.parseVlmContent(json));
    }

    private static void parseVlmContentEmpty(MiniMaxCodingPlanClient client) {
        String thrown = "no-error";
        try {
            client.parseVlmContent("{\"base_resp\":{\"status_code\":0}}");
        } catch (RuntimeException e) {
            thrown = e.getMessage();
        }
        check("图片理解：空内容抛异常", "true", String.valueOf(thrown.contains("空内容")));
    }

    private static void parseVlmBaseRespError(MiniMaxCodingPlanClient client) {
        String thrown = "no-error";
        try {
            client.parseVlmContent("{\"base_resp\":{\"status_code\":1004,\"status_msg\":\"invalid api key\"}}");
        } catch (RuntimeException e) {
            thrown = e.getMessage();
        }
        check("图片理解：base_resp 错误码抛异常", "true", String.valueOf(thrown.contains("1004")));
    }

    // ==================== 额度解析 ====================

    private static void parseQuotaWithPercent(MiniMaxCodingPlanClient client) {
        String json = """
                {
                  "model_remains": [
                    {"model_name": "video", "current_interval_remaining_percent": 10},
                    {"model_name": "general", "current_interval_total_count": 200,
                     "current_interval_usage_count": 135, "current_interval_remaining_percent": 67.5,
                     "end_time": 1767225600000, "current_subscribe_title": "Token Plan 标准版"}
                  ],
                  "base_resp": {"status_code": 0, "status_msg": "success"}
                }
                """;
        MiniMaxCodingPlanClient.QuotaSnapshot snapshot = client.parseQuota(json);
        check("额度解析：优先 general 桶", "67.5", String.valueOf(snapshot.remainingPercent()));
        check("额度解析：剩余次数", "135", String.valueOf(snapshot.remainingCount()));
        check("额度解析：总次数", "200", String.valueOf(snapshot.totalCount()));
        check("额度解析：重置时间", "1767225600000", String.valueOf(snapshot.resetAt()));
        check("额度解析：套餐名", "Token Plan 标准版", snapshot.planName());
    }

    private static void parseQuotaComputePercent(MiniMaxCodingPlanClient client) {
        String json = """
                {
                  "model_remains": [
                    {"current_interval_total_count": 200, "current_interval_usage_count": 50}
                  ],
                  "base_resp": {"status_code": 0}
                }
                """;
        MiniMaxCodingPlanClient.QuotaSnapshot snapshot = client.parseQuota(json);
        check("额度解析：缺百分比时按计数计算（25%）", "25.0", String.valueOf(snapshot.remainingPercent()));
    }

    private static void parseQuotaBaseRespError(MiniMaxCodingPlanClient client) {
        String thrown = "no-error";
        try {
            client.parseQuota("{\"base_resp\":{\"status_code\":2062,\"status_msg\":\"no active token plan\"}}");
        } catch (RuntimeException e) {
            thrown = e.getMessage();
        }
        check("额度解析：2062（无套餐）抛异常", "true", String.valueOf(thrown.contains("2062")));
    }

    // ==================== 区域 host 解析 ====================

    private static void resolveHostCases(MiniMaxCodingPlanClient client) throws Exception {
        check("host：国内站 baseUrl", "https://api.minimaxi.com",
                client.resolveHost(provider("https://api.minimaxi.com"), MAPPER.createObjectNode()));
        check("host：国际站 baseUrl（含路径）", "https://api.minimax.io",
                client.resolveHost(provider("https://api.minimax.io/v1"), MAPPER.createObjectNode()));
        check("host：无 baseUrl 默认国内", "https://api.minimaxi.com",
                client.resolveHost(provider(null), MAPPER.createObjectNode()));
        check("host：扩展配置 apiHost 覆盖", "https://www.minimax.io",
                client.resolveHost(provider("https://api.minimaxi.com"),
                        MAPPER.readTree("{\"apiHost\":\"https://www.minimax.io/\"}")));
    }

    // ==================== 视觉能力判定 ====================

    private static void visionCapability() {
        check("vision：MiMo v2.5 支持", "true",
                String.valueOf(FileVisionService.supportsVisionModel("mimo", "mimo-v2.5")));
        check("vision：MiMo v2.5-pro 支持", "true",
                String.valueOf(FileVisionService.supportsVisionModel("mimo", "mimo-v2.5-pro")));
        check("vision：MiMo 早期纯文本模型不支持", "false",
                String.valueOf(FileVisionService.supportsVisionModel("mimo", "mimo-7b-rl")));
        check("vision：MiniMax-M3 支持", "true",
                String.valueOf(FileVisionService.supportsVisionModel("minimax", "MiniMax-M3")));
        check("vision：MiniMax-M2.7 不支持", "false",
                String.valueOf(FileVisionService.supportsVisionModel("minimax", "MiniMax-M2.7")));
        check("vision：MiniMax VL 系列支持", "true",
                String.valueOf(FileVisionService.supportsVisionModel("minimax-cn", "MiniMax-VL-01")));
    }

    // ==================== 工具方法 ====================

    private static ProviderConfig provider(String baseUrl) {
        ProviderConfig config = new ProviderConfig();
        config.setCode("minimax");
        config.setRequestTemplate("minimax");
        config.setBaseUrl(baseUrl);
        return config;
    }

    private static void check(String name, String expected, String actual) {
        if (expected.equals(actual)) {
            passed++;
            System.out.println("  ✅ " + name);
        } else {
            failed++;
            System.out.println("  ❌ " + name + "：期望 [" + expected + "]，实际 [" + actual + "]");
        }
    }
}
