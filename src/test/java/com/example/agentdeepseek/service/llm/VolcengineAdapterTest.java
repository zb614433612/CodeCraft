package com.example.agentdeepseek.service.llm;

import com.example.agentdeepseek.service.files.FileVisionService;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 火山引擎（方舟）适配器纯逻辑测试（main 方法，不依赖 JUnit / Spring / 外部网络）。
 *
 * <p>覆盖最容易出错、线上表现为「功能静默失效 / 接口 400」的四块逻辑：</p>
 * <ol>
 *   <li>Chat 端点解析：baseUrl 带不带 /api/v3、带不带尾斜杠，都必须得到正确的 chat/completions 地址；</li>
 *   <li>thinking 参数映射：方舟只接受 enabled/disabled/auto，reasoning_effort 仅对 deepseek-v4 系列下发；</li>
 *   <li>费用中心 V4 签名：结构正确、确定性（同输入同签名）；</li>
 *   <li>联网搜索响应解析：字段名必须与官方文档一致（Result.WebResults + 首字母大写字段）。</li>
 * </ol>
 *
 * <h3>运行方式</h3>
 * <pre>
 * mvn -q -DskipTests -DskipFrontend=true test-compile
 * mvn -q org.codehaus.mojo:exec-maven-plugin:3.1.0:java \
 *     -Dexec.mainClass=com.example.agentdeepseek.service.llm.VolcengineAdapterTest \
 *     -Dexec.classpathScope=test -DskipFrontend=true
 * </pre>
 */
public class VolcengineAdapterTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static int passed = 0;
    private static int failed = 0;

    public static void main(String[] args) throws Exception {
        // ==================== Chat 端点解析 ====================
        resolveEndpointWithApiVersion();
        resolveEndpointWithoutApiVersion();
        resolveEndpointWithFullPath();
        resolveEndpointWithTrailingSlash();

        // ==================== thinking / reasoning_effort 映射 ====================
        thinkingModeMapping();
        reasoningEffortOnlyForDeepseekV4();

        // ==================== 多模态 detail 归一 ====================
        imageDetailNormalizedToHigh();
        imageDetailConfigurable();

        // ==================== 扩展配置：档位白名单 / 全模型开关 / 档位文案 ====================
        reasoningEffortConfigurable();

        // ==================== 联网搜索凭证判定 ====================
        searchKeyMaskHandling();

        // ==================== 费用中心签名 ====================
        billingAuthorizationStructure();
        billingAuthorizationDeterministic();

        // ==================== 联网搜索解析 ====================
        parseWebSearchHits();
        buildQueryFromUrlCases();

        // ==================== vision 能力判定 ====================
        visionModelCapability();

        System.out.println("\n========== 结果: " + passed + " passed, " + failed + " failed ==========");
        if (failed > 0) {
            System.exit(1);
        }
    }

    // ==================== 端点解析 ====================

    private static void resolveEndpointWithApiVersion() {
        check("端点：baseUrl 已含 /api/v3",
                "https://ark.cn-beijing.volces.com/api/v3/chat/completions",
                VolcengineArkClient.resolveChatEndpoint("https://ark.cn-beijing.volces.com/api/v3"));
    }

    private static void resolveEndpointWithoutApiVersion() {
        check("端点：baseUrl 不含 /api/v3 时自动补齐",
                "https://ark.cn-beijing.volces.com/api/v3/chat/completions",
                VolcengineArkClient.resolveChatEndpoint("https://ark.cn-beijing.volces.com"));
    }

    private static void resolveEndpointWithFullPath() {
        check("端点：已是完整 chat 地址时原样使用",
                "https://proxy.example.com/ark/chat/completions",
                VolcengineArkClient.resolveChatEndpoint("https://proxy.example.com/ark/chat/completions"));
    }

    private static void resolveEndpointWithTrailingSlash() {
        check("端点：baseUrl 尾斜杠被裁剪",
                "https://ark.cn-beijing.volces.com/api/v3/chat/completions",
                VolcengineArkClient.resolveChatEndpoint("https://ark.cn-beijing.volces.com/api/v3/"));
    }

    // ==================== thinking 参数 ====================

    private static void thinkingModeMapping() {
        VolcengineArkClient client = new VolcengineArkClient(null, MAPPER, "volcengine",
                "https://ark.cn-beijing.volces.com/api/v3");

        check("thinking：non-thinking → disabled", "disabled",
                thinkingType(client.buildRequestBody(messages(), "doubao-seed-2-1-pro-260628",
                        0.3, "non-thinking", true, null)));
        check("thinking：thinking → enabled", "enabled",
                thinkingType(client.buildRequestBody(messages(), "doubao-seed-2-1-pro-260628",
                        0.3, "thinking", true, null)));
        check("thinking：thinking_max → enabled", "enabled",
                thinkingType(client.buildRequestBody(messages(), "doubao-seed-2-1-pro-260628",
                        0.3, "thinking_max", true, null)));
        check("thinking：auto → auto", "auto",
                thinkingType(client.buildRequestBody(messages(), "doubao-seed-2-1-pro-260628",
                        0.3, "auto", true, null)));
        check("thinking：默认（空）→ enabled", "enabled",
                thinkingType(client.buildRequestBody(messages(), "doubao-seed-2-1-pro-260628",
                        0.3, null, true, null)));
    }

    private static void reasoningEffortOnlyForDeepseekV4() {
        VolcengineArkClient client = new VolcengineArkClient(null, MAPPER, "volcengine",
                "https://ark.cn-beijing.volces.com/api/v3");

        Map<String, Object> dsMax = client.buildRequestBody(messages(), "deepseek-v4-pro-260425",
                0.3, "thinking_max", false, null);
        check("reasoning_effort：deepseek-v4 + thinking_max → max", "max", String.valueOf(dsMax.get("reasoning_effort")));

        Map<String, Object> dsThinking = client.buildRequestBody(messages(), "deepseek-v4-flash-260731",
                0.3, "thinking", false, null);
        check("reasoning_effort：deepseek-v4 + thinking → high", "high",
                String.valueOf(dsThinking.get("reasoning_effort")));

        Map<String, Object> doubao = client.buildRequestBody(messages(), "doubao-seed-2-1-pro-260628",
                0.3, "thinking_max", false, null);
        check("reasoning_effort：非 deepseek-v4 不下发（避免 400）", "false",
                String.valueOf(doubao.containsKey("reasoning_effort")));
    }

    // ==================== 多模态 detail ====================

    private static void imageDetailNormalizedToHigh() {
        VolcengineArkClient client = new VolcengineArkClient(null, MAPPER, "volcengine",
                "https://ark.cn-beijing.volces.com/api/v3");

        check("多模态：detail=auto 归一为 high（方舟取值域 low/high/xhigh）", "high", detailOf(client));
    }

    /** 扩展配置 imageDetail：显式生效 / 大小写不敏感 / 非法值回落 */
    private static void imageDetailConfigurable() {
        check("detail：imageDetail=low 生效", "low",
                detailOf(arcClient(new VolcengineArkClient.Options("low", null, false, null, null))));
        check("detail：imageDetail 大小写不敏感（XHIGH → xhigh）", "xhigh",
                detailOf(arcClient(new VolcengineArkClient.Options("XHIGH", null, false, null, null))));
        check("detail：非法值 auto 回落 high", "high",
                detailOf(arcClient(new VolcengineArkClient.Options("auto", null, false, null, null))));
    }

    /** 扩展配置：档位白名单 / 全模型开关 / 档位文案可配 */
    private static void reasoningEffortConfigurable() {
        // 1) 自定义白名单：doubao-seed-1-6 命中，deepseek-v4 不再命中
        VolcengineArkClient custom = arcClient(
                new VolcengineArkClient.Options(null, List.of("doubao-seed-1-6"), false, null, null));
        check("reasoning_effort：自定义白名单命中 doubao", "high",
                String.valueOf(custom.buildRequestBody(messages(), "doubao-seed-1-6-thinking-250715",
                        0.3, "thinking", false, null).get("reasoning_effort")));
        check("reasoning_effort：自定义白名单后 deepseek-v4 不下发", "false",
                String.valueOf(custom.buildRequestBody(messages(), "deepseek-v4-pro-260425",
                        0.3, "thinking", false, null).containsKey("reasoning_effort")));

        // 2) 全模型开关 + 自定义档位文案（medium / high）
        VolcengineArkClient all = arcClient(
                new VolcengineArkClient.Options(null, null, true, "medium", "high"));
        check("reasoning_effort：全模型开关对任意模型下发（thinking → medium）", "medium",
                String.valueOf(all.buildRequestBody(messages(), "kimi-k2-250711",
                        0.3, "thinking", false, null).get("reasoning_effort")));
        check("reasoning_effort：档位文案可配（thinking_max → high）", "high",
                String.valueOf(all.buildRequestBody(messages(), "kimi-k2-250711",
                        0.3, "thinking_max", false, null).get("reasoning_effort")));

        // 3) 非思考模式一律不下发
        check("reasoning_effort：non-thinking 不下发", "false",
                String.valueOf(all.buildRequestBody(messages(), "kimi-k2-250711",
                        0.3, "non-thinking", false, null).containsKey("reasoning_effort")));
    }

    /** 联网搜索凭证：掩码 / 空值必须视为未配置 */
    private static void searchKeyMaskHandling() {
        check("搜索 Key：空串不可用", "false", String.valueOf(VolcengineSearchApiClient.isUsableKey("")));
        check("搜索 Key：空白不可用", "false", String.valueOf(VolcengineSearchApiClient.isUsableKey("   ")));
        check("搜索 Key：掩码 **** 不可用", "false",
                String.valueOf(VolcengineSearchApiClient.isUsableKey("****")));
        check("搜索 Key：含掩码的脱敏串不可用", "false",
                String.valueOf(VolcengineSearchApiClient.isUsableKey("sk-abc****123")));
        check("搜索 Key：正常 Key 可用", "true",
                String.valueOf(VolcengineSearchApiClient.isUsableKey("sk-search-123")));
    }

    private static VolcengineArkClient arcClient(VolcengineArkClient.Options options) {
        return new VolcengineArkClient(null, MAPPER, "volcengine",
                "https://ark.cn-beijing.volces.com/api/v3", options);
    }

    /** 构建带 image_url(detail=auto) 的多模态消息 */
    private static List<Map<String, Object>> imageMessages() {
        List<Map<String, Object>> messages = new ArrayList<>();
        Map<String, Object> user = new LinkedHashMap<>();
        user.put("role", "user");
        List<Object> parts = new ArrayList<>();
        Map<String, Object> text = new LinkedHashMap<>();
        text.put("type", "text");
        text.put("text", "这张图里是什么？");
        parts.add(text);
        Map<String, Object> imageBlock = new LinkedHashMap<>();
        imageBlock.put("type", "image_url");
        Map<String, Object> imageUrl = new LinkedHashMap<>();
        imageUrl.put("url", "data:image/png;base64,AAA");
        imageUrl.put("detail", "auto");
        imageBlock.put("image_url", imageUrl);
        parts.add(imageBlock);
        user.put("content", parts);
        messages.add(user);
        return messages;
    }

    /** 走一次请求体构建，取出归一后的 image detail */
    private static String detailOf(VolcengineArkClient client) {
        Map<String, Object> body = client.buildRequestBody(imageMessages(), "doubao-seed-2-1-pro-260628",
                0.3, "non-thinking", true, null);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> bodyMessages = (List<Map<String, Object>>) body.get("messages");
        @SuppressWarnings("unchecked")
        List<Object> bodyParts = (List<Object>) bodyMessages.get(0).get("content");
        @SuppressWarnings("unchecked")
        Map<String, Object> bodyImage = (Map<String, Object>) ((Map<?, ?>) bodyParts.get(1)).get("image_url");
        return String.valueOf(bodyImage.get("detail"));
    }

    // ==================== 费用中心签名 ====================

    private static void billingAuthorizationStructure() {
        VolcengineBillingClient billing = new VolcengineBillingClient(MAPPER);
        String auth = billing.buildAuthorization("AKLTtest", "secretKey",
                "cn-beijing", "20240101T120000Z", "{}");

        check("签名：算法前缀", "true", String.valueOf(auth.startsWith("HMAC-SHA256 Credential=AKLTtest/")));
        check("签名：credential scope", "true",
                String.valueOf(auth.contains("/20240101/cn-beijing/billing/request")));
        check("签名：signedHeaders", "true",
                String.valueOf(auth.contains("SignedHeaders=content-type;host;x-content-sha256;x-date")));
        String signature = auth.substring(auth.indexOf("Signature=") + "Signature=".length());
        check("签名：64 位小写 hex", "true", String.valueOf(signature.matches("[0-9a-f]{64}")));
    }

    private static void billingAuthorizationDeterministic() {
        VolcengineBillingClient billing = new VolcengineBillingClient(MAPPER);
        String a = billing.buildAuthorization("AKLTtest", "secretKey", "cn-beijing", "20240101T120000Z", "{}");
        String b = billing.buildAuthorization("AKLTtest", "secretKey", "cn-beijing", "20240101T120000Z", "{}");
        check("签名：同输入同签名（确定性）", "true", String.valueOf(a.equals(b)));

        String c = billing.buildAuthorization("AKLTtest", "otherSecret", "cn-beijing", "20240101T120000Z", "{}");
        check("签名：不同 SK 产生不同签名", "false", String.valueOf(a.equals(c)));
    }

    // ==================== 联网搜索解析 ====================

    private static void parseWebSearchHits() throws Exception {
        String json = """
                {
                  "ResponseMetadata": {"RequestId": "abc"},
                  "Result": {
                    "ResultCount": 2,
                    "WebResults": [
                      {"Title": "火山方舟文档", "Url": "https://www.volcengine.com/docs/82379",
                       "Snippet": "片段A", "Summary": "摘要A", "Content": "正文A",
                       "SiteName": "火山引擎", "PublishTime": "2025-05-30T19:35:24+08:00",
                       "RankScore": 0.98, "AuthInfoLevel": 2},
                      {"Title": "无链接结果", "Url": "", "Snippet": "应被丢弃"}
                    ]
                  }
                }
                """;
        VolcengineSearchApiClient client = new VolcengineSearchApiClient(null, MAPPER);
        List<VolcengineSearchApiClient.WebHit> hits = client.parseHits(json);

        check("搜索解析：空 URL 结果被丢弃，仅保留 1 条", "1", String.valueOf(hits.size()));
        VolcengineSearchApiClient.WebHit hit = hits.get(0);
        check("搜索解析：Title", "火山方舟文档", hit.title());
        check("搜索解析：Url", "https://www.volcengine.com/docs/82379", hit.url());
        check("搜索解析：Summary 优先作为摘要", "摘要A", hit.bestDescription());
        check("搜索解析：AuthInfoLevel", "2", String.valueOf(hit.authLevel()));
    }

    private static void buildQueryFromUrlCases() {
        check("URL 关键词：普通文章页（连字符转空格）", "spring.io whats new in spring boot 3",
                VolcengineSearchApiClient.buildQueryFromUrl("https://spring.io/blog/whats-new-in-spring-boot-3.html"));
        check("URL 关键词：域名根路径", "example.com",
                VolcengineSearchApiClient.buildQueryFromUrl("https://example.com"));
        check("URL 关键词：非法 URL 返回空串", "",
                VolcengineSearchApiClient.buildQueryFromUrl("not-a-url"));
    }

    // ==================== vision 能力判定 ====================

    private static void visionModelCapability() {
        check("vision：方舟 doubao-seed 支持", "true",
                String.valueOf(FileVisionService.supportsVisionModel("volcengine", "doubao-seed-2-1-pro-260628")));
        check("vision：方舟 deepseek 文本模型不支持", "false",
                String.valueOf(FileVisionService.supportsVisionModel("volcengine", "deepseek-v4-pro-260425")));
        check("vision：方舟 vision 命名支持", "true",
                String.valueOf(FileVisionService.supportsVisionModel("ark", "doubao-1-5-vision-pro")));
        check("vision：deepseek 官方 flash 支持", "true",
                String.valueOf(FileVisionService.supportsVisionModel("deepseek", "deepseek-v4-flash")));
        check("vision：deepseek 官方 chat 不支持", "false",
                String.valueOf(FileVisionService.supportsVisionModel("deepseek", "deepseek-chat")));
    }

    // ==================== 工具方法 ====================

    private static List<Map<String, Object>> messages() {
        List<Map<String, Object>> messages = new ArrayList<>();
        Map<String, Object> system = new LinkedHashMap<>();
        system.put("role", "system");
        system.put("content", "你是助手");
        messages.add(system);
        Map<String, Object> user = new LinkedHashMap<>();
        user.put("role", "user");
        user.put("content", "你好");
        messages.add(user);
        return messages;
    }

    @SuppressWarnings("unchecked")
    private static String thinkingType(Map<String, Object> body) {
        Object thinking = body.get("thinking");
        if (!(thinking instanceof Map<?, ?> map)) {
            return "(缺失)";
        }
        return String.valueOf(((Map<String, Object>) map).get("type"));
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
