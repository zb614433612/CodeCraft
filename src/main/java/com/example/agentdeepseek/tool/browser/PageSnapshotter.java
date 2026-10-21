package com.example.agentdeepseek.tool.browser;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 页面快照采集与格式化。
 *
 * <p>采集脚本（{@code resources/browser/snapshot.js}）在页面内执行，返回 JSON 字符串；
 * 本类负责把它解析成结构化对象，再渲染成模型友好的紧凑文本。</p>
 *
 * <p><b>为什么不用 HTML</b>：一个中等页面的 outerHTML 通常 3~10 万 token，
 * 而「可见可交互元素清单」通常只有几百 token。模型需要的是「能点什么」，
 * 不是「DOM 树长什么样」。</p>
 */
@Slf4j
@Component
public class PageSnapshotter {

    private static final String SCRIPT_RESOURCE = "browser/snapshot.js";

    private final ObjectMapper objectMapper;
    private final BrowserConfig config;

    /** 采集脚本只有几 KB，读一次缓存住即可 */
    private volatile String scriptCache;

    public PageSnapshotter(ObjectMapper objectMapper, BrowserConfig config) {
        this.objectMapper = objectMapper;
        this.config = config;
    }

    // ── 脚本装配 ──────────────────────────────────────────────────

    private String script() {
        String cached = scriptCache;
        if (cached != null) return cached;
        synchronized (this) {
            if (scriptCache == null) {
                try (InputStream in = Thread.currentThread().getContextClassLoader()
                        .getResourceAsStream(SCRIPT_RESOURCE)) {
                    if (in == null) {
                        throw new IllegalStateException("找不到采集脚本资源：" + SCRIPT_RESOURCE);
                    }
                    scriptCache = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                } catch (Exception e) {
                    throw new IllegalStateException("读取页面采集脚本失败：" + e.getMessage(), e);
                }
            }
            return scriptCache;
        }
    }

    /**
     * 组装成可直接交给 {@code Runtime.evaluate} 的表达式。
     *
     * <p>外层包 try/catch：页面环境的异常（被站点 CSP 拦、$ 被占用、浏览器过旧不支持某 API）
     * 必须变成一条 JSON 错误返回，而不是让 CDP 抛一个信息量极低的异常。</p>
     */
    public String buildExpression() {
        ObjectNode opts = objectMapper.createObjectNode();
        opts.put("maxElements", config.getSnapshotMaxElements());
        opts.put("labelLimit", config.getSnapshotLabelLimit());
        opts.put("textLimit", config.getSnapshotTextLimit());

        String optsJson;
        try {
            optsJson = objectMapper.writeValueAsString(opts);
        } catch (Exception e) {
            optsJson = "{}";
        }

        return "(() => { try { return (" + script() + ")(" + optsJson + "); } "
                + "catch (e) { return JSON.stringify({ ok: false, error: String((e && e.stack) || e) }); } })()";
    }

    // ── 数据结构 ──────────────────────────────────────────────────

    /**
     * 一个可交互元素。
     *
     * @param frame    非空表示该元素位于同源 iframe 内（值为 {@code iframe} 或多层 {@code iframe>iframe}）
     * @param options  select 的选项清单（{@code 文案→值}，最多 20 项；非 select 为 null）
     * @param multiple select 是否多选
     * @param crossOrigin 是否为「跨域 iframe 占位元素」：读不到内部 DOM，
     *                    但可以用 click 的 {@code rx}/{@code ry} 相对坐标点进去
     */
    public record Element(String ref, String role, String label, boolean inViewport,
                          boolean disabled, String type, Boolean checked,
                          int x, int y, int w, int h, String frame,
                          List<String> options, boolean multiple, boolean crossOrigin) {
    }

    /**
     * 一次页面快照。
     *
     * @param iframes           页面里 iframe 总数（含跨域）
     * @param sameOriginFrames  已穿透的同源 iframe 数
     * @param crossOriginFrames 无法穿透的跨域 iframe 数
     * @param shadowRoots       已穿透的 open shadow root 数
     */
    public record Snapshot(String url, String title, String text,
                           int totalInteractive, int visibleInteractive, int returned,
                           boolean truncated, List<String> dialogs, int iframes,
                           int sameOriginFrames, int crossOriginFrames, int shadowRoots,
                           String viewportText, List<Element> elements) {
    }

    // ── 解析 ─────────────────────────────────────────────────────

    /** 解析采集脚本的返回值；结构不符时抛出带原因异常（由调用方转成模型可读提示） */
    public Snapshot parse(JsonNode raw) {
        if (raw == null || raw.isNull()) {
            throw new IllegalArgumentException("页面采集脚本没有返回内容");
        }
        // Runtime.evaluate 的 returnByValue 会直接把字符串放在 value 里；
        // 若拿到的是对象的 description（未成功 byValue），这里再兜一次
        JsonNode root = raw;
        if (root.isTextual()) {
            try {
                root = objectMapper.readTree(root.asText());
            } catch (Exception e) {
                throw new IllegalArgumentException("页面采集结果不是合法 JSON：" + e.getMessage());
            }
        }

        if (root.has("ok") && !root.path("ok").asBoolean(true)) {
            throw new IllegalStateException("页面采集脚本执行失败：" + root.path("error").asText("未知错误"));
        }

        JsonNode viewport = root.path("viewport");
        String viewportText = "%d×%d，滚动 %d/%d"
                .formatted(viewport.path("width").asInt(0), viewport.path("height").asInt(0),
                        viewport.path("scrollY").asInt(0), viewport.path("docHeight").asInt(0));

        List<Element> elements = new ArrayList<>();
        for (JsonNode node : root.path("elements")) {
            Boolean checked = node.hasNonNull("checked") ? node.path("checked").asBoolean() : null;
            List<String> options = null;
            if (node.has("options") && node.path("options").isArray()) {
                options = new ArrayList<>();
                for (JsonNode opt : node.path("options")) {
                    String text = opt.asText("");
                    if (!text.isBlank()) options.add(text);
                }
            }
            elements.add(new Element(
                    node.path("ref").asText(""),
                    node.path("role").asText(""),
                    node.path("label").asText(""),
                    node.path("inViewport").asBoolean(true),
                    node.path("disabled").asBoolean(false),
                    node.path("type").asText(""),
                    checked,
                    node.path("x").asInt(0), node.path("y").asInt(0),
                    node.path("w").asInt(0), node.path("h").asInt(0),
                    node.path("frame").asText(""),
                    options,
                    node.path("multiple").asBoolean(false),
                    node.path("crossOrigin").asBoolean(false)
            ));
        }

        List<String> dialogs = new ArrayList<>();
        for (JsonNode node : root.path("dialogs")) {
            String text = node.asText("");
            if (!text.isBlank()) dialogs.add(text);
        }

        return new Snapshot(
                root.path("url").asText(""),
                root.path("title").asText(""),
                root.path("text").asText(""),
                root.path("totalInteractive").asInt(0),
                root.path("visibleInteractive").asInt(0),
                root.path("returned").asInt(elements.size()),
                root.path("truncated").asBoolean(false),
                dialogs,
                root.path("iframes").asInt(0),
                root.path("sameOriginFrames").asInt(0),
                root.path("crossOriginFrames").asInt(0),
                root.path("shadowRoots").asInt(0),
                viewportText,
                elements
        );
    }

    // ── 渲染 ─────────────────────────────────────────────────────

    /**
     * 渲染成模型友好的文本。
     *
     * <p>行格式刻意做成「ref  角色  「文案」」：模型照抄 ref 就能点，
     * 不需要自己推坐标、写选择器，也不需要理解 DOM 结构。</p>
     */
    public String format(Snapshot snapshot) {
        StringBuilder sb = new StringBuilder(2048);

        sb.append("[页面] ").append(snapshot.title().isBlank() ? "(无标题)" : snapshot.title()).append('\n');
        sb.append("[地址] ").append(snapshot.url()).append('\n');
        sb.append("[视口] ").append(snapshot.viewportText()).append('\n');

        if (!snapshot.dialogs().isEmpty()) {
            sb.append("[弹窗] 页面上有 ").append(snapshot.dialogs().size())
                    .append(" 个可见弹层，可能遮挡下方元素：\n");
            for (String dialog : snapshot.dialogs()) {
                sb.append("       · ").append(dialog).append('\n');
            }
        }

        if(!snapshot.text().isBlank()) {
            sb.append("\n[正文]\n").append(snapshot.text()).append('\n');
        } else {
            sb.append("\n[正文] （页面没有可见文本，可能是纯图片 / 画布，或内容在跨域 iframe 内）\n");
        }

        sb.append("\n[可交互元素] 命中 ").append(snapshot.totalInteractive())
                .append(" 个，可见 ").append(snapshot.visibleInteractive())
                .append(" 个，返回 ").append(snapshot.returned()).append(" 个");
        if (snapshot.truncated()) {
            sb.append("（已截断，如需更多请先滚动或缩小范围）");
        }
        sb.append('\n');

        if (snapshot.elements().isEmpty()) {
            sb.append("  （没有找到可交互元素）\n");
        }
        for (Element el : snapshot.elements()) {
            sb.append("  ").append(el.ref()).append("  ");
            String role = el.role() == null || el.role().isBlank() ? "element" : el.role();
            sb.append(padRight(role, 9));
            if (el.checked() != null) {
                sb.append(el.checked() ? "[已勾选] " : "[未勾选] ");
            }
            String label = el.label() == null ? "" : el.label();
            sb.append(label.isBlank() ? "(无文案)" : "「" + label + "」");
            if (el.disabled()) sb.append(" (禁用)");
            if (el.multiple()) sb.append(" (多选)");
            if (!el.inViewport()) sb.append(" (在视口外，点击时会自动滚过去)");
            if (el.frame() != null && !el.frame().isBlank()) sb.append(" [iframe内]");
            if (el.crossOrigin()) sb.append(" [跨域iframe·读不到内部元素，可用 rx/ry 相对坐标点击]");
            sb.append('\n');
            // select 的候选值直接列出来：模型不必猜「该填什么 value」，也不必先点开
            if (el.options() != null && !el.options().isEmpty()) {
                sb.append("      ↳ 可选：").append(String.join(" · ", el.options()));
                if (el.options().size() >= 20) sb.append(" · …");
                sb.append('\n');
            }
        }

        // ── 穿透情况说明：哪些内容「看得到」，哪些只能靠截图 ──────────
        if (snapshot.shadowRoots() > 0) {
            sb.append("\n[穿透] 已进入 ").append(snapshot.shadowRoots())
                    .append(" 个 Shadow DOM（Web Components 内部元素已包含在上面的清单里）\n");
        }
        if (snapshot.sameOriginFrames() > 0) {
            sb.append("[穿透] 已进入 ").append(snapshot.sameOriginFrames())
                    .append(" 个同源 iframe（标记 [iframe内] 的元素可直接 click / fill）\n");
        }
        if (snapshot.crossOriginFrames() > 0) {
            sb.append("[限制] 页面含 ").append(snapshot.crossOriginFrames())
                    .append(" 个跨域 iframe：内部 DOM 受同源策略限制读不到，但已作为占位元素输出（标 [跨域iframe]）。\n")
                    .append("      可以用 click ref=<该元素> rx=0.5 ry=0.5 按比例点进去（rx/ry 是 0~1 的相对位置，")
                    .append("先 screenshot 看清布局再估比例最稳）；要看内容也可直接 screenshot。\n");
        } else if (snapshot.sameOriginFrames() == 0 && snapshot.iframes() > 0) {
            // 理论上走不到这里（有 iframe 必属同源或跨域之一），保底提示
            sb.append("\n[提示] 页面含 ").append(snapshot.iframes())
                    .append(" 个 iframe，其内部元素不在上面的清单里\n");
        }

        return sb.toString();
    }

    private String padRight(String text, int width) {
        if (text.length() >= width) return text;
        return text + " ".repeat(width - text.length());
    }
}
