package com.example.agentdeepseek.tool.browser;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * 浏览器桥客户端：Java → Electron 主进程的 HTTP 通道。
 *
 * <p><b>为什么需要握手文件</b>：Electron 主进程原本没有任何对外端口（只有 ipcMain，
 * 是「渲染进程 → 主进程」的方向）。这里让 Electron 启动时监听一个系统分配的空闲端口，
 * 把 {@code {port, token, pid}} 写进 {@code <userData>/runtime/browser-bridge.json}；
 * Java 侧读这个文件即得连接信息。好处是双方零配置、端口不冲突、进程重启自动失效。</p>
 *
 * <p><b>为什么必须 NO_PROXY</b>：很多开发者机器配了全局 HTTP 代理（Clash / 公司代理），
 * 若 {@link HttpClient} 走系统代理，发往 {@code 127.0.0.1} 的请求会被代理接管并直接失败。
 * 这类问题排查起来极其费时，所以在这里硬性绕开代理。</p>
 */
@Slf4j
@Component
public class BrowserBridgeClient {

    /** 与 electron/browser-bridge.js 的 HANDSHAKE_FILE 保持一致 */
    private static final String HANDSHAKE_FILE = "browser-bridge.json";
    private static final String RUNTIME_DIR = "runtime";

    private final ObjectMapper objectMapper;
    private final BrowserConfig config;

    /** 已发现的连接信息；进程重启后端口会变，失败时会清空重读 */
    private volatile Endpoint endpoint;
    /** 上次尝试发现的时间戳，用于冷却（避免每次工具调用都去读不存在的文件） */
    private volatile long lastDiscoverAt = 0L;
    /** 最近一次失败原因，供错误信息展示 */
    private volatile String lastFailure = "";

    private volatile HttpClient httpClient;

    public BrowserBridgeClient(ObjectMapper objectMapper, BrowserConfig config) {
        this.objectMapper = objectMapper;
        this.config = config;
    }

    /** 握手文件里的连接信息 */
    private record Endpoint(int port, String token, long pid, String appVersion) {
    }

    /**
     * 一次 bridge 调用的结果。
     *
     * @param status HTTP 状态码（0 = 根本没连上）
     * @param ok     业务成功（2xx 且 body.ok=true）
     * @param body   原始响应
     * @param error  失败原因（成功时为 null）
     */
    public record BridgeResponse(int status, boolean ok, JsonNode body, String error) {

        static BridgeResponse of(int status, JsonNode body) {
            boolean ok = status >= 200 && status < 300
                    && body != null && body.path("ok").asBoolean(false);
            String error = null;
            if (!ok) {
                if (body != null && body.hasNonNull("error")) {
                    error = body.path("error").asText();
                } else if (status >= 400) {
                    error = "浏览器桥返回 HTTP " + status;
                } else {
                    error = "浏览器桥返回了未预期的结果";
                }
            }
            return new BridgeResponse(status, ok, body, error);
        }

        static BridgeResponse unavailable(String reason) {
            return new BridgeResponse(0, false, null, reason);
        }

        /** 响应体里的字段，缺失时返回默认值 */
        public String text(String field, String fallback) {
            if (body == null) return fallback;
            JsonNode node = body.get(field);
            return node == null || node.isNull() ? fallback : node.asText(fallback);
        }

        public boolean flag(String field, boolean fallback) {
            if (body == null) return fallback;
            JsonNode node = body.get(field);
            return node == null || node.isNull() ? fallback : node.asBoolean(fallback);
        }

        public JsonNode node(String field) {
            return body == null ? null : body.get(field);
        }
    }

    // ── 连接发现 ──────────────────────────────────────────────────

    /**
     * 握手文件的候选路径，按优先级排列。
     *
     * <p>打包运行：Electron 用 {@code app.getPath('userData')}，并通过 {@code -Dapp.userdata} 传给 Java。
     * <br>开发运行：Electron 写 {@code <项目根>/runtime/}，Java 在 IDE 里跑时 {@code user.dir} 就是项目根。</p>
     */
    private List<Path> handshakeCandidates() {
        List<Path> candidates = new ArrayList<>(3);
        String appUserData = System.getProperty("app.userdata");
        if (appUserData != null && !appUserData.isBlank()) {
            candidates.add(Paths.get(appUserData, RUNTIME_DIR, HANDSHAKE_FILE));
            // dev 场景补一条：Electron 侧把握手文件写在「项目根/runtime」（见 main.js 的 bridgeDataDir），
            // 而 dev 下后端 cwd 与 -Dapp.userdata 都是 <项目根>/target（resolveBackendWorkDir 直接返回 jarDir）。
            // 少了这一条，开发模式会出现「bridge 明明起来了、Java 却一直说没检测到客户端」的假故障。
            Path parent = Paths.get(appUserData).getParent();
            if (parent != null) {
                Path devCandidate = parent.resolve(RUNTIME_DIR).resolve(HANDSHAKE_FILE);
                if (!candidates.contains(devCandidate)) {
                    candidates.add(devCandidate);
                }
            }
        }
        String userDir = System.getProperty("user.dir");
        if (userDir != null && !userDir.isBlank()) {
            Path userDirCandidate = Paths.get(userDir, RUNTIME_DIR, HANDSHAKE_FILE);
            if (!candidates.contains(userDirCandidate)) {
                candidates.add(userDirCandidate);
            }
        }
        return candidates;
    }

    private Endpoint discover() {
        for (Path path : handshakeCandidates()) {
            try {
                if (!Files.isRegularFile(path)) continue;
                JsonNode node = objectMapper.readTree(Files.readString(path, StandardCharsets.UTF_8));
                int port = node.path("port").asInt(0);
                String token = node.path("token").asText("");
                if (port <= 0 || port > 65535 || token.isBlank()) {
                    log.debug("浏览器桥握手文件内容不完整：{}", path);
                    continue;
                }
                log.info("已发现浏览器桥：127.0.0.1:{}（客户端 {}）", port,
                        node.path("appVersion").asText("?"));
                return new Endpoint(port, token,
                        node.path("pid").asLong(0), node.path("appVersion").asText(""));
            } catch (Exception e) {
                log.debug("读取浏览器桥握手文件失败 {}: {}", path, e.getMessage());
            }
        }
        return null;
    }

    private Endpoint endpoint() {
        Endpoint current = endpoint;
        if (current != null) return current;

        long now = System.currentTimeMillis();
        long cooldown = Math.max(1, config.getDiscoverRetrySeconds()) * 1000L;
        if (now - lastDiscoverAt < cooldown) {
            return null;
        }
        lastDiscoverAt = now;
        Endpoint found = discover();
        endpoint = found;
        return found;
    }

    /** 连接失效（端口不通 / token 不匹配 / 客户端重启）时清空缓存，下次调用重新发现 */
    public void invalidate() {
        endpoint = null;
        lastDiscoverAt = 0L;
    }

    /** 给模型看的「为什么用不了」 */
    private String missingHint() {
        StringBuilder sb = new StringBuilder();
        sb.append("未检测到 CodeCraft 桌面客户端提供的浏览器调试通道。\n");
        sb.append("· 浏览器工具依赖 Electron 主进程的 CDP 转发，只能在**桌面客户端**内使用\n");
        sb.append("  （用浏览器直接打开 8084 端口时不可用，属预期）\n");
        sb.append("· 若你确实在桌面客户端内：请重启客户端让浏览器桥初始化，再重试\n");
        if (!lastFailure.isBlank()) {
            sb.append("· 上次失败：").append(lastFailure).append('\n');
        }
        sb.append("· 已查找位置：");
        List<Path> candidates = handshakeCandidates();
        for (int i = 0; i < candidates.size(); i++) {
            if (i > 0) sb.append("；");
            sb.append(candidates.get(i));
        }
        return sb.toString();
    }

    private HttpClient httpClient() {
        HttpClient client = httpClient;
        if (client != null) return client;
        synchronized (this) {
            if (httpClient == null) {
                httpClient = HttpClient.newBuilder()
                        .connectTimeout(Duration.ofSeconds(3))
                        // 硬性绕开代理：全局代理会吞掉 127.0.0.1 的请求
                        .proxy(HttpClient.Builder.NO_PROXY)
                        // 不跟随重定向：回环服务不会有重定向，跟了反而掩盖问题
                        .followRedirects(HttpClient.Redirect.NEVER)
                        .build();
            }
            return httpClient;
        }
    }

    // ── 调用 ─────────────────────────────────────────────────────

    /** 是否处于可用状态（配置开启 + 已发现握手文件） */
    public boolean isAvailable() {
        return config.isEnabled() && endpoint() != null;
    }

    /**
     * 调用 bridge 端点。
     *
     * @param path   形如 {@code /open}、{@code /cdp}、{@code /close}
     * @param payload 请求体（会被序列化成 JSON）
     */
    public BridgeResponse call(String path, ObjectNode payload) {
        if (!config.isEnabled()) {
            return BridgeResponse.unavailable(
                    "浏览器工具已被配置禁用（codecraft.browser.enabled=false）。"
                            + "如需使用，请在 application.yml 中打开该开关后重启后端。");
        }

        Endpoint ep = endpoint();
        if (ep == null) {
            return BridgeResponse.unavailable(missingHint());
        }

        try {
            String body = objectMapper.writeValueAsString(payload);
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("http://127.0.0.1:" + ep.port() + path))
                    .timeout(Duration.ofSeconds(Math.max(5, config.getBridgeTimeoutSeconds())))
                    .header("Content-Type", "application/json; charset=utf-8")
                    .header("x-cc-token", ep.token())
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                    .build();

            HttpResponse<String> response = httpClient()
                    .send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

            JsonNode node = parseOrNull(response.body());

            if (response.statusCode() == 401) {
                // 典型场景：客户端重启换了 token，而我们手里还是旧握手文件内容
                invalidate();
                return BridgeResponse.unavailable(
                        "浏览器桥鉴权失败（token 已失效），已刷新连接信息，请再试一次。");
            }
            if (response.statusCode() == 410) {
                // 窗口被用户手动关掉 / 空闲回收：不静默重建，让调用方决定要不要重新 open
                invalidateQuietly();
                return BridgeResponse.of(410, node);
            }

            lastFailure = "";
            return BridgeResponse.of(response.statusCode(), node);

        } catch (java.net.http.HttpConnectTimeoutException e) {
            invalidate();
            lastFailure = "连接超时：" + e.getMessage();
            return BridgeResponse.unavailable(
                    "连接浏览器桥超时（127.0.0.1:" + ep.port() + "）。客户端可能正在重启，请稍后重试。");
        } catch (java.io.IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            // ConnectException / 端口被回收：多半是客户端退出或重启了
            invalidate();
            lastFailure = e.getClass().getSimpleName() + ": " + e.getMessage();
            return BridgeResponse.unavailable(
                    "无法连接浏览器桥（127.0.0.1:" + ep.port() + "）：" + e.getMessage()
                            + "\n若 CodeCraft 客户端刚重启过，重试一次即可（会自动重新发现端口）。");
        } catch (Exception e) {
            lastFailure = e.getClass().getSimpleName() + ": " + e.getMessage();
            return BridgeResponse.unavailable("调用浏览器桥失败：" + e.getMessage());
        }
    }

    private void invalidateQuietly() {
        // 410 不代表连接坏了（只是窗口没了），保留 endpoint 免得下次白读一遍文件
    }

    private JsonNode parseOrNull(String body) {
        if (body == null || body.isBlank()) return null;
        try {
            return objectMapper.readTree(body);
        } catch (Exception e) {
            log.debug("浏览器桥响应不是合法 JSON：{}", body.length() > 200 ? body.substring(0, 200) : body);
            return null;
        }
    }

    /** 健康检查摘要（供 action=status 展示） */
    public String healthSummary() {
        if (!config.isEnabled()) {
            return "浏览器工具：已被配置禁用（codecraft.browser.enabled=false）";
        }
        Endpoint ep = endpoint();
        if (ep == null) {
            return "浏览器工具：不可用\n" + missingHint();
        }
        BridgeResponse response = call("/health", objectMapper.createObjectNode());
        if (!response.ok()) {
            return "浏览器工具：握手成功但健康检查失败（127.0.0.1:" + ep.port() + "）\n"
                    + response.error();
        }
        JsonNode body = response.body();
        return "浏览器工具：可用"
                + "\n· 客户端版本：" + body.path("appVersion").asText("?")
                + "\n· 协议版本：" + body.path("protocol").asInt(0)
                + "\n· 当前窗口数：" + body.path("windows").asInt(0)
                + "/" + body.path("maxWindows").asInt(0)
                + "\n· 端口：" + ep.port()
                + "\n· 域名策略：" + hostPolicyText();
    }

    private String hostPolicyText() {
        if (!config.getBlockedHosts().isEmpty()) {
            return "黑名单 " + config.getBlockedHosts() + "（其余放行）";
        }
        if (!config.getAllowedHosts().isEmpty()) {
            return "白名单 " + config.getAllowedHosts();
        }
        return "不限制（全部放行）";
    }
}
