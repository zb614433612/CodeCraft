package com.example.agentdeepseek.service.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.io.IOException;
import java.util.concurrent.TimeoutException;

/**
 * LLM API 错误格式化工具（面向用户的前端友好错误反馈）
 *
 * <p>把调用 LLM API（DeepSeek 等 OpenAI 兼容 Provider）时抛出的底层异常，
 * 转换为符合 DeepSeek 官方错误码表
 * （https://api-docs.deepseek.com/zh-cn/quick_start/error_codes）的中文提示
 * （标题 + 原因 + 解决建议），供聊天页展示与「一键继续」任务恢复。</p>
 *
 * <p>覆盖的错误码（官方表）：400 格式错误 / 401 认证失败 / 402 余额不足 / 422 参数错误 /
 * 429 请求速率达到上限 / 500 服务器故障 / 503 服务器繁忙；
 * 另补充：400 上下文超长特判、网络类异常（连接失败/超时）、其他未知异常兜底。</p>
 *
 * <p>使用场景：任务级 error 回调（工具循环异常出口）与无工具直连路径的错误出口。
 * 转换结果经 {@link #toTaskErrorEventJson(FriendlyError)} 构造为 task_error SSE 事件下发前端；
 * 同时其 message 会写入 agent_task.error_message（供页面刷新后的「任务中断」恢复）。</p>
 */
@Slf4j
public final class LLMErrorFormatter {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    /** 原始信息（响应体/异常消息）截断长度 */
    private static final int RAW_MAX_LEN = 300;

    private LLMErrorFormatter() {
    }

    /**
     * 用户友好的错误信息
     *
     * @param title     简短标题（如「余额不足（HTTP 402）」）
     * @param message   完整说明（原因 + 解决建议，可含原始错误详情）
     * @param status    HTTP 状态码（网络类异常为 null）
     * @param errorKey  错误分类键（前端可定制展示；网络类为 network_error 等）
     * @param retryable 是否建议稍后重试（429/5xx/网络类为 true）
     * @param raw       原始错误详情（截断后；可能为 null）
     */
    public record FriendlyError(String title, String message, Integer status,
                                String errorKey, boolean retryable, String raw) {

        /** 单行纯文本（日志/兜底展示用） */
        public String plainText() {
            return (title != null && !title.isEmpty()) ? title + "：" + message : message;
        }
    }

    /**
     * 将异常转换为用户友好的错误信息（沿 cause 链解析，深度上限 10）。
     * <p>优先级：HTTP 状态码类错误（精确匹配官方错误码表）→ 网络类错误 → 其他异常兜底。</p>
     */
    public static FriendlyError format(Throwable error) {
        if (error == null) {
            return new FriendlyError("任务执行失败", "任务因未知错误中断，请重试。",
                    null, "unknown", true, null);
        }
        // 1) HTTP 状态码类错误（WebClient 的 retrieve() 在 4xx/5xx 时抛出）：按官方错误码表映射
        WebClientResponseException wcre = findCause(error, WebClientResponseException.class);
        if (wcre != null) {
            return formatHttpError(wcre);
        }
        // 2) 网络类错误（DNS 解析失败/连接拒绝/连接超时/读响应头超时等，响应头到达前失败）
        if (findCause(error, WebClientRequestException.class) != null
                || findCause(error, IOException.class) != null
                || findCause(error, TimeoutException.class) != null
                || findCause(error, io.netty.handler.timeout.TimeoutException.class) != null) {
            String raw = safeMessage(error);
            return new FriendlyError(
                    "网络连接失败",
                    "无法连接到模型服务，请检查本机网络或代理设置后重试。"
                            + (raw != null && !raw.isBlank() ? "\n原始信息：" + truncate(raw) : ""),
                    null, "network_error", true, truncate(raw));
        }
        // 3) 其他异常：透传原始消息（如业务校验错误、模型不可用提示等）
        String raw = safeMessage(error);
        return new FriendlyError("任务执行失败",
                (raw != null && !raw.isBlank()) ? raw : "任务因错误中断，请重试。",
                null, "unknown", true, truncate(raw));
    }

    /**
     * 构造 task_error SSE 事件 JSON。
     * <p>前端 SseParser 将其解析为 task_error 事件：聊天区展示错误详情，
     * 输入区展示「任务中断」横幅并提供「继续任务」一键恢复入口。</p>
     */
    public static String toTaskErrorEventJson(FriendlyError fe) {
        try {
            ObjectNode node = OBJECT_MAPPER.createObjectNode();
            node.put("event", "task_error");
            node.put("title", fe.title());
            node.put("message", fe.message());
            if (fe.status() != null) {
                node.put("status", fe.status());
            }
            node.put("errorKey", fe.errorKey());
            node.put("retryable", fe.retryable());
            if (fe.raw() != null) {
                node.put("raw", fe.raw());
            }
            return OBJECT_MAPPER.writeValueAsString(node);
        } catch (Exception e) {
            // 兜底：手工拼 JSON（最小转义），确保错误信息一定能送达前端
            log.warn("构造 task_error 事件失败，使用兜底拼接: {}", e.getMessage());
            String fallback = (fe.message() == null || fe.message().isBlank())
                    ? "任务因错误中断，请重试。" : fe.message();
            String escaped = fallback.replace("\\", "\\\\").replace("\"", "\\\"")
                    .replace("\n", "\\n").replace("\r", "");
            return "{\"event\":\"task_error\",\"message\":\"" + escaped + "\"}";
        }
    }

    // ==================== 内部实现 ====================

    /** 按 DeepSeek 官方错误码表格式化 HTTP 错误（400/401/402/422/429/500/503 + 兜底分类） */
    private static FriendlyError formatHttpError(WebClientResponseException wcre) {
        int status = wcre.getStatusCode().value();
        String body = wcre.getResponseBodyAsString();
        String detail = extractApiErrorMessage(body);
        String raw = detail != null ? truncate(detail) : truncate(body);

        String title;
        String message;
        String key;
        boolean retryable;
        switch (status) {
            case 400 -> {
                if (isContextLengthExceeded(raw)) {
                    title = "上下文长度超限（HTTP 400）";
                    message = "本轮对话上下文已超过模型最大长度，请求被拒绝。"
                            + "建议：开启或调低上下文压缩、新建会话后继续，或减少单轮携带的内容。";
                    key = "context_length_exceeded";
                    retryable = false;
                } else {
                    title = "请求格式错误（HTTP 400）";
                    message = "请求体格式错误，请根据错误信息提示修改请求体。";
                    key = "bad_request";
                    retryable = false;
                }
            }
            case 401 -> {
                title = "认证失败（HTTP 401）";
                message = "API key 错误或未配置，认证失败。"
                        + "请检查 API key 是否正确（如没有 API key，请先在 DeepSeek 平台创建）。";
                key = "auth_failed";
                retryable = false;
            }
            case 402 -> {
                title = "余额不足（HTTP 402）";
                message = "账号余额不足。请确认账户余额，并前往 DeepSeek 平台充值页面进行充值。";
                key = "insufficient_balance";
                retryable = false;
            }
            case 422 -> {
                title = "参数错误（HTTP 422）";
                message = "请求体参数错误，请根据错误信息提示修改相关参数。";
                key = "invalid_params";
                retryable = false;
            }
            case 429 -> {
                title = "请求速率达到上限（HTTP 429）";
                message = "请求速率（TPM 或 RPM）达到上限。请稍后重试，或合理规划请求速率（降低并发、缩短上下文）。";
                key = "rate_limit";
                retryable = true;
            }
            case 500 -> {
                title = "服务器故障（HTTP 500）";
                message = "模型服务器内部故障，请等待后重试；若问题一直存在，请联系 DeepSeek 官方解决。";
                key = "server_error";
                retryable = true;
            }
            case 503 -> {
                title = "服务器繁忙（HTTP 503）";
                message = "模型服务器负载过高，请稍后重试。";
                key = "server_busy";
                retryable = true;
            }
            default -> {
                if (status >= 500) {
                    title = "服务端错误（HTTP " + status + "）";
                    message = "模型服务端错误，请稍后重试。";
                    key = "server_error";
                    retryable = true;
                } else {
                    title = "请求被拒绝（HTTP " + status + "）";
                    message = "模型服务拒绝了本次请求，请根据错误信息检查后重试。";
                    key = "request_rejected";
                    retryable = false;
                }
            }
        }
        if (raw != null && !raw.isBlank()) {
            message = message + "\n原始信息：" + raw;
        }
        return new FriendlyError(title, message, status, key, retryable, raw);
    }

    /**
     * 从 API 错误响应体中提取错误描述。
     * <p>兼容 OpenAI/DeepSeek 风格：{"error":{"message":"..."}} / {"error":"..."} / {"message":"..."}；
     * 非 JSON 时原样返回（由调用方截断）。</p>
     */
    private static String extractApiErrorMessage(String body) {
        if (body == null || body.isBlank()) {
            return null;
        }
        try {
            JsonNode root = OBJECT_MAPPER.readTree(body);
            JsonNode err = root.path("error");
            if (err.isObject()) {
                JsonNode m = err.path("message");
                if (m.isTextual() && !m.asText().isBlank()) {
                    return m.asText();
                }
            } else if (err.isTextual() && !err.asText().isBlank()) {
                return err.asText();
            }
            JsonNode m2 = root.path("message");
            if (m2.isTextual() && !m2.asText().isBlank()) {
                return m2.asText();
            }
            return null;
        } catch (Exception e) {
            return body;
        }
    }

    /** 判定 400 错误是否为「上下文长度超限」（DeepSeek 常见场景，给出针对性建议） */
    private static boolean isContextLengthExceeded(String raw) {
        if (raw == null || raw.isBlank()) {
            return false;
        }
        String lower = raw.toLowerCase();
        return lower.contains("context length") || lower.contains("context_length")
                || lower.contains("maximum context") || lower.contains("too many tokens")
                || lower.contains("token limit") || lower.contains("tokens in the messages");
    }

    /** 沿 cause 链查找指定类型异常（深度上限 10，防环） */
    @SuppressWarnings("unchecked")
    private static <T extends Throwable> T findCause(Throwable error, Class<T> type) {
        Throwable t = error;
        for (int depth = 0; t != null && depth < 10; depth++) {
            if (type.isInstance(t)) {
                return (T) t;
            }
            Throwable cause = t.getCause();
            if (cause == t) {
                break;
            }
            t = cause;
        }
        return null;
    }

    private static String truncate(String s) {
        if (s == null) {
            return null;
        }
        String trimmed = s.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        return trimmed.length() > RAW_MAX_LEN ? trimmed.substring(0, RAW_MAX_LEN) + "…" : trimmed;
    }

    private static String safeMessage(Throwable t) {
        try {
            return t.getMessage();
        } catch (Exception e) {
            return null;
        }
    }
}
