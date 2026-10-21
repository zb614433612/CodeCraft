package com.example.agentdeepseek.tool.impl;

import com.example.agentdeepseek.model.vo.FileAssetVO;
import com.example.agentdeepseek.service.files.FileAssetService;
import com.example.agentdeepseek.tool.Tool;
import com.example.agentdeepseek.tool.browser.BrowserBridgeClient;
import com.example.agentdeepseek.tool.browser.BrowserConfig;
import com.example.agentdeepseek.tool.browser.PageSnapshotter;
import com.example.agentdeepseek.tool.permission.OperationCategory;
import com.example.agentdeepseek.tool.permission.SideEffectFreePreflight;
import com.example.agentdeepseek.tool.permission.ToolPermission;
import com.example.agentdeepseek.util.ProjectRootContext;
import com.example.agentdeepseek.util.ToolContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 浏览器自动化工具（DOM 级网页操作）。
 *
 * <p>补齐 Agent 的第三类网页能力：</p>
 * <ul>
 *   <li>{@code web_fetch} / {@code web_search} —— 文本层，只读正文，不能交互</li>
 *   <li>{@code screen_capture} / {@code desktop_control} —— 视觉层，截图像素 + 模拟键鼠，
 *       能应付任何界面但要靠猜坐标、费 token、容易点偏</li>
 *   <li><b>本工具</b> —— 语义层，直接读 DOM、按 ref 点击输入，稳定且省 token</li>
 * </ul>
 *
 * <p><b>权限</b>：{@code highRisk=true} —— 它带着用户的真实浏览器身份操作站点，
 * 一个误点可能提交表单、发起支付、删除数据。所以 auto 模式下首次使用会弹一次授权；
 * 用户选「本次会话全部同意」后即不再打扰（{@code PermissionContext.isSessionApproved}）。</p>
 *
 * <p><b>免授权白名单</b>：{@code snapshot} / {@code status} / {@code list} / {@code console}
 * / {@code wait} / {@code screenshot} 六个动作通过 {@link SideEffectFreePreflight} 声明为无副作用
 * —— 反复看页面不该反复弹窗，否则一个正常流程要点十几次授权。
 * 其中 {@code screenshot} 只读页面并生成一张本地资产，不改变页面业务状态。</p>
 */
@Slf4j
@Component
@ToolPermission(category = OperationCategory.BROWSER, highRisk = true,
        description = "浏览器自动化（DOM 级网页操作：打开页面 / 读取可交互元素 / 点击 / 输入 / 按键 / 滚动 / 截图）")
public class BrowserTool implements Tool, SideEffectFreePreflight {

    private static final String ACTION_OPEN = "open";
    private static final String ACTION_NAVIGATE = "navigate";
    private static final String ACTION_SNAPSHOT = "snapshot";
    private static final String ACTION_CLICK = "click";
    private static final String ACTION_FILL = "fill";
    private static final String ACTION_SELECT = "select";
    private static final String ACTION_PRESS = "press";
    private static final String ACTION_HOVER = "hover";
    private static final String ACTION_SCROLL = "scroll";
    private static final String ACTION_WAIT = "wait";
    private static final String ACTION_SCREENSHOT = "screenshot";
    private static final String ACTION_CONSOLE = "console";
    private static final String ACTION_EVAL = "eval";
    private static final String ACTION_SHOW = "show";
    private static final String ACTION_HIDE = "hide";
    private static final String ACTION_CLOSE = "close";
    private static final String ACTION_STATUS = "status";
    private static final String ACTION_LIST = "list";
    private static final String ACTION_TAB = "tab";

    /** 导航后等待页面 readyState=complete 的上限 */
    private static final long NAVIGATE_WAIT_MS = 20_000L;
    /** 轮询间隔 */
    private static final long POLL_INTERVAL_MS = 250L;
    /** {@code wait} 轮询间隔（比导航轮询更细，等待通常要求响应更快） */
    private static final long WAIT_POLL_MS = 200L;
    /** 截图临时文件名时间戳 */
    private static final DateTimeFormatter SHOT_TIME_FORMAT =
            DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss_SSS");
    /** 截图落盘目录（相对系统临时目录） */
    private static final String SHOT_DIR = "codecraft-browser";
    /** 单张截图 base64 上限（约 7MB 原始字节）：超过说明页面太大，先缩小或只截视口 */
    private static final int SHOT_MAX_B64_CHARS = 7 * 1024 * 1024;

    private final BrowserBridgeClient bridge;
    private final PageSnapshotter snapshotter;
    private final BrowserConfig config;
    private final ObjectMapper objectMapper;
    private final FileAssetService fileAssetService;

    /**
     * 每个会话的事件游标（conversationId → 已消费到的 seq）。
     *
     * <p>bridge 侧每个窗口有事件环形缓冲（页面报错、console.error、自动处理的弹窗）。
     * 不带游标的话每次调用都会把历史上最多 200 条事件重复回放一遍，
     * 模型会误以为「这次又报了 5 个错」。所以必须只取增量。</p>
     */
    private final Map<String, Long> eventCursors = new ConcurrentHashMap<>();

    /**
     * 每个会话当前活跃的标签 id（conversationId → tabId）。
     *
     * <p>为什么不每次调用都去问 bridge：绝大多数动作（click / fill / snapshot）都不带 tabId，
     * 若每次都多发一次 {@code /tabs} 查询，一个正常流程要多跑十几趟 HTTP。
     * 这里的缓存只在 {@code tab op=switch} 与 {@code open newTab=true} 时更新，
     * 与 bridge 侧的 {@code activeTabOf} 保持同源；标签被关掉时 bridge 会回 410，
     * 那时 {@link #forgetTab(String)} 清掉缓存即可。</p>
     */
    private final Map<String, String> activeTabs = new ConcurrentHashMap<>();

    public BrowserTool(BrowserBridgeClient bridge, PageSnapshotter snapshotter,
                       BrowserConfig config, ObjectMapper objectMapper,
                       FileAssetService fileAssetService) {
        this.bridge = bridge;
        this.snapshotter = snapshotter;
        this.config = config;
        this.objectMapper = objectMapper;
        this.fileAssetService = fileAssetService;
    }

    @Override
    public String getName() {
        return "browser";
    }

    @Override
    public String getDescription() {
        return "【适用场景】需要**真的操作网页**时——登录、填表单、点按钮、翻页、抓需要交互后才出现的内容。"
                + "只读一篇文章用 web_fetch 就够了，本工具开销更大。\n"
                + "★ 与其它网页类工具的分工：\n"
                + "  - web_fetch / web_search：只要正文文字，不能交互 → 优先用它，最省\n"
                + "  - browser（本工具）：要点击/输入/等异步加载 → DOM 级，稳且省 token\n"
                + "  - screen_capture / desktop_control：目标不是网页（原生软件、画布、验证码）→ 像素级兜底\n"
                + "【标准流程】open（或 navigate）→ snapshot（看有哪些元素）→ click / fill → snapshot（验证结果）\n"
                + "  每个动作后建议 snapshot 一次，因为你无法假定上一步真的生效了。\n"
                + "【ref 机制（关键）】snapshot 会给每个可交互元素分配 e1、e2… 的编号，"
                + "click / fill / select / hover 都要填这个编号（select 用它指定「哪个下拉框」，"
                + "hover 用它指定「悬停到哪」）。\n"
                + "  · **每次 snapshot 都会重建全部 ref，旧的立即失效**——页面变了还拿旧 ref 操作会被拒绝\n"
                + "  · 遇到「ref 已失效」不要猜，重新 snapshot 即可\n"
                + "【action 说明】\n"
                + "  open       — 打开（或复用）本会话的浏览器窗口。不传 url 则开空白页。窗口会真的显示出来\n"
                + "  navigate   — 在当前窗口跳转到新地址（推荐用它代替反复 open）\n"
                + "  snapshot   — 读取当前页面：标题、地址、正文摘要、所有可见可交互元素（带 ref）\n"
                + "  click      — 点击某个 ref。会先滚动到可见位置，再发送真实鼠标事件；"
                + "点「跨域 iframe」占位元素时用 rx / ry 指定相对位置（0~1，默认 0.5 中心）\n"
                + "  fill       — 给 input / textarea 填值（select 也能填，但更推荐 select 动作）。"
                + "走原生 setter + input/change 事件，React/Vue 受控组件也能正确响应\n"
                + "  select     — 下拉框按选项文案或 value 选择。传 label（显示文案，支持部分匹配）"
                + "或 value（选项值）；snapshot 会直接列出可选清单，照抄即可\n"
                + "  press      — 按键。key 形如 Enter / Escape / Tab / ArrowDown / Control+a / "
                + "Control+Enter；也支持先 ref 聚焦再按（搜索框回车提交最常用）\n"
                + "  hover      — 悬停到某个 ref（触发 hover 才出现的菜单 / tooltip）\n"
                + "  scroll     — 滚动页面（direction=up/down/top/bottom，amount=像素）；"
                + "传 ref 则滚动该元素内部（如可滚动的列表容器）\n"
                + "  wait       — 等待（waitFor=time/selector/text/url）。异步加载、跳转、弹层出现时用它，"
                + "比反复 snapshot 省得多；state=hidden 可等元素消失\n"
                + "  screenshot — 截取页面并注入到你的上下文（图片会直接出现在下一轮对话里）。"
                + "页面是 canvas / 图表 / 纯图片、或要看视觉效果时用它\n"
                + "  console    — 读取页面控制台报错与网络失败。点了没反应、页面空白时优先看它\n"
                + "  eval       — 在页面内执行自定义脚本（需配置显式开启，默认关闭）\n"
                + "  show / hide — 显示 / 隐藏窗口（操作照常执行，只是你看不见）\n"
                + "  close      — 关闭当前标签；传 all=true 则关掉本会话全部标签\n"
                + "  tab        — 标签管理：op=list 列出 / op=new 新建 / op=switch 切换 / op=close 关闭\n"
                + "  status     — 检查浏览器通道是否可用、当前窗口数与标签上限、域名策略\n"
                + "  list       — 列出当前所有 AI 浏览器标签（含其他会话的，便于排查残留窗口）\n"
                + "【注意事项】\n"
                + "  1) 每个会话有独立窗口，不同会话不会互相抢页面；同一项目下的会话共享登录态\n"
                + "  2) 窗口空闲 10 分钟会被回收，但 cookie 留盘 —— 重新 open 后仍是登录状态\n"
                + "  3) 如果用户手动关掉了窗口，后续动作会明确报「窗口不存在」，重新 open 即可（不会静默重建）\n"
                + "  4) 密码框的值永远不会被读取（快照里只显示「密码已隐藏」），填密码时你也不知道原值\n"
                + "  5) 下载默认被拦截（点了下载按钮也不会落盘）\n"
                + "  6) **同源 iframe 与 open 模式的 Shadow DOM 已自动穿透**（元素会标 [iframe内]）；"
                + "跨域 iframe 读不到内部 DOM，但它本身会作为占位元素输出（标 [跨域iframe]），"
                + "可用 click ref=<它> rx=0.7 ry=0.3 按比例点进去；closed 模式 Shadow DOM 与 canvas 绘制内容拿不到，"
                + "这些场景改用 screenshot 看图\n"
                + "  7) 遇到弹窗（alert/confirm）会自动点「确定」并记一条提示，避免页面卡死\n"
                + "  8) 只允许 http / https；file:// 被拒绝\n"
                + "  9) 需要登录态才能做的事：先 open 让用户手动登录一次，之后同项目的会话都能复用\n"
                + "  10) 异步加载不要靠连续 snapshot 空转 —— 用 wait（waitFor=selector/text/url）"
                + "一次等到位，省 token 也更快\n"
                + "  11) 点了没反应先看 console（页面报错 / 接口 4xx5xx），比盲猜有效得多\n"
                + "  12) 目标是 canvas / 图表 / 验证码这类「画出来的」内容时，snapshot 只能给空清单，"
                + "直接 screenshot 让模型看图\n"
                + "  13) **一个会话可以开多个标签**（各自独立窗口，共享登录态）：open 传 newTab=true 新建，"
                + "tab op=list 查看、op=switch 切换、op=close 关闭。后续动作默认作用于「当前标签」——"
                + "切错标签就会操作到另一个页面上，不确定时先 tab op=list 确认";
    }

    @Override
    public JsonNode getParameters() {
        ObjectNode parameters = objectMapper.createObjectNode();
        parameters.put("type", "object");

        ObjectNode properties = objectMapper.createObjectNode();

        ObjectNode action = objectMapper.createObjectNode();
        action.put("type", "string");
        action.put("description", "【必填】操作类型。open=开/复用标签；navigate=跳转；snapshot=读页面元素；"
                + "click=点元素；fill=填值；select=选下拉项；press=按键；hover=悬停；scroll=滚动；"
                + "wait=等待；screenshot=截图；console=看报错；eval=执行脚本（默认禁用）；"
                + "show/hide=显隐窗口；close=关标签；tab=标签管理（多标签）；status/list=诊断。");
        ArrayNode actionEnum = objectMapper.createArrayNode();
        actionEnum.add(ACTION_OPEN).add(ACTION_NAVIGATE).add(ACTION_SNAPSHOT).add(ACTION_CLICK)
                .add(ACTION_FILL).add(ACTION_SELECT).add(ACTION_PRESS).add(ACTION_HOVER)
                .add(ACTION_SCROLL).add(ACTION_WAIT).add(ACTION_SCREENSHOT).add(ACTION_CONSOLE)
                .add(ACTION_EVAL).add(ACTION_SHOW).add(ACTION_HIDE).add(ACTION_CLOSE)
                .add(ACTION_TAB).add(ACTION_STATUS).add(ACTION_LIST);
        action.set("enum", actionEnum);
        properties.set("action", action);

        ObjectNode op = objectMapper.createObjectNode();
        op.put("type", "string");
        op.put("description", "【tab 用】子操作：list=列出本会话标签（默认）；new=新建标签（可带 url）；"
                + "switch=切换活跃标签（需 tabId）；close=关闭标签（需 tabId，缺省关当前）。");
        ArrayNode opEnum = objectMapper.createArrayNode();
        opEnum.add("list").add("new").add("switch").add("close");
        op.set("enum", opEnum);
        properties.set("op", op);

        ObjectNode tabId = objectMapper.createObjectNode();
        tabId.put("type", "string");
        tabId.put("description", "【tab / open / close 用】标签编号，形如 t1、t2（由 open 或 tab op=list 返回）。"
                + "省略时用本会话的当前活跃标签。");
        properties.set("tabId", tabId);

        ObjectNode newTab = objectMapper.createObjectNode();
        newTab.put("type", "boolean");
        newTab.put("description", "【open 用】true=强制新建一个标签（保留当前页面不动），默认 false 复用当前标签。"
                + "多个页面要同时保留状态时用它（如一个查资料、一个填表单）。");
        properties.set("newTab", newTab);

        ObjectNode closeAll = objectMapper.createObjectNode();
        closeAll.put("type", "boolean");
        closeAll.put("description", "【close 用】true=关闭本会话全部标签；默认 false 只关当前标签。");
        properties.set("all", closeAll);

        ObjectNode url = objectMapper.createObjectNode();
        url.put("type", "string");
        url.put("description", "【open / navigate 必填】要打开的地址。可省略协议（按 https 处理）。"
                + "只支持 http/https；file:// 会被拒绝。open 时留空则开空白页。");
        properties.set("url", url);

        ObjectNode ref = objectMapper.createObjectNode();
        ref.put("type", "string");
        ref.put("description", "【click / fill / select / hover 必填，scroll 可选】元素编号，来自最近一次 snapshot（形如 e12）。"
                + "select 用它指定操作哪个下拉框，hover 用它指定悬停目标；scroll 传它表示滚该元素内部而非整页。"
                + "每次 snapshot 都会重建编号，旧编号立即失效，失效后重新 snapshot 即可。");
        properties.set("ref", ref);

        ObjectNode rx = objectMapper.createObjectNode();
        rx.put("type", "number");
        rx.put("description", "【click 用】相对横坐标 0~1（默认 0.5=元素水平中心）。"
                + "只有点「跨域 iframe」占位元素时才需要传：内部 DOM 读不到，只能按比例点进去。"
                + "先 screenshot 看清布局再估比例，例如输入框在 iframe 右侧 70% 处就传 rx=0.7。");
        properties.set("rx", rx);

        ObjectNode ry = objectMapper.createObjectNode();
        ry.put("type", "number");
        ry.put("description", "【click 用】相对纵坐标 0~1（默认 0.5=元素垂直中心），配合 rx 使用。");
        properties.set("ry", ry);

        ObjectNode value = objectMapper.createObjectNode();
        value.put("type", "string");
        value.put("description", "【fill / select 用】要填入的内容（fill 传空字符串表示清空）。"
                + "select 时填选项的 value（也可改用 label）。");
        properties.set("value", value);

        ObjectNode label = objectMapper.createObjectNode();
        label.put("type", "string");
        label.put("description", "【select 用】选项的显示文案，支持部分匹配（如「北京市」能匹配「北京市→110000」）。"
                + "与 value 二选一，优先 label。snapshot 的 ↳ 可选 行里箭头右边是 value、左边是 label。");
        properties.set("label", label);

        ObjectNode key = objectMapper.createObjectNode();
        key.put("type", "string");
        key.put("description", "【press 必填】按键，支持组合键。示例：Enter / Escape / Tab / ArrowDown / "
                + "PageDown / Backspace / Control+a / Control+Enter / Shift+Tab。"
                + "组合键用 + 连接，修饰键可用 Control / Alt / Shift / Meta（Meta 在 Windows 上即 Win 键）。");
        properties.set("key", key);

        ObjectNode direction = objectMapper.createObjectNode();
        direction.put("type", "string");
        direction.put("description", "【scroll 用】滚动方向：up / down / top（回到顶部）/ bottom（到底部）。默认 down。");
        ArrayNode directionEnum = objectMapper.createArrayNode();
        directionEnum.add("up").add("down").add("top").add("bottom");
        direction.set("enum", directionEnum);
        properties.set("direction", direction);

        ObjectNode amount = objectMapper.createObjectNode();
        amount.put("type", "integer");
        amount.put("description", "【scroll 用】滚动像素，默认 " + config.getScrollDefaultPx()
                + "（约 2/3 屏）。direction=top/bottom 时忽略。");
        properties.set("amount", amount);

        ObjectNode waitFor = objectMapper.createObjectNode();
        waitFor.put("type", "string");
        waitFor.put("description", "【wait 必填】等什么：time=固定等待 timeoutMs；selector=等元素出现"
                + "（配合 selector）；text=等页面出现某段文字（配合 text）；url=等地址包含某段字符串（配合 url）。");
        ArrayNode waitEnum = objectMapper.createArrayNode();
        waitEnum.add("time").add("selector").add("text").add("url");
        waitFor.set("enum", waitEnum);
        properties.set("waitFor", waitFor);

        ObjectNode selector = objectMapper.createObjectNode();
        selector.put("type", "string");
        selector.put("description", "【wait 用】CSS 选择器，如 #result、.ant-table-row、button[type=submit]。"
                + "waitFor=selector 时必填。");
        properties.set("selector", selector);

        ObjectNode waitText = objectMapper.createObjectNode();
        waitText.put("type", "string");
        waitText.put("description", "【wait 用】期望出现在页面正文里的文字（子串匹配，忽略大小写）。"
                + "waitFor=text 时必填。");
        properties.set("text", waitText);

        ObjectNode state = objectMapper.createObjectNode();
        state.put("type", "string");
        state.put("description", "【wait 用】等待的状态，默认 visible。hidden=等元素消失（如 loading 遮罩关闭）。");
        ArrayNode stateEnum = objectMapper.createArrayNode();
        stateEnum.add("visible").add("hidden");
        state.set("enum", stateEnum);
        properties.set("state", state);

        ObjectNode timeoutMs = objectMapper.createObjectNode();
        timeoutMs.put("type", "integer");
        timeoutMs.put("description", "【wait 用】超时毫秒数，默认 " + config.getWaitDefaultTimeoutMs()
                + "，上限 " + config.getWaitMaxTimeoutMs() + "。超时不会抛错，会返回「至今仍未满足条件」。");
        properties.set("timeoutMs", timeoutMs);

        ObjectNode fullPage = objectMapper.createObjectNode();
        fullPage.put("type", "boolean");
        fullPage.put("description", "【screenshot 用】是否截整页（含需滚动才能看到的部分），默认 false 只截当前视口。"
                + "整页图在长页面上会很高，模型反而看不清细节，按需使用。");
        properties.set("fullPage", fullPage);

        ObjectNode shotFormat = objectMapper.createObjectNode();
        shotFormat.put("type", "string");
        shotFormat.put("description", "【screenshot 用】图片格式：png（无损，默认）/ jpeg（有损但小得多）。"
                + "整页长图或页面很大时用 jpeg 更省，细节要求高时用 png。");
        ArrayNode shotFormatEnum = objectMapper.createArrayNode();
        shotFormatEnum.add("png").add("jpeg");
        shotFormat.set("enum", shotFormatEnum);
        properties.set("format", shotFormat);

        ObjectNode show = objectMapper.createObjectNode();
        show.put("type", "boolean");
        show.put("description", "【open 可选】本次是否显示窗口，默认 true（可在配置里改默认值）。");
        properties.set("show", show);

        ObjectNode width = objectMapper.createObjectNode();
        width.put("type", "integer");
        width.put("description", "【open 可选】窗口宽度，默认 1280。");
        properties.set("width", width);

        ObjectNode height = objectMapper.createObjectNode();
        height.put("type", "integer");
        height.put("description", "【open 可选】窗口高度，默认 860。");
        properties.set("height", height);

        parameters.set("properties", properties);
        ArrayNode required = objectMapper.createArrayNode();
        required.add("action");
        parameters.set("required", required);
        return parameters;
    }

    /**
     * 免授权判定：只看不改的六个动作。
     *
     * <p>契约要求「判定与执行路径必须严格同源」——这里的判定就是
     * {@link #execute} 里走的分支，且这些分支确实不改变页面业务状态
     * （snapshot 只重建工具自己的 ref 表；screenshot 只读像素并生成一张本地资产）。
     * {@code hover} / {@code scroll} 故意不放进白名单：它们会触发页面自身的 hover 菜单、
     * 滚动监听与懒加载请求，属于会改变页面状态的操作。</p>
     */
    @Override
    public boolean isSideEffectFreePreflight(JsonNode arguments) {
        String action = arguments.path("action").asText("").trim().toLowerCase();
        return ACTION_SNAPSHOT.equals(action)
                || ACTION_STATUS.equals(action)
                || ACTION_LIST.equals(action)
                || ACTION_CONSOLE.equals(action)
                || ACTION_WAIT.equals(action)
                || ACTION_SCREENSHOT.equals(action)
                // tab 只有 op=list 是纯读；new / switch / close 会改窗口状态，仍需授权
                || (ACTION_TAB.equals(action) && "list".equalsIgnoreCase(arguments.path("op").asText("")));
    }

    // ── 入口 ─────────────────────────────────────────────────────

    @Override
    public String execute(JsonNode arguments) {
        String action = arguments.path("action").asText("").trim().toLowerCase();
        if (action.isEmpty()) {
            return fail("缺少 action 参数。可用值：" + ACTION_HINT);
        }

        try {
            return switch (action) {
                case ACTION_OPEN -> doOpen(arguments);
                case ACTION_NAVIGATE -> doNavigate(arguments);
                case ACTION_SNAPSHOT -> doSnapshot();
                case ACTION_CLICK -> doClick(arguments);
                case ACTION_FILL -> doFill(arguments);
                case ACTION_SELECT -> doSelect(arguments);
                case ACTION_PRESS -> doPress(arguments);
                case ACTION_HOVER -> doHover(arguments);
                case ACTION_SCROLL -> doScroll(arguments);
                case ACTION_WAIT -> doWait(arguments);
                case ACTION_SCREENSHOT -> doScreenshot(arguments);
                case ACTION_CONSOLE -> doConsole(arguments);
                case ACTION_EVAL -> doEval(arguments);
                case ACTION_SHOW -> doShow(true);
                case ACTION_HIDE -> doShow(false);
                case ACTION_CLOSE -> doClose(arguments);
                case ACTION_STATUS -> bridge.healthSummary();
                case ACTION_LIST -> doList();
                case ACTION_TAB -> doTab(arguments);
                default -> fail("不支持的 action：" + action + "。可用值：" + ACTION_HINT);
            };
        } catch (IllegalArgumentException | IllegalStateException e) {
            // 参数错误 / 页面脚本异常：直接回给模型，它通常能自行纠正
            return fail(e.getMessage());
        } catch (Exception e) {
            log.warn("browser 工具执行异常 action={}", action, e);
            return fail("执行失败：" + e.getMessage());
        }
    }

    /** 可用动作清单（错误提示里复用，避免各处写得不一样） */
    private static final String ACTION_HINT =
            "open / navigate / snapshot / click / fill / select / press / hover / scroll / wait / "
                    + "screenshot / console / eval / show / hide / close / tab / status / list";

    // ── 动作实现 ─────────────────────────────────────────────────

    private String doOpen(JsonNode arguments) {
        return openTab(arguments, arguments.path("newTab").asBoolean(false));
    }

    /**
     * 打开 / 复用浏览器标签。
     *
     * @param forceNew 强制新建标签（{@code tab op=new} 走这条路径，与 {@code open newTab=true} 完全等价）
     */
    private String openTab(JsonNode arguments, boolean forceNew) {
        String rawUrl = arguments.path("url").asText("").trim();
        String url = null;
        if (!rawUrl.isEmpty()) {
            url = normalizeUrl(rawUrl);
            assertHostAllowed(url);
        }

        boolean show = arguments.has("show")
                ? arguments.path("show").asBoolean(true)
                : config.isDefaultShow();

        ObjectNode payload = basePayload();
        if (forceNew) {
            // 「新建标签」与「复用当前标签」语义冲突：必须摘掉活跃 tabId，否则 bridge 会走复用分支
            payload.remove("tabId");
            payload.put("newTab", true);
        }
        String explicitTab = arguments.path("tabId").asText("").trim();
        if (!explicitTab.isEmpty()) {
            payload.put("tabId", explicitTab);
        }
        if (url != null) payload.put("url", url);
        payload.put("show", show);
        payload.put("width", arguments.path("width").asInt(config.getViewportWidth()));
        payload.put("height", arguments.path("height").asInt(config.getViewportHeight()));

        BrowserBridgeClient.BridgeResponse response = bridge.call("/open", payload);
        if (!response.ok()) {
            return fail("打开浏览器窗口失败：" + response.error());
        }

        String tabId = response.text("tabId", "");
        if (!tabId.isBlank()) activeTabs.put(conversationKey(), tabId);
        int tabCount = response.body() == null ? 1 : Math.max(1, response.body().path("tabCount").asInt(1));
        boolean reused = response.flag("reused", false);
        String pageUrl = response.text("pageUrl", url == null ? "" : url);

        StringBuilder sb = new StringBuilder();
        sb.append(reused ? "已复用浏览器标签 " : "已打开新的浏览器标签 ").append(tabId.isBlank() ? "" : tabId);
        sb.append("（本会话共 ").append(tabCount).append(" 个）\n");
        sb.append("· 当前页面：").append(pageUrl.isBlank() ? "(空白页)" : pageUrl).append('\n');
        sb.append("· 窗口").append(response.flag("visible", true) ? "可见" : "已隐藏").append('\n');
        if (pageUrl.isBlank() || "about:blank".equals(pageUrl)) {
            sb.append("\n下一步：用 action=navigate 打开目标页面，或直接 action=snapshot 查看当前页。");
        } else {
            sb.append("\n下一步：action=snapshot 读取页面元素（会给出 e1、e2… 编号供 click/fill 使用）。");
        }
        if (tabCount > 1) {
            sb.append("\n提示：本会话有多个标签，后续动作默认作用于当前标签 ")
                    .append(tabId.isBlank() ? "" : tabId)
                    .append("；要切到别的标签用 action=tab op=switch tabId=t1。");
        }
        return sb.toString();
    }

    private String doNavigate(JsonNode arguments) {
        String rawUrl = arguments.path("url").asText("").trim();
        if (rawUrl.isEmpty()) {
            return fail("navigate 需要 url 参数");
        }
        String url = normalizeUrl(rawUrl);
        assertHostAllowed(url);

        if (!ensureWindowAlive()) {
            return fail(windowMissingHint());
        }

        ObjectNode payload = basePayload();
        payload.put("method", "Page.navigate");
        ObjectNode params = objectMapper.createObjectNode();
        params.put("url", url);
        payload.set("params", params);

        BrowserBridgeClient.BridgeResponse response = callBridge("/cdp", payload);
        if (!response.ok()) {
            return fail("跳转失败：" + response.error());
        }

        // Page.navigate 只是「已开始导航」，真正的失败信息在 errorText 里
        JsonNode result = response.node("result");
        String errorText = result != null && result.hasNonNull("errorText")
                ? result.path("errorText").asText("") : "";
        if (!errorText.isBlank()) {
            return fail("跳转失败（" + errorText + "）：" + url
                    + "\n请检查地址是否正确、站点是否可达。");
        }

        // 等页面就绪：不等的话紧接着的 snapshot 会读到旧页面或空白
        String readyState = awaitReady();
        StringBuilder sb = new StringBuilder();
        sb.append("已跳转：").append(url).append('\n');
        sb.append("· 页面状态：").append(describeReadyState(readyState)).append('\n');
        String finalUrl = response.text("pageUrl", url);
        if (!finalUrl.isBlank() && !finalUrl.equals(url)) {
            sb.append("· 实际地址：").append(finalUrl).append("（发生了重定向）\n");
        }
        appendEvents(sb, response);
        sb.append("\n下一步：action=snapshot 查看页面元素。");
        return sb.toString();
    }

    private String doSnapshot() {
        if (!ensureWindowAlive()) {
            return fail(windowMissingHint());
        }

        String expression = snapshotter.buildExpression();
        BrowserBridgeClient.BridgeResponse response = evaluate(expression);
        if (!response.ok()) {
            return fail("读取页面失败：" + response.error());
        }

        JsonNode value = evaluateValue(response);
        PageSnapshotter.Snapshot snapshot = snapshotter.parse(value);

        StringBuilder sb = new StringBuilder(snapshotter.format(snapshot));
        appendEvents(sb, response);
        return sb.toString();
    }

    private String doClick(JsonNode arguments) {
        String ref = arguments.path("ref").asText("").trim();
        if (ref.isEmpty()) {
            return fail("click 需要 ref 参数（来自最近一次 snapshot 的元素编号，如 e12）");
        }
        if (!ensureWindowAlive()) {
            return fail(windowMissingHint());
        }

        // rx/ry：0~1 的相对位置（默认 0.5 = 元素中心）。
        // 跨域 iframe 读不到内部元素，只能把整个 iframe 当成一块可点区域，
        // 靠这个比例把点击送进它内部对应的位置（配合 screenshot 估算最稳）。
        double rx = clamp01(arguments.path("rx").asDouble(0.5));
        double ry = clamp01(arguments.path("ry").asDouble(0.5));

        // 第一段：滚动到可见位置并取中心点坐标
        String coordsExpr = coordinateExpression(ref, rx, ry);
        BrowserBridgeClient.BridgeResponse coordResp = evaluate(coordsExpr);
        if (!coordResp.ok()) {
            return fail("定位元素失败：" + coordResp.error());
        }
        JsonNode coordValue = parseJsonValue(evaluateValue(coordResp));
        if (coordValue == null || !coordValue.path("ok").asBoolean(false)) {
            String reason = coordValue == null ? "无法解析元素坐标" : coordValue.path("error").asText("未知原因");
            return fail("无法点击 " + ref + "：" + reason
                    + "\n页面结构可能已变化，请先 action=snapshot 重新获取元素编号。");
        }

        int x = coordValue.path("x").asInt(0);
        int y = coordValue.path("y").asInt(0);
        String elementText = coordValue.path("text").asText("");

        // 第二段：发送真实鼠标事件（按压 + 释放）
        // 比 el.click() 更可靠：能触发 mousedown/mouseup 链、能命中 React 的事件委托、
        // 也能触发依赖真实指针位置的组件（拖拽手柄、滑块、画布控件）。
        ObjectNode batchPayload = basePayload();
        ArrayNode calls = objectMapper.createArrayNode();

        ObjectNode press = objectMapper.createObjectNode();
        press.put("method", "Input.dispatchMouseEvent");
        ObjectNode pressParams = objectMapper.createObjectNode();
        pressParams.put("type", "mousePressed");
        pressParams.put("x", x);
        pressParams.put("y", y);
        pressParams.put("button", "left");
        pressParams.put("clickCount", 1);
        press.set("params", pressParams);
        calls.add(press);

        ObjectNode release = objectMapper.createObjectNode();
        release.put("method", "Input.dispatchMouseEvent");
        ObjectNode releaseParams = objectMapper.createObjectNode();
        releaseParams.put("type", "mouseReleased");
        releaseParams.put("x", x);
        releaseParams.put("y", y);
        releaseParams.put("button", "left");
        releaseParams.put("clickCount", 1);
        release.set("params", releaseParams);
        calls.add(release);

        batchPayload.set("calls", calls);

        BrowserBridgeClient.BridgeResponse clickResp = callBridge("/cdp", batchPayload);
        boolean dispatched = clickResp.ok() && allCallsOk(clickResp);

        String mode = "真实鼠标事件";
        if (!dispatched) {
            // 回退：直接 el.click()。少数站点会拦截合成事件，但总比什么都没发生好。
            String jsClick = jsClickExpression(ref);
            BrowserBridgeClient.BridgeResponse fallback = evaluate(jsClick);
            JsonNode fbValue = fallback.ok() ? parseJsonValue(evaluateValue(fallback)) : null;
            if (fbValue != null && fbValue.path("ok").asBoolean(false)) {
                mode = "JS 直接触发（合成鼠标事件未生效）";
            } else {
                return fail("点击 " + ref + " 失败：两种方式都没生效。"
                        + "\n请先 action=snapshot 确认元素仍存在且未被弹层遮挡。");
            }
        }

        StringBuilder sb = new StringBuilder();
        sb.append("已点击 ").append(ref);
        if (!elementText.isBlank()) {
            sb.append("「").append(elementText).append("」");
        }
        sb.append('\n');
        sb.append("· 方式：").append(mode).append("（坐标 ").append(x).append(',').append(y).append("）\n");
        if (coordValue.path("crossOrigin").asBoolean(false)) {
            sb.append("· 目标：跨域 iframe 区域，按相对位置 rx=").append(rx).append(" ry=").append(ry).append(" 点击\n");
            sb.append("  iframe 内部元素读不到：要看结果用 screenshot；要输入文字先 click 聚焦再用 press 键入\n");
        }
        appendEvents(sb, clickResp);
        sb.append("\n下一步：action=snapshot 确认点击结果。");
        return sb.toString();
    }

    private String doFill(JsonNode arguments) {
        String ref = arguments.path("ref").asText("").trim();
        if (ref.isEmpty()) {
            return fail("fill 需要 ref 参数（来自最近一次 snapshot 的元素编号，如 e12）");
        }
        if (!arguments.has("value")) {
            return fail("fill 需要 value 参数（传空字符串表示清空）");
        }
        String value = arguments.path("value").asText("");
        if (!ensureWindowAlive()) {
            return fail(windowMissingHint());
        }

        // 第一段：聚焦并全选（这样后面的 insertText 会替换原有内容）
        BrowserBridgeClient.BridgeResponse focusResp = evaluate(focusAndSelectExpression(ref));
        if (!focusResp.ok()) {
            return fail("聚焦元素失败：" + focusResp.error());
        }
        JsonNode focusValue = parseJsonValue(evaluateValue(focusResp));
        if (focusValue == null || !focusValue.path("ok").asBoolean(false)) {
            String reason = focusValue == null ? "无法解析元素信息" : focusValue.path("error").asText("未知原因");
            return fail("无法填入 " + ref + "：" + reason
                    + "\n请先 action=snapshot 确认这个编号对应的是输入框。");
        }
        String tagName = focusValue.path("tag").asText("");
        boolean editable = focusValue.path("editable").asBoolean(false);

        String mode;
        if ("SELECT".equalsIgnoreCase(tagName)) {
            // select 不能用键盘输入，直接设 value 并派发 change
            mode = "下拉框赋值";
            BrowserBridgeClient.BridgeResponse selectResp = evaluate(nativeSetExpression(ref, value, tagName));
            JsonNode selectValue = selectResp.ok() ? parseJsonValue(evaluateValue(selectResp)) : null;
            if (selectValue == null || !selectValue.path("ok").asBoolean(false)) {
                return fail("设置下拉框失败：" + (selectResp.error() == null ? "页面脚本未返回结果" : selectResp.error()));
            }
        } else if (!editable) {
            return fail("元素 " + ref + " 不是可输入控件（" + tagName + "）。"
                    + "只有 input / textarea / select 支持 fill。");
        } else {
            // 第二段：用真实键盘事件插入文本（能触发 React/Vue 的受控更新链路）
            mode = "键盘输入";
            if (!value.isEmpty()) {
                ObjectNode payload = basePayload();
                payload.put("method", "Input.insertText");
                ObjectNode params = objectMapper.createObjectNode();
                params.put("text", value);
                payload.set("params", params);
                BrowserBridgeClient.BridgeResponse typeResp = callBridge("/cdp", payload);
                if (!typeResp.ok()) {
                    mode = "原生 setter（键盘输入失败）";
                }
            }

            // 第三段：校验实际值，不一致就走原生 setter（React 受控组件常见：
            // insertText 进去了但框架 state 没同步，下次渲染又被覆盖回去）
            BrowserBridgeClient.BridgeResponse verifyResp = evaluate(readValueExpression(ref));
            JsonNode verifyValue = verifyResp.ok() ? parseJsonValue(evaluateValue(verifyResp)) : null;
            String actual = verifyValue == null ? null : verifyValue.path("value").asText(null);
            if (actual == null || !actual.equals(value)) {
                BrowserBridgeClient.BridgeResponse setResp = evaluate(nativeSetExpression(ref, value, tagName));
                JsonNode setValue = setResp.ok() ? parseJsonValue(evaluateValue(setResp)) : null;
                if (setValue == null || !setValue.path("ok").asBoolean(false)) {
                    return fail("填入失败：键盘输入后值不匹配，原生 setter 兜底也没成功。"
                            + "\n期望：" + preview(value) + "，实际：" + preview(actual));
                }
                mode = "原生 setter（受控组件兜底）";
            }
        }

        StringBuilder sb = new StringBuilder();
        sb.append("已填入 ").append(ref).append('\n');
        sb.append("· 内容：").append(preview(value)).append('\n');
        sb.append("· 方式：").append(mode).append('\n');
        sb.append("\n下一步：action=snapshot 检查表单其余字段，填写完后用 click 点提交按钮。");
        return sb.toString();
    }

    // ── P1 动作：select / press / hover / scroll / wait / screenshot / console / eval ──

    /**
     * 下拉框选择。
     *
     * <p>为什么单独做一个动作而不是复用 fill：原生 select 的「选项」有两套标识
     * （显示文案 / value），模型经常只知道前者；这里两种都能匹配，失败时还把候选清单
     * 回给它，一轮就能纠正过来。</p>
     */
    private String doSelect(JsonNode arguments) {
        String ref = arguments.path("ref").asText("").trim();
        if (ref.isEmpty()) {
            return fail("select 需要 ref 参数（来自最近一次 snapshot 的元素编号，如 e12）");
        }
        String wanted = arguments.path("label").asText("").trim();
        boolean byLabel = !wanted.isEmpty();
        String value = arguments.has("value") ? arguments.path("value").asText("") : null;
        if (!byLabel && (value == null || value.isEmpty())) {
            return fail("select 需要 label（选项文案）或 value（选项值）。"
                    + "\nsnapshot 的「↳ 可选」行里能看到全部选项，形如：北京市→110000");
        }
        if (!ensureWindowAlive()) {
            return fail(windowMissingHint());
        }

        BrowserBridgeClient.BridgeResponse response =
                evaluate(selectOptionExpression(ref, byLabel ? wanted : null, byLabel ? null : value));
        if (!response.ok()) {
            return fail("设置下拉框失败：" + response.error());
        }
        JsonNode result = parseJsonValue(evaluateValue(response));
        if (result == null || !result.path("ok").asBoolean(false)) {
            String reason = result == null ? "页面脚本未返回结果" : result.path("error").asText("未知原因");
            return fail("选择失败：" + reason + describeOptions(result == null ? null : result.path("options"))
                    + "\n下一步：先 action=snapshot 确认该编号是下拉框（role=combobox），并查看 ↳ 可选 清单。");
        }

        StringBuilder sb = new StringBuilder();
        sb.append("已选择 ").append(ref).append("「")
                .append(result.path("text").asText("")).append("」\n");
        sb.append("· 选项值：").append(result.path("value").asText("")).append('\n');
        sb.append("· 方式：").append(result.path("via").asText("原生 select")).append('\n');
        appendEvents(sb, response);
        sb.append("\n下一步：action=snapshot 确认已选中，或继续填其它字段。");
        return sb.toString();
    }

    /**
     * 按键。
     *
     * <p>用 {@code Input.dispatchKeyEvent} 而不是 {@code Input.insertText}：
     * insertText 只产生「插入文本」，不产生 keydown/keyup，站点监听 Enter / Escape
     * 的逻辑（搜索提交、关闭弹层、快捷键）全都收不到。</p>
     */
    private String doPress(JsonNode arguments) {
        String rawKey = arguments.path("key").asText("").trim();
        if (rawKey.isEmpty()) {
            return fail("press 需要 key 参数。示例：Enter / Escape / Tab / ArrowDown / Control+a / Control+Enter");
        }
        KeyStroke stroke = parseKeyStroke(rawKey);
        if (stroke == null) {
            return fail("无法识别的按键：" + rawKey
                    + "\n可用：Enter / Escape / Tab / Backspace / Delete / Space / Home / End / "
                    + "PageUp / PageDown / ArrowUp / ArrowDown / ArrowLeft / ArrowRight / F1~F12 / "
                    + "单个字母数字（a、1）/ 组合键（Control+a、Control+Enter、Shift+Tab）");
        }
        if (!ensureWindowAlive()) {
            return fail(windowMissingHint());
        }

        // 先聚焦目标元素再按键：不聚焦的话 Enter 会打到页面 body 上，等于什么都没发生
        String ref = arguments.path("ref").asText("").trim();
        if (!ref.isEmpty()) {
            BrowserBridgeClient.BridgeResponse focusResp = evaluate(focusOnlyExpression(ref));
            if (focusResp.ok()) {
                JsonNode focusValue = parseJsonValue(evaluateValue(focusResp));
                if (focusValue == null || !focusValue.path("ok").asBoolean(false)) {
                    String reason = focusValue == null ? "无法解析元素信息"
                            : focusValue.path("error").asText("未知原因");
                    return fail("无法聚焦 " + ref + "：" + reason
                            + "\n下一步：先 action=snapshot 确认这个编号仍有效。");
                }
            }
        }

        ObjectNode payload = basePayload();
        ArrayNode calls = objectMapper.createArrayNode();
        // 带修饰键时不能带 text：Ctrl+A 是快捷键，不是「输入字符 a」
        boolean withText = stroke.modifiers() == 0;
        calls.add(keyEventCall("keyDown", stroke, withText));
        calls.add(keyEventCall("keyUp", stroke, false));
        payload.set("calls", calls);

        BrowserBridgeClient.BridgeResponse response = callBridge("/cdp", payload);
        if (!response.ok()) {
            return fail("按键失败：" + response.error());
        }
        boolean dispatched = allCallsOk(response);

        StringBuilder sb = new StringBuilder();
        sb.append("已按键：").append(rawKey);
        if (!ref.isEmpty()) {
            sb.append("（焦点在 ").append(ref).append("）");
        }
        sb.append('\n');
        if (!dispatched) {
            sb.append("· 注意：按键事件未全部确认送达，若页面无反应请重新 snapshot 并改用 click。\n");
        }
        appendEvents(sb, response);
        sb.append("\n下一步：action=snapshot 或 action=wait 确认页面反应。");
        return sb.toString();
    }

    /** 悬停：触发 hover 才出现的菜单 / tooltip */
    private String doHover(JsonNode arguments) {
        String ref = arguments.path("ref").asText("").trim();
        if (ref.isEmpty()) {
            return fail("hover 需要 ref 参数（来自最近一次 snapshot 的元素编号）");
        }
        if (!ensureWindowAlive()) {
            return fail(windowMissingHint());
        }

        BrowserBridgeClient.BridgeResponse coordResp = evaluate(coordinateExpression(ref, 0.5, 0.5));
        if (!coordResp.ok()) {
            return fail("定位元素失败：" + coordResp.error());
        }
        JsonNode coordValue = parseJsonValue(evaluateValue(coordResp));
        if (coordValue == null || !coordValue.path("ok").asBoolean(false)) {
            String reason = coordValue == null ? "无法解析元素坐标" : coordValue.path("error").asText("未知原因");
            return fail("无法悬停 " + ref + "：" + reason
                    + "\n页面结构可能已变化，请先 action=snapshot 重新获取元素编号。");
        }
        int x = coordValue.path("x").asInt(0);
        int y = coordValue.path("y").asInt(0);
        // 先移动到目标附近再移到目标中心：一次性跳过去，部分站点的 mouseenter 不触发
        ObjectNode payload = basePayload();
        ArrayNode calls = objectMapper.createArrayNode();
        calls.add(mouseCall("mouseMoved", x, Math.max(0, y - 4)));
        calls.add(mouseCall("mouseMoved", x, y));
        payload.set("calls", calls);

        BrowserBridgeClient.BridgeResponse response = callBridge("/cdp", payload);
        if (!response.ok()) {
            return fail("悬停失败：" + response.error());
        }

        StringBuilder sb = new StringBuilder();
        sb.append("已悬停 ").append(ref);
        String text = coordValue.path("text").asText("");
        if (!text.isBlank()) {
            sb.append("「").append(preview(text)).append("」");
        }
        sb.append("（坐标 ").append(x).append(',').append(y).append("）\n");
        appendEvents(sb, response);
        sb.append("\n下一步：action=snapshot 查看是否弹出了菜单 / 提示。");
        return sb.toString();
    }

    /** 滚动：页面级或元素内 */
    private String doScroll(JsonNode arguments) {
        if (!ensureWindowAlive()) {
            return fail(windowMissingHint());
        }
        String direction = arguments.path("direction").asText("down").trim().toLowerCase();
        if (!List.of("up", "down", "top", "bottom").contains(direction)) {
            direction = "down";
        }
        int amount = arguments.path("amount").asInt(0);
        if (amount <= 0) {
            amount = config.getScrollDefaultPx();
        }
        amount = Math.min(Math.max(amount, 50), 20_000);

        String ref = arguments.path("ref").asText("").trim();
        String expression = ref.isEmpty()
                ? scrollPageExpression(direction, amount)
                : scrollElementExpression(ref, direction, amount);

        BrowserBridgeClient.BridgeResponse response = evaluate(expression);
        if (!response.ok()) {
            return fail("滚动失败：" + response.error());
        }
        JsonNode value = parseJsonValue(evaluateValue(response));
        if (value == null || !value.path("ok").asBoolean(false)) {
            String reason = value == null ? "页面脚本未返回结果" : value.path("error").asText("未知原因");
            return fail("滚动失败：" + reason);
        }

        StringBuilder sb = new StringBuilder();
        String target = value.path("target").asText("");
        sb.append("已滚动").append(target.isBlank() ? "" : "（" + target + "）").append('\n');
        sb.append("· 位置：").append(value.path("describe").asText("")).append('\n');
        if (value.path("unchanged").asBoolean(false)) {
            sb.append("· 注意：滚动位置没有变化 —— 可能已到边界，或该区域本身不可滚动\n");
        }
        appendEvents(sb, response);
        sb.append("\n下一步：action=snapshot 查看滚动后出现的元素（懒加载列表常需要滚动才渲染）。");
        return sb.toString();
    }

    /**
     * 等待。
     *
     * <p>用轮询 {@code Runtime.evaluate} 而不是 CDP 的 {@code Page.waitForSelector}：
     * 后者只在较新版本可用，且换条件（文字 / URL）就没法用了；
     * 统一轮询一套表达式，行为可预期、好排查。</p>
     */
    private String doWait(JsonNode arguments) {
        String waitFor = arguments.path("waitFor").asText("").trim().toLowerCase();
        if (waitFor.isEmpty()) {
            return fail("wait 需要 waitFor 参数：time（固定等待）/ selector（等元素）/ "
                    + "text（等文字）/ url（等地址变化）");
        }
        if (!ensureWindowAlive()) {
            return fail(windowMissingHint());
        }
        long timeout = arguments.path("timeoutMs").asLong(0);
        if (timeout <= 0) {
            timeout = config.getWaitDefaultTimeoutMs();
        }
        timeout = Math.min(Math.max(timeout, 500L), Math.max(500L, config.getWaitMaxTimeoutMs()));

        switch (waitFor) {
            case "time" -> {
                sleepQuiet(timeout);
                return "已等待 " + timeout + " 毫秒。\n下一步：action=snapshot 看页面当前状态。";
            }
            case "selector" -> {
                String selector = arguments.path("selector").asText("").trim();
                if (selector.isEmpty()) {
                    return fail("waitFor=selector 时需要 selector 参数（CSS 选择器，如 #result、.ant-table-row）");
                }
                String state = arguments.path("state").asText("visible").trim().toLowerCase();
                boolean wantHidden = "hidden".equals(state);
                return pollUntil(() -> waitSelectorExpression(selector, wantHidden), timeout,
                        wantHidden ? "元素消失：" + selector : "元素出现：" + selector);
            }
            case "text" -> {
                String text = arguments.path("text").asText("").trim();
                if (text.isEmpty()) {
                    return fail("waitFor=text 时需要 text 参数（期望出现在页面正文里的文字）");
                }
                return pollUntil(() -> waitTextExpression(text), timeout,
                        "页面出现文字：" + preview(text, 40));
            }
            case "url" -> {
                String urlPart = arguments.path("url").asText("").trim();
                if (urlPart.isEmpty()) {
                    return fail("waitFor=url 时需要 url 参数（期望地址里包含的字符串，如 /dashboard）");
                }
                return pollUntil(() -> waitUrlExpression(urlPart), timeout,
                        "地址包含：" + urlPart);
            }
            default -> {
                return fail("不支持的 waitFor：" + waitFor + "。可用值：time / selector / text / url");
            }
        }
    }

    /** 轮询直到条件满足或超时；超时返回「仍未满足」而不是报错 */
    private String pollUntil(ExpressionSupplier supplier, long timeout, String label) {
        long deadline = System.currentTimeMillis() + timeout;
        JsonNode last = null;
        String lastError = null;
        while (System.currentTimeMillis() < deadline) {
            BrowserBridgeClient.BridgeResponse response = evaluate(supplier.get());
            if (response.status() == 410) {
                return fail("等待期间浏览器窗口被关闭了。需要继续操作请重新 action=open。");
            }
            if (!response.ok()) {
                lastError = response.error();
            } else {
                JsonNode value = parseJsonValue(evaluateValue(response));
                if (value != null && value.path("ok").asBoolean(false)) {
                    if (value.path("satisfied").asBoolean(false)) {
                        long spent = timeout - Math.max(0, deadline - System.currentTimeMillis());
                        StringBuilder sb = new StringBuilder();
                        sb.append("已等到 —— ").append(label).append('\n');
                        sb.append("· 耗时：").append(Math.max(0, spent)).append(" 毫秒\n");
                        String extra = value.path("describe").asText("");
                        if (!extra.isBlank()) {
                            sb.append("· 当前：").append(extra).append('\n');
                        }
                        sb.append("\n下一步：action=snapshot 读取新出现的元素。");
                        return sb.toString();
                    }
                    last = value;
                } else if (value != null) {
                    // 选择器语法错误之类：直接回给模型，别白等到超时
                    String error = value.path("error").asText("");
                    if (!error.isBlank()) {
                        return fail("等待条件无法执行：" + error);
                    }
                }
            }
            sleepQuiet(WAIT_POLL_MS);
        }

        StringBuilder sb = new StringBuilder();
        sb.append("⏱ 等待超时（").append(timeout).append(" 毫秒）—— ").append(label).append(" 至今仍未满足\n");
        if (last != null) {
            String describe = last.path("describe").asText("");
            if (!describe.isBlank()) {
                sb.append("· 当前：").append(describe).append('\n');
            }
        }
        if (lastError != null && !lastError.isBlank()) {
            sb.append("· 最后一次读取失败：").append(preview(lastError, 200)).append('\n');
        }
        sb.append("· 页面可能加载很慢，或条件写错了（先 action=snapshot 看实际结构再重试）\n");
        sb.append("· 若目标在跨域 iframe 内，选择器永远等不到 —— 改用 screenshot");
        return sb.toString();
    }

    /**
     * 页面截图并注入上下文。
     *
     * <p>复用 {@code desktop_inject} 的图片注入通道：截图落临时文件 → 上传为文件资产 →
     * 登记 {@code addInjectUserFileAssetId}，由主流程在下一轮把图片作为 user 消息发出去。
     * 这样模型「真的能看到」页面，而不是只知道「有个截图在那儿」。</p>
     */
    private String doScreenshot(JsonNode arguments) {
        if (!ensureWindowAlive()) {
            return fail(windowMissingHint());
        }
        boolean fullPage = arguments.path("fullPage").asBoolean(false);
        String format = arguments.path("format").asText("").trim().toLowerCase();
        if (!"jpeg".equals(format) && !"png".equals(format)) {
            format = config.getScreenshotFormat() == null ? "png"
                    : config.getScreenshotFormat().trim().toLowerCase(Locale.ROOT);
        }
        if (!"jpeg".equals(format)) {
            format = "png";
        }

        ObjectNode params = objectMapper.createObjectNode();
        params.put("format", format);
        if ("jpeg".equals(format)) {
            params.put("quality", Math.min(100, Math.max(1, config.getScreenshotQuality())));
        }

        StringBuilder rangeText = new StringBuilder();
        if (fullPage) {
            // 整页截图必须先拿 contentSize，否则 clip 只能靠猜
            Size size = layoutSize();
            if (size == null || size.width() <= 0 || size.height() <= 0) {
                return fail("无法获取页面尺寸，整页截图失败。可改用 fullPage=false 只截当前视口。");
            }
            ObjectNode clip = objectMapper.createObjectNode();
            clip.put("x", 0);
            clip.put("y", 0);
            clip.put("width", size.width());
            // 上限 8000px：再高图片会大到看不清，也容易被下游压缩
            clip.put("height", Math.min(size.height(), 8000));
            clip.put("scale", 1);
            params.set("clip", clip);
            params.put("captureBeyondViewport", true);
            rangeText.append("整页 ").append(size.width()).append('×').append(clip.path("height").asInt());
        } else {
            rangeText.append("当前视口");
        }

        ObjectNode payload = basePayload();
        payload.put("method", "Page.captureScreenshot");
        payload.set("params", params);

        BrowserBridgeClient.BridgeResponse response = callBridge("/cdp", payload);
        if (!response.ok()) {
            return fail("截图失败：" + response.error());
        }
        JsonNode result = response.node("result");
        String base64 = result == null ? "" : result.path("data").asText("");
        if (base64.isBlank()) {
            return fail("截图失败：页面没有返回图像数据（可能窗口已隐藏到最小化，或 GPU 合成被禁用）。"
                    + "\n可先 action=show 把窗口显示出来再试。");
        }
        if (base64.length() > SHOT_MAX_B64_CHARS) {
            return fail("截图体积过大（约 " + (base64.length() / 1024 / 1024) + "MB），已放弃。"
                    + "\n请改用 fullPage=false 只截视口，或让窗口小一些（先 action=open 指定 width/height）。");
        }

        byte[] raw;
        try {
            raw = Base64.getDecoder().decode(base64);
        } catch (IllegalArgumentException e) {
            return fail("截图数据解码失败：" + e.getMessage());
        }
        byte[] image = downscaleIfNeeded(raw, format);
        String ext = "jpeg".equals(format) && image == raw ? "jpg" : "png";

        String filename = "browser_" + SHOT_TIME_FORMAT.format(LocalDateTime.now()) + "." + ext;
        Path saved = null;
        try {
            Path dir = Paths.get(System.getProperty("java.io.tmpdir", "."), SHOT_DIR);
            Files.createDirectories(dir);
            saved = dir.resolve(filename);
            Files.write(saved, image);
        } catch (Exception e) {
            log.debug("浏览器截图落盘失败（不影响注入）: {}", e.getMessage());
        }

        Long userId = ToolContext.getUserId();
        if (userId == null) {
            return "已截图（" + rangeText + "，约 " + (image.length / 1024) + "KB）"
                    + (saved == null ? "" : "，保存在 " + saved)
                    + "\n但当前会话缺少用户信息，未能注入到对话上下文 —— 请改用 action=snapshot 读取元素。";
        }

        String assetNote;
        try {
            FileAssetVO asset = fileAssetService.uploadLocalBytes(
                    image, filename, userId, ToolContext.getProviderCode(), "tool_browser");
            // 标记来源：注入文案走「网页截图」版本（不含坐标网格协议，避免误导模型数格子）
            ToolContext.setInjectKind("browser");
            ToolContext.addInjectUserFileAssetId(asset.getId());
            assetNote = "已注入你的上下文（下一轮对话里会直接看到这张图）";
        } catch (Exception e) {
            log.warn("浏览器截图上传失败: {}", e.getMessage());
            return "已截图（" + rangeText + "，约 " + (image.length / 1024) + "KB）"
                    + (saved == null ? "" : "，保存在 " + saved)
                    + "\n但上传/注入失败：" + e.getMessage()
                    + "\n（截图本身可用，只是模型看不到；元素读取请改用 action=snapshot）";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("已截取页面：").append(rangeText).append('\n');
        sb.append("· 尺寸：").append(imageDimensionText(image)).append('\n');
        sb.append("· 大小：约 ").append(image.length / 1024).append(" KB\n");
        if (saved != null) {
            sb.append("· 文件：").append(saved).append('\n');
        }
        sb.append("· 状态：").append(assetNote).append('\n');
        appendEvents(sb, response);
        sb.append("\n提示：图片看完后继续操作请用 snapshot 拿元素编号（截图不能点）。");
        return sb.toString();
    }

    /**
     * 读取页面事件（console 错误 / 未捕获异常 / 网络失败 / 自动处理的弹窗）。
     *
     * <p>走 bridge 的 {@code /events} 而不是先 evaluate：页面导航中或已崩溃时
     * evaluate 会失败，而那恰恰是最需要看报错的时候。</p>
     */
    private String doConsole(JsonNode arguments) {
        ObjectNode payload = basePayload();
        payload.put("limit", config.getConsoleMaxEvents());
        if (arguments.has("sinceSeq")) {
            payload.put("sinceSeq", Math.max(0, arguments.path("sinceSeq").asLong(0)));
        }
        BrowserBridgeClient.BridgeResponse response = callBridge("/events", payload);
        if (!response.ok()) {
            if (response.status() == 410) {
                return fail("本会话还没有打开浏览器窗口。先 action=open 再操作页面。");
            }
            return fail("读取页面事件失败：" + response.error());
        }

        JsonNode events = response.node("events");
        int total = events == null || !events.isArray() ? 0 : events.size();
        StringBuilder sb = new StringBuilder();
        if (total == 0) {
            sb.append("本次会话自上次检查以来没有新的页面事件。\n");
            sb.append("（页面无 JS 报错、无 console.error/warn、无失败请求、无阻塞弹窗）\n");
            sb.append("\n如果页面行为异常但不报错，下一步建议：action=snapshot 看元素是否真的存在，"
                    + "或 action=screenshot 直接看画面。");
            return sb.toString();
        }

        int dropped = response.body() == null ? 0 : response.body().path("dropped").asInt(0);
        sb.append("页面事件 ").append(total).append(" 条");
        if (dropped > 0) {
            sb.append("（另有 ").append(dropped).append(" 条更早的未显示）");
        }
        sb.append("：\n");
        int index = 0;
        for (JsonNode event : events) {
            index++;
            String type = event.path("type").asText("");
            String level = event.path("level").asText("");
            String text = event.path("text").asText("");
            if (text.isBlank()) continue;
            String tag = switch (type) {
                case "exception" -> "❌ 未捕获异常";
                case "console" -> "error".equals(level) ? "❌ console.error" : "⚠️ console.warn";
                case "log" -> "error".equals(level) ? "❌ 网络/日志" : "⚠️ 网络/日志";
                case "dialog" -> "🔔 弹窗（已自动确定）";
                default -> type;
            };
            sb.append("  ").append(index).append(". ").append(tag).append("：")
                    .append(preview(text, 400)).append('\n');
        }
        sb.append("\n提示：这些是页面的真实反馈 —— 接口 4xx/5xx 通常意味着参数或权限问题，")
                .append("JS 异常通常意味着页面脚本被破坏。");
        return sb.toString();
    }

    /**
     * 在页面内执行自定义脚本（逃生舱）。
     *
     * <p>默认关闭：它能绕过本工具的全部安全护栏（读 cookie、以用户身份发请求、改页面状态）。
     * 需要用户显式在 {@code application.yml} 打开 {@code codecraft.browser.allow-script-eval}。</p>
     */
    private String doEval(JsonNode arguments) {
        if (!config.isAllowScriptEval()) {
            return fail("eval 已被配置禁用（codecraft.browser.allow-script-eval=false）。\n"
                    + "绝大多数操作可以用 click / fill / select / press / scroll / wait 完成；"
                    + "确实需要执行自定义脚本时，请让用户在 application.yml 打开该开关并重启后端。");
        }
        String script = arguments.path("script").asText("").trim();
        if (script.isEmpty()) {
            return fail("eval 需要 script 参数（JS 表达式或语句；支持返回 Promise）");
        }
        if (!ensureWindowAlive()) {
            return fail(windowMissingHint());
        }

        ObjectNode payload = basePayload();
        payload.put("method", "Runtime.evaluate");
        ObjectNode params = objectMapper.createObjectNode();
        params.put("expression", script);
        params.put("returnByValue", true);
        // eval 允许返回 Promise：这是它与内部脚本最大的区别（内部脚本都同步返回）
        params.put("awaitPromise", true);
        params.put("timeout", Math.max(1000L, config.getEvalTimeoutMs()));
        payload.set("params", params);

        long started = System.currentTimeMillis();
        BrowserBridgeClient.BridgeResponse response = callBridge("/cdp", payload);
        if (!response.ok()) {
            return fail("脚本执行失败：" + response.error());
        }
        JsonNode value = evaluateValue(response);
        long elapsed = System.currentTimeMillis() - started;

        StringBuilder sb = new StringBuilder();
        sb.append("已执行自定义脚本（").append(script.length()).append(" 字符，耗时 ")
                .append(elapsed).append("ms）\n");
        sb.append("· 返回：").append(describeEvalValue(value)).append('\n');
        appendEvents(sb, response);
        sb.append("\n注意：eval 能绕过本工具的安全护栏，请只用于读取信息或验证假设。");
        return sb.toString();
    }

    // ── P1 辅助 ──────────────────────────────────────────────────

    /** 表达式供应器（wait 轮询用，避免每次循环都重新拼长字符串） */
    @FunctionalInterface
    private interface ExpressionSupplier {
        String get();
    }

    /** 页面尺寸（整页截图用） */
    private record Size(int width, int height) {
    }

    /**
     * 一个按键的 CDP 描述。
     *
     * @param key       事件里的 key（如 Enter、a、ArrowDown）
     * @param code       物理键位 code（如 Enter、KeyA）
     * @param vk         windowsVirtualKeyCode
     * @param text       该键产生的文本（Enter 为 \r、可打印字符为自身；无产出为 null）
     * @param modifiers  修饰键位掩码：Alt=1 / Control=2 / Meta=4 / Shift=8
     * @param raw        调用方传入的原始写法（回执里展示）
     */
    private record KeyStroke(String key, String code, int vk, String text,
                             int modifiers, String raw) {
    }

    /**
     * 解析按键写法：{@code Control+Shift+Enter} → KeyStroke。
     *
     * <p>不支持任意符号键（如 {@code Control+;}）：这类需求极少，且符号的 code / vk
     * 映射在三大平台不一致，猜错会静默无效果，不如直接不支持。</p>
     *
     * @return 解析失败返回 null（由调用方给出可用示例）
     */
    private KeyStroke parseKeyStroke(String raw) {
        String text = raw == null ? "" : raw.trim();
        if (text.isEmpty()) return null;

        int modifiers = 0;
        String main = "";
        for (String part : text.split("\\+", -1)) {
            String p = part == null ? "" : part.trim();
            if (p.isEmpty()) continue;
            switch (p.toLowerCase(Locale.ROOT)) {
                case "control", "ctrl" -> modifiers |= 2;
                case "alt", "option" -> modifiers |= 1;
                case "shift" -> modifiers |= 8;
                case "meta", "cmd", "command", "win", "super" -> modifiers |= 4;
                default -> main = p;
            }
        }
        if (main.isEmpty()) return null;

        String lower = main.toLowerCase(Locale.ROOT);
        // F1~F12：vk 112~123
        if (lower.matches("^f([1-9]|1[0-2])$")) {
            int n = Integer.parseInt(lower.substring(1));
            return new KeyStroke("F" + n, "F" + n, 111 + n, null, modifiers, raw);
        }
        switch (lower) {
            case "enter", "return":
                return new KeyStroke("Enter", "Enter", 13, "\r", modifiers, raw);
            case "tab":
                return new KeyStroke("Tab", "Tab", 9, null, modifiers, raw);
            case "escape", "esc":
                return new KeyStroke("Escape", "Escape", 27, null, modifiers, raw);
            case "backspace":
                return new KeyStroke("Backspace", "Backspace", 8, null, modifiers, raw);
            case "delete", "del":
                return new KeyStroke("Delete", "Delete", 46, null, modifiers, raw);
            case "space":
                return new KeyStroke(" ", "Space", 32, " ", modifiers, raw);
            case "arrowup", "up":
                return new KeyStroke("ArrowUp", "ArrowUp", 38, null, modifiers, raw);
            case "arrowdown", "down":
                return new KeyStroke("ArrowDown", "ArrowDown", 40, null, modifiers, raw);
            case "arrowleft", "left":
                return new KeyStroke("ArrowLeft", "ArrowLeft", 37, null, modifiers, raw);
            case "arrowright", "right":
                return new KeyStroke("ArrowRight", "ArrowRight", 39, null, modifiers, raw);
            case "home":
                return new KeyStroke("Home", "Home", 36, null, modifiers, raw);
            case "end":
                return new KeyStroke("End", "End", 35, null, modifiers, raw);
            case "pageup":
                return new KeyStroke("PageUp", "PageUp", 33, null, modifiers, raw);
            case "pagedown":
                return new KeyStroke("PageDown", "PageDown", 34, null, modifiers, raw);
            case "insert":
                return new KeyStroke("Insert", "Insert", 45, null, modifiers, raw);
            default:
                break;
        }
        if (lower.length() == 1) {
            char c = lower.charAt(0);
            if (c >= 'a' && c <= 'z') {
                String upper = String.valueOf(Character.toUpperCase(c));
                return new KeyStroke(lower, "Key" + upper, upper.charAt(0), lower, modifiers, raw);
            }
            if (c >= '0' && c <= '9') {
                return new KeyStroke(lower, "Digit" + c, c, lower, modifiers, raw);
            }
        }
        return null;
    }

    /** 组装一条 Input.dispatchKeyEvent 调用 */
    private ObjectNode keyEventCall(String type, KeyStroke stroke, boolean withText) {
        ObjectNode call = objectMapper.createObjectNode();
        call.put("method", "Input.dispatchKeyEvent");
        ObjectNode params = objectMapper.createObjectNode();
        params.put("type", type);
        params.put("key", stroke.key());
        if (stroke.code() != null && !stroke.code().isBlank()) {
            params.put("code", stroke.code());
        }
        params.put("windowsVirtualKeyCode", stroke.vk());
        params.put("nativeVirtualKeyCode", stroke.vk());
        if (stroke.modifiers() != 0) {
            params.put("modifiers", stroke.modifiers());
        }
        if (withText && stroke.text() != null && !stroke.text().isEmpty()) {
            params.put("text", stroke.text());
            params.put("unmodifiedText", stroke.text());
        }
        call.set("params", params);
        return call;
    }

    /** 组装一条 Input.dispatchMouseEvent 调用 */
    private ObjectNode mouseCall(String type, int x, int y) {
        ObjectNode call = objectMapper.createObjectNode();
        call.put("method", "Input.dispatchMouseEvent");
        ObjectNode params = objectMapper.createObjectNode();
        params.put("type", type);
        params.put("x", x);
        params.put("y", y);
        if (!"mouseMoved".equals(type)) {
            params.put("button", "left");
            params.put("clickCount", 1);
        }
        call.set("params", params);
        return call;
    }

    /** 用 Page.getLayoutMetrics 拿整页尺寸 */
    private Size layoutSize() {
        ObjectNode payload = basePayload();
        payload.put("method", "Page.getLayoutMetrics");
        payload.put("params", objectMapper.createObjectNode());
        BrowserBridgeClient.BridgeResponse response = callBridge("/cdp", payload);
        if (!response.ok()) return null;

        JsonNode result = response.node("result");
        if (result == null) return null;
        // 新版 CDP 用 cssContentSize，老版用 contentSize，两个都兼容
        JsonNode size = result.path("cssContentSize");
        if (!size.has("width")) {
            size = result.path("contentSize");
        }
        int width = (int) Math.ceil(size.path("width").asDouble(0));
        int height = (int) Math.ceil(size.path("height").asDouble(0));
        if (width <= 0 || height <= 0) return null;
        return new Size(width, height);
    }

    /** 截图超长边时等比缩小（避免 base64 撑爆 bridge 的请求/响应体） */
    private byte[] downscaleIfNeeded(byte[] raw, String format) {
        int maxEdge = Math.max(200, config.getScreenshotMaxEdge());
        try {
            java.awt.image.BufferedImage image =
                    javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(raw));
            if (image == null) return raw;
            int longEdge = Math.max(image.getWidth(), image.getHeight());
            if (longEdge <= maxEdge) return raw;

            double scale = (double) maxEdge / longEdge;
            int width = Math.max(1, (int) Math.round(image.getWidth() * scale));
            int height = Math.max(1, (int) Math.round(image.getHeight() * scale));
            java.awt.image.BufferedImage scaled = new java.awt.image.BufferedImage(
                    width, height, java.awt.image.BufferedImage.TYPE_INT_RGB);
            java.awt.Graphics2D g = scaled.createGraphics();
            g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION,
                    java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.drawImage(image, 0, 0, width, height, null);
            g.dispose();

            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            javax.imageio.ImageIO.write(scaled, "png", out);
            log.debug("浏览器截图已缩放：{}x{} → {}x{}（format={}）",
                    image.getWidth(), image.getHeight(), width, height, format);
            return out.toByteArray();
        } catch (Exception e) {
            log.debug("浏览器截图缩放失败，使用原图: {}", e.getMessage());
            return raw;
        }
    }

    /** 图片尺寸描述（读不出来就不说，不编造） */
    private String imageDimensionText(byte[] image) {
        try {
            java.awt.image.BufferedImage buffered =
                    javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(image));
            if (buffered == null) return "未知";
            return buffered.getWidth() + "×" + buffered.getHeight();
        } catch (Exception e) {
            return "未知";
        }
    }

    /** select 失败时把候选清单附上，让模型一轮就能改对 */
    private String describeOptions(JsonNode options) {
        if (options == null || !options.isArray() || options.isEmpty()) return "";
        StringBuilder sb = new StringBuilder("\n· 该下拉框的选项：");
        int index = 0;
        for (JsonNode option : options) {
            if (index++ > 0) sb.append(" · ");
            sb.append(option.asText(""));
            if (index >= 20) {
                sb.append(" · …");
                break;
            }
        }
        return sb.toString();
    }

    /** eval 返回值的可读形式 */
    private String describeEvalValue(JsonNode value) {
        if (value == null || value.isNull() || value.isMissingNode()) return "undefined";
        if (value.isTextual()) return preview(value.asText(""), 600);
        String json;
        try {
            json = objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            json = value.toString();
        }
        return preview(json, 600);
    }

    private void sleepQuiet(long millis) {
        try {
            Thread.sleep(Math.max(1L, millis));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private String doShow(boolean visible) {
        if (!ensureWindowAlive()) {
            return fail(windowMissingHint());
        }
        ObjectNode payload = basePayload();
        payload.put("visible", visible);
        BrowserBridgeClient.BridgeResponse response = bridge.call("/show", payload);
        if (!response.ok()) {
            return fail((visible ? "显示" : "隐藏") + "窗口失败：" + response.error());
        }
        return visible
                ? "浏览器窗口已显示。"
                : "浏览器窗口已隐藏（操作仍会正常执行，只是看不见）。需要时用 action=show 恢复显示。";
    }

    private String doClose(JsonNode arguments) {
        boolean all = arguments.path("all").asBoolean(false);
        ObjectNode payload = basePayload();
        if (all) {
            payload.remove("tabId");
            payload.put("all", true);
        }
        BrowserBridgeClient.BridgeResponse response = bridge.call("/close", payload);
        if (!response.ok()) {
            return fail("关闭窗口失败：" + response.error());
        }
        boolean closed = response.flag("closed", false);
        if (!closed) {
            forgetTab(conversationKey());
            return "本会话当前没有打开的浏览器窗口（无需关闭）。";
        }
        int closedCount = response.body() == null ? 1 : response.body().path("closedCount").asInt(1);
        int remain = response.body() == null ? 0 : response.body().path("remain").asInt(0);
        if (all || remain <= 0) {
            // 标签全没了：活跃标签与事件游标都要清，否则新窗口的事件会被当成「已消费」
            forgetTab(conversationKey());
            eventCursors.keySet().removeIf(k -> k.startsWith(conversationKey() + "::"));
            return all
                    ? "已关闭本会话全部 " + closedCount + " 个浏览器标签。\n登录态会保留在项目中，下次 open 打开同一站点仍是登录状态。"
                    : "已关闭本会话的浏览器窗口。\n登录态会保留在项目中，下次 open 打开同一站点仍是登录状态。";
        }
        // 还有剩余标签：把活跃标签同步成 bridge 的选择（关掉的若正好是当前标签，必须换到别的上面）
        refreshActiveTab();
        String tabId = response.text("tabId", "");
        return "已关闭浏览器标签 " + (tabId.isBlank() ? "" : tabId)
                + "（剩余 " + remain + " 个）\n后续动作用当前活跃标签 "
                + activeTabs.getOrDefault(conversationKey(), "?") + "。";
    }

    // ── 标签管理（多标签） ───────────────────────────────────────

    /**
     * 标签管理：同会话内可以并行保留多个页面。
     *
     * <p>为什么把标签做成独立窗口而不是自绘标签栏：AI 浏览器窗口加载的是任意站点，
     * 往远程页面里注入标签栏会被站点 CSP / 全局样式打乱，反而破坏要测的页面本身。
     * 标签在视觉上就是多个窗口，语义上属于同一会话、共享登录态（同一 session partition）。</p>
     *
     * <p>op：{@code list}（只读免授权）/ {@code new}（可带 url）/ {@code switch} / {@code close}。</p>
     */
    private String doTab(JsonNode arguments) {
        String op = arguments.path("op").asText("list").trim().toLowerCase();
        return switch (op) {
            case "list" -> doTabList();
            case "new" -> openTab(arguments, true);
            case "switch" -> doTabSwitch(arguments);
            case "close" -> doTabClose(arguments);
            default -> fail("tab 的 op 只支持 list / new / switch / close，收到：" + op);
        };
    }

    private String doTabList() {
        ObjectNode payload = basePayload();
        // 列全部标签：必须摘掉活跃 tabId，否则 bridge 会以为要查单个
        payload.remove("tabId");
        BrowserBridgeClient.BridgeResponse response = bridge.call("/tabs", payload);
        if (!response.ok()) {
            return fail("查询标签失败：" + response.error());
        }
        JsonNode tabs = response.node("tabs");
        String active = response.text("activeTabId", "");
        int count = tabs == null ? 0 : tabs.size();
        StringBuilder sb = new StringBuilder();
        sb.append("本会话浏览器标签：").append(count).append(" 个");
        if (count == 0) {
            sb.append("（还没有打开，用 action=open 创建）\n");
            return sb.toString();
        }
        sb.append("（当前 ").append(active).append("，上限 ")
                .append(response.body() == null ? 4 : response.body().path("maxTabsPerConversation").asInt(4))
                .append("）\n");
        for (JsonNode tab : tabs) {
            sb.append("  · ").append(tab.path("tabId").asText("?"));
            if (tab.path("active").asBoolean(false)) {
                sb.append(" [当前]");
            } else {
                sb.append("      ");
            }
            sb.append("  ").append(tab.path("visible").asBoolean(false) ? "可见" : "隐藏");
            sb.append("  ").append(tab.path("pageUrl").asText("(空白)")).append('\n');
        }
        sb.append("\n切换：action=tab op=switch tabId=t2；关闭：action=tab op=close tabId=t2");
        return sb.toString();
    }

    private String doTabSwitch(JsonNode arguments) {
        String tabId = arguments.path("tabId").asText("").trim();
        if (tabId.isEmpty()) {
            return fail("tab op=switch 需要 tabId（先 action=tab op=list 查看现有标签）");
        }
        ObjectNode payload = basePayload();
        payload.remove("tabId");
        payload.put("tabId", tabId);
        payload.put("show", arguments.path("show").asBoolean(true));
        BrowserBridgeClient.BridgeResponse response = bridge.call("/tabs", payload);
        if (!response.ok()) {
            if (response.status() == 404) {
                return fail("标签 " + tabId + " 不存在（可能已关闭或被空闲回收）。"
                        + "先 action=tab op=list 看看还有哪些，或 action=open 新建。");
            }
            return fail("切换标签失败：" + response.error());
        }
        activeTabs.put(conversationKey(), tabId);
        String pageUrl = "";
        JsonNode tabs = response.node("tabs");
        if (tabs != null) {
            for (JsonNode tab : tabs) {
                if (tabId.equals(tab.path("tabId").asText(""))) {
                    pageUrl = tab.path("pageUrl").asText("");
                    break;
                }
            }
        }
        return "已切换到标签 " + tabId + "\n"
                + "· 当前页面：" + (pageUrl.isBlank() ? "(空白页)" : pageUrl) + "\n"
                + "后续动作默认作用于该标签（要换回来用 action=tab op=list 查看编号）。";
    }

    private String doTabClose(JsonNode arguments) {
        String key = conversationKey();
        String tabId = arguments.path("tabId").asText("").trim();
        if (tabId.isEmpty()) {
            tabId = activeTabs.getOrDefault(key, "");
        }
        if (tabId.isEmpty()) {
            return fail("当前没有可关闭的标签（先 action=tab op=list 查看，或 action=open 新建）");
        }
        ObjectNode payload = basePayload();
        payload.remove("tabId");
        payload.put("tabId", tabId);
        BrowserBridgeClient.BridgeResponse response = bridge.call("/close", payload);
        if (!response.ok()) {
            return fail("关闭标签失败：" + response.error());
        }
        if (!response.flag("closed", false)) {
            forgetTab(key);
            return "标签 " + tabId + " 本就不存在（可能已关闭）。";
        }
        int remain = response.body() == null ? 0 : response.body().path("remain").asInt(0);
        // 被关掉的标签事件游标要清掉：编号可能被后续新标签复用
        eventCursors.remove(key + "::" + tabId);
        if (remain <= 0) {
            forgetTab(key);
            return "已关闭最后一个标签 " + tabId + "。\n再操作页面需要先 action=open（登录态会保留）。";
        }
        refreshActiveTab();
        return "已关闭标签 " + tabId + "（剩余 " + remain + " 个）\n"
                + "当前活跃标签：" + activeTabs.getOrDefault(key, "?");
    }

    /** 向 bridge 同步一次活跃标签（关闭/切换后调用，避免本地缓存与真实状态漂移） */
    private void refreshActiveTab() {
        String key = conversationKey();
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("conversationId", key);
        BrowserBridgeClient.BridgeResponse response = bridge.call("/tabs", payload);
        if (!response.ok()) return;
        String active = response.text("activeTabId", "");
        if (active.isBlank()) {
            forgetTab(key);
        } else {
            activeTabs.put(key, active);
        }
    }

    private String doList() {
        ObjectNode payload = objectMapper.createObjectNode();
        BrowserBridgeClient.BridgeResponse response = bridge.call("/list", payload);
        if (!response.ok()) {
            return fail("查询窗口列表失败：" + response.error());
        }
        JsonNode windows = response.node("windows");
        StringBuilder sb = new StringBuilder();
        int count = windows == null ? 0 : windows.size();
        sb.append("当前 AI 浏览器标签：").append(count)
                .append(" 个（全局上限 ").append(response.body().path("maxWindows").asInt(0)).append("）\n");
        if (count == 0) {
            sb.append("  （没有打开的窗口，用 action=open 创建）\n");
        } else {
            String mine = conversationKey();
            for (JsonNode win : windows) {
                String conv = win.path("conversationId").asText("?");
                sb.append("  · ").append(conv.equals(mine) ? "本会话 " : "会话 " + conv + " ")
                        .append(win.path("tabId").asText("?"))
                        .append(win.path("active").asBoolean(false) ? "[当前]" : "")
                        .append("  ").append(win.path("visible").asBoolean(false) ? "可见" : "隐藏")
                        .append("  ").append(win.path("pageUrl").asText("(空白)"))
                        .append("  空闲 ").append(win.path("idleMs").asLong(0) / 1000).append("s\n");
            }
        }
        return sb.toString();
    }

    // ── CDP 辅助 ─────────────────────────────────────────────────

    /** 当前会话的标识：会话 ID（拿不到时退化为 default，多个匿名调用共享一个窗口） */
    private String conversationKey() {
        Long id = ToolContext.getConversationId();
        return id == null ? "default" : String.valueOf(id);
    }

    private ObjectNode basePayload() {
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("conversationId", conversationKey());
        // 带上「当前活跃标签」：绝大多数动作都不显式指定 tabId，靠这里定位到正确的窗口
        String tabId = activeTabs.get(conversationKey());
        if (tabId != null && !tabId.isBlank()) {
            payload.put("tabId", tabId);
        }
        String root = ProjectRootContext.get();
        if (root != null && !root.isBlank()) {
            payload.put("projectRoot", root);
        }
        return payload;
    }

    /** 忘掉某会话的活跃标签缓存（标签被关闭 / bridge 回 410 时调用） */
    private void forgetTab(String conversationKey) {
        activeTabs.remove(conversationKey);
    }

    /**
     * 带事件游标的 bridge 调用：请求前带上 sinceSeq，响应后推进游标。
     *
     * <p>只有 {@code /cdp} 会返回 {@code lastSeq}/{@code events}，其它端点忽略这两个字段，
     * 所以统一走这里不会有副作用。</p>
     */
    private BrowserBridgeClient.BridgeResponse callBridge(String path, ObjectNode payload) {
        String key = cursorKey(payload);
        Long cursor = eventCursors.get(key);
        // 调用方显式给了 sinceSeq 时以它为准（console 动作支持「从头看」），否则用游标做增量
        if (cursor != null && !payload.has("sinceSeq")) {
            payload.put("sinceSeq", cursor);
        }
        BrowserBridgeClient.BridgeResponse response = bridge.call(path, payload);
        if (response.body() != null) {
            long lastSeq = response.body().path("lastSeq").asLong(0L);
            if (lastSeq > 0) {
                eventCursors.put(key, lastSeq);
            }
        }
        return response;
    }

    /**
     * 事件游标按「会话 + 标签」隔离。
     *
     * <p>多标签下若只按会话记游标，t1 消费到 seq=12 之后，t2 的 seq=1..12 会被整段吞掉 ——
     * 表现为「新标签页的报错一条都看不到」。所以 key 必须带上 tabId。</p>
     */
    private String cursorKey(ObjectNode payload) {
        String tab = payload.path("tabId").asText("");
        return conversationKey() + "::" + (tab.isBlank() ? "-" : tab);
    }

    /** 窗口是否还在；不在时给一句能指导下一步的提示 */
    private boolean ensureWindowAlive() {
        return bridge.isAvailable();
    }

    /**
     * 通道不可用时的提示。
     *
     * <p>注意这个方法只覆盖「通道层面」的不可用（没连上 / 被配置禁用）；
     * 窗口被关掉的情况由 bridge 返回的 410 承载，文案更精确。</p>
     */
    private String windowMissingHint() {
        return "浏览器通道当前不可用，可能是以下原因之一：\n"
                + "· 不在桌面客户端内运行 —— 浏览器工具依赖 Electron 主进程的 CDP 转发，"
                + "用浏览器直接开 8084 端口时不可用\n"
                + "· 客户端刚重启过 —— 端口会变，重试一次通常自动恢复\n"
                + "· 窗口已被关闭或空闲回收 —— 用 action=open 重新打开（同一项目下登录态会保留）\n"
                + "下一步：先 action=status 看具体原因。";
    }

    private BrowserBridgeClient.BridgeResponse evaluate(String expression) {
        ObjectNode payload = basePayload();
        payload.put("method", "Runtime.evaluate");
        ObjectNode params = objectMapper.createObjectNode();
        params.put("expression", expression);
        params.put("returnByValue", true);
        // 不要 awaitPromise：页面脚本都是同步返回，等 Promise 只会在页面卡住时白等
        params.put("awaitPromise", false);
        payload.set("params", params);
        return callBridge("/cdp", payload);
    }

    /** 从 evaluate 响应里取返回值；有 exceptionDetails 时抛异常（比返回 null 更好定位） */
    private JsonNode evaluateValue(BrowserBridgeClient.BridgeResponse response) {
        JsonNode result = response.node("result");
        if (result != null && result.hasNonNull("exceptionDetails")) {
            JsonNode details = result.path("exceptionDetails");
            String description = details.path("exception").path("description").asText("");
            String text = details.path("text").asText("");
            throw new IllegalStateException("页面脚本执行异常："
                    + (description.isBlank() ? text : description));
        }
        return response.node("value");
    }

    /** 页面脚本统一返回 JSON 字符串，这里解析成 JsonNode */
    private JsonNode parseJsonValue(JsonNode value) {
        if (value == null || value.isNull()) return null;
        if (!value.isTextual()) return value;
        try {
            return objectMapper.readTree(value.asText());
        } catch (Exception e) {
            log.debug("页面脚本返回值不是 JSON：{}", preview(value.asText()));
            return null;
        }
    }

    private boolean allCallsOk(BrowserBridgeClient.BridgeResponse response) {
        JsonNode results = response.node("results");
        if (results == null || !results.isArray() || results.isEmpty()) return false;
        for (JsonNode item : results) {
            if (!item.path("ok").asBoolean(false)) return false;
        }
        return true;
    }

    /** 轮询 document.readyState 直到 complete 或超时 */
    private String awaitReady() {
        long deadline = System.currentTimeMillis() + NAVIGATE_WAIT_MS;
        String last = "";
        while (System.currentTimeMillis() < deadline) {
            try {
                BrowserBridgeClient.BridgeResponse response = evaluate("document.readyState");
                if (response.ok()) {
                    JsonNode value = response.node("value");
                    if (value != null && value.isTextual()) {
                        last = value.asText("");
                        if ("complete".equals(last)) return last;
                    }
                }
                // 导航过程中 execution context 会被销毁，evaluate 报错属正常，继续轮询即可
            } catch (Exception e) {
                log.debug("等待页面就绪时出错（导航中属正常）：{}", e.getMessage());
            }
            sleep();
        }
        return last.isBlank() ? "timeout" : last;
    }

    private void sleep() {
        try {
            Thread.sleep(POLL_INTERVAL_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private String describeReadyState(String state) {
        if ("complete".equals(state)) return "已加载完成";
        if ("interactive".equals(state)) return "DOM 已就绪，资源仍在加载（可以先 snapshot 看元素）";
        if ("loading".equals(state)) return "仍在加载（部分元素可能还没出现，稍后可再 snapshot）";
        return "等待超时（页面可能很慢或有资源卡住，可直接 snapshot 看当前状态）";
    }

    /** 把浏览器事件（页面报错等）附到结果后面，帮模型理解「为什么点了没反应」 */
    private void appendEvents(StringBuilder sb, BrowserBridgeClient.BridgeResponse response) {
        if (response == null || response.body() == null) return;
        JsonNode events = response.node("events");
        if (events == null || !events.isArray() || events.isEmpty()) return;

        int shown = 0;
        StringBuilder lines = new StringBuilder();
        for (JsonNode event : events) {
            if (shown >= 5) break;
            String type = event.path("type").asText("");
            String text = event.path("text").asText("");
            if (text.isBlank()) continue;
            String label;
            switch (type) {
                case "exception" -> label = "页面异常";
                case "console" -> label = "控制台" + ("error".equals(event.path("level").asText()) ? "错误" : "警告");
                case "log" -> label = "网络/日志";
                case "dialog" -> label = "弹窗（已自动确定）";
                default -> label = type;
            }
            lines.append("       · ").append(label).append("：").append(preview(text, 300)).append('\n');
            shown++;
        }
        if (lines.length() > 0) {
            sb.append("\n[页面事件] 本次操作期间捕获到：\n").append(lines);
        }
    }

    // ── 页面内脚本 ────────────────────────────────────────────────

    // ── 页面内脚本（P1） ──────────────────────────────────────────

    /**
     * 下拉框选择脚本。
     *
     * <p>匹配顺序刻意是「文案精确 → 文案部分 → value」：模型从 snapshot 看到的是
     * 「北京市→110000」这种形式，通常直接抄左边的文案；部分匹配放在精确匹配之后，
     * 是为了避免「北京」先把「北京市」以外的项抢走。</p>
     */
    private String selectOptionExpression(String ref, String label, String value) {
        String labelJs = label == null ? "null" : jsString(label);
        String valueJs = value == null ? "null" : jsString(value);
        return """
                (() => {
                  try {
                    const el = (window.__ccRefs || {})[%s];
                    if (!el) return JSON.stringify({ ok: false, error: '编号不存在或已失效（每次 snapshot 都会重建编号）' });
                    if (!el.isConnected) return JSON.stringify({ ok: false, error: '元素已从页面移除' });
                    if (String(el.tagName).toUpperCase() !== 'SELECT') {
                      return JSON.stringify({ ok: false,
                        error: '该元素不是原生下拉框（' + el.tagName + '）；自定义下拉组件请用 click 点开后再点选项' });
                    }
                    const wantLabel = %s;
                    const wantValue = %s;
                    const opts = Array.prototype.slice.call(el.options || []);
                    let idx = -1;
                    let via = '';
                    if (wantLabel != null) {
                      for (let i = 0; i < opts.length; i++) {
                        const t = (opts[i].text || '').trim();
                        if (t === wantLabel) { idx = i; via = '文案精确匹配'; break; }
                      }
                      if (idx < 0) {
                        for (let i = 0; i < opts.length; i++) {
                          const t = (opts[i].text || '').trim();
                          if (t.indexOf(wantLabel) >= 0) { idx = i; via = '文案部分匹配'; break; }
                        }
                      }
                    }
                    if (idx < 0 && wantValue != null) {
                      for (let i = 0; i < opts.length; i++) {
                        if (String(opts[i].value) === wantValue) { idx = i; via = 'value 匹配'; break; }
                      }
                    }
                    if (idx < 0) {
                      const list = opts.slice(0, 20).map((o) => (o.text || '') + '→' + o.value);
                      return JSON.stringify({ ok: false, error: '找不到匹配的选项', options: list });
                    }
                    if (el.multiple) {
                      // 多选：切换该选项（覆盖式赋值会把用户其它选择清掉）
                      el.options[idx].selected = !el.options[idx].selected;
                      via += '（多选切换）';
                    } else {
                      el.selectedIndex = idx;
                    }
                    el.dispatchEvent(new Event('input', { bubbles: true }));
                    el.dispatchEvent(new Event('change', { bubbles: true }));
                    const chosen = el.options[el.selectedIndex] || el.options[idx];
                    return JSON.stringify({
                      ok: true,
                      index: idx,
                      text: chosen ? (chosen.text || '').trim() : '',
                      value: chosen ? String(chosen.value) : '',
                      via: via || '原生 select'
                    });
                  } catch (e) {
                    return JSON.stringify({ ok: false, error: String(e) });
                  }
                })()
                """.formatted(jsString(ref), labelJs, valueJs);
    }

    /** 只聚焦（press 前用）：不 select，避免把已有内容全选后被下一次按键清掉 */
    private String focusOnlyExpression(String ref) {
        return """
                (() => {
                  try {
                    const el = (window.__ccRefs || {})[%s];
                    if (!el) return JSON.stringify({ ok: false, error: '编号不存在或已失效' });
                    if (!el.isConnected) return JSON.stringify({ ok: false, error: '元素已从页面移除' });
                    if (el.__ccHost) { try { el.__ccHost.scrollIntoView({ block: 'center' }); } catch (e) {} }
                    try { el.scrollIntoView({ block: 'center', inline: 'nearest' }); } catch (e) {}
                    try { el.focus(); } catch (e) {}
                    return JSON.stringify({ ok: true, tag: el.tagName });
                  } catch (e) {
                    return JSON.stringify({ ok: false, error: String(e) });
                  }
                })()
                """.formatted(jsString(ref));
    }

    private String scrollPageExpression(String direction, int amount) {
        return """
                (() => {
                  try {
                    const dir = %s;
                    const amount = %d;
                    const beforeY = window.scrollY || window.pageYOffset || 0;
                    const doc = document.documentElement || {};
                    const viewH = window.innerHeight || 0;
                    const maxY = Math.max(0, (doc.scrollHeight || 0) - viewH);
                    let target = beforeY;
                    if (dir === 'top') target = 0;
                    else if (dir === 'bottom') target = maxY;
                    else if (dir === 'up') target = Math.max(0, beforeY - amount);
                    else target = Math.min(maxY, beforeY + amount);
                    window.scrollTo(0, target);
                    const afterY = window.scrollY || window.pageYOffset || 0;
                    return JSON.stringify({
                      ok: true,
                      unchanged: Math.abs(afterY - beforeY) < 1,
                      describe: Math.round(afterY) + ' / ' + Math.round(maxY)
                        + '（视口 ' + viewH + '，文档 ' + (doc.scrollHeight || 0) + '）'
                    });
                  } catch (e) {
                    return JSON.stringify({ ok: false, error: String(e) });
                  }
                })()
                """.formatted(jsString(direction), amount);
    }

    /**
     * 元素内滚动脚本。
     *
     * <p>元素自身不可滚动时向上找可滚动祖先（最多 12 层），都找不到就退化为页面滚动 ——
     * 模型经常把「列表里的某项」当容器传进来，直接报错不如滚页面有用。</p>
     */
    private String scrollElementExpression(String ref, String direction, int amount) {
        return """
                (() => {
                  try {
                    const el = (window.__ccRefs || {})[%s];
                    if (!el) return JSON.stringify({ ok: false, error: '编号不存在或已失效' });
                    if (!el.isConnected) return JSON.stringify({ ok: false, error: '元素已从页面移除' });
                    const dir = %s;
                    const amount = %d;
                    const view = (el.ownerDocument && el.ownerDocument.defaultView) || window;
                    function scrollableOf(node) {
                      if (!node) return null;
                      try {
                        const style = view.getComputedStyle(node);
                        const oy = style.overflowY;
                        if ((oy === 'auto' || oy === 'scroll' || oy === 'overlay')
                            && node.scrollHeight > node.clientHeight + 4) return node;
                      } catch (e) {}
                      return null;
                    }
                    let target = scrollableOf(el);
                    let node = el;
                    let guard = 0;
                    while (!target && node && node.parentElement && guard++ < 12) {
                      node = node.parentElement;
                      target = scrollableOf(node);
                    }
                    if (!target) {
                      const beforeY = window.scrollY || window.pageYOffset || 0;
                      const maxY = Math.max(0,
                        ((document.documentElement || {}).scrollHeight || 0) - (window.innerHeight || 0));
                      let next = beforeY;
                      if (dir === 'top') next = 0;
                      else if (dir === 'bottom') next = maxY;
                      else if (dir === 'up') next = Math.max(0, beforeY - amount);
                      else next = Math.min(maxY, beforeY + amount);
                      window.scrollTo(0, next);
                      const afterY = window.scrollY || window.pageYOffset || 0;
                      return JSON.stringify({
                        ok: true,
                        target: '整个页面（该元素自身不可滚动）',
                        unchanged: Math.abs(afterY - beforeY) < 1,
                        describe: Math.round(afterY) + ' / ' + Math.round(maxY)
                      });
                    }
                    const beforeTop = target.scrollTop || 0;
                    const maxTop = Math.max(0, (target.scrollHeight || 0) - (target.clientHeight || 0));
                    let next = beforeTop;
                    if (dir === 'top') next = 0;
                    else if (dir === 'bottom') next = maxTop;
                    else if (dir === 'up') next = Math.max(0, beforeTop - amount);
                    else next = Math.min(maxTop, beforeTop + amount);
                    target.scrollTop = next;
                    const afterTop = target.scrollTop || 0;
                    const cls = (typeof target.className === 'string' && target.className.trim())
                      ? '.' + target.className.trim().split(/\\s+/)[0] : '';
                    const label = target.tagName.toLowerCase()
                      + (target.id ? '#' + target.id : '') + cls;
                    return JSON.stringify({
                      ok: true,
                      target: label,
                      unchanged: Math.abs(afterTop - beforeTop) < 1,
                      describe: Math.round(afterTop) + ' / ' + Math.round(maxTop)
                        + '（可见高度 ' + (target.clientHeight || 0) + '）'
                    });
                  } catch (e) {
                    return JSON.stringify({ ok: false, error: String(e) });
                  }
                })()
                """.formatted(jsString(ref), jsString(direction), amount);
    }

    private String waitSelectorExpression(String selector, boolean wantHidden) {
        return """
                (() => {
                  try {
                    const sel = %s;
                    let node = null;
                    try { node = document.querySelector(sel); }
                    catch (e) { return JSON.stringify({ ok: false, error: '选择器语法不合法：' + String(e) }); }
                    let visible = false;
                    if (node) {
                      try {
                        const rects = node.getClientRects();
                        const style = window.getComputedStyle(node);
                        visible = !!(rects && rects.length > 0)
                          && style.visibility !== 'hidden' && style.display !== 'none';
                      } catch (e) { visible = false; }
                    }
                    const wantHidden = %s;
                    return JSON.stringify({
                      ok: true,
                      satisfied: wantHidden ? !visible : visible,
                      describe: node
                        ? (visible ? '元素已存在且可见' : '元素已存在但不可见')
                        : '元素尚不存在'
                    });
                  } catch (e) {
                    return JSON.stringify({ ok: false, error: String(e) });
                  }
                })()
                """.formatted(jsString(selector), wantHidden);
    }

    private String waitTextExpression(String text) {
        return """
                (() => {
                  try {
                    const needle = %s.toLowerCase();
                    const body = (document.body ? document.body.innerText : '') || '';
                    return JSON.stringify({
                      ok: true,
                      satisfied: body.toLowerCase().indexOf(needle) >= 0,
                      describe: '正文 ' + body.length + ' 字符'
                    });
                  } catch (e) {
                    return JSON.stringify({ ok: false, error: String(e) });
                  }
                })()
                """.formatted(jsString(text));
    }

    private String waitUrlExpression(String urlPart) {
        return """
                (() => {
                  try {
                    const needle = %s.toLowerCase();
                    const href = String(location.href || '');
                    return JSON.stringify({
                      ok: true,
                      satisfied: href.toLowerCase().indexOf(needle) >= 0,
                      describe: href.length > 200 ? href.slice(0, 200) + '…' : href
                    });
                  } catch (e) {
                    return JSON.stringify({ ok: false, error: String(e) });
                  }
                })()
                """.formatted(jsString(urlPart));
    }

    /**
     * 元素中心点坐标（顶层视口坐标系）。
     *
     * <p>iframe 内元素有两处特殊处理：</p>
     * <ol>
     *   <li>先把 iframe 宿主滚进视野 —— 直接对内部元素 scrollIntoView 只会滚 iframe 自己</li>
     *   <li>偏移量在<b>执行时</b>逐层累加 frameElement 的位置，而不是用 snapshot 时算好的旧值
     *       （滚动宿主后位置就变了，用旧值会点偏）</li>
     * </ol>
     */
    /** 把任意数值夹到 0~1（rx/ry 越界时按边界处理，不让模型传 1.5 点出界） */
    private static double clamp01(double value) {
        if (Double.isNaN(value)) return 0.5;
        return Math.max(0, Math.min(1, value));
    }

    private String coordinateExpression(String ref, double rx, double ry) {
        return """
                (() => {
                  try {
                    const el = (window.__ccRefs || {})[%s];
                    if (!el) return JSON.stringify({ ok: false, error: '编号不存在或已失效（每次 snapshot 都会重建编号）' });
                    if (!el.isConnected) return JSON.stringify({ ok: false, error: '元素已从页面移除' });
                    if (el.__ccHost) {
                      try { el.__ccHost.scrollIntoView({ block: 'center', inline: 'nearest' }); } catch (e) {}
                    }
                    try { el.scrollIntoView({ block: 'center', inline: 'nearest' }); } catch (e) {}

                    // 动态累加 iframe 偏移（跨域 frameElement 会抛，届时保持已知值）
                    let ox = 0;
                    let oy = 0;
                    try {
                      let w = el.ownerDocument && el.ownerDocument.defaultView;
                      let guard = 0;
                      while (w && w !== window && guard++ < 4) {
                        const fe = w.frameElement;
                        if (!fe) break;
                        const fr = fe.getBoundingClientRect();
                        ox += fr.left + (fe.clientLeft || 0);
                        oy += fr.top + (fe.clientTop || 0);
                        w = fe.ownerDocument && fe.ownerDocument.defaultView;
                      }
                    } catch (e) { ox = 0; oy = 0; }

                    const r = el.getBoundingClientRect();
                    if (r.width <= 0 || r.height <= 0) {
                      return JSON.stringify({ ok: false, error: '元素尺寸为 0（不可见或未渲染）' });
                    }
                    // RX / RY 是 0~1 的相对位置（默认 0.5 = 中心）。跨域 iframe 内部元素读不到，
                    // 只能靠这个比例把点击送进 iframe 里对应的位置。
                    const RX = Math.min(1, Math.max(0, %s));
                    const RY = Math.min(1, Math.max(0, %s));
                    const x = r.left + r.width * RX + ox;
                    const y = r.top + r.height * RY + oy;
                    if (x < 0 || y < 0 || x > window.innerWidth || y > window.innerHeight) {
                      return JSON.stringify({ ok: false,
                        error: '目标点在视口外（滚动未能把它带进视野），可先 scroll 再重试' });
                    }
                    const text = (el.innerText || el.value || el.getAttribute('aria-label') || '')
                      .trim().slice(0, 60);
                    return JSON.stringify({ ok: true, x: Math.round(x), y: Math.round(y),
                      tag: el.tagName, text: text, frame: el.__ccFrame || '',
                      crossOrigin: !!el.__ccCrossOrigin });
                  } catch (e) {
                    return JSON.stringify({ ok: false, error: String(e) });
                  }
                })()
                """.formatted(jsString(ref), rx, ry);
    }

    private String jsClickExpression(String ref) {
        return """
                (() => {
                  try {
                    const el = (window.__ccRefs || {})[%s];
                    if (!el) return JSON.stringify({ ok: false, error: '编号已失效' });
                    el.click();
                    return JSON.stringify({ ok: true });
                  } catch (e) {
                    return JSON.stringify({ ok: false, error: String(e) });
                  }
                })()
                """.formatted(jsString(ref));
    }

    private String focusAndSelectExpression(String ref) {
        return """
                (() => {
                  try {
                    const el = (window.__ccRefs || {})[%s];
                    if (!el) return JSON.stringify({ ok: false, error: '编号不存在或已失效' });
                    if (!el.isConnected) return JSON.stringify({ ok: false, error: '元素已从页面移除' });
                    const tag = el.tagName.toUpperCase();
                    const editable = (tag === 'INPUT' && !el.disabled && !el.readOnly
                        && ['text','search','email','url','tel','number','password','date','datetime-local','month','week','time'].includes((el.type || 'text').toLowerCase()))
                      || tag === 'TEXTAREA'
                      || tag === 'SELECT'
                      || el.isContentEditable === true;
                    if (!editable) return JSON.stringify({ ok: false, error: '该元素不是可输入控件', tag: tag, editable: false });
                    try { el.scrollIntoView({ block: 'center', inline: 'nearest' }); } catch (e) {}
                    el.focus();
                    if (typeof el.select === 'function') { try { el.select(); } catch (e) {} }
                    return JSON.stringify({ ok: true, tag: tag, editable: true });
                  } catch (e) {
                    return JSON.stringify({ ok: false, error: String(e) });
                  }
                })()
                """.formatted(jsString(ref));
    }

    private String readValueExpression(String ref) {
        return """
                (() => {
                  try {
                    const el = (window.__ccRefs || {})[%s];
                    if (!el) return JSON.stringify({ ok: false, error: '编号已失效' });
                    const type = (el.type || '').toLowerCase();
                    const value = type === 'password' ? null : (el.value == null ? null : String(el.value));
                    return JSON.stringify({ ok: true, value: value, hidden: type === 'password' });
                  } catch (e) {
                    return JSON.stringify({ ok: false, error: String(e) });
                  }
                })()
                """.formatted(jsString(ref));
    }

    /**
     * 用原生 setter 赋值 + 派发 input/change。
     *
     * <p>为什么不能直接 {@code el.value = x}：React 劫持了 value 的 setter 用来追踪变更，
     * 直接赋值会让 React 内部 state 与 DOM 不一致，下次渲染又把值刷回去。
     * 从原型链上取原生 setter 绕过这层劫持，是目前最通用的做法。</p>
     */
    private String nativeSetExpression(String ref, String value, String tagName) {
        String proto = switch (tagName == null ? "" : tagName.toUpperCase()) {
            case "TEXTAREA" -> "window.HTMLTextAreaElement.prototype";
            case "SELECT" -> "window.HTMLSelectElement.prototype";
            default -> "window.HTMLInputElement.prototype";
        };
        return """
                (() => {
                  try {
                    const el = (window.__ccRefs || {})[%s];
                    if (!el) return JSON.stringify({ ok: false, error: '编号已失效' });
                    const next = %s;
                    const proto = %s;
                    const desc = Object.getOwnPropertyDescriptor(proto, 'value');
                    if (desc && desc.set) { desc.set.call(el, next); } else { el.value = next; }
                    el.dispatchEvent(new Event('input', { bubbles: true }));
                    el.dispatchEvent(new Event('change', { bubbles: true }));
                    const shown = (el.type || '').toLowerCase() === 'password' ? null : String(el.value);
                    return JSON.stringify({ ok: true, value: shown });
                  } catch (e) {
                    return JSON.stringify({ ok: false, error: String(e) });
                  }
                })()
                """.formatted(jsString(ref), jsString(value), proto);
    }

    /** 安全地把 Java 字符串嵌入 JS 源码（走 JSON 序列化，避免引号/换行注入） */
    private String jsString(String raw) {
        try {
            return objectMapper.writeValueAsString(raw == null ? "" : raw);
        } catch (Exception e) {
            return "\"\"";
        }
    }

    // ── URL / 域名策略 ────────────────────────────────────────────

    private String normalizeUrl(String raw) {
        String text = raw.trim();
        if (text.isEmpty()) throw new IllegalArgumentException("url 不能为空");

        URI uri;
        try {
            uri = URI.create(text);
        } catch (Exception e) {
            throw new IllegalArgumentException("url 不合法：" + text);
        }

        if (uri.getScheme() == null) {
            // 省略协议：example.com/path → https://example.com/path
            if (!text.matches("^[\\w.-]+\\.[a-zA-Z]{2,}(?:[/:?#].*)?$")) {
                throw new IllegalArgumentException("url 不合法：" + text + "（示例：https://example.com/page）");
            }
            text = "https://" + text;
            uri = URI.create(text);
        }

        String scheme = uri.getScheme().toLowerCase();
        if (!"http".equals(scheme) && !"https".equals(scheme)) {
            throw new IllegalArgumentException(
                    "只允许 http / https，当前为 " + scheme + "://"
                            + "\nfile:// 被拒绝是刻意的：它能绕过后端对本地文件的访问控制。");
        }
        if (uri.getHost() == null || uri.getHost().isBlank()) {
            throw new IllegalArgumentException("url 缺少主机名：" + text);
        }
        return uri.toString();
    }

    private void assertHostAllowed(String url) {
        String host = URI.create(url).getHost();
        if (host == null) return;
        String lower = host.toLowerCase();

        List<String> blocked = config.getBlockedHosts();
        for (String pattern : blocked) {
            if (hostMatches(lower, pattern)) {
                throw new IllegalArgumentException("域名 " + lower + " 命中黑名单配置（codecraft.browser.blocked-hosts），已拒绝访问。");
            }
        }

        List<String> allowed = config.getAllowedHosts();
        if (allowed == null || allowed.isEmpty()) return;   // 空 = 不限制
        for (String pattern : allowed) {
            if (hostMatches(lower, pattern)) return;
        }
        throw new IllegalArgumentException("域名 " + lower + " 不在白名单配置（codecraft.browser.allowed-hosts）中，已拒绝访问。"
                + "\n当前白名单：" + allowed);
    }

    private boolean hostMatches(String host, String pattern) {
        if (pattern == null) return false;
        String p = pattern.trim().toLowerCase();
        if (p.isEmpty()) return false;
        // 方法自防御：不依赖调用方先把 host 转小写，否则以后谁直接调这里都会中招
        String h = host == null ? "" : host.trim().toLowerCase();
        if (h.isEmpty()) return false;
        if (p.startsWith("*.")) {
            // *.example.com 只匹配子域，不匹配 example.com 本身
            return h.endsWith(p.substring(1));
        }
        // 精确域名同时放行其子域：example.com → api.example.com
        // 注意用 "." + p 而不是 endsWith(p)：否则 evil-example.com 会被 example.com 放行
        return h.equals(p) || h.endsWith("." + p);
    }

    // ── 输出辅助 ─────────────────────────────────────────────────

    private String fail(String message) {
        return "❌ " + (message == null ? "未知错误" : message);
    }

    private String preview(String text) {
        return preview(text, 80);
    }

    private String preview(String text, int limit) {
        if (text == null) return "(空)";
        String flat = text.replaceAll("\\s+", " ").trim();
        if (flat.isEmpty()) return "(空)";
        return flat.length() <= limit ? flat : flat.substring(0, limit) + "…";
    }
}
