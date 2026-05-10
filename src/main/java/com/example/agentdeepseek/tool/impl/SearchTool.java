package com.example.agentdeepseek.tool.impl;

import com.example.agentdeepseek.config.NetworkToolConfig;
import com.example.agentdeepseek.tool.Tool;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * 互联网搜索工具
 * 调用本地 SearXNG 搜索引擎获取实时信息
 */
@Slf4j
@Component
public class SearchTool implements Tool {

    private static final int MAX_RESULT_TITLE_LENGTH = 150;
    private static final int MAX_RESULT_CONTENT_LENGTH = 300;

    private final ObjectMapper objectMapper;
    private final RestTemplate restTemplate;
    private final NetworkToolConfig config;

    public SearchTool(ObjectMapper objectMapper, NetworkToolConfig config) {
        this.objectMapper = objectMapper;
        this.config = config;

        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(config.getFetchConnectTimeout());
        factory.setReadTimeout(config.getFetchReadTimeout());
        this.restTemplate = new RestTemplate(factory);
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

        String url = UriComponentsBuilder.fromHttpUrl(config.getSearxngUrl())
                .queryParam("q", query)
                .queryParam("format", "json")
                .build()
                .toUriString();

        log.info("SearXNG 搜索: {}", query);

        try {
            String response = restTemplate.getForObject(url, String.class);
            if (response == null) {
                return "错误：搜索引擎返回空响应，请确认 SearXNG 服务是否在 " + config.getSearxngUrl() + " 正常运行";
            }

            JsonNode root = objectMapper.readTree(response);
            JsonNode results = root.path("results");

            if (results.isMissingNode() || !results.isArray() || results.size() == 0) {
                return "未找到与「" + query + "」相关的搜索结果，请尝试使用不同的关键词";
            }

            StringBuilder sb = new StringBuilder();
            sb.append("--- 搜索结果 ---\n\n");

            int maxResults = config.getSearchMaxResults();
            int count = Math.min(results.size(), maxResults);
            for (int i = 0; i < count; i++) {
                JsonNode result = results.get(i);
                String title = truncate(result.path("title").asText(""), MAX_RESULT_TITLE_LENGTH);
                String content = truncate(result.path("content").asText(""), MAX_RESULT_CONTENT_LENGTH);
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

            if (results.size() > maxResults) {
                sb.append("... 共 ").append(results.size()).append(" 条结果，显示前 ")
                  .append(maxResults).append(" 条\n");
            }

            return sb.toString();

        } catch (org.springframework.web.client.ResourceAccessException e) {
            log.error("SearXNG 连接失败: {}", e.getMessage());
            return "错误：无法连接搜索引擎 " + config.getSearxngUrl()
                    + "，请确认 SearXNG 服务是否已启动（默认端口 8888）";
        } catch (org.springframework.web.client.HttpStatusCodeException e) {
            log.error("SearXNG 搜索失败: {} - HTTP {}", query, e.getStatusCode());
            return "错误：搜索失败 - 搜索引擎返回 HTTP " + e.getStatusCode().value();
        } catch (Exception e) {
            log.error("SearXNG 搜索失败", e);
            return "错误：搜索失败 - " + e.getClass().getSimpleName() + ": " + e.getMessage();
        }
    }

    private String truncate(String text, int maxLength) {
        if (text == null || text.isEmpty()) return "";
        if (text.length() <= maxLength) return text;
        return text.substring(0, maxLength) + "…";
    }
}
