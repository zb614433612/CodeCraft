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
import org.jsoup.select.Elements;
import org.springframework.stereotype.Component;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import com.example.agentdeepseek.tool.permission.OperationCategory;
import com.example.agentdeepseek.tool.permission.ToolPermission;

/**
 * 互联网搜索工具
 * 调用 Bing 搜索引擎获取实时搜索结果
 * 优化点：1) 设置 User-Agent 避免被反爬 2) 支持代理 3) 使用 HTML 解析获取更丰富结果
 */
@Slf4j
@Component
@ToolPermission(category = OperationCategory.NETWORK, description = "网络搜索")
public class SearchTool implements Tool {

    private static final int MAX_RESULT_TITLE_LENGTH = 150;
    private static final int MAX_RESULT_CONTENT_LENGTH = 300;
    private static final String BING_SEARCH_URL = "https://cn.bing.com/search";

    private final ObjectMapper objectMapper;
    private final NetworkToolConfig config;
    private final String userAgent;

    public SearchTool(ObjectMapper objectMapper, NetworkToolConfig config) {
        this.objectMapper = objectMapper;
        this.config = config;
        this.userAgent = config.getFetchUserAgent();
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
                + "搜索源为 Bing 和百度（国内备用），自动切换，无需 API Key。";
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

        log.info("Bing 搜索: {}", query);

        // 尝试多种搜索策略
        String result = tryBingSearch(query, arguments);
        if (result != null) {
            return result;
        }

        // Bing 失败，尝试百度
        result = tryBaiduSearch(query, arguments);
        if (result != null) {
            return result;
        }

        return "【无结果】未找到与「" + query + "」相关的搜索结果。建议：1) 缩短关键词，用更通用的词（如将完整报错信息缩减为核心关键词）2) 去掉引号和特殊符号 3) 尝试用英文关键词搜索（英文结果通常更丰富）";
    }

    /**
     * 使用 Jsoup 直接连接 Bing 搜索（更可靠）
     */
    private String tryBingSearch(String query, JsonNode arguments) {
        try {
            String searchUrl = BING_SEARCH_URL + "?q=" + URLEncoder.encode(query, StandardCharsets.UTF_8);

            // 使用 Jsoup 直接连接，自动处理重定向和编码
            Document doc = Jsoup.connect(searchUrl)
                    .userAgent(userAgent)
                    .header("Accept-Language", "zh-CN,zh;q=0.9,en-US;q=0.8,en;q=0.7")
                    .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                    .timeout(config.getFetchConnectTimeout())
                    .proxy(getProxy())
                    .get();

            // 尝试多种选择器
            Elements items = doc.select("li.b_algo");
            if (items.isEmpty()) items = doc.select("#b_results .b_algo");
            if (items.isEmpty()) items = doc.select(".b_results > li");
            if (items.isEmpty()) items = doc.select("li[data-bm]");

            if (!items.isEmpty()) {
                return formatSearchResults(items, arguments, "Bing");
            }

            log.debug("Bing HTML 解析未找到结果，尝试备用选择器");
            return null;

        } catch (Exception e) {
            log.warn("Bing 搜索失败: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 使用 Jsoup 直接连接百度搜索（国内备用）
     */
    private String tryBaiduSearch(String query, JsonNode arguments) {
        try {
            String searchUrl = "https://www.baidu.com/s?wd=" + URLEncoder.encode(query, StandardCharsets.UTF_8);
            log.info("百度搜索备用: {}", query);

            Document doc = Jsoup.connect(searchUrl)
                    .userAgent(userAgent)
                    .header("Accept-Language", "zh-CN,zh;q=0.9")
                    .timeout(config.getFetchConnectTimeout())
                    .proxy(getProxy())
                    .get();

            // 百度搜索结果选择器
            Elements items = doc.select("div.result, div.c-container");
            if (items.isEmpty()) items = doc.select("#content_left > div");

            if (!items.isEmpty()) {
                return formatSearchResults(items, arguments, "百度");
            }

            log.debug("百度搜索解析未找到结果");
            return null;

        } catch (Exception e) {
            log.warn("百度搜索失败: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 格式化搜索结果
     */
    private String formatSearchResults(Elements items, JsonNode arguments, String source) {
        int maxResults = arguments.path("count").asInt(config.getSearchMaxResults());
        maxResults = Math.min(maxResults, config.getSearchMaxResults());

        StringBuilder sb = new StringBuilder();
        sb.append("--- 搜索结果（").append(source).append("）---\n\n");

        int validCount = 0;
        int count = Math.min(items.size(), maxResults);

        for (int i = 0; i < count && validCount < maxResults; i++) {
            Element item = items.get(i);

            // 提取标题 - 多种选择器
            Element titleElement = item.selectFirst("h2 a, h3 a, .t a, .c-title a");
            if (titleElement == null) continue;

            String title = truncate(titleElement.text(), MAX_RESULT_TITLE_LENGTH);
            String resultUrl = titleElement.attr("href");

            // 提取摘要 - 多种选择器
            Element snippetElement = item.selectFirst(
                    ".b_caption p, .b_lineclamp2, .b_paractl, .c-abstract, .c-span-last");
            String description = snippetElement != null ?
                    truncate(snippetElement.text(), MAX_RESULT_CONTENT_LENGTH) : "";

            if (title.isEmpty() && resultUrl.isEmpty()) continue;

            validCount++;
            sb.append(validCount).append(". ").append(title).append("\n");
            if (!description.isEmpty()) {
                sb.append(description).append("\n");
            }
            if (!resultUrl.isEmpty()) {
                sb.append("   源: ").append(resultUrl).append("\n");
            }
            sb.append("\n");
        }

        if (validCount == 0) return null;

        if (items.size() > maxResults) {
            sb.append("... 共 ").append(items.size()).append(" 条结果，显示前 ")
              .append(maxResults).append(" 条\n");
        }

        return sb.toString();
    }

    /**
     * 获取代理配置
     */
    private java.net.Proxy getProxy() {
        if (config.getProxy() != null && config.getProxy().isEnabled()) {
            return new java.net.Proxy(
                    java.net.Proxy.Type.HTTP,
                    new java.net.InetSocketAddress(config.getProxy().getHost(), config.getProxy().getPort())
            );
        }
        return java.net.Proxy.NO_PROXY;
    }

    private String truncate(String text, int maxLength) {
        if (text == null || text.isEmpty()) return "";
        if (text.length() <= maxLength) return text;
        return text.substring(0, maxLength) + "…";
    }
}
