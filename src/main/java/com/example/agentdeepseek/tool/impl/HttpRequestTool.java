package com.example.agentdeepseek.tool.impl;

import com.example.agentdeepseek.config.NetworkToolConfig;
import com.example.agentdeepseek.tool.Tool;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.net.URI;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * HTTP 请求工具
 * 发送 HTTP 请求（GET/POST/PUT/DELETE），用于测试 API 或获取数据
 */
@Slf4j
@Component
public class HttpRequestTool implements Tool {

    private final ObjectMapper objectMapper;
    private final RestTemplate restTemplate;
    private final NetworkToolConfig config;
    private final Set<String> allowedMethods;

    public HttpRequestTool(ObjectMapper objectMapper, NetworkToolConfig config) {
        this.objectMapper = objectMapper;
        this.config = config;

        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(config.getHttpRequestConnectTimeout());
        factory.setReadTimeout(config.getHttpRequestReadTimeout());
        this.restTemplate = new RestTemplate(factory);

        this.allowedMethods = Set.copyOf(config.getHttpRequestAllowedMethods());
    }

    @Override
    public String getName() {
        return "http_request";
    }

    @Override
    public String getDescription() {
        return "发送 HTTP 请求，支持 GET/POST/PUT/DELETE。"
                + "支持自定义 Headers 和 JSON Request Body。"
                + "超时 " + (config.getHttpRequestReadTimeout() / 1000) + " 秒，响应体上限 "
                + (config.getHttpRequestMaxResponseBytes() / 1024) + "KB。"
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
    public String execute(JsonNode arguments) {
        String url = arguments.path("url").asText();
        if (url.isEmpty()) {
            return "错误：缺少必要参数 url";
        }

        String methodStr = arguments.path("method").asText("GET").toUpperCase();

        // 校验 HTTP 方法
        if (!allowedMethods.contains(methodStr)) {
            return "错误：不支持的 HTTP 方法 " + methodStr + "，仅支持 " + String.join(", ", allowedMethods);
        }

        // 解析 headers
        Map<String, String> headerMap = new HashMap<>();
        JsonNode headersNode = arguments.path("headers");
        if (headersNode.isObject()) {
            headersNode.fields().forEachRemaining(entry ->
                    headerMap.put(entry.getKey(), entry.getValue().asText()));
        }

        // 解析 body
        JsonNode bodyNode = arguments.path("body");
        String bodyStr = (bodyNode.isObject() && !bodyNode.isEmpty()) ? bodyNode.toString() : null;

        try {
            return executeRequest(url, methodStr, headerMap, bodyStr);
        } catch (org.springframework.web.client.HttpStatusCodeException e) {
            log.warn("HTTP 请求返回错误状态: {} {} -> {}", methodStr, url, e.getStatusCode());
            return "HTTP " + e.getStatusCode() + "\n" + e.getResponseBodyAsString();
        } catch (org.springframework.web.client.ResourceAccessException e) {
            log.error("HTTP 请求连接失败: {} {}", methodStr, url, e);
            return "错误：请求连接失败 - 网络不通或目标服务器无响应（" + e.getMessage() + "）";
        } catch (Exception e) {
            log.error("HTTP 请求失败: {} {}", methodStr, url, e);
            return "错误：请求失败 - " + e.getClass().getSimpleName() + ": " + e.getMessage();
        }
    }

    private String executeRequest(String url, String method, Map<String, String> headerMap, String bodyStr) {
        // 构建 Headers
        HttpHeaders httpHeaders = new HttpHeaders();
        boolean hasContentType = false;
        for (Map.Entry<String, String> entry : headerMap.entrySet()) {
            httpHeaders.set(entry.getKey(), entry.getValue());
            if ("Content-Type".equalsIgnoreCase(entry.getKey())) {
                hasContentType = true;
            }
        }

        // 有 body 且未指定 Content-Type 时，默认 application/json
        if (bodyStr != null && !hasContentType) {
            httpHeaders.setContentType(MediaType.APPLICATION_JSON);
        }

        HttpEntity<String> entity = new HttpEntity<>(bodyStr, httpHeaders);
        URI uri = URI.create(url);

        ResponseEntity<String> response = restTemplate.exchange(uri, HttpMethod.valueOf(method), entity, String.class);

        String responseBody = response.getBody();
        int maxBytes = config.getHttpRequestMaxResponseBytes();
        if (responseBody != null && responseBody.length() > maxBytes) {
            responseBody = responseBody.substring(0, maxBytes)
                    + "\n\n...（响应体已截断，上限 " + (maxBytes / 1024) + "KB）";
        }

        return responseBody != null ? responseBody : "（无响应体）";
    }
}
