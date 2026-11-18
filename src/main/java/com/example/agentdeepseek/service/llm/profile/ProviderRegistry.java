package com.example.agentdeepseek.service.llm.profile;

import com.example.agentdeepseek.service.llm.profile.ProviderProfile.AdapterType;
import com.example.agentdeepseek.service.llm.profile.ProviderProfile.AuthSpec;
import com.example.agentdeepseek.service.llm.profile.ProviderProfile.BalanceProvider;
import com.example.agentdeepseek.service.llm.profile.ProviderProfile.BalanceSpec;
import com.example.agentdeepseek.service.llm.profile.ProviderProfile.DisplaySpec;
import com.example.agentdeepseek.service.llm.profile.ProviderProfile.FilesApiProvider;
import com.example.agentdeepseek.service.llm.profile.ProviderProfile.FilesApiSpec;
import com.example.agentdeepseek.service.llm.profile.ProviderProfile.HostSpec;
import com.example.agentdeepseek.service.llm.profile.ProviderProfile.ImageBlockFormat;
import com.example.agentdeepseek.service.llm.profile.ProviderProfile.PeakPricingSpec;
import com.example.agentdeepseek.service.llm.profile.ProviderProfile.PricingMode;
import com.example.agentdeepseek.service.llm.profile.ProviderProfile.SearchProvider;
import com.example.agentdeepseek.service.llm.profile.ProviderProfile.SearchSpec;
import com.example.agentdeepseek.service.llm.profile.ProviderProfile.UsageProtocols;
import com.example.agentdeepseek.service.llm.profile.ProviderProfile.VisionSpec;
import lombok.extern.slf4j.Slf4j;

import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Provider 能力档案注册表 —— <b>「哪个 Provider 有什么能力」的唯一查表入口</b>
 *
 * <h3>本类的定位</h3>
 * <p>
 * 纯静态查表工具（无 Spring Bean、无网络调用），与 {@code ModelCapabilityRules} 的组织方式一致。
 * 判定逻辑暂不迁入本类：{@code ProviderBalanceService} / {@code FilesApiClientManager} /
 * {@code SearchTool} / {@code ModelCapabilityRules} 仍是<b>实际的执行者</b>，
 * 本类只提供「这个 template 的档案是什么」。
 * </p>
 *
 * <h3>数据来源（逐条搬运自现有实现，不夹带任何"优化"）</h3>
 * <table border="1">
 *   <caption>字段 → 现有真相源</caption>
 *   <tr><th>字段</th><th>现有真相源</th></tr>
 *   <tr><td>{@code template / aliases}</td><td>{@code LLMClientManager.createClient} 的 switch case</td></tr>
 *   <tr><td>{@code usageProtocol}</td><td>{@code AbstractLLMClient} 适配矩阵 + 各适配器 {@code getUsageProtocol()}</td></tr>
 *   <tr><td>{@code auth}</td><td>{@code LLMWebClientManager.getAuthHeaderName / getAuthHeaderPrefix}</td></tr>
 *   <tr><td>{@code vision}</td><td>{@code ModelCapabilityConfig.defaultVisionRules()} + 各适配器 {@code stripImageDetail}</td></tr>
 *   <tr><td>{@code search}</td><td>{@code VolcengineSearchApiClient} / {@code DeepSeekToolsApiClient} / {@code MiniMaxCodingPlanClient} / {@code QwenClient.enableSearch}</td></tr>
 *   <tr><td>{@code balance}</td><td>{@code ProviderBalanceService} 的 5 个 {@code isXxx} + {@code queryXxx}</td></tr>
 *   <tr><td>{@code filesApi}</td><td>{@code FilesApiClientManager.createClient} 的 switch</td></tr>
 *   <tr><td>{@code hostDetection}</td><td>上述各处 {@code hostOf(baseUrl)} 的兜底判定</td></tr>
 *   <tr><td>{@code display}</td><td>前端 {@code provider-capability.ts} 的 {@code templateLabel / providerIconTone}</td></tr>
 * </table>
 *
 * <h3>⚠️ 搬运过程中发现的「现状不一致」（本类<b>如实记录</b>，不在本阶段修复）</h3>
 * <ol>
 *   <li><b>MiniMax 区域别名在创建客户端时不被识别</b>：
 *       {@code ProviderBalanceService} 认 {@code minimax-cn / minimax-intl / minimax-global}，
 *       但 {@code LLMClientManager.createClient} 只有 {@code case "minimax"}。
 *       后果：这些模板会掉进 {@code default} 分支被当成 {@code DeepSeekClient} ——
 *       余额按 MiniMax 查、对话却走 DeepSeek 协议。见 {@link #selfCheck()} 的漂移报告。</li>
 *   <li><b>MiMo 别名在创建客户端/认证头时不被识别</b>：
 *       视觉规则与余额都认 {@code xiaomi-mimo}，但 {@code createClient} 只有 {@code case "mimo"}，
 *       且 {@code getAuthHeaderName} 也只对 {@code mimo} 返回 {@code api-key}。
 *       后果：{@code xiaomi-mimo} 会拿到 {@code Authorization: Bearer}（而非 {@code api-key}）+ DeepSeekClient。</li>
 *   <li><b>前端对 Ollama 有比后端更严格的模型规则</b>：
 *       前端 {@code visionStateOf} 对 ollama 判 {@code llava|vision|vl|gemma3|...} 才放行，
 *       而后端 {@code ModelCapabilityConfig} 没有 ollama 规则（宽松放行）。
 *       本类记录<b>后端</b>行为（{@code hasModelRule=false}），前端迁移见任务 T10。</li>
 *   <li><b>MiniMax 的套餐形态无法从 URL 判定</b>：
 *       火山已确认 {@code /api/coding} 是次数套餐（故配 {@code quotaModeUrlKeywords}），
 *       但 MiniMax Coding Plan 的实际端点未取证，因此<b>不臆造</b>——保持 TOKEN 并留 TODO。</li>
 * </ol>
 *
 * <h3>维护约定</h3>
 * <p>新增 Provider：在本类加一个 {@code buildXxx()} 并放进 {@link #PROFILES}，
 * 然后跑 {@link #selfCheck()}（{@code ProviderRegistrySelfCheck} 测试类会调用），
 * 确认「别名不冲突 + 与适配器实际支持集无漂移」。</p>
 */
@Slf4j
public final class ProviderRegistry {

    /**
     * 全部内置档案。
     *
     * <p><b>顺序有意义</b>：{@link #visionRuleFor(String)} 的前缀匹配按此顺序遍历，
     * 必须与 {@code ModelCapabilityRules.findVisionRule} 的遍历顺序（即
     * {@code ModelCapabilityConfig.defaultVisionRules()} 的插入顺序）保持一致，
     * 否则同一个 code 可能命中不同规则（如 {@code minimax} 与 {@code mimo} 都以 {@code mi} 开头）。</p>
     */
    private static final List<ProviderProfile> PROFILES = buildProfiles();

    /** name（主名或别名，小写）→ 档案 */
    private static final Map<String, ProviderProfile> BY_NAME = indexByName(PROFILES);

    static {
        // 启动自检：只告警不抛错（档案表本身的问题不应阻断应用启动）
        for (String problem : selfCheck()) {
            log.warn("[ProviderRegistry] {}", problem);
        }
    }

    private ProviderRegistry() {
    }

    // ==================== 查询 API ====================

    /** 全部档案（顺序见 {@link #PROFILES} 注释，不用于界面展示） */
    public static List<ProviderProfile> all() {
        return PROFILES;
    }

    /**
     * 按模板标识查档案（主名或别名，大小写不敏感）。
     *
     * @param template {@code ProviderConfig.requestTemplate}；为空时调用方应先用 {@code code} 兜底
     * @return 档案；未注册返回 {@code null}
     */
    public static ProviderProfile findByTemplate(String template) {
        if (template == null || template.isBlank()) {
            return null;
        }
        return BY_NAME.get(template.trim().toLowerCase(Locale.ROOT));
    }

    /**
     * 按 Provider 编码查档案 —— <b>先精确、后前缀匹配</b>。
     *
     * <p>为什么要前缀匹配：视觉规则历史上就是按前缀匹配的（{@code code.startsWith(alias)}），
     * 所以 {@code minimax-cn} / {@code ark-custom} / {@code qwen-dashscope} 这类自定义 code
     * 必须仍能命中对应档案，否则迁移后会改变现有行为。</p>
     *
     * @param providerCode Provider 编码（如 {@code volcengine} / {@code volc-deepseek}）
     * @return 档案；未命中返回 {@code null}
     */
    public static ProviderProfile findByCode(String providerCode) {
        if (providerCode == null || providerCode.isBlank()) {
            return null;
        }
        String code = providerCode.trim().toLowerCase(Locale.ROOT);
        ProviderProfile exact = BY_NAME.get(code);
        if (exact != null) {
            return exact;
        }
        // 前缀匹配：顺序即 PROFILES 顺序，与 ModelCapabilityRules.findVisionRule 对齐
        for (ProviderProfile profile : PROFILES) {
            for (String name : profile.allNames()) {
                if (!name.isEmpty() && code.startsWith(name)) {
                    return profile;
                }
            }
        }
        return null;
    }

    /**
     * 取「模型级视觉规则」（仅当该 Provider 确实有模型级规则时返回）。
     *
     * <p>返回 {@code null} 的语义与 {@code ModelCapabilityRules.findVisionRule} 完全一致：
     * <b>「没有专门规则」→ 上层走全局 {@code defaultAllowVision}（宽松放行）</b>，
     * 而不是「不支持视觉」。两者不可混为一谈。</p>
     *
     * @param providerCode Provider 编码
     */
    public static VisionSpec visionRuleFor(String providerCode) {
        ProviderProfile profile = findByCode(providerCode);
        if (profile == null || profile.getVision() == null || !profile.getVision().isHasModelRule()) {
            return null;
        }
        return profile.getVision();
    }

    /**
     * 余额查询的判定顺序 —— <b>与 {@code ProviderBalanceService} 历史 if 链完全一致</b>。
     *
     * <p>⚠️ <b>不要"顺手优化"这个顺序</b>：判定是短路的，先命中先返回。
     * 历史上火山排在 deepseek 之前，意味着「template=deepseek 但 baseUrl 指向火山域名」
     * 这种配置会走<b>火山余额</b> —— 这是既成事实，迁移时必须保持，否则同一配置会开始查另一个账户。</p>
     */
    private static final List<String> BALANCE_DETECTION_ORDER =
            List.of("volcengine", "deepseek", "minimax", "mimo", "qwen");

    /**
     * 按「历史 if 链顺序」解析该 Provider 实际应使用的余额档案。
     *
     * <p>判定条件与旧实现逐字等价：<b>{@code 模板命中 OR baseUrl 主机命中}</b>。
     * host 兜底存在的意义是「用户把 template 起成自定义名，仍能识别出平台」，
     * 副作用是「走第三方中转时能力会跟着变」—— 两者都予以保留。</p>
     *
     * @param template 请求模板（{@code requestTemplate}，空时由调用方用 code 兜底）
     * @param baseUrl  Provider 的 Base URL（用于 host 兜底识别）
     * @return 余额档案；无任何命中返回 {@code null}（= 不支持余额查询）
     */
    public static ProviderProfile balanceCandidateFor(String template, String baseUrl) {
        return balanceCandidateFor(template, null, baseUrl);
    }

    /**
     * 带 {@code code} 兜底的余额档案解析 —— <b>修复「界面说能查、点下去说不支持」的错位</b>。
     *
     * <h3>为什么需要 code 兜底</h3>
     * <p>
     * 旧签名只按 {@code template} 精确匹配 + {@code baseUrl} host 兜底。当用户把 code 起成
     * {@code volcengine-custom}（后端又会把 {@code requestTemplate} 自动填成同一个名字）时：
     * </p>
     * <ul>
     *   <li>能力接口用 {@link #resolveFor} 判定 → 前缀命中火山 → 显示「可查询，缺 AK/SK」；</li>
     *   <li>{@code queryBalance} 用旧逻辑 → template 精确不中、baseUrl 若是中转域名也不中
     *       → 抛「不支持余额查询」。</li>
     * </ul>
     * <p>
     * 用户看到「可查询」点下去却报不支持 —— 正是本项目反复强调要避免的「界面与实际不一致」。
     * </p>
     *
     * <h3>⚠️ 判定顺序与既有结论不变</h3>
     * <p>
     * 前两段<b>完全照搬旧逻辑</b>（顺序短路 + host 兜底），{@code code} 兜底只在
     * 「旧逻辑判定为 NONE」时才生效 —— <b>纯增量</b>，不会让任何原本有结论的配置改走另一个账户。
     * 这也是 {@code BalanceDispatchEquivalenceTest} 仍能全绿的原因（它只传 template+baseUrl，
     * 不触发新分支）。
     * </p>
     *
     * @param template 请求模板
     * @param code     Provider 编码（可为 null：保持旧行为）
     * @param baseUrl  Base URL
     */
    public static ProviderProfile balanceCandidateFor(String template, String code, String baseUrl) {
        String host = hostOf(baseUrl);
        for (String name : BALANCE_DETECTION_ORDER) {
            ProviderProfile profile = BY_NAME.get(name);
            if (profile == null) {
                continue;
            }
            if (profile.matches(template) || (host != null && profile.getHostDetection().matchesHost(host))) {
                return profile;
            }
        }
        // 旧逻辑无结论 → 用 code 前缀兜底（与对话侧 resolveFor 的识别口径保持一致）
        if (code != null && !code.isBlank()) {
            ProviderProfile byCode = findByCode(code);
            if (byCode != null && byCode.getBalance() != null
                    && byCode.getBalance().getProvider() != BalanceProvider.NONE) {
                return byCode;
            }
        }
        return null;
    }

    /** 解析 baseUrl 的 host（解析失败返回 null，与旧实现的 {@code hostOf} 行为一致） */
    private static String hostOf(String baseUrl) {
        if (baseUrl == null || baseUrl.isBlank()) {
            return null;
        }
        try {
            return URI.create(baseUrl.trim()).getHost();
        } catch (Exception e) {
            return null;
        }
    }

    /*
      ==================== ⚠️ 模板级规格查询（仅测试/自检用，生产代码必须用 resolveFor） ====================

      下面这组便捷方法（pricingModeFor / filesApiFor / searchFor / balanceFor / authFor /
      adapterTypeFor / usageProtocolFor）语义都是「按模板<b>精确匹配</b>后取某一份规格」，
      与 findByTemplate 完全一致。它们共同的缺陷是<b>不做前缀、不用 code、不看 baseUrl</b>：

        pricingModeFor("volcengine-custom", "/api/coding/v3") → TOKEN   （实际应为 QUOTA）
        authFor("mimo-prod")                                  → Authorization + Bearer（实际应为 api-key）
        filesApiFor("deepseek-custom")                        → 未适配     （实际应为 DeepSeek Files API）

      审查阶段已把<b>全部生产消费方</b>改为 {@link #resolveFor}（模板 → 前缀 → code → baseUrl 兜底），
      因此本组方法目前只有测试与自检在用。保留的理由：
        ① 测试需要「给定名字直接取规格」的最短表达式，改用 resolveFor 反而啰嗦；
        ② 它们等价于「精确查表」这一基础能力，删掉会让测试退化成手写一堆期望常量。

      ⚠️ 两条铁律：
        1) <b>新增生产代码不要再调用本组方法</b>，一律用 resolveFor 拿到档案后取字段 ——
           否则「同一个平台，填在 template 还是 code 字段」又会得出两种结论，
           这正是本轮修掉的那类 bug（改一处、漏一处）。
        2) <b>不要给本组方法补兜底逻辑</b>。让它们保持「精确匹配」这一单纯语义，
           兜底只存在于 resolveFor 一处；两套 API 各自进化比合并成一套更危险。
     */

    /** 取档案的计费模式（按 baseUrl 自动区分同一 Provider 的两种计费形态）；未注册返回 {@link PricingMode#TOKEN} */
    public static PricingMode pricingModeFor(String template, String baseUrl) {
        ProviderProfile profile = findByTemplate(template);
        return profile == null ? PricingMode.TOKEN : profile.resolvePricingMode(baseUrl);
    }

    /** 取 Files API 规格；未注册返回「未适配」（安全默认：本地存储 + base64 降级） */
    public static FilesApiSpec filesApiFor(String template) {
        ProviderProfile profile = findByTemplate(template);
        return profile == null ? new FilesApiSpec() : profile.getFilesApi();
    }

    /** 取联网搜索规格；未注册返回「无官方搜索」（降级通用抓取） */
    public static SearchSpec searchFor(String template) {
        ProviderProfile profile = findByTemplate(template);
        return profile == null ? new SearchSpec() : profile.getSearch();
    }

    /** 取余额查询规格；未注册返回「不支持」（界面按钮灰掉） */
    public static BalanceSpec balanceFor(String template) {
        ProviderProfile profile = findByTemplate(template);
        return profile == null ? new BalanceSpec() : profile.getBalance();
    }

    /**
     * 消费方的<b>统一入口</b>：按「{@code requestTemplate} 精确优先、{@code code} 前缀兜底」解析档案。
     *
     * <h3>为什么需要它（修掉一处统一性遗漏）</h3>
     * <p>
     * 改动前 6 个消费方各自写同一段逻辑：
     * </p>
     * <pre>{@code
     * if (template == null || template.isBlank()) template = config.getCode();
     * ProviderProfile p = ProviderRegistry.findByTemplate(template);   // ← 只做精确匹配
     * }</pre>
     * <p>
     * 问题在于 {@code ProviderConfig} 的 {@code requestTemplate} 后端会自动填成 {@code code}
     * （见 {@code LLMProviderController#create}）。于是用户把 code 起成
     * {@code volcengine-custom} / {@code deepseek-official} 这类「平台名在前缀」的名字时，
     * {@code findByTemplate("volcengine-custom")} 精确查不到 → 档案为 {@code null}
     * → {@code createClient} 回退成 {@code DeepSeekClient}、能力全部判为「未适配」。
     * </p>
     * <p>
     * 本方法补上「code 前缀匹配」这一层，语义与 {@link #findByCode} 一致 ——
     * <b>同一个名字，不该因为填在 template 还是 code 字段而得到不同结论</b>。
     * </p>
     *
     * <h3>⚠️ 有意见不用于 Files API</h3>
     * <p>
     * {@code FilesApiClientManager} <b>刻意不用本方法</b>，仍走精确 {@code findByTemplate}。
     * 原因：Files API 决定「用户文件是否上传到第三方云端」，属于数据外发动作，
     * 口径必须比对话更严 —— <b>宁可不识别（降级本地存储）也不能靠前缀猜平台</b>。
     * 这与 v3.1.4 定下的「用户没选就不许替他选」一致。
     * </p>
     *
     * @param template 请求模板（{@code requestTemplate}，可为空）
     * @param code     Provider 编码（兜底用，支持前缀匹配）
     * @return 档案；两者都未命中返回 {@code null}
     */
    public static ProviderProfile resolveFor(String template, String code) {
        // ① template 精确匹配（模板是协议真相源，最高优先级）
        ProviderProfile byTemplate = findByTemplate(template);
        if (byTemplate != null) {
            return byTemplate;
        }
        /*
          ② template 走「前缀语义」再匹配一次。

          这一步是审查时补上的 —— 最初的实现只有 ①③，声称「code 前缀兜底」，
          但当用户把 template 手动填成 volcengine-custom、code 填成无关名字（如 ark-prod 之外的东西）时，
          ① 精确落空、③ 又依赖 code，结果仍返回 null。
          而 template 与 code 共享同一套名字空间（主名 + 别名），
          所以对 template 复用 findByCode 的前缀语义才是完整的。
         */
        ProviderProfile looseTemplate = findByCode(template);
        if (looseTemplate != null) {
            return looseTemplate;
        }
        // ③ code 兜底（template 为空或确实无法识别时）
        return findByCode(code);
    }

    /**
     * 带 {@code baseUrl} 兜底的档案解析 —— 覆盖「名字完全起不出平台，但域名指向平台」的配置。
     *
     * <h3>为什么需要 host 兜底（修掉一类静默失败）</h3>
     * <p>
     * 名字空间识别（{@link #resolveFor(String, String)}）只能覆盖「平台名在<b>前缀</b>」的
     * 自定义命名。用户把 code / template 起成 {@code my-gateway} / {@code 我的中转}
     * 这类完全不含平台名的名字时，三层名字匹配全部落空 → 返回 {@code null}：
     * </p>
     * <ul>
     *   <li>{@code createClient} → 回退 {@code DeepSeekClient}（对话可能能跑，但协议细节错）；</li>
     *   <li>{@code TokenUsageParser} → 退回 {@code TOKEN} → <b>套餐制被按 token 估出虚假金额</b>；</li>
     *   <li>视觉判定 → 拿不到模型级规则 → 落到 {@code defaultAllowVision=true} → <b>纯文本模型发图被静默丢弃</b>。</li>
     * </ul>
     * <p>
     * 而这些配置的 {@code baseUrl} 往往明明白白写着 {@code ark.cn-beijing.volces.com} ——
     * 域名比用户随手起的名字可靠得多。
     * </p>
     *
     * <h3>安全边界</h3>
     * <ol>
     *   <li><b>只在名字完全识别不出时才用</b>：前两层命中就返回，绝不覆盖用户显式选的档案。
     *       （用户显式写 {@code template=custom} 就一定是 custom，不会被 baseUrl 改写。）</li>
     *   <li><b>只认平台专属域名</b>：{@code api.deepseek.com} / {@code volces.com} /
     *       {@code aliyuncs.com} / {@code minimaxi.com} / {@code xiaomimimo.com}，
     *       它们不会出现在无关服务里，因此不存在「猜错平台」的风险。</li>
     *   <li><b>第三方中转域名不会被识别</b>（本来也不该被识别）→ 保持降级行为，与改动前一致。</li>
     * </ol>
     *
     * <h3>⚠️ Files API 依旧不用本方法</h3>
     * <p>
     * 数据外发动作的口径必须比对话更严（见 {@link #resolveFor(String, String)} 的说明），
     * {@code FilesApiClientManager} 仍坚持精确匹配 template。
     * </p>
     *
     * @param template 请求模板（可为空）
     * @param code     Provider 编码（可为空）
     * @param baseUrl  Base URL（可为空；仅用于最后兜底）
     * @return 档案；全部未命中返回 {@code null}
     */
    public static ProviderProfile resolveFor(String template, String code, String baseUrl) {
        ProviderProfile byName = resolveFor(template, code);
        if (byName != null) {
            return byName;
        }
        String host = hostOf(baseUrl);
        if (host == null) {
            return null;
        }
        for (ProviderProfile profile : PROFILES) {
            if (profile.getHostDetection() != null && profile.getHostDetection().matchesHost(host)) {
                return profile;
            }
        }
        return null;
    }

    /** 取认证头规格；未注册返回默认（{@code Authorization} + {@code Bearer }） */
    public static AuthSpec authFor(String template) {
        ProviderProfile profile = findByTemplate(template);
        return profile == null ? new AuthSpec() : profile.getAuth();
    }

    /**
     * 取该模板应使用的适配器类型 —— <b>{@code LLMClientManager.createClient} 的唯一选型依据</b>。
     *
     * <p>未注册模板返回 {@code null}：调用方应<b>明确告警</b>而不是静默回退。
     * 旧实现回退成 {@code DeepSeekClient}，表现为「请求能发出去但 400」，极难定位。</p>
     *
     * @param template 模板标识（{@code requestTemplate}，调用方已处理过 code 兜底）
     */
    public static AdapterType adapterTypeFor(String template) {
        ProviderProfile profile = findByTemplate(template);
        return profile == null ? null : profile.getAdapterType();
    }

    /**
     * 按 Provider 编码取适配器类型（先精确、后前缀匹配，与 {@link #findByCode} 同语义）。
     *
     * <p>用于「只拿得到 code、拿不到 template」的调用点（如工具上下文的 providerCode）。</p>
     */
    public static AdapterType adapterTypeForCode(String providerCode) {
        ProviderProfile profile = findByCode(providerCode);
        return profile == null ? null : profile.getAdapterType();
    }

    /** 取 usage 协议；未注册按 {@code openai}（绝大多数兼容服务都是这套） */
    public static String usageProtocolFor(String template) {
        ProviderProfile profile = findByTemplate(template);
        return profile == null ? UsageProtocols.OPENAI : profile.getUsageProtocol();
    }

    /** 按界面排序返回全部档案（用于「模板下拉」与文档表格生成） */
    public static List<ProviderProfile> orderedByDisplay() {
        List<ProviderProfile> list = new ArrayList<>(PROFILES);
        list.sort((a, b) -> Integer.compare(
                a.getDisplay() == null ? Integer.MAX_VALUE : a.getDisplay().getOrder(),
                b.getDisplay() == null ? Integer.MAX_VALUE : b.getDisplay().getOrder()));
        return list;
    }

    // ==================== 自检 ====================

    /**
     * 静态一致性自检（不含网络与 Spring 上下文）。
     *
     * <p>检查项：别名冲突、必填字段、视觉规则完整性、以及与适配器实际支持集的漂移。
     * 返回空列表表示通过。本方法被 {@code ProviderRegistrySelfCheck} 测试类直接调用。</p>
     *
     * @return 问题描述列表（每条一句话，可直接打日志或断言为空）
     */
    public static List<String> selfCheck() {
        List<String> problems = new ArrayList<>();

        // 1) 必填字段
        for (ProviderProfile profile : PROFILES) {
            if (profile.getTemplate() == null || profile.getTemplate().isBlank()) {
                problems.add("档案缺少 template：" + profile);
            }
            if (profile.getDisplay() == null || profile.getDisplay().getLabel() == null
                    || profile.getDisplay().getLabel().isBlank()) {
                problems.add("档案 [" + profile.getTemplate() + "] 缺少中文标签 display.label");
            }
        }

        // 2) 别名冲突（同一个名字被两个档案声明 → 查表结果取决于顺序，必须避免）
        Map<String, String> seen = new LinkedHashMap<>();
        for (ProviderProfile profile : PROFILES) {
            for (String name : profile.allNames()) {
                String owner = seen.putIfAbsent(name, profile.getTemplate());
                if (owner != null && !owner.equals(profile.getTemplate())) {
                    problems.add("模板标识 [" + name + "] 被两个档案同时声明：" + owner + " 与 " + profile.getTemplate());
                }
            }
        }

        // 3) 视觉规则完整性：声明了「有模型级规则」但三个列表都空 → 规则永远不会命中，等于白声明
        for (ProviderProfile profile : PROFILES) {
            VisionSpec vision = profile.getVision();
            if (vision != null && vision.isHasModelRule()
                    && vision.getIncludeKeywords().isEmpty()
                    && vision.getExcludeKeywords().isEmpty()
                    && vision.getExcludePrefixes().isEmpty()) {
                problems.add("档案 [" + profile.getTemplate()
                        + "] 声明了模型级视觉规则，但 include/exclude 三组关键词全为空");
            }
        }

        // 4) 适配器类型必须显式声明（null → createClient 无法选型）
        for (ProviderProfile profile : PROFILES) {
            if (profile.getAdapterType() == null) {
                problems.add("档案 [" + profile.getTemplate()
                        + "] 未声明 adapterType → LLMClientManager.createClient 无法确定适配器实现");
            }
        }

        // 5) 别名一致性：同一个档案的所有名字必须映射到同一个适配器实现
        //    （这是 T12 要根治的漂移 —— 档案声明了别名、适配器 switch 却没写，导致掉进 default）
        for (ProviderProfile profile : PROFILES) {
            AdapterType canonical = profile.getAdapterType();
            if (canonical == null) {
                continue;
            }
            for (String name : profile.allNames()) {
                AdapterType resolved = adapterTypeFor(name);
                if (resolved != canonical) {
                    problems.add("模板标识 [" + name + "] 解析出的适配器为 " + resolved
                            + "，但所属档案 [" + profile.getTemplate() + "] 声明为 " + canonical
                            + " → 别名与实现不同源");
                }
            }
        }

        // 6) 反向覆盖：每个适配器实现至少被一个档案使用（新增枚举值后忘记配置会在这里暴露）
        Set<AdapterType> used = new LinkedHashSet<>();
        for (ProviderProfile profile : PROFILES) {
            if (profile.getAdapterType() != null) {
                used.add(profile.getAdapterType());
            }
        }
        for (AdapterType type : AdapterType.values()) {
            if (!used.contains(type)) {
                problems.add("适配器类型 [" + type + "] 没有被任何档案声明 → 该实现无法被选用（仅 custom 复用 OPENAI 属正常）");
            }
        }

        return problems;
    }

    // ==================== 档案构建 ====================

    private static List<ProviderProfile> buildProfiles() {
        List<ProviderProfile> list = new ArrayList<>();
        // 顺序：前 5 个是历史上带「模型级视觉规则」的 Provider，
        // 必须排在前面以保持与 ModelCapabilityRules.findVisionRule 相同的前缀匹配优先级。
        list.add(buildDeepSeek());
        list.add(buildVolcengine());
        list.add(buildMiMo());
        list.add(buildMiniMax());
        list.add(buildQwen());
        list.add(buildGpt());
        list.add(buildOpenAI());
        list.add(buildAnthropic());
        list.add(buildOllama());
        list.add(buildCustom());
        return List.copyOf(list);
    }

    /**
     * DeepSeek 官方（{@code api.deepseek.com}）。
     * <p>视觉：仅 flash / vision / vl 系列；余额：官方 {@code /user/balance} 只需 API Key；
     * Files API：已适配；官方搜索：{@code /v1/tools/search} 免额外凭证但<b>要求官方域名</b>。</p>
     */
    private static ProviderProfile buildDeepSeek() {
        ProviderProfile p = new ProviderProfile();
        p.setTemplate("deepseek");
        p.setUsageProtocol(UsageProtocols.OPENAI);
        p.setAdapterType(AdapterType.DEEPSEEK);
        p.setAuth(new AuthSpec("Authorization", "Bearer "));

        VisionSpec vision = new VisionSpec();
        vision.setHasModelRule(true);
        vision.setIncludeKeywords(list("flash", "vision", "-vl"));
        vision.setDefaultAllow(false);
        vision.setStripImageDetail(false);
        vision.setBlockFormat(ImageBlockFormat.IMAGE_URL);
        p.setVision(vision);

        SearchSpec search = new SearchSpec();
        search.setProvider(SearchProvider.DEEPSEEK_OFFICIAL);
        // 免额外凭证（复用 Provider 的 API Key），但主机必须严格是官方域名，否则走中转时接口不可用
        search.setRequiredHostExact("api.deepseek.com");
        p.setSearch(search);

        BalanceSpec balance = new BalanceSpec();
        balance.setProvider(BalanceProvider.DEEPSEEK_OFFICIAL);
        balance.setApiKeyOnly(true);
        p.setBalance(balance);

        FilesApiSpec files = new FilesApiSpec();
        files.setProvider(FilesApiProvider.DEEPSEEK);
        p.setFilesApi(files);

        p.setPricingMode(PricingMode.TOKEN);

        /*
          ==================== 峰谷计价（DeepSeek 官方独有） ====================

          官方价格页（2026-11-18 抓取）：
            北京时间周一至周五（不含中国法定节假日）09:00-12:00、14:00-18:00 = 高峰时段；
            其余时段（含周末 / 节假日全天）= 空闲时段；空闲价 = 高峰价的 50%。

          ⚠️ 本档案只声明「这个平台的时段规则」，具体单价在 llm.pricing.models 里配：
             cache-hit-input / cache-miss-input / output   → 空闲价
             peak-cache-hit-input / peak-cache-miss-input / peak-output → 高峰价

          ★ 扩展方式（项目约定「像适配器一样加功能」）：
             其它平台要支持峰谷时，只需在自己的 buildXxx() 里补一段同样的 PeakPricingSpec
             （并在 yml 里配 peak-* 单价），TokenUsageParser 的计价链路一行都不用改。
         */
        PeakPricingSpec peak = new PeakPricingSpec();
        peak.setEnabled(true);
        peak.setZone("Asia/Shanghai");
        /*
          窗口 / 工作日刻意不写死：留空即继承 llm.pricing.peak-period.windows / workdays。
          默认值就是官网口径（09:00-12:00、14:00-18:00 + 周一至周五），所以行为不变；
          但用户改用 application.yml 调整时段时是真正生效的（否则配置成了死配置）。
         */
        peak.setHolidayRegion(PeakPricingSpec.REGION_CN);
        // 官网原文只说「周一至周五（不含法定节假日）」，未提调休上班日 → 默认严格按字面：周末一律空闲
        peak.setHonorMakeupWorkdays(false);
        p.setPeakPricing(peak);

        p.setHostDetection(new HostSpec("api.deepseek.com", list()));
        p.setDisplay(display("DeepSeek", "", 1));
        return p;
    }

    /**
     * 火山引擎方舟（豆包 / 方舟上的 DeepSeek、GLM、Kimi 等）。
     *
     * <p>视觉：{@code v4.1} 放行、{@code v4-pro / v4-flash} 拦截（实测结论，见
     * {@code ModelCapabilityConfig} 注释）；其余豆包/GLM/Kimi 由 defaultAllow 放行。</p>
     * <p>计费：普通 {@code /api/v3} 按 token，<b>Coding Plan 的 {@code /api/coding/v3} 是次数套餐</b>
     * → 由 {@code quotaModeUrlKeywords} 自动改判为 {@link PricingMode#QUOTA}。</p>
     */
    private static ProviderProfile buildVolcengine() {
        ProviderProfile p = new ProviderProfile();
        p.setTemplate("volcengine");
        p.setAliases(new LinkedHashSet<>(list("ark", "doubao")));
        p.setUsageProtocol(UsageProtocols.OPENAI);
        p.setAdapterType(AdapterType.VOLCENGINE);
        p.setAuth(new AuthSpec("Authorization", "Bearer "));

        VisionSpec vision = new VisionSpec();
        vision.setHasModelRule(true);
        // 实测（2026-11，/api/coding/v3 直接发 image_url 块）：
        //   deepseek-v4.1-flash / -economy → 支持（图片真实计入 prompt_tokens）
        //   deepseek-v4-pro / v4-flash     → 纯文本（图片被静默丢弃）
        // excludeKeywords 优先于 include；"v4.1-flash" 不含连续子串 "v4-flash"，故 economy 版不会被误伤
        vision.setIncludeKeywords(list("vision", "-vl", "vlm", "v4.1"));
        vision.setExcludeKeywords(list("v4-pro", "v4-flash"));
        vision.setDefaultAllow(true);
        vision.setStripImageDetail(false);
        vision.setBlockFormat(ImageBlockFormat.IMAGE_URL);
        p.setVision(vision);

        SearchSpec search = new SearchSpec();
        search.setProvider(SearchProvider.VOLCENGINE);
        // 搜索 Key 在独立的搜索控制台，与方舟 Ark API Key 不通用
        search.setCredentialKeys(list("searchApiKey"));
        search.setEnvKeys(list("VOLCENGINE_SEARCH_API_KEY", "WEB_SEARCH_API_KEY"));
        // 搜索接口本身不额外要求域名（"这是不是火山 Provider" 由 hostDetection 判定）
        p.setSearch(search);

        BalanceSpec balance = new BalanceSpec();
        balance.setProvider(BalanceProvider.VOLCENGINE_BILLING);
        // 账号级 AK/SK（与 Ark API Key 是两套凭证）；费用中心 QueryBalanceAcct 走 V4 签名
        balance.setCredentialKeys(list("accessKey", "access_key", "ak", "secretKey", "secret_key", "sk"));
        p.setBalance(balance);

        FilesApiSpec files = new FilesApiSpec();
        files.setProvider(FilesApiProvider.NONE);
        p.setFilesApi(files);

        p.setPricingMode(PricingMode.TOKEN);
        // Coding Plan 是「200 元 / 9 万次」的次数套餐，不存在「元 / 百万 token」单价
        p.setQuotaModeUrlKeywords(list("/api/coding"));
        p.setHostDetection(new HostSpec(null, list("volces.com")));
        p.setDisplay(display("火山方舟", "is-volcengine", 3));
        return p;
    }

    /**
     * 小米 MiMo。
     * <p>视觉：v2.5 系列与 Omni（全模态）；余额：<b>只能用控制台 Cookie</b>（推理 Key 无法查）。</p>
     * <p>⚠️ 别名 {@code xiaomi-mimo} 在 {@code createClient} / {@code getAuthHeaderName} 中<b>没有</code>对应 case，
     * 属于已知漂移（见类头说明）。</p>
     */
    private static ProviderProfile buildMiMo() {
        ProviderProfile p = new ProviderProfile();
        p.setTemplate("mimo");
        p.setAliases(new LinkedHashSet<>(list("xiaomi-mimo")));
        p.setUsageProtocol(UsageProtocols.OPENAI);
        // MiMo 用自定义头 api-key，且无 Bearer 前缀
        p.setAdapterType(AdapterType.MIMO);
        p.setAuth(new AuthSpec("api-key", null));

        VisionSpec vision = new VisionSpec();
        vision.setHasModelRule(true);
        vision.setIncludeKeywords(list("v2.5", "vision", "-vl", "omni"));
        vision.setDefaultAllow(false);
        vision.setStripImageDetail(true);
        vision.setBlockFormat(ImageBlockFormat.IMAGE_URL);
        p.setVision(vision);

        SearchSpec search = new SearchSpec();
        search.setProvider(SearchProvider.NONE);
        p.setSearch(search);

        BalanceSpec balance = new BalanceSpec();
        balance.setProvider(BalanceProvider.MIMO_COOKIE);
        balance.setCredentialKeys(list("mimoCookie", "mimo_cookie", "cookie"));
        p.setBalance(balance);

        FilesApiSpec files = new FilesApiSpec();
        files.setProvider(FilesApiProvider.NONE);
        p.setFilesApi(files);

        p.setPricingMode(PricingMode.TOKEN);
        p.setHostDetection(new HostSpec(null, list("xiaomimimo.com")));
        p.setDisplay(display("MiMo", "", 9));
        return p;
    }

    /**
     * MiniMax（含区域别名）。
     * <p>视觉：M3 与 VL / abab-vl 支持，M2.x 纯文本；余额：Token Plan 额度（{@code codingPlanKey}）。</p>
     * <p>⚠️ 三个区域别名在 {@code createClient} 中没有对应 case（已知漂移）。</p>
     * <p>⚠️ TODO：MiniMax Coding Plan 若为次数套餐，应补 {@code quotaModeUrlKeywords}；
     * 现有代码未取证其端点，故不臆造，保持 {@link PricingMode#TOKEN}。</p>
     */
    private static ProviderProfile buildMiniMax() {
        ProviderProfile p = new ProviderProfile();
        p.setTemplate("minimax");
        p.setAliases(new LinkedHashSet<>(list("minimax-cn", "minimax-intl", "minimax-global")));
        p.setUsageProtocol(UsageProtocols.OPENAI);
        p.setAdapterType(AdapterType.MINIMAX);
        p.setAuth(new AuthSpec("Authorization", "Bearer "));

        VisionSpec vision = new VisionSpec();
        vision.setHasModelRule(true);
        vision.setIncludeKeywords(list("m3", "vision", "-vl", "abab"));
        vision.setExcludeKeywords(list("-m2"));
        vision.setExcludePrefixes(list("m2"));
        vision.setDefaultAllow(false);
        vision.setStripImageDetail(true);
        vision.setBlockFormat(ImageBlockFormat.IMAGE_URL);
        p.setVision(vision);

        SearchSpec search = new SearchSpec();
        search.setProvider(SearchProvider.MINIMAX_PLAN);
        search.setCredentialKeys(list("codingPlanKey", "coding_plan_key", "searchApiKey"));
        search.setEnvKeys(list("MINIMAX_CODE_PLAN_KEY", "MINIMAX_CODING_API_KEY"));
        p.setSearch(search);

        BalanceSpec balance = new BalanceSpec();
        balance.setProvider(BalanceProvider.MINIMAX_PLAN);
        balance.setCredentialKeys(list("codingPlanKey", "coding_plan_key"));
        p.setBalance(balance);

        FilesApiSpec files = new FilesApiSpec();
        files.setProvider(FilesApiProvider.NONE);
        p.setFilesApi(files);

        p.setPricingMode(PricingMode.TOKEN);
        p.setHostDetection(new HostSpec(null, list("minimaxi.com", "minimax.io")));
        p.setDisplay(display("MiniMax", "", 8));
        return p;
    }

    /**
     * 通义千问 / 阿里云百炼 DashScope。
     * <p>视觉：Qwen-VL / Omni / Plus 系列（官方声明 qwen3.6-plus、qwen3.7-plus 支持图片理解），
     * {@code qwen-max} 各代差异大，不确定不放行。</p>
     * <p>搜索：对话级 {@code enable_search}；余额：阿里云账号级 AK/SK（BssOpenApi）。</p>
     */
    private static ProviderProfile buildQwen() {
        ProviderProfile p = new ProviderProfile();
        p.setTemplate("qwen");
        p.setAliases(new LinkedHashSet<>(list("dashscope", "tongyi")));
        p.setUsageProtocol(UsageProtocols.OPENAI);
        p.setAdapterType(AdapterType.QWEN);
        p.setAuth(new AuthSpec("Authorization", "Bearer "));

        VisionSpec vision = new VisionSpec();
        vision.setHasModelRule(true);
        vision.setIncludeKeywords(list("-vl", "vl-", "omni", "vision", "plus"));
        vision.setDefaultAllow(false);
        vision.setStripImageDetail(true);
        vision.setBlockFormat(ImageBlockFormat.IMAGE_URL);
        p.setVision(vision);

        SearchSpec search = new SearchSpec();
        search.setProvider(SearchProvider.QWEN_DIALOG);
        // enableSearch 是布尔开关（不是凭证），searchApiKey 是可选凭证；任一出现即视为已就绪
        search.setCredentialKeys(list("enableSearch", "searchApiKey"));
        p.setSearch(search);

        BalanceSpec balance = new BalanceSpec();
        balance.setProvider(BalanceProvider.ALIYUN_BILLING);
        balance.setCredentialKeys(list("accessKey", "access_key", "ak", "secretKey", "secret_key", "sk"));
        p.setBalance(balance);

        FilesApiSpec files = new FilesApiSpec();
        files.setProvider(FilesApiProvider.NONE);
        p.setFilesApi(files);

        p.setPricingMode(PricingMode.TOKEN);
        p.setHostDetection(new HostSpec(null, list("aliyuncs.com")));
        p.setDisplay(display("通义千问", "", 6));
        return p;
    }

    /**
     * OpenAI 官方 GPT / o 系列（官方专精模板）。
     * <p>与 {@code openai} 公共契约的区别：本模板<b>会下发官方专有参数</b>
     * （{@code max_completion_tokens}、{@code reasoning_effort}、{@code stream_options}），
     * 因此只适合官方或官方代理，<b>不要配到第三方中转站</b>。</p>
     */
    private static ProviderProfile buildGpt() {
        ProviderProfile p = new ProviderProfile();
        p.setTemplate("gpt");
        p.setAliases(new LinkedHashSet<>(list("gpt-official", "chatgpt")));
        p.setUsageProtocol(UsageProtocols.OPENAI);
        p.setAdapterType(AdapterType.GPT);
        p.setAuth(new AuthSpec("Authorization", "Bearer "));

        // 无模型级视觉规则（宽松放行）：由 gpt-5 / gpt-6 等模型自身决定
        VisionSpec vision = new VisionSpec();
        vision.setHasModelRule(false);
        vision.setDefaultAllow(true);
        vision.setBlockFormat(ImageBlockFormat.IMAGE_URL);
        p.setVision(vision);

        p.setSearch(new SearchSpec());
        p.setBalance(new BalanceSpec());
        p.setFilesApi(new FilesApiSpec());
        p.setPricingMode(PricingMode.TOKEN);
        p.setDisplay(display("GPT", "is-openai", 2));
        return p;
    }

    /**
     * OpenAI 公共契约（Azure / 中转站 / 自建网关）。
     * <p><b>故意不下发任何厂商专有参数</b>：第三方网关可能严格校验未知字段而 400。</p>
     */
    private static ProviderProfile buildOpenAI() {
        ProviderProfile p = new ProviderProfile();
        p.setTemplate("openai");
        p.setUsageProtocol(UsageProtocols.OPENAI);
        p.setAdapterType(AdapterType.OPENAI);
        p.setAuth(new AuthSpec("Authorization", "Bearer "));

        VisionSpec vision = new VisionSpec();
        vision.setHasModelRule(false);
        vision.setDefaultAllow(true);
        vision.setBlockFormat(ImageBlockFormat.IMAGE_URL);
        p.setVision(vision);

        p.setSearch(new SearchSpec());
        p.setBalance(new BalanceSpec());
        p.setFilesApi(new FilesApiSpec());
        p.setPricingMode(PricingMode.TOKEN);
        p.setDisplay(display("OpenAI 兼容", "is-openai", 4));
        return p;
    }

    /**
     * Anthropic Messages 协议（官方 / Claude 兼容层 / 中转站）。
     * <p>图片块必须转 {@code image/source}；认证用 {@code x-api-key} + 必需头 {@code anthropic-version}。</p>
     */
    private static ProviderProfile buildAnthropic() {
        ProviderProfile p = new ProviderProfile();
        p.setTemplate("anthropic");
        p.setUsageProtocol(UsageProtocols.ANTHROPIC);
        p.setAdapterType(AdapterType.ANTHROPIC);
        p.setAuth(new AuthSpec("x-api-key", null));

        VisionSpec vision = new VisionSpec();
        vision.setHasModelRule(false);
        vision.setDefaultAllow(true);
        vision.setBlockFormat(ImageBlockFormat.IMAGE_SOURCE);
        p.setVision(vision);

        p.setSearch(new SearchSpec());
        p.setBalance(new BalanceSpec());

        FilesApiSpec files = new FilesApiSpec();
        files.setProvider(FilesApiProvider.ANTHROPIC);
        p.setFilesApi(files);

        p.setPricingMode(PricingMode.TOKEN);
        p.setDisplay(display("Claude", "is-anthropic", 5));
        return p;
    }

    /**
     * 本机 / 内网 Ollama。
     * <p>usage 走 NDJSON（{@code done=true} 行）；图片转 {@code messages[].images[]}（纯 base64）；
     * <b>本地免费</b>，故 {@link PricingMode#FREE}。</p>
     * <p>⚠️ 注意：前端对 ollama 有比后端更严格的模型规则（见类头「现状不一致」第 3 条），
     * 本档案记录的是<b>后端行为</b>（宽松放行）。</p>
     */
    private static ProviderProfile buildOllama() {
        ProviderProfile p = new ProviderProfile();
        p.setTemplate("ollama");
        p.setUsageProtocol(UsageProtocols.OLLAMA);
        p.setAdapterType(AdapterType.OLLAMA);
        p.setAuth(new AuthSpec("Authorization", null));

        VisionSpec vision = new VisionSpec();
        vision.setHasModelRule(false);
        vision.setDefaultAllow(true);
        vision.setBlockFormat(ImageBlockFormat.OLLAMA_IMAGES);
        p.setVision(vision);

        p.setSearch(new SearchSpec());
        p.setBalance(new BalanceSpec());
        p.setFilesApi(new FilesApiSpec());
        p.setPricingMode(PricingMode.FREE);
        p.setDisplay(display("Ollama", "is-local", 7));
        return p;
    }

    /**
     * 自定义（最小 OpenAI 兼容模式，不发送 thinking 等特有参数）。
     * <p>认证头是 {@code Authorization} 但<b>无前缀</b>（发裸 key）—— 与 {@code openai} 不同，
     * 因此必须单独建档而不是复用 {@code openai}。</p>
     */
    private static ProviderProfile buildCustom() {
        ProviderProfile p = new ProviderProfile();
        p.setTemplate("custom");
        p.setUsageProtocol(UsageProtocols.OPENAI);
        // custom 复用 OpenAIClient（最小兼容模式，不发送 thinking 等特有参数）；
        // 与 openai 的唯一差别在认证头：custom 发裸 key（无 Bearer 前缀）
        p.setAdapterType(AdapterType.OPENAI);
        p.setAuth(new AuthSpec("Authorization", null));

        VisionSpec vision = new VisionSpec();
        vision.setHasModelRule(false);
        vision.setDefaultAllow(true);
        vision.setBlockFormat(ImageBlockFormat.IMAGE_URL);
        p.setVision(vision);

        p.setSearch(new SearchSpec());
        p.setBalance(new BalanceSpec());
        p.setFilesApi(new FilesApiSpec());
        p.setPricingMode(PricingMode.TOKEN);
        p.setDisplay(display("自定义", "", 10));
        return p;
    }

    // ==================== 内部工具 ====================

    private static List<String> list(String... items) {
        return new ArrayList<>(Arrays.asList(items));
    }

    private static DisplaySpec display(String label, String iconTone, int order) {
        DisplaySpec spec = new DisplaySpec();
        spec.setLabel(label);
        spec.setIconTone(iconTone == null ? "" : iconTone);
        spec.setOrder(order);
        spec.setTemplateSelectable(true);
        return spec;
    }

    private static Map<String, ProviderProfile> indexByName(List<ProviderProfile> profiles) {
        Map<String, ProviderProfile> map = new LinkedHashMap<>();
        for (ProviderProfile profile : profiles) {
            for (String name : profile.allNames()) {
                map.putIfAbsent(name, profile);
            }
        }
        return map;
    }
}
