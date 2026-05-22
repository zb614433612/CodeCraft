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

import com.example.agentdeepseek.tool.permission.OperationCategory;
import com.example.agentdeepseek.tool.permission.ToolPermission;

/**
 * HTTP 请求工具
 * 发送 HTTP 请求（GET/POST/PUT/DELETE），用于测试 API 或获取数据
 */
@Slf4j
@Component
@ToolPermission(category = OperationCategory.NETWORK, description = "发送HTTP请求")
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
        return "【适用场景】调用 REST API 获取 JSON 数据、测试 API 接口、发送 POST/PUT 请求提交数据。适合获取结构化数据而非人类可读网页。"
                + "【与 web_fetch 的区别】web_fetch 抓取网页 HTML 并提取纯文本（适合阅读文章），http_request 发送原始 HTTP 请求并返回响应体（适合调用 API 获取 JSON/XML 数据）。"
                + "【限制】超时 " + (config.getHttpRequestReadTimeout() / 1000) + " 秒，响应体上限 "
                + (config.getHttpRequestMaxResponseBytes() / 1024) + "KB，支持方法：" + String.join("/", config.getHttpRequestAllowedMethods()) + "。";
    }

    @Override
    public JsonNode getParameters() {
        ObjectNode parameters = objectMapper.createObjectNode();
        parameters.put("type", "object");

        ObjectNode properties = objectMapper.createObjectNode();

        ObjectNode url = objectMapper.createObjectNode();
        url.put("type", "string");
        url.put("description", "【必填】API 请求 URL，必须包含协议前缀。示例：https://api.github.com/repos/spring-projects/spring-boot/releases/latest");
        properties.set("url", url);

        ObjectNode method = objectMapper.createObjectNode();
        method.put("type", "string");
        method.put("description", "【可选】HTTP 方法，默认 GET 无需传递。仅在需要 POST/PUT/DELETE 时传。可选值：GET / POST / PUT / DELETE。示例：\"POST\"");
        method.put("default", "GET");
        properties.set("method", method);

        ObjectNode headers = objectMapper.createObjectNode();
        headers.put("type", "object");
        headers.put("description", "【可选】自定义请求头，需要认证或特殊 Accept 类型时使用。示例：{\"Authorization\": \"Bearer ghp_xxxx\", \"Accept\": \"application/vnd.github+json\"}。默认会自动补充 Content-Type: application/json（当有 body 时）");
        properties.set("headers", headers);

        ObjectNode body = objectMapper.createObjectNode();
        body.put("type", "string");
        body.put("description", "【可选】请求体，传 JSON 字符串。仅在 POST/PUT 方法时需要。示例：{\"name\": \"test\", \"value\": 123}。如果不传 Content-Type header，自动设为 application/json");
        properties.set("body", body);

        parameters.set("properties", properties);
        parameters.putArray("required").add("url");
        return parameters;
    }

    @Override
    public String execute(JsonNode arguments) {
        String url = arguments.path("url").asText();
        if (url.isEmpty()) {
            return "【参数错误】url 参数不能为空。请提供完整的 API 地址，例如：{\"url\": \"https://api.github.com/users/octocat\", \"method\": \"GET\"}";
        }

        String methodStr = arguments.path("method").asText("GET").toUpperCase();

        // 校验 HTTP 方法
        if (!allowedMethods.contains(methodStr)) {
            return "【参数错误】不支持的 HTTP 方法 \"" + methodStr + "\"。当前仅允许：" + String.join(", ", allowedMethods) + "。请将 method 参数改为以上之一，例如：{\"method\": \"GET\"}";
        }

        // 解析 headers
        Map<String, String> headerMap = new HashMap<>();
        JsonNode headersNode = arguments.path("headers");
        if (headersNode.isObject()) {
            headersNode.fields().forEachRemaining(entry ->
                    headerMap.put(entry.getKey(), entry.getValue().asText()));
        }

        // 解析 body
        String bodyStr = arguments.path("body").asText(null);

        try {
            return executeRequest(url, methodStr, headerMap, bodyStr);
        } catch (org.springframework.web.client.HttpStatusCodeException e) {
            log.warn("HTTP 请求返回错误状态: {} {} -> {}", methodStr, url, e.getStatusCode());
            return "HTTP " + e.getStatusCode() + "\n" + e.getResponseBodyAsString();
        } catch (org.springframework.web.client.ResourceAccessException e) {
            log.error("HTTP 请求连接失败: {} {}", methodStr, url, e);
            return "【连接失败】网络不通或目标服务器无响应。建议：1) 使用 check_network 工具检测目标 URL 域名是否可达 2) 如果域名不可达，尝试启用代理 3) 检查 URL 协议是否正确（应为 https:// 而非 http:// 或缺少前缀）";
        } catch (Exception e) {
            log.error("HTTP 请求失败: {} {}", methodStr, url, e);
            return "【未知错误】请求异常（" + e.getClass().getSimpleName() + "）。建议：1) 检查 URL 格式是否正确（是否遗漏 https://）2) 检查 body 是否为合法 JSON 字符串 3) 检查 headers 格式是否为 {\"key\": \"value\"} 对象";
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
