package com.example.agentdeepseek.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 桌面自动化配置（screen_capture 桌面截图 / desktop_control 键鼠模拟）
 *
 * <p>对应 application.yml 的 desktop.* 配置块：总开关、截图标准化与频率限制、键鼠控制上限。</p>
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "desktop")
public class DesktopConfig {

    /** 总开关：关闭后桌面工具直接返回"功能未启用"提示 */
    private boolean enabled = true;

    /** 截图配置 */
    private Capture capture = new Capture();

    /** 键鼠控制配置（M6 实施使用） */
    private Control control = new Control();

    @Data
    public static class Capture {
        /** 截图标准化长边上限（像素）：截图统一缩放至模型友好尺寸（v2 归一化坐标协议下与坐标换算解耦，仅影响清晰度） */
        private int maxLongEdge = 1280;

        /** region 局部截图自动放大上限（放大镜；≤1 = 禁用放大。仅 region 请求生效，全屏/显示器截图只缩不放） */
        private double maxZoom = 3.0;

        /** 截图频率限制：同一会话每分钟上限（0 = 不限制；防模型死循环截图刷爆配额） */
        private int maxPerMinute = 0;

        /** 截图频率限制：同一会话最小间隔（毫秒；0 = 不限制） */
        private int minIntervalMs = 0;
    }

    @Data
    public static class Control {
        /** 单次批量动作上限（desktop_control；M6 实施） */
        private int maxActionsPerCall = 20;

        /** 动作间延迟（毫秒；M6 实施，模拟人类节奏让 UI 有响应时间） */
        private int actionDelayMs = 150;

        /** 预检闸门（先验证后执行；M6.2）：mouse_click/mouse_drag 坐标未经准星校验时先返回校验图（不执行动作），确认后再次调用执行 */
        private boolean verifyBeforeClick = true;
    }
}
