package com.example.agentdeepseek.tool.impl;

import com.example.agentdeepseek.config.WebFetchConfig;
import com.example.agentdeepseek.tool.Tool;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 网页内容获取工具（合并网络连通性检测 + 网页内容读取）
 * 先检测目标网页的可达性，不可达则返回错误信息，可达则读取网页内容
 */
@Slf4j
@Component
public class WebFetchTool implements Tool {

    private static final int CONNECT_TIMEOUT = 5000;
    private static final int FETCH_TIMEOUT = 15_000;
    private static final int MAX_CONTENT_LENGTH = 100_000;

    private final ObjectMapper objectMapper;
    private final RestTemplate restTemplate;

    public WebFetchTool(ObjectMapper objectMapper, WebFetchConfig webFetchConfig) {
        this.objectMapper = objectMapper;
        this.restTemplate = new RestTemplate();

        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(FETCH_TIMEOUT);
        factory.setReadTimeout(FETCH_TIMEOUT);

        if (webFetchConfig.getProxy().isEnabled()) {
            java.net.Proxy proxy = new java.net.Proxy(
                    java.net.Proxy.Type.HTTP,
                    new java.net.InetSocketAddress(
                            webFetchConfig.getProxy().getHost(),
                            webFetchConfig.getProxy().getPort()
                    )
            );
            factory.setProxy(proxy);
            log.info("网页读取工具已配置代理: {}:{}",
                    webFetchConfig.getProxy().getHost(),
                    webFetchConfig.getProxy().getPort());
        }

        restTemplate.setRequestFactory(factory);
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

        // ========== 阶段一：网络连通性检测 ==========
        log.info("检测网页连通性: {}", url);
        String reachableError = checkConnectivity(url);
        if (reachableError != null) {
            log.warn("网页不可达: {} - {}", url, reachableError);
            return "网页不可达：" + url + "\n原因：" + reachableError;
        }

        // ========== 阶段二：读取网页内容 ==========
        log.info("网页可达，开始读取内容: {}", url);
        try {
            URI uri = new URI(url);

            ResponseEntity<byte[]> response = restTemplate.exchange(uri, HttpMethod.GET, null, byte[].class);
            byte[] body = response.getBody();
            if (body == null || body.length == 0) {
                return "错误：网页内容为空";
            }

            String charset = null;
            MediaType contentType = response.getHeaders().getContentType();
            if (contentType != null) {
                Charset cs = contentType.getCharset();
                if (cs != null) {
                    charset = cs.name();
                }
            }

            if (charset == null) {
                charset = detectCharsetFromHtml(body);
            }

            String html;
            if (charset != null) {
                html = new String(body, charset);
            } else {
                html = new String(body, StandardCharsets.UTF_8);
            }

            String text = htmlToText(html);

            if (text.isEmpty()) {
                return "错误：该页面没有可读的文本内容";
            }

            if (text.length() > MAX_CONTENT_LENGTH) {
                text = text.substring(0, MAX_CONTENT_LENGTH)
                        + "\n\n...（内容过长，仅显示前 " + MAX_CONTENT_LENGTH + " 字符）";
            }

            StringBuilder sb = new StringBuilder();
            sb.append("--- 网页内容: ").append(url).append(" ---\n\n");
            sb.append(text);
            return sb.toString();

        } catch (org.springframework.web.client.HttpStatusCodeException e) {
            log.error("读取网页失败: {} - HTTP {}", url, e.getStatusCode());
            return "错误：读取网页失败 - HTTP " + e.getStatusCode().value() + " " + e.getStatusText()
                    + "（目标网站拒绝访问或需要验证）";
        } catch (Exception e) {
            log.error("读取网页失败: {}", url, e);
            return "错误：读取网页失败 - " + e.getClass().getSimpleName();
        }
    }

    /**
     * 检测目标网页的 TCP 连通性
     *
     * @param url 目标 URL
     * @return 不可达时返回错误描述，可达时返回 null
     */
    private String checkConnectivity(String url) {
        try {
            URI uri = new URI(url);
            String host = uri.getHost();
            int port = uri.getPort() > 0 ? uri.getPort() : (url.startsWith("https") ? 443 : 80);

            long start = System.currentTimeMillis();
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress(host, port), CONNECT_TIMEOUT);
            }
            long elapsed = System.currentTimeMillis() - start;
            log.debug("网页连通性检测通过: {} ({}ms)", url, elapsed);
            return null; // 可达
        } catch (Exception e) {
            return "连接失败: " + e.getMessage();
        }
    }

    private String htmlToText(String html) {
        if (html == null || html.isEmpty()) {
            return "";
        }

        String text = html;

        text = text.replaceAll("(?si)<script[^>]*>.*?</script>", "");
        text = text.replaceAll("(?si)<style[^>]*>.*?</style>", "");
        text = text.replaceAll("(?si)<noscript[^>]*>.*?</noscript>", "");

        text = text.replaceAll("(?si)</?(?:div|p|br|li|ol|ul|h[1-6]|blockquote|pre|tr|td|th|section|article|nav|header|footer|aside)[^>]*>", "\n");

        text = text.replaceAll("<[^>]+>", "");

        text = text.replace("&amp;", "&");
        text = text.replace("&lt;", "<");
        text = text.replace("&gt;", ">");
        text = text.replace("&quot;", "\"");
        text = text.replace("&nbsp;", " ");
        text = text.replace("&apos;", "'");
        text = text.replaceAll("&[a-zA-Z]+;", " ");

        text = text.replaceAll("\\s+", " ");
        text = text.replaceAll("\\n\\s*\\n", "\n\n");
        text = text.replaceAll("^\\s+|\\s+$", "");

        return text.trim();
    }

    private String detectCharsetFromHtml(byte[] body) {
        String head = new String(body, 0, Math.min(body.length, 4096), StandardCharsets.ISO_8859_1);

        Matcher matcher = Pattern.compile(
                "(?i)<meta[^>]*charset\\s*=\\s*[\"']?\\s*([a-zA-Z0-9\\-_]+)",
                Pattern.CASE_INSENSITIVE
        ).matcher(head);
        if (matcher.find()) {
            return matcher.group(1);
        }

        matcher = Pattern.compile(
                "(?i)<meta[^>]*http-equiv\\s*=\\s*[\"']?Content-Type[\"']?[^>]*charset\\s*=\\s*([a-zA-Z0-9\\-_]+)",
                Pattern.CASE_INSENSITIVE
        ).matcher(head);
        if (matcher.find()) {
            return matcher.group(1);
        }

        return null;
    }
}
