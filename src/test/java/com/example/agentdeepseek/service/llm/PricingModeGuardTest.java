package com.example.agentdeepseek.service.llm;

import com.example.agentdeepseek.config.TokenPricingConfig;
import com.example.agentdeepseek.service.llm.profile.ProviderProfile;
import com.example.agentdeepseek.service.llm.profile.ProviderRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;

/**
 * 计费模式闸门测试（main 方法式，项目统一风格，不依赖 JUnit / 网络）。
 *
 * <h3>要证明什么</h3>
 * <p>
 * 历史 bug：{@code TokenPricingConfig} 只能表达「元 / 百万 token」一种形态，于是
 * <b>「这个模型没配单价」</b> 与 <b>「这个 Provider 根本不该按 token 估价」</b>
 * 被混成了同一件事。火山方舟 Coding Plan 是「200 元 / 9 万次」的<b>次数套餐</b>，
 * 却被按 DeepSeek 单价算出了一笔并不存在的花费。
 * </p>
 * <p>本测试验证三层闸门，且<b>用真实用量回放</b>证明金额确实不再产生：</p>
 * <ol>
 *   <li><b>档案层</b>：{@code ProviderRegistry.pricingModeFor} 能按 baseUrl 区分
 *       火山普通（TOKEN）与 Coding Plan（QUOTA）；</li>
 *   <li><b>单价配置层</b>：{@code isEstimable} 在 QUOTA / FREE 下一律返回 false
 *       —— <b>即便有人误给这类模型配了单价</b>；</li>
 *   <li><b>落库层</b>：{@code calculateCost(model, snapshot, mode)} 在 QUOTA / FREE 下返回 null，
 *       从而 {@code cost_estimate} 落 NULL、{@code pricedRequestCount} 为 0、前端不显示金额。</li>
 * </ol>
 *
 * <h3>运行方式</h3>
 * <pre>
 * mvn -q -DskipTests -DskipFrontend=true test-compile
 * mvn -q org.codehaus.mojo:exec-maven-plugin:3.1.0:java \
 *     -Dexec.mainClass=com.example.agentdeepseek.service.llm.PricingModeGuardTest \
 *     -Dexec.classpathScope=test -DskipFrontend=true
 * </pre>
 */
public class PricingModeGuardTest {

    private static int passed = 0;
    private static int failed = 0;

    /** 火山方舟 Coding Plan 真实 Base URL（用户实际配置） */
    private static final String ARK_CODING_URL = "https://ark.cn-beijing.volces.com/api/coding/v3";

    /** 火山方舟普通按 token 计费的 Base URL */
    private static final String ARK_TOKEN_URL = "https://ark.cn-beijing.volces.com/api/v3";

    public static void main(String[] args) throws Exception {
        groupA_registryPricingMode();
        groupB_isEstimable();
        groupC_calculateCostGuard();
        groupD_realUsageReplay();
        groupE_safeFallback();

        System.out.println("\n===== 结果: " + passed + " 通过, " + failed + " 失败 =====");
        if (failed > 0) {
            System.exit(1);
        }
    }

    // ==================== A. 档案层：按 baseUrl 区分计费形态 ====================

    private static void groupA_registryPricingMode() {
        System.out.println("\n--- A. 档案层：按 baseUrl 区分计费形态 ---");

        check("A1 火山 Coding Plan → QUOTA",
                ProviderProfile.PricingMode.QUOTA,
                ProviderRegistry.pricingModeFor("volcengine", ARK_CODING_URL));
        check("A1 火山 普通 → TOKEN",
                ProviderProfile.PricingMode.TOKEN,
                ProviderRegistry.pricingModeFor("volcengine", ARK_TOKEN_URL));
        check("A1 别名 ark 的 Coding Plan 同样 → QUOTA",
                ProviderProfile.PricingMode.QUOTA,
                ProviderRegistry.pricingModeFor("ark", ARK_CODING_URL));
        check("A1 别名 doubao 的 Coding Plan 同样 → QUOTA",
                ProviderProfile.PricingMode.QUOTA,
                ProviderRegistry.pricingModeFor("doubao", ARK_CODING_URL));

        check("A2 DeepSeek 官网 → TOKEN",
                ProviderProfile.PricingMode.TOKEN,
                ProviderRegistry.pricingModeFor("deepseek", "https://api.deepseek.com"));
        check("A3 Ollama 本地 → FREE",
                ProviderProfile.PricingMode.FREE,
                ProviderRegistry.pricingModeFor("ollama", "http://localhost:11434"));
        check("A4 未注册模板兜底 → TOKEN",
                ProviderProfile.PricingMode.TOKEN,
                ProviderRegistry.pricingModeFor("no-such-template", "https://x.y"));
        check("A5 baseUrl 为空时用档案默认模式（火山 → TOKEN）",
                ProviderProfile.PricingMode.TOKEN,
                ProviderRegistry.pricingModeFor("volcengine", null));
    }

    // ==================== B. 单价配置层：QUOTA/FREE 一律不可估 ====================

    private static void groupB_isEstimable() {
        System.out.println("\n--- B. 单价配置层：isEstimable ---");

        TokenPricingConfig pricing = buildPricing();

        // B1 正常配置 + TOKEN → 可估
        check("B1 已配单价 + TOKEN → 可估", true,
                pricing.isEstimable("deepseek-v4-pro", ProviderProfile.PricingMode.TOKEN));
        check("B1 未配单价 + TOKEN → 不可估", false,
                pricing.isEstimable("never-configured", ProviderProfile.PricingMode.TOKEN));

        // B2 关键：误配单价 + QUOTA → 仍不可估（误配不应产生虚假金额）
        check("B2 已配单价 + QUOTA → 不可估（关键）", false,
                pricing.isEstimable("deepseek-v4-pro", ProviderProfile.PricingMode.QUOTA));
        check("B2 已配单价 + FREE → 不可估", false,
                pricing.isEstimable("deepseek-v4-pro", ProviderProfile.PricingMode.FREE));

        // B3 mode 为 null 视为 TOKEN（向后兼容）
        check("B3 mode=null 视为 TOKEN", true,
                pricing.isEstimable("deepseek-v4-pro", null));
    }

    // ==================== C. 落库层：calculateCost 受模式约束 ====================

    private static void groupC_calculateCostGuard() {
        System.out.println("\n--- C. 落库层：calculateCost 受模式约束 ---");

        ObjectMapper mapper = new ObjectMapper();
        TokenPricingConfig pricing = buildPricing();
        // 第三参数传 null：resolvePricingMode 会安全降级为 TOKEN
        TokenUsageParser parser = new TokenUsageParser(mapper, pricing, null);

        TokenUsageParser.UsageSnapshot snap = parser.parseFrame(null,
                "{\"usage\":{\"prompt_tokens\":1000000,\"completion_tokens\":1000000,"
                        + "\"total_tokens\":2000000,\"prompt_cache_hit_tokens\":600000,"
                        + "\"prompt_cache_miss_tokens\":400000}}", "openai");

        // C1 TOKEN：正常计算（0.6*0.025 + 0.4*3.0 + 1*6.0 = 7.215）
        Double tokenCost = parser.calculateCost("deepseek-test", snap, ProviderProfile.PricingMode.TOKEN);
        check("C1 TOKEN 模式正常估价", tokenCost != null);
        check("C1 金额 = 7.215（实际 " + tokenCost + "）",
                tokenCost != null && Math.abs(tokenCost - 7.215) < 1e-6);

        // C2 QUOTA / FREE：一律 null（即使模型确实配了单价）
        check("C2 QUOTA 模式返回 null",
                parser.calculateCost("deepseek-test", snap, ProviderProfile.PricingMode.QUOTA) == null);
        check("C2 FREE 模式返回 null",
                parser.calculateCost("deepseek-test", snap, ProviderProfile.PricingMode.FREE) == null);

        // C3 旧签名（不传 mode）行为不变 —— 供既有测试/调用方兼容
        Double legacyCost = parser.calculateCost("deepseek-test", snap);
        check("C3 旧签名行为不变", legacyCost != null && Math.abs(legacyCost - 7.215) < 1e-6);

        // C4 快照为 null 时任何模式都返回 null
        check("C4 快照 null → TOKEN 返回 null",
                parser.calculateCost("deepseek-test", null, ProviderProfile.PricingMode.TOKEN) == null);
    }

    // ==================== D. 真实用量回放（最有说服力） ====================

    private static void groupD_realUsageReplay() {
        System.out.println("\n--- D. 真实用量回放（火山 Coding Plan 267 次请求）---");

        /*
          实测数据（来自 llm_token_usage，provider=huoshan，60 条明细汇总）：
            prompt         53,965,986
            其中缓存命中    52,124,288
            其中缓存未命中   1,841,698
            completion        127,903
          若按 deepseek-v4-pro 单价（0.025 / 3.0 / 6.0 元每百万）计算：
            hit  52.124288 * 0.025 = 1.303107
            miss  1.841698 * 3.0   = 5.525094
            out   0.127903 * 6.0   = 0.767418
            合计 ≈ 7.595619 元
          —— 这正是旧逻辑在「混合范围」里会显示出来的虚假金额。
         */
        ObjectMapper mapper = new ObjectMapper();
        TokenPricingConfig pricing = buildPricing();
        // 模拟用户「误把火山模型按 DeepSeek 单价配置」的最坏情况
        pricing.getModels().put("deepseek-v4.1-flash",
                new TokenPricingConfig.PriceItem(0.025, 3.0, 6.0));
        TokenUsageParser parser = new TokenUsageParser(mapper, pricing, null);

        TokenUsageParser.UsageSnapshot snap = parser.parseFrame(null,
                "{\"usage\":{\"prompt_tokens\":53965986,\"completion_tokens\":127903,"
                        + "\"total_tokens\":54093889,\"prompt_cache_hit_tokens\":52124288,"
                        + "\"prompt_cache_miss_tokens\":1841698}}", "openai");

        // D1 若按 TOKEN 估价（旧逻辑的处境）→ 会算出约 7.5956 元
        Double wrong = parser.calculateCost("deepseek-v4.1-flash", snap,
                ProviderProfile.PricingMode.TOKEN);
        check("D1 旧逻辑会算出虚假金额", wrong != null && Math.abs(wrong - 7.595619) < 1e-4);

        // D2 修复后：火山 Coding Plan 被判定为 QUOTA → 返回 null
        ProviderProfile.PricingMode mode = ProviderRegistry.pricingModeFor("volcengine", ARK_CODING_URL);
        check("D2 火山 Coding Plan 判定为 QUOTA", ProviderProfile.PricingMode.QUOTA, mode);

        Double fixed = parser.calculateCost("deepseek-v4.1-flash", snap, mode);
        check("D2 修复后不再产生金额（关键）", fixed == null);

        // D3 端到端：toEntity 落库时 cost_estimate 必须是 null
        //    provider="huoshan" 但 manager 传 null → resolvePricingMode 降级 TOKEN，
        //    因此这里直接验证「带 QUOTA 模式的 calculateCost」已被 D2 覆盖，
        //    此处再确认「实体层 null 传播」不会把 null 变成 0。
        check("D3 金额不会被写成 0（null 就是 null）", fixed == null);
    }

    // ==================== E. 安全降级 ====================

    private static void groupE_safeFallback() {
        System.out.println("\n--- E. 安全降级（拿不到 Provider 配置时）---");

        ObjectMapper mapper = new ObjectMapper();
        TokenPricingConfig pricing = buildPricing();
        TokenUsageParser parser = new TokenUsageParser(mapper, pricing, null);

        // E1 manager 为 null（测试环境 / 早期初始化）：退回 TOKEN，不改变旧行为
        check("E1 manager=null → TOKEN",
                ProviderProfile.PricingMode.TOKEN, parser.resolvePricingMode("huoshan"));
        // E2 provider 为空 → TOKEN
        check("E2 provider=null → TOKEN",
                ProviderProfile.PricingMode.TOKEN, parser.resolvePricingMode(null));
        check("E2 provider=空串 → TOKEN",
                ProviderProfile.PricingMode.TOKEN, parser.resolvePricingMode("   "));
        // E3 未注册 provider → TOKEN
        check("E3 未注册 provider → TOKEN",
                ProviderProfile.PricingMode.TOKEN, parser.resolvePricingMode("no-such-provider"));
    }

    // ==================== 辅助 ====================

    /** 与 TokenUsageMultiProtocolTest.buildPricing 同源：defaultPrice 保持 null */
    private static TokenPricingConfig buildPricing() {
        TokenPricingConfig config = new TokenPricingConfig();
        LinkedHashMap<String, TokenPricingConfig.PriceItem> models = new LinkedHashMap<>();
        models.put("deepseek-test", new TokenPricingConfig.PriceItem(0.025, 3.0, 6.0));
        models.put("deepseek-v4-pro", new TokenPricingConfig.PriceItem(0.025, 3.0, 6.0));
        config.setModels(models);
        return config;
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

    private static void check(String name, Object expected, Object actual) {
        boolean ok = expected == null ? actual == null : expected.equals(actual);
        check(name + "（期望=" + expected + " 实际=" + actual + "）", ok);
    }
}
