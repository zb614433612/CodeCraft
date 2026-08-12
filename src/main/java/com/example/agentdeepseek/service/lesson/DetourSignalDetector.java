package com.example.agentdeepseek.service.lesson;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * P1 弯路通道（C3）· 弯路信号检测器
 * <p>
 * 纯字符串匹配（同步、微秒级、零 LLM 成本），扫描一轮对话的消息流：
 * - 信号 A：用户否定/纠正（user 消息命中词表；权限指令语境排除，如「不要删除文件」）
 * - 信号 B：LLM 自述换方案（assistant 消息；强/中两级，强信号可单独触发）
 * - 信号 C：工具序列模式（file_writer 同文件重写 / command 命令族切换；仅辅助证据）
 * <p>
 * 触发判定（设计矩阵）：
 * | B 强（单独）            | ✅ 直接触发   |
 * | A +（B 中 或 C）        | ✅ 触发       |
 * | A 单独                  | ⚠️ 触发（LLM worthRecord 二次过滤） |
 * | B 中 单独 / C 单独      | ❌ 不触发（避免把正常重构误判为弯路） |
 * <p>
 * 配置来源：application.yml {@code lesson.detour-signals.*}（词表可配置，默认内置）。
 */
@Slf4j
@Data
@Component
@ConfigurationProperties(prefix = "lesson.detour-signals")
public class DetourSignalDetector {

    // ============================================================
    // 信号 A：用户否定/纠正词表（默认值，yml 可覆盖）
    // ============================================================

    /** 方案否定 */
    private List<String> signalA = new ArrayList<>(List.of(
            "不对", "不行", "不是这样", "不是这个意思", "方向不对", "思路不对", "这样不行",
            "搞错了", "理解错了", "理解错"));

    /** 要求更换 */
    private List<String> signalAChange = new ArrayList<>(List.of(
            "换一种", "换方案", "换个方式", "别用", "不要这样", "不能用这个", "改一下方案",
            "重新做", "重来", "推倒重来", "推翻", "白做了", "白费", "绕远路", "走了弯路"));

    /** 结果否定 */
    private List<String> signalAResult = new ArrayList<>(List.of(
            "实现不了", "达不到", "做不到", "没法实现", "不满足需求", "不符合要求", "不是我要的"));

    // ============================================================
    // 信号 B：LLM 自述换方案（正则；强/中两级）
    // ============================================================

    /** 强信号：单独命中即可触发 */
    private List<String> signalBStrong = new ArrayList<>(List.of(
            "改用", "换成", "换用", "换一种", "换了个方案", "绕了远路", "走了弯路"));

    /** 中信号：需配合信号 A 或 C */
    private List<String> signalBMedium = new ArrayList<>(List.of(
            "重新实现", "推翻重来", "重写", "换了个思路"));

    /** 中信号正则（独立编译） */
    private static final List<Pattern> SIGNAL_B_REGEX = List.of(
            Pattern.compile("试了.{0,12}才成功"),
            Pattern.compile("多次尝试后"),
            Pattern.compile("方案.{0,12}(不行|不可行|无法|失败|放弃|走不通|不适用|不合适)"),
            Pattern.compile("最终(采用|选择|用)(方案|方式)?.{0,6}(二|B|2)"));

    // ============================================================
    // 权限指令排除（信号 A 误报控制）
    // ============================================================

    /** 权限指令特征：否定词 + 对象词 → 不算方案否定 */
    private static final List<Pattern> PERMISSION_CONTEXT_PATTERNS = List.of(
            Pattern.compile("(不要|别|别动|不要动|别去|不要删).{0,10}(删除|修改|动|改|文件|配置|目录|文件夹|表|数据)"),
            Pattern.compile("(不要|别|禁止).{0,6}(删除|修改|覆盖|清空|格式化)"));

    // ============================================================
    // 信号 C：工具序列模式（辅助证据，不单独触发）
    // ============================================================

    /** 命令族前缀（用于检测命令族切换） */
    private static final List<String> COMMAND_FAMILIES = List.of("mvn", "npm", "npx", "git", "curl", "node", "pip", "gradle");

    /** 信号检测结果 */
    @Data
    public static class DetourSignal {
        /** 用户否定/纠正信号（含权限指令排除后的结果） */
        private boolean signalA;
        /** LLM 强信号（可单独触发） */
        private boolean signalBStrong;
        /** LLM 中信号（需配合 A/C） */
        private boolean signalBMedium;
        /** 工具序列辅助信号 */
        private boolean signalC;
        /** 命中的用户消息原文（截断 300，供 LLM 判定上下文） */
        private String userText;
        /** 命中的 assistant 消息原文（截断 300，供 LLM 判定上下文） */
        private String assistantText;
        /** 信号 C 事实描述（如「file_writer 重写 3 次 / 命令族切换 mvn→npm」） */
        private String toolFact;

        /** 触发判定（设计矩阵） */
        public boolean shouldTrigger() {
            if (signalBStrong) {
                return true;
            }
            if (signalA && (signalBMedium || signalC)) {
                return true;
            }
            return signalA; // A 单独 ⚠️ 触发（LLM worthRecord 二次过滤）
        }
    }

    // ============================================================
    // 对外入口：扫描一轮消息
    // ============================================================

    /**
     * 扫描一轮对话消息，检测弯路信号。
     * 消息格式与 LessonReviewService.extractFailedToolCalls 同源：
     * role=user/assistant/tool；assistant 消息含 tool_calls；tool 消息含 tool_name。
     *
     * @param messages 本轮完整消息列表
     * @return 信号检测结果（无信号时 shouldTrigger()=false，调用方零成本跳过）
     */
    public DetourSignal scan(List<Map<String, Object>> messages) {
        DetourSignal signal = new DetourSignal();
        if (messages == null || messages.isEmpty()) {
            return signal;
        }
        for (Map<String, Object> msg : messages) {
            String role = String.valueOf(msg.get("role"));
            Object contentObj = msg.get("content");
            String content = contentObj == null ? "" : String.valueOf(contentObj);
            if (content.isBlank()) {
                continue;
            }
            if ("user".equals(role)) {
                // 信号 A：用户否定/纠正（权限指令语境排除）
                if (!signal.isSignalA() && matchSignalA(content)) {
                    signal.setSignalA(true);
                    signal.setUserText(truncate(content, 300));
                }
            } else if ("assistant".equals(role)) {
                // 信号 B：LLM 自述换方案（强/中）
                if (matchSignalBStrong(content)) {
                    signal.setSignalBStrong(true);
                    signal.setAssistantText(truncate(content, 300));
                } else if (matchSignalBMedium(content)) {
                    signal.setSignalBMedium(true);
                    if (signal.getAssistantText() == null) {
                        signal.setAssistantText(truncate(content, 300));
                    }
                }
            }
        }
        // 信号 C：工具序列扫描（同文件重写 / 命令族切换；从 assistant tool_calls 提取，M1 修复）
        signal.setToolFact(scanToolSequence(messages));
        signal.setSignalC(signal.getToolFact() != null);
        return signal;
    }

    // ============================================================
    // 信号 A 匹配
    // ============================================================

    private boolean matchSignalA(String text) {
        // 权限指令语境优先排除（「不要删除文件」不是方案否定）
        if (isPermissionInstruction(text)) {
            return false;
        }
        return containsAny(text, signalA) || containsAny(text, signalAChange) || containsAny(text, signalAResult);
    }

    private boolean isPermissionInstruction(String text) {
        for (Pattern p : PERMISSION_CONTEXT_PATTERNS) {
            if (p.matcher(text).find()) {
                return true;
            }
        }
        return false;
    }

    // ============================================================
    // 信号 B 匹配
    // ============================================================

    private boolean matchSignalBStrong(String text) {
        if (containsAny(text, signalBStrong)) {
            return true;
        }
        for (Pattern p : SIGNAL_B_REGEX) {
            if (p.matcher(text).find()) {
                return true;
            }
        }
        return false;
    }

    private boolean matchSignalBMedium(String text) {
        return containsAny(text, signalBMedium);
    }

    // ============================================================
    // 信号 C：工具序列扫描（辅助证据）
    // ============================================================

    /**
     * 扫描工具调用序列（M1 修复：从 assistant 消息的 tool_calls 提取——
     * 消息流中 tool 消息只有 role/tool_call_id/content，无 tool_name/arguments；
     * tool_calls 数组含 function.name 与 function.arguments，是权威来源）：
     * - file_writer 对同一 file_path 的 write/edit ≥2 次 → 文件重写事实
     * - command 命令前缀族 ≥2 类 → 命令族切换事实
     * 返回事实描述；无事实返回 null（不触发信号 C）。
     */
    private String scanToolSequence(List<Map<String, Object>> messages) {
        Map<String, Integer> fileWrites = new java.util.HashMap<>();
        Set<String> commandFamilies = new HashSet<>();
        for (Map<String, Object> msg : messages) {
            if (!"assistant".equals(String.valueOf(msg.get("role")))) {
                continue;
            }
            Object tcObj = msg.get("tool_calls");
            if (!(tcObj instanceof List<?> tcList)) {
                continue;
            }
            for (Object item : tcList) {
                if (!(item instanceof Map<?, ?> tc)) {
                    continue;
                }
                Object fn = tc.get("function");
                if (!(fn instanceof Map<?, ?> fnMap)) {
                    continue;
                }
                String toolName = String.valueOf(fnMap.get("name"));
                String args = fnMap.get("arguments") == null ? "" : String.valueOf(fnMap.get("arguments"));
                if ("file_writer".equals(toolName) && args.contains("file_path")) {
                    String path = extractParam(args, "file_path");
                    if (path != null) {
                        fileWrites.merge(path, 1, Integer::sum);
                    }
                } else if ("command".equals(toolName)) {
                    String cmd = extractParam(args, "command");
                    if (cmd != null) {
                        for (String family : COMMAND_FAMILIES) {
                            if (cmd.startsWith(family)) {
                                commandFamilies.add(family);
                                break;
                            }
                        }
                    }
                }
            }
        }
        // 组装事实
        List<String> facts = new ArrayList<>();
        fileWrites.forEach((path, count) -> {
            if (count >= 2) {
                facts.add("file_writer 重写 " + path + " " + count + " 次");
            }
        });
        if (commandFamilies.size() >= 2) {
            facts.add("命令族切换: " + String.join("→", commandFamilies));
        }
        return facts.isEmpty() ? null : String.join("; ", facts);
    }

    /** 简易提取 JSON 参数值（工具参数中 name 的字符串值） */
    private String extractParam(String argsJson, String name) {
        int idx = argsJson.indexOf("\"" + name + "\"");
        if (idx < 0) {
            return null;
        }
        int colon = argsJson.indexOf(':', idx);
        if (colon < 0) {
            return null;
        }
        String rest = argsJson.substring(colon + 1).trim();
        if (rest.startsWith("\"")) {
            int end = rest.indexOf('"', 1);
            return end > 1 ? rest.substring(1, end) : null;
        }
        int end = rest.indexOf(',');
        if (end < 0) {
            end = rest.indexOf('}');
        }
        return end > 0 ? rest.substring(0, end).trim() : rest;
    }

    // ============================================================
    // 工具方法
    // ============================================================

    private boolean containsAny(String text, List<String> keywords) {
        for (String kw : keywords) {
            if (kw != null && !kw.isBlank() && text.contains(kw)) {
                return true;
            }
        }
        return false;
    }

    private static String truncate(String s, int maxLen) {
        if (s == null) {
            return "";
        }
        return s.length() <= maxLen ? s : s.substring(0, maxLen);
    }
}
