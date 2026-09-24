package com.example.agentdeepseek.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.List;

/**
 * 网络工具统一配置
 * 涵盖 web_search / web_fetch / http_request / check_network 四个网络工具
 * web_search 采用「多源聚合」策略（全球版/中国版 Bing + 百度，HTML 抓取，无需 API Key）
 */
@Configuration
@ConfigurationProperties(prefix = "network-tool")
@Data
public class NetworkToolConfig {


    /** 搜索结果最大条数 */
    private int searchMaxResults = 8;

    /** 搜索源优先级（bing-global=全球版 Bing / bing-cn=中国版 Bing / baidu=百度） */
    private List<String> searchEngines = new ArrayList<>(List.of("bing-global", "bing-cn", "baidu"));

    /** 搜索结果缓存 TTL（秒），<=0 表示禁用缓存 */
    private int searchCacheTtlSeconds = 300;

    /** 搜索结果缓存最大条目数 */
    private int searchCacheMaxEntries = 100;

    /** 低质量域名列表（排序降权），留空则关闭；示例：baijiahao.baidu.com */
    private List<String> searchLowQualityDomains = new ArrayList<>(List.of("baijiahao.baidu.com"));

    /**
     * 未检测到可用代理（走国内直连）时使用的搜索源优先级。
     * 直连场景下全球版 Bing 往往不可达 / 被强制重定向，故默认只保留国内可达源。
     */
    private List<String> searchEnginesNoProxy = new ArrayList<>(List.of("bing-cn", "baidu"));

    /** web_fetch 连接超时（毫秒） */
    private int fetchConnectTimeout = 5000;

    /** web_fetch 读取超时（毫秒） */
    private int fetchReadTimeout = 15000;

    /** web_fetch 内容最大长度（字符） */
    private int fetchMaxContentLength = 100_000;

    /** web_fetch 兜底 User-Agent（出网验证、UA 池为空时使用） */
    private String fetchUserAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";

    /**
     * web_fetch 抓取 User-Agent 池。
     * 同一域名稳定选择同一条（避免 UA 频繁变化触发风控），失败重试时按次序轮换；
     * sec-ch-ua 等客户端提示头由代码从 UA 字符串自动推导，保证与 UA 版本/平台相互匹配。
     */
    private List<String> fetchUserAgents = new ArrayList<>(List.of(
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36",
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36",
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36 Edg/131.0.0.0",
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:133.0) Gecko/20100101 Firefox/133.0",
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.6 Safari/605.1.15",
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
    ));

    /** web_fetch 本地抓取失败重试次数（403/429/503/5xx/超时/拦截页等可恢复错误时换 UA 重试），0 = 不重试 */
    private int fetchRetryCount = 2;

    /** web_fetch 重试之间的等待（毫秒）；HTTP 429 时优先遵循响应头的 Retry-After */
    private int fetchRetryDelayMs = 500;

    /** 反爬拦截页识别开关（页面命中风控关键词且正文极短时判定为拦截页，换 UA 重试） */
    private boolean fetchBlockDetectionEnabled = true;

    /** 反爬拦截页关键词（大小写不敏感；需与「正文极短」双重条件同时满足才判定） */
    private List<String> fetchBlockKeywords = new ArrayList<>(List.of(
            "just a moment", "cf-browser-verification", "cf_chl_", "checking your browser",
            "attention required", "enable javascript and cookies to continue",
            "verify you are human", "security check", "zse-ck",
            "安全验证", "访问验证", "滑动验证"
    ));

    /** 拦截页判定阈值：正文长度低于该值且命中关键词才判为拦截页（避免误伤正常页面） */
    private int fetchBlockMinTextLength = 1500;

    /**
     * web_fetch 抓取策略开关：本机有可用代理时优先走 Jina Reader 渲染抓取（更擅长反爬/JS 页面），
     * Jina 不可用（配额受限/请求失败）或本机无代理时自动降级为本地抓取。
     */
    private boolean fetchJinaEnabled = true;

    /** Jina Reader 服务地址（作为前缀拼接目标 URL，形如 https://r.jina.ai/https://example.com） */
    private String fetchJinaReaderUrl = "https://r.jina.ai/";

    /** Jina Reader 请求读取超时（毫秒）；渲染型页面较慢，通常放宽于本地抓取 */
    private int fetchJinaTimeoutMs = 20000;

    /** Jina Reader 配额受限（HTTP 429）后的冷却时长（秒）；冷却期内直接走本地抓取，不再消耗 Jina 额度 */
    private int fetchJinaCooldownSeconds = 120;

    /** Jina Reader API Key（可选；留空则匿名调用，免费额度约 20 次/分钟） */
    private String fetchJinaApiKey = "";

    /**
     * Jina Reader 请求 User-Agent。
     * ⚠️ 实测 Jina 会对「浏览器 UA」的自动化请求返回 403（反爬保护），必须使用工具型 UA（非 Mozilla 风格）。
     */
    private String fetchJinaUserAgent = "CodeCraft-WebFetch/2.0";

    /** http_request 连接超时（毫秒） */
    private int httpRequestConnectTimeout = 10_000;

    /** http_request 读取超时（毫秒） */
    private int httpRequestReadTimeout = 30_000;

    /** http_request 响应体上限（字节） */
    private int httpRequestMaxResponseBytes = 50 * 1024;

    /** http_request 允许的 HTTP 方法白名单 */
    private List<String> httpRequestAllowedMethods = new ArrayList<>(
            List.of("GET", "POST", "PUT", "DELETE")
    );

    /** check_network 默认检测站点列表 */
    private List<String> networkCheckSites = new ArrayList<>(
            List.of(
                    "https://www.google.com",
                    "https://github.com",
                    "https://huggingface.co",
                    "https://api.deepseek.com"
            )
    );

    /** 连通性检测超时（毫秒） */
    private int connectivityCheckTimeout = 5000;

    /** HTTP 代理配置（支持自动探测本地代理，探测不到则直连兜底） */
    private ProxyConfig proxy = new ProxyConfig();

    /**
     * 代理配置。
     *
     * <p>工作模式由 {@link #mode} 决定：</p>
     * <ul>
     *   <li>{@code AUTO}（默认）：自动探测本机代理端口，探测到就走代理，探测不到则直连（国内网络兜底）；</li>
     *   <li>{@code ALWAYS}：强制使用 {@link #host}:{@link #port}，不探测；</li>
     *   <li>{@code NEVER}：始终直连，不使用代理。</li>
     * </ul>
     *
     * <p>兼容旧配置：{@code enabled: true} 等价于 {@code mode: ALWAYS}。</p>
     */
    @Data
    public static class ProxyConfig {
        /** 是否启用代理（兼容旧配置；true 等价于 mode=ALWAYS） */
        private boolean enabled = false;

        /**
         * 代理模式：AUTO（自动探测，默认）/ ALWAYS（强制使用）/ NEVER（不使用）。
         * 留空表示未配置，此时按 {@link #enabled} 回退判断（true → ALWAYS，false → AUTO），以兼容旧配置。
         */
        private String mode;

        /** 代理主机地址 */
        private String host = "127.0.0.1";

        /** 代理端口（AUTO 模式下作为首个探测目标） */
        private int port = 7890;

        /** AUTO 模式下的候选探测端口（依次探测，命中即用） */
        private List<Integer> candidatePorts = new ArrayList<>(List.of(7890, 7897, 7891, 10809));

        /** 单端口探测超时（毫秒），过大会拖慢工具调用，建议 100~500 */
        private int detectTimeoutMs = 250;

        /**
         * 探测到「可用代理」时的缓存时长（秒），避免每次请求都重新探测。
         * 代理状态相对稳定，可缓存久一些；<=0 表示不缓存。
         */
        private int detectCacheSeconds = 300;

        /**
         * 探测结果为「直连」时的缓存时长（秒），比 {@link #detectCacheSeconds} 短得多。
         * 这样用户中途启动代理软件（Clash 等）后最多几十秒即可自动切换，无需重启应用。
         */
        private int directCacheSeconds = 30;

        /**
         * 是否在端口探测通过后，进一步验证「代理真的能出网」。
         * 开启后可识别「端口在监听但代理不可用（节点全挂/规则异常）」的情形，避免误用死代理。
         */
        private boolean verifyEgress = true;

        /** 出网验证目标（应选择一个必须经代理才能访问的轻量地址） */
        private String egressCheckUrl = "https://www.google.com/generate_204";

        /**
         * 出网验证超时（毫秒）。
         * 需覆盖「经代理建隧道 + TLS 握手」的首次开销（实测冷启动约 2~3s、热连接约 0.1~0.5s），过小会误判代理不可用。
         */
        private int egressTimeoutMs = 6000;
    }
}
