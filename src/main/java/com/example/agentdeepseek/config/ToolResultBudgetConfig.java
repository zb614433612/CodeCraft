package com.example.agentdeepseek.config;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 工具结果体积预算配置（控制「单条工具结果进入上下文的字符上限」）
 * <p>
 * <b>为什么需要按工具分级，而不是一个统一上限</b>：
 * 工具结果的「信息密度」差异极大——
 * {@code command} 的编译日志 90% 是进度噪声，{@code file_explorer} 读出来的是真实代码。
 * 用同一个 30000 字符的上限，等于「把日志和代码按同一标准裁剪」：
 * 日志裁得太松（白烧 token），代码裁得太狠（模型读不全 → 重复读 → 轮次暴涨）。
 * </p>
 *
 * <h3>⚠️ 截断发生在「写入前」，不在「组装历史时」</h3>
 * <p>
 * {@link com.example.agentdeepseek.util.ToolResultBudget} 由
 * {@code ToolExecutor#buildToolMessages} 调用，也就是「落库与发送 LLM 的同一份内容」。
 * 若改在组装历史时回溯改写，每轮请求的截断位置会随消息列表长度变化而漂移，
 * 导致<b>前缀缓存全量失效</b>（DeepSeek 缓存命中/未命中单价相差 50 倍）。
 * </p>
 *
 * <p>配置示例（application.yml）：
 * <pre>
 * tool:
 *   result-budget:
 *     enabled: true
 *     default-chars: 12000      # 未列出的工具用这个
 *     head-ratio: 0.7           # 截断时头部占比（0.2~0.9），其余给尾部
 *     include-guide: true       # 是否附带「如何取回被省略部分」的指引
 *     tools:
 *       file_explorer: 16000    # 读代码刚需，给宽一点（&lt;0 表示该工具不限）
 *       command: 10000
 *       web_fetch: 16000
 *       web_search: 10000
 *       execute_sql: 10000
 *       http_request: 10000
 *       browser: 16000
 *       agent_invoke: 12000
 * </pre>
 * </p>
 */
@Slf4j
@Data
@Configuration
@ConfigurationProperties(prefix = "tool.result-budget")
public class ToolResultBudgetConfig {

    /** 总开关：关闭后所有工具结果原样进入上下文（应急回退用） */
    private boolean enabled = true;

    /** 未在 {@link #tools} 中列出的工具使用的默认字符预算 */
    private int defaultChars = 12000;

    /**
     * 截断时头部保留占比（0.2~0.9，其余给尾部）。
     * <p>头部通常是「结构 / 摘要 / 第一屏」，尾部通常是「错误信息 / 收尾结论」，
     * 中间是最容易被省略的部分，故默认 0.7 / 0.3。</p>
     */
    private double headRatio = 0.7;

    /** 是否在截断标记后附带「如何取回被省略部分」的指引（强烈建议开启，否则模型倾向重复调用） */
    private boolean includeGuide = true;

    /** 按工具的字符预算表；值 {@code <= 0} 表示该工具不限制（如需要完整输出的专用工具） */
    private Map<String, Integer> tools = new LinkedHashMap<>();

    /**
     * 解析某工具的单条结果预算
     *
     * @param toolName 工具名（大小写不敏感；null / 空按默认值）
     * @return 字符预算；{@code <= 0} 表示不限制
     */
    public int resolveBudget(String toolName) {
        if (toolName != null && !toolName.isEmpty()) {
            Integer exact = tools.get(toolName);
            if (exact != null) {
                return exact;
            }
            // 容错：配置里写了大写或带空格，退化为大小写不敏感匹配
            for (Map.Entry<String, Integer> entry : tools.entrySet()) {
                if (entry.getKey() != null && entry.getKey().equalsIgnoreCase(toolName)) {
                    return entry.getValue();
                }
            }
        }
        return defaultChars;
    }

    /** 头部保留比例（夹紧到 0.2~0.9，防止配错导致只留标记） */
    public double resolvedHeadRatio() {
        if (headRatio < 0.2) return 0.2;
        if (headRatio > 0.9) return 0.9;
        return headRatio;
    }
}
