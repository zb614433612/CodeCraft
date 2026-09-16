package com.example.agentdeepseek.tool.desktop;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 桌面动作解析与摘要工具（desktop_control 的授权弹窗预览与执行结果展示共用）。
 *
 * <p>动作列表提取规则：优先 {@code actions} 数组（批量形态）；否则回退顶层 {@code action} 字段
 * （单动作兼容形态）。摘要为单行动人话文案，弹窗预览为"将要执行"，执行结果为"已执行"，
 * 文案主体一致、前缀由调用方添加。</p>
 */
public final class DesktopActionSummary {

    private DesktopActionSummary() {
    }

    /**
     * 提取动作列表。
     *
     * @return 动作节点列表；无有效动作（既无 actions 数组也无顶层 action）时为空列表
     */
    public static List<JsonNode> extractActions(JsonNode args) {
        if (args == null) {
            return List.of();
        }
        JsonNode actionsNode = args.path("actions");
        if (actionsNode.isArray() && !actionsNode.isEmpty()) {
            List<JsonNode> list = new ArrayList<>();
            actionsNode.forEach(list::add);
            return list;
        }
        String action = args.path("action").asText("");
        if (!action.isBlank()) {
            return List.of(args);
        }
        return List.of();
    }

    /** 单动作类型（小写规范化）；非法/缺失返回空字符串 */
    public static String actionType(JsonNode act) {
        return act == null ? "" : act.path("action").asText("").trim().toLowerCase(Locale.ROOT);
    }

    /** 该动作是否需要坐标基准（mouse_* 需要；key / type / screen_size 不需要） */
    public static boolean needsCoordinateBase(String actionType) {
        return switch (actionType) {
            case "mouse_move", "mouse_click", "mouse_drag", "mouse_scroll" -> true;
            default -> false;
        };
    }

    /** 单动作一行摘要（用于弹窗预览与执行结果列表） */
    public static String summarize(JsonNode act) {
        String type = actionType(act);
        return switch (type) {
            case "mouse_move" -> "移动鼠标 (" + intOf(act, "x") + ", " + intOf(act, "y") + ")";
            case "mouse_click" -> {
                int clicks = Math.max(1, act.path("clicks").asInt(1));
                String button = act.path("button").asText("left").toLowerCase(Locale.ROOT);
                String label = clicks >= 2 ? "双击" : switch (button) {
                    case "right" -> "右键点击";
                    case "middle" -> "中键点击";
                    default -> "点击";
                };
                yield label + " (" + intOf(act, "x") + ", " + intOf(act, "y") + ")"
                        + (clicks >= 2 ? " ×" + clicks : "");
            }
            case "mouse_drag" -> "拖拽 (" + intOf(act, "fromX") + ", " + intOf(act, "fromY")
                    + ") → (" + intOf(act, "toX") + ", " + intOf(act, "toY") + ")";
            case "mouse_scroll" -> {
                int amount = act.path("amount").asInt(0);
                String dir = amount >= 0 ? "向上" : "向下";
                yield "滚轮 (" + intOf(act, "x") + ", " + intOf(act, "y") + ") " + dir + " " + Math.abs(amount) + " 格";
            }
            case "key" -> {
                String key = act.path("key").asText("?");
                String mods = modifierPrefix(act.path("modifiers"));
                // 带修饰键的单字符统一大写（快捷键显示惯例：Ctrl+S）；其余保持原样
                if (!mods.isEmpty() && key.length() == 1) {
                    key = key.toUpperCase(Locale.ROOT);
                }
                yield "按键 " + mods + key;
            }
            case "type" -> "键入 " + act.path("text").asText("").length() + " 字符";
            case "screen_size" -> "查询屏幕尺寸";
            default -> "未知动作(" + (type.isBlank() ? "缺少 action" : type) + ")";
        };
    }

    /**
     * 批量动作摘要（markdown 多行）；超过 maxItems 条时折叠为"…（共 N 个动作）"。
     *
     * @return 摘要文本；无有效动作时返回 null
     */
    public static String summarizeBatch(JsonNode args, int maxItems) {
        List<JsonNode> actions = extractActions(args);
        if (actions.isEmpty()) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        int shown = Math.min(actions.size(), Math.max(1, maxItems));
        for (int i = 0; i < shown; i++) {
            sb.append("- ").append(summarize(actions.get(i))).append("\n");
        }
        if (actions.size() > shown) {
            sb.append("- …（共 ").append(actions.size()).append(" 个动作）\n");
        }
        return sb.toString();
    }

    private static String modifierPrefix(JsonNode modifiers) {
        if (modifiers == null || !modifiers.isArray() || modifiers.isEmpty()) {
            return "";
        }
        List<String> names = new ArrayList<>();
        for (JsonNode m : modifiers) {
            String s = m.asText("").trim();
            if (s.isEmpty()) {
                continue;
            }
            names.add(switch (s.toLowerCase(Locale.ROOT)) {
                case "ctrl", "control" -> "Ctrl";
                case "shift" -> "Shift";
                case "alt" -> "Alt";
                case "meta", "win", "cmd", "command", "super" -> "Meta";
                default -> s;
            });
        }
        return names.isEmpty() ? "" : String.join("+", names) + "+";
    }

    private static String intOf(JsonNode node, String field) {
        return String.valueOf(node.path(field).asInt(0));
    }
}
