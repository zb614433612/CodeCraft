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
import com.example.agentdeepseek.tool.permission.OperationCategory;
import com.example.agentdeepseek.tool.permission.ToolPermission;

/**
 * 互联网搜索工具
 * 调用 Bing 搜索引擎（通过 RSS 格式，无需 API Key）获取实时搜索结果
 */
@Slf4j
@Component
@ToolPermission(category = OperationCategory.NETWORK, description = "网络搜索")
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
        return "【适用场景】获取最新新闻、实时数据、百科知识、技术问题解决方案等需要搜索引擎聚合的信息。"
                + "【与 web_fetch 的区别】web_search 返回多个结果的标题+摘要+链接（概览），web_fetch 读取某一个 URL 的完整页面内容（深入）。"
                + "【建议】先用本工具搜索找到目标页面，再用 web_fetch 深入了解其中某个结果。"
                + "搜索源为 Bing 搜索引擎（RSS 格式，无需 API Key）。";
    }

    @Override
    public JsonNode getParameters() {
        ObjectNode parameters = objectMapper.createObjectNode();
        parameters.put("type", "object");
        parameters.put("description", "搜索互联网信息");

        ObjectNode properties = objectMapper.createObjectNode();
        ObjectNode queryProperty = objectMapper.createObjectNode();
        queryProperty.put("type", "string");
        queryProperty.put("description", "【必填】搜索关键词。尽量简洁精确，用空格分隔多个词。示例：Java 21 虚拟线程 最佳实践");
        properties.set("query", queryProperty);

        ObjectNode count = objectMapper.createObjectNode();
        count.put("type", "integer");
        count.put("description", "【可选】返回结果数量，默认 " + config.getSearchMaxResults() + "，最大 " + config.getSearchMaxResults() + "。仅在需要更多/更少结果时传，一般情况下用默认值即可");
        properties.set("count", count);

        parameters.set("properties", properties);
        parameters.putArray("required").add("query");
        return parameters;
    }

    @Override
    public String execute(JsonNode arguments) {
        String query = arguments.path("query").asText("");
        if (query.isEmpty()) {
            return "【参数错误】query 参数不能为空。请提供搜索关键词，例如：{\"query\": \"Spring Boot 3 配置指南\"}";
        }

        try {
            String encodedQuery = URLEncoder.encode(query, StandardCharsets.UTF_8);
            String url = BING_SEARCH_URL + "?q=" + encodedQuery + "&format=rss";

            log.info("Bing 搜索: {}", query);

            String response = restTemplate.getForObject(url, String.class);
            if (response == null || response.isBlank()) {
                return "【服务端错误】搜索引擎返回空响应，可能是网络问题或 Bing 暂时不可用。建议：1) 稍后重试 2) 更换搜索关键词 3) 使用 check_network 工具检测网络环境";
            }

            // 解析 RSS XML
            Document doc = Jsoup.parse(response, "", Parser.xmlParser());
            Elements items = doc.select("item");

            if (items.isEmpty()) {
                return "【无结果】未找到与「" + query + "」相关的搜索结果。建议：1) 缩短关键词，用更通用的词（如将完整报错信息缩减为核心关键词）2) 去掉引号和特殊符号 3) 尝试用英文关键词搜索（英文结果通常更丰富）";
            }

            StringBuilder sb = new StringBuilder();
            sb.append("--- 搜索结果 ---\n\n");

            int maxResults = arguments.path("count").asInt(config.getSearchMaxResults());
            maxResults = Math.min(maxResults, config.getSearchMaxResults());
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
            return "【网络错误】无法连接 Bing 搜索引擎。建议：1) 使用 check_network 工具检测当前网络环境 2) 如果检测到国外网站不通，请在设置中启用代理 3) 确认 cn.bing.com 在当前网络可达";
        } catch (org.springframework.web.client.HttpStatusCodeException e) {
            log.error("Bing 搜索失败: {} - HTTP {}", query, e.getStatusCode());
            return "【请求失败】搜索引擎返回 HTTP " + e.getStatusCode().value() + "。建议：1) 如果是 429 则请求过于频繁，等待几秒后再试 2) 如果是 5xx 则 Bing 服务暂时异常，稍后重试 3) 简化搜索关键词（缩短长度、去掉特殊字符）后重试";
        } catch (Exception e) {
            log.error("Bing 搜索失败", e);
            return "【未知错误】搜索异常（" + e.getClass().getSimpleName() + "）。建议：1) 检查搜索关键词是否包含特殊字符，尝试简化 2) 将中文关键词切换为英文尝试 3) 使用 check_network 检测网络状态后重试";
        }
    }

    private String truncate(String text, int maxLength) {
        if (text == null || text.isEmpty()) return "";
        if (text.length() <= maxLength) return text;
        return text.substring(0, maxLength) + "…";
    }
}
