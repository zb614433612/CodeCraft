package com.example.agentdeepseek.util;

import com.example.agentdeepseek.common.enums.ContextMode;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * {@link ReasoningPassback} 回归测试（main 方法式，非 JUnit）
 *
 * <p>背景：v3.0.9 把「不回传 reasoning」实现成了「不下发字段」，
 * 导致 DeepSeek thinking + tools 场景下第一轮工具调用后必然 400：</p>
 * <pre>
 * The `reasoning_content` in the thinking mode must be passed back to the API.
 * </pre>
 * <p>本测试锁死修复后的契约：<b>字段必须在，值可以置空</b>。</p>
 *
 * <p>运行：{@code mvn exec:java '-Dexec.mainClass=com.example.agentdeepseek.util.ReasoningPassbackTest' '-Dexec.classpathScope=test'}</p>
 */
public class ReasoningPassbackTest {

    private static int passed = 0;
    private static int failed = 0;

    public static void main(String[] args) {
        System.out.println("===== ReasoningPassback 回归测试 =====\n");

        testCurrentTurnAlwaysCarriesField();
        testHistoryFullModeKeepsRealValue();
        testHistoryBalancedModeUsesPlaceholder();
        testHistoryCompactModeUsesPlaceholder();
        testStripToPlaceholderKeepsField();
        testNeedsPadding();
        testFullMessageListNeverMissesField();
        testNoReasoningAssistantStillGetsField();

        System.out.println("\n===== 结果：" + passed + " 通过, " + failed + " 失败 =====");
        if (failed > 0) {
            System.exit(1);
        }
    }

    // ==================== 用例 ====================

    /** 本轮工具循环：有值传真实值，无值也必须写空串（字段不能缺） */
    private static void testCurrentTurnAlwaysCarriesField() {
        Map<String, Object> msg1 = assistantWithToolCalls();
        ReasoningPassback.putCurrentTurn(msg1, "我想先看看目录结构");
        check("当前轮-有值→真实值", "我想先看看目录结构".equals(msg1.get(ReasoningPassback.FIELD)));

        Map<String, Object> msg2 = assistantWithToolCalls();
        ReasoningPassback.putCurrentTurn(msg2, null);
        check("当前轮-null→空串占位", "".equals(msg2.get(ReasoningPassback.FIELD)));
        check("当前轮-null→字段存在", msg2.containsKey(ReasoningPassback.FIELD));

        Map<String, Object> msg3 = assistantWithToolCalls();
        ReasoningPassback.putCurrentTurn(msg3, "");
        check("当前轮-空串→空串占位", "".equals(msg3.get(ReasoningPassback.FIELD)));
        check("当前轮-不破坏 tool_calls", msg3.containsKey("tool_calls"));
    }

    /** full 模式：历史回传真实值 */
    private static void testHistoryFullModeKeepsRealValue() {
        Map<String, Object> msg = assistantWithToolCalls();
        ReasoningPassback.putHistory(msg, "上一轮的分析", ContextMode.FULL);
        check("full-历史→真实值", "上一轮的分析".equals(msg.get(ReasoningPassback.FIELD)));

        Map<String, Object> msgNull = assistantWithToolCalls();
        ReasoningPassback.putHistory(msgNull, null, ContextMode.FULL);
        check("full-null→空串（字段仍存在）", "".equals(msgNull.get(ReasoningPassback.FIELD)));
    }

    /** balanced 模式（默认）：历史置空串，但字段必须在 */
    private static void testHistoryBalancedModeUsesPlaceholder() {
        Map<String, Object> msg = assistantWithToolCalls();
        ReasoningPassback.putHistory(msg, "这段思考很长".repeat(1000), ContextMode.BALANCED);
        check("balanced-历史→空串占位", "".equals(msg.get(ReasoningPassback.FIELD)));
        check("balanced-字段存在（400 防线）", msg.containsKey(ReasoningPassback.FIELD));
    }

    /** compact 模式：历史同样置空串 */
    private static void testHistoryCompactModeUsesPlaceholder() {
        Map<String, Object> msg = assistantWithToolCalls();
        ReasoningPassback.putHistory(msg, "这段思考很长", ContextMode.COMPACT);
        check("compact-历史→空串占位", "".equals(msg.get(ReasoningPassback.FIELD)));
        check("compact-字段存在（400 防线）", msg.containsKey(ReasoningPassback.FIELD));
    }

    /** 剥离真实值：只改值不删字段 */
    private static void testStripToPlaceholderKeepsField() {
        Map<String, Object> msg = assistantWithToolCalls();
        msg.put(ReasoningPassback.FIELD, "很长的历史思考链".repeat(500));

        boolean stripped = ReasoningPassback.stripToPlaceholder(msg);
        check("strip-返回 true（确实剥离了内容）", stripped);
        check("strip-值被置空", "".equals(msg.get(ReasoningPassback.FIELD)));
        check("strip-字段仍然存在（关键：不能 remove）", msg.containsKey(ReasoningPassback.FIELD));

        // 已经是空串 → 无需再剥离，但仍不能删字段
        boolean again = ReasoningPassback.stripToPlaceholder(msg);
        check("strip-幂等（二次调用返回 false）", !again);
        check("strip-二次调用后字段仍在", msg.containsKey(ReasoningPassback.FIELD));

        // 没有该字段的消息：不做任何事（由兜底补齐负责）
        Map<String, Object> noField = assistantWithToolCalls();
        check("strip-无字段消息返回 false", !ReasoningPassback.stripToPlaceholder(noField));
        check("strip-无字段消息不被凭空添加", !noField.containsKey(ReasoningPassback.FIELD));
    }

    /** 兜底判定：只有 assistant 缺字段才需要补齐 */
    private static void testNeedsPadding() {
        Map<String, Object> needPad = assistantWithToolCalls();
        check("needsPadding-assistant 缺字段 → true", ReasoningPassback.needsPadding(needPad));

        Map<String, Object> padded = assistantWithToolCalls();
        padded.put(ReasoningPassback.FIELD, "");
        check("needsPadding-assistant 有空串 → false", !ReasoningPassback.needsPadding(padded));

        Map<String, Object> userMsg = new HashMap<>();
        userMsg.put("role", "user");
        userMsg.put("content", "你好");
        check("needsPadding-user 缺字段 → false", !ReasoningPassback.needsPadding(userMsg));

        Map<String, Object> systemMsg = new HashMap<>();
        systemMsg.put("role", "system");
        check("needsPadding-system 缺字段 → false", !ReasoningPassback.needsPadding(systemMsg));

        check("needsPadding-null → false", !ReasoningPassback.needsPadding(null));
    }

    /**
     * 端到端模拟：模拟 ContextBuilder 的兜底补齐环节。
     * <p>覆盖「模型只返回裸 tool_call、完全没思考」这类真实场景 —— 它同样必须带字段。</p>
     */
    private static void testFullMessageListNeverMissesField() {
        List<Map<String, Object>> messages = new ArrayList<>();

        Map<String, Object> sys = new HashMap<>();
        sys.put("role", "system");
        sys.put("content", "你是助手");
        messages.add(sys);

        Map<String, Object> user = new HashMap<>();
        user.put("role", "user");
        user.put("content", "帮我看看项目结构");
        messages.add(user);

        // 第一轮：有思考内容的 tool_call
        Map<String, Object> a1 = assistantWithToolCalls();
        ReasoningPassback.putHistory(a1, "先列目录", ContextMode.BALANCED);
        messages.add(a1);

        Map<String, Object> tool = new HashMap<>();
        tool.put("role", "tool");
        tool.put("tool_call_id", "call_1");
        tool.put("content", "src/ main/ ...");
        messages.add(tool);

        // 第二轮：模型没给思考内容（长工具链后段常见）
        Map<String, Object> a2 = assistantWithToolCalls();
        a2.put("_no_reasoning_at_all", true);   // 故意不写 reasoning_content，交给兜底
        messages.add(a2);

        // 模拟 ContextBuilder 最后的兜底补齐
        int padded = 0;
        for (Map<String, Object> message : messages) {
            if (ReasoningPassback.needsPadding(message)) {
                ReasoningPassback.putHistory(message, null, ContextMode.BALANCED);
                padded++;
            }
        }

        check("兜底-补齐 1 条（第二轮的裸 tool_call）", padded == 1);

        long missing = messages.stream()
                .filter(m -> "assistant".equals(m.get("role")))
                .filter(m -> !m.containsKey(ReasoningPassback.FIELD))
                .count();
        check("兜底-所有 assistant 均含 reasoning_content 字段", missing == 0);

        long nonEmpty = messages.stream()
                .filter(m -> "assistant".equals(m.get("role")))
                .filter(m -> {
                    Object v = m.get(ReasoningPassback.FIELD);
                    return v instanceof String s && !s.isEmpty();
                })
                .count();
        check("兜底-balanced 下无真实思考内容泄露（全部空串）", nonEmpty == 0);
    }

    /** 空 reasoning 的 assistant（模型未思考）也必须带字段 */
    private static void testNoReasoningAssistantStillGetsField() {
        Map<String, Object> msg = new HashMap<>();
        msg.put("role", "assistant");
        msg.put("content", "");
        msg.put("tool_calls", "[]");

        ReasoningPassback.putHistory(msg, "", ContextMode.BALANCED);
        check("空思考-assistant → 字段存在", msg.containsKey(ReasoningPassback.FIELD));
        check("空思考-assistant → 值为空串", "".equals(msg.get(ReasoningPassback.FIELD)));
    }

    // ==================== 工具方法 ====================

    private static Map<String, Object> assistantWithToolCalls() {
        Map<String, Object> msg = new HashMap<>();
        msg.put("role", "assistant");
        msg.put("tool_calls", "[]");
        return msg;
    }

    private static void check(String name, boolean ok) {
        if (ok) {
            passed++;
            System.out.println("  [PASS] " + name);
        } else {
            failed++;
            System.out.println("  [FAIL] " + name);
        }
    }
}
