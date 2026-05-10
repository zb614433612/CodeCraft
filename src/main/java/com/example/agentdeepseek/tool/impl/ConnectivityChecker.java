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
     * 检测目标 URL 的可达性（HTTP HEAD 请求）
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

            restTemplate.exchange(uri, HttpMethod.HEAD, null, Void.class);

            long elapsed = System.currentTimeMillis() - start;
            result.timeMs = elapsed;
            result.reachable = true;
            log.debug("连通性检测通过: {} ({}ms)", url, elapsed);
        } catch (Exception e) {
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
