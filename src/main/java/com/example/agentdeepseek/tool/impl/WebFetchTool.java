package com.example.agentdeepseek.tool.impl;

import com.example.agentdeepseek.config.NetworkToolConfig;
import com.example.agentdeepseek.config.ProxyResolver;
import com.example.agentdeepseek.tool.Tool;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import java.net.Proxy;
import java.net.URI;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import com.example.agentdeepseek.tool.permission.OperationCategory;
import com.example.agentdeepseek.tool.permission.ToolPermission;

/**
 * 网页内容获取工具（连通性检测 + 网页内容读取）。
 *
 * <p>抓取策略（P0 + P1 组合，优先级：Jina 优先版）：
 * <ol>
 *   <li>本机有可用代理时，优先经 <b>Jina Reader</b> 渲染抓取（反爬挑战页、JS 渲染页同样有效）；</li>
 *   <li>Jina 不可用（配额受限 429 / 请求失败）或本机无代理时，<b>降级为本地抓取</b>——
 *       连通性快速失败 + 多 UA 重试 + 反爬拦截页识别，网络模式跟随 {@link ProxyResolver}（代理或直连兜底）。</li>
 * </ol>
 */
@Slf4j
@Component
@ToolPermission(category = OperationCategory.NETWORK, description = "获取网页内容")
public class WebFetchTool implements Tool {

    /** Jina Reader 默认地址（配置缺失时兜底） */
    private static final String DEFAULT_JINA_READER_URL = "https://r.jina.ai/";

    /** UA 池为空时的兜底 UA（配置极端情况下保证抓取可用） */
    private static final String FALLBACK_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";

    /** 重试等待上限（毫秒），防止异常配置值导致长时间阻塞 */
    private static final long MAX_RETRY_WAIT_MS = 30_000L;

    private final ObjectMapper objectMapper;
    private final ConnectivityChecker connectivityChecker;
    private final NetworkToolConfig config;
    private final ProxyResolver proxyResolver;

    /** 本地抓取模板（与代理状态绑定，代理启停时自动重建） */
    private volatile RestTemplate fetchTemplate;
    private volatile String fetchTemplateProxyKey;

    /** Jina Reader 模板（独立读取超时，同样跟随代理状态重建） */
    private volatile RestTemplate jinaTemplate;
    private volatile String jinaTemplateProxyKey;

    /** Jina 配额受限（429）后的冷却截止时间（epoch ms）；冷却期内直接走本地抓取 */
    private volatile long jinaCooldownUntilMs = 0L;

    public WebFetchTool(ObjectMapper objectMapper, ConnectivityChecker connectivityChecker,
                        NetworkToolConfig config, ProxyResolver proxyResolver) {
        this.objectMapper = objectMapper;
        this.connectivityChecker = connectivityChecker;
        this.config = config;
        this.proxyResolver = proxyResolver;
        this.fetchTemplate = buildRestTemplate(config.getFetchReadTimeout());
        this.fetchTemplateProxyKey = proxyResolver.signature();
    }

    // ============================================================
    // HTTP 模板管理（代理状态变化时自动重建，无需重启应用）
    // ============================================================

    /** 本地抓取用 RestTemplate */
    private RestTemplate fetchTemplate() {
        String key = proxyResolver.signature();
        RestTemplate current = fetchTemplate;
        if (current != null && key.equals(fetchTemplateProxyKey)) {
            return current;
        }
        synchronized (this) {
            if (fetchTemplate == null || !key.equals(fetchTemplateProxyKey)) {
                fetchTemplate = buildRestTemplate(config.getFetchReadTimeout());
                fetchTemplateProxyKey = key;
                log.info("网页读取工具网络模式变更: {}", proxyResolver.describe());
            }
            return fetchTemplate;
        }
    }

    /** Jina Reader 用 RestTemplate（读取超时单独放宽，适配渲染慢页面） */
    private RestTemplate jinaTemplate() {
        String key = proxyResolver.signature();
        RestTemplate current = jinaTemplate;
        if (current != null && key.equals(jinaTemplateProxyKey)) {
            return current;
        }
        synchronized (this) {
            if (jinaTemplate == null || !key.equals(jinaTemplateProxyKey)) {
                jinaTemplate = buildRestTemplate(resolveJinaTimeoutMs());
                jinaTemplateProxyKey = key;
                log.info("网页读取工具（Jina 通道）网络模式变更: {}", proxyResolver.describe());
            }
            return jinaTemplate;
        }
    }

    private RestTemplate buildRestTemplate(int readTimeoutMs) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(config.getFetchConnectTimeout());
        factory.setReadTimeout(readTimeoutMs);

        // 代理动态解析：探测到本地代理则走代理，否则直连（国内网络兜底）
        Proxy proxy = proxyResolver.resolve();
        if (proxy != Proxy.NO_PROXY) {
            factory.setProxy(proxy);
        }

        // 兜底请求头（具体抓取请求会显式携带完整浏览器头，覆盖此处默认值）
        RestTemplate template = new RestTemplate(factory);
        template.getInterceptors().add((request, body, execution) -> {
            if (!request.getHeaders().containsKey("User-Agent")) {
                request.getHeaders().set("User-Agent", config.getFetchUserAgent());
            }
            if (!request.getHeaders().containsKey("Accept")) {
                request.getHeaders().set("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8");
            }
            if (!request.getHeaders().containsKey("Accept-Language")) {
                request.getHeaders().set("Accept-Language", "zh-CN,zh;q=0.9,en-US;q=0.8,en;q=0.7");
            }
            return execution.execute(request, body);
        });
        return template;
    }

    private int resolveJinaTimeoutMs() {
        return config.getFetchJinaTimeoutMs() > 0 ? config.getFetchJinaTimeoutMs() : 20_000;
    }

    // ============================================================
    // Tool 接口
    // ============================================================

    @Override
    public String getName() {
        return "web_fetch";
    }

    @Override
    public String getDescription() {
        return "【适用场景】获取单个 URL 的完整网页文本内容，用于阅读完整文章、技术文档、API 返回正文等需要深入了解的场景。"
                + "【与 web_search 的区别】web_search 返回搜索结果的标题+摘要列表（概览），本工具读取某一个具体 URL 的完整内容（深入）。两个工具配合使用：先搜后读。"
                + "【与 http_request 的区别】http_request 用于调用 API 获取 JSON 数据，本工具用于抓取网页 HTML 并提取纯文本。"
                + "【抓取策略】自动选择最优通道：本机有可用代理时优先经 Jina Reader 渲染抓取（反爬验证页、JS 渲染页同样有效）；"
                + "无代理或 Jina 配额受限时自动降级为本地抓取（多 UA 重试 + 反爬识别），失败时会说明具体原因。"
                + "【限制】内容上限 " + (config.getFetchMaxContentLength() / 1024) + "KB，超出部分截断。";
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

        URI uri;
        try {
            uri = new URI(url);
        } catch (Exception e) {
            return "【参数错误】URL 格式不合法（" + e.getMessage() + "）。请检查是否包含协议前缀（如 https://），且不含多余空格或非法字符。";
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!"http".equals(scheme) && !"https".equals(scheme)) {
            return "【参数错误】仅支持 http/https 协议的网页地址，当前 URL：" + url;
        }
        if (uri.getHost() == null || uri.getHost().isBlank()) {
            return "【参数错误】URL 缺少主机名：" + url;
        }

        boolean privateTarget = isPrivateHost(uri.getHost());

        // 阶段一：本机有可用代理时，优先走 Jina Reader（渲染抓取，更擅长反爬 / JS 页面）
        if (shouldTryJina(privateTarget)) {
            log.info("优先尝试 Jina Reader 抓取: {}（网络模式: {}）", url, proxyResolver.describe());
            String jinaOutput = tryJina(url);
            if (jinaOutput != null) {
                return jinaOutput;
            }
            log.info("Jina Reader 不可用，降级本地抓取: {}", url);
        }

        // 阶段二：本地抓取（Jina 不可用 / 本机无代理时的降级路径）
        return localFetch(url, uri);
    }

    // ============================================================
    // 阶段一：Jina Reader（有代理时的优先通道）
    // ============================================================

    /** 是否走 Jina：开关开启 + 非内网地址 + 不在冷却期 + 本机代理可用 */
    private boolean shouldTryJina(boolean privateTarget) {
        if (!config.isFetchJinaEnabled()) {
            return false;
        }
        if (privateTarget) {
            log.debug("目标为内网/私有地址，跳过 Jina Reader（避免内网信息发送给第三方服务）");
            return false;
        }
        if (isJinaCoolingDown()) {
            log.debug("Jina Reader 处于配额冷却期，直接走本地抓取");
            return false;
        }
        if (!proxyResolver.isProxyActive()) {
            log.debug("未检测到可用本地代理，跳过 Jina Reader（其国内直连不可达）");
            return false;
        }
        return true;
    }

    private boolean isJinaCoolingDown() {
        return System.currentTimeMillis() < jinaCooldownUntilMs;
    }

    private void enterJinaCooldown() {
        int seconds = Math.max(1, config.getFetchJinaCooldownSeconds());
        jinaCooldownUntilMs = System.currentTimeMillis() + seconds * 1000L;
    }

    /**
     * 尝试通过 Jina Reader 抓取目标页面。
     *
     * @return 成功时返回格式化后的工具输出；失败（含配额受限）返回 null，由调用方降级本地抓取
     */
    private String tryJina(String targetUrl) {
        String base = config.getFetchJinaReaderUrl();
        if (base == null || base.isBlank()) {
            base = DEFAULT_JINA_READER_URL;
        }
        if (!base.endsWith("/")) {
            base = base + "/";
        }
        String jinaUrl = base + targetUrl;
        long start = System.currentTimeMillis();
        try {
            HttpHeaders headers = new HttpHeaders();
            // ⚠️ Jina 对「浏览器 UA」的自动化请求返回 403（实测），必须使用工具型 UA
            String jinaUa = config.getFetchJinaUserAgent();
            headers.set("User-Agent", (jinaUa == null || jinaUa.isBlank()) ? "CodeCraft-WebFetch/2.0" : jinaUa.trim());
            headers.set("Accept", "text/plain, text/markdown, */*");
            String apiKey = config.getFetchJinaApiKey();
            if (apiKey != null && !apiKey.isBlank()) {
                headers.set("Authorization", "Bearer " + apiKey.trim());
            }

            ResponseEntity<String> response = jinaTemplate().exchange(
                    new URI(jinaUrl), HttpMethod.GET, new HttpEntity<>(headers), String.class);
            String body = response.getBody();
            if (body == null || body.isBlank()) {
                log.warn("Jina Reader 返回空内容（{}ms），降级本地抓取: {}", System.currentTimeMillis() - start, targetUrl);
                return null;
            }
            log.info("Jina Reader 抓取成功: {}（{}ms, {} 字符）",
                    targetUrl, System.currentTimeMillis() - start, body.length());
            return formatResult(targetUrl, "Jina Reader（经代理渲染抓取）", body);
        } catch (HttpStatusCodeException e) {
            int code = e.getStatusCode().value();
            if (code == 429) {
                enterJinaCooldown();
                log.warn("Jina Reader 配额受限（HTTP 429），冷却 {}s 内直接走本地抓取: {}",
                        Math.max(1, config.getFetchJinaCooldownSeconds()), targetUrl);
            } else {
                log.warn("Jina Reader 抓取失败（HTTP {}），降级本地抓取: {} | 响应片段: {}",
                        code, targetUrl, brief(e.getResponseBodyAsString(), 160));
            }
            return null;
        } catch (Exception e) {
            log.warn("Jina Reader 抓取异常（{}），降级本地抓取: {} - {}",
                    e.getClass().getSimpleName(), targetUrl, e.getMessage());
            return null;
        }
    }

    // ============================================================
    // 阶段二：本地抓取（降级路径）
    // ============================================================

    /** 本地抓取：连通性快速失败 + 多 UA 重试 + 反爬拦截页识别 */
    private String localFetch(String url, URI uri) {
        // 连通性快速失败：网络不通时无需浪费多次重试
        log.info("检测网页连通性: {}（网络模式: {}）", url, proxyResolver.describe());
        ConnectivityChecker.CheckResult checkResult = connectivityChecker.check(url);
        if (!checkResult.reachable) {
            log.warn("网页不可达: {} - {}", url, checkResult.error);
            return "【连通性检测失败】目标网页不可达：" + url
                    + "\n原因：" + (checkResult.error != null ? checkResult.error : "连接失败")
                    + "\n当前网络模式：" + proxyResolver.describe()
                    + "\n建议：1) 使用 check_network 工具检测该网站是否在当前网络可达"
                    + " 2) 如果网站本身正常但不可达，尝试启用代理"
                    + " 3) 检查 URL 是否拼写正确后重试";
        }

        int maxRetries = Math.max(0, config.getFetchRetryCount());
        List<String> pool = resolveUserAgentPool();
        int baseIndex = pickProfileIndex(uri.getHost(), 0, pool.size());
        long nextDelayMs = Math.max(0, config.getFetchRetryDelayMs());

        for (int attempt = 0; attempt <= maxRetries; attempt++) {
            if (attempt > 0) {
                sleepQuietly(nextDelayMs);
            }
            // 每轮先归位默认等待值；HTTP 429 时按 Retry-After 覆写（供下一轮使用）
            nextDelayMs = Math.max(0, config.getFetchRetryDelayMs());
            boolean lastAttempt = attempt >= maxRetries;
            UserAgentProfile profile = UserAgentProfile.of(pool.get((baseIndex + attempt) % pool.size()));

            try {
                long start = System.currentTimeMillis();
                ResponseEntity<byte[]> response = fetchTemplate().exchange(
                        uri, HttpMethod.GET, new HttpEntity<>(buildBrowserHeaders(profile)), byte[].class);
                long cost = System.currentTimeMillis() - start;

                byte[] body = response.getBody();
                if (body == null || body.length == 0) {
                    return "【内容为空】目标网页返回了空内容（HTTP 200 但响应体为 0 字节）。建议：1) 确认该 URL 在浏览器中是否能正常打开 2) 可能是登录墙/付费墙页面，尝试其他同类页面 3) 使用 http_request 工具直接发 GET 请求查看原始响应";
                }

                String html = decodeHtml(body, response.getHeaders().getContentType());
                String text = extractText(html);

                if (config.isFetchBlockDetectionEnabled()
                        && isBlockPage(html, text, config.getFetchBlockKeywords(), config.getFetchBlockMinTextLength())) {
                    log.warn("疑似反爬拦截页（第 {}/{} 次尝试）: {}", attempt + 1, maxRetries + 1, url);
                    if (!lastAttempt) {
                        continue;
                    }
                    return "【反爬拦截】目标页面疑似触发了网站的风控 / 人机验证（Cloudflare 等），多次换 UA 重试仍未通过。"
                            + "\n当前网络模式：" + proxyResolver.describe()
                            + "\n建议：1) 换一个同类内容的公开页面或镜像站"
                            + " 2) 稍等片刻再重试（部分风控为临时限制）"
                            + " 3) 本机代理可用时 Jina Reader 通道对反爬页面效果更好（会自动优先启用）";
                }

                if (text.isBlank()) {
                    return "【无文本内容】该页面没有可提取的文本内容。建议：1) 页面可能是纯 JavaScript 渲染的 SPA（服务端渲染不出内容），此类页面无法抓取 2) 尝试使用 http_request 直接获取 JSON API 数据 3) 换一个静态内容更多的同类页面";
                }

                String tidyText = tidy(text);
                log.info("本地抓取成功: {}（{}ms, {} 字符, 第 {} 次尝试）", url, cost, tidyText.length(), attempt + 1);
                return formatResult(url, "本地抓取（" + proxyResolver.describe() + "）", tidyText);

            } catch (HttpStatusCodeException e) {
                int code = e.getStatusCode().value();
                if (code == 400 || code == 401 || code == 404) {
                    log.warn("抓取失败（确定性错误，不重试）: {} - HTTP {}", url, code);
                    return httpErrorHint(code, e.getStatusText());
                }
                boolean retryable = code == 403 || code == 429 || code == 503 || code >= 500;
                if (retryable && !lastAttempt) {
                    if (code == 429) {
                        nextDelayMs = Math.max(nextDelayMs, parseRetryAfter(retryAfterValues(e), nextDelayMs));
                    }
                    log.warn("抓取失败（HTTP {}，换 UA 重试）: {}", code, url);
                    continue;
                }
                return httpErrorHint(code, e.getStatusText());

            } catch (ResourceAccessException e) {
                log.warn("抓取超时/连接失败（第 {}/{} 次尝试）: {} - {}", attempt + 1, maxRetries + 1, url, e.getMessage());
                if (!lastAttempt) {
                    continue;
                }
                return "【连接失败】连接超时或网络不通。当前网络模式：" + proxyResolver.describe()
                        + "\n建议：1) 使用 check_network 工具检测目标网站可达性"
                        + " 2) 如果 check_network 也不可达，说明该网站在当前网络被屏蔽，需启用代理"
                        + " 3) 如果 check_network 可达但 web_fetch 失败，可能是超时设置太短，尝试简化页面或换更小的页面";

            } catch (Exception e) {
                log.error("读取网页失败: {}", url, e);
                return "【未知错误】抓取网页异常（" + e.getClass().getSimpleName() + "）。建议：1) 检查 URL 格式是否正确（是否遗漏 https:// 前缀）2) 尝试用 IP 地址替代域名 3) 对同一页面尝试用 http_request 工具发送 GET 请求";
            }
        }

        // 理论不可达（循环内所有分支均已 return/continue），保底返回
        return "【未知错误】多次尝试后仍无法获取网页：" + url;
    }

    // ============================================================
    // 请求构造与解析
    // ============================================================

    /** 构造浏览器风格请求头（UA 与 Sec-Fetch / client hints 配套，模拟真实导航请求） */
    private HttpHeaders buildBrowserHeaders(UserAgentProfile profile) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("User-Agent", profile.userAgent);
        headers.set("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8");
        headers.set("Accept-Language", "zh-CN,zh;q=0.9,en-US;q=0.8,en;q=0.7");
        headers.set("Sec-Fetch-Dest", "document");
        headers.set("Sec-Fetch-Mode", "navigate");
        headers.set("Sec-Fetch-Site", "none");
        headers.set("Sec-Fetch-User", "?1");
        headers.set("Upgrade-Insecure-Requests", "1");
        if (profile.secChUa != null) {
            headers.set("sec-ch-ua", profile.secChUa);
            headers.set("sec-ch-ua-mobile", "?0");
            if (profile.platform != null) {
                headers.set("sec-ch-ua-platform", "\"" + profile.platform + "\"");
            }
        }
        return headers;
    }

    /** UA 池：过滤空值；配置为空时回退到 fetch-user-agent 单值，极端情况使用内置兜底 UA */
    List<String> resolveUserAgentPool() {
        List<String> result = new ArrayList<>();
        if (config.getFetchUserAgents() != null) {
            for (String ua : config.getFetchUserAgents()) {
                if (ua != null && !ua.isBlank()) {
                    result.add(ua.trim());
                }
            }
        }
        if (result.isEmpty()) {
            String fallback = config.getFetchUserAgent();
            result.add(fallback == null || fallback.isBlank() ? FALLBACK_UA : fallback.trim());
        }
        return result;
    }

    /** 解析响应字节为 HTML 字符串：优先 HTTP charset，其次 HTML meta 声明，最后回退 UTF-8 */
    private String decodeHtml(byte[] body, MediaType contentType) {
        Charset charset = null;
        if (contentType != null && contentType.getCharset() != null) {
            charset = contentType.getCharset();
        }
        if (charset == null) {
            try {
                charset = Jsoup.parse(new String(body, StandardCharsets.ISO_8859_1)).charset();
            } catch (Exception e) {
                log.debug("从HTML meta标签检测charset失败", e);
            }
        }
        return new String(body, charset != null ? charset : StandardCharsets.UTF_8);
    }

    /** Jsoup 提取正文：剔除脚本 / 样式 / 导航等噪声后取纯文本 */
    static String extractText(String html) {
        Document doc = Jsoup.parse(html);
        doc.select("script, style, noscript, nav, footer, header, aside, iframe").remove();
        return doc.body() != null ? doc.body().wholeText() : "";
    }

    /** 排版整理：合并连续空白，去除多余空行 */
    static String tidy(String text) {
        String t = text.replaceAll("\\n{3,}", "\n\n");
        return t.replaceAll("(?m)^[ \t]+|[ \t]+$", "");
    }

    /** 统一输出格式：标注抓取来源与内容（超长截断），便于模型判断内容可靠性 */
    private String formatResult(String url, String source, String content) {
        String text = content;
        int maxLen = config.getFetchMaxContentLength();
        if (text.length() > maxLen) {
            text = text.substring(0, maxLen)
                    + "\n\n...（内容过长，仅显示前 " + maxLen + " 字符）";
        }
        return "--- 网页内容: " + url + " ---\n"
                + "[抓取方式] " + source + "\n\n"
                + text;
    }

    /** 统一 HTTP 错误提示（按状态码给出针对性建议） */
    private String httpErrorHint(int code, String statusText) {
        StringBuilder sb = new StringBuilder();
        sb.append("【HTTP 错误】服务器返回 HTTP ").append(code);
        if (statusText != null && !statusText.isBlank()) {
            sb.append(' ').append(statusText);
        }
        sb.append("。当前网络模式：").append(proxyResolver.describe());
        if (code == 403 || code == 401) {
            sb.append("。建议：1) 目标网站拒绝访问或有登录墙，换一个公开可访问的同类页面 2) 稍后重试（可能是临时风控）");
        } else if (code == 404) {
            sb.append("。建议：检查 URL 是否拼写正确或页面已不存在");
        } else if (code == 429) {
            sb.append("。建议：请求过于频繁（目标站点限流），等待几秒后重试");
        } else if (code >= 500) {
            sb.append("。建议：目标服务器暂时故障，稍后重试或换同类页面");
        } else {
            sb.append("。建议：1) 稍后重试 2) 换一个同类页面 3) 若持续失败可用 http_request 工具查看原始响应");
        }
        return sb.toString();
    }

    /** 重试等待（中断时恢复中断标记，避免吞掉中断信号） */
    private void sleepQuietly(long ms) {
        if (ms <= 0) {
            return;
        }
        try {
            Thread.sleep(Math.min(ms, MAX_RETRY_WAIT_MS));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // ============================================================
    // 纯函数工具（包级可见，便于逻辑测试）
    // ============================================================

    /**
     * 判断目标主机是否为内网 / 私有地址（此类地址不允许发送给 Jina 等第三方服务）。
     * 覆盖：localhost 及常见内网后缀、IPv4 私有段 / 回环 / 链路本地、IPv6 回环 / ULA / 链路本地。
     */
    static boolean isPrivateHost(String host) {
        if (host == null || host.isBlank()) {
            return true;
        }
        String h = host.trim().toLowerCase(Locale.ROOT);
        if (h.startsWith("[") && h.endsWith("]") && h.length() > 2) {
            h = h.substring(1, h.length() - 1);   // IPv6 字面量
        }
        if (h.equals("localhost") || h.endsWith(".localhost") || h.endsWith(".local")
                || h.endsWith(".internal") || h.endsWith(".lan") || h.endsWith(".home")) {
            return true;
        }
        if (h.contains(":")) {
            // IPv6：回环 ::1、ULA fc00::/7、链路本地 fe80::/10
            return h.equals("::1") || h.equals("0:0:0:0:0:0:0:1")
                    || h.startsWith("fc") || h.startsWith("fd") || h.startsWith("fe80");
        }
        String[] parts = h.split("\\.");
        if (parts.length == 4) {
            try {
                int a = Integer.parseInt(parts[0]);
                int b = Integer.parseInt(parts[1]);
                if (a == 127 || a == 10 || a == 0) {
                    return true;                    // 回环 / 10.x / 本网络
                }
                if (a == 172 && b >= 16 && b <= 31) {
                    return true;                    // 172.16-31.x
                }
                if (a == 192 && b == 168) {
                    return true;                    // 192.168.x
                }
                if (a == 169 && b == 254) {
                    return true;                    // 链路本地
                }
                return false;
            } catch (NumberFormatException e) {
                return false;                       // 形似 IP 实为域名（如 1.2.3.example），按公网域名处理
            }
        }
        return false;
    }

    /**
     * 反爬拦截页判定：页面命中风控关键词，且正文极短（真拦截页几乎无正文）。
     * 双重条件用于避免误伤「正文里恰好提到关键词」的正常页面。
     */
    static boolean isBlockPage(String html, String text, List<String> keywords, int minTextLength) {
        if (html == null || keywords == null || keywords.isEmpty()) {
            return false;
        }
        String lower = html.toLowerCase(Locale.ROOT);
        boolean hit = false;
        for (String keyword : keywords) {
            if (keyword == null || keyword.isBlank()) {
                continue;
            }
            if (lower.contains(keyword.toLowerCase(Locale.ROOT))) {
                hit = true;
                break;
            }
        }
        if (!hit) {
            return false;
        }
        int textLen = text == null ? 0 : text.trim().length();
        return textLen < Math.max(1, minTextLength);
    }

    /**
     * 选择 UA 下标：同一域名基准一致（避免同站 UA 频繁变化触发风控），重试时按 attempt 轮换。
     */
    static int pickProfileIndex(String host, int attempt, int poolSize) {
        if (poolSize <= 0) {
            return 0;
        }
        String key = host == null ? "" : host;
        int base = Math.floorMod(key.hashCode(), poolSize);
        int offset = Math.floorMod(Math.max(0, attempt), poolSize);
        return (base + offset) % poolSize;
    }

    /**
     * 解析 Retry-After（秒数形式）为等待毫秒数；非法值（如 HTTP-date 形式）回退默认值。
     * 上限 10s，避免被服务端异常值长时间阻塞。
     */
    static long parseRetryAfter(List<String> values, long fallbackMs) {
        if (values != null && !values.isEmpty()) {
            try {
                long seconds = Long.parseLong(values.get(0).trim());
                if (seconds > 0) {
                    return Math.min(seconds * 1000L, 10_000L);
                }
            } catch (NumberFormatException ignore) {
                // HTTP-date 形式暂不支持，回退默认等待
            }
        }
        return fallbackMs;
    }

    /** 提取 Retry-After 响应头值（可能为 null） */
    static List<String> retryAfterValues(HttpStatusCodeException e) {
        return e.getResponseHeaders() == null ? null : e.getResponseHeaders().get("Retry-After");
    }

    /** 截断并压缩空白，用于日志输出（防止超长 / 多行响应体污染日志） */
    static String brief(String body, int maxLen) {
        if (body == null) {
            return "";
        }
        String t = body.strip().replaceAll("\\s+", " ");
        return t.length() <= maxLen ? t : t.substring(0, maxLen) + "...";
    }

    /**
     * UA 档案：UA 字符串 + 对应的 client hints 头（仅 Chromium 系下发；Firefox / Safari 不发）。
     * sec-ch-ua 与 UA 的版本 / 平台保持一致，避免 UA 与客户端提示头相互矛盾被风控识别。
     */
    static final class UserAgentProfile {
        final String userAgent;
        final String secChUa;
        final String platform;

        UserAgentProfile(String userAgent, String secChUa, String platform) {
            this.userAgent = userAgent;
            this.secChUa = secChUa;
            this.platform = platform;
        }

        /** 依据 UA 字符串推导配套 client hints */
        static UserAgentProfile of(String ua) {
            String value = ua == null ? "" : ua.trim();
            String brand = null;
            String marker = null;
            if (value.contains("Edg/")) {
                brand = "Microsoft Edge";
                marker = "Edg/";
            } else if (value.contains("Chrome/")) {
                brand = "Google Chrome";
                marker = "Chrome/";
            }
            if (brand == null) {
                return new UserAgentProfile(value, null, null);   // Firefox / Safari 等不使用 client hints
            }
            String version = extractDigitsAfter(value, marker);
            if (version == null) {
                return new UserAgentProfile(value, null, null);
            }
            String platform = null;
            if (value.contains("Windows")) {
                platform = "Windows";
            } else if (value.contains("Macintosh") || value.contains("Mac OS X")) {
                platform = "macOS";
            } else if (value.contains("Android")) {
                platform = "Android";
            } else if (value.contains("Linux")) {
                platform = "Linux";
            }
            String secChUa = "\"" + brand + "\";v=\"" + version + "\", \"Chromium\";v=\""
                    + version + "\", \"Not_A Brand\";v=\"24\"";
            return new UserAgentProfile(value, secChUa, platform);
        }

        private static String extractDigitsAfter(String text, String marker) {
            int index = text.indexOf(marker);
            if (index < 0) {
                return null;
            }
            int start = index + marker.length();
            int end = start;
            while (end < text.length() && Character.isDigit(text.charAt(end))) {
                end++;
            }
            return end > start ? text.substring(start, end) : null;
        }
    }
}
