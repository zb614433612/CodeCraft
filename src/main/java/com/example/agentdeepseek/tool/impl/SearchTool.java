package com.example.agentdeepseek.tool.impl;

import com.example.agentdeepseek.tool.Tool;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

/**
 * 互联网搜索工具
 * 调用本地 SearXNG 搜索引擎获取实时信息
 */
@Slf4j
@Component
public class SearchTool implements Tool {

    private static final String SEARXNG_URL = "http://localhost:8888/search";
    private static final int MAX_RESULTS = 8;

    private final ObjectMapper objectMapper;
    private final RestTemplate restTemplate;

    public SearchTool(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.restTemplate = new RestTemplate();
    }

    @Override
    public String getName() {
        return "web_search";
    }

    @Override
    public String getDescription() {
        return "搜索互联网获取实时信息（通过本地 SearXNG 搜索引擎），返回标题、摘要片段和来源链接。适用于获取最新新闻、百科知识、实时数据等。"
                + "当需要获取某条搜索结果的完整页面内容时，请使用 web_fetch 工具读取具体网页";
    }

    @Override
    public JsonNode getParameters() {
        ObjectNode parameters = objectMapper.createObjectNode();
        parameters.put("type", "object");
        parameters.put("description", "搜索互联网信息");

        ObjectNode properties = objectMapper.createObjectNode();
        ObjectNode queryProperty = objectMapper.createObjectNode();
        queryProperty.put("type", "string");
        queryProperty.put("description", "搜索关键词，尽量简洁精确");
        properties.set("query", queryProperty);

        parameters.set("properties", properties);
        parameters.putArray("required").add("query");
        return parameters;
    }

    @Override
    public String execute(JsonNode arguments) {
        String query = arguments.path("query").asText("");
        if (query.isEmpty()) {
            return "错误：搜索关键词不能为空";
        }

        String url = SEARXNG_URL + "?q=" + encodeQuery(query) + "&format=json";
        log.info("SearXNG 搜索: {}", query);

        try {
            String response = restTemplate.getForObject(url, String.class);
            if (response == null) {
                return "错误：搜索引擎返回空响应";
            }

            JsonNode root = objectMapper.readTree(response);
            JsonNode results = root.path("results");

            if (results.isMissingNode() || !results.isArray() || results.size() == 0) {
                return "错误：未找到与「" + query + "」相关的搜索结果，请尝试使用不同的关键词";
            }

            StringBuilder sb = new StringBuilder();
            sb.append("--- 搜索结果 ---\n\n");

            int count = Math.min(results.size(), MAX_RESULTS);
            for (int i = 0; i < count; i++) {
                JsonNode result = results.get(i);
                String title = result.path("title").asText("");
                String content = result.path("content").asText("");
                String resultUrl = result.path("url").asText("");

                sb.append(i + 1).append(". ").append(title).append("\n");
                if (!content.isEmpty()) {
                    sb.append(content).append("\n");
                }
                if (!resultUrl.isEmpty()) {
                    sb.append("   源: ").append(resultUrl).append("\n");
                }
                sb.append("\n");
            }

            if (results.size() > MAX_RESULTS) {
                sb.append("... 共 ").append(results.size()).append(" 条结果，显示前 ")
                  .append(MAX_RESULTS).append(" 条\n");
            }

            return sb.toString();
        } catch (org.springframework.web.client.HttpStatusCodeException e) {
            log.error("SearXNG 搜索失败: {} - HTTP {}", query, e.getStatusCode());
            return "错误：搜索失败 - 搜索引擎返回 HTTP " + e.getStatusCode().value() + " " + e.getStatusText();
        } catch (Exception e) {
            log.error("SearXNG 搜索失败", e);
            return "错误：搜索失败 - " + e.getClass().getSimpleName();
        }
    }

    private String encodeQuery(String query) {
        try {
            return java.net.URLEncoder.encode(query, "UTF-8");
        } catch (Exception e) {
            return query;
        }
    }
}
