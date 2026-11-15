package com.example.agentdeepseek.service.llm.profile;

import lombok.Data;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * LLM Provider 能力档案 —— <b>一个 Provider 的全部「非通用」能力声明（唯一真相源）</b>
 *
 * <h3>为什么需要这个类</h3>
 * <p>
 * 「对话 API 兼容」只解决了协议层的通用性。除此之外，各 Provider 在<b>能力层</b>上几乎全部不同：
 * 图片能不能发、走哪种块格式、要不要剔除 {@code detail}、有没有 Files API、余额怎么查（要什么凭证）、
 * 有没有官方联网搜索、以及<b>到底按 token 计费还是按次数套餐计费</b>。
 * </p>
 * <p>
 * 现状是同一个能力在<b>四份副本</b>里各写一遍：
 * </p>
 * <ol>
 *   <li>后端各 service 的 if / switch 链（{@code ProviderBalanceService} / {@code FilesApiClientManager} /
 *       {@code SearchTool} / {@code ModelCapabilityRules}）；</li>
 *   <li>{@code application.yml} 的 {@code llm.model-capabilities.vision-rules}；</li>
 *   <li>{@code ModelCapabilityConfig.defaultVisionRules()} 里的代码默认值（与 ② 内容重复）；</li>
 *   <li>前端 {@code provider-capability.ts} 的 350 行推导副本。</li>
 * </ol>
 * <p>
 * 后果是「改一处、漏三处」，而漏掉不报错 —— 表现为「界面说能发图，实际图片被静默丢弃」
 * 或「切了 Provider 余额仍显示别家的数字」。本类把上述信息收敛成<b>一份可查表的数据</b>。
 * </p>
 *
 * <h3>本类的定位：只承载数据，不承载判定逻辑</h3>
 * <p>
 * 这是一个<b>纯数据载体</b>（POJO），不做任何网络调用、不注入 Spring Bean、不判断业务条件。
 * 判定逻辑仍然留在各自 service 里，只是从「散落的 if 链」改为「查本表 → 分派」。
 * 因此引入本类<b>不改变任何现有行为</b>，可以安全地分批迁移（见任务 T2~T8）。
 * </p>
 * <p>
 * 与之互补的另一半是 {@code LLMClient} 类头的「A/B/C/D 适配清单」：
 * 那份清单回答「<b>新增 Provider 要按什么顺序做什么</b>」（操作指南），
 * 本类回答「<b>这个 Provider 的每项能力具体是什么</b>」（数据事实）。
 * </p>
 *
 * <h3>与「协议层通用」的边界</h3>
 * <table border="1">
 *   <caption>协议层 vs 能力层</caption>
 *   <tr><th>层次</th><th>是否通用</th><th>载体</th><th>漏了会怎样</th></tr>
 *   <tr>
 *     <td>协议层（端点 / usage 协议 / thinking 参数 / 图片块格式 / SSE 解析）</td>
 *     <td>各 Provider 不同，但<b>已有钩子</b>（{@code AbstractLLMClient}）</td>
 *     <td>{@code AbstractLLMClient} abstract 方法 + 钩子覆写</td>
 *     <td>编译报错（必须实现 abstract）</td>
 *   </tr>
 *   <tr>
 *     <td>能力层（图片模型规则 / Files API / 余额 / 官方搜索 / 计费模式）</td>
 *     <td>各 Provider 不同，<b>此前没有任何钩子</b></td>
 *     <td>本类（{@code ProviderProfile} + {@code ProviderRegistry}）</td>
 *     <td><b>静默缺功能</b>（编译通过、测试通过，用户才发现）</td>
 *   </tr>
 * </table>
 *
 * <h3>计费模式为什么必须显式声明（{@link PricingMode}）</h3>
 * <p>
 * 历史 bug：{@code TokenPricingConfig} 只能表达「元 / 百万 token」一种形态，于是
 * 「这个模型没配单价」与「这个 Provider 根本不该按 token 估价」被混成了同一件事。
 * 实测后果：火山方舟 Coding Plan（200 元 / 9 万次的<b>次数套餐</b>）的 5396 万 token
 * 被按 DeepSeek 官网单价算出了一笔并不存在的花费。
 * 把 {@code pricingMode} 显式建模后，「套餐制永不估算金额」成为<b>声明出来的规则</b>，
 * 不再依赖「配没配单价」去猜。
 * </p>
 *
 * @see ProviderRegistry 查表入口（9 个内置模板的档案数据）
 */
@Data
public class ProviderProfile {

    /**
     * 主模板名（小写，唯一）。
     * <p>对应 {@code ProviderConfig.requestTemplate}；为空时调用方会用 {@code code} 兜底，
     * 这个兜底规则与 {@code LLMClientManager} 保持一致。</p>
     */
    private String template;

    /**
     * 历史别名（小写）。
     * <p>如方舟的 {@code ark} / {@code doubao}、千问的 {@code dashscope} / {@code tongyi}。
     * 别名必须保证不与其他主模板名冲突，否则同一模板会被解析成两个不同适配器
     * （{@code ProviderRegistry} 启动自检会检查这一点）。</p>
     */
    private Set<String> aliases = new LinkedHashSet<>();

    /**
     * 流式用量的承载协议：{@code openai} / {@code anthropic} / {@code ollama}。
     * <p>与 {@code LLMClient#getUsageProtocol()} 一一对应，此处冗余一份便于「查表即知」。</p>
     */
    private String usageProtocol = UsageProtocols.OPENAI;

    /**
     * 该模板应使用哪个适配器实现 —— <b>这是 {@code LLMClientManager.createClient} 的选型依据</b>。
     *
     * <h3>为什么把它也放进档案（T12 的根治点）</h3>
     * <p>
     * 改动前「能力档案」与「适配器选型」是两份独立清单：
     * {@code ProviderRegistry} 声明了别名 {@code xiaomi-mimo} / {@code minimax-cn} …，
     * 但 {@code createClient} 的 {@code switch} 只写了 {@code case "mimo"} / {@code case "minimax"}。
     * </p>
     * <p>
     * 后果是配了这些别名的 Provider <b>会掉进 {@code default} 分支被当成 DeepSeekClient</b>：
     * 能力按本档案判（说能发图、能查余额），对话却跑 DeepSeek 协议 ——
     * 「余额查 A 平台、对话跑 B 协议」的错位，而且编译通过、测试通过，只在用户那儿才暴露。
     * </p>
     * <p>
     * 把适配器类型收进档案后，「别名」与「实现」同源：档案声明了几个名字，
     * 这些名字就必然映射到同一个实现，<b>漂移在结构上不可能发生</b>，
     * 不再依赖「记得同步改两处」。
     * </p>
     */
    private AdapterType adapterType = AdapterType.DEEPSEEK;

    /** 认证头规格 */
    private AuthSpec auth = new AuthSpec();

    /** 图片识别能力 */
    private VisionSpec vision = new VisionSpec();

    /** 官方联网搜索能力 */
    private SearchSpec search = new SearchSpec();

    /** 余额 / 额度查询能力 */
    private BalanceSpec balance = new BalanceSpec();

    /** Files API（云端文件上传）能力 */
    private FilesApiSpec filesApi = new FilesApiSpec();

    /**
     * 计费模式（决定「要不要估算金额」）。
     * <p>默认 {@link PricingMode#TOKEN}：需要配单价才显示金额。
     * 套餐制 Provider 必须显式设为 {@link PricingMode#QUOTA}，否则界面会拿别家单价编造花费。</p>
     */
    private PricingMode pricingMode = PricingMode.TOKEN;

    /**
     * baseUrl 命中这些关键字（小写、包含匹配）时，计费模式改判为 {@link PricingMode#QUOTA}。
     * <p>
     * 用于<b>同一 Provider 存在两种计费形态</b>的情况：火山方舟普通调用按 token 付费
     * （{@code /api/v3}），而 Coding Plan 是次数套餐（{@code /api/coding/v3}）。
     * 仅靠 template 区分不开，必须看 baseUrl。
     * </p>
     * <p>为空表示该 Provider 不存在「按地址切换计费形态」的情况。</p>
     */
    private List<String> quotaModeUrlKeywords = new ArrayList<>();

    /** 界面展示规格（中文标签 / 配色 / 下拉排序） */
    private DisplaySpec display = new DisplaySpec();

    /**
     * 主机名兜底识别规则（baseUrl 的 host 命中即视为该模板）。
     * <p>
     * 现状里「能力判定」不只比 {@code requestTemplate}，还会拿 {@code baseUrl} 的 host 兜底：
     * {@code ProviderBalanceService} 的 5 个 {@code isXxx} 全部带 host 兜底，
     * {@code VolcengineSearchApiClient} / {@code MiniMaxCodingPlanClient} 同样如此。
     * 这样做的动机是「用户把 code / template 起成自定义名，仍能识别出平台」，
     * 但副作用是<b>走第三方中转时能力会跟着变</b>（如 DeepSeek 官方搜索在非官方域名下失效）。
     * 收进档案后，这条规则从「5 处各写一遍」变成「一处声明」。
     * </p>
     */
    private HostSpec hostDetection = new HostSpec();

    // ==================== 纯查询方法（无副作用，不依赖任何外部服务） ====================

    /**
     * 该档案是否匹配给定的模板标识（主名或任分别名，大小写不敏感）。
     *
     * @param template 模板标识（{@code requestTemplate}，调用方已处理过 code 兜底）
     */
    public boolean matches(String template) {
        if (template == null || template.isBlank()) {
            return false;
        }
        String key = template.trim().toLowerCase(Locale.ROOT);
        if (this.template != null && this.template.equals(key)) {
            return true;
        }
        return aliases != null && aliases.contains(key);
    }

    /**
     * 主模板名 + 全部别名（小写去重），用于注册表登记与冲突检测。
     */
    public Set<String> allNames() {
        Set<String> names = new LinkedHashSet<>();
        if (template != null && !template.isBlank()) {
            names.add(template.toLowerCase(Locale.ROOT));
        }
        if (aliases != null) {
            for (String alias : aliases) {
                if (alias != null && !alias.isBlank()) {
                    names.add(alias.trim().toLowerCase(Locale.ROOT));
                }
            }
        }
        return names;
    }

    /**
     * 按 baseUrl 解析实际生效的计费模式。
     *
     * <p>默认返回 {@link #pricingMode}；若 {@link #quotaModeUrlKeywords} 命中则该地址下的调用
     * 属于套餐形态，返回 {@link PricingMode#QUOTA}。</p>
     *
     * @param baseUrl Provider 的 Base URL（可为 null）
     */
    public PricingMode resolvePricingMode(String baseUrl) {
        if (baseUrl != null && !baseUrl.isBlank() && quotaModeUrlKeywords != null
                && !quotaModeUrlKeywords.isEmpty()) {
            String url = baseUrl.trim().toLowerCase(Locale.ROOT);
            for (String keyword : quotaModeUrlKeywords) {
                if (keyword != null && !keyword.isBlank() && url.contains(keyword.trim().toLowerCase(Locale.ROOT))) {
                    return PricingMode.QUOTA;
                }
            }
        }
        return pricingMode == null ? PricingMode.TOKEN : pricingMode;
    }

    // ==================== 嵌套规格 ====================

    /** usage 协议常量（避免各处散写字符串字面量） */
    public static final class UsageProtocols {
        public static final String OPENAI = "openai";
        public static final String ANTHROPIC = "anthropic";
        public static final String OLLAMA = "ollama";

        private UsageProtocols() {
        }
    }

    /**
     * 该类 Provider 的计费形态。
     */
    public enum PricingMode {
        /**
         * 按 token 计价（元 / 百万 token）。
         * <p>必须在 {@code llm.pricing.models} 配置单价才显示金额；未配置时显示「—」（不是 ¥0）。</p>
         */
        TOKEN,

        /**
         * 套餐 / 次数制（如火山 Coding Plan 200 元 9 万次、MiniMax Coding Plan）。
         * <p><b>永不估算金额</b>：这类 Provider 不存在「元 / 百万 token」单价，
         * 硬套别家单价只会得出一个看似合理、实则完全错误的数字。
         * 界面只展示 token 与缓存命中率这类真实观测值。</p>
         */
        QUOTA,

        /**
         * 本地部署 / 免费（如 Ollama）。
         * <p>不产生费用，界面不显示金额，也不会因「未配单价」而显示「—」。</p>
         */
        FREE
    }

    /**
     * 适配器实现类型（与 {@code com.example.agentdeepseek.service.llm} 下的 Client 类一一对应）。
     *
     * <p>用枚举而不是字符串类名：拼错会在编译期报错，而不是在运行时掉进
     * {@code createClient} 的 {@code default} 分支（那个分支会静默回退成 DeepSeekClient，
     * 表现为「请求能发出去但 400」，极难定位）。</p>
     */
    public enum AdapterType {
        /** {@code DeepSeekClient}：DeepSeek 官方 */
        DEEPSEEK,

        /** {@code GptClient}：OpenAI 官方专精（下发 max_completion_tokens / reasoning_effort / stream_options） */
        GPT,

        /** {@code OpenAIClient}：公共契约（不下发任何厂商专有参数，适合中转站 / 自建网关） */
        OPENAI,

        /** {@code AnthropicClient}：Messages 协议（图片转 image/source，x-api-key 认证） */
        ANTHROPIC,

        /** {@code OllamaClient}：NDJSON 流，图片转 messages[].images[] */
        OLLAMA,

        /** {@code MiMoClient}：小米 MiMo（api-key 头，剔除 detail） */
        MIMO,

        /** {@code MiniMaxClient}：MiniMax（剔除 detail，支持区域域名） */
        MINIMAX,

        /** {@code QwenClient}：通义千问（enableSearch / thinkingBudget 扩展参数） */
        QWEN,

        /** {@code VolcengineArkClient}：火山方舟（imageDetail 归一、reasoningEffort 系列） */
        VOLCENGINE
    }

    /**
     * 认证头规格。
     *
     * <p>现状（{@code LLMWebClientManager} 两个独立 switch，T12 将合一）：</p>
     * <ul>
     *   <li>{@code anthropic} → {@code x-api-key}，无前缀</li>
     *   <li>{@code mimo} → {@code api-key}，无前缀</li>
     *   <li>{@code ollama} / {@code custom} → {@code Authorization}，<b>无前缀（发裸 key）</b></li>
     *   <li>其余 → {@code Authorization} + {@code Bearer } 前缀</li>
     * </ul>
     * <p>
     * ⚠️ 注意：{@code LLMWebClientManager} 原注释写「ollama / custom 无前缀时跳过」，
     * 但代码实际是「prefix 为 null 时发送裸 key」（见 {@code LLMWebClientManager} 第 174~178 行）。
     * 此处以<b>代码行为</b>为准，不搬错误注释。
     * </p>
     */
    @Data
    public static class AuthSpec {
        /** 认证头名称（默认 {@code Authorization}） */
        private String headerName = "Authorization";

        /**
         * 认证头值前缀；{@code null} 表示「不加前缀，直接放 apiKey」（不是「不发送」）。
         */
        private String headerPrefix = "Bearer ";

        public AuthSpec() {
        }

        public AuthSpec(String headerName, String headerPrefix) {
            this.headerName = headerName;
            this.headerPrefix = headerPrefix;
        }
    }

    /**
     * 主机名识别规则（baseUrl 的 host 命中即视为该模板）。
     *
     * <p>两者都可为空：{@link #exact} 用于 DeepSeek（必须严格等于 {@code api.deepseek.com}），
     * {@link #suffixes} 用于「火山 volces.com」「阿里云 aliyuncs.com」「MiniMax 双域名」这类后缀判定。</p>
     */
    @Data
    public static class HostSpec {
        /** 要求 host <b>严格等于</b>该值（小写比较）；为空表示不用此规则 */
        private String exact;

        /** 要求 host <b>以任一值结尾</b>（小写比较）；空集合表示不用此规则 */
        private List<String> suffixes = new ArrayList<>();

        public HostSpec() {
        }

        public HostSpec(String exact, List<String> suffixes) {
            this.exact = exact;
            this.suffixes = suffixes;
        }

        /** 给定 host 是否命中本规则（{@code host} 应为小写；null/空返回 false） */
        public boolean matchesHost(String host) {
            if (host == null || host.isBlank()) {
                return false;
            }
            String h = host.trim().toLowerCase(Locale.ROOT);
            if (exact != null && !exact.isBlank() && h.equals(exact.trim().toLowerCase(Locale.ROOT))) {
                return true;
            }
            if (suffixes != null) {
                for (String suffix : suffixes) {
                    if (suffix != null && !suffix.isBlank()
                            && h.endsWith(suffix.trim().toLowerCase(Locale.ROOT))) {
                        return true;
                    }
                }
            }
            return false;
        }
    }

    /**
     * 图片识别能力规格。
     *
     * <p><b>四层差异必须一起看</b>（这是历史上踩坑最多的地方）：</p>
     * <ol>
     *   <li>{@link #protocolSupported}：协议层是否支持图片输入（当前所有已适配模板都支持）；</li>
     *   <li>{@link #hasModelRule} + 三个关键词列表：模型级判定（同一 Provider 内不同模型能力不同）；</li>
     *   <li>{@link #stripImageDetail}：是否必须剔除 {@code image_url.detail}（厂商未声明该字段，严格校验会 400）；</li>
     *   <li>{@link #blockFormat}：内部统一产出 OpenAI 风格块，各适配器如何转换。</li>
     * </ol>
     */
    @Data
    public static class VisionSpec {
        /** 协议层是否支持图片输入（false 时前端直接判「不支持」） */
        private boolean protocolSupported = true;

        /** 是否存在模型级判定规则（对应 {@code ModelCapabilityConfig.VisionRule}） */
        private boolean hasModelRule = false;

        /** 命中任一关键词（包含匹配，小写）→ 支持视觉 */
        private List<String> includeKeywords = new ArrayList<>();

        /** 命中任一关键词（包含匹配，小写）→ 不支持视觉（<b>优先级高于 include</b>） */
        private List<String> excludeKeywords = new ArrayList<>();

        /** 命中任一前缀（前缀匹配，小写）→ 不支持视觉（<b>优先级高于 include</b>） */
        private List<String> excludePrefixes = new ArrayList<>();

        /** include / exclude 都未命中时的兜底结果 */
        private boolean defaultAllow = true;

        /** 是否必须剔除 {@code image_url.detail}（Qwen / MiniMax / MiMo 需要） */
        private boolean stripImageDetail = false;

        /** 内部块 → 该协议块的转换方式 */
        private ImageBlockFormat blockFormat = ImageBlockFormat.IMAGE_URL;
    }

    /**
     * 图片块格式（内部链路统一产出 OpenAI 的 {@code image_url} 块，各适配器负责转换）。
     */
    public enum ImageBlockFormat {
        /** OpenAI 风格，原样透传：{@code {"type":"image_url","image_url":{"url","detail"}}} */
        IMAGE_URL,

        /** Anthropic Messages：{@code {"type":"image","source":{"type","media_type","data"|"url"}}} */
        IMAGE_SOURCE,

        /** Ollama：{@code messages[].images[]}（纯 base64，无 {@code data:} 前缀） */
        OLLAMA_IMAGES
    }

    /**
     * 官方联网搜索规格。
     *
     * <p>不满足条件时<b>不是「不能联网」</b>，而是降级为通用抓取（Bing / 百度 / Jina / 本地），
     * 界面文案必须区分（否则会被读成「完全不可用」）。</p>
     */
    @Data
    public static class SearchSpec {
        /** 官方搜索实现类型；{@link SearchProvider#NONE} 表示只能走通用抓取 */
        private SearchProvider provider = SearchProvider.NONE;

        /** 需要从 Provider 扩展配置（extraConfig）中读取的凭证键（任一命中即视为已配置） */
        private List<String> credentialKeys = new ArrayList<>();

        /** 环境变量兜底键（任一命中即视为已配置） */
        private List<String> envKeys = new ArrayList<>();

        /** 要求 baseUrl 主机名<b>严格等于</b>该值（DeepSeek 官方接口 {@code api.deepseek.com}） */
        private String requiredHostExact;

        /** 要求 baseUrl 主机名<b>以该值结尾</b>（如火山 {@code volces.com}） */
        private String requiredHostSuffix;
    }

    /**
     * 官方联网搜索实现类型。
     */
    public enum SearchProvider {
        /** 无官方搜索接口，只能通用抓取 */
        NONE,

        /** 火山方舟搜索服务（独立搜索控制台 Key，与 Ark API Key 不通用） */
        VOLCENGINE,

        /** DeepSeek 官方 {@code /v1/tools/search}（免额外凭证，但要求官方域名） */
        DEEPSEEK_OFFICIAL,

        /** MiniMax Coding Plan 搜索（{@code codingPlanKey}） */
        MINIMAX_PLAN,

        /** 通义千问对话级联网（{@code enable_search}） */
        QWEN_DIALOG
    }

    /**
     * 余额 / 额度查询规格。
     *
     * <p>⚠️ 两点易错：</p>
     * <ul>
     *   <li>查的是<b>资金余额</b>，不是套餐剩余次数（火山 Coding Plan 的额度查不到）；</li>
     *   <li>凭证与模型 API Key 常常是<b>两套</b>（火山 / 阿里云的账号级 AK+SK）。</li>
     * </ul>
     */
    @Data
    public static class BalanceSpec {
        /** 余额查询实现类型；{@link BalanceProvider#NONE} 表示不支持 */
        private BalanceProvider provider = BalanceProvider.NONE;

        /** 需要从扩展配置中读取的凭证键（任一命中即视为已配置） */
        private List<String> credentialKeys = new ArrayList<>();

        /** 是否只需 Provider 自身的 API Key（DeepSeek 官方 {@code /user/balance}） */
        private boolean apiKeyOnly = false;
    }

    /**
     * 余额查询实现类型。
     */
    public enum BalanceProvider {
        /** 无余额接口（界面按钮灰掉） */
        NONE,

        /** DeepSeek 官方 {@code GET /user/balance}（仅需 API Key） */
        DEEPSEEK_OFFICIAL,

        /** 火山费用中心 {@code QueryBalanceAcct}（V4 签名，账号级 AK/SK） */
        VOLCENGINE_BILLING,

        /** 阿里云账户余额（账号级 AK/SK） */
        ALIYUN_BILLING,

        /** MiniMax Token Plan 额度（{@code codingPlanKey}） */
        MINIMAX_PLAN,

        /** MiMo 控制台余额（<b>浏览器 Cookie</b>，约 24 小时过期） */
        MIMO_COOKIE
    }

    /**
     * Files API（云端文件上传）规格。
     *
     * <p>{@link FilesApiProvider#NONE} 时上传走「仅本地存储 + 注入时 base64 降级」，
     * <b>不向任何第三方云盘上传用户文件</b>——这是 v3.1.4 定下的隐私口径，不要改回「猜一个 Provider 试试」。</p>
     */
    @Data
    public static class FilesApiSpec {
        /** Files API 实现类型；{@link FilesApiProvider#NONE} 表示未适配（本地存储 + base64 降级） */
        private FilesApiProvider provider = FilesApiProvider.NONE;

        /** 是否适配了 Files API（便捷判定，等价于 {@code provider != NONE}） */
        public boolean isSupported() {
            return provider != null && provider != FilesApiProvider.NONE;
        }
    }

    /**
     * Files API 实现类型。
     */
    public enum FilesApiProvider {
        /** 未适配：上传仅落本地，注入时转 base64 */
        NONE,

        /** DeepSeek Files API */
        DEEPSEEK,

        /** Anthropic Files API（{@code /v1/files} + {@code anthropic-beta} 头） */
        ANTHROPIC
    }

    /**
     * 界面展示规格。
     *
     * <p>当前这些信息只存在于前端（{@code provider-capability.ts} 的 {@code templateLabel} /
     * {@code providerIconTone}、{@code ProviderEditDrawer.vue} 的 {@code TEMPLATE_OPTIONS}）。
     * 收到后端后，前端可改为拉取，避免「后端加了模板、前端下拉里选不到」。</p>
     */
    @Data
    public static class DisplaySpec {
        /** 中文展示名（如「火山方舟」「通义千问」） */
        private String label;

        /** 配色档位（与 {@code app.css} 的 {@code .is-*} 修饰类对应，空串表示默认） */
        private String iconTone = "";

        /** 模板下拉排序（小的在前） */
        private int order = 100;

        /** 是否出现在「新建 Provider」的模板下拉中 */
        private boolean templateSelectable = true;
    }
}
