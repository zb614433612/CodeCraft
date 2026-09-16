package com.example.agentdeepseek.tool.impl;

import com.example.agentdeepseek.config.DesktopConfig;
import com.example.agentdeepseek.tool.Tool;
import com.example.agentdeepseek.tool.desktop.CaptureContext;
import com.example.agentdeepseek.tool.desktop.CaptureContextRegistry;
import com.example.agentdeepseek.tool.desktop.DesktopActionSummary;
import com.example.agentdeepseek.tool.desktop.DesktopPlatformAdapter;
import com.example.agentdeepseek.tool.desktop.DesktopVerifyRegistry;
import com.example.agentdeepseek.tool.desktop.DesktopVerifySupport;
import com.example.agentdeepseek.tool.desktop.VerifyPreviewRenderer;
import com.example.agentdeepseek.tool.permission.OperationCategory;
import com.example.agentdeepseek.tool.permission.SideEffectFreePreflight;
import com.example.agentdeepseek.tool.permission.ToolPermission;
import com.example.agentdeepseek.util.TaskContext;
import com.example.agentdeepseek.util.TaskContextRegistry;
import com.example.agentdeepseek.util.ToolContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.awt.GraphicsDevice;
import java.awt.GraphicsEnvironment;
import java.awt.Rectangle;
import java.awt.Robot;
import java.awt.Toolkit;
import java.awt.datatransfer.Clipboard;
import java.awt.datatransfer.StringSelection;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 桌面键鼠模拟工具（M6：Computer Use 组合能力之"手"）
 *
 * <p>能力链路：屏幕截图（M5）建立坐标基准 → 本工具按序执行批量动作（1~20 个/次）→
 * 归一化坐标换算回物理屏幕坐标 → Robot/剪贴板执行 → 建议截图验证。</p>
 *
 * <p>坐标约定（v2 归一化协议）：所有坐标使用 <b>0~1000 归一化值</b>（读最近一次 screen_capture
 * 截图的网格刻度；换算见 {@link CaptureContext}）；无截图基准时坐标类动作拒绝执行并引导先截图。</p>
 *
 * <p>安全机制：DESKTOP 高危权限（授权弹窗展示动作摘要）+ 单次动作数上限 + 动作间延迟 +
 * 每动作前取消检查（配合工具循环检查点）+ 红线约束（禁止密码/支付类输入，写入 description）。</p>
 *
 * <p>预检闸门（M6.2"先验证后执行"）：mouse_click / mouse_drag 的坐标若未经准星校验
 * （见 {@link DesktopVerifyRegistry}），本工具不执行动作，而是先返回一张带准星的校验图
 * （{@link VerifyPreviewRenderer} 生成），模型确认后再次调用即放行——预检轮无副作用，
 * 经 {@link SideEffectFreePreflight} 在授权层免弹窗。</p>
 */
@Slf4j
@Component
@ToolPermission(
        category = OperationCategory.DESKTOP,
        affectsData = true,
        highRisk = true,
        description = "桌面键鼠模拟（坐标基于最近截图，支持批量动作；点击前自动预检）"
)
public class DesktopControlTool implements Tool, SideEffectFreePreflight {

    /** 已知动作类型 */
    private static final Set<String> KNOWN_ACTIONS = Set.of(
            "mouse_move", "mouse_click", "mouse_drag", "mouse_scroll", "key", "type", "screen_size");

    /** type 动作文本长度上限（防超大文本刷剪贴板） */
    private static final int TYPE_TEXT_MAX_LENGTH = 10000;

    /** 双击/多击的点击间隔（毫秒） */
    private static final int CLICK_INTERVAL_MS = 80;

    /** 拖拽分步数（中间移动事件，提升画布类应用兼容性） */
    private static final int DRAG_STEPS = 10;

    /** 拖拽分步间隔（毫秒） */
    private static final int DRAG_STEP_INTERVAL_MS = 10;

    private final DesktopConfig config;
    private final CaptureContextRegistry captureContextRegistry;
    private final DesktopPlatformAdapter platformAdapter;
    private final TaskContextRegistry taskContextRegistry;
    private final ObjectMapper objectMapper;
    private final DesktopVerifyRegistry verifyRegistry;
    private final VerifyPreviewRenderer verifyPreviewRenderer;

    public DesktopControlTool(DesktopConfig config,
                              CaptureContextRegistry captureContextRegistry,
                              DesktopPlatformAdapter platformAdapter,
                              TaskContextRegistry taskContextRegistry,
                              ObjectMapper objectMapper,
                              DesktopVerifyRegistry verifyRegistry,
                              VerifyPreviewRenderer verifyPreviewRenderer) {
        this.config = config;
        this.captureContextRegistry = captureContextRegistry;
        this.platformAdapter = platformAdapter;
        this.taskContextRegistry = taskContextRegistry;
        this.objectMapper = objectMapper;
        this.verifyRegistry = verifyRegistry;
        this.verifyPreviewRenderer = verifyPreviewRenderer;
    }

    @Override
    public String getName() {
        return "desktop_control";
    }

    @Override
    public String getDescription() {
        return "桌面键鼠控制工具（Computer Use 之'手'）：对屏幕执行鼠标/键盘操作，配合 screen_capture 完成 GUI 自动化任务。"
                + "\n【使用流程】① 先调用 screen_capture 截图（图上叠加 10×10 网格与 0~1000 刻度）；"
                + "② 读取目标位置对应的网格刻度，以 0~1000 归一化坐标调用本工具执行动作（可批量）；"
                + "③ 点击/拖拽前建议先用 screen_capture(mark={x,y}) 校验坐标（准星正中目标再执行）；"
                + "④ 操作后建议再截图验证结果，失败则修正后重试。"
                + "\n【坐标协议】所有坐标使用 0~1000 归一化值（相对最近一次截图）：x 横向（0=最左、1000=最右）、"
                + "y 纵向（0=最上、1000=最下）；系统自动换算为屏幕物理坐标，与图片缩放/分辨率无关，无需你折算像素。"
                + "目标较小时可先用 screen_capture 的 region 局部放大截图，再读格以提高定位精度。"
                + "\n【预检机制】mouse_click / mouse_drag 的坐标执行前需通过准星校验：未校验时系统会先返回一张带准星的校验图（本批动作不执行），"
                + "你确认准星落在目标上后再次调用即可执行；修正坐标后会自动重新预检。已用 screen_capture(mark) 主动校验过的坐标（3 分钟内）直接执行。"
                + "\n【批量多动作】使用 actions 数组一次执行 1~" + config.getControl().getMaxActionsPerCall()
                + " 个动作（按序执行，减少往返）；也支持单动作形态（顶层直接传 action 与动作参数）。"
                + "\n【动作集】mouse_move(x,y) / mouse_click(x,y,button,clicks) / mouse_drag(fromX,fromY,toX,toY) / "
                + "mouse_scroll(x,y,amount 正上负下) / key(key,modifiers[]) / type(text 键入文本) / screen_size(查屏幕尺寸)。"
                + "\n【重要约束】① 能用 API 级工具（command/file_writer/git 等）完成的事，不要用 GUI 自动化；"
                + "② 严禁用于输入密码、支付确认、验证码等敏感操作；③ 需要坐标基准（先截图），无基准时坐标类动作会被拒绝。"
                + "\n【系统边界】UAC 提权窗口、锁屏界面不可操控；部分全屏 DirectX 场景受限；Wayland 下可能不可用。";
    }

    @Override
    public JsonNode getParameters() {
        ObjectNode parameters = objectMapper.createObjectNode();
        parameters.put("type", "object");
        parameters.put("description", "桌面键鼠动作（批量 actions 数组，或单动作顶层 action）");

        ObjectNode properties = objectMapper.createObjectNode();

        // 动作对象字段说明（actions 数组元素与单动作共用）
        ObjectNode actions = objectMapper.createObjectNode();
        actions.put("type", "array");
        actions.put("description", "【推荐】按序执行的动作列表（1~" + config.getControl().getMaxActionsPerCall()
                + " 个）。每个元素形如 {\"action\":\"mouse_click\",\"x\":500,\"y\":500}。坐标为 0~1000 归一化值，"
                + "基于最近一次 screen_capture 截图（读图上的网格刻度）。");
        ObjectNode items = objectMapper.createObjectNode();
        items.put("type", "object");
        ObjectNode itemProps = objectMapper.createObjectNode();
        itemProps.set("action", enumNode("动作类型",
                "mouse_move / mouse_click / mouse_drag / mouse_scroll / key / type / screen_size"));
        itemProps.set("x", intNode("X 归一化坐标（0~1000；读最近截图的网格刻度：0=最左、1000=最右）"));
        itemProps.set("y", intNode("Y 归一化坐标（0~1000；读最近截图的网格刻度：0=最上、1000=最下）"));
        itemProps.set("button", enumNode("鼠标按键（mouse_click/mouse_drag 用）", "left / right / middle"));
        itemProps.set("clicks", intNode("点击次数（1=单击，2=双击；mouse_click 用，默认 1）"));
        itemProps.set("fromX", intNode("拖拽起点 X 归一化坐标（0~1000，mouse_drag 用）"));
        itemProps.set("fromY", intNode("拖拽起点 Y 归一化坐标（0~1000，mouse_drag 用）"));
        itemProps.set("toX", intNode("拖拽终点 X 归一化坐标（0~1000，mouse_drag 用）"));
        itemProps.set("toY", intNode("拖拽终点 Y 归一化坐标（0~1000，mouse_drag 用）"));
        itemProps.set("amount", intNode("滚轮格数（mouse_scroll 用；正数向上、负数向下，如 3 / -3）"));
        itemProps.set("key", strNode("按键名（key 用）：字母/数字/enter/esc/tab/space/backspace/delete/"
                + "home/end/pageup/pagedown/up/down/left/right/f1~f12 等"));
        ObjectNode modifiers = objectMapper.createObjectNode();
        modifiers.put("type", "array");
        modifiers.put("description", "修饰键（key 用，可选）：如 [\"ctrl\"]、[\"ctrl\",\"shift\"]；可选值 ctrl/alt/shift/meta(win/cmd)");
        ObjectNode modifierItems = objectMapper.createObjectNode();
        modifierItems.put("type", "string");
        modifiers.set("items", modifierItems);
        itemProps.set("modifiers", modifiers);
        itemProps.set("text", strNode("要键入的文本（type 用；支持中文/任意字符，经剪贴板粘贴）"));
        items.set("properties", itemProps);
        items.set("required", objectMapper.createArrayNode().add("action"));
        actions.set("items", items);
        properties.set("actions", actions);

        // 单动作兼容：顶层字段
        properties.set("action", enumNode("单动作兼容模式：直接传一个动作类型 + 对应参数（等价于 actions 数组只含一个元素）",
                "mouse_move / mouse_click / mouse_drag / mouse_scroll / key / type / screen_size"));
        properties.set("x", intNode("单动作模式：X 归一化坐标（0~1000）"));
        properties.set("y", intNode("单动作模式：Y 归一化坐标（0~1000）"));
        properties.set("button", enumNode("单动作模式：鼠标按键", "left / right / middle"));
        properties.set("clicks", intNode("单动作模式：点击次数"));
        properties.set("fromX", intNode("单动作模式：拖拽起点 X 归一化坐标（0~1000）"));
        properties.set("fromY", intNode("单动作模式：拖拽起点 Y 归一化坐标（0~1000）"));
        properties.set("toX", intNode("单动作模式：拖拽终点 X 归一化坐标（0~1000）"));
        properties.set("toY", intNode("单动作模式：拖拽终点 Y 归一化坐标（0~1000）"));
        properties.set("amount", intNode("单动作模式：滚轮格数（正上负下）"));
        properties.set("key", strNode("单动作模式：按键名"));
        ObjectNode topModifiers = objectMapper.createObjectNode();
        topModifiers.put("type", "array");
        topModifiers.put("description", "单动作模式：修饰键（key 用），如 [\"ctrl\"]");
        ObjectNode topModifierItems = objectMapper.createObjectNode();
        topModifierItems.put("type", "string");
        topModifiers.set("items", topModifierItems);
        properties.set("modifiers", topModifiers);
        properties.set("text", strNode("单动作模式：要键入的文本"));

        parameters.set("properties", properties);
        return parameters;
    }

    // ==================== 主流程 ====================

    @Override
    public String execute(JsonNode arguments) {
        // 1. 总开关
        if (!config.isEnabled()) {
            return "【错误类型】【功能未启用】桌面自动化功能已关闭（配置项 desktop.enabled=false）。"
                    + "请在应用配置中启用后重试。";
        }

        // 2. 环境检查
        if (!platformAdapter.isEnvironmentReady()) {
            return "【错误类型】【环境不支持】当前运行环境为无图形界面（Headless）模式，无法进行桌面键鼠控制。"
                    + "桌面自动化功能仅支持有图形界面的桌面环境。";
        }

        // 3. 解析动作列表
        List<JsonNode> actions = DesktopActionSummary.extractActions(arguments);
        if (actions.isEmpty()) {
            return "【错误类型】【参数错误】缺少动作定义：请使用 actions 数组（批量形态）或顶层 action 字段"
                    + "（单动作形态）。示例：{\"actions\": [{\"action\": \"mouse_click\", \"x\": 640, \"y\": 360}]}";
        }

        // 4. 数量上限
        int maxActions = Math.max(1, config.getControl().getMaxActionsPerCall());
        if (actions.size() > maxActions) {
            return "【错误类型】【动作数超限】单次调用最多 " + maxActions + " 个动作（收到 " + actions.size()
                    + " 个）。请拆分为多次调用，或先执行关键动作后再继续。";
        }

        // 5. 动作合法性 + 坐标基准检查
        Long conversationId = ToolContext.getConversationId();
        CaptureContext captureContext = conversationId == null ? null : captureContextRegistry.get(conversationId);
        boolean needsBase = false;
        for (int i = 0; i < actions.size(); i++) {
            String type = DesktopActionSummary.actionType(actions.get(i));
            if (!KNOWN_ACTIONS.contains(type)) {
                return "【错误类型】【参数错误】第 " + (i + 1) + " 个动作类型非法：'"
                        + (type.isBlank() ? "缺少 action" : type) + "'。可选值："
                        + String.join(" / ", KNOWN_ACTIONS);
            }
            if (DesktopActionSummary.needsCoordinateBase(type)) {
                needsBase = true;
            }
        }
        if (needsBase && captureContext == null) {
            return "【错误类型】【缺少坐标基准】使用坐标类动作（鼠标移动/点击/拖拽/滚轮）前，请先无参调用一次 "
                    + "screen_capture 截取整屏建立坐标基准（本工具坐标使用 0~1000 归一化值，读最近截图的网格刻度，"
                    + "系统自动换算为屏幕物理坐标）。";
        }

        // 5.5 预检闸门（先验证后执行）：click/drag 坐标未经准星校验时，先生成校验图（不执行任何动作）
        if (verifyRegistry != null && verifyPreviewRenderer != null) {
            List<int[]> pending = DesktopVerifySupport.pendingVerifyPoints(
                    config.getControl().isVerifyBeforeClick(), captureContext != null,
                    actions, conversationId, verifyRegistry);
            if (!pending.isEmpty()) {
                String renderError = verifyPreviewRenderer.renderVerifyPreview(
                        conversationId, ToolContext.getUserId(), pending);
                if (renderError != null) {
                    return "【错误类型】【预检失败】无法生成坐标校验图：" + renderError
                            + "。可先手动调用 screen_capture(mark={x,y}) 校验坐标后重试。";
                }
                // 校验图已注入（模型下一轮可见）→ 登记记录：模型确认后重发同坐标即放行执行
                for (int[] p : pending) {
                    verifyRegistry.record(conversationId, p[0], p[1]);
                }
                log.info("桌面预检拦截: conversationId={}, pendingPoints={}", conversationId, pending.size());
                return DesktopVerifySupport.buildPrecheckText(pending);
            }
        }

        // 6. 创建 Robot（一次性，全批次复用）
        Robot robot;
        try {
            robot = new Robot();
        } catch (Exception e) {
            log.warn("Robot 初始化失败: {}", e.getMessage());
            return "【错误类型】【环境不支持】键鼠控制初始化失败：" + e.getMessage()
                    + "。（macOS 需要'辅助功能'权限；Wayland 环境可能受限）请检查系统权限后重试。";
        }

        // 7. 逐动作执行（每动作前取消检查 + 动作间延迟）
        boolean hadContexts = hasActiveContexts(conversationId);
        int delayMs = Math.max(0, config.getControl().getActionDelayMs());
        List<String> doneSummaries = new ArrayList<>();
        for (int i = 0; i < actions.size(); i++) {
            JsonNode act = actions.get(i);

            // 取消检查（任务取消后立即停止后续动作）
            if (isCancelled(conversationId, hadContexts)) {
                log.info("桌面控制检测到任务取消，停止后续动作: conversationId={}, done={}/{}",
                        conversationId, i, actions.size());
                return buildCancelledText(doneSummaries, i, actions.size());
            }

            String type = DesktopActionSummary.actionType(act);
            String summary = DesktopActionSummary.summarize(act);
            try {
                executeAction(robot, act, type, captureContext);
                doneSummaries.add(summary);
                log.info("桌面动作已执行 [{}/{}]: {}", i + 1, actions.size(), summary);
            } catch (ActionFailedException e) {
                log.warn("桌面动作失败 [{}/{}] {}: {}", i + 1, actions.size(), summary, e.getMessage());
                return buildPartialFailureText(doneSummaries, i, actions.size(), summary, e.getMessage());
            }

            // 动作间延迟（最后一个动作后不延迟）
            if (i < actions.size() - 1 && delayMs > 0) {
                try {
                    Thread.sleep(delayMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return buildCancelledText(doneSummaries, i + 1, actions.size());
                }
            }
        }

        // 8. 成功返回
        return buildSuccessText(doneSummaries, actions.size());
    }

    // ==================== 授权豁免（预检轮无副作用） ====================

    /**
     * 授权层钩子：判定本次调用是否会被预检闸门拦截（=无副作用，仅出校验图）。
     *
     * <p><b>与 execute 内闸门严格同源</b>（同一 {@link DesktopVerifySupport#pendingVerifyPoints}
     * 判定 + 同类前置条件），保证"免授权执行的调用"确实不会产生键鼠副作用；判定异常/不确定时
     * 返回 false（保守走正常授权）。同会话工具调用串行执行，判定与执行之间无并发写入。</p>
     */
    @Override
    public boolean isSideEffectFreePreflight(JsonNode arguments) {
        try {
            if (!config.isEnabled() || verifyRegistry == null || verifyPreviewRenderer == null) {
                return false;
            }
            if (!config.getControl().isVerifyBeforeClick()) {
                return false;
            }
            Long conversationId = ToolContext.getConversationId();
            if (conversationId == null) {
                return false;
            }
            CaptureContext ctx = captureContextRegistry.get(conversationId);
            if (ctx == null) {
                return false;
            }
            List<JsonNode> actions = DesktopActionSummary.extractActions(arguments);
            if (actions.isEmpty()) {
                return false;
            }
            return !DesktopVerifySupport.pendingVerifyPoints(true, true, actions, conversationId, verifyRegistry)
                    .isEmpty();
        } catch (Exception e) {
            log.debug("预检免授权判定异常（走正常授权）: {}", e.getMessage());
            return false;
        }
    }

    // ==================== 动作执行 ====================

    /**
     * 执行单个动作（坐标类动作经 CaptureContext 归一化换算回物理屏幕坐标）
     */
    private void executeAction(Robot robot, JsonNode act, String type, CaptureContext ctx)
            throws ActionFailedException {
        switch (type) {
            case "mouse_move" -> {
                int x = toScreenX(ctx, act, "x");
                int y = toScreenY(ctx, act, "y");
                robot.mouseMove(x, y);
            }
            case "mouse_click" -> {
                int x = toScreenX(ctx, act, "x");
                int y = toScreenY(ctx, act, "y");
                int buttonMask = resolveButtonMask(act.path("button").asText("left"));
                int clicks = Math.max(1, Math.min(3, act.path("clicks").asInt(1)));
                robot.mouseMove(x, y);
                for (int i = 0; i < clicks; i++) {
                    robot.mousePress(buttonMask);
                    robot.mouseRelease(buttonMask);
                    if (i < clicks - 1) {
                        sleepQuietly(CLICK_INTERVAL_MS);
                    }
                }
            }
            case "mouse_drag" -> {
                int fromX = toScreenX(ctx, act, "fromX");
                int fromY = toScreenY(ctx, act, "fromY");
                int toX = toScreenX(ctx, act, "toX");
                int toY = toScreenY(ctx, act, "toY");
                int buttonMask = resolveButtonMask(act.path("button").asText("left"));
                robot.mouseMove(fromX, fromY);
                sleepQuietly(CLICK_INTERVAL_MS);
                robot.mousePress(buttonMask);
                try {
                    // 分步移动（中间移动事件，提升画布/列表拖拽兼容性）
                    for (int i = 1; i <= DRAG_STEPS; i++) {
                        int mx = fromX + (toX - fromX) * i / DRAG_STEPS;
                        int my = fromY + (toY - fromY) * i / DRAG_STEPS;
                        robot.mouseMove(mx, my);
                        sleepQuietly(DRAG_STEP_INTERVAL_MS);
                    }
                } finally {
                    robot.mouseRelease(buttonMask);
                }
            }
            case "mouse_scroll" -> {
                int x = toScreenX(ctx, act, "x");
                int y = toScreenY(ctx, act, "y");
                int amount = act.path("amount").asInt(0);
                if (amount == 0) {
                    throw new ActionFailedException("mouse_scroll 的 amount 不能为 0");
                }
                robot.mouseMove(x, y);
                // 约定：正数向上、负数向下；Robot.mouseWheel 正数向下 → 取反
                robot.mouseWheel(-amount);
            }
            case "key" -> {
                String keyName = act.path("key").asText("");
                if (keyName.isBlank()) {
                    throw new ActionFailedException("key 动作缺少 key 参数（如 \"c\"、\"enter\"、\"f5\"）");
                }
                int keyCode = resolveKeyCode(keyName);
                int[] modifierCodes = resolveModifiers(act.path("modifiers"));
                for (int mod : modifierCodes) {
                    robot.keyPress(mod);
                }
                try {
                    robot.keyPress(keyCode);
                    robot.keyRelease(keyCode);
                } finally {
                    for (int i = modifierCodes.length - 1; i >= 0; i--) {
                        robot.keyRelease(modifierCodes[i]);
                    }
                }
            }
            case "type" -> {
                String text = act.path("text").asText("");
                if (text.isEmpty()) {
                    throw new ActionFailedException("type 动作缺少 text 参数（要键入的文本）");
                }
                if (text.length() > TYPE_TEXT_MAX_LENGTH) {
                    throw new ActionFailedException("type 文本过长（" + text.length() + " 字符，上限 "
                            + TYPE_TEXT_MAX_LENGTH + "）");
                }
                pasteViaClipboard(robot, text);
            }
            case "screen_size" -> {
                // 无副作用：仅作为信息型动作（实际尺寸在结果文本中统一返回）
            }
            default -> throw new ActionFailedException("未知动作类型: " + type);
        }
    }

    /**
     * 文本输入（统一走剪贴板 + 粘贴快捷键）：
     * Robot 键盘对 IME/中文/特殊符号不可靠，剪贴板方案覆盖全字符集且与业界 Computer Use 工具一致。
     * macOS 粘贴快捷键为 Cmd+V（VK_META），其余平台为 Ctrl+V。
     */
    private void pasteViaClipboard(Robot robot, String text) throws ActionFailedException {
        try {
            Clipboard clipboard = Toolkit.getDefaultToolkit().getSystemClipboard();
            clipboard.setContents(new StringSelection(text), null);
        } catch (Exception e) {
            throw new ActionFailedException("写入剪贴板失败: " + e.getMessage());
        }
        int pasteModifier = "macos".equals(platformAdapter.getPlatform())
                ? KeyEvent.VK_META : KeyEvent.VK_CONTROL;
        robot.keyPress(pasteModifier);
        try {
            robot.keyPress(KeyEvent.VK_V);
            robot.keyRelease(KeyEvent.VK_V);
        } finally {
            robot.keyRelease(pasteModifier);
        }
    }

    // ==================== 坐标反变换（v2 归一化协议） ====================

    private int toScreenX(CaptureContext ctx, JsonNode act, String field) throws ActionFailedException {
        if (ctx == null) {
            throw new ActionFailedException("缺少坐标基准，请先调用 screen_capture 截图（坐标使用 0~1000 归一化值）");
        }
        return ctx.normToScreenX(requireNormCoordinate(act, field));
    }

    private int toScreenY(CaptureContext ctx, JsonNode act, String field) throws ActionFailedException {
        if (ctx == null) {
            throw new ActionFailedException("缺少坐标基准，请先调用 screen_capture 截图（坐标使用 0~1000 归一化值）");
        }
        return ctx.normToScreenY(requireNormCoordinate(act, field));
    }

    /**
     * 读取并校验归一化坐标参数（0~1000；v2 协议）
     *
     * <p>缺失 / 非数字 / 越界均抛出带引导信息的异常——校验发生在任何真实键鼠操作之前。</p>
     */
    private int requireNormCoordinate(JsonNode act, String field) throws ActionFailedException {
        JsonNode node = act.path(field);
        if (node.isMissingNode() || node.isNull()) {
            throw new ActionFailedException("缺少坐标参数 " + field + "（应为 0~1000 的归一化坐标）");
        }
        if (!node.isNumber()) {
            throw new ActionFailedException("坐标参数 " + field + " 必须为数字（0~1000 归一化坐标，收到："
                    + node.asText() + "）");
        }
        int value = node.asInt();
        if (value < 0 || value > 1000) {
            throw new ActionFailedException("坐标参数 " + field + "=" + value + " 超出归一化范围（0~1000）；"
                    + "请读取最近截图上的网格刻度：x 横向 0=最左、1000=最右；y 纵向 0=最上、1000=最下");
        }
        return value;
    }

    // ==================== 按键映射 ====================

    private int resolveButtonMask(String button) throws ActionFailedException {
        return switch (button.toLowerCase(Locale.ROOT)) {
            case "left" -> InputEvent.BUTTON1_DOWN_MASK;
            case "right" -> InputEvent.BUTTON3_DOWN_MASK;
            case "middle" -> InputEvent.BUTTON2_DOWN_MASK;
            default -> throw new ActionFailedException("无法识别的鼠标按键: " + button + "（可选 left/right/middle）");
        };
    }

    private int resolveKeyCode(String key) throws ActionFailedException {
        String k = key.trim().toLowerCase(Locale.ROOT);
        if (k.isEmpty()) {
            throw new ActionFailedException("key 参数为空");
        }
        // 已知特殊键
        Integer special = switch (k) {
            case "enter", "return" -> KeyEvent.VK_ENTER;
            case "esc", "escape" -> KeyEvent.VK_ESCAPE;
            case "tab" -> KeyEvent.VK_TAB;
            case "space", "spacebar" -> KeyEvent.VK_SPACE;
            case "backspace" -> KeyEvent.VK_BACK_SPACE;
            case "delete", "del" -> KeyEvent.VK_DELETE;
            case "insert", "ins" -> KeyEvent.VK_INSERT;
            case "home" -> KeyEvent.VK_HOME;
            case "end" -> KeyEvent.VK_END;
            case "pageup", "pgup" -> KeyEvent.VK_PAGE_UP;
            case "pagedown", "pgdn", "pgdown" -> KeyEvent.VK_PAGE_DOWN;
            case "up", "arrowup" -> KeyEvent.VK_UP;
            case "down", "arrowdown" -> KeyEvent.VK_DOWN;
            case "left", "arrowleft" -> KeyEvent.VK_LEFT;
            case "right", "arrowright" -> KeyEvent.VK_RIGHT;
            case "capslock" -> KeyEvent.VK_CAPS_LOCK;
            case "printscreen", "prtsc" -> KeyEvent.VK_PRINTSCREEN;
            case "ctrl", "control" -> KeyEvent.VK_CONTROL;
            case "alt", "option" -> KeyEvent.VK_ALT;
            case "shift" -> KeyEvent.VK_SHIFT;
            case "win", "meta", "cmd", "command", "super" -> KeyEvent.VK_META;
            case "f1" -> KeyEvent.VK_F1;
            case "f2" -> KeyEvent.VK_F2;
            case "f3" -> KeyEvent.VK_F3;
            case "f4" -> KeyEvent.VK_F4;
            case "f5" -> KeyEvent.VK_F5;
            case "f6" -> KeyEvent.VK_F6;
            case "f7" -> KeyEvent.VK_F7;
            case "f8" -> KeyEvent.VK_F8;
            case "f9" -> KeyEvent.VK_F9;
            case "f10" -> KeyEvent.VK_F10;
            case "f11" -> KeyEvent.VK_F11;
            case "f12" -> KeyEvent.VK_F12;
            default -> null;
        };
        if (special != null) {
            return special;
        }
        // 单字符：字母/数字
        if (k.length() == 1) {
            char c = k.charAt(0);
            if (c >= 'a' && c <= 'z') {
                return KeyEvent.VK_A + (c - 'a');
            }
            if (c >= '0' && c <= '9') {
                return KeyEvent.VK_0 + (c - '0');
            }
        }
        // 其它单字符：尝试 VK 常量语义（常见符号按 US 布局映射）
        if (k.length() == 1) {
            int code = resolveSymbolKeyCode(k.charAt(0));
            if (code != 0) {
                return code;
            }
        }
        throw new ActionFailedException("无法识别的按键: " + key
                + "（支持字母/数字/特殊键名，如需输入文本请改用 type 动作）");
    }

    /** 常见符号键（US 布局；含 Shift 符号时仅返回主键码，调用方不自动加 Shift——符号输入建议用 type） */
    private int resolveSymbolKeyCode(char c) {
        return switch (c) {
            case ' ' -> KeyEvent.VK_SPACE;
            case '-' -> KeyEvent.VK_MINUS;
            case '=' -> KeyEvent.VK_EQUALS;
            case '[' -> KeyEvent.VK_OPEN_BRACKET;
            case ']' -> KeyEvent.VK_CLOSE_BRACKET;
            case ';' -> KeyEvent.VK_SEMICOLON;
            case '\'' -> KeyEvent.VK_QUOTE;
            case ',' -> KeyEvent.VK_COMMA;
            case '.' -> KeyEvent.VK_PERIOD;
            case '/' -> KeyEvent.VK_SLASH;
            case '\\' -> KeyEvent.VK_BACK_SLASH;
            case '`' -> KeyEvent.VK_BACK_QUOTE;
            default -> 0;
        };
    }

    private int[] resolveModifiers(JsonNode modifiers) throws ActionFailedException {
        if (modifiers == null || !modifiers.isArray() || modifiers.isEmpty()) {
            return new int[0];
        }
        int[] result = new int[modifiers.size()];
        int i = 0;
        for (JsonNode m : modifiers) {
            String name = m.asText("").trim().toLowerCase(Locale.ROOT);
            result[i++] = switch (name) {
                case "ctrl", "control" -> KeyEvent.VK_CONTROL;
                case "alt", "option" -> KeyEvent.VK_ALT;
                case "shift" -> KeyEvent.VK_SHIFT;
                case "meta", "win", "cmd", "command", "super" -> KeyEvent.VK_META;
                default -> throw new ActionFailedException("无法识别的修饰键: " + name
                        + "（可选 ctrl/alt/shift/meta(win/cmd)）");
            };
        }
        return result;
    }

    // ==================== 取消检查 ====================

    /**
     * 会话是否存在活跃任务上下文（工具开始执行时的快照；辅助取消推断）
     */
    private boolean hasActiveContexts(Long conversationId) {
        return conversationId != null && !taskContextRegistry.getByConversationId(conversationId).isEmpty();
    }

    /**
     * 任务取消检查（尽力而为，工具循环检查点兜底）：
     * <ol>
     *   <li>任一上下文 cancelFlag=true（取消置位窗口内）；</li>
     *   <li>开始时有上下文、执行中变空——工具执行期间任务不会正常结束，视为取消路径的注销。</li>
     * </ol>
     */
    private boolean isCancelled(Long conversationId, boolean hadContextsAtStart) {
        if (conversationId == null) {
            return false;
        }
        Set<TaskContext> contexts = taskContextRegistry.getByConversationId(conversationId);
        if (contexts.stream().anyMatch(TaskContext::isCancelled)) {
            return true;
        }
        return hadContextsAtStart && contexts.isEmpty();
    }

    private void sleepQuietly(int millis) {
        if (millis <= 0) {
            return;
        }
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // ==================== 结果文本 ====================

    private String buildSuccessText(List<String> doneSummaries, int total) {
        StringBuilder sb = new StringBuilder();
        sb.append("✅ 桌面操作已执行完成（").append(total).append(" 个动作）：\n");
        for (int i = 0; i < doneSummaries.size(); i++) {
            sb.append(i + 1).append(". ").append(doneSummaries.get(i)).append("\n");
        }
        sb.append("\n建议调用 screen_capture 截图验证操作结果；如需继续操作，可基于最新截图给出下一批动作。");
        List<String> notes = platformAdapter.getPermissionNotes();
        if (!notes.isEmpty() && "macos".equals(platformAdapter.getPlatform())) {
            // macOS 仅在首用体验中提示（避免日志噪音）：不附加到每次返回
        }
        return sb.toString();
    }

    private String buildPartialFailureText(List<String> doneSummaries, int failedIndex,
                                           int total, String failedSummary, String error) {
        StringBuilder sb = new StringBuilder();
        sb.append("❌ 第 ").append(failedIndex + 1).append("/").append(total).append(" 个动作执行失败：")
                .append(failedSummary).append("\n原因：").append(error).append("\n");
        if (!doneSummaries.isEmpty()) {
            sb.append("\n已完成的动作（").append(doneSummaries.size()).append(" 个）：\n");
            for (int i = 0; i < doneSummaries.size(); i++) {
                sb.append(i + 1).append(". ").append(doneSummaries.get(i)).append("\n");
            }
        }
        sb.append("\n请根据失败原因修正后重试（坐标为 0~1000 归一化值，读最近截图网格刻度；必要时先重新截图）。");
        return sb.toString();
    }

    private String buildCancelledText(List<String> doneSummaries, int doneCount, int total) {
        StringBuilder sb = new StringBuilder();
        sb.append("⛔ 任务已取消：已执行 ").append(doneCount).append("/").append(total).append(" 个动作后停止。\n");
        if (!doneSummaries.isEmpty()) {
            sb.append("\n已完成的动作：\n");
            for (int i = 0; i < doneSummaries.size(); i++) {
                sb.append(i + 1).append(". ").append(doneSummaries.get(i)).append("\n");
            }
        }
        return sb.toString();
    }

    // ==================== 辅助 ====================

    private ObjectNode strNode(String description) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("type", "string");
        node.put("description", description);
        return node;
    }

    private ObjectNode intNode(String description) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("type", "integer");
        node.put("description", description);
        return node;
    }

    private ObjectNode enumNode(String description, String slashSeparated) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("type", "string");
        node.put("description", description);
        // enum 仅用于提示（保留 string 类型，避免模型因 enum 误判）
        return node;
    }

    /** 动作执行失败（带用户可读消息） */
    private static class ActionFailedException extends Exception {
        ActionFailedException(String message) {
            super(message);
        }
    }
}
