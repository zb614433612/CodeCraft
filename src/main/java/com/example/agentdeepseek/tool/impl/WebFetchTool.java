package com.example.agentdeepseek.tool.impl;

import com.example.agentdeepseek.config.NetworkToolConfig;
import com.example.agentdeepseek.tool.Tool;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.URI;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

/**
 * 网页内容获取工具（连通性检测 + 网页内容读取）
 * 先通过 ConnectivityChecker 检测目标网页可达性，再抓取网页内容
 * 使用 Jsoup 解析 HTML，比正则更准确
 */
@Slf4j
@Component
public class WebFetchTool implements Tool {

    private final ObjectMapper objectMapper;
    private final RestTemplate restTemplate;
    private final ConnectivityChecker connectivityChecker;
    private final NetworkToolConfig config;

    public WebFetchTool(ObjectMapper objectMapper, ConnectivityChecker connectivityChecker, NetworkToolConfig config) {
        this.objectMapper = objectMapper;
        this.connectivityChecker = connectivityChecker;
        this.config = config;

        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(config.getFetchConnectTimeout());
        factory.setReadTimeout(config.getFetchReadTimeout());

        if (config.getProxy() != null && config.getProxy().isEnabled()) {
            factory.setProxy(new Proxy(
                    Proxy.Type.HTTP,
                    new InetSocketAddress(config.getProxy().getHost(), config.getProxy().getPort())
            ));
            log.info("网页读取工具已配置代理: {}:{}", config.getProxy().getHost(), config.getProxy().getPort());
        }

        this.restTemplate = new RestTemplate(factory);
    }

    @Override
    public String getName() {
        return "web_fetch";
    }

    @Override
    public String getDescription() {
        return "获取指定网页的完整文本内容（先检测连通性，再抓取内容）。适用于需要阅读完整文章、文档或获取搜索结果之外的详细页面信息。"
                + "注意：区别于 web_search 工具（搜索返回摘要），web_fetch 用于读取具体 URL 的完整内容";
    }

    @Override
    public JsonNode getParameters() {
        ObjectNode parameters = objectMapper.createObjectNode();
        parameters.put("type", "object");
        parameters.put("description", "获取网页内容");

        ObjectNode properties = objectMapper.createObjectNode();
        ObjectNode urlProperty = objectMapper.createObjectNode();
        urlProperty.put("type", "string");
        urlProperty.put("description", "要获取的完整网页 URL（包含 http:// 或 https://）");
        properties.set("url", urlProperty);

        parameters.set("properties", properties);
        parameters.putArray("required").add("url");
        return parameters;
    }

    @Override
    public String execute(JsonNode arguments) {
        String url = arguments.path("url").asText("");
        if (url.isEmpty()) {
            return "错误：URL 不能为空";
        }

        // 阶段一：连通性检测（使用公共 ConnectivityChecker，走代理配置）
        log.info("检测网页连通性: {}", url);
        ConnectivityChecker.CheckResult checkResult = connectivityChecker.check(url);
        if (!checkResult.reachable) {
            log.warn("网页不可达: {} - {}", url, checkResult.error);
            return "网页不可达：" + url + "\n原因：" + (checkResult.error != null ? checkResult.error : "连接失败");
        }

        // 阶段二：读取网页内容
        log.info("网页可达，开始读取内容: {}", url);
        try {
            URI uri = new URI(url);

            ResponseEntity<byte[]> response = restTemplate.exchange(uri, HttpMethod.GET, null, byte[].class);
            byte[] body = response.getBody();
            if (body == null || body.length == 0) {
                return "错误：网页内容为空";
            }

            // 优先使用 HTTP Header 中的 Content-Type charset
            String charset = null;
            org.springframework.http.MediaType contentType = response.getHeaders().getContentType();
            if (contentType != null) {
                Charset cs = contentType.getCharset();
                if (cs != null) {
                    charset = cs.name();
                }
            }

            // 其次从 HTML meta 标签检测
            if (charset == null) {
                charset = Jsoup.parse(new String(body, StandardCharsets.ISO_8859_1))
                        .charset().name();
            }

            String html = new String(body, charset != null ? charset : StandardCharsets.UTF_8.name());

            // 使用 Jsoup 解析 HTML 并提取文本
            Document doc = Jsoup.parse(html);
            doc.select("script, style, noscript, nav, footer, header, aside, iframe").remove();
            String text = doc.body() != null ? doc.body().wholeText() : "";

            if (text.isBlank()) {
                return "错误：该页面没有可读的文本内容";
            }

            // 排版整理：合并连续空白，去除多余空行
            text = text.replaceAll("\\n{3,}", "\n\n");
            text = text.replaceAll("(?m)^[ \t]+|[ \t]+$", "");

            int maxLen = config.getFetchMaxContentLength();
            if (text.length() > maxLen) {
                text = text.substring(0, maxLen)
                        + "\n\n...（内容过长，仅显示前 " + maxLen + " 字符）";
            }

            StringBuilder sb = new StringBuilder();
            sb.append("--- 网页内容: ").append(url).append(" ---\n\n");
            sb.append(text);
            return sb.toString();

        } catch (org.springframework.web.client.HttpStatusCodeException e) {
            log.error("读取网页失败: {} - HTTP {}", url, e.getStatusCode());
            return "错误：读取网页失败 - HTTP " + e.getStatusCode().value() + " " + e.getStatusText()
                    + "（目标网站拒绝访问或需要验证）";
        } catch (org.springframework.web.client.ResourceAccessException e) {
            log.error("读取网页连接失败: {} - {}", url, e.getMessage());
            return "错误：读取网页失败 - 连接超时或网络不通，请用 check_network 检测目标网站可达性";
        } catch (Exception e) {
            log.error("读取网页失败: {}", url, e);
            return "错误：读取网页失败 - " + e.getClass().getSimpleName() + ": " + e.getMessage();
        }
    }
}
