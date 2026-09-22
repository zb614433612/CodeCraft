package com.example.agentdeepseek.tool.desktop;

import java.awt.image.BufferedImage;

/**
 * 点击命中回执支持（★ 新增：mouse_click 前后帧差分判定）。
 *
 * <p>用途：点击后原本依赖人工截图验证；本工具对比"点击前/点击后"两帧同尺寸画面，
 * 给出"检测到界面变化 / 未检测到明显变化"的自动回执（由 desktop_control 的 note 机制返回）。</p>
 *
 * <p>算法（与误报权衡）：逐像素计算 RGB 三通道差绝对值之和，大于 {@link #PIXEL_DIFF_THRESHOLD}
 * 记 1 个差异像素；差异像素占比大于 {@link #DIFF_RATIO_THRESHOLD} 判"检测到变化"。
 * 阈值不可过低——桌面时钟跳动、动画/光标闪烁可能造成少量像素变化（误报）；
 * 0.5% 的占比阈值可滤除这类噪声，同时对"弹窗/菜单/页面切换"等大面积真实反馈保持敏感。</p>
 *
 * <p>纯静态工具（无状态、不依赖 Spring 上下文），便于独立验证。</p>
 */
public final class ClickFeedbackSupport {

    /**
     * 单像素差异阈值（RGB 三通道差绝对值之和；> 该值记 1 个差异像素）。
     *
     * <p>60 为经验值：抗轻微压缩噪声/亚像素渲染抖动，又对"局部 UI 变化"敏感；
     * 调低会放大时钟/动画误报，调高会漏检浅色小面积反馈。</p>
     */
    private static final int PIXEL_DIFF_THRESHOLD = 60;

    /**
     * 差异像素占比阈值（0.5%）：超过判"检测到变化"。
     *
     * <p>目标区域中"时钟秒位跳动/光标闪烁"等噪声通常远低于 0.5%；
     * 而弹窗、菜单、按钮按下视觉反馈等真实变化通常远超该比例。</p>
     */
    private static final double DIFF_RATIO_THRESHOLD = 0.005;

    private ClickFeedbackSupport() {
    }

    /**
     * 对比两帧同尺寸画面，判定"是否有明显差异"（★ 核心差分）。
     *
     * <p>实现说明：经 {@code getRGB(0, 0, w, h, null, 0, w)} 批量取样为 int 数组后逐元素比较，
     * 避免逐像素 {@code getRGB(x, y)} 的调用开销（全屏帧可 >200 万像素）。</p>
     *
     * @param before 点击前基准帧
     * @param after  点击后对比帧（尺寸须与 before 一致；分辨率突变时返回 null = 跳过判定）
     * @return 判定结果（差异像素数/占比/是否变化）；输入为空或尺寸不一致时返回 null
     */
    public static Result compare(BufferedImage before, BufferedImage after) {
        if (before == null || after == null) {
            return null;
        }
        int w = before.getWidth();
        int h = before.getHeight();
        if (w <= 0 || h <= 0 || after.getWidth() != w || after.getHeight() != h) {
            return null;
        }
        int[] pixelsBefore = before.getRGB(0, 0, w, h, null, 0, w);
        int[] pixelsAfter = after.getRGB(0, 0, w, h, null, 0, w);
        int total = w * h;
        int diffPixels = 0;
        for (int i = 0; i < total; i++) {
            int c1 = pixelsBefore[i];
            int c2 = pixelsAfter[i];
            int dr = Math.abs(((c1 >> 16) & 0xFF) - ((c2 >> 16) & 0xFF));
            int dg = Math.abs(((c1 >> 8) & 0xFF) - ((c2 >> 8) & 0xFF));
            int db = Math.abs((c1 & 0xFF) - (c2 & 0xFF));
            if (dr + dg + db > PIXEL_DIFF_THRESHOLD) {
                diffPixels++;
            }
        }
        double ratio = (double) diffPixels / total;
        return new Result(diffPixels, total, ratio, ratio > DIFF_RATIO_THRESHOLD);
    }

    /**
     * 差分判定结果。
     *
     * @param diffPixels  差异像素数（RGB 差和大于单像素阈值的像素）
     * @param totalPixels 总像素数
     * @param diffRatio   差异像素占比（0.0~1.0）
     * @param changed     是否判定"检测到变化"（占比大于 0.5%）
     */
    public record Result(int diffPixels, int totalPixels, double diffRatio, boolean changed) {
    }
}
