package com.example.agentdeepseek.service.llm;

import com.example.agentdeepseek.util.ReasoningPassback;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 请求出口 reasoning_content 兜底补齐测试（main 方法式，非 JUnit）
 *
 * <p>验证 {@link AbstractLLMClient#padReasoningContent} 的契约：</p>
 * <ul>
 *   <li>缺失字段的 assistant 消息被补空串</li>
 *   <li>已有真实值的消息绝不被覆盖</li>
 *   <li>copy-on-write：不修改调用方的列表与 Map</li>
 *   <li>非 assistant 角色不受影响</li>
 *   <li>无需补齐时返回原引用（零拷贝）</li>
 * </ul>
 *
 * <p>运行：{@code mvn exec:java '-Dexec.mainClass=com.example.agentdeepseek.service.llm.ReasoningPaddingTest' '-Dexec.classpathScope=test'}</p>
 */
public class ReasoningPaddingTest {

    private static int passed = 0;
    private static int failed = 0;

    public static void main(String[] args) {
        System.out.println("===== 请求出口 reasoning_content 兜底补齐测试 =====\n");

        testPadMissingField();
        testNeverOverwriteRealValue();
        testCopyOnWrite();
        testNonAssistantUntouched();
        testNoPadReturnsSameReference();
        testNullAndEmptySafe();
        testEnsureReasoningContentAtSendExit();

        System.out.println("\n===== 结果：" + passed + " 通过, " + failed + " 失败 =====");
        if (failed > 0) {
            System.exit(1);
        }
    }

    // ==================== 用例 ====================

    /** 缺失字段的 assistant 被补空串 */
    private static void testPadMissingField() {
        List<Map<String, Object>> messages = new ArrayList<>();
        messages.add(userMsg("帮我改个 bug"));
        messages.add(assistantWithToolCalls("call_1"));   // 模型这轮没给思考内容

        List<Map<String, Object>> padded = AbstractLLMClient.padReasoningContent(messages);

        check("补齐-返回新列表", padded != messages);
        check("补齐-条数不变", padded.size() == messages.size());
        check("补齐-assistant 字段存在", padded.get(1).containsKey(ReasoningPassback.FIELD));
        check("补齐-值为空串", "".equals(padded.get(1).get(ReasoningPassback.FIELD)));
        check("补齐-tool_calls 保留", padded.get(1).containsKey("tool_calls"));
    }

    /** 已有真实值的消息不被覆盖（兜底绝不降级为占位） */
    private static void testNeverOverwriteRealValue() {
        List<Map<String, Object>> messages = new ArrayList<>();
        Map<String, Object> withReal = assistantWithToolCalls("call_1");
        withReal.put(ReasoningPassback.FIELD, "真实思考内容不应被覆盖");
        messages.add(withReal);

        List<Map<String, Object>> padded = AbstractLLMClient.padReasoningContent(messages);

        check("不覆盖-真实值保留", "真实思考内容不应被覆盖".equals(padded.get(0).get(ReasoningPassback.FIELD)));
        check("不覆盖-无需补齐时返回原引用", padded == messages);
    }

    /** copy-on-write：不修改调用方持有的对象 */
    private static void testCopyOnWrite() {
        List<Map<String, Object>> messages = new ArrayList<>();
        Map<String, Object> original = assistantWithToolCalls("call_1");
        messages.add(original);

        List<Map<String, Object>> padded = AbstractLLMClient.padReasoningContent(messages);

        check("copy-原 Map 未被添加字段", !original.containsKey(ReasoningPassback.FIELD));
        check("copy-原列表未被替换", messages.get(0) == original);
        check("copy-新列表中是副本", padded.get(0) != original);
    }

    /** 非 assistant 角色不受影响 */
    private static void testNonAssistantUntouched() {
        List<Map<String, Object>> messages = new ArrayList<>();
        Map<String, Object> system = new HashMap<>();
        system.put("role", "system");
        system.put("content", "你是助手");
        messages.add(system);
        messages.add(userMsg("你好"));
        Map<String, Object> tool = new HashMap<>();
        tool.put("role", "tool");
        tool.put("tool_call_id", "call_1");
        tool.put("content", "结果");
        messages.add(tool);
        messages.add(assistantWithToolCalls("call_2"));

        List<Map<String, Object>> padded = AbstractLLMClient.padReasoningContent(messages);

        check("非assistant-system 不加字段", !padded.get(0).containsKey(ReasoningPassback.FIELD));
        check("非assistant-user 不加字段", !padded.get(1).containsKey(ReasoningPassback.FIELD));
        check("非assistant-tool 不加字段", !padded.get(2).containsKey(ReasoningPassback.FIELD));
        check("assistant 加字段", padded.get(3).containsKey(ReasoningPassback.FIELD));
    }

    /** 全部已有字段时零拷贝返回 */
    private static void testNoPadReturnsSameReference() {
        List<Map<String, Object>> messages = new ArrayList<>();
        Map<String, Object> a = assistantWithToolCalls("call_1");
        a.put(ReasoningPassback.FIELD, "");
        messages.add(a);

        check("零拷贝-返回同一引用", AbstractLLMClient.padReasoningContent(messages) == messages);
    }

    /** null / 空列表安全 */
    private static void testNullAndEmptySafe() {
        check("安全-null 返回 null", AbstractLLMClient.padReasoningContent(null) == null);
        List<Map<String, Object>> empty = new ArrayList<>();
        check("安全-空列表原样返回", AbstractLLMClient.padReasoningContent(empty) == empty);
    }

    /**
     * 发送出口兜底：{@link LLMClient#ensureReasoningContent}。
     * <p>
     * 这是本次修复<b>真正生效</b>的一环——主链路与工具循环直接用
     * {@code webClient.bodyValue(requestBody)} 发送，不经过 buildRequestBody / streamChat。
     * </p>
     */
    private static void testEnsureReasoningContentAtSendExit() {
        LLMClient deepseekLike = new TestClient("deepseek", true);
        LLMClient openaiLike = new TestClient("openai", false);

        // 1) 需要补齐：assistant 缺字段 → 副本中补空串，其它请求字段原样保留
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", "deepseek-v4-flash");
        body.put("stream", true);
        body.put("thinking", Map.of("type", "disabled"));   // 关闭思考的场景
        List<Map<String, Object>> messages = new ArrayList<>();
        messages.add(userMsg("你好"));
        messages.add(assistantWithToolCalls("call_1"));      // 关闭思考时模型没有 reasoning
        body.put("messages", messages);

        Map<String, Object> prepared = deepseekLike.ensureReasoningContent(body);

        check("出口兜底-返回副本", prepared != body);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> preparedMessages = (List<Map<String, Object>>) prepared.get("messages");
        check("出口兜底-assistant 补空串", "".equals(preparedMessages.get(1).get(ReasoningPassback.FIELD)));
        check("出口兜底-其它请求字段保留", "deepseek-v4-flash".equals(prepared.get("model"))
                && Boolean.TRUE.equals(prepared.get("stream"))
                && prepared.containsKey("thinking"));
        // copy-on-write：原请求体里的那条 assistant 不允许被就地加上字段
        Map<?, ?> originalAssistant = (Map<?, ?>) ((List<?>) body.get("messages")).get(1);
        check("出口兜底-原请求体未被修改", !originalAssistant.containsKey(ReasoningPassback.FIELD));

        // 2) 不需要补齐（已有字段）→ 原引用返回
        Map<String, Object> already = new LinkedHashMap<>();
        List<Map<String, Object>> msgs2 = new ArrayList<>();
        Map<String, Object> a = assistantWithToolCalls("call_2");
        a.put(ReasoningPassback.FIELD, "已有思考");
        msgs2.add(a);
        already.put("messages", msgs2);
        check("出口兜底-无需补齐返回原引用", deepseekLike.ensureReasoningContent(already) == already);

        // 3) 不支持的 Provider（OpenAI / Anthropic / Ollama）→ 不注入未知字段
        Map<String, Object> bodyForOpenAi = new LinkedHashMap<>();
        List<Map<String, Object>> msgs3 = new ArrayList<>();
        msgs3.add(assistantWithToolCalls("call_3"));
        bodyForOpenAi.put("messages", msgs3);
        check("出口兜底-非 DeepSeek 系不注入", openaiLike.ensureReasoningContent(bodyForOpenAi) == bodyForOpenAi);

        // 4) 边界：null / 无 messages 字段
        check("出口兜底-null 安全", deepseekLike.ensureReasoningContent(null) == null);
        Map<String, Object> noMessages = new LinkedHashMap<>();
        noMessages.put("model", "x");
        check("出口兜底-无 messages 原样返回", deepseekLike.ensureReasoningContent(noMessages) == noMessages);
    }

    /** 测试用最小 Client：只关心 requiresReasoningPadding 开关 */
    private static class TestClient extends AbstractLLMClient {

        private final boolean paddingEnabled;

        TestClient(String providerCode, boolean paddingEnabled) {
            super(null, new com.fasterxml.jackson.databind.ObjectMapper(), providerCode);
            this.paddingEnabled = paddingEnabled;
        }

        @Override
        protected Map<String, Object> buildThinkingParams(String thinkingMode) {
            return new HashMap<>();
        }

        @Override
        protected boolean requiresReasoningPadding() {
            return paddingEnabled;
        }
    }

    // ==================== 工具方法 ====================

    private static Map<String, Object> userMsg(String content) {
        Map<String, Object> msg = new HashMap<>();
        msg.put("role", "user");
        msg.put("content", content);
        return msg;
    }

    private static Map<String, Object> assistantWithToolCalls(String callId) {
        Map<String, Object> msg = new HashMap<>();
        msg.put("role", "assistant");
        msg.put("tool_calls", "[{\"id\":\"" + callId + "\"}]");
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
