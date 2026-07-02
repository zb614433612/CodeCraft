package com.example.agentdeepseek.tool.impl;

import com.example.agentdeepseek.config.NetworkToolConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpMethod;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.URI;

/**
 * 网络连通性检测器（公共组件）
 * 使用 HTTP HEAD 请求检测目标 URL 可达性，比原始 TCP Socket 检测更准确
 * WebFetchTool 和 NetworkCheckTool 共用此组件
 */
@Slf4j
@Component
public class ConnectivityChecker {

    /** HEAD 请求的超时时间（毫秒） */
    private static final int HEAD_TIMEOUT = 5000;

    private final RestTemplate restTemplate;

    public ConnectivityChecker(NetworkToolConfig config) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(HEAD_TIMEOUT);
        factory.setReadTimeout(HEAD_TIMEOUT);

        if (config.getProxy() != null && config.getProxy().isEnabled()) {
            Proxy proxy = new Proxy(
                    Proxy.Type.HTTP,
                    new InetSocketAddress(config.getProxy().getHost(), config.getProxy().getPort())
            );
            factory.setProxy(proxy);
            log.info("连通性检测器已配置代理: {}:{}", config.getProxy().getHost(), config.getProxy().getPort());
        }

        this.restTemplate = new RestTemplate(factory);
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
            result.timeMs = HEAD_TIMEOUT;
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
