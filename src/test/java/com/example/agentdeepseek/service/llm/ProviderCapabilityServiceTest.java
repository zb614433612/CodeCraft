package com.example.agentdeepseek.service.llm;

import com.example.agentdeepseek.model.entity.ProviderConfig;
import com.example.agentdeepseek.model.vo.ProviderCapabilityVO;
import com.example.agentdeepseek.service.files.FileVisionService;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Provider 能力判定测试（main 方法式，不依赖 Spring / 网络）。
 *
 * <h3>要证明什么</h3>
 * <p>
 * T10 之后前端不再自己推导能力，而是<b>消费本服务下发的结论</b>。
 * 因此这里必须证明三件事，否则前端迁移后会出现「界面说能发、实际被拦」：
 * </p>
 * <ol>
 *   <li><b>A 视觉</b>：结论必须与后端实际拦截逻辑（{@code FileVisionService.supportsVisionModel}）一致；</li>
 *   <li><b>B 搜索</b>：能区分「平台没有官方接口」(off) 与「有但当前配置用不了」(warn)；</li>
 *   <li><b>C 余额</b>：凭证必须<b>成对</b>才算就绪（修掉旧前端「任一有就算好」的缺陷）；</li>
 *   <li><b>D 计费</b>：火山按 baseUrl 区分 TOKEN / QUOTA；</li>
 *   <li><b>E 边界</b>：未知模板不能假装支持任何能力。</li>
 * </ol>
 *
 * <h3>运行方式</h3>
 * <pre>
 * mvn -q -DskipTests -DskipFrontend=true test-compile
 * mvn -q org.codehaus.mojo:exec-maven-plugin:3.1.0:java \
 *     -Dexec.mainClass=com.example.agentdeepseek.service.llm.ProviderCapabilityServiceTest \
 *     -Dexec.classpathScope=test -DskipFrontend=true
 * </pre>
 */
public class ProviderCapabilityServiceTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final ProviderCapabilityService SERVICE = new ProviderCapabilityService(MAPPER);

    private static int passed = 0;
    private static int failed = 0;

    public static void main(String[] args) {
        groupA_templateLevel();
        groupB_vision();
        groupC_search();
        groupD_balance();
        groupE_filesAndPricing();
        groupF_boundaries();

        System.out.println("\n===== 结果: " + passed + " 通过, " + failed + " 失败 =====");
        if (failed > 0) {
            System.exit(1);
        }
    }

    // ==================== A. 模板级档案 ====================

    private static void groupA_templateLevel() {
        System.out.println("\n--- A. 模板级档案 ---");

        List<ProviderCapabilityVO> templates = SERVICE.templates();
        check("A 模板数量（10 个档案全部可选）", 10, templates.size());

        // 每个模板都必须有中文标签与排序（前端下拉直接用，不再本地维护）
        for (ProviderCapabilityVO vo : templates) {
            check("A 模板 [" + vo.getTemplate() + "] 有中文标签",
                    true, vo.getLabel() != null && !vo.getLabel().isBlank());
            check("A 模板 [" + vo.getTemplate() + "] 有能力四项",
                    true, vo.getVision() != null && vo.getSearch() != null
                            && vo.getBalance() != null && vo.getFilesApi() != null);
        }

        // 排序必须单调递增（前端下拉按此顺序展示）
        int prev = Integer.MIN_VALUE;
        boolean ordered = true;
        for (ProviderCapabilityVO vo : templates) {
            if (vo.getOrder() < prev) {
                ordered = false;
                break;
            }
            prev = vo.getOrder();
        }
        check("A 模板按 order 升序返回", true, ordered);

        // 关键标签抽查（前端原先是硬编码 map，现在由后端给）
        check("A volcengine 标签", "火山方舟", labelOf(templates, "volcengine"));
        check("A deepseek 标签", "DeepSeek", labelOf(templates, "deepseek"));
        check("A anthropic 标签", "Claude", labelOf(templates, "anthropic"));
        check("A ollama 标签", "Ollama", labelOf(templates, "ollama"));

        // 模板级（无凭证）时，平台有的能力应显示 warn（"配好就能用"），不是 off
        check("A 模板级 火山 search = warn（有接口但未配 Key）", "warn",
                stateOfTemplate(templates, "volcengine", "search"));
        check("A 模板级 火山 balance = warn（有接口但未配 AK/SK）", "warn",
                stateOfTemplate(templates, "volcengine", "balance"));
        check("A 模板级 openai search = off（平台无官方接口）", "off",
                stateOfTemplate(templates, "openai", "search"));
        check("A 模板级 openai balance = off（平台无余额接口）", "off",
                stateOfTemplate(templates, "openai", "balance"));
    }

    // ==================== B. 视觉（必须与实际拦截一致） ====================

    private static void groupB_vision() {
        System.out.println("\n--- B. 视觉（与 FileVisionService 实际拦截一致）---");

        // B1 火山 v4.1 放行（上一轮修复的核心场景）
        ProviderConfig ark41 = provider("huoshan", "volcengine",
                "https://ark.cn-beijing.volces.com/api/coding/v3",
                "sk-test", "{}", "deepseek-v4.1-flash");
        check("B1 火山 v4.1-flash → on", "on", SERVICE.forProvider(ark41).getVision().getState());

        // B2 火山 v4-pro 拦截 → 显示 warn（平台支持、换模型即可），不是 off
        ProviderConfig arkPro = provider("huoshan2", "volcengine",
                "https://ark.cn-beijing.volces.com/api/v3",
                "sk-test", "{}", "deepseek-v4-pro");
        ProviderCapabilityVO proVo = SERVICE.forProvider(arkPro);
        check("B2 火山 v4-pro → warn（平台支持但模型不行）", "warn", proVo.getVision().getState());
        check("B2 v4-pro 平台仍标记为支持（可换模型修复）",
                true, proVo.getVision().isPlatformSupported());
        check("B2 v4-pro hint 提示会被后端拦截",
                true, proVo.getVision().getHint().contains("拦截"));

        // B3 未配置默认模型 → warn（后端宽松放行，但无法判断）
        ProviderConfig noModel = provider("nomodel", "deepseek",
                "https://api.deepseek.com", "sk-test", "{}", null);
        check("B3 未配默认模型 → warn", "warn", SERVICE.forProvider(noModel).getVision().getState());

        // B4 DeepSeek 官方：v4-flash 支持 / chat 不支持
        check("B4 deepseek-v4-flash → on", "on",
                SERVICE.forProvider(provider("ds1", "deepseek", "https://api.deepseek.com",
                        "sk-test", "{}", "deepseek-v4-flash")).getVision().getState());
        check("B4 deepseek-chat → warn", "warn",
                SERVICE.forProvider(provider("ds2", "deepseek", "https://api.deepseek.com",
                        "sk-test", "{}", "deepseek-chat")).getVision().getState());

        // B5 无模型级规则的平台（gpt）：模型已知 → on
        check("B5 gpt + gpt-4o → on", "on",
                SERVICE.forProvider(provider("gpt1", "gpt", "https://api.openai.com/v1",
                        "sk-test", "{}", "gpt-4o")).getVision().getState());

        // B6 一致性断言：能力结论必须与后端实际判定逐条相同（这是最关键的一条）
        String[][] cases = {
                {"volcengine", "deepseek-v4.1-flash"},
                {"volcengine", "deepseek-v4.1-flash-economy"},
                {"volcengine", "deepseek-v4-pro"},
                {"volcengine", "deepseek-v4-flash"},
                {"volcengine", "doubao-seed-1-6-250615"},
                {"deepseek", "deepseek-chat"},
                {"deepseek", "deepseek-v4-flash"},
                {"qwen", "qwen-plus"},
                {"qwen", "qwen-max"},
                {"minimax", "MiniMax-M2"},
                {"minimax", "MiniMax-M3"},
                {"mimo", "mimo-v2.5"},
        };
        for (String[] c : cases) {
            String code = c[0];
            String model = c[1];
            boolean actualSupports = FileVisionService.supportsVisionModel(code, model);
            String state = SERVICE.forProvider(provider(code + "-x", code,
                    "https://example.com", "sk-test", "{}", model)).getVision().getState();
            String expectedState = actualSupports ? "on" : "warn";
            check("B6 与实际判定一致 [" + code + " / " + model + "]", expectedState, state);
        }
    }

    // ==================== C. 搜索 ====================

    private static void groupC_search() {
        System.out.println("\n--- C. 官方联网搜索 ---");

        // C1 火山：有 searchApiKey → on
        check("C1 火山 + searchApiKey → on", "on",
                SERVICE.forProvider(provider("v1", "volcengine",
                        "https://ark.cn-beijing.volces.com/api/v3", "sk-test",
                        "{\"searchApiKey\":\"ws-xxx\"}", "doubao-seed")).getSearch().getState());
        // C1 火山：无 Key → warn（不是 off！联网仍可通用抓取）
        ProviderCapabilityVO v2 = SERVICE.forProvider(provider("v2", "volcengine",
                "https://ark.cn-beijing.volces.com/api/v3", "sk-test", "{}", "doubao-seed"));
        check("C1 火山 无 Key → warn", "warn", v2.getSearch().getState());
        check("C1 火山 无 Key 平台仍标记支持", true, v2.getSearch().isPlatformSupported());
        check("C1 火山 缺 searchApiKey 被精确指出", true,
                v2.getSearch().getMissingKeys().contains("searchApiKey"));
        check("C1 火山 hint 说明仍可通用抓取", true, v2.getSearch().getHint().contains("通用抓取"));

        // C2 DeepSeek 官方域名 → on
        check("C2 DeepSeek 官方域名 → on", "on",
                SERVICE.forProvider(provider("d1", "deepseek",
                        "https://api.deepseek.com", "sk-test", "{}", "deepseek-chat")).getSearch().getState());
        // C2 DeepSeek 第三方中转 → warn（官方接口拿不到）
        ProviderCapabilityVO d2 = SERVICE.forProvider(provider("d2", "deepseek",
                "https://proxy.example.com/v1", "sk-test", "{}", "deepseek-chat"));
        check("C2 DeepSeek 走中转 → warn", "warn", d2.getSearch().getState());
        check("C2 走中转时文案说明原因", true, d2.getSearch().getHint().contains("api.deepseek.com"));
        check("C2 走中转时徽章改为·通用抓取", true, d2.getSearch().getLabel().contains("通用抓取"));

        // C3 千问：enableSearch=true → on / 未开启 → warn
        check("C3 千问 enableSearch=true → on", "on",
                SERVICE.forProvider(provider("q1", "qwen", "https://dashscope.aliyuncs.com",
                        "sk-test", "{\"enableSearch\":true}", "qwen-plus")).getSearch().getState());
        check("C3 千问 未开启 → warn", "warn",
                SERVICE.forProvider(provider("q2", "qwen", "https://dashscope.aliyuncs.com",
                        "sk-test", "{}", "qwen-plus")).getSearch().getState());

        // C4 MiniMax：有 codingPlanKey → on
        check("C4 MiniMax + codingPlanKey → on", "on",
                SERVICE.forProvider(provider("m1", "minimax", "https://api.minimaxi.com",
                        "sk-test", "{\"codingPlanKey\":\"sk-cp-xxx\"}", "MiniMax-M3"))
                        .getSearch().getState());

        // C5 无官方接口的平台 → off
        for (String t : new String[]{"gpt", "openai", "anthropic", "ollama", "mimo", "custom"}) {
            check("C5 无官方搜索 [" + t + "] → off", "off",
                    SERVICE.forProvider(provider(t + "-p", t, "https://example.com",
                            "sk-test", "{}", "any")).getSearch().getState());
        }
    }

    // ==================== D. 余额（成对凭证） ====================

    private static void groupD_balance() {
        System.out.println("\n--- D. 余额查询（凭证必须成对）---");

        // D1 DeepSeek：只需 API Key
        check("D1 DeepSeek 有 API Key → on", "on",
                SERVICE.forProvider(provider("db1", "deepseek", "https://api.deepseek.com",
                        "sk-test", "{}", "deepseek-chat")).getBalance().getState());
        check("D1 DeepSeek 无 API Key → warn", "warn",
                SERVICE.forProvider(provider("db2", "deepseek", "https://api.deepseek.com",
                        null, "{}", "deepseek-chat")).getBalance().getState());

        // D2 火山：AK + SK 都配 → on
        check("D2 火山 AK+SK 齐全 → on", "on",
                SERVICE.forProvider(provider("vb1", "volcengine",
                        "https://ark.cn-beijing.volces.com/api/v3", "sk-test",
                        "{\"accessKey\":\"AKxxx\",\"secretKey\":\"SKxxx\"}", "doubao"))
                        .getBalance().getState());

        // D2 关键：只配 AK 不配 SK → warn（旧前端会误判成 on，点下去必然失败）
        ProviderCapabilityVO onlyAk = SERVICE.forProvider(provider("vb2", "volcengine",
                "https://ark.cn-beijing.volces.com/api/v3", "sk-test",
                "{\"accessKey\":\"AKxxx\"}", "doubao"));
        check("D2 只配 AK → warn（关键：不能显示成可查询）", "warn", onlyAk.getBalance().getState());
        check("D2 只配 AK 时精确指出缺 secretKey", true,
                onlyAk.getBalance().getMissingKeys().contains("secretKey"));
        check("D2 只配 AK 时不把 accessKey 列为缺失", false,
                onlyAk.getBalance().getMissingKeys().contains("accessKey"));

        // D2 反向：只配 SK 不配 AK
        ProviderCapabilityVO onlySk = SERVICE.forProvider(provider("vb3", "volcengine",
                "https://ark.cn-beijing.volces.com/api/v3", "sk-test",
                "{\"secretKey\":\"SKxxx\"}", "doubao"));
        check("D2 只配 SK → warn", "warn", onlySk.getBalance().getState());
        check("D2 只配 SK 时精确指出缺 accessKey", true,
                onlySk.getBalance().getMissingKeys().contains("accessKey"));

        // D3 千问：同样要成对
        check("D3 千问 AK+SK 齐全 → on", "on",
                SERVICE.forProvider(provider("qb1", "qwen", "https://dashscope.aliyuncs.com",
                        "sk-test", "{\"accessKey\":\"LTAI\",\"secretKey\":\"SKxxx\"}", "qwen-plus"))
                        .getBalance().getState());
        check("D3 千问 只配 AK → warn", "warn",
                SERVICE.forProvider(provider("qb2", "qwen", "https://dashscope.aliyuncs.com",
                        "sk-test", "{\"accessKey\":\"LTAI\"}", "qwen-plus")).getBalance().getState());

        // D4 掩码视为已配置（列表接口返回的就是掩码，能力判定不能因此变灰）
        check("D4 掩码 AK+SK 视为已配置 → on", "on",
                SERVICE.forProvider(provider("vb4", "volcengine",
                        "https://ark.cn-beijing.volces.com/api/v3", "****",
                        "{\"accessKey\":\"****\",\"secretKey\":\"****\"}", "doubao"))
                        .getBalance().getState());

        // D5 MiniMax / MiMo
        check("D5 MiniMax + codingPlanKey → on", "on",
                SERVICE.forProvider(provider("mb1", "minimax", "https://api.minimaxi.com",
                        "sk-test", "{\"codingPlanKey\":\"sk-cp\"}", "MiniMax-M3")).getBalance().getState());
        check("D5 MiniMax 无 Key → warn", "warn",
                SERVICE.forProvider(provider("mb2", "minimax", "https://api.minimaxi.com",
                        "sk-test", "{}", "MiniMax-M3")).getBalance().getState());
        check("D5 MiMo + mimoCookie → on", "on",
                SERVICE.forProvider(provider("mib1", "mimo", "https://api.xiaomimimo.com",
                        "sk-test", "{\"mimoCookie\":\"session=xxx\"}", "mimo-v2.5")).getBalance().getState());

        // D6 无余额接口的平台 → off
        for (String t : new String[]{"gpt", "openai", "anthropic", "ollama", "custom"}) {
            check("D6 无余额接口 [" + t + "] → off", "off",
                    SERVICE.forProvider(provider(t + "-b", t, "https://example.com",
                            "sk-test", "{}", "any")).getBalance().getState());
        }
    }

    // ==================== E. Files API 与计费模式 ====================

    private static void groupE_filesAndPricing() {
        System.out.println("\n--- E. Files API 与计费模式 ---");

        // E1 已适配 Files API
        check("E1 deepseek filesApi → on", "on",
                SERVICE.forProvider(provider("f1", "deepseek", "https://api.deepseek.com",
                        "sk-test", "{}", "deepseek-chat")).getFilesApi().getState());
        check("E1 anthropic filesApi → on", "on",
                SERVICE.forProvider(provider("f2", "anthropic", "https://api.anthropic.com",
                        "sk-test", "{}", "claude-3-5-sonnet")).getFilesApi().getState());

        // E2 未适配：必须 warn（"仅本地"），绝不能 off —— 否则用户以为不能发图
        for (String t : new String[]{"volcengine", "qwen", "minimax", "mimo", "gpt", "openai", "ollama", "custom"}) {
            ProviderCapabilityVO vo = SERVICE.forProvider(provider(t + "-f", t,
                    "https://example.com", "sk-test", "{}", "any"));
            check("E2 未适配 Files API [" + t + "] → warn（仅本地，非 off）", "warn",
                    vo.getFilesApi().getState());
            check("E2 [" + t + "] 文案声明不上传第三方云盘", true,
                    vo.getFilesApi().getHint().contains("第三方云盘"));
        }

        // E3 计费模式：火山按 baseUrl 区分（这是 T4 的根治点）
        check("E3 火山 Coding Plan → QUOTA", "QUOTA",
                SERVICE.forProvider(provider("p1", "volcengine",
                        "https://ark.cn-beijing.volces.com/api/coding/v3", "sk-test", "{}", "deepseek-v4.1-flash"))
                        .getPricingMode());
        check("E3 火山 普通 → TOKEN", "TOKEN",
                SERVICE.forProvider(provider("p2", "volcengine",
                        "https://ark.cn-beijing.volces.com/api/v3", "sk-test", "{}", "doubao"))
                        .getPricingMode());
        check("E3 Ollama → FREE", "FREE",
                SERVICE.forProvider(provider("p3", "ollama", "http://localhost:11434",
                        "irrelevant", "{}", "llama3")).getPricingMode());
        check("E3 DeepSeek → TOKEN", "TOKEN",
                SERVICE.forProvider(provider("p4", "deepseek", "https://api.deepseek.com",
                        "sk-test", "{}", "deepseek-chat")).getPricingMode());

        // E4 QUOTA 的说明文案必须讲清「不估价」，否则用户以为金额丢了
        ProviderCapabilityVO quota = SERVICE.forProvider(provider("p5", "volcengine",
                "https://ark.cn-beijing.volces.com/api/coding/v3", "sk-test", "{}", "deepseek-v4.1-flash"));
        check("E4 QUOTA hint 说明不按 token 计价", true,
                quota.getPricingHint().contains("不按 token"));
        check("E4 FREE hint 说明无费用", true,
                SERVICE.forProvider(provider("p6", "ollama", "http://localhost:11434",
                        "x", "{}", "llama3")).getPricingHint().contains("无费用"));
    }

    // ==================== F. 边界 ====================

    private static void groupF_boundaries() {
        System.out.println("\n--- F. 边界 ---");

        // F1 未知模板：不能假装支持任何能力
        ProviderCapabilityVO unknown = SERVICE.forProvider(provider("u1", "some-unknown",
                "https://example.com", "sk-test", "{}", "whatever"));
        check("F1 未知模板 vision → off", "off", unknown.getVision().getState());
        check("F1 未知模板 search → off", "off", unknown.getSearch().getState());
        check("F1 未知模板 balance → off", "off", unknown.getBalance().getState());
        check("F1 未知模板 filesApi → warn（仅本地）", "warn", unknown.getFilesApi().getState());
        check("F1 未知模板 pricing 保守按 TOKEN", "TOKEN", unknown.getPricingMode());

        // F2 config 为 null（不应崩，且给出保守结论）
        ProviderCapabilityVO nullConfig;
        try {
            nullConfig = SERVICE.forProvider(null);
            check("F2 config=null 不抛异常", true, nullConfig != null);
            check("F2 config=null 全部 off", "off", nullConfig.getVision().getState());
        } catch (Exception e) {
            check("F2 config=null 不抛异常（实际抛出 " + e.getClass().getSimpleName() + "）", false, true);
        }

        // F3 非法 extraConfig JSON：不应崩，按未配置处理
        ProviderCapabilityVO badJson = SERVICE.forProvider(provider("b1", "volcengine",
                "https://ark.cn-beijing.volces.com/api/v3", "sk-test",
                "this is not json {", "doubao"));
        check("F3 非法 JSON 不抛异常且按未配置处理", "warn", badJson.getBalance().getState());

        // F4 模板为空时用 code 兜底（code 需能命中前缀，如 volcengine-custom）
        // 注意：这里刻意用「平台名在前」的 code —— 现状前缀匹配只认 startsWith，
        // 所以 "my-volc" / "volc-deepseek" 这类「平台名在中间」的 code 识别不出（与 ModelCapabilityRules 一致）
        ProviderConfig noTemplate = new ProviderConfig();
        noTemplate.setCode("volcengine-custom");
        noTemplate.setBaseUrl("https://ark.cn-beijing.volces.com/api/v3");
        noTemplate.setApiKey("sk-test");
        noTemplate.setExtraConfig("{}");
        noTemplate.setDefaultModel("doubao");
        ProviderCapabilityVO fallback = SERVICE.forProvider(noTemplate);
        check("F4 模板为空时按 code 前缀识别出火山", "火山方舟", fallback.getLabel());
        check("F4 兜底后仍能判出绑卡能力（缺 AK/SK → warn）", "warn", fallback.getBalance().getState());

        // F4b 现状局限：平台名在中间的 code 识别不出 → 保守按未知模板处理
        ProviderConfig middleCode = new ProviderConfig();
        middleCode.setCode("my-volc");
        middleCode.setBaseUrl("https://ark.cn-beijing.volces.com/api/v3");
        middleCode.setDefaultModel("doubao");
        check("F4b 现状局限：my-volc 识别不出（按未知模板保守处理）", "off",
                SERVICE.forProvider(middleCode).getBalance().getState());

        // F5 extraConfig 大小写不敏感
        check("F5 extraConfig 键名大小写不敏感（AccessKey）", "warn",
                SERVICE.forProvider(provider("b2", "volcengine",
                        "https://ark.cn-beijing.volces.com/api/v3", "sk-test",
                        "{\"AccessKey\":\"AKxxx\"}", "doubao")).getBalance().getState());
        check("F5 大小写不影响成对判定（AK+SK 齐 → on）", "on",
                SERVICE.forProvider(provider("b3", "volcengine",
                        "https://ark.cn-beijing.volces.com/api/v3", "sk-test",
                        "{\"AccessKey\":\"AK\",\"SecretKey\":\"SK\"}", "doubao")).getBalance().getState());

        // F6 批量接口
        List<ProviderConfig> list = new ArrayList<>();
        list.add(provider("x1", "deepseek", "https://api.deepseek.com", "sk", "{}", "deepseek-v4-flash"));
        list.add(provider("x2", "volcengine", "https://ark.cn-beijing.volces.com/api/v3", "sk", "{}", "doubao"));
        Map<String, ProviderCapabilityVO> map = SERVICE.forAllProviders(list);
        check("F6 批量返回 2 条", 2, map.size());
        check("F6 批量 key = provider code", true, map.containsKey("x1") && map.containsKey("x2"));
        check("F6 批量按各自模板判定视觉（x1 deepseek-v4-flash → on）", "on",
                map.get("x1").getVision().getState());
        check("F6 批量 x2 火山豆包 → on", "on", map.get("x2").getVision().getState());
        check("F6 批量 x1 计费 TOKEN / x2 计费 TOKEN", true,
                "TOKEN".equals(map.get("x1").getPricingMode())
                        && "TOKEN".equals(map.get("x2").getPricingMode()));
    }

    // ==================== 辅助 ====================

    private static ProviderConfig provider(String code, String template, String baseUrl,
                                           String apiKey, String extraConfig, String defaultModel) {
        ProviderConfig config = new ProviderConfig();
        config.setCode(code);
        config.setRequestTemplate(template);
        config.setBaseUrl(baseUrl);
        config.setApiKey(apiKey);
        config.setExtraConfig(extraConfig);
        config.setDefaultModel(defaultModel);
        return config;
    }

    private static String labelOf(List<ProviderCapabilityVO> list, String template) {
        for (ProviderCapabilityVO vo : list) {
            if (template.equals(vo.getTemplate())) {
                return vo.getLabel();
            }
        }
        return "<未找到>";
    }

    private static String stateOfTemplate(List<ProviderCapabilityVO> list, String template, String field) {
        for (ProviderCapabilityVO vo : list) {
            if (template.equals(vo.getTemplate())) {
                return switch (field) {
                    case "vision" -> vo.getVision().getState();
                    case "search" -> vo.getSearch().getState();
                    case "balance" -> vo.getBalance().getState();
                    case "filesApi" -> vo.getFilesApi().getState();
                    default -> "<未知字段>";
                };
            }
        }
        return "<未找到>";
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

    private static void check(String name, int expected, int actual) {
        check(name + "（期望=" + expected + " 实际=" + actual + "）", expected == actual);
    }
}
