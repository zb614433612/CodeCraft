package com.example.agentdeepseek.tool.impl;

import com.example.agentdeepseek.tool.Tool;
import com.example.agentdeepseek.tool.permission.OperationCategory;
import com.example.agentdeepseek.tool.permission.ToolPermission;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 桌面统一工具 —— 合并原 screen_capture（看）与 desktop_control（动）
 * <p>
 * <b>为什么合并</b>：两个工具的坐标体系完全同源（0~1000 归一化 + 10×10 网格刻度 + 元素清单 + 点击前校验），
 * 这套说明在原来的两个工具描述里<b>各写了一遍</b>；合并后只保留一份，
 * 同时把"先看再动"的固定流程写进同一个描述里，减少"截图工具和键鼠工具分两次理解"的认知负担。
 * </p>
 * <p>
 * <b>实现策略</b>：纯委托。{@code action=capture} 走 screen_capture，其余动作（mouse_click / key / type …）
 * 与批量 {@code actions} 数组走 desktop_control，参数原样透传，行为与合并前完全一致。
 * </p>
 */
@Slf4j
@Component
@ToolPermission(category = OperationCategory.DESKTOP, affectsData = true,
        description = "桌面自动化：截图（capture）与键鼠控制（click/type/key 等）")
public class DesktopTool implements Tool {

    private final ScreenCaptureTool screenCaptureTool;
    private final DesktopControlTool desktopControlTool;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public DesktopTool(ScreenCaptureTool screenCaptureTool, DesktopControlTool desktopControlTool) {
        this.screenCaptureTool = screenCaptureTool;
        this.desktopControlTool = desktopControlTool;
    }

    @Override
    public String getName() {
        return "desktop";
    }

    @Override
    public String getDescription() {
        return "【适用场景】桌面（非网页）自动化：截屏观察界面 + 鼠标键盘操作。网页内部操作请用 browser 工具，不要用本工具。\n"
                + "【坐标体系（capture 与键鼠动作共用）】所有坐标都是 0~1000 归一化值，基于最近一次 capture 截图的坐标空间；\n"
                + "  截图会自动叠加 10×10 网格与四边刻度（每 25/50/100），并检测可交互元素输出【元素清单】（编号 → 名称/类型/中心点坐标），"
                + "点击目标时优先用清单坐标。\n"
                + "【标准流程】capture 截图 → 读网格/元素清单定坐标 → （可选）capture 带 mark={x,y} 校验准星是否落在目标上 → 执行键鼠动作 → 再 capture 验证结果。\n"
                + "【action 说明】\n"
                + "  · capture：截屏。可选 region={x,y,width,height} 局部放大、mark={x,y} 单点校验、marks=[{x,y}...] 多点对比、screen=显示器序号。\n"
                + "  · mouse_move / mouse_click / mouse_drag / mouse_scroll / key / type / screen_size：键鼠动作，配合 x/y/button/clicks/fromX/fromY/toX/toY/amount/key/modifiers/text 使用。\n"
                + "  · 也可用 actions 数组一次批量执行 1~20 个动作（按序执行，减少往返）。\n"
                + "【何时不要用】① 目标在网页里 → 用 browser（DOM 级更稳更省）；② 能用 API/命令完成的 → 用对应工具（command/file_writer 等）；"
                + "③ 输入密码、支付确认、验证码等敏感操作禁止自动化。\n"
                + "【注意事项】UAC 提权窗口、锁屏界面不可操控；DirectX 全屏场景可能受限；Wayland 下可能不可用。"
                + "点击/拖拽前建议先用 mark 校验坐标，未校验坐标会被预检拦截并返回校验图。";
    }

    @Override
    public JsonNode getParameters() {
        ObjectNode parameters = objectMapper.createObjectNode();
        parameters.put("type", "object");

        ObjectNode properties = objectMapper.createObjectNode();

        // ===== 共用：action =====
        ObjectNode action = objectMapper.createObjectNode();
        action.put("type", "string");
        action.put("description", "【必填（或用 actions 数组）】动作类型：capture=截图；mouse_move/mouse_click/mouse_drag/mouse_scroll=鼠标；key=按键；type=键入文本；screen_size=查屏幕尺寸。");
        ArrayNode actionEnum = action.putArray("enum");
        for (String a : new String[]{"capture", "mouse_move", "mouse_click", "mouse_drag", "mouse_scroll",
                "key", "type", "screen_size"}) {
            actionEnum.add(a);
        }
        properties.set("action", action);

        // ===== capture 专属参数 =====
        ObjectNode region = objectMapper.createObjectNode();
        region.put("type", "object");
        region.put("description", "【可选，仅 capture】局部放大截图区域 {x,y,width,height}，均为 0~1000 归一化值（读最近一次截图的刻度值；x+width ≤ 1000、y+height ≤ 1000）。小区域会自动放大便于看清细节。");
        ObjectNode regionProps = objectMapper.createObjectNode();
        regionProps.set("x", intNode("区域左上角 X 归一化坐标（0~1000）"));
        regionProps.set("y", intNode("区域左上角 Y 归一化坐标（0~1000）"));
        regionProps.set("width", intNode("区域宽度 归一化值（1~1000）"));
        regionProps.set("height", intNode("区域高度 归一化值（1~1000）"));
        region.set("properties", regionProps);
        region.set("required", objectMapper.createArrayNode().add("x").add("y").add("width").add("height"));
        properties.set("region", region);

        ObjectNode mark = objectMapper.createObjectNode();
        mark.put("type", "object");
        mark.put("description", "【可选，仅 capture】单点准星校验标记 {x,y}（0~1000）。在截图上绘制洋红十字，用于目测校验坐标是否准确（估坐标 → 带 mark 截图 → 目测偏差 → 修正 → 点击）。");
        ObjectNode markProps = objectMapper.createObjectNode();
        markProps.set("x", intNode("标记位置 X 归一化坐标（0~1000）"));
        markProps.set("y", intNode("标记位置 Y 归一化坐标（0~1000）"));
        mark.set("properties", markProps);
        mark.set("required", objectMapper.createArrayNode().add("x").add("y"));
        properties.set("mark", mark);

        ObjectNode marks = objectMapper.createObjectNode();
        marks.put("type", "array");
        marks.put("description", "【可选，仅 capture】多准星对比：2~5 个候选点 [{x,y}, ...]（0~1000），按序绘制 A/B/C/D/E 五色准星并各附放大窗，供对比挑选最佳点位。与 mark 二选一。");
        ObjectNode marksItems = objectMapper.createObjectNode();
        marksItems.put("type", "object");
        ObjectNode marksItemProps = objectMapper.createObjectNode();
        marksItemProps.set("x", intNode("候选点 X 归一化坐标（0~1000）"));
        marksItemProps.set("y", intNode("候选点 Y 归一化坐标（0~1000）"));
        marksItems.set("properties", marksItemProps);
        marksItems.set("required", objectMapper.createArrayNode().add("x").add("y"));
        marks.set("items", marksItems);
        properties.set("marks", marks);

        properties.set("screen", intNode("【可选，仅 capture】显示器序号（从 0 开始）。多屏时截取指定显示器；缺省截取全虚拟屏。与 region 互斥。"));

        // ===== 键鼠动作参数 =====
        properties.set("x", intNode("【键鼠动作】X 归一化坐标（0~1000）"));
        properties.set("y", intNode("【键鼠动作】Y 归一化坐标（0~1000）"));
        properties.set("button", enumNode("【可选，mouse_click/drag】鼠标按键", "left", "right", "middle"));
        properties.set("clicks", intNode("【可选，mouse_click】点击次数（1=单击，2=双击；默认 1）"));
        properties.set("fromX", intNode("【mouse_drag 必填】拖拽起点 X 归一化坐标（0~1000）"));
        properties.set("fromY", intNode("【mouse_drag 必填】拖拽起点 Y 归一化坐标（0~1000）"));
        properties.set("toX", intNode("【mouse_drag 必填】拖拽终点 X 归一化坐标（0~1000）"));
        properties.set("toY", intNode("【mouse_drag 必填】拖拽终点 Y 归一化坐标（0~1000）"));
        properties.set("amount", intNode("【mouse_scroll 必填】滚轮格数（正数向上、负数向下，如 3 / -3）"));
        properties.set("key", strNode("【key 必填】按键名：字母/数字/enter/esc/tab/space/backspace/delete/home/end/pageup/pagedown/up/down/left/right/f1~f12 等"));
        ObjectNode modifiers = objectMapper.createObjectNode();
        modifiers.put("type", "array");
        modifiers.put("description", "【可选，配合 key】修饰键：ctrl / alt / shift / meta（win/cmd）。示例：[\"ctrl\"]、[\"ctrl\",\"shift\"]");
        ObjectNode modifierItems = objectMapper.createObjectNode();
        modifierItems.put("type", "string");
        modifiers.set("items", modifierItems);
        properties.set("modifiers", modifiers);
        properties.set("text", strNode("【type 必填】要键入的文本（支持中文/任意字符，经剪贴板粘贴）"));

        // ===== 批量模式 =====
        ObjectNode actions = objectMapper.createObjectNode();
        actions.put("type", "array");
        actions.put("description", "【可选，批量模式】一次执行 1~20 个动作（按序执行，减少往返），每个元素形如 {\"action\":\"mouse_click\",\"x\":500,\"y\":500}。与顶层 action 二选一。");
        ObjectNode actionsItems = objectMapper.createObjectNode();
        actionsItems.put("type", "object");
        actionsItems.set("required", objectMapper.createArrayNode().add("action"));
        actions.set("items", actionsItems);
        properties.set("actions", actions);

        parameters.set("properties", properties);
        return parameters;
    }

    @Override
    public String execute(JsonNode arguments) {
        String action = arguments.has("action") ? arguments.get("action").asText("").trim() : "";
        boolean hasBatchActions = arguments.has("actions")
                && arguments.get("actions").isArray()
                && !arguments.get("actions").isEmpty();

        // capture，或（既没 action 也没 actions）→ 统一按截图处理（兼容原 screen_capture 的无参调用习惯）
        if ("capture".equals(action) || (action.isEmpty() && !hasBatchActions)) {
            log.info("desktop action=capture（委托 screen_capture）");
            return screenCaptureTool.execute(arguments);
        }

        log.info("desktop action={}（委托 desktop_control）", action);
        return desktopControlTool.execute(arguments);
    }

    // ==================== schema 构造小工具 ====================

    private ObjectNode intNode(String description) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("type", "integer");
        node.put("description", description);
        return node;
    }

    private ObjectNode strNode(String description) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("type", "string");
        node.put("description", description);
        return node;
    }

    private ObjectNode enumNode(String description, String... values) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("type", "string");
        node.put("description", description);
        ArrayNode array = node.putArray("enum");
        for (String value : values) {
            array.add(value);
        }
        return node;
    }
}
