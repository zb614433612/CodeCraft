package com.example.agentdeepseek;

import com.example.agentdeepseek.config.NetworkToolConfig;
import com.example.agentdeepseek.config.ProxyResolver;

import java.io.IOException;
import java.lang.reflect.Method;
import java.net.InetAddress;
import java.net.Proxy;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;

/**
 * ProxyResolver 代理自动探测回归测试（纯逻辑 + 本地端口，不访问外网）。
 *
 * <p>覆盖核心承诺：「能走代理就走代理，走不了就直连（国内网络兜底）」。</p>
 *
 * <p>用本地 {@link ServerSocket} 模拟代理端口，因此运行环境无关，可在 CI 中稳定执行。
 * 运行方式（main 方法，非 JUnit）：
 * <pre>
 * mvn -q -DskipTests -DskipFrontend=true test-compile
 * mvn -q org.codehaus.mojo:exec-maven-plugin:3.1.0:java \
 *     -Dexec.mainClass=com.example.agentdeepseek.ProxyResolverTest \
 *     -Dexec.classpathScope=test -DskipFrontend=true
 * </pre>
 */
public class ProxyResolverTest {

    static int passed = 0;
    static int failed = 0;

    /** 假代理持有的连接，避免 GC 提前关闭 */
    static final List<Socket> heldSockets = new ArrayList<>();

    interface Assertion {
        boolean check() throws Exception;
    }

    public static void main(String[] args) throws Exception {
        System.out.println("=== ProxyResolver 代理自动探测回归测试 ===\n");

        // 1) 显式模式
        test("NEVER: 始终直连，不探测", () -> {
            ProxyResolver r = new ProxyResolver(cfg("NEVER"));
            return !r.isProxyActive() && r.resolve() == Proxy.NO_PROXY && "direct".equals(r.signature());
        });

        test("ALWAYS: 强制使用配置的 host:port（不探测）", () -> {
            ProxyResolver r = new ProxyResolver(cfg("ALWAYS"));
            return r.isProxyActive() && "http://127.0.0.1:7890".equals(r.signature());
        });

        // 2) 旧配置兼容
        test("兼容: mode 未配置 + enabled=true → 等价 ALWAYS", () -> {
            NetworkToolConfig c = new NetworkToolConfig();
            c.getProxy().setEnabled(true);
            ProxyResolver r = new ProxyResolver(c);
            return r.isProxyActive() && "http://127.0.0.1:7890".equals(r.signature());
        });

        test("兼容: mode 未配置 + enabled=false → AUTO（探测不到则直连）", () -> {
            NetworkToolConfig c = new NetworkToolConfig();
            c.getProxy().setEnabled(false);
            c.getProxy().setCandidatePorts(new ArrayList<>(List.of(freePort())));
            c.getProxy().setVerifyEgress(false);
            ProxyResolver r = new ProxyResolver(c);
            return !r.isProxyActive();
        });

        // 3) AUTO 探测：未命中
        test("AUTO: 无端口监听 → 直连兜底", () -> {
            NetworkToolConfig c = cfg("AUTO");
            c.getProxy().setCandidatePorts(new ArrayList<>(List.of(freePort(), freePort())));
            c.getProxy().setDetectTimeoutMs(200);
            ProxyResolver r = new ProxyResolver(c);
            return !r.isProxyActive() && r.describe().contains("直连");
        });

        // 4) AUTO 探测：命中端口 → 走代理
        test("AUTO: 探测到本地代理端口 → 走代理", () -> {
            ServerSocket server = dummyProxyServer();
            try {
                int port = server.getLocalPort();
                NetworkToolConfig c = cfg("AUTO");
                c.getProxy().setPort(port);
                c.getProxy().setCandidatePorts(new ArrayList<>(List.of(port)));
                c.getProxy().setVerifyEgress(false);
                ProxyResolver r = new ProxyResolver(c);
                return r.isProxyActive() && r.signature().endsWith(":" + port);
            } finally {
                close(server);
            }
        });

        // 5) 核心兜底：端口在监听但代理实际不可用（出网验证失败）→ 直连
        test("AUTO: 端口在监听但出网验证失败（死代理）→ 直连兜底", () -> {
            ServerSocket server = dummyProxyServer();
            try {
                int port = server.getLocalPort();
                NetworkToolConfig c = cfg("AUTO");
                c.getProxy().setPort(port);
                c.getProxy().setCandidatePorts(new ArrayList<>(List.of(port)));
                c.getProxy().setVerifyEgress(true);
                c.getProxy().setEgressCheckUrl("http://127.0.0.1:" + port + "/ping");
                c.getProxy().setEgressTimeoutMs(400);
                ProxyResolver r = new ProxyResolver(c);
                return !r.isProxyActive() && r.describe().contains("直连");
            } finally {
                close(server);
            }
        });

        // 6) 探测结果缓存
        test("缓存: TTL 内复用探测结果，invalidate 后重新探测", () -> {
            ServerSocket server = dummyProxyServer();
            int port = server.getLocalPort();
            NetworkToolConfig c = cfg("AUTO");
            c.getProxy().setPort(port);
            c.getProxy().setCandidatePorts(new ArrayList<>(List.of(port)));
            c.getProxy().setVerifyEgress(false);
            c.getProxy().setDetectCacheSeconds(60);
            ProxyResolver r = new ProxyResolver(c);

            boolean first = r.isProxyActive();
            close(server);                              // 代理"下线"
            boolean cached = r.isProxyActive();         // 仍命中缓存
            r.invalidate();                             // 强制复检
            boolean refreshed = r.isProxyActive();      // 探测不到 → 直连
            return first && cached && !refreshed;
        });

        test("缓存: 直连结果短缓存，用户中途启动代理后可快速自适应", () -> {
            int port = freePort();                      // 此刻该端口无监听
            NetworkToolConfig c = cfg("AUTO");
            c.getProxy().setPort(port);
            c.getProxy().setCandidatePorts(new ArrayList<>(List.of(port)));
            c.getProxy().setVerifyEgress(false);
            c.getProxy().setDirectCacheSeconds(1);      // 直连结果只缓存 1s
            c.getProxy().setDetectCacheSeconds(300);
            ProxyResolver r = new ProxyResolver(c);
            boolean before = !r.isProxyActive();        // 直连兜底

            ServerSocket server = dummyProxyServerAt(port);   // 用户"启动了代理"
            try {
                Thread.sleep(1200);                     // 等直连缓存过期
                boolean after = r.isProxyActive();      // 复检 → 自动切到代理
                return before && after;
            } finally {
                close(server);
            }
        });

        // 7) 容错
        test("容错: 无法识别的 mode 回退 AUTO，且不抛异常", () -> {
            NetworkToolConfig c = cfg("banana");
            c.getProxy().setCandidatePorts(new ArrayList<>(List.of(freePort())));
            c.getProxy().setVerifyEgress(false);
            ProxyResolver r = new ProxyResolver(c);
            return !r.isProxyActive();
        });

        // 8) 候选端口：配置端口优先 + 去重保序
        test("候选端口: 配置端口优先且去重保序", () -> {
            NetworkToolConfig c = new NetworkToolConfig();
            c.getProxy().setPort(7897);
            c.getProxy().setCandidatePorts(new ArrayList<>(List.of(7890, 7897, 7890)));
            ProxyResolver r = new ProxyResolver(c);
            Method m = ProxyResolver.class.getDeclaredMethod("candidatePorts", NetworkToolConfig.ProxyConfig.class);
            m.setAccessible(true);
            @SuppressWarnings("unchecked")
            List<Integer> ports = (List<Integer>) m.invoke(r, c.getProxy());
            return ports.equals(List.of(7897, 7890));
        });

        // 9) 描述文案
        test("describe: 直连/代理文案可读", () -> {
            boolean direct = new ProxyResolver(cfg("NEVER")).describe().contains("直连");
            boolean viaProxy = new ProxyResolver(cfg("ALWAYS")).describe().contains("127.0.0.1:7890");
            return direct && viaProxy;
        });

        System.out.println("\n=== 结果: " + passed + " 通过 / " + failed + " 失败 ===");
        if (failed > 0) {
            System.exit(1);
        }
    }

    // ==================== 辅助 ====================

    static NetworkToolConfig cfg(String mode) {
        NetworkToolConfig c = new NetworkToolConfig();
        c.getProxy().setMode(mode);
        return c;
    }

    /** 起一个「只 accept、不应答」的假代理服务器，用于模拟端口在监听但代理不可用 */
    static ServerSocket dummyProxyServer() throws IOException {
        return dummyProxyServerAt(0);
    }

    /** 在指定端口起假代理（port=0 表示随机空闲端口） */
    static ServerSocket dummyProxyServerAt(int port) throws IOException {
        ServerSocket server = new ServerSocket(port, 50, InetAddress.getByName("127.0.0.1"));
        Thread t = new Thread(() -> {
            while (!server.isClosed()) {
                try {
                    heldSockets.add(server.accept());
                } catch (Exception e) {
                    return;
                }
            }
        }, "dummy-proxy");
        t.setDaemon(true);
        t.start();
        return server;
    }

    /** 取一个当前空闲的端口（随即释放，用于模拟"没有代理"） */
    static int freePort() throws IOException {
        try (ServerSocket s = new ServerSocket(0)) {
            return s.getLocalPort();
        }
    }

    static void close(ServerSocket server) {
        try {
            server.close();
        } catch (IOException ignore) {
            // 忽略
        }
    }

    static void test(String name, Assertion assertion) {
        try {
            if (assertion.check()) {
                passed++;
                System.out.println("  [PASS] " + name);
            } else {
                failed++;
                System.out.println("  [FAIL] " + name);
            }
        } catch (Exception e) {
            failed++;
            System.out.println("  [ERROR] " + name + " -> " + e);
        }
    }
}
