package com.example.agentdeepseek.tool.impl;

import com.example.agentdeepseek.config.NetworkToolConfig;
import com.example.agentdeepseek.tool.Tool;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.parser.Parser;
import org.jsoup.select.Elements;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * 互联网搜索工具
 * 调用 Bing 搜索引擎（通过 RSS 格式，无需 API Key）获取实时搜索结果
 */
@Slf4j
@Component
public class SearchTool implements Tool {

    private static final int MAX_RESULT_TITLE_LENGTH = 150;
    private static final int MAX_RESULT_CONTENT_LENGTH = 300;
    private static final String BING_SEARCH_URL = "https://cn.bing.com/search";

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
        return "搜索互联网获取实时信息（通过 Bing 搜索引擎），返回标题、摘要片段和来源链接。适用于获取最新新闻、百科知识、实时数据等。"
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

        try {
            String encodedQuery = URLEncoder.encode(query, StandardCharsets.UTF_8);
            String url = BING_SEARCH_URL + "?q=" + encodedQuery + "&format=rss";

            log.info("Bing 搜索: {}", query);

            String response = restTemplate.getForObject(url, String.class);
            if (response == null || response.isBlank()) {
                return "错误：搜索引擎返回空响应";
            }

            // 解析 RSS XML
            Document doc = Jsoup.parse(response, "", Parser.xmlParser());
            Elements items = doc.select("item");

            if (items.isEmpty()) {
                return "未找到与「" + query + "」相关的搜索结果，请尝试使用不同的关键词";
            }

            StringBuilder sb = new StringBuilder();
            sb.append("--- 搜索结果 ---\n\n");

            int maxResults = config.getSearchMaxResults();
            int count = Math.min(items.size(), maxResults);
            for (int i = 0; i < count; i++) {
                Element item = items.get(i);
                String title = truncate(item.select("title").text(), MAX_RESULT_TITLE_LENGTH);
                String description = truncate(item.select("description").text(), MAX_RESULT_CONTENT_LENGTH);
                String resultUrl = item.select("link").text();

                sb.append(i + 1).append(". ").append(title).append("\n");
                if (!description.isEmpty()) {
                    sb.append(description).append("\n");
                }
                if (!resultUrl.isEmpty()) {
                    sb.append("   源: ").append(resultUrl).append("\n");
                }
                sb.append("\n");
            }

            if (items.size() > maxResults) {
                sb.append("... 共 ").append(items.size()).append(" 条结果，显示前 ")
                  .append(maxResults).append(" 条\n");
            }

            return sb.toString();

        } catch (org.springframework.web.client.ResourceAccessException e) {
            log.error("Bing 搜索连接失败: {}", e.getMessage());
            return "错误：无法连接搜索引擎，请检查网络连接是否正常（Bing 搜索需要联网）";
        } catch (org.springframework.web.client.HttpStatusCodeException e) {
            log.error("Bing 搜索失败: {} - HTTP {}", query, e.getStatusCode());
            return "错误：搜索失败 - 搜索引擎返回 HTTP " + e.getStatusCode().value();
        } catch (Exception e) {
            log.error("Bing 搜索失败", e);
            return "错误：搜索失败 - " + e.getClass().getSimpleName() + ": " + e.getMessage();
        }
    }

    private String truncate(String text, int maxLength) {
        if (text == null || text.isEmpty()) return "";
        if (text.length() <= maxLength) return text;
        return text.substring(0, maxLength) + "…";
    }
}
