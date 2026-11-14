package com.example.agentdeepseek.util;

import com.example.agentdeepseek.config.ToolResultBudgetConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 工具结果体积预算：单条工具结果的<b>统一、幂等</b>截断器
 *
 * <h3>为什么必须有这个类（而不是各处各写一套阈值）</h3>
 * <p>
 * 历史实现散落三套互不相干的阈值：{@code ToolExecutor.MAX_TOOL_RESULT_CHARS=30000}、
 * {@code ContextBuilder.MAX_TOOL_CONTENT_CHARS=30000}、以及各工具内部的私有上限。
 * 同一份内容在不同链路上被不同规则裁剪，一旦规则不一致就会出现
 * 「落库是 A 形态、发给 LLM 是 B 形态」的错位；而只要某条历史消息的文本每轮都变，
 * 它之后的<b>全部前缀缓存都会失效</b>（DeepSeek 命中/未命中输入单价相差约 50 倍）。
 * </p>
 *
 * <h3>三条硬约束</h3>
 * <ol>
 *   <li><b>只在这里定义截断规则</b>：所有调用方都必须走 {@link #apply(String, String)}，
 *       禁止再新增私有阈值常量。</li>
 *   <li><b>必须幂等</b>：截断后的文本再截一次，返回值必须<b>完全相同</b>
 *       （靠 {@link #MARK} 标记检测实现）。否则重建历史时内容会持续漂移。</li>
 *   <li><b>必须在 append 时执行</b>：由 {@code ToolExecutor#buildToolMessages} 在写入
 *       DB / 发送 LLM 前调用一次，历史组装阶段只做幂等兜底（处理存量超预算数据）。</li>
 * </ol>
 *
 * <h3>截断形态</h3>
 * <pre>
 * {头部 headRatio}            ← 结构 / 摘要 / 第一屏
 * …[CC-TRUNC][工具 xxx 结果过长] 原始 N 字符，中间省略 M 字符。
 * 续读指引：……                  ← 告诉模型怎么取回被省略部分，避免它盲目重跑
 * {尾部 1-headRatio}          ← 错误信息 / 收尾结论
 * </pre>
 *
 * <p>为什么一定要带「续读指引」：被静默砍掉一半的结果会让模型以为拿到了全部，
 * 于是基于残缺信息下结论或反复重试。给出明确取回方式后，
 * 模型能一次拿到需要的那段，而不是把整个工具再调一遍（那才是真正的 token 黑洞）。</p>
 */
@Slf4j
@Component
public class ToolResultBudget {

    /**
     * 截断标记前缀（幂等判据）。
     * <p>选用不可能出现在正常工具输出里的独特串，避免「内容碰巧含标记导致跳过截断」。</p>
     */
    public static final String MARK = "…[CC-TRUNC]";

    /** 拼接用的换行（计入预算） */
    private static final String SEP = "\n\n";

    /** 标记预留字符数下限（首轮估算用，二轮会按实际长度重算） */
    private static final int MARK_RESERVE_HINT = 600;

    /** 头部至少保留字符数（防止预算被标记吃光） */
    private static final int MIN_HEAD = 200;

    private final ToolResultBudgetConfig config;

    public ToolResultBudget(ToolResultBudgetConfig config) {
        this.config = config;
    }

    /**
     * 按工具名裁剪单条工具结果（幂等）
     *
     * @param toolName 工具名（决定预算与续读指引文案；可为 null）
     * @param content  原始工具结果
     * @return 裁剪后的内容；未超预算、已截断过、或预算为负时原样返回
     */
    public String apply(String toolName, String content) {
        return apply(config, toolName, content);
    }

    /**
     * 静态实现（便于单测与无 Spring 环境复用）
     */
    public static String apply(ToolResultBudgetConfig cfg, String toolName, String content) {
        if (content == null || content.isEmpty()) return content;
        if (cfg == null || !cfg.isEnabled()) return content;

        int budget = cfg.resolveBudget(toolName);
        // budget <= 0：该工具不限（例如需要完整输出的专用工具）
        if (budget <= 0) return content;
        if (content.length() <= budget) return content;
        // ★ 幂等核心：已带截断标记的内容不再二次裁剪
        if (content.contains(MARK)) return content;

        double headRatio = cfg.resolvedHeadRatio();
        String guide = cfg.isIncludeGuide() ? buildGuide(toolName) : "";

        // 两轮：第一轮按预留长度估算，第二轮用真实标记长度精确凑到 <= budget
        String marker = null;
        int head = 0;
        int tail = 0;
        for (int pass = 0; pass < 2; pass++) {
            int reserve = (marker == null ? MARK_RESERVE_HINT : marker.length()) + SEP.length() * 2;
            int available = budget - reserve;
            if (available < MIN_HEAD) {
                // 预算小到放不下标记：退化为「只保留头部」，保证不超过预算
                head = Math.min(budget, content.length()) - MARK.length();
                if (head < MIN_HEAD) head = Math.min(MIN_HEAD, content.length());
                tail = 0;
                marker = MARK;
                break;
            }
            head = (int) Math.round(available * headRatio);
            if (head < MIN_HEAD) head = MIN_HEAD;
            tail = available - head;
            if (tail < 0) tail = 0;
            int omitted = content.length() - head - tail;
            marker = buildMarker(toolName, content.length(), omitted, guide);
        }

        StringBuilder sb = new StringBuilder(budget + 64);
        sb.append(content, 0, Math.min(head, content.length()));
        sb.append(SEP).append(marker).append(SEP);
        if (tail > 0) {
            sb.append(content, Math.max(0, content.length() - tail), content.length());
        }
        String result = sb.toString();

        // 极端情况下（标记异常膨胀）再兜一层：按预算硬切，仍带标记保证幂等
        if (result.length() > budget + 64) {
            result = result.substring(0, Math.max(MIN_HEAD, budget - MARK.length())) + MARK;
        }
        // INFO 级：截断是「上下文成本」的关键事件，需在日志里可盘点（每轮通常只有几条）
        log.info("工具结果超预算已裁剪: tool={}, {} → {} 字符（预算 {}，省略 {}）",
                toolName, content.length(), result.length(), budget,
                content.length() - Math.min(content.length(), head + tail));
        return result;
    }

    /** 截断标记正文（含原始/省略字符数，均取自原文，故同一份原文多次调用结果一致） */
    private static String buildMarker(String toolName, int original, int omitted, String guide) {
        StringBuilder sb = new StringBuilder(MARK);
        sb.append("[工具 ").append(toolName == null || toolName.isEmpty() ? "unknown" : toolName)
                .append(" 结果过长] 原始 ").append(original)
                .append(" 字符，中间省略 ").append(Math.max(0, omitted)).append(" 字符。");
        if (!guide.isEmpty()) {
            sb.append("\n").append(guide);
        }
        return sb.toString();
    }

    /**
     * 按工具类型给出「如何取回被省略部分」的指引
     * <p>写的是<b>该工具真实存在的参数</b>，不能凭空编造——错误的指引比没有指引更糟，
     * 模型会照着调用然后拿到第二个错误。</p>
     */
    private static String buildGuide(String toolName) {
        String name = toolName == null ? "" : toolName.toLowerCase();
        return switch (name) {
            case "file_explorer" ->
                    "续读指引：如确需被省略的部分，用 file_explorer(action=\"read\", path=\"<同一文件>\", "
                            + "start_line=N, end_line=M) 分段读取；或先用 action=\"grep\" 精确定位行号再只读该段。";
            case "command" ->
                    "续读指引：如确需完整输出，用 command(action=\"logs\", service_id=<任务ID>, tail=0) 回看；"
                            + "重跑命令时建议加过滤减少噪声（如 `| findstr 关键字` / `| Select-String`）。";
            case "web_fetch" ->
                    "续读指引：如确需被省略的段落，用 web_search 先定位到具体章节，"
                            + "或改用 http_request 分段获取；不要重复抓取同一 URL 期望拿到不同结果。";
            case "web_search" ->
                    "续读指引：如结果不足，请细化关键词、加 site: 限定，或减小 count 参数重新搜索。";
            case "execute_sql" ->
                    "续读指引：如确需完整结果，请加 WHERE 条件或 LIMIT 缩小结果集，"
                            + "或改用 COUNT/MAX/SUM 等聚合函数只取需要的统计值。";
            case "http_request" ->
                    "续读指引：如确需完整响应，请用接口自带的分页参数（page/per_page/offset 等）分段获取，"
                            + "或只取需要的字段。";
            case "browser" ->
                    "续读指引：如确需完整页面内容，请用更精确的 selector 只取目标区域，"
                            + "或先用 action=\"snapshot\" 定位到具体元素再操作。";
            case "agent_invoke" ->
                    "续读指引：如确需子 Agent 的完整产出，请让它「只输出关键结论 + 文件清单」，"
                            + "或改用 agent action=\"poll\" 的 scope=final 只取最终答复。";
            default ->
                    "续读指引：如确需被省略的内容，请缩小查询范围或分页获取；不要用相同参数重复调用同一工具。";
        };
    }

    /**
     * 判断某段内容是否已被本组件裁剪过（供调用方判断「是否需要再处理」）
     */
    public static boolean isTruncated(String content) {
        return content != null && content.contains(MARK);
    }
}
