package com.example.agentdeepseek.tool.desktop;

import java.awt.Rectangle;
import java.util.List;

/**
 * 桌面 UI 元素检测器（★ 新增：SoM / Set-of-Mark——截图时枚举可交互元素并编号，让模型用"机器计算的坐标"点击）。
 *
 * <p>v1 实现为 Windows UIAutomation（{@link UiaElementDetector}，经 PowerShell 调用系统组件，零新依赖）；
 * 其他平台 / 环境不支持 / 任何失败场景一律返回空列表（静默降级），绝不抛异常、绝不阻断截图主链。</p>
 *
 * <p>坐标契约：{@code physicalRegion} 入参与返回元素均为<b>物理像素</b>坐标系（与 Robot 截图链一致，
 * 无需任何换算）；返回值只包含"与传入区域相交"的元素。</p>
 */
public interface DesktopElementDetector {

    /**
     * 检测指定物理区域内可交互 UI 元素。
     *
     * @param physicalRegion 目标区域（截图的物理矩形 target；x/y 可为负值/多屏合并区域）
     * @return 区域内元素列表（物理像素坐标）；不支持平台/设置缺失/任何失败时返回空列表
     */
    List<DetectedElement> detect(Rectangle physicalRegion);
}
