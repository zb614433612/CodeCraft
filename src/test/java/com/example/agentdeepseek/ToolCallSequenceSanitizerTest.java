package com.example.agentdeepseek;

import com.example.agentdeepseek.service.llm.ToolCallSequenceSanitizer;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@link ToolCallSequenceSanitizer} 回归测试（纯逻辑，不依赖 Spring 与外部环境）。
 *
 * <h3>背景</h3>
 * <p>「工具调用已下发、结果未落库时中断任务 → 继续聊天就上下文孤立」这个问题历史上被修过 10 多次，
 * 根因是旧修复只处理「assistant 有 tool_calls 但缺 tool」，却会让已落库的 tool 消息变成
 * 「无前置 tool_calls 的孤立 tool」——依然 400。本测试用「接口视角的严格校验器」验证净化器
 * 对全部已知脏数据形态都能输出合法序列。</p>
 *
 * <h3>运行方式（main 方法，非 JUnit）</h3>
 * <pre>
 * mvn -q -DskipTests -DskipFrontend=true test-compile
 * mvn -q org.codehaus.mojo:exec-maven-plugin:3.1.0:java \
 *     -Dexec.mainClass=com.example.agentdeepseek.ToolCallSequenceSanitizerTest \
 *     -Dexec.classpathScope=test -DskipFrontend=true
 * </pre>
 */
public class ToolCallSequenceSanitizerTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static int passed = 0;
    private static int failed = 0;

    public static void main(String[] args) throws Exception {
        // 场景 A：用户描述的典型场景 —— assistant 带 tool_calls，但工具结果没落库（中断）
        caseOrphanToolCallsWithText();
        // 场景 B：多条 tool_call 只回来一条 —— 必须逐项剔除而不是整段删除
        casePartialToolResults();
        // 场景 C：旧修复逻辑制造的脏数据 —— assistant 的 tool_calls 被删了，只剩孤立 tool 消息
        caseOrphanToolMessage();
        // 场景 D：tool 消息的 id 与 assistant 声明完全不匹配
        caseMismatchedToolId();
        // 场景 E：完全合法的序列 —— 净化必须零改动（幂等）
        caseAlreadyValid();
        // 场景 F：非法 tool_call（无 id / id="null" / 重复 id）
        caseInvalidToolCallIds();
        // 场景 G：空 assistant 消息（既无 content 也无 tool_calls）
        caseEmptyAssistant();
        // 场景 H：多轮混合（前一轮正常、后一轮中断）
        caseMultiRoundMixed();
        // 场景 I：真实历史结构（system + user + assistant(tool_calls) + tool + assistant 文本）
        caseRealisticHistory();
        // 场景 J：requestBody 包装层 + 无 messages 时无副作用
        caseRequestBodyWrapper();

        System.out.println("\n========== 结果: " + passed + " passed, " + failed + " failed ==========");
        if (failed > 0) {
            System.exit(1);
        }
    }

    // ==================== 场景 ====================

    /** A：assistant(tool_calls=[c1]) + 无 tool 结果（中断）→ 有文本时清除 tool_calls，无文本时整条丢弃 */
    private static void caseOrphanToolCallsWithText() throws Exception {
        List<Map<String, Object>> msgs = new ArrayList<>();
        msgs.add(user("帮我改配置"));
        msgs.add(assistant("我先看一下文件", "[{\"id\":\"c1\",\"type\":\"function\",\"function\":{\"name\":\"file_writer\",\"arguments\":\"{}\"}}]"));
        // 工具结果缺失（中断）
        ToolCallSequenceSanitizer.sanitize(msgs);

        assertEquals("A: 消息条数（保留 user + 降级后的 assistant）", 2, msgs.size());
        assertEquals("A: assistant 的 tool_calls 被清除", null, msgs.get(1).get("tool_calls"));
        assertEquals("A: assistant 文本内容保留", "我先看一下文件", msgs.get(1).get("content"));
        assertValid("A", msgs);

        // A2：assistant 只有 tool_calls、没有文本 → 整条丢弃（空 assistant 接口同样拒绝）
        List<Map<String, Object>> msgs2 = new ArrayList<>();
        msgs2.add(user("帮我改配置"));
        msgs2.add(assistant(null, "[{\"id\":\"c1\",\"function\":{\"name\":\"file_writer\"}}]"));
        ToolCallSequenceSanitizer.sanitize(msgs2);
        assertEquals("A2: 空 assistant 被整条丢弃", 1, msgs2.size());
        assertValid("A2", msgs2);
    }

    /** B：assistant 声明 [c1,c2]，只回来 c1 → 只保留 c1 的 tool_call，tool(c1) 保留 */
    private static void casePartialToolResults() throws Exception {
        List<Map<String, Object>> msgs = new ArrayList<>();
        msgs.add(user("跑两个命令"));
        msgs.add(assistant("执行中", "[{\"id\":\"c1\",\"function\":{\"name\":\"command\"}},{\"id\":\"c2\",\"function\":{\"name\":\"command\"}}]"));
        msgs.add(tool("c1", "命令1输出"));
        // c2 缺失（中断）
        ToolCallSequenceSanitizer.sanitize(msgs);

        assertEquals("B: 消息条数不变", 3, msgs.size());
        List<String> keptIds = declaredToolCallIds(msgs.get(1));
        assertEquals("B: 只保留有结果的 tool_call", List.of("c1"), keptIds);
        assertEquals("B: tool 消息保留", "c1", msgs.get(2).get("tool_call_id"));
        assertValid("B", msgs);
    }

    /** C：assistant 的 tool_calls 已被旧逻辑删除，只剩孤立 tool 消息 → tool 消息必须被丢弃 */
    private static void caseOrphanToolMessage() throws Exception {
        List<Map<String, Object>> msgs = new ArrayList<>();
        msgs.add(user("看看文件"));
        msgs.add(assistant("我读一下", null));   // 旧修复把 tool_calls 删掉了
        msgs.add(tool("c1", "文件内容"));        // 于是这条 tool 变成孤儿
        ToolCallSequenceSanitizer.sanitize(msgs);

        assertEquals("C: 孤立 tool 消息被丢弃", 2, msgs.size());
        assertEquals("C: 第二条仍是 assistant", "assistant", msgs.get(1).get("role"));
        assertValid("C", msgs);
    }

    /** D：tool 消息的 id 与 assistant 声明不匹配 → tool 丢弃 + tool_calls 清除 */
    private static void caseMismatchedToolId() throws Exception {
        List<Map<String, Object>> msgs = new ArrayList<>();
        msgs.add(user("执行"));
        msgs.add(assistant("开始", "[{\"id\":\"c1\",\"function\":{\"name\":\"command\"}}]"));
        msgs.add(tool("c99", "另外一个结果"));
        ToolCallSequenceSanitizer.sanitize(msgs);

        assertEquals("D: 不匹配 tool 被丢弃", 2, msgs.size());
        assertEquals("D: tool_calls 被清除", null, msgs.get(1).get("tool_calls"));
        assertValid("D", msgs);
    }

    /** E：合法序列 → 一个字段都不能动 */
    private static void caseAlreadyValid() throws Exception {
        List<Map<String, Object>> msgs = new ArrayList<>();
        msgs.add(system("你是助手"));
        msgs.add(user("读文件"));
        msgs.add(assistant("好的", "[{\"id\":\"c1\",\"function\":{\"name\":\"file_explorer\"}}]"));
        msgs.add(tool("c1", "文件内容"));
        msgs.add(assistant("读完了", null));
        String before = MAPPER.writeValueAsString(msgs);

        ToolCallSequenceSanitizer.sanitize(msgs);
        String after = MAPPER.writeValueAsString(msgs);

        assertEquals("E: 合法序列净化前后完全一致", before, after);
        assertValid("E", msgs);

        // 幂等：连续净化两次结果一致
        ToolCallSequenceSanitizer.sanitize(msgs);
        assertEquals("E2: 幂等（再次净化无变化）", before, MAPPER.writeValueAsString(msgs));
    }

    /** F：无 id / id="null" / 重复 id 的 tool_call 必须被剔除 */
    private static void caseInvalidToolCallIds() throws Exception {
        List<Map<String, Object>> msgs = new ArrayList<>();
        msgs.add(user("执行"));
        msgs.add(assistant("开始", "[{\"function\":{\"name\":\"a\"}},{\"id\":\"null\",\"function\":{\"name\":\"b\"}},"
                + "{\"id\":\"c1\",\"function\":{\"name\":\"c\"}},{\"id\":\"c1\",\"function\":{\"name\":\"d\"}}]"));
        msgs.add(tool("c1", "ok"));
        ToolCallSequenceSanitizer.sanitize(msgs);

        assertEquals("F: 只保留唯一合法 id", List.of("c1"), declaredToolCallIds(msgs.get(1)));
        assertValid("F", msgs);
    }

    /** G：空 assistant（content="" 且无 tool_calls）→ 丢弃 */
    private static void caseEmptyAssistant() throws Exception {
        List<Map<String, Object>> msgs = new ArrayList<>();
        msgs.add(user("你好"));
        msgs.add(assistant("", null));
        msgs.add(assistant("   ", null));
        msgs.add(assistant("在的", null));
        ToolCallSequenceSanitizer.sanitize(msgs);

        assertEquals("G: 空 assistant 全部丢弃", 2, msgs.size());
        assertEquals("G: 保留最后一条有效回复", "在的", msgs.get(1).get("content"));
        assertValid("G", msgs);
    }

    /** H：多轮混合 —— 前一轮正常，后一轮中途中断 */
    private static void caseMultiRoundMixed() throws Exception {
        List<Map<String, Object>> msgs = new ArrayList<>();
        msgs.add(user("第一轮"));
        msgs.add(assistant("调用1", "[{\"id\":\"a1\",\"function\":{\"name\":\"command\"}}]"));
        msgs.add(tool("a1", "结果1"));
        msgs.add(assistant("第一轮完成", null));
        msgs.add(user("第二轮"));
        msgs.add(assistant("调用2", "[{\"id\":\"b1\",\"function\":{\"name\":\"command\"}},{\"id\":\"b2\",\"function\":{\"name\":\"command\"}}]"));
        msgs.add(tool("b1", "结果2-1"));
        // b2 缺失（中断）
        ToolCallSequenceSanitizer.sanitize(msgs);

        assertValid("H", msgs);
        // 第一轮完全不受影响
        assertEquals("H: 第一轮 tool_call 原样保留", List.of("a1"), declaredToolCallIds(msgs.get(1)));
        assertEquals("H: 第二轮只保留有结果的调用", List.of("b1"), declaredToolCallIds(msgs.get(5)));
    }

    /** I：真实历史结构（含 system 与多轮），确保净化不误伤正常上下文 */
    private static void caseRealisticHistory() throws Exception {
        List<Map<String, Object>> msgs = new ArrayList<>();
        msgs.add(system("system prompt"));
        msgs.add(user("任务：修改配置"));
        msgs.add(assistant("", "[{\"id\":\"t1\",\"function\":{\"name\":\"file_explorer\"}}]"));   // 只有 reasoning 的情况
        msgs.add(tool("t1", "文件内容"));
        msgs.add(assistant("分析完成", "[{\"id\":\"t2\",\"function\":{\"name\":\"file_writer\"}}]"));
        // t2 结果缺失（任务在这里被中断）
        msgs.add(user("继续"));
        ToolCallSequenceSanitizer.sanitize(msgs);

        assertValid("I", msgs);
        assertEquals("I: system 保留", "system", msgs.get(0).get("role"));
        assertEquals("I: 用户「继续」保留", "继续", msgs.get(msgs.size() - 1).get("content"));
        // 中断那轮的 assistant 因无文本被丢弃，tool_calls 也不会残留
        boolean anyOrphan = msgs.stream().anyMatch(m -> "assistant".equals(m.get("role"))
                && m.get("tool_calls") != null
                && declaredToolCallIds(m).contains("t2"));
        assertTrue("I: 缺失结果的 tool_call 不再出现", !anyOrphan);
    }

    /** J：requestBody 包装层；无 messages 字段时无副作用 */
    private static void caseRequestBodyWrapper() throws Exception {
        List<Map<String, Object>> msgs = new ArrayList<>();
        msgs.add(user("执行"));
        msgs.add(assistant("开始", "[{\"id\":\"c1\",\"function\":{\"name\":\"command\"}}]"));
        Map<String, Object> body = new HashMap<>();
        body.put("messages", msgs);
        body.put("stream", true);
        ToolCallSequenceSanitizer.sanitizeRequestBody(body);
        assertValid("J", msgs);

        // 没有 messages 的请求体不应抛异常
        Map<String, Object> noMessages = new HashMap<>();
        noMessages.put("prompt", "x");
        ToolCallSequenceSanitizer.sanitizeRequestBody(noMessages);
        ToolCallSequenceSanitizer.sanitizeRequestBody(null);
        passed++;
        System.out.println("  ✅ J: requestBody 包装层 / 无 messages / null 均安全");

        // J2：不可变列表（List.of）→ 用副本接口发送，得到修复后的合法序列，且原列表不被篡改
        List<Map<String, Object>> immutable = List.of(
                user("执行"),
                assistant("开始", "[{\"id\":\"c1\",\"function\":{\"name\":\"command\"}}]"));
        Map<String, Object> body2 = new HashMap<>();
        body2.put("messages", immutable);
        String originalSnapshot = MAPPER.writeValueAsString(immutable);

        Map<String, Object> fixedBody = ToolCallSequenceSanitizer.sanitizeRequestBodyCopy(body2);
        Object fixedObj = fixedBody.get("messages");
        assertTrue("J2: 副本 messages 已被替换为修复后的列表", fixedObj != immutable);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> fixed = (List<Map<String, Object>>) fixedObj;
        assertValid("J2", fixed);
        assertEquals("J2: 原不可变列表内容未被篡改（copy-on-write）",
                originalSnapshot, MAPPER.writeValueAsString(immutable));
    }

    // ==================== 严格校验器（模拟 OpenAI/DeepSeek 接口约束） ====================

    /**
     * 按接口约束校验序列：
     * 1) 每条 tool 消息必须匹配「紧邻其前、未被其它消息打断」的 assistant tool_calls，且同一个 id 只能出现一次；
     * 2) assistant 声明的每个 tool_call 必须都有对应的 tool 消息；
     * 3) assistant 必须至少包含 content 或 tool_calls 之一。
     */
    private static void assertValid(String caseName, List<Map<String, Object>> msgs) {
        Map<String, Boolean> currentIds = null;
        for (Map<String, Object> msg : msgs) {
            String role = String.valueOf(msg.get("role"));
            if ("tool".equals(role)) {
                String id = String.valueOf(msg.get("tool_call_id"));
                if (currentIds == null || !currentIds.containsKey(id)) {
                    fail(caseName + ": 出现孤立 tool 消息（id=" + id + "）");
                    return;
                }
                if (Boolean.TRUE.equals(currentIds.get(id))) {
                    fail(caseName + ": tool 结果重复（id=" + id + "）");
                    return;
                }
                currentIds.put(id, Boolean.TRUE);
                continue;
            }
            // 非 tool 消息 → 结算上一批
            if (currentIds != null) {
                for (Map.Entry<String, Boolean> e : currentIds.entrySet()) {
                    if (!Boolean.TRUE.equals(e.getValue())) {
                        fail(caseName + ": tool_call 缺少结果（id=" + e.getKey() + "）");
                        return;
                    }
                }
                currentIds = null;
            }
            if ("assistant".equals(role)) {
                Object content = msg.get("content");
                Object toolCalls = msg.get("tool_calls");
                boolean hasContent = content != null && !String.valueOf(content).trim().isEmpty()
                        && !"null".equalsIgnoreCase(String.valueOf(content));
                boolean hasToolCalls = toolCalls != null;
                if (!hasContent && !hasToolCalls) {
                    fail(caseName + ": 存在空 assistant 消息");
                    return;
                }
                if (hasToolCalls) {
                    List<String> ids = declaredToolCallIds(msg);
                    if (ids.isEmpty()) {
                        fail(caseName + ": assistant 的 tool_calls 为空数组");
                        return;
                    }
                    currentIds = new LinkedHashMap<>();
                    for (String id : ids) {
                        if (id.isEmpty() || "null".equalsIgnoreCase(id)) {
                            fail(caseName + ": tool_call id 非法");
                            return;
                        }
                        if (currentIds.put(id, Boolean.FALSE) != null) {
                            fail(caseName + ": tool_call id 重复（" + id + "）");
                            return;
                        }
                    }
                }
            }
        }
        if (currentIds != null) {
            for (Map.Entry<String, Boolean> e : currentIds.entrySet()) {
                if (!Boolean.TRUE.equals(e.getValue())) {
                    fail(caseName + ": 末尾 tool_call 缺少结果（id=" + e.getKey() + "）");
                    return;
                }
            }
        }
        passed++;
        System.out.println("  ✅ " + caseName + ": 序列合法（" + msgs.size() + " 条消息）");
    }

    // ==================== 构造与断言辅助 ====================

    private static Map<String, Object> user(String content) {
        return msg("user", content, null);
    }

    private static Map<String, Object> system(String content) {
        return msg("system", content, null);
    }

    private static Map<String, Object> assistant(String content, String toolCallsJson) {
        return msg("assistant", content, toolCallsJson);
    }

    private static Map<String, Object> tool(String toolCallId, String content) {
        Map<String, Object> m = new HashMap<>();
        m.put("role", "tool");
        m.put("tool_call_id", toolCallId);
        m.put("content", content);
        return m;
    }

    private static Map<String, Object> msg(String role, String content, String toolCallsJson) {
        Map<String, Object> m = new HashMap<>();
        m.put("role", role);
        if (content != null) {
            m.put("content", content);
        }
        if (toolCallsJson != null) {
            try {
                m.put("tool_calls", MAPPER.readTree(toolCallsJson));
            } catch (Exception e) {
                throw new IllegalStateException("构造测试数据失败: " + toolCallsJson, e);
            }
        }
        return m;
    }

    private static List<String> declaredToolCallIds(Map<String, Object> msg) {
        List<String> ids = new ArrayList<>();
        Object tc = msg.get("tool_calls");
        if (tc instanceof com.fasterxml.jackson.databind.JsonNode node && node.isArray()) {
            node.forEach(n -> ids.add(n.path("id").asText("")));
        }
        return ids;
    }

    private static void assertEquals(String name, Object expected, Object actual) {
        boolean ok = expected == null ? actual == null : expected.equals(actual);
        if (ok) {
            passed++;
            System.out.println("  ✅ " + name);
        } else {
            fail(name + "（期望=" + expected + "，实际=" + actual + "）");
        }
    }

    private static void assertTrue(String name, boolean condition) {
        if (condition) {
            passed++;
            System.out.println("  ✅ " + name);
        } else {
            fail(name);
        }
    }

    private static void fail(String message) {
        failed++;
        // [FAIL] 用 ASCII 标记，避免控制台编码问题导致 CI 日志里看不出失败项
        System.out.println("  [FAIL] " + message);
    }
}
