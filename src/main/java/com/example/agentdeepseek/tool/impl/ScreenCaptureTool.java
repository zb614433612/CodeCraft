package com.example.agentdeepseek.tool.impl;

import com.example.agentdeepseek.config.DesktopConfig;
import com.example.agentdeepseek.model.vo.FileAssetVO;
import com.example.agentdeepseek.service.files.FileAssetService;
import com.example.agentdeepseek.tool.Tool;
import com.example.agentdeepseek.tool.desktop.CaptureContext;
import com.example.agentdeepseek.tool.desktop.CaptureContextRegistry;
import com.example.agentdeepseek.tool.desktop.DesktopVerifyRegistry;
import com.example.agentdeepseek.tool.desktop.VerifyPreviewRenderer;
import com.example.agentdeepseek.tool.permission.OperationCategory;
import com.example.agentdeepseek.tool.permission.ToolPermission;
import com.example.agentdeepseek.util.ToolContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import java.awt.AWTException;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.GraphicsDevice;
import java.awt.GraphicsEnvironment;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.Robot;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 桌面截图工具（M5：Computer Use 组合能力之"眼睛"）
 *
 * <p>能力链路：Robot 截屏 → 分辨率标准化（长边 ≤ desktop.capture.max-long-edge）→ PNG 编码
 * → 自动上传 Files API（source=tool_capture）→ 工具返回文本 + 登记 user 消息注入
 * （图片只能进 user 消息，由 DeepSeekServiceImpl 统一追加并建立 file_reference）。</p>
 *
 * <p>坐标空间约定（v2 归一化协议）：截图上叠加 10×10 网格与四边三级刻度（v2.1 升级：每 25/50/100），
 * 模型"读数"给出 <b>归一化坐标</b>；映射关系存入会话级 {@link CaptureContextRegistry}，供
 * desktop_control（M6）与 region 参数换算回物理屏幕坐标执行（与图片缩放/服务端压缩解耦）。</p>
 *
 * <p>定位精度增强（v2.1 第一批）：① region 局部截图自动放大（放大镜，上限 {@code desktop.capture.max-zoom}，
 * 全屏截图只缩不放）；② 四边三级刻度尺（读数从"格内插值"变为"数刻度"）；③ mark={x,y} 准星标记——
 * 把坐标理解外化为可见标记，形成"估坐标 → 带标记截图 → 目测校正 → 操作"的瞄准闭环。</p>
 *
 * <p>频率限制：可在 {@code desktop.capture.min-interval-ms} / {@code max-per-minute} 配置，
 * 0 或负数 = 不限制该项（当前默认已取消限频）；超限返回引导文本。</p>
 *
 * <p>M6 预检联动（"先验证后执行"）：mark 校验成功时登记 {@link DesktopVerifyRegistry}
 * （该坐标在有效期内执行点击/拖拽免预检）；并实现 {@link VerifyPreviewRenderer} 供
 * desktop_control 预检闸门复用截图链路生成校验图。</p>
 */
@Slf4j
@Component
@ToolPermission(
        category = OperationCategory.DESKTOP,
        affectsData = false,
        highRisk = true,
        description = "桌面截图（自动上传并注入上下文，供视觉分析）"
)
public class ScreenCaptureTool implements Tool, VerifyPreviewRenderer {

    private static final DateTimeFormatter FILE_TIME_FORMAT =
            DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss_SSS");

    /** 会话级截图时间戳（毫秒）；用于频率限制（最小间隔 + 每分钟上限） */
    private final Map<Long, Deque<Long>> captureTimestamps = new ConcurrentHashMap<>();

    private final DesktopConfig config;
    private final FileAssetService fileAssetService;
    private final CaptureContextRegistry captureContextRegistry;
    private final ObjectMapper objectMapper;
    private final DesktopVerifyRegistry verifyRegistry;

    public ScreenCaptureTool(DesktopConfig config,
                             FileAssetService fileAssetService,
                             CaptureContextRegistry captureContextRegistry,
                             ObjectMapper objectMapper,
                             DesktopVerifyRegistry verifyRegistry) {
        this.config = config;
        this.fileAssetService = fileAssetService;
        this.captureContextRegistry = captureContextRegistry;
        this.objectMapper = objectMapper;
        this.verifyRegistry = verifyRegistry;
    }

    @Override
    public String getName() {
        return "screen_capture";
    }

    @Override
    public String getDescription() {
        return "桌面截图工具（Computer Use 之'眼睛'）：截取屏幕画面并自动上传注入上下文，供你直接查看桌面/应用界面。"
                + "\n【适用场景】需要查看当前屏幕内容（如帮用户找窗口、检查界面状态、为键鼠操作确认目标位置）时调用。"
                + "\n【参数说明】无参=截取全虚拟屏；screen=显示器序号（0 开始，多屏时选择指定屏幕）；"
                + "region={x,y,width,height}=局部放大截图（小区域会自动放大，便于看清细节），坐标基于最近一次截图的归一化坐标空间（0~1000；先无参截图一次再用）；"
                + "mark={x,y}=在截图上绘制准星标记（点击/拖拽前的坐标校验标准手段，与 region 可组合）。"
                + "\n【坐标约定】截图会自动标准化尺寸并叠加 10×10 网格与四边三级刻度（每 25/50/100，0~1000）："
                + "把目标位置对应的刻度值直接用于 desktop_control 与 region 即可，系统自动换算回屏幕坐标，"
                + "无需折算像素（图片再缩放/压缩也不影响）。每次截图（含 region/mark）都会更新坐标基准；跨大范围操作前建议先无参截图重置基准。"
                + "\n【点击前校验（默认流程）】对屏幕目标执行点击/拖拽前，先以 mark={x,y} 校验目标坐标：准星正中目标即可执行；"
                + "有偏差则按刻度修正后再次校验。密集列表/菜单项/小图标务必先校验。"
                + "已校验坐标（3 分钟内）执行时直接放行；未校验坐标会被系统预检拦截（自动返回校验图，不执行动作）。"
                + "小目标/密集列表可先用 region 放大再读数（放大后按新图刻度重新报坐标）。"
                + "\n【频率限制】" + buildRateLimitText() + "截图会上传至云端 LLM 用于视觉分析。";
    }

    /** 频率限制描述（按配置动态拼接；0 或负数 = 不限制该项） */
    private String buildRateLimitText() {
        int minIntervalMs = config.getCapture().getMinIntervalMs();
        int maxPerMinute = config.getCapture().getMaxPerMinute();
        if (minIntervalMs <= 0 && maxPerMinute <= 0) {
            return "无（不限频）；";
        }
        StringBuilder sb = new StringBuilder();
        if (minIntervalMs > 0) {
            sb.append("同一会话最小间隔 ").append(minIntervalMs).append(" 毫秒");
        }
        if (minIntervalMs > 0 && maxPerMinute > 0) {
            sb.append("、");
        }
        if (maxPerMinute > 0) {
            sb.append("每分钟最多 ").append(maxPerMinute).append(" 张");
        }
        return sb.append("；").toString();
    }

    @Override
    public JsonNode getParameters() {
        ObjectNode parameters = objectMapper.createObjectNode();
        parameters.put("type", "object");
        parameters.put("description", "截取桌面屏幕并自动注入上下文供视觉分析");

        ObjectNode properties = objectMapper.createObjectNode();

        // region 参数（局部放大截图）
        ObjectNode region = objectMapper.createObjectNode();
        region.put("type", "object");
        region.put("description", "【可选】局部放大截图区域：{x, y, width, height}，均为 0~1000 归一化值"
                + "（读最近一次截图的刻度值；x+width ≤ 1000、y+height ≤ 1000）；小区域会自动放大便于看清细节；"
                + "需先无参调用一次 screen_capture 建立基准。缺省截取整屏。");
        ObjectNode regionProps = objectMapper.createObjectNode();
        ObjectNode regionX = objectMapper.createObjectNode();
        regionX.put("type", "integer");
        regionX.put("description", "区域左上角 X 归一化坐标（0~1000）");
        regionProps.set("x", regionX);
        ObjectNode regionY = objectMapper.createObjectNode();
        regionY.put("type", "integer");
        regionY.put("description", "区域左上角 Y 归一化坐标（0~1000）");
        regionProps.set("y", regionY);
        ObjectNode regionW = objectMapper.createObjectNode();
        regionW.put("type", "integer");
        regionW.put("description", "区域宽度 归一化值（1~1000，且 x+width ≤ 1000）");
        regionProps.set("width", regionW);
        ObjectNode regionH = objectMapper.createObjectNode();
        regionH.put("type", "integer");
        regionH.put("description", "区域高度 归一化值（1~1000，且 y+height ≤ 1000）");
        regionProps.set("height", regionH);
        region.set("properties", regionProps);
        ArrayNode regionRequired = objectMapper.createArrayNode()
                .add("x").add("y").add("width").add("height");
        region.set("required", regionRequired);
        properties.set("region", region);

        // mark 参数（准星标记：坐标校验闭环）
        ObjectNode mark = objectMapper.createObjectNode();
        mark.put("type", "object");
        mark.put("description", "【可选】准星校验标记：{x, y}，均为 0~1000 归一化值（基于最近一次截图的坐标空间；与 region 可组合）。"
                + "在截图上绘制洋红十字标记该位置，用于目测校验坐标是否准确（估坐标 → 带 mark 截图 → 目测偏差 → 修正 → 点击）。");
        ObjectNode markProps = objectMapper.createObjectNode();
        ObjectNode markX = objectMapper.createObjectNode();
        markX.put("type", "integer");
        markX.put("description", "标记位置 X 归一化坐标（0~1000）");
        markProps.set("x", markX);
        ObjectNode markY = objectMapper.createObjectNode();
        markY.put("type", "integer");
        markY.put("description", "标记位置 Y 归一化坐标（0~1000）");
        markProps.set("y", markY);
        mark.set("properties", markProps);
        ArrayNode markRequired = objectMapper.createArrayNode()
                .add("x").add("y");
        mark.set("required", markRequired);
        properties.set("mark", mark);

        // screen 参数（显示器序号）
        ObjectNode screen = objectMapper.createObjectNode();
        screen.put("type", "integer");
        screen.put("description", "【可选】显示器序号（从 0 开始计数）。多屏时用于截取指定显示器；缺省截取全虚拟屏（所有显示器合并区域）。"
                + "与 region 参数互斥。");
        properties.set("screen", screen);

        parameters.set("properties", properties);
        return parameters;
    }

    @Override
    public String execute(JsonNode arguments) {
        // 1. 总开关
        if (!config.isEnabled()) {
            return "【错误类型】【功能未启用】桌面自动化功能已关闭（配置项 desktop.enabled=false）。"
                    + "请在应用配置中启用后重试。";
        }

        // 2. 图形环境检查（Headless 服务器不可用）
        if (GraphicsEnvironment.isHeadless()) {
            return "【错误类型】【环境不支持】当前运行环境为无图形界面（Headless）模式，无法进行桌面截图。"
                    + "桌面自动化功能仅支持有图形界面的桌面环境（Windows / macOS / Linux 桌面）。";
        }

        // 3. 上下文
        Long conversationId = ToolContext.getConversationId();
        Long userId = ToolContext.getUserId();
        if (userId == null) {
            return "【错误类型】【上下文缺失】无法获取当前用户ID，桌面截图需要用户上下文以关联文件资产。";
        }

        // 4. 频率限制（防死循环截图）
        String rateLimitError = checkRateLimit(conversationId);
        if (rateLimitError != null) {
            return rateLimitError;
        }

        // 5. 解析截图区域
        Rectangle target;
        JsonNode region = arguments.path("region");
        try {
            target = resolveTarget(arguments, region, conversationId);
        } catch (IllegalArgumentException e) {
            return e.getMessage();
        }
        if (target == null) {
            return "【错误类型】【截图失败】未能解析出有效的截图区域。";
        }

        // 5.5 解析准星标记 mark（参数校验在截屏前完成；无副作用）
        int[] markNorm = null;
        int markScreenX = 0;
        int markScreenY = 0;
        String markLabel = null;
        JsonNode markNode = arguments.path("mark");
        if (!markNode.isMissingNode() && !markNode.isNull()) {
            if (!markNode.isObject()) {
                return "【错误类型】【参数错误】mark 需为对象 {x, y}（0~1000 归一化坐标，读最近截图的刻度值）。";
            }
            try {
                markNorm = parseMarkNode(markNode);
            } catch (IllegalArgumentException e) {
                return e.getMessage();
            }
            CaptureContext markCtx = conversationId == null ? null : captureContextRegistry.get(conversationId);
            if (markCtx == null) {
                return "【错误类型】【缺少坐标基准】使用 mark 前请先无参调用一次 screen_capture 截取整屏"
                        + "（mark 坐标为 0~1000 归一化值，基于最近一次截图的坐标空间）。";
            }
            markScreenX = markCtx.normToScreenX(markNorm[0]);
            markScreenY = markCtx.normToScreenY(markNorm[1]);
            markLabel = "(" + markNorm[0] + ", " + markNorm[1] + ")";
        }

        // 6. 截屏（java.awt.Robot）
        BufferedImage image;
        try {
            Robot robot = new Robot();
            image = robot.createScreenCapture(target);
        } catch (AWTException | SecurityException e) {
            log.warn("桌面截图失败: {}", e.getMessage());
            return "【错误类型】【截图失败】截屏失败：" + e.getMessage()
                    + "。（macOS 需要'屏幕录制'权限；Linux Wayland 桌面截图受限）请检查系统权限后重试。";
        }
        if (image == null) {
            return "【错误类型】【截图失败】截屏返回空图像，请重试。";
        }

        // 7. 分辨率标准化：全屏/显示器截图只缩不放；region 局部截图在尺寸不足时自动放大（放大镜，
        //    上限 desktop.capture.max-zoom）——小区域放大后文字更大、读数更准
        boolean regionRequest = region != null && region.isObject();
        int physicalWidth = image.getWidth();
        int physicalHeight = image.getHeight();
        int[] sizePlan = planStandardSize(physicalWidth, physicalHeight, regionRequest);
        int standardWidth = sizePlan[0];
        int standardHeight = sizePlan[1];
        boolean scaledDown = standardWidth < physicalWidth || standardHeight < physicalHeight;
        boolean scaledUp = standardWidth > physicalWidth || standardHeight > physicalHeight;
        if (scaledDown) {
            image = scaleDown(image, standardWidth, standardHeight);
        } else if (scaledUp) {
            image = scaleUp(image, standardWidth, standardHeight);
        }

        // 7.5 网格叠加（v2 协议：10×10 网格 + 四边三级刻度，模型"读数"获取归一化坐标）
        image = drawGrid(image);

        // 7.6 准星标记叠加（mark：画在网格之上，坐标校验闭环）
        String markNote = null;
        if (markNorm != null) {
            int[] markPx = mapPointToImage(target, image.getWidth(), image.getHeight(), markScreenX, markScreenY);
            if (markPx != null) {
                image = drawMark(image, markPx[0], markPx[1], markLabel);
                // M6 预检联动：mark 校验成功 → 登记校验记录（该坐标点击/拖拽执行时免预检）
                if (verifyRegistry != null && conversationId != null) {
                    verifyRegistry.record(conversationId, markNorm[0], markNorm[1]);
                }
                markNote = "· 准星标记：已在图上绘制 " + markLabel + " 位置的洋红准星（十字+圆圈+标签）——"
                        + "请目测准星与实际目标位置的偏差：准星正中即可放心执行点击；有偏差先修正坐标。";
            } else {
                markNote = "【提示】mark 坐标 " + markLabel + " 对应的位置不在本次截图区域内，未绘制准星；"
                        + "如需校验该点请改用包含该位置的 region，或先无参截图重置基准。";
            }
        }

        // 8. PNG 编码
        byte[] pngBytes;
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            ImageIO.write(image, "png", baos);
            pngBytes = baos.toByteArray();
        } catch (IOException e) {
            log.warn("截图 PNG 编码失败: {}", e.getMessage());
            return "【错误类型】【截图失败】截图编码失败：" + e.getMessage() + "，请重试。";
        }

        // 9. 自动上传 Files API（source=tool_capture；永久保存，文件管理页可按来源筛选）
        String filename = "screen_capture_" + FILE_TIME_FORMAT.format(LocalDateTime.now()) + ".png";
        FileAssetVO asset;
        try {
            asset = fileAssetService.uploadLocalBytes(pngBytes, filename, userId,
                    ToolContext.getProviderCode(), "tool_capture");
        } catch (Exception e) {
            log.warn("截图上传失败: {}", e.getMessage());
            return "【错误类型】【上传失败】截图上传失败：" + e.getMessage()
                    + "。请检查文件服务（Files API）配置后重试。";
        }

        // 10. 记录会话级坐标映射（供 desktop_control / region 归一化换算）+ 登记 user 消息注入
        if (conversationId != null) {
            captureContextRegistry.put(conversationId, new CaptureContext(
                    target.x, target.y, target.width, target.height,
                    standardWidth, standardHeight,
                    asset.getFileId(), System.currentTimeMillis()));
        }
        ToolContext.addInjectUserFileAssetId(asset.getId());

        // 11. 返回文本（模型据此直接使用图片来源与坐标空间）
        log.info("桌面截图成功: conversationId={}, 物理={}x{}@({},{}), 标准={}x{}, 缩放down={}, up={}, mark={}, fileAssetId={}, fileId={}",
                conversationId, target.width, target.height, target.x, target.y,
                standardWidth, standardHeight, scaledDown, scaledUp, markLabel, asset.getId(), asset.getFileId());
        return buildSuccessText(asset, target, markNote);
    }

    // ==================== 预检图渲染（M6 预检闸门复用） ====================

    /**
     * 渲染并注入"准星校验图"（desktop_control 预检闸门调用；无副作用：不执行任何键鼠动作）。
     *
     * <p>复用截图链路：重截当前会话坐标基准区域（与最近一次截图相同的物理区域）→ 标准化
     * （缩放模式按基准图推断，保证网格刻度与基准图一致）→ 网格 + 批量准星 →
     * 上传（source=tool_verify）→ 登记 user 消息注入 + 刷新坐标基准。</p>
     *
     * @return null = 成功（图片已登记注入）；非 null = 失败原因（可直接返回给模型）
     */
    @Override
    public String renderVerifyPreview(Long conversationId, Long userId, List<int[]> normMarks) {
        // 1. 前置检查（与 execute 早退路径一致）
        if (!config.isEnabled()) {
            return "桌面自动化功能已关闭（配置项 desktop.enabled=false）";
        }
        if (GraphicsEnvironment.isHeadless()) {
            return "当前运行环境为无图形界面（Headless）模式，无法截图";
        }
        if (userId == null) {
            return "无法获取当前用户ID（预检图注入需要用户上下文）";
        }
        if (conversationId == null) {
            return "无法获取当前会话ID";
        }
        if (normMarks == null || normMarks.isEmpty()) {
            return "待校验坐标为空";
        }
        CaptureContext ctx = captureContextRegistry == null ? null : captureContextRegistry.get(conversationId);
        if (ctx == null) {
            return "缺少坐标基准，请先调用 screen_capture 截取整屏建立基准";
        }

        // 2. 重截基准区域（物理区域一致，保证坐标语义不变）
        Rectangle target = new Rectangle(ctx.physicalX(), ctx.physicalY(),
                ctx.physicalWidth(), ctx.physicalHeight());
        BufferedImage image;
        try {
            Robot robot = new Robot();
            image = robot.createScreenCapture(target);
        } catch (AWTException | SecurityException e) {
            return "截屏失败：" + e.getMessage() + "（请检查系统屏幕权限）";
        }
        if (image == null) {
            return "截屏返回空图像";
        }

        // 3. 标准化：缩放模式按基准图推断（基准图放大过 → 放大模式；否则只缩不放），保证刻度与基准图一致
        boolean baseWasUpscaled = ctx.standardWidth() > ctx.physicalWidth()
                || ctx.standardHeight() > ctx.physicalHeight();
        int physicalWidth = image.getWidth();
        int physicalHeight = image.getHeight();
        int[] sizePlan = planStandardSize(physicalWidth, physicalHeight, baseWasUpscaled);
        int standardWidth = sizePlan[0];
        int standardHeight = sizePlan[1];
        if (standardWidth < physicalWidth || standardHeight < physicalHeight) {
            image = scaleDown(image, standardWidth, standardHeight);
        } else if (standardWidth > physicalWidth || standardHeight > physicalHeight) {
            image = scaleUp(image, standardWidth, standardHeight);
        }

        // 4. 网格 + 批量准星（画在最终图上，确保清晰可读）
        image = drawGrid(image);
        for (int[] mark : normMarks) {
            int screenX = ctx.normToScreenX(mark[0]);
            int screenY = ctx.normToScreenY(mark[1]);
            int[] px = mapPointToImage(target, image.getWidth(), image.getHeight(), screenX, screenY);
            if (px != null) {
                image = drawMark(image, px[0], px[1], "(" + mark[0] + ", " + mark[1] + ")");
            }
        }

        // 5. PNG 编码 → 上传（source=tool_verify，与普通截图区分）→ 注入登记 → 刷新坐标基准
        byte[] pngBytes;
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            ImageIO.write(image, "png", baos);
            pngBytes = baos.toByteArray();
        } catch (IOException e) {
            return "校验图编码失败：" + e.getMessage();
        }
        String filename = "desktop_verify_" + FILE_TIME_FORMAT.format(LocalDateTime.now()) + ".png";
        FileAssetVO asset;
        try {
            asset = fileAssetService.uploadLocalBytes(pngBytes, filename, userId,
                    ToolContext.getProviderCode(), "tool_verify");
        } catch (Exception e) {
            return "校验图上传失败：" + e.getMessage();
        }
        captureContextRegistry.put(conversationId, new CaptureContext(
                target.x, target.y, target.width, target.height,
                standardWidth, standardHeight,
                asset.getFileId(), System.currentTimeMillis()));
        ToolContext.addInjectUserFileAssetId(asset.getId());
        log.info("预检校验图已生成: conversationId={}, marks={}, fileAssetId={}",
                conversationId, normMarks.size(), asset.getId());
        return null;
    }

    // ==================== 区域解析 ====================

    /**
     * 解析截图目标区域（物理屏幕坐标系）：
     * <ol>
     *   <li>region 指定：基于最近截图上下文的"归一化坐标（0~1000）"换算回物理坐标（与 desktop_control 坐标语义一致），
     *       并裁剪到最近截图的物理范围内；无最近截图时提示先全屏截图；</li>
     *   <li>screen 指定：对应显示器的完整物理边界（与 region 互斥）；</li>
     *   <li>缺省：全虚拟屏合并区域（所有显示器并集）。</li>
     * </ol>
     *
     * @return 目标区域；解析出的错误提示通过 {@link IllegalArgumentException} 抛出（消息可直接返回给模型）
     */
    private Rectangle resolveTarget(JsonNode arguments, JsonNode region, Long conversationId) {
        GraphicsEnvironment ge = GraphicsEnvironment.getLocalGraphicsEnvironment();
        GraphicsDevice[] devices = ge.getScreenDevices();
        if (devices.length == 0) {
            throw new IllegalArgumentException("【错误类型】【截图失败】未检测到显示器设备。");
        }

        boolean hasRegion = region != null && region.isObject();
        boolean hasScreen = arguments.hasNonNull("screen");

        if (hasRegion && hasScreen) {
            throw new IllegalArgumentException("【错误类型】【参数冲突】region 与 screen 不能同时使用："
                    + "region 用于在最近截图基础上局部放大，screen 用于直接截取指定显示器，请二选一。");
        }

        // 场景一：region 局部放大（基于最近截图的归一化坐标，v2 协议）
        if (hasRegion) {
            CaptureContext ctx = conversationId == null ? null : captureContextRegistry.get(conversationId);
            if (ctx == null) {
                throw new IllegalArgumentException("【错误类型】【缺少坐标基准】使用 region 前请先无参调用一次 screen_capture 截取整屏"
                        + "（region 坐标为 0~1000 归一化值，读最近截图的网格刻度）。");
            }
            int rx = region.path("x").asInt(0);
            int ry = region.path("y").asInt(0);
            int rw = region.path("width").asInt(0);
            int rh = region.path("height").asInt(0);
            if (rx < 0 || ry < 0 || rw <= 0 || rh <= 0
                    || rw > 1000 || rh > 1000 || rx + rw > 1000 || ry + rh > 1000) {
                throw new IllegalArgumentException("【错误类型】【参数错误】region 需使用 0~1000 归一化坐标（读最近截图的网格刻度）："
                        + "x/y=区域左上角、width/height=区域尺寸（正整数），且 x+width ≤ 1000、y+height ≤ 1000。"
                        + "收到：x=" + rx + ", y=" + ry + ", width=" + rw + ", height=" + rh + "。");
            }
            // 归一化坐标 → 物理坐标（右/下边界含端点）
            int px0 = ctx.normToScreenX(rx);
            int py0 = ctx.normToScreenY(ry);
            int px1 = ctx.normToScreenX(rx + rw);
            int py1 = ctx.normToScreenY(ry + rh);
            Rectangle requested = new Rectangle(px0, py0,
                    Math.max(1, px1 - px0), Math.max(1, py1 - py0));
            Rectangle base = new Rectangle(ctx.physicalX(), ctx.physicalY(),
                    ctx.physicalWidth(), ctx.physicalHeight());
            Rectangle clipped = base.intersection(requested);
            if (clipped.isEmpty()) {
                throw new IllegalArgumentException("【错误类型】【参数错误】region 超出最近一次截图的画面范围"
                        + "（请求区域 x=" + rx + ", y=" + ry + ", width=" + rw + ", height=" + rh
                        + " 归一化值）。请检查坐标（0~1000）或先重新全屏截图。");
            }
            return clipped;
        }

        // 场景二：指定显示器
        if (hasScreen) {
            int screenIndex = arguments.path("screen").asInt(-1);
            if (screenIndex < 0 || screenIndex >= devices.length) {
                throw new IllegalArgumentException("【错误类型】【参数错误】screen=" + screenIndex
                        + " 无效：当前检测到 " + devices.length + " 个显示器（序号 0-" + (devices.length - 1)
                        + "），请使用有效序号。");
            }
            return devices[screenIndex].getDefaultConfiguration().getBounds();
        }

        // 场景三：全虚拟屏合并区域
        Rectangle union = null;
        for (GraphicsDevice device : devices) {
            Rectangle bounds = device.getDefaultConfiguration().getBounds();
            union = (union == null) ? bounds : union.union(bounds);
        }
        return union;
    }

    /**
     * 解析 mark 准星参数（0~1000 归一化坐标；截屏前校验，参数非法时抛
     * {@link IllegalArgumentException}，消息可直接返回给模型）。
     *
     * @return [x, y] 归一化坐标
     */
    private int[] parseMarkNode(JsonNode mark) {
        int mx = mark.path("x").asInt(Integer.MIN_VALUE);
        int my = mark.path("y").asInt(Integer.MIN_VALUE);
        if (mx == Integer.MIN_VALUE || my == Integer.MIN_VALUE || mx < 0 || mx > 1000 || my < 0 || my > 1000) {
            throw new IllegalArgumentException("【错误类型】【参数错误】mark 需为 0~1000 归一化坐标：请同时提供 x 与 y"
                    + "（整数，读最近一次截图的刻度值）。收到：x=" + (mx == Integer.MIN_VALUE ? "缺失/非法" : mx)
                    + ", y=" + (my == Integer.MIN_VALUE ? "缺失/非法" : my) + "。");
        }
        return new int[]{mx, my};
    }

    // ==================== 频率限制 ====================

    /**
     * 会话级频率限制检查（通过即记账；失败也占用配额，防"失败重试"爆破）。
     *
     * <p>配置为 0 或负数时该项限制不启用；两项均未启用时直接放行。</p>
     *
     * @return null=通过；非 null=可直接返回给模型的超限引导文本
     */
    private String checkRateLimit(Long conversationId) {
        if (conversationId == null) {
            return null;
        }
        long now = System.currentTimeMillis();
        int minIntervalMs = config.getCapture().getMinIntervalMs();
        int maxPerMinute = config.getCapture().getMaxPerMinute();
        // 两项限制均未启用（0 或负数）：直接放行，无需记账
        if (minIntervalMs <= 0 && maxPerMinute <= 0) {
            return null;
        }
        Deque<Long> deque = captureTimestamps.computeIfAbsent(conversationId, k -> new ArrayDeque<>());
        synchronized (deque) {
            // 清理 1 分钟前的记录
            while (!deque.isEmpty() && now - deque.peekFirst() > 60_000L) {
                deque.pollFirst();
            }
            // 最小间隔（> 0 时生效）
            Long last = deque.peekLast();
            if (minIntervalMs > 0 && last != null && now - last < minIntervalMs) {
                long waitMs = minIntervalMs - (now - last);
                return "【错误类型】【频率限制】截图过于频繁：同一会话两张截图最小间隔 " + minIntervalMs
                        + " 毫秒（还需等待约 " + waitMs + " 毫秒）。请先用已有截图继续分析，稍后再试。";
            }
            // 每分钟上限（> 0 时生效）
            if (maxPerMinute > 0 && deque.size() >= maxPerMinute) {
                long waitMs = Math.max(0, deque.peekFirst() + 60_000L - now);
                return "【错误类型】【频率限制】本会话每分钟最多截图 " + maxPerMinute
                        + " 张，已达到上限（约 " + (waitMs / 1000 + 1) + " 秒后可继续）。"
                        + "请基于已有截图操作，或等待后重试。";
            }
            deque.addLast(now);
        }
        return null;
    }

    // ==================== 图像缩放 ====================

    /**
     * 将截图缩小到目标尺寸（渐进式减半 + 最终精确缩放，显著优于一步缩小，保证文字可读性）
     */
    private BufferedImage scaleDown(BufferedImage src, int targetW, int targetH) {
        BufferedImage current = src;
        while (current.getWidth() / 2 >= targetW && current.getHeight() / 2 >= targetH) {
            current = scaleOnce(current, current.getWidth() / 2, current.getHeight() / 2);
        }
        if (current.getWidth() != targetW || current.getHeight() != targetH) {
            current = scaleOnce(current, targetW, targetH);
        }
        return current;
    }

    /** 单步缩放（双线性插值 + 高质量渲染） */
    private BufferedImage scaleOnce(BufferedImage src, int w, int h) {
        BufferedImage dst = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = dst.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.drawImage(src, 0, 0, w, h, null);
        } finally {
            g.dispose();
        }
        return dst;
    }

    /**
     * 单步放大（双三次插值 + 高质量渲染；仅 region 放大镜使用——放大后文字更大、读数更准）
     */
    private BufferedImage scaleUp(BufferedImage src, int w, int h) {
        BufferedImage dst = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = dst.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.drawImage(src, 0, 0, w, h, null);
        } finally {
            g.dispose();
        }
        return dst;
    }

    /**
     * 计算截图标准化后的输出尺寸（纯函数，供测试）。
     *
     * <p>规则：</p>
     * <ol>
     *   <li>全屏/显示器截图：只缩不放（长边超上限时等比缩小）；</li>
     *   <li>region 局部截图：长边超上限时同样缩小；不足时自动放大（放大镜），
     *       倍率 = min(maxZoom, maxLongEdge / 长边)，maxZoom ≤ 1 时禁用放大。</li>
     * </ol>
     *
     * @return [width, height]（与源尺寸不同时由调用方选择 scaleDown/scaleUp 执行）
     */
    private int[] planStandardSize(int physicalW, int physicalH, boolean regionRequest) {
        int maxLongEdge = Math.max(64, config.getCapture().getMaxLongEdge());
        int longEdge = Math.max(physicalW, physicalH);
        if (longEdge > maxLongEdge) {
            double scale = (double) maxLongEdge / longEdge;
            return new int[]{
                    Math.max(1, (int) Math.round(physicalW * scale)),
                    Math.max(1, (int) Math.round(physicalH * scale))
            };
        }
        if (regionRequest) {
            double maxZoom = config.getCapture().getMaxZoom();
            if (maxZoom > 1.0) {
                double zoom = Math.min(maxZoom, (double) maxLongEdge / longEdge);
                if (zoom > 1.0) {
                    return new int[]{
                            Math.max(1, (int) Math.round(physicalW * zoom)),
                            Math.max(1, (int) Math.round(physicalH * zoom))
                    };
                }
            }
        }
        return new int[]{physicalW, physicalH};
    }

    // ==================== 网格叠加（v2 协议：归一化坐标网格） ====================

    /** 网格分割数（10×10 格；刻度 100~900 对应 0~1000 归一化坐标） */
    private static final int GRID_DIVISIONS = 10;

    /** 四边刻度尺分割数（每 25 一档：1000/25 = 40） */
    private static final int GRID_TICK_COUNT = 40;

    /** 短刻度长度（像素；25 档） */
    private static final int TICK_LEN_SHORT = 6;

    /** 中刻度长度（像素；50 档） */
    private static final int TICK_LEN_MID = 12;

    /** 长刻度长度（像素；100 档） */
    private static final int TICK_LEN_LONG = 18;

    /** 网格线颜色（半透明红：叠加在任意画面内容上均可辨识；v2.1 不透明度 96 → 128 提升读图对比） */
    private static final Color GRID_LINE_COLOR = new Color(255, 0, 0, 128);

    /** 刻度标签底色（近白半透明：保证文字在任意内容上可读） */
    private static final Color GRID_LABEL_BG = new Color(255, 255, 255, 210);

    /** 刻度标签文字颜色（深红） */
    private static final Color GRID_LABEL_FG = new Color(200, 0, 0);

    /** 准星标记颜色（洋红：与网格红线区分明显，不与任何常见 UI 配色混淆） */
    private static final Color MARK_COLOR = new Color(255, 0, 255);

    /** 准星描边色（白色描边保证在暗色/亮色背景上均可辨识） */
    private static final Color MARK_OUTLINE_COLOR = new Color(255, 255, 255);

    /**
     * 在标准化截图上叠加 10×10 主网格 + 四边三级刻度 + 归一化数字（0~1000 坐标空间）。
     *
     * <p>v2.1 直尺升级：每 25 短刻度（6px）/ 每 50 中刻度（12px）/ 每 100 长刻度（18px）四边布尺，
     * 模型读数从"格内插值"变为"数刻度"；网格是图像内容的一部分，对任何后续缩放/压缩免疫。</p>
     */
    private BufferedImage drawGrid(BufferedImage src) {
        int w = src.getWidth();
        int h = src.getHeight();
        BufferedImage dst = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = dst.createGraphics();
        try {
            g.drawImage(src, 0, 0, null);
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            // 1) 主网格线（每 100 一档，贯穿全图；"读格"主参考）
            g.setStroke(new BasicStroke(1f));
            g.setColor(GRID_LINE_COLOR);
            for (int i = 1; i < GRID_DIVISIONS; i++) {
                int x = (int) Math.round(w * (double) i / GRID_DIVISIONS);
                int y = (int) Math.round(h * (double) i / GRID_DIVISIONS);
                g.drawLine(x, 0, x, h);
                g.drawLine(0, y, w, y);
            }
            // 2) 四边三级刻度（每 25 短 / 50 中 / 100 长；精细读数用）
            drawRulerTicks(g, w, h);
            // 3) 刻度数字（归一化坐标值；仅顶部/左侧，字号随图缩放，保证可读性）
            int fontSize = Math.max(12, Math.min(w, h) / 40);
            g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, fontSize));
            FontMetrics fm = g.getFontMetrics();
            int labelStep = 1000 / GRID_DIVISIONS;
            for (int i = 1; i < GRID_DIVISIONS; i++) {
                String label = String.valueOf(i * labelStep);
                int x = (int) Math.round(w * (double) i / GRID_DIVISIONS);
                int y = (int) Math.round(h * (double) i / GRID_DIVISIONS);
                drawGridLabel(g, fm, label, x + 2, 2);  // 顶部：X 刻度
                drawGridLabel(g, fm, label, 2, y + 2);  // 左侧：Y 刻度
            }
        } finally {
            g.dispose();
        }
        return dst;
    }

    /**
     * 四边刻度尺：每 25 短刻度（{@link #TICK_LEN_SHORT}px）/ 每 50 中刻度（{@link #TICK_LEN_MID}px）/
     * 每 100 长刻度（{@link #TICK_LEN_LONG}px），均从边缘向内绘制。
     */
    private void drawRulerTicks(Graphics2D g, int w, int h) {
        g.setColor(GRID_LINE_COLOR);
        g.setStroke(new BasicStroke(1f));
        for (int i = 1; i < GRID_TICK_COUNT; i++) {
            int len;
            if (i % 4 == 0) {
                len = TICK_LEN_LONG;        // 100 的倍数
            } else if (i % 2 == 0) {
                len = TICK_LEN_MID;         // 50 的倍数
            } else {
                len = TICK_LEN_SHORT;       // 25 的倍数
            }
            int x = (int) Math.round(w * (double) i / GRID_TICK_COUNT);
            int y = (int) Math.round(h * (double) i / GRID_TICK_COUNT);
            g.drawLine(x, 0, x, len);                   // 顶部（向下）
            g.drawLine(x, h - 1, x, h - 1 - len);       // 底部（向上）
            g.drawLine(0, y, len, y);                   // 左侧（向右）
            g.drawLine(w - 1, y, w - 1 - len, y);       // 右侧（向左）
        }
    }

    /** 绘制单个刻度标签（白底红字） */
    private void drawGridLabel(Graphics2D g, FontMetrics fm, String label, int x, int y) {
        int textW = fm.stringWidth(label);
        int textH = fm.getHeight();
        g.setColor(GRID_LABEL_BG);
        g.fillRect(x - 1, y - 1, textW + 4, textH + 2);
        g.setColor(GRID_LABEL_FG);
        g.drawString(label, x + 1, y + fm.getAscent());
    }

    // ==================== 准星标记（mark：瞄准校验闭环） ====================

    /**
     * 将"物理屏幕点"映射为"输出图像像素坐标"（纯函数，供测试）。
     *
     * <p>映射基于相对位置：输出图可能是物理区域的缩放/放大结果，但相对位置不变。</p>
     *
     * @return [x, y]（含边界，clamp 到图像有效范围内）；点不在截取区域内时返回 null
     */
    static int[] mapPointToImage(Rectangle target, int imgW, int imgH, int screenX, int screenY) {
        if (screenX < target.x || screenX > target.x + target.width
                || screenY < target.y || screenY > target.y + target.height) {
            return null;
        }
        int px = (int) Math.round((screenX - target.x) * (double) imgW / target.width);
        int py = (int) Math.round((screenY - target.y) * (double) imgH / target.height);
        px = Math.max(0, Math.min(imgW - 1, px));
        py = Math.max(0, Math.min(imgH - 1, py));
        return new int[]{px, py};
    }

    /**
     * 在截图上绘制准星标记（mark）：洋红十字 + 细圆圈 + 坐标标签，画在网格之上。
     *
     * <p>白色粗描边 + 洋红细线双层绘制，保证在任意背景上均可辨识；标签位置自适应
     * （右/下空间不足时翻转到左/上侧），避免出界。</p>
     */
    private BufferedImage drawMark(BufferedImage src, int cx, int cy, String label) {
        int w = src.getWidth();
        int h = src.getHeight();
        BufferedImage dst = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = dst.createGraphics();
        try {
            g.drawImage(src, 0, 0, null);
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            int arm = Math.max(14, Math.min(36, Math.min(w, h) / 24));  // 十字半臂长
            int r = Math.max(9, (int) (arm * 0.65));                    // 圆圈半径
            // 白色粗描边 → 洋红细主线（两层形成描边效果）
            drawCrossCircle(g, cx, cy, arm, r, MARK_OUTLINE_COLOR, 4.5f);
            drawCrossCircle(g, cx, cy, arm, r, MARK_COLOR, 2.0f);
            // 坐标标签（白底洋红字，样式同网格刻度）
            int fontSize = Math.max(12, Math.min(w, h) / 40);
            g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, fontSize));
            FontMetrics fm = g.getFontMetrics();
            int textW = fm.stringWidth(label);
            int textH = fm.getHeight();
            int lx = cx + r + 6;
            int ly = cy + r + 4;
            if (lx + textW + 4 > w) {
                lx = cx - r - 6 - textW - 4;    // 右边界空间不足 → 放左侧
            }
            if (ly + textH + 2 > h) {
                ly = cy - r - 6 - textH - 2;    // 下边界空间不足 → 放上方
            }
            lx = Math.max(2, lx);
            ly = Math.max(2, ly);
            g.setColor(GRID_LABEL_BG);
            g.fillRect(lx - 1, ly - 1, textW + 4, textH + 2);
            g.setColor(MARK_COLOR);
            g.drawString(label, lx + 1, ly + fm.getAscent());
        } finally {
            g.dispose();
        }
        return dst;
    }

    /** 绘制十字 + 圆圈（描边层与主线层共用；出画布部分由 Graphics2D 自动裁剪） */
    private void drawCrossCircle(Graphics2D g, int cx, int cy, int arm, int r, Color color, float strokeW) {
        g.setColor(color);
        g.setStroke(new BasicStroke(strokeW, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.drawLine(cx - arm, cy, cx + arm, cy);
        g.drawLine(cx, cy - arm, cx, cy + arm);
        g.drawOval(cx - r, cy - r, 2 * r, 2 * r);
    }

    // ==================== 返回文本 ====================

    /**
     * 构建工具返回文本（模型据此得知图片来源、坐标协议与使用方式）
     *
     * <p>不携带"图片像素尺寸/缩放比/文件大小"等元信息（v2 协议下与模型坐标操作无关，
     * 且可能诱导"像素思维"）——坐标一律以 0~1000 归一化协议表达。</p>
     *
     * @param markNote mark 准星绘制结果提示/警告（可空；传了 mark 参数时由主流程生成）
     */
    private String buildSuccessText(FileAssetVO asset, Rectangle target, String markNote) {
        StringBuilder sb = new StringBuilder();
        sb.append("桌面截图已成功上传并注入上下文（下一轮即可直接看到本图）。\n");
        sb.append("· 文件：file_asset_id=").append(asset.getId())
                .append(", file_id=").append(asset.getFileId());
        sb.append("\n· 截图区域（物理屏幕）：x=").append(target.x).append(", y=").append(target.y)
                .append(", ").append(target.width).append("x").append(target.height);
        sb.append("\n· 坐标协议：本图叠加了 10×10 网格与四边三级刻度（每 25/50/100，0~1000；0=最左/最上、1000=最右/最下）；"
                + "desktop_control 与 region 直接使用归一化坐标（读目标位置对应的刻度值），只传 0~1000 的数值"
                + "、不要传像素值；系统自动换算为屏幕物理坐标，无需自行折算（图片被进一步缩放/压缩也不影响）。");
        if (markNote != null && !markNote.isEmpty()) {
            sb.append("\n").append(markNote);
        }
        sb.append("\n如需放大查看局部细节，可再次调用 screen_capture 并传入基于本图归一化坐标（0~1000）的 region；"
                + "执行点击/拖拽前建议先用 mark={x,y} 校验目标坐标（准星居中再执行）。");
        return sb.toString();
    }

    /** 供测试/诊断使用：当前会话已被限流的最近上限（空列表表示不限） */
    List<Long> snapshotTimestamps(Long conversationId) {
        Deque<Long> deque = captureTimestamps.get(conversationId);
        return deque == null ? List.of() : List.copyOf(deque);
    }
}
