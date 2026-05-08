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

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 网页内容读取工具
 * 获取指定 URL 的完整文本内容
 */
@Slf4j
public class FetchWebpageTool implements Tool {

    private static final int MAX_CONTENT_LENGTH = 100_000;
    private static final int FETCH_TIMEOUT = 15_000;

    private final ObjectMapper objectMapper;
    private final RestTemplate restTemplate;

    public FetchWebpageTool(ObjectMapper objectMapper, WebFetchConfig webFetchConfig) {
        this.objectMapper = objectMapper;
        this.restTemplate = new RestTemplate();

        org.springframework.http.client.SimpleClientHttpRequestFactory factory =
                new org.springframework.http.client.SimpleClientHttpRequestFactory();
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
        return "读取指定网页的完整文本内容，获取详细信息和完整文章内容。当搜索结果摘要信息不足时使用此工具";
    }

    @Override
    public JsonNode getParameters() {
        ObjectNode parameters = objectMapper.createObjectNode();
        parameters.put("type", "object");
        parameters.put("description", "读取网页内容");

        ObjectNode properties = objectMapper.createObjectNode();
        ObjectNode urlProperty = objectMapper.createObjectNode();
        urlProperty.put("type", "string");
        urlProperty.put("description", "要读取的完整网页 URL（包含 http:// 或 https://）");
        properties.set("url", urlProperty);

        parameters.set("properties", properties);
        parameters.putArray("required").add("url");
        return parameters;
    }

    @Override
    public String execute(JsonNode arguments) {
        String url = arguments.path("url").asText("");
        if (url.isEmpty()) {
            return "读取失败：URL 不能为空";
        }

        log.info("读取网页: {}", url);

        try {
            java.net.URI uri = new java.net.URI(url);

            // 以字节数组获取原始响应，避免 StringHttpMessageConverter 默认编码导致乱码
            ResponseEntity<byte[]> response = restTemplate.exchange(uri, HttpMethod.GET, null, byte[].class);
            byte[] body = response.getBody();
            if (body == null || body.length == 0) {
                return "读取失败：网页内容为空";
            }

            // 1) 优先从响应头 Content-Type 获取编码
            String charset = null;
            MediaType contentType = response.getHeaders().getContentType();
            if (contentType != null) {
                Charset cs = contentType.getCharset();
                if (cs != null) {
                    charset = cs.name();
                }
            }

            // 2) 响应头没有编码，从 HTML <meta charset> 中解析
            if (charset == null) {
                charset = detectCharsetFromHtml(body);
            }

            // 3) 用检测到的编码解码，默认 UTF-8
            String html;
            if (charset != null) {
                html = new String(body, charset);
            } else {
                html = new String(body, StandardCharsets.UTF_8);
            }

            String text = htmlToText(html);

            if (text.isEmpty()) {
                return "该页面没有可读的文本内容";
            }

            // 限制返回长度
            if (text.length() > MAX_CONTENT_LENGTH) {
                text = text.substring(0, MAX_CONTENT_LENGTH)
                        + "\n\n...（内容过长，仅显示前 " + MAX_CONTENT_LENGTH + " 字符，完整内容请分段读取）";
            }

            StringBuilder sb = new StringBuilder();
            sb.append("--- 网页内容: ").append(url).append(" ---\n\n");
            sb.append(text);
            return sb.toString();

        } catch (Exception e) {
            log.error("读取网页失败: {}", url, e);
            return "读取网页失败（" + e.getMessage() + "）";
        }
    }

    private String htmlToText(String html) {
        if (html == null || html.isEmpty()) {
            return "";
        }

        String text = html;

        // 移除 script、style、noscript 块及其内容
        text = text.replaceAll("(?si)<script[^>]*>.*?</script>", "");
        text = text.replaceAll("(?si)<style[^>]*>.*?</style>", "");
        text = text.replaceAll("(?si)<noscript[^>]*>.*?</noscript>", "");

        // 块级标签替换为换行
        text = text.replaceAll("(?si)</?(?:div|p|br|li|ol|ul|h[1-6]|blockquote|pre|tr|td|th|section|article|nav|header|footer|aside)[^>]*>", "\n");

        // 移除剩余 HTML 标签
        text = text.replaceAll("<[^>]+>", "");

        // 解码常见 HTML 实体
        text = text.replace("&amp;", "&");
        text = text.replace("&lt;", "<");
        text = text.replace("&gt;", ">");
        text = text.replace("&quot;", "\"");
        text = text.replace("&nbsp;", " ");
        text = text.replace("&apos;", "'");
        text = text.replaceAll("&[a-zA-Z]+;", " ");

        // 折叠空白：多个换行合并为两个，行首行尾空白去除
        text = text.replaceAll("\\s+", " ");
        text = text.replaceAll("\\n\\s*\\n", "\n\n");
        text = text.replaceAll("^\\s+|\\s+$", "");

        return text.trim();
    }

    /**
     * 从 HTML 的 meta 标签中检测字符编码
     */
    private String detectCharsetFromHtml(byte[] body) {
        // 先用 ISO-8859-1 读取前 4096 字节，不会丢失任何字节信息
        String head = new String(body, 0, Math.min(body.length, 4096), StandardCharsets.ISO_8859_1);

        // 匹配 <meta charset="xxx">
        Matcher matcher = Pattern.compile(
                "(?i)<meta[^>]*charset\\s*=\\s*[\"']?\\s*([a-zA-Z0-9\\-_]+)",
                Pattern.CASE_INSENSITIVE
        ).matcher(head);
        if (matcher.find()) {
            return matcher.group(1);
        }

        // 匹配 <meta http-equiv="Content-Type" content="text/html; charset=xxx">
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
