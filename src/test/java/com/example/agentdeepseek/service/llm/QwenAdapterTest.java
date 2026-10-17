package com.example.agentdeepseek.service.llm;

import com.example.agentdeepseek.service.files.FileVisionService;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 通义千问 + OpenAI / Anthropic「公共契约」适配器纯逻辑测试（main 方法，不依赖 JUnit / 网络）。
 *
 * <p>覆盖五块最容易出错、线上表现为「404 / 400 / 静默失效」的逻辑：</p>
 * <ol>
 *   <li>端点解析：Qwen {@code /compatible-mode/v1}、OpenAI {@code /v1}、Anthropic 兼容层 {@code /anthropic}；</li>
 *   <li>Qwen 扩展参数：{@code enable_thinking} / {@code thinking_budget} / {@code enable_search}；</li>
 *   <li>OpenAI 公共契约：官方才下发 max_tokens / reasoning_effort，第三方兼容服务不下发；</li>
 *   <li>Anthropic 公共契约：{@code image_url → image/source} 协议转换、thinking 仅开启时下发、max_tokens &gt; budget；</li>
 *   <li>阿里云 RPC 签名与 percentEncode；Qwen 视觉能力判定。</li>
 * </ol>
 *
 * <h3>运行方式</h3>
 * <pre>
 * mvn -q -DskipTests -DskipFrontend=true test-compile
 * mvn -q org.codehaus.mojo:exec-maven-plugin:3.1.0:java \
 *     -Dexec.mainClass=com.example.agentdeepseek.service.llm.QwenAdapterTest \
 *     -Dexec.classpathScope=test -DskipFrontend=true
 * </pre>
 */
public class QwenAdapterTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static int passed = 0;
    private static int failed = 0;

    public static void main(String[] args) {
        // ==================== Qwen ====================
        qwenEndpoint();
        qwenThinkingParams();
        qwenThinkingBudgetOverride();
        qwenEnableSearch();
        qwenStripImageDetail();

        // ==================== OpenAI 公共契约 ====================
        openAIEndpoint();
        openAIModelKeyNormalize();
        openAIVsGptBoundary();
        openAIReasoningModel();

        // ==================== Anthropic 公共契约 ====================
        anthropicEndpoint();
        anthropicImageBlockConvert();
        anthropicThinkingAndMaxTokens();

        // ==================== 阿里云签名 ====================
        aliyunPercentEncode();
        aliyunSignedUrl();

        // ==================== 视觉能力判定 ====================
        qwenVisionCapability();

        System.out.println("\n========== 结果: " + passed + " passed, " + failed + " failed ==========");
        if (failed > 0) {
            System.exit(1);
        }
    }

    // ==================== Qwen ====================

    private static void qwenEndpoint() {
        check("Qwen 端点：已含 compatible-mode/v1",
                "https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions",
                QwenClient.resolveChatEndpoint("https://dashscope.aliyuncs.com/compatible-mode/v1"));
        check("Qwen 端点：裸域名自动补 compatible-mode",
                "https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions",
                QwenClient.resolveChatEndpoint("https://dashscope.aliyuncs.com"));
        check("Qwen 端点：工作空间域名 + 尾斜杠",
                "https://ws1.cn-beijing.maas.aliyuncs.com/compatible-mode/v1/chat/completions",
                QwenClient.resolveChatEndpoint("https://ws1.cn-beijing.maas.aliyuncs.com/compatible-mode/v1/"));
        check("Qwen 端点：已是完整地址原样使用",
                "https://my-proxy.com/qwen/chat/completions",
                QwenClient.resolveChatEndpoint("https://my-proxy.com/qwen/chat/completions"));
    }

    private static void qwenThinkingParams() {
        QwenClient client = new QwenClient(null, MAPPER, "qwen",
                "https://dashscope.aliyuncs.com/compatible-mode/v1", false, null);

        check("Qwen thinking：non-thinking → enable_thinking=false", "false",
                String.valueOf(qwenBody(client, "non-thinking").get("enable_thinking")));
        check("Qwen thinking：thinking → enable_thinking=true", "true",
                String.valueOf(qwenBody(client, "thinking").get("enable_thinking")));
        check("Qwen thinking：thinking_max → thinking_budget=8192", "8192",
                String.valueOf(qwenBody(client, "thinking_max").get("thinking_budget")));
        check("Qwen thinking：空模式不下发 enable_thinking", "false",
                String.valueOf(qwenBody(client, null).containsKey("enable_thinking")));
    }

    private static void qwenThinkingBudgetOverride() {
        QwenClient client = new QwenClient(null, MAPPER, "qwen", null, false, 4096);
        check("Qwen thinking：扩展配置 thinkingBudget 优先", "4096",
                String.valueOf(qwenBody(client, "thinking_max").get("thinking_budget")));
    }

    private static void qwenEnableSearch() {
        QwenClient client = new QwenClient(null, MAPPER, "qwen", null, true, null);
        Map<String, Object> body = qwenBody(client, "non-thinking");
        check("Qwen 搜索：enable_search=true", "true", String.valueOf(body.get("enable_search")));
        check("Qwen 搜索：携带 search_options", "true",
                String.valueOf(body.get("search_options") instanceof Map));

        QwenClient disabled = new QwenClient(null, MAPPER, "qwen", null, false, null);
        check("Qwen 搜索：未开启时不下发", "false",
                String.valueOf(qwenBody(disabled, "non-thinking").containsKey("enable_search")));
    }

    private static void qwenStripImageDetail() {
        QwenClient client = new QwenClient(null, MAPPER, "qwen", null, false, null);
        List<Map<String, Object>> messages = messagesWithImage("auto");
        Map<String, Object> body = client.buildRequestBody(messages, "qwen-vl-max", 0.3, "non-thinking", true, null);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> sent = (List<Map<String, Object>>) body.get("messages");
        @SuppressWarnings("unchecked")
        List<Object> parts = (List<Object>) sent.get(sent.size() - 1).get("content");
        @SuppressWarnings("unchecked")
        Map<String, Object> image = (Map<String, Object>) ((Map<?, ?>) parts.get(1)).get("image_url");
        check("Qwen 图片块：detail 字段被剔除", "false", String.valueOf(image.containsKey("detail")));
    }

    // ==================== OpenAI 公共契约 ====================

    private static void openAIEndpoint() {
        check("OpenAI 端点：裸域名 → /v1/chat/completions",
                "https://api.openai.com/v1/chat/completions",
                OpenAIClient.resolveChatEndpoint("https://api.openai.com"));
        check("OpenAI 端点：已含 /v1 不重复拼接",
                "https://api.openai.com/v1/chat/completions",
                OpenAIClient.resolveChatEndpoint("https://api.openai.com/v1"));
        check("OpenAI 端点：中转站自定义路径原样使用",
                "https://proxy.example.com/openai/chat/completions",
                OpenAIClient.resolveChatEndpoint("https://proxy.example.com/openai/chat/completions"));
        check("OpenAI 端点：本地网关（无 /v1 后缀）自动补齐",
                "http://127.0.0.1:8000/v1/chat/completions",
                OpenAIClient.resolveChatEndpoint("http://127.0.0.1:8000"));
    }

    private static void openAIModelKeyNormalize() {
        check("OpenAI 模型名归一：openai/gpt-4o → gpt-4o", "gpt-4o",
                OpenAIClient.normalizeModelKey("openai/gpt-4o"));
        check("OpenAI 模型名归一：azure/o3-mini → o3-mini", "o3-mini",
                OpenAIClient.normalizeModelKey("azure/o3-mini"));
    }

    /**
     * OpenAI 公共契约与 gpt 官方专精的职责分界：
     * openai 模板即使 baseUrl 指向官方，也不下发厂商专有参数（交由 gpt 模板负责）。
     */
    private static void openAIVsGptBoundary() {
        OpenAIClient openai = new OpenAIClient(null, MAPPER, "openai", "https://api.openai.com/v1");
        Map<String, Object> body = openai.buildRequestBody(
                plainMessages(), "gpt-4o", 0.3, "thinking", true, null);

        check("openai 公共契约：不下发 max_tokens", "false",
                String.valueOf(body.containsKey("max_tokens")));
        check("openai 公共契约：普通模型不下发 max_completion_tokens", "false",
                String.valueOf(body.containsKey("max_completion_tokens")));
        check("openai 公共契约：不下发 reasoning_effort", "false",
                String.valueOf(body.containsKey("reasoning_effort")));
        check("openai 公共契约：不下发 stream_options", "false",
                String.valueOf(body.containsKey("stream_options")));
    }

    private static void openAIReasoningModel() {
        check("OpenAI 推理模型判定：o3-mini", "true",
                String.valueOf(OpenAIClient.isReasoningModel("o3-mini")));
        check("OpenAI 推理模型判定：gpt-4o", "false",
                String.valueOf(OpenAIClient.isReasoningModel("gpt-4o")));

        OpenAIClient proxy = new OpenAIClient(null, MAPPER, "openai", "https://proxy.example.com/v1");
        Map<String, Object> body = proxy.buildRequestBody(
                plainMessages(), "azure/o3-mini", 0.3, "non-thinking", true, null);
        check("OpenAI 推理模型：中转站前缀写法仍识别为推理模型（max_completion_tokens）", "16384",
                String.valueOf(body.get("max_completion_tokens")));
        check("OpenAI 推理模型：移除 temperature", "false", String.valueOf(body.containsKey("temperature")));
    }

    // ==================== Anthropic 公共契约 ====================

    private static void anthropicEndpoint() {
        check("Anthropic 端点：官方裸域名",
                "https://api.anthropic.com/v1/messages",
                AnthropicClient.resolveChatEndpoint("https://api.anthropic.com"));
        check("Anthropic 端点：已含 /v1",
                "https://api.anthropic.com/v1/messages",
                AnthropicClient.resolveChatEndpoint("https://api.anthropic.com/v1"));
        check("Anthropic 端点：兼容层 /anthropic 前缀",
                "https://api.minimaxi.com/anthropic/v1/messages",
                AnthropicClient.resolveChatEndpoint("https://api.minimaxi.com/anthropic"));
        check("Anthropic 端点：已是完整地址原样使用",
                "https://proxy.example.com/v1/messages",
                AnthropicClient.resolveChatEndpoint("https://proxy.example.com/v1/messages"));
    }

    private static void anthropicImageBlockConvert() {
        List<Map<String, Object>> messages = messagesWithImage("auto");
        AnthropicClient.convertImageBlocks(messages);

        @SuppressWarnings("unchecked")
        List<Object> parts = (List<Object>) messages.get(messages.size() - 1).get("content");
        @SuppressWarnings("unchecked")
        Map<String, Object> imageBlock = (Map<String, Object>) parts.get(1);
        check("Anthropic 图片转换：块类型 image", "image", String.valueOf(imageBlock.get("type")));

        @SuppressWarnings("unchecked")
        Map<String, Object> source = (Map<String, Object>) imageBlock.get("source");
        check("Anthropic 图片转换：data URL → base64", "base64", String.valueOf(source.get("type")));
        check("Anthropic 图片转换：media_type", "image/png", String.valueOf(source.get("media_type")));
        check("Anthropic 图片转换：data 载荷", "AAAA", String.valueOf(source.get("data")));

        // http(s) URL → url 类型
        List<Map<String, Object>> httpMessages = new ArrayList<>();
        Map<String, Object> user = new LinkedHashMap<>();
        user.put("role", "user");
        List<Object> httpParts = new ArrayList<>();
        Map<String, Object> text = new LinkedHashMap<>();
        text.put("type", "text");
        text.put("text", "看看这张图");
        httpParts.add(text);
        Map<String, Object> imageBlockOpenAI = new LinkedHashMap<>();
        imageBlockOpenAI.put("type", "image_url");
        Map<String, Object> iu = new LinkedHashMap<>();
        iu.put("url", "https://example.com/a.jpg");
        imageBlockOpenAI.put("image_url", iu);
        httpParts.add(imageBlockOpenAI);
        user.put("content", httpParts);
        httpMessages.add(user);
        AnthropicClient.convertImageBlocks(httpMessages);

        @SuppressWarnings("unchecked")
        Map<String, Object> httpSource = (Map<String, Object>) ((Map<String, Object>)
                ((List<Object>) httpMessages.get(0).get("content")).get(1)).get("source");
        check("Anthropic 图片转换：公网 URL → url 类型", "url", String.valueOf(httpSource.get("type")));
        check("Anthropic 图片转换：url 值", "https://example.com/a.jpg", String.valueOf(httpSource.get("url")));
    }

    private static void anthropicThinkingAndMaxTokens() {
        AnthropicClient client = new AnthropicClient(null, MAPPER, "anthropic",
                "https://api.anthropic.com", null);

        List<Map<String, Object>> messages = plainMessages();
        Map<String, Object> nonThinking = client.buildRequestBody(
                messages, "claude-sonnet-4", 0.3, "non-thinking", true, null);
        check("Anthropic：non-thinking 不下发 thinking 字段", "false",
                String.valueOf(nonThinking.containsKey("thinking")));
        check("Anthropic：普通模式 max_tokens=8192", "8192", String.valueOf(nonThinking.get("max_tokens")));
        check("Anthropic：system 提升到顶层", "你是助手", String.valueOf(nonThinking.get("system")));

        Map<String, Object> thinking = client.buildRequestBody(
                plainMessages(), "claude-sonnet-4", 0.3, "thinking", true, null);
        check("Anthropic：thinking 模式下发 thinking", "true",
                String.valueOf(thinking.containsKey("thinking")));
        check("Anthropic：thinking 模式 max_tokens 大于 budget（16192）", "16192",
                String.valueOf(thinking.get("max_tokens")));
        check("Anthropic：thinking 模式移除 temperature", "false",
                String.valueOf(thinking.containsKey("temperature")));

        AnthropicClient custom = new AnthropicClient(null, MAPPER, "anthropic",
                "https://proxy.example.com/anthropic", 4096);
        Map<String, Object> customBody = custom.buildRequestBody(
                plainMessages(), "claude-sonnet-4", 0.3, "non-thinking", true, null);
        check("Anthropic：扩展配置 maxTokens 生效", "4096", String.valueOf(customBody.get("max_tokens")));
    }

    // ==================== 阿里云签名 ====================

    private static void aliyunPercentEncode() {
        check("阿里云编码：空格 → %20", "a%20b", AliyunBillingClient.percentEncode("a b"));
        check("阿里云编码：星号 → %2A", "%2A", AliyunBillingClient.percentEncode("*"));
        check("阿里云编码：波浪号保持", "~", AliyunBillingClient.percentEncode("~"));
        check("阿里云编码：斜杠", "%2F", AliyunBillingClient.percentEncode("/"));
    }

    private static void aliyunSignedUrl() {
        AliyunBillingClient client = new AliyunBillingClient(MAPPER);
        String url = client.buildSignedUrl("LTAI-test", "secret", "2026-10-18T00:00:00Z", "nonce-1");
        check("阿里云签名：包含 Action", "true", String.valueOf(url.contains("Action=QueryAccountBalance")));
        check("阿里云签名：包含 Version", "true", String.valueOf(url.contains("Version=2017-12-14")));
        check("阿里云签名：包含 Signature", "true", String.valueOf(url.contains("&Signature=")));
        check("阿里云签名：无未编码空格", "false", String.valueOf(url.contains(" ")));

        String url2 = client.buildSignedUrl("LTAI-test", "secret", "2026-10-18T00:00:00Z", "nonce-1");
        check("阿里云签名：同参数同签名（确定性）", "true", String.valueOf(url.equals(url2)));

        String url3 = client.buildSignedUrl("LTAI-test", "other-secret", "2026-10-18T00:00:00Z", "nonce-1");
        check("阿里云签名：不同 SK 不同签名", "false", String.valueOf(url.equals(url3)));
    }

    // ==================== 视觉能力判定 ====================

    private static void qwenVisionCapability() {
        check("vision：qwen-vl-max 支持", "true",
                String.valueOf(FileVisionService.supportsVisionModel("qwen", "qwen-vl-max")));
        check("vision：qwen3-vl-plus 支持", "true",
                String.valueOf(FileVisionService.supportsVisionModel("qwen", "qwen3-vl-plus")));
        check("vision：qwen-omni-turbo 支持", "true",
                String.valueOf(FileVisionService.supportsVisionModel("qwen", "qwen-omni-turbo")));
        check("vision：qwen-plus 纯文本不支持", "false",
                String.valueOf(FileVisionService.supportsVisionModel("qwen", "qwen-plus")));
    }

    // ==================== 工具方法 ====================

    private static Map<String, Object> qwenBody(QwenClient client, String thinkingMode) {
        return client.buildRequestBody(plainMessages(), "qwen-plus", 0.3, thinkingMode, true, null);
    }

    private static List<Map<String, Object>> plainMessages() {
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

    private static List<Map<String, Object>> messagesWithImage(String detail) {
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
        imageUrl.put("url", "data:image/png;base64,AAAA");
        imageUrl.put("detail", detail);
        imageBlock.put("image_url", imageUrl);
        parts.add(imageBlock);
        user.put("content", parts);
        messages.add(user);
        return messages;
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
