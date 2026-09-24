package com.example.agentdeepseek;

import com.example.agentdeepseek.config.NetworkToolConfig;
import com.example.agentdeepseek.config.ProxyResolver;
import com.example.agentdeepseek.tool.impl.ConnectivityChecker;
import com.example.agentdeepseek.tool.impl.WebFetchTool;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * WebFetchTool 核心逻辑回归测试（纯逻辑为主，实机探测仅供参考）。
 *
 * <p>覆盖：内网地址判定（Jina 安全闸门）、UA 档案 client hints 推导、UA 稳定/轮换选择、
 * 反爬拦截页双重判定、Retry-After 解析、正文提取、UA 池回退、输出格式化截断。</p>
 *
 * <p>运行方式（main 方法，非 JUnit）：
 * mvn -q -DskipTests -DskipFrontend=true test-compile &amp;&amp;
 * mvn -q org.codehaus.mojo:exec-maven-plugin:3.1.0:java
 *   -Dexec.mainClass=com.example.agentdeepseek.WebFetchToolLogicTest -Dexec.classpathScope=test -DskipFrontend=true</p>
 */
public class WebFetchToolLogicTest {

    static int passed = 0;
    static int failed = 0;

    static final ObjectMapper MAPPER = new ObjectMapper();
    static final Class<?>[] STR = new Class[]{String.class};

    /** WebFetchTool$UserAgentProfile 内部类 */
    static Class<?> uapClass;

    public static void main(String[] args) throws Exception {
        uapClass = Class.forName("com.example.agentdeepseek.tool.impl.WebFetchTool$UserAgentProfile");
        System.out.println("=== WebFetchTool 核心逻辑回归测试 ===\n");

        // 1) 内网地址判定（Jina 安全闸门：内网地址不得发送给第三方服务）
        test("私有地址: localhost/127/10/192.168/172.16-31/169.254 → 私有", () ->
                bool("isPrivateHost", STR, "localhost")
                        && bool("isPrivateHost", STR, "127.0.0.1")
                        && bool("isPrivateHost", STR, "10.1.2.3")
                        && bool("isPrivateHost", STR, "192.168.1.100")
                        && bool("isPrivateHost", STR, "172.16.0.1")
                        && bool("isPrivateHost", STR, "172.31.255.254")
                        && bool("isPrivateHost", STR, "169.254.1.1"));
        test("私有地址: 公网 IP / 公网域名 / 172.32.x → 公网", () ->
                !bool("isPrivateHost", STR, "8.8.8.8")
                        && !bool("isPrivateHost", STR, "example.com")
                        && !bool("isPrivateHost", STR, "172.32.0.1")
                        && !bool("isPrivateHost", STR, "1.2.3.example"));
        test("私有地址: null / 空白 → 保守判为私有", () ->
                bool("isPrivateHost", STR, (Object) null) && bool("isPrivateHost", STR, "  "));
        test("私有地址: .local 后缀与 IPv6 回环/ULA → 私有", () ->
                bool("isPrivateHost", STR, "myhost.local")
                        && bool("isPrivateHost", STR, "[::1]")
                        && bool("isPrivateHost", STR, "::1")
                        && bool("isPrivateHost", STR, "fd00::1"));

        // 2) UA 档案：client hints 与 UA 版本/平台配套（防「UA 与提示头矛盾」被风控）
        String chromeWin = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36";
        String edgeWin = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36 Edg/131.0.0.0";
        String firefox = "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:133.0) Gecko/20100101 Firefox/133.0";
        String safari = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.6 Safari/605.1.15";
        String chromeMac = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36";

        test("UA 档案: Chrome/131 生成配套 sec-ch-ua（版本一致）与 Windows 平台", () -> {
            Object p = uaProfile(chromeWin);
            String sec = uaField(p, "secChUa");
            return sec != null && sec.contains("Google Chrome\";v=\"131")
                    && sec.contains("Chromium\";v=\"131")
                    && "Windows".equals(uaField(p, "platform"));
        });
        test("UA 档案: Edge 品牌识别为 Microsoft Edge", () -> {
            String sec = uaField(uaProfile(edgeWin), "secChUa");
            return sec != null && sec.contains("Microsoft Edge\";v=\"131");
        });
        test("UA 档案: Firefox / Safari 不下发 client hints", () ->
                uaField(uaProfile(firefox), "secChUa") == null
                        && uaField(uaProfile(safari), "secChUa") == null);
        test("UA 档案: Chrome macOS 平台为 macOS", () ->
                "macOS".equals(uaField(uaProfile(chromeMac), "platform")));

        // 3) UA 选择：同域稳定（不频繁变化）、重试轮换（换指纹破 UA 黑名单）
        test("UA 选择: 同域同 attempt 稳定且下标合法", () -> {
            int a = intCall("pickProfileIndex", new Class[]{String.class, int.class, int.class}, "example.com", 0, 6);
            int b = intCall("pickProfileIndex", new Class[]{String.class, int.class, int.class}, "example.com", 0, 6);
            return a == b && a >= 0 && a < 6;
        });
        test("UA 选择: 重试轮换（相邻 attempt 不同）且不越界", () -> {
            int prev = -1;
            for (int attempt = 0; attempt < 6; attempt++) {
                int idx = intCall("pickProfileIndex", new Class[]{String.class, int.class, int.class}, "zhihu.com", attempt, 6);
                if (idx < 0 || idx >= 6) {
                    return false;
                }
                if (attempt > 0 && idx == prev) {
                    return false;
                }
                prev = idx;
            }
            return true;
        });
        test("UA 选择: 单元素池恒为 0", () ->
                intCall("pickProfileIndex", new Class[]{String.class, int.class, int.class}, "a.com", 3, 1) == 0
                        && intCall("pickProfileIndex", new Class[]{String.class, int.class, int.class}, "a.com", 5, 0) == 0);

        // 4) 反爬拦截页判定（关键词 + 正文极短 双重条件，防误伤）
        Class<?>[] blockTypes = new Class[]{String.class, String.class, List.class, int.class};
        List<String> keywords = List.of("just a moment", "cf-browser-verification", "zse-ck", "安全验证");
        test("拦截页: Cloudflare 挑战页（短正文 + 关键词）→ 拦截", () -> {
            String html = "<html><head><title>Just a moment...</title></head><body>Enable JavaScript and cookies to continue</body></html>";
            return bool("isBlockPage", blockTypes, html, "Just a moment... Enable JavaScript and cookies to continue", keywords, 1500);
        });
        test("拦截页: 长正文含关键词 → 不误伤", () -> {
            String text = "安全\n".repeat(800) + "本文讨论 security check 机制";
            return !bool("isBlockPage", blockTypes, "<html><body>安全 讨论 security check</body></html>", text, keywords, 1500);
        });
        test("拦截页: 短页但无关键词 → 不判定", () ->
                !bool("isBlockPage", blockTypes, "<html><body>hello world</body></html>", "hello world", keywords, 1500));
        test("拦截页: 空关键词列表 → 不判定", () ->
                !bool("isBlockPage", blockTypes, "<html>just a moment</html>", "x", List.of(), 1500));

        // 5) Retry-After 解析（429 退避）
        Class<?>[] retryTypes = new Class[]{List.class, long.class};
        test("Retry-After: 秒数解析为毫秒", () ->
                longCall("parseRetryAfter", retryTypes, List.of("3"), 500L) == 3000L);
        test("Retry-After: 超过 10s 上限截断", () ->
                longCall("parseRetryAfter", retryTypes, List.of("999"), 500L) == 10_000L);
        test("Retry-After: HTTP-date / null / 非法值回退默认", () ->
                longCall("parseRetryAfter", retryTypes, List.of("Wed, 21 Oct 2026 07:28:00 GMT"), 500L) == 500L
                        && longCall("parseRetryAfter", retryTypes, null, 500L) == 500L
                        && longCall("parseRetryAfter", retryTypes, List.of("-1"), 500L) == 500L);

        // 6) 正文提取（剔除噪声）
        test("正文提取: 剔除 script/style 噪声", () -> {
            String html = "<html><head><script>var a=1;</script><style>.x{}</style></head>"
                    + "<body><p>正文内容A</p><script>track();</script></body></html>";
            String text = strCall("extractText", STR, html);
            return text.contains("正文内容A") && !text.contains("track()") && !text.contains("var a=1");
        });

        // 7) UA 池回退与输出格式化（实例方法）
        test("UA 池: 默认配置含多条 UA（混合浏览器）", () -> {
            List<?> pool = (List<?>) callOn(newNeverTool(new NetworkToolConfig()), "resolveUserAgentPool", new Class[]{});
            return pool.size() >= 5;
        });
        test("UA 池: 配置为空时回退 fetch-user-agent", () -> {
            NetworkToolConfig cfg = new NetworkToolConfig();
            cfg.setFetchUserAgents(new ArrayList<>());
            cfg.setFetchUserAgent("MyCustomUA/1.0");
            List<?> pool = (List<?>) callOn(newNeverTool(cfg), "resolveUserAgentPool", new Class[]{});
            return pool.size() == 1 && "MyCustomUA/1.0".equals(pool.get(0));
        });
        test("格式化输出: 超长内容截断并标注抓取方式", () -> {
            NetworkToolConfig cfg = new NetworkToolConfig();
            cfg.setFetchMaxContentLength(10);
            String out = (String) callOn(newNeverTool(cfg), "formatResult",
                    new Class[]{String.class, String.class, String.class},
                    "https://example.com", "本地抓取（直连）", "0123456789ABCDEFGHIJ");
            return out.contains("[抓取方式] 本地抓取（直连）")
                    && out.contains("仅显示前 10 字符")
                    && !out.contains("ABCDEFGHIJ");
        });

        System.out.println("\n=== 结果: " + passed + " 通过 / " + failed + " 失败 ===");

        liveProbe();

        if (failed > 0) {
            System.exit(1);
        }
    }

    // ==================== 实机探测（依赖真实网络与本地代理，仅供参考） ====================

    static void liveProbe() {
        System.out.println("\n=== 实机探测（依赖真实网络，不计入通过率） ===");

        // 1) 直连模式（mode=NEVER）：应走本地抓取路径
        try {
            NetworkToolConfig cfg = new NetworkToolConfig();
            cfg.getProxy().setMode("NEVER");
            WebFetchTool tool = newNeverTool(cfg);
            String out = tool.execute(MAPPER.readTree("{\"url\":\"https://example.com\"}"));
            System.out.println("  [LIVE] 直连模式 example.com → " + headline(out));
        } catch (Exception e) {
            System.out.println("  [LIVE] 直连模式探测异常: " + e.getMessage());
        }

        // 2) 自动模式（默认 AUTO）：本机有代理时应优先 Jina Reader
        try {
            NetworkToolConfig cfg = new NetworkToolConfig();   // 默认 AUTO 自动探测
            ProxyResolver resolver = new ProxyResolver(cfg);
            System.out.println("  [LIVE] 本机代理探测: " + resolver.describe());
            WebFetchTool tool = new WebFetchTool(MAPPER, new ConnectivityChecker(cfg, resolver), cfg, resolver);
            String out = tool.execute(MAPPER.readTree("{\"url\":\"https://example.com\"}"));
            boolean viaJina = out.contains("Jina Reader");
            System.out.println("  [LIVE] 自动模式 example.com → 通道: "
                    + (viaJina ? "Jina Reader（优先通道生效）" : "本地抓取（Jina 未启用或已降级）"));
            System.out.println("  [LIVE] 输出预览: " + headline(out));
        } catch (Exception e) {
            System.out.println("  [LIVE] 自动模式探测异常: " + e.getMessage());
        }

        // 3) 降级路径：Jina 地址不可用时，应自动降级本地抓取且抓取成功
        try {
            NetworkToolConfig cfg = new NetworkToolConfig();   // AUTO：应探测到本机代理
            cfg.setFetchJinaReaderUrl("https://127.0.0.1:9/"); // 故意指向不可达端口
            ProxyResolver resolver = new ProxyResolver(cfg);
            WebFetchTool tool = new WebFetchTool(MAPPER, new ConnectivityChecker(cfg, resolver), cfg, resolver);
            String out = tool.execute(MAPPER.readTree("{\"url\":\"https://example.com\"}"));
            boolean degraded = out.contains("本地抓取");
            System.out.println("  [LIVE] 降级场景（Jina 地址不可达）example.com → "
                    + (degraded ? "已降级本地抓取并成功 ✅" : "未按预期降级：" + headline(out)));
        } catch (Exception e) {
            System.out.println("  [LIVE] 降级场景探测异常: " + e.getMessage());
        }
    }

    /** 构造直连模式（NEVER）的 WebFetchTool，避免测试引入代理探测不确定性 */
    static WebFetchTool newNeverTool(NetworkToolConfig cfg) {
        cfg.getProxy().setMode("NEVER");
        ProxyResolver resolver = new ProxyResolver(cfg);
        return new WebFetchTool(MAPPER, new ConnectivityChecker(cfg, resolver), cfg, resolver);
    }

    static String headline(String text) {
        String one = text.replaceAll("\\s+", " ").trim();
        return one.length() <= 160 ? one : one.substring(0, 160) + "...";
    }

    // ==================== 断言与反射辅助 ====================

    interface Assertion {
        boolean check() throws Exception;
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

    static Object call(String method, Class<?>[] types, Object... args) throws Exception {
        Method m = WebFetchTool.class.getDeclaredMethod(method, types);
        m.setAccessible(true);
        return m.invoke(null, args);
    }

    static Object callOn(Object target, String method, Class<?>[] types, Object... args) throws Exception {
        Method m = WebFetchTool.class.getDeclaredMethod(method, types);
        m.setAccessible(true);
        return m.invoke(target, args);
    }

    static boolean bool(String method, Class<?>[] types, Object... args) throws Exception {
        return (Boolean) call(method, types, args);
    }

    static int intCall(String method, Class<?>[] types, Object... args) throws Exception {
        return (Integer) call(method, types, args);
    }

    static long longCall(String method, Class<?>[] types, Object... args) throws Exception {
        return (Long) call(method, types, args);
    }

    static String strCall(String method, Class<?>[] types, Object... args) throws Exception {
        return (String) call(method, types, args);
    }

    static Object uaProfile(String ua) throws Exception {
        Method m = uapClass.getDeclaredMethod("of", String.class);
        m.setAccessible(true);
        return m.invoke(null, ua);
    }

    static String uaField(Object profile, String field) throws Exception {
        Field f = uapClass.getDeclaredField(field);
        f.setAccessible(true);
        return (String) f.get(profile);
    }
}
