package com.example.agentdeepseek.tool.desktop;

/**
 * 最近一次桌面截图的坐标映射上下文（会话级，M5 截图工具写入 / M6 键鼠工具读取）。
 *
 * <p>坐标空间关系（v2 归一化协议）：截图经"分辨率标准化"缩放（长边 ≤ max-long-edge）上传后，
 * 视觉服务端还可能对图片二次压缩——模型对"绝对像素"的感知不可靠。因此对外统一采用
 * <b>0~1000 归一化坐标</b>（相对位置）：模型读截图上的网格刻度给出归一化坐标，
 * 系统按物理尺寸直接换算，与所有中间缩放层解耦。</p>
 *
 * <p>换算规则（v2，全屏与局部区域统一）：</p>
 * <pre>
 * screenX = physicalX + round(normX / 1000 × physicalWidth)
 * screenY = physicalY + round(normY / 1000 × physicalHeight)
 * </pre>
 *
 * <p>兼容保留（v1）：{@link #toScreenX(int)}/{@link #toScreenY(int)} 提供"标准化图像素空间"
 * 换算（与 v2 同构，供历史诊断用）。</p>
 *
 * @param physicalX        截取区域左上角 X（物理屏幕坐标系；全屏时为 0）
 * @param physicalY        截取区域左上角 Y（物理屏幕坐标系；全屏时为 0）
 * @param physicalWidth    截取区域宽（物理像素）
 * @param physicalHeight   截取区域高（物理像素）
 * @param standardWidth    标准化图像宽（模型所见像素）
 * @param standardHeight   标准化图像高（模型所见像素）
 * @param fileId           截图关联的远端 file_id（展示/排查用，可空）
 * @param capturedAtEpochMs 截图时间戳（毫秒；过期判定用）
 */
public record CaptureContext(
        int physicalX,
        int physicalY,
        int physicalWidth,
        int physicalHeight,
        int standardWidth,
        int standardHeight,
        String fileId,
        long capturedAtEpochMs
) {

    /**
     * 标准化图像 X 坐标 → 物理屏幕 X 坐标（M6 desktop_control 反变换）
     */
    public int toScreenX(int imageX) {
        return physicalX + (int) Math.round(imageX * (double) physicalWidth / standardWidth);
    }

    /**
     * 标准化图像 Y 坐标 → 物理屏幕 Y 坐标（M6 desktop_control 反变换）
     */
    public int toScreenY(int imageY) {
        return physicalY + (int) Math.round(imageY * (double) physicalHeight / standardHeight);
    }

    /**
     * 归一化坐标（0~1000）→ 物理屏幕 X 坐标（v2 协议：模型读网格刻度给出的横向坐标）
     *
     * <p>normX=0 → 区域左边界（physicalX）；normX=1000 → 区域右边界
     * （physicalX + physicalWidth，恰好为右边界外侧数学点）。输入合法性（0~1000）
     * 由调用方校验。</p>
     */
    public int normToScreenX(int normX) {
        return physicalX + (int) Math.round(normX * (double) physicalWidth / 1000.0);
    }

    /**
     * 归一化坐标（0~1000）→ 物理屏幕 Y 坐标（v2 协议：模型读网格刻度给出的纵向坐标）
     *
     * <p>normY=0 → 区域上边界（physicalY）；normY=1000 → 区域下边界
     * （physicalY + physicalHeight）。输入合法性（0~1000）由调用方校验。</p>
     */
    public int normToScreenY(int normY) {
        return physicalY + (int) Math.round(normY * (double) physicalHeight / 1000.0);
    }

    /**
     * 标准化图像 → 物理屏幕的 X 缩放比（物理/标准；≈1.0 表示未缩放；展示用）
     */
    public double scaleX() {
        return (double) physicalWidth / standardWidth;
    }

    /**
     * 标准化图像 → 物理屏幕的 Y 缩放比（物理/标准；≈1.0 表示未缩放；展示用）
     */
    public double scaleY() {
        return (double) physicalHeight / standardHeight;
    }
}
