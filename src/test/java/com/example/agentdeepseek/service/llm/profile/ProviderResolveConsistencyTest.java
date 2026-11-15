package com.example.agentdeepseek.service.llm.profile;

import com.example.agentdeepseek.model.entity.ProviderConfig;
import com.example.agentdeepseek.model.vo.ProviderCapabilityVO;
import com.example.agentdeepseek.service.llm.ProviderCapabilityService;
import com.example.agentdeepseek.service.llm.profile.ProviderProfile.AdapterType;
import com.example.agentdeepseek.service.llm.profile.ProviderProfile.AuthSpec;
import com.example.agentdeepseek.service.llm.profile.ProviderProfile.BalanceProvider;
import com.example.agentdeepseek.service.llm.profile.ProviderProfile.PricingMode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 统一性审查回归测试 —— 验证「同一个名字不该因字段位置不同而结论不同」。
 *
 * <h3>本测试要证明什么</h3>
 * <p>
 * 审查阶段一~四的重构时发现一处<b>系统性遗漏</b>：6 个消费方各自写同一段
 * 「template 为空才用 code」+ 精确查找，但 <b>都漏掉了前缀匹配</b>。
 * </p>
 * <p>
 * 触发条件很常见：后端 {@code LLMProviderController#create} 会把
 * {@code requestTemplate} 自动填成 {@code code}，所以用户把 code 起成
 * {@code volcengine-custom} / {@code deepseek-official} 时，template 与 code 是同一个
 * 「平台名在前缀」的名字，精确查找必然落空，于是：
 * </p>
 * <ul>
 *   <li>{@code createClient} → 回退 DeepSeekClient（对话走错协议）；</li>
 *   <li>{@code resolveAuth} → 退回 Authorization+Bearer（mimo/anthropic 必然 401）；</li>
 *   <li>{@code TokenUsageParser} → 退回 TOKEN（火山 Coding Plan 又被估出虚假金额）；</li>
 *   <li>能力接口说「可查询」但 {@code queryBalance} 说「不支持」（界面与实际打架）。</li>
 * </ul>
 *
 * <h3>运行方式</h3>
 * <pre>
 * mvn -q -DskipTests -DskipFrontend=true test-compile
 * mvn -q org.codehaus.mojo:exec-maven-plugin:3.1.0:java \
 *     -Dexec.mainClass=com.example.agentdeepseek.service.llm.profile.ProviderResolveConsistencyTest \
 *     -Dexec.classpathScope=test -DskipFrontend=true
 * </pre>
 */
public class ProviderResolveConsistencyTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final ProviderCapabilityService CAPABILITY_SERVICE =
            new ProviderCapabilityService(MAPPER);

    private static int passed = 0;
    private static int failed = 0;

    public static void main(String[] args) {
        System.out.println("========== 统一性审查回归测试 ==========");

        groupA_resolveFor();
        groupB_balanceCodeFallback();
        groupC_allConsumersAligned();
        groupD_filesApiStaysStrict();
        groupE_nullSafety();
        groupF_displayEqualsExecution();

        System.out.println("\n========== 结果: " + passed + " passed, " + failed + " failed ==========");
        if (failed > 0) {
            System.exit(1);
        }
    }

    // ==================== A. resolveFor：模板精确优先，code 前缀兜底 ====================

    private static void groupA_resolveFor() {
        System.out.println("\n--- A. resolveFor（模板精确优先 + code 前缀兜底）---");

        // A1 模板精确命中，不受 code 干扰
        ProviderProfile p1 = ProviderRegistry.resolveFor("volcengine", "my-weird-code");
        check("A1 模板精确命中（volcengine + 无关 code）",
                "volcengine", p1 == null ? null : p1.getTemplate());

        // A2 曾报错的场景：template 与 code 都是「平台名在前缀」的自定义名
        ProviderProfile p2 = ProviderRegistry.resolveFor("volcengine-custom", "volcengine-custom");
        check("A2 volcengine-custom 经 resolveFor 命中火山",
                "volcengine", p2 == null ? null : p2.getTemplate());
        check("A2 适配器 = VOLCENGINE（不再回退 DeepSeekClient）",
                AdapterType.VOLCENGINE, p2 == null ? null : p2.getAdapterType());

        // A3 关键：旧代码用 findByTemplate 会落空 —— 证明这个坑真实存在
        check("A3 对照：findByTemplate 对自定义名落空（旧实现的 bug 根源）",
                null, ProviderRegistry.findByTemplate("volcengine-custom"));
        check("A3 对照：findByCode 能命中（新入口正是补这一层）",
                "volcengine", ProviderRegistry.findByCode("volcengine-custom") == null
                        ? null : ProviderRegistry.findByCode("volcengine-custom").getTemplate());

        // A4 template 为空时用 code 兜底
        ProviderProfile p4 = ProviderRegistry.resolveFor(null, "deepseek-official");
        check("A4 template=null → 用 code 前缀命中 deepseek",
                "deepseek", p4 == null ? null : p4.getTemplate());
        ProviderProfile p4b = ProviderRegistry.resolveFor("  ", "mimo-prod");
        check("A4 template=空白 → 用 code 前缀命中 mimo",
                "mimo", p4b == null ? null : p4b.getTemplate());

        // A5 各平台的自定义命名（覆盖「平台名在前缀」的常见写法）
        //
        // 注意：前缀匹配只认档案的主名与别名，所以必须写成「平台名在前缀」才命中。
        // 例如 anthropic 的别名表里没有 claude（Claude 只是展示用 label），
        // 因此 "claude-proxy" 不会命中 —— 这不是 bug，而是前缀匹配的语义边界。
        String[][] aliasCases = {
                {"volcengine-custom", "volcengine"},
                {"ark-prod", "volcengine"},
                {"doubao-test", "volcengine"},
                {"deepseek-official", "deepseek"},
                {"qwen-dashscope", "qwen"},
                {"dashscope-pro", "qwen"},
                {"minimax-cn-prod", "minimax"},
                {"mimo-local", "mimo"},
                {"anthropic-proxy", "anthropic"},
                {"gpt-4o-gateway", "gpt"},
        };
        for (String[] c : aliasCases) {
            ProviderProfile p = ProviderRegistry.resolveFor(c[0], c[0]);
            check("A5[" + c[0] + "] → " + c[1], c[1], p == null ? null : p.getTemplate());
        }

        // A5b 语义边界：前缀匹配只认主名/别名，不认展示名（claude 只是 anthropic 的 label）
        check("A5b claude-proxy 不命中（claude 是展示名而非别名，符合预期）",
                null, ProviderRegistry.resolveFor("claude-proxy", "claude-proxy"));

        // A6 完全未知的名字仍返回 null（不能瞎猜）
        check("A6 完全未知 → null", null, ProviderRegistry.resolveFor("totally-unknown", "totally-unknown"));
        check("A6 两者皆空 → null", null, ProviderRegistry.resolveFor(null, null));
    }

    // ==================== B. 余额：code 兜底（界面与实际不能打架） ====================

    private static void groupB_balanceCodeFallback() {
        System.out.println("\n--- B. 余额 code 兜底（修「界面说能查、点下去说不支持」）---");

        // B1 旧逻辑：自定义名 + 中转域名 → 两层都落空 → NONE（这是 bug 现场）
        check("B1 对照：旧签名（仅 template+baseUrl）对自定义名判 NONE",
                null, ProviderRegistry.balanceCandidateFor(
                        "volcengine-custom", "https://proxy.example.com/v1"));

        // B2 新签名：code 兜底生效 → 认出火山
        ProviderProfile p2 = ProviderRegistry.balanceCandidateFor(
                "volcengine-custom", "volcengine-custom", "https://proxy.example.com/v1");
        check("B2 新签名 code 兜底 → 火山余额",
                BalanceProvider.VOLCENGINE_BILLING,
                p2 == null || p2.getBalance() == null ? null : p2.getBalance().getProvider());

        // B3 无余额能力的平台，code 兜底也不能凭空造出能力
        check("B3 gpt 自定义名 → 仍 NONE（不瞎猜）",
                null, ProviderRegistry.balanceCandidateFor("gpt-custom", "gpt-custom", "https://x.com"));
        check("B3 ollama 自定义名 → 仍 NONE", null,
                ProviderRegistry.balanceCandidateFor("ollama-local", "ollama-local", "http://localhost:11434"));

        // B4 关键：既成事实的顺序不能因为新分支改变
        //    「template=deepseek + 火山域名 → 走火山余额」这是历史行为，必须保持
        ProviderProfile p4 = ProviderRegistry.balanceCandidateFor(
                "deepseek", "deepseek", "https://ark.cn-beijing.volces.com/api/v3");
        check("B4 顺序陷阱保持：template=deepseek + 火山域名 → 火山余额",
                BalanceProvider.VOLCENGINE_BILLING,
                p4 == null || p4.getBalance() == null ? null : p4.getBalance().getProvider());

        // B5 关键：命中 template 时 code 兜底绝不参与（否则会把已正确的结论改写）
        ProviderProfile p5 = ProviderRegistry.balanceCandidateFor(
                "minimax", "deepseek-something", "");
        check("B5 template 已命中 → code 兜底不参与（仍 MiniMax）",
                BalanceProvider.MINIMAX_PLAN,
                p5 == null || p5.getBalance() == null ? null : p5.getBalance().getProvider());

        // B6 code 为 null / 空白 → 完全等价旧行为（保证向后兼容）
        check("B6 code=null → 与旧签名一致（deepseek）",
                BalanceProvider.DEEPSEEK_OFFICIAL,
                ProviderRegistry.balanceCandidateFor("deepseek", null, "")
                        .getBalance().getProvider());
        check("B6 code=空白 → 与旧签名一致（未知）",
                null, ProviderRegistry.balanceCandidateFor("unknown", "  ", ""));
    }

    // ==================== C. 所有消费方口径一致 ====================

    private static void groupC_allConsumersAligned() {
        System.out.println("\n--- C. 所有消费方口径一致（同一名字 → 同一结论）---");

        String customName = "volcengine-custom";
        ProviderConfig config = config(customName, customName,
                "https://ark.cn-beijing.volces.com/api/coding/v3", "sk-test", "{}", "deepseek-v4.1-flash");

        // C1 对话侧：适配器选型
        ProviderProfile dialogue = ProviderRegistry.resolveFor(config.getRequestTemplate(), config.getCode());
        check("C1 对话侧认出火山", "volcengine", dialogue == null ? null : dialogue.getTemplate());

        // C2 认证头侧
        AuthSpec auth = ProviderRegistry.resolveFor(config.getRequestTemplate(), config.getCode()).getAuth();
        check("C2 认证头 = Authorization", "Authorization", auth.getHeaderName());
        check("C2 前缀 = Bearer", "Bearer ", auth.getHeaderPrefix());

        // C3 计费侧：Coding Plan 必须判 QUOTA
        check("C3 计费模式 = QUOTA（否则会估出虚假金额）",
                PricingMode.QUOTA,
                dialogue == null ? null : dialogue.resolvePricingMode(config.getBaseUrl()));

        // C4 能力接口侧：视觉必须认得出 v4.1 支持图片（不能因自定义名退化为「未识别」）
        ProviderCapabilityVO cap = CAPABILITY_SERVICE.forProvider(config);
        check("C4 能力接口 label = 火山方舟（未退化为未识别模板）",
                "火山方舟", cap.getLabel());
        check("C4 能力接口 vision = on（v4.1-flash 支持图片）", "on", cap.getVision().getState());
        check("C4 能力接口 pricingMode = QUOTA", "QUOTA", cap.getPricingMode());
        check("C4 能力接口 balance = warn（缺 AK/SK，不是 off）", "warn", cap.getBalance().getState());
        check("C4 filesApi = warn（仅本地，非 off）", "warn", cap.getFilesApi().getState());

        // C5 余额执行侧：必须与 C4 的「warn（可配好）」一致，不能报「不支持」
        ProviderProfile balance = ProviderRegistry.balanceCandidateFor(
                config.getRequestTemplate(), config.getCode(), config.getBaseUrl());
        check("C5 余额执行侧认出火山（与能力接口一致）",
                BalanceProvider.VOLCENGINE_BILLING,
                balance == null || balance.getBalance() == null ? null : balance.getBalance().getProvider());
    }

    // ==================== D. Files API 刻意保持严格 ====================

    private static void groupD_filesApiStaysStrict() {
        System.out.println("\n--- D. Files API 刻意不用 resolveFor（数据外发必须更严）---");

        /*
          Files API 决定「用户文件是否上传到第三方云端」，属于数据外发动作。
          口径必须比对话更严：宁可不识别（降级本地存储），也不能靠前缀猜平台。
          这是 v3.1.4 定下的「用户没选就不许替他选」。
         */
        check("D1 对照：findByTemplate 对自定义名落空（严格语义）",
                null, ProviderRegistry.findByTemplate("deepseek-custom"));
        check("D2 而 resolveFor 能命中（对话/能力侧需要宽松识别）",
                "deepseek", ProviderRegistry.resolveFor("deepseek-custom", "deepseek-custom") == null
                        ? null : ProviderRegistry.resolveFor("deepseek-custom", "deepseek-custom").getTemplate());

        // D3 说明：FilesApiClientManager 用的是 findByTemplate（严格），因此自定义名走本地存储。
        //    这里只固化语义差异，不重复测试那个类的私有方法。
        check("D3 deepseek 标准名仍能精确命中 Files API",
                ProviderProfile.FilesApiProvider.DEEPSEEK,
                ProviderRegistry.findByTemplate("deepseek").getFilesApi().getProvider());
    }

    // ==================== E. 空值安全 ====================

    private static void groupE_nullSafety() {
        System.out.println("\n--- E. 空值安全（不能让日志分支打挂主链路）---");

        // E1 resolveFor 各种 null 组合都不抛异常
        try {
            ProviderRegistry.resolveFor(null, null);
            ProviderRegistry.resolveFor(null, "deepseek");
            ProviderRegistry.resolveFor("deepseek", null);
            ProviderRegistry.resolveFor("", "");
            check("E1 resolveFor 各种 null 组合不抛异常", true);
        } catch (Exception e) {
            check("E1 resolveFor 抛了 " + e.getClass().getSimpleName(), false);
        }

        // E2 balanceCandidateFor 新签名 null 安全
        try {
            ProviderRegistry.balanceCandidateFor(null, null, null);
            ProviderRegistry.balanceCandidateFor("", "", "");
            check("E2 balanceCandidateFor 各种 null 组合不抛异常", true);
        } catch (Exception e) {
            check("E2 balanceCandidateFor 抛了 " + e.getClass().getSimpleName(), false);
        }

        // E3 能力接口：template 与 code 都空 → 保守判未识别，不崩
        ProviderConfig blank = new ProviderConfig();
        blank.setBaseUrl("https://example.com");
        blank.setDefaultModel("some-model");
        try {
            ProviderCapabilityVO vo = CAPABILITY_SERVICE.forProvider(blank);
            check("E3 全空配置不抛异常", true, vo != null);
            check("E3 全空配置保守判 off", "off", vo.getVision().getState());
        } catch (Exception e) {
            check("E3 全空配置抛了 " + e.getClass().getSimpleName(), false);
        }

        // E4 大小写 / 前后空格不影响结论（用能前缀匹配的名字，精确名带空格也能 trim 命中）
        check("E4 大小写不敏感", "volcengine",
                ProviderRegistry.resolveFor("VOLCENGINE-CUSTOM", "VOLCENGINE-CUSTOM") == null
                        ? null : ProviderRegistry.resolveFor("VOLCENGINE-CUSTOM", "VOLCENGINE-CUSTOM").getTemplate());
        check("E4 前后空格不影响（前缀匹配路径）", "volcengine",
                ProviderRegistry.resolveFor("  volcengine-custom  ", "x") == null
                        ? null : ProviderRegistry.resolveFor("  volcengine-custom  ", "x").getTemplate());
        check("E4 前后空格不影响（精确匹配路径）", "deepseek",
                ProviderRegistry.resolveFor("  deepseek  ", "x") == null
                        ? null : ProviderRegistry.resolveFor("  deepseek  ", "x").getTemplate());
    }

    // ==================== F. 展示层 == 执行层（能力接口不能自己另算一遍） ====================

    /**
     * 本轮审查最核心的一组：能力接口（展示）与执行层必须给出同一个结论。
     *
     * <h3>为什么单独成组</h3>
     * <p>
     * 之前只统一了「解析档案」这一层（resolveFor），但**每个能力的具体判定**仍然各写一套：
     * </p>
     * <ul>
     *   <li>余额：展示用 {@code profile.getBalance()}（resolveFor，名字优先），
     *       执行用 {@code balanceCandidateFor}（平台顺序优先）→ template=deepseek + 火山域名会分歧；</li>
     *   <li>视觉：展示用 {@code config.getRequestTemplate()} 原始串，
     *       执行用「档案主名」→ code=my-gateway + 火山域名会分歧；</li>
     *   <li>Files API：展示用 resolveFor（带前缀兜底），执行用 findByTemplate（严格）
     *       → deepseek-custom 会分歧。</li>
     * </ul>
     * <p>
     * 共同后果都是「界面说的」与「实际做的」不一致，且都不报错 —— 正是本项目要根治的病灶。
     * </p>
     */
    private static void groupF_displayEqualsExecution() {
        System.out.println("\n--- F. 展示层 == 执行层（能力接口不能自己另算一遍）---");

        // ---------- F1 余额：template 命中与 host 命中冲突时必须与执行层一致 ----------
        //
        // 这是 balanceCandidateFor 的历史顺序陷阱（volcengine 排在 deepseek 之前）：
        // template=deepseek 但 baseUrl 指向火山 → 实际查的是火山账户。
        // 展示层若走 resolveFor（名字优先）就会显示成 DeepSeek 余额，两者都"可查询"但查的是不同的钱。
        ProviderConfig ambiguous = config("deepseek", "deepseek",
                "https://ark.cn-beijing.volces.com/api/v3", "sk-test", "{}", "deepseek-chat");

        ProviderProfile executed = ProviderRegistry.balanceCandidateFor(
                ambiguous.getRequestTemplate(), ambiguous.getCode(), ambiguous.getBaseUrl());
        check("F1 执行层：template=deepseek + 火山域名 → 火山余额",
                BalanceProvider.VOLCENGINE_BILLING,
                executed == null || executed.getBalance() == null
                        ? null : executed.getBalance().getProvider());

        ProviderCapabilityVO shown = CAPABILITY_SERVICE.forProvider(ambiguous);
        check("F1 展示层与执行层一致（火山，而非 DeepSeek）", "warn", shown.getBalance().getState());
        check("F1 展示层文案指向火山账号（AK/SK），而不是 DeepSeek API Key",
                true, containsAnyIgnoreCase(shown.getBalance().getHint(), "accessKey", "secretKey", "AK"));

        // 对照：正常配置（template 与域名一致）不应被影响
        ProviderConfig normal = config("deepseek", "deepseek",
                "https://api.deepseek.com", "sk-test", "{}", "deepseek-chat");
        check("F1 对照：正常 DeepSeek 配置 → on（有 API Key）", "on",
                CAPABILITY_SERVICE.forProvider(normal).getBalance().getState());

        // ---------- F2 视觉：展示用的键必须与执行侧 resolveVisionTemplateKey 同源 ----------
        //
        // 执行侧返回「档案主名」（如 volcengine），因此展示层也必须用档案主名。
        // 若展示层用 config.getRequestTemplate() 原始串（my-gateway），
        // findVisionRule 落空 → 宽松放行 → 显示 on，但实际发图会被拦截。
        ProviderConfig opaqueName = config("my-gateway", "my-gateway",
                "https://ark.cn-beijing.volces.com/api/v3", "sk-test", "{}", "deepseek-v4-pro");

        ProviderProfile resolved = ProviderRegistry.resolveFor(
                opaqueName.getRequestTemplate(), opaqueName.getCode(), opaqueName.getBaseUrl());
        check("F2 执行侧解析出火山档案（域名兜底）", "volcengine",
                resolved == null ? null : resolved.getTemplate());

        // 执行侧判定：档案主名 + v4-pro → 命中 exclude → 不支持
        check("F2 执行侧判定 v4-pro 不支持发图",
                false, com.example.agentdeepseek.service.files.FileVisionService
                        .supportsVisionModel(resolved == null ? null : resolved.getTemplate(), "deepseek-v4-pro"));

        // 展示侧必须同样判 warn（模型不支持），不能因为名字识别不出就显示 on
        ProviderCapabilityVO visionShown = CAPABILITY_SERVICE.forProvider(opaqueName);
        check("F2 展示层与执行层一致（warn，不是 on）", "warn", visionShown.getVision().getState());

        // 对照：同配置换成支持视觉的模型 → 两侧都应为 on
        ProviderConfig opaqueNameVision = config("my-gateway", "my-gateway",
                "https://ark.cn-beijing.volces.com/api/v3", "sk-test", "{}", "deepseek-v4.1-flash");
        check("F2 对照：v4.1-flash → 展示层 on", "on",
                CAPABILITY_SERVICE.forProvider(opaqueNameVision).getVision().getState());

        // ---------- F3 Files API：展示必须比视觉/余额更严（数据外发口径） ----------
        //
        // 执行层 FilesApiClientManager 用 findByTemplate 精确匹配。
        // 展示层若跟随 resolveFor，会把 deepseek-custom 说成「可上传 DeepSeek 云端」，
        // 而实际执行落空 → 只存本地。用户会以为云端有备份。
        ProviderConfig filesCustom = config("deepseek-custom", "deepseek-custom",
                "https://api.deepseek.com", "sk-test", "{}", "deepseek-chat");

        check("F3 执行层：findByTemplate 对 deepseek-custom 落空（→ 本地存储）",
                null, ProviderRegistry.findByTemplate("deepseek-custom"));

        ProviderCapabilityVO filesShown = CAPABILITY_SERVICE.forProvider(filesCustom);
        check("F3 展示层与执行层一致（仅本地 warn，不声称云端）", "warn",
                filesShown.getFilesApi().getState());
        check("F3 展示层文案声明不上传第三方云盘（与本地存储事实一致）", true,
                containsAnyIgnoreCase(filesShown.getFilesApi().getHint(), "仅存本地", "不会"));
        check("F3 展示层徽章不得出现「云端」二字（否则等于虚假承诺）", false,
                filesShown.getFilesApi().getLabel() != null && filesShown.getFilesApi().getLabel().contains("云端"));

        // 对照：标准名仍应显示云端
        ProviderConfig filesStd = config("deepseek", "deepseek",
                "https://api.deepseek.com", "sk-test", "{}", "deepseek-chat");
        check("F3 对照：标准 deepseek → 云端 on", "on",
                CAPABILITY_SERVICE.forProvider(filesStd).getFilesApi().getState());

        // ---------- F4 模板级预览不能被实例级判定污染 ----------
        //
        // 模板级（config == null）回答「平台具备什么」，不能因为「没有实例配置」
        // 就说「不支持余额查询」——那会让用户以为平台没这个功能。
        ProviderCapabilityVO volcPreview = findTemplatePreview("volcengine");
        check("F4 火山模板级预览 balance = warn（有接口，配好 AK/SK 可用）", "warn",
                volcPreview == null ? null : volcPreview.getBalance().getState());
        check("F4 火山模板级预览 filesApi = warn（仅本地）", "warn",
                volcPreview == null ? null : volcPreview.getFilesApi().getState());

        ProviderCapabilityVO dsPreview = findTemplatePreview("deepseek");
        check("F4 DeepSeek 模板级预览 filesApi = on（平台适配了云端）", "on",
                dsPreview == null ? null : dsPreview.getFilesApi().getState());

        // ---------- F5 matches() 大小写不敏感（与 allNames() 同口径） ----------
        ProviderProfile volc = ProviderRegistry.findByTemplate("volcengine");
        check("F5 matches 对大小写不敏感（Volcengine）", true, volc != null && volc.matches("Volcengine"));
        check("F5 matches 对前后空格不敏感", true, volc != null && volc.matches("  ARK  "));
        check("F5 matches 不误命中其它平台", false, volc != null && volc.matches("deepseek"));
    }

    /** 从模板级列表里取某个模板的预览（找不到返回 null） */
    private static ProviderCapabilityVO findTemplatePreview(String template) {
        for (ProviderCapabilityVO vo : CAPABILITY_SERVICE.templates()) {
            if (template.equalsIgnoreCase(vo.getTemplate())) {
                return vo;
            }
        }
        return null;
    }

    private static boolean containsAnyIgnoreCase(String text, String... keys) {
        if (text == null) {
            return false;
        }
        String lower = text.toLowerCase();
        for (String key : keys) {
            if (key != null && lower.contains(key.toLowerCase())) {
                return true;
            }
        }
        return false;
    }

    // ==================== 辅助 ====================

    private static ProviderConfig config(String code, String template, String baseUrl,
                                         String apiKey, String extraConfig, String defaultModel) {
        ProviderConfig c = new ProviderConfig();
        c.setCode(code);
        c.setRequestTemplate(template);
        c.setBaseUrl(baseUrl);
        c.setApiKey(apiKey);
        c.setExtraConfig(extraConfig);
        c.setDefaultModel(defaultModel);
        return c;
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
