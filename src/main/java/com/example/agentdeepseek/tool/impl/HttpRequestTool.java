package com.example.agentdeepseek.tool.impl;

import com.example.agentdeepseek.tool.Tool;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

/**
 * HTTP 请求工具
 * 发送 HTTP 请求，用于测试 API 或获取数据
 */
@Slf4j
@Component
public class HttpRequestTool implements Tool {

    private static final int REQUEST_TIMEOUT_SECONDS = 30;
    private static final int MAX_RESPONSE_BYTES = 50 * 1024;

    private final ObjectMapper objectMapper;
    private final WebClient webClient;

    public HttpRequestTool(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.webClient = WebClient.builder()
                .codecs(config -> config.defaultCodecs().maxInMemorySize(MAX_RESPONSE_BYTES))
                .build();
    }

    @Override
    public String getName() {
        return "http_request";
    }

    @Override
    public String getDescription() {
        return "发送 HTTP 请求，支持 GET/POST/PUT/DELETE。"
                + "支持自定义 Headers 和 JSON Request Body。"
                + "超时 " + REQUEST_TIMEOUT_SECONDS + " 秒，响应体上限 50KB。"
                + "适用于测试 API 接口或获取远程数据";
    }

    @Override
    public JsonNode getParameters() {
        ObjectNode parameters = objectMapper.createObjectNode();
        parameters.put("type", "object");

        ObjectNode properties = objectMapper.createObjectNode();

        ObjectNode url = objectMapper.createObjectNode();
        url.put("type", "string");
        url.put("description", "请求 URL（必填），如 https://api.example.com/data");
        properties.set("url", url);

        ObjectNode method = objectMapper.createObjectNode();
        method.put("type", "string");
        method.put("description", "HTTP 方法，默认为 GET。可选：GET / POST / PUT / DELETE");
        method.put("default", "GET");
        properties.set("method", method);

        ObjectNode headers = objectMapper.createObjectNode();
        headers.put("type", "object");
        headers.put("description", "请求头（可选），如 {\"Authorization\": \"Bearer xxx\", \"Accept\": \"application/json\"}");
        properties.set("headers", headers);

        ObjectNode body = objectMapper.createObjectNode();
        body.put("type", "object");
        body.put("description", "请求体 JSON（可选），POST/PUT 时使用");
        properties.set("body", body);

        parameters.set("properties", properties);
        parameters.putArray("required").add("url");
        return parameters;
    }

    @Override
    @SuppressWarnings("unchecked")
    public String execute(JsonNode arguments) {
        String url = arguments.path("url").asText();
        if (url.isEmpty()) {
            return "错误：缺少必要参数 url";
        }

        String methodStr = arguments.path("method").asText("GET").toUpperCase();

        // 解析 headers
        Map<String, String> headers = new HashMap<>();
        JsonNode headersNode = arguments.path("headers");
        if (headersNode.isObject()) {
            headersNode.fields().forEachRemaining(entry ->
                    headers.put(entry.getKey(), entry.getValue().asText()));
        }

        // 解析 body
        JsonNode bodyNode = arguments.path("body");

        try {
            return executeRequest(url, methodStr, headers, bodyNode.isObject() ? bodyNode : null);
        } catch (Exception e) {
            log.error("HTTP 请求失败: {} {}", methodStr, url, e);
            return "错误：请求失败 - " + e.getMessage();
        }
    }

    private String executeRequest(String url, String method, Map<String, String> headers, JsonNode body) {
        WebClient.RequestBodySpec requestSpec = webClient
                .method(HttpMethod.valueOf(method))
                .uri(url);

        // 设置 headers
        for (Map.Entry<String, String> header : headers.entrySet()) {
            requestSpec = requestSpec.header(header.getKey(), header.getValue());
        }

        // 设置 body
        WebClient.ResponseSpec responseSpec;
        if (body != null && !body.isEmpty()) {
            responseSpec = requestSpec
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(BodyInserters.fromValue(body))
                    .retrieve();
        } else {
            responseSpec = requestSpec.retrieve();
        }

        // 执行请求（同步阻塞）
        String responseBody = responseSpec
                .bodyToMono(String.class)
                .timeout(Duration.ofSeconds(REQUEST_TIMEOUT_SECONDS))
                .onErrorResume(e -> {
                    if (e instanceof org.springframework.web.reactive.function.client.WebClientResponseException wcre) {
                        String errorBody = wcre.getResponseBodyAsString();
                        return Mono.just("HTTP " + wcre.getStatusCode() + "\n" +
                                (errorBody != null ? errorBody : wcre.getMessage()));
                    }
                    return Mono.just("请求异常: " + e.getMessage());
                })
                .block();

        // 截断过长响应
        if (responseBody != null && responseBody.length() > MAX_RESPONSE_BYTES) {
            responseBody = responseBody.substring(0, MAX_RESPONSE_BYTES)
                    + "\n\n...（响应体已截断，上限 " + (MAX_RESPONSE_BYTES / 1024) + "KB）";
        }

        return responseBody != null ? responseBody : "（无响应体）";
    }
}
