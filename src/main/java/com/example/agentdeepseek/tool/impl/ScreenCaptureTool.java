package com.example.agentdeepseek.tool.impl;

import com.example.agentdeepseek.config.DesktopConfig;
import com.example.agentdeepseek.model.vo.FileAssetVO;
import com.example.agentdeepseek.service.files.FileAssetService;
import com.example.agentdeepseek.tool.Tool;
import com.example.agentdeepseek.tool.desktop.CaptureContext;
import com.example.agentdeepseek.tool.desktop.CaptureContextRegistry;
import com.example.agentdeepseek.tool.desktop.DesktopElementDetector;
import com.example.agentdeepseek.tool.desktop.DesktopVerifyRegistry;
import com.example.agentdeepseek.tool.desktop.DetectedElement;
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
import java.awt.Shape;
import java.awt.font.GlyphVector;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
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
 * <p>定位精度增强（v2.1 第二批，★ 本次新增）：④ marks=[{x,y},…] 多准星对比——一次绘制 2~5 个候选点
 * （按序标 A/B/C/D/E，五色轮转洋红/青/橙/绿/紫），一张图并列对比选最佳点位，全部候选点自动登记预检；
 * ⑤ 准星"画中画"inset——每个准星自动附中心小窗放大图（约 3× 双三次插值）贴图像右下角，
 * 窗内以细线小十字+中心小点标示准星位置，解决"准星遮挡小目标、难判断是否正中"的问题。
 * 两条截图链路（mark 主动校验 / desktop_control 预检图）统一使用"字母+颜色+画中画"渲染。</p>
 *
 * <p>定位精度增强（v2.1 第三批，★ 本次新增）：⑥ region 基准范围标注——局部放大图在左下角标注
 * "本区=基准 x∈[gx0,gx1] y∈[gy0,gy1]"（本区域对应"基准截图（最近一次）"的归一化范围），
 * 返回文本同步输出"【坐标空间】"说明；解决 region 图刻度为"本区域相对"0~1000、模型在多图/
 * 多坐标空间之间易混淆的问题。</p>
 *
 * <p>定位精度增强（v2.1 第四批，★ 本次新增）：⑦ SoM 元素检测 + 编号（Set-of-Mark）——截图时自动
 * 枚举可交互 UI 元素（Windows UIAutomation，经 {@link DesktopElementDetector}；失败静默降级为空清单），
 * 在图上叠加青色"#N"编号徽标并输出【元素清单】（编号 → 名称/类型/机器计算的中心点归一化坐标），
 * 模型"按编号选点"点击，摆脱目测像素误差。</p>
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
    private final DesktopElementDetector elementDetector;

    public ScreenCaptureTool(DesktopConfig config,
                             FileAssetService fileAssetService,
                             CaptureContextRegistry captureContextRegistry,
                             ObjectMapper objectMapper,
                             DesktopVerifyRegistry verifyRegistry,
                             DesktopElementDetector elementDetector) {
        this.config = config;
        this.fileAssetService = fileAssetService;
        this.captureContextRegistry = captureContextRegistry;
        this.objectMapper = objectMapper;
        this.verifyRegistry = verifyRegistry;
        this.elementDetector = elementDetector;
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
                + "mark={x,y}=在截图上绘制准星标记（点击/拖拽前的坐标校验标准手段，与 region 可组合）；"
                + "marks=[{x,y},…]=一次绘制 2~5 个候选准星（按序标 A/B/C/D/E、五色轮转，各附右下角画中画放大窗）供对比选最佳点位，与 mark 二选一。"
                + "\n【坐标约定】截图会自动标准化尺寸并叠加 10×10 网格与四边三级刻度（每 25/50/100，0~1000）："
                + "把目标位置对应的刻度值直接用于 desktop_control 与 region 即可，系统自动换算回屏幕坐标，"
                + "无需折算像素（图片再缩放/压缩也不影响）。每次截图（含 region/mark）都会更新坐标基准；跨大范围操作前建议先无参截图重置基准。"
                + "\n【元素清单（自动编号）】截图会自动检测界面可交互元素并叠加青色 #编号 徽标，返回文本附【元素清单】"
                + "（编号 → 名称/类型/中心点归一化坐标，机器计算）。点击目标时优先使用清单坐标（比目测更准）；"
                + "未在清单中的目标仍用网格刻度读数。检测失败/非 Windows 自动跳过（不影响截图）。"
                + "\n【点击前校验（默认流程）】对屏幕目标执行点击/拖拽前，先以 mark={x,y} 校验目标坐标：准星正中目标即可执行；"
                + "有偏差则按刻度修正后再次校验。密集列表/菜单项/小图标务必先校验；拿不准哪个点更准时，可用 marks=[…] 一次给出 2~5 个候选点对比选择。"
                + "已校验坐标（3 分钟内）执行时直接放行；未校验坐标会被系统预检拦截（自动返回校验图，不执行动作）。"
                + "小目标（<50px）必须走放大闭环：先 region 放大到目标占画面 5% 以上，在放大图空间内完成校验与点击（直接用放大图坐标，绝不跨图心算）；"
                + "图标类/语义不明的目标先 hover 看 tooltip 确认语义后再点击。"
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

        // marks 参数（多准星对比：一次绘制 2~5 个候选点，★ 新增）
        ObjectNode marks = objectMapper.createObjectNode();
        marks.put("type", "array");
        marks.put("description", "【可选】多准星对比标记：2~5 个候选点 [{x,y}, ...]，每项均为 0~1000 归一化值"
                + "（基于最近一次截图的坐标空间；与 mark 二选一、与 region 可组合）。"
                + "在截图上按序绘制 A/B/C/D/E 五色准星（各附右下角画中画放大窗），供对比挑选最佳点位后再执行点击。");
        ObjectNode marksItems = objectMapper.createObjectNode();
        marksItems.put("type", "object");
        ObjectNode marksItemProps = objectMapper.createObjectNode();
        ObjectNode marksItemX = objectMapper.createObjectNode();
        marksItemX.put("type", "integer");
        marksItemX.put("description", "候选点 X 归一化坐标（0~1000）");
        marksItemProps.set("x", marksItemX);
        ObjectNode marksItemY = objectMapper.createObjectNode();
        marksItemY.put("type", "integer");
        marksItemY.put("description", "候选点 Y 归一化坐标（0~1000）");
        marksItemProps.set("y", marksItemY);
        marksItems.set("properties", marksItemProps);
        ArrayNode marksItemRequired = objectMapper.createArrayNode().add("x").add("y");
        marksItems.set("required", marksItemRequired);
        marks.set("items", marksItems);
        properties.set("marks", marks);

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

        // 5.5 解析准星标记（mark 单点 / marks 多候选点对比；参数校验在截屏前完成；无副作用）
        List<int[]> markNorms = new ArrayList<>();      // 候选点归一化坐标（mark: 1 个；marks: 2~5 个）
        List<int[]> markScreens = new ArrayList<>();    // 候选点物理屏幕坐标（与 markNorms 一一对应）
        boolean multiMarkMode = false;                  // marks 多点模式（影响返回文案与登记策略）
        JsonNode markNode = arguments.path("mark");
        JsonNode marksNode = arguments.path("marks");
        boolean hasMark = !markNode.isMissingNode() && !markNode.isNull();
        boolean hasMarks = !marksNode.isMissingNode() && !marksNode.isNull();
        if (hasMark && hasMarks) {
            return "【错误类型】【参数冲突】mark 与 marks 不能同时使用：mark 为单点校验、marks 为多候选点对比（A/B/C…），请二选一。";
        }
        if (hasMark) {
            if (!markNode.isObject()) {
                return "【错误类型】【参数错误】mark 需为对象 {x, y}（0~1000 归一化坐标，读最近截图的刻度值）。";
            }
            try {
                markNorms.add(parseMarkNode(markNode));
            } catch (IllegalArgumentException e) {
                return e.getMessage();
            }
        } else if (hasMarks) {
            try {
                markNorms.addAll(parseMarksNode(marksNode));
            } catch (IllegalArgumentException e) {
                return e.getMessage();
            }
            multiMarkMode = true;
        }
        if (!markNorms.isEmpty()) {
            CaptureContext markCtx = conversationId == null ? null : captureContextRegistry.get(conversationId);
            if (markCtx == null) {
                return "【错误类型】【缺少坐标基准】使用 " + (multiMarkMode ? "marks" : "mark")
                        + " 前请先无参调用一次 screen_capture 截取整屏"
                        + "（准星坐标为 0~1000 归一化值，基于最近一次截图的坐标空间）。";
            }
            for (int[] norm : markNorms) {
                markScreens.add(new int[]{markCtx.normToScreenX(norm[0]), markCtx.normToScreenY(norm[1])});
            }
        }

        // 5.6 ★ region 基准范围计算（局部放大图标注用：本区域对应"基准截图"的归一化范围；
        //     时序注意：必须在步骤 10 登记新基准之前取当前基准 ctx）
        int[] regionRange = null;
        if (region != null && region.isObject() && conversationId != null) {
            CaptureContext baseCtx = captureContextRegistry.get(conversationId);
            if (baseCtx != null) {
                regionRange = computeRegionNormRange(target, baseCtx);
            }
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

        // 7.6 ★ SoM 元素检测 + 编号徽标（Set-of-Mark：截屏+标准化后按物理区域检测；配置开启时生效；
        //     检测失败静默降级为空清单、绝不阻断截图主链。徽标画在网格之上、准星之下——
        //     准星/画中画/region 信息条保持最上层不被遮挡）
        List<DetectedElement> elements = List.of();
        if (config.getCapture().isElementDetection()) {
            elements = elementDetector.detect(target);
            if (!elements.isEmpty()) {
                image = appendElementBadges(image, elements, target);
            }
        }

        // 7.7 准星标记叠加（画在网格之上，坐标校验闭环；★ 多候选点按序标 A/B/C/D/E、五色轮转 + 画中画）
        String markNote = null;
        if (!markNorms.isEmpty()) {
            List<MarkSpec> specs = new ArrayList<>();
            List<String> drawnLabels = new ArrayList<>();
            List<String> missedLabels = new ArrayList<>();
            for (int i = 0; i < markNorms.size(); i++) {
                int[] norm = markNorms.get(i);
                char letter = markLetter(i);
                String label = letter + " (" + norm[0] + ", " + norm[1] + ")";
                int[] screenPos = markScreens.get(i);
                int[] px = mapPointToImage(target, image.getWidth(), image.getHeight(), screenPos[0], screenPos[1]);
                if (px != null) {
                    specs.add(new MarkSpec(px[0], px[1], label, markColor(i), letter));
                    drawnLabels.add(label);
                } else {
                    missedLabels.add(label);
                }
            }
            if (!specs.isEmpty()) {
                image = drawMarkSet(image, specs);
            }
            // M6 预检联动：登记校验记录（单点：绘制成功才登记；marks：全部候选点登记——模型对比选点后点击直接放行）
            if (verifyRegistry != null && conversationId != null) {
                if (multiMarkMode) {
                    for (int[] norm : markNorms) {
                        verifyRegistry.record(conversationId, norm[0], norm[1]);
                    }
                } else if (!specs.isEmpty()) {
                    verifyRegistry.record(conversationId, markNorms.get(0)[0], markNorms.get(0)[1]);
                }
            }
            markNote = buildMarkNote(multiMarkMode, drawnLabels, missedLabels);
        }

        // 7.8 ★ region 范围信息条（局部放大图标注"本区=基准 x∈[…] y∈[…]"，绘制在最上层——画中画 inset 之后）
        if (regionRange != null) {
            image = appendRegionRangeLabel(image, regionRange[0], regionRange[1], regionRange[2], regionRange[3]);
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
        log.info("桌面截图成功: conversationId={}, 物理={}x{}@({},{}), 标准={}x{}, 缩放down={}, up={}, marks={}, elements={}, fileAssetId={}, fileId={}",
                conversationId, target.width, target.height, target.x, target.y,
                standardWidth, standardHeight, scaledDown, scaledUp,
                markNorms.isEmpty() ? "-" : (multiMarkMode ? markNorms.size() + "个候选" : "单点"),
                elements.size(), asset.getId(), asset.getFileId());
        return buildSuccessText(asset, target, markNote, regionRange, elements);
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

        // 4. 网格 + 批量准星（画在最终图上，确保清晰可读；★ 与 mark 链路统一"字母+颜色+画中画"渲染）
        image = drawGrid(image);
        List<MarkSpec> specs = new ArrayList<>();
        for (int i = 0; i < normMarks.size(); i++) {
            int[] mark = normMarks.get(i);
            int screenX = ctx.normToScreenX(mark[0]);
            int screenY = ctx.normToScreenY(mark[1]);
            int[] px = mapPointToImage(target, image.getWidth(), image.getHeight(), screenX, screenY);
            if (px != null) {
                char letter = markLetter(i);
                specs.add(new MarkSpec(px[0], px[1],
                        letter + " (" + mark[0] + ", " + mark[1] + ")", markColor(i), letter));
            }
        }
        if (!specs.isEmpty()) {
            image = drawMarkSet(image, specs);
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
     * 计算 region 截取区域在"基准截图"归一化坐标空间中的范围（★ 新增：局部放大图基准范围标注用；纯函数，供测试）。
     *
     * <p>规则（与 {@link CaptureContext#normToScreenX(int)} 反变换对齐，右/下边界含端点）：</p>
     * <pre>
     * gx0 = clamp(round((clipped.x - base.physicalX) × 1000 / base.physicalWidth), 0, 1000)
     * gx1 = clamp(round((clipped.x + clipped.width - base.physicalX) × 1000 / base.physicalWidth), 0, 1000)
     * </pre>
     * <p>y 同理（gy0/gy1）；结果统一 clamp 到 [0, 1000]（防御基准变更/裁剪误差导致的越界）。</p>
     *
     * @param clipped region 请求裁剪后的物理矩形（{@link #resolveTarget} 返回值）
     * @param base    基准截图（最近一次截图）的坐标上下文
     * @return [gx0, gx1, gy0, gy1]（本区域在基准截图中的归一化范围）
     */
    static int[] computeRegionNormRange(Rectangle clipped, CaptureContext base) {
        int gx0 = clampNorm((int) Math.round((clipped.x - base.physicalX()) * 1000.0 / base.physicalWidth()));
        int gx1 = clampNorm((int) Math.round((clipped.x + clipped.width - base.physicalX()) * 1000.0 / base.physicalWidth()));
        int gy0 = clampNorm((int) Math.round((clipped.y - base.physicalY()) * 1000.0 / base.physicalHeight()));
        int gy1 = clampNorm((int) Math.round((clipped.y + clipped.height - base.physicalY()) * 1000.0 / base.physicalHeight()));
        return new int[]{gx0, gx1, gy0, gy1};
    }

    /** 归一化值 clamp 到 [0, 1000]（★ {@link #computeRegionNormRange} 辅助） */
    private static int clampNorm(int value) {
        return Math.max(0, Math.min(1000, value));
    }

    /**
     * 解析 mark 准星参数（0~1000 归一化坐标；截屏前校验，参数非法时抛
     * {@link IllegalArgumentException}，消息可直接返回给模型）。
     *
     * @return [x, y] 归一化坐标
     */
    private int[] parseMarkNode(JsonNode mark) {
        return parseMarkPoint(mark, "mark");
    }

    /**
     * 解析 marks 多候选点参数（★ 新增；1~5 个，每项 {x, y} 为 0~1000 归一化坐标；
     * 对比场景建议 2~5 个。截屏前校验，消息可直接返回给模型）。
     *
     * @return 候选点归一化坐标列表（按提交顺序）
     */
    private List<int[]> parseMarksNode(JsonNode marks) {
        if (!marks.isArray() || marks.isEmpty()) {
            throw new IllegalArgumentException("【错误类型】【参数错误】marks 需为非空数组（1~5 个候选点），"
                    + "每项 {x, y} 为 0~1000 归一化坐标（读最近截图的刻度值；对比场景建议 2~5 个）。");
        }
        if (marks.size() > MAX_MARK_COUNT) {
            throw new IllegalArgumentException("【错误类型】【参数错误】marks 最多 " + MAX_MARK_COUNT
                    + " 个候选点（收到 " + marks.size() + " 个）。请减少候选点后重试。");
        }
        List<int[]> result = new ArrayList<>(marks.size());
        for (int i = 0; i < marks.size(); i++) {
            JsonNode item = marks.get(i);
            if (!item.isObject()) {
                throw new IllegalArgumentException("【错误类型】【参数错误】marks 第 " + (i + 1)
                        + " 项需为对象 {x, y}（0~1000 归一化坐标）。");
            }
            result.add(parseMarkPoint(item, "marks 第 " + (i + 1) + " 项"));
        }
        return result;
    }

    /**
     * 解析单个准星点坐标（mark / marks 元素共用；★ 抽取自原 parseMarkNode，保持错误文案一致）。
     *
     * @param node 点对象 {x, y}
     * @param desc 点描述（错误文案定位用，如 "mark"、"marks 第 2 项"）
     * @return [x, y] 归一化坐标
     */
    private int[] parseMarkPoint(JsonNode node, String desc) {
        int mx = node.path("x").asInt(Integer.MIN_VALUE);
        int my = node.path("y").asInt(Integer.MIN_VALUE);
        if (mx == Integer.MIN_VALUE || my == Integer.MIN_VALUE || mx < 0 || mx > 1000 || my < 0 || my > 1000) {
            throw new IllegalArgumentException("【错误类型】【参数错误】" + desc + " 需为 0~1000 归一化坐标：请同时提供 x 与 y"
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

    /** 单步缩放（双线性插值 + 高质量渲染；scaleWithHint 复用版，★ 抽取公共实现） */
    private BufferedImage scaleOnce(BufferedImage src, int w, int h) {
        return scaleWithHint(src, w, h, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
    }

    /**
     * 单步放大（双三次插值 + 高质量渲染；region 放大镜与"画中画"inset 共用。★ 抽取公共实现）
     */
    private BufferedImage scaleUp(BufferedImage src, int w, int h) {
        return scaleWithHint(src, w, h, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
    }

    /**
     * 单步缩放公共实现（★ 新增：scaleDown/scaleUp/画中画 inset 放大共用，按需指定插值算法）。
     *
     * @param interpolation 插值算法（{@link RenderingHints#VALUE_INTERPOLATION_BILINEAR 双线性} /
     *                      {@link RenderingHints#VALUE_INTERPOLATION_BICUBIC 双三次}）
     */
    private BufferedImage scaleWithHint(BufferedImage src, int w, int h, Object interpolation) {
        BufferedImage dst = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = dst.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, interpolation);
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

    /** 准星标记颜色（洋红：与网格红线区分明显，不与任何常见 UI 配色混淆；单点 mark 与候选点 A 使用） */
    private static final Color MARK_COLOR = new Color(255, 0, 255);

    /** 准星描边色（白色描边保证在暗色/亮色背景上均可辨识） */
    private static final Color MARK_OUTLINE_COLOR = new Color(255, 255, 255);

    /** ★ 多候选准星颜色轮转（A 洋红 / B 青 / C 橙 / D 绿 / E 紫；保持白描边双线画法，任意背景可辨识） */
    private static final Color[] MARK_COLORS = {
            MARK_COLOR,                 // A：洋红（与单点 mark 样式一致）
            new Color(0, 255, 255),     // B：青
            new Color(255, 165, 0),     // C：橙
            new Color(0, 230, 0),       // D：绿
            new Color(160, 32, 240)     // E：紫
    };

    /** ★ marks 候选点数量上限（与颜色轮转数量一致：A~E） */
    private static final int MAX_MARK_COUNT = 5;

    // ==================== 画中画 inset（★ 新增：准星局部放大对比窗） ====================

    /** ★ 画中画裁剪半径上限（图面像素；实际值按 inset 尺寸自适应 ≈20~27px，即小窗边长约 40~54px） */
    private static final int INSET_CROP_HALF = 40;

    /** ★ 画中画目标放大倍数（2~4 范围；裁剪窗口边长 × 该倍数 ≈ 面板内容区边长） */
    private static final int INSET_CROP_ZOOM = 3;

    /** ★ 画中画面板最小尺寸（像素；随图自适应：取 min(宽,高)/6 后 clamp） */
    private static final int INSET_MIN_SIZE = 120;

    /** ★ 画中画面板最大尺寸（像素） */
    private static final int INSET_MAX_SIZE = 160;

    /** ★ 画中画面板堆叠间距（像素） */
    private static final int INSET_GAP = 8;

    /** ★ 画中画面板最大数量（超出时不绘制——主图仍有完整准星与标签） */
    private static final int INSET_MAX_COUNT = 3;

    /** ★ 画中画面板距图像右/下边缘的外边距（像素） */
    private static final int INSET_MARGIN = 4;

    /** ★ 画中画窗内"细线小十字"半臂长（像素；约 1px 线宽，不用粗圆圈、不带文字标签） */
    private static final int INSET_CROSS_ARM = 9;

    /** ★ 画中画面板底色（半透明：即使内容贴片边缘有像素缝隙也不露底） */
    private static final Color INSET_BG = new Color(0, 0, 0, 90);

    /** ★ 画中画面板边框色（1px） */
    private static final Color INSET_BORDER = new Color(255, 255, 255, 220);

    // ==================== 元素编号徽标（★ 新增：SoM Set-of-Mark） ====================

    /** ★ 元素编号徽标主色（青色 RGB(0,180,255)：避开网格红/准星洋红/信息条深红，不撞常见 UI 配色） */
    private static final Color BADGE_COLOR = new Color(0, 180, 255);

    /** ★ 元素编号徽标描边色（白色：双层画法保证在暗色/亮色背景上均可辨识，样式参照 drawMark） */
    private static final Color BADGE_OUTLINE_COLOR = new Color(255, 255, 255);

    /** ★ 元素编号徽标与元素矩形（首选位）的间距（像素） */
    private static final int BADGE_GAP = 2;

    /** ★ 元素徽标数量上限（防御；与检测脚本侧上限一致） */
    private static final int MAX_ELEMENT_BADGES = 40;

    /** ★ 元素清单名称最大显示长度（超出截断加省略号；防超长/异常名称撑爆返回文本） */
    private static final int MAX_ELEMENT_NAME_LEN = 30;

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

    // ==================== 准星标记（mark / marks：瞄准校验闭环 + 画中画） ====================

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
     * 批量绘制准星标记（★ 升级自原单点 drawMark）：白描边双线十字/圆圈 + 彩色坐标标签，画在网格之上；
     * 每个准星按序使用"字母+颜色"（A 洋红 / B 青 / C 橙 / D 绿 / E 紫），并在图像右下角附加
     * "画中画"放大窗（{@link #appendInsets}）。
     *
     * <p>标签位置自适应（右/下空间不足时翻转到左/上侧），避免出界；单点 mark、多点 marks 与
     * 预检校验图三条路径统一走本方法，保证观感一致。</p>
     */
    private BufferedImage drawMarkSet(BufferedImage src, List<MarkSpec> specs) {
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
            int fontSize = Math.max(12, Math.min(w, h) / 40);
            g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, fontSize));
            FontMetrics fm = g.getFontMetrics();
            for (MarkSpec spec : specs) {
                // 白色粗描边 → 彩色细主线（两层形成描边效果；颜色随候选点轮转）
                drawCrossCircle(g, spec.cx(), spec.cy(), arm, r, MARK_OUTLINE_COLOR, 4.5f);
                drawCrossCircle(g, spec.cx(), spec.cy(), arm, r, spec.color(), 2.0f);
                // 坐标标签（白底彩字，样式同网格刻度）
                drawMarkLabel(g, fm, spec.label(), spec.cx(), spec.cy(), r, spec.color(), w, h);
            }
            // 画中画 inset：从"未画准星"的底图裁剪放大（避免窗内出现主图粗准星），贴右下角纵向堆叠
            appendInsets(g, src, specs, w, h);
        } finally {
            g.dispose();
        }
        return dst;
    }

    /** 绘制准星坐标标签（白底彩字；★ 抽取自原 drawMark，右/下空间不足时翻转到左/上侧，避免出界） */
    private void drawMarkLabel(Graphics2D g, FontMetrics fm, String label,
                               int cx, int cy, int r, Color color, int w, int h) {
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
        g.setColor(color);
        g.drawString(label, lx + 1, ly + fm.getAscent());
    }

    // ==================== 画中画 inset（★ 新增：准星局部放大对比窗） ====================

    /**
     * 追加"画中画"面板（★ 新增）：每个准星以中心裁剪小窗 → 放大（约 {@link #INSET_CROP_ZOOM}×，双三次插值）
     * → 贴图像右下角纵向堆叠（从上到下按序 A/B/C，最多 {@link #INSET_MAX_COUNT} 个）。
     *
     * <p>面板尺寸随图自适应（{@link #INSET_MIN_SIZE}~{@link #INSET_MAX_SIZE}px）；高度不足时按可用空间
     * 减少面板数（至少 1 个）。面板内容取自"未画准星"的底图，窗内以细线小十字+中心小点标示准星位置。</p>
     */
    private void appendInsets(Graphics2D g, BufferedImage src, List<MarkSpec> specs, int w, int h) {
        if (specs.isEmpty()) {
            return;
        }
        // 画中画参数链（类内常量，不新增配置项）：面板尺寸随图自适应；
        // 裁剪半径按"放大后恰好填满面板"反推（≈3×），并以 INSET_CROP_HALF 兜底
        int insetSize = Math.max(INSET_MIN_SIZE, Math.min(INSET_MAX_SIZE, Math.min(w, h) / 6));
        int cropHalf = Math.max(20, Math.min(INSET_CROP_HALF,
                (int) Math.round(insetSize / (2.0 * INSET_CROP_ZOOM))));
        // 高度不足时按可用空间减少面板数（至少 1 个；最多 INSET_MAX_COUNT 个）
        int maxFit = Math.max(1, (h - 2 * INSET_MARGIN + INSET_GAP) / (insetSize + INSET_GAP));
        int count = Math.min(Math.min(specs.size(), INSET_MAX_COUNT), maxFit);
        int totalH = count * insetSize + (count - 1) * INSET_GAP;
        int px = w - INSET_MARGIN - insetSize;
        int py = h - INSET_MARGIN - totalH;
        for (int i = 0; i < count; i++) {
            g.drawImage(buildInsetPanel(src, specs.get(i), cropHalf, insetSize),
                    px, py + i * (insetSize + INSET_GAP), null);
        }
    }

    /**
     * 构建单个"画中画"面板（★ 新增）：裁剪（贴边 clamp）→ 双三次放大 → 细线小十字+中心小点 →
     * 半透明底 + 1px 边框 + 左上角字母角标。
     *
     * <p>准星在窗内按实际相对位置绘制（不必居中）；窗内不画粗圆圈、不带文字标签。</p>
     */
    private BufferedImage buildInsetPanel(BufferedImage src, MarkSpec spec, int cropHalf, int insetSize) {
        int w = src.getWidth();
        int h = src.getHeight();
        // 1) 裁剪小窗：以准星为中心，贴边 clamp（窗口整体保持在图内，准星偏一侧时按实际位置绘制）
        int winSize = Math.min(2 * cropHalf, Math.min(w, h));
        int x0 = Math.max(0, Math.min(spec.cx() - winSize / 2, w - winSize));
        int y0 = Math.max(0, Math.min(spec.cy() - winSize / 2, h - winSize));
        BufferedImage crop = src.getSubimage(x0, y0, winSize, winSize);
        // 2) 放大（双三次插值，复用 scaleUp 同源公共实现；内容区预留 1px 边框）
        int content = insetSize - 2;
        BufferedImage zoomed = scaleWithHint(crop, content, content, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        // 3) 窗内细节标记：细线小十字 + 中心小点（约 1px 线，不用粗圆圈、不带文字标签）
        Graphics2D gz = zoomed.createGraphics();
        try {
            gz.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            int mx = Math.max(0, Math.min(content - 1,
                    (int) Math.round((spec.cx() - x0) * (double) content / winSize)));
            int my = Math.max(0, Math.min(content - 1,
                    (int) Math.round((spec.cy() - y0) * (double) content / winSize)));
            gz.setColor(spec.color());
            gz.setStroke(new BasicStroke(1f));
            gz.drawLine(mx - INSET_CROSS_ARM, my, mx + INSET_CROSS_ARM, my);
            gz.drawLine(mx, my - INSET_CROSS_ARM, mx, my + INSET_CROSS_ARM);
            gz.fillOval(mx - 2, my - 2, 4, 4);     // 中心小点（直径 4px）
        } finally {
            gz.dispose();
        }
        // 4) 面板组装：半透明底 → 内容 → 1px 边框 → 左上角字母角标（白底彩字，与主图标签同风格）
        BufferedImage panel = new BufferedImage(insetSize, insetSize, BufferedImage.TYPE_INT_ARGB);
        Graphics2D gp = panel.createGraphics();
        try {
            gp.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            gp.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            gp.setColor(INSET_BG);
            gp.fillRect(0, 0, insetSize, insetSize);
            gp.drawImage(zoomed, 1, 1, null);
            gp.setColor(INSET_BORDER);
            gp.setStroke(new BasicStroke(1f));
            gp.drawRect(0, 0, insetSize - 1, insetSize - 1);
            int fs = Math.max(10, insetSize / 12);
            gp.setFont(new Font(Font.SANS_SERIF, Font.BOLD, fs));
            FontMetrics fm = gp.getFontMetrics();
            String letter = String.valueOf(spec.letter());
            gp.setColor(GRID_LABEL_BG);
            gp.fillRect(3, 3, fm.stringWidth(letter) + 4, fm.getHeight() + 2);
            gp.setColor(spec.color());
            gp.drawString(letter, 4, 3 + fm.getAscent());
        } finally {
            gp.dispose();
        }
        return panel;
    }

    /** 准星序号 → 字母标识（A/B/C…；超过 26 个时循环，防极端批量越界） */
    private static char markLetter(int index) {
        return (char) ('A' + (index % 26));
    }

    /** 准星序号 → 颜色（五色轮转：洋红/青/橙/绿/紫） */
    private static Color markColor(int index) {
        return MARK_COLORS[index % MARK_COLORS.length];
    }

    /** 准星绘制规格（主图准星 + 画中画面板共用；★ 新增） */
    private record MarkSpec(int cx, int cy, String label, Color color, char letter) {
    }

    /** 绘制十字 + 圆圈（描边层与主线层共用；出画布部分由 Graphics2D 自动裁剪） */
    private void drawCrossCircle(Graphics2D g, int cx, int cy, int arm, int r, Color color, float strokeW) {
        g.setColor(color);
        g.setStroke(new BasicStroke(strokeW, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.drawLine(cx - arm, cy, cx + arm, cy);
        g.drawLine(cx, cy - arm, cx, cy + arm);
        g.drawOval(cx - r, cy - r, 2 * r, 2 * r);
    }

    // ==================== region 基准范围标注（★ 新增：局部放大图坐标空间提示） ====================

    /** ★ region 范围信息条字号系数（相对网格标签字号略小；辅助信息不与刻度读数抢视觉） */
    private static final double REGION_RANGE_FONT_SCALE = 0.9;

    /**
     * 在图像左下角叠加"本区=基准 x∈[gx0,gx1] y∈[gy0,gy1]"信息条（★ 新增）。
     *
     * <p>仅 region 局部放大图调用（非 region 不绘制）；白底红字（配色同网格刻度标签），
     * 绘制在最上层（画中画 inset 之后），位置紧贴左下角——避开右下角 inset 区与左上刻度标签。
     * 用途：region 图刻度为"本区域相对"0~1000，标注本区域对应"基准截图（最近一次）"的
     * 归一化范围，避免模型在多图/多坐标空间之间混淆。</p>
     */
    private BufferedImage appendRegionRangeLabel(BufferedImage src, int gx0, int gx1, int gy0, int gy1) {
        int w = src.getWidth();
        int h = src.getHeight();
        BufferedImage dst = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = dst.createGraphics();
        try {
            g.drawImage(src, 0, 0, null);
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            String text = "本区=基准 x∈[" + gx0 + "," + gx1 + "] y∈[" + gy0 + "," + gy1 + "]";
            int fontSize = Math.max(12, (int) Math.round(Math.min(w, h) / 40.0 * REGION_RANGE_FONT_SCALE));
            g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, fontSize));
            FontMetrics fm = g.getFontMetrics();
            int textW = fm.stringWidth(text);
            int textH = fm.getHeight();
            int x = 4;                                  // 左下角（避开右下角 inset 区与左上刻度标签）
            int y = Math.max(2, h - 4 - textH);
            g.setColor(GRID_LABEL_BG);
            g.fillRect(x - 1, y - 1, textW + 4, textH + 2);
            g.setColor(GRID_LABEL_FG);
            g.drawString(text, x + 1, y + fm.getAscent());
        } finally {
            g.dispose();
        }
        return dst;
    }

    // ==================== 元素编号徽标（★ 新增：SoM Set-of-Mark 编号渲染） ====================

    /**
     * 在图像上叠加元素编号徽标（★ 新增）：每个元素在"左上角"绘制"#N"徽标（N 与【元素清单】编号一致），
     * 白描边 + 青色主体双层画法（样式参照 drawMark 的"描边层 + 主体层"）。
     *
     * <p>位置策略：首选"元素左上角上方外侧"（不遮元素内容）；贴图像顶边时改为元素内侧左上角避让；
     * 徽标整体 clamp 到图像范围内（贴右/下边时向内避让，防出界）。元素定位先裁剪到本次截图区域
     * （元素可能部分超出）再按相对位置映射到图面（同 {@link #mapPointToImage} 思路，纯函数供验证）。</p>
     *
     * <p>渲染层级：调用方在"网格之后、准星之前"调用——准星/画中画/region 信息条保持最上层不被遮挡
     * （徽标不遮关键内容）。元素列表为空/参数非法时原图返回（不产生副作用）。</p>
     */
    static BufferedImage appendElementBadges(BufferedImage src, List<DetectedElement> elements, Rectangle target) {
        if (src == null || elements == null || elements.isEmpty() || target == null
                || target.width <= 0 || target.height <= 0) {
            return src;
        }
        int w = src.getWidth();
        int h = src.getHeight();
        BufferedImage dst = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = dst.createGraphics();
        try {
            g.drawImage(src, 0, 0, null);
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            int fontSize = Math.max(12, Math.min(w, h) / 40);
            g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, fontSize));
            FontMetrics fm = g.getFontMetrics();
            int index = 0;
            for (DetectedElement element : elements) {
                index++;
                if (index > MAX_ELEMENT_BADGES) {
                    break;
                }
                // 元素矩形裁剪到 target → 左上角按相对位置映射到图面（clamp 到图像内；元素与 target 保证相交）
                int ex = Math.max(element.x(), target.x);
                int ey = Math.max(element.y(), target.y);
                int x0 = Math.max(0, Math.min(w - 1,
                        (int) Math.round((ex - target.x) * (double) w / target.width)));
                int y0 = Math.max(0, Math.min(h - 1,
                        (int) Math.round((ey - target.y) * (double) h / target.height)));
                String text = "#" + index;
                int bw = fm.stringWidth(text);
                int bh = fm.getHeight();
                // 首选：元素左上角上方外侧；贴顶边（上方空间不足）→ 内侧避让（元素内部左上角）
                int bx = x0;
                int by = y0 - bh - BADGE_GAP;
                if (by < 2) {
                    bx = x0 + BADGE_GAP;
                    by = y0 + BADGE_GAP;
                }
                // 整体 clamp 到图像内（贴右/下边时向内避让，防出界）
                bx = Math.max(2, Math.min(bx, w - bw - 2));
                by = Math.max(2, Math.min(by, h - bh - 2));
                drawBadgeText(g, fm, text, bx, by);
            }
        } finally {
            g.dispose();
        }
        return dst;
    }

    /**
     * 绘制编号徽标文字（★ 新增：白描边 → 青色填充双层画法；描边宽度随字号自适应）。
     *
     * <p>文字轮廓经 {@link GlyphVector#getOutline} 转为 Shape：先以白色粗笔迹画"描边层"、
     * 再以青色填充"主体层"——任意底色（含网格红线/界面彩色背景）上均可辨识，
     * 观感与 drawMark 的"白描边 + 彩色主体"两层结构一致。描边宽度按字号比例（1.5~4px），
     * 小字号时不糊住笔画主体、大字号时保持醒目。</p>
     */
    private static void drawBadgeText(Graphics2D g, FontMetrics fm, String text, int x, int y) {
        GlyphVector gv = g.getFont().createGlyphVector(g.getFontRenderContext(), text);
        Shape outline = gv.getOutline(x, y + fm.getAscent());
        float halo = Math.max(1.5f, g.getFont().getSize() / 8f);
        g.setStroke(new BasicStroke(halo, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.setColor(BADGE_OUTLINE_COLOR);
        g.draw(outline);
        g.setColor(BADGE_COLOR);
        g.fill(outline);
    }

    // ==================== 返回文本 ====================

    /**
     * 构建准星绘制结果提示（★ 新增：区分"单点校验"与"多候选点对比"两种文案）。
     *
     * @param multiMarkMode marks 多点模式（true=多候选点对比）
     * @param drawnLabels   已绘制点的标签列表（如 "A (450, 320)"）
     * @param missedLabels  未绘制点（不在本次截图区域内）的标签列表
     */
    private String buildMarkNote(boolean multiMarkMode, List<String> drawnLabels, List<String> missedLabels) {
        StringBuilder sb = new StringBuilder();
        if (multiMarkMode) {
            if (!drawnLabels.isEmpty()) {
                sb.append("· 多准星对比：已绘制 ").append(drawnLabels.size()).append(" 个候选准星（");
                for (int i = 0; i < drawnLabels.size(); i++) {
                    if (i > 0) {
                        sb.append("/");
                    }
                    sb.append(drawnLabels.get(i).charAt(0));
                }
                sb.append("）：").append(String.join("、", drawnLabels))
                        .append("——对比后选用最佳坐标执行；候选点已登记校验，±")
                        .append(DesktopVerifyRegistry.TOLERANCE)
                        .append(" 容差内直接放行。");
            }
            if (!missedLabels.isEmpty()) {
                if (sb.length() > 0) {
                    sb.append("\n");
                }
                sb.append("【提示】以下候选点不在本次截图区域内，未绘制准星：")
                        .append(String.join("、", missedLabels)).append("；请核对坐标或先重新截图。");
            }
        } else {
            if (!drawnLabels.isEmpty()) {
                sb.append("· 准星标记：已在图上绘制 ").append(drawnLabels.get(0))
                        .append(" 位置的洋红准星（十字+圆圈+坐标标签；右下角附画中画放大窗）——"
                                + "请目测准星与实际目标位置的偏差：准星正中即可放心执行点击；有偏差先修正坐标。");
            }
            if (!missedLabels.isEmpty()) {
                sb.append("【提示】mark 坐标 ").append(missedLabels.get(0))
                        .append(" 对应的位置不在本次截图区域内，未绘制准星；"
                                + "如需校验该点请改用包含该位置的 region，或先无参截图重置基准。");
            }
        }
        return sb.toString();
    }

    /**
     * 构建工具返回文本（模型据此得知图片来源、坐标协议与使用方式）
     *
     * <p>不携带"图片像素尺寸/缩放比/文件大小"等元信息（v2 协议下与模型坐标操作无关，
     * 且可能诱导"像素思维"）——坐标一律以 0~1000 归一化协议表达。</p>
     *
     * @param markNote    mark/marks 准星绘制结果提示/警告（可空；传了准星参数时由主流程生成）
     * @param regionRange region 局部放大图对应"基准截图"的归一化范围 [gx0,gx1,gy0,gy1]；
     *                    非 region 截图传 null（不输出"【坐标空间】"行）
     * @param elements    ★ 本次检测到的元素列表（可空/空列表：不输出【元素清单】段）
     */
    private String buildSuccessText(FileAssetVO asset, Rectangle target, String markNote, int[] regionRange,
                                    List<DetectedElement> elements) {
        StringBuilder sb = new StringBuilder();
        sb.append("桌面截图已成功上传并注入上下文（下一轮即可直接看到本图）。\n");
        sb.append("· 文件：file_asset_id=").append(asset.getId())
                .append(", file_id=").append(asset.getFileId());
        sb.append("\n· 截图区域（物理屏幕）：x=").append(target.x).append(", y=").append(target.y)
                .append(", ").append(target.width).append("x").append(target.height);
        sb.append("\n· 坐标协议：本图叠加了 10×10 网格与四边三级刻度（每 25/50/100，0~1000；0=最左/最上、1000=最右/最下）；"
                + "desktop_control 与 region 直接使用归一化坐标（读目标位置对应的刻度值），只传 0~1000 的数值"
                + "、不要传像素值；系统自动换算为屏幕物理坐标，无需自行折算（图片被进一步缩放/压缩也不影响）。");
        // ★ region 局部放大图：补充"坐标空间"说明（本图刻度=本区域相对坐标，与基准全图刻度不同）
        if (regionRange != null) {
            sb.append("\n· 【坐标空间】本图为局部放大：网格刻度 0~1000 为本区域相对坐标；")
                    .append("本区域=基准截图（最近一次）归一化范围 x∈[").append(regionRange[0]).append(",").append(regionRange[1])
                    .append("]、y∈[").append(regionRange[2]).append(",").append(regionRange[3])
                    .append("]；执行动作时直接使用本图刻度值（系统自动换算）。");
        }
        // ★ 元素清单（SoM：非空时输出；#编号与图上徽标一一对应）
        String elementListText = buildElementListText(elements, target);
        if (!elementListText.isEmpty()) {
            sb.append(elementListText);
        }
        if (markNote != null && !markNote.isEmpty()) {
            sb.append("\n").append(markNote);
        }
        sb.append("\n如需放大查看局部细节，可再次调用 screen_capture 并传入基于本图归一化坐标（0~1000）的 region；"
                + "执行点击/拖拽前建议先用 mark={x,y} 校验目标坐标（准星居中再执行），"
                + "拿不准点位时也可用 marks=[{x,y},…] 一次对比 2~5 个候选准星（各带画中画放大窗）。");
        return sb.toString();
    }

    /**
     * 构建【元素清单】段文本（★ 新增：SoM 编号 → 机器计算坐标；纯函数，供验证）。
     *
     * <p>格式：{@code #N「名称」(类型) → (x, y)}；x/y 为元素中心点在本图归一化坐标空间的 round 值
     * （与 {@link CaptureContext#normToScreenX(int)} 反变换对齐，clamp 到 0~1000）。
     * 编号与图上"#N"徽标一一对应——模型按编号选点，消除目测刻度误差。</p>
     *
     * @param elements 元素列表（空/未检测时返回空字符串——不输出本段）
     * @param target   本次截图物理区域（归一化换算基准）
     */
    static String buildElementListText(List<DetectedElement> elements, Rectangle target) {
        if (elements == null || elements.isEmpty() || target == null
                || target.width <= 0 || target.height <= 0) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("\n· 【元素清单】本次截图自动检测到 ").append(elements.size())
                .append(" 个可交互元素（#编号与图上徽标一致；坐标为机器计算、比目测更准，点击优先使用）：");
        int index = 0;
        for (DetectedElement element : elements) {
            index++;
            int nx = clampNorm((int) Math.round((element.centerX() - target.x) * 1000.0 / target.width));
            int ny = clampNorm((int) Math.round((element.centerY() - target.y) * 1000.0 / target.height));
            sb.append("\n  #").append(index).append("「").append(formatElementName(element.name())).append("」(")
                    .append(element.type() == null || element.type().isBlank() ? "Unknown" : element.type())
                    .append(") → (").append(nx).append(", ").append(ny).append(")");
        }
        sb.append("\n  提示：清单坐标为机器计算（比目测更准），点击优先使用；未在清单中的目标仍用网格刻度读数。");
        return sb.toString();
    }

    /** 元素名称规范化（★ 新增：空白折叠 + 截断；空名显示 "-"；防超长/多行名称破坏清单排版） */
    private static String formatElementName(String name) {
        if (name == null || name.isBlank()) {
            return "-";
        }
        String cleaned = name.replaceAll("\\s+", " ").trim();
        if (cleaned.length() > MAX_ELEMENT_NAME_LEN) {
            cleaned = cleaned.substring(0, MAX_ELEMENT_NAME_LEN) + "…";
        }
        return cleaned;
    }

    /** 供测试/诊断使用：当前会话已被限流的最近上限（空列表表示不限） */
    List<Long> snapshotTimestamps(Long conversationId) {
        Deque<Long> deque = captureTimestamps.get(conversationId);
        return deque == null ? List.of() : List.copyOf(deque);
    }
}
