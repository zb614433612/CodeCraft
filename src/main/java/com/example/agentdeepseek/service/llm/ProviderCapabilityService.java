package com.example.agentdeepseek.service.llm;

import com.example.agentdeepseek.model.entity.ProviderConfig;
import com.example.agentdeepseek.model.vo.ProviderCapabilityVO;
import com.example.agentdeepseek.model.vo.ProviderCapabilityVO.CapabilityItem;
import com.example.agentdeepseek.service.files.FileVisionService;
import com.example.agentdeepseek.service.llm.profile.ProviderProfile;
import com.example.agentdeepseek.service.llm.profile.ProviderProfile.BalanceProvider;
import com.example.agentdeepseek.service.llm.profile.ProviderProfile.FilesApiProvider;
import com.example.agentdeepseek.service.llm.profile.ProviderProfile.PricingMode;
import com.example.agentdeepseek.service.llm.profile.ProviderProfile.SearchProvider;
import com.example.agentdeepseek.service.llm.profile.ProviderRegistry;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Provider 能力判定服务 —— <b>把「界面该显示什么」算在后端</b>（T9 / T10 的唯一真相源）
 *
 * <h3>本服务做什么</h3>
 * <p>
 * 给定一个 {@link ProviderConfig}（或仅一个模板），产出 {@link ProviderCapabilityVO}：
 * 图片识别 / 官方联网搜索 / 余额查询 / Files API / 计费模式 五项能力的
 * <b>三态结论 + 可读原因 + 缺失凭证</b>。前端拿到后只负责渲染，不再自己推导。
 * </p>
 *
 * <h3>三条设计原则（都来自踩过的坑）</h3>
 *
 * <h4>① 用「实际生效的判定」，不要复制一份规则</h4>
 * <p>
 * 视觉判定直接调用 {@link FileVisionService#supportsVisionModel}，
 * 而不是自己读 {@code ProviderRegistry} 的静态规则重算一遍。
 * 原因：{@code application.yml} 的 {@code vision-rules} 是<b>可选覆盖层</b>，
 * 一旦运维覆盖过，真正拦截发图的是那份 yml 规则。能力展示若读静态规则，
 * 就会出现「界面说能发、实际被后端拦下」的错位 —— 这正是本项目反复踩的坑。
 * 而 {@code supportsVisionModel} 内部走的是生效中的规则表，天然一致。
 * </p>
 *
 * <h4>② 区分「平台没有」和「配置没配」</h4>
 * <p>
 * 这两件事对用户的意义完全不同：前者换平台才能解决，后者补配置就能解决。
 * 因此每一项都同时返回 {@code state}（三态）与 {@code platformSupported}（平台是否有该能力），
 * 并在「缺凭证」时用 {@link CapabilityItem#getMissingKeys()} 精确指出缺哪个字段。
 * </p>
 *
 * <h4>③ 凭证要「成对」才算就绪（修掉前端的一个真实缺陷）</h4>
 * <p>
 * 前端旧实现判断火山 / 千问余额时用的是
 * {@code hasExtraValue(extra, ['accessKey', 'secretKey'])} —— <b>任一存在即算已配置</b>。
 * 但后端 {@code ProviderBalanceService.queryVolcengineBalance} 明确要求两者都非空，
 * 只配一个会直接抛「需要 AccessKey/SecretKey」。
 * 于是界面显示「可查询」，点下去必然失败。
 * 本服务改为<b>成对校验</b>，缺哪个报哪个。
 * </p>
 */
@Slf4j
@Service
public class ProviderCapabilityService {

    /** 后端掩码占位符：列表接口把密钥替换成该值，收到掩码视为「已配置」 */
    private static final String MASK = "****";

    private final ObjectMapper objectMapper;

    public ProviderCapabilityService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    // ==================== 对外入口 ====================

    /**
     * 全部内置模板的能力档案（<b>模板级，不含凭证判定</b>）。
     *
     * <p>用途：① 前端「新建 Provider」的模板下拉（替代 {@code ProviderEditDrawer} 里硬编码的
     * {@code TEMPLATE_OPTIONS}）；② 用户刚选模板、还没填凭证时的能力预览。</p>
     *
     * <p>此时没有凭证信息，因此搜索 / 余额会显示为 {@code warn}（「平台支持，填好凭证即可用」），
     * 而不是 {@code off} —— 这样用户能预期「配完凭证就会亮」。</p>
     */
    public List<ProviderCapabilityVO> templates() {
        List<ProviderCapabilityVO> list = new ArrayList<>();
        for (ProviderProfile profile : ProviderRegistry.orderedByDisplay()) {
            if (profile.getDisplay() != null && !profile.getDisplay().isTemplateSelectable()) {
                continue;
            }
            list.add(build(profile, null, null));
        }
        return list;
    }

    /**
     * 单个 Provider 实例的能力（<b>结合其真实凭证</b>）。
     *
     * @param config Provider 配置（应为 DB 原始值，非掩码；若含掩码也按「已配置」处理）
     */
    public ProviderCapabilityVO forProvider(ProviderConfig config) {
        ProviderProfile profile = resolveProfile(config);
        return build(profile, config, config == null ? null : config.getDefaultModel());
    }

    /**
     * 所有已启用 Provider 的能力（key = provider code）。
     *
     * <p>前端一次拉取即可填满本地缓存，避免逐个 Provider 发 N 次请求。</p>
     */
    public Map<String, ProviderCapabilityVO> forAllProviders(List<ProviderConfig> configs) {
        Map<String, ProviderCapabilityVO> map = new LinkedHashMap<>();
        if (configs == null) {
            return map;
        }
        for (ProviderConfig config : configs) {
            if (config == null || config.getCode() == null) {
                continue;
            }
            map.put(config.getCode(), forProvider(config));
        }
        return map;
    }

    // ==================== 档案解析 ====================

    /**
     * 解析 Provider 对应的档案。
     *
     * <p>顺序：{@code requestTemplate} 精确匹配 → {@code code} 精确/前缀匹配。
     * 先模板后 code 是因为「模板决定协议与能力」，而 code 是用户自定义的（可能是 {@code volc-deepseek} 这类）。</p>
     */
    /**
     * 解析 Provider 对应的档案。
     *
     * <p>统一走 {@link ProviderRegistry#resolveFor}：模板精确优先、code 前缀兜底。
     * 不在这里自己写「template 空才用 code」的分支 —— 那正是此前 6 个消费方各写一遍、
     * 且都漏掉前缀匹配的根源。</p>
     */
    private ProviderProfile resolveProfile(ProviderConfig config) {
        if (config == null) {
            return null;
        }
        return ProviderRegistry.resolveFor(
                config.getRequestTemplate(), config.getCode(), config.getBaseUrl());
    }

    // ==================== 能力构建 ====================

    /**
     * 构建能力视图。
     *
     * @param profile 档案（可为 null：未知模板时全部按 off / 保守处理）
     * @param config  实例配置（可为 null：模板级预览，不做凭证判定）
     * @param model   默认模型（hint 文案引用；模板级预览传 null）
     */
    private ProviderCapabilityVO build(ProviderProfile profile, ProviderConfig config, String model) {
        ProviderCapabilityVO vo = new ProviderCapabilityVO();

        String template = config == null ? null : config.getRequestTemplate();
        if (template == null || template.isBlank()) {
            template = config == null ? null : config.getCode();
        }
        if (template == null || template.isBlank()) {
            template = profile == null ? null : profile.getTemplate();
        }
        vo.setTemplate(template);
        vo.setProviderCode(config == null ? null : config.getCode());
        vo.setDefaultModel(model);

        if (profile == null) {
            // 未知模板：不能假装支持任何能力（宁可保守，也不要显示假 supported）
            vo.setLabel(template == null ? "未指定" : template);
            vo.setIconTone("");
            vo.setOrder(999);
            vo.setVision(CapabilityItem.off("图片识别", "未识别的请求模板，无法判断图片输入能力"));
            vo.setSearch(CapabilityItem.off("联网搜索", "该平台未配置官方搜索接口，联网靠通用抓取，仍可正常使用"));
            vo.setBalance(CapabilityItem.off("余额查询", "该平台未接入余额查询接口"));
            vo.setFilesApi(CapabilityItem.warn("文件上传·仅本地",
                    "未适配 Files API：文件仅存本地，发图时转 base64 内联（功能完全可用，不向第三方云盘上传）",
                    List.of()));
            vo.setPricingMode(PricingMode.TOKEN.name());
            vo.setPricingHint("按 token 计价（需在 llm.pricing.models 配置单价才显示金额）");
            return vo;
        }

        if (profile.getDisplay() != null) {
            vo.setLabel(profile.getDisplay().getLabel());
            vo.setIconTone(profile.getDisplay().getIconTone());
            vo.setOrder(profile.getDisplay().getOrder());
        }

        vo.setVision(buildVision(profile, config, model));
        vo.setSearch(buildSearch(profile, config));
        vo.setBalance(buildBalance(profile, config));
        vo.setFilesApi(buildFilesApi(profile, config));
        fillPricing(vo, profile, config);
        return vo;
    }

    // ==================== ① 图片识别 ====================

    /**
     * 图片识别三态。
     *
     * <h3>为什么同时用 template 和 code 两个键判定（重要）</h3>
     * <p>
     * 视觉规则是按<b>平台名</b>注册的（{@code deepseek} / {@code volcengine,ark,doubao} / {@code qwen,…}），
     * 但用户在新建 Provider 时可以自起 {@code code}。典型例子：火山方舟的
     * {@code code=huoshan}、{@code requestTemplate=volcengine}。
     * </p>
     * <p>
     * 此时若<b>只按 {@code code} 查规则</b>，{@code findVisionRule("huoshan")} 会返回 {@code null}
     * （「huoshan」不在任何别名里）→ 落到全局 {@code defaultAllowVision=true} → 判为「支持」。
     * 于是 {@code deepseek-v4-pro}（纯文本）会被显示成可发图。
     * </p>
     * <p>
     * 因此这里改为<b>双键取严</b>：{@code template} 与 {@code code} 各判一次，任一判为不支持即 {@code warn}。
     * {@code template} 是平台真相源，{@code code} 反映后端拦截点的实际入参 ——
     * 取严可以保证「界面显示的严格程度不低于实际行为」，
     * <b>宁可少说一个能力，也不要让用户发了图才发现图片被丢弃</b>。
     * </p>
     *
     * <h3>三态映射</h3>
     * <ul>
     *   <li>平台协议不支持 → {@code off}（当前所有已适配模板都支持，保留兜底）；</li>
     *   <li>模型为空 → {@code warn}（后端宽松放行，但用户该知道「取决于模型」）；</li>
     *   <li>模型明确不支持 → {@code warn}（平台支持、换个模型就行，<b>不是 off</b>）；</li>
     *   <li>模型支持 → {@code on}。</li>
     * </ul>
     */
    private CapabilityItem buildVision(ProviderProfile profile, ProviderConfig config, String model) {
        if (profile == null || profile.getVision() == null || !profile.getVision().isProtocolSupported()) {
            return CapabilityItem.off("图片识别", "该平台协议不支持图片输入");
        }

        String modelName = model == null ? "" : model.trim();
        if (modelName.isEmpty()) {
            // 未配置默认模型：后端对空模型名放行，但真实能力取决于最终用哪个模型
            return CapabilityItem.warn("图片识别",
                    "平台支持图片输入，但未配置默认模型，无法判断当前模型是否可发图",
                    List.of());
        }

        /*
          双键取严：主键（档案主名）+ code（后端拦截点的实际入参）。

          ⚠️ 审查修复：主键必须用 profile.getTemplate()（档案主名），不能再用
          config.getRequestTemplate() 的原始字符串 —— 否则展示与执行会分叉。

          原因：执行侧 DeepSeekServiceImpl#resolveVisionTemplateKey 已改为返回
          「档案主名」（如 volcengine）。对于 code=my-gateway + 火山域名的配置：
            执行侧：resolveFor 域名兜底 → "volcengine" → v4-pro 命中 exclude → 拦截
            展示侧（旧）：supportsVisionModel("my-gateway") → 无规则 → 宽松放行 → 显示 on
          结果是「界面说能发图，一发就报不支持」，正好是本次重构要消灭的那类错位。
          统一到档案主名后，两侧查的是同一条规则。
         */
        String template = profile.getTemplate();
        String code = config == null ? null : config.getCode();

        boolean supportedByTemplate = FileVisionService.supportsVisionModel(template, modelName);
        boolean supportedByCode = code == null || code.isBlank()
                || FileVisionService.supportsVisionModel(code, modelName);
        boolean supported = supportedByTemplate && supportedByCode;

        if (supported) {
            return CapabilityItem.on("图片识别",
                    "当前默认模型（" + modelName + "）支持图片输入");
        }

        // 文案说清「为什么不行」与「怎么修」，并明确后端会拦截（这是真实行为，不该瞒着用户）
        String reason = !supportedByTemplate
                ? "平台已声明该模型为纯文本"
                : "该模型在视觉规则中未命中多模态关键词";
        return CapabilityItem.warn("图片识别",
                "平台支持图片输入，但默认模型「" + modelName + "」" + reason + "。"
                        + "发图前请切换多模态模型，否则后端会拦截该请求",
                List.of());
    }

    // ==================== ② 官方联网搜索 ====================

    /**
     * 官方联网搜索三态。
     *
     * <p>⚠️ 关键语义：{@code warn} / {@code off} <b>都不等于「不能联网」</b>，
     * 只是拿不到平台侧的搜索结果，仍会降级到通用抓取（Bing / 百度 / Jina / 本地）。
     * 文案必须保留这句话，否则会被用户读成「搜索坏了」。</p>
     */
    private CapabilityItem buildSearch(ProviderProfile profile, ProviderConfig config) {
        SearchProvider provider = (profile.getSearch() == null || profile.getSearch().getProvider() == null)
                ? SearchProvider.NONE : profile.getSearch().getProvider();

        if (provider == SearchProvider.NONE) {
            return CapabilityItem.off("联网搜索",
                    "该平台未配置官方搜索接口，联网靠通用抓取（Bing / 百度 / Jina / 本地），仍可正常使用");
        }

        JsonNode extra = parseExtra(config);
        boolean preview = isPreview(config);

        switch (provider) {
            case VOLCENGINE -> {
                // 搜索 Key 在独立的搜索控制台，与方舟 Ark API Key 不通用
                if (hasCredential(extra, "searchApiKey")) {
                    return CapabilityItem.on("联网搜索", "火山方舟搜索服务已配置（extraConfig.searchApiKey）");
                }
                if (preview) {
                    return CapabilityItem.warn("联网搜索",
                            "平台有官方搜索接口，配置 extraConfig.searchApiKey 后即可用"
                                    + "（或设环境变量 VOLCENGINE_SEARCH_API_KEY）；"
                                    + "未配置时联网靠通用抓取（Bing / 百度 / Jina / 本地），仍可正常使用",
                            List.of("searchApiKey"));
                }
                return CapabilityItem.warn("联网搜索",
                        "未配置火山联网搜索 Key（extraConfig.searchApiKey 或环境变量 "
                                + "VOLCENGINE_SEARCH_API_KEY / WEB_SEARCH_API_KEY），"
                                + "联网靠通用抓取（Bing / 百度 / Jina / 本地），仍可正常使用",
                        List.of("searchApiKey"));
            }
            case QWEN_DIALOG -> {
                if (isExtraTrue(extra, "enableSearch") || hasCredential(extra, "searchApiKey")) {
                    return CapabilityItem.on("联网搜索",
                            "通义千问联网搜索已开启（extraConfig.enableSearch 或 searchApiKey）");
                }
                return CapabilityItem.warn("联网搜索",
                        "通义千问联网搜索未开启，在扩展配置设置 {\"enableSearch\":true} 即可用；"
                                + "未开启时联网靠通用抓取（Bing / 百度 / Jina / 本地），仍可正常使用",
                        List.of("enableSearch"));
            }
            case MINIMAX_PLAN -> {
                if (hasAnyCredential(extra, "codingPlanKey", "coding_plan_key", "searchApiKey")) {
                    return CapabilityItem.on("联网搜索", "MiniMax 联网搜索已配置（extraConfig.codingPlanKey）");
                }
                return CapabilityItem.warn("联网搜索",
                        "未配置 MiniMax Coding Plan Key（extraConfig.codingPlanKey），"
                                + "联网靠通用抓取（Bing / 百度 / Jina / 本地），仍可正常使用",
                        List.of("codingPlanKey"));
            }
            case DEEPSEEK_OFFICIAL -> {
                // 官方 /v1/tools/search 免额外凭证，但要求 Base URL 主机严格是 api.deepseek.com。
                // 走第三方中转时官方接口拿不到 —— 这是历史上把 deepseek 一律判成「不支持」的漏判点。
                /*
                  ⚠️ 已知边界（有意保留，不是 bug）：template 与域名指向不同平台时，
                  这里会偏保守地说「官方搜索不可用」，而 SearchTool 实际可能走了火山搜索。

                  场景：template=deepseek + baseUrl=ark.cn-beijing.volces.com
                    · 展示层：resolveFor 精确命中 deepseek → 本分支 → 域名不是官方 → warn
                    · 执行层：SearchTool 先试火山，isVolcengineProvider 的 host 兜底命中 → 真的走火山
                  即「界面说只能用通用抓取，实际走了火山结构化搜索」。

                  为什么不强行对齐：
                    ① 这是畸形配置（用户手填 deepseek 模板却指向火山域名），非正常路径；
                    ② 差异方向是「展示更保守」，不会让用户误以为能用而实际失败 ——
                       反向错位（说能用、实际不行）才是必须消灭的那类；
                    ③ 要对齐就得把 SearchTool 的平台优先级链（火山 → MiniMax → DeepSeek）
                       在展示层再实现一份，等于新增一处必然漂移的副本。

                  注意：余额在同样场景下已通过 balanceCandidateFor 对齐（会显示火山余额），
                  因此界面不会出现「余额说火山、搜索说 DeepSeek」的自相矛盾描述 ——
                  本分支的 warn 文案只说「DeepSeek 官方接口不可用」，不否认其他平台。
                 */
                String baseUrl = config == null ? null : config.getBaseUrl();
                if (isOfficialHost(baseUrl)) {
                    return CapabilityItem.on("联网搜索",
                            "Base URL 指向 api.deepseek.com，DeepSeek 官方搜索接口免额外凭证可用");
                }
                /*
                  模板级预览（新建抽屉刚选完模板、baseUrl 还没填）不能说「不可用」。

                  旧文案在这种情况下会输出「当前 Base URL 不是 api.deepseek.com，
                  DeepSeek 官方搜索接口不可用」——用户看到的是「这平台不能用搜索」，
                  但事实是「填对域名就能用，而且免额外凭证」。文案必须区分
                  「还没填」与「填错了」两种情形。
                 */
                if (baseUrl == null || baseUrl.isBlank()) {
                    return CapabilityItem.warn("联网搜索",
                            "平台有官方搜索接口且免额外凭证，Base URL 指向 api.deepseek.com 时即可用；"
                                    + "未指向官方域名时联网靠通用抓取（Bing / 百度 / Jina / 本地），仍可正常使用",
                            List.of());
                }
                return CapabilityItem.warn("联网搜索·通用抓取",
                        "当前 Base URL（" + baseUrl + "）不是 api.deepseek.com，"
                                + "DeepSeek 官方搜索接口不可用，联网只能靠通用抓取（Bing / 百度 / Jina / 本地）",
                        List.of());
            }
            case NONE -> {
                // 不可达：NONE 已在方法开头 return，这里只保证 switch 覆盖全部枚举值
                return CapabilityItem.off("联网搜索", "该平台未配置官方搜索接口");
            }
        }
        /*
          ⚠️ 这行 throw 是「新增枚举漏补 case」的护栏。

          原来这里是 default -> 返回一句「暂未接入判断」，问题在于它把
          「代码没写完」伪装成「正常结论」：新增 SearchProvider 后界面显示
          「平台官方联网接口暂未接入判断」，既不报错、也不像故障，没人会去查。

          现在删掉 default、只保留全部枚举 case，并把兜底改成抛异常：
            · 忘记补 case → 运行到这里立刻 IllegalStateException，一眼看到
              「有枚举值没被 switch 处理」，附带确切枚举名；
            · 配合 ProviderCapabilityServiceTest 的枚举遍历断言，
              这个错误会在 CI 阶段（而不是用户手上）暴露。
         */
        throw new IllegalStateException("buildSearch 未处理 SearchProvider." + provider
                + " —— 新增枚举值后必须在此补充展示文案");
    }

    // ==================== ③ 余额查询 ====================

    /**
     * 余额查询三态。
     *
     * <p>⚠️ 查的是<b>资金余额</b>，不是套餐剩余次数：火山 Coding Plan 的额度查不到
     * （那是套餐计数），只有账号余额能查。</p>
     */
    private CapabilityItem buildBalance(ProviderProfile profile, ProviderConfig config) {
        /*
          ⚠️ 审查修复：这里必须复用「执行层的判定函数」，不能直接用 profile.getBalance()。

          为什么两者会分叉：
            · resolveProfile 走 resolveFor，优先级是「名字优先」
              （template 精确 → template 前缀 → code 前缀 → baseUrl 域名）；
            · 而真正查余额的 ProviderBalanceService#queryBalance 走 balanceCandidateFor，
              优先级是「平台顺序优先」（volcengine → deepseek → minimax → mimo → qwen，
              先试 template 命中，再试 host 兜底，短路返回）。

          典型分歧配置：template=deepseek + baseUrl 指向 ark.cn-beijing.volces.com
            · 旧展示层：resolveFor 精确命中 deepseek → 显示「DeepSeek 官方余额」
            · 实际执行：balanceCandidateFor 里 volcengine 排第一且域名命中 → 查的是火山账户
          两者都显示「可查询」，用户却不知道自己看到的是哪家的钱 —— 属于最难察觉的错位。
          直接调 balanceCandidateFor，展示与执行就共用同一个判定，结构上不可能再分叉。
         */
        /*
          分两种场景，语义不同（都必须是「真实」的，只是真实的对象不同）：

            ① 模板级预览（config == null）：回答「这个平台具备什么能力」。
               没有实例配置可供判定（连 baseUrl 都还没有），只能用该模板的档案。
               此时显示 warn（有接口、配好凭证即可用）是准确的 —— 不能因为
               「还没填 AK/SK」就说这个平台不支持查余额。

            ② 实例级（config != null）：回答「这台实例点下去会查什么」。
               必须复用执行层的 balanceCandidateFor，否则展示与执行会分叉
               （详见下方 switch 前的说明）。
         */
        ProviderProfile balanceProfile;
        if (config == null) {
            balanceProfile = profile;
        } else {
            balanceProfile = ProviderRegistry.balanceCandidateFor(
                    config.getRequestTemplate(), config.getCode(), config.getBaseUrl());
        }

        BalanceProvider provider = (balanceProfile == null
                || balanceProfile.getBalance() == null
                || balanceProfile.getBalance().getProvider() == null)
                ? BalanceProvider.NONE : balanceProfile.getBalance().getProvider();

        if (provider == BalanceProvider.NONE) {
            return CapabilityItem.off("余额查询", "该平台未接入余额查询接口");
        }

        JsonNode extra = parseExtra(config);
        boolean preview = isPreview(config);

        switch (provider) {
            case DEEPSEEK_OFFICIAL -> {
                // 只需 API Key（由 Provider 自身配置，无需 extraConfig）
                if (hasApiKey(config)) {
                    return CapabilityItem.on("余额查询", "DeepSeek 官方 /user/balance 可直接查询（只需 API Key）");
                }
                return CapabilityItem.warn("余额查询",
                        "DeepSeek 余额查询只需 Provider 的 API Key，请先填写 API Key",
                        List.of("apiKey"));
            }
            case VOLCENGINE_BILLING, ALIYUN_BILLING -> {
                String platform = provider == BalanceProvider.VOLCENGINE_BILLING ? "火山引擎" : "阿里云";
                // 注意：必须成对校验。只配 AK 不配 SK 时后端会抛「需要 AccessKey/SecretKey」，
                // 旧前端「任一有就算配好」的判定会把这种情况显示成「可查询」，点下去必然失败。
                List<String> missing = new ArrayList<>();
                if (!hasCredential(extra, "accessKey", "access_key", "ak")) {
                    missing.add("accessKey");
                }
                if (!hasCredential(extra, "secretKey", "secret_key", "sk")) {
                    missing.add("secretKey");
                }
                if (missing.isEmpty()) {
                    return CapabilityItem.on("余额查询",
                            platform + "账户余额可查询（账号级 AccessKey/SecretKey 已配置）");
                }
                String hint = preview
                        ? "平台有账户余额接口，需在扩展配置填写账号级 accessKey + secretKey 后可用"
                        : platform + "余额查询需要账号级 AccessKey/SecretKey（与模型 API Key 是两套凭证），"
                        + "当前缺少：" + String.join(" / ", missing);
                return CapabilityItem.warn("余额查询", hint, missing);
            }
            case MINIMAX_PLAN -> {
                if (hasCredential(extra, "codingPlanKey", "coding_plan_key")) {
                    return CapabilityItem.on("余额查询",
                            "MiniMax Token Plan 额度可查询（extraConfig.codingPlanKey 已配置）");
                }
                return CapabilityItem.warn("余额查询",
                        "MiniMax 额度查询需要 Coding Plan / Token Plan Key，请填写 extraConfig.codingPlanKey",
                        List.of("codingPlanKey"));
            }
            case MIMO_COOKIE -> {
                if (hasCredential(extra, "mimoCookie", "mimo_cookie", "cookie")) {
                    return CapabilityItem.on("余额查询",
                            "MiMo 控制台余额可查询（extraConfig.mimoCookie 已配置）");
                }
                return CapabilityItem.warn("余额查询",
                        "MiMo 官方未开放余额 OpenAPI，只能用控制台 Cookie："
                                + "登录 platform.xiaomimimo.com → F12 复制 balance 请求的 Cookie，"
                                + "填入扩展配置 {\"mimoCookie\":\"...\"}（约 24 小时有效）",
                        List.of("mimoCookie"));
            }
            case NONE -> {
                // 不可达：NONE 已在方法开头 return，这里只保证 switch 覆盖全部枚举值
                return CapabilityItem.off("余额查询", "该平台未接入余额查询接口");
            }
        }
        // 同 buildSearch：把「漏补 case」变成显式异常，而不是伪装成「暂未接入判断」
        throw new IllegalStateException("buildBalance 未处理 BalanceProvider." + provider
                + " —— 新增枚举值后必须在此补充展示文案");
    }

    // ==================== ④ Files API ====================

    /**
     * Files API 能力。
     *
     * <p>{@code warn} 而不是 {@code off}：未适配时文件<b>仅本地存储 + 发图时转 base64</b>，
     * 功能完全可用。把它显示成 off 会让用户以为「这个平台不能发图」——
     * 那正是 v3.1.3 之前的真实 bug。</p>
     *
     * <p>同时这也是一条隐私说明：未适配平台<b>不会</b>把用户文件传上第三方云盘。</p>
     */
    private CapabilityItem buildFilesApi(ProviderProfile profile, ProviderConfig config) {
        /*
          ⚠️ 审查修复：Files API 的判定必须与「执行层」完全一致 —— 用**精确匹配**，
          不能用 resolveProfile 的结果（那条路带了前缀与域名兜底）。

          为什么这里要刻意比视觉/余额更严：
            Files API 决定「用户文件是否上传到第三方云端」，属于数据外发动作。
            执行层 FilesApiClientManager#createClient 用的是 findByTemplate（精确匹配），
            口径是「认不出就走本地存储，绝不靠名字猜平台」。
            而 resolveProfile 走 resolveFor，会把 deepseek-custom 前缀识别成 deepseek。

          若展示层跟随 resolveFor，就会出现：
            · 界面显示「文件上传·云端：文件可上传到 DeepSeek 云端」
            · 实际执行 findByTemplate("deepseek-custom") 落空 → 降级本地存储
          用户以为文件在云端有备份，实际只在本机 —— 描述与事实相反，
          清理本地缓存后才发现文件没了。这类错位比「功能少一个」严重得多。

          因此这里**主动降级为严格匹配**：宁可显示「仅本地」（保守但真实），
          也不显示一个不会发生的数据外发承诺。
         */
        /*
          与余额同样的分流逻辑：
            ① 模板级预览（config == null）：回答「这个平台是否适配了 Files API」，
               直接用该模板档案。deepseek / anthropic 预览时应显示「云端」。
            ② 实例级：必须严格匹配，与执行层同口径（宁可显示仅本地）。
         */
        ProviderProfile filesProfile;
        if (config == null) {
            filesProfile = profile;
        } else {
            String template = config.getRequestTemplate();
            if (template == null || template.isBlank()) {
                template = config.getCode();
            }
            // 精确匹配不到 → 执行层必然降级本地，展示层不能声称支持云端
            filesProfile = ProviderRegistry.findByTemplate(template);
        }

        FilesApiProvider provider = (filesProfile == null
                || filesProfile.getFilesApi() == null
                || filesProfile.getFilesApi().getProvider() == null)
                ? FilesApiProvider.NONE : filesProfile.getFilesApi().getProvider();

        return switch (provider) {
            case DEEPSEEK -> CapabilityItem.on("文件上传·云端",
                    "已适配 Files API：文件可上传到 DeepSeek 云端，超限时自动降级为本地存储");
            case ANTHROPIC -> CapabilityItem.on("文件上传·云端",
                    "已适配 Files API：文件可上传到 Anthropic 云端（需 baseUrl 含 /anthropic 前缀），超限时自动降级");
            case NONE -> CapabilityItem.warn("文件上传·仅本地",
                    "未适配 Files API：文件仅存本地，发图时转 base64 内联。"
                            + "功能完全可用，且不会把文件上传到任何第三方云盘",
                    List.of());
        };
    }

    // ==================== ⑤ 计费模式 ====================

    /**
     * 填充计费模式与解释文案。
     *
     * <p>同一 Provider 可能有两种计费形态（火山 {@code /api/v3} 按 token、
     * {@code /api/coding} 是次数套餐），因此必须把 baseUrl 一起传给档案。</p>
     */
    private void fillPricing(ProviderCapabilityVO vo, ProviderProfile profile, ProviderConfig config) {
        String baseUrl = config == null ? null : config.getBaseUrl();
        PricingMode mode = profile.resolvePricingMode(baseUrl);
        vo.setPricingMode(mode.name());
        vo.setPricingHint(switch (mode) {
            case QUOTA -> "套餐 / 次数制计费（如 Coding Plan），不按 token 计价 —— "
                    + "成本看板不会为该 Provider 估算金额，只展示 token 与缓存命中率";
            case FREE -> "本地部署，无费用 —— 不显示金额";
            case TOKEN -> "按 token 计价 —— 需在 llm.pricing.models 配置该模型单价后才显示金额";
        });
    }

    // ==================== 凭证工具 ====================

    /** 是否为「模板级预览」（没有真实 ProviderConfig 实例） */
    private boolean isPreview(ProviderConfig config) {
        return config == null || config.getCode() == null;
    }

    /** 解析 extraConfig；非法 JSON 视为空（与后端其它服务保持一致，不影响主链路） */
    private JsonNode parseExtra(ProviderConfig config) {
        if (config == null) {
            return objectMapper.createObjectNode();
        }
        String text = config.getExtraConfig();
        if (text == null || text.isBlank()) {
            return objectMapper.createObjectNode();
        }
        try {
            JsonNode node = objectMapper.readTree(text);
            return node != null && node.isObject() ? node : objectMapper.createObjectNode();
        } catch (Exception e) {
            log.debug("Provider [{}] 扩展配置不是合法 JSON，按未配置处理: {}",
                    config.getCode(), e.getMessage());
            return objectMapper.createObjectNode();
        }
    }

    /** Provider 自身的 API Key 是否已配置（掩码也算已配置） */
    private boolean hasApiKey(ProviderConfig config) {
        if (config == null) {
            return false;
        }
        String key = config.getApiKey();
        return key != null && !key.isBlank();
    }

    /**
     * extraConfig 中任一同名字段是否已配置。
     * <p>字符串非空即算配置（{@code ****} 是后端掩码，同样代表已配置）；布尔 / 数字取真值。</p>
     */
    private boolean hasCredential(JsonNode extra, String... names) {
        return hasAnyCredential(extra, names);
    }

    private boolean hasAnyCredential(JsonNode extra, String... names) {
        if (extra == null || extra.isMissingNode()) {
            return false;
        }
        for (String name : names) {
            JsonNode value = extra.get(name);
            if (value == null || value.isNull()) {
                // 大小写兜底：用户可能写 SearchApiKey / ACCESS_KEY
                value = findIgnoreCase(extra, name);
            }
            if (value == null || value.isNull()) {
                continue;
            }
            if (value.isTextual()) {
                String text = value.asText();
                if (text != null && !text.isBlank()) {
                    return true;   // 含 **** 掩码
                }
            } else if (value.isBoolean() || value.isNumber()) {
                if (value.asBoolean()) {
                    return true;
                }
            }
        }
        return false;
    }

    private JsonNode findIgnoreCase(JsonNode node, String name) {
        var fields = node.fields();
        while (fields.hasNext()) {
            var entry = fields.next();
            if (entry.getKey().equalsIgnoreCase(name)) {
                return entry.getValue();
            }
        }
        return null;
    }

    /** extraConfig 中的布尔开关是否显式为 true */
    private boolean isExtraTrue(JsonNode extra, String name) {
        if (extra == null || extra.isMissingNode()) {
            return false;
        }
        JsonNode value = extra.get(name);
        if (value == null) {
            value = findIgnoreCase(extra, name);
        }
        if (value == null || value.isNull()) {
            return false;
        }
        if (value.isBoolean()) {
            return value.asBoolean();
        }
        return "true".equalsIgnoreCase(value.asText()) || "1".equals(value.asText());
    }

    /**
     * Base URL 是否指向 DeepSeek 官方域名。
     *
     * <p>用 URI 解析取 host 严格比较，避免 {@code https://proxy.example.com/api.deepseek.com}
     * 这类「路径里带域名」的中转地址被误判成官方（与 {@code DeepSeekToolsApiClient} 的判定一致）。</p>
     */
    private boolean isOfficialHost(String baseUrl) {
        if (baseUrl == null || baseUrl.isBlank()) {
            return false;
        }
        try {
            String host = java.net.URI.create(baseUrl.trim()).getHost();
            if (host == null) {
                return false;
            }
            ProviderProfile deepseek = ProviderRegistry.findByTemplate("deepseek");
            return deepseek != null && deepseek.getHostDetection().matchesHost(host);
        } catch (Exception e) {
            return false;
        }
    }

    /** 供日志/调试用：把能力结论压成一行（排查「界面说能发、实际被拦」时很有用） */
    public static String summarize(ProviderCapabilityVO vo) {
        if (vo == null) {
            return "null";
        }
        return String.format(Locale.ROOT, "%s(vision=%s,search=%s,balance=%s,files=%s,price=%s)",
                vo.getTemplate(),
                vo.getVision() == null ? "-" : vo.getVision().getState(),
                vo.getSearch() == null ? "-" : vo.getSearch().getState(),
                vo.getBalance() == null ? "-" : vo.getBalance().getState(),
                vo.getFilesApi() == null ? "-" : vo.getFilesApi().getState(),
                vo.getPricingMode());
    }

    /** 掩码常量（与 LLMProviderController 的 MASK 保持一致，此处仅作说明用途） */
    public static String maskConstant() {
        return MASK;
    }
}
