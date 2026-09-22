package com.example.agentdeepseek.tool.desktop;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.awt.Rectangle;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * Windows UIAutomation 元素检测器（★ 新增：SoM 截图编号数据源）。
 *
 * <p>实现方式（零新依赖）：内嵌 PowerShell 脚本 {@value #SCRIPT_RESOURCE}（首次调用时释放到临时文件并缓存）
 * → {@link ProcessBuilder} 执行（不经 shell）：
 * {@code powershell -NoProfile -ExecutionPolicy Bypass -File <script> -Region "x,y,w,h" -OutFile <temp.json>}
 * → waitFor 超时 {@value #TIMEOUT_MS} 毫秒（超时强杀降级）→ Jackson 解析 JSON 文件（stdout 编码不可靠，POC 结论）。</p>
 *
 * <p>脚本侧与 POC 实测对齐：SetProcessDpiAwareness(2)（坐标=物理像素，与截图链一致）
 * → ControlViewCondition 枚举（比 TrueCondition 快一倍、集合相同）→ 过滤（rect 非空、宽高 ≥8、
 * 非离屏、<b>与目标区域相交</b>——剔除托盘窗口 ≈(-32000) 污染）→（11 种交互类型 ∪ 名称非空）
 * → 相同 rect 去重 → 有名优先稳定排序 → 上限 {@value #MAX_ELEMENTS} → JSON 数组写文件（无 BOM UTF-8）。</p>
 *
 * <p>降级铁律：非 Windows / 脚本释放失败 / 进程启动失败 / 超时 / 非零退出 / 文件读取或解析失败 /
 * 任何异常 → 空列表 + debug 日志，绝不抛异常、绝不阻断截图主链。</p>
 */
@Slf4j
@Component
public class UiaElementDetector implements DesktopElementDetector {

    /** 内嵌脚本资源路径 */
    private static final String SCRIPT_RESOURCE = "/scripts/uia-dump.ps1";

    /** 脚本执行超时（毫秒；POC 实测全程 ≈200ms，5s 为余量上限） */
    private static final long TIMEOUT_MS = 5000;

    /** 单次检测元素上限（与脚本侧一致；超出截断） */
    static final int MAX_ELEMENTS = 40;

    private final ObjectMapper objectMapper;

    /** 已释放脚本的缓存路径（首次调用释放；JVM 生命周期内复用） */
    private volatile Path cachedScriptPath;

    public UiaElementDetector(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public List<DetectedElement> detect(Rectangle physicalRegion) {
        if (!isWindows() || physicalRegion == null
                || physicalRegion.width <= 0 || physicalRegion.height <= 0) {
            return List.of();
        }
        Path outFile = null;
        try {
            Path script = ensureScriptFile();
            outFile = Files.createTempFile("codecraft-uia-out-", ".json");
            List<String> command = List.of(
                    "powershell", "-NoProfile", "-ExecutionPolicy", "Bypass",
                    "-File", script.toString(),
                    "-Region", physicalRegion.x + "," + physicalRegion.y
                            + "," + physicalRegion.width + "," + physicalRegion.height,
                    "-OutFile", outFile.toString());
            ProcessBuilder pb = new ProcessBuilder(command);
            pb.redirectOutput(ProcessBuilder.Redirect.DISCARD);
            pb.redirectError(ProcessBuilder.Redirect.DISCARD);
            Process process = pb.start();
            boolean finished;
            try {
                finished = process.waitFor(TIMEOUT_MS, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                process.destroyForcibly();
                log.debug("UIA 元素检测被中断（降级为空清单）");
                return List.of();
            }
            if (!finished) {
                process.destroyForcibly();
                log.debug("UIA 元素检测超时（{}ms；已终止，降级为空清单）", TIMEOUT_MS);
                return List.of();
            }
            if (process.exitValue() != 0) {
                log.debug("UIA 元素检测脚本退出码={}（降级为空清单）", process.exitValue());
                return List.of();
            }
            if (!Files.isRegularFile(outFile) || Files.size(outFile) == 0) {
                return List.of();
            }
            JsonNode root = objectMapper.readTree(Files.readAllBytes(outFile));
            List<DetectedElement> elements = parseElements(root);
            log.debug("UIA 元素检测完成: region={},{},{},{} physical, elements={}",
                    physicalRegion.x, physicalRegion.y, physicalRegion.width, physicalRegion.height, elements.size());
            return elements;
        } catch (Exception e) {
            log.debug("UIA 元素检测失败（降级为空清单）: {}", e.getMessage());
            return List.of();
        } finally {
            if (outFile != null) {
                try {
                    Files.deleteIfExists(outFile);
                } catch (Exception ignored) {
                    // 临时文件清理失败不影响主链
                }
            }
        }
    }

    /**
     * 解析脚本输出的 JSON（★ 纯函数，供验证）：
     * 兼容标准"数组"与 PowerShell 单元素退化时的"对象"形态；空/null/非法结构 → 空列表。
     *
     * @param root Jackson 解析出的根节点（可为 null）
     * @return 元素列表（缺失关键字段或尺寸非正的条目跳过；上限 {@link #MAX_ELEMENTS} 防御截断）
     */
    static List<DetectedElement> parseElements(JsonNode root) {
        if (root == null || root.isNull() || root.isMissingNode()) {
            return List.of();
        }
        List<DetectedElement> result = new ArrayList<>();
        if (root.isArray()) {
            for (JsonNode node : root) {
                DetectedElement element = parseOne(node);
                if (element != null) {
                    result.add(element);
                }
                if (result.size() >= MAX_ELEMENTS) {
                    break;
                }
            }
        } else if (root.isObject()) {
            DetectedElement element = parseOne(root);
            if (element != null) {
                result.add(element);
            }
        }
        return result;
    }

    /** 解析单个元素节点（缺失/非法跳过；尺寸非正视为无效——脚本侧已过滤，此处为防御） */
    private static DetectedElement parseOne(JsonNode node) {
        if (node == null || !node.isObject()) {
            return null;
        }
        int width = node.path("w").asInt(0);
        int height = node.path("h").asInt(0);
        if (width <= 0 || height <= 0) {
            return null;
        }
        return new DetectedElement(
                node.path("name").asText(""),
                node.path("type").asText(""),
                node.path("x").asInt(0),
                node.path("y").asInt(0),
                width,
                height);
    }

    /**
     * 确保脚本已释放到临时文件（首次调用释放；缓存路径，后续复用）。
     * 释放失败（资源缺失/临时目录不可写）抛异常，由 {@link #detect} 统一降级。
     */
    private Path ensureScriptFile() throws Exception {
        Path cached = cachedScriptPath;
        if (cached != null && Files.isRegularFile(cached)) {
            return cached;
        }
        synchronized (this) {
            if (cachedScriptPath != null && Files.isRegularFile(cachedScriptPath)) {
                return cachedScriptPath;
            }
            try (InputStream in = UiaElementDetector.class.getResourceAsStream(SCRIPT_RESOURCE)) {
                if (in == null) {
                    throw new IllegalStateException("内嵌脚本资源缺失: " + SCRIPT_RESOURCE);
                }
                Path script = Files.createTempFile("codecraft-uia-", ".ps1");
                Files.write(script, in.readAllBytes());
                script.toFile().deleteOnExit();
                cachedScriptPath = script;
                log.debug("UIA 检测脚本已释放: {}", script);
                return script;
            }
        }
    }

    /** 当前系统是否为 Windows（非 Windows 直接空列表——检测仅在 Windows 生效） */
    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }
}
