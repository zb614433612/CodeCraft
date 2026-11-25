package com.example.agentdeepseek.model.vo;

import com.example.agentdeepseek.model.entity.ConversationMessage;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * 会话消息分页结果（按「轮次」游标分页，从最新往前翻）。
 *
 * <p>分页设计（2026-10-17 聊天记录懒加载改造）：
 * <ul>
 *   <li>一轮 = 一次用户提问（role=USER，且非 desktop_inject 注入消息）+ 其后该轮的全部回复（assistant/tool）；</li>
 *   <li>首页不带 {@code beforeId}，返回最近 {@code rounds} 轮；</li>
 *   <li>向上翻页时把上一页返回的 {@code nextBeforeId} 作为 {@code beforeId} 传入；</li>
 *   <li>{@code messages} 始终按 id 升序（与前端 processMessageGroups 的时间正序要求一致）。</li>
 * </ul>
 * 该结构用于避免超长会话一次性全量查询导致的卡顿（DB 查询 + 网络传输 + 前端分组渲染全部变重）。
 */
@Schema(description = "会话消息分页结果（按轮次游标分页，从最新往前翻）")
public class ConversationMessagePageVO {

    @Schema(description = "本页消息列表（按 id 升序）")
    private List<ConversationMessage> messages;

    @Schema(description = "是否还有更早的历史消息（true 时前端可在滚动到顶部继续加载）")
    private boolean hasMore;

    @Schema(description = "下一页游标：本页最早一条消息的 id，继续往前翻时作为 beforeId 传入；无消息时为 null")
    private Long nextBeforeId;

    @Schema(description = "本次返回覆盖的轮数（实际被截取的用户消息轮数，上限为请求的 rounds）")
    private int rounds;

    /*
     ═══════════════════════════════════════════════════════════════════════════
      上下文压缩边界（2026-11-17 新增）

      【为什么前端需要它】
      上下文压缩后，后端重建会话上下文时只使用「system + 压缩摘要 + 摘要之后的消息」，
      被压缩的历史**不再计入上下文**（但原文仍完整保留在 conversation_message 表中，
      用户向上翻页照样能看到历史聊天记录）。
      前端「输入区下方『上下文 xx』」是另一套独立的前端字符估算，若仍把被压缩的历史算进去，
      就会与后端真实上下文严重不符（长会话可能虚高几倍），用户看到的是错误的数据。

      【口径约定】
      · {@code compactedUpToMessageId} 为「已折叠进摘要的最后一条消息 id」：
        id ≤ 该值的消息不参与上下文计算，由 {@code compactedSummaryTokens} 统一代表。
      · 未发生任何压缩时为 null，前端行为与改动前完全一致（向后兼容）。
      ═══════════════════════════════════════════════════════════════════════════
     */
    @Schema(description = "已压缩到的消息 id（含）：id≤该值的消息不再计入上下文；null 表示未发生压缩")
    private Long compactedUpToMessageId;

    @Schema(description = "被压缩区间折叠成摘要后的估算 token 数（多条摘要累加），用于前端上下文口径修正")
    private int compactedSummaryTokens;

    /**
     * 当前上下文的「真实」token 数（后端口径，可直接与模型窗口上限比较）。
     *
     * <p><b>为什么前端需要它（2026-11-25）</b>：输入区下方的「上下文 xx」原本是纯前端字符估算，
     * 且只统计「已分页加载的最近 N 轮」—— 长会话会严重偏小：实测某会话后端真实请求体
     * 2,891,302 字符 ≈ 1,229,069 token（已撞 1,048,576 窗口上限并报 400），
     * 而前端只显示 5.6 万，用户完全看不出危险。</p>
     *
     * <p>口径见 {@link #contextTokensSource}：</p>
     * <ul>
     *   <li>{@code real}：最近一次请求的 API 真实 {@code usage.prompt_tokens}（含 tools 定义，最权威）；</li>
     *   <li>{@code estimate}：压缩刚完成、尚未发生新请求时的即时估算（摘要 + 水位线之后的消息，
     *       字符口径 × 0.425）。下一次请求落库后自动切换为 {@code real}。</li>
     * </ul>
     *
     * <p>为 0 或来源为空表示后端未提供（旧版本），前端应回退到自身的字符估算，保持向后兼容。</p>
     */
    private int contextTokens;

    /** contextTokens 的来源标记：{@code mode} = 按当前上下文模式现算；{@code real} / {@code estimate} 为旧值 */
    private String contextTokensSource;

    /**
     * 最近一次<b>真实</b>请求的 {@code usage.prompt_tokens}（含 tools 定义）。
     *
     * <p>用途：作为 {@link #contextTokens} 的对照值 —— 前者是「按当前模式算的」，
     * 本字段是「上一次实际发出去多少」。两者在模式切换后必然不一致（这正是预期行为），
     * 前端放在 tooltip 里让用户能对照，而不是混成一个数。</p>
     *
     * <p>为 0 表示该会话从未发起过请求。</p>
     */
    private int lastPromptTokens;

    /**
     * 本次 {@link #contextTokens} 计算所用的上下文模式（{@code full} / {@code balanced} / {@code compact}）。
     *
     * <p>前端据此校验「显示的数字确实对应当前选中的模式」，也可在模式切换后立刻判断是否需要重拉。</p>
     */
    private String contextMode;

    public ConversationMessagePageVO() {
    }

    public ConversationMessagePageVO(List<ConversationMessage> messages, boolean hasMore, Long nextBeforeId, int rounds) {
        this.messages = messages;
        this.hasMore = hasMore;
        this.nextBeforeId = nextBeforeId;
        this.rounds = rounds;
    }

    public Long getCompactedUpToMessageId() {
        return compactedUpToMessageId;
    }

    public void setCompactedUpToMessageId(Long compactedUpToMessageId) {
        this.compactedUpToMessageId = compactedUpToMessageId;
    }

    public int getCompactedSummaryTokens() {
        return compactedSummaryTokens;
    }

    public int getContextTokens() {
        return contextTokens;
    }

    public void setContextTokens(int contextTokens) {
        this.contextTokens = contextTokens;
    }

    public String getContextTokensSource() {
        return contextTokensSource;
    }

    public void setContextTokensSource(String contextTokensSource) {
        this.contextTokensSource = contextTokensSource;
    }

    public int getLastPromptTokens() {
        return lastPromptTokens;
    }

    public void setLastPromptTokens(int lastPromptTokens) {
        this.lastPromptTokens = lastPromptTokens;
    }

    public String getContextMode() {
        return contextMode;
    }

    public void setContextMode(String contextMode) {
        this.contextMode = contextMode;
    }

    public void setCompactedSummaryTokens(int compactedSummaryTokens) {
        this.compactedSummaryTokens = compactedSummaryTokens;
    }

    public List<ConversationMessage> getMessages() {
        return messages;
    }

    public void setMessages(List<ConversationMessage> messages) {
        this.messages = messages;
    }

    public boolean isHasMore() {
        return hasMore;
    }

    public void setHasMore(boolean hasMore) {
        this.hasMore = hasMore;
    }

    public Long getNextBeforeId() {
        return nextBeforeId;
    }

    public void setNextBeforeId(Long nextBeforeId) {
        this.nextBeforeId = nextBeforeId;
    }

    public int getRounds() {
        return rounds;
    }

    public void setRounds(int rounds) {
        this.rounds = rounds;
    }
}
