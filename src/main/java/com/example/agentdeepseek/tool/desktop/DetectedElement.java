package com.example.agentdeepseek.tool.desktop;

/**
 * 检测到的 UI 元素（★ 新增：SoM 编号元素；物理像素坐标系，与截图链一致）。
 *
 * <p>由 {@link DesktopElementDetector} 产出，供 screen_capture 绘制"#N"编号徽标并在返回文本中
 * 输出【元素清单】（编号 → 名称/类型/中心点归一化坐标）。</p>
 *
 * @param name   元素名称（UIA Name；中文正常；可能为空字符串——如无名输入框）
 * @param type   控件类型短名（UIA ControlType，如 Button / Pane / Edit / Window）
 * @param x      左上角 X（物理像素）
 * @param y      左上角 Y（物理像素）
 * @param width  宽度（物理像素）
 * @param height 高度（物理像素）
 */
public record DetectedElement(String name, String type, int x, int y, int width, int height) {

    /** 中心点 X（物理像素；点击优先使用中心点，容错率最高） */
    public int centerX() {
        return (int) Math.round(x + width / 2.0);
    }

    /** 中心点 Y（物理像素；点击优先使用中心点，容错率最高） */
    public int centerY() {
        return (int) Math.round(y + height / 2.0);
    }
}
