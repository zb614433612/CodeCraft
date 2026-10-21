package com.example.agentdeepseek.tool.browser;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.List;

/**
 * 浏览器自动化（browser 工具）配置。
 *
 * <p>对应 application.yml 的 {@code codecraft.browser.*} 配置块。</p>
 *
 * <p><b>与 Electron 侧的分工</b>：窗口数量上限、空闲回收时长、是否允许下载这几项由
 * {@code electron/browser-bridge.js} 自己持有（Electron 主进程不读 Java 配置）；
 * 这里只管「Java 侧怎么用」——域名策略、视口尺寸、超时、snapshot 规模。</p>
 *
 * <p><b>关于域名白名单</b>：默认 {@code allowedHosts} 为空 = 不限制。
 * 原因是本地 Agent 本来就有 shell 执行、文件读写、任意 HTTP 请求的能力，
 * 浏览器的 SSRF 面在这个前提下不构成新增威胁，加白名单只是徒增配置负担。
 * 需要收紧时填上几行 yml 即可（支持 {@code *.example.com} 通配）。</p>
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "codecraft.browser")
public class BrowserConfig {

    /** 总开关：false 时工具直接返回「已被配置禁用」，不做任何连接尝试 */
    private boolean enabled = true;

    /** 窗口默认是否可见（单次 open 可覆盖；已开窗口可用 action=hide/show 切换） */
    private boolean defaultShow = true;

    /** 默认视口宽度（仅在 open 时未传 width 的情况下生效） */
    private int viewportWidth = 1280;

    /** 默认视口高度 */
    private int viewportHeight = 860;

    /**
     * 域名白名单。空 = 不限制；非空 = 只允许命中项访问。
     * <p>匹配规则：忽略大小写；{@code *.example.com} 匹配所有子域（不含 example.com 本身）；
     * 精确域名同时匹配其子域（{@code example.com} 放行 {@code api.example.com}）。</p>
     */
    private List<String> allowedHosts = new ArrayList<>();

    /**
     * 域名黑名单。优先级高于白名单。
     * <p>典型用途：临时封掉某个有破坏性按钮的站点，而不必推翻整体策略。</p>
     */
    private List<String> blockedHosts = new ArrayList<>();

    /**
     * 单次 CDP 请求超时（秒）。
     * <p>不能设太短：{@code navigate} 到慢站点时 Chromium 的 {@code Page.navigate} 本身很快返回，
     * 但若走的是 {@code loadURL} 等待则可能几十秒。</p>
     */
    private int bridgeTimeoutSeconds = 60;

    /** 握手文件发现失败后的重试冷却（秒）；避免每次工具调用都去读不存在的文件 */
    private int discoverRetrySeconds = 5;

    /** snapshot 返回的可交互元素上限（按重要性排序后截断） */
    private int snapshotMaxElements = 60;

    /** snapshot 页面正文摘要的字符上限 */
    private int snapshotTextLimit = 1200;

    /** 单个元素文案的截断长度 */
    private int snapshotLabelLimit = 60;

    /**
     * 是否允许 {@code eval} 动作在页面内执行任意脚本。
     *
     * <p>默认 false：eval 能绕过本工具全部安全护栏（读 cookie、发请求、改页面状态），
     * 而绝大多数操作已经能用 click/fill/press 完成。真需要时再显式打开，
     * 打开后每次 eval 调用仍会在回执里提示「已执行自定义脚本」。</p>
     */
    private boolean allowScriptEval = false;

    /** {@code eval} 单次执行的超时（毫秒）；超时后不再等待页面返回值 */
    private long evalTimeoutMs = 15_000L;

    /** 截图格式：{@code png}（无损、体积大）/ {@code jpeg}（有损、体积小） */
    private String screenshotFormat = "png";

    /** jpeg 质量（1~100）；仅 {@code format=jpeg} 时生效 */
    private int screenshotQuality = 80;

    /**
     * 截图最长边上限（像素）。
     *
     * <p>超过则等比缩小：bridge 的请求体上限是 8MB，4K 页面全页 PNG 的 base64
     * 很容易超；缩小对「看清布局」几乎没有影响，但能避免整次调用失败。</p>
     */
    private int screenshotMaxEdge = 1920;

    /** {@code wait} 未传 timeoutMs 时的默认超时（毫秒） */
    private long waitDefaultTimeoutMs = 10_000L;

    /** {@code wait} 允许的最大超时（毫秒）：模型给再大也按这个封顶，防一次调用挂 10 分钟 */
    private long waitMaxTimeoutMs = 60_000L;

    /** {@code scroll} 未传 amount 时的默认滚动像素（约 2/3 屏） */
    private int scrollDefaultPx = 600;

    /** {@code console} 单次返回的事件条数上限（按时间正序保留最后 N 条） */
    private int consoleMaxEvents = 40;
}
