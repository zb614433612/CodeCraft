package com.example.agentdeepseek.service.llm.profile;

import com.example.agentdeepseek.service.files.FileVisionService;
import com.example.agentdeepseek.service.llm.ModelCapabilityRules;
import com.example.agentdeepseek.service.llm.profile.ProviderProfile.AdapterType;
import com.example.agentdeepseek.service.llm.profile.ProviderProfile.BalanceProvider;
import com.example.agentdeepseek.service.llm.profile.ProviderProfile.FilesApiProvider;
import com.example.agentdeepseek.service.llm.profile.ProviderProfile.PricingMode;
import com.example.agentdeepseek.service.llm.profile.ProviderProfile.SearchProvider;
import com.example.agentdeepseek.service.llm.profile.ProviderProfile.UsageProtocols;
import com.example.agentdeepseek.service.llm.profile.ProviderProfile.VisionSpec;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * {@code ProviderProfile} + {@code ProviderRegistry} 的自检与<b>等价性</b>验证（main 方法，不依赖 JUnit / 网络）。
 *
 * <h3>为什么必须验「等价性」而不是「正确性」</h3>
 * <p>
 * 阶段一（T1~T3）的验收标准<b>不是</b>「档案写得漂亮」，而是
 * <b>「同一输入下，查档案的结论与现有 if 链 / 现有配置的结论逐条相同」</b>。
 * 只要有一条不同，后续 T4~T8 把逻辑改为查表时就会<b>悄悄改变线上行为</b>。
 * </p>
 *
 * <h3>覆盖点</h3>
 * <ol>
 *   <li><b>A 组</b>：{@code selfCheck()} 分类断言 —— 档案内部问题必须为 0；
 *       适配器漂移只允许<b>已知的 4 条</b>（多一条就是新增漂移，必须处理）。</li>
 *   <li><b>B 组</b>：视觉规则内容等价 —— {@code ProviderRegistry.visionRuleFor(code)}
 *       与 {@code ModelCapabilityRules.findVisionRule(code)} 的
 *       include / exclude / excludePrefixes / defaultAllow 完全一致。</li>
 *   <li><b>C 组</b>：视觉<b>判定结果</b>等价 —— 对 13 个 code × 30+ 模型名的矩阵，
 *       用档案规则复算的结果必须与 {@code FileVisionService.supportsVisionModel} 相同。</li>
 *   <li><b>D 组</b>：其余能力字段表驱动断言（usage 协议 / 认证头 / Files API / 余额 / 搜索 / 计费模式）。</li>
 *   <li><b>E 组</b>：别名唯一性与前缀匹配优先级（{@code minimax} 与 {@code mimo} 别串味）。</li>
 * </ol>
 *
 * <h3>运行方式</h3>
 * <pre>
 * mvn -q -DskipTests -DskipFrontend=true test-compile
 * mvn -q org.codehaus.mojo:exec-maven-plugin:3.1.0:java \
 *     -Dexec.mainClass=com.example.agentdeepseek.service.llm.profile.ProviderRegistrySelfCheck \
 *     -Dexec.classpathScope=test -DskipFrontend=true
 * </pre>
 */
public class ProviderRegistrySelfCheck {

    private static int passed = 0;
    private static int failed = 0;

    /**
     * T12 之前存在的 4 条适配器漂移（档案声明了别名，但 createClient 没有对应 case）。
     *
     * <p>⭐ 现在清单<b>必须为空</b>：适配器选型已改为读档案的 {@code adapterType}，
     * 别名与实现同源，漂移在结构上不可能发生。
     * 若这里又出现条目，说明有人把 {@code createClient} 改回了本地 switch —— 属于回归。</p>
     */
    private static final List<String> KNOWN_ADAPTER_DRIFTS = List.of();

    /** T12 修复前真实存在的漂移别名（用于验证「它们现在能解析到正确适配器」） */
    private static final List<String> FORMER_DRIFT_ALIASES = List.of(
            "minimax-cn", "minimax-intl", "minimax-global", "xiaomi-mimo"
    );

    public static void main(String[] args) {
        System.out.println("========== ProviderRegistry 自检 ==========");

        groupA_selfCheck();
        groupB_visionRuleEquivalence();
        groupC_visionDecisionEquivalence();
        groupD_capabilityFields();
        groupE_aliasUniqueness();

        printCapabilityMatrix();

        System.out.println("\n========== 结果: " + passed + " passed, " + failed + " failed ==========");
        if (failed > 0) {
            System.exit(1);
        }
    }

    // ==================== A. selfCheck 分类断言 ====================

    private static void groupA_selfCheck() {
        System.out.println("\n--- A. 静态自检 ---");

        List<String> problems = ProviderRegistry.selfCheck();
        List<String> drifts = new ArrayList<>();
        List<String> internal = new ArrayList<>();
        for (String p : problems) {
            if (p.contains("没有对应 case")) {
                drifts.add(p);
            } else {
                internal.add(p);
            }
        }

        // A1：档案自身必须干净（必填字段 / 别名冲突 / 视觉规则完整性）
        check("A1 档案内部问题数", 0, internal.size());
        for (String p : internal) {
            System.out.println("   [内部问题] " + p);
        }

        // A2：适配器漂移必须为 0（T12 后已根治，出现即回归）
        check("A2 适配器漂移条数（T12 后应为 0）", KNOWN_ADAPTER_DRIFTS.size(), drifts.size());
        for (String p : drifts) {
            System.out.println("   [漂移回归] " + p);
        }

        // A2b：T12 前漂移过的 4 个别名，现在必须都能解析到正确适配器（不能掉进 default）
        for (String alias : FORMER_DRIFT_ALIASES) {
            AdapterType type = ProviderRegistry.adapterTypeFor(alias);
            check("A2b 曾漂移别名可解析适配器: " + alias, true, type != null);
        }
        // 逐条验证解析结果正确（而非仅仅非空）
        check("A2b xiaomi-mimo → MIMO", AdapterType.MIMO, ProviderRegistry.adapterTypeFor("xiaomi-mimo"));
        check("A2b minimax-cn → MINIMAX", AdapterType.MINIMAX, ProviderRegistry.adapterTypeFor("minimax-cn"));
        check("A2b minimax-intl → MINIMAX", AdapterType.MINIMAX, ProviderRegistry.adapterTypeFor("minimax-intl"));
        check("A2b minimax-global → MINIMAX", AdapterType.MINIMAX, ProviderRegistry.adapterTypeFor("minimax-global"));

        // A3：10 个内置档案（9 个适配器 + custom）必须全部在表
        check("A3 档案总数", 10, ProviderRegistry.all().size());
    }

    // ==================== B. 视觉规则内容等价 ====================

    private static void groupB_visionRuleEquivalence() {
        System.out.println("\n--- B. 视觉规则内容等价（Registry vs ModelCapabilityRules）---");

        // 覆盖：5 个带规则的主名 + 8 个别名 + 2 个区域别名 + 3 个自定义前缀 code + 2 个无规则 code
        String[] codes = {
                "deepseek", "volcengine", "ark", "doubao", "mimo", "xiaomi-mimo",
                "minimax", "minimax-cn", "qwen", "dashscope", "tongyi",
                "volc-deepseek", "ark-custom", "my-qwen-proxy",
                "gpt", "openai", "anthropic", "ollama", "custom", "unknown-provider"
        };

        for (String code : codes) {
            VisionSpec fromRegistry = ProviderRegistry.visionRuleFor(code);
            com.example.agentdeepseek.config.ModelCapabilityConfig.VisionRule fromLegacy =
                    ModelCapabilityRules.findVisionRule(code);

            // 命中/未命中必须一致（这是最关键的：一边返回规则、一边返回 null 就会改变行为）
            check("B 规则命中一致性 [" + code + "]",
                    fromLegacy != null, fromRegistry != null);

            if (fromLegacy != null && fromRegistry != null) {
                check("B include [" + code + "]",
                        fromLegacy.getIncludeKeywords(), fromRegistry.getIncludeKeywords());
                check("B exclude [" + code + "]",
                        fromLegacy.getExcludeKeywords(), fromRegistry.getExcludeKeywords());
                check("B excludePrefixes [" + code + "]",
                        fromLegacy.getExcludePrefixes(), fromRegistry.getExcludePrefixes());
                check("B defaultAllow [" + code + "]",
                        fromLegacy.isDefaultAllow(), fromRegistry.isDefaultAllow());
            }
        }
    }

    // ==================== C. 视觉判定结果等价 ====================

    private static void groupC_visionDecisionEquivalence() {
        System.out.println("\n--- C. 视觉判定结果等价（档案规则复算 vs FileVisionService）---");

        // 每组：code → 模型名 → 期望（期望值同时用于校验两边一致，任一边不符即失败）
        String[][] cases = {
                // deepseek 官方
                {"deepseek", "deepseek-chat", "false"},
                {"deepseek", "deepseek-reasoner", "false"},
                {"deepseek", "deepseek-v4-flash", "true"},
                {"deepseek", "deepseek-vl2", "true"},
                {"deepseek", "deepseek-vision-preview", "true"},
                {"deepseek", "", "true"},
                // 火山方舟（v4.1 放行 / v4-pro·v4-flash 拦截 / 其余 defaultAllow）
                {"volcengine", "deepseek-v4.1-flash", "true"},
                {"volcengine", "deepseek-v4.1-flash-economy", "true"},
                {"volcengine", "deepseek-v4-pro", "false"},
                {"volcengine", "deepseek-v4-flash", "false"},
                {"volcengine", "doubao-seed-1-6-250615", "true"},
                {"volcengine", "glm-5.3-flash", "true"},
                {"volcengine", "kimi-k3.1-pro", "true"},
                {"ark", "deepseek-v4.1-flash", "true"},
                {"doubao", "deepseek-v4-pro", "false"},
                // 千问（plus 已放行；qwen-max 不放行）
                {"qwen", "qwen-max", "false"},
                {"qwen", "qwen-plus", "true"},
                {"qwen", "qwen3.6-plus", "true"},
                {"qwen", "qwen3-vl-plus", "true"},
                {"qwen", "qwen-omni-turbo", "true"},
                {"dashscope", "qwen-plus", "true"},
                {"tongyi", "qwen-max", "false"},
                // MiniMax（M2.x 纯文本 / M3 与 abab 多模态）
                {"minimax", "MiniMax-M2", "false"},
                {"minimax", "MiniMax-M2.5", "false"},
                {"minimax", "m2", "false"},
                {"minimax", "MiniMax-M3", "true"},
                {"minimax", "abab6.5-vl", "true"},
                {"minimax-cn", "MiniMax-M2", "false"},
                // MiMo（v2.5 / omni 多模态）
                {"mimo", "mimo-v2", "false"},
                {"mimo", "mimo-v2.5", "true"},
                {"mimo", "mimo-omni", "true"},
                {"xiaomi-mimo", "mimo-v2.5", "true"},
                // 无规则 / 未知 code：宽松放行（与 defaultAllowVision=true 一致）
                {"gpt", "gpt-4o", "true"},
                {"openai", "any-model", "true"},
                {"anthropic", "claude-3-5-sonnet", "true"},
                {"unknown-provider", "whatever", "true"},
        };

        for (String[] c : cases) {
            String code = c[0];
            String model = c[1];
            boolean expected = Boolean.parseBoolean(c[2]);

            boolean legacy = FileVisionService.supportsVisionModel(code, model);
            boolean byRegistry = supportsVisionByRegistry(code, model);

            check("C 期望一致 [" + code + " / " + model + "]", expected, legacy);
            check("C 实现等价 [" + code + " / " + model + "]", legacy, byRegistry);
        }
    }

    /**
     * 用「档案规则 + 现有算法」复算视觉判定 —— 这是 T5 迁移后 {@code supportsVisionModel} 应有的行为。
     *
     * <p>算法与 {@code ModelCapabilityRules.supportsVisionModel} 完全一致：
     * 空模型放行 → 排除项优先 → include 命中放行 → 规则 defaultAllow → 无规则时全局 defaultAllowVision。</p>
     */
    private static boolean supportsVisionByRegistry(String code, String model) {
        if (model == null || model.isBlank()) {
            return true;
        }
        String m = model.trim().toLowerCase(Locale.ROOT);
        VisionSpec rule = ProviderRegistry.visionRuleFor(code);
        if (rule == null) {
            // 无专门规则 → 全局 defaultAllowVision（现状默认 true）
            return true;
        }
        if (containsAny(m, rule.getExcludeKeywords()) || startsWithAny(m, rule.getExcludePrefixes())) {
            return false;
        }
        if (containsAny(m, rule.getIncludeKeywords())) {
            return true;
        }
        return rule.isDefaultAllow();
    }

    // ==================== D. 其余能力字段 ====================

    private static void groupD_capabilityFields() {
        System.out.println("\n--- D. 能力字段表驱动断言 ---");

        // D1: usage 协议
        check("D1 usage deepseek", UsageProtocols.OPENAI, ProviderRegistry.usageProtocolFor("deepseek"));
        check("D1 usage anthropic", UsageProtocols.ANTHROPIC, ProviderRegistry.usageProtocolFor("anthropic"));
        check("D1 usage ollama", UsageProtocols.OLLAMA, ProviderRegistry.usageProtocolFor("ollama"));
        check("D1 usage 未注册兜底 openai", UsageProtocols.OPENAI, ProviderRegistry.usageProtocolFor("no-such"));

        // D2: 认证头（含「无前缀发裸 key」这一易错点）
        check("D2 auth deepseek 头名", "Authorization", ProviderRegistry.authFor("deepseek").getHeaderName());
        check("D2 auth deepseek 前缀", "Bearer ", ProviderRegistry.authFor("deepseek").getHeaderPrefix());
        check("D2 auth anthropic 头名", "x-api-key", ProviderRegistry.authFor("anthropic").getHeaderName());
        check("D2 auth anthropic 前缀", null, ProviderRegistry.authFor("anthropic").getHeaderPrefix());
        check("D2 auth mimo 头名", "api-key", ProviderRegistry.authFor("mimo").getHeaderName());
        check("D2 auth mimo 前缀", null, ProviderRegistry.authFor("mimo").getHeaderPrefix());
        check("D2 auth ollama 头名", "Authorization", ProviderRegistry.authFor("ollama").getHeaderName());
        check("D2 auth ollama 前缀", null, ProviderRegistry.authFor("ollama").getHeaderPrefix());
        check("D2 auth custom 头名", "Authorization", ProviderRegistry.authFor("custom").getHeaderName());
        check("D2 auth custom 前缀", null, ProviderRegistry.authFor("custom").getHeaderPrefix());
        check("D2 auth gpt 前缀", "Bearer ", ProviderRegistry.authFor("gpt").getHeaderPrefix());

        // D3: Files API（现状只有 deepseek / anthropic 适配）
        check("D3 files deepseek", FilesApiProvider.DEEPSEEK,
                ProviderRegistry.filesApiFor("deepseek").getProvider());
        check("D3 files anthropic", FilesApiProvider.ANTHROPIC,
                ProviderRegistry.filesApiFor("anthropic").getProvider());
        for (String t : List.of("volcengine", "ark", "doubao", "qwen", "minimax", "mimo",
                "gpt", "openai", "ollama", "custom")) {
            check("D3 files 未适配 [" + t + "]", FilesApiProvider.NONE,
                    ProviderRegistry.filesApiFor(t).getProvider());
        }
        check("D3 files 未注册兜底", false, ProviderRegistry.filesApiFor("no-such").isSupported());

        // D4: 余额
        check("D4 balance deepseek", BalanceProvider.DEEPSEEK_OFFICIAL,
                ProviderRegistry.balanceFor("deepseek").getProvider());
        check("D4 balance deepseek 仅需 API Key", true,
                ProviderRegistry.balanceFor("deepseek").isApiKeyOnly());
        check("D4 balance 火山", BalanceProvider.VOLCENGINE_BILLING,
                ProviderRegistry.balanceFor("volcengine").getProvider());
        check("D4 balance 千问", BalanceProvider.ALIYUN_BILLING,
                ProviderRegistry.balanceFor("qwen").getProvider());
        check("D4 balance MiniMax", BalanceProvider.MINIMAX_PLAN,
                ProviderRegistry.balanceFor("minimax").getProvider());
        check("D4 balance MiMo", BalanceProvider.MIMO_COOKIE,
                ProviderRegistry.balanceFor("mimo").getProvider());
        for (String t : List.of("gpt", "openai", "anthropic", "ollama", "custom")) {
            check("D4 balance 不支持 [" + t + "]", BalanceProvider.NONE,
                    ProviderRegistry.balanceFor(t).getProvider());
        }

        // D5: 官方搜索
        check("D5 search 火山", SearchProvider.VOLCENGINE,
                ProviderRegistry.searchFor("volcengine").getProvider());
        check("D5 search DeepSeek 官方", SearchProvider.DEEPSEEK_OFFICIAL,
                ProviderRegistry.searchFor("deepseek").getProvider());
        check("D5 search DeepSeek 要求官方域名", "api.deepseek.com",
                ProviderRegistry.searchFor("deepseek").getRequiredHostExact());
        check("D5 search MiniMax", SearchProvider.MINIMAX_PLAN,
                ProviderRegistry.searchFor("minimax").getProvider());
        check("D5 search 千问", SearchProvider.QWEN_DIALOG,
                ProviderRegistry.searchFor("qwen").getProvider());
        for (String t : List.of("gpt", "openai", "anthropic", "ollama", "mimo", "custom")) {
            check("D5 search 无官方接口 [" + t + "]", SearchProvider.NONE,
                    ProviderRegistry.searchFor(t).getProvider());
        }

        // D6: 计费模式（火山按 baseUrl 区分两种形态 —— 上一轮 bug 的根治点）
        check("D6 pricing deepseek", PricingMode.TOKEN,
                ProviderRegistry.pricingModeFor("deepseek", "https://api.deepseek.com"));
        check("D6 pricing ollama 免费", PricingMode.FREE,
                ProviderRegistry.pricingModeFor("ollama", "http://localhost:11434"));
        check("D6 pricing 火山普通(按 token)", PricingMode.TOKEN,
                ProviderRegistry.pricingModeFor("volcengine", "https://ark.cn-beijing.volces.com/api/v3"));
        check("D6 pricing 火山 CodingPlan(套餐)", PricingMode.QUOTA,
                ProviderRegistry.pricingModeFor("volcengine",
                        "https://ark.cn-beijing.volces.com/api/coding/v3"));
        check("D6 pricing 火山 CodingPlan 别名 ark", PricingMode.QUOTA,
                ProviderRegistry.pricingModeFor("ark", "https://ark.cn-beijing.volces.com/api/coding/v3"));
        check("D6 pricing 未注册兜底 TOKEN", PricingMode.TOKEN,
                ProviderRegistry.pricingModeFor("no-such", "https://x.y"));

        // D7: 主机兜底识别
        check("D7 host deepseek 官方", true,
                ProviderRegistry.findByTemplate("deepseek").getHostDetection()
                        .matchesHost("api.deepseek.com"));
        check("D7 host deepseek 中转不命中", false,
                ProviderRegistry.findByTemplate("deepseek").getHostDetection()
                        .matchesHost("proxy.example.com"));
        check("D7 host 火山", true,
                ProviderRegistry.findByTemplate("volcengine").getHostDetection()
                        .matchesHost("ark.cn-beijing.volces.com"));
        check("D7 host 阿里云", true,
                ProviderRegistry.findByTemplate("qwen").getHostDetection()
                        .matchesHost("dashscope.aliyuncs.com"));
        check("D7 host MiniMax 国内", true,
                ProviderRegistry.findByTemplate("minimax").getHostDetection()
                        .matchesHost("api.minimaxi.com"));
        check("D7 host MiniMax 国际", true,
                ProviderRegistry.findByTemplate("minimax").getHostDetection()
                        .matchesHost("api.minimax.io"));
        check("D7 host MiMo", true,
                ProviderRegistry.findByTemplate("mimo").getHostDetection()
                        .matchesHost("platform.xiaomimimo.com"));
    }

    // ==================== E. 别名唯一性 / 前缀匹配 ====================

    private static void groupE_aliasUniqueness() {
        System.out.println("\n--- E. 别名与前缀匹配 ---");

        // E1: 精确/别名命中
        check("E1 ark → volcengine", "volcengine", ProviderRegistry.findByTemplate("ark").getTemplate());
        check("E1 doubao → volcengine", "volcengine", ProviderRegistry.findByTemplate("doubao").getTemplate());
        check("E1 dashscope → qwen", "qwen", ProviderRegistry.findByTemplate("dashscope").getTemplate());
        check("E1 tongyi → qwen", "qwen", ProviderRegistry.findByTemplate("tongyi").getTemplate());
        check("E1 xiaomi-mimo → mimo", "mimo", ProviderRegistry.findByTemplate("xiaomi-mimo").getTemplate());
        // 大小写不敏感
        check("E1 大小写不敏感 ARK", "volcengine", ProviderRegistry.findByTemplate("ARK").getTemplate());
        check("E1 未注册返回 null", null, ProviderRegistry.findByTemplate("no-such"));

        // E2: 前缀匹配（自定义 code 仍要能识别出平台），且 minimax / mimo 不能串味
        check("E2 minimax-cn 前缀 → minimax", "minimax",
                ProviderRegistry.findByCode("minimax-cn").getTemplate());
        check("E2 minimax-global 前缀 → minimax", "minimax",
                ProviderRegistry.findByCode("minimax-global").getTemplate());
        check("E2 xiaomi-mimo 前缀 → mimo", "mimo",
                ProviderRegistry.findByCode("xiaomi-mimo").getTemplate());
        check("E2 volcengine-custom 前缀 → volcengine", "volcengine",
                ProviderRegistry.findByCode("volcengine-custom").getTemplate());
        check("E2 ark-custom 前缀 → volcengine", "volcengine",
                ProviderRegistry.findByCode("ark-custom").getTemplate());
        check("E2 关键：minimax 不会被误判成 mimo", "minimax",
                ProviderRegistry.findByCode("minimax").getTemplate());
        check("E2 关键：mimo 不会被误判成 minimax", "mimo",
                ProviderRegistry.findByCode("mimo").getTemplate());
        // 现状局限：只做 startsWith 前缀匹配，所以 "volc-deepseek" / "my-qwen-proxy" 这类
        // 「平台名在中间/末尾」的自定义 code 识别不出来 —— 这与 ModelCapabilityRules 现状一致，
        // 本阶段如实保持，不做"优化"（改成 contains 会改变线上行为）。
        check("E2 现状局限：volc-deepseek 不命中", null,
                ProviderRegistry.findByCode("volc-deepseek"));
        check("E2 现状局限：my-qwen-proxy 不命中", null,
                ProviderRegistry.findByCode("my-qwen-proxy"));
        check("E2 未知 code 返回 null", null, ProviderRegistry.findByCode("unknown-provider"));

        // E3: 视觉规则只对「有模型级规则」的 Provider 返回非 null
        check("E3 gpt 无模型级规则", null, ProviderRegistry.visionRuleFor("gpt"));
        check("E3 openai 无模型级规则", null, ProviderRegistry.visionRuleFor("openai"));
        check("E3 anthropic 无模型级规则", null, ProviderRegistry.visionRuleFor("anthropic"));
        check("E3 ollama 无模型级规则(后端宽松放行)", null, ProviderRegistry.visionRuleFor("ollama"));
        check("E3 custom 无模型级规则", null, ProviderRegistry.visionRuleFor("custom"));
        check("E3 deepseek 有模型级规则", true,
                ProviderRegistry.visionRuleFor("deepseek") != null);
    }

    // ==================== 能力总表输出 ====================

    private static void printCapabilityMatrix() {
        System.out.println("\n--- 能力总表（按界面排序）---");
        System.out.printf("%-12s %-14s %-8s %-6s %-10s %-18s %-15s %-10s%n",
                "template", "aliases", "usage", "vision", "filesApi", "balance", "search", "pricing");
        for (ProviderProfile p : ProviderRegistry.orderedByDisplay()) {
            String aliases = p.getAliases() == null || p.getAliases().isEmpty()
                    ? "-" : String.join("/", p.getAliases());
            String vision = p.getVision() != null && p.getVision().isHasModelRule() ? "模型级规则" : "宽松放行";
            System.out.printf("%-12s %-14s %-8s %-6s %-10s %-18s %-15s %-10s%n",
                    p.getTemplate(),
                    aliases,
                    p.getUsageProtocol(),
                    vision,
                    p.getFilesApi().getProvider(),
                    p.getBalance().getProvider(),
                    p.getSearch().getProvider(),
                    p.getPricingMode());
        }
        System.out.println("（火山 volcengine 的 pricing 在 /api/coding 地址下会改判为 QUOTA）");
    }

    // ==================== 断言工具 ====================

    private static void check(String label, Object expected, Object actual) {
        boolean ok;
        if (expected instanceof List<?> && actual instanceof List<?>) {
            ok = Objects.equals(expected, actual);
        } else {
            ok = Objects.equals(
                    expected == null ? null : String.valueOf(expected),
                    actual == null ? null : String.valueOf(actual));
        }
        if (ok) {
            passed++;
        } else {
            failed++;
            System.out.println("   FAIL " + label + " | 期望=" + expected + " 实际=" + actual);
        }
    }

    private static boolean containsAny(String text, List<String> keywords) {
        if (keywords == null || keywords.isEmpty()) {
            return false;
        }
        for (String kw : keywords) {
            if (kw != null && !kw.isBlank() && text.contains(kw.trim().toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private static boolean startsWithAny(String text, List<String> prefixes) {
        if (prefixes == null || prefixes.isEmpty()) {
            return false;
        }
        for (String pfx : prefixes) {
            if (pfx != null && !pfx.isBlank() && text.startsWith(pfx.trim().toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    /** 避免 Map 未使用告警（本类保留 Map 引用以便后续扩展表驱动断言） */
    @SuppressWarnings("unused")
    private static Map<String, String> reserved() {
        return Map.of();
    }
}
