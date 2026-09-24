package com.example.agentdeepseek;

import com.example.agentdeepseek.config.NetworkToolConfig;
import com.example.agentdeepseek.config.ProxyResolver;
import com.example.agentdeepseek.tool.impl.SearchTool;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * SearchTool 核心逻辑回归测试（纯逻辑，无需 Spring / 网络）。
 *
 * <p>通过反射验证 SearchTool 的私有纯函数：查询编码、中文检测、引擎顺序自适应、
 * Bing 跳转链解包、域名提取、去重键、质量打分、去重+单域限量+排序。</p>
 *
 * <p>运行方式（main 方法，非 JUnit）：见同目录 README 或命令说明。</p>
 */
public class SearchToolLogicTest {

    static int passed = 0;
    static int failed = 0;

    /** 测试固定为直连模式（NEVER），避免自动探测本机代理引入不确定性 */
    static final NetworkToolConfig CONFIG = new NetworkToolConfig();
    static final SearchTool TOOL;
    static Class<?> SR_CLASS;

    static {
        CONFIG.getProxy().setMode("NEVER");
        TOOL = new SearchTool(new ObjectMapper(), CONFIG, new ProxyResolver(CONFIG));
    }

    interface Assertion {
        boolean check() throws Exception;
    }

    public static void main(String[] args) throws Exception {
        SR_CLASS = Class.forName("com.example.agentdeepseek.tool.impl.SearchTool$SearchResult");
        System.out.println("=== SearchTool 核心逻辑回归测试 ===\n");

        // 1) 查询编码（P0-2 算符透传）
        test("编码: 空格转为 %20 且不含 +", () ->
                "spring%20boot%203".equals(str("encodeQuery", new Class[]{String.class}, "spring boot 3")));

        test("编码: site: 结构保留（编码为 %3A，服务端解码后仍为算符），且无 +", () -> {
            String out = str("encodeQuery", new Class[]{String.class}, "site:github.com spring");
            return out.startsWith("site%3Agithub.com") && out.indexOf('+') < 0;
        });

        // 2) 中文检测（P0-1 语言自适应基础）
        test("中文检测: 含汉字 → true", () ->
                bool("containsChinese", new Class[]{String.class}, "虚拟线程 最佳实践"));
        test("中文检测: 纯英文 → false", () ->
                !bool("containsChinese", new Class[]{String.class}, "virtual threads best practice"));

        // 3) 引擎顺序自适应（P0-1 语言偏好 + 网络模式兜底）
        test("引擎顺序: 无代理-中文 query 优先 bing-cn", () -> {
            List<?> order = list("resolveEngineOrder", new Class[]{String.class}, "中文关键词");
            return "bing-cn".equals(order.get(0));
        });
        test("引擎顺序: 无代理-英文 query 只用国内源（不出现被墙的 bing-global）", () -> {
            List<?> order = list("resolveEngineOrder", new Class[]{String.class}, "virtual threads");
            return "bing-cn".equals(order.get(0)) && !order.contains("bing-global");
        });
        // 有代理时恢复「全球版 Bing 优先」（mode=ALWAYS 不触发探测，测试无网络开销）
        NetworkToolConfig proxyConfig = new NetworkToolConfig();
        proxyConfig.getProxy().setMode("ALWAYS");
        SearchTool proxyTool = new SearchTool(new ObjectMapper(), proxyConfig, new ProxyResolver(proxyConfig));
        test("引擎顺序: 有代理-英文 query 优先 bing-global", () -> {
            List<?> order = listOn(proxyTool, "resolveEngineOrder", new Class[]{String.class}, "virtual threads");
            return "bing-global".equals(order.get(0));
        });
        test("引擎顺序: 有代理-中文 query 仍优先 bing-cn", () -> {
            List<?> order = listOn(proxyTool, "resolveEngineOrder", new Class[]{String.class}, "中文关键词");
            return "bing-cn".equals(order.get(0));
        });

        // 4) Bing 跳转链解包（P1-7 结构增强前提）
        test("Bing 跳转链解包为真实 URL", () -> {
            String wrapped = "https://www.bing.com/ck/a?!&&p=x&u=a1aHR0cHM6Ly9leGFtcGxlLmNvbQ&ntb=1";
            return "https://example.com".equals(str("unwrapBingRedirect", new Class[]{String.class}, wrapped));
        });
        test("普通 URL 不被解包逻辑改动", () ->
                "https://a.com/x".equals(str("unwrapBingRedirect", new Class[]{String.class}, "https://a.com/x")));

        // 5) 域名提取
        test("域名提取: 去掉 www. 前缀", () ->
                "example.com".equals(strStatic("extractDomain", new Class[]{String.class}, "https://www.example.com/a/b")));

        // 6) 去重键规范化（P1-6）
        test("去重键: 去协议/www/查询参数/锚点/末尾斜杠", () -> {
            Object r = newResult("t", "https://www.a.com/x/?utm=1#frag", "d");
            return "a.com/x".equals(str("canonicalKey", new Class[]{SR_CLASS}, r));
        });

        // 7) 质量打分（P1-6）
        test("打分: 权威域 github 提权 +3", () ->
                intVal("scoreOf", new Class[]{SR_CLASS}, newResult("t", "https://github.com/a/b", "d")) == 3);
        test("打分: 低质域 baijiahao 降权 -5", () ->
                intVal("scoreOf", new Class[]{SR_CLASS}, newResult("t", "https://baijiahao.baidu.com/s?id=1", "d")) == -5);
        test("打分: 普通域为 0 分", () ->
                intVal("scoreOf", new Class[]{SR_CLASS}, newResult("t", "https://someblog.cn/a", "d")) == 0);

        // 8) 去重 + 单域限量 + 权威优先排序（P1-6）
        test("去重/限量: 同 URL 去重、同域≤2、权威域排前", () -> {
            List<Object> raw = new ArrayList<>();
            raw.add(newResult("csdn-a", "https://blog.csdn.net/a", "d"));
            raw.add(newResult("csdn-a-重复", "https://blog.csdn.net/a?spm=1001", "d")); // 与上条同 key
            raw.add(newResult("csdn-b", "https://blog.csdn.net/b", "d"));
            raw.add(newResult("csdn-c", "https://blog.csdn.net/c", "d"));
            raw.add(newResult("github", "https://github.com/x/y", "d"));
            List<?> out = list("dedupRankAndLimit", new Class[]{List.class, int.class}, raw, 10);
            // 期望：csdn 去重后 3 条 → 限量 2 条，溢出 1 条补齐；+github 1 条 = 4 条；首条为权威 github
            if (out.size() != 4) {
                System.out.println("      实际条数=" + out.size());
                return false;
            }
            return urlOf(out.get(0)).contains("github.com");
        });

        System.out.println("\n=== 结果: " + passed + " 通过 / " + failed + " 失败 ===");
        if (failed > 0) {
            System.exit(1);
        }
    }

    // ==================== 断言与反射辅助 ====================

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

    static String str(String method, Class<?>[] types, Object... args) throws Exception {
        Method m = SearchTool.class.getDeclaredMethod(method, types);
        m.setAccessible(true);
        return (String) m.invoke(TOOL, args);
    }

    static String strStatic(String method, Class<?>[] types, Object... args) throws Exception {
        Method m = SearchTool.class.getDeclaredMethod(method, types);
        m.setAccessible(true);
        return (String) m.invoke(null, args);
    }

    static boolean bool(String method, Class<?>[] types, Object... args) throws Exception {
        Method m = SearchTool.class.getDeclaredMethod(method, types);
        m.setAccessible(true);
        return (Boolean) m.invoke(TOOL, args);
    }

    static int intVal(String method, Class<?>[] types, Object... args) throws Exception {
        Method m = SearchTool.class.getDeclaredMethod(method, types);
        m.setAccessible(true);
        return (Integer) m.invoke(TOOL, args);
    }

    static List<?> list(String method, Class<?>[] types, Object... args) throws Exception {
        Method m = SearchTool.class.getDeclaredMethod(method, types);
        m.setAccessible(true);
        return (List<?>) m.invoke(TOOL, args);
    }

    /** 在指定 SearchTool 实例上调用返回 List 的私有方法（用于对比「有代理/无代理」两种实例） */
    static List<?> listOn(Object target, String method, Class<?>[] types, Object... args) throws Exception {
        Method m = SearchTool.class.getDeclaredMethod(method, types);
        m.setAccessible(true);
        return (List<?>) m.invoke(target, args);
    }

    static Object newResult(String title, String url, String desc) throws Exception {
        Constructor<?> c = SR_CLASS.getDeclaredConstructor(String.class, String.class, String.class);
        c.setAccessible(true);
        return c.newInstance(title, url, desc);
    }

    static String urlOf(Object sr) throws Exception {
        Field f = SR_CLASS.getDeclaredField("url");
        f.setAccessible(true);
        return (String) f.get(sr);
    }
}
