package com.example.agentdeepseek.service.llm.profile;

import com.example.agentdeepseek.service.llm.profile.ProviderProfile.BalanceProvider;

/**
 * 余额分派等价性测试（main 方法式，纯静态、不依赖 Spring / 网络）。
 *
 * <h3>为什么单独测这个</h3>
 * <p>
 * T6 把 {@code ProviderBalanceService} 里 5 个 {@code isXxx} if 链换成
 * {@code ProviderRegistry.balanceCandidateFor}。<b>判定顺序是短路的</b>，一旦顺序错了，
 * 同一个配置就会开始<b>查另一个平台的账户余额</b> —— 这是"看起来能用、实际查错地方"的典型事故。
 * </p>
 * <p>因此这里把「旧实现的判定结果」逐条写成期望值，逐项比对。</p>
 *
 * <h3>覆盖点</h3>
 * <ol>
 *   <li><b>A</b>：模板命中（含全部历史别名）</li>
 *   <li><b>B</b>：host 兜底（模板名不相干时仍能按域名识别平台）</li>
 *   <li><b>C</b>：<b>顺序陷阱</b> —— 模板与 host 指向不同平台时，谁先命中（旧行为不可改）</li>
 *   <li><b>D</b>：边界（空 baseUrl / 非法 URL / 未知模板）</li>
 * </ol>
 *
 * <h3>运行方式</h3>
 * <pre>
 * mvn -q -DskipTests -DskipFrontend=true test-compile
 * mvn -q org.codehaus.mojo:exec-maven-plugin:3.1.0:java \
 *     -Dexec.mainClass=com.example.agentdeepseek.service.llm.profile.BalanceDispatchEquivalenceTest \
 *     -Dexec.classpathScope=test -DskipFrontend=true
 * </pre>
 */
public class BalanceDispatchEquivalenceTest {

    private static int passed = 0;
    private static int failed = 0;

    public static void main(String[] args) {
        groupA_templateHit();
        groupB_hostFallback();
        groupC_orderTraps();
        groupD_boundaries();

        System.out.println("\n===== 结果: " + passed + " 通过, " + failed + " 失败 =====");
        if (failed > 0) {
            System.exit(1);
        }
    }

    // ==================== A. 模板命中 ====================

    private static void groupA_templateHit() {
        System.out.println("\n--- A. 模板命中（含历史别名）---");

        // 火山系三别名
        checkBalance("A 火山 volcengine", "volcengine", "https://ark.cn-beijing.volces.com/api/v3",
                BalanceProvider.VOLCENGINE_BILLING);
        checkBalance("A 火山 ark", "ark", "https://ark.cn-beijing.volces.com/api/v3",
                BalanceProvider.VOLCENGINE_BILLING);
        checkBalance("A 火山 doubao", "doubao", "https://ark.cn-beijing.volces.com/api/v3",
                BalanceProvider.VOLCENGINE_BILLING);

        // DeepSeek
        checkBalance("A DeepSeek", "deepseek", "https://api.deepseek.com",
                BalanceProvider.DEEPSEEK_OFFICIAL);

        // MiniMax 四别名
        checkBalance("A MiniMax", "minimax", "https://api.minimaxi.com",
                BalanceProvider.MINIMAX_PLAN);
        checkBalance("A MiniMax 国内", "minimax-cn", "https://api.minimaxi.com",
                BalanceProvider.MINIMAX_PLAN);
        checkBalance("A MiniMax 国际", "minimax-intl", "https://api.minimax.io",
                BalanceProvider.MINIMAX_PLAN);
        checkBalance("A MiniMax 全球", "minimax-global", "https://api.minimax.io",
                BalanceProvider.MINIMAX_PLAN);

        // MiMo 两别名
        checkBalance("A MiMo", "mimo", "https://api.xiaomimimo.com",
                BalanceProvider.MIMO_COOKIE);
        checkBalance("A MiMo 别名", "xiaomi-mimo", "https://api.xiaomimimo.com",
                BalanceProvider.MIMO_COOKIE);

        // 千问三别名
        checkBalance("A 千问", "qwen", "https://dashscope.aliyuncs.com/compatible-mode/v1",
                BalanceProvider.ALIYUN_BILLING);
        checkBalance("A 千问 dashscope", "dashscope", "https://dashscope.aliyuncs.com",
                BalanceProvider.ALIYUN_BILLING);
        checkBalance("A 千问 tongyi", "tongyi", "https://dashscope.aliyuncs.com",
                BalanceProvider.ALIYUN_BILLING);

        // 不支持余额的平台
        for (String t : new String[]{"gpt", "gpt-official", "chatgpt", "openai",
                "anthropic", "ollama", "custom"}) {
            checkBalance("A 不支持余额 [" + t + "]", t, "https://api.example.com",
                    BalanceProvider.NONE);
        }
    }

    // ==================== B. host 兜底 ====================

    private static void groupB_hostFallback() {
        System.out.println("\n--- B. host 兜底（模板名不相干，靠域名识别）---");

        // 模板统一用 custom（本身无余额能力），能否靠域名识别出平台
        checkBalance("B host=api.deepseek.com", "custom", "https://api.deepseek.com",
                BalanceProvider.DEEPSEEK_OFFICIAL);
        checkBalance("B host=*.volces.com", "custom", "https://ark.cn-beijing.volces.com/api/v3",
                BalanceProvider.VOLCENGINE_BILLING);
        checkBalance("B host=*.minimaxi.com", "custom", "https://api.minimaxi.com",
                BalanceProvider.MINIMAX_PLAN);
        checkBalance("B host=*.minimax.io", "custom", "https://api.minimax.io",
                BalanceProvider.MINIMAX_PLAN);
        checkBalance("B host=*.xiaomimimo.com", "custom", "https://platform.xiaomimimo.com/api/v1/balance",
                BalanceProvider.MIMO_COOKIE);
        checkBalance("B host=*.aliyuncs.com", "custom", "https://dashscope.aliyuncs.com",
                BalanceProvider.ALIYUN_BILLING);

        // 中转域名不应被误判成任何官方平台
        checkBalance("B 第三方中转不误判（openai 模板）", "openai",
                "https://my-proxy.example.com/v1", BalanceProvider.NONE);
        checkBalance("B 第三方中转不误判（custom 模板）", "custom",
                "https://my-proxy.example.com/v1", BalanceProvider.NONE);
    }

    // ==================== C. 顺序陷阱（最关键） ====================

    private static void groupC_orderTraps() {
        System.out.println("\n--- C. 顺序陷阱（模板与 host 指向不同平台，谁先命中）---");

        /*
          旧实现 if 链顺序：volcengine → deepseek → minimax → mimo → qwen
          每个判定都是「模板命中 OR host 命中」，短路返回。
          因此下面这些"自相矛盾"的配置，结果由【顺序】决定，不是由模板决定。
          这些结果看起来反直觉，但都是线上的既成事实 —— 迁移时必须保持不变。
         */

        // 火山排在 deepseek 之前 → 火山域名优先
        checkBalance("C1 模板=deepseek + 火山域名 → 火山余额", "deepseek",
                "https://ark.cn-beijing.volces.com/api/v3", BalanceProvider.VOLCENGINE_BILLING);

        // deepseek 排在 minimax/mimo/qwen 之前 → deepseek 域名优先
        checkBalance("C2 模板=qwen + DeepSeek 域名 → DeepSeek 余额", "qwen",
                "https://api.deepseek.com", BalanceProvider.DEEPSEEK_OFFICIAL);
        checkBalance("C3 模板=mimo + DeepSeek 域名 → DeepSeek 余额", "mimo",
                "https://api.deepseek.com", BalanceProvider.DEEPSEEK_OFFICIAL);

        // minimax 排在 mimo 之前 → minimax 域名优先（注意 minimax 与 mimo 名字很像，最易串味）
        checkBalance("C4 模板=mimo + MiniMax 域名 → MiniMax 额度", "mimo",
                "https://api.minimaxi.com", BalanceProvider.MINIMAX_PLAN);
        // minimax 排在 mimo 之前 → minimax 这一项先命中，它的两个条件（模板 OR 域名）任一成立即返回。
        // 所以「模板=minimax + 域名指向 MiMo」会走 MiniMax 额度，而不是 MiMo ——
        // 因为模板命中发生在第 3 项，域名命中发生在第 4 项，第 3 项先短路。
        // 这与旧实现的 if 链完全一致（旧 isMiniMax 也是先判 template 再看 host）。
        checkBalance("C5 模板=minimax + MiMo 域名 → 仍按模板走 MiniMax（顺序在前）", "minimax",
                "https://platform.xiaomimimo.com", BalanceProvider.MINIMAX_PLAN);
        checkBalance("C5 模板=minimax + MiMo 域名（空 baseUrl 也一样）", "minimax",
                null, BalanceProvider.MINIMAX_PLAN);
        // 反过来：模板是 MiMo（不命中 minimax 的模板），但域名是 MiniMax → 第 3 项的域名条件成立
        checkBalance("C5 模板=xiaomi-mimo + MiniMax 域名 → MiniMax 额度", "xiaomi-mimo",
                "https://api.minimaxi.com", BalanceProvider.MINIMAX_PLAN);
        // 正常情况：模板与域名都是 MiMo
        checkBalance("C5 模板=mimo + MiMo 域名 → MiMo 余额", "mimo",
                "https://platform.xiaomimimo.com", BalanceProvider.MIMO_COOKIE);

        // 模板与域名一致时无歧义
        checkBalance("C6 模板与域名一致（火山）", "volcengine",
                "https://ark.cn-beijing.volces.com/api/v3", BalanceProvider.VOLCENGINE_BILLING);
    }

    // ==================== D. 边界 ====================

    private static void groupD_boundaries() {
        System.out.println("\n--- D. 边界 ---");

        // 空 baseUrl：只靠模板判定
        checkBalance("D1 空 baseUrl + 已知模板", "deepseek", null,
                BalanceProvider.DEEPSEEK_OFFICIAL);
        checkBalance("D1 空 baseUrl + 已知模板（火山）", "volcengine", "",
                BalanceProvider.VOLCENGINE_BILLING);

        // 空 baseUrl + 未知模板：无法识别
        checkBalance("D2 空 baseUrl + 未知模板", "some-random", null,
                BalanceProvider.NONE);

        // 非法 URL：host 解析失败 → 只靠模板
        checkBalance("D3 非法 URL + 已知模板", "deepseek", "not a valid url",
                BalanceProvider.DEEPSEEK_OFFICIAL);
        checkBalance("D3 非法 URL + 未知模板", "some-random", "not a valid url",
                BalanceProvider.NONE);

        // 未知模板 + 非官方域名
        checkBalance("D4 未知模板 + 非官方域名", "some-random", "https://example.com",
                BalanceProvider.NONE);

        // null 模板（调用方未兜底 code 时）
        checkBalance("D5 null 模板 + 火山域名", null,
                "https://ark.cn-beijing.volces.com/api/v3", BalanceProvider.VOLCENGINE_BILLING);

        // 大小写不敏感
        checkBalance("D6 模板大小写不敏感", "DEEPSEEK", "https://api.deepseek.com",
                BalanceProvider.DEEPSEEK_OFFICIAL);
        checkBalance("D6 域名大小写不敏感", "custom", "https://API.DEEPSEEK.COM",
                BalanceProvider.DEEPSEEK_OFFICIAL);
    }

    // ==================== 辅助 ====================

    private static void checkBalance(String name, String template, String baseUrl,
                                     BalanceProvider expected) {
        ProviderProfile profile = ProviderRegistry.balanceCandidateFor(template, baseUrl);
        BalanceProvider actual = profile == null || profile.getBalance() == null
                ? BalanceProvider.NONE
                : profile.getBalance().getProvider();
        boolean ok = actual == expected;
        if (ok) {
            passed++;
            System.out.println("  PASS " + name);
        } else {
            failed++;
            System.out.println("  FAIL " + name + " | 期望=" + expected + " 实际=" + actual);
        }
    }
}
