package com.example.agentdeepseek;

import com.example.agentdeepseek.service.llm.LLMErrorFormatter;
import com.example.agentdeepseek.service.llm.LLMErrorFormatter.FriendlyError;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeoutException;

/**
 * LLMErrorFormatter 错误格式化回归测试（纯逻辑，不依赖 Spring 与外部环境）。
 *
 * <p>覆盖 DeepSeek 官方错误码表映射：400（含上下文超长特判）/401/402/422/429/500/503、
 * 未知 4xx/5xx 兜底、cause 链提取（RuntimeException 包装）、网络类异常（WebClientRequestException/
 * TimeoutException）、非 JSON 响应体、null 兜底，以及 task_error SSE 事件 JSON 构造（可解析、字段齐全）。</p>
 *
 * <p>运行方式（main 方法，非 JUnit）：
 * <pre>
 * mvn -q -DskipTests -DskipFrontend=true test-compile
 * mvn -q org.codehaus.mojo:exec-maven-plugin:3.1.0:java \
 *     -Dexec.mainClass=com.example.agentdeepseek.LLMErrorFormatterTest \
 *     -Dexec.classpathScope=test -DskipFrontend=true
 * </pre>
 * </p>
 */
public class LLMErrorFormatterTest {

    static int passed = 0;
    static int failed = 0;
    static final ObjectMapper MAPPER = new ObjectMapper();

    interface Assertion {
        boolean check() throws Exception;
    }

    public static void main(String[] args) throws Exception {
        System.out.println("=== LLMErrorFormatter 错误格式化回归测试 ===\n");

        // ===== 官方错误码：按表映射 =====

        test("402 余额不足：标题/状态/不重试/提取原始信息", () -> {
            FriendlyError fe = LLMErrorFormatter.format(httpError(402, "Payment Required",
                    "{\"error\":{\"message\":\"Insufficient Balance\",\"type\":\"unknown_error\"}}"));
            return Integer.valueOf(402).equals(fe.status())
                    && fe.title().contains("余额不足")
                    && !fe.retryable()
                    && fe.message().contains("充值")
                    && fe.message().contains("Insufficient Balance")
                    && "insufficient_balance".equals(fe.errorKey());
        });

        test("401 认证失败：提示检查 API key", () -> {
            FriendlyError fe = LLMErrorFormatter.format(httpError(401, "Unauthorized",
                    "{\"error\":{\"message\":\"Authentication Fails\",\"type\":\"authentication_error\"}}"));
            return Integer.valueOf(401).equals(fe.status())
                    && fe.title().contains("认证失败")
                    && !fe.retryable()
                    && fe.message().contains("API key");
        });

        test("429 请求速率达到上限：建议稍后重试（可重试）", () -> {
            FriendlyError fe = LLMErrorFormatter.format(httpError(429, "Too Many Requests",
                    "{\"error\":{\"message\":\"Rate limit reached\"}}"));
            return Integer.valueOf(429).equals(fe.status())
                    && fe.title().contains("请求速率达到上限")
                    && fe.retryable()
                    && "rate_limit".equals(fe.errorKey());
        });

        test("500 服务器故障：建议等待后重试（可重试）", () -> {
            FriendlyError fe = LLMErrorFormatter.format(httpError(500, "Internal Server Error",
                    "{\"error\":{\"message\":\"Server internal error\"}}"));
            return Integer.valueOf(500).equals(fe.status())
                    && fe.title().contains("服务器故障")
                    && fe.retryable();
        });

        test("503 服务器繁忙：建议稍后重试（可重试）", () -> {
            FriendlyError fe = LLMErrorFormatter.format(httpError(503, "Service Unavailable",
                    "{\"error\":{\"message\":\"Server is overloaded\"}}"));
            return Integer.valueOf(503).equals(fe.status())
                    && fe.title().contains("服务器繁忙")
                    && fe.retryable();
        });

        test("422 参数错误：不可重试", () -> {
            FriendlyError fe = LLMErrorFormatter.format(httpError(422, "Unprocessable Entity",
                    "{\"error\":{\"message\":\"Invalid parameter\"}}"));
            return Integer.valueOf(422).equals(fe.status())
                    && fe.title().contains("参数错误")
                    && !fe.retryable();
        });

        test("400 格式错误（普通）：按官方提示修改请求体", () -> {
            FriendlyError fe = LLMErrorFormatter.format(httpError(400, "Bad Request",
                    "{\"error\":{\"message\":\"Invalid request format\"}}"));
            return Integer.valueOf(400).equals(fe.status())
                    && fe.title().contains("请求格式错误")
                    && fe.message().contains("请求体");
        });

        test("400 上下文超长特判：给出压缩/新建会话建议", () -> {
            FriendlyError fe = LLMErrorFormatter.format(httpError(400, "Bad Request",
                    "{\"error\":{\"message\":\"This model's maximum context length is 65536 tokens. However, your messages resulted in 70000 tokens.\"}}"));
            return Integer.valueOf(400).equals(fe.status())
                    && fe.title().contains("上下文长度超限")
                    && fe.message().contains("压缩")
                    && "context_length_exceeded".equals(fe.errorKey());
        });

        // ===== 兜底分类 =====

        test("未知 4xx（418）：请求被拒绝兜底，不可重试", () -> {
            FriendlyError fe = LLMErrorFormatter.format(httpError(418, "I'm a teapot", "{}"));
            return Integer.valueOf(418).equals(fe.status())
                    && fe.title().contains("请求被拒绝")
                    && !fe.retryable();
        });

        test("未知 5xx（502）：服务端错误兜底，可重试", () -> {
            FriendlyError fe = LLMErrorFormatter.format(httpError(502, "Bad Gateway", "upstream error"));
            return Integer.valueOf(502).equals(fe.status())
                    && fe.title().contains("服务端错误")
                    && fe.retryable();
        });

        test("非 JSON 响应体：原文进原始信息（截断）", () -> {
            FriendlyError fe = LLMErrorFormatter.format(httpError(403, "Forbidden",
                    "<html>blocked by gateway</html>"));
            return Integer.valueOf(403).equals(fe.status())
                    && fe.raw() != null
                    && fe.raw().contains("blocked by gateway");
        });

        test("cause 链提取：RuntimeException 包装的 WebClientResponseException 仍能命中 402", () -> {
            WebClientResponseException wcre = httpError(402, "Payment Required",
                    "{\"error\":{\"message\":\"Insufficient Balance\"}}");
            RuntimeException wrapped = new RuntimeException("blockingChat 包装", wcre);
            FriendlyError fe = LLMErrorFormatter.format(wrapped);
            return Integer.valueOf(402).equals(fe.status())
                    && fe.title().contains("余额不足");
        });

        // ===== 网络类与未知异常 =====

        test("网络类：WebClientRequestException → 网络连接失败", () -> {
            WebClientRequestException ex = new WebClientRequestException(
                    new java.net.UnknownHostException("api.deepseek.com"),
                    HttpMethod.POST, URI.create("https://api.deepseek.com/chat/completions"),
                    new HttpHeaders());
            FriendlyError fe = LLMErrorFormatter.format(ex);
            return fe.status() == null
                    && fe.title().contains("网络连接失败")
                    && fe.retryable()
                    && "network_error".equals(fe.errorKey());
        });

        test("网络类：TimeoutException → 网络连接失败（可重试）", () -> {
            FriendlyError fe = LLMErrorFormatter.format(new TimeoutException("request timeout"));
            return fe.title().contains("网络连接失败") && fe.retryable();
        });

        test("未知业务异常：透传原始消息，可重试兜底", () -> {
            FriendlyError fe = LLMErrorFormatter.format(new RuntimeException("没有可用的 LLM Provider，请先在 LLM管理 页面配置"));
            return fe.status() == null
                    && fe.title().contains("任务执行失败")
                    && fe.message().contains("LLM Provider");
        });

        test("null 兜底：不抛异常，返回通用失败提示", () -> {
            FriendlyError fe = LLMErrorFormatter.format(null);
            return fe.title().contains("任务执行失败") && fe.message() != null;
        });

        // ===== task_error SSE 事件 JSON =====

        test("toTaskErrorEventJson：JSON 合法且字段齐全（含换行转义）", () -> {
            FriendlyError fe = LLMErrorFormatter.format(httpError(402, "Payment Required",
                    "{\"error\":{\"message\":\"Insufficient Balance\"}}"));
            String json = LLMErrorFormatter.toTaskErrorEventJson(fe);
            JsonNode node = MAPPER.readTree(json);
            return "task_error".equals(node.path("event").asText())
                    && node.path("status").asInt() == 402
                    && node.path("message").asText().contains("充值")
                    && node.path("message").asText().contains("原始信息")
                    && !node.path("retryable").asBoolean();
        });

        test("toTaskErrorEventJson：message 含换行时 JSON 仍可解析", () -> {
            FriendlyError fe = new FriendlyError("标题", "第一行\n第二行", 429, "rate_limit", true, "raw");
            String json = LLMErrorFormatter.toTaskErrorEventJson(fe);
            JsonNode node = MAPPER.readTree(json);
            return node.path("message").asText().contains("第一行")
                    && node.path("message").asText().contains("第二行");
        });

        System.out.println("\n===== 结果: " + passed + " 通过, " + failed + " 失败 =====");
        if (failed > 0) {
            System.exit(1);
        }
    }

    /** 构造一个携带响应体的 WebClientResponseException（模拟 WebClient 在 4xx/5xx 时抛出的异常） */
    static WebClientResponseException httpError(int status, String statusText, String body) {
        return WebClientResponseException.create(status, statusText,
                new HttpHeaders(), body.getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8);
    }

    static void test(String name, Assertion a) {
        try {
            if (a.check()) {
                passed++;
                System.out.println("  ✓ " + name);
            } else {
                failed++;
                System.out.println("  ✗ " + name);
            }
        } catch (Exception e) {
            failed++;
            System.out.println("  ✗ " + name + " （异常: " + e + "）");
        }
    }
}
