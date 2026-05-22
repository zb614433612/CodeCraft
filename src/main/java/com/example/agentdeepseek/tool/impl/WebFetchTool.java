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

import com.example.agentdeepseek.tool.permission.OperationCategory;
import com.example.agentdeepseek.tool.permission.ToolPermission;

/**
 * 网页内容获取工具（连通性检测 + 网页内容读取）
 * 先通过 ConnectivityChecker 检测目标网页可达性，再抓取网页内容
 * 使用 Jsoup 解析 HTML，比正则更准确
 */
@Slf4j
@Component
@ToolPermission(category = OperationCategory.NETWORK, description = "获取网页内容")
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
        return "【适用场景】获取单个 URL 的完整网页文本内容，用于阅读完整文章、技术文档、API 返回正文等需要深入了解的场景。"
                + "【与 web_search 的区别】web_search 返回搜索结果的标题+摘要列表（概览），本工具读取某一个具体 URL 的完整内容（深入）。两个工具配合使用：先搜后读。"
                + "【与 http_request 的区别】http_request 用于调用 API 获取 JSON 数据，本工具用于抓取网页 HTML 并提取纯文本。"
                + "【限制】先自动检测连通性再抓取，内容上限 " + (config.getFetchMaxContentLength() / 1024) + "KB，超出部分截断。";
    }

    @Override
    public JsonNode getParameters() {
        ObjectNode parameters = objectMapper.createObjectNode();
        parameters.put("type", "object");
        parameters.put("description", "获取网页内容");

        ObjectNode properties = objectMapper.createObjectNode();
        ObjectNode urlProperty = objectMapper.createObjectNode();
        urlProperty.put("type", "string");
        urlProperty.put("description", "【必填】要获取的完整网页 URL，必须包含协议前缀。示例：https://www.baidu.com/s?wd=Java 或 https://docs.spring.io/spring-boot/docs/current/reference/html/");
        properties.set("url", urlProperty);

        parameters.set("properties", properties);
        parameters.putArray("required").add("url");
        return parameters;
    }

    @Override
    public String execute(JsonNode arguments) {
        String url = arguments.path("url").asText("");
        if (url.isEmpty()) {
            return "【参数错误】url 参数不能为空。请提供完整的网页地址，例如：{\"url\": \"https://docs.oracle.com/en/java/\"}";
        }

        // 阶段一：连通性检测（使用公共 ConnectivityChecker，走代理配置）
        log.info("检测网页连通性: {}", url);
        ConnectivityChecker.CheckResult checkResult = connectivityChecker.check(url);
        if (!checkResult.reachable) {
            log.warn("网页不可达: {} - {}", url, checkResult.error);
            return "【连通性检测失败】目标网页不可达：" + url
                    + "\n原因：" + (checkResult.error != null ? checkResult.error : "连接失败")
                    + "\n建议：1) 使用 check_network 工具检测该网站是否在当前网络可达"
                    + " 2) 如果网站本身正常但不可达，尝试启用代理"
                    + " 3) 检查 URL 是否拼写正确后重试";
        }

        // 阶段二：读取网页内容
        log.info("网页可达，开始读取内容: {}", url);
        try {
            URI uri = new URI(url);

            ResponseEntity<byte[]> response = restTemplate.exchange(uri, HttpMethod.GET, null, byte[].class);
            byte[] body = response.getBody();
            if (body == null || body.length == 0) {
                return "【内容为空】目标网页返回了空内容（HTTP 200 但响应体为 0 字节）。建议：1) 确认该 URL 在浏览器中是否能正常打开 2) 可能是登录墙/付费墙页面，尝试其他同类页面 3) 使用 http_request 工具直接发 GET 请求查看原始响应";
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
                return "【无文本内容】该页面没有可提取的文本内容。建议：1) 页面可能是纯 JavaScript 渲染的 SPA（服务端渲染不出内容），此类页面无法抓取 2) 尝试使用 http_request 直接获取 JSON API 数据 3) 换一个静态内容更多的同类页面";
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
            return "【HTTP 错误】服务器返回 HTTP " + e.getStatusCode().value() + " " + e.getStatusText()
                    + "。建议：1) 如果是 403/401，表示目标网站拒绝访问或有登录墙，换一个公开可访问的同类页面"
                    + " 2) 如果是 404，检查 URL 是否拼写正确或页面已不存在"
                    + " 3) 如果是 429，请求过于频繁，等待几秒后重试";
        } catch (org.springframework.web.client.ResourceAccessException e) {
            log.error("读取网页连接失败: {} - {}", url, e.getMessage());
            return "【连接失败】连接超时或网络不通。建议：1) 使用 check_network 工具检测目标网站可达性 2) 如果 check_network 也不可达，说明该网站在当前网络被屏蔽，需启用代理 3) 如果 check_network 可达但 web_fetch 失败，可能是超时设置太短，尝试简化页面或换更小的页面";
        } catch (Exception e) {
            log.error("读取网页失败: {}", url, e);
            return "【未知错误】抓取网页异常（" + e.getClass().getSimpleName() + "）。建议：1) 检查 URL 格式是否正确（是否遗漏 https:// 前缀）2) 尝试用 IP 地址替代域名 3) 对同一页面尝试用 http_request 工具发送 GET 请求";
        }
    }
}
