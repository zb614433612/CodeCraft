package com.example.agentdeepseek.service.llm.profile;

import com.example.agentdeepseek.service.llm.profile.ProviderProfile.AdapterType;
import com.example.agentdeepseek.service.llm.profile.ProviderProfile.AuthSpec;

/**
 * T12 认证头合并等价性测试（main 方法式，不依赖 Spring / 网络）。
 *
 * <h3>为什么需要这个测试</h3>
 * <p>
 * T12 把 {@code LLMWebClientManager} 里<b>两个各自独立的 switch</b> 合并成
 * {@code ProviderRegistry} 的单一 {@code auth} 声明：
 * </p>
 * <ul>
 *   <li>旧 {@code getAuthHeaderName}：返回 {@code Authorization} / {@code x-api-key} / {@code api-key}</li>
 *   <li>旧 {@code getAuthHeaderPrefix}：返回 {@code "Bearer "} / {@code null}</li>
 * </ul>
 * <p>
 * 合并过程中最容易犯的错是<b>「顺手把行为也改了」</b>。这里把旧代码的每个分支结论
 * 固化成断言表，逐条比对，确保合并只是「换个地方存」，<b>行为零变化</b>。
 * </p>
 *
 * <h3>⚠️ 特别固化的一个历史行为</h3>
 * <p>
 * 旧 {@code getAuthHeaderName} 的注释写「ollama / custom → 无（返回 Authorization 但无前缀时跳过）」，
 * 但<b>代码实际是「发送裸 key」</b>（{@code prefix} 为 null 时走 {@code defaultHeader(name, apiKey)}）。
 * 本测试按<b>代码行为</b>固化：{@code Authorization} + <b>裸 key（不是不发送）</b>。
 * 若将来有人「按注释修 bug」改成跳过，这里会立刻失败并提醒他：那是行为变更，需要单独评估。
 * </p>
 *
 * <h3>运行方式</h3>
 * <pre>
 * mvn -q -DskipTests -DskipFrontend=true test-compile
 * mvn -q org.codehaus.mojo:exec-maven-plugin:3.1.0:java \
 *     -Dexec.mainClass=com.example.agentdeepseek.service.llm.profile.AuthHeaderEquivalenceTest \
 *     -Dexec.classpathScope=test -DskipFrontend=true
 * </pre>
 */
public class AuthHeaderEquivalenceTest {

    private static int passed = 0;
    private static int failed = 0;

    /** 旧 getAuthHeaderName 的分支结论（原样搬过来，作为对照基准） */
    private static String legacyHeaderName(String template) {
        return switch (template.toLowerCase()) {
            case "anthropic" -> "x-api-key";
            case "mimo" -> "api-key";
            // 注意：旧实现这里没有 mimo 别名、没有 minimax 别名处理，全部落到 default
            default -> "Authorization";
        };
    }

    /** 旧 getAuthHeaderPrefix 的分支结论（原样搬过来，作为对照基准） */
    private static String legacyHeaderPrefix(String template) {
        return switch (template.toLowerCase()) {
            case "anthropic", "mimo", "ollama", "custom" -> null;
            default -> "Bearer ";
        };
    }

    public static void main(String[] args) {
        System.out.println("========== T12 认证头等价性测试 ==========");

        groupA_legacyEquivalence();
        groupB_prefixSemantics();
        groupC_aliasAndDrift();

        System.out.println("\n========== 结果: " + passed + " passed, " + failed + " failed ==========");
        if (failed > 0) {
            System.exit(1);
        }
    }

    // ==================== A. 与旧 switch 逐条等价 ====================

    private static void groupA_legacyEquivalence() {
        System.out.println("\n--- A. 与旧 switch 逐条等价（T12 前后行为必须一致）---");

        /*
          覆盖范围 = 旧 switch 里出现过的<b>所有字面量</b> + 少量别名。

          为什么要连别名一起测：旧 getAuthHeaderName/Prefix 的 switch <b>没写别名</b>
          （如 xiaomi-mimo / minimax-cn 会掉进 default 拿 Authorization+Bearer），
          而新实现按档案解析会拿到 api-key / Bearer。
          这是<b>有意的修正</b>（T12 修掉的 4 条漂移之一），不能算「不等价」，
          因此在 B/C 组单独断言，A 组只比对旧 switch 明确处理过的名字。
         */
        String[] legacyHandled = {
                "deepseek", "gpt", "openai", "anthropic", "ollama", "custom",
                "mimo", "minimax", "qwen", "dashscope", "tongyi", "volcengine", "ark", "doubao"
        };

        for (String template : legacyHandled) {
            AuthSpec spec = ProviderRegistry.authFor(template);
            check("A[" + template + "] 认证头名称与旧 switch 一致",
                    legacyHeaderName(template), spec.getHeaderName());
            check("A[" + template + "] 认证头前缀与旧 switch 一致",
                    legacyHeaderPrefix(template), spec.getHeaderPrefix());
        }

        // A2 逐个点名关键分支（防止上面的循环因数据源改错而整体失效）
        check("A2 anthropic → x-api-key", "x-api-key", ProviderRegistry.authFor("anthropic").getHeaderName());
        check("A2 anthropic 无前缀", null, ProviderRegistry.authFor("anthropic").getHeaderPrefix());
        check("A2 mimo → api-key", "api-key", ProviderRegistry.authFor("mimo").getHeaderName());
        check("A2 mimo 无前缀", null, ProviderRegistry.authFor("mimo").getHeaderPrefix());
        check("A2 deepseek → Authorization", "Authorization", ProviderRegistry.authFor("deepseek").getHeaderName());
        check("A2 deepseek 有 Bearer", "Bearer ", ProviderRegistry.authFor("deepseek").getHeaderPrefix());
        check("A2 volcengine → Authorization + Bearer",
                "Authorization", ProviderRegistry.authFor("volcengine").getHeaderName());
        check("A2 volcengine 有 Bearer", "Bearer ", ProviderRegistry.authFor("volcengine").getHeaderPrefix());
    }

    // ==================== B. 前缀语义（最容易误解的一条） ====================

    private static void groupB_prefixSemantics() {
        System.out.println("\n--- B. 前缀语义（null = 裸 key，不是不发送）---");

        // B1 固化历史行为：ollama / custom 发「裸 key」，不是「不发认证头」
        for (String template : new String[]{"ollama", "custom"}) {
            AuthSpec spec = ProviderRegistry.authFor(template);
            check("B1[" + template + "] 仍有认证头（不是不发）",
                    true, spec.getHeaderName() != null && !spec.getHeaderName().isBlank());
            check("B1[" + template + "] 前缀为 null（裸 key）", null, spec.getHeaderPrefix());
            // 拼值后必须等于裸 key —— 这是真正的行为契约
            check("B1[" + template + "] 拼值等于裸 key", "sk-test", withPrefix(spec.getHeaderPrefix(), "sk-test"));
        }

        // B2 Bearer 需带尾随空格（少一个空格 = 401，且日志里看不出来）
        AuthSpec bearer = ProviderRegistry.authFor("deepseek");
        check("B2 Bearer 前缀带尾随空格", "Bearer ", bearer.getHeaderPrefix());
        check("B2 Bearer 拼值无粘连", "Bearer sk-test", withPrefix(bearer.getHeaderPrefix(), "sk-test"));
        check("B2 若少了尾随空格会出问题（对照）", true,
                !"Bearer".equals(bearer.getHeaderPrefix()));

        // B3 anthropic / mimo：前缀 null 时也是裸 key（官方就是裸 key，不加 Bearer）
        check("B3 anthropic 拼值 = 裸 key", "sk-ant", withPrefix(
                ProviderRegistry.authFor("anthropic").getHeaderPrefix(), "sk-ant"));
        check("B3 mimo 拼值 = 裸 key", "sk-mimo", withPrefix(
                ProviderRegistry.authFor("mimo").getHeaderPrefix(), "sk-mimo"));
    }

    // ==================== C. 别名与漂移修正 ====================

    private static void groupC_aliasAndDrift() {
        System.out.println("\n--- C. 别名解析与漂移修正（T12 有意义地改变行为的部分）---");

        /*
          T12 前：这些别名在旧 getAuthHeaderName / Prefix 的 switch 里<b>没有 case</b>，
          会掉进 default → Authorization + "Bearer "。
          对 xiaomi-mimo 来说这是<b>错的</b>（MiMo 要求 api-key 头），
          属于「档案声明了别名、适配器却不认识」的同一类漂移。

          T12 后：按档案解析 → xiaomi-mimo 正确拿到 api-key。
          这是修正，不是回归。
         */
        check("C1 xiaomi-mimo 现在 → api-key（旧实现会错给 Authorization）",
                "api-key", ProviderRegistry.authFor("xiaomi-mimo").getHeaderName());
        check("C1 xiaomi-mimo 前缀 null（旧实现会错给 Bearer）",
                null, ProviderRegistry.authFor("xiaomi-mimo").getHeaderPrefix());

        // C2 minimax 区域别名：旧实现掉 default 拿 Authorization+Bearer，
        //    恰好与 minimax 正确值相同 → 行为不变，但现在是「查表得知」而非「碰巧对」
        for (String alias : new String[]{"minimax-cn", "minimax-intl", "minimax-global"}) {
            check("C2[" + alias + "] → Authorization + Bearer（与主名一致）",
                    "Authorization", ProviderRegistry.authFor(alias).getHeaderName());
            check("C2[" + alias + "] 有 Bearer", "Bearer ", ProviderRegistry.authFor(alias).getHeaderPrefix());
        }

        // C3 所有档案的所有名字：认证头必须与主名一致（同源，不会只改一半）
        for (ProviderProfile profile : ProviderRegistry.all()) {
            AuthSpec canonical = profile.getAuth();
            if (canonical == null) {
                continue;
            }
            for (String name : profile.allNames()) {
                AuthSpec resolved = ProviderRegistry.authFor(name);
                check("C3[" + name + "] 认证头与主名同源",
                        canonical.getHeaderName(), resolved.getHeaderName());
                check("C3[" + name + "] 前缀与主名同源",
                        canonical.getHeaderPrefix(), resolved.getHeaderPrefix());
            }
        }

        // C4 未注册模板：返回默认（Authorization + Bearer），不抛异常
        AuthSpec unknown = ProviderRegistry.authFor("some-unknown-template");
        check("C4 未注册模板不抛异常", true, unknown != null);
        check("C4 未注册模板用默认头名", "Authorization", unknown.getHeaderName());
        check("C4 未注册模板用默认前缀", "Bearer ", unknown.getHeaderPrefix());

        // C5 每个适配器类型都已建档（认证头不可能缺失）
        for (AdapterType type : AdapterType.values()) {
            boolean covered = ProviderRegistry.all().stream()
                    .anyMatch(p -> p.getAdapterType() == type);
            check("C5 适配器 [" + type + "] 有档案覆盖", true, covered);
        }
    }

    // ==================== 辅助 ====================

    /**
     * 复刻 {@code LLMWebClientManager.withPrefix} 的语义，用于验证拼值结果。
     *
     * <p>刻意在测试里再实现一遍而不是调用私有方法：这样即使生产代码被改错，
     * 测试仍按「契约」验证，而不是跟着一起错。</p>
     */
    private static String withPrefix(String prefix, String apiKey) {
        return (prefix == null || prefix.isEmpty()) ? apiKey : prefix + apiKey;
    }

    private static void check(String name, Object expected, Object actual) {
        boolean ok = expected == null ? actual == null : expected.equals(actual);
        if (ok) {
            passed++;
            System.out.println("  PASS " + name);
        } else {
            failed++;
            System.out.println("  FAIL " + name + "（期望=" + expected + " 实际=" + actual + "）");
        }
    }

    private static void check(String name, boolean ok) {
        if (ok) {
            passed++;
            System.out.println("  PASS " + name);
        } else {
            failed++;
            System.out.println("  FAIL " + name);
        }
    }
}
