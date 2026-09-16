package com.example.agentdeepseek.tool.desktop;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.awt.GraphicsEnvironment;
import java.util.List;
import java.util.Locale;

/**
 * 默认桌面平台适配器（跨平台兜底实现，仅依赖 java.awt.Robot——"FallbackAdapter"角色）。
 *
 * <p>v1 执行机制为纯视觉模式：Robot 截图（M5）+ Robot 键鼠（M6）+ LLM 直估坐标，
 * 所有平台同一套代码路径；平台差异仅体现在权限/受限提示（macOS 授权引导、Wayland 限制说明）。</p>
 *
 * <p>P2 增强通道（UIA / AX / AT-SPI 无障碍树、OCR）实施时，新增平台专属适配器类并在此演进选择逻辑
 * （如 @Primary / 条件装配），本类保持为最终兜底。</p>
 */
@Slf4j
@Component
public class DefaultDesktopPlatformAdapter implements DesktopPlatformAdapter {

    private final String platform;
    private final String displayName;

    public DefaultDesktopPlatformAdapter() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("win")) {
            platform = "windows";
            displayName = "Windows";
        } else if (os.contains("mac") || os.contains("darwin")) {
            platform = "macos";
            displayName = "macOS";
        } else if (os.contains("linux")) {
            platform = "linux";
            displayName = "Linux";
        } else {
            platform = "unknown";
            displayName = os.isBlank() ? "未知平台" : os;
        }
        log.info("桌面自动化平台适配器初始化: platform={}, displayName={}", platform, displayName);
    }

    @Override
    public String getPlatform() {
        return platform;
    }

    @Override
    public String getDisplayName() {
        return displayName;
    }

    @Override
    public boolean isEnvironmentReady() {
        return !GraphicsEnvironment.isHeadless();
    }

    @Override
    public List<String> getPermissionNotes() {
        return switch (platform) {
            case "macos" -> List.of(
                    "macOS 首次使用需授予两项权限：「屏幕录制」（截图）与「辅助功能」（键鼠控制）："
                            + "系统设置 → 隐私与安全性 → 对应权限中勾选本应用。",
                    "授权后可能需要重启本应用才能生效。");
            case "linux" -> List.of(
                    "Wayland 桌面环境下截图与键鼠模拟可能受限（取决于发行版与桌面环境）；"
                            + "如遇黑屏或操作无效，可尝试切换到 X11 会话或配置 xdg-desktop-portal。");
            default -> List.of();
        };
    }
}
