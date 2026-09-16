package com.example.agentdeepseek.tool.desktop;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;

/**
 * 桌面预检闸门判定与文案（纯函数工具类）。
 *
 * <p>desktop_control 的"执行路径闸门"与"授权豁免判定"共用本类方法，保证判定严格同源——
 * 即"被判定为预检（无副作用）的调用"在执行时确实不会执行任何键鼠动作。</p>
 */
public final class DesktopVerifySupport {

    private DesktopVerifySupport() {
    }

    /**
     * 预检决策：返回"尚未经准星校验、需先出校验图"的待校验坐标点（已去重）。
     *
     * <p>收集范围：mouse_click 的 (x,y)、mouse_drag 的起点 (fromX,fromY)——其余动作无定位破坏风险，
     * 不拦截；坐标缺失/非法（留给执行路径报参数错误）或已在容差内通过校验的点跳过。</p>
     *
     * @param verifyEnabled 预检总开关（desktop.control.verify-before-click）
     * @param hasBaseline   会话是否已有截图坐标基准
     * @param actions       动作节点列表（{@link DesktopActionSummary#extractActions} 提取）
     * @param conversationId 当前会话ID
     * @param registry      校验记录注册表
     * @return 待校验点列表（空 = 无需预检，可直接执行）
     */
    public static List<int[]> pendingVerifyPoints(boolean verifyEnabled, boolean hasBaseline,
                                                  List<JsonNode> actions, Long conversationId,
                                                  DesktopVerifyRegistry registry) {
        List<int[]> pending = new ArrayList<>();
        if (!verifyEnabled || !hasBaseline || conversationId == null || registry == null || actions == null) {
            return pending;
        }
        for (JsonNode act : actions) {
            String type = DesktopActionSummary.actionType(act);
            String xField;
            String yField;
            if ("mouse_click".equals(type)) {
                xField = "x";
                yField = "y";
            } else if ("mouse_drag".equals(type)) {
                xField = "fromX";
                yField = "fromY";
            } else {
                continue;
            }
            Integer x = normValue(act, xField);
            Integer y = normValue(act, yField);
            if (x == null || y == null) {
                continue;   // 非法坐标：交给执行路径报参数错误（不生成预检图）
            }
            if (registry.isVerified(conversationId, x, y)) {
                continue;
            }
            if (!containsPoint(pending, x, y)) {
                pending.add(new int[]{x, y});
            }
        }
        return pending;
    }

    /**
     * 预检拦截提示文本（返回给模型；含继续执行的明确指引，避免反复截图绕路）。
     */
    public static String buildPrecheckText(List<int[]> pending) {
        StringBuilder sb = new StringBuilder();
        sb.append("【预检模式】为提升点击精度，本批动作未执行：以下坐标尚未经准星校验，"
                + "系统已自动截取校验图并绘制洋红准星（见下方注入图片）。\n");
        sb.append("待校验坐标：\n");
        for (int[] p : pending) {
            sb.append("· (").append(p[0]).append(", ").append(p[1]).append(")\n");
        }
        sb.append("请目测核对准星与实际目标的位置关系，然后：\n");
        sb.append("① 准星正中目标 → 直接再次调用完全相同的动作列表（原坐标 ±")
                .append(DesktopVerifyRegistry.TOLERANCE)
                .append(" 内微调亦视为已确认），系统将直接放行执行；\n");
        sb.append("② 有偏差 → 按校验图上的刻度修正坐标后重新调用（修正后的新坐标会自动再次预检）；\n");
        sb.append("③ 校验图已刷新坐标基准，读数以本图为准；点击/拖拽前已用 screen_capture(mark={x,y}) "
                + "主动校验过的坐标不会触发预检。");
        return sb.toString();
    }

    /**
     * 读取归一化坐标值（缺失/非数字/越界返回 null）。
     *
     * <p>判定语义与 desktop_control 执行路径的坐标校验一致（0~1000）。</p>
     */
    static Integer normValue(JsonNode act, String field) {
        JsonNode node = act.path(field);
        if (node.isMissingNode() || node.isNull() || !node.isNumber()) {
            return null;
        }
        int value = node.asInt();
        return (value < 0 || value > 1000) ? null : value;
    }

    private static boolean containsPoint(List<int[]> points, int x, int y) {
        for (int[] p : points) {
            if (p[0] == x && p[1] == y) {
                return true;
            }
        }
        return false;
    }
}
