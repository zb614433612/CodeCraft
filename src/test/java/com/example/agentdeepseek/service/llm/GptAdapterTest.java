package com.example.agentdeepseek.service.llm;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * GPT 官方专精适配器（{@code gpt} 模板）纯逻辑测试（main 方法，不依赖 JUnit / 网络）。
 *
 * <p>核心验证点：官方参数语义（{@code max_tokens} 已废弃、推理模型拒绝 {@code temperature}、
 * {@code reasoning_effort} 取值域随模型不同）+ 与 {@code openai} 公共契约的职责分界。</p>
 *
 * <h3>运行方式</h3>
 * <pre>
 * mvn -q -DskipTests -DskipFrontend=true test-compile
 * mvn -q org.codehaus.mojo:exec-maven-plugin:3.1.0:java \
 *     -Dexec.mainClass=com.example.agentdeepseek.service.llm.GptAdapterTest \
 *     -Dexec.classpathScope=test -DskipFrontend=true
 * </pre>
 */
public class GptAdapterTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static int passed = 0;
    private static int failed = 0;

    public static void main(String[] args) {
        endpoint();
        hostAndModelKey();
        reasoningEffortMapping();
        requestBodyOfficialParams();
        requestBodyReasoningParams();
        requestBodyStreamOptions();
        requestBodyInjectedMaxTokens();

        System.out.println("\n========== 结果: " + passed + " passed, " + failed + " failed ==========");
        if (failed > 0) {
            System.exit(1);
        }
    }

    // ==================== 端点 ====================

    private static void endpoint() {
        check("GPT 端点：默认官方", "https://api.openai.com/v1/chat/completions",
                GptClient.resolveChatEndpoint(null));
        check("GPT 端点：裸域名补 /v1", "https://api.openai.com/v1/chat/completions",
                GptClient.resolveChatEndpoint("https://api.openai.com"));
        check("GPT 端点：已含 /v1 不重复", "https://api.openai.com/v1/chat/completions",
                GptClient.resolveChatEndpoint("https://api.openai.com/v1"));
        check("GPT 端点：官方代理完整地址原样使用", "https://gpt-proxy.example.com/openai/chat/completions",
                GptClient.resolveChatEndpoint("https://gpt-proxy.example.com/openai/chat/completions"));
    }

    private static void hostAndModelKey() {
        check("GPT 官方域名判定：api.openai.com", "true",
                String.valueOf(GptClient.isOfficialHost("https://api.openai.com/v1")));
        check("GPT 官方域名判定：代理", "false",
                String.valueOf(GptClient.isOfficialHost("https://gpt-proxy.example.com")));
        check("GPT 模型名归一：openai/gpt-5 → gpt-5", "gpt-5",
                GptClient.normalizeModelKey("openai/gpt-5"));
        check("GPT-5 系列判定：gpt-5.4-mini", "true",
                String.valueOf(GptClient.isGpt5Family("gpt-5.4-mini")));
        check("GPT-5 系列判定：o3-mini", "false",
                String.valueOf(GptClient.isGpt5Family("o3-mini")));
    }

    // ==================== reasoning_effort 映射 ====================

    private static void reasoningEffortMapping() {
        check("effort：non-thinking + GPT-5 → none", "none",
                String.valueOf(GptClient.resolveReasoningEffort("non-thinking", true, true)));
        check("effort：non-thinking + o 系列 → 不下发（o 系列不支持 none）", "null",
                String.valueOf(GptClient.resolveReasoningEffort("non-thinking", true, false)));
        check("effort：thinking → medium", "medium",
                String.valueOf(GptClient.resolveReasoningEffort("thinking", true, true)));
        check("effort：thinking_max → high", "high",
                String.valueOf(GptClient.resolveReasoningEffort("thinking_max", true, true)));
        check("effort：minimal + GPT-5 → minimal", "minimal",
                String.valueOf(GptClient.resolveReasoningEffort("minimal", true, true)));
        check("effort：minimal + o 系列 → low（降级）", "low",
                String.valueOf(GptClient.resolveReasoningEffort("minimal", true, false)));
        check("effort：空 → 不下发", "null",
                String.valueOf(GptClient.resolveReasoningEffort(null, true, true)));
        check("effort：auto → 不下发", "null",
                String.valueOf(GptClient.resolveReasoningEffort("auto", true, true)));
    }

    // ==================== 请求体：官方参数 ====================

    private static void requestBodyOfficialParams() {
        GptClient client = new GptClient(null, MAPPER, "gpt", "https://api.openai.com/v1");

        Map<String, Object> body = client.buildRequestBody(
                plainMessages(), "gpt-4o", 0.3, "non-thinking", false, null);
        check("GPT 请求体：普通模型 max_completion_tokens=8192", "8192",
                String.valueOf(body.get("max_completion_tokens")));
        check("GPT 请求体：普通模型不下发 max_tokens", "false",
                String.valueOf(body.containsKey("max_tokens")));
        check("GPT 请求体：普通模型保留 temperature", "true",
                String.valueOf(body.containsKey("temperature")));
    }

    private static void requestBodyReasoningParams() {
        GptClient client = new GptClient(null, MAPPER, "gpt", null);

        Map<String, Object> gpt5 = client.buildRequestBody(
                plainMessages(), "gpt-5", 0.3, "non-thinking", false, null);
        check("GPT 请求体：GPT-5 + non-thinking → reasoning_effort=none", "none",
                String.valueOf(gpt5.get("reasoning_effort")));
        check("GPT 请求体：推理模型 max_completion_tokens=16384", "16384",
                String.valueOf(gpt5.get("max_completion_tokens")));
        check("GPT 请求体：推理模型移除 temperature", "false",
                String.valueOf(gpt5.containsKey("temperature")));

        Map<String, Object> o3 = client.buildRequestBody(
                plainMessages(), "o3-mini", 0.3, "thinking", false, null);
        check("GPT 请求体：o3-mini + thinking → reasoning_effort=medium", "medium",
                String.valueOf(o3.get("reasoning_effort")));
        check("GPT 请求体：o3-mini 移除 temperature", "false",
                String.valueOf(o3.containsKey("temperature")));
    }

    private static void requestBodyStreamOptions() {
        GptClient client = new GptClient(null, MAPPER, "gpt", null);
        Map<String, Object> body = client.buildRequestBody(
                plainMessages(), "gpt-4o", 0.3, "non-thinking", true, null);

        Object streamOptions = body.get("stream_options");
        check("GPT 请求体：流式带 stream_options", "true", String.valueOf(streamOptions instanceof Map));
        @SuppressWarnings("unchecked")
        Map<String, Object> options = (Map<String, Object>) streamOptions;
        check("GPT 请求体：include_usage=true", "true", String.valueOf(options.get("include_usage")));

        Map<String, Object> nonStream = client.buildRequestBody(
                plainMessages(), "gpt-4o", 0.3, "non-thinking", false, null);
        check("GPT 请求体：非流式不带 stream_options", "false",
                String.valueOf(nonStream.containsKey("stream_options")));
    }

    private static void requestBodyInjectedMaxTokens() {
        GptClient client = new GptClient(null, MAPPER, "gpt", null);

        // 模拟 post-build 注入（如 PromptOptimizeService 绕过 customizeRequest 写入 max_tokens）
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("model", "gpt-5");
        request.put("messages", plainMessages());
        request.put("max_tokens", 1234);
        request.put("temperature", 0.7);

        client.customizeRequest(request, plainMessages(), "gpt-5", 0.7, "non-thinking", false, null);

        check("GPT 请求体：注入的 max_tokens 被移除", "false",
                String.valueOf(request.containsKey("max_tokens")));
        check("GPT 请求体：转换后沿用注入值 1234", "1234",
                String.valueOf(request.get("max_completion_tokens")));
        check("GPT 请求体：推理模型移除 temperature", "false",
                String.valueOf(request.containsKey("temperature")));
    }

    // ==================== 工具方法 ====================

    private static List<Map<String, Object>> plainMessages() {
        List<Map<String, Object>> messages = new ArrayList<>();
        Map<String, Object> user = new LinkedHashMap<>();
        user.put("role", "user");
        user.put("content", "你好");
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
