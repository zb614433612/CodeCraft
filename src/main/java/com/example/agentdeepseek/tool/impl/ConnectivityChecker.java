package com.example.agentdeepseek.tool.impl;

import com.example.agentdeepseek.config.NetworkToolConfig;
import com.example.agentdeepseek.config.ProxyResolver;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpMethod;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.net.Proxy;
import java.net.URI;

/**
 * 网络连通性检测器（公共组件）
 * 使用 HTTP HEAD 请求检测目标 URL 可达性，比原始 TCP Socket 检测更准确
 * WebFetchTool 和 NetworkCheckTool 共用此组件
 *
 * <p>代理不再是启动时固化的开关，而是每次检测前按 {@link ProxyResolver} 的当前解析结果决定：
 * 探测到可用本地代理就走代理，探测不到就直连（国内网络兜底），运行中启停代理无需重启。</p>
 */
@Slf4j
@Component
public class ConnectivityChecker {

    /** 默认检测超时（毫秒），可被 network-tool.connectivity-check-timeout 覆盖 */
    private static final int DEFAULT_TIMEOUT = 5000;

    private final ProxyResolver proxyResolver;

    /** 检测超时（毫秒） */
    private final int timeoutMs;

    /** 与当前代理状态绑定的 RestTemplate；代理启停时自动重建 */
    private volatile RestTemplate restTemplate;
    private volatile String templateProxyKey;

    public ConnectivityChecker(NetworkToolConfig config, ProxyResolver proxyResolver) {
        this.proxyResolver = proxyResolver;
        this.timeoutMs = config.getConnectivityCheckTimeout() > 0
                ? config.getConnectivityCheckTimeout()
                : DEFAULT_TIMEOUT;
        this.restTemplate = buildRestTemplate();
        this.templateProxyKey = proxyResolver.signature();
    }

    /** 取与当前网络模式匹配的 RestTemplate（代理开关变化时自动重建，无需重启应用） */
    private RestTemplate template() {
        String key = proxyResolver.signature();
        RestTemplate current = restTemplate;
        if (current != null && key.equals(templateProxyKey)) {
            return current;
        }
        synchronized (this) {
            if (restTemplate == null || !key.equals(templateProxyKey)) {
                restTemplate = buildRestTemplate();
                templateProxyKey = key;
                log.info("连通性检测器网络模式变更: {}", proxyResolver.describe());
            }
            return restTemplate;
        }
    }

    private RestTemplate buildRestTemplate() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(timeoutMs);
        factory.setReadTimeout(timeoutMs);
        Proxy proxy = proxyResolver.resolve();
        if (proxy != Proxy.NO_PROXY) {
            factory.setProxy(proxy);
        }
        return new RestTemplate(factory);
    }

    /**
     * 检测目标 URL 的可达性
     * 优化策略：先尝试 HEAD 请求（高效），如果失败（如 405）则回退到 GET 请求
     * 某些 HTTP 状态码（如 403、401）表示服务器可达但拒绝访问，仍标记为可达
     *
     * @param url 目标 URL
     * @return 检测结果，可达时 error 为 null
     */
    public CheckResult check(String url) {
        RestTemplate restTemplate = template();
        CheckResult result = new CheckResult();
        result.url = url;

        try {
            URI uri = new URI(url);
            long start = System.currentTimeMillis();

            // 第一步：尝试 HEAD 请求
            try {
                restTemplate.exchange(uri, HttpMethod.HEAD, null, Void.class);
                long elapsed = System.currentTimeMillis() - start;
                result.timeMs = elapsed;
                result.reachable = true;
                log.debug("连通性检测通过（HEAD）: {} ({}ms)", url, elapsed);
                return result;
            } catch (org.springframework.web.client.HttpStatusCodeException headEx) {
                int statusCode = headEx.getStatusCode().value();
                // 405 Method Not Allowed：服务器不支持 HEAD，但可达
                // 403 Forbidden：服务器拒绝访问，但可达
                // 401 Unauthorized：需要认证，但可达
                // 这些情况都说明服务器是可达的
                if (statusCode == 405 || statusCode == 403 || statusCode == 401) {
                    long elapsed = System.currentTimeMillis() - start;
                    result.timeMs = elapsed;
                    result.reachable = true;
                    log.debug("连通性检测通过（HEAD 返回 {}，回退判断为可达）: {} ({}ms)", statusCode, url, elapsed);
                    return result;
                }
                // 其他 HTTP 错误（如 5xx）继续尝试 GET
                log.debug("HEAD 请求返回 {}，尝试 GET: {}", statusCode, url);
            } catch (Exception headException) {
                // HEAD 请求失败（如超时），继续尝试 GET
                log.debug("HEAD 请求失败，尝试 GET: {} ({})", url, headException.getMessage());
            }

            // 第二步：HEAD 失败时，回退到 GET 请求（读取少量数据）
            try {
                restTemplate.exchange(uri, HttpMethod.GET, null, String.class);
                long elapsed = System.currentTimeMillis() - start;
                result.timeMs = elapsed;
                result.reachable = true;
                log.debug("连通性检测通过（GET）: {} ({}ms)", url, elapsed);
                return result;
            } catch (org.springframework.web.client.HttpStatusCodeException getEx) {
                // GET 请求返回任何 HTTP 状态码都说明服务器可达
                int statusCode = getEx.getStatusCode().value();
                long elapsed = System.currentTimeMillis() - start;
                result.timeMs = elapsed;
                result.reachable = true;
                log.debug("连通性检测通过（GET 返回 {}）: {} ({}ms)", statusCode, url, elapsed);
                return result;
            }

        } catch (Exception e) {
            // 所有请求都失败，判定为不可达
            result.timeMs = timeoutMs;
            result.reachable = false;
            result.error = e.getMessage();
            log.debug("连通性检测失败: {} ({})", url, e.getMessage());
        }

        return result;
    }

    /**
     * 检测结果
     */
    public static class CheckResult {
        public String url;
        public boolean reachable;
        public long timeMs;
        public String error;

        public String statusEmoji() {
            return reachable ? "✅ 可达" : "❌ 不可达";
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder();
            sb.append(statusEmoji()).append(" ").append(url);
            sb.append(" (").append(timeMs).append("ms)");
            if (error != null && !reachable) {
                // 只取错误的第一行，避免噪声
                String shortError = error.split("\\n")[0];
                sb.append(" - ").append(shortError);
            }
            return sb.toString();
        }
    }
}
