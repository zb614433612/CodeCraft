package com.example.agentdeepseek.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.Socket;
import java.net.URL;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 本地代理自动解析器（所有网络工具的统一代理入口）。
 *
 * <p>核心策略：<b>能走代理就走代理，走不了就直连（国内网络兜底）</b>，全程无需人工开关。</p>
 *
 * <p>三种模式（{@code network-tool.proxy.mode}）：</p>
 * <ul>
 *   <li>{@code AUTO}（默认）：探测本机候选代理端口 → 命中后再验证能否出网 → 可用则走代理，否则直连；</li>
 *   <li>{@code ALWAYS}：不探测，强制使用配置的 host:port（等价于旧的 {@code enabled: true}）；</li>
 *   <li>{@code NEVER}：始终直连。</li>
 * </ul>
 *
 * <p>探测结果按 {@code detect-cache-seconds}（默认 60s）缓存，避免每次工具调用都重新探测；
 * 缓存过期后自动复检，因此运行中开启/关闭 Clash 无需重启应用。</p>
 *
 * <p>「出网验证」用于识别<b>端口在监听但代理实际不可用</b>（节点全挂、订阅过期、规则异常）的情形，
 * 避免把请求发给一个死代理导致全部超时。</p>
 */
@Slf4j
@Component
public class ProxyResolver {

    private static final String MODE_AUTO = "AUTO";
    private static final String MODE_ALWAYS = "ALWAYS";
    private static final String MODE_NEVER = "NEVER";

    private static final String DEFAULT_HOST = "127.0.0.1";
    private static final int DEFAULT_PORT = 7890;

    private final NetworkToolConfig config;

    /** 缓存的探测结果 */
    private volatile Resolution cached;
    private volatile long cachedAtMs;

    public ProxyResolver(NetworkToolConfig config) {
        this.config = config;
    }

    // ============================================================
    // 对外 API
    // ============================================================

    /** 当前应使用的代理；返回 {@link Proxy#NO_PROXY} 表示直连。 */
    public Proxy resolve() {
        return resolution().getProxy();
    }

    /** 当前是否正在使用代理（false = 走直连兜底）。 */
    public boolean isProxyActive() {
        return !resolution().isDirect();
    }

    /**
     * 当前代理状态的稳定签名，用于判断依赖代理的客户端（如 RestTemplate）是否需要重建。
     * 形如 {@code direct} 或 {@code http://127.0.0.1:7890}。
     */
    public String signature() {
        Resolution r = resolution();
        return r.isDirect() ? "direct" : "http://" + r.getHost() + ":" + r.getPort();
    }

    /** 人类可读的当前网络模式描述，用于日志与工具输出。 */
    public String describe() {
        Resolution r = resolution();
        return r.isDirect()
                ? "直连（国内网络兜底" + (r.getReason() == null ? "" : "：" + r.getReason()) + "）"
                : "代理 " + r.getHost() + ":" + r.getPort();
    }

    /** 强制使缓存失效（配置热更新或诊断场景使用）。 */
    public void invalidate() {
        cached = null;
        cachedAtMs = 0L;
    }

    // ============================================================
    // 探测与缓存
    // ============================================================

    private Resolution resolution() {
        Resolution current = cached;
        if (current != null && !isExpired()) {
            return current;
        }
        synchronized (this) {
            if (cached != null && !isExpired()) {
                return cached;
            }
            Resolution fresh = detect();
            cached = fresh;
            cachedAtMs = System.currentTimeMillis();
            return fresh;
        }
    }

    /**
     * 非对称缓存：
     * 直连结果只缓存很短时间（便于用户中途启动代理后快速自适应），
     * 代理结果缓存较久（代理状态相对稳定，减少探测开销）。
     */
    private boolean isExpired() {
        Resolution current = cached;
        if (current == null) {
            return true;
        }
        NetworkToolConfig.ProxyConfig p = proxyConfig();
        if (p == null) {
            return System.currentTimeMillis() - cachedAtMs >= 60_000L;
        }
        int cacheSeconds = current.isDirect() ? p.getDirectCacheSeconds() : p.getDetectCacheSeconds();
        if (cacheSeconds <= 0) {
            return true;
        }
        return System.currentTimeMillis() - cachedAtMs >= cacheSeconds * 1000L;
    }

    private Resolution detect() {
        NetworkToolConfig.ProxyConfig p = proxyConfig();
        if (p == null) {
            return Resolution.direct("未配置代理");
        }
        String mode = normalizeMode(p);
        if (MODE_NEVER.equals(mode)) {
            return Resolution.direct("代理已关闭（mode=NEVER）");
        }
        if (MODE_ALWAYS.equals(mode)) {
            String host = safeHost(p.getHost());
            int port = p.getPort() > 0 ? p.getPort() : DEFAULT_PORT;
            log.info("代理模式 ALWAYS：强制使用 {}:{}", host, port);
            return Resolution.viaProxy(host, port, "mode=ALWAYS 强制使用");
        }
        return autoDetect(p);
    }

    /** AUTO：先探测端口，再验证出网，全部失败则直连兜底 */
    private Resolution autoDetect(NetworkToolConfig.ProxyConfig p) {
        String host = safeHost(p.getHost());
        List<Integer> ports = candidatePorts(p);
        int detectTimeout = Math.max(50, p.getDetectTimeoutMs());

        List<Integer> openPorts = new ArrayList<>();
        for (int port : ports) {
            if (isPortOpen(host, port, detectTimeout)) {
                openPorts.add(port);
            }
        }

        if (openPorts.isEmpty()) {
            log.info("代理自动探测：{} 上未发现监听中的代理端口 {}，本次直连（国内网络兜底）", host, ports);
            return Resolution.direct("未探测到本地代理端口");
        }

        String checkUrl = p.getEgressCheckUrl();
        if (!p.isVerifyEgress() || checkUrl == null || checkUrl.isBlank()) {
            int port = openPorts.get(0);
            log.info("代理自动探测：命中端口 {}:{}（未启用出网验证），使用代理", host, port);
            return Resolution.viaProxy(host, port, "端口探测命中");
        }

        int egressTimeout = Math.max(detectTimeout, p.getEgressTimeoutMs());
        for (int port : openPorts) {
            if (verifyEgress(host, port, checkUrl, egressTimeout)) {
                log.info("代理自动探测：命中可用代理 {}:{}（出网验证通过），使用代理", host, port);
                return Resolution.viaProxy(host, port, "端口探测 + 出网验证通过");
            }
            log.warn("代理自动探测：端口 {}:{} 可连接但出网验证失败，尝试下一个候选", host, port);
        }

        log.warn("代理自动探测：端口 {} 均在监听但无一可用（出网不通），本次直连（国内网络兜底）", openPorts);
        return Resolution.direct("代理端口在监听但出网不通");
    }

    private String normalizeMode(NetworkToolConfig.ProxyConfig p) {
        String mode = p.getMode();
        if (mode != null && !mode.isBlank()) {
            String upper = mode.trim().toUpperCase();
            if (MODE_AUTO.equals(upper) || MODE_ALWAYS.equals(upper) || MODE_NEVER.equals(upper)) {
                return upper;
            }
            log.warn("无法识别的代理模式 '{}'，回退为 AUTO（自动探测）", mode);
        }
        // 兼容旧配置：enabled=true 等价于 ALWAYS；未配置时为 AUTO，保证「默认自动探测」
        return p.isEnabled() ? MODE_ALWAYS : MODE_AUTO;
    }

    /** 候选端口：配置端口优先，其后是候选列表（去重保序） */
    private List<Integer> candidatePorts(NetworkToolConfig.ProxyConfig p) {
        Set<Integer> ports = new LinkedHashSet<>();
        if (p.getPort() > 0 && p.getPort() <= 65535) {
            ports.add(p.getPort());
        }
        if (p.getCandidatePorts() != null) {
            for (Integer port : p.getCandidatePorts()) {
                if (port != null && port > 0 && port <= 65535) {
                    ports.add(port);
                }
            }
        }
        if (ports.isEmpty()) {
            ports.add(DEFAULT_PORT);
        }
        return new ArrayList<>(ports);
    }

    /** TCP 连通性探测（本地回环，耗时通常在毫秒级） */
    private boolean isPortOpen(String host, int port, int timeoutMs) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), timeoutMs);
            return true;
        } catch (Exception e) {
            log.debug("代理端口探测失败 {}:{} - {}", host, port, e.getMessage());
            return false;
        }
    }

    /** 通过指定代理访问验证地址，确认代理真的能出网 */
    private boolean verifyEgress(String host, int port, String url, int timeoutMs) {
        HttpURLConnection connection = null;
        try {
            Proxy proxy = new Proxy(Proxy.Type.HTTP, new InetSocketAddress(host, port));
            connection = (HttpURLConnection) new URL(url).openConnection(proxy);
            connection.setConnectTimeout(timeoutMs);
            connection.setReadTimeout(timeoutMs);
            connection.setRequestMethod("GET");
            connection.setInstanceFollowRedirects(false);
            connection.setRequestProperty("User-Agent", config.getFetchUserAgent());
            connection.setRequestProperty("Accept", "*/*");
            int code = connection.getResponseCode();
            return code > 0 && code < 500;
        } catch (Exception e) {
            log.debug("代理出网验证失败 {}:{} → {}：{}", host, port, url, e.getMessage());
            return false;
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private NetworkToolConfig.ProxyConfig proxyConfig() {
        return config.getProxy();
    }

    private String safeHost(String host) {
        return (host == null || host.isBlank()) ? DEFAULT_HOST : host.trim();
    }

    /**
     * 单次解析结果。
     */
    public static final class Resolution {
        private final Proxy proxy;
        private final String host;
        private final int port;
        private final String reason;

        private Resolution(Proxy proxy, String host, int port, String reason) {
            this.proxy = proxy;
            this.host = host;
            this.port = port;
            this.reason = reason;
        }

        static Resolution direct(String reason) {
            return new Resolution(Proxy.NO_PROXY, null, -1, reason);
        }

        static Resolution viaProxy(String host, int port, String reason) {
            Proxy proxy = new Proxy(Proxy.Type.HTTP, new InetSocketAddress(host, port));
            return new Resolution(proxy, host, port, reason);
        }

        public Proxy getProxy() {
            return proxy;
        }

        public String getHost() {
            return host;
        }

        public int getPort() {
            return port;
        }

        public String getReason() {
            return reason;
        }

        public boolean isDirect() {
            return host == null || host.isBlank() || port <= 0;
        }

        @Override
        public String toString() {
            return isDirect() ? "direct(" + reason + ")" : "proxy " + host + ":" + port;
        }
    }
}
