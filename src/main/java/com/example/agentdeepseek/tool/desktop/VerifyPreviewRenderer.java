package com.example.agentdeepseek.tool.desktop;

import java.util.List;

/**
 * 准星预检图渲染器（M6 预检闸门）：对当前会话坐标基准区域重新截图，
 * 叠加网格与准星标记，上传并登记 user 消息注入。
 *
 * <p>由 screen_capture 工具实现（复用其截图/网格/准星链路）；desktop_control 的预检闸门
 * 在"点击/拖拽坐标未经准星校验"时调用——生成校验图（不执行任何动作），模型确认后再次调用
 * 才放行执行。</p>
 */
public interface VerifyPreviewRenderer {

    /**
     * 渲染并注入预检图（无副作用：仅截图 + 上传 + 登记注入）。
     *
     * @param conversationId 会话ID（坐标基准与注入目标）
     * @param userId         用户ID（文件资产归属；缺失时返回错误）
     * @param normMarks      待校验的归一化坐标点列表（0~1000；调用方已校验范围，非空）
     * @return null = 成功（图片已登记注入）；非 null = 失败原因（可直接返回给模型）
     */
    String renderVerifyPreview(Long conversationId, Long userId, List<int[]> normMarks);
}
