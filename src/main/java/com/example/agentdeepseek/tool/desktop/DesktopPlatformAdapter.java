package com.example.agentdeepseek.tool.desktop;

import java.util.List;

/**
 * 桌面自动化平台适配器（M6 评审⑥定稿：从第一天适配器化，禁止写死 Windows）。
 *
 * <p>职责边界：</p>
 * <ul>
 *   <li><b>v1（当前）</b>：平台探测（OS 识别 + 图形环境可用性）+ 权限/受限提示（供工具返回值与前端引导使用）；
 *       执行机制统一为 java.awt.Robot（跨平台），无平台特定代码；</li>
 *   <li><b>P2 增强（预留扩展点）</b>：无障碍树（Windows UIA / macOS AX / Linux AT-SPI）与 OCR 通道——
 *       {@link #supportsElementList()} / {@link #supportsOcr()} 默认 false，届时由具体平台适配器覆写。</li>
 * </ul>
 *
 * <p>降级原则（评审⑦）：有兼容通道的平台 → 增强模式（清单优先，纯视觉兜底）；无兼容通道 → 纯视觉模式
 * （功能完整可用、仅精度降低，绝不禁用）；仅当截图/输入模拟本身不可用（如 Wayland 限制）才禁用并显式提示。</p>
 */
public interface DesktopPlatformAdapter {

    /** 平台标识：windows / macos / linux / unknown */
    String getPlatform();

    /** 平台展示名（用于日志与提示文本） */
    String getDisplayName();

    /** 图形环境是否可用（桌面自动化前置：非 Headless） */
    boolean isEnvironmentReady();

    /** 权限/受限提示（可为空列表）；用于工具返回与前端引导（如 macOS 授权、Wayland 限制） */
    List<String> getPermissionNotes();

    /** P2 扩展点：是否支持无障碍树元素清单（v1 默认不支持，纯视觉模式） */
    default boolean supportsElementList() {
        return false;
    }

    /** P2 扩展点：是否支持 OCR 文本清单（v1 默认不支持，纯视觉模式） */
    default boolean supportsOcr() {
        return false;
    }
}
