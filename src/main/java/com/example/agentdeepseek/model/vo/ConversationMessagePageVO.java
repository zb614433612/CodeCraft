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

    public ConversationMessagePageVO() {
    }

    public ConversationMessagePageVO(List<ConversationMessage> messages, boolean hasMore, Long nextBeforeId, int rounds) {
        this.messages = messages;
        this.hasMore = hasMore;
        this.nextBeforeId = nextBeforeId;
        this.rounds = rounds;
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
